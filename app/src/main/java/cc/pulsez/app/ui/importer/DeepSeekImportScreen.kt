package cc.pulsez.app.ui.importer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.pulsez.app.core.ConsolidateRange
import cc.pulsez.app.importer.deepseek.ImportErrorCodes
import cc.pulsez.app.importer.deepseek.DeepSeekSessionPreview
import cc.pulsez.app.ui.components.PulsezPrimaryButton
import cc.pulsez.app.ui.importer.ImportTaskState.AwaitingConsent
import cc.pulsez.app.ui.importer.ImportTaskState.Done
import cc.pulsez.app.ui.importer.ImportTaskState.Failed
import cc.pulsez.app.ui.importer.ImportTaskState.Idle
import cc.pulsez.app.ui.importer.ImportTaskState.Inspecting
import cc.pulsez.app.ui.importer.ImportTaskState.Ready
import cc.pulsez.app.ui.importer.ImportTaskState.Running
import java.time.Clock
import java.time.ZoneId

/**
 * 旧聊天（DeepSeek 导出 ZIP）导入页。
 *
 * 一个页面把「选包 → 预检 → 一屏确认 → 进度 → 结果」画完：状态全在 [ImportTaskState] 里，
 * 页面重进不丢（store 挂在 `appScope`）。本页只做三件事：
 * 1. 按状态分支渲染（[Idle] / [Inspecting] / [Ready] / [Running] / [AwaitingConsent] /
 *    [Done] / [Failed]）；
 * 2. 收集确认屏的一屏输入（名字 / 人设 / 是否整理 / 范围 / 续导选择）；
 * 3. 返回的确认（导入中「停下并保留」、结果页撤销的二次确认）——用 [AlertDialog]，
 *    系统返回先关弹窗，**不自己抢手势**。
 *
 * 交互纪律：返回箭头 48dp + `AutoMirrored` 图标；触控目标 ≥48dp；
 * 键盘用 `imePadding()`（insets 由 `AppRoot` 的根 padding 统一处理，见 `）；
 * 不引入渐变/emoji，沿用现有黑白 Material 3 样式。
 */
@Composable
fun DeepSeekImportScreen(
    state: DeepSeekImportUiState,
    task: ImportTaskState,
    onBack: () -> Unit,
    onPickZip: () -> Unit,
    onNameChange: (String) -> Unit,
    onPersonaChange: (String) -> Unit,
    onConsolidateChange: (Boolean) -> Unit,
    onEmbeddingChange: (Boolean) -> Unit = {},
    onRangeChange: (ConsolidateRange) -> Unit,
    onResumeChoice: (Boolean) -> Unit,
    onStart: () -> Unit,
    onConfirmConsent: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenChat: (String) -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshAi: () -> Unit = {},
    onAddNewGroup: () -> Unit = {},
    onRemoveGroup: (String) -> Unit = {},
    onGroupNameChange: (String, String) -> Unit = { _, _ -> },
    onGroupPersonaChange: (String, String) -> Unit = { _, _ -> },
    onAssignWindow: (String, String?) -> Unit = { _, _ -> },
    onAutoCluster: () -> Unit = {},
    onCancelAutoCluster: () -> Unit = {},
    onAwakenOnImportChange: (Boolean) -> Unit = {},
    onOpenChatConfig: () -> Unit = onOpenSettings,
) {
    var showStopConfirm by remember { mutableStateOf(false) }
    var showUndoConfirm by remember { mutableStateOf(false) }

    // 从「去连接」回来时重新确认 AI 是否已配好（连上后复选框立刻可用）。
    LaunchedEffect(Unit) { onRefreshAi() }

    val running = task is Running
    val finished = task is Done || task is Failed

    // 导入中按返回 = 先确认「停下并保留」；结果页按返回 = 完成。
    // 页面级回调只在这两个状态启用：离开页面后任务仍在 appScope 里继续，符合「离开页面任务继续」。
    BackHandler(enabled = running) { showStopConfirm = true }
    BackHandler(enabled = finished) { onDone() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ImportTopBar(
            title = taskTitle(task),
            onBack = {
                when {
                    running -> showStopConfirm = true
                    finished -> onDone()
                    else -> onBack()
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            when (task) {
                Idle -> IdleContent(state = state, onPickZip = onPickZip)
                is Inspecting -> InspectingContent()
                is Ready -> ReadyContent(
                    state = state,
                    task = task,
                    onNameChange = onNameChange,
                    onPersonaChange = onPersonaChange,
                    onConsolidateChange = onConsolidateChange,
                    onEmbeddingChange = onEmbeddingChange,
                    onRangeChange = onRangeChange,
                    onResumeChoice = onResumeChoice,
                    onStart = onStart,
                    onOpenSettings = onOpenSettings,
                    onAddNewGroup = onAddNewGroup,
                    onRemoveGroup = onRemoveGroup,
                    onGroupNameChange = onGroupNameChange,
                    onGroupPersonaChange = onGroupPersonaChange,
                    onAssignWindow = onAssignWindow,
                    onAutoCluster = onAutoCluster,
                    onCancelAutoCluster = onCancelAutoCluster,
                    onAwakenOnImportChange = onAwakenOnImportChange,
                    onOpenChatConfig = onOpenChatConfig,
                )

                is Running -> RunningContent(
                    task = task,
                    semanticIndexing = state.semanticIndexing,
                    onCancel = onCancel,
                )
                is AwaitingConsent -> AwaitingConsentContent(task = task)
                is Done -> DoneContent(
                    state = state,
                    task = task,
                    onOpenChat = onOpenChat,
                    onUndo = { showUndoConfirm = true },
                    onDone = onDone,
                )

                is Failed -> FailedContent(task = task, onRetry = onRetry, onDone = onDone)
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    // 导入中「停下」确认（`停下会保留已导入的）。
    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("要停下吗？") },
            text = { Text("停下会保留已经导入的，确定停下吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStopConfirm = false
                        onCancel()
                    },
                ) { Text("停下") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("继续") }
            },
        )
    }

    // 批次撤销按本批新增聊天精确删除；已有聊天、后续聊天与记忆保护规则在确认文案里说明。
    if (showUndoConfirm) {
        AlertDialog(
            onDismissRequest = { showUndoConfirm = false },
            title = { Text("撤销这次导入？") },
            text = {
                Text((task as? Done)?.summary?.let(::undoConfirmationText)
                    ?: "只会撤销这次导入新增的聊天；记忆有变化时会拒绝撤销，不会删除内容。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUndoConfirm = false
                        onUndo()
                    },
                ) { Text("撤销") }
            },
            dismissButton = {
                TextButton(onClick = { showUndoConfirm = false }) { Text("算了") }
            },
        )
    }

    // 精确费用二次确认（` >¥1 或超当日上限时，第一次模型调用前问一句）。
    // v0.4.1：正文去掉内部单位「片」（` 三·#8），只说金额与要买什么。
    if (task is AwaitingConsent) {
        AlertDialog(
            onDismissRequest = { onConfirmConsent(false) },
            title = { Text("这段往事挺长") },
            text = {
                Text(importConsentText(formatYuan(task.plan.estimatedYuan)))
            },
            confirmButton = {
                TextButton(onClick = { onConfirmConsent(true) }) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { onConfirmConsent(false) }) { Text("先不整理") }
            },
        )
    }
}

// ------------------------------------------------------------------ 各状态

@Composable
private fun IdleContent(state: DeepSeekImportUiState, onPickZip: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = "在 DeepSeek：头像 → 设置 → 数据管理 → 导出所有历史对话，把下载的 ZIP 选进来。",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    PulsezPrimaryButton(
        onClick = onPickZip,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text("选一个 ZIP 文件")
    }
    state.pickError?.let {
        Spacer(Modifier.height(12.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun InspectingContent() {
    Spacer(Modifier.height(48.dp))
    CircularProgressIndicator(modifier = Modifier.size(32.dp))
    Spacer(Modifier.height(16.dp))
    Text(
        text = "正在看这个文件……",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReadyContent(
    state: DeepSeekImportUiState,
    task: Ready,
    onNameChange: (String) -> Unit,
    onPersonaChange: (String) -> Unit,
    onConsolidateChange: (Boolean) -> Unit,
    onEmbeddingChange: (Boolean) -> Unit,
    onRangeChange: (ConsolidateRange) -> Unit,
    onResumeChoice: (Boolean) -> Unit,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddNewGroup: () -> Unit,
    onRemoveGroup: (String) -> Unit,
    onGroupNameChange: (String, String) -> Unit,
    onGroupPersonaChange: (String, String) -> Unit,
    onAssignWindow: (String, String?) -> Unit,
    onAutoCluster: () -> Unit,
    onCancelAutoCluster: () -> Unit,
    onAwakenOnImportChange: (Boolean) -> Unit,
    onOpenChatConfig: () -> Unit,
) {
    val inspect = task.inspect
    val previewNow = remember(inspect.contentFingerprint) { Clock.systemDefaultZone().instant() }
    val previewZone = remember(inspect.contentFingerprint) { ZoneId.systemDefault() }
    var dateFilter by remember(inspect.contentFingerprint) { mutableStateOf(DEFAULT_IMPORT_PREVIEW_DATE_FILTER) }
    var dateSort by remember(inspect.contentFingerprint) { mutableStateOf(DEFAULT_IMPORT_PREVIEW_DATE_SORT) }
    var selectedSourceIds by remember(inspect.contentFingerprint) { mutableStateOf(emptySet<String>()) }
    var showOlderBucket by remember(inspect.contentFingerprint) { mutableStateOf(false) }
    var showNoiseBucket by remember(inspect.contentFingerprint) { mutableStateOf(false) }
    var showUnknownBucket by remember(inspect.contentFingerprint) { mutableStateOf(false) }
    val visibleWindows = remember(inspect.sourceWindows, dateFilter, dateSort, previewNow, previewZone) {
        filterAndSortImportPreviews(
            previews = inspect.sourceWindows,
            now = previewNow,
            zone = previewZone,
            filter = dateFilter,
            sort = dateSort,
        )
    }
    val canChangeAssignments = state.savedBatchPlan == null
    val bulkAssignableIds = remember(inspect.sourceWindows, canChangeAssignments) {
        if (canChangeAssignments) assignablePreviewIds(inspect.sourceWindows) else emptySet()
    }
    val visibleSourceIds = visibleWindows.mapTo(mutableSetOf()) { it.sourceSessionId }
    val assignableVisibleIds = bulkAssignableIds intersect visibleSourceIds
    val selectedVisibleIds = selectedSourceIds intersect assignableVisibleIds

    Spacer(Modifier.height(4.dp))
    Text(
        text = formatImportTimeRange(inspect.timeRange),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "找到 ${inspect.sourceWindows.size} 段对话。每段可以放进不同 TA；没选的会跳过。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!state.sourceDedupeCheckComplete) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "正在确认哪些对话以前导入过……",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (state.sourceDedupeCheckFailed) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "现在没法确认哪些对话以前导入过。请重新选择这个压缩包，再试一次。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(16.dp))

    Text(
        text = "导入时不会整理记忆。之后若整理或修改了记忆，撤销会拒绝并保留全部内容；你仍可在记忆面板整理。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("TA 分组", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (state.savedBatchPlan == null) {
            OutlinedButton(onClick = onAddNewGroup, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("新建 TA")
            }
        }
    }
    Text(
        text = "已有 TA 的名字、头像和人设不会改。每个 TA 名下选中的对话会合并为一段历史。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.savedBatchPlan == null) {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onAutoCluster,
            enabled = state.aiReady && !state.autoClustering,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(ImportAutoClusterCopy.BUTTON)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = ImportAutoClusterCopy.HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.aiReady) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = ImportAutoClusterCopy.NEED_AI_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenChatConfig, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("去连接对话服务")
            }
        }
        if (state.autoClustering) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    text = ImportAutoClusterCopy.RUNNING,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onCancelAutoCluster,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("取消")
                }
            }
        }
        state.autoClusterError?.let { err ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.autoClusterSummary?.let { summaryText ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (state.savedBatchPlan != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "这是未完成的导入批次。已完成的 TA 组会跳过，失败的组可以重试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    state.importGroups.forEach { group ->
        Spacer(Modifier.height(8.dp))
        if (group.existingCharacterId != null) {
            Text("已有 TA：${group.name}", style = MaterialTheme.typography.bodyLarge)
        } else {
            OutlinedTextField(
                value = group.name,
                onValueChange = { onGroupNameChange(group.id, it) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.savedBatchPlan == null,
                label = { Text("新 TA 的名字") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = group.persona,
                onValueChange = { onGroupPersonaChange(group.id, it) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.savedBatchPlan == null,
                label = { Text("TA 是什么样的人（可选）") },
                placeholder = { Text("不填也行") },
            )
            if (state.savedBatchPlan == null) {
                TextButton(onClick = { onRemoveGroup(group.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("删除这个新 TA 分组")
                }
            }
        }
        group.status?.let { status ->
            Text(
                text = when (status) {
                    "SUCCESS" -> "这个 TA 组已完成"
                    "FAILED" -> "这个 TA 组上次没成功，可以重试"
                    else -> "这个 TA 组还没完成"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("对话列表", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (canChangeAssignments) {
            OutlinedButton(
                onClick = {
                    val allVisibleSelected = assignableVisibleIds.isNotEmpty() &&
                        assignableVisibleIds.all { it in selectedSourceIds }
                    selectedSourceIds = if (allVisibleSelected) {
                        selectedSourceIds - assignableVisibleIds
                    } else {
                        selectedSourceIds + assignableVisibleIds
                    }
                },
                enabled = assignableVisibleIds.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                val allVisibleSelected = assignableVisibleIds.isNotEmpty() &&
                    assignableVisibleIds.all { it in selectedSourceIds }
                Text(if (allVisibleSelected) "取消全选" else "全选可读取结果")
            }
        }
    }
    val skipped = state.assignments.values.count { it == null }
    Text("将跳过 $skipped 段对话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("按最后一条可导入消息的日期筛选；日期未知的对话会单独列出。", style = MaterialTheme.typography.bodySmall)
    Text(
        text = "超过一年：只显示最后消息早于 ${formatImportPreviewCutoff(previewNow, previewZone)} 的对话；刚好到边界不算超过一年。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PreviewChoice(
        label = "显示全部",
        selected = dateFilter == ImportPreviewDateFilter.ALL,
        onClick = { dateFilter = ImportPreviewDateFilter.ALL; selectedSourceIds = emptySet() },
    )
    PreviewChoice(
        label = "最后消息超过一年",
        selected = dateFilter == ImportPreviewDateFilter.OLDER_THAN_ONE_YEAR,
        onClick = { dateFilter = ImportPreviewDateFilter.OLDER_THAN_ONE_YEAR; selectedSourceIds = emptySet() },
    )
    PreviewChoice(
        label = "最后消息日期未知",
        selected = dateFilter == ImportPreviewDateFilter.UNKNOWN,
        onClick = { dateFilter = ImportPreviewDateFilter.UNKNOWN; selectedSourceIds = emptySet() },
    )
    Text("排序", style = MaterialTheme.typography.titleSmall)
    PreviewChoice(
        label = "保持原有顺序",
        selected = dateSort == ImportPreviewDateSort.SOURCE_ORDER,
        onClick = { dateSort = ImportPreviewDateSort.SOURCE_ORDER },
    )
    PreviewChoice(
        label = "最后消息从新到旧",
        selected = dateSort == ImportPreviewDateSort.LAST_MESSAGE_NEWEST_FIRST,
        onClick = { dateSort = ImportPreviewDateSort.LAST_MESSAGE_NEWEST_FIRST },
    )
    PreviewChoice(
        label = "最后消息从早到晚",
        selected = dateSort == ImportPreviewDateSort.LAST_MESSAGE_OLDEST_FIRST,
        onClick = { dateSort = ImportPreviewDateSort.LAST_MESSAGE_OLDEST_FIRST },
    )
    if (visibleWindows.any { it.damageCode != null }) {
        Text(
            text = "无法读取的对话仍会显示，但不会被批量选中。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (visibleWindows.any { it.sourceSessionId in state.duplicateSourceIdsInZip }) {
        Text(
            text = "同一来源在压缩包中重复出现时，可以单独处理，不会被批量选中。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text("当前显示 ${visibleWindows.size} 段，已选 ${selectedVisibleIds.size} 段。", style = MaterialTheme.typography.bodySmall)
    if (selectedVisibleIds.isNotEmpty()) {
        BatchWindowAssignmentMenu(
            groups = state.importGroups,
            selectedCount = selectedVisibleIds.size,
            enabled = state.savedBatchPlan == null,
            onAssign = { groupId ->
                val projected = assignmentsAfterBatchChange(state.assignments, selectedVisibleIds, groupId)
                selectedVisibleIds.forEach { sourceId ->
                    onAssignWindow(sourceId, projected[sourceId])
                }
                selectedSourceIds = emptySet()
            },
        )
    }
    if (dateFilter == ImportPreviewDateFilter.ALL) {
        val buckets = remember(visibleWindows, previewNow, previewZone) {
            bucketImportPreviews(visibleWindows, previewNow, previewZone)
        }
        if (buckets.usedRecentFallback) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "近 7 天没有对话，已自动为你展开最近聊过的 ${buckets.expandedActive.size} 段。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (buckets.expandedActive.isEmpty() && (buckets.toolNoise.isNotEmpty() || buckets.unknownOrDamaged.isNotEmpty())) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "没有可直接展开的近期对话，可在下方折叠分组里查看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        buckets.expandedActive.forEach { preview ->
            PreviewWindowRow(
                preview = preview,
                state = state,
                previewZone = previewZone,
                canChangeAssignments = canChangeAssignments,
                selectable = preview.sourceSessionId in assignableVisibleIds,
                selected = preview.sourceSessionId in selectedSourceIds,
                onToggleSelect = { checked ->
                    selectedSourceIds = if (checked) {
                        selectedSourceIds + preview.sourceSessionId
                    } else {
                        selectedSourceIds - preview.sourceSessionId
                    }
                },
                onAssign = { onAssignWindow(preview.sourceSessionId, it) },
            )
        }
        if (buckets.olderThanSevenDays.isNotEmpty()) {
            PreviewBucketSection(
                title = "7 天前的对话（${buckets.olderThanSevenDays.size} 段）",
                expanded = showOlderBucket,
                onToggle = { showOlderBucket = !showOlderBucket },
                previews = buckets.olderThanSevenDays,
                state = state,
                previewZone = previewZone,
                canChangeAssignments = canChangeAssignments,
                assignableVisibleIds = assignableVisibleIds,
                selectedSourceIds = selectedSourceIds,
                onToggleSelect = { id, checked ->
                    selectedSourceIds = if (checked) selectedSourceIds + id else selectedSourceIds - id
                },
                onAssignWindow = onAssignWindow,
            )
        }
        if (buckets.toolNoise.isNotEmpty()) {
            PreviewBucketSection(
                title = "疑似查资料或极短问答（${buckets.toolNoise.size} 段）",
                expanded = showNoiseBucket,
                onToggle = { showNoiseBucket = !showNoiseBucket },
                previews = buckets.toolNoise,
                state = state,
                previewZone = previewZone,
                canChangeAssignments = canChangeAssignments,
                assignableVisibleIds = assignableVisibleIds,
                selectedSourceIds = selectedSourceIds,
                onToggleSelect = { id, checked ->
                    selectedSourceIds = if (checked) selectedSourceIds + id else selectedSourceIds - id
                },
                onAssignWindow = onAssignWindow,
            )
        }
        if (buckets.unknownOrDamaged.isNotEmpty()) {
            PreviewBucketSection(
                title = "日期未知或无法导入（${buckets.unknownOrDamaged.size} 段）",
                expanded = showUnknownBucket,
                onToggle = { showUnknownBucket = !showUnknownBucket },
                previews = buckets.unknownOrDamaged,
                state = state,
                previewZone = previewZone,
                canChangeAssignments = canChangeAssignments,
                assignableVisibleIds = assignableVisibleIds,
                selectedSourceIds = selectedSourceIds,
                onToggleSelect = { id, checked ->
                    selectedSourceIds = if (checked) selectedSourceIds + id else selectedSourceIds - id
                },
                onAssignWindow = onAssignWindow,
            )
        }
    } else {
        visibleWindows.forEach { preview ->
            PreviewWindowRow(
                preview = preview,
                state = state,
                previewZone = previewZone,
                canChangeAssignments = canChangeAssignments,
                selectable = preview.sourceSessionId in assignableVisibleIds,
                selected = preview.sourceSessionId in selectedSourceIds,
                onToggleSelect = { checked ->
                    selectedSourceIds = if (checked) {
                        selectedSourceIds + preview.sourceSessionId
                    } else {
                        selectedSourceIds - preview.sourceSessionId
                    }
                },
                onAssign = { onAssignWindow(preview.sourceSessionId, it) },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    val awakenChecked = state.awakenOnImport && state.aiReady
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = awakenChecked,
                enabled = state.aiReady,
                role = Role.Checkbox,
                onValueChange = onAwakenOnImportChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = awakenChecked,
            onCheckedChange = null,
            enabled = state.aiReady,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = ImportAwakenCopy.TITLE,
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.aiReady) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = ImportAwakenCopy.HINT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!state.aiReady) {
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenChatConfig, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("去连接对话服务")
        }
    }

    // 顺便按意思找旧事（v0.4.1）：**只在配好「找旧事」时才有这一行**；
    // 默认开、可关。没配置时整行不显示（不用灰色/禁用制造困惑，引导在「找旧事」设置行旁边）。
    if (state.embedReady) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = state.embedOnImport,
                    enabled = true,
                    role = Role.Checkbox,
                    onValueChange = onEmbeddingChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.embedOnImport, onCheckedChange = null)
            Spacer(Modifier.size(12.dp))
            Text(
                text = ImportSemanticCopy.OPTION,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        // 说清三件事：是什么 / 费用谁收 / 不勾会怎样。
        Text(
            text = ImportSemanticCopy.HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (state.aiReady) {
        TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("去设置「找旧事」")
        }
    }

    Spacer(Modifier.height(16.dp))
    Text(
        text = "旧对话里那些 AI 的回答会原样留在往事里，但不会变成 TA 的性格。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    PulsezPrimaryButton(
        onClick = onStart,
        enabled = state.assignments.values.any { it != null } &&
            state.sourceDedupeCheckComplete && !state.sourceDedupeCheckFailed,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text(if (state.savedBatchPlan == null) "开始导入已分组的对话" else "继续导入")
    }
}

@Composable
private fun PreviewBucketSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    previews: List<DeepSeekSessionPreview>,
    state: DeepSeekImportUiState,
    previewZone: ZoneId,
    canChangeAssignments: Boolean,
    assignableVisibleIds: Set<String>,
    selectedSourceIds: Set<String>,
    onToggleSelect: (String, Boolean) -> Unit,
    onAssignWindow: (String, String?) -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (expanded) {
        previews.forEach { preview ->
            PreviewWindowRow(
                preview = preview,
                state = state,
                previewZone = previewZone,
                canChangeAssignments = canChangeAssignments,
                selectable = preview.sourceSessionId in assignableVisibleIds,
                selected = preview.sourceSessionId in selectedSourceIds,
                onToggleSelect = { checked -> onToggleSelect(preview.sourceSessionId, checked) },
                onAssign = { onAssignWindow(preview.sourceSessionId, it) },
            )
        }
    }
}

@Composable
private fun PreviewWindowRow(
    preview: DeepSeekSessionPreview,
    state: DeepSeekImportUiState,
    previewZone: ZoneId,
    canChangeAssignments: Boolean,
    selectable: Boolean,
    selected: Boolean,
    onToggleSelect: (Boolean) -> Unit,
    onAssign: (String?) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
        val title = preview.title ?: "未命名对话"
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (canChangeAssignments) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onToggleSelect,
                    enabled = selectable,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "选择对话：$title" },
                )
            }
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            text = "开始于 ${formatImportSourceStartTime(preview.sourceStartedAt)} · " +
                if (preview.damageCode == null) "${preview.messageCount} 条消息" else "无法读取消息",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "最后一条可导入消息：${formatImportLastMessageDate(preview.lastMessageAt, previewZone)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (preview.sourceSessionId in state.alreadyImportedSourceIds) {
            Text(
                text = "这段对话以前已经导入过，开始后会跳过，不会重复写入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (preview.sourceSessionId in state.duplicateSourceIdsInZip) {
            Text(
                text = "压缩包里重复出现了这段对话；同一个来源只会处理一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (preview.damageCode != null) {
            Text(
                text = "这段对话有损坏。若放进分组，整组会失败；也可以先跳过。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        WindowAssignmentMenu(
            preview = preview,
            groups = state.importGroups,
            selectedGroupId = state.assignments[preview.sourceSessionId],
            enabled = state.savedBatchPlan == null,
            onAssign = onAssign,
        )
    }
}

@Composable
private fun WindowAssignmentMenu(
    preview: DeepSeekSessionPreview,
    groups: List<ImportGroupOption>,
    selectedGroupId: String?,
    enabled: Boolean,
    onAssign: (String?) -> Unit,
) {
    var expanded by remember(preview.sourceSessionId, enabled) { mutableStateOf(false) }
    val selected = groups.firstOrNull { it.id == selectedGroupId }
    val label = selected?.let { (if (it.existingCharacterId == null) "新建：" else "已有：") + it.name }
        ?: "跳过这段对话"
    Spacer(Modifier.height(8.dp))
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("归到：$label", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("跳过这段对话") },
                onClick = { expanded = false; onAssign(null) },
            )
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text((if (group.existingCharacterId == null) "新建：" else "已有：") + group.name) },
                    onClick = { expanded = false; onAssign(group.id) },
                )
            }
        }
    }
}

@Composable
private fun PreviewChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

@Composable
private fun BatchWindowAssignmentMenu(
    groups: List<ImportGroupOption>,
    selectedCount: Int,
    enabled: Boolean,
    onAssign: (String?) -> Unit,
) {
    var expanded by remember(groups, enabled, selectedCount) { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("把已选的 $selectedCount 段对话归到……", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("跳过已选的对话") },
                onClick = { expanded = false; onAssign(null) },
            )
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text((if (group.existingCharacterId == null) "新建：" else "已有：") + group.name) },
                    onClick = { expanded = false; onAssign(group.id) },
                )
            }
        }
    }
}

@Composable
private fun ResumeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        PulsezPrimaryButton(onClick = onClick, modifier = modifier.height(48.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(48.dp)) { Text(label) }
    }
}

@Composable
private fun RangeChoice(range: ConsolidateRange, onRangeChange: (ConsolidateRange) -> Unit) {
    Column {
        ConsolidateRange.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = range == option,
                        role = Role.RadioButton,
                        onClick = { onRangeChange(option) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = range == option, onClick = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (option == ConsolidateRange.LAST_90_DAYS) "最近 90 天" else "全部",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun RunningContent(task: Running, semanticIndexing: Boolean, onCancel: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = importPhaseText(task.phase, semanticIndexing),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(12.dp))
    if (task.total > 0) {
        LinearProgressIndicator(
            progress = { (task.done.toFloat() / task.total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = if (task.phase == Running.Phase.IMPORT) {
            "${task.done} / ${task.total} 段"
        } else {
            "${task.done} / ${task.total}"
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "已写入 ${task.messages} 条",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text("停下")
    }
}

@Composable
private fun AwaitingConsentContent(task: AwaitingConsent) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = "已经导入 ${task.summary.sessionsImported} 段 · ${task.summary.messagesInserted} 条。",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "整理成记忆要花一点钱，先问你一句。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DoneContent(
    state: DeepSeekImportUiState,
    task: Done,
    onOpenChat: (String) -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
) {
    val summary = task.summary
    var showReasons by remember { mutableStateOf(false) }
    val failedGroups = failedImportGroupNotices(task.batch, state.importGroups)

    Spacer(Modifier.height(8.dp))
    Text(
        text = "已导入 ${summary.sessionsImported} 段 · ${summary.messagesInserted} 条",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    // 三类计数分开说：之前导过的（没重复）与读不出来的，只在真的有数时出现。
    if (summary.sessionsSkippedExisting > 0) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "之前导过 ${summary.sessionsSkippedExisting} 段，这次没重复",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (summary.sessionsFailed > 0) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "未完成 ${summary.sessionsFailed} 段",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 只展示原因码，不展示正文/标题（§2 隐私收敛）。
        val reasons = summary.issues.groupingBy { it.code }.eachCount()
            .toList()
            .sortedByDescending { it.second }
        if (reasons.isNotEmpty()) {
            TextButton(
                onClick = { showReasons = !showReasons },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (showReasons) "收起原因" else "看看是什么原因")
            }
            if (showReasons) {
                reasons.forEach { (code, count) ->
                    Text(
                        text = "${importErrorText(code)} × $count",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (failedGroups.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("失败的分组", style = MaterialTheme.typography.titleSmall)
        failedGroups.forEach { failed ->
            Text(
                text = "${failed.groupName}：${failed.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "没成功的分组已自动恢复原样，不会留下半截聊天；已完成的分组仍保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = FAILED_GROUP_RETRY_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (!task.indexed) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "聊天已保存，但部分旧对话还没处理完，暂时只能按原话找。可以到记忆面板继续处理。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 「整理」相关提示：0 片时明确说没有可整理的旧事，不要再引导去面板空转。
    consolidationDoneHint(task.consolidation, summary.messagesInserted)?.let { hint ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    state.undoError?.let {
        Spacer(Modifier.height(12.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (summary.characterId.isNotBlank()) {
        Spacer(Modifier.height(20.dp))
        PulsezPrimaryButton(
            onClick = { onOpenChat(summary.characterId) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("打开 TA")
        }
        Spacer(Modifier.height(8.dp))
    }
    // 撤销只对「本次新建的 TA」提供（review）：
    // 续导复用旧 TA 时整批撤销会把之前几次导入的历史一起隐藏，所以不显示按钮，只解释一句。
    if (canUndoImport(summary)) {
        OutlinedButton(
            onClick = onUndo,
            enabled = !state.undoBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(if (state.undoBusy) "正在撤销……" else "撤销这次导入")
        }
    } else {
        Text(
            text = undoUnavailableHint(summary).orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
    TextButton(
        onClick = onDone,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text("完成")
    }
}

@Composable
private fun FailedContent(task: Failed, onRetry: () -> Unit, onDone: () -> Unit) {
    val cancelled = task.code == ImportErrorCodes.CANCELLED
    Spacer(Modifier.height(8.dp))
    Text(
        text = importErrorText(task.code),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(24.dp))
    if (!cancelled) {
        PulsezPrimaryButton(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("再试一次")
        }
        Spacer(Modifier.height(8.dp))
    }
    TextButton(
        onClick = onDone,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text("完成")
    }
}

// ------------------------------------------------------------------ 小组件

@Composable
private fun ImportTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 顶栏标题：状态说人话，不暴露「预检/整理任务」之类的内部词。 */
private fun taskTitle(task: ImportTaskState): String = when (task) {
    Idle, is Inspecting -> "旧聊天"
    is Ready -> "找到 ${task.inspect.sessions} 段对话"
    is Running -> "正在导入"
    is AwaitingConsent -> "正在导入"
    is Done -> "导入完了"
    is Failed -> if (task.code == ImportErrorCodes.CANCELLED) "已停止" else "没导成"
}
