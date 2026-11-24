package cc.pulsez.app.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmClientCompleteTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var client: LlmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = HttpFactory.create()
        client = LlmClient(
            http = http,
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
            retryDelayMs = 10,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `complete returns message content and uses non stream request`() {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"role":"assistant","content":"记住了"}}]}"""))

        val content = runBlocking { client.complete(request()) }

        assertEquals("记住了", content)
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(!body.contains("stream_options"))
    }

    @Test
    fun `complete 401 throws api exception auth`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))

        val error = runCatching { runBlocking { client.complete(request()) } }.exceptionOrNull()
        assertTrue(error is ApiException)
        assertEquals(ApiError.Kind.AUTH, (error as ApiException).error.kind)
        assertEquals(1, server.requestCount) // 401 不重试
    }

    @Test
    fun `complete retries server errors twice`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("boom"))
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"ok"}}]}"""))

        val content = runBlocking { client.complete(request()) }

        assertEquals("ok", content)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `completeJson sends response_format and returns json text`() {
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"{\"facts\":[]}"}}]}"""))

        val content = runBlocking { client.completeJson(request()) }

        assertEquals("{\"facts\":[]}", content)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"))
    }

    @Test
    fun `completeJson retries without response_format on 400`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"message":"response_format is not supported"}}"""),
        )
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"{}"}}]}"""))

        val content = runBlocking { client.completeJson(request()) }

        assertEquals("{}", content)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("response_format"))
        assertFalse(second.contains("response_format"))
    }

    @Test
    fun `base url without v1 falls back once on 404`() {
        val bareClient = LlmClient(
            http = http,
            baseUrl = server.url("/").toString(),
            apiKey = "sk-test",
            retryDelayMs = 10,
        )
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"好"}}]}"""))

        val content = runBlocking { bareClient.complete(request()) }

        assertEquals("好", content)
        assertEquals("/chat/completions", server.takeRequest().path)
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    private fun request() = ChatRequest(
        model = "deepseek-flash",
        messages = listOf(ChatMessage(role = "user", content = "在吗")),
        stream = false,
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
