package cc.pulsez.app.mem

import cc.pulsez.app.importer.TextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 时间口径统一：
 * `MemTime` 解析/展示统一按**设备时区**，并与导入侧 `TextParser` 互证。
 *
 * 测试用 `TimeZone.setDefault(...)` 固定设备时区、`finally` 恢复原值；
 * 断言的是"同一时刻在不同设备时区下的解释"，不是某台机器的偶然现象。
 */
class MemTimeZoneTest {

    private fun <T> withDefaultZone(zoneId: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun epochAt(zoned: ZoneId, text: String): Long =
        LocalDateTime.parse(text.replace(' ', 'T')).atZone(zoned).toInstant().toEpochMilli()

    // ------------------------------------------------------------------
    // 1. 设备时区下解析 → 格式化往返（含跨零点）
    // ------------------------------------------------------------------

    @Test
    fun `Asia上海默认时区下日期时间往返一致`() {
        withDefaultZone("Asia/Shanghai") {
            // 晚上 21:03：旧 UTC 口径会显示成次日 05:03，这里必须原样往返。
            assertEquals("2026-11-18 21:03", MemTime.formatDateTime(MemTime.parse("2026-11-18 21:03")!!))
            // 跨零点附近的 00:30 不能变成前一天/后一天。
            assertEquals("2026-11-18 00:30", MemTime.formatDateTime(MemTime.parse("2026-11-18 00:30")!!))
            assertEquals("2026-11-18 23:59", MemTime.formatDateTime(MemTime.parse("2026-11-18 23:59")!!))
            // 只有日期：按当天 0 点解释，格式化回同一天。
            assertEquals("2026-11-18", MemTime.formatDate(MemTime.parse("2026-11-18")!!))
        }
    }

    @Test
    fun `UTC默认时区下日期时间往返一致`() {
        withDefaultZone("UTC") {
            assertEquals("2026-11-18 21:03", MemTime.formatDateTime(MemTime.parse("2026-11-18 21:03")!!))
            assertEquals("2026-11-18 00:30", MemTime.formatDateTime(MemTime.parse("2026-11-18 00:30")!!))
            assertEquals("2026-11-18", MemTime.formatDate(MemTime.parse("2026-11-18")!!))
        }
    }

    @Test
    fun `注入zone的重载与默认时区无关`() {
        val epoch = epochAt(ZoneId.of("Asia/Shanghai"), "2026-11-18 21:03")
        // 显式注入：同一 epoch 在东京/上海/UTC 下的本地表示。
        assertEquals("2026-11-18 21:03", MemTime.formatDateTime(epoch, ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-11-18 22:03", MemTime.formatDateTime(epoch, ZoneId.of("Asia/Tokyo")))
        assertEquals("2026-11-18 13:03", MemTime.formatDateTime(epoch, ZoneId.of("UTC")))
        // 解析侧注入同样成立：把"上海本地时间"读成同一 epoch。
        assertEquals(epoch, MemTime.parse("2026-11-18 21:03", ZoneId.of("Asia/Shanghai")))
    }

    // ------------------------------------------------------------------
    // 2. 带 Z 的 ISO 字符串按绝对时刻解析，不受设备时区影响
    // ------------------------------------------------------------------

    @Test
    fun `Instant串往返不受默认时区影响`() {
        val iso = "2026-11-18T21:03:00Z"
        val expected = Instant.parse(iso).toEpochMilli()

        val inShanghai = withDefaultZone("Asia/Shanghai") { MemTime.parse(iso) }
        val inUtc = withDefaultZone("UTC") { MemTime.parse(iso) }

        assertEquals("两个默认时区下解析同一 ISO 串必须得到同一 epoch", inShanghai, inUtc)
        assertEquals(expected, inShanghai)

        // 展示按设备时区：上海是次日 05:03（跨零点），UTC 是当天 21:03。
        assertEquals(
            "2026-11-19 05:03",
            withDefaultZone("Asia/Shanghai") { MemTime.formatDateTime(inShanghai!!) },
        )
        assertEquals(
            "2026-11-18 21:03",
            withDefaultZone("UTC") { MemTime.formatDateTime(inShanghai!!) },
        )
    }

    // ------------------------------------------------------------------
    // 3. 与 TextParser 互证：导入聊天记录与 MemTime 同一口径
    // ------------------------------------------------------------------

    @Test
    fun `TextParser与MemTime在设备时区下互证`() {
        withDefaultZone("Asia/Shanghai") {
            val parsed = TextParser.parse(
                "2026-11-18 21:03 你：在\n2026-11-18 21:04 TA：在",
            )
            assertEquals(2, parsed.turns.size)

            val expected = epochAt(ZoneId.of("Asia/Shanghai"), "2026-11-18 21:03")
            assertEquals("TextParser 应按设备时区（Asia/Shanghai）换算", expected, parsed.turns[0].at)
            assertEquals("MemTime 与 TextParser 必须得到同一 epoch", expected, MemTime.parse("2026-11-18 21:03"))
            // 来回格式化仍是我们读得懂的那一行。
            assertEquals("2026-11-18 21:03", MemTime.formatDateTime(parsed.turns[0].at!!))
        }
    }

    @Test
    fun `非法输入返回null`() {
        assertNull(MemTime.parse(""))
        assertNull(MemTime.parse(null))
        assertNull(MemTime.parse("这不是时间"))
        assertNull("0 或负值不展示", MemTime.formatDateTime(0L))
        assertNull("0 或负值不展示", MemTime.formatDate(-1L))
    }
}
