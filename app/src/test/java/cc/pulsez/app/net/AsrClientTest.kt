package cc.pulsez.app.net

import cc.pulsez.app.audio.WavWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * DeepSeek ASR 客户端（ 基建层）。
 *
 * 用 MockWebServer 抓真实发出去的请求：路径必须是 `{origin}/v1/tools/asr`、
 * multipart 字段名必须是 `file`、文件名 `voice.wav`、类型 `audio/wav`、带 Bearer 密钥；
 * 返回解析 `{text, duration_ms}`；HTTP 失败映射到现有 `ApiError` 体系；
 * 非法/超限在本地前置拒绝（一次请求都不发，不产生费用）。
 *
 * /；官方 ASR 指南 https://api-docs.deepseek.com/zh-cn/guides/asr
 */
class AsrClientTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = HttpFactory.create()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        tempFiles.forEach { runCatching { it.delete() } }
    }

    // ---------------------------------------------------------------- 请求形态

    @Test
    fun `multipart 字段名 file 文件名 voice wav 路径是 v1 tools asr`() {
        server.enqueue(
            jsonResponse("""{"request_id":"abc","text":"今天天气不错","duration_ms":3200}"""),
        )
        val client = AsrClient(http, server.url("/v1").toString(), "sk-test")
        val result = runBlocking { client.transcribe(newWavFile(1_000L), 1_000L) }

        assertEquals("今天天气不错", result.text)
        assertEquals(3_200L, result.durationMs)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/tools/asr", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val contentType = request.getHeader("Content-Type").orEmpty()
        assertTrue("必须是 multipart/form-data，实际=$contentType", contentType.startsWith("multipart/form-data"))

        val body = String(request.body.readByteArray(), Charsets.ISO_8859_1)
        assertTrue("字段名必须是官方唯一字段 file", body.contains("name=\"file\""))
        assertTrue("文件名 voice.wav", body.contains("filename=\"voice.wav\""))
        assertTrue("内容类型 audio/wav", body.contains("Content-Type: audio/wav"))
        assertTrue("上传的是 WAV（RIFF 头）", body.contains("RIFF"))
    }

    @Test
    fun `baseUrl 只有根路径也拼成 v1 tools asr`() {
        server.enqueue(jsonResponse("""{"text":"好","duration_ms":10}"""))
        val client = AsrClient(http, server.url("/").toString(), "sk-test")
        runBlocking { client.transcribe(newWavFile(500L), 500L) }
        assertEquals("/v1/tools/asr", server.takeRequest().path)
    }

    @Test
    fun `baseUrl 粘了 chat completions 也能拼对`() {
        server.enqueue(jsonResponse("""{"text":"好","duration_ms":10}"""))
        val client = AsrClient(
            http,
            server.url("/v1/chat/completions").toString(),
            "sk-test",
        )
        runBlocking { client.transcribe(newWavFile(500L), 500L) }
        assertEquals("不能出现 /v1/chat/completions/v1/tools/asr", "/v1/tools/asr", server.takeRequest().path)
    }

    @Test
    fun `空文本是成功结果而不是异常`() {
        // 没听清时服务端可能返回空 text：由 UI 提示「没听清，再说一遍」，不是网络错误。
        server.enqueue(jsonResponse("""{"request_id":"x","text":"","duration_ms":800}"""))
        val client = AsrClient(http, server.url("/v1").toString(), "sk-test")
        val result = runBlocking { client.transcribe(newWavFile(500L), 500L) }
        assertEquals("", result.text)
        assertEquals(800L, result.durationMs)
    }

    // ---------------------------------------------------------------- HTTP 错误映射

    @Test
    fun `401 映射成密钥错误`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val error = transcribeError(AsrClient(http, server.url("/v1").toString(), "sk-test"))
        assertEquals(ApiError.Kind.AUTH, error.kind)
        assertEquals(401, error.httpCode)
    }

    @Test
    fun `429 映射成额度错误`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limit"}}"""))
        val error = transcribeError(AsrClient(http, server.url("/v1").toString(), "sk-test"))
        assertEquals(ApiError.Kind.QUOTA, error.kind)
        assertEquals(429, error.httpCode)
    }

    @Test
    fun `5xx 全部映射成服务端错误`() {
        listOf(500, 503, 504).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("busy"))
            val error = transcribeError(AsrClient(http, server.url("/v1").toString(), "sk-test"))
            assertEquals("HTTP $code 要归服务端错误", ApiError.Kind.SERVER, error.kind)
            assertEquals(code, error.httpCode)
        }
    }

    @Test
    fun `400 普通错误体映射成坏响应`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"message":"bad request"}}"""),
        )
        val error = transcribeError(AsrClient(http, server.url("/v1").toString(), "sk-test"))
        assertEquals(ApiError.Kind.BAD_RESPONSE, error.kind)
        assertEquals(400, error.httpCode)
    }

    @Test
    fun `413 保留 httpCode 供 UI 说文件太大`() {
        server.enqueue(MockResponse().setResponseCode(413).setBody("payload too large"))
        val error = transcribeError(AsrClient(http, server.url("/v1").toString(), "sk-test"))
        assertEquals(ApiError.Kind.BAD_RESPONSE, error.kind)
        assertEquals("413 必须保留，UI 才能说「文件太大」", 413, error.httpCode)
    }

    @Test
    fun `200 但不是合法 JSON 映射成坏响应`() {
        server.enqueue(jsonResponse("not a json"))
        val error = transcribeError(AsrClient(http, server.url("/v1").toString(), "sk-test"))
        assertEquals(ApiError.Kind.BAD_RESPONSE, error.kind)
    }

    @Test
    fun `网络不通映射成网络错误`() {
        val deadServer = MockWebServer()
        deadServer.start()
        val url = deadServer.url("/v1").toString()
        deadServer.shutdown()
        val client = AsrClient(http, url, "sk-test")
        val error = transcribeError(client, wav = newWavFile(500L), durationMs = 500L)
        assertEquals(ApiError.Kind.NETWORK, error.kind)
    }

    // ---------------------------------------------------------------- 可取消（06）

    /**
 * 协程取消 → `Call.cancel` 真的发生（OkHttp `EventListener.canceled` 在取消线程回调），
 * 并以 `CancellationException` 结束（**不是** `ApiException`/「没网了」），远早于 180 s 超时。
 *
 * 做法`SocketPolicy.NO_RESPONSE` 让请求保持在途，
 * 避免时序抖动；`EventListener.canceled` 用 `AtomicBoolean`（回调在取消线程）。
 * `EventListener.canceled` 存在性：本机 okhttp-4.12.0.jar `javap okhttp3.EventListener` 实测
 *，。
 */
    @Test
    fun `协程取消会真的 cancel HTTP 调用且不映射成网络错误`() = runBlocking {
        val canceled = AtomicBoolean(false)
        val callStarted = CompletableDeferred<Unit>()
        val thrown = AtomicReference<Throwable?>(null)
        val listener = object : EventListener() {
            override fun callStart(call: Call) {
                callStarted.complete(Unit)
            }

            override fun canceled(call: Call) {
                canceled.set(true)
            }
        }
        // AsrClient 内部 `http.newBuilder` 会保留父客户端的 EventListener。
        val cancelableHttp = HttpFactory.create().newBuilder().eventListener(listener).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = AsrClient(cancelableHttp, server.url("/v1").toString(), "sk-test")

        val transcribeJob = launch(Dispatchers.IO) {
            try {
                client.transcribe(newWavFile(1_000L), 1_000L)
            } catch (t: Throwable) {
                thrown.set(t)
                // 取消异常原样上抛让协程以「已取消」收尾；其它异常只记录（别把测试作用域一起取消）。
                if (t is CancellationException) throw t
            }
        }
        try {
            withTimeout(10_000L) { callStarted.await() }
            val cancelStartNanos = System.nanoTime()
            transcribeJob.cancel()
            transcribeJob.join()
            val elapsedMs = (System.nanoTime() - cancelStartNanos) / 1_000_000L

            val error = thrown.get()
            assertTrue("取消必须以 CancellationException 结束，实际=$error", error is CancellationException)
            assertFalse("取消不得是 ApiException（否则会弹「没网了」）", error is ApiException)
            assertTrue("必须真的 Call.cancel()（EventListener.canceled）", canceled.get())
            assertTrue("取消必须立刻结束，实测 ${elapsedMs}ms（不能等 180s 超时）", elapsedMs < 5_000L)
        } finally {
            transcribeJob.cancel()
        }
    }

    // ---------------------------------------------------------------- 本地前置拒绝（不发请求）

    @Test
    fun `文件超过 8MiB 本地拒绝且不发请求`() {
        val huge = newWavFile(500L).also { file ->
            RandomAccessFile(file, "rw").use { it.setLength(AsrClient.MAX_AUDIO_BYTES + 1L) }
        }
        val client = AsrClient(http, server.url("/v1").toString(), "sk-test")
        val rejected = assertThrows(AsrRejectedException::class.java) {
            runBlocking { client.transcribe(huge, 500L) }
        }
        assertEquals(AsrRejectReason.FILE_TOO_LARGE, rejected.reason)
        assertEquals("本地拒绝必须一次请求都不发", 0, server.requestCount)
    }

    @Test
    fun `时长超过 120 秒或非法本地拒绝`() {
        val client = AsrClient(http, server.url("/v1").toString(), "sk-test")
        val tooLong = assertThrows(AsrRejectedException::class.java) {
            runBlocking { client.transcribe(newWavFile(500L), AsrClient.MAX_AUDIO_DURATION_MS + 1L) }
        }
        assertEquals(AsrRejectReason.DURATION_TOO_LONG, tooLong.reason)

        val invalid = assertThrows(AsrRejectedException::class.java) {
            runBlocking { client.transcribe(newWavFile(500L), 0L) }
        }
        assertEquals(AsrRejectReason.DURATION_INVALID, invalid.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `不是 WAV 的文件本地拒绝`() {
        val notWav = File(Files.createTempDirectory("asr-not-wav").toFile(), "voice.wav").apply {
            writeText("this is not a wav file")
        }
        tempFiles += notWav
        val client = AsrClient(http, server.url("/v1").toString(), "sk-test")
        val rejected = assertThrows(AsrRejectedException::class.java) {
            runBlocking { client.transcribe(notWav, 500L) }
        }
        assertEquals(AsrRejectReason.NOT_WAV, rejected.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `文件不存在本地拒绝`() {
        val missing = File(Files.createTempDirectory("asr-missing").toFile(), "nope.wav")
        val client = AsrClient(http, server.url("/v1").toString(), "sk-test")
        val rejected = assertThrows(AsrRejectedException::class.java) {
            runBlocking { client.transcribe(missing, 500L) }
        }
        assertEquals(AsrRejectReason.FILE_MISSING, rejected.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `空文件本地拒绝`() {
        val empty = File(Files.createTempDirectory("asr-empty").toFile(), "voice.wav").apply { createNewFile() }
        val client = AsrClient(http, server.url("/v1").toString(), "sk-test")
        val rejected = assertThrows(AsrRejectedException::class.java) {
            runBlocking { client.transcribe(empty, 500L) }
        }
        assertEquals(AsrRejectReason.FILE_EMPTY, rejected.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `没有密钥不发请求`() {
        val client = AsrClient(http, server.url("/v1").toString(), "  ")
        val rejected = assertThrows(AsrRejectedException::class.java) {
            runBlocking { client.transcribe(newWavFile(500L), 500L) }
        }
        assertEquals(AsrRejectReason.NO_KEY, rejected.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `地址无效不发请求`() {
        val client = AsrClient(http, "   ", "sk-test")
        val rejected = assertThrows(AsrRejectedException::class.java) {
            runBlocking { client.transcribe(newWavFile(500L), 500L) }
        }
        assertEquals(AsrRejectReason.BAD_BASE_URL, rejected.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `结果 toString 不包含转写文本`() {
        val result = AsrResult(text = "这是用户说的话", durationMs = 1_000L)
        val text = result.toString()
        assertFalse("toString 不能泄密转写文本", text.contains("这是用户说的话"))
        assertTrue(text.contains("textLength=7"))
    }

    // ---------------------------------------------------------------- 辅助

    private fun newWavFile(durationMs: Long): File {
        val samples = (16_000L * durationMs / 1_000L).toInt()
        val pcm = ByteArray(samples * 2)
        val wav = WavWriter.pcm16MonoToWav(pcm)
        val file = Files.createTempFile("asr-voice", ".wav").toFile()
        file.writeBytes(wav)
        tempFiles += file
        return file
    }

    private fun transcribeError(
        client: AsrClient,
        wav: File = newWavFile(500L),
        durationMs: Long = 500L,
    ): ApiError {
        val exception = assertThrows(ApiException::class.java) {
            runBlocking { client.transcribe(wav, durationMs) }
        }
        return exception.error
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
