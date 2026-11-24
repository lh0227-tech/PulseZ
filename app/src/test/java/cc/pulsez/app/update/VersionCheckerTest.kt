package cc.pulsez.app.update

import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.TimeUnit

/**
 * 清单检查器：主源 → 备源降级、失败静默、限长、超时、请求形态（-A）。
 *
 * （主源 + 降级链：两个源都失败 → 静默）、
 *（请求约束：只 GET 静态文件、不带设备标识/query/cookie、短超时、最多 2 个源各 1 次不重试）、
 *（更新源宕机 = 等同没有新版）。
 *
 * 全程用本机 MockWebServer，**不请求任何真实域名**。
 */
class VersionCheckerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ---------------------------------------------------------------- 请求形态

    @Test
    fun `主源成功即用 请求是 GET 静态文件且不带认证和 query`() = runBlocking {
        server.enqueue(jsonResponse(manifestJson()))
        val checker = VersionChecker(
            sources = listOf(primarySource()),
            client = UpdateConfig.newClient(),
        )

        val manifest = checker.fetchLatest()
        assertNotNull(manifest)
        assertEquals(8L, manifest!!.versionCode)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("只能是静态文件路径，不能加随机 query", "/stable/version.json", request.path)
        assertNull("不能带 Authorization（清单是公开只读文件）", request.getHeader("Authorization"))
        assertNull("不能带 Cookie", request.getHeader("Cookie"))
        val userAgent = request.getHeader("User-Agent").orEmpty()
        assertTrue(
            "不能带 App/设备标识的 UA（OkHttp 默认 UA 只含库名版本）：$userAgent",
            !userAgent.contains("pulsez", ignoreCase = true) && !userAgent.contains("cc.pulsez"),
        )
        assertEquals("只请求一次（不重试）", 1, server.requestCount)
    }

    // ---------------------------------------------------------------- 降级链

    @Test
    fun `主源 5xx 时走备源`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(jsonResponse(manifestJson(versionCode = 9L, versionName = "0.5.2")))

        val manifest = checker().fetchLatest()

        assertNotNull("备源成功就应该拿到清单", manifest)
        assertEquals(9L, manifest!!.versionCode)
        assertEquals("主源", "/stable/version.json", server.takeRequest().path)
        assertEquals("备源", "/gh/version.json", server.takeRequest().path)
    }

    @Test
    fun `主源 404 时走备源`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(jsonResponse(manifestJson()))

        assertNotNull(checker().fetchLatest())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `主源清单非法时走备源`() = runBlocking {
        server.enqueue(jsonResponse("{not a manifest"))
        server.enqueue(jsonResponse(manifestJson()))

        val manifest = checker().fetchLatest()
        assertNotNull("主源清单无效只算这一源失败，换备源", manifest)
        assertEquals(8L, manifest!!.versionCode)
    }

    @Test
    fun `两个源都失败返回 null 且每个源只请求一次`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(503))

        assertNull("两个源都失败 = 等同没有新版，静默", checker().fetchLatest())
        assertEquals("不重试：两个源各 1 次", 2, server.requestCount)
    }

    // ---------------------------------------------------------------- 超时 / 网络错误 / 超长

    @Test
    fun `超时按失败处理 不抛异常`() = runBlocking {
        val shortTimeoutClient: OkHttpClient = UpdateConfig.newClient().newBuilder()
            .callTimeout(200, TimeUnit.MILLISECONDS)
            .readTimeout(200, TimeUnit.MILLISECONDS)
            .build()
        // 响应头故意拖 2 秒（超过 200ms 超时）。
        server.enqueue(
            MockResponse()
                .setHeadersDelay(2, TimeUnit.SECONDS)
                .setBody(manifestJson()),
        )

        val manifest = VersionChecker(
            sources = listOf(primarySource()),
            client = shortTimeoutClient,
        ).fetchLatest()

        assertNull("超时只算这一源失败，不抛异常", manifest)
    }

    @Test
    fun `网络不通返回 null 不崩`() = runBlocking {
        val dead = MockWebServer()
        dead.start()
        val deadUrl = dead.url("/stable/version.json").toString()
        dead.shutdown()

        val manifest = VersionChecker(
            sources = listOf(UpdateSource("dead", deadUrl)),
            client = UpdateConfig.newClient(),
        ).fetchLatest()

        assertNull(manifest)
    }

    @Test
    fun `响应体超过上限直接拒绝`() = runBlocking {
        // 正常清单约 1 KB；这里给 70 KB（> 64 KiB 上限），带 Content-Length 走快速拒绝。
        server.enqueue(jsonResponse("x".repeat(70_000)))
        assertNull(checker().fetchLatest())
    }

    @Test
    fun `chunked 超长响应也拒绝 不把超大正文读进内存`() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("x".repeat(70_000), 1024))
        assertNull(checker().fetchLatest())
    }

    // ---------------------------------------------------------------- 辅助

    private fun primarySource(): UpdateSource =
        UpdateSource("primary", server.url("/stable/version.json").toString())

    private fun fallbackSource(): UpdateSource =
        UpdateSource("github", server.url("/gh/version.json").toString())

    private fun checker(): VersionChecker =
        VersionChecker(sources = listOf(primarySource(), fallbackSource()), client = UpdateConfig.newClient())

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun manifestJson(versionCode: Long = 8L, versionName: String = "0.5.1"): String =
        TestManifests.json(
            versionCode = versionCode.toString(),
            versionName = "\"$versionName\"",
        )
}
