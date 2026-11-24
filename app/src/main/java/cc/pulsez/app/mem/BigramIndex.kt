package cc.pulsez.app.mem

/**
 * 中文二元组倒排索引的纯文本部分（02 §3 chunk_term、03 §7.4 关键词召回）。
 *
 * 规则：
 * - 中文按连续汉字做**双字切片**（"杭州的雨" → `杭州` / `州的` / `的雨`）；
 *   单字片段保留单字（便于单字查询），停用词丢弃。
 * - 英文 / 数字按连续字母数字串切词并小写（"Hello, World2" → `hello` / `world2`）。
 * - 标点、空白、emoji 不产生 term。
 *
 * 这是无 embeddings 时的检索兜底（03 §7.1：关键词是兜底，向量是一级能力）。
 */
object BigramIndex {

    /**
     * 短小的中文停用词表：只去掉对检索几乎无区分度的二元组/单字。
     *
     * 注意不要放语义词（如"面试""杭州"），否则会削弱召回。
     */
    val CHINESE_STOP_WORDS: Set<String> = setOf(
        "我们", "你们", "他们", "她们", "它们", "大家", "自己",
        "什么", "怎么", "这个", "那个", "这样", "那样", "这里", "那里",
        "一些", "这些", "那些", "有些", "很多", "这些", "这种", "那种",
        "就是", "因为", "所以", "但是", "不过", "而且", "然后", "如果",
        "可以", "没有", "不是", "不能", "不会", "不要", "不用", "还有",
        "知道", "觉得", "感觉", "认为", "可能", "应该", "需要", "喜欢",
        "一下", "一直", "一起", "一样", "现在", "时候", "之前", "之后",
        "真的", "非常", "特别", "有点", "起来", "出来", "下来", "知道",
        "东西", "事情", "问题", "话说", "其实", "只是", "已经", "还是",
        "我的", "你的", "他是", "她是", "说是", "说了", "说的", "我要", "你是",
    )

    /**
     * 抽取文本的检索 term 集合。
     *
     * 对应文档：02 §3 chunk_term（中文双字切片 / 英文小写词）、03 §7.4。
     *
     * @param text 任意文本
     * @return 去重后的 term 集合（保持首次出现顺序）
     */
    fun terms(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val result = LinkedHashSet<String>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            when {
                isCjkWordCodePoint(codePoint) -> {
                    val start = index
                    while (index < text.length) {
                        val current = text.codePointAt(index)
                        if (!isCjkWordCodePoint(current)) break
                        index += charCountOf(current)
                    }
                    addCjkTerms(text.substring(start, index), result)
                }

                isAsciiLetterOrDigit(codePoint) -> {
                    val start = index
                    while (index < text.length) {
                        val current = text.codePointAt(index)
                        if (!isAsciiLetterOrDigit(current)) break
                        index += charCountOf(current)
                    }
                    val word = text.substring(start, index).lowercase()
                    if (word.isNotEmpty()) result.add(word)
                }

                else -> index += charCountOf(codePoint)
            }
        }
        return result
    }

    /**
     * 关键词命中数（可选 IDF 加权）。
     *
     * 对应文档：03 §7.4（关键词 top 6）、02 §7 DAO 的 `SUM(weight)` 口径。
     *
     * @param queryTerms 查询 term
     * @param docTerms 文档 term
     * @param idf 可选的 IDF 权重表；为空时返回命中个数，非空时返回 `round(Σ idf[命中 term])`
     */
    fun score(
        queryTerms: Set<String>,
        docTerms: Set<String>,
        idf: Map<String, Double>? = null,
    ): Int {
        if (queryTerms.isEmpty() || docTerms.isEmpty()) return 0
        if (idf == null) {
            var hits = 0
            for (term in queryTerms) {
                if (term in docTerms) hits++
            }
            return hits
        }
        var weighted = 0.0
        for (term in queryTerms) {
            if (term in docTerms) weighted += idf[term] ?: 1.0
        }
        return kotlin.math.round(weighted).toInt()
    }

    private fun addCjkTerms(run: String, output: MutableSet<String>) {
        if (run.isEmpty()) return
        val codePoints = ArrayList<Int>(run.length)
        var index = 0
        while (index < run.length) {
            val codePoint = run.codePointAt(index)
            codePoints.add(codePoint)
            index += charCountOf(codePoint)
        }
        if (codePoints.size == 1) {
            addTerm(String(Character.toChars(codePoints[0])), output)
            return
        }
        for (position in 0 until codePoints.size - 1) {
            val term = String(Character.toChars(codePoints[position])) +
                String(Character.toChars(codePoints[position + 1]))
            addTerm(term, output)
        }
    }

    private fun addTerm(term: String, output: MutableSet<String>) {
        if (term in CHINESE_STOP_WORDS) return
        output.add(term)
    }

    private fun isAsciiLetterOrDigit(codePoint: Int): Boolean = when (codePoint) {
        in 0x30..0x39 -> true
        in 0x41..0x5A -> true
        in 0x61..0x7A -> true
        else -> false
    }

    /**
     * 只把"汉字/表意文字"当词字符：CJK 标点（0x3000–0x303F）与全角符号（0xFF00–0xFFEF）
     * 是分隔符，不能与相邻汉字的标点一起产出 `，。` 这类无意义二元组。
     */
    private fun isCjkWordCodePoint(codePoint: Int): Boolean = when (codePoint) {
        in 0x3400..0x4DBF -> true // 扩展 A
        in 0x4E00..0x9FFF -> true // 基本区
        in 0xF900..0xFAFF -> true // 兼容表意文字
        in 0x20000..0x2FA1F -> true // 扩展 B–F
        else -> false
    }
}
