package cc.pulsez.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * [LatestReminder] 格式化契约测试（纯 JVM）。
 *
 * DeepSeek protocol documentation: `https://api-docs.deepseek.com/guides/latest_reminder/`
 * 严格 5 段 `<date>,<weekday>,<location>,<platform>,<language>`，无时分秒、`location` 留空。
 */
class LatestReminderTest {

    @Test
    fun formatsExactFiveSegmentLayoutWithEmptyLocationAndNoTime() {
        val epoch = Instant.parse("2026-11-25T06:00:00Z").toEpochMilli()
        val formatted = LatestReminder.format(epoch, ZoneId.of("Asia/Shanghai"))

        assertEquals("2026-11-25,星期三,,App,中文", formatted)
        val segments = formatted.split(',')
        assertEquals(5, segments.size)
        assertEquals("2026-11-25", segments[0])
        assertEquals("星期三", segments[1])
        assertEquals("", segments[2])
        assertEquals("App", segments[3])
        assertEquals("中文", segments[4])
        assertFalse("不能包含时分秒冒号", formatted.contains(':'))
    }

    @Test
    fun formatsAllSevenWeekdaysInChinese() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val expected = listOf(
            "2026-11-23" to "星期一",
            "2026-11-24" to "星期二",
            "2026-11-25" to "星期三",
            "2026-11-26" to "星期四",
            "2026-11-27" to "星期五",
            "2026-11-28" to "星期六",
            "2026-11-29" to "星期日",
        )
        for ((date, weekday) in expected) {
            val epoch = Instant.parse("${date}T12:00:00+08:00").toEpochMilli()
            assertEquals("$date,$weekday,,App,中文", LatestReminder.format(epoch, shanghai))
        }
    }

    @Test
    fun respectsZoneIdAcrossUtcDayBoundary() {
        // UTC 2026-11-25 20:00 == Asia/Shanghai 2026-11-26 04:00
        val epoch = Instant.parse("2026-11-25T20:00:00Z").toEpochMilli()
        assertEquals("2026-11-25,星期三,,App,中文", LatestReminder.format(epoch, ZoneId.of("UTC")))
        assertEquals("2026-11-26,星期四,,App,中文", LatestReminder.format(epoch, ZoneId.of("Asia/Shanghai")))
    }
}
