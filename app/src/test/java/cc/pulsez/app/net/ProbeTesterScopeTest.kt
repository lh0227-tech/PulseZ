package cc.pulsez.app.net

import cc.pulsez.app.wire.ProbeTester
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

/**
 * 两个「测一下」各只访问自己的端点。
 *
 * [ModelProbeTest] 锁底层开关；这里锁 [ProbeTester] 的接线：
 * - 聊天「测一下」只发 `/models` + `/chat/completions`，不再顺带探 embeddings；
 * - 「测一下找旧事」只发 `/embeddings`，不再发 chat 请求、也不为挑模型发 `/models`；
 * - 跳过不会把结论误判成成功或失败（失败仍按原逻辑归类）。
 *
 * 为什么测试文件放在 `net/` 而不是 `wire/`：本允许改的测试目录是
 * `ui/config/` 与 `net/`；被测类在 `wire`，纯 JVM 测试按包名放哪都能跑。
 * 用 MockWebServer 全程离线，不碰真机、不调真实 API。
 */
class ProbeTesterScopeTest {

    private lateinit var server: MockWebServer
    private lateinit var tester: ProbeTester

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // settings = null：只测连接，不写识图能力缓存。
        tester = ProbeTester(HttpFactory.create() as OkHttpClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `chat test only hits chat endpoints`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"object":"list","data":[{"id":"deepseek-chat","input_modalities":["text"]}]}""",
            ),
        )
        server.enqueue(jsonResponse("""{"choices":[{"message":{"role":"assistant","content":"在"}}]}"""))
        // 万一聊天测试仍探 embeddings，会取走这个 404 并出现在请求记录里。
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"not found"}}"""))

        val result = tester.testChat(server.url("/v1").toString(), "sk-test", "deepseek-chat")

        assertTrue(result.chatOk)
        assertEquals("只发 models + chat 两次请求", 2, server.requestCount)
        assertEquals("/v1/models", server.takeRequest().path)
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun `chat test failure is not masked by skipped embeddings`() = runBlocking {
        server.enqueue(jsonResponse("""{"data":[]}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        server.enqueue(jsonResponse("""{"data":[{"embedding":[0.1]}]}"""))

        val result = tester.testChat(server.url("/v1").toString(), "sk-test", "deepseek-chat")

        assertFalse("聊天失败不能被跳过的 embeddings 探测掩盖", result.chatOk)
        assertFalse(result.embeddingsOk)
        assertTrue("失败要给出人话而不是空串", result.message.isNotBlank())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `find old things test only hits embeddings endpoint`() = runBlocking {
        server.enqueue(jsonResponse("""{"data":[{"index":0,"embedding":[0.1,0.2,0.3]}]}"""))
        // 万一仍探聊天，会取走这个响应并出现在记录里。
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"在"}}]}"""))

        val result = tester.testEmbeddings(server.url("/v1").toString(), "sk-test", "bge-m3")

        assertTrue(result.embeddingsOk)
        assertEquals("只发一次 embeddings 请求", 1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/v1/embeddings", request.path)
        assertTrue("要找旧事模型名", request.body.readUtf8().contains("bge-m3"))
    }

    @Test
    fun `find old things test failure stays an embeddings result`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"not found"}}"""))
        server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"在"}}]}"""))

        val result = tester.testEmbeddings(server.url("/v1").toString(), "sk-test", "bge-m3")

        assertFalse(result.embeddingsOk)
        assertFalse("找旧事测试不能声称聊天可用", result.chatOk)
        assertEquals(1, server.requestCount)
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
