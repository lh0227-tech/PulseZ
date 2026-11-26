package cc.pulsez.app.net

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * DeepSeek Files API 客户端离线单测：
 * - `POST /files` multipart 字段顺序必须是 `purpose` 在 `file` 之前；
 * - 官方 DeepSeek 仅标准根和 `/v1` base 选根 `/files`，代理路径、其他版本和 query 不重写；
 * - 400 / 408 / 413 / 429 / 500 / 503 / 504 映射到 [ApiError]；
 * - 协程取消能真正中断底层 OkHttp Call 并以 `CancellationException` 结束；
 * - `deleteFile` 返回可辨识结果，必须确认响应 ID 匹配且 `deleted=true`；
 * - `purposeForFile` 与 `maxBytesForPurpose` 规则校验。
 */
class FilesClientTest {

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

    @Test
    fun `uploadFile sends purpose before file and parses UploadedFile`() {
        server.enqueue(
            jsonResponse(
                """{"id":"file-api-abc123","bytes":17,"filename":"report.pdf","purpose":"text_recognition","expires_at":1893456000}""",
            ),
        )
        val file = newTempFile("report.pdf", "pdf-dummy-content")
        val client = FilesClient(http, server.url("/v1").toString(), "sk-test")

        val uploaded = runBlocking { client.uploadFile(file, "report.pdf", "text_recognition") }

        assertEquals(
            UploadedFile(
                id = "file-api-abc123",
                bytes = 17L,
                filename = "report.pdf",
                purpose = "text_recognition",
                expiresAt = 1893456000L,
            ),
            uploaded,
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/files", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))

        val rawBody = String(recorded.body.readByteArray(), Charsets.UTF_8)
        val purposeIndex = rawBody.indexOf("name=\"purpose\"")
        val fileIndex = rawBody.indexOf("name=\"file\"")
        assertTrue("multipart 必须包含 purpose 字段", purposeIndex >= 0)
        assertTrue("multipart 必须包含 file 字段", fileIndex >= 0)
        assertTrue("purpose 必须排在 file 字段之前（否则服务端 400）", purposeIndex < fileIndex)
        assertTrue(rawBody.contains("text_recognition"))
        assertTrue(rawBody.contains("filename=\"report.pdf\""))
    }

    @Test
    fun `uploadFile omits expiresAt when server returns permanent file`() {
        server.enqueue(
            jsonResponse(
                """{"id":"file-api-perm","bytes":5,"filename":"notes.txt","purpose":"user_data"}""",
            ),
        )
        val file = newTempFile("notes.txt", "hello")
        val client = FilesClient(http, server.url("/v1").toString(), "sk-test")

        val uploaded = runBlocking { client.uploadFile(file, "notes.txt", "user_data") }
        assertNull(uploaded.expiresAt)
        assertEquals("user_data", uploaded.purpose)
    }

    @Test
    fun `uploadFile retries once with v1 on 404 when base lacks v1`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(
            jsonResponse(
                """{"id":"file-api-v1","bytes":4,"filename":"a.txt","purpose":"user_data"}""",
            ),
        )
        val file = newTempFile("a.txt", "test")
        val client = FilesClient(http, server.url("/").toString(), "sk-test")

        val uploaded = runBlocking { client.uploadFile(file, "a.txt", "user_data") }
        assertEquals("file-api-v1", uploaded.id)
        assertEquals("/files", server.takeRequest().path)
        assertEquals("/v1/files", server.takeRequest().path)
    }

    @Test
    fun `official Files API endpoint stays at root for root and v1 chat bases`() {
        assertEquals(
            "https://api.deepseek.com/files",
            FilesClient.filesEndpointFor("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/files",
            FilesClient.filesEndpointFor("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/files",
            FilesClient.filesEndpointFor("https://api.deepseek.com/v1/"),
        )
    }

    @Test
    fun `official DeepSeek custom paths and query remain unchanged`() {
        assertEquals(
            "https://api.deepseek.com/proxy/files",
            FilesClient.filesEndpointFor("https://api.deepseek.com/proxy"),
        )
        assertEquals(
            "https://api.deepseek.com/v2/files",
            FilesClient.filesEndpointFor("https://api.deepseek.com/v2"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/files?api-version=1",
            FilesClient.filesEndpointFor("https://api.deepseek.com/v1?api-version=1"),
        )
        assertEquals(
            "https://api.deepseek.com/files?api-version=1",
            FilesClient.filesEndpointFor("https://api.deepseek.com?api-version=1"),
        )
    }

    @Test
    fun `only exact official DeepSeek origin uses root endpoint selection`() {
        assertEquals(
            "https://proxy.example/v1/files",
            FilesClient.filesEndpointFor("https://proxy.example/v1"),
        )
        assertEquals(
            "https://api.deepseek.com.evil/v1/files",
            FilesClient.filesEndpointFor("https://api.deepseek.com.evil/v1"),
        )
        assertEquals(
            "http://api.deepseek.com/v1/files",
            FilesClient.filesEndpointFor("http://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com:8443/v1/files",
            FilesClient.filesEndpointFor("https://api.deepseek.com:8443/v1"),
        )
    }

    @Test
    fun `uploadFile maps 400 408 413 429 500 503 504 to ApiException`() {
        val cases = listOf(
            400 to ApiError.Kind.BAD_RESPONSE,
            408 to ApiError.Kind.NETWORK,
            413 to ApiError.Kind.BAD_RESPONSE,
            429 to ApiError.Kind.QUOTA,
            500 to ApiError.Kind.SERVER,
            503 to ApiError.Kind.SERVER,
            504 to ApiError.Kind.SERVER,
        )
        val file = newTempFile("doc.pdf", "123")
        cases.forEach { (code, expectedKind) ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"message":"err $code"}}"""))
            val client = FilesClient(http, server.url("/v1").toString(), "sk-test")
            val ex = assertThrows(ApiException::class.java) {
                runBlocking { client.uploadFile(file, "doc.pdf", "text_recognition") }
            }
            assertEquals("HTTP $code 映射错误", expectedKind, ex.error.kind)
            assertEquals(code, ex.error.httpCode)
        }
    }

    @Test
    fun `upload client allows the official ten minute non-document window`() {
        val filesClient = FilesClient(http, server.url("/v1").toString(), "sk-test")
        val field = FilesClient::class.java.getDeclaredField("client").apply { isAccessible = true }
        val configuredHttp = field.get(filesClient) as OkHttpClient
        val expectedMillis = (10 * 60 * 1000)

        assertEquals(expectedMillis, configuredHttp.writeTimeoutMillis)
        assertEquals(expectedMillis, configuredHttp.readTimeoutMillis)
        assertEquals(expectedMillis, configuredHttp.callTimeoutMillis)
        assertFalse(configuredHttp.retryOnConnectionFailure)
    }

    @Test
    fun `upload is not resent after server reads the request and disconnects`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val file = newTempFile("a.txt", "content")
        val client = FilesClient(http, server.url("/").toString(), "sk-test")

        assertThrows(ApiException::class.java) {
            runBlocking { client.uploadFile(file, "a.txt", "user_data") }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `uploadFile cancellation cancels underlying OkHttp call`() = runBlocking {
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
        val cancelableHttp = http.newBuilder().eventListener(listener).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val file = newTempFile("big.pdf", "content")
        val client = FilesClient(cancelableHttp, server.url("/v1").toString(), "sk-test")

        val job = launch(Dispatchers.IO) {
            try {
                client.uploadFile(file, "big.pdf", "text_recognition")
            } catch (t: Throwable) {
                thrown.set(t)
                if (t is CancellationException) throw t
            }
        }
        try {
            withTimeout(10_000L) { callStarted.await() }
            job.cancel()
            job.join()

            val err = thrown.get()
            assertTrue("取消必须抛出 CancellationException，实际=$err", err is CancellationException)
            assertFalse("取消不得包装成 ApiException", err is ApiException)
            assertTrue("必须调用 Call.cancel()", canceled.get())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `deleteFile confirms documented response and returns HTTP failures`() {
        server.enqueue(jsonResponse("""{"id":"file-api-1","object":"file","deleted":true}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))

        val client = FilesClient(http, server.url("/v1").toString(), "sk-test")
        val deleted = runBlocking { client.deleteFile("file-api-1") }
        val failed = runBlocking { client.deleteFile("file-api-missing") }
        assertEquals(DeleteFileResult.Deleted("file-api-1"), deleted)
        assertEquals(
            DeleteFileResult.Failed(DeleteFileResult.FailureReason.HTTP_ERROR, 500),
            failed,
        )

        val first = server.takeRequest()
        assertEquals("DELETE", first.method)
        assertEquals("/v1/files/file-api-1", first.path)
        assertEquals("Bearer sk-test", first.getHeader("Authorization"))

        val second = server.takeRequest()
        assertEquals("DELETE", second.method)
        assertEquals("/v1/files/file-api-missing", second.path)
    }

    @Test
    fun `deleteFile does not treat 404 or unconfirmed success as deleted`() {
        val client = FilesClient(http, server.url("/").toString(), "sk-test")
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val missing = runBlocking { client.deleteFile("file-api-missing") }
        assertEquals(
            DeleteFileResult.Failed(DeleteFileResult.FailureReason.HTTP_ERROR, 404),
            missing,
        )
        assertEquals("/files/file-api-missing", server.takeRequest().path)
        assertEquals("/v1/files/file-api-missing", server.takeRequest().path)

        val unconfirmedCases = listOf(
            200 to """{"id":"file-api-1","deleted":false}""",
            200 to """{"id":"other-file","deleted":true}""",
            204 to "",
        )
        unconfirmedCases.forEach { (code, body) ->
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            val result = runBlocking { client.deleteFile("file-api-1") }
            assertEquals(
                DeleteFileResult.Failed(DeleteFileResult.FailureReason.UNCONFIRMED_SUCCESS, code),
                result,
            )
            server.takeRequest()
        }
    }

    @Test
    fun `deleteFile cancellation cancels the underlying OkHttp call`() = runBlocking {
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
        val cancelableHttp = http.newBuilder().eventListener(listener).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = FilesClient(cancelableHttp, server.url("/v1").toString(), "sk-test")

        val job = launch(Dispatchers.IO) {
            try {
                client.deleteFile("file-api-cancel")
            } catch (t: Throwable) {
                thrown.set(t)
                if (t is CancellationException) throw t
            }
        }
        try {
            withTimeout(10_000L) { callStarted.await() }
            job.cancel()
            job.join()

            val err = thrown.get()
            assertTrue("取消必须抛出 CancellationException，实际=$err", err is CancellationException)
            assertFalse("取消不得包装成 ApiException", err is ApiException)
            assertTrue("必须调用 Call.cancel()", canceled.get())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `purposeForFile and per-file limits follow official formats and sizes`() {
        listOf("a.pdf", "b.DOC", "c.docx", "d.ppt", "e.pptx").forEach { name ->
            val purpose = FilesClient.purposeForFile(name, null)
            assertEquals("文档 $name 应走 text_recognition", "text_recognition", purpose)
            assertEquals(64L * 1024L * 1024L, FilesClient.maxBytesForPurpose(purpose))
            assertEquals(100L * 1024L * 1024L, FilesClient.maxBytesForFile(name))
        }

        assertEquals(
            "MIME 为 application/pdf 时无扩展名也应走 text_recognition",
            "text_recognition",
            FilesClient.purposeForFile("document", "application/pdf"),
        )
        assertEquals(100L * 1024L * 1024L, FilesClient.maxBytesForFile("document", "application/pdf"))

        listOf("readme.txt", "note.md", "data.csv", "cfg.json", "sheet.xlsx", "book.epub", "book.mobi", "pic.png")
            .forEach { name ->
                val purpose = FilesClient.purposeForFile(name, null)
                assertEquals("文件 $name 应走 user_data", "user_data", purpose)
                assertEquals(32L * 1024L * 1024L, FilesClient.maxBytesForPurpose(purpose))
                val expectedLimit = if (name.endsWith(".png")) 64L else 32L
                assertEquals(expectedLimit * 1024L * 1024L, FilesClient.maxBytesForFile(name))
            }

        assertEquals(64L * 1024L * 1024L, FilesClient.maxBytesForFile("image", "image/jpeg"))
        assertEquals(32L * 1024L * 1024L, FilesClient.maxBytesForFile("conflict.pdf", "image/png"))
        assertEquals("text_recognition", FilesClient.purposeForFile("scan.tiff", null))
        assertEquals("text_recognition", FilesClient.purposeForFile("scan", "image/bmp"))
        assertEquals(64L * 1024L * 1024L, FilesClient.maxBytesForFile("scan.tiff", "image/tiff"))
    }

    private fun newTempFile(name: String, content: String): File {
        val dir = Files.createTempDirectory("files-client-test").toFile()
        val file = File(dir, name).apply { writeText(content) }
        tempFiles += file
        tempFiles += dir
        return file
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
