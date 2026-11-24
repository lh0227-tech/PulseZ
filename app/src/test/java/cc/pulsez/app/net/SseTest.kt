package cc.pulsez.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseTest {

    @Test
    fun `data without space is parsed`() {
        val event = Sse.parse("data:{\"choices\":[]}")
        assertEquals("{\"choices\":[]}", event?.data)
        assertNull(event?.event)
        assertNull(event?.id)
    }

    @Test
    fun `data with one space is parsed`() {
        val event = Sse.parse("data: {\"a\":1}")
        assertEquals("{\"a\":1}", event?.data)
    }

    @Test
    fun `data with two spaces keeps one space in payload`() {
        // 协议只剥一个前导空格，第二个空格属于内容；[DONE] 判断会 trim，JSON 解析也不受影响。
        val event = Sse.parse("data:  {\"a\":1}")
        assertEquals(" {\"a\":1}", event?.data)
    }

    @Test
    fun `comment keep-alive line is skipped`() {
        assertNull(Sse.parse(": keep-alive"))
        assertNull(Sse.parse(":keep-alive"))
    }

    @Test
    fun `empty and blank lines are skipped`() {
        assertNull(Sse.parse(""))
        assertNull(Sse.parse("   "))
    }

    @Test
    fun `event only frame is parsed with blank data`() {
        val event = Sse.parse("event: error")
        assertEquals("error", event?.event)
        assertTrue(event?.data.isNullOrEmpty())
    }

    @Test
    fun `done marker is data not a special token at parse layer`() {
        assertEquals(Sse.DONE, Sse.parse("data: [DONE]")?.data)
        assertEquals(Sse.DONE, Sse.parse("data:[DONE]")?.data)
    }

    @Test
    fun `crlf line ending is stripped`() {
        val event = Sse.parse("data: [DONE]\r\n")
        assertEquals(Sse.DONE, event?.data)
        assertNull(Sse.parse("\r\n"))
    }

    @Test
    fun `id field is parsed`() {
        val event = Sse.parse("id: 42")
        assertEquals("42", event?.id)
        assertNull(event?.data?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun `bare data field is an empty event`() {
        val event = Sse.parse("data:")
        assertEquals("", event?.data)
    }

    @Test
    fun `retry and unknown fields are ignored`() {
        assertNull(Sse.parse("retry: 1000"))
        assertNull(Sse.parse("ping"))
    }

    @Test
    fun `bom at start of first line is removed`() {
        val event = Sse.parse("\uFEFFdata: [DONE]")
        assertEquals(Sse.DONE, event?.data)
    }
}
