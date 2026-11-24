package cc.pulsez.app.mem

import kotlin.math.pow

/**
 * 召回候选（对应 02 §3 chunk 的检索视图，字段与持久层解耦）。
 *
 * @property id chunk id
 * @property text 打包后的原文（03 §7.3：一来一回 + 重叠 ≤300 token）
 * @property vec int8 量化向量；无 embeddings 配置时为 null（03 §7.2）
 * @property lastMessageAt 该 chunk 最后一条消息时间（用于时间衰减，02 §3）
 * @property tokens 原文估算 token（用于注入预算，03 §7.4：合计 ≤1200）
 * @property terms 关键词倒排 term；为空时 rank 会用 [BigramIndex] 现算
 */
data class Candidate(
    val id: String,
    val text: String,
    val vec: QuantizedVector? = null,
    val lastMessageAt: Long = 0L,
    val tokens: Int = 0,
    val terms: Set<String> = emptySet(),
)

/** 内部排序载体。 */
private data class ScoredCandidate(val candidate: Candidate, val score: Double)

/**
 * 召回三件事：构造查询、判断该不该召回、融合排序。
 *
 * 对应文档：
 * - 03 §7.4（查询 = 最近 2 条 + 当前，取最后 300 字；向量 top6 / 余弦 0.35；关键词 top6；
 *   合并 ≤3 条 ≤1200 token）
 * - 03 §7.6（打招呼 / <10 字短句不召回；连续 3 轮无新话题降频为每 2 轮一次）
 * - 02 §3 chunk_term（关键词兜底）
 *
 * 向量是一级能力、关键词是兜底：有 queryVec 时按 0.7 向量 + 0.3 关键词融合，否则纯关键词。
 */
object Recall {

    /** 向量分数权重（03 §7.4）。 */
    const val VECTOR_WEIGHT: Double = 0.7

    /** 关键词分数权重。 */
    const val KEYWORD_WEIGHT: Double = 0.3

    /** 向量召回默认 top-k（03 §7.4 / §9）。 */
    const val DEFAULT_TOP_K: Int = 6

    /** 余弦阈值（03 §7.4 / §9）。 */
    const val DEFAULT_COSINE_THRESHOLD: Float = 0.35f

    /** 短句 / 招呼不召回的字数阈值（03 §7.6：<10 字）。 */
    const val MIN_QUERY_CHARS: Int = 10

    /** 连续多少轮没有新话题后降频（03 §7.6：连续 3 轮）。 */
    const val QUIET_TURNS_BEFORE_DECAY: Int = 3

    /** 查询取最后多少字（03 §7.4：300 字）。 */
    const val QUERY_MAX_CHARS: Int = 300

    /** 时间衰减半衰期（天）。取 180 天，保证半年前的旧事仍可能被召回到（03 §7.5）。 */
    const val DECAY_HALF_LIFE_DAYS: Double = 180.0

    private val GREETING_PATTERN = Regex(
        "^(你好|您好|在吗|在不在|在么|嗨|哈喽|哈罗|hello|hi|hey|早上好|早安|中午好|午安|下午好|晚上好|晚安|早|睡了|睡了吗|吃了没|吃了吗|在干嘛|干嘛呢|在忙吗|忙吗|哈+|嗯+|哦+|好的|行|ok)[呀啊哦呢吧哈啦~！!。.，,？?、\\s]*$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 构造召回查询文本 = 最近 2 条消息 + 当前消息，取最后 [maxChars] 字。
     *
     * 解决"就那个事""跟上次一样"这类指代（03 §7.4）。
     *
     * @param recent 历史消息（按时间正序；只取最后 2 条）
     * @param current 当前用户消息
     * @param maxChars 查询最大字符数，默认 300
     */
    fun buildQuery(
        recent: List<MemMessage>,
        current: MemMessage,
        maxChars: Int = QUERY_MAX_CHARS,
    ): String {
        if (maxChars <= 0) return ""
        val tail = recent.takeLast(2).map { it.content.trim() }.filter { it.isNotEmpty() }
        val joined = (tail + current.content.trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
        if (joined.length <= maxChars) return joined
        return joined.takeLast(maxChars).trimStart()
    }

    /**
     * 是否值得发起一次召回（省调用，03 §7.6）。
     *
     * 规则：
     * 1. 空文本 / 纯标点 / 纯 emoji → 不召回
     * 2. 去掉标点后 < [MIN_QUERY_CHARS] 字（打招呼、情绪短句）→ 不召回
     * 3. 命中招呼词表 → 不召回
     * 4. 连续 [QUIET_TURNS_BEFORE_DECAY] 轮无新话题 → 每 2 轮召回一次（奇数轮跳过）
     *
     * @param userText 用户当前输入
     * @param turnsSinceLastRecall 距上次召回过了多少轮
     */
    fun shouldRecall(userText: String, turnsSinceLastRecall: Int): Boolean {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return false
        val stripped = trimmed.filter { it.isLetterOrDigit() }
        if (stripped.isEmpty()) return false
        if (stripped.length < MIN_QUERY_CHARS) return false
        if (GREETING_PATTERN.matches(trimmed.lowercase())) return false
        val quietTurns = turnsSinceLastRecall.coerceAtLeast(0)
        if (quietTurns >= QUIET_TURNS_BEFORE_DECAY && quietTurns % 2 == 1) return false
        return true
    }

    /**
     * 融合排序：向量 0.7 + 关键词 0.3，再乘时间衰减；无 queryVec 时退化为纯关键词。
     *
     * - 有 queryVec：余弦低于 [threshold] 的候选不计向量分（避免噪声），但关键词命中仍可保留；
     * - 有 queryVec 但候选 `vec == null` / 维度不符（典型：导入块 `dim=0`）：
     *   跳过向量分、只按关键词分参与融合，**不抛异常**（v0.4 阻断）；
     * - 无 queryVec：只保留关键词命中数 > 0 的候选（向量不可用时关键词就是唯一通路）。
     * - 时间衰减相对"候选集内最新时间"计算，保证纯函数、可单测。
     *
     * @param candidates 候选 chunk（调用方已做删除过滤与候选集并集，03 §7.5）
     * @param queryVec 查询向量；null 表示降级为关键词检索
     * @param queryTerms 查询 term，见 [BigramIndex.terms]
     * @param topK 返回条数，默认 6
     * @param threshold 余弦阈值，默认 0.35
     */
    fun rank(
        candidates: List<Candidate>,
        queryVec: FloatArray?,
        queryTerms: Set<String>,
        topK: Int = DEFAULT_TOP_K,
        threshold: Float = DEFAULT_COSINE_THRESHOLD,
    ): List<Candidate> {
        if (candidates.isEmpty() || topK <= 0) return emptyList()
        val reference = candidates.maxOfOrNull { it.lastMessageAt } ?: 0L
        val scored = ArrayList<ScoredCandidate>(candidates.size)
        for (candidate in candidates) {
            val documentTerms = if (candidate.terms.isNotEmpty()) {
                candidate.terms
            } else {
                BigramIndex.terms(candidate.text)
            }
            val hits = if (queryTerms.isEmpty()) 0 else BigramIndex.score(queryTerms, documentTerms)
            val keywordScore = if (queryTerms.isEmpty()) 0.0 else hits.toDouble() / queryTerms.size
            val score = if (queryVec != null) {
                // 第二道防线（v0.4 阻断）：
                // 候选可能来自导入块（dim=0/vec=null）或历史遗留的异维向量。维度不符时
                // **跳过向量分**（保留关键词分），绝不把不匹配的向量喂给 VectorMath.cosine
                // —— 其 require(query.size == document.dim) 会抛，导致整轮 prepare 失败。
                val documentVec = candidate.vec
                val vectorCosine = if (
                    documentVec != null &&
                    documentVec.dim == queryVec.size &&
                    documentVec.bytes.size == documentVec.dim
                ) {
                    VectorMath.cosine(queryVec, documentVec)
                } else {
                    0f
                }
                val vectorScore = if (vectorCosine >= threshold) vectorCosine.toDouble().coerceAtLeast(0.0) else 0.0
                val fused = VECTOR_WEIGHT * vectorScore + KEYWORD_WEIGHT * keywordScore
                if (fused <= 0.0) continue
                fused * timeDecay(candidate.lastMessageAt, reference)
            } else {
                if (hits <= 0) continue
                keywordScore * timeDecay(candidate.lastMessageAt, reference)
            }
            if (!score.isFinite() || score <= 0.0) continue
            scored.add(ScoredCandidate(candidate, score))
        }
        return scored
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.score }
                    .thenByDescending { it.candidate.lastMessageAt }
                    .thenBy { it.candidate.id },
            )
            .take(topK)
            .map { it.candidate }
    }

    private fun timeDecay(lastMessageAt: Long, reference: Long): Double {
        if (lastMessageAt <= 0L || reference <= 0L) return 1.0
        val ageDays = (reference - lastMessageAt).coerceAtLeast(0L) / 86_400_000.0
        return 0.5.pow(ageDays / DECAY_HALF_LIFE_DAYS)
    }
}
