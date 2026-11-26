package cc.pulsez.app.data.group

import cc.pulsez.app.AppContainer
import cc.pulsez.app.core.GroupHistoryPort
import cc.pulsez.app.core.GroupMemoryPort
import cc.pulsez.app.core.GroupMessage
import cc.pulsez.app.core.GroupReply
import cc.pulsez.app.core.GroupReplyPort
import cc.pulsez.app.core.GroupMember
import cc.pulsez.app.core.GroupUsageEstimator
import cc.pulsez.app.data.db.ThreadKind
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.MemoryRepository
import cc.pulsez.app.mem.BigramIndex
import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.mem.PromptAssembler
import cc.pulsez.app.mem.TokenEstimator
import cc.pulsez.app.net.ApiError
import cc.pulsez.app.net.ApiException
import cc.pulsez.app.net.ChatMessage
import cc.pulsez.app.net.ChatRequest
import cc.pulsez.app.net.LlmEvent
import cc.pulsez.app.net.Usage
import cc.pulsez.app.net.asInternalRequest
import cc.pulsez.app.wire.ErrorCopy
import cc.pulsez.app.wire.ProtocolTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * [GroupHistoryPort] 的 Room 实现（步骤 3「共享历史」）。
 *
 * 只做数据搬运，**不在正文里拼名字前缀**：引擎 [cc.pulsez.app.core.GroupChatEngine] 会按
 * `speakerCharacterId -> 当前成员名` 拼「名字：正文」，这样换群/换成员顺序时前缀仍然正确；
 * 已退出群的老成员用 [GroupMessage.speakerName] 兜底（名字从 character 表补）。
 *
 * 依据 隔离原则「共享历史里的 assistant 消息必须带发言人名字」；
 * 一条消息只能属于一个发言人。
 */
class RoomGroupHistoryPort(
    private val chat: ChatRepository,
    private val characters: CharacterRepository,
) : GroupHistoryPort {

    override suspend fun history(threadId: String): List<GroupMessage> {
        val rows = chat.groupHistory(threadId)
        // 只查出现过的发言人，避免为每条消息都打一次库
        val speakerIds = rows.asSequence().mapNotNull { it.speakerCharacterId }.distinct().toList()
        val names = speakerIds.mapNotNull { id ->
            characters.get(id)?.let { id to it.name }
        }.toMap()
        return rows.map { row ->
            GroupMessage(
                role = row.role,
                content = row.content,
                speakerCharacterId = row.speakerCharacterId,
                speakerName = row.speakerCharacterId?.let(names::get),
                createdAt = row.createdAt,
            )
        }
    }
}

/**
 * [GroupMemoryPort] 的 Room 实现（「记忆读写键统一为 (threadId, characterId)」）。
 *
 * ## 记忆隔离（硬要求）
 * - [renderMemory] 只读 `memory_item` 里 `(threadId, characterId = 当前发言人)` 的行，
 *   A 的记忆不会进 B 的 prompt（验收 5）；
 * - 单聊 thread（`kind = SINGLE`）没有角色维度，沿用 v1 的 `characterId IS NULL` 维度读，
 *   升级用户的老记忆照常可渲染。
 *
 * ## 召回的降级（已核实的数据限制）
 * v2 的 `chunk` / `chunk_term` 表**没有** `characterId` 列（未改检索块 schema），
 * 因此群聊里无法按角色过滤召回。这里按 **thread 归属**降级：只检索当前群聊 thread 的块，
 * 不跨群/不跨角色私聊。群聊的共享历史本来就对所有在场成员可见（隔离原则），
 * 所以这是"能看到同群共享对话"，而不是"A 的记忆串到 B"；真正的记忆隔离由 renderMemory 承担。
 */
class RoomGroupMemoryPort(private val container: AppContainer) : GroupMemoryPort {

    private val memory: MemoryRepository get() = container.memory

    override suspend fun renderMemory(threadId: String, characterId: String, maxTokens: Int): String {
        val thread = container.database.threadDao().byId(threadId)
        val ownerId = when {
            thread == null -> characterId
            thread.kind == ThreadKind.GROUP -> characterId
            // 单聊：v1 起记忆按 thread 维度存（characterId IS NULL）
            else -> null
        }
        val table = memory.load(threadId, ownerId)
        if (table.isEmpty()) return ""
        return PromptAssembler.renderMemory(table, maxTokens)
    }

    override suspend fun recall(threadId: String, characterId: String, query: String): String? {
        if (query.isBlank()) return null
        val terms = BigramIndex.terms(query).take(MAX_QUERY_TERMS)
        if (terms.isEmpty()) return null
        val ids = memory.keywordSearch(threadId, terms, limit = KEYWORD_TOP_K)
        if (ids.isEmpty()) return null
        val byId = memory.chunksByIds(ids).associateBy { it.id }
        val builder = StringBuilder(RECALL_HEADER)
        var used = TokenEstimator.estimate(RECALL_HEADER)
        var picked = 0
        for (id in ids) {
            val chunk = byId[id] ?: continue
            val text = chunk.text.trim()
            if (text.isEmpty()) continue
            val cost = TokenEstimator.estimate(text)
            if (used + cost > RECALL_MAX_TOKENS) continue
            builder.append(text).append('\n')
            used += cost
            picked++
            if (picked >= RECALL_TOP_K) break
        }
        return if (picked == 0) null else builder.toString()
    }

    private companion object {
        /** 与 `ChatEngine.indexTurn` 写倒排时一致的关键词上限。 */
        const val MAX_QUERY_TERMS: Int = 60

        /** 关键词先取 6 个候选，注入 3 条。*/
        const val KEYWORD_TOP_K: Int = 6
        const val RECALL_TOP_K: Int = 3

        /** 召回片段注入上限（≤1200 token）。*/
        const val RECALL_MAX_TOKENS: Int = 1_200

        /** 与 1:1 聊天同一条召回抬头。*/
        const val RECALL_HEADER: String =
            "【你们过去聊过，以下是原文摘录。只在自然相关时提起，不要逐条复述。】\n"
    }
}

/**
 * [GroupReplyPort] 的 `LlmClient` 实现（缺陷 群聊改为**可取消**的流式生成）。
 *
 * ## 为什么从 `complete()` 换成 `stream()`
 * v0.2.0 群聊走 `LlmClient.complete()`：非流式 `call.execute()` 阻塞在 IO 线程上，
 * 取消协程**不会** cancel 底层 OkHttp Call（用户点「停下」后请求照跑到底、照样计费），
 * 而且 `complete()` 不返回 usage，请求跑完后用量被丢弃（有费用、无记录）。
 * `LlmClient.stream()` 是本工程 1:1 聊天已验证的路径：collect 取消会 cancel Call
 * （LlmClient.kt:170 `invokeOnCompletion { call.cancel() }`、:303-306 finally 里
 * `activeCall.getAndSet(null)?.cancel()`），且终止事件 `Finished` 带 usage
 * （LlmClient.kt:268 / :312），能直接记账、不用估。故不新增 `completeCancellable`：
 * 复用已测的流式路径，比再写一套 enqueue + suspendCancellableCoroutine 的网络代码风险更低。
 * 行为上与 1:1 一致：失败不自动重试（避免重复计费），交给引擎的「重试」入口。
 *
 * - `maxTokens` 原样透传（主回 256 / 短反应 48），引擎还会按汉字数二次截断；
 * - 剥离输出协议标签 `[[pz:emo=..|rel=..]]`（PromptAssembler 的协议层要求模型输出它，
 *   用户不该看到），失败一律返回 [GroupReply.failed] 而不是抛异常；
 * - **记账**：成功用服务端 usage，拿不到时按 [GroupUsageEstimator] 估算；
 *   取消时也按估算补记（见 [GroupReplyCall]）。记账失败不影响回复；
 * - 协程取消**不吞**：用户打断/切页必须立即停止（用户永远优先）。
 *
 * @param onReplyStart 每次真正开始生成前回调当前发言人（UI 的「XX 正在写」用：
 *   引擎不提前暴露选人计划，只能从生成端口拿到"这一条是谁在写"）
 */
class LlmGroupReplyPort(
    private val container: AppContainer,
    private val onReplyStart: (GroupMember) -> Unit = {},
) : GroupReplyPort {

    override suspend fun reply(
        character: GroupMember,
        messages: List<MemMessage>,
        maxTokens: Int,
    ): GroupReply {
        onReplyStart(character)
        val client = container.llmClient() ?: return GroupReply(
            text = "",
            failed = true,
            errorMessage = ErrorCopy.of(ApiError.NoKey()),
        )
        val settings = container.settings.flow.first()
        // 本轮起点的清空代际。
        // 本函数的记账在「取消轮次」里用 `NonCancellable` 写（见 GroupReplyCall.recordSafely），
        // 所以协程取消挡不住它；必须在发起处取代际，写成前比对——清空已发生就不写账。
        val roundWipeEpoch = container.wipeEpoch.value
        return try {
            val output = GroupReplyCall(
                model = settings.chatModel,
                stream = client::stream,
                recordUsage = { usage ->
                    // 用量页只做展示：记账失败（IO 异常等）不能把已生成的回复判成失败。
                    runCatching { container.engine.recordUsage(settings.chatModel, usage) }
                },
                // 清空已发生（清空代际变了）→ 这一轮属于清空前的对话，不再写用量。
                recordAllowed = { container.wipeEpoch.value == roundWipeEpoch },
                // 群聊回复是内部请求：DeepSeek 端点必须显式关思考，否则主回 256 / 短反应 48
                // 的 max_tokens 会被默认 high 思考吃光，整轮沉默。见 net/DeepSeekEndpoint.kt。
                internalBaseUrl = settings.chatBaseUrl,
            ).invoke(messages = messages, maxTokens = maxTokens)
            // 协议标签是给客户端解析的，不能进聊天正文。
            GroupReply(
                text = ProtocolTag.parse(output.text).text,
                failed = false,
                usageTokens = output.usage.totalTokens,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ApiException) {
            // 依据：ErrorCopy.kt:20-41，把网络/服务商错误分类转成用户可读文案。
            GroupReply(text = "", failed = true, errorMessage = ErrorCopy.of(e.error))
        } catch (_: Exception) {
            // 失败只报告给引擎，由引擎降级成 Failed（主回可重试、短反应无痕）。
            GroupReply(text = "", failed = true, errorMessage = "没接上。再点一次试试？")
        }
    }
}

/**
 * 群聊一次**可取消**生成 + 用量记账（缺陷）。
 *
 * 拆成独立类的原因：`LlmClient` 是 final 类，无法在 JVM 单测里做 Fake；把
 * 「请求组装 → 收集流 → 估算/读取 usage → 记账」抽成可注入 [stream] / [recordUsage] 的编排，
 * 就能离线验证三件事：
 * 1. 一轮群聊成功后 usage.record **恰好一次**（优先服务端 usage，拿不到才估算）；
 * 2. 用户取消时**停止消费流**，并且已产生的用量仍记一笔（按估算）；
 * 3. 失败发生在已经吐字之后也要记账（服务商很可能已计费）。
 *
 * 估算口径见 [GroupUsageEstimator]：不是服务商账单数字，界面按「预估」展示。
 *
 * @param stream 流式请求入口（生产传 `LlmClient::stream`，冷流每次 collect 都是新请求）
 * @param recordUsage 记账（生产传 `ChatEngine.recordUsage` → `Pricing.costOf` + `UsageStore.record`）
 * @param internalBaseUrl 聊天端点；用于 「群聊回复在 DeepSeek 端点显式关思考」判定，
 *   传空串 = 不判定（测试默认值）。见 `net/DeepSeekEndpoint.kt`。
 * @param recordAllowed 记账前的「还可以写吗」判定：
 *   生产传「清空代际是否已变」；默认 `{ true }` = 既有正常路径行为不变。
 *   为什么必须做成判定而不是只靠取消：取消轮次的记账在 [recordSafely] 里是 `NonCancellable`
 *   写（取消也要落账），协程取消挡不住它，只能由调用方在发起本轮时取代际、写前比对。
 */
class GroupReplyCall(
    private val model: String,
    private val stream: (ChatRequest) -> Flow<LlmEvent>,
    private val recordUsage: suspend (Usage) -> Unit,
    private val internalBaseUrl: String = "",
    private val recordAllowed: () -> Boolean = { true },
    private val maxPreContentRetries: Int = 1,
    private val retryDelayMs: Long = 800L,
) {

    /** 一次生成的结果：[usage] 是**实际记账**用的那份（服务端真实 usage 或本地估算）。 */
    data class Output(val text: String, val usage: Usage)

    /**
     * 发起一次群聊生成并记账。
     *
     * @return 模型原始输出（未剥协议标签）+ 记账使用的 usage
     * @throws ApiException 流以 [LlmEvent.Failed] 结束；失败前的前缀只用于估算已产生的用量，绝不作为 [Output] 返回
     * @throws kotlinx.coroutines.CancellationException 用户取消（取消前已按估算记账）
     */
    suspend fun invoke(messages: List<MemMessage>, maxTokens: Int): Output {
        val request = ChatRequest(
            model = model,
            messages = messages.map { ChatMessage(it.role, it.content) },
            stream = true,
            maxTokens = maxTokens,
            temperature = 1.3,
            includeUsage = true,
        // 群聊回复是内部请求：DeepSeek 端点显式 thinking=disabled。
        ).asInternalRequest(internalBaseUrl)
        val retriesBudget = maxPreContentRetries.coerceAtLeast(0)
        var retriesUsed = 0
        while (true) {
            val buffer = StringBuilder()
            var usage: Usage? = null
            var failure: ApiError? = null
            var sawAnyProgress = false
            val attemptStartNs = System.nanoTime()
            try {
                stream(request).collect { event ->
                    when (event) {
                        is LlmEvent.Delta -> {
                            sawAnyProgress = true
                            buffer.append(event.text)
                        }
                        is LlmEvent.Reasoning -> {
                            sawAnyProgress = true
                        }
                        is LlmEvent.ToolProgress -> Unit
                        is LlmEvent.Finished -> {
                            sawAnyProgress = true
                            usage = event.usage
                        }
                        is LlmEvent.Failed -> failure = event.error
                    }
                }
            } catch (cancelled: CancellationException) {
                // 用户点「停下」：collect 取消会顺带 cancel 底层 OkHttp Call（LlmClient.stream 的
                // finally + Job.invokeOnCompletion，LlmClient.kt:170 / :303-306）。请求已经发生、
                // 可能已计费，但此刻拿不到服务端 usage，只能按本地估算补记一笔（有费用不能无记录）。
                recordSafely(usage ?: GroupUsageEstimator.estimate(messages, buffer.toString()))
                throw cancelled
            }

            val error = failure
            if (error != null) {
                val elapsedMs = (System.nanoTime() - attemptStartNs) / 1_000_000L
                val canRetry = !sawAnyProgress &&
                    buffer.isEmpty() &&
                    retriesUsed < retriesBudget &&
                    isSafePreContentTransientError(error, elapsedMs)
                if (canRetry) {
                    retriesUsed++
                    // 退避等待必须在 try { stream(...) } catch (CancellationException) 之外，
                    // 避免在等待期间取消协程时误入 catch(CancellationException) 补记一次假用量账。
                    if (retryDelayMs > 0L) delay(retryDelayMs)
                    continue
                }
                val recorded = usage ?: GroupUsageEstimator.estimate(messages, buffer.toString())
                // 已经吐过字 = 服务商很可能已经计费，必须记账；一个字都没出就失败（没密钥 / 地址错 /
                // 连不上）不能凭空记一笔钱，否则用量页会越看越假。
                if (buffer.isNotEmpty()) recordSafely(recorded)
                throw ApiException(error)
            }

            val recorded = usage ?: GroupUsageEstimator.estimate(messages, buffer.toString())
            recordSafely(recorded)
            return Output(text = buffer.toString(), usage = recorded)
        }
    }

    /**
     * 开口前（未收到任何 Delta / Reasoning / Finished 且未吐字）的瞬时故障才允许重连，
     * 避免已开始计费或长超时后盲目重试。依据：ApiError.kt:44,56,59。
     */
    private fun isSafePreContentTransientError(error: ApiError, elapsedMs: Long): Boolean =
        when (error.kind) {
            ApiError.Kind.SERVER -> true
            ApiError.Kind.QUOTA -> error.httpCode == 429
            ApiError.Kind.NETWORK -> elapsedMs < PRE_CONTENT_NETWORK_RETRY_WINDOW_MS
            else -> false
        }

    /**
     * 记账失败（DataStore IO 异常等）不能把已生成的回复判成失败；取消时也要能落账，
     * 因此统一在 [NonCancellable] 里写（已取消的协程上下文里 suspend IO 会立刻抛 CancellationException）。
     *
     * 写入前调用 [recordAllowed]。如果已经清空所有数据 →
     * 这一轮属于清空前的对话，用量不得写回（否则用量页会重新出现清空前的花费）；
     * 正常路径（没清空）[recordAllowed] 恒 true，行为与改动前一致。
     */
    private suspend fun recordSafely(usage: Usage) {
        if (!recordAllowed()) return
        runCatching { withContext(NonCancellable) { recordUsage(usage) } }
    }

    private companion object {
        const val PRE_CONTENT_NETWORK_RETRY_WINDOW_MS: Long = 20_000L
    }
}
