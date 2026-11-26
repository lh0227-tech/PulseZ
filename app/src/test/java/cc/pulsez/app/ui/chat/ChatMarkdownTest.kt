package cc.pulsez.app.ui.chat

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import cc.pulsez.app.net.Citation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {

    @Test
    fun rendersBoldAsSemiBoldAndItalicAsItalic() {
        val result = ChatMarkdown.render("*轻声叹气* 「**就是这样**，别担心。」")
        assertEquals("轻声叹气 「就是这样，别担心。」", result.text)

        val italicSpans = result.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(1, italicSpans.size)
        assertEquals("轻声叹气", result.text.substring(italicSpans[0].start, italicSpans[0].end))

        val boldSpans = result.spanStyles.filter { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals(1, boldSpans.size)
        assertEquals("就是这样", result.text.substring(boldSpans[0].start, boldSpans[0].end))
    }

    @Test
    fun supportsBoldItalicAndNestedEmphasis() {
        val triple = ChatMarkdown.render("***非常重要***")
        assertEquals("非常重要", triple.text)
        assertTrue(
            triple.spanStyles.any {
                it.start == 0 && it.end == 4 &&
                    it.item.fontWeight == FontWeight.SemiBold &&
                    it.item.fontStyle == FontStyle.Italic
            },
        )

        val nested = ChatMarkdown.render("**外层 *内层* 结束**")
        assertEquals("外层 内层 结束", nested.text)
        assertTrue(
            nested.spanStyles.any {
                nested.text.substring(it.start, it.end) == "内层" &&
                    it.item.fontWeight == FontWeight.SemiBold &&
                    it.item.fontStyle == FontStyle.Italic
            },
        )
    }

    @Test
    fun preservesIsolatedStarsEmptyStarsAndUnclosedDelimitersVerbatim() {
        val cases = listOf(
            "",
            "3 * 4 = 12",
            "2 * 3 * 4 = 24",
            "**",
            "***",
            "****",
            "* *",
            "** **",
            "*还没写完的斜体",
            "**还没写完的加粗",
            "a*b",
            "* 第一条\n* 第二条",
        )
        cases.forEach { raw ->
            val rendered = ChatMarkdown.render(raw)
            assertEquals("不能吞掉任何字符：$raw", raw, rendered.text)
            assertTrue("孤立/空/未闭合星号不应产生样式：$raw", rendered.spanStyles.isEmpty())
        }
    }

    @Test
    fun preservesInlineCodeAndFencedCodeBlocksVerbatim() {
        val inline = "试试 `val x = 3 * 4` 和 `*not italic*`，再看 **重点**。"
        val inlineRendered = ChatMarkdown.render(inline)
        assertEquals("试试 `val x = 3 * 4` 和 `*not italic*`，再看 重点。", inlineRendered.text)
        assertEquals(1, inlineRendered.spanStyles.size)
        assertEquals("重点", inlineRendered.text.substring(inlineRendered.spanStyles[0].start, inlineRendered.spanStyles[0].end))

        val fenced = "```kotlin\nval a = *b*\nval c = **d**\n```\n然后 *轻笑*。"
        val fencedRendered = ChatMarkdown.render(fenced)
        assertEquals("```kotlin\nval a = *b*\nval c = **d**\n```\n然后 轻笑。", fencedRendered.text)
        assertEquals(1, fencedRendered.spanStyles.size)
        assertEquals("轻笑", fencedRendered.text.substring(fencedRendered.spanStyles[0].start, fencedRendered.spanStyles[0].end))
    }

    @Test
    fun mapsCodePointCitationOffsetsBeforeStrippingMarkdownAndDeduplicatesUrls() {
        // 原始文本含 emoji（占 2 个 UTF-16 code units，1 个 Unicode code point）和 **加粗**
        val raw = "☀️**杭州今日晴**，适合去西湖。"
        // code points: 0='☀', 1=U+FE0F(变体选择符), 2='*', 3='*', 4='杭', 5='州', 6='今', 7='日', 8='晴', 9='*', 10='*', 11='，'...
        val endBoldCp = raw.codePointCount(0, raw.indexOf("，"))
        val endSentenceCp = raw.codePointCount(0, raw.length)
        val citations = listOf(
            Citation(url = "https://weather.example/hz", title = "天气", startIndex = 2, endIndex = endBoldCp),
            Citation(url = "https://travel.example/xihu", title = "西湖", startIndex = endBoldCp, endIndex = endSentenceCp),
            // 同一 URL 再次引用应复用编号 [1]
            Citation(url = "https://weather.example/hz", title = "天气", startIndex = endBoldCp, endIndex = endSentenceCp),
        )

        val rendered = ChatMarkdown.render(raw, citations)
        assertEquals("☀️杭州今日晴[1]，适合去西湖。[1][2]", rendered.text)

        val boldSpans = rendered.spanStyles.filter { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals(1, boldSpans.size)
        assertEquals("杭州今日晴", rendered.text.substring(boldSpans[0].start, boldSpans[0].end))

        val superscripts = rendered.spanStyles.filter { it.item.baselineShift == BaselineShift.Superscript }
        assertEquals(3, superscripts.size)
        assertEquals("[1]", rendered.text.substring(superscripts[0].start, superscripts[0].end))
        assertEquals("[1]", rendered.text.substring(superscripts[1].start, superscripts[1].end))
        assertEquals("[2]", rendered.text.substring(superscripts[2].start, superscripts[2].end))
    }

    @Test
    fun clampsOutOfRangeCitationOffsetsAndSkipsInvalidEntries() {
        val raw = "短句子"
        val citations = listOf(
            Citation(url = "https://a.example", endIndex = 999),
            Citation(url = "https://b.example", endIndex = -5),
            Citation(url = "   ", endIndex = 2),
        )
        val rendered = ChatMarkdown.render(raw, citations)
        assertEquals("短句子[1]", rendered.text)
    }

    @Test
    fun numbersCitationsByFirstUrlAndValidatesHttpUrls() {
        val numbered = ChatMarkdown.numberCitations(
            listOf(
                Citation(url = "https://a.example", title = null),
                Citation(url = "https://b.example", title = "B"),
                Citation(url = "https://a.example", title = "A"),
                Citation(url = " ", title = "blank"),
            ),
        )
        assertEquals(
            listOf(
                NumberedCitation(1, "https://a.example", "A"),
                NumberedCitation(2, "https://b.example", "B"),
            ),
            numbered,
        )
        assertTrue(ChatMarkdown.isValidHttpUrl("https://example.com/a?x=1"))
        assertTrue(ChatMarkdown.isValidHttpUrl("http://localhost/path"))
        assertFalse(ChatMarkdown.isValidHttpUrl("javascript:alert(1)"))
        assertFalse(ChatMarkdown.isValidHttpUrl("file:///etc/passwd"))
        assertFalse(ChatMarkdown.isValidHttpUrl(""))
    }
}
