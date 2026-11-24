package cc.pulsez.app.ui.chat

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.ui.settings.CreativeModeCopy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 聊天页顶栏「入戏 / 出戏」切换与 `ChatViewModel.enterCreativeMode` / `exitCreativeMode` 离线行为测试。
 *
 *
 * - `app/src/main/java/cc/pulsez/app/ui/chat/ChatScreen.kt:1553`
 * - `app/src/main/java/cc/pulsez/app/ui/chat/ChatViewModel.kt:585`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatCreativeModeToggleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking { SettingsStore(context).update { it.clear() } }
    }

    @Test
    fun `normal mode shows enter button with 48dp touch target and opens intro dialog when unseen`() {
        val enterCalls = AtomicInteger()
        val seenCalls = AtomicInteger()
        val creativeMode = mutableStateOf(false)
        val introSeen = mutableStateOf(false)

        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    characterName = "阿淮",
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
                    creativeMode = creativeMode.value,
                    creativeModeIntroSeen = introSeen.value,
                    onEnterCreativeMode = {
                        enterCalls.incrementAndGet()
                        creativeMode.value = true
                    },
                    onCreativeModeIntroSeen = {
                        seenCalls.incrementAndGet()
                        introSeen.value = true
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("入戏")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(CreativeModeCopy.DIALOG_TITLE).assertIsDisplayed()
        assertEquals(0, enterCalls.get())

        composeRule.onNodeWithText(CreativeModeCopy.CONFIRM)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, enterCalls.get())
        assertEquals(1, seenCalls.get())
        composeRule.onNodeWithText(CreativeModeCopy.DIALOG_TITLE).assertDoesNotExist()
        composeRule.onNodeWithText("出戏").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `unseen intro dialog dismiss marks seen without entering creative mode`() {
        val enterCalls = AtomicInteger()
        val seenCalls = AtomicInteger()

        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    characterName = "阿淮",
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
                    creativeMode = false,
                    creativeModeIntroSeen = false,
                    onEnterCreativeMode = { enterCalls.incrementAndGet() },
                    onCreativeModeIntroSeen = { seenCalls.incrementAndGet() },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("入戏").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(CreativeModeCopy.DIALOG_TITLE).assertIsDisplayed()

        composeRule.onNodeWithText(CreativeModeCopy.DISMISS)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.waitForIdle()

        assertEquals(0, enterCalls.get())
        assertEquals(1, seenCalls.get())
        composeRule.onNodeWithText(CreativeModeCopy.DIALOG_TITLE).assertDoesNotExist()
        composeRule.onNodeWithText("入戏").assertIsDisplayed()
    }

    @Test
    fun `seen intro enters creative mode directly and creative mode exits directly`() {
        val enterCalls = AtomicInteger()
        val exitCalls = AtomicInteger()
        val creativeMode = mutableStateOf(false)

        composeRule.setContent {
            MaterialTheme {
                ChatScreen(
                    characterName = "阿淮",
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
                    creativeMode = creativeMode.value,
                    creativeModeIntroSeen = true,
                    onEnterCreativeMode = {
                        enterCalls.incrementAndGet()
                        creativeMode.value = true
                    },
                    onExitCreativeMode = {
                        exitCalls.incrementAndGet()
                        creativeMode.value = false
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("入戏").performClick()
        composeRule.waitForIdle()

        assertEquals(1, enterCalls.get())
        composeRule.onNodeWithText(CreativeModeCopy.DIALOG_TITLE).assertDoesNotExist()
        composeRule.onNodeWithText("出戏").assertIsDisplayed()
        composeRule.onNodeWithText("入戏").assertDoesNotExist()

        composeRule.onNodeWithText("出戏").performClick()
        composeRule.waitForIdle()

        assertEquals(1, exitCalls.get())
        composeRule.onNodeWithText("入戏").assertIsDisplayed()
    }

    @Test
    fun `viewModel enterCreativeMode and exitCreativeMode persist setting and update state`() {
        val container = AppContainer(context)
        try {
            val vm = ChatViewModel(container, "char-creative-toggle-test")

            vm.enterCreativeMode()
            awaitMainCondition {
                vm.state.value.creativeMode && vm.state.value.notice == ChatViewModel.ENTER_CREATIVE_MODE_NOTICE
            }
            assertTrue(runBlocking { container.settings.flow.first().creativeMode })

            vm.exitCreativeMode()
            awaitMainCondition {
                !vm.state.value.creativeMode && vm.state.value.notice == ChatViewModel.EXIT_CREATIVE_MODE_NOTICE
            }
            assertFalse(runBlocking { container.settings.flow.first().creativeMode })
        } finally {
            runCatching { container.database.close() }
        }
    }

    private fun awaitMainCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out waiting for ChatViewModel state update")
            }
            Thread.sleep(20L)
        }
    }
}
