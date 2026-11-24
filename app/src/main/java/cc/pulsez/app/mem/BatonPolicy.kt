package cc.pulsez.app.mem

/**
 * 交接（baton）与增量抽取的阈值策略（纯函数）。
 *
 * 对应文档：
 * - 03 §2.2（预算公式与钳位：`baton_at = max(8000, context_budget × 0.9)`）
 * - 03 §6.0（增量抽取：≥8 轮或 ≥5k token 触发）
 * - 03 §6.1（交接状态机的触发条件）
 * - 03 §6.2（按 40k 分片、片间重叠 2 条消息）
 * - 02 §3 segment（baton 单元）
 */
object BatonPolicy {

    /** 交接最小阈值（03 §2.2）。 */
    const val MIN_BATON_TOKENS: Int = 8_000

    /** 交接比例（03 §2.2）。 */
    const val BATON_RATIO: Double = 0.9

    /** 窗口预留：`budget_soft = min(档位, model_window − 8k)`（03 §2.2）。 */
    const val WINDOW_HEADROOM_TOKENS: Int = 8_000

    /** 模型窗口未知时的保守值（03 §2.2：查不到按 32k 保守起步）。 */
    const val DEFAULT_UNKNOWN_WINDOW_TOKENS: Int = 32_000

    /** 预算价格反推的安全系数（03 §2.2：`× 0.9`）。 */
    const val PRICE_SAFETY_RATIO: Double = 0.9

    /** 预算硬下限，防止极端参数算出 0 / 负值。 */
    const val MIN_CONTEXT_BUDGET: Int = 1_000

    /** 增量抽取的轮数间隔（03 §6.0 / §9：8 轮）。 */
    const val EXTRACT_TURN_INTERVAL: Int = 8

    /** 增量抽取的 token 间隔（03 §9：5k token）。 */
    const val EXTRACT_TOKEN_INTERVAL: Int = 5_000

    /** 分片大小默认值（03 §6.2 / §9：40k）。 */
    const val DEFAULT_SHARD_TOKENS: Int = 40_000

    /** 分片重叠消息数默认值（03 §6.2：2 条）。 */
    const val DEFAULT_SHARD_OVERLAP: Int = 2

    /**
     * 当前段是否该交接（03 §6.1：`段 token ≥ baton_at`）。
     *
     * @param segmentTokens 当前段已累计 token
     * @param contextBudget 上下文预算（[contextBudget] 的结果，不是 baton_at）
     */
    fun shouldBaton(segmentTokens: Int, contextBudget: Int): Boolean {
        if (segmentTokens <= 0 || contextBudget <= 0) return false
        val batonAt = maxOf(MIN_BATON_TOKENS, (contextBudget * BATON_RATIO).toInt())
        return segmentTokens >= batonAt
    }

    /**
     * 是否该做一次增量抽取（03 §6.0 / §9）。
     *
     * 触发条件：距上次抽取 ≥ [EXTRACT_TURN_INTERVAL] 轮，或新增 ≥ [EXTRACT_TOKEN_INTERVAL] token。
     * 节奏保护（间隔 <20 秒等）由调用方负责，本函数只判阈值。
     *
     * @param turnsSinceExtract 距上次抽取的轮数
     * @param tokensSinceExtract 距上次抽取新增的 token
     */
    fun shouldExtract(turnsSinceExtract: Int, tokensSinceExtract: Int): Boolean =
        turnsSinceExtract >= EXTRACT_TURN_INTERVAL || tokensSinceExtract >= EXTRACT_TOKEN_INTERVAL

    /**
     * 按 token 分片，片间保留 [overlap] 条消息重叠（03 §6.2）。
     *
     * 保证：每个分片非空；消息不丢失；进度严格向前（即使单条消息就超过分片预算也不会死循环）。
     *
     * @param messages 待抽取消息（按时间正序）
     * @param shardTokens 单分片 token 预算
     * @param overlap 相邻分片重叠的消息条数
     */
    fun shard(
        messages: List<MemMessage>,
        shardTokens: Int = DEFAULT_SHARD_TOKENS,
        overlap: Int = DEFAULT_SHARD_OVERLAP,
    ): List<List<MemMessage>> {
        if (messages.isEmpty()) return emptyList()
        val budget = shardTokens.coerceAtLeast(1)
        val safeOverlap = overlap.coerceAtLeast(0)
        val result = ArrayList<List<MemMessage>>()
        var start = 0
        while (start < messages.size) {
            val shard = ArrayList<MemMessage>()
            var tokens = 0
            var cursor = start
            while (cursor < messages.size) {
                val messageTokens = TokenEstimator.estimateMessage(messages[cursor])
                if (shard.isNotEmpty() && tokens + messageTokens > budget) break
                shard.add(messages[cursor])
                tokens += messageTokens
                cursor++
            }
            result.add(shard)
            if (cursor >= messages.size) break
            start = maxOf(start + 1, cursor - safeOverlap)
        }
        return result
    }

    /**
     * 按 03 §2.2 公式计算上下文预算（`context_budget`）。
     *
     * ```
     * budget_soft  = min(档位值, model_window − 8_000)
     * budget_price = cost_cap_per_turn × 1_000_000 / hit_price × 0.9
     * context_budget = min(budget_soft, budget_price)
     * ```
     *
     * 钳位：[modelWindow] 为 null / 非正 → 按 32k 保守；结果不小于 [MIN_CONTEXT_BUDGET]。
     * `hitPricePerMillion ≤ 0` 或 `costCapPerTurn ≤ 0` 视为不限制价格。
     *
     * @param contextBudgetTier 窗口档位（32k / 64k / 300k / 手填值；≤0 时用模型窗口）
     * @param modelWindow 模型窗口，可空
     * @param hitPricePerMillion 缓存命中价（元 / 百万 token，如 0.04）
     * @param costCapPerTurn 单轮成本上限（元，如 0.05）
     * @return 上下文预算 token
     */
    fun contextBudget(
        contextBudgetTier: Int,
        modelWindow: Int?,
        hitPricePerMillion: Double,
        costCapPerTurn: Double,
    ): Int {
        val window = if (modelWindow != null && modelWindow > 0) {
            modelWindow
        } else {
            DEFAULT_UNKNOWN_WINDOW_TOKENS
        }
        val tier = if (contextBudgetTier > 0) contextBudgetTier else window
        val budgetSoft = minOf(tier, maxOf(MIN_CONTEXT_BUDGET, window - WINDOW_HEADROOM_TOKENS))
        val budgetPrice = if (hitPricePerMillion > 0.0 && costCapPerTurn > 0.0) {
            costCapPerTurn * 1_000_000.0 / hitPricePerMillion * PRICE_SAFETY_RATIO
        } else {
            Double.MAX_VALUE
        }
        val result = minOf(budgetSoft.toDouble(), budgetPrice)
        return result.coerceAtLeast(MIN_CONTEXT_BUDGET.toDouble()).toInt()
    }

    /**
     * 计算 `baton_at`：交接阈值 = `max(8_000, context_budget × 0.9)`（03 §2.2）。
     *
     * 这就是"baton_at 用的预算"：段 token 达到该值即触发交接（03 §6.1）。
     *
     * @param contextBudgetTier 窗口档位
     * @param modelWindow 模型窗口，可空
     * @param hitPricePerMillion 缓存命中价（元 / 百万 token）
     * @param costCapPerTurn 单轮成本上限（元）
     */
    fun budget(
        contextBudgetTier: Int,
        modelWindow: Int?,
        hitPricePerMillion: Double,
        costCapPerTurn: Double,
    ): Int {
        val context = contextBudget(contextBudgetTier, modelWindow, hitPricePerMillion, costCapPerTurn)
        return maxOf(MIN_BATON_TOKENS, (context * BATON_RATIO).toInt())
    }
}
