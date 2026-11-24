package cc.pulsez.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpeakerLedger] 的纯逻辑测试。
 *
 * 回归点： 只在主回成功时记账，主回失败 / `[PASS]` 的轮次里其他人的冷落轮数不会 +1、
 * 被计划的人也不进冷却 → 「2 轮不连任」和「连续 6 轮没人接强制主回」都会失真。
 * 这里覆盖成功 / 失败 / 沉默三种结局，以及第二人的记账边界。
 */
class SpeakerLedgerTest {

    private fun member(id: String, ordinal: Int, silentTurns: Int) = GroupMember(
        characterId = id,
        name = "成员$id",
        persona = "",
        ordinal = ordinal,
        silentTurns = silentTurns,
    )

    private fun spoken(id: String) = GroupTurnResult.Spoken(characterId = id, name = "成员$id", text = "我在。")

    private fun failed(id: String) = GroupTurnResult.Failed(characterId = id, name = "成员$id", reason = "boom")

    private fun passed(id: String) = GroupTurnResult.Passed(characterId = id, name = "成员$id")

    private val members = listOf(member("a", 0, 3), member("b", 1, 1), member("c", 2, 0))

    @Test
    fun spokenPrimaryResetsItsTurnsAgesOthersAndEntersCooldown() {
        val plan = Plan(primary = members[0])
        val update = SpeakerLedger.update(
            members = members,
            lastSpeakers = listOf("b"),
            plan = plan,
            results = listOf(spoken("a")),
        )

        assertEquals(0, update.silentTurns["a"])
        assertEquals("没被轮到的 b 要 +1", 2, update.silentTurns["b"])
        assertEquals("没被轮到的 c 要 +1", 1, update.silentTurns["c"])
        assertEquals(listOf("a", "b"), update.lastSpeakers)
    }

    @Test
    fun failedPrimaryStillSettlesLedger() {
        val plan = Plan(primary = members[0])
        val update = SpeakerLedger.update(
            members = members,
            lastSpeakers = listOf("b"),
            plan = plan,
            results = listOf(failed("a")),
        )

        assertEquals("主回失败也算轮到了 TA，冷落计数清零", 0, update.silentTurns["a"])
        assertEquals("其他人照常 +1（旧实现的漏加就在这里）", 2, update.silentTurns["b"])
        assertEquals(1, update.silentTurns["c"])
        assertEquals("失败的主回同样进冷却，2 轮内不连任", listOf("a", "b"), update.lastSpeakers)
    }

    @Test
    fun passedPrimaryStillSettlesLedger() {
        val plan = Plan(primary = members[0])
        val update = SpeakerLedger.update(
            members = members,
            lastSpeakers = emptyList(),
            plan = plan,
            results = listOf(passed("a")),
        )

        assertEquals("`[PASS]` 是正常状态，账本照常结算", 0, update.silentTurns["a"])
        assertEquals(2, update.silentTurns["b"])
        assertEquals(1, update.silentTurns["c"])
        assertEquals(listOf("a"), update.lastSpeakers)
    }

    @Test
    fun secondaryAlsoGetsCreditAndCooldown() {
        val plan = Plan(primary = members[0], secondary = members[1])
        val update = SpeakerLedger.update(
            members = members,
            lastSpeakers = emptyList(),
            plan = plan,
            results = listOf(spoken("a"), spoken("b")),
        )

        assertEquals(0, update.silentTurns["a"])
        assertEquals(0, update.silentTurns["b"])
        assertEquals(1, update.silentTurns["c"])
        assertEquals("lastSpeakers 是新→旧：短反应比主回更近", listOf("b", "a"), update.lastSpeakers)
    }

    @Test
    fun uninvokedSecondaryIsNotCredited() {
        // 主回失败时引擎不会调用第二人（串行）：没轮到就不该白得一次免冷落
        val plan = Plan(primary = members[0], secondary = members[1])
        val update = SpeakerLedger.update(
            members = members,
            lastSpeakers = emptyList(),
            plan = plan,
            results = listOf(failed("a")),
        )

        assertEquals(0, update.silentTurns["a"])
        assertEquals("第二人没真的被调用，冷落计数必须 +1", 2, update.silentTurns["b"])
        assertEquals(1, update.silentTurns["c"])
        assertEquals(listOf("a"), update.lastSpeakers)
        assertFalse(update.lastSpeakers.contains("b"))
    }

    @Test
    fun rejectedTurnLeavesLedgerUntouched() {
        val update = SpeakerLedger.update(
            members = members,
            lastSpeakers = listOf("b"),
            plan = null,
            results = listOf(GroupTurnResult.Rejected("人数不合法")),
        )

        assertEquals(3, update.silentTurns["a"])
        assertEquals(1, update.silentTurns["b"])
        assertEquals(0, update.silentTurns["c"])
        assertEquals(listOf("b"), update.lastSpeakers)
    }

    @Test
    fun lastSpeakersIsDeduplicatedAndTruncatedToCooldown() {
        val plan = Plan(primary = members[0], secondary = members[1])
        val update = SpeakerLedger.update(
            members = members,
            lastSpeakers = listOf("a", "c"),
            plan = plan,
            results = listOf(spoken("a"), spoken("b")),
        )

        assertEquals("不能出现重复 id，且只保留 2 轮冷却窗口", listOf("b", "a"), update.lastSpeakers)
        assertTrue("c 已被挤出 2 轮窗口", "c" !in update.lastSpeakers)
    }

    @Test
    fun everyMemberGetsAnEntryEvenWithoutPlanCredit() {
        val plan = Plan(primary = members[2])
        val update = SpeakerLedger.update(
            members = members,
            lastSpeakers = emptyList(),
            plan = plan,
            results = listOf(spoken("c")),
        )

        assertEquals(setOf("a", "b", "c"), update.silentTurns.keys)
        assertEquals(0, update.silentTurns["c"])
    }
}
