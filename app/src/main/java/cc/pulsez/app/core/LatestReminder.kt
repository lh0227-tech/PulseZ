package cc.pulsez.app.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * DeepSeek `latest_reminder` 场景信息格式化（[官方文档](https://api-docs.deepseek.com/guides/latest_reminder/)）。
 *
 * 格式严格为 5 段逗号分隔：`<date>,<weekday>,<location>,<platform>,<language>`。
 * - 不含小时/分钟（DeepSeek 协议不支持时刻字段，见 https://api-docs.deepseek.com/guides/latest_reminder/）；
 * - `location` 严格留空 `""`（不申请定位权限，不基于 IP 猜城市）；
 * - 平台固定 `App`，语言固定 `中文`。
 */
object LatestReminder {

    private const val PLATFORM: String = "App"
    private const val LANGUAGE: String = "中文"

    /**
     * 按给定时间戳与时区生成 `YYYY-MM-DD,星期X,,App,中文`。
     *
     * 默认使用当前时刻与设备当前时区（每次调用读取 [ZoneId.systemDefault]，与 `MemTime` 口径一致）。
     */
    fun format(
        epochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()
        val weekday = chineseWeekday(date.dayOfWeek)
        return "$date,$weekday,,$PLATFORM,$LANGUAGE"
    }

    private fun chineseWeekday(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "星期一"
        DayOfWeek.TUESDAY -> "星期二"
        DayOfWeek.WEDNESDAY -> "星期三"
        DayOfWeek.THURSDAY -> "星期四"
        DayOfWeek.FRIDAY -> "星期五"
        DayOfWeek.SATURDAY -> "星期六"
        DayOfWeek.SUNDAY -> "星期日"
    }
}
