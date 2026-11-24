package cc.pulsez.app.core

import cc.pulsez.app.mem.MemMessage
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GroupChatEngine] 的「@ 点名」行为（ 1–6、9）。
 *
 * 与既有 `GroupChatEngineTest` 分开：那边锁默认流水线，这边只锁点名语义。
 */
class GroupChatMentionTest {

    // ------------------------------------------------------------------ fakes

    private class FakeHistory : GroupHistoryPort {
        override suspend fun history(threadId: String): List<GroupMessage> = emptyList()
    }

    private class FakeMemory : GroupMemoryPort {
        override suspend fun renderMemory(threadId: String, characterId: String, maxTokens: Int): String = ""

        override suspend fun recall(threadId: String, characterId: String, query: String): String? = null
    }

    private class FakeReply(
        private val behavior: (GroupMember, List<MemMessage>, Int) -> GroupReply = { member, _, _ ->
            GroupReply(text = "我在呢，${member.name}在。", failed = false)
        },
    ) : GroupReplyPort {
        data class Call(val member: GroupMember, val messages: List<MemMessage>, val maxTokens: Int)

        val calls = mutableListOf<Call>()

        override suspend fun reply(
            character: GroupMember,
            messages: List<MemMessage>,
            maxTokens: Int,
        ): GroupReply {
            calls += Call(character, messages, maxTokens)
            return behavior(character, messages, maxTokens)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun member(
        id: String,
        name: String,
        ordinal: Int,
        silentTurns: Int = 0,
        chattyScore: Double = 0.5,
    ): GroupMember = GroupMember(
        characterId = id,
        name = name,
        persona = "你是$name。",
        styleSamples = listOf("嗯……"),
        ordinal = ordinal,
        silentTurns = silentTurns,
        chattyScore = chattyScore,
    )

    private fun threeMembers(): List<GroupMember> = listOf(
        member("c", "阿丙", 3),
        member("a", "阿甲", 1, silentTurns = GroupChatEngine.STARVE_TURNS),
        member("b", "阿乙", 2),
    )

    private fun engine(reply: GroupReplyPort): GroupChatEngine = GroupChatEngine(
        history = FakeHistory(),
        memory = FakeMemory(),
        reply = reply,
        random = Random(7),
    )

    private fun ephemeralOf(messages: List<MemMessage>): MemMessage = messages[messages.size - 2]

    private fun promptOf(call: FakeReply.Call): String = call.messages.joinToString("\n") { it.content }

    private fun visible(text: String): Int = text.count { !it.isWhitespace() }

    private fun GroupTurn.spoken(): List<GroupTurnResult.Spoken> =
        results.filterIsInstance<GroupTurnResult.Spoken>()

    // ------------------------------------------------------------------ 1. 强触发

    @Test
    fun addressedMemberBeatsCooldownAndStarveFallback() = runBlocking {
        val reply = FakeReply()
        // 阿甲连续 6 轮没说话（默认会被冷落兜底强制主回）、阿乙在冷却里；点名阿乙 → 只有阿乙回
        val turn = engine(reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "阿乙，你觉得呢？",
            addressedToIds = listOf("b"),
            lastSpeakers = listOf("b"),
        )

        assertEquals(1, reply.calls.size)
        assertEquals("b", reply.calls.single().member.characterId)
        assertEquals("b", turn.plan!!.primary.characterId)
        assertNull("只 @ 一个人时没有短反应", turn.plan!!.secondary)
        assertEquals(1, turn.spoken().size)
    }

    @Test
    fun legacyAddressedToIsTreatedAsSingleMention() = runBlocking {
        val reply = FakeReply()
        val turn = engine(reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "想听听你的看法",
            addressedTo = "@b",
        )
        assertEquals("b", reply.calls.single().member.characterId)
        assertTrue("旧参数也要有点名指令（④ 段）", ephemeralOf(reply.calls.single().messages).content.contains("点了你的名字"))
        assertEquals(turn.plan!!.primary.characterId, "b")
    }

    @Test
    fun everyoneBroadcastFallsBackToNormalSelection() = runBlocking {
        val reply = FakeReply()
        val turn = engine(reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "大家晚上好呀",
            addressedTo = "@所有人",
        )
        assertEquals("不广播：只正常选 1 人回", 1, reply.calls.size)
        assertEquals(1, turn.spoken().size)
        assertFalse(
            "@所有人 不能带点名指令",
            ephemeralOf(reply.calls.single().messages).content.contains("点了你的名字"),
        )
    }

    // ------------------------------------------------------------------ 2. PASS / 失败：同角色重试，绝不换人

    @Test
    fun addressedPassRetriesTheSameMemberOnce() = runBlocking {
        var calls = 0
        val reply = FakeReply { _, _, _ ->
            calls++
            if (calls == 1) GroupReply("[PASS]", failed = false) else GroupReply("我在呢。", failed = false)
        }
        val turn = engine(reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "阿乙在吗",
            addressedToIds = listOf("b"),
        )

        assertEquals("被 @ 者 PASS → 同角色重试 1 次", 2, reply.calls.size)
        assertEquals("两次都必须是阿乙", listOf("b", "b"), reply.calls.map { it.member.characterId })
        assertEquals("最终只产出一条可见回复", 1, turn.spoken().size)
        assertEquals("b", turn.spoken().single().characterId)
        assertEquals("b", turn.plan!!.primary.characterId)
    }

    @Test
    fun addressedNeverPassesSilentlyAfterRetry() = runBlocking {
        val reply = FakeReply { _, _, _ -> GroupReply("[PASS]", failed = false) }
        val turn = engine(reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "阿乙在吗",
            addressedToIds = listOf("b"),
        )

        assertEquals("最多重试 1 次", 2, reply.calls.size)
        assertEquals("两次都是同一个人（没换人）", setOf("b"), reply.calls.map { it.member.characterId }.toSet())
        assertEquals(0, turn.spoken().size)
        val passed = turn.results.single() as GroupTurnResult.Passed
        assertTrue("点名失败必须可见可重试，不能像普通 PASS 一样无声", passed.mentionMissed)
        assertEquals("b", passed.characterId)
    }

    @Test
    fun addressedFailureRetriesSameMemberAndNeverSwitches() = runBlocking {
        val reply = FakeReply { _, _, _ -> throw IllegalStateException("boom") }
        val turn = engine(reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "阿乙在吗",
            addressedToIds = listOf("b"),
        )

        assertEquals("网络或超时失败也同角色重试 1 次", 2, reply.calls.size)
        assertEquals(setOf("b"), reply.calls.map { it.member.characterId }.toSet())
        val failed = turn.results.single() as GroupTurnResult.Failed
        assertFalse("主回失败要留给 UI 重试", failed.silent)
        assertEquals("b", failed.characterId)
        assertEquals(0, turn.spoken().size)
    }

    // ------------------------------------------------------------------ 3. 两个 @：主回 + 短反应

    @Test
    fun twoMentionsProducePrimaryThenShortReactionInTextOrder() = runBlocking {
        val reply = FakeReply { _, _, maxTokens ->
            if (maxTokens == GroupChatEngine.MAIN_MAX_TOKENS) {
                GroupReply("主回内容。", failed = false)
            } else {
                GroupReply("嗯，我也在。", failed = false)
            }
        }
        val turn = engine(reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "@阿丙 @阿甲 你们说",
            // 正文顺序：阿丙 → 阿甲（不按 ordinal）
            addressedToIds = listOf("c", "a"),
        )

        assertEquals(2, reply.calls.size)
        assertEquals("主回给第一个被 @ 的人", "c", reply.calls[0].member.characterId)
        assertEquals(GroupChatEngine.MAIN_MAX_TOKENS, reply.calls[0].maxTokens)
        assertEquals("第 2 个被 @ 的人只给短反应", "a", reply.calls[1].member.characterId)
        assertEquals(GroupChatEngine.SHORT_MAX_TOKENS, reply.calls[1].maxTokens)

        val spoken = turn.spoken()
        assertEquals(2, spoken.size)
        assertEquals(listOf("c", "a"), spoken.map { it.characterId })
        assertTrue(
            "第 2 条是短反应，必须 ≤${GroupChatEngine.SHORT_MAX_CHARS} 字",
            visible(spoken[1].text) <= GroupChatEngine.SHORT_MAX_CHARS,
        )
        assertEquals("c", turn.plan!!.primary.characterId)
        assertEquals("a", turn.plan!!.secondary!!.characterId)

        // 点名信息只进 ④（易变段），且两个人拿到不同的指令
        assertTrue(ephemeralOf(reply.calls[0].messages).content.contains("点了你的名字"))
        assertTrue(ephemeralOf(reply.calls[1].messages).content.contains("也点了你的名字"))
        // 短反应者看得到主回刚说的话（同轮共享历史）
        assertTrue(promptOf(reply.calls[1]).contains("阿丙：主回内容。"))
    }

    @Test
    fun threeMentionsOnlyFirstTwoAreUsed() = runBlocking {
        val reply = FakeReply { _, _, maxTokens ->
            if (maxTokens == GroupChatEngine.MAIN_MAX_TOKENS) {
                GroupReply("主回内容。", failed = false)
            } else {
                GroupReply("我也在。", failed = false)
            }
        }
        val turn = engine(reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "@阿甲 @阿乙 @阿丙",
            addressedToIds = listOf("a", "b", "c"),
        )

        assertEquals("第 3 个被 @ 者静默忽略", 2, reply.calls.size)
        assertEquals(listOf("a", "b"), reply.calls.map { it.member.characterId })
        assertTrue("阿丙不能被调用", reply.calls.none { it.member.characterId == "c" })
        assertEquals(2, turn.spoken().size)
    }

    @Test
    fun secondMentionFailureIsSilent() = runBlocking {
        val reply = FakeReply { _, _, maxTokens ->
            if (maxTokens == GroupChatEngine.MAIN_MAX_TOKENS) {
                GroupReply("主回内容。", failed = false)
            } else {
                throw IllegalStateException("short boom")
            }
        }
        val turn = engine(reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "@阿丙 @阿甲 你们说",
            addressedToIds = listOf("c", "a"),
        )

        assertEquals(1, turn.spoken().size)
        val failed = turn.results.filterIsInstance<GroupTurnResult.Failed>().single()
        assertTrue("第 2 个被 @ 者失败无痕（它本来就是可选的）", failed.silent)
    }

    @Test
    fun unknownAddressedIdDegradesToNormalSelection() = runBlocking {
        val reply = FakeReply()
        // 被 @ 者已退群：静默降级为默认选人，不报错、不提示
        val turn = engine(reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "阿牛，你觉得呢？",
            addressedToIds = listOf("gone"),
        )
        assertEquals(1, reply.calls.size)
        assertEquals(1, turn.spoken().size)
        assertFalse(ephemeralOf(reply.calls.single().messages).content.contains("点了你的名字"))
    }
}
