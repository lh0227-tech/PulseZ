package cc.pulsez.app.ui

/**
 * 「清空所有数据」的落点决策（v0.5.2，`）。
 *
 * 为什么抽成纯函数：`AppRoot` 里的清空按钮回调是 Compose 组合的一部分，既有测试设施不跑
 * Compose UI 测试；把「成功 → 回首页 / 失败 → 显示失败对话框」抽成纯函数，就能在 JVM 单测里
 * 锁住两个分支，UI 层只负责按返回值做事（不许复制两份逻辑）。
 *
 * 重试语义：失败对话框的「再试一次」重新执行**同一个**清空 lambda，本次结果同样交给
 * [clearOutcomeAfter] 决定落点；重试成功 → [ClearOutcome.RETURN_HOME]（回首页）。
 */
internal enum class ClearOutcome {

    /** 清空成功：清掉导航栈回首页（`nav.reset(Route.Threads)`）。 */
    RETURN_HOME,

    /** 清空失败：保持当前页，显示「没清干净」对话框（不得静默回首页、不得声称已全清）。 */
    SHOW_FAILURE,
}

/**
 * 清空结束后的落点。
 *
 * @param success `ExportService.clearAll` 是否成功（调用方用 `runCatching { … }.isSuccess`
 *   取值；失败原因不需要在这里分支——用户看到的只有「没清干净 + 再试一次」）
 */
internal fun clearOutcomeAfter(success: Boolean): ClearOutcome =
    if (success) ClearOutcome.RETURN_HOME else ClearOutcome.SHOW_FAILURE
