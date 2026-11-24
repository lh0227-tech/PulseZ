package cc.pulsez.app.ui.config

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * `ConfigViewModel` 的保存/清除/断开接线。
 *
 * 重点不是重测三步协议（`CredentialSaveWorkflowTest` 已覆盖每一步失败点），而是锁
 * **ViewModel 层**的对外契约：
 * - 失败/被拒 `saved=false`、返回 false（AppRoot 据此不 `nav.back`）；
 * - 成功 `saved=true`、返回 true、输入框清空；
 * - `clearChatKey` / `disconnectEmbed` 后持久配置与状态行同步；
 * - 「知道了」只清一次性提示。
 *
 * 密钥用注入的内存实现（Robolectric 不能用 AndroidKeyStore）；Main 换 TestDispatcher，
 * 避免 `viewModelScope` 的采集器在后台与断言抢时序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfigViewModelCredentialTest {

    private companion object {
        const val DEEPSEEK = "https://api.deepseek.com"
        const val DEEPSEEK_V1 = "https://api.deepseek.com/v1"
        const val SILICONFLOW = "https://api.siliconflow.cn/v1"
        const val ZHIPU = "https://open.bigmodel.cn/api/paas/v4"
        const val CHAT_KEY_NAME = "chat_api_key"
        const val EMBED_KEY_NAME = "embed_api_key"
        const val PLACEHOLDER_KEY_A = "test-key-A"
        const val PLACEHOLDER_KEY_B = "test-key-B"
    }

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var vm: ConfigViewModel
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
        container = AppContainer(
            context,
            secretReader = { name -> secrets[name] },
            secretRemover = { name -> secrets.remove(name) },
            secretWriter = { name, value -> secrets[name] = value },
        )
        vm = ConfigViewModel(container, NoopTester)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        secrets.clear()
    }

    // ---------------------------------------------------------------- saveChat

    @Test
    fun `saveChat 换服务商留空密钥 返回 false 显示重填人话`() = runBlocking {
        container.settings.setChatBoundEndpoint(DEEPSEEK_V1, "deepseek-flash", DEEPSEEK)
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A

        vm.onBaseUrlChange(SILICONFLOW)
        vm.onModelChange("Qwen")

        assertFalse("换服务商留空密钥不能算保存成功", vm.saveChat())
        val state = vm.state.value
        assertFalse("saved 必须 false", state.saved)
        assertEquals("要显示逐字的重填提示", CredentialCopy.REQUIRE_NEW_KEY_CHAT, state.saveError)
        assertEquals("新地址不能被写进去", DEEPSEEK_V1, container.settings.flow.first().chatBaseUrl)
        assertEquals(PLACEHOLDER_KEY_A, secrets[CHAT_KEY_NAME])
    }

    @Test
    fun `saveChat 坏地址 返回 false 且给出清除出路`() = runBlocking {
        container.settings.setChatBoundEndpoint(DEEPSEEK_V1, "deepseek-flash", DEEPSEEK)
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A
        vm.onBaseUrlChange("https://")
        vm.onModelChange("deepseek-flash")

        assertFalse(vm.saveChat())
        val state = vm.state.value
        assertFalse(state.saved)
        assertEquals(
            "有密钥的用户要看到「清除密钥」的出路",
            CredentialCopy.badOrigin(forEmbed = false, hasKey = true),
            state.saveError,
        )
        assertEquals(DEEPSEEK_V1, container.settings.flow.first().chatBaseUrl)
    }

    @Test
    fun `saveChat 模型为空 不写不假成功`() = runBlocking {
        vm.onBaseUrlChange(DEEPSEEK_V1)
        vm.onModelChange("")

        assertFalse(vm.saveChat())
        val state = vm.state.value
        assertFalse(state.saved)
        assertEquals(CredentialCopy.INCOMPLETE, state.saveError)
        assertEquals("没填全时不能写地址", "", container.settings.flow.first().chatBaseUrl)
    }

    @Test
    fun `saveChat 成功 返回 true saved 且清空密钥框`() = runBlocking {
        vm.onBaseUrlChange(DEEPSEEK_V1)
        vm.onModelChange("deepseek-flash")
        vm.onApiKeyChange(PLACEHOLDER_KEY_B)

        assertTrue(vm.saveChat())

        val state = vm.state.value
        assertTrue(state.saved)
        assertNull("成功要清错误", state.saveError)
        assertEquals("密钥框要清空（不回显明文）", "", state.apiKey)
        assertTrue("状态行要变成可用", state.chatKeyStatus is KeyStatus.Usable)
        val persisted = container.settings.flow.first()
        assertTrue(persisted.hasChatKey)
        assertEquals(DEEPSEEK, persisted.chatKeyHost)
        assertEquals(PLACEHOLDER_KEY_B, secrets[CHAT_KEY_NAME])
    }

    // ---------------------------------------------------------------- saveEmbed（G / B / 成功）

    @Test
    fun `saveEmbed 三格不全 返回 false 不写不假成功`() = runBlocking {
        vm.onEmbedBaseUrlChange(ZHIPU)
        vm.onEmbedModelChange("")

        assertFalse(vm.saveEmbed())
        val state = vm.state.value
        assertFalse(state.saved)
        assertEquals(CredentialCopy.INCOMPLETE, state.embedSaveError)
        assertEquals("没填全时不能写地址", "", container.settings.flow.first().embedBaseUrl)
    }

    @Test
    fun `saveEmbed 三格全空 不写不假成功`() = runBlocking {
        // 用户把找旧事三格清空后点保存： 不写、不假成功（G）；断开请用「停止使用找旧事」。
        assertFalse(vm.saveEmbed())
        val state = vm.state.value
        assertFalse(state.saved)
        assertEquals(CredentialCopy.INCOMPLETE, state.embedSaveError)
        val persisted = container.settings.flow.first()
        assertEquals("", persisted.embedBaseUrl)
        assertEquals("", persisted.embedModel)
        assertFalse(persisted.hasEmbedKey)
    }

    @Test
    fun `saveEmbed 换域留空密钥 返回 false 显示重填人话`() = runBlocking {
        container.settings.setEmbedBoundEndpoint(ZHIPU, "embedding-3", "https://open.bigmodel.cn")
        secrets[EMBED_KEY_NAME] = PLACEHOLDER_KEY_A
        vm.onEmbedBaseUrlChange("https://api.openai.com/v1")
        vm.onEmbedModelChange("text-embedding-3-small")

        assertFalse(vm.saveEmbed())
        val state = vm.state.value
        assertFalse(state.saved)
        assertEquals(CredentialCopy.REQUIRE_NEW_KEY_EMBED, state.embedSaveError)
        assertEquals("新地址不能被写进去", ZHIPU, container.settings.flow.first().embedBaseUrl)
    }

    @Test
    fun `saveEmbed 成功 返回 true 且绑定到新地址`() = runBlocking {
        vm.onEmbedBaseUrlChange(ZHIPU)
        vm.onEmbedModelChange("embedding-3")
        vm.onEmbedApiKeyChange(PLACEHOLDER_KEY_B)

        assertTrue(vm.saveEmbed())

        val state = vm.state.value
        assertTrue(state.saved)
        assertNull(state.embedSaveError)
        assertEquals("", state.embedApiKey)
        assertTrue(state.embedKeyStatus is KeyStatus.Usable)
        val persisted = container.settings.flow.first()
        assertTrue(persisted.hasEmbedKey)
        assertEquals("https://open.bigmodel.cn", persisted.embedKeyHost)
        assertEquals(PLACEHOLDER_KEY_B, secrets[EMBED_KEY_NAME])
    }

    // ---------------------------------------------------------------- 清除 / 断开 / 一次性提示

    @Test
    fun `clearChatKey 后持久配置清掉 状态行变成还没有密钥`() = runBlocking {
        container.settings.setChatBoundEndpoint(DEEPSEEK_V1, "deepseek-flash", DEEPSEEK)
        secrets[CHAT_KEY_NAME] = PLACEHOLDER_KEY_A

        assertTrue(vm.clearChatKey())

        val persisted = container.settings.flow.first()
        assertFalse(persisted.hasChatKey)
        assertEquals("", persisted.chatKeyHost)
        assertFalse(secrets.containsKey(CHAT_KEY_NAME))
        val state = vm.state.value
        assertFalse("saved 不能被清除动作置 true", state.saved)
        assertEquals(KeyStatus.None, state.chatKeyStatus)
        assertNull("清除成功后不应显示错误文案", state.saveError)
    }

    @Test
    fun `disconnectEmbed 后配置清空 页面输入也清空`() = runBlocking {
        container.settings.setEmbedBoundEndpoint(ZHIPU, "embedding-3", "https://open.bigmodel.cn")
        secrets[EMBED_KEY_NAME] = PLACEHOLDER_KEY_A
        vm.onEmbedBaseUrlChange(ZHIPU)

        assertTrue(vm.disconnectEmbed())

        val persisted = container.settings.flow.first()
        assertEquals("", persisted.embedBaseUrl)
        assertEquals("", persisted.embedModel)
        assertFalse(persisted.hasEmbedKey)
        assertFalse(secrets.containsKey(EMBED_KEY_NAME))
        val state = vm.state.value
        assertEquals("", state.embedBaseUrl)
        assertEquals("", state.embedModel)
        assertEquals("", state.embedApiKey)
        assertEquals(KeyStatus.None, state.embedKeyStatus)
    }

    @Test
    fun `知道了 只清一次性提示 不改配置`() = runBlocking {
        container.settings.setChatKeyResetAfterUpgrade(true)
        container.settings.setEmbedKeyResetAfterUpgrade(true)

        vm.ackKeyResetNotice()
        vm.ackEmbedKeyResetNotice()

        val persisted = container.settings.flow.first()
        assertFalse(persisted.chatKeyResetAfterUpgrade)
        assertFalse(persisted.embedKeyResetAfterUpgrade)
        assertFalse(vm.state.value.keyResetNotice)
        assertFalse(vm.state.value.embedKeyResetNotice)
    }
}
