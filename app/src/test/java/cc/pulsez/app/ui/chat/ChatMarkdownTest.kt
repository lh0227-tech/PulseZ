package cc.pulsez.app.ui.chat

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
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
}
