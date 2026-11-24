package cc.pulsez.app.core

import cc.pulsez.app.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 识图历史策略的纯函数测试。
 *
 * 三条规则：
 * 1. 「最近 1 个带图用户轮」= 倒序第一条用户消息且带活图（assistant 永不带图）；
 * 2. 更早的图片消息在 prompt 里留「[图片]」占位，**保留用户当时打的字**；
 * 3. 组装结果里 ③ 段历史的下标计算（把图块挂回那条历史消息）。
 *
 * 只测纯函数，不启动 Room 或 AppContainer。
 */
class VisionHistoryTest {

    private fun row(id: String, role: String, content: String = "x", at: Long = 0L) = MessageEntity(
        id = id,
        threadId = "t",
        segmentId = "s",
        role = role,
        content = content,
        tokens = 1,
        emotion = null,
        status = "OK",
        draft = null,
        usageIn = null,
        usageOut = null,
        previousContent = null,
        createdAt = at,
    )

    @Test
    fun `最近一个带图用户轮取倒序第一条用户图片消息`() {
        val rows = listOf(
            row("u1", "user", at = 1),
            row("a1", "assistant", at = 2),
            row("u2", "user", at = 3),
            row("a2", "assistant", at = 4),
            row("u3", "user", at = 5),
        )
        val imageCounts = mapOf("u1" to 2, "u3" to 1)
        assertEquals("u3", ChatEngine.recentImageUserMessageId(rows, imageCounts))
    }

    @Test
    fun `assistant 即使有图也不算`() {
        val rows = listOf(row("u1", "user"), row("a1", "assistant"))
        assertEquals(null, ChatEngine.recentImageUserMessageId(rows, mapOf("a1" to 1)))
    }

    @Test
    fun `没有带图消息返回 null`() {
        assertNull(ChatEngine.recentImageUserMessageId(listOf(row("u1", "user")), emptyMap()))
        assertNull(ChatEngine.recentImageUserMessageId(emptyList(), mapOf("u1" to 1)))
    }

    @Test
    fun `更早的纯图消息只留占位`() {
        assertEquals("[图片]", ChatEngine.promptContentForMessage("[图片]", hasImage = true, keepImageBlock = false))
        assertEquals("[图片]", ChatEngine.promptContentForMessage("", hasImage = true, keepImageBlock = false))
        assertEquals(
            "[图片]",
            ChatEngine.promptContentForMessage("   ", hasImage = true, keepImageBlock = false),
        )
    }

    @Test
    fun `更早的图文消息保留文字并加占位`() {
        assertEquals(
            "[图片] 这张图里是什么",
            ChatEngine.promptContentForMessage(
                "这张图里是什么",
                hasImage = true,
                keepImageBlock = false,
            ),
        )
        // 已带占位不重复加。
        assertEquals(
            "[图片] 这张图里是什么",
            ChatEngine.promptContentForMessage(
                "[图片] 这张图里是什么",
                hasImage = true,
                keepImageBlock = false,
            ),
        )
    }

    @Test
    fun `保留图块的那一轮与无图消息原文不动`() {
        assertEquals(
            "这张图里是什么",
            ChatEngine.promptContentForMessage("这张图里是什么", hasImage = true, keepImageBlock = true),
        )
        assertEquals(
            "普通一句话",
            ChatEngine.promptContentForMessage("普通一句话", hasImage = false, keepImageBlock = false),
        )
    }

    @Test
    fun `组装结果里历史段起始下标`() {
        // 结构：[system] [history×3] [system ④] [user ⑤]
        assertEquals(1, ChatEngine.historyStartIndex(assembledSize = 6, historySize = 3))
        // 有编年史：[system] [system 编年史] [history×3] [system ④] [user ⑤]
        assertEquals(2, ChatEngine.historyStartIndex(assembledSize = 7, historySize = 3))
    }

    @Test
    fun `占位常量与落库口径一致`() {
        assertEquals("[图片]", ChatEngine.IMAGE_PLACEHOLDER)
    }
}
