package cc.pulsez.app.importer

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 聊天记录 → [Turn] 列表。识别规则见 。
 *
 * 支持的轮次标记（中英混排、大小写不敏感、全角冒号）：
 * `你：/我：/用户：/User:/U:` 与 `她：/他：/对方：/Assistant:/AI:/Bot:`；
 * 另外 `「」`（或 `『』`）包住的独立行也算一条，说话人记为 [Speaker.UNKNOWN]。
 *
 * 时间戳前缀：`[2026-11-18 21:03]`、`21:03`、`2026-11-18 21:03:`。
 * 只有时分的记录换算不出日期，[Turn.at] 留 null。
 *
 * 这里不替用户判断谁是"她"，但硬规则"轮次少于 [ImportPipeline.MIN_CHAT_TURNS] 就当人设"
 * 由 [isChatLog] / [parseChatLog] 明确表达，[ImportPipeline] 也走这一条。
 */
object TextParser {

    /**
     * 解析结果。
     *
     * [preamble] 是第一条轮次之前的内容（聊天文件开头常带一段人设）；
     * [explicitSpeakerTurns] 是"有明确说话人标记"的条数——只有它大于 0 才算真的像对话，
     * 用来避免把一段带引号范文的人设误判成"对话太短"。
     */
    data class ParseResult(
        val turns: List<Turn>,
        val preamble: String,
        val explicitSpeakerTurns: Int,
    )

    /** 说什么都带冒号；`说` 可选（`她说：`/`她：` 都认）。 */
    private val SPEAKER_MARKER = Regex(
        """^(你|我|用户|她|他|TA|ta|对方|user|u|assistant|ai|bot)(?:说)?[\s\u3000]*[:：][\s\u3000]*""",
        RegexOption.IGNORE_CASE,
    )

    /** 整行只有引号：`「你今天有点安静」`。 */
    private val QUOTED_LINE = Regex("""^[「『](.+)[」』]$""")

    private val BRACKET_TS = Regex(
        """^[\[［][\s\u3000]*([^\]］]{1,40}?)[\s\u3000]*[\]］][\s\u3000]*""",
    )
    private val DATE_PREFIX = Regex(
        """^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?""",
    )
    private val TIME_PREFIX = Regex("""^(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?""")
    private val STAMP_DATE = Regex(
        """^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?$""",
    )
    private val STAMP_TIME = Regex("""^(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?$""")

    /**
     * 设备时区（v0.4）：与 `mem/MemTime.kt` 同一口径，聊天记录里的
     * `YYYY-MM-DD HH:mm` 一律按**设备时区**换算成 epochMillis。
     *
     * 刻意写成函数而不是缓存的 `val`：本对象是单例，`val` 只在首次访问时取一次时区；
     * 单测用 `TimeZone.setDefault(...)` 固定时区后调用 [parse]，缓存会拿到上一个测试的时区。
     */
    private fun zone(): ZoneId = ZoneId.systemDefault()

    fun parse(text: String): ParseResult {
        val turns = mutableListOf<Builder>()
        val preamble = StringBuilder()
        var pendingAt: Long? = null
        var explicitSpeakerTurns = 0

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val stamp = stripStamp(line)

            // 整行只有一个时间戳：留给下一条消息用。
            if (stamp.rest.isBlank()) {
                if (stamp.stripped) pendingAt = stamp.at
                continue
            }

            // ① 说话人标记。
            val marker = SPEAKER_MARKER.find(stamp.rest)
            if (marker != null) {
                val body = stamp.rest.substring(marker.range.last + 1).trim()
                turns += Builder(speakerOf(marker.groupValues[1]), body, stamp.at ?: pendingAt)
                explicitSpeakerTurns += 1
                pendingAt = null
                continue
            }

            // ② 整行引号。
            val quoted = QUOTED_LINE.matchEntire(stamp.rest)
            if (quoted != null) {
                val body = quoted.groupValues[1].trim()
                if (body.isNotEmpty()) {
                    turns += Builder(Speaker.UNKNOWN, body, stamp.at ?: pendingAt)
                    pendingAt = null
                    continue
                }
            }

            // ③ 带时间戳的普通行：时间戳本身就是一条消息的开始，说话人未知。
            if (stamp.stripped) {
                turns += Builder(Speaker.UNKNOWN, stamp.rest.trim(), stamp.at ?: pendingAt)
                pendingAt = null
                continue
            }

            // ④ 其余行是上一条消息的续行；第一条消息之前的归 preamble。
            val last = turns.lastOrNull()
            if (last != null) {
                if (last.text.isNotEmpty()) last.text.append('\n')
                last.text.append(stamp.rest.trim())
            } else {
                if (preamble.isNotEmpty()) preamble.append('\n')
                preamble.append(stamp.rest.trim())
            }
        }

        val result = turns.mapNotNull { builder ->
            val body = builder.text.toString().trim()
            if (body.isEmpty()) null else Turn(builder.speaker, body, builder.at)
        }
        return ParseResult(result, preamble.toString().trim(), explicitSpeakerTurns)
    }

    /**
     * 硬规则：识别出的轮次少于 [minTurns] 时放弃拆轮次，
     * 整段当人设。返回 null 就表示"这段不是聊天记录，别拆"。
     */
    fun parseChatLog(
        text: String,
        minTurns: Int = ImportPipeline.MIN_CHAT_TURNS,
    ): ParseResult? = parse(text).takeIf { it.turns.size >= minTurns }

    /** [ParseResult] 是否够格当聊天记录。 */
    fun isChatLog(
        result: ParseResult,
        minTurns: Int = ImportPipeline.MIN_CHAT_TURNS,
    ): Boolean = result.turns.size >= minTurns

    // ---- 说话人 ----

    private fun speakerOf(raw: String): Speaker {
        return when (raw.trim().lowercase()) {
            "你", "我", "用户", "user", "u" -> Speaker.USER
            "她", "他", "ta", "对方", "assistant", "ai", "bot" -> Speaker.CHARACTER
            else -> Speaker.UNKNOWN
        }
    }

    // ---- 时间戳 ----

    private data class Stamp(val at: Long?, val rest: String, val stripped: Boolean = true)

    /** 剥掉行首时间戳。只有"明确是时间戳"时才剥，宁可少剥也不要吃掉正文。 */
    private fun stripStamp(line: String): Stamp {
        BRACKET_TS.find(line)?.let { m ->
            val parsed = parseStamp(m.groupValues[1])
            if (parsed.isStamp) {
                return Stamp(parsed.at, line.substring(m.range.last + 1).trimStart())
            }
        }

        DATE_PREFIX.find(line)?.let { m ->
            val date = dateOf(m)
            if (date != null) {
                val hour = m.groupValues[4].toIntOrNull()
                val minute = m.groupValues[5].toIntOrNull()
                val second = m.groupValues[6].toIntOrNull()
                val validTime = hour == null || (hour in 0..23 && minute != null && minute in 0..59)
                if (validTime) {
                    val after = line.substring(m.range.last + 1)
                    val rest = dropColon(after)
                    if (startsWithColon(after) || rest.isEmpty() || SPEAKER_MARKER.containsMatchIn(rest)) {
                        return Stamp(instantOf(date, hour, minute, second), rest)
                    }
                }
            }
        }

        TIME_PREFIX.find(line)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = m.groupValues[2].toIntOrNull() ?: return@let
            if (hour !in 0..23 || minute !in 0..59) return@let
            val after = line.substring(m.range.last + 1)
            val rest = dropColon(after)
            if (startsWithColon(after) || rest.isEmpty() || SPEAKER_MARKER.containsMatchIn(rest)) {
                return Stamp(null, rest)
            }
        }

        return Stamp(null, line.trim(), stripped = false)
    }

    /** 整段认时间戳（方括号里的内容），认得出但只有时分时 [Stamp.at] 是 null。 */
    private data class ParsedStamp(val isStamp: Boolean, val at: Long?)

    private fun parseStamp(inner: String): ParsedStamp {
        val s = inner.trim()

        STAMP_DATE.matchEntire(s)?.let { m ->
            val date = dateOf(m)
            if (date != null) {
                val hour = m.groupValues[4].toIntOrNull()
                val minute = m.groupValues[5].toIntOrNull()
                val second = m.groupValues[6].toIntOrNull()
                if (hour == null || (hour in 0..23 && minute != null && minute in 0..59)) {
                    return ParsedStamp(true, instantOf(date, hour, minute, second))
                }
            }
        }

        STAMP_TIME.matchEntire(s)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull()
            val minute = m.groupValues[2].toIntOrNull()
            if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                // 只有时分，没有日期：认得出是时间戳，但换算不出毫秒。
                return ParsedStamp(true, null)
            }
        }

        return ParsedStamp(false, null)
    }

    private fun dateOf(match: MatchResult): LocalDate? = runCatching {
        LocalDate.of(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        )
    }.getOrNull()

    private fun instantOf(date: LocalDate, hour: Int?, minute: Int?, second: Int?): Long? {
        val zone = zone()
        if (hour == null || minute == null) return date.atStartOfDay(zone).toInstant().toEpochMilli()
        val time = runCatching { LocalTime.of(hour, minute, second ?: 0) }.getOrNull() ?: return null
        return LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
    }

    private fun startsWithColon(text: String): Boolean {
        val t = text.trimStart(' ', '\t', '\u3000')
        return t.startsWith(":") || t.startsWith("：")
    }

    private fun dropColon(text: String): String {
        var t = text.trimStart(' ', '\t', '\u3000')
        if (t.startsWith(":") || t.startsWith("：")) t = t.substring(1)
        return t.trimStart(' ', '\t', '\u3000')
    }

    private class Builder(val speaker: Speaker, text: String, val at: Long?) {
        val text = StringBuilder(text)
    }
}
