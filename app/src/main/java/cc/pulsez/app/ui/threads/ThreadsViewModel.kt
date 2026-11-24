package cc.pulsez.app.ui.threads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.db.ThreadKind
import cc.pulsez.app.data.repo.ImportRepository
import kotlinx.coroutines.CancellationException
import cc.pulsez.app.ui.group.GroupChatUiState
import cc.pulsez.app.ui.group.GroupRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 首页状态（团宠化）：
 * - cards：按真实最近单聊活动排序的前六个角色卡；
 * - directoryItems：全部未删除角色，供「全部角色」页搜索和排序；
 * - memoryCharacterCount：有记忆条目的角色数，首页标题下的状态文案使用；
 * - groups：历史群聊。退出群聊后首页必须还有入口，空列表时整块不渲染。
 */
data class ThreadsUiState(
    val cards: List<ThreadCardUi> = emptyList(),
    val memoryCharacterCount: Int = 0,
    val groups: List<GroupThreadCardUi> = emptyList(),
    val totalCharacterCount: Int = 0,
    val directoryItems: List<CharacterDirectoryItem> = emptyList(),
    val directoryQuery: String = "",
    val directorySort: CharacterDirectorySort = CharacterDirectorySort.RECENT_CHAT,
    val deletionNotice: CharacterDeletionNotice? = null,
    val deletingCharacters: Boolean = false,
)

data class CharacterDeletionNotice(
    val deletedCount: Int = 0,
    val pendingAttachmentFiles: Int = 0,
    val failed: Boolean = false,
)

internal data class CharacterSelectionState(
    val active: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
) {
    fun begin(): CharacterSelectionState = copy(active = true)

    fun beginAndSelect(id: String): CharacterSelectionState =
        copy(active = true, selectedIds = selectedIds + id)

    fun toggle(id: String): CharacterSelectionState = copy(
        active = true,
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id,
    )

    fun selectAll(ids: Collection<String>): CharacterSelectionState =
        copy(active = true, selectedIds = ids.toSet())

    fun clearSelection(): CharacterSelectionState = copy(selectedIds = emptySet())

    fun exit(): CharacterSelectionState = CharacterSelectionState()
}

internal data class CharacterDirectoryInteractionState(
    val selection: CharacterSelectionState = CharacterSelectionState(),
    val confirmationVisible: Boolean = false,
) {
    fun enterSelection(): CharacterDirectoryInteractionState = copy(selection = selection.begin())

    fun tap(id: String): CharacterDirectoryInteractionState =
        if (selection.active) copy(selection = selection.toggle(id)) else this

    fun longPress(id: String): CharacterDirectoryInteractionState = copy(
        selection = if (selection.active) selection.toggle(id) else selection.beginAndSelect(id),
    )

    fun selectAll(ids: Collection<String>): CharacterDirectoryInteractionState =
        copy(selection = selection.selectAll(ids))

    fun clearSelection(): CharacterDirectoryInteractionState =
        copy(selection = selection.clearSelection())

    fun requestDelete(): CharacterDirectoryInteractionState =
        if (selection.selectedIds.isEmpty()) this else copy(confirmationVisible = true)

    fun cancelDelete(): CharacterDirectoryInteractionState = copy(confirmationVisible = false)

    fun back(): CharacterDirectoryInteractionState =
        if (selection.active) CharacterDirectoryInteractionState() else this

    fun confirmDelete(): ConfirmedCharacterDeletion {
        val ids = selection.selectedIds.toList()
        return ConfirmedCharacterDeletion(
            nextState = CharacterDirectoryInteractionState(),
            ids = ids,
        )
    }
}

internal data class ConfirmedCharacterDeletion(
    val nextState: CharacterDirectoryInteractionState,
    val ids: List<String>,
)

/** Product copy for the destructive action and its explicit outcome. */
internal object CharacterDeletionCopy {
    const val CONFIRMATION: String = "会删掉选中角色的单聊、专属记忆、查找记录和已关联的附件，删掉后找不回来。涉及其他角色的群聊历史会保留，该角色退出群聊，旧发言显示“已删除角色”。以前导出的备份不受影响；若之后恢复旧备份，角色会重新出现。旧版本留下、无法归属到角色的未发送附件可能保留。"
    const val FAILURE: String = "删除失败，角色数据没有完成清理。旧版本留下、无法归属到角色的未发送附件可能保留。请稍后重试。"

    fun success(count: Int): String = "已删掉 $count 个角色。"

    fun pendingFiles(count: Int): String =
        "角色已删除，但有 $count 个附件文件尚未清理。应用已保存重试记录，之后会自动重试。旧版本留下、无法归属到角色的未发送附件可能保留。"
}

/** Refreshing data must not reset the directory controls held by this route's shared ViewModel. */
internal fun preserveDirectoryControls(previous: ThreadsUiState, refreshed: ThreadsUiState): ThreadsUiState =
    refreshed.copy(
        directoryQuery = previous.directoryQuery,
        directorySort = previous.directorySort,
        deletionNotice = previous.deletionNotice,
        deletingCharacters = previous.deletingCharacters,
    )

/** Directory rows use stable role IDs for selection and deletion. */
data class CharacterDirectoryItem(
    val id: String,
    val name: String,
    val imported: Boolean,
    val lastSingleChatActivityAt: Long?,
    val createdAt: Long = 0L,
    val threadLastMessageAt: Long? = null,
)

enum class CharacterDirectorySort { RECENT_CHAT, NAME }

internal const val HOME_CHARACTER_LIMIT = 6
internal const val INACTIVE_FOLD_DAYS: Long = 7L

internal fun sevenDaysCutoffMillis(
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): Long = now.atZone(zone).minusDays(INACTIVE_FOLD_DAYS).toInstant().toEpochMilli()

internal fun effectiveDirectoryActiveAt(item: CharacterDirectoryItem): Long =
    maxOf(
        item.lastSingleChatActivityAt ?: 0L,
        item.createdAt,
        item.threadLastMessageAt ?: 0L,
    )

internal data class CharacterDirectoryPartition(
    val recentItems: List<CharacterDirectoryItem>,
    val olderItems: List<CharacterDirectoryItem>,
    val autoExpandedOlder: Boolean,
)

internal fun partitionDirectoryItems(
    sortedItems: List<CharacterDirectoryItem>,
    cutoffMillis: Long,
    query: String = "",
    selectionActive: Boolean = false,
): CharacterDirectoryPartition {
    val recent = ArrayList<CharacterDirectoryItem>(sortedItems.size)
    val older = ArrayList<CharacterDirectoryItem>()
    for (item in sortedItems) {
        if (effectiveDirectoryActiveAt(item) >= cutoffMillis) {
            recent.add(item)
        } else {
            older.add(item)
        }
    }
    val autoExpandedOlder = older.isNotEmpty() &&
        (query.isNotBlank() || selectionActive || recent.isEmpty())
    return CharacterDirectoryPartition(
        recentItems = recent,
        olderItems = older,
        autoExpandedOlder = autoExpandedOlder,
    )
}

/**
 * Chinese names use the Simplified Chinese locale's pinyin collation; IDs keep equal names deterministic.
 * The comparator owns a local Collator for each sort, avoiding sharing the mutable collation object.
 * 依据：Android Collator API (locale-sensitive; getInstance(Locale))、AOSP libcore delegates
 * java.text.Collator to android.icu.text.Collator、CLDR zh default collation is pinyin:
 * https://developer.android.com/reference/java/text/Collator
 * https://android.googlesource.com/platform/libcore/+/daaf5894fe3d422dfecbe7e92de10483d3e4467a/ojluni/src/main/java/java/text/Collator.java
 * https://unicode-org.github.io/cldr/ldml/tr35.html#Collation
 */
private fun characterNameComparator(): Comparator<CharacterDirectoryItem> {
    val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
    return Comparator { first, second ->
        val byChineseName = collator.compare(first.name.trim(), second.name.trim())
        if (byChineseName != 0) byChineseName else first.id.compareTo(second.id)
    }
}

private fun recentChatComparator(): Comparator<CharacterDirectoryItem> {
    val nameComparator = characterNameComparator()
    return Comparator { first, second ->
        val firstAt = first.lastSingleChatActivityAt
        val secondAt = second.lastSingleChatActivityAt
        when {
            firstAt == null && secondAt != null -> 1
            firstAt != null && secondAt == null -> -1
            firstAt != null && secondAt != null && firstAt != secondAt -> secondAt.compareTo(firstAt)
            else -> nameComparator.compare(first, second)
        }
    }
}

/** Recent live single chats first; roles with no real activity fill the remaining home slots by name. */
internal fun homeCharacterItems(items: List<CharacterDirectoryItem>): List<CharacterDirectoryItem> =
    items.sortedWith(recentChatComparator()).take(HOME_CHARACTER_LIMIT)

/** Local name-only filtering and deterministic sort for the full role directory. */
internal fun filterAndSortCharacterItems(
    items: List<CharacterDirectoryItem>,
    query: String,
    sort: CharacterDirectorySort,
): List<CharacterDirectoryItem> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val comparator = if (sort == CharacterDirectorySort.NAME) characterNameComparator() else recentChatComparator()
    return items.asSequence()
        .filter { normalizedQuery.isEmpty() || it.name.trim().lowercase(Locale.ROOT).contains(normalizedQuery) }
        .sortedWith(comparator)
        .toList()
}

/**
 * 首页数据：角色 + 最后一条消息 + 记忆条数 + 历史群聊。
 *
 * 记忆数来自现有的 `container.memory.activeCount(threadId)`（有效条目数）；
 * 当前不把摘要段数计入该统计值。
 *
 * 群聊入口：此前只有建群成功时 push 一次 `Route.GroupChat`，退出后 thread 还在库里
 * 但首页没有任何入口。现在从 `thread(kind = GROUP)` 重新列出来（首页「一起聊」）。
 */
class ThreadsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ThreadsUiState())
    val state: StateFlow<ThreadsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshState()
        }
    }

    fun updateDirectoryQuery(value: String) {
        _state.value = _state.value.copy(directoryQuery = value)
    }

    fun updateDirectorySort(value: CharacterDirectorySort) {
        _state.value = _state.value.copy(directorySort = value)
    }

    fun dismissDeletionNotice() {
        _state.value = _state.value.copy(deletionNotice = null)
    }

    fun deleteCharacters(ids: Collection<String>) {
        if (ids.isEmpty() || _state.value.deletingCharacters) return
        viewModelScope.launch {
            _state.value = _state.value.copy(deletingCharacters = true, deletionNotice = null)
            val result = try {
                container.roleDeletions.deleteCharacters(ids, container.attachmentStore)
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(deletingCharacters = false)
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    deletingCharacters = false,
                    deletionNotice = CharacterDeletionNotice(failed = true),
                )
                return@launch
            }
            _state.value = _state.value.copy(
                deletionNotice = if (result.deletedCount == 0) {
                    null
                } else {
                    CharacterDeletionNotice(
                        deletedCount = result.deletedCount,
                        pendingAttachmentFiles = result.pendingAttachmentFiles,
                    )
                },
                deletingCharacters = false,
            )
            // The deletion outcome is already committed; a transient list refresh failure must not
            // claim that deletion failed or erase the persisted cleanup result.
            try {
                refreshState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Next route entry or explicit refresh will reload the committed database state.
            }
        }
    }

    private suspend fun refreshState() {
            // One read for all active single chats; selecting the newest row is deterministic and read-only.
            val latestThreadsByCharacter = LinkedHashMap<String, cc.pulsez.app.data.db.ThreadEntity>()
            container.database.threadDao().activeSinglesByCreation().forEach { thread ->
                val characterId = thread.characterId ?: return@forEach
                latestThreadsByCharacter.putIfAbsent(characterId, thread)
            }
            val directoryItems = container.database.characterDao().allWithChatActivity().map { row ->
                CharacterDirectoryItem(
                    id = row.character.id,
                    name = row.character.name,
                    imported = row.character.sourceType == ImportRepository.SOURCE_TYPE_DEEPSEEK,
                    lastSingleChatActivityAt = row.lastSingleChatActivityAt,
                    createdAt = row.character.createdAt,
                    threadLastMessageAt = latestThreadsByCharacter[row.character.id]?.lastMessageAt,
                )
            }
            val homeItems = homeCharacterItems(directoryItems)
            val memoryCountsByThread = container.database.memoryDao()
                .activeCountsForSingleThreads()
                .associate { it.threadId to it.memoryCount }
            val homeThreads = homeItems.mapNotNull { latestThreadsByCharacter[it.id] }
            val segmentIds = homeThreads.mapNotNull { it.activeSegmentId }.distinct()
            val latestMessageBySegment = if (segmentIds.isEmpty()) {
                emptyMap()
            } else {
                container.database.messageDao().latestForSegments(segmentIds)
                    .distinctBy { it.segmentId }
                    .associateBy { it.segmentId }
            }
            val cards = homeItems.map { item ->
                val thread = latestThreadsByCharacter[item.id]
                val lastText = thread?.activeSegmentId
                    ?.let(latestMessageBySegment::get)
                    ?.content
                    ?.takeIf { it.isNotBlank() }
                ThreadCardUi(
                    id = item.id,
                    name = item.name,
                    lastText = lastText,
                    memoryCount = thread?.let { memoryCountsByThread[it.id] } ?: 0,
                    imported = item.imported,
                    lastSingleChatActivityAt = item.lastSingleChatActivityAt,
                )
            }
            val memoryCharacterCount = latestThreadsByCharacter.values.count { thread ->
                (memoryCountsByThread[thread.id] ?: 0) > 0
            }
            val refreshed = ThreadsUiState(
                cards = cards,
                memoryCharacterCount = memoryCharacterCount,
                groups = loadGroups(),
                totalCharacterCount = directoryItems.size,
                directoryItems = directoryItems,
            )
            _state.value = preserveDirectoryControls(_state.value, refreshed)
    }

    /**
     * 历史群聊，按最近说话时间倒序（DAO 已按 `lastMessageAt DESC` 排）。
     *
     * 群名：建群时没设 title（v0.2 的建群路径传的是 null），默认用成员名拼，
     * 如「阿淮、小鹿、老周」；成员都被删光了才退回默认「你们几个」。
     */
    private suspend fun loadGroups(): List<GroupThreadCardUi> =
        container.database.threadDao().activeByKind(ThreadKind.GROUP).map { thread ->
            val names = container.chat.membersOf(thread.id)
                .sortedBy { it.ordinal }
                .mapNotNull { member ->
                    container.characters.get(member.characterId)?.name?.takeIf { it.isNotBlank() }
                }
            val tail = container.chat.tail(thread.id, limit = 1).lastOrNull()
            GroupThreadCardUi(
                threadId = thread.id,
                title = thread.title?.takeIf { it.isNotBlank() }
                    ?: names.joinToString("、").ifBlank { GroupChatUiState.TITLE_DEFAULT },
                lastText = tail?.content.orEmpty(),
                memberInitials = names.take(GroupRules.MAX_MEMBERS).map { avatarInitial(it) },
            )
        }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ThreadsViewModel(container) }
        }
    }
}

/**
 * 头像首字：按 Unicode **码点**取第一个完整字符（v0.5.2）。
 *
 * 为什么不能 `take(1)`：`String.take(1)` 按 UTF-16 码元截，emoji（代理对）会被截半，
 * 头像显示损坏字符。这里用 codePointAt + toChars，
 * 保证返回值不含孤立代理项。
 *
 * 边界（测试锁定）：
 * - 前后空白先 trim（"  🐳x" → "🐳"）；全空白 → ""；
 * - 组合附加符不跟随（"e\u0301" → "e"）；ZWJ 家庭 emoji 只取第一个码点（不承诺 grapheme cluster）；
 * - 输入本身是孤立代理项（数据已坏）→ ""，绝不输出半个字符。
 *
 * 放这里的原因：本包写作用域只允许改 ui/threads 这两个文件、且不新增主源码文件；本文件不依赖
 * Compose，纯 JVM 测试可直接 import。依据。
 */
internal fun avatarInitial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ""
    val first = trimmed[0]
    if (Character.isHighSurrogate(first)) {
        if (trimmed.length < 2 || !Character.isLowSurrogate(trimmed[1])) return ""
    } else if (Character.isLowSurrogate(first)) {
        return ""
    }
    return String(Character.toChars(trimmed.codePointAt(0)))
}
