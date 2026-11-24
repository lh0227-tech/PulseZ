package cc.pulsez.app.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮次解析：中英混排标记、时间戳前缀、「」引号行、续行合并。
 *。
 */
class TextParserTest {

    @Test
    fun `中文标记分轮`() {
        val result = TextParser.parse("你：今天面试怎么样？\n她：还行吧。\n你：那你紧张吗？\n她：有点。")
        assertEquals(4, result.turns.size)
        assertEquals(4, result.explicitSpeakerTurns)
        assertEquals(Speaker.USER, result.turns[0].speaker)
        assertEquals("今天面试怎么样？", result.turns[0].text)
        assertEquals(Speaker.CHARACTER, result.turns[1].speaker)
    }

    @Test
    fun `她说和全角冒号`() {
        val result = TextParser.parse("我说：今晚吃什么？\n她说：随便。\n用户：那就火锅。\n对方：行。")
        assertEquals(4, result.turns.size)
        assertEquals(
            listOf(Speaker.USER, Speaker.CHARACTER, Speaker.USER, Speaker.CHARACTER),
            result.turns.map { it.speaker },
        )
    }

    @Test
    fun `英文标记大小写不敏感`() {
        val result = TextParser.parse("USER: hi\nAssistant: hey\nU: how are you\nAI: fine")
        assertEquals(4, result.turns.size)
        assertEquals(
            listOf(Speaker.USER, Speaker.CHARACTER, Speaker.USER, Speaker.CHARACTER),
            result.turns.map { it.speaker },
        )
    }

    @Test
    fun `中英混排`() {
        val result = TextParser.parse("我：在吗\nAssistant: yes\n她：好\nU: ok")
        assertEquals(4, result.turns.size)
        assertEquals(
            listOf(Speaker.USER, Speaker.CHARACTER, Speaker.CHARACTER, Speaker.USER),
            result.turns.map { it.speaker },
        )
    }

    @Test
    fun `方括号时间戳前缀`() {
        val result = TextParser.parse("[2026-11-18 21:03] 你：在吗\n[2026-11-18 21:04] 她：在")
        assertEquals(2, result.turns.size)
        assertEquals("在吗", result.turns[0].text)
        assertNotNull(result.turns[0].at)
        assertNotNull(result.turns[1].at)
    }

    @Test
    fun `裸时间戳和日期时间冒号`() {
        val result = TextParser.parse("21:03 你：在吗\n21:04 她：在\n2026-11-18 21:05: 我刚到。")
        assertEquals(3, result.turns.size)
        assertEquals("在吗", result.turns[0].text)
        // 只有时分没有日期，换算不出毫秒，别硬编一个"今天"。
        assertNull(result.turns[0].at)
        // 时间戳后面没有说话人：算一条未知说话人的消息，不能当成上一条的续行。
        assertEquals(Speaker.UNKNOWN, result.turns[2].speaker)
        assertEquals("我刚到。", result.turns[2].text)
        assertNotNull(result.turns[2].at)
    }

    @Test
    fun `不像时间戳的行不剥`() {
        // 没有冒号也没有说话人标记，时间戳后面跟着正文，不能吃掉。
        val text = "2026-11-18 21:03 我们见面了"
        val result = TextParser.parse(text)
        assertEquals(0, result.turns.size)
        assertEquals(text, result.preamble)
    }

    @Test
    fun `整行引号算一轮`() {
        val result = TextParser.parse("「今天好累」\n「那就早点睡」\n「还有活没做完」\n「明天再做」")
        assertEquals(4, result.turns.size)
        assertTrue(result.turns.all { it.speaker == Speaker.UNKNOWN })
        assertEquals(0, result.explicitSpeakerTurns)
        assertEquals("今天好累", result.turns[0].text)
    }

    @Test
    fun `多行续写合并到同一条`() {
        val result = TextParser.parse("你：第一行\n第二行\n她：嗯")
        assertEquals(2, result.turns.size)
        assertEquals("第一行\n第二行", result.turns[0].text)
    }

    @Test
    fun `没有说话人标记返回零轮`() {
        val text = "她叫阿淮，南方人，说话慢。"
        val result = TextParser.parse(text)
        assertEquals(0, result.turns.size)
        assertEquals(0, result.explicitSpeakerTurns)
        assertEquals(text, result.preamble)
    }

    @Test
    fun `少于四轮不算聊天记录`() {
        val three = TextParser.parse("你：在吗\n她：在\n你：那我去找你。")
        assertEquals(3, three.turns.size)
        assertNull(TextParser.parseChatLog("你：在吗\n她：在\n你：那我去找你。"))
        assertTrue(!TextParser.isChatLog(three))

        val four = TextParser.parse("你：在吗\n她：在\n你：那我去找你。\n她：好。")
        assertNotNull(TextParser.parseChatLog("你：在吗\n她：在\n你：那我去找你。\n她：好。"))
        assertTrue(TextParser.isChatLog(four))
    }
}
