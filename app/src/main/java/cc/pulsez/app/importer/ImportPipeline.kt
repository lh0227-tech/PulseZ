package cc.pulsez.app.importer

/**
 * 角色导入管线：格式识别 → 结构化 → 预览草稿。规范见 。
 *
 * 只做本地解析，不联网（"一次提炼"由 mem/net 层另做，这里只产出待提炼的结构）。
 * 编码判定不在管线里：文件渠道先 [CharsetDetector.decode]，把 String 交给 [parse]；
 * 图省事也可以直接 [parseBytes]。
 *
 * 输出是可编辑的草稿，不是最终人设：
 * - 聊天记录只给出 [personaFromTurns] 的草稿 + 语气样本，真正的"她是谁"要用户确认；
 * - 角色卡字段原样映射，缺的给 null + warning。
 */
object ImportPipeline {

    /** 少于此数就不拆轮次，整段当人设（硬规则，见）。*/
    const val MIN_CHAT_TURNS: Int = 4

    /** 人设层上限，见 。*/
    const val PERSONA_MAX_CHARS: Int = 40_000

    /** 超过这个字符数就提示"只复制最重要的部分"（§7 的 5MB）。 */
    const val MAX_INPUT_CHARS: Int = 5 * 1024 * 1024

    /** 超过这个字符数提醒等待和额度（§6）。 */
    const val LONG_INPUT_CHARS: Int = 50_000

    private const val MAX_STYLE_SAMPLES: Int = 5

    /** 一条消息在"她话多"打分里折算成多少字。 */
    private const val MESSAGE_WEIGHT: Double = 20.0

    /**
     * 解析一段已解码的文本。
     *
     * 判别优先级照
     * JSON 角色卡 → Markdown frontmatter → 聊天记录（≥4 轮）→ 纯文本。
     * 这个顺序不能乱：卡片里可能带对话样例，对话里也可能带 `---` 分隔线。
     */
    fun parse(raw: String, source: Source): ParsedImport {
        val original = raw.withoutBom()
        if (original.isBlank()) {
            val message = if (source == Source.LINK) ImportWarnings.LINK_EMPTY else ImportWarnings.EMPTY
            return ParsedImport(
                name = null,
                persona = null,
                greeting = null,
                styleSamples = emptyList(),
                turns = emptyList(),
                format = Format.TEXT,
                warnings = listOf(message),
            )
        }

        val warnings = mutableListOf<String>()

        // 超大输入先截断再解析：正则和逐行扫描都不该在 5MB 以上白烧。
        var text = original
        if (text.length > MAX_INPUT_CHARS) {
            warnings += ImportWarnings.TOO_LARGE
            text = truncateChars(text, MAX_INPUT_CHARS)
        } else if (text.length > LONG_INPUT_CHARS) {
            warnings += ImportWarnings.LONG_CONTENT
        }
        if (looksGarbled(text)) warnings += ImportWarnings.GARBLED

        val trimmed = text.trim()

        // ① JSON 角色卡。以 { 开头且含 name/personality/first_mes/spec/description。
        if (JsonCardParser.detect(trimmed)) {
            val card = JsonCardParser.parse(trimmed)
            if (card != null) return finish(card, warnings)
            warnings += ImportWarnings.NOT_CARD
        }

        // ② Markdown frontmatter。以 --- 开头且 frontmatter 里有 name:。
        if (MarkdownParser.detect(trimmed)) {
            return finish(MarkdownParser.parse(trimmed), warnings)
        }

        // ③ 聊天记录。少于 MIN_CHAT_TURNS 轮就放弃拆轮次（硬规则）。
        val chat = TextParser.parse(text)
        if (TextParser.isChatLog(chat, MIN_CHAT_TURNS)) {
            val samples = styleSamples(chat.turns)
            val persona = personaFromTurns(chat.turns, PERSONA_MAX_CHARS)
            val chatWarnings = mutableListOf<String>()
            if (chat.turns.all { it.speaker == Speaker.UNKNOWN }) {
                chatWarnings += ImportWarnings.NO_SPEAKER
            }
            val result = ParsedImport(
                name = null,
                persona = persona.takeIf { it.isNotEmpty() },
                greeting = null,
                styleSamples = samples,
                turns = chat.turns,
                format = Format.CHAT_LOG,
                warnings = (warnings + chatWarnings).distinct(),
            )
            return finish(result, emptyList())
        }

        // 有说话人标记但轮次不够：说一句"太短了，当人设了"，别让用户以为解析失败。
        if (chat.explicitSpeakerTurns > 0) warnings += ImportWarnings.TOO_FEW_TURNS

        // ④ 纯文本：整段当人设。
        val result = ParsedImport(
            name = null,
            persona = trimmed.takeIf { it.isNotEmpty() },
            greeting = null,
            styleSamples = StyleSamples.fromQuotedLines(trimmed),
            turns = emptyList(),
            format = Format.TEXT,
            warnings = warnings.distinct(),
        )
        return finish(result, emptyList())
    }

    /**
     * 文件渠道的便捷入口：解码 + 解析，lossy 时补一条"换编码"提示。
     * 文件读取留给调用方，这里只要字节。
     */
    fun parseBytes(bytes: ByteArray, source: Source): ParsedImport {
        val decoded = CharsetDetector.decode(bytes)
        val parsed = parse(decoded.text, source)
        if (!decoded.lossy) return parsed
        return parsed.copy(warnings = (listOf(ImportWarnings.GARBLED) + parsed.warnings).distinct())
    }

    /**
     * 默认猜测哪一方是"她"：消息更多、更长的那一方（§2.5）。
     *
     * 返回值是"她"当前对应的那个标记：[Speaker.USER] 表示"标成你/User 的那一侧其实是她"，
     * 界面把默认选项定在那一侧，用户在"谁是她的"里确认后再调 [applySpeakerChoice]。
     * 打平或没有说话人时返回 [Speaker.CHARACTER]，即保持原文标记不动。
     */
    fun guessSpeaker(turns: List<Turn>): Speaker {
        val userScore = speakerScore(turns, Speaker.USER)
        val characterScore = speakerScore(turns, Speaker.CHARACTER)
        return if (userScore > characterScore) Speaker.USER else Speaker.CHARACTER
    }

    /**
     * 把用户确认的那一方归一为 [Speaker.CHARACTER]，另一方归一为 [Speaker.USER]，
     * 没识别出来的 [Speaker.UNKNOWN] 原样保留。
     *
     * [characterSpeaker] 传 [guessSpeaker] 的返回值或用户在界面上选的选项。
     * 只对聊天记录生效，且**只调一次**：再调一次会把两侧换回去。
     * 换向会按新的角色侧重算 persona 草稿和语气样本，所以要在用户编辑之前调用。
     */
    fun applySpeakerChoice(parsed: ParsedImport, characterSpeaker: Speaker): ParsedImport {
        if (parsed.format != Format.CHAT_LOG || parsed.turns.isEmpty()) return parsed
        val chosen = if (characterSpeaker == Speaker.UNKNOWN) Speaker.CHARACTER else characterSpeaker
        if (chosen == Speaker.CHARACTER) return parsed

        val normalized = parsed.turns.map { turn ->
            if (turn.speaker == Speaker.UNKNOWN) {
                turn
            } else {
                turn.copy(speaker = if (turn.speaker == chosen) Speaker.CHARACTER else Speaker.USER)
            }
        }
        val persona = personaFromTurns(normalized, PERSONA_MAX_CHARS)
        return parsed.copy(
            turns = normalized,
            styleSamples = styleSamples(normalized),
            persona = if (persona.isNotEmpty()) persona else parsed.persona,
        )
    }

    /**
     * 从对话里抽 3–5 条角色台词 + 骨架，拼一份可编辑的人设草稿。
     *
     * 这是草稿不是成品：没有模型读不懂"她是谁"，名字/性格/关系留给用户填，
     * 能自动给的只有语气样本和消息统计。返回内容保证不超过 [maxChars] 字，
     * 默认 [PERSONA_MAX_CHARS]（40000，见）。
     */
    fun personaFromTurns(turns: List<Turn>, maxChars: Int = PERSONA_MAX_CHARS): String {
        if (maxChars <= 0) return ""
        val characterTurns = turns.filter { it.speaker == Speaker.CHARACTER && it.text.isNotBlank() }
        if (characterTurns.isEmpty()) return ""

        val samples = styleSamples(turns)
        val totalChars = characterTurns.sumOf { it.text.length }
        val average = totalChars / characterTurns.size

        val draft = buildString {
            append("（从 ").append(turns.size).append(" 条对话里整理出来的草稿，下面这些都可以改）\n\n")
            append("TA 是谁：还没填。写 TA 的名字、性格、你们的关系。\n\n")
            if (samples.isNotEmpty()) {
                append("TA 怎么说话：\n")
                samples.forEach { sample ->
                    append('「').append(sample).append('」').append('\n')
                }
            } else {
                append("TA 怎么说话：还没认出 TA 的台词，先把你印象里的语气写在这里。\n")
            }
            append("\nTA 说过的 ").append(characterTurns.size).append(" 条，平均每条 ").append(average).append(" 字。")
        }
        return truncateChars(draft.trim(), maxChars)
    }

    /** 角色那一侧的语气样本，最多 5 条。 */
    fun styleSamples(turns: List<Turn>): List<String> = StyleSamples.pick(turns, MAX_STYLE_SAMPLES)

    /**
     * 能不能进预览/入库：至少要有一样可用的内容。
     * 名字可以之后再起（§7"你帮她起一个？"），但人设和往事不能都是空的。
     */
    fun isValid(parsed: ParsedImport): Boolean =
        !parsed.persona.isNullOrBlank() || parsed.turns.isNotEmpty()

    /** 解析时攒下的提示，去重后给预览页。 */
    fun warnings(parsed: ParsedImport): List<String> = parsed.warnings.distinct()

    // ---- 内部实现 ----

    /** 收尾：合并提示、补"人设超 40000 字"的提醒。 */
    private fun finish(parsed: ParsedImport, extra: List<String>): ParsedImport {
        val warnings = (extra + parsed.warnings).filter { it.isNotBlank() }.distinct()
        val persona = parsed.persona
        val tooLong = persona != null && persona.length > PERSONA_MAX_CHARS
        return parsed.copy(
            warnings = if (tooLong) (warnings + ImportWarnings.PERSONA_TOO_LONG).distinct() else warnings,
        )
    }

    private fun speakerScore(turns: List<Turn>, speaker: Speaker): Double {
        var chars = 0
        var count = 0
        for (turn in turns) {
            if (turn.speaker != speaker) continue
            chars += turn.text.length
            count += 1
        }
        return chars + count * MESSAGE_WEIGHT
    }

    /**
     * 乱码嗅探（§7"这文件像是别的编码"）。
     *
     * 只认两种硬信号：出现 U+FFFD 替换字符，或者控制字符占比超过 5%。
     * 不去猜"这段中文像不像乱码"——GBK 字节碰巧是合法 UTF-8 时（例如"你"的
     * 不是，但有些字是）这一步本来也救不回来，只能靠预览让用户换编码。
     */
    private fun looksGarbled(text: String): Boolean {
        if (text.length < 8) return false
        var replacement = 0
        var control = 0
        for (c in text) {
            when {
                c == '\uFFFD' -> replacement += 1
                c.code < 0x20 && c != '\n' && c != '\r' && c != '\t' -> control += 1
            }
        }
        return replacement > 0 || control * 20 > text.length
    }
}
