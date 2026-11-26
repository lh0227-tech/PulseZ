package cc.pulsez.app.net

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DeepSeek `web_tools` 流式协议闭环离线单测：
 * - `server_tool_event`（`step_reasoning_done`、`call_started`、`call_completed`）；
 * - 末帧 `delta.server_tool_calls` 与 `message.server_tool_calls` 完整保留（含 `signature`）；
 * - `Finished.reasoningContent` 在存在 `server_tool_calls` 时按末个 `step_reasoning_done` 切片；
 * - `parseCitations` keeps same-URL citations with different `start_index`/`end_index` spans and preserves code point offsets.
 */
class LlmClientWebToolsTest {

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
    fun `server_tool_event emits ToolProgress and slices final step reasoning`() {
        val rawToolCalls =
            """[{"index":1,"reasoning_content":"第一步思考","calls":[{"index":0,"id":"srv_1","type":"deepseek_web_search","signature":"sig_xyz"}]}]"""
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"reasoning_content":"第一步"}}]}"""),
                frame("""{"choices":[{"delta":{"reasoning_content":"思考"}}]}"""),
                frame("""{"choices":[{"delta":{},"server_tool_event":{"type":"step_reasoning_done","step":1}}]}"""),
                frame(
                    """{"choices":[{"delta":{},"server_tool_event":{"type":"call_started","step":1,"call":{"kind":"search","status":"in_progress","action":{"type":"search","queries":["杭州天气","西湖开放"]}}}}]}""",
                ),
                frame(
                    """{"choices":[{"delta":{},"server_tool_event":{"type":"call_completed","step":1,"call":{"kind":"search","status":"completed","action":{"type":"search","queries":["杭州天气"]},"sources":[{"url":"https://a.example"},{"url":"https://b.example"}]}}}]}""",
                ),
                frame(
                    """{"choices":[{"delta":{},"server_tool_event":{"type":"call_started","step":1,"call":{"kind":"open_page","status":"in_progress","action":{"type":"open_page","url":"https://a.example/page"}}}}]}""",
                ),
                frame(
                    """{"choices":[{"delta":{},"server_tool_event":{"type":"call_completed","step":1,"call":{"kind":"open_page","status":"completed","action":{"type":"open_page","url":"https://a.example/page"}}}}]}""",
                ),
                frame("""{"choices":[{"delta":{"reasoning_content":"末步整理"}}]}"""),
                frame("""{"choices":[{"delta":{"content":"今天晴。"}}]}"""),
                frame(
                    """{"choices":[{"delta":{"server_tool_calls":$rawToolCalls},"finish_reason":"stop"}]}""",
                ),
                doneFrame(),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }

        val toolEvents = events.filterIsInstance<LlmEvent.ToolProgress>()
        assertEquals(4, toolEvents.size)
        assertEquals(
            LlmEvent.ToolProgress(
                kind = "search",
                status = "in_progress",
                queries = listOf("杭州天气", "西湖开放"),
                url = null,
                sourceCount = 0,
            ),
            toolEvents[0],
        )
        assertEquals(
            LlmEvent.ToolProgress(
                kind = "search",
                status = "completed",
                queries = listOf("杭州天气"),
                url = null,
                sourceCount = 2,
            ),
            toolEvents[1],
        )
        assertEquals(
            LlmEvent.ToolProgress(
                kind = "open_page",
                status = "in_progress",
                queries = emptyList(),
                url = "https://a.example/page",
                sourceCount = 0,
            ),
            toolEvents[2],
        )

        val finished = events.last() as LlmEvent.Finished
        assertEquals("末步整理", finished.reasoningContent)
        assertNotNull(finished.serverToolCallsJson)
        val parsedCalls = json.parseToJsonElement(finished.serverToolCallsJson!!).jsonArray
        assertEquals(
            "sig_xyz",
            parsedCalls[0].jsonObject["calls"]!!.jsonArray[0].jsonObject["signature"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `server_tool_calls with empty final step reasoning keeps empty string for round-trip`() {
        val rawToolCalls = """[{"index":1,"reasoning_content":"先查一下","calls":[{"id":"srv_2","signature":"sig_2"}]}]"""
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"reasoning_content":"先查一下"}}]}"""),
                frame("""{"choices":[{"delta":{},"server_tool_event":{"type":"step_reasoning_done","step":1}}]}"""),
                frame("""{"choices":[{"delta":{"content":"好了"},"server_tool_calls":$rawToolCalls,"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )

        val finished = runBlocking { client().stream(request()).toList().last() } as LlmEvent.Finished
        assertEquals("", finished.reasoningContent)
        assertNotNull(finished.serverToolCallsJson)
    }

    @Test
    fun `thinking off web search without reasoning output returns null reasoningContent`() {
        val rawToolCalls = """[{"index":1,"calls":[{"id":"srv_no_think","type":"deepseek_web_search","signature":"sig_no_think"}]}]"""
        server.enqueue(
            sseResponse(
                frame(
                    """{"choices":[{"delta":{},"server_tool_event":{"type":"call_started","step":1,"call":{"kind":"search","status":"in_progress","action":{"type":"search","queries":["杭州天气"]}}}}]}""",
                ),
                frame(
                    """{"choices":[{"delta":{},"server_tool_event":{"type":"call_completed","step":1,"call":{"kind":"search","status":"completed","action":{"type":"search","queries":["杭州天气"]},"sources":[{"url":"https://a.example"}]}}}]}""",
                ),
                frame("""{"choices":[{"delta":{"content":"今天杭州晴。"}}]}"""),
                frame("""{"choices":[{"delta":{"server_tool_calls":$rawToolCalls},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )

        val finished = runBlocking { client().stream(request(thinking = false)).toList().last() } as LlmEvent.Finished
        assertNull(finished.reasoningContent)
        assertNotNull(finished.serverToolCallsJson)
    }

    @Test
    fun `thinking off web search with explicit null reasoning_content still returns null`() {
        val rawToolCalls = """[{"index":1,"calls":[{"id":"srv_null_reason","type":"deepseek_web_search","signature":"sig_null"}]}]"""
        server.enqueue(
            sseResponse(
                frame(
                    """{"choices":[{"delta":{},"server_tool_event":{"type":"call_started","step":1,"call":{"kind":"search","status":"in_progress","action":{"type":"search","queries":["杭州天气"]}}}}]}""",
                ),
                frame(
                    """{"choices":[{"delta":{},"server_tool_event":{"type":"call_completed","step":1,"call":{"kind":"search","status":"completed","action":{"type":"search","queries":["杭州天气"]}}}}]}""",
                ),
                frame("""{"choices":[{"delta":{"reasoning_content":null,"reasoning":null,"content":"今天杭州晴。"}}]}"""),
                frame("""{"choices":[{"delta":{"server_tool_calls":$rawToolCalls},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )

        val finished = runBlocking { client().stream(request(thinking = false)).toList().last() } as LlmEvent.Finished
        assertNull(finished.reasoningContent)
        assertNotNull(finished.serverToolCallsJson)
    }

    @Test
    fun `message level server_tool_calls without step_reasoning_done returns full reasoning buffer`() {
        val rawToolCalls = """[{"index":1,"calls":[{"id":"srv_3","signature":"sig_3"}]}]"""
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"reasoning_content":"整段推理"}}]}"""),
                frame(
                    """{"choices":[{"message":{"content":"回答","server_tool_calls":$rawToolCalls},"finish_reason":"stop"}]}""",
                ),
                doneFrame(),
            ),
        )

        val finished = runBlocking { client().stream(request()).toList().last() } as LlmEvent.Finished
        assertEquals("整段推理", finished.reasoningContent)
        assertNotNull(finished.serverToolCallsJson)
    }

    @Test
    fun `normal thinking turn without server_tool_calls still returns full reasoningContent for persistence`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"reasoning_content":"思考甲"}}]}"""),
                frame("""{"choices":[{"delta":{"reasoning_content":"思考乙"}}]}"""),
                frame("""{"choices":[{"delta":{"content":"正文"},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )

        val finished = runBlocking { client().stream(request()).toList().last() } as LlmEvent.Finished
        assertEquals("思考甲思考乙", finished.reasoningContent)
        assertNull(finished.serverToolCallsJson)
    }

    @Test
    fun `citations preserve duplicate url across distinct start and end indices`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"第一句。第二句。"}}]}"""),
                frame(
                    """{"choices":[{"delta":{"annotations":[""" +
                        """{"type":"url_citation","url_citation":{"url":"https://same.example/doc","title":"同一来源","start_index":0,"end_index":4}},""" +
                        """{"type":"url_citation","url_citation":{"url":"https://other.example/ref","title":"另一来源","start_index":4,"end_index":8}},""" +
                        """{"type":"url_citation","url_citation":{"url":"https://same.example/doc","title":"同一来源","start_index":5,"end_index":9}},""" +
                        """{"type":"url_citation","url_citation":{"url":"https://same.example/doc","title":"重复同位置","start_index":0,"end_index":4}}""" +
                        """]},"finish_reason":"stop"}]}""",
                ),
                doneFrame(),
            ),
        )

        val finished = runBlocking { client().stream(request()).toList().last() } as LlmEvent.Finished
        assertEquals(3, finished.citations.size)
        assertEquals(
            listOf(
                Citation("https://same.example/doc", "同一来源", startIndex = 0, endIndex = 4),
                Citation("https://other.example/ref", "另一来源", startIndex = 4, endIndex = 8),
                Citation("https://same.example/doc", "同一来源", startIndex = 5, endIndex = 9),
            ),
            finished.citations,
        )
        assertTrue(finished.citations[0].startIndex == 0 && finished.citations[2].startIndex == 5)
    }

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

    private fun request(thinking: Boolean = true) = ChatRequest(
        model = "deepseek-flash",
        messages = listOf(ChatMessage(role = "user", content = "搜一下")),
        thinking = thinking,
        webSearch = true,
    )

    private fun frame(json: String) = "data: $json\n\n"
    private fun doneFrame() = "data: [DONE]\n\n"
    private fun sseResponse(vararg chunks: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString(""))
}
