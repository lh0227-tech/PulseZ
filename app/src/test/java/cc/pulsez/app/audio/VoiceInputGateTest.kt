package cc.pulsez.app.audio

import cc.pulsez.app.data.repo.UsageSnapshot
import cc.pulsez.app.data.settings.Settings
import cc.pulsez.app.wire.Pricing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音输入门禁与 60 秒预估额度预检（ 基建层，纯 JVM）。
 *
 * 门禁口径：只有 DeepSeek 官方端点 + 聊天密钥已配置才可用
 * （`/v1/tools/asr` 是 DeepSeek 私有形状）。
 *
 * 额度口径：按最长 60 秒的预估费用先计入已花金额，再复用 `UsageLimits` 的「>= 上限即到顶」判定。
 */
class VoiceInputGateTest {

    private fun settings(baseUrl: String = "https://api.deepseek.com/v1", key: Boolean = true) =
        Settings(chatBaseUrl = baseUrl, hasChatKey = key)

    private fun snapshot(today: Double, month: Double): UsageSnapshot =
        UsageSnapshot(today, month, 0, 0, 0.0)

    // ---------------------------------------------------------------- 门禁

    @Test
    fun `DeepSeek 端点且有密钥才可用`() {
        assertEquals(
            AsrAvailability.AVAILABLE,
            VoiceInputGate.availability(settings()),
        )
        assertTrue(VoiceInputGate.isAvailable(settings()))
    }

    @Test
    fun `没有密钥不可用`() {
        assertEquals(
            AsrAvailability.NO_KEY,
            VoiceInputGate.availability(settings(key = false)),
        )
        assertFalse(VoiceInputGate.isAvailable(settings(key = false)))
    }

    @Test
    fun `第三方端点不可用`() {
        val openAi = settings(baseUrl = "https://api.openai.com/v1")
        assertEquals(AsrAvailability.NOT_DEEPSEEK, VoiceInputGate.availability(openAi))
        // 镜像/伪装域名也按 host 边界判成第三方（复用 DeepSeekEndpoint 的既有判定）。
        assertEquals(
            AsrAvailability.NOT_DEEPSEEK,
            VoiceInputGate.availability(settings(baseUrl = "https://deepseek-proxy.example.com/v1")),
        )
        assertEquals(
            AsrAvailability.NOT_DEEPSEEK,
            VoiceInputGate.availability(settings(baseUrl = "https://api.deepseek.com.evil.com/v1")),
        )
    }

    @Test
    fun `空地址不可用`() {
        assertEquals(AsrAvailability.NOT_DEEPSEEK, VoiceInputGate.availability(settings(baseUrl = "")))
    }

    // ---------------------------------------------------------------- 额度预检

    @Test
    fun `上限为 0 表示不限制 花再多也允许`() {
        val decision = VoiceQuotaGate.check(
            snapshot = snapshot(today = 999.0, month = 9_999.0),
            dailyLimitYuanX100 = 0,
            monthlyLimitYuanX100 = 0,
        )
        assertEquals(VoiceQuotaDecision.Allowed, decision)
    }

    @Test
    fun `有剩余额度时允许`() {
        val decision = VoiceQuotaGate.check(
            snapshot = snapshot(today = 0.50, month = 0.50),
            dailyLimitYuanX100 = 100,
            monthlyLimitYuanX100 = 100,
        )
        assertEquals(VoiceQuotaDecision.Allowed, decision)
    }

    @Test
    fun `按最长 60 秒预估后顶到日上限就拦`() {
        // 已花 0.99 元 + 60 秒预估约 0.0139 元 > 1 元上限。
        val decision = VoiceQuotaGate.check(
            snapshot = snapshot(today = 0.99, month = 0.99),
            dailyLimitYuanX100 = 100,
            monthlyLimitYuanX100 = 0,
        )
        assertEquals(
            VoiceQuotaDecision.Blocked(VoiceQuotaReason.DAILY_LIMIT),
            decision,
        )
    }

    @Test
    fun `日上限没到但月上限会到就按月拦`() {
        val decision = VoiceQuotaGate.check(
            snapshot = snapshot(today = 0.0, month = 0.99),
            dailyLimitYuanX100 = 0,
            monthlyLimitYuanX100 = 100,
        )
        assertEquals(
            VoiceQuotaDecision.Blocked(VoiceQuotaReason.MONTHLY_LIMIT),
            decision,
        )
    }

    @Test
    fun `日上限优先于月上限`() {
        val decision = VoiceQuotaGate.check(
            snapshot = snapshot(today = 0.99, month = 0.99),
            dailyLimitYuanX100 = 100,
            monthlyLimitYuanX100 = 100,
        )
        assertEquals(
            VoiceQuotaDecision.Blocked(VoiceQuotaReason.DAILY_LIMIT),
            decision,
        )
    }

    @Test
    fun `预估口径是 60 秒且与 Pricing 一致`() {
        assertEquals(VoiceRecorderConfig.MAX_RECORD_MS, VoiceQuotaGate.PLANNED_RECORD_MS)
        assertEquals(
            Pricing.asrEstimateYuan(VoiceRecorderConfig.MAX_RECORD_MS),
            VoiceQuotaGate.estimatedCostYuan(),
            0.0,
        )
        // 60 秒 ≈ ¥0.0139（官方 list 价；限时 5 折约一半），量级检查。
        val estimate = VoiceQuotaGate.estimatedCostYuan()
        assertTrue("60 秒预估应大于 0 且小于 2 分钱，实际=$estimate", estimate > 0.0 && estimate < 0.02)
    }
}
