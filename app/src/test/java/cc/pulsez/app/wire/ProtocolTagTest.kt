package cc.pulsez.app.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTagTest {

    @Test
    fun `正常标签被剥离`() {
        val parsed = ProtocolTag.parse("我在。\n[[pz:emo=shy|rel=close]]")
        assertEquals("我在。", parsed.text)
        assertEquals("shy", parsed.emotion)
        assertEquals("close", parsed.relation)
    }

    @Test
    fun `未知取值被丢弃`() {
        val parsed = ProtocolTag.parse("嗯[[pz:emo=furious|rel=alien]]")
        assertEquals("嗯", parsed.text)
        assertNull(parsed.emotion)
        assertNull(parsed.relation)
    }

    @Test
    fun `半截标签在流式中被截掉`() {
        assertEquals("今天", ProtocolTag.stripPartial("今天[[pz:emo=ha"))
        assertEquals("今天", ProtocolTag.parse("今天[[pz:emo").text)
    }

    @Test
    fun `流式末尾已闭合的完整协议标签也会被提前剥离`() {
        assertEquals("今天", ProtocolTag.stripPartial("今天\n[[pz:emo=happy|rel=close]]"))
        assertEquals("今天", ProtocolTag.stripPartial("今天\n[[pz:emo=shy]]\n"))
        assertEquals("今天", ProtocolTag.stripPartial("今天[[pz:emo=calm]] [[pz:rel"))
    }

    @Test
    fun `没有标签时原文不变`() {
        val parsed = ProtocolTag.parse("就这样。")
        assertEquals("就这样。", parsed.text)
        assertNull(parsed.emotion)
    }
}
