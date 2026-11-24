package cc.pulsez.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊轮换账本序列化、反序列化与恢复的纯逻辑测试。
 *
 * 的 `lastSpeakers` / `silentTurns` 只活在 ViewModel 内存里：杀进程重进后，
 * 失败与 `[PASS]` 轮次（不落库）对冷落计数的影响全丢，轮换公平性会失真。
 * 这里不依赖 Android DataStore，只测核心事实：JSON 能写进去、能读回来、坏数据不崩、恢复规则正确。
 */
class GroupLedgerPersistenceTest {

    private fun member(id: String, name: String = "成员$id", silentTurns: Int = 0) = GroupMember(
        characterId = id,
        name = name,
        persona = "",
        ordinal = id.hashCode(),
        silentTurns = silentTurns,
    )

    private val members = listOf(member("a"), member("b"), member("c"))

    // ------------------------------------------------------------------ 编解码

    @Test
    fun encodeKeepsBothFieldsAndRoundTrips() {
        val original = SpeakerLedgerSnapshot(
            lastSpeakers = listOf("b", "a"),
            silentTurns = mapOf("a" to 0, "b" to 0, "c" to 2),
        )

        val json = GroupLedgerCodec.encode(original)
        assertTrue("JSON 必须带 lastSpeakers 数组：$json", json.contains("\"lastSpeakers\""))
        assertTrue("JSON 必须带 silentTurns 映射：$json", json.contains("\"silentTurns\""))

        val decoded = GroupLedgerCodec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun decodeReturnsNullForEmptyOrBrokenData() {
        assertNull("从没存过", GroupLedgerCodec.decode(null))
        assertNull(GroupLedgerCodec.decode(""))
        assertNull(GroupLedgerCodec.decode("   "))
        assertNull("坏 JSON 不能让群聊页崩", GroupLedgerCodec.decode("{not json"))
        assertNull("字段类型不对按坏数据处理", GroupLedgerCodec.decode("""{"lastSpeakers":"a"}"""))
    }

    @Test
    fun decodeToleratesMissingAndUnknownFields() {
        val decoded = GroupLedgerCodec.decode("""{"lastSpeakers":["a"],"extra":1}""")
        assertEquals(listOf("a"), decoded?.lastSpeakers)
        assertEquals("缺 silentTurns 时当空", emptyMap<String, Int>(), decoded?.silentTurns)

        val empty = GroupLedgerCodec.decode("{}")
        assertEquals(emptyList<String>(), empty?.lastSpeakers)
        assertEquals(emptyMap<String, Int>(), empty?.silentTurns)
    }

    // ------------------------------------------------------------------ 恢复

    @Test
    fun restoreUsesPersistedLedgerIncludingSilentTurns() {
        val snapshot = SpeakerLedgerSnapshot(
            lastSpeakers = listOf("b", "a"),
            silentTurns = mapOf("a" to 0, "b" to 0, "c" to 5),
        )

        val restored = SpeakerLedgerRestore.restore(members, snapshot)

        assertEquals("冷却窗口原样恢复", listOf("b", "a"), restored.lastSpeakers)
        assertEquals(0, restored.members.first { it.characterId == "a" }.silentTurns)
        assertEquals("失败 / PASS 轮次累出来的冷落计数要能恢复", 5, restored.members.first { it.characterId == "c" }.silentTurns)
    }

    @Test
    fun restoreDropsUnknownMembersAndClampsBadValues() {
        // b 已不在群里（例如被移出群）：账本里它的行与冷却位都要剔除
        val currentMembers = listOf(member("a"), member("c"))
        val snapshot = SpeakerLedgerSnapshot(
            lastSpeakers = listOf("b", "a", "a", "ghost"),
            silentTurns = mapOf("a" to -3, "b" to 4, "ghost" to 9),
        )

        val restored = SpeakerLedgerRestore.restore(currentMembers, snapshot)

        assertEquals("未知成员从 lastSpeakers 里剔除并去重", listOf("a"), restored.lastSpeakers)
        assertEquals("负数钳位到 0", 0, restored.members.first { it.characterId == "a" }.silentTurns)
        assertEquals("快照里缺席的当前成员从 0 开始", 0, restored.members.first { it.characterId == "c" }.silentTurns)
        assertEquals("未知成员的记录不落到任何当前成员", 2, restored.members.size)
    }

    @Test
    fun restoreWithoutSnapshotLeavesHistoryRebuildUntouched() {
        val input = listOf(member("a", silentTurns = 4), member("b", silentTurns = 1))
        val restored = SpeakerLedgerRestore.restore(input, snapshot = null)

        assertEquals("没有持久化数据时保持调用方（历史重建）的结果", input, restored.members)
        assertTrue("没有快照就没有冷却窗口", restored.lastSpeakers.isEmpty())
    }

    @Test
    fun lastSpeakersNeverExceedsCooldownWindow() {
        val restored = SpeakerLedgerRestore.restore(
            members,
            SpeakerLedgerSnapshot(lastSpeakers = listOf("c", "b", "a", "c")),
        )
        assertEquals(GroupChatEngine.SPEAKER_COOLDOWN_TURNS, restored.lastSpeakers.size)
        assertEquals(listOf("c", "b"), restored.lastSpeakers)
    }

    @Test
    fun killAndRestoreCycleKeepsLedger() {
        // 模拟 "一轮结束写入 → 杀进程 → 重新进入群聊读取"
        val afterTurn = members.map { it.copy(silentTurns = if (it.characterId == "b") 0 else it.silentTurns + 1) }
        val saved = SpeakerLedgerRestore.snapshot(afterTurn, lastSpeakers = listOf("b", "a"))

        val reloaded = GroupLedgerCodec.decode(GroupLedgerCodec.encode(saved))
        val restored = SpeakerLedgerRestore.restore(members, reloaded)

        assertEquals("最近主回队列跨进程保持", listOf("b", "a"), restored.lastSpeakers)
        assertEquals(
            afterTurn.associate { it.characterId to it.silentTurns },
            restored.members.associate { it.characterId to it.silentTurns },
        )
        assertFalse("恢复出来的账本不能被历史重建覆盖成空", restored.members.all { it.silentTurns == 0 })
    }
}
