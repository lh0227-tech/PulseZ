package cc.pulsez.app.ui.group

import cc.pulsez.app.core.GroupChatEngine
import cc.pulsez.app.core.GroupHistoryPort
import cc.pulsez.app.core.GroupMember
import cc.pulsez.app.core.GroupMemoryPort
import cc.pulsez.app.core.GroupMessage
import cc.pulsez.app.core.GroupReply
import cc.pulsez.app.core.GroupReplyPort
import cc.pulsez.app.core.GroupTurnResult
import cc.pulsez.app.mem.MemMessage
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ：同时 @ 两个人时，**第 2 个人的短反应也要带「被叫到」标记**。
 *
 * 标记的判定收口在 [isAddressedReply]（实时发送与历史重建共用同一条规则）；
 * 这里用真实 [GroupChatEngine] 产出的一轮结果证明：@ 两人时两个发言人都在 addressed 集合里，
 * 没被 @ 的人不在。UI 层只按这个布尔值渲染标记。
 */
class AddressedReplyMarkTest {

    // ------------------------------------------------------------------ fakes

    private class FakeHistory : GroupHistoryPort {
        override suspend fun history(threadId: String): List<GroupMessage> = emptyList()
    }

    private class FakeMemory : GroupMemoryPort {
        override suspend fun renderMemory(threadId: String, characterId: String, maxTokens: Int): String = ""

        override suspend fun recall(threadId: String, characterId: String, query: String): String? = null
    }

    private class FakeReply : GroupReplyPort {
        override suspend fun reply(
            character: GroupMember,
            messages: List<MemMessage>,
            maxTokens: Int,
        ): GroupReply = if (maxTokens == GroupChatEngine.MAIN_MAX_TOKENS) {
            GroupReply(text = "主回内容。", failed = false)
        } else {
            GroupReply(text = "我也在。", failed = false)
        }
    }

    private fun member(id: String, name: String, ordinal: Int) = GroupMember(
        characterId = id,
        name = name,
        persona = "你是$name。",
        ordinal = ordinal,
    )

    private fun members() = listOf(
        member("c", "阿丙", 3),
        member("a", "阿甲", 1),
        member("b", "阿乙", 2),
    )

    private fun engine() = GroupChatEngine(
        history = FakeHistory(),
        memory = FakeMemory(),
        reply = FakeReply(),
        random = Random(7),
    )

    // ------------------------------------------------------------------ tests

    @Test
    fun `second mentioned speaker short reaction is marked addressed`() = runBlocking {
        val turn = engine().runTurn(
            threadId = "t",
            members = members(),
            userText = "@阿丙 @阿甲 你们说",
            addressedToIds = listOf("c", "a"),
        )
        val spoken = turn.results.filterIsInstance<GroupTurnResult.Spoken>()
        val addressedIds = setOf("c", "a")

        assertEquals("主回 + 短反应一共两条", 2, spoken.size)
        assertTrue("主回发言人被 @，必须带标记", isAddressedReply(spoken[0].characterId, addressedIds))
        assertTrue(
            "第 2 个被 @ 者的短反应也必须带标记（F4 的核心断言）",
            isAddressedReply(spoken[1].characterId, addressedIds),
        )
        // 没被 @ 的人即使在本轮说话，也不能带标记。
        assertFalse(isAddressedReply("b", addressedIds))
    }

    @Test
    fun `reply without mention carries no addressed mark`() = runBlocking {
        val turn = engine().runTurn(
            threadId = "t",
            members = members(),
            userText = "大家晚上好呀",
        )
        val spoken = turn.results.filterIsInstance<GroupTurnResult.Spoken>()
        assertTrue(spoken.isNotEmpty())

        val addressedIds = emptySet<String>()
        assertTrue(
            "没有 @ 时任何人都不带标记",
            spoken.none { isAddressedReply(it.characterId, addressedIds) },
        )
    }

    @Test
    fun `empty addressed set never marks anyone`() {
        assertFalse(isAddressedReply("a", emptySet()))
        assertTrue(isAddressedReply("a", setOf("a")))
        assertFalse(isAddressedReply("b", setOf("a")))
    }
}
