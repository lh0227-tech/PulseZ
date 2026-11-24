package cc.pulsez.app.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown frontmatter 角色卡：字段映射、正文作人设、无 frontmatter 退化为纯文本。
 *。
 */
class MarkdownParserTest {

    @Test
    fun `frontmatter 角色卡字段映射`() {
        val parsed = MarkdownParser.parse(resource("card_markdown.md"))
        assertEquals(Format.MARKDOWN, parsed.format)
        // 中文名优先：name 常是英文 id。
        assertEquals("阿淮", parsed.name)
        assertEquals("你来了。外面下雨了吗？", parsed.greeting)
        assertNotNull(parsed.persona)
        val persona = requireNotNull(parsed.persona)
        assertTrue(persona.contains("便利店上夜班"))
        assertTrue(persona.contains("慢热"))
        assertTrue(persona.contains("南方人"))
        assertTrue(parsed.styleSamples.isNotEmpty())
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `detect 只认带 name 的 frontmatter`() {
        assertTrue(MarkdownParser.detect("---\nname: 阿淮\nage: 20\n---\n正文"))
        assertFalse(MarkdownParser.detect("她叫阿淮。"))
        assertFalse(MarkdownParser.detect("---\ntitle: 随便\n---\n正文"))
    }

    @Test
    fun `只写 name_zh 的 frontmatter 也认`() {
        val text = "---\nname_zh: 阿淮\nfirst_mes: 你来了。\n---\n正文。"
        assertTrue(MarkdownParser.detect(text))
        val parsed = MarkdownParser.parse(text)
        assertEquals(Format.MARKDOWN, parsed.format)
        assertEquals("阿淮", parsed.name)
    }

    @Test
    fun `没有 frontmatter 退化为纯文本`() {
        val parsed = MarkdownParser.parse("她叫阿淮，说话慢。")
        assertEquals(Format.TEXT, parsed.format)
        assertEquals("她叫阿淮，说话慢。", parsed.persona)
        assertEquals(null, parsed.name)
    }

    @Test
    fun `frontmatter 没闭合退化为纯文本`() {
        val parsed = MarkdownParser.parse("---\nname: 阿淮\n正文没闭合")
        assertEquals(Format.TEXT, parsed.format)
        assertTrue(requireNotNull(parsed.persona).contains("name: 阿淮"))
    }

    @Test
    fun `引号值里的冒号不会被截断`() {
        val text = "---\nname: 阿淮\npersonality: \"说话慢：慢条斯理\"\n---\n正文。"
        val parsed = MarkdownParser.parse(text)
        assertEquals(Format.MARKDOWN, parsed.format)
        assertTrue(requireNotNull(parsed.persona).contains("说话慢：慢条斯理"))
    }

    @Test
    fun `缺名字给 warning`() {
        val parsed = MarkdownParser.parse("---\ndescription: 一个不说话的姑娘。\n---\n正文。")
        assertEquals(null, parsed.name)
        assertTrue(parsed.warnings.contains(ImportWarnings.MISSING_NAME))
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/importer/$name")) { "缺少测试样本 $name" }
            .use { it.bufferedReader(Charsets.UTF_8).readText() }
}
