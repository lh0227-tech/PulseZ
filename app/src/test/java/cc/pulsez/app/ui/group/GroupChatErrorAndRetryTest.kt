package cc.pulsez.app.ui.group

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.net.ApiError
import cc.pulsez.app.ui.theme.PulsezTheme
import cc.pulsez.app.wire.ErrorCopy
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ：群聊失败原因透传、失败重试按钮高度 >= 48dp、以及 `GroupChatViewModel.retry`
 * 保留 `@` 点名语义 / 再次失败更新提示 / 成功后原位替换的离线行为测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GroupChatErrorAndRetryTest {

    private companion object {
        const val APP_DB_NAME = "pulsez.db"
    }

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer
    private lateinit var threadId: String
    private lateinit var memberAId: String
    private val secrets = mutableMapOf<String, String>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(APP_DB_NAME)
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
        container.saveChatCredential(
            baseUrl = server.url("/v1").toString(),
            model = "deepseek-chat",
            keyInput = "test-key",
        )
        val (charA, _) = container.characters.createWithThread(name = "阿甲", persona = "沉稳")
        val (charB, _) = container.characters.createWithThread(name = "阿乙", persona = "活泼")
        memberAId = charA.id
        val group = container.chat.createGroupThread(listOf(charA.id, charB.id), "测试群")
        threadId = group.id
    }

    @After
    fun tearDown() {
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        runCatching { container.database.close() }
        runCatching { server.shutdown() }
        secrets.clear()
        context.deleteDatabase(APP_DB_NAME)
    }

    private fun sseSuccess(text: String): MockResponse {
        val body = buildString {
            append("""data: {"choices":[{"delta":{"content":"$text"}}]}""").append("\n\n")
            append("""data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":6,"total_tokens":16}}""").append("\n\n")
            append("data: [DONE]\n\n")
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    private fun waitUntilIdle(vm: GroupChatViewModel, predicate: (GroupChatUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate(vm.state.value)) return
            Thread.sleep(20L)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("等待状态超时，当前状态：${vm.state.value}", predicate(vm.state.value))
    }

    @Test
    fun failedRowDisplaysErrorCopyWithRetrySuffixAndMeets48dpMinHeight() {
        val hint = ErrorCopy.of(ApiError.Auth(httpCode = 401))
        val expectedText = "$hint（点这里重试）"
        var retriedId: String? = null
        val state = GroupChatUiState(
            historyLoaded = true,
            historySessionId = "err-ui",
            members = listOf(GroupMemberUi("a", "阿甲"), GroupMemberUi("b", "阿乙")),
            messages = listOf(
                GroupMessageUi(id = "u1", role = GroupMessageRole.USER, text = "在吗"),
                GroupMessageUi(
                    id = "f1",
                    role = GroupMessageRole.MEMBER,
                    speakerId = "a",
                    speakerName = "阿甲",
                    failed = true,
                    failureHint = hint,
                ),
            ),
        )

        compose.setContent {
            PulsezTheme {
                GroupChatScreen(
                    state = state,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiNotice = {},
                    onRetry = { retriedId = it },
                )
            }
        }
        compose.waitForIdle()

        val node = compose.onNodeWithText(expectedText)
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("重试按钮高度必须 >= 48.dp，实际=${bounds.height}", bounds.height >= 48.dp)

        node.performClick()
        assertEquals("f1", retriedId)
    }

    @Test
    fun retryPreservesMentionSemanticsUpdatesHintOnSecondFailureAndReplacesInPlaceOnSuccess() {
        val authHint = ErrorCopy.of(ApiError.Auth(httpCode = 401))
        val quotaHint = ErrorCopy.of(ApiError.Quota(httpCode = 402))
        // 初次 @阿甲 发送：generateMentionMain 最多尝试 2 次，两次都给 401
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))

        val vm = GroupChatViewModel(container, threadId)
        vm.refresh()
        waitUntilIdle(vm) { it.historyLoaded }

        vm.onInputChange("@阿甲 帮我想想")
        vm.send()
        waitUntilIdle(vm) { !it.streaming && it.messages.any { msg -> msg.failed } }

        val firstFailed = vm.state.value.messages.single { it.failed }
        assertEquals(memberAId, firstFailed.speakerId)
        assertTrue("点名失败行必须保留 addressed=true", firstFailed.addressed)
        assertEquals(authHint, firstFailed.failureHint)

        // 第一次 retry：再次失败（402 额度用完），应更新该行 failureHint，不追加新行
        server.enqueue(MockResponse().setResponseCode(402).setBody("quota"))
        server.enqueue(MockResponse().setResponseCode(402).setBody("quota"))
        vm.retry(firstFailed.id)
        waitUntilIdle(vm) {
            !it.streaming && it.messages.any { msg -> msg.id == firstFailed.id && msg.failureHint == quotaHint }
        }
        assertEquals(1, vm.state.value.messages.count { it.failed })

        // 第二次 retry：第 1 次尝试模型回 [PASS]，因为保留了点名语义（addressedIds），
        // 引擎不会换给阿乙，而是给阿甲第 2 次机会并成功替换原失败行。
        server.enqueue(sseSuccess("[PASS]"))
        server.enqueue(sseSuccess("我想好了，就这样办。"))
        vm.retry(firstFailed.id)
        waitUntilIdle(vm) {
            !it.streaming && it.messages.none { msg -> msg.failed }
        }

        val replaced = vm.state.value.messages.single { it.id == firstFailed.id }
        assertFalse(replaced.failed)
        assertEquals(memberAId, replaced.speakerId)
        assertTrue("重试成功后仍应保留「点了 TA 的名」标记", replaced.addressed)
        assertEquals("我想好了，就这样办。", replaced.text)
        assertNull("重试成功后应清空全局 error", vm.state.value.error)
    }
}
