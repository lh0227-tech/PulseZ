package cc.pulsez.app.net

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * LlmClient 流式行为。普通场景用 MockWebServer；RawSseServer 用于「不发 body / 首帧后静默」。
 * 中途断连由 MockWebServer 的 SocketPolicy 表达。
 */
class LlmClientStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

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

    // ------------------------------------------------------------------ 正常流

    @Test
    fun `normal stream emits deltas and finished with usage`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"你"}}]}"""),
                frame(
                    """{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}],""" +
                        """"usage":{"prompt_tokens":12,"completion_tokens":2}}""",
                ),
                doneFrame(),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }

        assertEquals(
            listOf(
                LlmEvent.Delta("你"),
                LlmEvent.Delta("好"),
                LlmEvent.Finished(Usage(promptTokens = 12, completionTokens = 2), truncated = false),
            ),
            events,
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"stream_options\":{\"include_usage\":true}"))
        assertTrue(body.contains("\"model\":\"deepseek-flash\""))
    }

    @Test
    fun `reasoning content is emitted as reasoning not delta`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"reasoning_content":"想一想"}}]}"""),
                frame("""{"choices":[{"delta":{"content":"答案"},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }

        assertEquals(LlmEvent.Reasoning("想一想"), events[0])
        assertEquals(LlmEvent.Delta("答案"), events[1])
        assertTrue(events.last() is LlmEvent.Finished)
    }

    @Test
    fun `reasoning deltas accumulate separately from content`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"reasoning_content":"先想"}}]}"""),
                frame("""{"choices":[{"delta":{"reasoning_content":"一下"}}]}"""),
                frame("""{"choices":[{"delta":{"content":"正文"},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }

        // 思考增量与正文严格分开：Reasoning 不进 Delta，Delta 里也不带思考。
        assertEquals(
            listOf(LlmEvent.Reasoning("先想"), LlmEvent.Reasoning("一下"), LlmEvent.Delta("正文")),
            events.dropLast(1),
        )
        assertTrue(events.last() is LlmEvent.Finished)
    }

    // ------------------------------------------------------------------ 联网搜索引用

    @Test
    fun `url citations in final message become citations on finished`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"答案"}}]}"""),
                frame(
                    """{"choices":[{"message":{"annotations":[""" +
                        """{"type":"url_citation","url_citation":{"url":"https://example.com/a","title":"甲","start_index":0,"end_index":2}},""" +
                        """{"type":"url_citation","url_citation":{"url":"https://example.com/b","title":"乙"}},""" +
                        // Keep citations for the same URL at different positions; de-duplicate only identical (url, start_index, end_index) entries.
                        """{"type":"url_citation","url_citation":{"url":"https://example.com/a","title":"甲（重复同位置）","start_index":0,"end_index":2}},""" +
                        """{"type":"url_citation","url_citation":{"url":"https://example.com/a","title":"甲（第二处）","start_index":6,"end_index":9}},""" +
                        """{"type":"url_citation","url_citation":{"url":"https://example.com/b","title":"乙（无跨度重复）"}},""" +
                        // 缺 url、未知类型：静默跳过，不能让整轮失败。
                        """{"type":"url_citation","url_citation":{"title":"缺地址"}},""" +
                        """{"type":"other","url_citation":{"url":"https://example.com/c"}}""" +
                        """]},"finish_reason":"stop"}]}""",
                ),
                doneFrame(),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }

        assertEquals(LlmEvent.Delta("答案"), events.first())
        val finished = events.last() as LlmEvent.Finished
        assertEquals(
            listOf(
                Citation("https://example.com/a", "甲", startIndex = 0, endIndex = 2),
                Citation("https://example.com/b", "乙"),
                Citation("https://example.com/a", "甲（第二处）", startIndex = 6, endIndex = 9),
            ),
            finished.citations,
        )
    }

    @Test
    fun `url citations in delta are also accepted`() {
        // 兼容服务端把 annotations 放在 delta 里的形态（官方流式文档只说"最后一帧下发"）。
        server.enqueue(
            sseResponse(
                frame(
                    """{"choices":[{"delta":{"content":"答案","annotations":[""" +
                        """{"type":"url_citation","url_citation":{"url":"https://example.com/d","title":"丁"}}""" +
                        """]},"finish_reason":"stop"}]}""",
                ),
                doneFrame(),
            ),
        )

        val finished = runBlocking { client().stream(request()).toList().last() } as LlmEvent.Finished
        assertEquals(listOf(Citation("https://example.com/d", "丁")), finished.citations)
    }

    @Test
    fun `no annotations means empty citations`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )

        val finished = runBlocking { client().stream(request()).toList().last() } as LlmEvent.Finished
        assertTrue(finished.citations.isEmpty())
    }

    @Test
    fun `finish reason without done marker is not truncated`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}"""),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }
        val finished = events.last() as LlmEvent.Finished
        assertTrue(!finished.truncated)
    }

    @Test
    fun `done marker without finish reason is not truncated`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"好"}}]}"""),
                doneFrame(),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }
        val finished = events.last() as LlmEvent.Finished
        assertTrue(!finished.truncated)
    }

    @Test
    fun `missing done and finish reason is truncated`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"说到一半"}}]}"""),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }
        assertEquals(LlmEvent.Delta("说到一半"), events.first())
        val finished = events.last() as LlmEvent.Finished
        assertTrue(finished.truncated)
    }

    @Test
    fun `usage is merged across frames without overwriting with nulls`() {
        server.enqueue(
            sseResponse(
                frame(
                    """{"choices":[{"delta":{"content":"a"}}],""" +
                        """"usage":{"prompt_tokens":10,"completion_tokens":1}}""",
                ),
                frame(
                    """{"choices":[{"delta":{"content":"b"}}],""" +
                        """"usage":{"prompt_tokens":10,"completion_tokens":2,""" +
                        """"prompt_cache_hit_tokens":8,"prompt_cache_miss_tokens":2}}""",
                ),
                frame("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }
        val finished = events.last() as LlmEvent.Finished
        assertEquals(
            Usage(promptTokens = 10, completionTokens = 2, cacheHitTokens = 8, cacheMissTokens = 2),
            finished.usage,
        )
    }

    @Test
    fun `usage in trailing frame after finish reason is drained`() {
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}"""),
                frame("""{"choices":[],"usage":{"prompt_tokens":30,"completion_tokens":5}}"""),
                doneFrame(),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }
        val finished = events.last() as LlmEvent.Finished
        assertEquals(Usage(promptTokens = 30, completionTokens = 5), finished.usage)
        assertTrue(!finished.truncated)
    }

    @Test
    fun `base url without v1 retries once and remembers`() {
        val bareClient = timeoutClient(server.url("/").toString())

        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )
        val firstEvents = runBlocking { bareClient.stream(request()).toList() }
        assertTrue(firstEvents.last() is LlmEvent.Finished)
        assertEquals("/chat/completions", server.takeRequest().path)
        assertEquals("/v1/chat/completions", server.takeRequest().path)

        // 成功后记住 /v1：下一次不再走 404。
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"又"},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )
        runBlocking { bareClient.stream(request()).toList() }
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun `resolved v1 is remembered across client instances`() {
        val bareBase = server.url("/").toString()
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )
        runBlocking { timeoutClient(bareBase).stream(request()).toList() }
        assertEquals("/chat/completions", server.takeRequest().path)
        assertEquals("/v1/chat/completions", server.takeRequest().path)

        // 新实例（AppContainer 每次发消息都会新建）也直接命中 /v1，说明进程级缓存生效。
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"又"},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )
        runBlocking { timeoutClient(bareBase).stream(request()).toList() }
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun `blank api key fails fast without network`() {
        val noKeyClient = LlmClient(http, server.url("/v1").toString(), "   ")
        val events = runBlocking { noKeyClient.stream(request()).toList() }
        assertEquals(ApiError.Kind.NO_KEY, (events.single() as LlmEvent.Failed).error.kind)
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------------ 错误分类

    @Test
    fun `http 401 becomes auth failure`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val events = runBlocking { client().stream(request()).toList() }
        val failed = events.single() as LlmEvent.Failed
        assertEquals(ApiError.Kind.AUTH, failed.error.kind)
    }

    @Test
    fun `http 429 becomes quota failure`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        val events = runBlocking { client().stream(request()).toList() }
        val failed = events.single() as LlmEvent.Failed
        assertEquals(ApiError.Kind.QUOTA, failed.error.kind)
    }

    @Test
    fun `non sse json error body on http 200 becomes bad response`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"upstream overloaded"}}"""),
        )
        val events = runBlocking { client().stream(request()).toList() }
        val failed = events.single() as LlmEvent.Failed
        assertEquals(ApiError.Kind.BAD_RESPONSE, failed.error.kind)
        assertTrue(failed.error.raw.orEmpty().contains("upstream overloaded"))
    }

    @Test
    fun `event error frame becomes bad response`() {
        server.enqueue(
            sseResponse(
                "event: error\ndata: upstream exploded\n\n",
            ),
        )
        val events = runBlocking { client().stream(request()).toList() }
        val failed = events.single() as LlmEvent.Failed
        assertEquals(ApiError.Kind.BAD_RESPONSE, failed.error.kind)
        assertTrue(failed.error.raw.orEmpty().contains("upstream exploded"))
    }

    @Test
    fun `stream_options 400 is retried once without the field`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"unknown parameter stream_options"}"""))
        server.enqueue(
            sseResponse(
                frame("""{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}"""),
                doneFrame(),
            ),
        )

        val events = runBlocking { client().stream(request()).toList() }

        assertTrue(events.last() is LlmEvent.Finished)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("stream_options"))
        assertTrue(!second.contains("stream_options"))
    }

    // ------------------------------------------------------------------ 超时 / 断线

    @Test
    fun `mid stream disconnect emits stream broken`() {
        val firstFrame = frame("""{"choices":[{"delta":{"content":"在"}}]}""")
        val secondFrame = frame("""{"choices":[{"delta":{"content":"${"长".repeat(256)}"}}]}""")
        val responseBody = firstFrame + secondFrame
        val responseBodyBytes = responseBody.toByteArray(Charsets.UTF_8)
        val firstFrameBytes = firstFrame.toByteArray(Charsets.UTF_8).size
        val disconnectAfterBytes = responseBodyBytes.size / 2

        assertTrue(
            "断开点必须在完整首帧之后",
            disconnectAfterBytes > firstFrameBytes,
        )
        assertTrue(
            "断开点必须早于响应体结束",
            disconnectAfterBytes < responseBodyBytes.size,
        )

        // 官方源码定义该策略为写完响应体一半后断开连接：
        // https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/mockwebserver/src/main/kotlin/okhttp3/mockwebserver/SocketPolicy.kt
        server.enqueue(
            sseResponse(firstFrame, secondFrame)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val events = runBlocking {
            withTimeout(5_000) { client().stream(request()).toList() }
        }

        assertEquals("/v1/chat/completions", server.takeRequest().path)
        assertEquals("首帧必须先于断流错误到达", LlmEvent.Delta("在"), events[0])
        assertEquals("断流后不应再产生其他流事件", 2, events.size)
        val failed = events[1] as LlmEvent.Failed
        assertEquals(ApiError.Kind.STREAM_BROKEN, failed.error.kind)
    }

    @Test
    fun `stalled stream after first event hits heartbeat and emits stream broken`() {
        val raw = RawSseServer { socket ->
            socket.sendSseHeaders()
            socket.writeSseChunk(frame("""{"choices":[{"delta":{"content":"在"}}]}"""))
            Thread.sleep(3_000) // 首帧之后静默：只有心跳能发现
        }
        try {
            val client = timeoutClient(raw.baseUrl, firstByteMs = 5_000, heartbeatMs = 200)
            val events = runBlocking {
                withTimeout(5_000) { client.stream(request()).toList() }
            }
            assertEquals(LlmEvent.Delta("在"), events.first())
            val failed = events.last() as LlmEvent.Failed
            assertEquals(ApiError.Kind.STREAM_BROKEN, failed.error.kind)
        } finally {
            raw.close()
        }
    }

    @Test
    fun `first byte timeout emits network failure`() {
        val raw = RawSseServer { socket ->
            socket.sendSseHeaders()
            Thread.sleep(3_000) // 头到了，但一个 event 都没有
        }
        try {
            val client = timeoutClient(raw.baseUrl, firstByteMs = 250, heartbeatMs = 2_000)
            val events = runBlocking {
                withTimeout(5_000) { client.stream(request()).toList() }
            }
            val failed = events.single() as LlmEvent.Failed
            assertEquals(ApiError.Kind.NETWORK, failed.error.kind)
        } finally {
            raw.close()
        }
    }

    /**
 * 关键回归：首字阶段心跳**没有**启动。
 *
 * 注入 heartbeat=200ms、firstByte=1500ms，服务端只回头部、不发任何 event。
 * 如果实现错误地把心跳从请求开始就启动，200ms 后就会 Failed；
 * 正确实现会一直等到首字超时（1500ms），所以 600ms 时应当一个事件都没有。
 */
    @Test
    fun `heartbeat is not armed during first byte phase`() {
        val raw = RawSseServer { socket ->
            socket.sendSseHeaders()
            Thread.sleep(2_000)
        }
        try {
            val client = timeoutClient(raw.baseUrl, firstByteMs = 1_500, heartbeatMs = 200)
            val events = mutableListOf<LlmEvent>()
            val completed = runBlocking {
                withTimeoutOrNull(600) {
                    client.stream(request()).collect { events += it }
                }
            }
            assertNull("600ms 内流不应结束（心跳未启动、首字未超时）", completed)
            assertTrue("首字阶段不应产生任何事件，实际=$events", events.isEmpty())
        } finally {
            raw.close()
        }
    }

    /**
 * 关键回归（ 修复项）：**只有 keep-alive、没有正文时，首字超时不得触发**。
 *
 * 深度思考 / 联网搜索会出现分钟级无正文期，DeepSeek 官方靠 `: keep-alive` 注释行保活。
 * 旧实现把注释行直接 continue，不刷新首字计时，注入 300ms 首字超时就会在第一个 keep-alive
 * 之后被掐断；修复后每一行字节都续命。
 */
    @Test
    fun `keep alive comments keep first byte timer alive`() {
        val raw = RawSseServer { socket ->
            socket.sendSseHeaders()
            // 首字阶段只发注释行（没有任何 data），持续 1.2s，远超注入的首字超时。
            repeat(12) {
                socket.writeSseChunk(": keep-alive\n\n")
                Thread.sleep(100)
            }
            socket.writeSseChunk(
                frame("""{"choices":[{"delta":{"content":"终于"},"finish_reason":"stop"}]}"""),
            )
            socket.writeSseChunk(doneFrame())
        }
        try {
            val client = timeoutClient(raw.baseUrl, firstByteMs = 1_000, heartbeatMs = 500)
            val events = runBlocking {
                withTimeout(10_000) { client.stream(request()).toList() }
            }
            assertEquals(LlmEvent.Delta("终于"), events.first())
            assertTrue("keep-alive 期间不应被判首字超时，实际=$events", events.last() is LlmEvent.Finished)
        } finally {
            raw.close()
        }
    }

    /**
 * keep-alive 同样要给**流中心跳**续命：首帧之后长时间只有注释行（思考/搜索中途），
 * 旧实现 200ms 心跳就会判 StreamBroken。
 */
    @Test
    fun `keep alive comments keep heartbeat alive after first event`() {
        val raw = RawSseServer { socket ->
            socket.sendSseHeaders()
            socket.writeSseChunk(frame("""{"choices":[{"delta":{"content":"想"}}]}"""))
            repeat(10) {
                Thread.sleep(100)
                socket.writeSseChunk(": keep-alive\n\n")
            }
            socket.writeSseChunk(
                frame("""{"choices":[{"delta":{"content":"好了"},"finish_reason":"stop"}]}"""),
            )
            socket.writeSseChunk(doneFrame())
        }
        try {
            val client = timeoutClient(raw.baseUrl, firstByteMs = 5_000, heartbeatMs = 500)
            val events = runBlocking {
                withTimeout(10_000) { client.stream(request()).toList() }
            }
            assertEquals(LlmEvent.Delta("想"), events.first())
            assertEquals(LlmEvent.Delta("好了"), events[1])
            assertTrue("keep-alive 期间不应被判心跳超时，实际=$events", events.last() is LlmEvent.Finished)
        } finally {
            raw.close()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun client(
        firstByteMs: Long = 3_000,
        heartbeatMs: Long = 300,
    ): LlmClient = LlmClient(
        http = http,
        baseUrl = server.url("/v1").toString(),
        apiKey = "sk-test",
        firstByteTimeoutMs = firstByteMs,
        heartbeatTimeoutMs = heartbeatMs,
        firstByteTimeoutCapMs = 10_000,
        firstByteScaleBaseMs = firstByteMs,
        retryDelayMs = 10,
    )

    private fun timeoutClient(
        baseUrl: String,
        firstByteMs: Long = 3_000,
        heartbeatMs: Long = 3_000,
    ): LlmClient = LlmClient(
        http = http,
        baseUrl = baseUrl,
        apiKey = "sk-test",
        firstByteTimeoutMs = firstByteMs,
        heartbeatTimeoutMs = heartbeatMs,
        firstByteTimeoutCapMs = 10_000,
        firstByteScaleBaseMs = firstByteMs,
        retryDelayMs = 10,
    )

    private fun request() = ChatRequest(
        model = "deepseek-flash",
        messages = listOf(ChatMessage(role = "user", content = "在吗")),
    )

    private fun frame(json: String) = "data: $json\n\n"

    private fun doneFrame() = "data: [DONE]\n\n"

    private fun sseResponse(vararg chunks: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString(""))

    // ------------------------------------------------------------------ raw sse server

    /** 直写 socket 的极简 SSE 服务端，用来表达 MockWebServer 表达不了的节奏。 */
    private class RawSseServer(handler: (Socket) -> Unit) : Closeable {

        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

        @Volatile
        private var accepted: Socket? = null

        private val thread = Thread {
            try {
                serverSocket.accept().use { socket ->
                    accepted = socket
                    handler(socket)
                }
            } catch (_: Throwable) {
                // 测试结束主动关 socket 会打断 handler，忽略。
            }
        }.apply {
            isDaemon = true
            start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${serverSocket.localPort}/v1"

        override fun close() {
            runCatching { accepted?.close() }
            runCatching { serverSocket.close() }
            thread.join(1_000)
        }
    }

    private fun Socket.sendSseHeaders() {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Connection: close\r\n\r\n"
        getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            flush()
        }
    }

    private fun Socket.writeSseChunk(payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val out = getOutputStream()
        out.write("${Integer.toHexString(bytes.size)}\r\n".toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }
}
