package cc.pulsez.app.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmbeddingClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: EmbeddingClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = EmbeddingClient(
            http = HttpFactory.create(),
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
    fun `batches of 16 and preserves order`() {
        server.enqueue(embeddingResponse(16, batchOffset = 0))
        server.enqueue(embeddingResponse(4, batchOffset = 100))

        val texts = (0 until 20).map { "text-$it" }
        val vectors = runBlocking { client.embed(texts, "BAAI/bge-m3") }

        assertEquals(20, vectors.size)
        assertEquals(2, server.requestCount)
        // 第一批 index 0..15；第二批 index 0..3，值和向量长度都应对应。
        assertEquals(2f, vectors[5].size.toFloat())
        assertEquals(5f, vectors[5][0])
        assertEquals(100f, vectors[16][0])
        assertEquals(103f, vectors[19][0])

        val request = server.takeRequest()
        assertEquals("/v1/embeddings", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()) as JsonObject
        assertEquals(16, (body["input"] as JsonArray).size)
        assertEquals("BAAI/bge-m3", body["model"]?.jsonPrimitive?.content)
        assertEquals("float", body["encoding_format"]?.jsonPrimitive?.content)
    }

    @Test
    fun `overlong text is truncated to 3000 chars`() {
        server.enqueue(embeddingResponse(1, batchOffset = 0))

        runBlocking { client.embed(listOf("字".repeat(5_000)), "bge-m3") }

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
        val first = (body["input"] as JsonArray)[0].jsonPrimitive.content
        assertEquals(3_000, first.length)
    }

    @Test
    fun `uses provider dimension when every vector in batch agrees`() {
        server.enqueue(jsonResponse("""{"data":[{"index":0,"embedding":[0.1,0.2,0.3]},{"index":1,"embedding":[0.4,0.5,0.6]}]}"""))

        val vectors = runBlocking { client.embed(listOf("first", "second"), "bge-m3") }

        assertEquals(2, vectors.size)
        assertEquals(3, vectors[0].size)
        assertEquals(3, vectors[1].size)
        assertEquals(0.1f, vectors[0][0])
        assertEquals(0.6f, vectors[1][2])
    }

    @Test
    fun `rejects malformed and non-finite components without exposing response body`() {
        val privateMarker = "private-response-marker"
        val responses = listOf(
            """{"data":[{"index":0,"embedding":[1.0,"$privateMarker"]}]}""",
            """{"data":[{"index":0,"embedding":[1.0,1e1000]}]}""",
        )

        responses.forEach { body ->
            server.enqueue(jsonResponse(body))
            val error = runCatching { runBlocking { client.embed(listOf("private input"), "bge-m3") } }
                .exceptionOrNull()

            assertTrue(error is ApiException)
            val apiError = error as ApiException
            assertEquals(ApiError.Kind.BAD_RESPONSE, apiError.error.kind)
            assertFalse(apiError.message.orEmpty().contains(privateMarker))
            assertFalse(apiError.toString().contains(privateMarker))
        }
        assertEquals("非法响应不触发客户端自动重试", responses.size, server.requestCount)
    }

    @Test
    fun `rejects all-zero and inconsistent batch vectors`() {
        server.enqueue(jsonResponse("""{"data":[{"index":0,"embedding":[0.0,0.0]}]}"""))
        server.enqueue(jsonResponse("""{"data":[{"index":0,"embedding":[0.1,0.2]},{"index":1,"embedding":[0.3,0.4,0.5]}]}"""))

        listOf(listOf("one"), listOf("one", "two")).forEach { texts ->
            val error = runCatching { runBlocking { client.embed(texts, "bge-m3") } }.exceptionOrNull()
            assertTrue(error is ApiException)
            assertEquals(ApiError.Kind.BAD_RESPONSE, (error as ApiException).error.kind)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `429 is retried then succeeds`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        server.enqueue(embeddingResponse(1, batchOffset = 0))

        val vectors = runBlocking { client.embed(listOf("一句话"), "bge-m3") }

        assertEquals(1, vectors.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `401 throws api exception auth`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))

        val error = runCatching { runBlocking { client.embed(listOf("一句话"), "bge-m3") } }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertEquals(ApiError.Kind.AUTH, (error as ApiException).error.kind)
    }

    @Test
    fun `empty input makes no request`() {
        val vectors = runBlocking { client.embed(emptyList(), "bge-m3") }

        assertTrue(vectors.isEmpty())
        assertEquals(0, server.requestCount)
    }

    private fun embeddingResponse(count: Int, batchOffset: Int): MockResponse {
        val data = (0 until count).joinToString(",") { index ->
            """{"index":$index,"embedding":[${index + batchOffset}.0,1.0]}"""
        }
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"object":"list","data":[$data]}""")
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
