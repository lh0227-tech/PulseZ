package cc.pulsez.app.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 成功类行内反馈的停留时间（毫秒）：看过就收，不打扰。 */
private const val NOTICE_VISIBLE_MILLIS: Long = 5_000

/**
 * 记忆面板。
 *
 * 三条分支（` 验收）：
 * 1. 有导入旧事 → 顶部浅色提示块 +「整理往事」（可续跑、可停下、>¥1 先确认费用）；
 * 2. 没有记忆 → 空状态文案；
 * 3. 有记忆 → 分组列表 + 每行「…」菜单（忘掉 / 以后别再提这件事）。
 *
 * v0.4.1 追加：有 `dim=0` 的导入旧事且「找旧事」已配置时，多一块「让 TA 能按意思找旧事」
 * （补建向量；不自动执行、可停下；文案说清费用由用户的服务商收，`）。
 *
 * 交互纪律：返回箭头 48dp + `AutoMirrored` 图标；触控目标 ≥48dp；
 * 不抢返回手势（页面级返回由 `AppRoot` 的预测性返回处理，本页不注册 `BackHandler`）；
 * 不引入新视觉概念：提示块沿用 `surfaceVariant` 浅色块（与聊天页 AI 标识条同款）。
 */
@Composable
fun MemoryScreen(
    state: MemoryUiState,
    onBack: () -> Unit,
    onForget: (String, String) -> Unit,
    onAddBoundary: (String, String) -> Unit,
    onConsolidate: () -> Unit,
    onCancelConsolidate: () -> Unit,
    onBackfillSemantic: () -> Unit = {},
    onCancelBackfill: () -> Unit = {},
    onConfirmConsent: (Boolean) -> Unit,
    onDismissNotice: () -> Unit,
    /** 「未连 AI」引导 → 聊天设置页（`Route.Config`）。 */
    onOpenSettings: () -> Unit,
    /**
     * 「先在设置里加一个『找旧事』」引导 → 找旧事独立设置页（`Route.FindOldThings`）。
     *
     * 依据：用户指令 2026-11-21——记忆面板里所有「加一个找旧事」类引导必须直接进找旧事设置，
     * 不能进聊天设置页（两套服务分开后，进错页会让用户以为找旧事就是聊天服务商）。
     */
    onOpenFindOldThings: () -> Unit = {},
) {
    // 删除/别再提/整理结果这类反馈几秒后自己收起来；带引导按钮的提示留给用户操作。
    // 是否有按钮、去哪个页面都由 state.noticeOpenTarget 决定（不再比较提示语字符串）。
    LaunchedEffect(state.notice, state.noticeOpenTarget) {
        val notice = state.notice
        if (notice != null && state.noticeOpenTarget == null) {
            delay(NOTICE_VISIBLE_MILLIS)
            onDismissNotice()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        MemoryTopBar(onBack = onBack)

        state.notice?.let { text ->
            NoticeLine(
                text = text,
                target = state.noticeOpenTarget,
                onOpenSettings = onOpenSettings,
                onOpenFindOldThings = onOpenFindOldThings,
            )
        }

        if (state.importPending) {
            ImportedHistoryBlock(
                state = state,
                onConsolidate = onConsolidate,
                onCancelConsolidate = onCancelConsolidate,
            )
        }

        // 老数据「补建」：有 dim=0 的导入旧事且「找旧事」已配置才出现；
        // 不自动执行，用户点了才用 TA 的找旧事服务（费用由服务商收）。
        if (state.semanticBackfillNeeded || state.backfilling) {
            SemanticBackfillBlock(
                state = state,
                onBackfill = onBackfillSemantic,
                onCancelBackfill = onCancelBackfill,
            )
        }

        when {
            state.loading -> Spacer(Modifier.height(24.dp))
            state.groups.isEmpty() -> EmptyState()
            else -> MemoryGroups(
                groups = state.groups,
                onForget = onForget,
                onAddBoundary = onAddBoundary,
            )
        }
    }

    // 费用二次确认（` 预估 >¥1 才问；确认前一分钱都没花）。
    state.consentPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { onConfirmConsent(false) },
            modifier = Modifier.widthIn(max = 360.dp),
            text = { Text(MemoryCopy.consentText(plan.estimatedYuan, plan.shards)) },
            confirmButton = {
                TextButton(onClick = { onConfirmConsent(true) }) { Text(MemoryCopy.CONSENT_CONTINUE) }
            },
            dismissButton = {
                TextButton(onClick = { onConfirmConsent(false) }) { Text(MemoryCopy.CONSENT_CANCEL) }
            },
        )
    }
}

// ------------------------------------------------------------------ 顶部

@Composable
private fun MemoryTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = MemoryCopy.TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = MemoryCopy.CAPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 面板内一行反馈（行内 Snackbar 的替代）：删掉了 / 别再提 / 整理结果 / 未连 AI。
 *
 * 用 `surfaceVariant` 浅色条，和聊天页的 AI 标识条同一视觉；需要引导时右侧给一个可点入口（48dp）。
 * 入口去向由显式枚举 [NoticeOpenTarget] 决定（用户指令 2026-11-21：两套服务分开；
 * 不再用提示语字符串判断），按钮文案也由同一枚举派生。
 */
@Composable
private fun NoticeLine(
    text: String,
    target: NoticeOpenTarget?,
    onOpenSettings: () -> Unit,
    onOpenFindOldThings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )
        if (target != null) {
            TextButton(
                onClick = when (target) {
                    NoticeOpenTarget.CHAT -> onOpenSettings
                    NoticeOpenTarget.FIND_OLD_THINGS -> onOpenFindOldThings
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(openButtonLabel(target))
            }
        }
    }
}

/** 引导按钮文案由目标派生（纯函数，`MemoryPanelLogicTest` 锁定）。*/
internal fun openButtonLabel(target: NoticeOpenTarget): String = when (target) {
    NoticeOpenTarget.CHAT -> MemoryCopy.OPEN_SETTINGS
    NoticeOpenTarget.FIND_OLD_THINGS -> OPEN_FIND_OLD_THINGS
}

/** 找旧事提示右侧的按钮：说清是"去加一个服务"，不是"去连接聊天"（用户指令 2026-11-21）。 */
private const val OPEN_FIND_OLD_THINGS = "去添加"

/**
 * 「这些是导入的旧事」提示块（` 导入未整理的分支）。
 *
 * 只要 thread 里还有 IMPORT 段就一直显示（部分整理过也显示，按钮变「再整理一些」）；
 * 整理完的段落仍在，但按钮文案按是否有断点区分，不假装「全整理完了」。
 */
@Composable
private fun ImportedHistoryBlock(
    state: MemoryUiState,
    onConsolidate: () -> Unit,
    onCancelConsolidate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = MemoryCopy.IMPORT_TIP,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        state.consolidateProgress?.let { progress ->
            Text(
                text = if (state.consolidating) {
                    MemoryCopy.PROGRESS_RUNNING_PREFIX + progress
                } else {
                    MemoryCopy.PROGRESS_PARTIAL_PREFIX + progress
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onConsolidate,
                enabled = !state.consolidating,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    when {
                        state.consolidating -> MemoryCopy.CONSOLIDATING
                        state.consolidateProgress != null -> MemoryCopy.CONSOLIDATE_AGAIN
                        else -> MemoryCopy.CONSOLIDATE
                    },
                )
            }
            if (state.consolidating) {
                TextButton(
                    onClick = onCancelConsolidate,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(MemoryCopy.CONSOLIDATE_CANCEL)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 老数据补建

/**
 * 「让 TA 能按意思找旧事」提示块（v0.4.1 `）。
 *
 * 与上面的「整理往事」是两件事：这里只用用户填的「找旧事」服务给导入的旧事建检索向量，
 * 不调用聊天 AI、不写记忆。**不自动执行**：用户点了才跑（不静默花钱）。
 */
@Composable
private fun SemanticBackfillBlock(
    state: MemoryUiState,
    onBackfill: () -> Unit,
    onCancelBackfill: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = MemoryCopy.BACKFILL_TIP,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        state.backfillProgress?.let { progress ->
            Text(
                text = MemoryCopy.BACKFILL_PROGRESS_PREFIX + progress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onBackfill,
                enabled = !state.backfilling,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (state.backfilling) MemoryCopy.BACKFILLING else MemoryCopy.BACKFILL)
            }
            if (state.backfilling) {
                TextButton(
                    onClick = onCancelBackfill,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(MemoryCopy.BACKFILL_CANCEL)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 列表

@Composable
private fun EmptyState() {
    Column(modifier = Modifier.padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = MemoryCopy.EMPTY,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemoryGroups(
    groups: List<MemoryGroup>,
    onForget: (String, String) -> Unit,
    onAddBoundary: (String, String) -> Unit,
) {
    groups.forEach { group ->
        Text(
            text = group.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
        )
        group.items.forEach { item ->
            // 用 (kind, item) 做 key：分组内条目变化时菜单状态不串行到别的条目。
            key(group.kind, item) {
                MemoryRow(
                    kind = group.kind,
                    item = item,
                    onForget = onForget,
                    onAddBoundary = onAddBoundary,
                )
            }
        }
    }
    Spacer(Modifier.height(40.dp))
}

/**
 * 一条记忆：正文 + 右侧「…」菜单（`每行右侧「…」）。
 *
 * 「以后别再提这件事」只对可以"被提起"的条目开放；RELATION（你们之间）没有这个语义，
 * 不显示（` 验收）。
 */
@Composable
private fun MemoryRow(
    kind: String,
    item: String,
    onForget: (String, String) -> Unit,
    onAddBoundary: (String, String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = MemoryCopy.MENU,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(MemoryCopy.FORGET) },
                    onClick = {
                        menuOpen = false
                        onForget(kind, item)
                    },
                )
                if (kind != MEMORY_KIND_RELATION) {
                    DropdownMenuItem(
                        text = { Text(MemoryCopy.BOUNDARY) },
                        onClick = {
                            menuOpen = false
                            onAddBoundary(kind, item)
                        },
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
