package cc.pulsez.app.net

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ：**搜索多轮 400 降级**。
 *
 * 背景：请求带 `tools` 时，历史轮必须回传 `reasoning_content`
 * （缺了 400）与 `server_tool_calls`（缺了静默丢页面）。pulsez 目前不存/不回传思考，
 * 所以开「智能搜索」后多轮必 400。这里加**一次**降级重试：去掉 tools 再发一遍，
 * 保留思考设置；成功后 UI 给轻提示「这次没能联网，先按普通回答」。
 *
 * 断言原则：
 * - 只对「400 + 错误体像联网工具问题」降级，其它 400 原样失败（不吞错、不多打请求）；
 * - **只降级一次**（第二次还失败就走正常错误分类）；
 * - 降级请求保留 `thinking` / `reasoning_effort`。
 */
class WebSearchFallbackTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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

    // ------------------------------------------------------------------ 降级成功

    @Test
    fun `400 about missing reasoning content drops tools and retries once`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Missing Reasoning_Content for the previous assistant message"}}""",
            ),
        )
        server.enqueue(sseResponse())

        val events = runBlocking { client().stream(request()).toList() }
        val finished = events.last() as LlmEvent.Finished

        assertTrue("降级重试成功后必须告诉 UI 这轮没联网", finished.webSearchDropped)
        assertEquals("只重试一次：共两个请求", 2, server.requestCount)

        val first = bodyOf(server.takeRequest())
        val second = bodyOf(server.takeRequest())
        assertTrue("第一次请求必须带 tools，实际=$first", "tools" in first)
        assertFalse("降级重试必须去掉 tools，实际=$second", "tools" in second)
        // 保留思考设置（「保留思考设置」）。
        assertEquals("enabled", thinkingType(first))
        assertEquals("enabled", thinkingType(second))
        assertEquals("low", second["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `normal web search response is not downgraded`() {
        server.enqueue(sseResponse())

        val events = runBlocking { client().stream(request()).toList() }
        val finished = events.last() as LlmEvent.Finished

        assertFalse("没遇到 400 不应标记降级", finished.webSearchDropped)
        assertEquals(1, server.requestCount)
        assertTrue("正常请求仍带 tools", "tools" in bodyOf(server.takeRequest()))
    }

    @Test
    fun `400 when readUrl enabled drops both webSearch and readUrl and retries once`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"unknown tool deepseek_read_url"}}""",
            ),
        )
        server.enqueue(sseResponse())

        val events = runBlocking {
            client().stream(
                request(webSearch = false).copy(readUrl = true),
            ).toList()
        }
        val finished = events.last() as LlmEvent.Finished

        assertTrue(finished.webSearchDropped)
        assertEquals(2, server.requestCount)
        val first = bodyOf(server.takeRequest())
        val second = bodyOf(server.takeRequest())
        assertTrue("第一次请求应带 tools", "tools" in first)
        assertFalse("降级后不得再带 tools", "tools" in second)
    }

    // ------------------------------------------------------------------ 不降级的情况

    @Test
    fun `unrelated 400 is not retried`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"This model's maximum context length is 128000 tokens"}}""",
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }
        val failed = events.single() as LlmEvent.Failed

        assertEquals(ApiError.Kind.CONTEXT_OVERFLOW, failed.error.kind)
        assertEquals("与联网无关的 400 不能重试（会重复计费）", 1, server.requestCount)
    }

    @Test
    fun `400 mentioning tools without web search is not retried`() {
        // 请求本来就没带 tools：不存在"去掉 tools"这个降级动作，不能加重试。
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"message":"unknown field tools"}}"""),
        )

        val events = runBlocking {
            client().stream(request(webSearch = false, includeUsage = false)).toList()
        }
        val failed = events.single() as LlmEvent.Failed

        assertEquals(ApiError.Kind.BAD_RESPONSE, failed.error.kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `only one downgrade is attempted even if retry is rejected again`() {
        val rejection = MockResponse().setResponseCode(400).setBody(
            """{"error":{"message":"missing reasoning_content"}}""",
        )
        server.enqueue(rejection)
        server.enqueue(rejection)
        server.enqueue(sseResponse())

        // includeUsage=false：避免 stream_options 的既有 400 回退混进来，专测 只降级一次。
        val events = runBlocking {
            client().stream(request(includeUsage = false)).toList()
        }
        val failed = events.single() as LlmEvent.Failed

        assertEquals(ApiError.Kind.BAD_RESPONSE, failed.error.kind)
        assertEquals("只降级一次：两个请求后直接按错误分类失败", 2, server.requestCount)
    }

    @Test
    fun `failed downgrade falls back to normal error classification`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"message":"unknown tool: web_search"}}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""),
        )

        val events = runBlocking { client().stream(request()).toList() }
        val failed = events.single() as LlmEvent.Failed

        assertEquals("降级后仍失败 → 走现有错误分类", ApiError.Kind.AUTH, failed.error.kind)
        assertEquals(2, server.requestCount)
    }

    // ------------------------------------------------------------------ helpers

    private fun request(
        webSearch: Boolean = true,
        includeUsage: Boolean = true,
    ) = ChatRequest(
        model = "deepseek-flash",
        messages = listOf(ChatMessage(role = "user", content = "今天上海天气怎么样")),
        thinking = true,
        reasoningEffort = "low",
        webSearch = webSearch,
        includeUsage = includeUsage,
    )

    private fun client(): LlmClient = LlmClient(
        http = http,
        baseUrl = server.url("/v1").toString(),
        apiKey = "sk-test",
        firstByteTimeoutMs = 3_000,
        heartbeatTimeoutMs = 3_000,
        firstByteTimeoutCapMs = 10_000,
        firstByteScaleBaseMs = 3_000,
        retryDelayMs = 10,
    )

    private fun bodyOf(recorded: okhttp3.mockwebserver.RecordedRequest): JsonObject =
        json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

    private fun thinkingType(body: JsonObject): String? =
        body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content

    private fun sseResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        )
}
