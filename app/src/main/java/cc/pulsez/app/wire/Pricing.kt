package cc.pulsez.app.wire

import cc.pulsez.app.net.Usage

/**
 * 价格表与花费估算。
 *
 * 单位：元 / 百万 token。**不绑定任何厂商**——表只是常见示例，
 * 查不到就按最贵的档位保守估算，并在界面标明"预估，以服务商账单为准"。
 */
object Pricing {

    data class Price(
        val cacheHit: Double,
        val cacheMiss: Double,
        val output: Double,
    )

    /** 内置示例（高峰价）。只用于估算，用户可理解为"大致每轮多少钱"。 */
    private val table: List<Pair<String, Price>> = listOf(
        "deepseek-flash" to Price(cacheHit = 0.04, cacheMiss = 2.0, output = 8.0),
        "deepseek-v4-pro" to Price(cacheHit = 0.30, cacheMiss = 9.0, output = 27.0),
        "glm-5" to Price(cacheHit = 2.0, cacheMiss = 8.0, output = 28.0),
        "kimi-k3" to Price(cacheHit = 2.0, cacheMiss = 20.0, output = 100.0),
        "qwen" to Price(cacheHit = 0.05, cacheMiss = 3.0, output = 9.0),
    )

    /** 查不到时的保守价：按较贵的一档估，宁可让用户看到"可能有点贵"。 */
    private val fallback = Price(cacheHit = 2.0, cacheMiss = 20.0, output = 100.0)

    fun forModel(model: String): Price {
        val key = model.lowercase()
        return table.firstOrNull { key.contains(it.first) }?.second ?: fallback
    }

    /**
     * 估算一轮的花费（元）。
     * 有缓存拆分字段就分开算；没有就把输入全按未命中价算（保守）。
     */
    fun costOf(model: String, usage: Usage?): Double {
        val price = forModel(model)
        if (usage == null) return 0.0
        val hit = usage.cacheHitTokens ?: 0
        val miss = usage.cacheMissTokens ?: (usage.promptTokens - hit).coerceAtLeast(0)
        val inputCost = (hit * price.cacheHit + miss * price.cacheMiss) / 1_000_000.0
        val outputCost = usage.completionTokens * price.output / 1_000_000.0
        return inputCost + outputCost
    }

    /** 这一轮大概省下了多少（相对全未命中）。用于"省下 92%"这种文案。 */
    fun savedRatio(usage: Usage?): Double {
        if (usage == null) return 0.0
        val hit = usage.cacheHitTokens ?: return 0.0
        if (usage.promptTokens <= 0) return 0.0
        return hit.toDouble() / usage.promptTokens
    }

    /**
     * 估算一轮里**图片部分**的输入花费（元，v0.5.0 识图，`）。
     *
     * 口径：官方 vision 按输入 token 计费、每张图缩放后最多 1024 token
     * 。按**未命中价**算（保守：
     * 图片 token 一般不会命中前缀缓存，且宁可高估——见 「不要悄悄扣钱」）。
     *
     * 只是**预估**：界面上配「预估，以服务商账单为准」；用量页「图片」一栏与
     * `UsageStore` 的图片分类共用这个函数。
     */
    fun visionEstimateYuan(model: String, imageCount: Int): Double {
        if (imageCount <= 0) return 0.0
        val price = forModel(model)
        return imageCount * cc.pulsez.app.mem.TokenEstimator.VISION_TOKENS_PER_IMAGE *
            price.cacheMiss / 1_000_000.0
    }

    /**
     * 官方 ASR（语音转文字）单价：**美元 / 小时音频**，list 价。
     *
     * 依据（ASR $0.116/小时，限时 $0.058）、
     * 官方价格页 https://api-docs.deepseek.com/zh-cn/quick_start/pricing
     *
     * 口径说明（**预估，以服务商账单为准**）：
     * - 用 **list 价 $0.116**，不用限时 5 折 $0.058——折扣是限时的，按原价估更保守；
     * - 官方按秒计费（不足 1 秒按 1 秒的说法见官方文档；这里直接按毫秒线性折算，
     *   四舍五入误差远小于 1 分钱）；
     * - 只用于「这条语音大概花多少钱」的界面提示与本地记账，不参与任何计费逻辑。
     */
    const val ASR_USD_PER_HOUR: Double = 0.116

    /**
     * 美元 → 人民币的估算汇率（1 USD ≈ 7.2 CNY）。
     *
     * 项目此前没有汇率常量 的所有人民币示例都按 7.2 折算，且明确标注
     * 「汇率未核实，只看量级」 的人民币价（ASR 0.78 元/小时）来自官方
     * 人民币价格页，与 0.116 USD × 7.2 ≈ 0.84 元/小时同量级（我们取更保守的高值）。
     *
     * 汇率一变这个估算就偏，所以界面文案必须写「预估，以服务商账单为准」
     * （数字规则）。
     */
    const val USD_TO_CNY: Double = 7.2

    /**
     * 估算一段语音转文字的花费（元）。
     *
     * 公式：`durationMs / 3_600_000 × $0.116 × 7.2`。
     * 60 秒 ≈ ¥0.0139、120 秒 ≈ ¥0.0278（按 list 价；限时 5 折时约为一半）。
     *
     * @param durationMs 音频时长（毫秒）；≤ 0 返回 0（没有音频就不花钱）
     */
    fun asrEstimateYuan(durationMs: Long): Double {
        if (durationMs <= 0L) return 0.0
        return durationMs / MILLIS_PER_HOUR * ASR_USD_PER_HOUR * USD_TO_CNY
    }

    private const val MILLIS_PER_HOUR: Double = 3_600_000.0
}
