package cc.pulsez.app.ui.threads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.pulsez.app.ui.group.GroupRules
import cc.pulsez.app.ui.theme.PulsezTheme

/**
 * 首页（团宠化）："不是列表，是『你的 TA 们』"。
 *
 * - 标题下一行 `caption`：
 *   无角色不显示；有角色 0 记忆 →「TA 还没记住什么。聊上几句，就会慢慢记住你。」；
 *   有记忆 → 单角色「TA 记得你们聊过的事」/ 多角色「N 个 TA 记得你们聊过的事」
 *   （N = 有记忆条目的角色数，沿用现口径，不改数据层）
 * - 角色卡两列网格：圆形头像 56dp、名字 1 行、最后一句 1 行、`caption`「TA 记得 N 件事」
 * - 首页最多展示六张角色卡；所有数量都可从角色目录入口进入，超过六个显示「全部角色（N）」
 * - 角色网格下方「最近一起聊」：历史群聊横滑卡片，点进去回到那个群；
 *   没有群聊时整块不显示，不占位
 * - 底部两个入口：「添加角色」（虚线卡片）、「一起聊」（描边次按钮，≥2 个角色才可点）
 * - 全部角色页可用显式入口或长按进入多选；删除有逐项确认
 */
data class ThreadCardUi(
    val id: String,
    val name: String,
    /** 当前 open 段最后一条消息；null/空 = 还没有新聊的内容（导入的往事不算）。 */
    val lastText: String?,
    val memoryCount: Int,
    /** 是否从旧聊天导入的 TA（`character.sourceType == "DEEPSEEK"`）；用于显示「有旧聊天记录」。 */
    val imported: Boolean = false,
    /** Explicit event time from successful live user messages; null for legacy/import-only roles. */
    val lastSingleChatActivityAt: Long? = null,
)

/**
 * 首页「最近一起聊」的一条群卡片。
 *
 * @param threadId 群聊 thread id，点击后 push `Route.GroupChat(threadId)`
 * @param title 群名：用 `thread.title`；没设过就用成员名拼（如「阿淮、小鹿、老周」）
 * @param lastText 最后一条消息正文；空串 = 这个群还没聊过
 * @param memberInitials 成员头像首字（≤4，与角色卡头像同一套视觉）
 */
data class GroupThreadCardUi(
    val threadId: String,
    val title: String,
    val lastText: String,
    val memberInitials: List<String>,
)

/**
 * 首页文案。
 *
 * 顶部从「被记得的数量」改成状态描述：主语由「你」换成「你们聊过的事」，
 * 不再把「是否被 AI 记得」做成情感指标。
 * 收口成常量的原因同 `CreativeModeCopy`：纯 JVM 可逐字断言（`WarmCopyTest`）。
 */
internal object HomeCopy {

    /** 有角色、但还没有任何记忆：半句暖话 + 可执行下一步。*/
    const val CAPTION_NO_MEMORY: String = "TA 还没记住什么。聊上几句，就会慢慢记住你。"

    /** 有记忆（有记忆的角色数 == 1）：说「你们聊过的事」，不说「记得你」。 */
    const val CAPTION_ONE: String = "TA 记得你们聊过的事"

    /** 有记忆（有记忆的角色数 > 1）。 */
    fun captionMany(count: Int): String = "$count 个 TA 记得你们聊过的事"

    /** 空态标题（原「这里还没有人。」：App 已声明没有真人，这里说「TA」，正文保留）。 */
    const val EMPTY_TITLE: String = "还没有 TA。"

    /** 角色卡 0 记忆（原「TA 还在认识你」：不承诺关系进度，`）。*/
    const val CARD_NO_MEMORY: String = "还没记住什么"

    /**
     * 首页顶部副标题；`null` = 什么都不显示（新装、还没有角色时只留 PulseZ + 设置图标）。
     *
     * @param hasCharacters 是否有未删除角色，现成状态，不新增数据查询
     * @param memoryCharacterCount 有记忆条目的角色数（`ThreadsViewModel` 现口径）
     */
    fun caption(hasCharacters: Boolean, memoryCharacterCount: Int): String? = when {
        !hasCharacters -> null
        memoryCharacterCount <= 0 -> CAPTION_NO_MEMORY
        memoryCharacterCount == 1 -> CAPTION_ONE
        else -> captionMany(memoryCharacterCount)
    }
}

@Composable
fun ThreadsScreen(
    cards: List<ThreadCardUi>,
    memoryCharacterCount: Int,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onCreateGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit = {},
    groups: List<GroupThreadCardUi> = emptyList(),
    onOpenGroup: (String) -> Unit = {},
    totalCharacterCount: Int = cards.size,
    onOpenAllCharacters: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onRefresh() }
    val homeCards = cards.take(HOME_CHARACTER_LIMIT)
    val visibleCharacterCount = maxOf(totalCharacterCount, cards.size)

    Column(modifier = Modifier.fillMaxSize()) {
        HomeHeader(
            hasCharacters = visibleCharacterCount > 0,
            memoryCharacterCount = memoryCharacterCount,
            onOpenSettings = onOpenSettings,
        )

        if (homeCards.isEmpty()) {
            EmptyState(
                onAdd = onAdd,
                characterCount = visibleCharacterCount,
                onOpenDirectory = onOpenAllCharacters,
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(homeCards, key = { it.id }) { card ->
                CharacterCard(card = card, onClick = { onOpen(card.id) })
            }
        }

        CharacterDirectoryEntryButton(
            characterCount = visibleCharacterCount,
            onOpen = onOpenAllCharacters,
        )

        // 历史群聊：没有群时整块不渲染
        if (groups.isNotEmpty()) {
            RecentGroupsSection(groups = groups, onOpen = onOpenGroup)
        }

        val groupEnabled = visibleCharacterCount >= GroupRules.MIN_MEMBERS
        HomeBottomEntries(
            groupEnabled = groupEnabled,
            onAdd = onAdd,
            onCreateGroup = onCreateGroup,
        )
    }
}

internal fun homeCharacterDirectoryEntryLabel(characterCount: Int): String =
    if (characterCount > HOME_CHARACTER_LIMIT) "全部角色（$characterCount）" else "管理角色"

internal data class HomeCharacterDirectoryEntry(val label: String, val onOpen: () -> Unit)

internal fun homeCharacterDirectoryEntry(
    characterCount: Int,
    onOpen: () -> Unit,
): HomeCharacterDirectoryEntry = HomeCharacterDirectoryEntry(homeCharacterDirectoryEntryLabel(characterCount), onOpen)

@Composable
private fun CharacterDirectoryEntryButton(characterCount: Int, onOpen: () -> Unit) {
    val entry = homeCharacterDirectoryEntry(characterCount, onOpen)
    OutlinedButton(
        onClick = entry.onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(entry.label)
    }
}

/** Full local role directory with explicit selection, long-press multi-select, and a confirmable delete. */
@Composable
fun AllCharactersScreen(
    items: List<CharacterDirectoryItem>,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    query: String = "",
    sort: CharacterDirectorySort = CharacterDirectorySort.RECENT_CHAT,
    onQueryChange: (String) -> Unit = {},
    onSortChange: (CharacterDirectorySort) -> Unit = {},
    onDelete: (Collection<String>) -> Unit = {},
    deletionNotice: CharacterDeletionNotice? = null,
    deleting: Boolean = false,
    onDismissDeletionNotice: () -> Unit = {},
) {
    var interaction by remember { mutableStateOf(CharacterDirectoryInteractionState()) }
    var olderManuallyExpanded by rememberSaveable { mutableStateOf(false) }
    val selection = interaction.selection
    val visibleItems = filterAndSortCharacterItems(items, query, sort)
    val cutoffMillis = remember(items, query, selection.active) { sevenDaysCutoffMillis() }
    val partition = partitionDirectoryItems(
        sortedItems = visibleItems,
        cutoffMillis = cutoffMillis,
        query = query,
        selectionActive = selection.active,
    )
    val olderExpanded = partition.autoExpandedOlder || olderManuallyExpanded
    val leaveSelection = { interaction = interaction.back() }
    val handleBack = { if (selection.active) leaveSelection() else onBack() }
    // Registered inside this route so system back exits selection before the navigation back stack.
    // Precedence follows AndroidX BackHandler's documented last-composed enabled handler rule.
    // 依据：https://developer.android.com/reference/kotlin/androidx/activity/compose/BackHandler.composable
    BackHandler(enabled = selection.active) { leaveSelection() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = handleBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (selection.active) "取消选择" else "返回",
                )
            }
            Text(
                text = "全部角色（${items.size}）",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (selection.active) {
                Text("已选 ${selection.selectedIds.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedButton(onClick = { interaction = interaction.enterSelection() }, modifier = Modifier.height(48.dp)) {
                    Text("选择")
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            singleLine = true,
            label = { Text("搜索角色") },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = sort == CharacterDirectorySort.RECENT_CHAT,
                onClick = { onSortChange(CharacterDirectorySort.RECENT_CHAT) },
                label = { Text("最近聊过") },
                modifier = Modifier.height(48.dp),
            )
            FilterChip(
                selected = sort == CharacterDirectorySort.NAME,
                onClick = { onSortChange(CharacterDirectorySort.NAME) },
                label = { Text("名称") },
                modifier = Modifier.height(48.dp),
            )
        }
        if (selection.active) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { interaction = interaction.selectAll(visibleItems.map { it.id }) },
                    modifier = Modifier.height(48.dp),
                ) { Text("全选") }
                OutlinedButton(
                    onClick = { interaction = interaction.clearSelection() },
                    modifier = Modifier.height(48.dp),
                ) { Text("清空") }
                OutlinedButton(
                    onClick = { interaction = interaction.requestDelete() },
                    enabled = selection.selectedIds.isNotEmpty() && !deleting,
                    modifier = Modifier.height(48.dp),
                ) { Text("删除（${selection.selectedIds.size}）") }
            }
        }
        if (visibleItems.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (items.isEmpty()) "还没有角色。" else "没找到这个名字。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (partition.recentItems.isNotEmpty() && partition.olderItems.isNotEmpty()) {
                    item(key = "section-recent", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "近 7 天活跃（${partition.recentItems.size}）",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                }
                items(partition.recentItems, key = { it.id }) { item ->
                    DirectoryCharacterCard(
                        item = item,
                        selected = item.id in selection.selectedIds,
                        onClick = {
                            if (selection.active) interaction = interaction.tap(item.id) else onOpen(item.id)
                        },
                        onLongClick = {
                            interaction = interaction.longPress(item.id)
                        },
                    )
                }
                if (partition.olderItems.isNotEmpty()) {
                    item(key = "section-older-toggle", span = { GridItemSpan(maxLineSpan) }) {
                        OlderCharactersFoldHeader(
                            count = partition.olderItems.size,
                            expanded = olderExpanded,
                            lockedOpen = partition.autoExpandedOlder,
                            recentEmpty = partition.recentItems.isEmpty(),
                            onToggle = { olderManuallyExpanded = !olderManuallyExpanded },
                        )
                    }
                    if (olderExpanded) {
                        items(partition.olderItems, key = { it.id }) { item ->
                            DirectoryCharacterCard(
                                item = item,
                                selected = item.id in selection.selectedIds,
                                onClick = {
                                    if (selection.active) interaction = interaction.tap(item.id) else onOpen(item.id)
                                },
                                onLongClick = {
                                    interaction = interaction.longPress(item.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (interaction.confirmationVisible) {
        AlertDialog(
            onDismissRequest = { if (!deleting) interaction = interaction.cancelDelete() },
            title = { Text("删掉选中的 ${selection.selectedIds.size} 个角色？") },
            text = { Text(CharacterDeletionCopy.CONFIRMATION) },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        val confirmed = interaction.confirmDelete()
                        interaction = confirmed.nextState
                        onDelete(confirmed.ids)
                    },
                ) { Text(if (deleting) "正在删除……" else "确认删掉") }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { interaction = interaction.cancelDelete() }) { Text("取消") }
            },
        )
    }

    deletionNotice?.let { notice ->
        val message = when {
            notice.failed -> CharacterDeletionCopy.FAILURE
            notice.pendingAttachmentFiles > 0 -> CharacterDeletionCopy.pendingFiles(notice.pendingAttachmentFiles)
            else -> CharacterDeletionCopy.success(notice.deletedCount)
        }
        AlertDialog(
            onDismissRequest = onDismissDeletionNotice,
            title = {
                Text(
                    when {
                        notice.failed -> "删除未完成"
                        notice.pendingAttachmentFiles > 0 -> "部分附件仍待清理"
                        else -> "角色已删除"
                    },
                )
            },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissDeletionNotice) { Text("知道了") } },
        )
    }
}

@Composable
private fun OlderCharactersFoldHeader(
    count: Int,
    expanded: Boolean,
    lockedOpen: Boolean,
    recentEmpty: Boolean,
    onToggle: () -> Unit,
) {
    val label = if (lockedOpen && recentEmpty) {
        "近 7 天暂无活跃，已展开 7 天前的角色（$count）"
    } else {
        "7 天前的角色（$count）"
    }
    val baseModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .clip(RoundedCornerShape(12.dp))
    val rowModifier = if (lockedOpen) {
        baseModifier
    } else {
        baseModifier.clickable(
            onClickLabel = if (expanded) "收起" else "展开",
            onClick = onToggle,
        )
    }
    Row(
        modifier = rowModifier.padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (!lockedOpen) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DirectoryCharacterCard(
    item: CharacterDirectoryItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            // 依据：https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Avatar(name = item.name, size = 56.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (selected) "✓ ${item.name}" else item.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 「最近一起聊」：区块标题 + 横滑群卡片。
 *
 * 退出群聊后这是回到那个群的唯一入口，所以文案只描述事实，不解释"默认谁接话"。
 */
@Composable
private fun RecentGroupsSection(
    groups: List<GroupThreadCardUi>,
    onOpen: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "最近一起聊",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(groups, key = { it.threadId }) { group ->
                GroupThreadCard(group = group, onClick = { onOpen(group.threadId) })
            }
        }
    }
}

@Composable
private fun GroupThreadCard(group: GroupThreadCardUi, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.width(232.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                group.memberInitials.forEach { initial -> MemberInitial(initial) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = group.lastText.ifBlank { "还没聊过" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 群成员头像首字（24dp，与 顶栏叠头像尺寸一致）。*/
@Composable
private fun MemberInitial(initial: String) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 标题 + 首页状态那一行（` 的口吻：说事实，不卖萌；无角色时不占行）。*/
@Composable
private fun HomeHeader(
    hasCharacters: Boolean,
    memoryCharacterCount: Int,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "PulseZ",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            // 无角色（cards.isEmpty）时整行不显示：新装第一屏不播报「没人记得你」（v0.4.1）。
            HomeCopy.caption(hasCharacters, memoryCharacterCount)?.let { caption ->
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = "设置")
        }
    }
}

@Composable
private fun CharacterCard(card: ThreadCardUi, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Avatar(name = card.name, size = 56.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            if (card.imported && card.lastText.isNullOrBlank()) {
                // 导入的 TA 还没新聊：说事实，不用「还没开始聊」把旧往事抹掉。
                Text(
                    text = "有旧聊天记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = card.lastText?.takeIf { it.isNotBlank() } ?: "还没开始聊",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                // 0 条时不说"TA 记得 0 件事"：那不像人话；也不写「还在认识你」
                // （关系进度不承诺v0.4.1 改为纯状态句「还没记住什么」）。
                text = if (card.memoryCount > 0) {
                    "TA 记得 ${card.memoryCount} 件事"
                } else {
                    HomeCopy.CARD_NO_MEMORY
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Avatar(name: String, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // 首字按 Unicode 码点取（v0.5.2）：`take(1)` 会把 emoji 代理对截半。
            text = avatarInitial(name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 底部两个入口：主「添加角色」= 虚线卡片，次「一起聊」= 1dp 描边。*/
@Composable
private fun HomeBottomEntries(
    groupEnabled: Boolean,
    onAdd: () -> Unit,
    onCreateGroup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        DashedAddEntry(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            onClick = onAdd,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedEntry(
            label = "一起聊",
            enabled = groupEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            onClick = onCreateGroup,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            // 一句说明群聊"不吵 + 成本"，不解释机制
            text = if (groupEnabled) {
                "默认只有一个 TA 接话，不吵"
            } else {
                "至少两个角色，才聊得起来。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DashedAddEntry(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val line = MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = line,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(14.dp.toPx() - stroke / 2f),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 5.dp.toPx()),
                        ),
                    ),
                )
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "添加角色",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun OutlinedEntry(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun EmptyState(characterCount: Int, onAdd: () -> Unit, onOpenDirectory: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            // v0.4 （附录 A 4.3）：不预设关系、不写"交给我"，只给操作与结果。
            // v0.4.1：不再说「这里还没有人」（App 已声明没有真人；「人」易读成"没人陪你"）。
            text = HomeCopy.EMPTY_TITLE,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "粘贴一段文字，或者导入一份聊天记录，就能有个说话的 TA。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedCard(
            onClick = onAdd,
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                text = "创建一个角色",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        CharacterDirectoryEntryButton(characterCount = characterCount, onOpen = onOpenDirectory)
    }
}

// ---------------------------------------------------------------- 预览用假状态

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ThreadsScreenPreview() {
    PulsezTheme(darkTheme = false) {
        ThreadsScreen(
            cards = listOf(
                ThreadCardUi(id = "c1", name = "阿淮", lastText = "你上次说的面试，怎么样了？", memoryCount = 37),
                ThreadCardUi(id = "c2", name = "林野", lastText = "那就早点睡，明天再说。", memoryCount = 12),
                // imported 且当前段没有新消息：显示「有旧聊天记录」而不是「还没开始聊」
                ThreadCardUi(id = "c3", name = "小满", lastText = null, memoryCount = 0, imported = true),
                ThreadCardUi(id = "c4", name = "老周", lastText = "别急，慢慢来。", memoryCount = 3),
            ),
            memoryCharacterCount = 3,
            groups = listOf(
                GroupThreadCardUi(
                    threadId = "g1",
                    title = "阿淮、小满、老周",
                    lastText = "那就都早点睡，明天再说。",
                    memberInitials = listOf("阿", "小", "老"),
                ),
            ),
            onOpen = {},
            onAdd = {},
            onCreateGroup = {},
            onOpenSettings = {},
            onOpenGroup = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ThreadsScreenEmptyPreview() {
    PulsezTheme(darkTheme = true) {
        ThreadsScreen(
            cards = emptyList(),
            memoryCharacterCount = 0,
            onOpen = {},
            onAdd = {},
            onCreateGroup = {},
            onOpenSettings = {},
        )
    }
}
