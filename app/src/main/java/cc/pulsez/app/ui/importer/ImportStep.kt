package cc.pulsez.app.ui.importer

import cc.pulsez.app.importer.BackupInspection
import cc.pulsez.app.importer.Speaker
import cc.pulsez.app.ui.copy.BackupCopy

/**
 * 导入页的步骤判定与纯状态转换（v0.5.2 前半）。
 *
 * 为什么抽成纯函数：`ImportScreen` 的条件分支和 `AppRoot` 里的状态回调没法在 JVM 单测里跑
 * （工程没有 Compose UI 测试依赖），把「现在是哪一步 / 切过去要清哪些字段」抽出来，
 * 就能用纯 JVM 测试锁住两态互斥、raw 语义与退出清理，UI 层只负责按结果渲染。
 *
 * 互斥不变量：`backup` 与 `backupRejected` **永不同时非空**；`parsed` 与备份两态也互斥。
 * 备份原文任何时刻都不得进 `raw`（草稿输入框）。
 */
internal enum class ImportStep { EDIT, PARSE_PREVIEW, BACKUP_PREVIEW, BACKUP_REJECTED }

/** 当前步骤：备份预览 > 拒绝态 > 解析预览 > 初始编辑步。 */
internal fun importStep(state: ImportUiState): ImportStep = when {
    state.backup != null -> ImportStep.BACKUP_PREVIEW
    state.backupRejected != null -> ImportStep.BACKUP_REJECTED
    state.parsed != null -> ImportStep.PARSE_PREVIEW
    else -> ImportStep.EDIT
}

/** 页头标题：四态各一句（备份两态取 `BackupCopy` 常量 可继续复用）。*/
internal fun importTitle(state: ImportUiState): String = when (importStep(state)) {
    ImportStep.EDIT -> "新建角色"
    ImportStep.PARSE_PREVIEW -> "看看对不对"
    ImportStep.BACKUP_PREVIEW -> BackupCopy.PREVIEW_TITLE
    ImportStep.BACKUP_REJECTED -> BackupCopy.REJECT_TITLE
}

/**
 * 把一次识别结果应用成纯状态转移。
 *
 * - [BackupInspection.Preview]：进备份预览；清 `backupRejected` 与旧的解析草稿/错误；
 * - [BackupInspection.Rejected]：进拒绝态；清 `backup` 与旧的解析草稿/错误；
 * - [BackupInspection.Normal]：退回普通解析路径；**两个备份字段都清**（普通文本不得残留旧预览）。
 *
 * 备份两态都显式 `raw = ""`（禁止备份原文进输入框，且不是「≤200 字摘要」）。
 */
internal fun applyInspection(state: ImportUiState, result: BackupInspection): ImportUiState = when (result) {
    is BackupInspection.Preview -> state.copy(
        raw = "",
        parsed = null,
        backup = result.preview,
        backupRejected = null,
        name = "",
        persona = "",
        speakerSide = Speaker.UNKNOWN,
        warnings = emptyList(),
        error = null,
    )

    is BackupInspection.Rejected -> state.copy(
        raw = "",
        parsed = null,
        backup = null,
        backupRejected = result.issue,
        name = "",
        persona = "",
        speakerSide = Speaker.UNKNOWN,
        warnings = emptyList(),
        error = null,
    )

    BackupInspection.Normal -> state.copy(backup = null, backupRejected = null)
}

/**
 * 回初始编辑步（备份两态的「取消 / 换一个文件」与 的恢复后复位都走它）。
 *
 * 除清草稿与备份两态外，**同时 `busy=false`、`createdId=null`**：否则 `AppRoot` 的
 * `LaunchedEffect(state.createdId)` 可能拿旧 id 重新导航（`AppRoot` 的创建完成跳转）。
 */
internal fun clearedForEdit(state: ImportUiState): ImportUiState = state.copy(
    raw = "",
    parsed = null,
    backup = null,
    backupRejected = null,
    name = "",
    persona = "",
    speakerSide = Speaker.UNKNOWN,
    warnings = emptyList(),
    busy = false,
    createdId = null,
    error = null,
)
