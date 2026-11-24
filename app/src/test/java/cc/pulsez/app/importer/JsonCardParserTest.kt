package cc.pulsez.app.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SillyTavern JSON 角色卡：v1 平铺字段、chara_card_v3 的 data 节点、缺字段处理。
 *。
 */
class JsonCardParserTest {

    @Test
    fun `SillyTavern 平铺字段`() {
        val parsed = requireNotNull(JsonCardParser.parse(resource("st_card_v1.json")))
        assertEquals(Format.ST_CARD_JSON, parsed.format)
        assertEquals("沈砚", parsed.name)
        assertEquals("门没锁。你自己找地方坐吧。", parsed.greeting)
        val persona = requireNotNull(parsed.persona)
        assertTrue(persona.contains("旧书店"))
        assertTrue(persona.contains("安静、固执"))
        assertTrue(persona.contains("雨天的旧书店"))
        assertEquals(3, parsed.styleSamples.size)
        assertTrue(parsed.styleSamples.contains("不卖。那本是我自己的。"))
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `chara_card_v3 取 data 节点`() {
        val parsed = requireNotNull(JsonCardParser.parse(resource("card_chara_v3.json")))
        assertEquals(Format.ST_CARD_JSON, parsed.format)
        assertEquals("林晚", parsed.name)
        assertEquals("这里是《晚点》，我是林晚。今晚想聊点什么？", parsed.greeting)
        assertTrue(requireNotNull(parsed.persona).contains("电台夜间节目"))
        assertEquals(1, parsed.styleSamples.size)
    }

    @Test
    fun `缺字段给 null 并记 warning`() {
        val json = """{"spec":"chara_card_v3","data":{"name":"只有名字"}}"""
        val parsed = requireNotNull(JsonCardParser.parse(json))
        assertEquals("只有名字", parsed.name)
        assertNull(parsed.persona)
        assertNull(parsed.greeting)
        assertTrue(parsed.warnings.contains(ImportWarnings.MISSING_PERSONA))
        assertTrue(parsed.warnings.contains(ImportWarnings.MISSING_GREETING))
        assertTrue(parsed.warnings.contains(ImportWarnings.MISSING_SCENARIO))
        assertTrue(parsed.warnings.contains(ImportWarnings.MISSING_EXAMPLES))
    }

    @Test
    fun `没有名字的卡给 warning`() {
        val parsed = requireNotNull(JsonCardParser.parse("""{"description":"一个只在夜里出现的人。"}"""))
        assertNull(parsed.name)
        assertEquals("一个只在夜里出现的人。", parsed.persona)
        assertTrue(parsed.warnings.contains(ImportWarnings.MISSING_NAME))
    }

    @Test
    fun `first_mes 缺失时用 alternate_greetings`() {
        val json = """{"spec":"chara_card_v3","data":{"name":"林晚","first_mes":null,"alternate_greetings":["还没睡？"]}}"""
        val parsed = requireNotNull(JsonCardParser.parse(json))
        assertEquals("还没睡？", parsed.greeting)
        assertFalse(parsed.warnings.contains(ImportWarnings.MISSING_GREETING))
    }

    @Test
    fun `不是角色卡返回 null`() {
        assertNull(JsonCardParser.parse("""{"foo":1}"""))
        assertNull(JsonCardParser.parse("[]"))
        assertNull(JsonCardParser.parse("{oops"))
    }

    @Test
    fun `detect 看开头和字段`() {
        assertTrue(JsonCardParser.detect("""{"name":"阿淮"}"""))
        assertTrue(JsonCardParser.detect("""{"description":"..."}"""))
        assertTrue(JsonCardParser.detect("""{"spec":"chara_card_v3","data":{}}"""))
        assertFalse(JsonCardParser.detect("""{"foo":1}"""))
        assertFalse(JsonCardParser.detect("name: 阿淮"))
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/importer/$name")) { "缺少测试样本 $name" }
            .use { it.bufferedReader(Charsets.UTF_8).readText() }
}
