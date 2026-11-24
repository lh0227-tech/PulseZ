package cc.pulsez.app.core

import cc.pulsez.app.data.repo.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日/月花费上限在发送链路中的纯逻辑判定。
 *
 * 设置页能填上限，但发送前完全不校验（0.1.x 遗留），超了照发。
 * 语义按：0 = 不限制；`>=` 上限即到顶、下一句停止发起请求。
 */
class UsageLimitsTest {

    private fun snapshot(today: Double, month: Double): UsageSnapshot = UsageSnapshot(
        todayYuan = today,
        monthYuan = month,
        todayMessages = 3,
        monthMessages = 9,
        savedRatio = 0.5,
    )

    @Test
    fun underBothLimitsIsNotBlocked() {
        val value = snapshot(today = 0.50, month = 2.00)
        assertFalse(UsageLimits.isOverLimit(value, dailyLimitYuanX100 = 100, monthlyLimitYuanX100 = 500))
        assertNull(UsageLimits.message(value, dailyLimitYuanX100 = 100, monthlyLimitYuanX100 = 500))
    }

    @Test
    fun dailyLimitReachedBlocksNewRequests() {
        val atLimit = snapshot(today = 1.00, month = 1.00)
        assertTrue("用到 100% 就停发", UsageLimits.isOverLimit(atLimit, 100, 0))

        val over = snapshot(today = 1.20, month = 1.20)
        assertTrue(UsageLimits.isOverLimit(over, 100, 0))
        assertEquals(UsageLimits.DAILY_LIMIT_MESSAGE, UsageLimits.message(over, 100, 0))
        assertTrue(UsageLimits.DAILY_LIMIT_MESSAGE.contains("把上限调高"))
    }

    @Test
    fun monthlyLimitReachedBlocksNewRequests() {
        val over = snapshot(today = 0.20, month = 30.00)
        assertTrue(UsageLimits.isOverLimit(over, dailyLimitYuanX100 = 0, monthlyLimitYuanX100 = 3_000))
        assertEquals(UsageLimits.MONTHLY_LIMIT_MESSAGE, UsageLimits.message(over, 0, 3_000))
        assertTrue(UsageLimits.MONTHLY_LIMIT_MESSAGE.contains("把上限调高"))
    }

    @Test
    fun zeroMeansUnlimited() {
        val huge = snapshot(today = 999.0, month = 9999.0)
        assertFalse(UsageLimits.isOverLimit(huge, dailyLimitYuanX100 = 0, monthlyLimitYuanX100 = 0))
        assertNull(UsageLimits.message(huge, 0, 0))

        // 日上限为 0（不限）但月上限生效时，只有月上限拦
        assertTrue(UsageLimits.isOverLimit(huge, dailyLimitYuanX100 = 0, monthlyLimitYuanX100 = 100))
        assertEquals(UsageLimits.MONTHLY_LIMIT_MESSAGE, UsageLimits.message(huge, 0, 100))
    }

    @Test
    fun dailyLimitTakesPriorityWhenBothExceeded() {
        val over = snapshot(today = 5.00, month = 50.00)
        assertEquals(
            "日上限先提示，用户更关心今天还能不能聊",
            UsageLimits.DAILY_LIMIT_MESSAGE,
            UsageLimits.message(over, dailyLimitYuanX100 = 100, monthlyLimitYuanX100 = 1_000),
        )
    }

    @Test
    fun raisingLimitTakesEffectImmediately() {
        val over = snapshot(today = 1.50, month = 1.50)

        assertTrue("旧上限下被拦", UsageLimits.isOverLimit(over, dailyLimitYuanX100 = 100, monthlyLimitYuanX100 = 0))
        // 用户把日上限调到 5 元：同一份快照下一次判定立即放行（不缓存）
        assertFalse("调高后立即生效", UsageLimits.isOverLimit(over, dailyLimitYuanX100 = 500, monthlyLimitYuanX100 = 0))
        assertNull(UsageLimits.message(over, dailyLimitYuanX100 = 500, monthlyLimitYuanX100 = 0))
    }

    @Test
    fun justBelowLimitIsAllowed() {
        val value = snapshot(today = 0.99, month = 0.99)
        assertFalse(UsageLimits.isOverLimit(value, 100, 100))
        assertNull(UsageLimits.message(value, 100, 100))
    }
}
