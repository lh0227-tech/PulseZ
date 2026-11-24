package cc.pulsez.app.net

import kotlinx.coroutines.runBlocking
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

class ModelProbeTest {

    private lateinit var server: MockWebServer
    private lateinit var probe: ModelProbe

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        probe = ModelProbe(HttpFactory.create() as OkHttpClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `all capabilities supported`() {
        server.enqueue(
            jsonResponse(
                """{"object":"list","data":[{"id":"deepseek-chat"},{"id":"bge-m3"}]}""",
            ),
        )
        server.enqueue(
            jsonResponse("""{"choices":[{"message":{"role":"assistant","content":"在"}}]}"""),
        )
        server.enqueue(
            jsonResponse("""{"data":[{"index":0,"embedding":[0.1,0.2,0.3]}]}"""),
        )

        val result = runBlocking {
            probe.probe(server.url("/v1").toString(), "sk-test", model = "deepseek-chat")
        }
        assertTrue(result.chatOk)
        assertTrue(result.embeddingsOk)
        assertNull(result.error)
        assertEquals(listOf("deepseek-chat", "bge-m3"), result.models)

        // 不传开关时仍是旧行为：models → chat → embeddings 三步都探（的默认值回归）。
        assertEquals("/v1/models", server.takeRequest().path)
        val chat = server.takeRequest()
        assertEquals("/v1/chat/completions", chat.path)
        val chatBody = chat.body.readUtf8()
        assertTrue(chatBody.contains("\"max_tokens\":1"))
        assertTrue(chatBody.contains("\"stream\":false"))
        val embedding = server.takeRequest()
        assertEquals("/v1/embeddings", embedding.path)
        assertTrue(embedding.body.readUtf8().contains("在吗在吗在吗在吗"))
    }

    @Test
    fun `chat only probe never hits embeddings endpoint`() {
        // 聊天「测一下」不能顺带探找旧事端点。
        server.enqueue(jsonResponse("""{"data":[{"id":"deepseek-chat"}]}"""))
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"在"}}]}"""))
        // 万一仍发 embeddings，会取走这个 404 并出现在请求记录里。
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"not found"}}"""))

        val result = runBlocking {
            probe.probe(
                server.url("/v1").toString(),
                "sk-test",
                model = "deepseek-chat",
                probeEmbeddings = false,
            )
        }

        // 跳过不等于"这家没有找旧事能力"：结论只报聊天，不误判成功/失败。
        assertTrue(result.chatOk)
        assertFalse("没探测的端点不能声称有 embeddings", result.embeddingsOk)
        assertNull(result.error)
        assertEquals("只发 models + chat 两次请求", 2, server.requestCount)
        assertEquals("/v1/models", server.takeRequest().path)
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun `chat only probe failure is still classified`() {
        // 跳过 embeddings 后，聊天失败仍要按原逻辑归类（不能被"跳过"掩盖成成功）。
        server.enqueue(jsonResponse("""{"data":[]}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))

        val result = runBlocking {
            probe.probe(server.url("/v1").toString(), "sk-test", probeEmbeddings = false)
        }

        assertFalse(result.chatOk)
        assertFalse(result.embeddingsOk)
        assertEquals(ApiError.Kind.AUTH, result.error?.kind)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `embeddings only probe hits only embeddings endpoint`() {
        // 找旧事「测一下」不能顺带探聊天端点，也不需要 /models 挑模型。
        server.enqueue(jsonResponse("""{"data":[{"index":0,"embedding":[0.1,0.2,0.3]}]}"""))
        // 万一多发了 chat 请求，会取走这个响应并出现在记录里。
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"在"}}]}"""))

        val result = runBlocking {
            probe.probe(
                server.url("/v1").toString(),
                "sk-test",
                embeddingModel = "bge-m3",
                probeChat = false,
            )
        }

        assertTrue(result.embeddingsOk)
        assertFalse("没探测聊天，不能声称聊天可用", result.chatOk)
        assertNull(result.error)
        assertEquals("只发一次 embeddings 请求", 1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/v1/embeddings", request.path)
        assertTrue(request.body.readUtf8().contains("bge-m3"))
    }

    @Test
    fun `embeddings only failure is not reported as a chat error`() {
        // 找旧事端点失败由调用方只看 embeddingsOk；不产生聊天类 error。
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"not found"}}"""))

        val result = runBlocking {
            probe.probe(server.url("/v1").toString(), "sk-test", probeChat = false)
        }

        assertFalse(result.embeddingsOk)
        assertFalse(result.chatOk)
        assertNull(result.error)
        assertEquals(1, server.requestCount)
        assertEquals("/v1/embeddings", server.takeRequest().path)
    }

    @Test
    fun `chat works but embeddings missing`() {
        server.enqueue(jsonResponse("""{"data":[{"id":"deepseek-chat"}]}"""))
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"在"}}]}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"not found"}}"""))

        val result = runBlocking {
            probe.probe(server.url("/v1").toString(), "sk-test")
        }
        assertTrue(result.chatOk)
        assertFalse(result.embeddingsOk)
        assertNull("embeddings 缺失不是连接错误", result.error)
    }

    @Test
    fun `chat 401 classifies auth and never throws`() {
        server.enqueue(jsonResponse("""{"data":[]}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))

        val result = runBlocking {
            probe.probe(server.url("/v1").toString(), "sk-test")
        }
        assertFalse(result.chatOk)
        assertFalse(result.embeddingsOk)
        assertEquals(ApiError.Kind.AUTH, result.error?.kind)
    }

    @Test
    fun `models failure does not break chat probe`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"在"}}]}"""))
        server.enqueue(jsonResponse("""{"data":[{"embedding":[0.1]}]}"""))

        val result = runBlocking {
            probe.probe(server.url("/v1").toString(), "sk-test", model = "deepseek-chat")
        }
        assertTrue(result.chatOk)
        assertTrue(result.embeddingsOk)
        assertTrue(result.models.isEmpty())
        assertNull(result.error)
    }

    @Test
    fun `blank key is no key without network calls`() {
        val result = runBlocking { probe.probe(server.url("/v1").toString(), "  ") }
        assertEquals(ApiError.Kind.NO_KEY, result.error?.kind)
        assertFalse(result.chatOk)
        assertEquals(0, server.requestCount)
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
