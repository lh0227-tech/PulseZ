package cc.pulsez.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 朗读文本清洗。纯 JVM，不碰 Android / Robolectric。
 *
 * （「剥 [[pz:…]]、去 Markdown 记号、截断 2000 字」）、
 *（送 TTS 前必须剥协议标签，否则会被念出来）、
 * （TTS 清洗/截断必须是可测的纯函数）。
 */
class TtsTextCleanerTest {

    // ------------------------------------------------------------ 协议标签

    @Test
    fun `strips protocol tag at the end`() {
        val raw = "今天也在等你。[[pz:emo=shy|rel=close]]"
        assertEquals("今天也在等你。", TtsTextCleaner.clean(raw))
    }

    @Test
    fun `strips protocol tag in the middle and multiple tags`() {
        val raw = "第一句。[[pz:emo=calm]]第二句。[[pz:rel=normal]]"
        assertEquals("第一句。 第二句。", TtsTextCleaner.clean(raw))
    }

    @Test
    fun `strips half open protocol tag at the end`() {
        // 流式被截断/模型输出异常时可能出现没闭合的标签；不能把 "[[pz:emo=shy" 念出来。
        val raw = "我在呢。[[pz:emo=shy"
        assertEquals("我在呢。", TtsTextCleaner.clean(raw))
    }

    @Test
    fun `protocol tag alone becomes empty`() {
        assertEquals("", TtsTextCleaner.clean("[[pz:emo=calm|rel=normal]]"))
        assertEquals("", TtsTextCleaner.clean("  [[pz:emo=calm]]  "))
    }

    // ------------------------------------------------------------ Markdown

    @Test
    fun `strips fenced code block entirely`() {
        val raw = "看这个：\n```kotlin\nval x = 1\nprintln(x)\n```\n就是这样。"
        val cleaned = TtsTextCleaner.clean(raw)
        assertFalse("代码块内容不能念出来：$cleaned", cleaned.contains("val x"))
        assertTrue(cleaned.contains("看这个"))
        assertTrue(cleaned.contains("就是这样"))
    }

    @Test
    fun `strips inline code backticks but keeps text`() {
        assertEquals("运行 fun 就好了。", TtsTextCleaner.clean("运行 `fun` 就好了。"))
    }

    @Test
    fun `strips markdown emphasis markers`() {
        assertEquals(
            "很重要。好的。不要这样。",
            TtsTextCleaner.clean("**很重要**。__好的__。~~不要这样~~。"),
        )
        assertEquals("斜体也去掉。", TtsTextCleaner.clean("*斜体*也去掉。"))
        assertEquals("下划线斜体。", TtsTextCleaner.clean("_下划线斜体_。"))
    }

    @Test
    fun `strips heading list quote and horizontal rule markers`() {
        val raw = """
            ## 今天
            - 第一件事
            - 第二件事
            1. 第三件事
            > 一句引用
            ---
            收尾。
        """.trimIndent()
        val cleaned = TtsTextCleaner.clean(raw)
        assertFalse("不能念出 #：$cleaned", cleaned.contains("#"))
        assertFalse("不能念出列表记号：$cleaned", cleaned.contains("- "))
        assertFalse("不能念出引用记号：$cleaned", cleaned.contains(">"))
        assertTrue(cleaned.contains("今天"))
        assertTrue(cleaned.contains("第一件事"))
        assertTrue(cleaned.contains("第三件事"))
        assertTrue(cleaned.contains("一句引用"))
        // 有序列表保留序号（改成顿号，念起来是「1、第三件事」）。
        assertTrue("有序列表序号要保留：$cleaned", cleaned.contains("1、"))
    }

    @Test
    fun `strips link target and keeps link text`() {
        // 链接地址念出来是噪音；文字要保留。
        assertEquals(
            "可以看看这个。",
            TtsTextCleaner.clean("可以看看[这个](https://example.com/a?b=c)。"),
        )
    }

    @Test
    fun `strips image markup`() {
        val cleaned = TtsTextCleaner.clean("给你看![猫](https://example.com/cat.png)可爱吧。")
        assertFalse("图片地址不能念出来：$cleaned", cleaned.contains("http"))
        assertFalse("图片 alt 不念：$cleaned", cleaned.contains("猫"))
        assertTrue(cleaned.contains("给你看"))
        assertTrue(cleaned.contains("可爱吧"))
    }

    @Test
    fun `strips table markup`() {
        val raw = """
            | 名字 | 年龄 |
            | --- | --- |
            | 小明 | 18 |
        """.trimIndent()
        val cleaned = TtsTextCleaner.clean(raw)
        assertFalse("不能念出竖线：$cleaned", cleaned.contains("|"))
        assertFalse("不能念出表格分隔线：$cleaned", cleaned.contains("---"))
        assertTrue(cleaned.contains("小明"))
    }

    @Test
    fun `collapses whitespace and newlines`() {
        assertEquals("第一句。 第二句。", TtsTextCleaner.clean("第一句。\n\n\n第二句。"))
        assertEquals("你好。", TtsTextCleaner.clean("  你好。  "))
    }

    // ------------------------------------------------------------ 空文本

    @Test
    fun `blank and whitespace only become empty`() {
        assertEquals("", TtsTextCleaner.clean(""))
        assertEquals("", TtsTextCleaner.clean("   \n\t  "))
        assertEquals("", TtsTextCleaner.clean("```\ncode only\n```"))
    }

    // ------------------------------------------------------------ 截断（不截半句）

    @Test
    fun `short text is untouched`() {
        val text = "今天也在等你。"
        assertEquals(text, TtsTextCleaner.clean(text, maxChars = 2_000))
    }

    @Test
    fun `long text truncates at a whole sentence`() {
        // 上限刚好容得下第一句、容不下第二句 → 只读第一句（整句）。
        val raw = "第一句话在这里。第二句话有点长，而且还有第三句。"
        val max = 9
        val cleaned = TtsTextCleaner.clean(raw, maxChars = max)
        assertEquals("第一句话在这里。", cleaned)
        assertTrue("不能超上限", cleaned.length <= max)
        // 不能截出半句：结果必须以句末标点结尾。
        assertTrue("必须是整句", cleaned.endsWith("。"))
    }

    @Test
    fun `truncation never returns a partial second sentence`() {
        // 第一句 5 字 + 句号，第二句很长：上限放在第二句中间时，必须只留第一句。
        val raw = "好的。接下来我要说一件很长很长很长的事情，关于今天的天气。"
        val cleaned = TtsTextCleaner.clean(raw, maxChars = 12)
        assertEquals("好的。", cleaned)
    }

    @Test
    fun `truncation falls back to clause punctuation for one huge sentence`() {
        // 整段没有句号，只有逗号：退到逗号截断，至少不从词中间切。
        val raw = "今天有一点累，但是想到明天要去公园，就觉得还好，先这样吧"
        val cleaned = TtsTextCleaner.clean(raw, maxChars = 16)
        assertTrue("不能超上限：${cleaned.length}", cleaned.length <= 16)
        assertTrue("应退到逗号处：$cleaned", cleaned.endsWith("，") || cleaned.endsWith("累"))
        assertEquals("今天有一点累，", cleaned)
    }

    @Test
    fun `truncation hard cuts only when no punctuation at all`() {
        val raw = "a".repeat(100)
        val cleaned = TtsTextCleaner.clean(raw, maxChars = 10)
        assertEquals(10, cleaned.length)
    }

    @Test
    fun `zero or negative max becomes empty`() {
        assertEquals("", TtsTextCleaner.clean("你好。", maxChars = 0))
        assertEquals("", TtsTextCleaner.clean("你好。", maxChars = -1))
    }
}
