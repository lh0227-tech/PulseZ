package cc.pulsez.app.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 轻量行内富文本转换器（纯 Kotlin + [AnnotatedString]，零新依赖）。
 *
 * 规则：
 * - 成对 `**加粗**` → [FontWeight.SemiBold]
 * - 成对 `*斜体描写*` → [FontStyle.Italic]
 * - 成对 `***粗斜体***` → [FontWeight.SemiBold] + [FontStyle.Italic]
 * - `**...**` 内嵌套的 `*...*` 叠加 [FontStyle.Italic]
 * - 代码块（```` ```...``` ````）与行内代码（`` `...` ``）原样保留，不解析内部星号
 * - 孤立/未闭合星号（如 `3 * 4`、`3 * 4 * 5`）、空星号（`**`、`****`）原样保留，绝不吞字或抛异常
 */
object ChatMarkdown {

    fun render(text: String): AnnotatedString {
        if (text.isEmpty() || (!text.contains('*') && !text.contains('`'))) {
            return AnnotatedString(text)
        }
        return buildAnnotatedString {
            appendParsed(text, allowCodeProtection = true, baseBold = false)
        }
    }

    private fun AnnotatedString.Builder.appendParsed(
        text: String,
        allowCodeProtection: Boolean,
        baseBold: Boolean,
    ) {
        var i = 0
        val n = text.length
        while (i < n) {
            if (allowCodeProtection && text[i] == '`') {
                if (text.startsWith("```", startIndex = i)) {
                    val closeFence = text.indexOf("```", startIndex = i + 3)
                    if (closeFence >= 0) {
                        append(text, i, closeFence + 3)
                        i = closeFence + 3
                    } else {
                        append(text, i, n)
                        i = n
                    }
                    continue
                }
                val lineEnd = findLineEnd(text, i + 1)
                val closeTick = findInlineCodeClose(text, i + 1, lineEnd)
                if (closeTick >= 0) {
                    append(text, i, closeTick + 1)
                    i = closeTick + 1
                    continue
                }
            }

            if (text[i] == '*') {
                val runLen = countStars(text, i)
                if (runLen in 1..3) {
                    val contentStart = i + runLen
                    val lineEnd = findLineEnd(text, contentStart)
                    if (contentStart < lineEnd && !text[contentStart].isWhitespace()) {
                        val closeIndex = findClosingStars(
                            text = text,
                            start = contentStart,
                            limit = lineEnd,
                            targetRun = runLen,
                            allowCodeProtection = allowCodeProtection,
                        )
                        if (closeIndex >= 0) {
                            val inner = text.substring(contentStart, closeIndex)
                            when (runLen) {
                                3 -> withStyle(
                                    SpanStyle(
                                        fontWeight = FontWeight.SemiBold,
                                        fontStyle = FontStyle.Italic,
                                    ),
                                ) {
                                    append(inner)
                                }
                                2 -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                    appendParsed(inner, allowCodeProtection = allowCodeProtection, baseBold = true)
                                }
                                1 -> {
                                    val style = if (baseBold) {
                                        SpanStyle(
                                            fontWeight = FontWeight.SemiBold,
                                            fontStyle = FontStyle.Italic,
                                        )
                                    } else {
                                        SpanStyle(fontStyle = FontStyle.Italic)
                                    }
                                    withStyle(style) {
                                        append(inner)
                                    }
                                }
                            }
                            i = closeIndex + runLen
                            continue
                        }
                    }
                }
                append(text, i, i + runLen)
                i += runLen
                continue
            }

            val nextSpecial = findNextSpecial(text, i, allowCodeProtection)
            append(text, i, nextSpecial)
            i = nextSpecial
        }
    }

    private fun countStars(text: String, start: Int): Int {
        var idx = start
        while (idx < text.length && text[idx] == '*') idx++
        return idx - start
    }

    private fun findLineEnd(text: String, start: Int): Int {
        val nl = text.indexOf('\n', start)
        return if (nl >= 0) nl else text.length
    }

    private fun findInlineCodeClose(text: String, start: Int, limit: Int): Int {
        var idx = start
        while (idx < limit) {
            if (text[idx] == '`') return idx
            idx++
        }
        return -1
    }

    private fun findClosingStars(
        text: String,
        start: Int,
        limit: Int,
        targetRun: Int,
        allowCodeProtection: Boolean,
    ): Int {
        var idx = start
        while (idx < limit) {
            if (allowCodeProtection && text[idx] == '`') {
                val closeTick = findInlineCodeClose(text, idx + 1, limit)
                if (closeTick >= 0) {
                    idx = closeTick + 1
                    continue
                }
            }
            if (text[idx] == '*') {
                val runLen = countStars(text, idx)
                if (runLen == targetRun && idx > start && !text[idx - 1].isWhitespace()) {
                    return idx
                }
                idx += runLen
            } else {
                idx++
            }
        }
        return -1
    }

    private fun findNextSpecial(text: String, start: Int, allowCodeProtection: Boolean): Int {
        var idx = start + 1
        while (idx < text.length) {
            val ch = text[idx]
            if (ch == '*' || (allowCodeProtection && ch == '`')) return idx
            idx++
        }
        return text.length
    }
}
