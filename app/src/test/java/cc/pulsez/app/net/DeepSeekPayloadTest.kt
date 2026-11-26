package cc.pulsez.app.net

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
 * 思考 / 联网搜索的请求体。
 *
 * 用 MockWebServer 抓真实发出的请求体，而不是直接测内部 builder：
 * 这样同时验证 LlmClient 到 Payloads 的整条链路（含 stream / stream_options 不变）。
 */
class DeepSeekPayloadTest {

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

    @Test
    fun `thinking off is sent explicitly as disabled and without reasoning_effort`() {
        val body = sendAndCapture(request(thinking = false))

        // 不传 ≠ 关：服务端默认思考开，所以关必须显式 disabled。
        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertTrue(
            "关思考时不应同时发 reasoning_effort，实际=$body",
            "reasoning_effort" !in body,
        )
    }

    @Test
    fun `thinking on is enabled with low reasoning effort by default`() {
        val body = sendAndCapture(request(thinking = true))

        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        // 默认低成本 low（产品补充 ：输入框只布尔，强度在设置页，默认低）。
        assertEquals("low", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `custom reasoning effort is passed through when thinking on`() {
        // 设置页三档 low / high / max：选中值必须原样进请求体。
        val high = sendAndCapture(request(thinking = true, reasoningEffort = "high"))
        assertEquals("high", high["reasoning_effort"]?.jsonPrimitive?.content)

        val max = sendAndCapture(request(thinking = true, reasoningEffort = "max"))
        assertEquals("max", max["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `custom reasoning effort is dropped when thinking off`() {
        // 关思考时不同时发 reasoning_effort，避免和 thinking.type 语义打架。
        val body = sendAndCapture(request(thinking = false, reasoningEffort = "max"))

        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertTrue("关思考时不应发 reasoning_effort，实际=$body", "reasoning_effort" !in body)
    }

    @Test
    fun `omitted thinking sends neither field`() {
        // 非 DeepSeek 服务商 / 群聊保持"不传"的原行为，一个字段都不多发。
        val body = sendAndCapture(request(thinking = null))

        assertTrue("thinking=null 时不应发 thinking，实际=$body", "thinking" !in body)
        assertTrue("thinking=null 时不应发 reasoning_effort，实际=$body", "reasoning_effort" !in body)
    }

    @Test
    fun `web search on declares the deepseek server tool`() {
        val body = sendAndCapture(request(webSearch = true))

        val tools = body["tools"]?.jsonArray
        assertTrue("webSearch=true 时应有 tools，实际=$body", tools != null)
        assertEquals(1, tools!!.size)
        assertEquals("deepseek_web_search", tools[0].jsonObject["type"]?.jsonPrimitive?.content)
        // Chat Completions 官方示例只写 type（第 6 条）。
        assertEquals(1, tools[0].jsonObject.size)
    }

    @Test
    fun `web search off sends no tools`() {
        val body = sendAndCapture(request(webSearch = false))

        assertTrue("webSearch=false 时不应发 tools，实际=$body", "tools" !in body)
    }

    @Test
    fun `thinking and web search can be enabled together`() {
        // 官方语义：思考开 + 搜索开 = 深度思考搜索，不需要额外参数。
        val body = sendAndCapture(request(thinking = true, webSearch = true))

        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("low", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("deepseek_web_search", body["tools"]?.jsonArray?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse("不得发送 tool_choice", "tool_choice" in body)
    }

    @Test
    fun `readUrl and webSearch serialize both server tools without tool_choice`() {
        val body = sendAndCapture(
            ChatRequest(
                model = "deepseek-flash",
                messages = listOf(ChatMessage(role = "user", content = "帮我看看 https://example.com")),
                webSearch = true,
                readUrl = true,
            ),
        )

        val tools = body["tools"]?.jsonArray
        assertTrue(tools != null && tools.size == 2)
        assertEquals("deepseek_web_search", tools!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("deepseek_read_url", tools[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertFalse("不得发送 tool_choice", "tool_choice" in body)
    }

    @Test
    fun `readUrl alone declares deepseek_read_url`() {
        val body = sendAndCapture(
            ChatRequest(
                model = "deepseek-flash",
                messages = listOf(ChatMessage(role = "user", content = "读一下 https://example.com")),
                webSearch = false,
                readUrl = true,
            ),
        )

        val tools = body["tools"]?.jsonArray
        assertEquals(1, tools?.size)
        assertEquals("deepseek_read_url", tools!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertFalse("不得发送 tool_choice", "tool_choice" in body)
    }

    @Test
    fun `latest_reminder message serializes cleanly with role and content only`() {
        val body = sendAndCapture(
            ChatRequest(
                model = "deepseek-flash",
                messages = listOf(
                    ChatMessage(role = "system", content = "人设"),
                    ChatMessage(role = "latest_reminder", content = "2026-11-25,星期三,,App,中文"),
                    ChatMessage(role = "user", content = "今天周几"),
                ),
            ),
        )

        val messages = body["messages"]!!.jsonArray
        val reminder = messages[1].jsonObject
        assertEquals(2, reminder.size)
        assertEquals("latest_reminder", reminder["role"]?.jsonPrimitive?.content)
        assertEquals("2026-11-25,星期三,,App,中文", reminder["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `content part file serializes to file type and file_id`() {
        val body = sendAndCapture(
            ChatRequest(
                model = "deepseek-flash",
                messages = listOf(
                    ChatMessage(
                        role = "user",
                        content = "总结这份文档",
                        parts = listOf(
                            ContentPart.File(fileId = "file-api-123", fileName = "report.pdf"),
                            ContentPart.Text("总结这份文档"),
                        ),
                    ),
                ),
            ),
        )

        val content = body["messages"]!!.jsonArray[0].jsonObject["content"] as JsonArray
        assertEquals(2, content.size)
        val filePart = content[0].jsonObject
        assertEquals(2, filePart.size)
        assertEquals("file", filePart["type"]?.jsonPrimitive?.content)
        assertEquals("file-api-123", filePart["file_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assistant message round trips reasoningContent including empty string and serverToolCallsJson`() {
        val rawCalls = """[{"index":1,"reasoning_content":"先搜一下","calls":[{"id":"srv_1","signature":"sig_abc"}]}]"""
        val body = sendAndCapture(
            ChatRequest(
                model = "deepseek-flash",
                messages = listOf(
                    ChatMessage(role = "user", content = "查天气"),
                    ChatMessage(
                        role = "assistant",
                        content = "上海今天晴",
                        reasoningContent = "",
                        serverToolCallsJson = rawCalls,
                    ),
                    ChatMessage(role = "user", content = "明天呢"),
                ),
                webSearch = true,
            ),
        )

        val assistant = body["messages"]!!.jsonArray[1].jsonObject
        assertTrue("空串 reasoning_content 也必须回传", "reasoning_content" in assistant)
        assertEquals("", assistant["reasoning_content"]?.jsonPrimitive?.content)
        val calls = assistant["server_tool_calls"] as? JsonArray
        assertTrue("server_tool_calls 应为 JsonArray", calls != null && calls.size == 1)
        assertEquals("sig_abc", calls!![0].jsonObject["calls"]!!.jsonArray[0].jsonObject["signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assistant message ignores corrupted serverToolCallsJson without crashing`() {
        val body = sendAndCapture(
            ChatRequest(
                model = "deepseek-flash",
                messages = listOf(
                    ChatMessage(
                        role = "assistant",
                        content = "好",
                        reasoningContent = "想好了",
                        serverToolCallsJson = "{not-an-array",
                    ),
                    ChatMessage(role = "user", content = "继续"),
                ),
            ),
        )

        val assistant = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("想好了", assistant["reasoning_content"]?.jsonPrimitive?.content)
        assertFalse("损坏的 serverToolCallsJson 不得写入字段或崩溃", "server_tool_calls" in assistant)
    }

    // ------------------------------------------------------------------ helpers

    private fun request(
        thinking: Boolean? = null,
        webSearch: Boolean = false,
        reasoningEffort: String = "low",
    ) = ChatRequest(
        model = "deepseek-flash",
        messages = listOf(ChatMessage(role = "user", content = "在吗")),
        thinking = thinking,
        webSearch = webSearch,
        reasoningEffort = reasoningEffort,
    )

    /** 发一次流式请求，抓取 MockWebServer 收到的请求体并解析成 JSON。 */
    private fun sendAndCapture(request: ChatRequest): JsonObject {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val client = LlmClient(
            http = http,
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
            firstByteTimeoutMs = 3_000,
            heartbeatTimeoutMs = 3_000,
            firstByteTimeoutCapMs = 10_000,
            firstByteScaleBaseMs = 3_000,
            retryDelayMs = 10,
        )
        runBlocking { client.stream(request).toList() }
        val recorded = server.takeRequest()
        return json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }
}
