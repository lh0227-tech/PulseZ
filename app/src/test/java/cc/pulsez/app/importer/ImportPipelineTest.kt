package cc.pulsez.app.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入管线：格式判别优先级、边界提示、谁是"她"、人设草稿。
 * 规范见、。
 */
class ImportPipelineTest {

    // ---- 格式判别：JSON → Markdown → 聊天记录 → 纯文本 ----

    @Test
    fun `JSON 角色卡优先`() {
        val parsed = ImportPipeline.parse("""{"name":"阿淮","description":"她说话慢。"}""", Source.PASTE)
        assertEquals(Format.ST_CARD_JSON, parsed.format)
        assertEquals("阿淮", parsed.name)
    }

    @Test
    fun `Markdown 次之`() {
        val parsed = ImportPipeline.parse(resource("card_markdown.md"), Source.FILE)
        assertEquals(Format.MARKDOWN, parsed.format)
        assertEquals("阿淮", parsed.name)
    }

    @Test
    fun `聊天记录四轮以上`() {
        val parsed = ImportPipeline.parse(resource("chat_cn_plain.txt"), Source.FILE)
        assertEquals(Format.CHAT_LOG, parsed.format)
        assertEquals(10, parsed.turns.size)
        assertNotNull(parsed.persona)
        assertTrue(parsed.styleSamples.size in 3..5)
        assertTrue(ImportPipeline.isValid(parsed))
    }

    @Test
    fun `纯文本整段当人设`() {
        val text = "她叫阿淮，南方人，说话慢。"
        val parsed = ImportPipeline.parse(text, Source.PASTE)
        assertEquals(Format.TEXT, parsed.format)
        assertEquals(text, parsed.persona)
        assertTrue(parsed.turns.isEmpty())
        assertTrue(ImportPipeline.isValid(parsed))
    }

    @Test
    fun `三轮以下放弃拆轮次`() {
        val text = "你：在吗\n她：在\n你：那我去找你。"
        val parsed = ImportPipeline.parse(text, Source.PASTE)
        assertEquals(Format.TEXT, parsed.format)
        assertTrue(parsed.turns.isEmpty())
        assertEquals(text, parsed.persona)
        assertTrue(parsed.warnings.contains(ImportWarnings.TOO_FEW_TURNS))
    }

    @Test
    fun `两条对话当人设并提示`() {
        val parsed = ImportPipeline.parse(resource("chat_short_two_turns.txt"), Source.FILE)
        assertEquals(Format.TEXT, parsed.format)
        assertTrue(parsed.warnings.contains(ImportWarnings.TOO_FEW_TURNS))
        assertTrue(ImportPipeline.isValid(parsed))
    }

    @Test
    fun `带引号范文的人设不报对话太短`() {
        val parsed = ImportPipeline.parse(resource("persona_cn.txt"), Source.FILE)
        assertEquals(Format.TEXT, parsed.format)
        assertFalse(parsed.warnings.contains(ImportWarnings.TOO_FEW_TURNS))
        assertTrue(parsed.styleSamples.size in 3..5)
        assertTrue(parsed.styleSamples.contains("你今天有点安静"))
    }

    // ---- 边界 ----

    @Test
    fun `空内容给提示且无效`() {
        val parsed = ImportPipeline.parse("   \n\t ", Source.PASTE)
        assertEquals(listOf(ImportWarnings.EMPTY), parsed.warnings)
        assertFalse(ImportPipeline.isValid(parsed))
        assertEquals(listOf(ImportWarnings.EMPTY), ImportPipeline.warnings(parsed))
    }

    @Test
    fun `链接空内容和粘贴空内容说法不同`() {
        val parsed = ImportPipeline.parse("", Source.LINK)
        assertEquals(listOf(ImportWarnings.LINK_EMPTY), parsed.warnings)
    }

    @Test
    fun `乱码给提示`() {
        val parsed = ImportPipeline.parse("这段文字里夹着乱码字符：\uFFFD\uFFFD", Source.PASTE)
        assertTrue(parsed.warnings.contains(ImportWarnings.GARBLED))
    }

    @Test
    fun `超过 5MB 给提示`() {
        val huge = "长".repeat(ImportPipeline.MAX_INPUT_CHARS + 8)
        val parsed = ImportPipeline.parse(huge, Source.PASTE)
        assertEquals(Format.TEXT, parsed.format)
        assertTrue(parsed.warnings.contains(ImportWarnings.TOO_LARGE))
    }

    @Test
    fun `五万字到 5MB 之间提示内容很长`() {
        val big = "她".repeat(60_000)
        val parsed = ImportPipeline.parse(big, Source.PASTE)
        assertTrue(parsed.warnings.contains(ImportWarnings.LONG_CONTENT))
    }

    @Test
    fun `粘贴带 BOM 的 JSON 也能识别`() {
        val parsed = ImportPipeline.parse(
            "\uFEFF{\"name\":\"阿淮\",\"description\":\"她说话慢。\"}",
            Source.PASTE,
        )
        assertEquals(Format.ST_CARD_JSON, parsed.format)
        assertEquals("阿淮", parsed.name)
    }

    @Test
    fun `parseBytes 解码失败也提示换编码`() {
        val bytes = byteArrayOf(
            0x81.toByte(), 0x20, 0xFF.toByte(),
            0x81.toByte(), 0x20, 0xFF.toByte(),
            0x81.toByte(), 0x20, 0xFF.toByte(),
        )
        val parsed = ImportPipeline.parseBytes(bytes, Source.FILE)
        assertTrue(parsed.warnings.contains(ImportWarnings.GARBLED))
    }

    @Test
    fun `不像角色卡的 JSON 退化为文字`() {
        val parsed = ImportPipeline.parse("""{"foo":1,"name":null}""", Source.PASTE)
        assertEquals(Format.TEXT, parsed.format)
        assertTrue(parsed.warnings.contains(ImportWarnings.NOT_CARD))
    }

    // ---- 谁是"她" ----

    @Test
    fun `默认猜测话多的一方是她`() {
        val herMore = listOf(
            Turn(Speaker.USER, "嗯。"),
            Turn(Speaker.CHARACTER, "我今天在下班路上看到一家旧书店，进去转了半小时。"),
            Turn(Speaker.CHARACTER, "老板说那本诗集放在最里面的架子上，我找了好久。"),
        )
        assertEquals(Speaker.CHARACTER, ImportPipeline.guessSpeaker(herMore))

        val userMore = listOf(
            Turn(Speaker.USER, "我今天下班去了一趟旧书店，在里面转了很久。"),
            Turn(Speaker.USER, "然后我又去旁边的咖啡馆坐了半小时。"),
            Turn(Speaker.CHARACTER, "嗯。"),
        )
        assertEquals(Speaker.USER, ImportPipeline.guessSpeaker(userMore))
    }

    @Test
    fun `空对话默认保持 CHARACTER`() {
        assertEquals(Speaker.CHARACTER, ImportPipeline.guessSpeaker(emptyList()))
    }

    @Test
    fun `选左边是她时把两侧换过来`() {
        val parsed = ImportPipeline.parse(
            "你：我今天有点累。\n她：那就早点睡。\n你：可是还有活没做完。\n她：明天再做。",
            Source.PASTE,
        )
        assertEquals(Format.CHAT_LOG, parsed.format)
        val swapped = ImportPipeline.applySpeakerChoice(parsed, Speaker.USER)
        assertEquals(Speaker.CHARACTER, swapped.turns[0].speaker)
        assertEquals(Speaker.USER, swapped.turns[1].speaker)
        val persona = requireNotNull(swapped.persona)
        assertTrue(persona.contains("我今天有点累"))
        assertTrue(persona.contains("可是还有活没做完"))
    }

    @Test
    fun `选右边是她时保持原样`() {
        val parsed = ImportPipeline.parse("你：在吗\n她：在\n你：那我去找你。\n她：好。", Source.PASTE)
        val same = ImportPipeline.applySpeakerChoice(parsed, Speaker.CHARACTER)
        assertEquals(parsed.turns, same.turns)
        assertEquals(parsed.persona, same.persona)
    }

    @Test
    fun `未识别的说话人保持不变`() {
        val parsed = ParsedImport(
            name = null,
            persona = "草稿",
            greeting = null,
            styleSamples = emptyList(),
            turns = listOf(
                Turn(Speaker.UNKNOWN, "「今天好累」"),
                Turn(Speaker.USER, "「那就早点睡」"),
                Turn(Speaker.CHARACTER, "「还有活没做完」"),
                Turn(Speaker.UNKNOWN, "「明天再做」"),
            ),
            format = Format.CHAT_LOG,
            warnings = emptyList(),
        )
        val swapped = ImportPipeline.applySpeakerChoice(parsed, Speaker.USER)
        assertEquals(Speaker.UNKNOWN, swapped.turns[0].speaker)
        assertEquals(Speaker.CHARACTER, swapped.turns[1].speaker)
        assertEquals(Speaker.USER, swapped.turns[2].speaker)
        assertEquals(Speaker.UNKNOWN, swapped.turns[3].speaker)
    }

    // ---- 人设草稿 ----

    @Test
    fun `人设草稿不超过 40000 字`() {
        val turns = (1..2500).map { i ->
            Turn(Speaker.CHARACTER, "第${i}句台词，用来测试人设草稿的长度上限，再补一点内容。")
        }
        val persona = ImportPipeline.personaFromTurns(turns)
        assertTrue(persona.isNotEmpty())
        assertTrue(persona.length <= ImportPipeline.PERSONA_MAX_CHARS)
        assertTrue(persona.contains("TA 怎么说话"))
    }

    @Test
    fun `2万字和4万字人设完整保留不触发超长提醒，40001字触发`() {
        val p20k = "林".repeat(20_000)
        val parsed20k = ImportPipeline.parse(p20k, Source.PASTE)
        assertEquals(p20k, parsed20k.persona)
        assertFalse(parsed20k.warnings.contains(ImportWarnings.PERSONA_TOO_LONG))

        val p40k = "林".repeat(40_000)
        val parsed40k = ImportPipeline.parse(p40k, Source.PASTE)
        assertEquals(p40k, parsed40k.persona)
        assertFalse(parsed40k.warnings.contains(ImportWarnings.PERSONA_TOO_LONG))

        val p40kPlus1 = "林".repeat(40_001)
        val parsed40kPlus1 = ImportPipeline.parse(p40kPlus1, Source.PASTE)
        assertEquals(p40kPlus1, parsed40kPlus1.persona)
        assertTrue(parsed40kPlus1.warnings.contains(ImportWarnings.PERSONA_TOO_LONG))
    }

    @Test
    fun `人设草稿带 3 到 5 条语气样本`() {
        val turns = (1..20).map { i -> Turn(Speaker.CHARACTER, "第${i}句台词，语气不太一样。") }
        val persona = ImportPipeline.personaFromTurns(turns)
        val sampleLines = persona.lines().filter { it.startsWith("「") }
        assertTrue(sampleLines.size in 3..5)
    }

    @Test
    fun `语气样本最多 5 条且都非空`() {
        val turns = (1..20).map { i -> Turn(Speaker.CHARACTER, "第${i}句台词，语气不太一样。") }
        val samples = ImportPipeline.styleSamples(turns)
        assertTrue(samples.size in 3..5)
        assertTrue(samples.all { it.isNotBlank() })
    }

    @Test
    fun `没有角色台词时草稿为空`() {
        assertEquals("", ImportPipeline.personaFromTurns(emptyList()))
        assertEquals("", ImportPipeline.personaFromTurns(listOf(Turn(Speaker.USER, "你好。"))))
    }

    // ---- 有效性 ----

    @Test
    fun `isValid 与 warnings`() {
        val empty = ParsedImport(
            name = null,
            persona = null,
            greeting = null,
            styleSamples = emptyList(),
            turns = emptyList(),
            format = Format.TEXT,
            warnings = listOf(ImportWarnings.EMPTY),
        )
        assertFalse(ImportPipeline.isValid(empty))
        assertEquals(listOf(ImportWarnings.EMPTY), ImportPipeline.warnings(empty))
        assertTrue(ImportPipeline.isValid(ImportPipeline.parse("她叫阿淮。", Source.PASTE)))
    }

    // ---- 8 个样本文件整体回归 ----

    @Test
    fun `八个样本文件都能解析出内容`() {
        val cases = listOf(
            "chat_cn_plain.txt" to Format.CHAT_LOG,
            "chat_mixed_timestamps.txt" to Format.CHAT_LOG,
            "chat_short_two_turns.txt" to Format.TEXT,
            "persona_cn.txt" to Format.TEXT,
            "card_markdown.md" to Format.MARKDOWN,
            "st_card_v1.json" to Format.ST_CARD_JSON,
            "card_chara_v3.json" to Format.ST_CARD_JSON,
        )
        for ((file, expected) in cases) {
            val parsed = ImportPipeline.parse(resource(file), Source.FILE)
            assertEquals(file, expected, parsed.format)
            assertTrue(file, ImportPipeline.isValid(parsed))
        }
        // 第 8 个样本是 GB18030 编码的字节文件，走 parseBytes。
        val gbk = ImportPipeline.parseBytes(resourceBytes("gbk_chinese.txt"), Source.FILE)
        assertEquals(Format.TEXT, gbk.format)
        assertTrue(ImportPipeline.isValid(gbk))
        assertTrue(requireNotNull(gbk.persona).contains("阿淮"))
    }

    @Test
    fun `时间戳混排样本认出时间和未知说话人`() {
        val parsed = ImportPipeline.parse(resource("chat_mixed_timestamps.txt"), Source.FILE)
        assertEquals(Format.CHAT_LOG, parsed.format)
        assertEquals(7, parsed.turns.size)
        assertNotNull(parsed.turns[0].at)
        assertEquals("想什么？", parsed.turns[2].text)
        assertNull(parsed.turns[2].at)
        assertEquals(Speaker.UNKNOWN, parsed.turns[4].speaker)
        assertEquals("你想多了。", parsed.turns[4].text)
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/importer/$name")) { "缺少测试样本 $name" }
            .use { it.bufferedReader(Charsets.UTF_8).readText() }

    private fun resourceBytes(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/importer/$name")) { "缺少测试样本 $name" }
            .use { it.readBytes() }
}
