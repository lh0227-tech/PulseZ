package cc.pulsez.app.wire

import cc.pulsez.app.mem.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * vision 费用预估。
 *
 * 口径：官方按输入 token 计费、每张图最多 1024 token；
 * 这里按未命中价保守估算，界面上永远标「预估，以账单为准」。
 */
class PricingVisionTest {

    @Test
    fun `deepseek-flash 一张图约千分之一到几厘钱`() {
        // 价格表 deepseek-flash cacheMiss = 2.0 元/百万 token；1024 token → 0.002048 元。
        assertEquals(0.002048, Pricing.visionEstimateYuan("deepseek-flash", 1), 1e-9)
        assertEquals(0.006144, Pricing.visionEstimateYuan("deepseek-flash", 3), 1e-9)
    }

    @Test
    fun `没有图不算钱`() {
        assertEquals(0.0, Pricing.visionEstimateYuan("deepseek-flash", 0), 0.0)
        assertEquals(0.0, Pricing.visionEstimateYuan("deepseek-flash", -3), 0.0)
    }

    @Test
    fun `未知模型按最贵保守档估算`() {
        // fallback cacheMiss = 20.0 元/百万 token → 1024 token ≈ 0.02048 元（宁可高估）。
        assertEquals(0.02048, Pricing.visionEstimateYuan("some-unknown-model", 1), 1e-9)
    }

    @Test
    fun `图片 token 常量与官方口径一致`() {
        assertEquals(1024, TokenEstimator.VISION_TOKENS_PER_IMAGE)
        assertEquals(1024, TokenEstimator.estimateImages(1))
        assertEquals(3072, TokenEstimator.estimateImages(3))
        assertEquals("图片不占文本预算，只合计", 0, TokenEstimator.estimateWithImages("", 0))
    }
}
