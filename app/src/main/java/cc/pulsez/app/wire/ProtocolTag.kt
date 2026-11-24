package cc.pulsez.app.wire

/**
 * 输出协议标签：回复末尾的 `[[pz:emo=shy|rel=close]]`。
 *
 * 解析失败不影响显示；流式过程中出现半截 `[[...` 时渲染前截掉，避免闪一下。
 * v1 不驱动 UI（情绪由文字承载），只留作以后立绘/记忆表参考。
 */
object ProtocolTag {

    data class Parsed(val text: String, val emotion: String?, val relation: String?)

    private val full = Regex("""\[\[pz:([^\]]*)\]\]\s*$""")
    private val halfOpen = Regex("""\[\[[^\]]*$""")

    private val emotions = setOf(
        "calm", "happy", "shy", "sad", "angry", "sleepy", "surprised", "smug",
    )
    private val relations = setOf("distant", "normal", "close", "intimate")

    fun parse(raw: String): Parsed {
        val trimmed = raw.trimEnd()
        val match = full.find(trimmed)
        if (match == null) {
            return Parsed(text = stripPartial(trimmed), emotion = null, relation = null)
        }
        var emotion: String? = null
        var relation: String? = null
        match.groupValues[1].split('|').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@forEach
            val key = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim().lowercase()
            when (key) {
                "emo" -> if (value in emotions) emotion = value
                "rel" -> if (value in relations) relation = value
            }
        }
        val body = trimmed.substring(0, match.range.first).trimEnd()
        return Parsed(text = body, emotion = emotion, relation = relation)
    }

    /** 流式渲染用：去掉末尾未闭合的 `[[` 或已闭合的完整 `[[pz:...]]` 标签，防止半截或完整标签在 Finished 帧前闪现。 */
    fun stripPartial(text: String): String {
        val m = halfOpen.find(text)
        val withoutHalf = if (m != null) text.substring(0, m.range.first).trimEnd() else text
        val f = full.find(withoutHalf)
        return if (f != null) withoutHalf.substring(0, f.range.first).trimEnd() else withoutHalf
    }
}
