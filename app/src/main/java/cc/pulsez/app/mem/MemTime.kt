package cc.pulsez.app.mem

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 记忆时间工具：统一 epochMillis 内部表示（02 §1.6），解析/格式化只在本文件内。
 *
 * 对应文档：02 §1.6（所有时间用 epochMillis）、03 §5.1（记忆表用 `YYYY-MM-DD`）。
 *
 * **时区口径**（v0.4）：解析与展示**统一按设备时区**
 * （[ZoneId.systemDefault]）解释，不再按 UTC。原因是导入侧 [cc.pulsez.app.importer.TextParser]
 * 一直按系统时区解析聊天记录里的 `YYYY-MM-DD HH:mm`，两边口径不一致会让导入的日期
 * 在抽取 prompt / 记忆面板里偏移一天（例如 UTC+8 的晚上 21:03 在 UTC 口径下变成次日）。
 *
 * 边界：
 * - epochMillis 仍是唯一内部表示（DB / 模型 JSON 里的数字不变）；
 * - **已有数据不迁移**：旧数据是按旧口径解释出来的 epoch，无法可靠还原用户当初的本地时间；
 *   强行迁移会把本来正确的日期改错（不破坏用户数据）。
 *
 * [zone] 只给单测注入时区用，生产调用一律不传；默认值在**每次调用时**读取当前系统时区，
 * 这样单测里的 `TimeZone.setDefault(...)` 能立即生效（若缓存成 val，同 JVM 内第二个测试会拿到旧时区）。
 */
internal object MemTime {

    private val DATE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * `formatDateTime` 的输出格式（空格分隔，秒可省略）必须能被 [parse] 读回。
     *
     * JDK 的 [DateTimeFormatter.ISO_LOCAL_DATE_TIME] 只接受 `T` 分隔（实测：`2026-11-18 21:03`
     * 在第 10 位解析失败），所以单独给"空格分隔"一支，保证 `parse(formatDateTime(x)) == x`。
     */
    private val SPACE_DATE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]", Locale.ROOT)

    /**
     * `YYYY-MM-DD` / `YYYY-MM-DD HH:mm[:ss]` / ISO 字符串 → epochMillis；非法返回 null。
     *
     * 数字仍是秒/毫秒兼容（见 [normalizeEpoch]）；带 `Z` / 偏移量的 ISO 字符串按绝对时刻解析，
     * 与设备时区无关。
     */
    fun parse(value: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return normalizeEpoch(it) }
        runCatching {
            return LocalDate.parse(text).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        runCatching {
            return LocalDateTime.parse(text, SPACE_DATE_TIME_FORMATTER).atZone(zone).toInstant()
                .toEpochMilli()
        }
        runCatching {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zone)
                .toInstant().toEpochMilli()
        }
        runCatching {
            return Instant.parse(text).toEpochMilli()
        }
        return null
    }

    /** epochMillis → `YYYY-MM-DD`（设备时区）；0 或负值返回 null。 */
    fun formatDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (epochMillis <= 0L) return null
        return runCatching {
            Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()
        }.getOrNull()
    }

    /** epochMillis → `YYYY-MM-DD HH:mm`（设备时区）；0 或负值返回 null。 */
    fun formatDateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (epochMillis <= 0L) return null
        return runCatching {
            Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()
                .format(DATE_TIME_FORMATTER)
        }.getOrNull()
    }

    /** 兼容秒级时间戳（LLM 偶尔给 10 位秒），统一成毫秒。 */
    fun normalizeEpoch(value: Long): Long = when {
        value <= 0L -> 0L
        value < 100_000_000_000L -> value * 1_000L // 小于 ~1973 年的毫秒值，当成秒
        else -> value
    }
}
