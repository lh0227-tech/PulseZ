package cc.pulsez.app.audio

import cc.pulsez.app.core.UsageLimits
import cc.pulsez.app.data.repo.UsageSnapshot
import cc.pulsez.app.data.settings.Settings
import cc.pulsez.app.data.settings.isDeepSeekProvider
import cc.pulsez.app.wire.Pricing

/**
 * 语音输入能不能用（门禁，v0.5.1 基建层）。
 *
 * DeepSeek 的 ASR 是**私有形状**：`POST {origin}/v1/tools/asr`（multipart 字段 `file`），
 * 不是 OpenAI 兼容的 `/audio/transcriptions`。第三方 OpenAI 兼容服务商没有这个端点，
 * 所以门禁与识图同一套口径：**只在 DeepSeek 官方端点 + 聊天密钥已配置时可用**，
 * 第三方端点不显示入口、不显示假开关。
 *
 * 依据：
 * - （ASR 不是 OpenAI 兼容端点）、
 *   §4.1「需要用户另配服务 / 按端点降级」；
 * - （不支持就置灰并说明）；
 * - 端点判定复用全工程唯一的 `Settings.isDeepSeekProvider()`（net/DeepSeekEndpoint.kt）。
 *
 * 纯函数、无 Android 依赖：可直接 JVM 单测（`VoiceInputGateTest`）。
 */
enum class AsrAvailability {
    /** DeepSeek 官方端点 + 聊天密钥已配置：语音输入可用。 */
    AVAILABLE,

    /** 第三方端点：`/v1/tools/asr` 是 DeepSeek 私有形状，不可用（入口隐藏）。*/
    NOT_DEEPSEEK,

    /** DeepSeek 端点但还没填聊天密钥：不可用（引导去设置填密钥）。 */
    NO_KEY,
}

/** 语音输入门禁判定入口（给下一节点的 UI/ViewModel 用）。 */
object VoiceInputGate {

    /**
     * 当前设置下 ASR 是否可用。
     *
     * @param settings 设置快照（`SettingsStore.flow.first()`）
     */
    fun availability(settings: Settings): AsrAvailability = when {
        !settings.isDeepSeekProvider() -> AsrAvailability.NOT_DEEPSEEK
        !settings.hasChatKey -> AsrAvailability.NO_KEY
        else -> AsrAvailability.AVAILABLE
    }

    /** 便捷布尔版（入口显示/隐藏）。 */
    fun isAvailable(settings: Settings): Boolean =
        availability(settings) == AsrAvailability.AVAILABLE
}

/** 录音前的额度预检结论。 */
sealed interface VoiceQuotaDecision {
    /** 允许录音。 */
    data object Allowed : VoiceQuotaDecision

    /** 按「最长 60 秒」预估后，会把今天/这个月的花费顶到用户设定的上限。 */
    data class Blocked(val reason: VoiceQuotaReason) : VoiceQuotaDecision
}

/** 额度预检的阻断原因（文案由 UI 层写；这里只给结构化原因）。 */
enum class VoiceQuotaReason {
    /** 日上限到顶（日上限优先判定，与 [UsageLimits] 口径一致）。 */
    DAILY_LIMIT,

    /** 月上限到顶。 */
    MONTHLY_LIMIT,
}

/**
 * 录音前的花费上限预检（第 11 项；复用 [UsageLimits] 的判定口径）。
 *
 * 口径：按**最长录音 60 秒**的预估费用（[Pricing.asrEstimateYuan]）先加到已花金额上，
 * 再看是否会达到/超过用户设的日/月上限（上限 0 = 不限制）。这样用户按下麦克风前就能
 * 知道「这一条可能把今天额度用光」，而不是录完/转写完才发现。
 *
 * 为什么不按已有录音长度预检：录音长度在按下时未知，只能按自限上限保守估；
 * 单次 60 秒预估约 ¥0.014（按官方 list 价折算，见 `），误报成本很低。
 */
object VoiceQuotaGate {

    /** 预检用的录音时长：录音器自限的 60 秒（[VoiceRecorderConfig.MAX_RECORD_MS]）。 */
    const val PLANNED_RECORD_MS: Long = VoiceRecorderConfig.MAX_RECORD_MS

    /** 一次最长录音的预估费用（元）；界面文案要标「预估，以服务商账单为准」。 */
    fun estimatedCostYuan(): Double = Pricing.asrEstimateYuan(PLANNED_RECORD_MS)

    /**
     * 预检是否允许录音。
     *
     * @param snapshot 当前记账快照（`UsageStore.flow`；跨天/跨月自动归零）
     * @param dailyLimitYuanX100 日上限（分）；0 = 不限制
     * @param monthlyLimitYuanX100 月上限（分）；0 = 不限制
     */
    fun check(
        snapshot: UsageSnapshot,
        dailyLimitYuanX100: Int,
        monthlyLimitYuanX100: Int,
    ): VoiceQuotaDecision {
        // 把这次最长录音的预估花费先算进去，再复用 UsageLimits（同一套「>= 上限即到顶」口径）。
        val estimated = estimatedCostYuan()
        val projected = snapshot.copy(
            todayYuan = snapshot.todayYuan + estimated,
            monthYuan = snapshot.monthYuan + estimated,
        )
        if (!UsageLimits.isOverLimit(projected, dailyLimitYuanX100, monthlyLimitYuanX100)) {
            return VoiceQuotaDecision.Allowed
        }
        return VoiceQuotaDecision.Blocked(
            reason = when {
                dailyLimitYuanX100 > 0 && projected.todayYuan >= dailyLimitYuanX100 / 100.0 ->
                    VoiceQuotaReason.DAILY_LIMIT
                else -> VoiceQuotaReason.MONTHLY_LIMIT
            },
        )
    }
}
