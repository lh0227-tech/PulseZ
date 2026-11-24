package cc.pulsez.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式结束整段播报一次的纯判定。
 *
 * 纯 JVM：`ChatAnnounce` 是 `ChatScreen.kt` 末尾的顶层纯 Kotlin object，不引用 Compose 类型，
 * 因此不需要 Robolectric。Compose 语义接线本身（`Text` 上的 `liveRegion`）无法在 JVM 单测里
 * 验证，真机 TalkBack 行为未覆盖。
 *
 * 判据来源：「流式文本必须关闭自动播报，结束后整段播报一次」；
 *。
 */
class ChatAnnounceTest {

    @Test
    fun `流式期间不挂 liveRegion 避免每段 delta 重复播报`() {
        assertFalse(
            "正在流式的最新一条：正文还在变，挂 liveRegion 会让 TalkBack 按 delta 反复播报",
            ChatAnnounce.shouldAnnounce(streaming = true, isNewestMessage = true),
        )
    }

    @Test
    fun `流式结束后最新一条挂 liveRegion 整段播报一次`() {
        assertTrue(
            "这一轮已结束（streaming=false）的最新一条：整段正文播报一次",
            ChatAnnounce.shouldAnnounce(streaming = false, isNewestMessage = true),
        )
    }

    @Test
    fun `历史消息不挂 liveRegion 翻历史时不被当成新内容再播报`() {
        assertFalse(
            "非最新一条：不自动播报（用户主动聚焦 / 滑动时仍可正常朗读）",
            ChatAnnounce.shouldAnnounce(streaming = false, isNewestMessage = false),
        )
        assertFalse(
            "流式期间的同屏历史消息：同样不挂",
            ChatAnnounce.shouldAnnounce(streaming = true, isNewestMessage = false),
        )
    }
}
