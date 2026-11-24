package cc.pulsez.app.core

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.net.BaseUrl
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「找旧事」读路径的容器守卫接线（ 01 的 embed 侧）。
 *
 * 锁的是**生产链路**：`ChatEngine.indexTurn` / `ChatEngine.prepare` 必须经
 * `AppContainer.embeddingSession`，绑定不成立时**不构造 `EmbeddingClient`、不发请求**，
 * 检索降级关键词、索引只写关键词 chunk。
 *
 * 使用 MockWebServer 而不是假域名：假域名下「0 请求」恒真，
 * ）——地址必须真的指向一台会记数的服务器，守卫回归时才会红。
 *
 * 设施：Robolectric + 真实 Room 文件库（库名写死 `pulsez.db`）+ 注入内存密钥
 * （Robolectric 里 AndroidKeyStore 不可用， 已确认）。全程离线，不碰真机/真实 API。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatEngineEmbedGuardTest {

    private companion object {
        const val EMBED_KEY_NAME = "embed_api_key"
        const val OTHER_HOST = "https://somebody-else.example.com"
        const val EMBED_MODEL = "embedding-3"

        /** 占位密钥（绝不使用真实密钥）。 */
        const val PLACEHOLDER_KEY_A = "test-key-A"

        /** `AppContainer` 里 Room 的库名是写死的（`PulsezDatabase.create`）。 */
        const val APP_DB_NAME = "pulsez.db"

    }

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer
    private lateinit var indexThreadId: String
    private lateinit var indexSegmentId: String

    /** 注入的内存密钥表（模拟 SecretStore 的内容；不落 Keystore、不打印）。 */
    private val secrets = mutableMapOf<String, String>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        // Room 库在 Robolectric 进程内是文件库：清掉上次运行残留，避免 chunk 串用例。
        context.deleteDatabase(APP_DB_NAME)
        // DataStore 在同一 Robolectric 进程里是单例，用例之间共享 → 清回默认。
        SettingsStore(context).update { it.clear() }
        secrets.clear()
        server = MockWebServer()
        server.start()
        container = AppContainer(
            context,
            secretReader = { name -> secrets[name] },
            secretRemover = { name -> secrets.remove(name) },
            secretWriter = { name, value -> secrets[name] = value },
        )
        val (_, indexThread) = container.characters.createWithThread(
            name = "embedding guard fixture",
            persona = "synthetic test fixture",
        )
        indexThreadId = indexThread.id
        indexSegmentId = requireNotNull(indexThread.activeSegmentId)
    }

    @After
    fun tearDown() {
        runCatching { container.database.close() }
        runCatching { server.shutdown() }
        secrets.clear()
        context.deleteDatabase(APP_DB_NAME)
    }

    // ---------------------------------------------------------------- 辅助

    private fun serverBase(): String = server.url("/v1").toString()

    private fun serverOrigin(): String = BaseUrl.origin(serverBase())

    /** 合法 embeddings 响应：1 条 2 维向量，`index=0`。 */
    private fun embeddingsResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"object":"list","data":[{"index":0,"embedding":[0.5,1.0]}]}""")

    /** 一条「导入往事」形状的关键词块（`dim=0 / vec 空 / model="none"`）。 */
    private suspend fun saveKeywordChunk(threadId: String, segmentId: String) {
        val now = System.currentTimeMillis()
        container.memory.saveChunks(
            listOf(
                ChunkEntity(
                    id = "chunk-w1b",
                    threadId = threadId,
                    segmentId = segmentId,
                    text = "你说：上次约好去海边\nTA 说：记得",
                    firstMessageAt = now,
                    lastMessageAt = now,
                    messageIds = "u1,a1",
                    dim = 0,
                    vec = ByteArray(0),
                    scale = 1f,
                    model = "none",
                    createdAt = now,
                    deletedAt = null,
                ),
            ),
            emptyList(),
        )
    }

    private suspend fun indexOneTurn() = container.engine.indexTurn(
        threadId = indexThreadId,
        segmentId = indexSegmentId,
        userText = "今天有点累",
        assistantText = "那就早点休息",
        userMessageId = "m1",
        assistantMessageId = "m2",
    )

    private suspend fun singleChunk(): ChunkEntity =
        container.database.chunkDao().recent(indexThreadId, 10).single()

    // ---------------------------------------------------------------- 索引：脏绑定不出网

    @Test
    fun `索引 脏绑定 host 为空 不出网且只写关键词块`() = runBlocking {
        // 升级后的形状：地址/模型都在、hasEmbedKey=true、没有 keyHost（迁移前）。
        container.settings.setEmbedEndpoint(serverBase(), EMBED_MODEL)
        container.settings.setEmbedKeyPresent(true)
        secrets[EMBED_KEY_NAME] = PLACEHOLDER_KEY_A
        // 守卫回归（真的发了请求）时这个响应会被消费 → requestCount 断言必然失败。
        server.enqueue(embeddingsResponse())

        indexOneTurn()

        assertEquals("绑定不成立（host 空）不能出网", 0, server.requestCount)
        val chunk = singleChunk()
        assertEquals("只写关键词 chunk：dim=0", 0, chunk.dim)
        assertTrue("只写关键词 chunk：vec 为空", chunk.vec.isEmpty())
        assertEquals("只写关键词 chunk：model=none", "none", chunk.model)
    }

    @Test
    fun `索引 脏绑定 host 不符 不出网且只写关键词块`() = runBlocking {
        container.settings.setEmbedBoundEndpoint(serverBase(), EMBED_MODEL, OTHER_HOST)
        secrets[EMBED_KEY_NAME] = PLACEHOLDER_KEY_A
        server.enqueue(embeddingsResponse())

        indexOneTurn()

        assertEquals("旧服务商的密钥不能拼到当前地址上", 0, server.requestCount)
        val chunk = singleChunk()
        assertEquals(0, chunk.dim)
        assertTrue(chunk.vec.isEmpty())
        assertEquals("none", chunk.model)
    }

    // ---------------------------------------------------------------- 索引：正向对照

    @Test
    fun `索引 正常绑定 恰好一次请求且写入向量`() = runBlocking {
        container.settings.setEmbedBoundEndpoint(serverBase(), EMBED_MODEL, serverOrigin())
        secrets[EMBED_KEY_NAME] = PLACEHOLDER_KEY_A
        server.enqueue(embeddingsResponse())

        indexOneTurn()

        assertEquals("绑定成立应恰好一次请求", 1, server.requestCount)
        assertEquals("/v1/embeddings", server.takeRequest().path)
        val chunk = singleChunk()
        assertEquals("2 维向量", 2, chunk.dim)
        assertTrue("向量不能为空", chunk.vec.isNotEmpty())
        assertEquals("chunk.model = 用户配置的模型", EMBED_MODEL, chunk.model)
    }

    // ---------------------------------------------------------------- 检索：脏绑定不出网

    @Test
    fun `检索 脏绑定 不出网且降级关键词`() = runBlocking {
        val (character, thread) = container.characters.createWithThread(
            name = "阿淮",
            persona = "温和、话不多",
        )
        saveKeywordChunk(thread.id, requireNotNull(thread.activeSegmentId))
        container.settings.setEmbedBoundEndpoint(serverBase(), EMBED_MODEL, OTHER_HOST)
        secrets[EMBED_KEY_NAME] = PLACEHOLDER_KEY_A
        server.enqueue(embeddingsResponse())

        // 「上次我们约好去海边的那件事你还记得吗」满足 Recall.shouldRecall（≥10 字、非招呼）。
        val prepared = container.engine.prepare(
            thread = thread,
            characterId = character.id,
            userText = "上次我们约好去海边的那件事你还记得吗",
        )

        assertNotNull("prepare 本身仍要能组装出请求", prepared.messages)
        assertEquals("绑定不成立时检索不能出网", 0, server.requestCount)
    }

    // ---------------------------------------------------------------- 检索：正向对照

    @Test
    fun `检索 正常绑定 恰好一次请求`() = runBlocking {
        val (character, thread) = container.characters.createWithThread(
            name = "阿淮",
            persona = "温和、话不多",
        )
        saveKeywordChunk(thread.id, requireNotNull(thread.activeSegmentId))
        container.settings.setEmbedBoundEndpoint(serverBase(), EMBED_MODEL, serverOrigin())
        secrets[EMBED_KEY_NAME] = PLACEHOLDER_KEY_A
        server.enqueue(embeddingsResponse())

        container.engine.prepare(
            thread = thread,
            characterId = character.id,
            userText = "上次我们约好去海边的那件事你还记得吗",
        )

        assertEquals("embedQuery 只在守卫放行时出网", 1, server.requestCount)
        assertEquals("/v1/embeddings", server.takeRequest().path)
    }
}
