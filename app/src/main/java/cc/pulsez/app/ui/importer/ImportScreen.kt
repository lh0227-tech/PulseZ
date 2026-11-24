package cc.pulsez.app.ui.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.pulsez.app.importer.BackupIssue
import cc.pulsez.app.importer.BackupPreview
import cc.pulsez.app.importer.Speaker
import cc.pulsez.app.ui.components.PulsezPrimaryButton
import cc.pulsez.app.ui.copy.BackupCopy

/**
 * 角色导入。
 * 三种入口：粘贴、选文件、从分享进来。链接与"自己写"在 后段补。
 *
 * v0.5.2：新增**备份预览 / 拒绝**两态——备份会被明确识别、**绝不降级成人设草稿**；
 * 起预览态可以**真的恢复**（预览 → 执行 → 结果三段），拒绝态仍只有「换一个文件 / 取消」
 * 两个出口。分支判定全部走 `importStep(state)`，标题走 `importTitle(state)`，便于纯 JVM 测试。
 */
@Composable
fun ImportScreen(
    state: ImportUiState,
    onBack: () -> Unit,
    onRawChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onParse: () -> Unit,
    onNameChange: (String) -> Unit,
    onPersonaChange: (String) -> Unit,
    onChooseSpeaker: (Speaker) -> Unit,
    onCloneLike: () -> Unit,
    onCreate: () -> Unit,
    // 旧聊天（DeepSeek 导出 ZIP）入口：独立页面，复用 的导入任务状态机。
    onOpenZipImport: () -> Unit,
    // 备份两态的独立出口：换文件 = 先回初始步再选；取消 = 回初始步。
    onPickAnotherFile: () -> Unit,
    onCancelBackup: () -> Unit,
    // 恢复：主按钮「恢复」/失败「再试一次」；结果页「好」→ 回首页。
    onRestore: () -> Unit,
    onRestoreFinished: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
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
                text = importTitle(state),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            when (importStep(state)) {
                ImportStep.EDIT -> EditStep(
                    raw = state.raw,
                    onRawChange = onRawChange,
                    onPickFile = onPickFile,
                    onParse = onParse,
                    error = state.error,
                    onOpenZipImport = onOpenZipImport,
                )

                ImportStep.PARSE_PREVIEW -> PreviewStep(
                    state = state,
                    onNameChange = onNameChange,
                    onPersonaChange = onPersonaChange,
                    onChooseSpeaker = onChooseSpeaker,
                    onCloneLike = onCloneLike,
                    onCreate = onCreate,
                )

                ImportStep.BACKUP_PREVIEW -> state.backup?.let { preview ->
                    BackupPreviewStep(
                        state = state,
                        preview = preview,
                        onRestore = onRestore,
                        onRestoreFinished = onRestoreFinished,
                        onCancelBackup = onCancelBackup,
                    )
                }

                ImportStep.BACKUP_REJECTED -> state.backupRejected?.let { issue ->
                    BackupRejectedStep(
                        issue = issue,
                        onPickAnotherFile = onPickAnotherFile,
                        onCancelBackup = onCancelBackup,
                    )
                }
            }
        }
    }
}

/**
 * 备份预览区块（预览 → 执行 → 结果三段 I / §6.4.4）。
 *
 * 预览态（`state.restore == null`）**永远**展示「不会恢复」清单：这是产品的如实承诺，
 * 不是可选提示。执行中禁用「恢复/取消」但保留位置（避免布局跳动），页头返回与系统返回
 * 始终可用（系统返回手势必须始终可用）——恢复在 VM scope 里跑完。
 *
 * 区块边界说明：本函数体同时承载预览/执行/结果三段的全部文案与按钮，源码扫描测试
 * （`BackupRestoreCopyTest`）按本函数体边界断言「文案真的被用上，而不是只定义」。
 * 拒绝态仍走 [BackupRejectedStep]（「换一个文件 / 取消」），不在本函数里。
 */
@Composable
private fun BackupPreviewStep(
    state: ImportUiState,
    preview: BackupPreview,
    onRestore: () -> Unit,
    onRestoreFinished: () -> Unit,
    onCancelBackup: () -> Unit,
) {
    when (val restore = state.restore) {
        // ---------------- 预览态：规模 + 导出时间 + 跳过/附件提示 + 「不会恢复」清单 + 恢复/取消
        null -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = BackupCopy.previewCounts(preview),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = BackupCopy.exportedAtText(preview.exportedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (preview.skippedOrphanThreads > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = BackupCopy.previewSkippedOrphans(preview.skippedOrphanThreads),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preview.attachmentCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = BackupCopy.previewFilesNotRestored(preview.attachmentCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = BackupCopy.PREVIEW_READY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = BackupCopy.NOT_RESTORED_TITLE,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            listOf(
                BackupCopy.NOT_RESTORED_AVATAR,
                BackupCopy.NOT_RESTORED_FILES,
                BackupCopy.NOT_RESTORED_SEMANTIC,
                BackupCopy.NOT_RESTORED_DELETED,
                BackupCopy.NOT_RESTORED_RELATION,
                BackupCopy.NOT_RESTORED_USAGE,
                BackupCopy.NOT_RESTORED_SETTINGS,
                BackupCopy.NOT_RESTORED_MEMORY_SOURCE,
            ).forEach { item ->
                Text(
                    text = "· $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = BackupCopy.RESTORE_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PulsezPrimaryButton(
                    onClick = onRestore,
                    enabled = !state.busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text(BackupCopy.RESTORE) }
                OutlinedButton(
                    onClick = onCancelBackup,
                    enabled = !state.busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text(BackupCopy.CANCEL) }
            }
        }

        // ---------------- 执行中：确定性进度（total>0 才有）+ 主/次按钮都禁用
        is RestoreUi.Running -> {
            Spacer(Modifier.height(8.dp))
            if (restore.total > 0) {
                LinearProgressIndicator(
                    progress = { restore.done.toFloat() / restore.total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = BackupCopy.restoreProgress(restore.done, restore.total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PulsezPrimaryButton(
                    onClick = onRestore,
                    enabled = false,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text(BackupCopy.RESTORE) }
                OutlinedButton(
                    onClick = onCancelBackup,
                    enabled = !state.busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text(BackupCopy.CANCEL) }
            }
        }

        // ---------------- 结果成功：计数 + 跳过/忽略/附件/索引逐条（n>0 才出现）+「好」
        is RestoreUi.Done -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = BackupCopy.RESTORE_DONE_TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            val result = restore.result
            val empty = result.characterCount == 0 && result.threadCount == 0 &&
                result.messageCount == 0 && result.memoryCount == 0
            Text(
                text = if (empty) BackupCopy.RESTORE_DONE_EMPTY else BackupCopy.restoreDoneCounts(result),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            val ignoredRefs = result.ignoredMembers + result.ignoredSpeakers + result.ignoredMemories
            if (result.skippedOrphanThreads > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = BackupCopy.restoreSkippedOrphans(result.skippedOrphanThreads),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ignoredRefs > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = BackupCopy.restoreIgnoredRefs(ignoredRefs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.attachmentCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = BackupCopy.restoreFilesNotRestored(result.attachmentCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.indexSkipped > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = BackupCopy.restoreIndexMissing(result.indexSkipped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
            PulsezPrimaryButton(
                onClick = onRestoreFinished,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) { Text(BackupCopy.RESTORE_OK) }
        }

        // ---------------- 结果失败：明确「原来的数据没有动」+ 再试一次/取消
        RestoreUi.Failed -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = BackupCopy.RESTORE_FAILED,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PulsezPrimaryButton(
                    onClick = onRestore,
                    enabled = !state.busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text(BackupCopy.RESTORE_RETRY) }
                OutlinedButton(
                    onClick = onCancelBackup,
                    enabled = !state.busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) { Text(BackupCopy.CANCEL) }
            }
        }
    }
    Spacer(Modifier.height(40.dp))
}

/**
 * 备份拒绝区块：确定性原因 + 同样两个出口（**不提供「当成人设」的出路**）。
 *
 * 依据 禁止项 1——形状命中的备份坏了就是 `Rejected`，绝不降级。
 */
@Composable
private fun BackupRejectedStep(
    issue: BackupIssue,
    onPickAnotherFile: () -> Unit,
    onCancelBackup: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = BackupCopy.issueText(issue),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onPickAnotherFile,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
        ) { Text(BackupCopy.PICK_ANOTHER) }
        OutlinedButton(
            onClick = onCancelBackup,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
        ) { Text(BackupCopy.CANCEL) }
    }
    Spacer(Modifier.height(40.dp))
}

@Composable
private fun EditStep(
    raw: String,
    onRawChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onParse: () -> Unit,
    error: String?,
    onOpenZipImport: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text(
        // 不写"交给我/交过来"这种承诺口吻，只描述操作与结果。
        text = "一段人设、一份聊天记录，都能变成一个 TA。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = raw,
        onValueChange = onRawChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        label = { Text("粘贴在这里") },
        placeholder = { Text("从别处复制过来，直接粘贴") },
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onPickFile, modifier = Modifier.weight(1f)) {
            Text("导入文件")
        }
        OutlinedButton(onClick = onParse, modifier = Modifier.weight(1f)) {
            Text("读一读内容")
        }
    }
    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    // 旧聊天（ZIP）：与「导入文件（txt/md/json）」不是一回事，单独一行 + 一行说明，
    // 避免用户把 DeepSeek 导出的包当角色卡选进来（「入口」）。
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onOpenZipImport,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text("旧聊天（ZIP）")
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "从 DeepSeek 导出的聊天记录",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "支持角色卡、文本聊天记录，也可以选之前导出的 PulseZ 备份来恢复。认不出格式时，会按普通文字当作角色介绍。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(40.dp))
}

@Composable
private fun PreviewStep(
    state: ImportUiState,
    onNameChange: (String) -> Unit,
    onPersonaChange: (String) -> Unit,
    onChooseSpeaker: (Speaker) -> Unit,
    onCloneLike: () -> Unit,
    onCreate: () -> Unit,
) {
    val parsed = state.parsed ?: return
    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = state.name,
        onValueChange = onNameChange,
        label = { Text("叫什么名字") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))

    if (parsed.turns.size >= 4) {
        Text(
            text = "这段对话里，哪一方是 TA？",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onChooseSpeaker(Speaker.USER) },
                modifier = Modifier.weight(1f),
            ) { Text("原来标成「我」的那一方") }
            OutlinedButton(
                onClick = { onChooseSpeaker(Speaker.CHARACTER) },
                modifier = Modifier.weight(1f),
            ) { Text("原来标成「TA」的那一方") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "现在选的是：${if (state.speakerSide == Speaker.CHARACTER) "原来标成「TA」的那一方" else "原来标成「我」的那一方"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }

    OutlinedTextField(
        value = state.persona,
        onValueChange = onPersonaChange,
        label = { Text("TA 是谁") },
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "这一段可以随便改。TA 说话的样子、你们的关系，都从这里开始。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (parsed.turns.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "识别到 ${parsed.turns.size} 条对话。这些会变成 TA 的记忆。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.warnings.forEach { warning ->
        Spacer(Modifier.height(6.dp))
        Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    Spacer(Modifier.height(24.dp))
    PulsezPrimaryButton(
        onClick = onCreate,
        enabled = !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text(if (state.busy) "正在创建……" else "创建角色")
    }
    // 快速克隆入口：将当前人设作为草稿，再创建一个角色。
    TextButton(
        onClick = onCloneLike,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        Text("再建一个像 TA 的", style = MaterialTheme.typography.labelLarge)
    }
    Spacer(Modifier.height(40.dp))
}
