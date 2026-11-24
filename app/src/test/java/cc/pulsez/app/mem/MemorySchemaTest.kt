package cc.pulsez.app.mem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [MemorySchema] 的解析容错与双时态合并测试。
 *
 * 对应文档：03、02 memory_item、02。
 */
class MemorySchemaTest {

    private val now = 1_700_000_000_000L

    // MemTime 按设备时区解释 `YYYY-MM-DD`（避免与导入侧 TextParser 口径不一致），
    // 期望值必须用同一口径构造，否则测试结果会随跑测机器的时区变化（而不是随代码变化）。
    private fun date(iso: String): Long =
        LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun parsesFullSchema() {
        val json = """
            {
              "facts":  [{"k": "称呼", "v": "她喜欢被叫「阿淮」", "conf": 0.95, "valid_from": "2026-11-02"}],
              "events": [{"t": "2026-11-18", "v": "说周五有一场面试", "imp": 4}],
              "loops":  [{"v": "面试结果还没告诉她", "due": "2026-11-21"}],
              "relation": {"stage": "暧昧", "nickname_by_me": "阿淮", "nickname_for_me": "小鹿", "mood": "轻松"},
              "boundaries": [{"v": "不要主动提前男友"}],
              "prefs":  [{"v": "喜欢雨天，讨厌被叫全名"}],
              "unknown_field": {"ignored": true}
            }
        """.trimIndent()
        val table = MemorySchema.parse(json)
        assertNotNull(table)
        table!!
        assertEquals(1, table.facts.size)
        assertEquals("称呼", table.facts[0].key)
        assertEquals(date("2026-11-02"), table.facts[0].validFrom)
        assertEquals(1, table.events.size)
        assertEquals(4, table.events[0].importance)
        assertEquals(date("2026-11-18"), table.events[0].happenedAt)
        assertEquals(date("2026-11-21"), table.loops[0].dueAt)
        assertEquals("暧昧", table.relation?.stage)
        assertEquals("阿淮", table.relation?.nicknameByMe)
        assertEquals("小鹿", table.relation?.nicknameForMe)
        assertEquals(1, table.boundaries.size)
        assertEquals(1, table.prefs.size)
        assertEquals(5, table.activeCount())
    }

    @Test
    fun toleratesMarkdownFenceAndSurroundingText() {
        val json = """
            好的，下面是抽取结果：
            ```json
            {"facts": [{"k": "城市", "v": "在杭州做设计"}]}
            ```
            希望有帮助。
        """.trimIndent()
        val table = MemorySchema.parse(json)
        assertNotNull(table)
        assertEquals(1, table?.facts?.size)
    }

    @Test
    fun rejectsBrokenJson() {
        assertNull(MemorySchema.parse(""))
        assertNull(MemorySchema.parse("   "))
        assertNull(MemorySchema.parse("not json at all"))
        assertNull(MemorySchema.parse("{\"facts\": [}"))
        assertNull(MemorySchema.parse("[1,2,3]"))
    }

    @Test
    fun emptyObjectYieldsEmptyTable() {
        val table = MemorySchema.parse("{}")
        assertNotNull(table)
        assertEquals(0, table?.activeCount())
        assertNull(table?.relation)
        assertTrue(table!!.isEmpty())
    }

    @Test
    fun dropsLowConfidenceAndTruncatesLongValues() {
        val longValue = "这是一条超长的记忆内容。".repeat(30) // 360 字
        val json = """
            {
              "facts": [
                {"k": "低置信", "v": "可能吧", "conf": 0.5},
                {"k": "长文本", "v": "$longValue", "conf": 0.9}
              ],
              "boundaries": [{"v": "不要问体重", "conf": 0.59}]
            }
        """.trimIndent()
        val table = MemorySchema.parse(json)
        assertNotNull(table)
        assertEquals(1, table?.facts?.size)
        assertEquals(MemorySchema.MAX_VALUE_CHARS, table?.facts?.get(0)?.value?.length)
        assertTrue(table!!.boundaries.isEmpty())
    }

    @Test
    fun dropsTypeMismatchAndUnknownFields() {
        val json = """
            {
              "facts": [{"v": 123}, "not-an-object", {"k": 42, "v": "有效内容"}],
              "events": "oops",
              "loops": 42,
              "relation": "not-an-object",
              "some_other": [{"v": "应被丢弃"}]
            }
        """.trimIndent()
        val table = MemorySchema.parse(json)
        assertNotNull(table)
        assertEquals(1, table?.facts?.size) // 只有 k=42 被丢弃后剩 v="有效内容" 一条
        assertEquals("有效内容", table?.facts?.get(0)?.value)
        assertTrue(table!!.events.isEmpty())
        assertTrue(table.loops.isEmpty())
        assertNull(table.relation)
    }

    @Test
    fun mergeSupersedesSameKeyAndKeepsHistory() {
        val old = MemoryTable(
            facts = listOf(Fact(key = "称呼", value = "叫小鹿", validFrom = 100L, lastSeenAt = 100L)),
        )
        val patch = MemoryTable(facts = listOf(Fact(key = "称呼", value = "改叫阿淮")))
        val merged = MemorySchema.merge(old, patch, now)

        assertEquals(2, merged.facts.size)
        val historical = merged.facts[0]
        val current = merged.facts[1]
        assertEquals("叫小鹿", historical.value)
        assertEquals(now, historical.validTo)
        assertEquals(MemoryStatus.SUPERSEDED, historical.status)
        assertTrue(!historical.isActive())
        assertEquals("改叫阿淮", current.value)
        assertNull(current.validTo)
        assertEquals(now, current.validFrom)
        assertEquals(now, current.lastSeenAt)
        // 同一 key 只有一条有效记录（03 8）
        assertEquals(1, merged.activeFacts().size)
    }

    @Test
    fun mergeSameValueRefreshesInsteadOfDuplicating() {
        val old = MemoryTable(
            facts = listOf(Fact(key = "称呼", value = "叫小鹿", validFrom = 100L, lastSeenAt = 100L)),
        )
        val patch = MemoryTable(facts = listOf(Fact(key = "称呼", value = "叫小鹿", conf = 0.95, importance = 4)))
        val merged = MemorySchema.merge(old, patch, now)

        assertEquals(1, merged.facts.size)
        assertEquals(now, merged.facts[0].lastSeenAt)
        assertEquals(0.95, merged.facts[0].conf, 1e-9)
        assertEquals(4, merged.facts[0].importance)
        assertEquals(100L, merged.facts[0].validFrom)
    }

    @Test
    fun mergeEventsDeduplicates() {
        val old = MemoryTable(events = listOf(Event(value = "说周五有一场面试", happenedAt = date("2026-11-18"))))
        val patch = MemoryTable(events = listOf(Event(value = "说周五有一场面试", happenedAt = date("2026-11-18"))))
        val merged = MemorySchema.merge(old, patch, now)
        assertEquals(1, merged.events.size)
        assertEquals(1, merged.activeEvents().size)
    }

    @Test
    fun mergeRelationOverridesNonBlankFields() {
        val old = MemoryTable(relation = Relation(stage = "熟悉", nicknameByMe = "小鹿", mood = "平淡"))
        val patch = MemoryTable(relation = Relation(stage = "暧昧", mood = "轻松"))
        val merged = MemorySchema.merge(old, patch, now)
        assertEquals("暧昧", merged.relation?.stage)
        assertEquals("小鹿", merged.relation?.nicknameByMe)
        assertEquals("轻松", merged.relation?.mood)
        assertEquals(now, merged.relation?.updatedAt)
    }

    @Test
    fun mergeLoopDueChangeKeepsHistory() {
        val old = MemoryTable(loops = listOf(Loop(value = "面试结果还没告诉她", dueAt = date("2026-11-21"))))
        val patch = MemoryTable(loops = listOf(Loop(value = "面试结果还没告诉她", dueAt = date("2026-11-25"))))
        val merged = MemorySchema.merge(old, patch, now)
        assertEquals(2, merged.loops.size)
        assertEquals(1, merged.activeLoops().size)
        assertEquals(date("2026-11-25"), merged.activeLoops()[0].dueAt)
        assertEquals(now, merged.loops[0].validTo)
    }

    @Test
    fun mergeEnforcesStorageCap() {
        val patch = MemoryTable(
            facts = (1..130).map { index -> Fact(key = "key$index", value = "第 $index 条事实") },
        )
        val merged = MemorySchema.merge(MemoryTable(), patch, now)
        assertEquals(130, merged.facts.size)
        assertEquals(MemoryTable.MAX_FACTS, merged.activeFacts().size)
        // 被淘汰的条目只标记失效，不物理删除（03）
        assertEquals(10, merged.facts.count { it.status == MemoryStatus.SUPERSEDED })
    }

    @Test
    fun mergeNormalizesDuplicateActiveEntries() {
        // 损坏数据里同一个 key 有两条有效记录，合并时必须收敛为一条
        val old = MemoryTable(
            facts = listOf(
                Fact(key = "称呼", value = "旧称呼", validFrom = 1L, lastSeenAt = 1L),
                Fact(key = "称呼", value = "新称呼", validFrom = 2L, lastSeenAt = 2L),
            ),
        )
        val merged = MemorySchema.merge(old, MemoryTable(), now)
        assertEquals(1, merged.activeFacts().size)
        assertEquals("新称呼", merged.activeFacts()[0].value)
    }

    @Test
    fun parseIgnoresSecondLevelJsonString() {
        // 单层 JSON：facts 里塞 JSON 字符串不允许被当成对象解析
        val json = """{"facts": ["{\"k\":\"x\",\"v\":\"y\"}"]}"""
        val table = MemorySchema.parse(json)
        assertNotNull(table)
        assertTrue(table!!.facts.isEmpty())
    }
}
