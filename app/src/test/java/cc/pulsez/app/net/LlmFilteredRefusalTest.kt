package cc.pulsez.app.net

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 图片内容被服务商安全策略拦下（ 识图）。
 *
 * 官方两种形态都要映射成 `ApiError.Filtered`，交给发送链路换成人话
 * 「这张图服务商不收。换个方式说说，或者换一张？」——**不冒称危机干预**。
 * 1. 流式 `delta.refusal`；
 * 2. `finish_reason = "content_filter"` 且没有正文；
 * 3. 400 错误体里带 content filter 关键词（既有 `ApiErrorMapper` 逻辑，回归护栏）。
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
    fun `finish_reason content_filter 但有正文 仍按正常回答收尾`() {
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
    fun `非流式有正文时 content_filter 不影响正常返回`() {
        // 的护栏：正常正文优先，不把带正文的响应误判成 Filtered。
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

    private fun frame(json: String) = "data: $json\n\n"

    private fun doneFrame() = "data: [DONE]\n\n"

    private fun sseResponse(vararg frames: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(frames.joinToString(""))
}
