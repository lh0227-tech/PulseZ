package cc.pulsez.app.mem

import kotlin.math.pow

/**
 * 组装输入：一次请求所需的全部上下文素材。
 *
 * 对应文档：03 §3.1（五段布局）、§4（角色锚三层）、§5.2（记忆渲染）、§7.4（召回注入）。
 *
 * @property personaLayer 人设层正文（用户可改，≤ 40000 字；03 §4.0）
 * @property styleSamples 语气样本（3–5 组，可为空；03 §4.0 样本层）
 * @property protocolEnabled 是否注入协议层（03 §4.0；false 时①不含协议说明，也不产生标签行）
 * @property chronicle 编年史梗概（②段；只在交接后变化）
 * @property history 本段历史原文（③段；调用方负责按窗口滑动取尾部，本类**绝不裁剪中间**）
 * @property memory 记忆表（④段；可为 null）
 * @property recall 召回片段文本，已按 03 §7.7 格式化（④段；可为 null）
 * @property userMessage 最新一条用户消息（⑤段）
 * @property budget 本轮上下文预算（03 §2.2）。仅作调用方契约提示，本类不据此裁剪历史；
 *   超出预算应触发交接（[BatonPolicy]），而不是掐头。
 * @property maxRecallTokens 召回片段允许的最大 token（03 §9：默认 1200）
 * @property creativeMode 是否开启创作模式（v0.4 WP9a）
 * @property isDeepSeek 是否 DeepSeek 官方端点（仅 DeepSeek 端点允许插入 `latest_reminder`）
 * @property currentLatestReminder 当轮 `latest_reminder` 文本（`YYYY-MM-DD,星期X,,App,中文`）
 */
data class AssembleInput(
    val personaLayer: String,
    val styleSamples: List<MemMessage> = emptyList(),
    val protocolEnabled: Boolean = true,
    val chronicle: String? = null,
    val history: List<MemMessage> = emptyList(),
    val memory: MemoryTable? = null,
    val recall: String? = null,
    val userMessage: String,
    val budget: Int = PromptAssembler.DEFAULT_CONTEXT_BUDGET,
    val maxRecallTokens: Int = PromptAssembler.RECALL_MAX_TOKENS,
    val creativeMode: Boolean = false,
    val isDeepSeek: Boolean = false,
    val currentLatestReminder: String? = null,
)

/**
 * Prompt 组装器：严格按缓存友好的五段顺序产出消息列表。
 *
 * ```
 * ① [system]          协议层 + 人设层 + 语气样本                  ← 字节级不变
 * ② [system]?         编年史 + 稳定记忆（在历史之前，不打断近因）   ← 低频变化
 * ③ [messages]        本段历史原文（DeepSeek 含 user 前 latest_reminder）← 只追加
 * ④ [system]?         当轮召回参考（仅命中有效 recall 时注入）       ← 仅命中时出现
 * [latest_reminder]?  当轮场景标记（仅 DeepSeek 且非空白时紧贴 ⑤ 前）
 * ⑤ [messages]        最新一条用户消息                            ← ≤16k token
 * ```
 *
 * 缓存与语感关键（v0.6.10 对齐 DeepSeek）：稳定记忆前移至历史之前，取消每轮 ⑤ 前的机械
 * `【临场块】`/`【尾锚·保持角色】`；未命中召回时，③ 与 ⑤ 之间无 `[system]` 隔断。
 */
object PromptAssembler {

    /** ④ 临场块封顶（03 §3.3 硬规则 4、§9）。 */
    const val EPHEMERAL_LAYER_MAX_TOKENS: Int = 1_500

    /** ⑤ 最新用户消息封顶（03 §9）。 */
    const val USER_MESSAGE_MAX_TOKENS: Int = 16_000

    /** ⑤ 段正文可用 token（扣掉消息结构开销）。 */
    private const val USER_MESSAGE_CONTENT_MAX_TOKENS: Int =
        USER_MESSAGE_MAX_TOKENS - TokenEstimator.MESSAGE_OVERHEAD_TOKENS

    /** 记忆表渲染默认上限（03 §5.2 `memory_render_tokens`、§9）。 */
    const val MEMORY_RENDER_TOKENS: Int = 900

    /** 记忆表"其余条目"的渲染条数上限（03 §5.2：取前 30–40 条）。 */
    const val MEMORY_REST_ITEMS: Int = 40

    /** 召回注入默认上限（03 §9：3 条 / ≤1200 token）。 */
    const val RECALL_MAX_TOKENS: Int = 1_200

    /** 小于该 token 的召回片段不值得注入，直接丢弃。 */
    private const val MIN_RECALL_TOKENS: Int = 32

    /** 编年史上限（03 §6.4 `summary_budget`、§9）。 */
    const val SUMMARY_BUDGET_TOKENS: Int = 6_000

    /** 尾锚上限（03 §4.1、§9：180 ±，120–250）。 */
    const val TAIL_ANCHOR_MAX_TOKENS: Int = 200

    /** 默认上下文预算：300k 档（03 §2.2）。 */
    const val DEFAULT_CONTEXT_BUDGET: Int = 300_000

    /** 新鲜度计算的半衰期（天）；importance × freshness 排序（03 §5.2）。 */
    private const val FRESHNESS_HALF_LIFE_DAYS: Double = 30.0

    /**
     * 组装五段消息列表（v0.6.10 对齐 DeepSeek）。
     *
     * - ① 恒存在且内容只由人设/协议/样本决定（字节级稳定）
     * - ② 编年史与稳定记忆（仅在至少一方非空时出现，位于 ③ 历史之前）
     * - ③ 原样追加历史，**不裁剪中间**（`isDeepSeek == true` 时在带非空白 `latestReminder` 的 `user` 前插入）
     * - ④ 仅当当轮真的命中有效 `recall`（≥ [MIN_RECALL_TOKENS]）时才注入回顾参考 `[system]`；未命中时无 `[system]` 隔断
     * - 当轮 `latest_reminder`（`isDeepSeek == true` 且非空白时紧贴 ⑤ 前）
     * - ⑤ 最后一条 user 消息，硬封顶 [USER_MESSAGE_MAX_TOKENS]
     *
     * @return 可直接发给模型的消息列表；不会修改入参
     */
    fun assemble(input: AssembleInput): List<MemMessage> {
        val messages = ArrayList<MemMessage>(input.history.size * 2 + 5)
        messages.add(MemMessage.system(buildFirstLayer(input)))
        buildPreHistoryContext(input)?.let { messages.add(MemMessage.system(it)) }
        for (msg in input.history) {
            if (input.isDeepSeek && msg.role == MemMessage.ROLE_USER && !msg.latestReminder.isNullOrBlank()) {
                messages.add(MemMessage.latestReminder(msg.latestReminder.trim()))
            }
            messages.add(msg)
        }
        buildRecallLayer(input)?.let { messages.add(MemMessage.system(it)) }
        if (input.isDeepSeek && !input.currentLatestReminder.isNullOrBlank()) {
            messages.add(MemMessage.latestReminder(input.currentLatestReminder.trim()))
        }
        messages.add(
            MemMessage.user(
                TokenEstimator.truncateToTokens(input.userMessage, USER_MESSAGE_CONTENT_MAX_TOKENS),
            ),
        )
        return messages
    }

    /**
     * 协议层文本（03 §4.0：内置、不可改）。
     *
     * 默认 [includeEmotionTag] = `false`：不再强迫模型每轮末尾输出 `[[pz:emo=...|rel=...]]`，
     * 避免对话末尾工整收束并减少对深度思考注意力的分散；[includeEmotionTag] = `true` 时保留
     * 完整 `emo/rel` 枚举契约，兼容旧测试与需要情绪标签的场景。
     *
     * 体量纪律：无论开关，字符数与 token 均不得超过 v0.3 基线（368 字符 / 182 token）。
     *
     * @param protocolEnabled false 时返回空串（03 §4.0：关闭后整行不产生、不解析）
     * @param creativeMode true 时允许创作语境自由展开
     * @param includeEmotionTag true 时追加 `[[pz:emo=..|rel=..]]` 枚举指令；默认 false
     */
    fun buildProtocolLayer(
        protocolEnabled: Boolean,
        creativeMode: Boolean = false,
        includeEmotionTag: Boolean = false,
    ): String {
        if (!protocolEnabled) return ""
        return buildString {
            appendLine("【输出协议】")
            if (includeEmotionTag) {
                appendLine("1. 正文后另起一行输出标签：[[pz:emo=<情绪>|rel=<关系>]]，会被剥离；emo 只取 calm happy shy sad angry sleepy surprised smug，rel 只取 distant normal close intimate，拿不准省略。")
                if (creativeMode) {
                    appendLine("2. 创作模式：完全沉浸在【人设】与故事语境中演绎，篇幅与画面感自由展开，不限字数，不说教。")
                } else {
                    appendLine("2. 全程沉浸在【人设】的语气与关系张力中自然对话，篇幅随语境与人设自由展开，不限句数，不用客服模板。")
                }
                append("3. 旧事只在自然相关时顺带提一条，不汇报，记错就认，不确定说不记得；尊重边界；仅在被问是不是真人时诚实说\"不是。但你说过的话，我尽量记着。\"")
            } else {
                if (creativeMode) {
                    appendLine("1. 创作模式：完全沉浸在【人设】与故事语境中演绎，篇幅与画面感自由展开，不限字数，不说教。")
                } else {
                    appendLine("1. 全程沉浸在【人设】的语气与关系张力中自然对话，篇幅随语境与人设自由展开，不限句数，不用客服模板。")
                }
                append("2. 旧事只在自然相关时顺带提一条，不汇报，记错就认，不确定说不记得；尊重边界；仅在被问是不是真人时诚实说\"不是。但你说过的话，我尽量记着。\"")
            }
        }
    }

    /**
     * 渲染记忆表（03 §5.2）。
     *
     * 优先级：boundaries 全量 → loops 全量 → relation 1 条 → 其余（facts/events/prefs）
     * 按 `importance × 新鲜度` 取前 [MEMORY_REST_ITEMS] 条。所有条目都受 `maxTokens` 硬上限约束：
     * 极端情况下（如 boundaries 本身超长）允许截断靠后的内容，但绝不越界。
     *
     * @param table 记忆表
     * @param maxTokens 渲染上限（默认 900，范围 500–1500）
     * @return 可直接放进 ④ 的文本；无可渲染内容时返回空串
     */
    fun renderMemory(table: MemoryTable, maxTokens: Int = MEMORY_RENDER_TOKENS): String {
        if (maxTokens <= 8 || table.isEmpty()) return ""
        val lines = ArrayList<String>(16)
        var used = 0

        fun append(line: String): Boolean {
            val cost = TokenEstimator.estimate(line)
            if (used + cost > maxTokens) return false
            lines.add(line)
            used += cost
            return true
        }

        render@ run {
            if (!append("【记忆】")) return@run

            val boundaries = table.activeBoundaries()
            if (boundaries.isNotEmpty()) {
                if (!append("◆ 边界（绝不触碰）")) return@run
                for (boundary in boundaries) {
                    if (!append("- ${boundary.value}")) return@run
                }
            }

            val loops = table.activeLoops()
            if (loops.isNotEmpty()) {
                if (!append("◆ 未完结线索")) return@run
                for (loop in loops) {
                    val due = MemTime.formatDate(loop.dueAt ?: 0L)
                    val suffix = if (due != null) "（到期：$due）" else ""
                    if (!append("- ${loop.value}$suffix")) return@run
                }
            }

            table.relation?.let { relation ->
                if (!relation.isBlank()) {
                    val parts = ArrayList<String>(4)
                    relation.stage?.takeIf { it.isNotBlank() }?.let { parts.add("阶段=$it") }
                    relation.nicknameByMe?.takeIf { it.isNotBlank() }?.let { parts.add("TA 叫你「$it」") }
                    relation.nicknameForMe?.takeIf { it.isNotBlank() }?.let { parts.add("你叫 TA「$it」") }
                    relation.mood?.takeIf { it.isNotBlank() }?.let { parts.add("气氛=$it") }
                    if (parts.isNotEmpty() && !append("◆ 关系：" + parts.joinToString("；"))) return@run
                }
            }

            val others = rankRestEntries(table).take(MEMORY_REST_ITEMS)
            if (others.isNotEmpty()) {
                if (!append("◆ 其它（按重要度与新鲜度）")) return@run
                for (entry in others) {
                    if (!append(renderEntry(entry))) return@run
                }
            }
        }

        val text = lines.joinToString("\n")
        return if (TokenEstimator.estimate(text) <= maxTokens) {
            text
        } else {
            TokenEstimator.truncateToTokens(text, maxTokens)
        }
    }

    /**
     * 尾锚（03 §4.1 双锚：④ 里的精简版，约 150–200 token）。
     *
     * 内容：身份提醒、回复长度要求、关系阶段 / 称谓、最多三条最硬边界。
     * v0.4 用"删冗余、同体量替换"补了三条边界：不独占、不制造亏欠、用户拒绝即换话题
     * （依据 原则 11 与 §2.3"拒绝即停"；净增 ≤0：字符 73→71、token 45→45）。
     * 超过 [TAIL_ANCHOR_MAX_TOKENS] 时从尾部截断（先保住身份与长度约束）。
     *
     * @param persona 人设层正文（仅用于判断是否需要"以上面人设为准"提醒）
     * @param relation 关系状态，可为 null
     * @param boundaries 边界列表（只取前三条最硬的）
     */
    fun buildTailAnchor(
        persona: String?,
        relation: Relation?,
        boundaries: List<Boundary> = emptyList(),
        creativeMode: Boolean = false,
    ): String {
        val builder = StringBuilder()
        builder.append("【尾锚·保持角色】\n")
        if (creativeMode) {
            builder.append("贴合人设与剧情自然接话，不限字数，始终入戏。\n")
        } else {
            builder.append("贴合人设与当前语境自然接话，始终入戏。\n")
        }
        relation?.let {
            val parts = ArrayList<String>(4)
            it.stage?.takeIf { stage -> stage.isNotBlank() }?.let { stage -> parts.add("关系阶段=$stage") }
            it.nicknameByMe?.takeIf { nick -> nick.isNotBlank() }?.let { nick -> parts.add("TA 叫你「$nick」") }
            it.nicknameForMe?.takeIf { nick -> nick.isNotBlank() }?.let { nick -> parts.add("你叫 TA「$nick」") }
            it.mood?.takeIf { mood -> mood.isNotBlank() }?.let { mood -> parts.add("气氛=$mood") }
            if (parts.isNotEmpty()) builder.append(parts.joinToString("；")).append('\n')
        }
        val hardBoundaries = boundaries.asSequence()
            .filter { it.isActive() }
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .toList()
        if (hardBoundaries.isNotEmpty()) {
            builder.append("最硬的边界：").append(hardBoundaries.joinToString("；"))
        }
        val text = builder.toString().trimEnd()
        return if (TokenEstimator.estimate(text) <= TAIL_ANCHOR_MAX_TOKENS) {
            text
        } else {
            TokenEstimator.truncateToTokens(text, TAIL_ANCHOR_MAX_TOKENS)
        }
    }

    /**
     * 两条消息列表的最长公共前缀 token 数（10 §4.3 缓存前缀断言的核心度量）。
     *
     * 语义：按消息顺序逐条比较 role 与 content；content 逐字符比较，公共部分用
     * [TokenEstimator] 折算。完全相同的消息计 `正文 token + 4` 结构开销，
     * 最后一条部分相同的消息只计公共字符部分。遇到第一条不同即停止。
     *
     * @return 公共前缀的 token 估算值
     */
    fun commonPrefixTokens(a: List<MemMessage>, b: List<MemMessage>): Int {
        var total = 0
        val limit = minOf(a.size, b.size)
        for (index in 0 until limit) {
            val left = a[index]
            val right = b[index]
            if (left.role != right.role) break
            val common = commonPrefixLength(left.content, right.content)
            val fullyMatched = common == left.content.length && common == right.content.length
            if (fullyMatched) {
                total += TokenEstimator.estimate(left.content) + TokenEstimator.MESSAGE_OVERHEAD_TOKENS
                continue
            }
            total += TokenEstimator.estimate(left.content.substring(0, common))
            break
        }
        return total
    }


    // 内部实现


    private fun buildFirstLayer(input: AssembleInput): String {
        val builder = StringBuilder()
        val protocol = buildProtocolLayer(input.protocolEnabled, input.creativeMode)
        if (protocol.isNotEmpty()) {
            builder.append(protocol).append("\n\n")
        }
        val persona = input.personaLayer.trim()
        if (persona.isNotEmpty()) {
            builder.append("【人设】\n").append(persona)
        }
        val samples = input.styleSamples.filter { it.content.isNotBlank() }
        if (samples.isNotEmpty()) {
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append("【语气样本】模仿下面的说话方式，不要照抄内容：\n")
            samples.forEach { sample ->
                builder.append(formatStyleSample(sample)).append('\n')
            }
        }
        if (builder.isEmpty()) {
            builder.append("【人设】\n保持角色身份与说话方式。")
        }
        return builder.toString().trim()
    }

    private fun buildChronicleLayer(chronicle: String): String {
        val header = "【编年史】以下是你们更早聊过的事（按时间顺序）。只作背景理解，不要主动复述，也不要当成用户刚说的。\n"
        return header + TokenEstimator.truncateToTokens(chronicle, SUMMARY_BUDGET_TOKENS)
    }

    private fun buildPreHistoryContext(input: AssembleInput): String? {
        val chronicle = input.chronicle?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { buildChronicleLayer(it) }
        val memory = input.memory?.takeUnless { it.isEmpty() }
            ?.let { renderMemory(it, MEMORY_RENDER_TOKENS) }
            ?.takeIf { it.isNotEmpty() }
        if (chronicle == null && memory == null) return null
        return listOfNotNull(chronicle, memory).joinToString("\n\n")
    }

    private fun buildRecallLayer(input: AssembleInput): String? {
        val rawRecall = input.recall?.trim().orEmpty()
        if (rawRecall.isEmpty()) return null
        val contentCap = EPHEMERAL_LAYER_MAX_TOKENS - TokenEstimator.MESSAGE_OVERHEAD_TOKENS
        val recallBudget = minOf(input.maxRecallTokens, contentCap)
        if (recallBudget < MIN_RECALL_TOKENS) return null
        if (TokenEstimator.estimate(rawRecall) < MIN_RECALL_TOKENS) return null
        val recall = TokenEstimator.truncateToTokens(rawRecall, recallBudget)
        if (TokenEstimator.estimate(recall) < MIN_RECALL_TOKENS) return null
        return recall
    }

    /** 其余条目排序：importance × 新鲜度，降序；同分按最近出现时间、类型、正文。 */
    private fun rankRestEntries(table: MemoryTable): List<TemporalEntry> {
        val reference = table.activeEntries().maxOfOrNull { entry ->
            maxOf(entry.lastSeenAt, entry.validFrom)
        } ?: 0L
        return table.activeEntries()
            // boundaries 与 loops 已全量单独渲染，不能进"其它"重复出现（03 §5.2）
            .filterNot { it is Boundary || it is Loop }
            .sortedWith(
                compareByDescending<TemporalEntry> { freshnessScore(it, reference) }
                    .thenByDescending { it.lastSeenAt }
                    .thenBy { kindLabel(it) }
                    .thenBy { it.value },
            )
    }

    private fun freshnessScore(entry: TemporalEntry, reference: Long): Double {
        val timestamp = if (entry.lastSeenAt > 0L) entry.lastSeenAt else entry.validFrom
        if (reference <= 0L || timestamp <= 0L) return entry.importance.toDouble()
        val ageDays = (reference - timestamp).coerceAtLeast(0L) / 86_400_000.0
        val freshness = 0.5.pow(ageDays / FRESHNESS_HALF_LIFE_DAYS)
        return entry.importance * freshness
    }

    private fun renderEntry(entry: TemporalEntry): String {
        val builder = StringBuilder("- [").append(kindLabel(entry)).append("] ")
        if (entry is Event) {
            val date = MemTime.formatDate(entry.happenedAt ?: 0L)
            if (date != null) builder.append(date).append('：')
        }
        builder.append(entry.value)
        if (entry.status == MemoryStatus.UNVERIFIED) builder.append("（TA 可能记混了）")
        return builder.toString()
    }

    private fun kindLabel(entry: TemporalEntry): String = when (entry) {
        is Fact -> "事实"
        is Event -> "事件"
        is Loop -> "线索"
        is Pref -> "偏好"
        is Boundary -> "边界"
        else -> "记忆"
    }

    private fun formatStyleSample(sample: MemMessage): String {
        val text = sample.content.trim()
        if (sample.role == MemMessage.ROLE_SYSTEM) {
            val legacyPrefix = "TA 的语气示例："
            if (text.startsWith(legacyPrefix)) {
                return "TA：" + text.removePrefix(legacyPrefix).trimStart()
            }
            if (text.startsWith("TA：") || text.startsWith("你：") || text.startsWith("旁白：")) {
                return text
            }
        }
        return "${roleLabel(sample.role)}：$text"
    }

    private fun roleLabel(role: String): String = when (role) {
        MemMessage.ROLE_ASSISTANT -> "TA"
        MemMessage.ROLE_USER -> "你"
        else -> "旁白"
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var index = 0
        while (index < limit && a[index] == b[index]) index++
        return index
    }
}
