package cc.pulsez.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 清空结束后的落点决策。
 *
 * `AppRoot` 里不方便做 Compose UI 测试，所以把「成功 → 回首页 / 失败 → 显示失败对话框」
 * 抽成纯函数单独锁住；重试语义用「同一函数再次求值」表达（失败的「再试一次」重跑同一个
 * 执行 lambda，结果再走一次 [clearOutcomeAfter]）。
 */
class ClearFlowTest {

    @Test
    fun successReturnsHome() {
        assertEquals(ClearOutcome.RETURN_HOME, clearOutcomeAfter(success = true))
    }

    @Test
    fun failureShowsFailureDialog() {
        assertEquals(
            "失败不许静默回首页，必须显示「没清干净」",
            ClearOutcome.SHOW_FAILURE,
            clearOutcomeAfter(success = false),
        )
    }

    @Test
    fun retryAfterFailureThenSuccessReturnsHome() {
        // 第一次失败 → 失败对话框；用户点「再试一次」后成功 → 回首页。
        assertEquals(ClearOutcome.SHOW_FAILURE, clearOutcomeAfter(success = false))
        assertEquals(ClearOutcome.RETURN_HOME, clearOutcomeAfter(success = true))
    }

    @Test
    fun retryAfterFailureFailsAgainKeepsFailureDialog() {
        // 重试也失败：继续显示失败对话框（可无限重试；不冒充成功）。
        assertEquals(ClearOutcome.SHOW_FAILURE, clearOutcomeAfter(success = false))
        assertEquals(ClearOutcome.SHOW_FAILURE, clearOutcomeAfter(success = false))
    }

    @Test
    fun onlyTwoOutcomesExist() {
        assertEquals(
            "落点只有两种：回首页 / 显示失败对话框",
            listOf(ClearOutcome.RETURN_HOME, ClearOutcome.SHOW_FAILURE),
            ClearOutcome.entries.toList(),
        )
    }
}
