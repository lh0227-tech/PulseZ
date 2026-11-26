package cc.pulsez.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import cc.pulsez.app.net.Citation
import java.net.URI

/**
 * 去重并按首次出现顺序编号（`1..K`）后的引用来源项。
 */
internal data class NumberedCitation(
    val index: Int,
    val url: String,
    val title: String?,
)

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
 * - 联网搜索引用：在剥离 Markdown 语法之前，先按 Unicode code point 偏移映射 [Citation.endIndex]
 *   到原串 UTF-16 偏移，再在转换阶段原位插入上标 `[n]`（同 URL 复用同一编号 `1..K`），
 *   零字符错位与越界。
 */
object ChatMarkdown {

    internal fun numberCitations(citations: List<Citation>): List<NumberedCitation> {
        if (citations.isEmpty()) return emptyList()
        val byUrl = LinkedHashMap<String, NumberedCitation>()
        citations.forEach { citation ->
            val url = citation.url.trim()
            if (url.isEmpty()) return@forEach
            val cleanTitle = citation.title?.trim()?.takeIf { it.isNotEmpty() }
            val existing = byUrl[url]
            if (existing == null) {
                byUrl[url] = NumberedCitation(
                    index = byUrl.size + 1,
                    url = url,
                    title = cleanTitle,
                )
            } else if (existing.title == null && cleanTitle != null) {
                byUrl[url] = existing.copy(title = cleanTitle)
            }
        }
        return byUrl.values.toList()
    }

    internal fun isValidHttpUrl(rawUrl: String): Boolean {
        val clean = rawUrl.trim()
        if (clean.isEmpty()) return false
        val uri = runCatching { URI(clean) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    @JvmOverloads
    fun render(
        text: String,
        citations: List<Citation> = emptyList(),
        citationColor: Color = Color.Unspecified,
        onCitationClick: ((Citation) -> Unit)? = null,
    ): AnnotatedString {
        if (text.isEmpty()) return AnnotatedString("")
        val markers = buildMarkers(text, citations)
        if (markers.isEmpty() && !text.contains('*') && !text.contains('`')) {
            return AnnotatedString(text)
        }
        val cursor = MarkerCursor(
            markers = markers,
            citationColor = citationColor,
            onCitationClick = onCitationClick,
        )
        return buildAnnotatedString {
            appendParsed(
                text = text,
                start = 0,
                end = text.length,
                allowCodeProtection = true,
                baseBold = false,
                markerCursor = cursor,
                flushAtEnd = true,
            )
            cursor.flushUpTo(this, text.length)
        }
    }

    private data class CitationMarker(
        val charOffset: Int,
        val number: Int,
        val citation: Citation,
    )

    private class MarkerCursor(
        private val markers: List<CitationMarker>,
        private val citationColor: Color,
        private val onCitationClick: ((Citation) -> Unit)?,
    ) {
        private var index = 0

        fun peekOffset(): Int? = markers.getOrNull(index)?.charOffset

        fun flushUpTo(builder: AnnotatedString.Builder, maxOffset: Int) {
            while (index < markers.size && markers[index].charOffset <= maxOffset) {
                appendMarker(builder, markers[index])
                index++
            }
        }

        private fun appendMarker(builder: AnnotatedString.Builder, marker: CitationMarker) {
            val style = SpanStyle(
                fontSize = 11.sp,
                baselineShift = BaselineShift.Superscript,
                fontWeight = FontWeight.Medium,
                color = citationColor,
            )
            val label = "[${marker.number}]"
            val clickHandler = onCitationClick
            if (clickHandler != null && isValidHttpUrl(marker.citation.url)) {
                builder.withLink(
                    LinkAnnotation.Clickable(
                        tag = "citation-${marker.number}",
                        linkInteractionListener = { clickHandler(marker.citation) },
                    ),
                ) {
                    builder.withStyle(style) {
                        builder.append(label)
                    }
                }
            } else {
                builder.withStyle(style) {
                    builder.append(label)
                }
            }
        }
    }

    private fun buildMarkers(text: String, citations: List<Citation>): List<CitationMarker> {
        if (citations.isEmpty()) return emptyList()
        val numbered = numberCitations(citations)
        if (numbered.isEmpty()) return emptyList()
        val urlToNumber = numbered.associate { it.url to it.index }
        val totalCodePoints = text.codePointCount(0, text.length)
        val seen = HashSet<Pair<Int, Int>>()
        val result = ArrayList<CitationMarker>(citations.size)
        for (citation in citations) {
            val url = citation.url.trim()
            val number = urlToNumber[url] ?: continue
            val endCp = citation.endIndex ?: continue
            if (endCp < 0) continue
            val clampedCp = endCp.coerceAtMost(totalCodePoints)
            val charOffset = text.offsetByCodePoints(0, clampedCp)
            if (seen.add(charOffset to number)) {
                result.add(
                    CitationMarker(
                        charOffset = charOffset,
                        number = number,
                        citation = citation.copy(url = url),
                    ),
                )
            }
        }
        result.sortWith(compareBy<CitationMarker>({ it.charOffset }, { it.number }))
        return result
    }

    private fun AnnotatedString.Builder.appendParsed(
        text: String,
        start: Int,
        end: Int,
        allowCodeProtection: Boolean,
        baseBold: Boolean,
        markerCursor: MarkerCursor,
        flushAtEnd: Boolean,
    ) {
        var i = start
        while (i < end) {
            if (allowCodeProtection && text[i] == '`') {
                if (i + 3 <= end && text.startsWith("```", startIndex = i)) {
                    markerCursor.flushUpTo(this, i)
                    val rawCloseFence = text.indexOf("```", startIndex = i + 3)
                    val closeEnd = if (rawCloseFence in (i + 3)..(end - 3)) {
                        rawCloseFence + 3
                    } else {
                        end
                    }
                    append(text, i, closeEnd)
                    markerCursor.flushUpTo(this, closeEnd)
                    i = closeEnd
                    continue
                }
                val lineEnd = findLineEnd(text, i + 1, end)
                val closeTick = findInlineCodeClose(text, i + 1, lineEnd)
                if (closeTick >= 0) {
                    markerCursor.flushUpTo(this, i)
                    val closeEnd = closeTick + 1
                    append(text, i, closeEnd)
                    markerCursor.flushUpTo(this, closeEnd)
                    i = closeEnd
                    continue
                }
            }

            if (text[i] == '*') {
                val runLen = countStars(text, i, end)
                if (runLen in 1..3) {
                    val contentStart = i + runLen
                    val lineEnd = findLineEnd(text, contentStart, end)
                    if (contentStart < lineEnd && !text[contentStart].isWhitespace()) {
                        val closeIndex = findClosingStars(
                            text = text,
                            start = contentStart,
                            limit = lineEnd,
                            targetRun = runLen,
                            allowCodeProtection = allowCodeProtection,
                        )
                        if (closeIndex >= 0) {
                            markerCursor.flushUpTo(this, contentStart)
                            when (runLen) {
                                3 -> withStyle(
                                    SpanStyle(
                                        fontWeight = FontWeight.SemiBold,
                                        fontStyle = FontStyle.Italic,
                                    ),
                                ) {
                                    appendSliceWithMarkers(
                                        text = text,
                                        from = contentStart,
                                        to = closeIndex,
                                        markerCursor = markerCursor,
                                        flushAtEnd = false,
                                    )
                                }
                                2 -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                    appendParsed(
                                        text = text,
                                        start = contentStart,
                                        end = closeIndex,
                                        allowCodeProtection = allowCodeProtection,
                                        baseBold = true,
                                        markerCursor = markerCursor,
                                        flushAtEnd = false,
                                    )
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
                                        appendSliceWithMarkers(
                                            text = text,
                                            from = contentStart,
                                            to = closeIndex,
                                            markerCursor = markerCursor,
                                            flushAtEnd = false,
                                        )
                                    }
                                }
                            }
                            val closeEnd = closeIndex + runLen
                            markerCursor.flushUpTo(this, closeEnd)
                            i = closeEnd
                            continue
                        }
                    }
                }
                appendSliceWithMarkers(
                    text = text,
                    from = i,
                    to = i + runLen,
                    markerCursor = markerCursor,
                    flushAtEnd = false,
                )
                i += runLen
                continue
            }

            val nextSpecial = findNextSpecial(text, i, end, allowCodeProtection)
            appendSliceWithMarkers(
                text = text,
                from = i,
                to = nextSpecial,
                markerCursor = markerCursor,
                flushAtEnd = flushAtEnd && nextSpecial == end,
            )
            i = nextSpecial
        }
    }

    private fun AnnotatedString.Builder.appendSliceWithMarkers(
        text: String,
        from: Int,
        to: Int,
        markerCursor: MarkerCursor,
        flushAtEnd: Boolean,
    ) {
        var cur = from
        while (cur < to) {
            val nextOffset = markerCursor.peekOffset()
            if (nextOffset == null || nextOffset > to || (nextOffset == to && !flushAtEnd)) {
                append(text, cur, to)
                cur = to
            } else {
                val clamped = nextOffset.coerceAtLeast(cur)
                if (clamped > cur) {
                    append(text, cur, clamped)
                }
                markerCursor.flushUpTo(this, clamped)
                cur = clamped
                if (clamped == to) break
            }
        }
        if (flushAtEnd) {
            markerCursor.flushUpTo(this, to)
        }
    }

    private fun countStars(text: String, start: Int, limit: Int): Int {
        var idx = start
        while (idx < limit && text[idx] == '*') idx++
        return idx - start
    }

    private fun findLineEnd(text: String, start: Int, limit: Int): Int {
        val nl = text.indexOf('\n', start)
        return if (nl in start until limit) nl else limit
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
                val runLen = countStars(text, idx, limit)
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

    private fun findNextSpecial(text: String, start: Int, limit: Int, allowCodeProtection: Boolean): Int {
        var idx = start + 1
        while (idx < limit) {
            val ch = text[idx]
            if (ch == '*' || (allowCodeProtection && ch == '`')) return idx
            idx++
        }
        return limit
    }
}
