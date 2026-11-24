package cc.pulsez.app.compliance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageClockTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun `前台不足两小时不提醒`() {
        val clock = UsageClock()
        clock.onForeground(0)
        assertFalse(clock.shouldRemind(101 * 60 * 1000L))
    }

    @Test
    fun `前台满两小时提醒一次`() {
        val clock = UsageClock()
        clock.onForeground(0)
        assertTrue(clock.shouldRemind(2 * hour + 1))
        assertFalse(clock.shouldRemind(2 * hour + 1000))
    }

    @Test
    fun `后台时间不计入`() {
        val clock = UsageClock()
        clock.onForeground(0)
        clock.onBackground(1 * hour)
        clock.onForeground(10 * hour)
        // 前 1 小时 + 恢复后的 1 小时 = 2 小时；中间的 9 小时后台不计
        assertEquals(2 * hour, clock.total(11 * hour))
    }

    @Test
    fun `累计满四小时再次提醒`() {
        val clock = UsageClock()
        clock.onForeground(0)
        assertTrue(clock.shouldRemind(2 * hour))
        clock.onBackground(2 * hour)
        clock.onForeground(2 * hour)
        assertTrue(clock.shouldRemind(2 * hour + 2 * hour))
    }
}
