package cc.pulsez.app.update

/**
 * 一次检查的结果（内部返回值；UI 状态见 [UpdateUiState]）。
 *
 * 结果类型是 Available(manifest)、UpToDate 或 FailedSilent。
 * 本实现把 `Skipped` 留在仓库内部判断，不暴露给 UI：当前没有总开关或门槛网关，
 * 不需要让 UI 知道「为什么没查」。
 *
 * v0.5.2 UI 状态新增 [UpdateUiState.Ignored]（「已跳过」），但**不**扩展本接口——
 * 「已跳过」是 UI 状态事实，不是一次检查的结果；`checkNow()` 对忽略版本仍如实返回
 * [Available]。
 */
sealed interface UpdateCheckResult {

    /** 发布侧有比当前安装版本更新的版本（`manifest.versionCode > 当前`）。 */
    data class Available(val manifest: VersionManifest) : UpdateCheckResult

    /** 已是最新（等于或小于当前版本；回滚指针不降级）。 */
    data class UpToDate(val checkedAt: Long) : UpdateCheckResult

    /** 所有来源都失败/清单非法：**静默**（自动检查不打扰；手动检查给一句人话）。 */
    data object FailedSilent : UpdateCheckResult
}

/**
 * 进程内「检查更新」的 UI 状态（设置页顶部行与启动提示都读它）。
 *
 * 六种：Idle / Checking / UpToDate(lastCheckedAt) / Available(manifest) / Ignored(manifest, checkedAt) /
 * ManualFailed(message)。
 *
 * 约定：
 * - 启动自动检查**不把状态置成 Checking**（静默，不闪一行「正在看」）；
 * - 自动检查失败**不改状态**（完全静默）；
 * - [ManualFailed] 只由用户手动点「检查更新」失败时产生，message 是一句人话
 *   （[UpdateConfig.MANUAL_FAILED_MESSAGE]），UI 当行下说明显示，不弹窗。
 * - [UpToDate] 只用于「确实没有更新的版本」；用户点过「稍后」的新版本必须进 [Ignored]，
 *   **不得**回落成 [UpToDate]（那是对用户谎报）。
 */
sealed interface UpdateUiState {

    /** 本次进程还没查过（含「从来没查过」和「查过但现在还没结果」）。 */
    data object Idle : UpdateUiState

    /** 手动检查进行中（行不可重复点）。 */
    data object Checking : UpdateUiState

    /** 已是最新；[checkedAt] 是这次检查的时间戳（epoch millis）。只用于确实没有更新的版本。 */
    data class UpToDate(val checkedAt: Long) : UpdateUiState

    /** 有新版本（且未被用户「稍后」跳过，或用户手动又查了一次）。 */
    data class Available(val manifest: VersionManifest) : UpdateUiState

    /**
     * 有新版本，但用户点了「稍后」：版本事实必须如实保留，不得回落成「已是最新」。
     *
     * 此前把「被跳过」显示为「已是最新」的处理已调整。
     * @property manifest 被跳过的那个新版本清单（行值要显示版本名，手动再查仍可打开对话框）
     * @property checkedAt 进入该状态的时间戳（epoch millis）
     */
    data class Ignored(val manifest: VersionManifest, val checkedAt: Long) : UpdateUiState

    /** 手动检查失败：一句人话，不弹窗、不拦截任何操作。 */
    data class ManualFailed(val message: String) : UpdateUiState
}
