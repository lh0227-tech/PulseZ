package cc.pulsez.app.core

import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.mem.AssembleInput
import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.mem.PromptAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：**当前用户消息在 prompt 里只能出现一次**。
 *
 * bug：1:1 的发送时序是「先落库、再组装」——
 * `ChatViewModel.send` 先 `append(role="user")`，`ChatEngine.prepare` 再从 active 段读历史
 * （已含刚落的这条），`PromptAssembler` 又在 ⑤ 段追加一次。修法是 prepare 按 id 剔除。
 *
 * **纯 JVM**：不启动 AppContainer / Room / Android。被测对象是
 * [ChatEngine.historyRowsForPrompt]（companion 纯函数）与 `PromptAssembler.assemble` 纯函数；
 * `MessageEntity` / `MemMessage` 都是普通 data class。
 */
class PromptDedupTest {

    private val threadId = "thread-1"
    private val segmentId = "segment-1"
    private val currentText = "我们周末去爬山吧"

    // ------------------------------------------------------------------ 工具

    private fun message(
        id: String,
        role: String,
        content: String,
        createdAt: Long,
        speakerCharacterId: String? = null,
    ): MessageEntity = MessageEntity(
        id = id,
        threadId = threadId,
        segmentId = segmentId,
        role = role,
        content = content,
        tokens = content.length / 2 + 4,
        emotion = null,
        status = "OK",
        draft = null,
        usageIn = null,
        usageOut = null,
        previousContent = null,
        createdAt = createdAt,
        deletedAt = null,
        speakerCharacterId = speakerCharacterId,
    )

    /** 与 `ChatEngine.prepare` 内部 `MessageEntity.toMem` 同口径（纯映射，不碰 DB）。 */
    private fun MessageEntity.toMem(): MemMessage = MemMessage(
        role = role,
        content = content,
        createdAt = createdAt,
    )

    /** 本段历史：两条旧消息 + 刚落库的本轮用户消息（时间正序，与 bySegment 一致）。 */
    private fun rowsWithCurrentUserMessage(): List<MessageEntity> = listOf(
        message("u0", MemMessage.ROLE_USER, "在吗", createdAt = 1_000L),
        message("a0", MemMessage.ROLE_ASSISTANT, "在的，怎么啦", createdAt = 2_000L),
        message("u1", MemMessage.ROLE_USER, currentText, createdAt = 3_000L),
    )

    /** 用给定历史行组装一次 prompt（① 段只放最小人设，③=history，⑤=userText）。 */
    private fun assemblePrompt(
        history: List<MessageEntity>,
        userText: String = currentText,
    ): List<MemMessage> = PromptAssembler.assemble(
        AssembleInput(
            personaLayer = "你是阿淮。",
            protocolEnabled = true,
            history = history.map { it.toMem() },
            userMessage = userText,
        ),
    )

    private fun List<MemMessage>.countUserLine(text: String): Int =
        count { it.role == MemMessage.ROLE_USER && it.content == text }

    // ------------------------------------------------------------------ historyRowsForPrompt

    @Test
    fun excludesOnlyTheGivenMessageId() {
        val rows = rowsWithCurrentUserMessage()

        val filtered = ChatEngine.historyRowsForPrompt(rows, excludeMessageId = "u1")

        assertEquals(2, filtered.size)
        assertFalse("本轮刚落库的消息不能留在 ③ 段", filtered.any { it.id == "u1" })
        assertTrue(filtered.any { it.id == "u0" })
        assertTrue(filtered.any { it.id == "a0" })
    }

    @Test
    fun regenerationExcludesCurrentUserAndOldReplyByIdButKeepsEarlierHistory() {
        val rows = listOf(
            message("u0", MemMessage.ROLE_USER, "在吗", createdAt = 1_000L),
            message("a0", MemMessage.ROLE_ASSISTANT, "在的", createdAt = 2_000L),
            message("u1", MemMessage.ROLE_USER, currentText, createdAt = 3_000L),
            message("a1", MemMessage.ROLE_ASSISTANT, "旧回复", createdAt = 4_000L),
        )

        val filtered = ChatEngine.historyRowsForPrompt(
            rows,
            excludeMessageId = "u1",
            additionalExcludedMessageIds = setOf("a1"),
        )

        assertEquals(listOf("u0", "a0"), filtered.map { it.id })
    }

    @Test
    fun nullExcludeIdKeepsAllRowsInOriginalOrder() {
        val rows = rowsWithCurrentUserMessage()

        val filtered = ChatEngine.historyRowsForPrompt(rows, excludeMessageId = null)

        // 默认参数路径 = 修复前行为，既有调用点/测试不受影响。
        assertEquals(rows, filtered)
        assertEquals(listOf("u0", "a0", "u1"), filtered.map { it.id })
    }

    @Test
    fun excludingKeepsRemainingRowsOrderUnchanged() {
        val rows = listOf(
            message("m1", MemMessage.ROLE_USER, "第一句", createdAt = 1L),
            message("m2", MemMessage.ROLE_ASSISTANT, "回第一句", createdAt = 2L),
            message("m3", MemMessage.ROLE_USER, "第二句", createdAt = 3L),
            message("m4", MemMessage.ROLE_ASSISTANT, "回第二句", createdAt = 4L),
            message("m5", MemMessage.ROLE_USER, "本轮", createdAt = 5L),
        )

        val filtered = ChatEngine.historyRowsForPrompt(rows, excludeMessageId = "m3")

        // ③ 段是时间顺序，剔除一条后其余顺序不能变（不能排序、不能反转）。
        assertEquals(listOf("m1", "m2", "m4", "m5"), filtered.map { it.id })
    }

    @Test
    fun emptyRowsDoesNotCrash() {
        assertEquals(
            emptyList<MessageEntity>(),
            ChatEngine.historyRowsForPrompt(emptyList(), excludeMessageId = "u1"),
        )
        assertEquals(
            emptyList<MessageEntity>(),
            ChatEngine.historyRowsForPrompt(emptyList(), excludeMessageId = null),
        )
    }

    @Test
    fun unknownExcludeIdLeavesRowsIntact() {
        val rows = rowsWithCurrentUserMessage()

        val filtered = ChatEngine.historyRowsForPrompt(rows, excludeMessageId = "not-in-this-segment")

        // 兼容路径：id 不在本段（例如 thread 已切段）时不能误删任何一条。
        assertEquals(rows, filtered)
    }

    @Test
    fun nullExcludeReturnsSameListInstance() {
        val rows = rowsWithCurrentUserMessage()

        assertSame(rows, ChatEngine.historyRowsForPrompt(rows, excludeMessageId = null))
    }

    // ------------------------------------------------------------------ 组装结果：用户话只出现一次

    @Test
    fun currentUserMessageAppearsExactlyOnceAfterFix() {
        val rows = rowsWithCurrentUserMessage()
        val currentRow = rows.last()

        val prompt = assemblePrompt(
            ChatEngine.historyRowsForPrompt(rows, excludeMessageId = currentRow.id),
        )

        // 整条 prompt 里这句话只能出现一次（⑤ 段），且必须是最后一条消息。
        assertEquals("用户本轮的话只能出现一次", 1, prompt.countUserLine(currentText))
        assertEquals(MemMessage.ROLE_USER, prompt.last().role)
        assertEquals(currentText, prompt.last().content)
        // ③ 段旧消息仍然保留（不能因为修重复把历史一起丢了）。
        assertTrue(prompt.any { it.role == MemMessage.ROLE_USER && it.content == "在吗" })
        assertTrue(prompt.any { it.role == MemMessage.ROLE_ASSISTANT && it.content == "在的，怎么啦" })
    }

    @Test
    fun withoutFilterTheSameTextAppearsTwiceWhichIsTheBug() {
        // 反例对照：不过滤（= 修复前的行为）时，③ 段一次 + ⑤ 段一次 → 模型收到两条。
        // 这条不验证"想要的行为"，而是锁住 bug 的可观测形态，确保修法确实改变了组装结果。
        val rows = rowsWithCurrentUserMessage()

        val prompt = assemblePrompt(rows)

        // assemble 的真实结构：①system + ③历史(u0,a0,u1) + ④system + ⑤user。
        // user 消息共 3 条（"在吗" + 2×本轮话），**不能**拿"user 消息总条数"当重复次数；
        // 只统计正文等于本轮话的那几条，并校验它们分别来自 ③ 段与末尾 ⑤ 段。
        val duplicated = prompt.withIndex().filter {
            it.value.role == MemMessage.ROLE_USER && it.value.content == currentText
        }
        assertEquals("修复前就是出现两次", 2, duplicated.size)
        assertTrue("一条来自 ③ 段刚落库的历史", duplicated[0].index < prompt.lastIndex)
        assertEquals("一条来自末尾的 ⑤ 段", prompt.lastIndex, duplicated[1].index)
        assertEquals("⑤ 段恒为最后一条", MemMessage.ROLE_USER, prompt.last().role)
        assertEquals(currentText, prompt.last().content)
    }

    @Test
    fun previousTurnIdenticalTextIsNotDropped() {
        // 按 id 过滤而不是按正文过滤：用户上一轮就说过同一句话时，那一条是合法历史，
        // 只能剔除本轮刚落库的那条（本条为修复设计的关键约束）。
        val rows = listOf(
            message("old", MemMessage.ROLE_USER, currentText, createdAt = 1L),
            message("ai", MemMessage.ROLE_ASSISTANT, "好呀", createdAt = 2L),
            message("now", MemMessage.ROLE_USER, currentText, createdAt = 3L),
        )

        val prompt = assemblePrompt(
            ChatEngine.historyRowsForPrompt(rows, excludeMessageId = "now"),
            userText = currentText,
        )

        // assemble 的真实结构：①system + ③历史(old, ai) + ④system + ⑤user。
        // Ⅰ) 上一轮那条仍在 ③ 段：正文与 id 无关（都是 currentText），用**保留的 createdAt**
        // （历史行原值 1L）区分它来自历史而不是 ⑤ 段（⑤ 段是新构造的 MemMessage，createdAt=0）；
        // Ⅱ) 本轮那条已被剔除，只由末尾 ⑤ 段出现一次。
        assertEquals(MemMessage.ROLE_USER, prompt[1].role)
        assertEquals(currentText, prompt[1].content)
        assertEquals("③ 段历史行保留原文时间", 1L, prompt[1].createdAt)
        assertEquals("历史里的上一条 AI 回复也还在", "好呀", prompt[2].content)
        assertEquals(MemMessage.ROLE_USER, prompt.last().role)
        assertEquals(currentText, prompt.last().content)
        assertEquals("⑤ 段是新构造的 MemMessage，createdAt=0", 0L, prompt.last().createdAt)
        assertEquals("总数 = 上一轮 1 条 + 本轮 1 条", 2, prompt.countUserLine(currentText))
    }
}
