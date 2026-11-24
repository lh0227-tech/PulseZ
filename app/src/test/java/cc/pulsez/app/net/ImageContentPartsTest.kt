package cc.pulsez.app.net

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * 识图：content parts 的请求体。
 *
 * 用 MockWebServer 抓**真实发出去**的请求体，验证 LlmClient → Payloads 整条链路：
 * - parts 非空 → `content` 是数组：text + `image_url` 的 base64 data URL，带 `detail:"low"`；
 * - parts 为空 → 旧行为字节不变（`content` 仍是字符串）——第三方端点零影响；
 * - 纯图消息（parts 只有 image）→ content 数组里没有文字块，不夹带落库占位「[图片]」；
 * - assistant 消息永远不带 parts（官方限制：assistant 放图会 400）；
 * - token 估算把每张图按 1024 计入（标「估算」）。
 *
 * ：text 块现在由 `ChatEngine.buildOutgoingMessages` 在生产链路里真正生成
 * （`OutgoingMessagesTest` 从组装结果一路断言到请求体）；本类继续锁 Payloads 层的序列化形态
 * （此前本类手工构造 Text、生产却从不生成，掩盖了阻断缺陷）。
 *
 * 官方格式/；https://api-docs.deepseek.com/guides/vision
 */
class ImageContentPartsTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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

    @Test
    fun `parts 非空时 content 序列化为 text 加 image_url 数组`() {
        val body = sendAndCapture(
            listOf(
                ChatMessage(
                    role = "user",
                    content = "看看这个",
                    // 生产形态：ChatEngine 把正文作为首个 Text 前置，再接 Image。
                    parts = listOf(
                        ContentPart.Text("看看这个"),
                        ContentPart.Image("BASE64_JPEG"),
                    ),
                ),
            ),
        )

        val message = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        // 数组形态：不再同时携带字符串 content（正文只能出现一次）。
        val content = message["content"] as JsonArray
        assertEquals(2, content.size)

        val textPart = content[0].jsonObject
        assertEquals("text", textPart["type"]!!.jsonPrimitive.content)
        assertEquals("看看这个", textPart["text"]!!.jsonPrimitive.content)

        val imagePart = content[1].jsonObject
        assertEquals("image_url", imagePart["type"]!!.jsonPrimitive.content)
        val imageUrl = imagePart["image_url"]!!.jsonObject
        assertEquals(
            "data:image/jpeg;base64,BASE64_JPEG",
            imageUrl["url"]!!.jsonPrimitive.content,
        )
        // 默认 low：官方会缩到 512×512，更快更省。
        assertEquals("low", imageUrl["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `纯图 parts 只有 image 时不夹带占位文字`() {
        // 纯图消息的正文是落库占位（导出/降级用）；它**不应**出现在请求里
        // （纯图只发图块）。
        val body = sendAndCapture(
            listOf(
                ChatMessage(
                    role = "user",
                    content = cc.pulsez.app.core.ChatEngine.IMAGE_PLACEHOLDER,
                    parts = listOf(ContentPart.Image("BASE64_ONLY_IMAGE")),
                ),
            ),
        )

        val message = body["messages"]!!.jsonArray[0].jsonObject
        val content = message["content"] as JsonArray
        assertEquals("纯图只有一个 image_url 块", 1, content.size)
        assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        // 整个请求体不能出现「[图片]」占位文字。
        assertFalse(
            "请求体不应包含落库占位",
            body.toString().contains(cc.pulsez.app.core.ChatEngine.IMAGE_PLACEHOLDER),
        )
    }

    @Test
    fun `parts 非空且正文为空时也只有 image`() {
        // 空正文 + 图块：仍按数组形态、只有 image_url；不能因为 content 为空就退回空字符串。
        val body = sendAndCapture(
            listOf(
                ChatMessage(
                    role = "user",
                    content = "",
                    parts = listOf(ContentPart.Image("BASE64_EMPTY_TEXT")),
                ),
            ),
        )

        val content = body["messages"]!!.jsonArray[0].jsonObject["content"] as JsonArray
        assertEquals(1, content.size)
        assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `无 parts 时请求体保持旧行为字符串 content`() {
        val body = sendAndCapture(listOf(ChatMessage(role = "user", content = "在吗")))

        val message = body["messages"]!!.jsonArray[0].jsonObject
        // 旧行为：content 是字符串，不是数组（字节语义与 一致）。
        assertTrue("旧行为 content 必须是字符串，实际=$message", message["content"] !is JsonArray)
        assertEquals("在吗", message["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant 消息不带 parts 时仍走字符串`() {
        val body = sendAndCapture(
            listOf(
                ChatMessage(role = "user", content = "你好"),
                ChatMessage(role = "assistant", content = "在的"),
            ),
        )

        val assistant = body["messages"]!!.jsonArray[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        assertTrue(assistant["content"] !is JsonArray)
    }

    @Test
    fun `token 估算把每张图按 1024 计入`() {
        val textOnly = LlmClient.estimateMessageTokens(ChatMessage(role = "user", content = "看看"))
        val withImage = LlmClient.estimateMessageTokens(
            ChatMessage(
                role = "user",
                content = "看看",
                parts = listOf(ContentPart.Text("看看"), ContentPart.Image("X")),
            ),
        )
        assertEquals(
            "每张图按 1024 token 保守估算",
            textOnly + 1024,
            withImage,
        )
        assertEquals(2048, cc.pulsez.app.mem.TokenEstimator.estimateImages(2))
        assertEquals(0, cc.pulsez.app.mem.TokenEstimator.estimateImages(0))
    }

    private fun sendAndCapture(messages: List<ChatMessage>): JsonObject {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val client = LlmClient(
            http = http,
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
            firstByteTimeoutMs = 3_000,
            heartbeatTimeoutMs = 3_000,
            firstByteTimeoutCapMs = 10_000,
            firstByteScaleBaseMs = 3_000,
            retryDelayMs = 10,
        )
        runBlocking {
            client.stream(
                ChatRequest(model = "deepseek-flash", messages = messages),
            ).toList()
        }
        val recorded = server.takeRequest()
        return json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }
}
