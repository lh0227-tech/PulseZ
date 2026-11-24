package cc.pulsez.app.importer

/**
 * 语气样本的提取规则。3–5 条，从原文摘，不生成。
 *
 * 三个来源：
 * - 结构化对话里的角色台词（[pick]）
 * - SillyTavern 卡片的 `mes_example`（[fromMesExample]）
 * - 纯文本/正文里的引号句（[fromQuotedLines]）
 */
internal object StyleSamples {

    const val MAX_ITEMS: Int = 5

    /** 单条样本的字符上限：太长塞不进预览，也太不像"一句话"。 */
    private const val MAX_LEN: Int = 160

    /** 从已经标好说话人的对话里挑角色那一侧。 */
    fun pick(turns: List<Turn>, max: Int = MAX_ITEMS): List<String> {
        if (max <= 0) return emptyList()
        val lines = turns.asSequence()
            .filter { it.speaker == Speaker.CHARACTER }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .map { truncateChars(it, MAX_LEN).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        return spread(lines, max)
    }

    /**
     * 从 `mes_example` 里摘。SillyTavern 的格式是：
     *
     * ```
     * <START>
     * {{user}}: 你好
     * {{char}}: 你来了。
     * ```
     *
     * 只取 `{{char}}` 行——`{{user}}` 行的语气不能当她的。
     */
    fun fromMesExample(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val charPrefix = Regex("""^\{\{\s*char\s*\}\}\s*[:：][\s]*""", RegexOption.IGNORE_CASE)
        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.equals("<START>", ignoreCase = true) || it == "<START/>" }
            .mapNotNull { line -> charPrefix.find(line)?.let { line.substring(it.range.last + 1).trim() } }
            .filter { it.isNotEmpty() }
            .map { truncateChars(it, MAX_LEN).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        return spread(lines, MAX_ITEMS)
    }

    /** 从纯文本里摘引号句：`「……」`、`『……』`。 */
    fun fromQuotedLines(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val quoted = Regex("""[「『]([^」』\n]{1,160})[」』]""")
        val lines = quoted.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        return spread(lines, MAX_ITEMS)
    }

    /**
     * 候选多的时候均匀取样，天然保留开头那条（通常最能代表第一印象）。
     * 候选不足 [max] 就都留下——硬凑会造假，宁可只给两条。
     */
    private fun spread(candidates: List<String>, max: Int): List<String> {
        if (candidates.isEmpty() || max <= 0) return emptyList()
        if (candidates.size <= max) return candidates
        val picked = LinkedHashSet<String>()
        val n = candidates.size
        for (i in 0 until max) {
            val index = if (max == 1) 0 else i * (n - 1) / (max - 1)
            picked += candidates[index]
        }
        return picked.toList()
    }
}
