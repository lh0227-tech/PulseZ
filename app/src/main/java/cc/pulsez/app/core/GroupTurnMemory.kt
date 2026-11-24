package cc.pulsez.app.core

import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.mem.MemoryExtractionPrompt
import cc.pulsez.app.mem.TokenEstimator
import cc.pulsez.app.net.ChatMessage
import cc.pulsez.app.net.ChatRequest
import cc.pulsez.app.net.Usage
import cc.pulsez.app.net.asInternalRequest

/**
 * 群聊「各自记忆」的增量抽取（隔离原则）。
 *
 * ## 为什么不能直接复用 1:1 的增量抽取
 * 1:1 的抽取在 [ChatEngine.maintenance] → `extractIfDue` 里做，写入维度是 **thread**
 * （`applyPatch(threadId, json, segmentId)` → `ownerId = null`）。群聊 thread 下如果照搬，
 * 所有角色会被合并进同一个 thread 维度，A 记得的事就会出现在 B 的 prompt 里，直接违反
 * 「记忆按角色隔离」。因此群聊必须单独走一条路径：
 * - 输入：只取「用户说过的话 + 该角色自己说过的话」，**别人的台词一条都不进**；
 * - 写入：`MemoryRepository.applyPatch(..., ownerId = characterId)`。
 *
 * 本文件是纯 Kotlin（只依赖 mem/net 的纯类型），DB/HTTP 通过参数注入，便于 JVM 单测。
 */
object GroupMemoryShard {

    /**
     * 每次抽取取最近多少条群消息（建议 12 条，含本轮）。
     *
     * 只截窗口、不全量读：抽取是后台轻量调用，成本按 控制在 2–4k token。
     */
    const val RECENT_MESSAGE_LIMIT: Int = 12

    /**
     * 从群共享历史里挑出「**该角色**能拿去形成记忆的对话」。
     *
     * 规则（隔离原则）：
     * - 用户消息（`speakerCharacterId == null` / role=user）保留：抽取的是"用户明确说过的事"；
     * - 只有 `speakerCharacterId == characterId` 的成员消息保留（它自己说过的话）；
     * - 别的成员的台词一律丢弃——这是"别人的台词不进它记忆"的第一道闸（第二道是写入时的
     *   `ownerId` 维度，见 [GroupMemoryExtractor] 的调用方）。
     *
     * 顺序是**先过滤、再取最近 [RECENT_MESSAGE_LIMIT] 条**：若先截窗口再过滤，默认 1 人主回时
     * 8 轮里只有约 3–4 轮是该角色说的，别人的台词会把这个角色的素材挤出窗口，导致"抽了 8 轮
     * 却漏掉一半"。先过滤后可覆盖整个 8 轮窗口，而输入体积不变。
     *
     * @param history 群共享历史，时间正序（`RoomGroupHistoryPort.history`）
     * @param characterId 这次抽取的 owner（主回角色）
     * @return 按原时间顺序排列的抽取分片；可能为空
     */
    fun select(history: List<GroupMessage>, characterId: String): List<MemMessage> {
        if (history.isEmpty() || characterId.isBlank()) return emptyList()
        return history
            .mapNotNull { message ->
                when {
                    message.role == MemMessage.ROLE_USER ->
                        MemMessage.user(message.content, message.createdAt)

                    message.role == MemMessage.ROLE_ASSISTANT && message.speakerCharacterId == characterId ->
                        // 只保留该角色自己的发言；它在抽取 prompt 里以 assistant 出现，
                        // 符合 03 §6.3「不要把角色自己的话写成用户事实」的语料结构。
                        MemMessage.assistant(message.content, message.createdAt)

                    // 其他成员的消息：不进它的记忆（隔离原则）。系统消息同理丢弃。
                    else -> null
                }
            }
            .takeLast(RECENT_MESSAGE_LIMIT)
    }
}

/**
 * 群聊一次「按角色」增量抽取的编排器。
 *
 * 依赖全部注入，方便用 Fake 抽取客户端做隔离性测试：
 * - [completeJson]：实际调用 `LlmClient.completeJson` 的地方；
 * - [applyPatch]：实际调用 `MemoryRepository.applyPatch` 的地方（调用方在这里绑定 ownerId）；
 * - [recordUsage]：用量记账（`ChatEngine.recordUsage` → `Pricing.costOf` + `UsageStore.record`）。
 *
 * **失败静默**：本类不吞异常，由调用方 `runCatching { }` 包住；失败不重试，下个窗口合并补
 * （「增量抽取失败 → 跳过，下轮合并补」）。
 */
class GroupMemoryExtractor(
    private val completeJson: suspend (ChatRequest) -> String,
    private val applyPatch: suspend (String) -> Unit,
    private val recordUsage: suspend (Usage) -> Unit = {},
) {

    /**
     * 跑一次轻量抽取。
     *
     * @param model 当前对话模型（与主回复同一个模型，不额外引模型）
     * @param shard 已按角色过滤好的消息分片（[GroupMemoryShard.select] 的产物）
     * @param internalBaseUrl 聊天端点；用于 「内部请求在 DeepSeek 端点显式关思考」判定，
     *   传空串 = 不判定（测试/旧调用点保持"不传字段"）。见 `net/DeepSeekEndpoint.kt`。
     * @return true = 发起并提交了一次抽取；false = 分片为空，什么都没调
     */
    suspend fun extract(
        model: String,
        shard: List<MemMessage>,
        internalBaseUrl: String = "",
    ): Boolean {
        if (shard.isEmpty()) return false
        val systemPrompt = MemoryExtractionPrompt.buildSystem()
        val userPrompt = MemoryExtractionPrompt.buildUser(shard)
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(MemMessage.ROLE_SYSTEM, systemPrompt),
                ChatMessage(MemMessage.ROLE_USER, userPrompt),
            ),
            stream = false,
            jsonMode = true,
            maxTokens = EXTRACTION_MAX_TOKENS,
            temperature = 0.2,
        ).asInternalRequest(internalBaseUrl)
        val json = completeJson(request)
        // 用量记账：LlmClient.completeJson 不返回 usage，只能本地估算（口径见 [GroupUsageEstimator]）。
        // 记账失败不能影响记忆落库，所以这里吞掉。
        runCatching {
            recordUsage(
                GroupUsageEstimator.estimate(
                    prompt = listOf(
                        MemMessage.system(systemPrompt),
                        MemMessage.user(userPrompt),
                    ),
                    completionText = json,
                ),
            )
        }
        applyPatch(json)
        return true
    }

    companion object {
        /** 与 1:1 增量抽取一致的单次输出上限（ChatEngine.extractIfDue）。*/
        const val EXTRACTION_MAX_TOKENS: Int = 2_000
    }
}

/**
 * 群聊用量**估算**（`LlmClient.complete()` 不返回 usage 时的兜底口径）。
 *
 * ## 估算口径（写进注释，界面按「预估」展示）
 * - prompt：`TokenEstimator.estimateMessages` —— CJK 0.7 token/字 + 每条消息 4 token 结构开销；
 * - completion：`TokenEstimator.estimate` —— 与正文同口径（CJK 0.7 token/字）；
 * - 不猜缓存命中：`Usage` 的 cacheHit/cacheMiss 留 null，`Pricing.costOf` 会把输入全部按
 *   未命中价算（**保守偏高**，符合 「查不到就按贵档估」的口径）。
 *
 * 依据TokenEstimator 类注释；这不是服务商账单数字。
 */
object GroupUsageEstimator {

    /** 把一次完整请求/响应折算成 [Usage]（仅估算）。 */
    fun estimate(prompt: List<MemMessage>, completionText: String): Usage = Usage(
        promptTokens = TokenEstimator.estimateMessages(prompt),
        completionTokens = TokenEstimator.estimate(completionText),
    )
}
