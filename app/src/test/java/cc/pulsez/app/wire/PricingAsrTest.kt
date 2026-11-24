package cc.pulsez.app.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ASR 费用预估（ 语音基建）。
 *
 * 口径：官方 list 价 $0.116/小时音频（不用限时 5 折，保守估）；
 * 汇率按项目既有估算口径 1 USD ≈ 7.2 CNY（汇率未核实）。
 * 界面必须标「预估，以服务商账单为准」。
 */
class PricingAsrTest {

    @Test
    fun `单价与汇率常量`() {
        assertEquals(0.116, Pricing.ASR_USD_PER_HOUR, 1e-12)
        assertEquals("估算汇率", 7.2, Pricing.USD_TO_CNY, 1e-12)
        // 1 小时 = 0.116 × 7.2 = 0.8352 元（官方人民币价 0.78 元/小时的同量级保守高值）。
        assertEquals(0.8352, Pricing.ASR_USD_PER_HOUR * Pricing.USD_TO_CNY, 1e-12)
    }

    @Test
    fun `负数和零不花钱`() {
        assertEquals(0.0, Pricing.asrEstimateYuan(0L), 0.0)
        assertEquals(0.0, Pricing.asrEstimateYuan(-1L), 0.0)
        assertEquals(0.0, Pricing.asrEstimateYuan(Long.MIN_VALUE), 0.0)
    }

    @Test
    fun `1 秒约 0 元但大于 0`() {
        // 1 秒 = 1/3_600_000 小时 × 0.116 美元 × 7.2 ≈ 0.000232 元（约 0.023 分）。
        // 与 的「10 秒 ≈ ¥0.0023」同量级。
        val oneSecond = Pricing.asrEstimateYuan(1_000L)
        assertEquals(2.32e-4, oneSecond, 1e-12)
        assertTrue(oneSecond > 0.0)
    }

    @Test
    fun `60 秒约一分四厘`() {
        assertEquals(0.01392, Pricing.asrEstimateYuan(60_000L), 1e-12)
    }

    @Test
    fun `120 秒约两分八厘`() {
        assertEquals(0.02784, Pricing.asrEstimateYuan(120_000L), 1e-12)
    }

    @Test
    fun `一小时等于单价折人民币`() {
        assertEquals(
            Pricing.ASR_USD_PER_HOUR * Pricing.USD_TO_CNY,
            Pricing.asrEstimateYuan(3_600_000L),
            1e-12,
        )
    }

    @Test
    fun `费用随时长线性增长`() {
        val thirty = Pricing.asrEstimateYuan(30_000L)
        val sixty = Pricing.asrEstimateYuan(60_000L)
        val ninety = Pricing.asrEstimateYuan(90_000L)
        assertTrue(thirty < sixty)
        assertTrue(sixty < ninety)
        assertEquals(thirty * 2, sixty, 1e-12)
        assertEquals(thirty * 3, ninety, 1e-12)
    }

    @Test
    fun `最长录音一分钟不到两分钱`() {
        // 产品口径：60 秒自限；这里只做一个「便宜到可忽略」的量级断言，
        // 防止有人把单价写成每毫秒而不是每小时。
        val sixty = Pricing.asrEstimateYuan(60_000L)
        assertTrue("60 秒应远小于 1 元，实际=$sixty", sixty > 0.0 && sixty < 0.1)
    }
}
