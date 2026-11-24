package cc.pulsez.app.real

import cc.pulsez.app.net.ApiError
import cc.pulsez.app.net.ChatMessage
import cc.pulsez.app.net.ChatRequest
import cc.pulsez.app.net.HttpFactory
import cc.pulsez.app.net.LlmClient
import cc.pulsez.app.net.LlmEvent
import cc.pulsez.app.net.ModelProbe
import cc.pulsez.app.net.Usage
import cc.pulsez.app.net.asInternalRequest
import cc.pulsez.app.wire.Pricing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真实 DeepSeek API 冒烟测试（ · // 的端到端验证）。
 *
 * ## 密钥安全（ 款 / ）
 * - Key **只**从环境变量 `PULSEZ_REAL_KEY` 读取，代码、日志、断言消息里都不出现；
 * - 未设置该变量时（CI / 离线开发机）每个用例被 [Assume] 跳过，**不会失败**；
 * - 本测试不打印请求头、不打印完整请求体、不打印 Key。
 *
 * ## 费用控制
 * - 上限 15 次真实 HTTP 请求，按次计。计数用注入的 OkHttp 拦截器（只 `incrementAndGet`，
 * 不读、不打印任何请求内容），全部用例跑完后由 [z_request_budget] 断言不超限。
 * - 每条 prompt 都尽量短；`max_tokens` 只给到够用。
 *
 * ## 用法
 * ```
 * $env:PULSEZ_REAL_KEY=(Get-Content '<keyfile>' -Raw).Trim
 * gradle --no-daemon testDebugUnitTest --tests 'cc.pulsez.app.real.*' --rerun-tasks
 * Remove-Item Env:PULSEZ_REAL_KEY
 * ```
 *
 * 用例按名字顺序执行（[FixMethodOrder]），单条失败不影响后续用例继续跑。
 * "服务端行为可能变化"的项（引用格式、前缀缓存命中）只记录、不判失败，实际值打到 stdout。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeepSeekRealApiTest {

    /** 一次流式请求的实测记录。 */
    private data class Run(
        val events: List<LlmEvent>,
        /** 首帧时间（思考或正文，谁先算谁）。 */
        val firstAnyMs: Long?,
        val firstReasoningMs: Long?,
        val firstContentMs: Long?,
        val totalMs: Long,
        /** 本次 collect 实际发出的 HTTP 请求数（含 400 降级重试）。 */
        val requests: Int,
    ) {
        val content: String get() = events.filterIsInstance<LlmEvent.Delta>().joinToString("") { it.text }
        val reasoning: String get() = events.filterIsInstance<LlmEvent.Reasoning>().joinToString("") { it.text }
        val finished: LlmEvent.Finished? get() = events.lastOrNull() as? LlmEvent.Finished
        val failure: ApiError? get() = (events.lastOrNull() as? LlmEvent.Failed)?.error
    }

    // ------------------------------------------------------------------ 用例

    @Test
    fun a_probe() {
        val key = keyOrSkip()
        val before = requests()
        val probe = ModelProbe(http)
        val result = runBlocking { probe.probe(baseUrl = BASE_URL, apiKey = key, model = MODEL) }
        report(
            "a_probe",
            "chatOk" to result.chatOk,
            "embeddingsOk" to result.embeddingsOk,
            "models" to result.models.size,
            "errorKind" to result.error?.kind,
            "http" to result.error?.httpCode,
            "requests" to (requests() - before),
        )
        assertTrue("聊天能力探测必须通过；实际 error=${result.error}", result.chatOk)
        // embeddingsOk 只记录：DeepSeek 官方当前没有 /embeddings，false 属预期，不判失败。
    }

    @Test
    fun b_non_thinking_stream() {
        val key = keyOrSkip()
        val run = collect(
            key,
            ChatRequest(
                model = MODEL,
                messages = listOf(ChatMessage("user", "只回答两个字：收到")),
                maxTokens = 32,
                thinking = false,
            ),
        )
        val usage = run.finished?.usage
        report(
            "b_non_thinking",
            "terminal" to terminalName(run),
            "ttftMs" to run.firstAnyMs,
            "totalMs" to run.totalMs,
            "contentLen" to run.content.length,
            "reasoningLen" to run.reasoning.length,
            "promptTokens" to usage?.promptTokens,
            "completionTokens" to usage?.completionTokens,
            "costYuan" to cost(usage),
            "requests" to run.requests,
        )
        requireNotNull(run.finished) { "非思考流没有正常结束；failure=${run.failure}" }
        assertNotNull("非思考流必须有 usage", usage)
        assertTrue("非思考流正文不能为空", run.content.isNotBlank())
        assertTrue(
            "显式 thinking=disabled 时不应有思考内容，实际 ${run.reasoning.length} 字",
            run.reasoning.isBlank(),
        )
    }

    @Test
    fun c_thinking_low() {
        val key = keyOrSkip()
        val run = collect(
            key,
            ChatRequest(
                model = MODEL,
                messages = listOf(ChatMessage("user", "9.11 和 9.8 哪个大？只回答结论。")),
                maxTokens = 4000,
                thinking = true,
                reasoningEffort = "low",
            ),
        )
        val usage = run.finished?.usage
        thinkingLowTotalMs = run.totalMs
        thinkingLowFirstContentMs = run.firstContentMs
        report(
            "c_thinking_low",
            "terminal" to terminalName(run),
            "firstFrameMs" to run.firstAnyMs,
            "firstReasoningMs" to run.firstReasoningMs,
            "firstContentMs" to run.firstContentMs,
            "totalMs" to run.totalMs,
            "contentLen" to run.content.length,
            "reasoningLen" to run.reasoning.length,
            "promptTokens" to usage?.promptTokens,
            "completionTokens" to usage?.completionTokens,
            "costYuan" to cost(usage),
            "requests" to run.requests,
        )
        requireNotNull(run.finished) { "思考 low 流没有正常结束；failure=${run.failure}" }
        assertTrue("思考 low 必须收到 reasoning_content，实际 0 字", run.reasoning.isNotBlank())
        assertTrue("思考时正文不能为空（max_tokens=4000），实际 0 字", run.content.isNotBlank())
    }

    @Test
    fun d_web_search_single_turn() {
        val key = keyOrSkip()
        val run = collect(
            key,
            ChatRequest(
                model = MODEL,
                messages = listOf(
                    ChatMessage("user", "今天是 2026 年 11 月 20 日。上海今天天气怎么样？用一句话回答。"),
                ),
                maxTokens = 512,
                thinking = false,
                webSearch = true,
            ),
            timeoutMs = 240_000,
        )
        val finished = run.finished
        report(
            "d_web_search_single",
            "terminal" to terminalName(run),
            "webSearchDropped" to finished?.webSearchDropped,
            "citations" to finished?.citations?.size,
            "citation0" to finished?.citations?.firstOrNull()?.url,
            "ttftMs" to run.firstAnyMs,
            "totalMs" to run.totalMs,
            "contentLen" to run.content.length,
            "promptTokens" to finished?.usage?.promptTokens,
            "completionTokens" to finished?.usage?.completionTokens,
            "costYuan" to cost(finished?.usage),
            "requests" to run.requests,
        )
        requireNotNull(finished) { "单轮联网搜索应正常结束；failure=${run.failure}" }
        assertTrue("联网搜索正文不能为空", run.content.isNotBlank())
        if (finished.citations.isEmpty()) {
            // 引用是服务端扩展字段，格式/是否下发可能变化 → 只记录，不判失败（口径）。
            println("[REALAPI] d_web_search_single WARN 引用为空（只记录，不判失败）")
        }
    }

    @Test
    fun e_web_search_multi_turn() {
        val key = keyOrSkip()
        val q1 = "用一句话说明 DeepSeek 现在提供哪些联网能力。"
        val turn1 = collect(
            key,
            ChatRequest(
                model = MODEL,
                messages = listOf(ChatMessage("user", q1)),
                maxTokens = 4000,
                thinking = true,
                reasoningEffort = "low",
                webSearch = true,
            ),
            timeoutMs = 240_000,
        )
        report(
            "e_web_search_multi_turn/turn1",
            "terminal" to terminalName(turn1),
            "webSearchDropped" to turn1.finished?.webSearchDropped,
            "citations" to turn1.finished?.citations?.size,
            "reasoningLen" to turn1.reasoning.length,
            "contentLen" to turn1.content.length,
            "requests" to turn1.requests,
        )
        val first = requireNotNull(turn1.finished) { "搜索多轮的第 1 轮必须成功；failure=${turn1.failure}" }

        // pulsez 现状：历史只回传正文，**不回传 reasoning_content / server_tool_calls**。
        // 按官方文档，带 tools 时缺 reasoning_content 应 400；
        // LlmClient 的 降级应当自动去掉 tools 重试一次并标记 webSearchDropped。
        val history = listOf(
            ChatMessage("user", q1),
            ChatMessage("assistant", turn1.content),
            ChatMessage("user", "那再补一句：它和别家比呢？一句话。"),
        )
        val turn2 = collect(
            key,
            ChatRequest(
                model = MODEL,
                messages = history,
                maxTokens = 4000,
                thinking = true,
                reasoningEffort = "low",
                webSearch = true,
            ),
            timeoutMs = 240_000,
        )
        val second = turn2.finished
        report(
            "e_web_search_multi_turn/turn2",
            "terminal" to terminalName(turn2),
            "webSearchDropped" to second?.webSearchDropped,
            "citations" to second?.citations?.size,
            "ttftMs" to turn2.firstAnyMs,
            "totalMs" to turn2.totalMs,
            "contentLen" to turn2.content.length,
            "promptTokens" to second?.usage?.promptTokens,
            "completionTokens" to second?.usage?.completionTokens,
            "costYuan" to cost(second?.usage),
            "requests" to turn2.requests,
        )
        requireNotNull(second) {
            "第 2 轮应成功（降级后成功或服务端直接接受）；failure=${turn2.failure}, 第 1 轮 dropped=${first.webSearchDropped}"
        }
        assertTrue("第 2 轮正文不能为空", turn2.content.isNotBlank())
        if (second.webSearchDropped) {
            println(
                "[REALAPI] e_web_search_multi_turn 降级生效：服务端 400 后自动去掉 tools 重试成功，" +
                    "第 2 轮共 ${turn2.requests} 个请求",
            )
        } else {
            println(
                "[REALAPI] e_web_search_multi_turn 未触发降级：服务端接受了缺少 reasoning_content 的历史" +
                    "（第 2 轮只有 ${turn2.requests} 个请求），说明当前 400 风险未复现",
            )
        }
    }

    @Test
    fun f_internal_group_request() {
        val key = keyOrSkip()
        // 与生产群聊 reply 完全一致的形状：stream + maxTokens=256 + asInternalRequest（显式关思考）。
        val request = ChatRequest(
            model = MODEL,
            messages = listOf(
                ChatMessage("system", "你是群聊里的一个角色「阿澈」，说话简短。"),
                ChatMessage("user", "用一句话说：今晚吃什么好？"),
            ),
            maxTokens = 256,
            includeUsage = true,
        ).asInternalRequest(BASE_URL)
        val run = collect(key, request)
        val usage = run.finished?.usage
        report(
            "f_internal_group",
            "terminal" to terminalName(run),
            "thinking" to request.thinking,
            "ttftMs" to run.firstAnyMs,
            "totalMs" to run.totalMs,
            "contentLen" to run.content.length,
            "reasoningLen" to run.reasoning.length,
            "promptTokens" to usage?.promptTokens,
            "completionTokens" to usage?.completionTokens,
            "costYuan" to cost(usage),
            "requests" to run.requests,
        )
        requireNotNull(run.finished) { "内部群聊请求没有正常结束；failure=${run.failure}" }
        assertTrue("内部请求正文不能为空", run.content.isNotBlank())
        assertTrue("asInternalRequest 在 DeepSeek 端点必须显式关思考，实际 reasoning=${run.reasoning.length} 字", run.reasoning.isBlank())
        val slower = thinkingLowTotalMs
        if (slower > 0) {
            val faster = run.totalMs < slower
            report(
                "f_internal_group_vs_c",
                "internalTotalMs" to run.totalMs,
                "thinkingLowTotalMs" to slower,
                "internalFirstContentMs" to run.firstContentMs,
                "thinkingLowFirstContentMs" to thinkingLowFirstContentMs,
                "internalFaster" to faster,
            )
            if (!faster) {
                println("[REALAPI] f_internal_group WARN 内部请求不比思考 low 快（服务端排队/网络波动，只记录不判失败）")
            }
        }
    }

    @Test
    fun g_prefix_cache() {
        val key = keyOrSkip()
        // 同一 system 前缀连发两次：第二次 usage 的 cacheHitTokens 应 > 0（自动前缀缓存）。
        // 前缀约 360 汉字 ≈ 240 token，超过 DeepSeek 缓存最小单位（64 token）。
        val prefix = buildString {
            repeat(6) {
                append("你是「阿澈」，一个说话简短、有点冷幽默的朋友。你记得对方说过的话，回答时不用敬语，也不用表情符号。")
            }
        }
        val messages = listOf(ChatMessage("system", prefix), ChatMessage("user", "只回复两个字：好的"))
        val first = collect(key, ChatRequest(model = MODEL, messages = messages, maxTokens = 16, thinking = false))
        // 服务端前缀缓存是**后台异步构建**的：背靠背连发两次通常还没建好（实测第一次即 0 命中）。
        // 给几秒构建时间再发第二次，才测得到"同一前缀第二次是否命中"。
        Thread.sleep(5_000)
        val second = collect(key, ChatRequest(model = MODEL, messages = messages, maxTokens = 16, thinking = false))
        val u1 = first.finished?.usage
        val u2 = second.finished?.usage
        report(
            "g_prefix_cache/first",
            "terminal" to terminalName(first),
            "promptTokens" to u1?.promptTokens,
            "completionTokens" to u1?.completionTokens,
            "cacheHitTokens" to u1?.cacheHitTokens,
            "cacheMissTokens" to u1?.cacheMissTokens,
            "costYuan" to cost(u1),
            "requests" to first.requests,
        )
        report(
            "g_prefix_cache/second",
            "terminal" to terminalName(second),
            "promptTokens" to u2?.promptTokens,
            "completionTokens" to u2?.completionTokens,
            "cacheHitTokens" to u2?.cacheHitTokens,
            "cacheMissTokens" to u2?.cacheMissTokens,
            "costYuan" to cost(u2),
            "requests" to second.requests,
        )
        requireNotNull(first.finished) { "前缀缓存第 1 次请求没有正常结束；failure=${first.failure}" }
        requireNotNull(second.finished) { "前缀缓存第 2 次请求没有正常结束；failure=${second.failure}" }
        val hit = u2?.cacheHitTokens
        val promptTokens2 = u2?.promptTokens ?: 0
        if (hit != null && hit > 0) {
            val ratio = if (promptTokens2 > 0) hit * 100.0 / promptTokens2 else 0.0
            println(
                "[REALAPI] g_prefix_cache 命中：第 2 次 cacheHitTokens=$hit / promptTokens=$promptTokens2 " +
                    "命中率=${String.format(Locale.ROOT, "%.1f", ratio)}%",
            )
        } else {
            // 缓存命中率受服务端状态影响（冷启动、节点、策略）→ 只记录，不判失败。
            println(
                "[REALAPI] g_prefix_cache 未观察到缓存命中：第 2 次 cacheHitTokens=$hit, " +
                    "promptTokens=$promptTokens2（只记录，不判失败）",
            )
        }
    }

    @Test
    fun z_request_budget() {
        keyOrSkip()
        val total = requests()
        report("z_request_budget", "totalHttpRequests" to total, "limit" to MAX_REQUESTS)
        assertTrue(
            "真实请求数超过上限 $MAX_REQUESTS 次：实际 $total 次",
            total <= MAX_REQUESTS,
        )
    }

    // ------------------------------------------------------------------ 基础设施

    private fun keyOrSkip(): String {
        val key = System.getenv(ENV_KEY)?.trim().orEmpty()
        Assume.assumeTrue(
            "未设置 $ENV_KEY：跳过真实 API 冒烟（离线 / CI 自动跳过，不判失败）",
            key.isNotEmpty(),
        )
        return key
    }

    /** 共享一个 LlmClient：`supportsStreamOptions` / base 解析结果在用例间复用，避免重复探测请求。 */
    private fun client(key: String): LlmClient {
        sharedClient?.let { return it }
        val created = LlmClient(
            http = http,
            baseUrl = BASE_URL,
            apiKey = key,
            // 与生产默认值一致（net/LlmClient.kt companion）。
            firstByteTimeoutMs = 90_000,
            heartbeatTimeoutMs = 45_000,
            firstByteTimeoutCapMs = 180_000,
            firstByteScaleBaseMs = 60_000,
            retryDelayMs = 2_000,
        )
        sharedClient = created
        return created
    }

    private fun collect(key: String, request: ChatRequest, timeoutMs: Long = 180_000): Run {
        val before = requests()
        val start = System.nanoTime()
        val events = mutableListOf<LlmEvent>()
        var firstAny: Long? = null
        var firstReasoning: Long? = null
        var firstContent: Long? = null
        runBlocking {
            withTimeout(timeoutMs) {
                client(key).stream(request).collect { event ->
                    val at = elapsedMs(start)
                    events += event
                    when (event) {
                        is LlmEvent.Delta -> {
                            if (firstAny == null) firstAny = at
                            if (firstContent == null) firstContent = at
                        }
                        is LlmEvent.Reasoning -> {
                            if (firstAny == null) firstAny = at
                            if (firstReasoning == null) firstReasoning = at
                        }
                        else -> Unit
                    }
                }
            }
        }
        return Run(
            events = events,
            firstAnyMs = firstAny,
            firstReasoningMs = firstReasoning,
            firstContentMs = firstContent,
            totalMs = elapsedMs(start),
            requests = requests() - before,
        )
    }

    private fun terminalName(run: Run): String =
        run.finished?.let { "Finished" }
            ?: run.failure?.let { "Failed(${it.kind}/http=${it.httpCode})" }
            ?: "无终止事件"

    private fun cost(usage: Usage?): String =
        String.format(Locale.ROOT, "%.6f", Pricing.costOf(MODEL, usage))

    private fun requests(): Int = requestCounter.get()

    private fun elapsedMs(sinceNanos: Long): Long = (System.nanoTime() - sinceNanos) / 1_000_000L

    /**
 * 打印一行结构化实测值，并落到 `build/realapi/report.txt`（Gradle 的 XML 也会抓 stdout，
 * 双份便于事后核对）。不含 Key、不含请求头。
 */
    private fun report(case: String, vararg fields: Pair<String, Any?>) {
        val line = "[REALAPI] $case " + fields.joinToString(" ") { (k, v) -> "$k=$v" }
        println(line)
        runCatching {
            val file = File("build/realapi/report.txt")
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
        }
    }

    companion object {
        /** DeepSeek 官方地址。 */
        private const val BASE_URL = "https://api.deepseek.com"

        /** 指定模型。 */
        private const val MODEL = "deepseek-flash"

        /** Key 只经环境变量进入进程，绝不写进代码 / 命令行参数 / 日志。 */
        private const val ENV_KEY = "PULSEZ_REAL_KEY"

        /** 硬上限：真实 HTTP 请求 ≤ 15 次（含探测与降级重试）。 */
        private const val MAX_REQUESTS = 15

        private val requestCounter = AtomicInteger(0)

        @Volatile
        private var sharedClient: LlmClient? = null

        /** c 用例的耗时，供 f 用例做"内部请求明显更快"的对比（服务端波动 → 只记录不判失败）。 */
        @Volatile
        private var thinkingLowTotalMs: Long = -1L

        @Volatile
        private var thinkingLowFirstContentMs: Long? = null

        /**
 * 复用 `HttpFactory.create` 的 OkHttpClient（不另写 HTTP），只加一个**纯计数**拦截器：
 * 统计真实请求数以守住 15 次预算；不读取、不打印请求内容与请求头。
 */
        private val http: OkHttpClient by lazy {
            HttpFactory.create().newBuilder()
                .addInterceptor { chain ->
                    requestCounter.incrementAndGet()
                    chain.proceed(chain.request())
                }
                .build()
        }
    }
}
