package cc.pulsez.app.mem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BatonPolicy] 的交接阈值、抽取阈值、分片与预算公式测试。
 *
 * 对应文档：03（预算公式与钳位）、（增量抽取）、（交接/分片）、。
 */
class BatonPolicyTest {

    @Test
    fun shouldBatonAtMaxOf8kAndNinetyPercent() {
        // 预算 8k → 阈值 max(8k, 7.2k) = 8k
        assertTrue(BatonPolicy.shouldBaton(8_000, 8_000))
        assertFalse(BatonPolicy.shouldBaton(7_999, 8_000))
        // 预算 100k → 阈值 90k
        assertTrue(BatonPolicy.shouldBaton(90_000, 100_000))
        assertFalse(BatonPolicy.shouldBaton(89_999, 100_000))
        // 非法输入不触发
        assertFalse(BatonPolicy.shouldBaton(9_000, 0))
        assertFalse(BatonPolicy.shouldBaton(0, 100_000))
    }

    @Test
    fun shouldExtractAtEightTurnsOrFiveThousandTokens() {
        assertFalse(BatonPolicy.shouldExtract(0, 0))
        assertFalse(BatonPolicy.shouldExtract(7, 4_999))
        assertTrue(BatonPolicy.shouldExtract(8, 0))
        assertTrue(BatonPolicy.shouldExtract(0, 5_000))
    }

    @Test
    fun shardKeepsMessagesOrderAndOverlap() {
        // "a".repeat(392) + "" ≈ 100 token + 4 结构开销 ≈ 104 token / 条
        val messages = (0 until 26).map { index -> MemMessage.user("a".repeat(392) + "#$index") }
        val shards = BatonPolicy.shard(messages, shardTokens = 1_100, overlap = 2)

        assertEquals(3, shards.size)
        assertEquals(10, shards[0].size)
        // 片间重叠 2 条
        assertSame(messages[8], shards[1][0])
        assertSame(messages[9], shards[1][1])
        assertSame(messages[16], shards[2][0])
        assertSame(messages[17], shards[2][1])

        // 所有消息都被覆盖，且不丢顺序
        val covered = shards.flatten().toSet()
        assertEquals(messages.toSet().size, covered.size)
        assertEquals(messages.size, covered.size)
    }

    @Test
    fun shardHandlesSingleOversizedMessage() {
        val messages = listOf(MemMessage.user("a".repeat(10_000)))
        val shards = BatonPolicy.shard(messages, shardTokens = 100, overlap = 2)
        assertEquals(1, shards.size)
        assertEquals(1, shards[0].size)
    }

    @Test
    fun shardOfEmptyListIsEmpty() {
        assertTrue(BatonPolicy.shard(emptyList()).isEmpty())
    }

    @Test
    fun budgetMatchesDocumentedFormula() {
        // 300k 档 / 300k 窗口 / 命中价 0.04 / 单轮上限 0.05：
        // soft = min(300000, 292000) = 292000；price = 0.05*1e6/0.04*0.9 = 1125000
        // context = 292000；baton_at = max(8000, 262800) = 262800
        assertEquals(262_800, BatonPolicy.budget(300_000, 300_000, 0.04, 0.05))
        assertEquals(292_000, BatonPolicy.contextBudget(300_000, 300_000, 0.04, 0.05))
    }

    @Test
    fun budgetFallsBackTo32kWhenWindowUnknown() {
        // 窗口未知 → 32k 保守起步 → soft = 32k - 8k = 24k → baton 21.6k
        assertEquals(21_600, BatonPolicy.budget(300_000, null, 0.04, 0.05))
        assertEquals(24_000, BatonPolicy.contextBudget(300_000, null, 0.04, 0.05))
    }

    @Test
    fun budgetHonoursTierAndWindowHeadroom() {
        // 32k 档在 64k 窗口下不超窗口：min(32000, 56000) = 32000 → baton 28800
        assertEquals(32_000, BatonPolicy.contextBudget(32_000, 64_000, 0.04, 0.05))
        assertEquals(28_800, BatonPolicy.budget(32_000, 64_000, 0.04, 0.05))
        // 手填 200k 但窗口只有 128k：min(200000, 120000) = 120000 → baton 108000
        assertEquals(120_000, BatonPolicy.contextBudget(200_000, 128_000, 0.04, 0.05))
        assertEquals(108_000, BatonPolicy.budget(200_000, 128_000, 0.04, 0.05))
    }

    @Test
    fun budgetIsClampedByPriceCap() {
        // 命中价 10 元/百万：price = 0.05*1e6/10*0.9 = 4500 → context 4500 → baton max(8000, 4050) = 8000
        assertEquals(4_500, BatonPolicy.contextBudget(300_000, 300_000, 10.0, 0.05))
        assertEquals(8_000, BatonPolicy.budget(300_000, 300_000, 10.0, 0.05))
    }

    @Test
    fun budgetHasHardFloor() {
        // 极小窗口也要保底，且 baton_at 不低于 8000
        assertEquals(8_000, BatonPolicy.budget(1_000, 1_000, 0.04, 0.05))
        assertEquals(
            BatonPolicy.MIN_CONTEXT_BUDGET,
            BatonPolicy.contextBudget(1_000, 1_000, 0.04, 0.05),
        )
    }
}
