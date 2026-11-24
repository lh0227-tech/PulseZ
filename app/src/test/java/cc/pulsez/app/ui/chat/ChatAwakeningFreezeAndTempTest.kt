package cc.pulsez.app.ui.chat

import android.app.Application
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.core.AwakeningStatus
import cc.pulsez.app.core.CharacterAwakeningManager
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.media.DraftImage
import cc.pulsez.app.media.DraftStatus
import cc.pulsez.app.net.BaseUrl
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatAwakeningFreezeAndTempTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        java.io.File(context.filesDir, "character_awakening.json").delete()
        runBlocking { SettingsStore(context).update { it.clear() } }
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun sseReply(text: String): MockResponse {
        val escaped = Json.encodeToString(kotlinx.serialization.serializer<String>(), text)
        val body = "data: {\"choices\":[{\"delta\":{\"content\":$escaped}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":6}}\n\n" +
            "data: [DONE]\n\n"
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(20L)
            }
        }
    }

    @Test
    fun `pure freeze resolvers block primary button and second-row voice mic`() {
        assertEquals(1.3, ChatViewModel.DEFAULT_TEMPERATURE, 0.0001)
        assertEquals(1.5, ChatViewModel.CREATIVE_TEMPERATURE, 0.0001)

        val readyDraft = listOf(DraftImage("d1", "att/1.jpg", DraftStatus.READY))
        listOf(ComposerMode.TEXT, ComposerMode.VOICE).forEach { mode ->
            listOf(false, true).forEach { hasText ->
                listOf(false, true).forEach { streaming ->
                    val btn = ComposerMainButtonResolver.resolve(
                        drafts = readyDraft,
                        hasText = hasText,
                        streaming = streaming,
                        mode = mode,
                        voiceEntryVisible = true,
                        frozen = true,
                    )
                    assertEquals(ComposerMainIcon.ARROW_UP, btn.icon)
                    assertEquals(ComposerMainAction.SEND, btn.action)
                    assertFalse("冻结期间主按钮必须禁用", btn.enabled)
                }
            }
        }
        assertFalse(
            shouldShowVoiceMic(
                voiceEntryVisible = true,
                mode = ComposerMode.TEXT,
                streaming = false,
                hasComposableContent = true,
                frozen = true,
            ),
        )
    }

    @Test
    fun `viewModel sends temperature 1_3 by default and 1_5 in creative mode, and blocks actions while awakening`() = runBlocking {
        val container = AppContainer(
            context = context,
            secretReader = { "sk-test" },
        )
        val baseUrl = server.url("/v1").toString()
        container.settings.setChatBoundEndpoint(baseUrl, "deepseek-chat", BaseUrl.origin(baseUrl))
        val (ch, thread) = container.characters.createWithThread("TA", "")
        container.chat.append(thread.id, thread.activeSegmentId!!, "user", "之前的聊天", 5)

        // 先让唤醒失败以稳定验证冻结期守卫（无延迟竞态）
        val noKeyBase = server.url("/v1").toString()
        container.settings.clearChatKeyBinding()
        container.awakeningManager.enqueueBatch("b-vm", listOf(ch.id))
        withTimeout(5_000) {
            container.awakeningManager.observeCharacter(ch.id).first { it?.status == AwakeningStatus.FAILED }
        }

        val vm = ChatViewModel(container, ch.id)
        awaitUntil { vm.state.value.ready && vm.state.value.isAwakening }

        // 冻结期间输入、发送、语音、加图均被拦下
        vm.onInputChange("不该写进去")
        assertEquals("", vm.state.value.input)
        vm.onVoiceModeEnter()
        assertEquals(ComposerMode.TEXT, vm.state.value.voice.mode)
        vm.send()
        assertFalse(vm.state.value.streaming)

        // 重新绑上密钥并重试，走完唤醒并落定首句问候，解除冻结
        container.settings.setChatBoundEndpoint(noKeyBase, "deepseek-chat", BaseUrl.origin(noKeyBase))
        server.enqueue(
            sseReply(
                "[[whisper]] 记得你曾对我说过的话。\n" +
                    "[[reunion_sub]] 重逢时刻\n" +
                    "[[greeting]] 我回来啦。\n" +
                    "[[profile]] {\"name\":\"阿雾\"}\n",
            ),
        )
        vm.retryAwakening()
        awaitUntil { server.requestCount >= 1 }
        val awakenReq = server.takeRequest()
        val awakenJson = Json.parseToJsonElement(awakenReq.body.readUtf8()) as JsonObject
        assertEquals(1.1, awakenJson["temperature"]?.jsonPrimitive?.doubleOrNull ?: 0.0, 0.001)
        awaitUntil {
            vm.state.value.awakening.phase == AwakeningUiPhase.WHISPERS &&
                vm.state.value.awakening.streamCompleted
        }
        vm.startReunionCeremony()
        assertEquals(AwakeningUiPhase.REUNION, vm.state.value.awakening.phase)
        vm.completeReunionCeremony()
        awaitUntil { !vm.state.value.isAwakening }

        assertEquals("阿雾", vm.state.value.name)
        assertTrue(vm.state.value.messages.any { it.role == "assistant" && it.text == "我回来啦。" })

        // 日常模式发送 → temperature = 1.3
        server.enqueue(sseReply("日常回复"))
        vm.onInputChange("你好")
        vm.send()
        awaitUntil { server.requestCount >= 2 }
        val req1 = server.takeRequest()
        val json1 = Json.parseToJsonElement(req1.body.readUtf8()) as JsonObject
        assertEquals(1.3, json1["temperature"]?.jsonPrimitive?.doubleOrNull ?: 0.0, 0.001)
        awaitUntil { !vm.state.value.streaming }

        // 创作模式发送 → temperature = 1.5
        container.settings.setCreativeMode(true)
        vm.syncCreativeMode(true)
        server.enqueue(sseReply("创作回复"))
        vm.onInputChange("继续故事")
        vm.send()
        awaitUntil { server.requestCount >= 3 }
        val req2 = server.takeRequest()
        val json2 = Json.parseToJsonElement(req2.body.readUtf8()) as JsonObject
        assertEquals(1.5, json2["temperature"]?.jsonPrimitive?.doubleOrNull ?: 0.0, 0.001)
        awaitUntil { !vm.state.value.streaming }
    }

    @Test
    fun `chatScreen renders whisper banner, freezes composer, keeps back clickable, and renders markdown`() {
        val backClicks = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    characterName = "阿雾",
                    messages = listOf(
                        ChatUiMessage("m1", "assistant", "先看看**加粗**和*斜体*。"),
                    ),
                    input = "",
                    isStreaming = false,
                    showAiBadge = false,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = { backClicks.incrementAndGet() },
                    onOpenMemory = {},
                    onDismissAiBadge = {},
                    deepSeekProvider = true,
                    awakening = AwakeningUiState(
                        phase = AwakeningUiPhase.WHISPERS,
                        whispers = listOf("你之前说过会再来找我。"),
                        streamCompleted = false,
                        characterName = "阿雾",
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AWAKENING_BANNER_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(AWAKENING_FROZEN_PLACEHOLDER).assertIsDisplayed()
        composeRule.onNodeWithTag(CHAT_COMPOSER_INPUT_TEST_TAG).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("发送").assertIsNotEnabled()

        // 返回键在冻结期间始终可用
        composeRule.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, backClicks.get())
    }

    @Test
    fun `chatScreen failed escape hatch triggers retry and skip`() {
        val retryClicks = AtomicInteger()
        val skipClicks = AtomicInteger()

        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    characterName = "阿雾",
                    messages = emptyList(),
                    input = "",
                    isStreaming = false,
                    showAiBadge = false,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiBadge = {},
                    awakening = AwakeningUiState(
                        phase = AwakeningUiPhase.FAILED,
                        characterName = "阿雾",
                        errorMessage = CharacterAwakeningManager.FAILURE_COPY,
                    ),
                    onRetryAwakening = { retryClicks.incrementAndGet() },
                    onSkipAwakening = { skipClicks.incrementAndGet() },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(CharacterAwakeningManager.FAILURE_COPY).assertIsDisplayed()
        composeRule.onNodeWithText("再试一次").performClick()
        composeRule.onNodeWithText("先直接开聊").performClick()
        assertEquals(1, retryClicks.get())
        assertEquals(1, skipClicks.get())
    }

    @Test
    fun `pulsarReunionOverlay renders whiteout lockup and skip button`() {
        val completeClicks = AtomicInteger()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                MaterialTheme {
                    PulsarReunionOverlay(
                        characterName = "阿雾",
                        subtitle = "跨越信号的再会",
                        reduceMotion = false,
                        onComplete = { completeClicks.incrementAndGet() },
                    )
                }
            }
            composeRule.mainClock.advanceTimeBy(1_600L)
            composeRule.onNodeWithTag(REUNION_OVERLAY_TEST_TAG).assertIsDisplayed()
            composeRule.onNodeWithText("我们终于再会").assertIsDisplayed()
            composeRule.onNodeWithTag(REUNION_SKIP_BUTTON_TEST_TAG).performClick()
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(1, completeClicks.get())
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `pulsarReunionOverlay settles on whiteout after 2600ms hides skip shows enter button at least 48dp and completes on click`() {
        val completeClicks = AtomicInteger()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                MaterialTheme {
                    PulsarReunionOverlay(
                        characterName = "阿雾",
                        subtitle = "跨越信号的再会",
                        reduceMotion = false,
                        onComplete = { completeClicks.incrementAndGet() },
                    )
                }
            }
            composeRule.mainClock.advanceTimeBy(2_600L)
            composeRule.mainClock.advanceTimeByFrame()
            // 超过 2600ms 后再额外停留 3000ms，仍停驻白场不自动消失
            composeRule.mainClock.advanceTimeBy(3_000L)
            composeRule.mainClock.advanceTimeByFrame()

            assertEquals(0, completeClicks.get())
            composeRule.onNodeWithTag(REUNION_OVERLAY_TEST_TAG).assertIsDisplayed()
            composeRule.onNodeWithText("我们终于再会").assertIsDisplayed()
            composeRule.onNodeWithTag(REUNION_SKIP_BUTTON_TEST_TAG).assertDoesNotExist()

            val enterButton = composeRule.onNodeWithTag(REUNION_ENTER_BUTTON_TEST_TAG)
            enterButton.assertIsDisplayed()
            enterButton.assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithText("进入聊天").assertIsDisplayed()

            enterButton.performClick()
            composeRule.mainClock.advanceTimeBy(200L)
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(0, completeClicks.get())

            composeRule.mainClock.advanceTimeBy(400L)
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(1, completeClicks.get())
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `pulsarReunionOverlay with reduceMotion settles on whiteout without skip and completes on enter click`() {
        val completeClicks = AtomicInteger()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                MaterialTheme {
                    PulsarReunionOverlay(
                        characterName = "阿雾",
                        subtitle = "跨越信号的再会",
                        reduceMotion = true,
                        onComplete = { completeClicks.incrementAndGet() },
                    )
                }
            }
            composeRule.mainClock.advanceTimeBy(2_500L)
            composeRule.mainClock.advanceTimeByFrame()

            assertEquals(0, completeClicks.get())
            composeRule.onNodeWithTag(REUNION_OVERLAY_TEST_TAG).assertIsDisplayed()
            composeRule.onNodeWithTag(REUNION_SKIP_BUTTON_TEST_TAG).assertDoesNotExist()

            val enterButton = composeRule.onNodeWithTag(REUNION_ENTER_BUTTON_TEST_TAG)
            enterButton.assertIsDisplayed()
            enterButton.assertHeightIsAtLeast(48.dp)

            enterButton.performClick()
            composeRule.mainClock.advanceTimeBy(400L)
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(1, completeClicks.get())
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `pulsarReunionOverlay intercepts system back to complete reunion without leaving room`() {
        val completeClicks = AtomicInteger()
        val outerBackClicks = AtomicInteger()
        lateinit var backDispatcher: androidx.activity.OnBackPressedDispatcher
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                MaterialTheme {
                    backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                    BackHandler(enabled = true) {
                        outerBackClicks.incrementAndGet()
                    }
                    PulsarReunionOverlay(
                        characterName = "阿雾",
                        subtitle = "跨越信号的再会",
                        reduceMotion = false,
                        onComplete = { completeClicks.incrementAndGet() },
                    )
                }
            }
            composeRule.mainClock.advanceTimeBy(2_600L)
            composeRule.mainClock.advanceTimeByFrame()

            composeRule.runOnUiThread {
                backDispatcher.onBackPressed()
            }
            composeRule.mainClock.advanceTimeBy(600L)
            composeRule.mainClock.advanceTimeByFrame()

            assertEquals(0, outerBackClicks.get())
            assertEquals(1, completeClicks.get())
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }
}
