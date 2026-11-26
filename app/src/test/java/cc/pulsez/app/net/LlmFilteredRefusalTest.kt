package cc.pulsez.app.net

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DeepSeek terminal safety refusals follow the documented behavior: https://api-docs.deepseek.com/guides/safety/.
 *
 * 官方两种形态都要映射成 `ApiError.Filtered`，交给发送链路换成人话
 * 「这张图服务商不收。换个方式说说，或者换一张？」——**不冒称危机干预**。
 * 覆盖 DeepSeek 部分正文后的终态 `content_filter` / `delta.refusal`、非流式拒绝、
 * 非 DeepSeek 旧行为、断流/取消和既有 400 内容过滤映射。
 */
class LlmFilteredRefusalTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = HttpFactory.create()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `流式 delta refusal 且无正文 判为内容被拦`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"refusal":"I can't help with that image"}}]}"""),
                frame("""{"choices":[{"delta":{},"finish_reason":"content_filter"}]}"""),
                doneFrame(),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }

        val failed = events.last() as LlmEvent.Failed
        assertEquals(ApiError.Kind.FILTERED, failed.error.kind)
        assertTrue("不应把 refusal 当正文输出", events.none { it is LlmEvent.Delta })
    }

    @Test
    fun `DeepSeek content_filter after body reasoning citations and tool calls is a failure`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"这段前缀必须撤下。"}}]}"""),
                frame("""{"choices":[{"delta":{"reasoning_content":"这段思考也要撤下。"}}]}"""),
                frame(
                    """{"choices":[{"delta":{"server_tool_calls":[{"id":"srv_1","type":"deepseek_web_search"}],"annotations":[{"type":"url_citation","url_citation":{"url":"https://example.com","start_index":0,"end_index":2}}]},"finish_reason":"content_filter"}]}""",
                ),
                doneFrame(),
            ),
        )

        val events = runBlocking { deepSeekClient().stream(request()).toList() }

        assertTrue("底层流式事件已发出前缀，需由终态拒绝驱动 UI 撤下", events.any { it == LlmEvent.Delta("这段前缀必须撤下。") })
        assertTrue(events.any { it == LlmEvent.Reasoning("这段思考也要撤下。") })
        assertTrue("content_filter 必须胜过已收集的引用/工具字段", events.last() is LlmEvent.Failed)
        assertEquals(ApiError.Kind.FILTERED, (events.last() as LlmEvent.Failed).error.kind)
        assertTrue("拒绝轮不能作为 Finished 被持久化", events.none { it is LlmEvent.Finished })
    }

    @Test
    fun `DeepSeek terminal refusal delta after body is a failure even without content_filter reason`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"未审核的前缀"}}]}"""),
                frame("""{"choices":[{"delta":{"refusal":"refused"},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )

        val events = runBlocking { deepSeekClient().stream(request()).toList() }

        assertTrue(events.any { it == LlmEvent.Delta("未审核的前缀") })
        assertEquals(ApiError.Kind.FILTERED, (events.last() as LlmEvent.Failed).error.kind)
    }

    @Test
    fun `非 DeepSeek finish_reason content_filter 但有正文仍按旧行为正常收尾`() {
        server.enqueue(
            sseResponse(
                frame(
                    """{"choices":[{"delta":{"content":"这张图里是猫。"},"finish_reason":"content_filter"}]}""",
                ),
                doneFrame(),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }

        assertTrue(events.first() is LlmEvent.Delta)
        val finished = events.last() as LlmEvent.Finished
        assertEquals(false, finished.truncated)
    }

    @Test
    fun `non DeepSeek refusal after body keeps existing success behavior`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"兼容服务商旧回答"}}]}"""),
                frame("""{"choices":[{"delta":{"refusal":"ignored by legacy mapping"},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }

        assertTrue(events.last() is LlmEvent.Finished)
    }

    @Test
    fun `DeepSeek disconnect after partial body remains stream broken`() {
        val prefix = frame("""{"choices":[{"delta":{"content":"已流出的前缀"}}]}""")
        val second = frame("""{"choices":[{"delta":{"content":"${"x".repeat(512)}"}}]}""")
        server.enqueue(
            sseResponse(prefix, second)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val events = runBlocking { deepSeekClient().stream(request()).toList() }

        assertTrue(events.any { it == LlmEvent.Delta("已流出的前缀") })
        assertEquals(ApiError.Kind.STREAM_BROKEN, (events.last() as LlmEvent.Failed).error.kind)
    }

    @Test
    fun `cancelling DeepSeek stream after first delta emits no synthetic refusal`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"仍按取消语义保留的前缀"}}]}"""),
                frame("""{"choices":[{"delta":{},"finish_reason":"content_filter"}]}"""),
                doneFrame(),
            ),
        )

        val events = runBlocking { deepSeekClient().stream(request()).take(1).toList() }

        assertEquals(listOf(LlmEvent.Delta("仍按取消语义保留的前缀")), events)
    }

    @Test
    fun `400 错误体含 content filter 归到 Filtered`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"content filter triggered by image"}}"""),
        )

        val events = runBlocking { client().stream(request()).toList() }

        val failed = events.last() as LlmEvent.Failed
        assertEquals(ApiError.Kind.FILTERED, failed.error.kind)
    }

    @Test
    fun `非流式 finish_reason content_filter 且正文为空 判为内容被拦`() {
        // ：非流式此前只认 `message.refusal`，漏了
        // `finish_reason=content_filter` + 空正文；主链路 stream=true 影响小，但内部请求/
        // 未来复用要一致，补一例与流式对齐。。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"message":{"role":"assistant","content":null},"finish_reason":"content_filter"}]}""",
                ),
        )

        val error = runCatching {
            runBlocking { client().complete(request().copy(stream = false)) }
        }.exceptionOrNull()

        assertTrue("应抛 ApiException，实际=$error", error is ApiException)
        assertEquals(ApiError.Kind.FILTERED, (error as ApiException).error.kind)
    }

    @Test
    fun `非 DeepSeek 非流式有正文时 content_filter 不影响旧行为`() {
        // Existing non-DeepSeek behavior: a response body takes priority over content_filter.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"message":{"role":"assistant","content":"这张图里是猫。"},"finish_reason":"content_filter"}]}""",
                ),
        )

        val content = runBlocking { client().complete(request().copy(stream = false)) }

        assertEquals("这张图里是猫。", content)
    }

    @Test
    fun `DeepSeek non streaming refusal never returns a partial body`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"message":{"role":"assistant","content":"非流式拒绝前缀","refusal":"refused"},"finish_reason":"content_filter"}]}""",
                ),
        )

        val error = runCatching {
            runBlocking { deepSeekClient().complete(request().copy(stream = false)) }
        }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertEquals(ApiError.Kind.FILTERED, (error as ApiException).error.kind)
    }

    private fun request() = ChatRequest(
        model = "deepseek-flash",
        messages = listOf(
            ChatMessage(
                role = "user",
                content = "看看",
                parts = listOf(ContentPart.Text("看看"), ContentPart.Image("X")),
            ),
        ),
    )

    private fun client() = LlmClient(
        http = http,
        baseUrl = server.url("/v1").toString(),
        apiKey = "sk-test",
        firstByteTimeoutMs = 3_000,
        heartbeatTimeoutMs = 3_000,
        firstByteTimeoutCapMs = 10_000,
        firstByteScaleBaseMs = 3_000,
        retryDelayMs = 10,
    )

    /** 将官方 DeepSeek 主机名通过本地 DNS 缝指向 MockWebServer，不发真实请求。 */
    private fun deepSeekClient() = LlmClient(
        http = http.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname != "api.deepseek.com") throw UnknownHostException(hostname)
                    return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                }
            })
            .build(),
        baseUrl = "http://api.deepseek.com:${server.port}/v1",
        apiKey = "sk-test",
        firstByteTimeoutMs = 3_000,
        heartbeatTimeoutMs = 3_000,
        firstByteTimeoutCapMs = 10_000,
        firstByteScaleBaseMs = 3_000,
        retryDelayMs = 10,
    )

    private fun frame(json: String) = "data: $json\n\n"

    private fun doneFrame() = "data: [DONE]\n\n"

    private fun sseResponse(vararg frames: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(frames.joinToString(""))
}
