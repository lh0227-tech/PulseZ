package cc.pulsez.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.audio.AsrAvailability
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.net.BaseUrl
import cc.pulsez.app.net.ChatMessage
import cc.pulsez.app.net.ChatRequest
import cc.pulsez.app.ui.config.CredentialSaveResult
import cc.pulsez.app.ui.config.KeyStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 运行时守卫 + 迁移 + 保存协议接线。
 *
 * 为什么必须注入密钥：Robolectric 里 AndroidKeyStore 不可用（第 2 轮 ），生产
 * `SecretStore` 的加解密在本环境会失败；所以用 `AppContainer(secretReader/secretRemover/
 * secretWriter)` 注入内存实现，**只替换密钥存取，设置/守卫/迁移逻辑全走生产代码**。
 *
 * 应用类显式换成普通 `Application`：`PulsezApp.onCreate` 会在后台跑启动迁移，若不换掉，
 * 本类刻意构造的「脏状态」可能被后台迁移提前清掉 → 测试互相干扰（不是产品缺陷）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppContainerCredentialGuardTest {

    private companion object {
        const val DEEPSEEK = "https://api.deepseek.com"
        const val DEEPSEEK_V1 = "https://api.deepseek.com/v1"
        const val SILICONFLOW = "https://api.siliconflow.cn/v1"
        const val ZHIPU = "https://open.bigmodel.cn/api/paas/v4"
        const val OTHER = "https://somebody-else.example.com"

        const val CHAT_KEY_NAME = "chat_api_key"
        const val EMBED_KEY_NAME = "embed_api_key"

        /** 占位密钥（绝不使用真实密钥）。 */
        const val PLACEHOLDER_KEY_A = "test-key-A"
        const val PLACEHOLDER_KEY_B = "test-key-B"
    }

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer

    /** 注入的内存密钥表（模拟 SecretStore 的内容；不落 Keystore、不打印）。 */
    private val secrets = mutableMapOf<String, String>()

    private fun newContainer(
        writer: ((String, String) -> Unit)? = { name, value -> secrets[name] = value },
    ): AppContainer = AppContainer(
        context,
        secretReader = { name -> secrets[name] },
        secretRemover = { name -> secrets.remove(name) },
        secretWriter = writer,
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        // DataStore 在同一 Robolectric 进程里是单例，用例之间共享 → 清回默认。
        SettingsStore(context).update { it.clear() }
        secrets.clear()
        server = MockWebServer()
        server.start()
        // 不预排队响应：每个真发请求的用例自己 enqueue；预排队会 FIFO 错配，
        // 让「守卫回归、请求真发出去」的用例以空队列阻塞而不是以断言失败收场。
        container = newContainer()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        secrets.clear()
    }

    private suspend fun snapshot() = container.settings.flow.first()

    // ---------------------------------------------------------------- MockWebServer 夹具

    /** 指向 MockWebServer 的 base（`http://localhost:<port>/v1`）。 */
    private fun serverBase(): String = server.url("/v1").toString()

    /** 与 [serverBase] 对应的 origin（绑定成立时写进 `keyHost`）。 */
    private fun serverOrigin(): String = BaseUrl.origin(serverBase())

    private fun chatRequest(): ChatRequest = ChatRequest(
        model = "deepseek-flash",
        messages = listOf(ChatMessage(role = "user", content = "在吗")),
        stream = false,
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    /** 合法非流式补全响应（`LlmClient.complete` 读 `choices[0].message.content`）。 */
    private fun completionResponse(content: String = "记住了"): MockResponse =
        jsonResponse("""{"choices":[{"message":{"role":"assistant","content":"$content"}}]}""")

    /** 合法 embeddings 响应：1 条 2 维向量。 */
    private fun embeddingResponse(): MockResponse =
        jsonResponse("""{"object":"list","data":[{"index":0,"embedding":[0.5,1.0]}]}""")

    private suspend fun seedChatBound(baseUrl: String, host: String, secret: String) {
        container.settings.setChatBoundEndpoint(baseUrl, "deepseek-flash", host)
        secrets[CHAT_KEY_NAME] = secret
    }

    private suspend fun seedEmbedBound(baseUrl: String, host: String, secret: String) {
        container.settings.setEmbedBoundEndpoint(baseUrl, "embedding-3", host)
        secrets[EMBED_KEY_NAME] = secret
    }

    // ---------------------------------------------------------------- 读路径守卫

    @Test
    fun `聊天 host 与当前地址不符 客户端为空且零请求`() = runBlocking {
        // 旧服务商的密钥配在新地址上（01 的形状）：地址真的指向 MockWebServer，
        // 守卫回归（建了客户端并调用）时下面的响应会被消费 → 0 请求断言必红（不是空队列阻塞）。
        seedChatBound(serverBase(), OTHER, PLACEHOLDER_KEY_A)
        server.enqueue(completionResponse())

        val client = container.llmClient()
        if (client != null) runCatching { client.complete(chatRequest()) }

        assertNull("host 不符不能创建聊天客户端", client)
        assertEquals("守卫拦下时不能有任何网络请求", 0, server.requestCount)
        // 刻意不在本用例断言 asrClient：localhost 不是 DeepSeek（NOT_DEEPSEEK），
        // 它返回 null 与绑定守卫无关（不要制造假阳性）；语音门禁见下方专项用例。
    }

    @Test
    fun `升级脏状态 host 为空 客户端为空且零请求`() = runBlocking {
        // 迁移前形状：hasChatKey=true、chatKeyHost 空（迁移会清，但读路径必须独立生效）。
        container.settings.setChatEndpoint(serverBase(), "deepseek-flash")
        container.settings.setChatKeyPresent(true)
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A
        server.enqueue(completionResponse())

        val client = container.llmClient()
        if (client != null) runCatching { client.complete(chatRequest()) }

        assertNull(client)
        assertEquals(0, server.requestCount)
        assertEquals("脏状态下 keyHost 仍为空", "", snapshot().chatKeyHost)
    }

    @Test
    fun `host 匹配才创建客户端`() = runBlocking {
        // 地址也指向 MockWebServer：构造客户端本身绝不能出网（就算守卫/构造逻辑将来手滑）。
        seedChatBound(serverBase(), serverOrigin(), PLACEHOLDER_KEY_A)
        assertNotNull("绑定成立应能创建聊天客户端", container.llmClient())
        assertEquals("创建客户端本身不发请求", 0, server.requestCount)
    }

    @Test
    fun `DeepSeek 端点绑定成立时语音客户端可用`() = runBlocking {
        // asrClient 的正面口径仍要覆盖：只对 DeepSeek 官方端点 + 绑定成立 + 密文可读才创建。
        seedChatBound(DEEPSEEK_V1, DEEPSEEK, PLACEHOLDER_KEY_A)
        assertNotNull("绑定成立应能创建语音客户端", container.asrClient())
        assertEquals("创建客户端本身不发请求", 0, server.requestCount)
    }

    @Test
    fun `绑定成立但密文不存在 客户端为空`() = runBlocking {
        container.settings.setChatBoundEndpoint(DEEPSEEK_V1, "deepseek-flash", DEEPSEEK)
        // 不写 secrets → 模拟清空数据只清密文、marker 还在。
        assertNull(container.llmClient())
        assertNull(container.asrClient())
        assertEquals(KeyStatus.NeedsReentry, container.chatKeyStatus(snapshot()))
    }

    @Test
    fun `语音门禁也要看绑定 脏状态归为无密钥`() = runBlocking {
        container.settings.setChatEndpoint(DEEPSEEK_V1, "deepseek-flash")
        container.settings.setChatKeyPresent(true)
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A
        assertEquals(
            "有密钥但绑定不成立时不能显示可用的语音入口",
            AsrAvailability.NO_KEY,
            container.voiceAvailability(),
        )
    }

    // ---------------------------------------------------------------- 语音门禁

    @Test
    fun `语音门禁 绑定不成立时归为无密钥`() = runBlocking {
        // hasChatKey=true + chatKeyHost 空：门禁原本只看 hasChatKey → 会显示麦克风，
        // 但开始录音前必须被容器守卫拦下（与 asrClient 同口径）。
        container.settings.setChatEndpoint(DEEPSEEK_V1, "deepseek-flash")
        container.settings.setChatKeyPresent(true)
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A

        assertNull("绑定不成立不能创建语音客户端", container.asrClient())
        assertEquals(AsrAvailability.NO_KEY, container.voiceAvailability())
        assertEquals("脏状态的 keyHost 仍为空", "", snapshot().chatKeyHost)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `语音门禁 绑定成立但密文丢失时归为无密钥`() = runBlocking {
        // 绑定成立但密文读不到（清数据只清密文 / 解密失败）： 前这里返回 AVAILABLE
        // （只看绑定），入口会显示、拖到转写期才落 SERVICE_UNSUPPORTED；本用例守护「由红转绿」。
        container.settings.setChatBoundEndpoint(DEEPSEEK_V1, "deepseek-flash", DEEPSEEK)
        // 不写 secrets。

        assertEquals(
            "绑定成立但密文丢失必须归 NO_KEY（与 asrClient() 同口径）",
            AsrAvailability.NO_KEY,
            container.voiceAvailability(),
        )
        assertNull(container.asrClient())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `语音门禁 绑定成立且密文可读时可用`() = runBlocking {
        container.settings.setChatBoundEndpoint(DEEPSEEK_V1, "deepseek-flash", DEEPSEEK)
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A

        assertEquals(
            "DeepSeek 端点 + 绑定成立 + 密文可读 → 语音可用",
            AsrAvailability.AVAILABLE,
            container.voiceAvailability(),
        )
        assertNotNull(container.asrClient())
        assertEquals("门禁评估不能构造客户端、不能出网", 0, server.requestCount)
    }

    @Test
    fun `找旧事 host 不符 视为未配置且零请求`() = runBlocking {
        seedEmbedBound(serverBase(), OTHER, PLACEHOLDER_KEY_A)
        server.enqueue(embeddingResponse())

        assertNull("绑定不成立不能拿到请求快照", container.embeddingSession())
        assertFalse("绑定不成立不能算已配置", container.embeddingConfigured())
        assertNull("未配置时建向量端口不能出网", container.importEmbedPort.embed(listOf("测试文本")))
        assertEquals("守卫拦下时不能有任何网络请求", 0, server.requestCount)
    }

    @Test
    fun `找旧事绑定匹配才算已配置`() = runBlocking {
        seedEmbedBound(serverBase(), serverOrigin(), PLACEHOLDER_KEY_A)
        assertTrue(container.embeddingConfigured())
        assertEquals(
            KeyStatus.Usable(serverOrigin()),
            container.embedKeyStatus(snapshot()),
        )
        assertNotNull(container.embeddingSession())
        assertEquals(0, server.requestCount)
    }

    // ---------------------------------------------------------------- ：MockWebServer 正负对照
    //
    // 负向用例都必须「真能红」：地址真的指向 MockWebServer、先 enqueue 合法响应、并在守卫
    // 万一回归出客户端时**真的尝试调用**（否则只会以空队列阻塞收场，而不是断言失败）。

    @Test
    fun `聊天 绑定不成立 零请求（地址指向 MockWebServer）`() = runBlocking {
        // 脏绑定（host 空）+ 地址指向 MockWebServer：守卫若不成立客户端，请求就会真的发出去。
        container.settings.setChatEndpoint(serverBase(), "deepseek-flash")
        container.settings.setChatKeyPresent(true)
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A
        server.enqueue(completionResponse())

        val client = container.llmClient()
        if (client != null) runCatching { client.complete(chatRequest()) }

        assertNull("脏绑定不能有可用客户端", client)
        assertEquals("守卫回归时必须在这里失败（而不是空队列阻塞）", 0, server.requestCount)
    }

    @Test
    fun `找旧事 绑定不成立 零请求（地址指向 MockWebServer）`() = runBlocking {
        container.settings.setEmbedEndpoint(serverBase(), "embedding-3")
        container.settings.setEmbedKeyPresent(true)
        secrets[EMBED_KEY_NAME] = PLACEHOLDER_KEY_A
        server.enqueue(embeddingResponse())

        assertNull("脏绑定不能拿到请求快照", container.embeddingSession())
        assertFalse(container.embeddingConfigured())
        assertNull(container.importEmbedPort.embed(listOf("测试文本")))
        assertEquals("守卫回归时必须在这里失败（而不是空队列阻塞）", 0, server.requestCount)
    }

    @Test
    fun `聊天 绑定成立 请求恰好一次（地址指向 MockWebServer）`() = runBlocking {
        seedChatBound(serverBase(), serverOrigin(), PLACEHOLDER_KEY_A)
        server.enqueue(completionResponse("在"))

        val client = container.llmClient()
        assertNotNull("绑定成立必须能创建客户端（正向对照）", client)
        assertEquals("在", client!!.complete(chatRequest()))
        assertEquals("正向对照恰好一次请求", 1, server.requestCount)
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun `找旧事 绑定成立 请求恰好一次（地址指向 MockWebServer）`() = runBlocking {
        seedEmbedBound(serverBase(), serverOrigin(), PLACEHOLDER_KEY_A)
        server.enqueue(embeddingResponse())

        assertNotNull(container.embeddingSession())
        val vectors = container.importEmbedPort.embed(listOf("测试文本"))
        assertEquals("正向对照必须真的拿到 1 条向量", 1, vectors?.size)
        assertEquals("正向对照恰好一次请求", 1, server.requestCount)
        assertEquals("/v1/embeddings", server.takeRequest().path)
    }

    // ---------------------------------------------------------------- +：EmbeddingSession 快照守卫

    @Test
    fun `EmbeddingSession 脏 host 为空 返回空`() = runBlocking {
        container.settings.setEmbedEndpoint(serverBase(), "embedding-3")
        container.settings.setEmbedKeyPresent(true)
        secrets[EMBED_KEY_NAME] = PLACEHOLDER_KEY_A

        assertNull("host 空（升级脏状态）不能拿到快照", container.embeddingSession())
    }

    @Test
    fun `EmbeddingSession host 不符 返回空`() = runBlocking {
        seedEmbedBound(serverBase(), OTHER, PLACEHOLDER_KEY_A)
        assertNull("host 不符不能拿到快照", container.embeddingSession())
    }

    @Test
    fun `EmbeddingSession 地址或模型为空 返回空`() = runBlocking {
        container.settings.setEmbedBoundEndpoint(serverBase(), "", serverOrigin())
        secrets[EMBED_KEY_NAME] = PLACEHOLDER_KEY_A
        assertNull("模型为空不能拿到快照", container.embeddingSession())

        container.settings.setEmbedEndpoint("", "embedding-3")
        container.settings.setEmbedKeyPresent(true)
        assertNull("地址为空不能拿到快照", container.embeddingSession())
    }

    @Test
    fun `EmbeddingSession 绑定成立时快照字段与持久值一致`() = runBlocking {
        seedEmbedBound(serverBase(), serverOrigin(), PLACEHOLDER_KEY_A)

        val session = container.embeddingSession()
        assertNotNull("绑定成立时必须拿到快照", session)
        assertEquals(serverBase(), session!!.baseUrl)
        assertEquals("embedding-3", session.model)
        assertEquals(PLACEHOLDER_KEY_A, session.apiKey)
        assertFalse(
            "toString() 不能泄露密钥",
            session.toString().contains(PLACEHOLDER_KEY_A),
        )
    }

    // ---------------------------------------------------------------- 保存协议接线（成功后读路径才可用）

    @Test
    fun `保存新密钥成功 读路径立刻可用`() = runBlocking {
        // 旧状态：A 家密钥。
        seedChatBound(DEEPSEEK_V1, DEEPSEEK, PLACEHOLDER_KEY_A)

        val result = container.saveChatCredential(SILICONFLOW, "Qwen", PLACEHOLDER_KEY_B)
        assertEquals(CredentialSaveResult.SAVED, result)

        val persisted = snapshot()
        assertTrue(persisted.hasChatKey)
        assertEquals("host = 新地址 origin", "https://api.siliconflow.cn", persisted.chatKeyHost)
        assertEquals("新密文写入", PLACEHOLDER_KEY_B, secrets[CHAT_KEY_NAME])
        assertNotNull("保存成功后应能创建客户端", container.llmClient())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `换服务商留空密钥 拒绝且保留旧配置`() = runBlocking {
        seedChatBound(DEEPSEEK_V1, DEEPSEEK, PLACEHOLDER_KEY_A)

        val result = container.saveChatCredential(SILICONFLOW, "Qwen", "")
        assertEquals(CredentialSaveResult.REQUIRE_NEW_KEY, result)

        val persisted = snapshot()
        assertEquals("地址不能被改成新服务商", DEEPSEEK_V1, persisted.chatBaseUrl)
        assertEquals("host 不能被改", DEEPSEEK, persisted.chatKeyHost)
        assertTrue("旧密钥仍绑在旧地址上（没有换成功，也没坏）", persisted.hasChatKey)
        assertEquals(PLACEHOLDER_KEY_A, secrets[CHAT_KEY_NAME])
        // 旧配置本身仍可用（用户没有保存任何新东西）。
        assertNotNull(container.llmClient())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `坏地址保存 拒绝且零写入`() = runBlocking {
        seedChatBound(DEEPSEEK_V1, DEEPSEEK, PLACEHOLDER_KEY_A)
        val result = container.saveChatCredential("https://", "deepseek-flash", PLACEHOLDER_KEY_B)
        assertEquals(CredentialSaveResult.BAD_ORIGIN, result)
        val persisted = snapshot()
        assertEquals(DEEPSEEK_V1, persisted.chatBaseUrl)
        assertEquals(DEEPSEEK, persisted.chatKeyHost)
        assertEquals(PLACEHOLDER_KEY_A, secrets[CHAT_KEY_NAME])
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `同 origin 换 path 密钥留空 保留旧绑定`() = runBlocking {
        seedChatBound(DEEPSEEK, DEEPSEEK, PLACEHOLDER_KEY_A)
        val result = container.saveChatCredential(DEEPSEEK_V1, "deepseek-flash", "")
        assertEquals(CredentialSaveResult.SAVED, result)
        val persisted = snapshot()
        assertEquals(DEEPSEEK_V1, persisted.chatBaseUrl)
        assertEquals(DEEPSEEK, persisted.chatKeyHost)
        assertEquals(PLACEHOLDER_KEY_A, secrets[CHAT_KEY_NAME])
        assertNotNull(container.llmClient())
    }

    @Test
    fun `第②步写密文失败 保存失败且持久态不可用`() = runBlocking {
        seedChatBound(DEEPSEEK_V1, DEEPSEEK, PLACEHOLDER_KEY_A)
        // 注入会抛错的写入缝：模拟 Keystore/文件写失败。
        val failing = newContainer(writer = { _, _ -> error("keystore boom") })

        val result = failing.saveChatCredential(SILICONFLOW, "Qwen", PLACEHOLDER_KEY_B)
        assertEquals(CredentialSaveResult.FAILED, result)

        val persisted = snapshot()
        assertFalse("失败后不能还显示有可用密钥", persisted.hasChatKey)
        assertEquals("失败后 host 必须清空", "", persisted.chatKeyHost)
        assertNull("第①步已失效绑定，客户端必须为空", failing.llmClient())
        assertNull(failing.asrClient())
        assertEquals("保存失败不能产生任何请求", 0, server.requestCount)
        assertEquals("第②步失败后新密文不能留下", PLACEHOLDER_KEY_A, secrets[CHAT_KEY_NAME])
    }

    // ---------------------------------------------------------------- 清除密钥（E）

    @Test
    fun `清除密钥 绑定失效 密文删除 探测结论清空`() = runBlocking {
        seedChatBound(DEEPSEEK_V1, DEEPSEEK, PLACEHOLDER_KEY_A)
        container.settings.setVisionProbe("deepseek-flash", supported = true)

        assertTrue(container.clearChatCredential())

        val persisted = snapshot()
        assertFalse(persisted.hasChatKey)
        assertEquals("", persisted.chatKeyHost)
        assertFalse("密文要删掉", secrets.containsKey(CHAT_KEY_NAME))
        assertEquals("识图探测结论要清掉", "", persisted.visionProbeModel)
        assertNull(container.llmClient())
        assertNull(container.asrClient())
        assertEquals(0, server.requestCount)
    }

    // ---------------------------------------------------------------- 停止使用找旧事（H）

    @Test
    fun `断开找旧事 配置与密钥全清 之后零出网`() = runBlocking {
        seedEmbedBound(ZHIPU, "https://open.bigmodel.cn", PLACEHOLDER_KEY_A)
        container.settings.setEmbedKeyResetAfterUpgrade(true)
        assertTrue(container.embeddingConfigured())

        assertTrue(container.disconnectEmbedCredential())

        val persisted = snapshot()
        assertEquals("地址要清空（ChatEngine 据此短路，零出网）", "", persisted.embedBaseUrl)
        assertEquals("模型要清空", "", persisted.embedModel)
        assertFalse(persisted.hasEmbedKey)
        assertEquals("", persisted.embedKeyHost)
        assertFalse("一次性提示也要清", persisted.embedKeyResetAfterUpgrade)
        assertFalse("密文要删掉", secrets.containsKey(EMBED_KEY_NAME))
        assertFalse(container.embeddingConfigured())
        assertNull(container.importEmbedPort.embed(listOf("测试文本")))
        assertEquals(0, server.requestCount)
    }

    // ---------------------------------------------------------------- 升级迁移（I）

    @Test
    fun `迁移 聊天脏状态 删密钥置标记且幂等`() = runBlocking {
        // 升级后的形状：地址已填、hasChatKey=true、没有 keyHost、密文是旧服务商的。
        container.settings.setChatEndpoint(SILICONFLOW, "Qwen")
        container.settings.setChatKeyPresent(true)
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A

        container.migrateLegacyCredentialBinding()

        var persisted = snapshot()
        assertFalse("旧密钥不能再被当成可用", persisted.hasChatKey)
        assertEquals("keyHost 不回填", "", persisted.chatKeyHost)
        assertTrue("要置一次性提示", persisted.chatKeyResetAfterUpgrade)
        assertFalse("旧密文要删掉", secrets.containsKey(CHAT_KEY_NAME))
        assertNull(container.llmClient())
        assertNull(container.asrClient())
        assertEquals(0, server.requestCount)

        // 幂等：再跑一次不改变结论，也不把标记弄丢。
        container.migrateLegacyCredentialBinding()
        persisted = snapshot()
        assertFalse(persisted.hasChatKey)
        assertTrue(persisted.chatKeyResetAfterUpgrade)
        assertFalse(secrets.containsKey(CHAT_KEY_NAME))
    }

    @Test
    fun `迁移 找旧事脏状态 同样删密钥置标记`() = runBlocking {
        container.settings.setEmbedEndpoint(ZHIPU, "embedding-3")
        container.settings.setEmbedKeyPresent(true)
        secrets[EMBED_KEY_NAME] = PLACEHOLDER_KEY_A

        container.migrateLegacyCredentialBinding()

        val persisted = snapshot()
        assertFalse(persisted.hasEmbedKey)
        assertEquals("", persisted.embedKeyHost)
        assertTrue(persisted.embedKeyResetAfterUpgrade)
        assertFalse(secrets.containsKey(EMBED_KEY_NAME))
        assertFalse(container.embeddingConfigured())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `迁移 正常绑定不动`() = runBlocking {
        seedChatBound(DEEPSEEK_V1, DEEPSEEK, PLACEHOLDER_KEY_A)
        container.settings.setChatKeyResetAfterUpgrade(false)

        container.migrateLegacyCredentialBinding()

        val persisted = snapshot()
        assertTrue("正常绑定不能被迁移清掉", persisted.hasChatKey)
        assertEquals(DEEPSEEK, persisted.chatKeyHost)
        assertFalse(persisted.chatKeyResetAfterUpgrade)
        assertEquals(PLACEHOLDER_KEY_A, secrets[CHAT_KEY_NAME])
        assertNotNull(container.llmClient())
    }

    @Test
    fun `迁移 对账孤儿密文`() = runBlocking {
        // hasKey=false 但密文还在（保存第②步后进程被杀 / 旧版清空数据只清密文）。
        secrets[CHAT_KEY_NAME] = "test-key-orphan"
        secrets[EMBED_KEY_NAME] = "test-key-orphan"

        container.migrateLegacyCredentialBinding()

        assertFalse("无主密文要删掉", secrets.containsKey(CHAT_KEY_NAME))
        assertFalse("无主密文要删掉", secrets.containsKey(EMBED_KEY_NAME))
    }

    @Test
    fun `迁移失败不崩 读路径仍不可用`() = runBlocking {
        // 注入会抛错的删除缝：迁移删不掉密文也不能崩，更不能让旧密钥变得可用。
        val broken = AppContainer(
            context,
            secretReader = { name -> secrets[name] },
            secretRemover = { error("remove boom") },
            secretWriter = { name, value -> secrets[name] = value },
        )
        container.settings.setChatEndpoint(SILICONFLOW, "Qwen")
        container.settings.setChatKeyPresent(true)
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A

        broken.migrateLegacyCredentialBinding()

        val persisted = snapshot()
        assertFalse("设置已置不可用", persisted.hasChatKey)
        assertEquals("", persisted.chatKeyHost)
        assertNull("读路径不可用", broken.llmClient())
        assertNull(broken.asrClient())
        assertEquals(0, server.requestCount)
    }
}
