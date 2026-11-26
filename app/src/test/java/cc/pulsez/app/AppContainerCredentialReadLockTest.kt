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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 凭据读取与保存互斥的回归测试。
 *
 * 读取应在同一锁内获取设置快照和密钥，避免保存或迁移插入两步之间，导致旧地址配上新密钥。
 * 网络请求仍在锁外完成。
 *
 * 三组时序分别验证：读取持锁时保存不能写入新凭据；保存持锁时读取只能看到完整的新状态；
 * 绑定不成立时不会读取密文。测试通过注入的 `secretReader`、`secretWriter` 和
 * `CountDownLatch` 控制时序，并用本地 HTTP 请求头确认地址与密钥来自同一快照。
 *
 * Robolectric 中 AndroidKeyStore 不可用，因此只替换密钥存取；设置、守卫和保存协议仍走生产代码。
 * 使用普通 `Application` 可避免后台迁移干扰测试时序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppContainerCredentialReadLockTest {

    private companion object {
        const val DEEPSEEK = "https://api.deepseek.com"
        const val DEEPSEEK_V1 = "https://api.deepseek.com/v1"
        const val SILICONFLOW = "https://api.siliconflow.cn/v1"

        const val CHAT_KEY_NAME = "chat_api_key"
        const val EMBED_KEY_NAME = "embed_api_key"

        /** 占位密钥（绝不使用真实密钥）。 */
        const val PLACEHOLDER_KEY_A = "test-key-A"
        const val PLACEHOLDER_KEY_B = "test-key-B"

        /** 等锁存器 / 等协程的上限（不是断言阈值；只是防挂死）。 */
        const val AWAIT_MS = 5_000L

        /** 「对方在持锁期间不能完成」的观察窗：正常写法一次保存/读取远小于它。 */
        const val HOLD_WINDOW_MS = 800L

        /** 给「若不互斥就会抢跑的读协程」留的调度窗口（期间主线程只挂起，不阻塞读线程）。 */
        const val SETTLE_MS = 300L
    }

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    /** 注入的内存密钥表（模拟 SecretStore 的内容；不落 Keystore、不打印）。 */
    private val secrets = mutableMapOf<String, String>()

    /**
 * 构造容器。默认读写缝都走 [secrets]；需要控制时序的用例传入会挂起 / 计数的实现。
 */
    private fun newContainer(
        reader: (String) -> String? = { name -> secrets[name] },
        writer: (String, String) -> Unit = { name, value -> secrets[name] = value },
    ): AppContainer = AppContainer(
        context,
        secretReader = reader,
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
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        secrets.clear()
    }

    // ---------------------------------------------------------------- 夹具

    /** 指向 MockWebServer 的 base（`http://localhost:<port>/v1`）。 */
    private fun serverBase(): String = server.url("/v1").toString()

    /** 与 [serverBase] 对应的 origin（绑定成立时写进 `keyHost`）。 */
    private fun serverOrigin(): String = BaseUrl.origin(serverBase())

    private fun chatRequest(): ChatRequest = ChatRequest(
        model = "deepseek-flash",
        messages = listOf(ChatMessage(role = "user", content = "在吗")),
        stream = false,
    )

    private fun completionResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"choices":[{"message":{"role":"assistant","content":"在"}}]}""")

    // ---------------------------------------------------------------- 1. 读持锁 → 保存被挡（聊天）

    @Test
    fun `读取持锁期间 并发保存不能写新密钥或新地址（聊天）`() = runBlocking {
        val readerEntered = CountDownLatch(1)
        val readerRelease = CountDownLatch(1)
        val writerReached = CountDownLatch(1)

        val container = newContainer(
            reader = { name ->
                if (name == CHAT_KEY_NAME) {
                    // 停在「已取旧设置快照（旧地址 + 旧 host）、正在读密文」的错配窗口。
                    readerEntered.countDown()
                    check(readerRelease.await(AWAIT_MS, TimeUnit.MILLISECONDS)) { "读路径未被放行" }
                }
                secrets[name]
            },
            writer = { name, value ->
                writerReached.countDown()
                secrets[name] = value
            },
        )
        // 旧状态：A 家密钥绑在 MockWebServer 的地址上（绑定成立，读路径会去读密文）。
        val oldBase = serverBase()
        container.settings.setChatBoundEndpoint(oldBase, "deepseek-flash", serverOrigin())
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A

        // 读协程先持锁（停在读密文处）。
        val read = async(Dispatchers.IO) { container.llmClient() }
        assertTrue(
            "读路径应进入「持锁读密钥」阶段",
            readerEntered.await(AWAIT_MS, TimeUnit.MILLISECONDS),
        )

        // 并发保存：读取持锁期间，它必须连第②步（写新密文）都进不去。
        val saveStarted = CountDownLatch(1)
        val save = async(Dispatchers.IO) {
            saveStarted.countDown()
            container.saveChatCredential(SILICONFLOW, "Qwen", PLACEHOLDER_KEY_B)
        }
        assertTrue("保存协程应已启动", saveStarted.await(AWAIT_MS, TimeUnit.MILLISECONDS))
        assertFalse(
            "读取持锁期间保存不得写新密钥（第②步必须被挡在锁外）",
            writerReached.await(HOLD_WINDOW_MS, TimeUnit.MILLISECONDS),
        )
        assertFalse("读取持锁期间保存不得完成", save.isCompleted)

        val duringRead = container.settings.flow.first()
        assertEquals("读取持锁期间地址不能被换掉", oldBase, duringRead.chatBaseUrl)
        assertEquals("读取持锁期间绑定 host 不能被换掉", serverOrigin(), duringRead.chatKeyHost)
        assertEquals("读取持锁期间新密钥不能落盘", PLACEHOLDER_KEY_A, secrets[CHAT_KEY_NAME])

        // 放行读路径：它应先完成（旧地址 + 旧密钥），保存之后才写入。
        readerRelease.countDown()
        val client = read.await()
        assertNotNull("旧绑定成立，读路径应拿到客户端", client)
        assertEquals("读路径构造客户端本身不得出网", 0, server.requestCount)
        // 真的发一次本地请求，用请求头证明客户端手里是「旧地址 + 旧密钥」，
        // 而不是「旧地址 + 新密钥」。
        server.enqueue(completionResponse())
        assertEquals("在", client!!.complete(chatRequest()))
        assertEquals("请求必须打到读快照里的旧地址", 1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals(
            "读路径在保存写入前完成：密钥必须是旧密钥 A",
            "Bearer $PLACEHOLDER_KEY_A",
            request.getHeader("Authorization"),
        )

        assertEquals("读完成后保存应正常完成（无死锁）", CredentialSaveResult.SAVED, save.await())
        val afterSave = container.settings.flow.first()
        assertEquals("保存最终写入新地址", SILICONFLOW, afterSave.chatBaseUrl)
        assertEquals("保存最终写入新地址 origin", "https://api.siliconflow.cn", afterSave.chatKeyHost)
        assertEquals("保存最终写入新密钥", PLACEHOLDER_KEY_B, secrets[CHAT_KEY_NAME])
    }

    // ---------------------------------------------------------------- 2. 读持锁 → 保存被挡（找旧事）

    @Test
    fun `读取持锁期间 并发保存不能换掉找旧事配置`() = runBlocking {
        val readerEntered = CountDownLatch(1)
        val readerRelease = CountDownLatch(1)
        val writerReached = CountDownLatch(1)

        val container = newContainer(
            reader = { name ->
                if (name == EMBED_KEY_NAME) {
                    readerEntered.countDown()
                    check(readerRelease.await(AWAIT_MS, TimeUnit.MILLISECONDS)) { "读路径未被放行" }
                }
                secrets[name]
            },
            writer = { name, value ->
                writerReached.countDown()
                secrets[name] = value
            },
        )
        val oldBase = serverBase()
        container.settings.setEmbedBoundEndpoint(oldBase, "embedding-3", serverOrigin())
        secrets[EMBED_KEY_NAME] = PLACEHOLDER_KEY_A

        val read = async(Dispatchers.IO) { container.embeddingSession() }
        assertTrue(
            "找旧事读路径应进入「持锁读密钥」阶段",
            readerEntered.await(AWAIT_MS, TimeUnit.MILLISECONDS),
        )

        val saveStarted = CountDownLatch(1)
        val save = async(Dispatchers.IO) {
            saveStarted.countDown()
            container.saveEmbedCredential(SILICONFLOW, "BAAI/bge-m3", PLACEHOLDER_KEY_B)
        }
        assertTrue("保存协程应已启动", saveStarted.await(AWAIT_MS, TimeUnit.MILLISECONDS))
        assertFalse(
            "读取持锁期间找旧事保存不得写新密钥",
            writerReached.await(HOLD_WINDOW_MS, TimeUnit.MILLISECONDS),
        )
        assertFalse("读取持锁期间找旧事保存不得完成", save.isCompleted)
        assertEquals(
            "读取持锁期间找旧事地址不能被换掉",
            oldBase,
            container.settings.flow.first().embedBaseUrl,
        )

        readerRelease.countDown()
        val session = read.await()
        assertNotNull("旧绑定成立，找旧事快照应拿到", session)
        assertEquals("快照里必须是旧地址", oldBase, session!!.baseUrl)
        assertEquals("快照里必须是旧密钥", PLACEHOLDER_KEY_A, session.apiKey)
        assertEquals(0, server.requestCount)

        assertEquals(CredentialSaveResult.SAVED, save.await())
        assertEquals(
            "保存最终写入新找旧事地址",
            SILICONFLOW,
            container.settings.flow.first().embedBaseUrl,
        )
        assertEquals("保存最终写入新找旧事密钥", PLACEHOLDER_KEY_B, secrets[EMBED_KEY_NAME])
    }

    // ---------------------------------------------------------------- 3. 保存持锁 → 读必须等（聊天）

    @Test
    fun `保存持锁期间 并发读取看到的是保存后的新地址新密钥`() = runBlocking {
        val writeEntered = CountDownLatch(1)
        val writeRelease = CountDownLatch(1)

        val container = newContainer(
            writer = { name, value ->
                if (name == CHAT_KEY_NAME) {
                    // 停在保存第②步：此时协议第①步已把绑定置为不可用，是典型的「半保存态」。
                    writeEntered.countDown()
                    check(writeRelease.await(AWAIT_MS, TimeUnit.MILLISECONDS)) { "保存未被放行" }
                }
                secrets[name] = value
            },
        )
        // 旧状态：A 家密钥绑在另一家地址上。
        container.settings.setChatBoundEndpoint(SILICONFLOW, "Qwen", "https://api.siliconflow.cn")
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A

        val newBase = serverBase()
        val save = async(Dispatchers.IO) {
            container.saveChatCredential(newBase, "deepseek-flash", PLACEHOLDER_KEY_B)
        }
        assertTrue(
            "保存应已持锁并走到第②步",
            writeEntered.await(AWAIT_MS, TimeUnit.MILLISECONDS),
        )

        // 保存仍持锁：并发读必须等它完成，不能拿「旧设置 + 新密钥」或任何半保存态。
        val read = async(Dispatchers.IO) { container.llmClient() }
        delay(SETTLE_MS) // 若不互斥，读会抢在这个窗口里跑完（拿到①步后的半保存态）。
        writeRelease.countDown()

        val client = read.await()
        assertNotNull("保存完成后读路径必须拿到新配置", client)
        assertEquals("读路径构造客户端本身不得出网", 0, server.requestCount)

        server.enqueue(completionResponse())
        assertEquals("在", client!!.complete(chatRequest()))
        val request = server.takeRequest()
        assertEquals("请求必须打到保存后的新地址", "/v1/chat/completions", request.path)
        assertEquals(
            "读路径只能看到保存后的新密钥 B（不能是旧设置 + 新密钥/半保存态）",
            "Bearer $PLACEHOLDER_KEY_B",
            request.getHeader("Authorization"),
        )

        assertEquals(CredentialSaveResult.SAVED, save.await())
    }

    // ---------------------------------------------------------------- 4. 保存持锁 → 语音门禁 / ASR 也要等

    @Test
    fun `保存持锁期间 语音门禁与 ASR 客户端看不到半保存状态`() = runBlocking {
        val writeEntered = CountDownLatch(1)
        val writeRelease = CountDownLatch(1)

        val container = newContainer(
            writer = { name, value ->
                if (name == CHAT_KEY_NAME) {
                    writeEntered.countDown()
                    check(writeRelease.await(AWAIT_MS, TimeUnit.MILLISECONDS)) { "保存未被放行" }
                }
                secrets[name] = value
            },
        )
        // 旧状态：DeepSeek 官方端点 + A 家密钥（新密钥仍绑同一端点，门禁口径不变）。
        container.settings.setChatBoundEndpoint(DEEPSEEK_V1, "deepseek-flash", DEEPSEEK)
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A

        val save = async(Dispatchers.IO) {
            container.saveChatCredential(DEEPSEEK_V1, "deepseek-flash", PLACEHOLDER_KEY_B)
        }
        assertTrue(
            "保存应已持锁并走到第②步",
            writeEntered.await(AWAIT_MS, TimeUnit.MILLISECONDS),
        )

        val availability = async(Dispatchers.IO) { container.voiceAvailability() }
        val asr = async(Dispatchers.IO) { container.asrClient() }
        delay(SETTLE_MS) // 给「未加锁的读」充分的抢跑机会：它会看到①步后的 hasKey=false。
        writeRelease.countDown()

        assertEquals(
            "保存完成后语音门禁应为可用（读到了新密钥 B + 绑定成立）",
            AsrAvailability.AVAILABLE,
            availability.await(),
        )
        assertNotNull("保存完成后 ASR 客户端应可创建", asr.await())
        assertEquals(0, server.requestCount)
        assertEquals(CredentialSaveResult.SAVED, save.await())
    }

    // ---------------------------------------------------------------- 5. 守卫语义不变

    @Test
    fun `加锁不改变先判绑定再读密钥`() = runBlocking {
        // 注入「被调用即失败」的读取缝：绑定不成立时读路径连一条密文都不该读。
        val container = newContainer(
            reader = { name -> throw AssertionError("绑定不成立时不得读密文：$name") },
        )
        // 升级脏状态：有密钥标记、没有归属 host（bindingState = NotBound）。
        container.settings.setChatEndpoint(DEEPSEEK_V1, "deepseek-flash")
        container.settings.setChatKeyPresent(true)
        container.settings.setEmbedEndpoint(DEEPSEEK_V1, "embedding-3")
        container.settings.setEmbedKeyPresent(true)

        assertNull("绑定不成立：聊天客户端为空", container.llmClient())
        assertNull("绑定不成立：ASR 客户端为空", container.asrClient())
        assertNull("绑定不成立：找旧事快照为空", container.embeddingSession())
        assertEquals(
            "绑定不成立：语音门禁归无密钥",
            AsrAvailability.NO_KEY,
            container.voiceAvailability(),
        )
        assertEquals("守卫拦下时零请求", 0, server.requestCount)
    }

    @Test
    fun `聊天端点会话固定同一轮读取的地址模型设置且不泄漏密钥`() = runBlocking {
        val container = newContainer()
        assertEquals(
            CredentialSaveResult.SAVED,
            container.saveChatCredential(DEEPSEEK, "deepseek-chat", PLACEHOLDER_KEY_A),
        )

        val first = container.resolvedChatEndpoint() ?: error("已保存的测试凭据应能解析出聊天会话")
        val firstSettings = first.settingsSnapshot ?: error("解析出的生产会话应携带读取时设置快照")
        assertEquals(DEEPSEEK, first.baseUrl)
        assertEquals(DEEPSEEK, firstSettings.chatBaseUrl)
        assertEquals("deepseek-chat", firstSettings.chatModel)
        assertEquals(PLACEHOLDER_KEY_A, first.apiKey)

        assertEquals(
            CredentialSaveResult.SAVED,
            container.saveChatCredential(DEEPSEEK_V1, "deepseek-reasoner", PLACEHOLDER_KEY_B),
        )
        val second = container.resolvedChatEndpoint() ?: error("轮换后的测试凭据应能解析出聊天会话")
        val secondSettings = second.settingsSnapshot ?: error("轮换后的生产会话应携带读取时设置快照")

        // 第一次返回的会话保留端点、模型和密钥同一快照；凭据轮换不能改写已返回对象。
        assertEquals(DEEPSEEK, first.baseUrl)
        assertEquals("deepseek-chat", firstSettings.chatModel)
        assertEquals(DEEPSEEK, firstSettings.chatBaseUrl)
        assertEquals(PLACEHOLDER_KEY_A, first.apiKey)
        assertEquals(DEEPSEEK_V1, second.baseUrl)
        assertEquals(DEEPSEEK_V1, secondSettings.chatBaseUrl)
        assertEquals("deepseek-reasoner", secondSettings.chatModel)
        assertEquals(PLACEHOLDER_KEY_B, second.apiKey)

        assertFalse(first.toString().contains(PLACEHOLDER_KEY_A))
        assertFalse(second.toString().contains(PLACEHOLDER_KEY_B))
        assertFalse(firstSettings.toString().contains(PLACEHOLDER_KEY_A))
        assertFalse(secondSettings.toString().contains(PLACEHOLDER_KEY_B))
    }
}
