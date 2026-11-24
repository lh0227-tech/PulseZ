package cc.pulsez.app.core

import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.mem.PromptAssembler
import cc.pulsez.app.mem.TokenEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [GroupChatEngine] 的行为：本地选人（默认 1 人）、点名、冷却、冷落兜底、
 * 长度硬约束、`[PASS]`、静默失败、后处理去重、①/④ 分层与 ④ 预算。
 *
 * 对应文档：
 *。
 */
class GroupChatEngineTest {

    // ------------------------------------------------------------------ fakes

    private class FakeHistory(
        private val messages: List<GroupMessage> = emptyList(),
    ) : GroupHistoryPort {
        val requestedThreadIds = mutableListOf<String>()

        override suspend fun history(threadId: String): List<GroupMessage> {
            requestedThreadIds += threadId
            return messages
        }
    }

    private class FakeMemory(
        private val memoryByCharacter: Map<String, String> = emptyMap(),
        private val recallByCharacter: Map<String, String?> = emptyMap(),
    ) : GroupMemoryPort {
        data class RenderCall(val threadId: String, val characterId: String, val maxTokens: Int)
        data class RecallCall(val threadId: String, val characterId: String, val query: String)

        val renderCalls = mutableListOf<RenderCall>()
        val recallCalls = mutableListOf<RecallCall>()

        override suspend fun renderMemory(threadId: String, characterId: String, maxTokens: Int): String {
            renderCalls += RenderCall(threadId, characterId, maxTokens)
            return memoryByCharacter[characterId].orEmpty()
        }

        override suspend fun recall(threadId: String, characterId: String, query: String): String? {
            recallCalls += RecallCall(threadId, characterId, query)
            return recallByCharacter[characterId]
        }
    }

    private class FakeReply(
        private val behavior: (GroupMember, List<MemMessage>, Int) -> GroupReply = { member, _, _ ->
            GroupReply(text = "我是${member.name}", failed = false)
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
        persona: String = "你是$name，说话有点懒。",
        silentTurns: Int = 0,
        chattyScore: Double = 0.5,
    ): GroupMember = GroupMember(
        characterId = id,
        name = name,
        persona = persona,
        styleSamples = listOf("嗯……让我想想。"),
        ordinal = ordinal,
        silentTurns = silentTurns,
        chattyScore = chattyScore,
    )

    /** 刻意乱序传入，验证引擎按 ordinal 处理。 */
    private fun threeMembers(): List<GroupMember> = listOf(
        member("c", "阿丙", 3),
        member("a", "阿甲", 1),
        member("b", "阿乙", 2),
    )

    private fun engine(
        history: GroupHistoryPort = FakeHistory(),
        memory: GroupMemoryPort = FakeMemory(),
        reply: GroupReplyPort = FakeReply(),
        hardCap: Int = GroupChatEngine.HARD_CAP,
        secondResponderProbability: Double = GroupChatEngine.SECOND_RESPONDER_PROBABILITY,
        random: Random = Random(7),
    ): GroupChatEngine = GroupChatEngine(
        history = history,
        memory = memory,
        reply = reply,
        hardCap = hardCap,
        secondResponderProbability = secondResponderProbability,
        random = random,
    )

    private fun FakeReply.Call.promptText(): String =
        messages.joinToString("\n") { it.content }

    /** ④ 段 = 倒数第二条（最后一条恒为 ⑤ 用户消息）。 */
    private fun ephemeralOf(messages: List<MemMessage>): MemMessage = messages[messages.size - 2]

    /** 可见字数（不计空白），与引擎的 120/20/180 口径一致。 */
    private fun visible(text: String): Int = text.count { !it.isWhitespace() }

    private fun GroupTurn.spoken(): List<GroupTurnResult.Spoken> =
        results.filterIsInstance<GroupTurnResult.Spoken>()

    // ------------------------------------------------------------------ 1. 默认只 1 人回

    @Test
    fun threeMembersDefaultOnlyOneReplies() = runBlocking {
        val reply = FakeReply()
        val turn = engine(reply = reply).runTurn("t", threeMembers(), "大家好，今天过得怎么样？")

        assertEquals(1, reply.calls.size)
        assertEquals(1, turn.results.size)
        val spoken = turn.results.single() as GroupTurnResult.Spoken
        assertNotNull(turn.plan)
        assertEquals(turn.plan!!.primary.characterId, spoken.characterId)
        assertNull("默认不开「多陪一句」时不应有计划中的第二人", turn.plan!!.secondary)
    }

    // ------------------------------------------------------------------ 2. 点名

    @Test
    fun addressedMemberIsTheOnlyOneWhoReplies() = runBlocking {
        val reply = FakeReply()
        val turn = engine(reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "阿乙，你觉得呢？",
            addressedTo = "阿乙",
            allowSecondResponder = true, // 点名压过「多陪一句」
        )

        assertEquals(1, reply.calls.size)
        assertEquals("b", reply.calls.single().member.characterId)
        assertEquals("b", turn.plan!!.primary.characterId)
        assertNull(turn.plan!!.secondary)
        assertEquals(1, turn.spoken().size)
    }

    @Test
    fun addressedByCharacterIdAlsoWorks() = runBlocking {
        val reply = FakeReply()
        val turn = engine(reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "想听听你的看法",
            addressedTo = "@c",
        )
        assertEquals("c", reply.calls.single().member.characterId)
        assertEquals("c", turn.plan!!.primary.characterId)
    }

    @Test
    fun broadcastMentionIsIgnoredAndFallsBackToNormalSelection() = runBlocking {
        val reply = FakeReply()
        val turn = engine(reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "大家晚上好呀",
            addressedTo = "@所有人",
        )
        assertEquals(1, reply.calls.size)
        assertNotNull(turn.plan)
        assertEquals(1, turn.spoken().size)
    }

    // ------------------------------------------------------------------ 3. 冷却与冷落兜底

    @Test
    fun recentMainSpeakersAreOnCooldown() = runBlocking {
        val reply = FakeReply()
        // 最近两轮是阿甲、阿乙主回 → 本轮只能是阿丙
        val turn = engine(reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "那我们继续聊刚才的话题吧",
            lastSpeakers = listOf("a", "b"),
        )
        assertEquals("c", turn.plan!!.primary.characterId)
        assertEquals(1, reply.calls.size)
    }

    @Test
    fun starvedMemberIsForcedAsPrimary() = runBlocking {
        val starved = member("a", "阿甲", 1, silentTurns = GroupChatEngine.STARVE_TURNS, chattyScore = 0.01)
        val chatty = member("b", "阿乙", 2, chattyScore = 1.0)
        val reply = FakeReply()
        val turn = engine(reply = reply).runTurn(
            threadId = "t",
            members = listOf(chatty, starved),
            userText = "今天天气不错，聊点轻松的吧",
        )
        assertEquals("连续 6 轮没被轮到必须强制主回", "a", turn.plan!!.primary.characterId)
        assertEquals(1, reply.calls.size)
    }

    @Test
    fun twelveRoundsRotateEveryMemberAndNeverRepeatWithinCooldown() = runBlocking {
        val reply = FakeReply { _, _, _ -> GroupReply("我在呢，今天还好。", failed = false) }
        val engine = engine(reply = reply, random = Random(42))
        var members = threeMembers()
        var lastSpeakers = emptyList<String>()
        val primaries = mutableListOf<String>()

        repeat(12) { round ->
            val turn = engine.runTurn(
                threadId = "t",
                members = members,
                userText = "第 $round 轮，随便聊聊今天的事情吧",
                lastSpeakers = lastSpeakers,
            )
            val primaryId = turn.plan!!.primary.characterId
            primaries += primaryId
            lastSpeakers = (listOf(primaryId) + lastSpeakers).take(GroupChatEngine.SPEAKER_COOLDOWN_TURNS)
            members = members.map { current ->
                if (current.characterId == primaryId) {
                    current.copy(silentTurns = 0)
                } else {
                    current.copy(silentTurns = current.silentTurns + 1)
                }
            }
        }

        // 7：同一角色 2 轮内不连任（无点名时）
        for (index in 2 until primaries.size) {
            assertNotEquals(primaries[index], primaries[index - 1])
            assertNotEquals(primaries[index], primaries[index - 2])
        }
        // 冷落兜底保证 12 轮内每个人都至少主回 1 次
        assertEquals(setOf("a", "b", "c"), primaries.toSet())
    }

    // ------------------------------------------------------------------ 4. 短反应

    @Test
    fun secondResponderIsShortAndFollowsPrimary() = runBlocking {
        val reply = FakeReply { member, _, maxTokens ->
            if (maxTokens == GroupChatEngine.MAIN_MAX_TOKENS) {
                GroupReply("主回内容。", failed = false)
            } else {
                GroupReply("第一句短反应。第二句也特别长特别长特别长。", failed = false)
            }
        }
        val turn = engine(reply = reply, secondResponderProbability = 1.0).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "你们今天都做了什么呀",
            allowSecondResponder = true,
        )

        assertEquals(2, reply.calls.size)
        // 串行：主回在前、短反应在后
        assertEquals(GroupChatEngine.MAIN_MAX_TOKENS, reply.calls[0].maxTokens)
        assertEquals(GroupChatEngine.SHORT_MAX_TOKENS, reply.calls[1].maxTokens)

        val spoken = turn.spoken()
        assertEquals(2, spoken.size)
        assertEquals(turn.plan!!.primary.characterId, spoken[0].characterId)
        assertEquals(turn.plan!!.secondary!!.characterId, spoken[1].characterId)

        val short = spoken[1].text
        assertTrue("短反应 ${short.length} 字超过 20", visible(short) <= GroupChatEngine.SHORT_MAX_CHARS)
        assertTrue(short.endsWith("。"))

        // 短反应 prompt 必须带「只补一句」和 20 字约束（2）
        val shortPrompt = reply.calls[1].promptText()
        assertTrue(shortPrompt.contains("只补一句"))
        assertTrue(shortPrompt.contains("${GroupChatEngine.SHORT_MAX_CHARS} 字"))
        // 且能看到主回刚说的话（同轮共享历史）
        assertTrue(shortPrompt.contains("${turn.plan!!.primary.name}：主回内容。"))
    }

    // ------------------------------------------------------------------ 5. 长度硬约束

    @Test
    fun mainReplyTruncatedTo120CharsBySentence() = runBlocking {
        val longReply = "第一句话写在这里了。".repeat(30)
        val reply = FakeReply { _, _, _ -> GroupReply(longReply, failed = false) }
        val turn = engine(reply = reply).runTurn("t", threeMembers(), "说点什么吧")

        val spoken = turn.spoken().single()
        assertTrue("主回 ${visible(spoken.text)} 字超过 120", visible(spoken.text) <= GroupChatEngine.MAIN_MAX_CHARS)
        assertTrue("应按句末标点截断", spoken.text.endsWith("。"))
        assertTrue(spoken.text.length < longReply.length)
    }

    @Test
    fun totalVisibleCharsPerTurnAtMost180() = runBlocking {
        val reply = FakeReply { _, _, maxTokens ->
            if (maxTokens == GroupChatEngine.MAIN_MAX_TOKENS) {
                GroupReply("主回的一句话。".repeat(40), failed = false)
            } else {
                GroupReply("短反应的一句话。".repeat(40), failed = false)
            }
        }
        val turn = engine(reply = reply, secondResponderProbability = 1.0).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "随便聊聊吧",
            allowSecondResponder = true,
        )
        assertEquals(2, turn.spoken().size)
        val total = turn.spoken().sumOf { visible(it.text) }
        assertTrue("单轮可见总量 $total 超过 ${GroupChatEngine.TOTAL_MAX_CHARS}", total <= GroupChatEngine.TOTAL_MAX_CHARS)
        for (spoken in turn.spoken()) {
            val limit = if (spoken.characterId == turn.plan!!.primary.characterId) {
                GroupChatEngine.MAIN_MAX_CHARS
            } else {
                GroupChatEngine.SHORT_MAX_CHARS
            }
            assertTrue(visible(spoken.text) <= limit)
        }
    }

    // ------------------------------------------------------------------ 6. PASS 与静默失败

    @Test
    fun passReturnsPassedAndIsNotAFailure() = runBlocking {
        val reply = FakeReply { _, _, _ -> GroupReply("  [pass]  ", failed = false, usageTokens = 9) }
        val turn = engine(reply = reply, secondResponderProbability = 1.0).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "你们在吗",
            allowSecondResponder = true,
        )

        // 主回复 PASS 时会换下一个候选再试一次（总调用 ≤2）；短反应仍不追加。
        assertEquals(2, reply.calls.size)
        assertEquals(0, turn.spoken().size)
        assertEquals(0, turn.results.filterIsInstance<GroupTurnResult.Failed>().size)
        assertEquals("两次都 PASS 就整轮沉默", 2, turn.results.count { it is GroupTurnResult.Passed })
    }

    // ------------------------------------------------------------------ 6b. 缺陷 ：主回 PASS 换人

    @Test
    fun mainPassSwitchesToNextCandidateAndProducesExactlyOneReply() = runBlocking {
        var calls = 0
        val reply = FakeReply { member, _, _ ->
            calls++
            if (calls == 1) {
                GroupReply("[PASS]", failed = false)
            } else {
                GroupReply("我在呢，${member.name}在。", failed = false)
            }
        }
        val turn = engine(reply = reply).runTurn("t", threeMembers(), "在吗，说句话吧")

        assertEquals("主回 PASS 后只多试一次（总调用 ≤2）", 2, reply.calls.size)
        val spoken = turn.spoken()
        assertEquals("换人不是追加第二条：整轮只产出 1 条可见回复", 1, spoken.size)
        assertEquals("可见回复来自换上的候选", reply.calls[1].member.characterId, spoken.single().characterId)
        assertEquals(
            "plan.primary 必须指向真正开口的人（UI 才会按主回渲染、危机兜底才生效）",
            spoken.single().characterId,
            turn.plan!!.primary.characterId,
        )
        assertEquals("第一次的沉默仍然记账为 Passed", 1, turn.results.count { it is GroupTurnResult.Passed })
        assertEquals(2, turn.results.size)
    }

    @Test
    fun bothMainAttemptsPassingLeavesTurnCompletelySilent() = runBlocking {
        val reply = FakeReply { _, _, _ -> GroupReply("[PASS] 这次不接", failed = false) }
        val turn = engine(reply = reply).runTurn("t", threeMembers(), "在吗")

        assertEquals("最多换 1 次：总调用 ≤2", 2, reply.calls.size)
        assertEquals(0, turn.spoken().size)
        assertEquals("两次都 PASS 不能变成失败占位", 0, turn.results.filterIsInstance<GroupTurnResult.Failed>().size)
        assertEquals(2, turn.results.count { it is GroupTurnResult.Passed })
        assertEquals("换人确实换了另一个候选", 2, reply.calls.map { it.member.characterId }.toSet().size)
    }

    @Test
    fun fallbackSwitchDoesNotAppendSecondResponder() = runBlocking {
        var calls = 0
        val reply = FakeReply { _, _, maxTokens ->
            calls++
            when {
                maxTokens == GroupChatEngine.SHORT_MAX_TOKENS ->
                    GroupReply("短反应不该被调用", failed = false)

                calls == 1 -> GroupReply("[PASS]", failed = false)
                else -> GroupReply("我来接一句吧。", failed = false)
            }
        }
        val turn = engine(reply = reply, secondResponderProbability = 1.0).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "有人吗，聊两句吧",
            allowSecondResponder = true,
        )

        assertEquals("换人后不再追加短反应（保持默认 1 人可见）", 2, reply.calls.size)
        assertEquals(1, turn.spoken().size)
    }

    @Test
    fun dedupeSilenceDoesNotSwitchSpeaker() = runBlocking {
        // 只有真正的 [PASS] 才换人；去重丢弃是内容质量问题，换人也只会再丢一次
        val repeated = "今天下雨了，我有点想你了，你那边天气怎么样呢？"
        val reply = FakeReply { _, _, _ -> GroupReply(repeated, failed = false) }
        val turn = engine(reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "今天怎么过呀",
            recentAiReplies = listOf(repeated),
        )
        assertEquals(1, reply.calls.size)
        assertTrue(turn.results.single() is GroupTurnResult.Passed)
    }

    @Test
    fun emptyOrTooShortMainReplyIsPassed() = runBlocking {
        val reply = FakeReply { _, _, _ -> GroupReply("", failed = false) }
        val turn = engine(reply = reply).runTurn("t", threeMembers(), "在吗在吗")
        assertTrue(turn.results.single() is GroupTurnResult.Passed)

        val tooShort = FakeReply { _, _, _ -> GroupReply("嗯。", failed = false) }
        val turn2 = engine(reply = tooShort).runTurn("t", threeMembers(), "在吗在吗")
        assertTrue(turn2.results.single() is GroupTurnResult.Passed)
    }

    @Test
    fun mainFailureIsNotSilentAndSkipsShortReaction() = runBlocking {
        val reply = FakeReply { _, _, maxTokens ->
            if (maxTokens == GroupChatEngine.MAIN_MAX_TOKENS) throw IllegalStateException("boom")
            GroupReply("不该被调用", failed = false)
        }
        val turn = engine(reply = reply, secondResponderProbability = 1.0).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "有人吗？说句话吧",
            allowSecondResponder = true,
        )

        assertEquals(1, reply.calls.size) // 主回失败不再补短反应
        val failed = turn.results.single() as GroupTurnResult.Failed
        assertFalse("主回失败要留给 UI 重试，不能静默", failed.silent)
        assertTrue(failed.reason.isNotBlank())

        // 端口用 GroupReply(failed = true) 失败时同样保留 usage 用于记账
        val reported = FakeReply { _, _, _ -> GroupReply("", failed = true, usageTokens = 17) }
        val turn2 = engine(reply = reported, secondResponderProbability = 1.0).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "有人吗？说句话吧",
            allowSecondResponder = true,
        )
        assertEquals(1, reported.calls.size)
        val failed2 = turn2.results.single() as GroupTurnResult.Failed
        assertEquals(17, failed2.usageTokens)
        assertFalse(failed2.silent)
    }

    @Test
    fun shortReactionFailureIsSilent() = runBlocking {
        val reply = FakeReply { _, _, maxTokens ->
            if (maxTokens == GroupChatEngine.MAIN_MAX_TOKENS) {
                GroupReply("主回内容。", failed = false)
            } else {
                throw IllegalStateException("short boom")
            }
        }
        val turn = engine(reply = reply, secondResponderProbability = 1.0).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "你们今天怎么样呀",
            allowSecondResponder = true,
        )

        assertEquals(1, turn.spoken().size)
        val failed = turn.results.filterIsInstance<GroupTurnResult.Failed>()
        assertEquals(1, failed.size)
        assertTrue("短反应失败必须静默", failed.single().silent)
    }

    @Test
    fun cancellationPropagatesInsteadOfBeingReportedAsFailure() = runBlocking {
        val reply = FakeReply { _, _, _ -> throw CancellationException("stop") }
        try {
            engine(reply = reply).runTurn("t", threeMembers(), "有人吗？说句话吧")
            fail("协程取消必须向上传播，不能被当成角色失败")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(1, reply.calls.size)
    }

    // ------------------------------------------------------------------ 7. 后处理去重与越界

    @Test
    fun highlySimilarReplyIsDroppedAsPassed() = runBlocking {
        val repeated = "今天下雨了，我有点想你了，你那边天气怎么样呢？"
        val reply = FakeReply { _, _, _ -> GroupReply(repeated, failed = false) }
        val turn = engine(reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "今天怎么过呀",
            recentAiReplies = listOf(repeated),
        )
        assertTrue("与最近回复重合的整条应丢弃", turn.results.single() is GroupTurnResult.Passed)
        assertEquals(0, turn.spoken().size)
    }

    @Test
    fun foreignSpeakerLinesAreTrimmed() = runBlocking {
        val reply = FakeReply { _, _, _ ->
            GroupReply("我挺好的呀。阿乙：我也挺好的。", failed = false)
        }
        val turn = engine(reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "你们好吗",
            addressedTo = "阿甲",
        )
        val spoken = turn.spoken().single()
        assertEquals("我挺好的呀。", spoken.text)
        assertFalse(spoken.text.contains("阿乙"))
    }

    // ------------------------------------------------------------------ 8. prompt 分层与 ④ 预算

    @Test
    fun firstLayerIsByteIdenticalAcrossTurns() = runBlocking {
        val reply = FakeReply()
        val engine = engine(reply = reply)
        engine.runTurn("t", threeMembers(), "第一轮消息，今天做了点什么呀", addressedTo = "阿甲")
        engine.runTurn("t", threeMembers(), "第二轮消息，晚上吃了什么好吃的", addressedTo = "阿甲")

        val firstLayer = reply.calls[0].messages.first()
        assertEquals(MemMessage.ROLE_SYSTEM, firstLayer.role)
        assertEquals(
            "① 段必须跨轮字节一致，保留前缀缓存",
            firstLayer.content,
            reply.calls[1].messages.first().content,
        )

        // ① 只放稳定内容：轮次信息必须在 ④
        assertFalse(firstLayer.content.contains("这一轮"))
        assertFalse(firstLayer.content.contains("${GroupChatEngine.MAIN_MAX_CHARS} 字"))

        val ephemeral = ephemeralOf(reply.calls[1].messages)
        assertTrue(ephemeral.content.contains("这一轮"))
        assertTrue(ephemeral.content.contains("${GroupChatEngine.MAIN_MAX_CHARS} 字"))
        assertTrue(ephemeral.content.contains("超过会被截断"))
    }

    @Test
    fun stableGroupRulesAreInFirstLayerAndForbidSpeakingForOthers() = runBlocking {
        val reply = FakeReply()
        engine(reply = reply).runTurn("t", threeMembers(), "大家好呀", addressedTo = "阿甲")

        val firstLayer = reply.calls.single().messages.first().content
        assertTrue(firstLayer.contains("群聊规则"))
        assertTrue(firstLayer.contains("在场的人：阿甲、阿乙、阿丙"))
        // 缺陷 ：群聊的身份锚必须明确到"名字"，不能只有"你是 X"
        assertTrue(firstLayer.contains("你的名字是 阿甲，别人问起来就说 阿甲。"))
        assertTrue(firstLayer.contains("不要替别人说话"))
        assertTrue(firstLayer.contains("不要复述"))
        assertTrue(firstLayer.contains("可以自然回应或提及在场的人"))
        assertTrue(firstLayer.contains("说话风格以你的【人设】为准"))
        assertTrue(firstLayer.contains(GroupChatEngine.PASS_TOKEN))
    }

    @Test
    fun ephemeralLayerStaysWithinBudget() = runBlocking {
        val hugeMemory = "【记忆】\n" + "这是一条很长的记忆，用来把临场块撑到上限。".repeat(400)
        val hugeRecall = "【你们过去聊过】\n" + "那天你们聊了很久，说了很多细节。".repeat(400)
        val memory = FakeMemory(
            memoryByCharacter = mapOf("a" to hugeMemory),
            recallByCharacter = mapOf("a" to hugeRecall),
        )
        val reply = FakeReply()
        engine(memory = memory, reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "你还记得我们上次聊的那个面试吗",
            addressedTo = "阿甲",
        )

        val messages = reply.calls.single().messages
        assertEquals(MemMessage.ROLE_USER, messages.last().role)
        val ephemeral = ephemeralOf(messages)
        assertEquals(MemMessage.ROLE_SYSTEM, ephemeral.role)
        val tokens = TokenEstimator.estimateMessages(listOf(ephemeral))
        assertTrue(
            "④ 段 $tokens token 超过 ${PromptAssembler.EPHEMERAL_LAYER_MAX_TOKENS}",
            tokens <= PromptAssembler.EPHEMERAL_LAYER_MAX_TOKENS,
        )
        // 每轮指令在最前，截断不会先裁掉「轮到你」
        assertTrue(ephemeral.content.contains("这一轮"))
    }

    // ------------------------------------------------------------------ 9. 记忆隔离与历史

    @Test
    fun memoryAndRecallAreIsolatedPerMember() = runBlocking {
        val memory = FakeMemory(
            memoryByCharacter = mapOf(
                "a" to "【记忆】\n◆ 其它（按重要度与新鲜度）\n- [事实] A_FACT：阿甲怕黑",
                "b" to "【记忆】\n◆ 其它（按重要度与新鲜度）\n- [事实] B_FACT：阿乙喜欢猫",
            ),
            recallByCharacter = mapOf(
                "a" to "A_RECALL：那次面试的细节",
                "b" to "B_RECALL：那次面试的细节",
            ),
        )
        val reply = FakeReply()
        val engine = engine(memory = memory, reply = reply)
        val userText = "你还记得我们上次聊的那个面试吗"
        engine.runTurn("t", threeMembers(), userText, addressedTo = "阿甲")
        engine.runTurn("t", threeMembers(), userText, addressedTo = "阿乙")

        val promptA = reply.calls[0].promptText()
        val promptB = reply.calls[1].promptText()

        assertTrue(promptA.contains("A_FACT"))
        assertTrue(promptA.contains("A_RECALL"))
        assertFalse(promptA.contains("B_FACT"))
        assertFalse(promptA.contains("B_RECALL"))

        assertTrue(promptB.contains("B_FACT"))
        assertTrue(promptB.contains("B_RECALL"))
        assertFalse(promptB.contains("A_FACT"))
        assertFalse(promptB.contains("A_RECALL"))

        assertEquals(listOf("a", "b"), memory.renderCalls.map { it.characterId })
        assertEquals(
            listOf(PromptAssembler.MEMORY_RENDER_TOKENS, PromptAssembler.MEMORY_RENDER_TOKENS),
            memory.renderCalls.map { it.maxTokens },
        )
        assertTrue(memory.recallCalls.first().query.contains("面试"))
    }

    @Test
    fun shortMemoryIsNotDroppedByRecallThreshold() = runBlocking {
        // PromptAssembler 会丢弃 <32 token 的 recall 片段；一条短边界必须仍能进 ④
        val memory = FakeMemory(memoryByCharacter = mapOf("a" to "【记忆】\n- 边界：不要提阿甲的前任"))
        val reply = FakeReply()
        engine(memory = memory, reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "你还记得我们上次聊的那个面试吗",
            addressedTo = "阿甲",
        )
        assertTrue(reply.calls.single().promptText().contains("不要提阿甲的前任"))
    }

    @Test
    fun historyIsSharedAndTrailingCurrentUserMessageIsNotDuplicated() = runBlocking {
        val history = FakeHistory(
            listOf(
                GroupMessage.user("之前的消息", createdAt = 1L),
                GroupMessage.fromCharacter("a", "阿甲", "上次我说的", createdAt = 2L),
                GroupMessage.user("本轮消息", createdAt = 3L),
            ),
        )
        val reply = FakeReply()
        engine(history = history, reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "本轮消息",
            addressedTo = "阿甲",
        )

        val prompt = reply.calls.single().messages
        assertEquals(1, prompt.count { it.role == MemMessage.ROLE_USER && it.content == "本轮消息" })
        assertTrue(prompt.any { it.role == MemMessage.ROLE_ASSISTANT && it.content == "阿甲：上次我说的" })
        assertTrue(prompt.any { it.role == MemMessage.ROLE_USER && it.content == "之前的消息" })
    }

    @Test
    fun onlyCurrentUserMessageIsTrimmedFromTrailingSameTextHistory() = runBlocking {
        // 边界：末尾连续两条同文用户消息时，只剔「本轮」这一条。
        // 上一轮同文消息后面没有 assistant 行（整轮失败）——那条是合法历史，不能被连坐删掉。
        val history = FakeHistory(
            listOf(
                GroupMessage.user("同一句话", createdAt = 1L),
                GroupMessage.user("同一句话", createdAt = 2L),
            ),
        )
        val reply = FakeReply()
        engine(history = history, reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "同一句话",
            addressedTo = "阿甲",
        )

        val prompt = reply.calls.single().messages
        // ③ 段保留上一轮那条 + ⑤ 段本轮那条 = 2；旧实现 while 连剔两条后只剩 ⑤ 的 1 条。
        assertEquals(2, prompt.count { it.role == MemMessage.ROLE_USER && it.content == "同一句话" })
    }

    // ------------------------------------------------------------------ 10. 人数边界

    @Test
    fun invalidMemberCountsAreRejectedWithoutCallingPorts() = runBlocking {
        val reply = FakeReply()
        val memory = FakeMemory()
        val history = FakeHistory()
        val engine = engine(history = history, memory = memory, reply = reply)

        val single = engine.runTurn("t", listOf(member("a", "阿甲", 1)), "hi")
        assertNull(single.plan)
        assertTrue(single.results.single() is GroupTurnResult.Rejected)

        val five = (1..5).map { member("m$it", "成员$it", it) }
        val tooMany = engine.runTurn("t", five, "hi")
        assertNull(tooMany.plan)
        assertTrue(tooMany.results.single() is GroupTurnResult.Rejected)

        assertTrue(reply.calls.isEmpty())
        assertTrue(memory.renderCalls.isEmpty())
        assertTrue(history.requestedThreadIds.isEmpty())
    }

    // ------------------------------------------------------------------ 11. PASS 前缀识别

    @Test
    fun passWithSuffixOrExplanationIsStillPassed() = runBlocking {
        // 回归：旧实现用全等判定，`[PASS]。` / `[PASS] 这次不接` 会被漏成正文落库
        val variants = listOf(
            "[PASS]。",
            "[PASS] 这次不接",
            "[PASS]\n这次没什么想说的",
            "  [pass] ，下次吧",
        )
        for (raw in variants) {
            val reply = FakeReply { _, _, _ -> GroupReply(raw, failed = false) }
            val turn = engine(reply = reply, secondResponderProbability = 1.0).runTurn(
                threadId = "t",
                members = threeMembers(),
                userText = "你们在吗",
                allowSecondResponder = true,
            )
            // 缺陷 ：PASS 会换一个候选再试一次（仍 PASS），所以是 2 次调用、0 条可见回复
            assertEquals("「$raw」两次都判沉默，且不触发短反应", 2, reply.calls.size)
            assertEquals("「$raw」不能产出可见消息", 0, turn.spoken().size)
            assertEquals("「$raw」两次都应记为 Passed", 2, turn.results.count { it is GroupTurnResult.Passed })
        }
    }

    @Test
    fun passMarkerInsideReplyBodyIsNotTreatedAsSilence() {
        // 只有开头是协议标记才算 PASS；正文里提到 [PASS] 照常落库
        val reply = FakeReply { _, _, _ -> GroupReply("你说的 [PASS] 是什么意思？我没懂。", failed = false) }
        val turn = runBlocking { engine(reply = reply).runTurn("t", threeMembers(), "我刚说了什么") }

        val spoken = turn.spoken().single()
        assertTrue(spoken.text.isNotBlank())
        assertTrue(spoken.text.contains("[PASS]"))
    }

    // ------------------------------------------------------------------ 12. errorMessage 透传

    @Test
    fun groupReplyErrorMessagePropagatesToFailedTurnResult() = runBlocking {
        val expected = "对面好像有点忙。等会儿再试一次？"
        val reply = FakeReply { _, _, _ ->
            GroupReply(text = "", failed = true, errorMessage = expected)
        }
        val normalTurn = engine(reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "今天好累",
        )
        val normalFailed = normalTurn.results.filterIsInstance<GroupTurnResult.Failed>().single()
        assertEquals(expected, normalFailed.errorMessage)

        val mentionTurn = engine(reply = reply).runTurn(
            threadId = "t",
            members = threeMembers(),
            userText = "@阿甲 帮我想想",
            addressedToIds = listOf("a"),
        )
        val mentionFailed = mentionTurn.results.filterIsInstance<GroupTurnResult.Failed>().single()
        assertEquals(expected, mentionFailed.errorMessage)
    }
}
