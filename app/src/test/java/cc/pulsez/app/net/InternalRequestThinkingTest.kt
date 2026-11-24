package cc.pulsez.app.net

import cc.pulsez.app.core.GroupMemoryExtractor
import cc.pulsez.app.data.group.GroupReplyCall
import cc.pulsez.app.mem.MemMessage
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ：**内部/后台请求**在 DeepSeek 端点下必须显式发 `thinking:{"type":"disabled"}`，
 * 非 DeepSeek 端点**一个 thinking 字段都不发**。
 *
 * 覆盖四个生产调用点：
 * 1. 群聊回复（`GroupReplyCall`，`data/group/GroupPortsImpl.kt` 的 reply 路径）；
 * 2. 群聊记忆抽取（`GroupMemoryExtractor`，`core/GroupTurnMemory.kt`）；
 * 3. 1:1 增量抽取 / 交接摘要（`core/ChatEngine.kt` 的请求形状）；
 * 4. 能力探测（`ModelProbe`，`max_tokens=1`）。
 *
 * 断言在**真实请求体**上做（MockWebServer 抓包），不是只断言内部字段：
 * 只有走通 `LlmClient` → `Payloads` 才知道最终发出去的是 `disabled` 而不是"省略"。
 * （不传 ≠ 关）、（关思考不发 effort）。
 */
class InternalRequestThinkingTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val deepSeek = "https://api.deepseek.com/v1"
    private val otherProvider = "https://api.siliconflow.cn/v1"

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

    // ------------------------------------------------------------------ 1. 群聊回复

    @Test
    fun groupReplyOnDeepSeekSendsDisabledThinking() {
        server.enqueue(sseResponse())

        val call = GroupReplyCall(
            model = "deepseek-flash",
            stream = client()::stream,
            recordUsage = {},
            internalBaseUrl = deepSeek,
        )
        runBlocking { call.invoke(listOf(MemMessage.user("在吗")), maxTokens = 256) }

        val body = lastBody()
        assertEquals("disabled", thinkingType(body))
        assertFalse("关思考时不得同时发 reasoning_effort", "reasoning_effort" in body)
    }

    @Test
    fun groupReplyOnOtherProviderSendsNoThinkingField() {
        server.enqueue(sseResponse())

        val call = GroupReplyCall(
            model = "some-model",
            stream = client()::stream,
            recordUsage = {},
            internalBaseUrl = otherProvider,
        )
        runBlocking { call.invoke(listOf(MemMessage.user("在吗")), maxTokens = 256) }

        val body = lastBody()
        assertFalse("非 DeepSeek 端点不能发 thinking，实际=$body", "thinking" in body)
        assertFalse("非 DeepSeek 端点不能发 reasoning_effort", "reasoning_effort" in body)
    }

    // ------------------------------------------------------------------ 2. 群聊记忆抽取

    @Test
    fun groupMemoryExtractionOnDeepSeekSendsDisabledThinking() {
        var captured: ChatRequest? = null
        val extractor = GroupMemoryExtractor(
            completeJson = { request ->
                captured = request
                """{"facts":[]}"""
            },
            applyPatch = {},
        )
        runBlocking {
            extractor.extract(
                model = "deepseek-flash",
                shard = listOf(MemMessage.user("我喜欢猫")),
                internalBaseUrl = deepSeek,
            )
        }

        val request = captured
        assertTrue("抽取必须真的发起一次请求", request != null)
        assertEquals("抽取请求在 DeepSeek 端点必须显式关思考", false, request!!.thinking)

        // 请求体层再验一次（走真实 Payloads）。
        server.enqueue(jsonCompletion())
        runBlocking { client().complete(request!!) }
        val body = lastBody()
        assertEquals("disabled", thinkingType(body))
        assertTrue("抽取仍是 JSON 模式", "response_format" in body)
    }

    @Test
    fun groupMemoryExtractionOnOtherProviderSendsNoThinkingField() {
        var captured: ChatRequest? = null
        val extractor = GroupMemoryExtractor(
            completeJson = { request ->
                captured = request
                """{"facts":[]}"""
            },
            applyPatch = {},
        )
        runBlocking {
            extractor.extract(
                model = "some-model",
                shard = listOf(MemMessage.user("我喜欢猫")),
                internalBaseUrl = otherProvider,
            )
        }

        assertNull("非 DeepSeek 端点不传 thinking", captured!!.thinking)
        server.enqueue(jsonCompletion())
        runBlocking { client().complete(captured!!) }
        assertFalse("非 DeepSeek 端点请求体不得出现 thinking", "thinking" in lastBody())
    }

    // ------------------------------------------------------------------ 3. 1:1 增量抽取 / 交接摘要

    @Test
    fun chatEngineMaintenanceRequestShapeSendsDisabledThinking() {
        // 与 ChatEngine.extractIfDue / batonIfDue 完全一致的请求形状 + 同样的 asInternalRequest。
        val request = ChatRequest(
            model = "deepseek-flash",
            messages = listOf(
                ChatMessage("system", "记忆抽取器"),
                ChatMessage("user", "用户说喜欢猫"),
            ),
            stream = false,
            jsonMode = true,
            maxTokens = 2000,
            temperature = 0.2,
        ).asInternalRequest(deepSeek)

        server.enqueue(jsonCompletion())
        runBlocking { client().complete(request) }

        val body = lastBody()
        assertEquals("disabled", thinkingType(body))
        assertEquals("2000", body["max_tokens"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------ 4. 能力探测

    @Test
    fun capabilityProbeOnDeepSeekSendsDisabledThinking() {
        enqueueProbeResponses()
        val probe = ModelProbe(http, deepSeekCheck = { true })

        runBlocking { probe.probe(server.url("/v1").toString(), "sk-test", model = "deepseek-flash") }

        // 顺序：GET /models → POST /chat/completions → POST /embeddings
        assertEquals("/v1/models", server.takeRequest().path)
        val chat = server.takeRequest()
        assertEquals("/v1/chat/completions", chat.path)
        val body = json.parseToJsonElement(chat.body.readUtf8()).jsonObject
        assertEquals("探测 max_tokens=1 时若思考开，正文必然为空", "disabled", thinkingType(body))
        assertEquals("1", body["max_tokens"]?.jsonPrimitive?.content)
    }

    @Test
    fun capabilityProbeOnOtherProviderSendsNoThinkingField() {
        enqueueProbeResponses()
        val probe = ModelProbe(http, deepSeekCheck = { false })

        runBlocking { probe.probe(server.url("/v1").toString(), "sk-test", model = "some-model") }

        assertEquals("/v1/models", server.takeRequest().path)
        val chat = server.takeRequest()
        val body = json.parseToJsonElement(chat.body.readUtf8()).jsonObject
        assertFalse("非 DeepSeek 端点不发 thinking，实际=$body", "thinking" in body)
    }

    // ------------------------------------------------------------------ helpers

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

    private fun lastBody(): JsonObject {
        val recorded = server.takeRequest()
        return json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }

    private fun thinkingType(body: JsonObject): String? =
        body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content

    private fun sseResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        )

    private fun jsonCompletion(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"choices":[{"message":{"content":"{}"}}]}""")

    private fun enqueueProbeResponses() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"id":"deepseek-flash"}]}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"在"}}]}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error":{"message":"not found"}}"""),
        )
    }
}
