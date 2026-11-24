package cc.pulsez.app.ui.config

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.net.BaseUrl
import cc.pulsez.app.net.ChatMessage
import cc.pulsez.app.net.ChatRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 容器级「保存第③步失败 / 第④步回读不一致」用例。
 *
 * 为什么必须**容器级**：`CredentialSaveWorkflowTest` 只测协议本身；
 * 要求的是「从 `ConfigViewModel.saveChat` 走一圈生产接线后，持久态不可用、
 * `saved==false`、零请求」。 只交了第②步（写密文失败），③/④ 缺失。
 *
 * 失败点注入走 `AppContainer(chatBoundEndpointWriterForTest = ...)` 这个**仅测试缝**
 * （生产默认 null）。Robolectric 里 AndroidKeyStore 不可用，密钥同样用注入的内存实现。
 *
 * 的断言另加一条**精确性**对照：③失败但 endpoint 没变时，仍然有效的识图探测结论不该被清。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfigViewModelSaveFailureTest {

    private companion object {
        const val SILICONFLOW = "https://api.siliconflow.cn/v1"
        const val OTHER = "https://somebody-else.example.com"

        const val CHAT_KEY_NAME = "chat_api_key"

        /** 占位密钥（绝不使用真实密钥）。 */
        const val PLACEHOLDER_KEY_A = "test-key-A"
        const val PLACEHOLDER_KEY_B = "test-key-B"
    }

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer

    /** 注入的内存密钥表（模拟 SecretStore 的内容；不落 Keystore、不打印）。 */
    private val secrets = mutableMapOf<String, String>()

    private object NoopTester : ConnectionTester {
        override suspend fun testChat(baseUrl: String, apiKey: String, model: String) =
            ConnectionTestResult(chatOk = true, embeddingsOk = false, message = "")

        override suspend fun testEmbeddings(baseUrl: String, apiKey: String, model: String) =
            ConnectionTestResult(chatOk = false, embeddingsOk = true, message = "")
    }

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(StandardTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        SettingsStore(context).update { it.clear() }
        secrets.clear()
        server = MockWebServer()
        server.start()
        container = newContainer()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        runCatching { server.shutdown() }
        secrets.clear()
    }

    // ---------------------------------------------------------------- 夹具

    private fun newContainer(
        chatBoundEndpointWriterForTest:
            (suspend (baseUrl: String, model: String, keyHost: String) -> Unit)? = null,
    ): AppContainer = AppContainer(
        context,
        secretReader = { name -> secrets[name] },
        secretRemover = { name -> secrets.remove(name) },
        secretWriter = { name, value -> secrets[name] = value },
        chatBoundEndpointWriterForTest = chatBoundEndpointWriterForTest,
    )

    private fun serverBase(): String = server.url("/v1").toString()

    private fun serverOrigin(): String = BaseUrl.origin(serverBase())

    private fun completionResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"choices":[{"message":{"role":"assistant","content":"在"}}]}""")

    private fun chatRequest(): ChatRequest = ChatRequest(
        model = "deepseek-flash",
        messages = listOf(ChatMessage(role = "user", content = "在吗")),
        stream = false,
    )

    // ---------------------------------------------------------------- 第③步写绑定失败

    @Test
    fun `第③步写绑定失败 保存失败且持久态不可用`() = runBlocking {
        // 旧状态：可用（旧地址 + 旧密钥）。
        container.settings.setChatBoundEndpoint(serverBase(), "deepseek-flash", serverOrigin())
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A
        // 只可能在守卫回归（真的建了客户端并调用）时被消费 → requestCount 断言必红。
        server.enqueue(completionResponse())

        val failing = newContainer(
            chatBoundEndpointWriterForTest = { _, _, _ -> error("step3 boom") },
        )
        val vm = ConfigViewModel(failing, NoopTester)
        vm.onBaseUrlChange(SILICONFLOW)
        vm.onModelChange("Qwen")
        vm.onApiKeyChange(PLACEHOLDER_KEY_B)

        assertFalse("第③步失败不能算保存成功", vm.saveChat())
        assertFalse("saved 必须 false", vm.state.value.saved)
        assertEquals(CredentialCopy.SAVE_FAILED, vm.state.value.saveError)

        val client = failing.llmClient()
        if (client != null) runCatching { client.complete(chatRequest()) }
        assertNull("失败后不能残留可用客户端", client)

        val persisted = failing.settings.flow.first()
        assertFalse("失败后不能还显示有可用密钥", persisted.hasChatKey)
        assertEquals("失败后 host 必须清空", "", persisted.chatKeyHost)
        assertFalse("新密文不能残留", secrets.containsKey(CHAT_KEY_NAME))
        assertEquals("保存失败不能产生任何请求", 0, server.requestCount)
    }

    // ---------------------------------------------------------------- 第④步回读不一致 +

    @Test
    fun `第④步回读不一致 回滚且清识图探测结论`() = runBlocking {
        container.settings.setChatBoundEndpoint(serverBase(), "deepseek-flash", serverOrigin())
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A
        // 旧识图结论属于「旧地址 + 旧模型」；③ 改了 endpoint 之后它必须失效。
        container.settings.setVisionProbe("deepseek-flash", supported = true)
        server.enqueue(completionResponse())

        val failing = newContainer(
            chatBoundEndpointWriterForTest = { baseUrl, model, _ ->
                // ③「写入成功」但写成了别的 host：④ 回读必然不一致（且 endpoint 已改）。
                SettingsStore(context).setChatBoundEndpoint(baseUrl, model, OTHER)
            },
        )
        val vm = ConfigViewModel(failing, NoopTester)
        vm.onBaseUrlChange(SILICONFLOW)
        vm.onModelChange("Qwen")
        vm.onApiKeyChange(PLACEHOLDER_KEY_B)

        assertFalse("第④步不一致不能算保存成功", vm.saveChat())
        assertFalse("saved 必须 false", vm.state.value.saved)
        assertEquals(CredentialCopy.SAVE_FAILED, vm.state.value.saveError)

        val persisted = failing.settings.flow.first()
        assertFalse("回滚后不能有可用密钥", persisted.hasChatKey)
        assertEquals("回滚后 host 必须清空", "", persisted.chatKeyHost)
        assertFalse("新密文被回滚删掉", secrets.containsKey(CHAT_KEY_NAME))
        assertEquals("③改过 endpoint → 旧识图结论必须失效（model）", "", persisted.visionProbeModel)
        assertEquals("③改过 endpoint → 旧识图结论必须失效（supported）", "", persisted.visionProbeSupported)
        assertEquals("③改过 endpoint → 旧识图结论必须失效（at）", 0L, persisted.visionProbeAt)

        assertNull("回滚后不能残留可用客户端", failing.llmClient())
        assertEquals("保存失败不能产生任何请求", 0, server.requestCount)
    }

    // ---------------------------------------------------------------- 精确性（不清仍有效的结论）

    @Test
    fun `第③步失败但 endpoint 没变 不清识图探测结论`() = runBlocking {
        // 与上一条对照：③直接抛错时 endpoint 没被写入，旧结论仍然对应当前地址 + 模型。
        container.settings.setChatBoundEndpoint(serverBase(), "deepseek-flash", serverOrigin())
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A
        container.settings.setVisionProbe("deepseek-flash", supported = true)
        server.enqueue(completionResponse())

        val failing = newContainer(
            chatBoundEndpointWriterForTest = { _, _, _ -> error("step3 boom") },
        )
        val vm = ConfigViewModel(failing, NoopTester)
        vm.onBaseUrlChange(SILICONFLOW)
        vm.onModelChange("Qwen")
        vm.onApiKeyChange(PLACEHOLDER_KEY_B)

        assertFalse(vm.saveChat())

        val persisted = failing.settings.flow.first()
        assertEquals(
            "endpoint 没变时不能丢掉仍然有效的识图结论",
            "deepseek-flash",
            persisted.visionProbeModel,
        )
        assertEquals("true", persisted.visionProbeSupported)
        assertTrue("探测结论仍在", persisted.visionProbeAt > 0L)
    }
}
