package cc.pulsez.app.ui.chat

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.audio.ReaderStatus
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.net.HttpFactory
import cc.pulsez.app.ui.copy.ImageCopy
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** DeepSeek 安全终态拒绝的本地端到端聊天回归；网络仅由 MockWebServer 接收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatDeepSeekRefusalEndToEndTest {

    private companion object {
        const val APP_DB_NAME = "pulsez.db"
        const val REFUSED_PREFIX = "这段合成正文最终会被拒绝。"
        const val REFUSED_REASONING = "这段合成思考也必须清掉。"
    }

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer
    private lateinit var characterId: String
    private lateinit var threadId: String
    private lateinit var segmentId: String
    private val secrets = mutableMapOf<String, String>()
    private var viewModelStore: ViewModelStore? = null

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(APP_DB_NAME)
        SettingsStore(context).update { it.clear() }
        AttachmentStore.install(AttachmentStore.forContext(context))
        secrets.clear()
        server = MockWebServer()
        server.start()
        container = AppContainer(
            context,
            secretReader = { name -> secrets[name] },
            secretRemover = { name -> secrets.remove(name) },
            secretWriter = { name, value -> secrets[name] = value },
        )
        installLocalDeepSeekDns(container)
        container.saveChatCredential(
            baseUrl = "http://api.deepseek.com:${server.port}/v1",
            model = "deepseek-chat",
            keyInput = "synthetic-test-key",
        )
        container.settings.setAutoRead(true)
        val (character, thread) = container.characters.createWithThread(name = "合成角色", persona = "仅用于离线测试")
        characterId = character.id
        threadId = thread.id
        segmentId = thread.activeSegmentId!!
        container.readVisibility.setAppForeground(true)
        container.readVisibility.setVisibleChatCharacter(characterId)
    }

    @After
    fun tearDown() {
        viewModelStore?.clear()
        viewModelStore = null
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        runCatching { container.database.close() }
        runCatching { server.shutdown() }
        AttachmentStore.resetForTest()
        secrets.clear()
        context.deleteDatabase(APP_DB_NAME)
    }

    @Test
    fun `DeepSeek content_filter withdraws streamed body and thinking without persisting assistant content`() = runBlocking {
        server.enqueue(
            sseResponse(
                """{"choices":[{"delta":{"content":"$REFUSED_PREFIX"}}]}""",
                """{"choices":[{"delta":{"reasoning_content":"$REFUSED_REASONING"}}]}""",
                """{"choices":[{"delta":{"content":"尾部也不应保留"},"finish_reason":"content_filter"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":9}}""",
            ),
        )

        val store = ViewModelStore()
        viewModelStore = store
        val viewModel = ChatViewModel(container, characterId)
        store.put("deepseek-refusal", viewModel)
        waitUntil(viewModel) { it.ready }
        assertEquals("朗读观察从未初始化状态开始", ReaderStatus.IDLE, container.reader.state.value.status)

        viewModel.onInputChange("合成测试输入")
        viewModel.send()
        waitUntil(viewModel) {
            !it.streaming && it.messages.size == 2 && it.messages.last().failed
        }

        val assistant = viewModel.state.value.messages.last()
        assertEquals(ChatViewModel.REPLY_FAILED_PLACEHOLDER, assistant.text)
        assertEquals(ImageCopy.FILTERED_REPLY, viewModel.state.value.error)
        assertTrue(assistant.failed)
        assertNull("拒绝的思考不能继续显示", assistant.reasoning)
        assertTrue("拒绝结果不能保留引用", assistant.citations.isEmpty())
        assertNull("拒绝结果不能保留工具进度", assistant.toolProgressText)
        assertEquals(1, server.requestCount)
        assertEquals("/v1/chat/completions", server.takeRequest().path)

        val rows = container.database.messageDao().allForThread(threadId)
        assertEquals("用户输入仍保留，拒绝正文不得新增助手行", listOf("user"), rows.map { it.role })
        assertFalse(rows.any { it.content.contains(REFUSED_PREFIX) || it.content.contains("尾部也不应保留") })
        assertTrue("拒绝轮不能写入记忆索引", container.database.chunkDao().forSegment(segmentId).isEmpty())
        assertEquals("失败路径不能触发自动朗读", ReaderStatus.IDLE, container.reader.state.value.status)
        assertFalse(container.reader.state.value.speaking)
    }

    private fun waitUntil(
        viewModel: ChatViewModel,
        predicate: (ChatViewModel.UiState) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate(viewModel.state.value)) return
            Thread.sleep(20L)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("等待 ChatViewModel 状态超时：${viewModel.state.value}", predicate(viewModel.state.value))
    }

    private fun sseResponse(vararg frames: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(buildString {
            frames.forEach { append("data: ").append(it).append("\n\n") }
            append("data: [DONE]\n\n")
        })

    /** AppContainer 默认 OkHttp 仍按 DeepSeek URL 发请求，但测试域名只能解析到本机。 */
    private fun installLocalDeepSeekDns(target: AppContainer) {
        val delegateField = AppContainer::class.java.getDeclaredField("http\$delegate").apply {
            isAccessible = true
        }
        val lazyDelegate = delegateField.get(target)
        val cachedValueField = lazyDelegate.javaClass.getDeclaredField("_value").apply {
            isAccessible = true
        }
        val localOnlyHttp = HttpFactory.create()
            .newBuilder()
            .proxy(Proxy.NO_PROXY)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname != "api.deepseek.com") throw UnknownHostException(hostname)
                    return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                }
            })
            .build()
        // Inject into the not-yet-evaluated Kotlin lazy delegate; no production code or global DNS is changed.
        cachedValueField.set(lazyDelegate, localOnlyHttp)
        assertTrue("AppContainer 应使用受控的 localhost 客户端", target.http === localOnlyHttp)
    }
}
