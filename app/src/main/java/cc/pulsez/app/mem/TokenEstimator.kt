package cc.pulsez.app.mem

import kotlin.math.ceil

/**
 * 本地 token 估算器：写入时冻结估算值、事后可按 usage 校准（02 §1.7、03 §2 / §9）。
 *
 * 估算口径（文档给定的起步值，可由 [calibrate] 用真实 usage 在线修正）：
 * - CJK 字符（含中文标点 / 全角符号）：0.7 token / 字（03 §2.1、§9 的 `memory_render_tokens` 口径）
 * - 拉丁字母：4 字符 / token（0.25 token / 字符）
 * - ASCII 数字：0.5 token / 字符（数字串单独计价，避免按字母低估）
 * - ASCII 标点：0.25 token / 字符（标点单独处理）
 * - 空白（空格 / 换行 / 全角空格）：0（随词吸收，避免把排版算成成本）
 * - 其它（emoji、其它文字）：1 token / code point
 * 总权重向上取整；空串返回 0。
 *
 * 对应文档：03 §2 / §9、10 §4.3（缓存前缀断言依赖本估算器）、02 §1.7。
 */
object TokenEstimator {

    /** 每条消息的固定结构开销（03 §3.1 的 role / 分隔符粗略开销）。 */
    const val MESSAGE_OVERHEAD_TOKENS: Int = 4

    /** 校准系数下界（03 §9 / 本模块约定）。 */
    const val MIN_CALIBRATION_FACTOR: Double = 0.7

    /** 校准系数上界。 */
    const val MAX_CALIBRATION_FACTOR: Double = 1.5

    /**
     * 一张图片按多少 token 估算（v0.5.0 识图，`）。
     *
     * 官方口径：图片按尺寸折算成输入 token，缩放后**每张最多 1024 token**
     * （`Vision › Token Usage）。
     * 这里取上限当估算值：**标"估算"，不是账单**；精确值以响应 `usage` 为准。
     * 保守取上限的原因：多轮里"当前 + 最近 1 轮带图"每轮都会重算这笔输入，
     * 低估会让用户以为便宜。
     */
    const val VISION_TOKENS_PER_IMAGE: Int = 1_024

    private const val CJK_WEIGHT: Double = 0.7
    private const val LATIN_CHAR_WEIGHT: Double = 0.25
    private const val DIGIT_WEIGHT: Double = 0.5
    private const val ASCII_PUNCT_WEIGHT: Double = 0.25
    private const val OTHER_WEIGHT: Double = 1.0
    private const val EPS: Double = 1e-9

    /**
     * 估算一段文本的 token 数。
     *
     * 对应文档：03 §2（预算模型）、§9（参数表）、10 §4.3。
     *
     * @param text 任意文本
     * @return token 估算值；空串为 0
     */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        val weight = weight(text)
        if (weight <= 0.0) return 0
        return ceil(weight - EPS).toInt()
    }

    /**
     * 估算单条消息（正文 + 每条固定开销）。
     *
     * 对应文档：03 §3.1。
     */
    fun estimateMessage(message: MemMessage): Int =
        estimate(message.content) + MESSAGE_OVERHEAD_TOKENS

    /**
     * 估算消息列表：每条正文估算 + 4 token 结构开销（03 §3.1）。
     */
    fun estimateMessages(messages: List<MemMessage>): Int {
        var total = 0
        for (message in messages) {
            total += estimate(message.content) + MESSAGE_OVERHEAD_TOKENS
        }
        return total
    }

    /**
     * 按张数估算图片占用的输入 token（v0.5.0 识图；见 [VISION_TOKENS_PER_IMAGE]）。
     *
     * 用途：首字超时放大（`LlmClient.estimatePromptTokens`）、总上下文保守估算、
     * 图片花费预估（`Pricing.visionEstimateYuan`）。**不占 ⑤ 段文本预算**
     * （`PromptAssembler.USER_MESSAGE_MAX_TOKENS` 只管正文；依据 `）。
     */
    fun estimateImages(imageCount: Int): Int =
        if (imageCount <= 0) 0 else imageCount * VISION_TOKENS_PER_IMAGE

    /** 文本 + 图片的合计估算（图片按 [VISION_TOKENS_PER_IMAGE]/张；标「估算」）。 */
    fun estimateWithImages(text: String, imageCount: Int): Int =
        estimate(text) + estimateImages(imageCount)

    /**
     * 用外部校准系数修正估算值：`raw * factor`，系数被钳位到 [MIN_CALIBRATION_FACTOR, MAX_CALIBRATION_FACTOR]。
     *
     * 对应文档：02 §1.7（token 估算写入时冻结、可用真实 usage 校准）。
     *
     * @param raw 原始本地估算
     * @param factor 校准系数，默认 1.0（不校准）
     */
    fun calibrated(raw: Int, factor: Double = 1.0): Int {
        if (raw <= 0) return 0
        val clamped = safeFactor(factor)
        return ceil(raw.toDouble() * clamped - EPS).toInt()
    }

    /**
     * 根据一次真实 usage 更新校准系数。
     *
     * 语义：`factor` 是"实际 / 本地估算"的乘数。若本次估算偏低（actual > estimated），
     * 系数按比例放大；结果钳位到 [MIN_CALIBRATION_FACTOR, MAX_CALIBRATION_FACTOR]，
     * 避免单次异常用量把估算器带偏。
     *
     * 对应文档：02 §1.7、03 §2。
     *
     * @param estimated 本次请求的本地估算 token
     * @param actual 服务端返回的 prompt_tokens（或 usage 中的实际值）
     * @param currentFactor 当前系数，默认 1.0
     * @return 新系数（始终落在 0.7–1.5）
     */
    fun calibrate(estimated: Int, actual: Int, currentFactor: Double = 1.0): Double {
        if (!currentFactor.isFinite()) return 1.0
        val current = currentFactor.coerceIn(MIN_CALIBRATION_FACTOR, MAX_CALIBRATION_FACTOR)
        if (estimated <= 0 || actual <= 0) return current
        val next = current * (actual.toDouble() / estimated.toDouble())
        if (!next.isFinite()) return current
        return next.coerceIn(MIN_CALIBRATION_FACTOR, MAX_CALIBRATION_FACTOR)
    }

    /**
     * 按 token 预算截断文本（保留开头），用于 ④/⑤ 段封顶（03 §3.3 硬规则、§9）。
     *
     * 截断后 [estimate] 一定不大于 `maxTokens`；短于预算的文本原样返回。
     *
     * @param text 原文
     * @param maxTokens 允许的最大 token
     */
    fun truncateToTokens(text: String, maxTokens: Int): String {
        if (text.isEmpty()) return text
        if (maxTokens <= 0) return ""
        if (estimate(text) <= maxTokens) return text
        var used = 0.0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val charWeight = weightOf(codePoint)
            if (used + charWeight > maxTokens + EPS) break
            used += charWeight
            index += charCountOf(codePoint)
        }
        return text.substring(0, index).trimEnd()
    }

    /** 文本的总权重（未取整）。 */
    internal fun weight(text: String): Double {
        var sum = 0.0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            sum += weightOf(codePoint)
            index += charCountOf(codePoint)
        }
        return sum
    }

    /** 单个 code point 的权重。 */
    internal fun weightOf(codePoint: Int): Double = when {
        codePoint == 0x20 || codePoint == 0x09 || codePoint == 0x0A || codePoint == 0x0D -> 0.0
        codePoint == 0x3000 -> 0.0
        isCjkCodePoint(codePoint) -> CJK_WEIGHT
        codePoint in 0x30..0x39 -> DIGIT_WEIGHT
        codePoint in 0x41..0x5A -> LATIN_CHAR_WEIGHT
        codePoint in 0x61..0x7A -> LATIN_CHAR_WEIGHT
        codePoint < 0x80 -> ASCII_PUNCT_WEIGHT
        else -> OTHER_WEIGHT
    }

    private fun safeFactor(factor: Double): Double {
        if (!factor.isFinite()) return 1.0
        return factor.coerceIn(MIN_CALIBRATION_FACTOR, MAX_CALIBRATION_FACTOR)
    }
}
