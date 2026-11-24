package cc.pulsez.app.wire

import cc.pulsez.app.net.Usage
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingTest {

    @Test
    fun `缓存命中比未命中便宜一个数量级`() {
        val hit = Pricing.costOf("deepseek-flash", Usage(promptTokens = 100_000, completionTokens = 0, cacheHitTokens = 100_000, cacheMissTokens = 0))
        val miss = Pricing.costOf("deepseek-flash", Usage(promptTokens = 100_000, completionTokens = 0, cacheHitTokens = 0, cacheMissTokens = 100_000))
        assertTrue(miss > hit * 10)
    }

    @Test
    fun `未知模型按保守价估算且不抛异常`() {
        val cost = Pricing.costOf("some-unknown-model", Usage(1_000, 500))
        assertTrue(cost > 0.0)
    }

    @Test
    fun `没有 usage 时成本为 0`() {
        assertTrue(Pricing.costOf("deepseek-flash", null) == 0.0)
    }
}
