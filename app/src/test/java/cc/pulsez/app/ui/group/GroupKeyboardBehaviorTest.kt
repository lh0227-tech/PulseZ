package cc.pulsez.app.ui.group

import android.content.Context
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.ui.chat.LocalImeVisibilityOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupKeyboardBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun matchingAcceptanceCallbackHidesKeyboardOnce() {
        val state = mutableStateOf(
            state(input = "hello", messages = listOf(userMessage("old-tail", "old"))),
        )
        val imeVisible = mutableStateOf(true)
        val keyboard = RecordingKeyboardController()
        val sendCalls = AtomicInteger()
        val acceptances = mutableListOf<() -> Unit>()

        compose.setContent {
            CompositionLocalProvider(
                LocalSoftwareKeyboardController provides keyboard,
                LocalImeVisibilityOverride provides imeVisible.value,
            ) {
                MaterialTheme {
                    GroupChatScreen(
                        state = state.value,
                        onInputChange = { state.value = state.value.copy(input = it) },
                        onSend = {},
                        onSendWithAcceptance = { accepted ->
                            sendCalls.incrementAndGet()
                            acceptances += accepted
                        },
                        onStop = {},
                        onBack = {},
                        onOpenMemory = {},
                        onDismissAiNotice = {},
                        onRetry = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitForIdle()
        assertEquals(1, sendCalls.get())
        assertEquals("a tap alone is not evidence the send was accepted", 0, keyboard.hideCalls.get())

        compose.runOnIdle {
            state.value = state.value.copy(
                messages = listOf(userMessage("older", "older")) + state.value.messages +
                    userMessage("other-user", "same body"),
            )
        }
        compose.waitForIdle()
        assertEquals("history changes do not imply acceptance", 0, keyboard.hideCalls.get())

        compose.runOnIdle { acceptances.single().invoke() }
        compose.waitForIdle()
        assertEquals(1, keyboard.hideCalls.get())
        compose.runOnIdle { acceptances.single().invoke() }
        compose.waitForIdle()
        assertEquals("one accepted request hides at most once", 1, keyboard.hideCalls.get())
    }

    @Test
    fun rejectedSendStopAndBlankInputDoNotHideKeyboard() {
        val state = mutableStateOf(state(input = "hello"))
        val imeVisible = mutableStateOf(true)
        val keyboard = RecordingKeyboardController()
        val stopCalls = AtomicInteger()
        val viewModel = GroupChatViewModel(
            AppContainer(ApplicationProvider.getApplicationContext()),
            "missing-group",
        )
        val viewModelStore = ViewModelStore().apply { put("group-rejected-send", viewModel) }

        try {
            compose.setContent {
            CompositionLocalProvider(
                LocalSoftwareKeyboardController provides keyboard,
                LocalImeVisibilityOverride provides imeVisible.value,
            ) {
                MaterialTheme {
                    GroupChatScreen(
                        state = state.value,
                        onInputChange = { state.value = state.value.copy(input = it) },
                        onSend = {},
                        onSendWithAcceptance = { onAccepted ->
                            viewModel.onInputChange(state.value.input)
                            viewModel.send(onAccepted)
                        },
                        onStop = { stopCalls.incrementAndGet() },
                        onBack = {},
                        onOpenMemory = {},
                        onDismissAiNotice = {},
                        onRetry = {},
                    )
                }
            }
            }

            compose.onNodeWithContentDescription("发送").performClick()
            compose.waitForIdle()
            assertEquals("the real ViewModel rejects because it has no members", 0, keyboard.hideCalls.get())

            compose.runOnIdle { state.value = state.value.copy(streaming = true) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("停下").performClick()
            compose.waitForIdle()
            assertEquals(1, stopCalls.get())
            assertEquals(0, keyboard.hideCalls.get())

            compose.runOnIdle { state.value = state.value.copy(streaming = false, input = "  ") }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
            assertEquals(0, keyboard.hideCalls.get())
        } finally {
            viewModelStore.clear()
        }
    }

    @Test
    fun sessionChangeAndImeReopenCancelDelayedHideAndMultilineTextIsPreserved() {
        val state = mutableStateOf(state())
        val imeVisible = mutableStateOf(true)
        val keyboard = RecordingKeyboardController()
        var delayedAcceptance: (() -> Unit)? = null

        compose.setContent {
            CompositionLocalProvider(
                LocalSoftwareKeyboardController provides keyboard,
                LocalImeVisibilityOverride provides imeVisible.value,
            ) {
                MaterialTheme {
                    Box(Modifier.width(420.dp).height(500.dp).testTag("group-viewport")) {
                        GroupChatScreen(
                            state = state.value,
                            onInputChange = { state.value = state.value.copy(input = it) },
                            onSend = {},
                            onSendWithAcceptance = { delayedAcceptance = it },
                            onStop = {},
                            onBack = {},
                            onOpenMemory = {},
                            onDismissAiNotice = {},
                            onRetry = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag(GROUP_COMPOSER_INPUT_TEST_TAG)
            .performTextInput("line one\nline two")
        compose.waitForIdle()
        assertEquals("line one\nline two", state.value.input)

        compose.onNodeWithContentDescription("发送").performClick()
        compose.onNodeWithTag(GROUP_COMPOSER_INPUT_TEST_TAG).performTextInput(" more")
        compose.waitForIdle()
        assertEquals("line one\nline two more", state.value.input)
        compose.runOnIdle { delayedAcceptance?.invoke() }
        compose.waitForIdle()
        assertEquals("new input cancels a previous request's delayed hide", 0, keyboard.hideCalls.get())

        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle { imeVisible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { imeVisible.value = true }
        compose.waitForIdle()
        compose.runOnIdle { delayedAcceptance?.invoke() }
        compose.waitForIdle()
        assertEquals("a later acceptance must not hide a keyboard reopened by the user", 0, keyboard.hideCalls.get())

        val viewportBottom = compose.onNodeWithTag("group-viewport")
            .fetchSemanticsNode().boundsInRoot.bottom
        val composerBottom = compose.onNodeWithTag(GROUP_COMPOSER_INPUT_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot.bottom
        val sendButtonBottom = compose.onNodeWithContentDescription("发送")
            .fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("the multiline input remains inside the shortened viewport", composerBottom <= viewportBottom)
        assertTrue("the send control remains inside the shortened viewport", sendButtonBottom <= viewportBottom)
    }

    @Test
    fun changingGroupSessionInvalidatesOldAcceptanceCallback() {
        val state = mutableStateOf(state(input = "hello"))
        val imeVisible = mutableStateOf(true)
        val keyboard = RecordingKeyboardController()
        var oldAcceptance: (() -> Unit)? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalSoftwareKeyboardController provides keyboard,
                LocalImeVisibilityOverride provides imeVisible.value,
            ) {
                MaterialTheme {
                    GroupChatScreen(
                        state = state.value,
                        onInputChange = { state.value = state.value.copy(input = it) },
                        onSend = {},
                        onSendWithAcceptance = { oldAcceptance = it },
                        onStop = {},
                        onBack = {},
                        onOpenMemory = {},
                        onDismissAiNotice = {},
                        onRetry = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle { state.value = state.value.copy(historySessionId = "another-session") }
        compose.waitForIdle()
        compose.runOnIdle { oldAcceptance?.invoke() }
        compose.waitForIdle()
        assertEquals("callbacks from the prior history session are discarded", 0, keyboard.hideCalls.get())
    }

    @Test
    fun imeInsetsMoveComposerAboveSyntheticKeyboard() {
        val keyboard = RecordingKeyboardController()
        var composeHost: View? = null
        var density = 1f
        compose.setContent {
            val host = LocalView.current
            val currentDensity = LocalDensity.current.density
            SideEffect {
                composeHost = host
                density = currentDensity
            }
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                MaterialTheme {
                    Box(Modifier.width(420.dp).height(720.dp).testTag("group-inset-viewport")) {
                        GroupChatScreen(
                            state = state(),
                            onInputChange = {},
                            onSend = {},
                            onStop = {},
                            onBack = {},
                            onOpenMemory = {},
                            onDismissAiNotice = {},
                            onRetry = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val beforeBottom = compose.onNodeWithContentDescription("发送")
            .fetchSemanticsNode().boundsInRoot.bottom
        dispatchImeInsets(composeHost ?: error("Compose host missing"), density, 220)
        compose.waitForIdle()
        val afterBottom = compose.onNodeWithContentDescription("发送")
            .fetchSemanticsNode().boundsInRoot.bottom
        val insetPx = 220f * density
        assertTrue("imePadding moves the group send button up by the synthetic IME inset", beforeBottom - afterBottom >= insetPx * 0.8f)
    }

    @Test
    fun realGroupViewModelRejectsWithoutMembersOrAcceptance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(context)
        val viewModel = GroupChatViewModel(container, "missing-group")
        val store = ViewModelStore().apply { put("group-test", viewModel) }
        val accepted = AtomicInteger()
        try {
            viewModel.onInputChange("hello")
            viewModel.send { accepted.incrementAndGet() }
            assertEquals("member validation rejects before acceptance", 0, accepted.get())
            assertTrue("the ViewModel records its real validation failure", viewModel.state.value.error != null)
        } finally {
            store.clear()
        }
    }

    @Test
    fun realGroupViewModelAcceptsValidSendAndSignalsExactlyOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "pulsez.db"
        context.deleteDatabase(databaseName)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val container = AppContainer(
            context,
            secretReader = { null },
            secretRemover = {},
            secretWriter = { _, _ -> },
        )
        val store = ViewModelStore()
        var viewModel: GroupChatViewModel? = null
        try {
            SettingsStore(context).update { it.clear() }
            val (first, _) = container.characters.createWithThread(
                name = "Keyboard test member one",
                persona = "Synthetic test fixture",
            )
            val (second, _) = container.characters.createWithThread(
                name = "Keyboard test member two",
                persona = "Synthetic test fixture",
            )
            val groupThread = container.chat.createGroupThread(listOf(first.id, second.id))
            val vm = GroupChatViewModel(container, groupThread.id)
            viewModel = vm
            store.put("group-accepted-send", vm)
            vm.refresh()
            awaitMainCondition("group-chat ViewModel history") {
                vm.state.value.historyLoaded
            }

            val syntheticText = "synthetic accepted group message"
            val accepted = AtomicInteger()
            val stateAtAcceptance = AtomicReference<GroupChatUiState?>()
            vm.onInputChange(syntheticText)
            vm.send {
                stateAtAcceptance.set(vm.state.value)
                accepted.incrementAndGet()
            }
            awaitMainCondition("group-chat send acceptance") { accepted.get() == 1 }

            val acceptedState = requireNotNull(stateAtAcceptance.get())
            assertEquals("the accepted send clears the composer", "", acceptedState.input)
            assertTrue("the accepted send starts a turn", acceptedState.streaming)
            assertEquals(GroupMessageRole.USER, acceptedState.messages.last().role)
            assertEquals(syntheticText, acceptedState.messages.last().text)
            val persisted = container.chat.groupHistory(groupThread.id)
            assertEquals(
                "the acceptance callback follows the persisted user message",
                1,
                persisted.count { it.role == "user" && it.content == syntheticText },
            )
            assertEquals("the real ViewModel reports acceptance once", 1, accepted.get())
        } finally {
            viewModel?.stop()
            store.clear()
            runCatching { container.database.close() }
            context.deleteDatabase(databaseName)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun stopInvalidatesPendingAcceptanceCallback() {
        val state = mutableStateOf(state(input = "hello"))
        val keyboard = RecordingKeyboardController()
        var pendingAcceptance: (() -> Unit)? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalSoftwareKeyboardController provides keyboard,
                LocalImeVisibilityOverride provides true,
            ) {
                MaterialTheme {
                    GroupChatScreen(
                        state = state.value,
                        onInputChange = { state.value = state.value.copy(input = it) },
                        onSend = {},
                        onSendWithAcceptance = { pendingAcceptance = it },
                        onStop = {},
                        onBack = {},
                        onOpenMemory = {},
                        onDismissAiNotice = {},
                        onRetry = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle { state.value = state.value.copy(streaming = true) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("停下").performClick()
        compose.runOnIdle { pendingAcceptance?.invoke() }
        compose.waitForIdle()
        assertEquals("stop invalidates a delayed acceptance event", 0, keyboard.hideCalls.get())
    }

    private fun state(
        input: String = "",
        messages: List<GroupMessageUi> = emptyList(),
    ) = GroupChatUiState(
        members = listOf(GroupMemberUi("m1", "One"), GroupMemberUi("m2", "Two")),
        messages = messages,
        historyLoaded = true,
        historySessionId = "test-session",
        input = input,
    )

    private fun userMessage(id: String, text: String) =
        GroupMessageUi(id = id, role = GroupMessageRole.USER, text = text)

    private class RecordingKeyboardController : SoftwareKeyboardController {
        val hideCalls = AtomicInteger()
        override fun show() = Unit
        override fun hide() { hideCalls.incrementAndGet() }
    }

    private fun dispatchImeInsets(host: View, density: Float, heightDp: Int) {
        val bottomPx = (heightDp * density).toInt()
        val imeInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottomPx))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        ViewCompat.dispatchApplyWindowInsets(host, imeInsets)
    }

    private fun TestScope.awaitMainCondition(description: String, condition: () -> Boolean) {
        val deadlineNanos = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            runCurrent()
            check(System.nanoTime() < deadlineNanos) { "Timed out waiting for $description" }
            Thread.sleep(5)
        }
        runCurrent()
    }
}
