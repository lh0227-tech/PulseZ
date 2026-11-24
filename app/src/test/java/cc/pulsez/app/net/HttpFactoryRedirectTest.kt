package cc.pulsez.app.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 回归：承载用户内容的共享客户端不跟随重定向。
 *
 * 背景：OkHttp 默认 `followRedirects = true`；307/308 会**保留原 method 与请求体**再发一次，
 * 跨 origin 时只摘掉 `Authorization`（OkHttp 的 `RetryAndFollowUpInterceptor` 行为，
 * 见 证据 的 dexdump 反编译片段）。用户内容（聊天正文/图片/检索片段/录音）
 * 因此可能被发到用户没有配置过的地址。修法：`HttpFactory.create` 关闭重定向，
 * 3xx 原样返回，由各客户端既有的 `isSuccessful` 检查变成可见失败。
 *
 * :81–85、:34。
 */
class HttpFactoryRedirectTest {

    /** 用户实际填写的服务商（第一台）。 */
    private lateinit var configuredServer: MockWebServer

    /** 重定向目标——用户没有配置过的 origin（第二台，端口不同即跨 origin）。 */
    private lateinit var unconfiguredServer: MockWebServer

    @Before
    fun setUp() {
        configuredServer = MockWebServer()
        configuredServer.start()
        unconfiguredServer = MockWebServer()
        unconfiguredServer.start()
    }

    @After
    fun tearDown() {
        configuredServer.shutdown()
        unconfiguredServer.shutdown()
    }

    @Test
    fun `factory disables redirect following`() {
        val client = HttpFactory.create()

        assertFalse("用户内容客户端不能自动跟随普通重定向", client.followRedirects)
        assertFalse("用户内容客户端不能跟随 https/http 换 scheme 的重定向", client.followSslRedirects)
    }

    @Test
    fun `307 does not forward chat body to unconfigured origin`() {
        assertNotForwarded(HttpFactory.create(), code = 307)
    }

    @Test
    fun `308 does not forward chat body to unconfigured origin`() {
        // 307 与 308 是 OkHttp 里保留 method + body 的两种重定向；两种都不允许跟随。
        assertNotForwarded(HttpFactory.create(), code = 308)
    }

    @Test
    fun `llm streaming client view keeps redirects disabled`() {
        // LlmClient 用 `http.newBuilder` 造流式视图（LlmClient.kt:68–71），
        // OkHttp 的 newBuilder 会复制原客户端的设置；这里既核对标志，也真发一次请求。
        val streamingClient: OkHttpClient = HttpFactory.create()
            .newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        assertFalse("newBuilder 视图不能把 followRedirects 恢复成 true", streamingClient.followRedirects)
        assertFalse("newBuilder 视图不能把 followSslRedirects 恢复成 true", streamingClient.followSslRedirects)

        assertNotForwarded(streamingClient, code = 307)
    }

    /**
 * 第一台返回 [code] + `Location` 指向第二台；发一个带正文的 POST。
 * 期望：第一台收到请求；第二台 0 个请求；客户端把 [code] 原样返回，不跟随。
 */
    private fun assertNotForwarded(client: OkHttpClient, code: Int) {
        val redirectedTo = unconfiguredServer.url("/v1/chat/completions").toString()
        configuredServer.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Location", redirectedTo),
        )

        val body = """{"messages":[{"role":"user","content":"这是一段不该发给未配置地址的聊天正文"}]}"""
        val request = Request.Builder()
            .url(configuredServer.url("/v1/chat/completions"))
            .header("Authorization", "Bearer sk-redirect-test")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            // 不跟随：3xx 原样返回，交给上层 isSuccessful 检查映射成可见失败。
            assertEquals("客户端不应把 $code 处理掉，应原样返回", code, response.code)
        }

        assertEquals("用户填写的服务商应收到这 1 次请求", 1, configuredServer.requestCount)
        assertEquals("未配置的 origin 必须收到 0 个请求", 0, unconfiguredServer.requestCount)

        val recorded = configuredServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-redirect-test", recorded.getHeader("Authorization"))
        assertEquals("正文只应到达用户配置的服务商", body, recorded.body.readUtf8())
    }
}
