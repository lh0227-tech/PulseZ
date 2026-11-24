@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package cc.pulsez.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI 兼容对话客户端。
 *
 * ## 超时策略（重要）
 * - `callTimeout` 保持 0，长回答不会被腰斩（由 [HttpFactory] 保证）。
 * - **首字超时**：从请求发出到最后一次收到字节。默认 90s（口径；docs 写 120s），
 *   按 prompt 估算长度放大：`60s + tokens / 20000 s`，上限 180s。
 * - **流中心跳**：只在收到第一个 event 之后才启动；45s 无 event 判死。
 * - **keep-alive 不算"没输出"**：收到任何一行（含 `: keep-alive` 注释行、空帧）都刷新
 *   首字截止时间与心跳时间。思考 / 联网搜索会出现分钟级只有 keep-alive、没有正文的阶段，
 *   不这么做会被自己的首字超时掐断。
 * - 两者绝不能合并成一个 45s 超时，否则每次长 prompt 的第一轮都会被误判中断。
 *
 * ## 重连不重发
 * 断线后只发一次 [LlmEvent.Failed]（[ApiError.StreamBroken]），保留已生成内容，
 * 由上层让用户选「继续」或「重试」。绝不自动重发上一条（会重复生成、重复计费）。
 *
 * 超时 / 退避参数全部可注入，默认值按文档，便于 JVM 单测。
 */
class LlmClient(
    private val http: OkHttpClient,
    baseUrl: String,
    apiKey: String,
    private val firstByteTimeoutMs: Long = DEFAULT_FIRST_BYTE_TIMEOUT_MS,
    private val heartbeatTimeoutMs: Long = DEFAULT_HEARTBEAT_TIMEOUT_MS,
    private val firstByteTimeoutCapMs: Long = DEFAULT_FIRST_BYTE_TIMEOUT_CAP_MS,
    private val firstByteScaleBaseMs: Long = DEFAULT_FIRST_BYTE_SCALE_BASE_MS,
    private val promptTokenEstimator: (List<ChatMessage>) -> Int = { estimatePromptTokens(it) },
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) {

    private val apiKey: String = apiKey.trim()

    /**
     * 流式请求专用的 client 视图：把 readTimeout / callTimeout 归零。
     * 即使调用方传进来一个默认 `OkHttpClient()`（readTimeout=10s），长回答也不会被腰斩；
     * 半开连接由 [stream] 内部的首字 / 心跳计时器负责。连接池、Dispatcher 与原 client 共享。
     */
    private val streamingHttp: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** 用户配置的归一化 base（缓存键，不随 /v1 解析改变）。*/
    private val configuredBaseUrl: String = BaseUrl.normalize(baseUrl)

    @Volatile
    private var resolvedBaseUrl: String = BaseUrl.initial(baseUrl)

    /** 服务商是否支持 stream_options（400 一次后记住）。*/
    @Volatile
    private var supportsStreamOptions: Boolean = true

    val baseUrl: String get() = resolvedBaseUrl

    // ------------------------------------------------------------------ 流式

    /**
     * 流式对话。冷流：每次 collect 都是一次新的 HTTP 请求。
     *
     * 终止事件恰好一个：[LlmEvent.Finished] 或 [LlmEvent.Failed]。
     * collect 取消会立即 cancel 底层 OkHttp Call，不会泄漏连接。
     */
    fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        if (apiKey.isEmpty()) {
            emit(LlmEvent.Failed(ApiError.NoKey()))
            return@flow
        }
        if (resolvedBaseUrl.isEmpty()) {
            emit(LlmEvent.Failed(ApiError.BadResponse(raw = "base url is blank")))
            return@flow
        }

        var terminal: LlmEvent? = null
        val firstByteNanos = firstByteTimeoutFor(request.messages) * NANOS_PER_MILLI

        coroutineScope {
            val activeCall = AtomicReference<Call?>(null)
            val firstEventSeen = AtomicBoolean(false)
            val finishSeen = AtomicBoolean(false)
            val lastEventAtNanos = AtomicLong(0L)
            // 收到 finish_reason 后短暂"排空"：有些服务商把 usage 放在 finish 之后的独立帧里。
            val drainDeadlineNanos = AtomicLong(0L)
            val timeoutKind = AtomicReference<StreamTimeout?>(null)
            // 首字截止时间用 AtomicLong：收到 keep-alive 注释行也要往后推（见读取循环里的 markActivity）。
            val firstDeadlineNanos = AtomicLong(System.nanoTime() + firstByteNanos)

            val watchdog = launch {
                while (isActive) {
                    val due = when {
                        finishSeen.get() -> drainDeadlineNanos.get()
                        firstEventSeen.get() -> lastEventAtNanos.get() + heartbeatTimeoutMs * NANOS_PER_MILLI
                        else -> firstDeadlineNanos.get()
                    }
                    val remainingNanos = due - System.nanoTime()
                    if (remainingNanos <= 0L) {
                        val kind = when {
                            finishSeen.get() -> StreamTimeout.DRAIN
                            firstEventSeen.get() -> StreamTimeout.HEARTBEAT
                            else -> StreamTimeout.FIRST_BYTE
                        }
                        timeoutKind.compareAndSet(null, kind)
                        activeCall.get()?.cancel()
                        return@launch
                    }
                    // 留出至少 5ms，避免 0 延迟空转；真实超时仍按绝对时间判定。
                    delay((remainingNanos / NANOS_PER_MILLI).coerceAtLeast(5L))
                }
            }

            var response: Response? = null
            var callHandle: DisposableHandle? = null
            var sawEvent = false
            var sawDone = false
            var finishReason: String? = null
            var usage: Usage? = null
            var failure: ApiError? = null
            // v0.5.0 识图：服务商对图片的内容安全拒绝。
            // 两种形态都要当「图片被服务商拒绝」而不是普通空回答：
            // 1. 流式 `delta.refusal`（OpenAI 兼容形态）；
            // 2. `finish_reason = "content_filter"` 且没有正文。
            // 上层按 ApiError.Filtered 换人话（media/发送链路），**不冒称危机干预**。
            var refusalSeen = false
            var sawContent = false
            // 带联网工具的请求被 400 拒绝后，是否已经做过一次"去掉 tools"的降级重试。
            // 独立布尔量（不复用 attempts）保证**只降级一次**，不会因为 404/stream_options 也重试过而漏掉或重试两次。
            var webSearchDropped = false
            // 联网搜索的引用：流式下完整 annotations 只在最后一帧下发。
            // 用 LinkedHashMap 按 url 去重且保持顺序（同一条来源可能重复出现）。
            val citations = LinkedHashMap<String, Citation>()

            try {
                var urlBase = resolvedBaseUrl
                var includeStreamOptions = request.includeUsage && supportsStreamOptions
                // 降级重试会改写请求体（例如去掉 tools），后续循环必须用改写后的这份。
                var activeRequest = request
                var attempts = 0

                while (true) {
                    if (timeoutKind.get() != null) {
                        failure = timeoutError(timeoutKind.get()!!, sawEvent)
                        break
                    }

                    val httpRequest = try {
                        buildChatRequest(
                            request = activeRequest,
                            urlBase = urlBase,
                            includeStreamOptions = includeStreamOptions,
                            useJsonMode = activeRequest.jsonMode,
                        )
                    } catch (e: IllegalArgumentException) {
                        failure = ApiError.BadResponse(raw = "invalid base url")
                        break
                    }

                    val call = streamingHttp.newCall(httpRequest)
                    activeCall.set(call)
                    callHandle?.dispose()
                    callHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

                    val resp = try {
                        call.execute()
                    } catch (io: IOException) {
                        currentCoroutineContext().ensureActive()
                        failure = streamFailure(io, timeoutKind.get(), firstEventSeen.get())
                        break
                    }
                    response = resp

                    if (!resp.isSuccessful) {
                        val code = resp.code
                        // 错误体只读一次（readErrorBody 会消费 body）：分类与 的关键词判定共用。
                        val errorBody = JsonHttp.readErrorBody(resp)
                        val error = ApiErrorMapper.fromHttp(code, errorBody)
                        activeCall.compareAndSet(call, null)
                        runCatching { resp.close() }
                        response = null
                        // 404 且 base 不像 /v1：补 /v1 重试一次。请求还没产生内容，不会重复计费。
                        if (code == 404 && !BaseUrl.isV1(urlBase) && attempts < MAX_PRE_STREAM_ATTEMPTS) {
                            urlBase = BaseUrl.withV1(urlBase)
                            attempts++
                            continue
                        }
                        // 带联网工具的请求 400，且错误体像"历史没回传 reasoning_content /
                        // server_tool_calls"或"不认识 deepseek_web_search"：去掉 tools 再试一次，
                        // **保留思考设置**。依据 与 。
                        // 只降级一次；降级后仍失败就走下面的正常错误分类（或 stream_options 回退）。
                        if (code == 400 &&
                            activeRequest.webSearch &&
                            !webSearchDropped &&
                            isWebSearchRejection(errorBody)
                        ) {
                            webSearchDropped = true
                            activeRequest = activeRequest.copy(webSearch = false)
                            attempts++
                            continue
                        }
                        // 400 且看起来只是 stream_options 不被支持：去掉再试一次并记住。
                        if (code == 400 &&
                            includeStreamOptions &&
                            error.kind == ApiError.Kind.BAD_RESPONSE &&
                            attempts < MAX_PRE_STREAM_ATTEMPTS
                        ) {
                            supportsStreamOptions = false
                            includeStreamOptions = false
                            attempts++
                            continue
                        }
                        failure = error
                        break
                    }

                    rememberResolvedBase(urlBase)
                    val body = resp.body
                    if (body == null) {
                        failure = ApiError.BadResponse(httpCode = resp.code, raw = "empty response body")
                        break
                    }

                    // 200 + JSON 错误体：错误不走 SSE，单独分支（第 4 条）。
                    val contentType = body.contentType()?.toString()?.lowercase().orEmpty()
                    if (contentType.contains("application/json") && !contentType.contains("event-stream")) {
                        val text = runCatching { body.string() }.getOrDefault("")
                        failure = ApiError.BadResponse(
                            httpCode = resp.code,
                            raw = messageFromErrorBody(text) ?: errorSnippet(text),
                        )
                        break
                    }

                    val source = body.source()
                    var currentEvent: String? = null
                    try {
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            // 生死判定只看"有没有字节回来"，不看"有没有正文"：
                            // 思考 / 联网搜索时服务端可能几分钟只发 `: keep-alive` 注释行，
                            // 旧实现把这些行 continue 掉、不刷新计时，90s 首字超时会把请求自己掐断。
                            // 现在任何一行（注释、空帧、event:、data:）都刷新首字截止时间与心跳时间。
                            // 依据DeepSeek 官方 rate_limit 文档的 keep-alive 说明。
                            val activeAt = System.nanoTime()
                            lastEventAtNanos.set(activeAt)
                            firstDeadlineNanos.set(activeAt + firstByteNanos)
                            if (line.isBlank()) {
                                currentEvent = null
                                continue
                            }
                            val parsed = Sse.parse(line) ?: continue
                            parsed.event?.let { currentEvent = it }
                            val data = parsed.data
                            if (data.isBlank()) continue // keep-alive / 只有 event: 的帧
                            val payload = data.trim()
                            if (payload == Sse.DONE) {
                                sawDone = true
                                break
                            }

                            val chunk = runCatching {
                                JsonHttp.json.parseToJsonElement(payload) as? JsonObject
                            }.getOrNull()
                            if (chunk == null) {
                                // 非 JSON 的错误 data（部分服务商的 event: error 帧是纯文本）
                                failure = ApiError.BadResponse(
                                    httpCode = resp.code,
                                    raw = messageFromErrorBody(payload) ?: errorSnippet(payload),
                                )
                                break
                            }
                            if (currentEvent == "error" || (chunk["error"] != null && chunk["choices"] == null)) {
                                failure = ApiError.BadResponse(
                                    httpCode = resp.code,
                                    raw = messageFromErrorBody(payload),
                                )
                                break
                            }

                            sawEvent = true
                            firstEventSeen.set(true)

                            chunk["usage"]?.let { usage = mergeUsage(usage, parseUsage(it)) }

                            // finish_reason 之后只排空 usage，不再照常处理 choices（可能已经结束）。
                            if (finishReason == null) {
                                var chunkFinish: String? = null
                                chunk["choices"]?.asArrayOrNull()?.forEach { element ->
                                    val choice = element as? JsonObject ?: return@forEach
                                    val message = choice["message"] as? JsonObject
                                    val delta = (choice["delta"] as? JsonObject) ?: message
                                    val content = delta?.stringOrNull("content").orEmpty()
                                    if (content.isNotEmpty()) {
                                        sawContent = true
                                        emit(LlmEvent.Delta(content))
                                    }
                                    // `delta.refusal`：图片/内容被服务商安全策略挡下。
                                    // 不把它当正文；只在"整轮没正文"时判成 Filtered（见下方终态判定）。
                                    if (!delta?.stringOrNull("refusal").isNullOrEmpty()) refusalSeen = true
                                    val reasoning = delta?.stringOrNull("reasoning_content")
                                        ?: delta?.stringOrNull("reasoning")
                                    if (!reasoning.isNullOrEmpty()) emit(LlmEvent.Reasoning(reasoning))
                                    // 联网搜索引用：官方流式只在最后一帧下发完整 annotations。
                                    // 兼容三种可能的位置：message.annotations（非流式形态）、
                                    // delta.annotations、以及 choice 级 annotations。
                                    parseCitations(message?.get("annotations"), citations)
                                    if (delta !== message) parseCitations(delta?.get("annotations"), citations)
                                    parseCitations(choice["annotations"], citations)
                                    val finish = choice.stringOrNull("finish_reason")
                                    if (!finish.isNullOrBlank()) chunkFinish = finish
                                }
                                if (chunkFinish != null) {
                                    finishReason = chunkFinish
                                    // 排空窗口：给"usage 在 finish 后的独立帧"留时间，最多 DRAIN_GRACE_MS。
                                    drainDeadlineNanos.set(System.nanoTime() + DRAIN_GRACE_MS * NANOS_PER_MILLI)
                                    finishSeen.set(true)
                                }
                            }
                        }
                    } catch (io: IOException) {
                        currentCoroutineContext().ensureActive()
                        // finish_reason 已经拿到：内容完整，断在排空阶段不算失败。
                        if (finishReason == null && timeoutKind.get() != StreamTimeout.DRAIN) {
                            failure = streamFailure(io, timeoutKind.get(), sawEvent)
                        }
                    }
                    break
                }
            } finally {
                callHandle?.dispose()
                activeCall.getAndSet(null)?.cancel()
                runCatching { response?.close() }
                watchdog.cancel()
            }

            terminal = when {
                // v0.5.0 识图：图片被服务商安全策略拒绝（content_filter / delta.refusal 且无正文）。
                // 归到 ApiError.Filtered，让发送链路换成「这张图服务商不收……」的人话。
                failure == null && !sawContent && (refusalSeen || finishReason == "content_filter") ->
                    LlmEvent.Failed(ApiError.Filtered(httpCode = 200))
                failure != null -> LlmEvent.Failed(failure!!)
                else -> LlmEvent.Finished(
                    usage = usage,
                    truncated = isTruncated(finishReason, sawDone),
                    citations = citations.values.toList(),
                    webSearchDropped = webSearchDropped,
                )
            }
        }

        terminal?.let { emit(it) }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------ 非流式

    /**
     * 非流式补全，用于记忆抽取等不需要逐字显示的场景。
     *
     * 失败抛 [ApiException]；429 / 5xx / IOException 按 退避重试 2 次
     * （2s，可注入）。返回 choices[0].message.content。
     */
    suspend fun complete(request: ChatRequest): String = withTransientRetry {
        executeCompletion(request.copy(stream = false), useJsonMode = request.jsonMode)
    }

    /**
     * JSON 模式补全（`response_format: {"type":"json_object"}`）。
     *
     * 服务端 400 表示不支持 response_format 时，自动去掉该字段重试一次
     * （的兼容处理）。
     */
    suspend fun completeJson(request: ChatRequest): String = withTransientRetry {
        try {
            executeCompletion(request.copy(stream = false, jsonMode = true), useJsonMode = true)
        } catch (e: ApiException) {
            if (e.error.httpCode == 400) {
                executeCompletion(request.copy(stream = false, jsonMode = false), useJsonMode = false)
            } else {
                throw e
            }
        }
    }

    private suspend fun executeCompletion(request: ChatRequest, useJsonMode: Boolean): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isEmpty()) throw ApiException(ApiError.NoKey())
            if (resolvedBaseUrl.isEmpty()) throw ApiException(ApiError.BadResponse(raw = "base url is blank"))

            var urlBase = resolvedBaseUrl
            var v1Tried = false
            while (true) {
                val httpRequest = try {
                    buildChatRequest(
                        request = request,
                        urlBase = urlBase,
                        includeStreamOptions = false,
                        useJsonMode = useJsonMode,
                    )
                } catch (e: IllegalArgumentException) {
                    throw ApiException(ApiError.BadResponse(raw = "invalid base url"))
                }
                val call = http.newCall(httpRequest)
                val resp = try {
                    call.execute()
                } catch (io: IOException) {
                    throw ApiException(ApiErrorMapper.fromThrowable(io))
                }
                val text = try {
                    resp.body?.string().orEmpty()
                } finally {
                    runCatching { resp.close() }
                }
                if (!resp.isSuccessful) {
                    val error = ApiErrorMapper.fromHttp(resp.code, text)
                    if (resp.code == 404 && !BaseUrl.isV1(urlBase) && !v1Tried) {
                        v1Tried = true
                        urlBase = BaseUrl.withV1(urlBase)
                        continue
                    }
                    throw ApiException(error)
                }
                rememberResolvedBase(urlBase)
                return@withContext parseCompletionContent(text, resp.code)
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        }

    private fun parseCompletionContent(text: String, httpCode: Int): String {
        val obj = JsonHttp.parseObject(text)
            ?: throw ApiException(ApiError.BadResponse(httpCode, errorSnippet(text)))
        if (obj["error"] != null) {
            throw ApiException(ApiError.BadResponse(httpCode, messageFromErrorBody(text)))
        }
        val choices = obj["choices"]?.asArrayOrNull()
            ?: throw ApiException(ApiError.BadResponse(httpCode, errorSnippet(text)))
        val first = choices.firstOrNull() as? JsonObject
            ?: throw ApiException(ApiError.BadResponse(httpCode, errorSnippet(text)))
        val message = first["message"] as? JsonObject
        val content = message?.stringOrNull("content")
        // 正文非空：正常返回（`finish_reason=content_filter` 但已有正文时与流式一致按正常收尾，
        // 见 `LlmFilteredRefusalTest` 的流式对应用例）。
        if (!content.isNullOrEmpty()) return content
        // v0.5.0 识图：服务商对图片的拒绝。上层按 Filtered 换人话。
        // v0.5.0 非流式也要认 `finish_reason=content_filter`
        // 且正文为空的形态（流式终态已映射，见 stream() 的 terminal 判定）。
        if (!message?.stringOrNull("refusal").isNullOrEmpty() ||
            first.stringOrNull("finish_reason") == "content_filter"
        ) {
            throw ApiException(ApiError.Filtered(httpCode, raw = null))
        }
        // 空字符串正文保持旧行为（原样返回，由调用方按"没内容"处理），不改语义。
        if (content != null) return content
        // 少数老服务商把文本放在顶层 text。
        return first.stringOrNull("text")
            ?: throw ApiException(ApiError.BadResponse(httpCode, errorSnippet(text)))
    }

    // ------------------------------------------------------------------ 内部

    /** 404 补 /v1 成功后：实例内记住，并写入进程级缓存（下次新建 client 直接用）。*/
    private fun rememberResolvedBase(working: String) {
        if (working == resolvedBaseUrl) return
        resolvedBaseUrl = working
        BaseUrl.remember(configuredBaseUrl, working)
    }

    private fun buildChatRequest(
        request: ChatRequest,
        urlBase: String,
        includeStreamOptions: Boolean,
        useJsonMode: Boolean,
    ): Request = JsonHttp.post(
        url = BaseUrl.endpoint(urlBase, "chat/completions"),
        apiKey = apiKey,
        body = buildChatPayload(request, useJsonMode, includeStreamOptions),
        accept = if (request.stream) "text/event-stream" else "application/json",
    )

    private fun firstByteTimeoutFor(messages: List<ChatMessage>): Long {
        val promptTokens = promptTokenEstimator(messages).coerceAtLeast(0).toLong()
        // 60s + prompt_tokens / 20000 秒把默认值定为 90s，取二者较大值。
        val scaled = firstByteScaleBaseMs + promptTokens / FIRST_BYTE_SCALE_TOKENS * 1_000L
        val maxMs = maxOf(firstByteTimeoutMs, firstByteTimeoutCapMs)
        return scaled.coerceIn(firstByteTimeoutMs, maxMs)
    }

    private fun timeoutError(timeout: StreamTimeout, sawEvent: Boolean): ApiError = when (timeout) {
        // 首字阶段超时：归到"没网/超时"，不用 StreamBroken（还没开始回答）。
        StreamTimeout.FIRST_BYTE -> ApiError.Network(raw = "first byte timeout")
        // 流中卡住：回答中断，保留已生成内容。
        StreamTimeout.HEARTBEAT -> ApiError.StreamBroken(raw = "stream heartbeat timeout")
        // 排空 usage 阶段的兜底打断不是错误。
        StreamTimeout.DRAIN -> if (sawEvent) ApiError.StreamBroken() else ApiError.Network()
    }

    private fun streamFailure(t: IOException, timeout: StreamTimeout?, sawEvent: Boolean): ApiError = when {
        timeout == StreamTimeout.FIRST_BYTE -> ApiError.Network(raw = "first byte timeout")
        timeout == StreamTimeout.HEARTBEAT -> ApiError.StreamBroken(raw = "stream heartbeat timeout")
        sawEvent -> ApiError.StreamBroken(raw = errorSnippet(t.message))
        else -> ApiErrorMapper.fromThrowable(t)
    }

    private fun parseUsage(element: JsonElement?): Usage? {
        val obj = element as? JsonObject ?: return null
        val prompt = obj.intOrNull("prompt_tokens") ?: 0
        val completion = obj.intOrNull("completion_tokens") ?: 0
        val hit = obj.intOrNull("prompt_cache_hit_tokens") ?: obj.intOrNull("cache_hit_tokens")
        val miss = obj.intOrNull("prompt_cache_miss_tokens") ?: obj.intOrNull("cache_miss_tokens")
        if (prompt == 0 && completion == 0 && hit == null && miss == null) return null
        return Usage(
            promptTokens = prompt,
            completionTokens = completion,
            cacheHitTokens = hit,
            cacheMissTokens = miss,
        )
    }

    /** usage 可能分帧出现：后到的非空字段覆盖，缺的字段不覆盖（「usage 只在最后一帧，累积时别覆盖」）。*/
    private fun mergeUsage(previous: Usage?, next: Usage?): Usage? {
        if (next == null) return previous
        if (previous == null) return next
        return Usage(
            promptTokens = next.promptTokens.takeIf { it > 0 } ?: previous.promptTokens,
            completionTokens = next.completionTokens.takeIf { it > 0 } ?: previous.completionTokens,
            cacheHitTokens = next.cacheHitTokens ?: previous.cacheHitTokens,
            cacheMissTokens = next.cacheMissTokens ?: previous.cacheMissTokens,
        )
    }

    /**
     * 解析联网搜索引用：`annotations[].url_citation`。
     *
     * 只取 `type == "url_citation"` 且 url 非空的项；按 url 去重并保持首次出现顺序。
     * 未知类型 / 缺字段静默跳过（服务端扩展字段只会增不会减，不能因为多一种就整轮失败）。
     */
    private fun parseCitations(element: JsonElement?, into: MutableMap<String, Citation>) {
        val array = element?.asArrayOrNull() ?: return
        array.forEach { item ->
            val obj = item.asObjectOrNull() ?: return@forEach
            if (obj.stringOrNull("type") != "url_citation") return@forEach
            val citation = obj["url_citation"]?.asObjectOrNull() ?: return@forEach
            val url = citation.stringOrNull("url")?.trim().orEmpty()
            if (url.isEmpty()) return@forEach
            val title = citation.stringOrNull("title")?.trim()?.takeIf { it.isNotEmpty() }
            into.putIfAbsent(url, Citation(url = url, title = title))
        }
    }

    private fun isTruncated(finishReason: String?, sawDone: Boolean): Boolean {
        // 文档口径：以 finish_reason 为准；两者都没有 → 截断。finish_reason=length 同样是"没说完"。
        if (finishReason == null && !sawDone) return true
        return finishReason == "length"
    }

    private suspend fun <T> withTransientRetry(block: suspend () -> T): T {
        var retries = 0
        while (true) {
            try {
                return block()
            } catch (e: ApiException) {
                if (!e.error.isTransient() || retries >= MAX_TRANSIENT_RETRIES) throw e
                retries++
                delay(retryDelayMs)
            }
        }
    }

    private fun ApiError.isTransient(): Boolean = when (kind) {
        ApiError.Kind.QUOTA -> httpCode == 429
        ApiError.Kind.SERVER, ApiError.Kind.NETWORK -> true
        else -> false
    }

    private enum class StreamTimeout { FIRST_BYTE, HEARTBEAT, DRAIN }

    companion object {
        /** 首字超时基准（90s 写 120s，取）。*/
        const val DEFAULT_FIRST_BYTE_TIMEOUT_MS: Long = 90_000L

        /** 流中心跳：收到第一个 event 之后才开始计。 */
        const val DEFAULT_HEARTBEAT_TIMEOUT_MS: Long = 45_000L

        /** 首字超时上限。 */
        const val DEFAULT_FIRST_BYTE_TIMEOUT_CAP_MS: Long = 180_000L

        /** 首字超时公式里的 60s 基值。 */
        const val DEFAULT_FIRST_BYTE_SCALE_BASE_MS: Long = 60_000L

        /** 429 / 5xx / IO 的退避间隔。 */
        const val DEFAULT_RETRY_DELAY_MS: Long = 2_000L

        /** 收到 finish_reason 后等独立 usage 帧的时间。 */
        const val DRAIN_GRACE_MS: Long = 2_000L

        private const val FIRST_BYTE_SCALE_TOKENS: Long = 20_000L
        private const val MAX_TRANSIENT_RETRIES = 2
        private const val MAX_PRE_STREAM_ATTEMPTS = 2
        private const val NANOS_PER_MILLI: Long = 1_000_000L

        /**
         * 粗略 prompt token 估算：中文约 1.5 字/token、英文约 4 字/token，取偏保守的 2 字/token。
         * 只用于放大首字超时，不参与计费（计费以 usage 为准，没有 usage 才本地估算，见）。
         *
         * v0.5.0 识图：图片按官方口径 **≤1024 token/张** 保守计入
         * （「缩放后每张最多 1024 token」）。这直接放大首字超时与总上下文估算，
         * 与 ⑤ 段文本预算无关（图片不占文本预算，见 `PromptAssembler.USER_MESSAGE_MAX_TOKENS`）。
         * 数值来源：`TokenEstimator.VISION_TOKENS_PER_IMAGE`（标"估算"）。
         */
        internal fun estimatePromptTokens(messages: List<ChatMessage>): Int =
            messages.sumOf { estimateMessageTokens(it) }

        /** 单条消息的 prompt token 估算（正文 + 结构开销 + 图片块）。 */
        internal fun estimateMessageTokens(message: ChatMessage): Int {
            val imageCount = message.parts.count { it is ContentPart.Image }
            return message.content.length / 2 + 1 +
                imageCount * cc.pulsez.app.mem.TokenEstimator.VISION_TOKENS_PER_IMAGE
        }
    }
}

/**
 * 这个 400 错误体是否像「带联网工具的请求不被接受」（降级的唯一触发条件）。
 *
 * 命中关键词（大小写不敏感）来自两处官方口径：
 * - 请求带 `tools` 时，历史轮必须回传 `reasoning_content`（缺了 400）与 `server_tool_calls`
 *   （缺了静默丢页面），见
 * - `deepseek_web_search` 是 DeepSeek 私有工具类型，第三方 OpenAI 兼容服务不认识。
 *
 * 只认「400 + 这组关键词」：其它 400（上下文超长、内容被拦、参数非法）**不降级**，
 * 否则会把真正的错误吞掉、多打一次请求（可能重复计费）。
 */
internal fun isWebSearchRejection(body: String?): Boolean {
    val text = body.orEmpty().lowercase()
    if (text.isEmpty()) return false
    return WEB_SEARCH_REJECTION_HINTS.any { text.contains(it) }
}

/** 见 [isWebSearchRejection]：官方字段名 + 工具类型名 + 通用 tools/tool_calls 提法。 */
private val WEB_SEARCH_REJECTION_HINTS = listOf(
    "reasoning_content",
    "reasoning content",
    "server_tool_calls",
    "tool_calls",
    "web_search",
    "tools",
)
