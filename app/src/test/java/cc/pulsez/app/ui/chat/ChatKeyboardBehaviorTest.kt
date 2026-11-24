package cc.pulsez.app.ui.chat

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.data.attachments.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import cc.pulsez.app.media.DraftStatus
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
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
class ChatKeyboardBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun matchingAcceptanceCallbackHidesKeyboardOnce() {
        val input = mutableStateOf("hello")
        val messages = mutableStateOf(listOf(ChatUiMessage("old-tail", "user", "old")))
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
                    ChatScreen(
                        characterName = "Test",
                        messages = messages.value,
                        input = input.value,
                        isStreaming = false,
                        showAiBadge = false,
                        onInputChange = { input.value = it },
                        onSend = {},
                        onSendWithAcceptance = { accepted ->
                            sendCalls.incrementAndGet()
                            acceptances += accepted
                        },
                        onStop = {},
                        onBack = {},
                        onOpenMemory = {},
                        onDismissAiBadge = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitForIdle()
        assertEquals(1, sendCalls.get())
        assertEquals("a tap alone is not evidence the send was accepted", 0, keyboard.hideCalls.get())

        // Unrelated list changes (history paging or another message) do not imply acceptance.
        compose.runOnIdle {
            messages.value = listOf(ChatUiMessage("older", "user", "older")) + messages.value +
                ChatUiMessage("other-user", "user", "same body")
        }
        compose.waitForIdle()
        assertEquals(0, keyboard.hideCalls.get())

        compose.runOnIdle { acceptances.single().invoke() }
        compose.waitForIdle()
        assertEquals(1, keyboard.hideCalls.get())
        compose.runOnIdle { acceptances.single().invoke() }
        compose.waitForIdle()
        assertEquals("one accepted request hides at most once", 1, keyboard.hideCalls.get())
    }

    @Test
    fun rejectedSendStopAndBlankInputDoNotHideKeyboard() {
        val input = mutableStateOf("hello")
        val messages = mutableStateOf(emptyList<ChatUiMessage>())
        val isStreaming = mutableStateOf(false)
        val imeVisible = mutableStateOf(true)
        val keyboard = RecordingKeyboardController()
        val stopCalls = AtomicInteger()
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        val viewModel = ChatViewModel(container, "missing-character")
        val viewModelStore = ViewModelStore().apply { put("chat-rejected-send", viewModel) }

        try {
            compose.setContent {
            CompositionLocalProvider(
                LocalSoftwareKeyboardController provides keyboard,
                LocalImeVisibilityOverride provides imeVisible.value,
            ) {
                MaterialTheme {
                    ChatScreen(
                        characterName = "Test",
                        messages = messages.value,
                        input = input.value,
                        isStreaming = isStreaming.value,
                        showAiBadge = false,
                        onInputChange = { input.value = it },
                        onSend = {},
                        onSendWithAcceptance = { onAccepted ->
                            viewModel.onInputChange(input.value)
                            viewModel.send(onAccepted)
                        },
                        onStop = { stopCalls.incrementAndGet() },
                        onBack = {},
                        onOpenMemory = {},
                        onDismissAiBadge = {},
                    )
                }
            }
            }

            compose.onNodeWithContentDescription("发送").performClick()
            compose.waitForIdle()
            assertEquals("the real ViewModel rejects because no chat thread is bound", 0, keyboard.hideCalls.get())

            compose.runOnIdle { isStreaming.value = true }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("停下").performClick()
            compose.waitForIdle()
            assertEquals(1, stopCalls.get())
            assertEquals(0, keyboard.hideCalls.get())

            compose.runOnIdle {
                isStreaming.value = false
                input.value = "  "
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
            assertEquals(0, keyboard.hideCalls.get())
        } finally {
            viewModelStore.clear()
        }
    }

    @Test
    fun freshInputAndImeReopenCancelDelayedAcceptanceAndMultilineTextIsPreserved() {
        val input = mutableStateOf("")
        val messages = mutableStateOf(emptyList<ChatUiMessage>())
        val imeVisible = mutableStateOf(true)
        val keyboard = RecordingKeyboardController()
        var delayedAcceptance: (() -> Unit)? = null

        compose.setContent {
            CompositionLocalProvider(
                LocalSoftwareKeyboardController provides keyboard,
                LocalImeVisibilityOverride provides imeVisible.value,
            ) {
                MaterialTheme {
                    Box(Modifier.width(420.dp).height(500.dp).testTag("chat-viewport")) {
                        ChatScreen(
                            characterName = "Test",
                            messages = messages.value,
                            input = input.value,
                            isStreaming = false,
                            showAiBadge = false,
                            onInputChange = { input.value = it },
                            onSend = {},
                            onSendWithAcceptance = { delayedAcceptance = it },
                            onStop = {},
                            onBack = {},
                            onOpenMemory = {},
                            onDismissAiBadge = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        assertEquals("blank input cannot begin an accepted send", 0, keyboard.hideCalls.get())

        compose.onNodeWithTag(CHAT_COMPOSER_INPUT_TEST_TAG).performTextInput("line one\nline two")
        compose.waitForIdle()
        assertEquals("line one\nline two", input.value)

        compose.onNodeWithContentDescription("发送").performClick()
        compose.onNodeWithTag(CHAT_COMPOSER_INPUT_TEST_TAG).performTextInput(" more")
        compose.waitForIdle()
        assertEquals("line one\nline two more", input.value)
        compose.runOnIdle { delayedAcceptance?.invoke() }
        compose.waitForIdle()
        assertEquals("new input cancels an earlier send hide", 0, keyboard.hideCalls.get())

        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle { imeVisible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { imeVisible.value = true }
        compose.waitForIdle()
        compose.runOnIdle { delayedAcceptance?.invoke() }
        compose.waitForIdle()
        assertEquals("a later acceptance must not hide a keyboard reopened by the user", 0, keyboard.hideCalls.get())

        val inputBounds = compose.onNodeWithTag(CHAT_COMPOSER_INPUT_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val viewportBottom = compose.onNodeWithTag("chat-viewport")
            .fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("the input remains inside the shortened page viewport", inputBounds.bottom <= viewportBottom)
        val sendButtonBottom = compose.onNodeWithContentDescription("发送")
            .fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("the send control remains inside the shortened page viewport", sendButtonBottom <= viewportBottom)
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
                    Box(Modifier.width(420.dp).height(720.dp).testTag("chat-inset-viewport")) {
                        ChatScreen(
                            characterName = "Test",
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
        assertTrue("imePadding moves the send button up by the synthetic IME inset", beforeBottom - afterBottom >= insetPx * 0.8f)
    }

    @Test
    fun changingChatSessionInvalidatesOldAcceptanceCallback() {
        val session = mutableStateOf("character-session-a")
        val keyboard = RecordingKeyboardController()
        var oldAcceptance: (() -> Unit)? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalSoftwareKeyboardController provides keyboard,
                LocalImeVisibilityOverride provides true,
            ) {
                MaterialTheme {
                    ChatScreen(
                        characterName = "Test",
                        sendSessionKey = session.value,
                        messages = emptyList(),
                        input = "hello",
                        isStreaming = false,
                        showAiBadge = false,
                        onInputChange = {},
                        onSend = {},
                        onSendWithAcceptance = { oldAcceptance = it },
                        onStop = {},
                        onBack = {},
                        onOpenMemory = {},
                        onDismissAiBadge = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle { session.value = "character-session-b" }
        compose.waitForIdle()
        compose.runOnIdle { oldAcceptance?.invoke() }
        compose.waitForIdle()
        assertEquals("callbacks from the prior chat route are discarded", 0, keyboard.hideCalls.get())
    }

    @Test
    fun stopInvalidatesPendingAcceptanceCallback() {
        val streaming = mutableStateOf(false)
        val keyboard = RecordingKeyboardController()
        var pendingAcceptance: (() -> Unit)? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalSoftwareKeyboardController provides keyboard,
                LocalImeVisibilityOverride provides true,
            ) {
                MaterialTheme {
                    ChatScreen(
                        characterName = "Test",
                        messages = emptyList(),
                        input = "hello",
                        isStreaming = streaming.value,
                        showAiBadge = false,
                        onInputChange = {},
                        onSend = {},
                        onSendWithAcceptance = { pendingAcceptance = it },
                        onStop = {},
                        onBack = {},
                        onOpenMemory = {},
                        onDismissAiBadge = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnIdle { streaming.value = true }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("停下").performClick()
        compose.runOnIdle { pendingAcceptance?.invoke() }
        compose.waitForIdle()
        assertEquals("stop invalidates a delayed acceptance event", 0, keyboard.hideCalls.get())
    }

    @Test
    fun realChatViewModelDoesNotAcceptBlankInput() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(context)
        val viewModel = ChatViewModel(container, "missing-character")
        val store = ViewModelStore().apply { put("chat-test", viewModel) }
        val accepted = AtomicInteger()
        try {
            viewModel.onInputChange("  \n ")
            viewModel.send { accepted.incrementAndGet() }
            assertEquals("blank input is rejected before the send pipeline", 0, accepted.get())
        } finally {
            store.clear()
        }
    }

    @Test
    fun realChatViewModelAcceptsValidSendAndSignalsExactlyOnce() = runTest {
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
        var viewModel: ChatViewModel? = null
        try {
            SettingsStore(context).update { it.clear() }
            val (character, _) = container.characters.createWithThread(
                name = "Keyboard test character",
                persona = "Synthetic test fixture",
            )
            val vm = ChatViewModel(container, character.id)
            viewModel = vm
            store.put("chat-accepted-send", vm)
            awaitMainCondition("single-chat ViewModel load") { vm.state.value.ready }

            val syntheticText = "synthetic accepted message"
            val accepted = AtomicInteger()
            val stateAtAcceptance = AtomicReference<ChatViewModel.UiState?>()
            vm.onInputChange(syntheticText)
            vm.send {
                stateAtAcceptance.set(vm.state.value)
                accepted.incrementAndGet()
            }
            awaitMainCondition("single-chat send acceptance") { accepted.get() == 1 }

            val acceptedState = requireNotNull(stateAtAcceptance.get())
            assertEquals("the accepted send clears the composer", "", acceptedState.input)
            assertTrue("the accepted send starts a turn", acceptedState.streaming)
            assertEquals(
                listOf("user", "assistant"),
                acceptedState.messages.takeLast(2).map { it.role },
            )
            assertEquals(syntheticText, acceptedState.messages[acceptedState.messages.lastIndex - 1].text)
            assertEquals("the real ViewModel reports acceptance once", 1, accepted.get())
        } finally {
            viewModel?.stop()
            store.clear()
            runCatching { container.database.close() }
            context.deleteDatabase(databaseName)
            Dispatchers.resetMain()
        }
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

    private class RecordingKeyboardController : SoftwareKeyboardController {
        val hideCalls = AtomicInteger()
        override fun show() = Unit
        override fun hide() { hideCalls.incrementAndGet() }
    }

    /** Every VM test gets a random database name and private files/preferences root. */
    internal class IsolatedImageSendFixture(baseContext: Context) : AutoCloseable {
        private val fixtureParent = baseContext.cacheDir.canonicalFile
        private val root = File(baseContext.cacheDir, "intro-${UUID.randomUUID()}")
            .apply { check(mkdirs()) { "could not create isolated image test root" } }
        private val databaseName = "i${UUID.randomUUID()}.db"
        val context = IsolatedImageContext(baseContext, root, databaseName)
        init {
            AttachmentStore.install(AttachmentStore.forContext(context))
        }
        val container = AppContainer(
            context,
            secretReader = { null },
            secretRemover = {},
            secretWriter = { _, _ -> },
        )
        val settings = SettingsStore(context)

        fun createSyntheticJpeg(label: String): File {
            val target = File(context.cacheDir, "$label-${UUID.randomUUID()}.jpg")
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                FileOutputStream(target).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                }
            } finally {
                bitmap.recycle()
            }
            return target
        }

        fun assertIsolatedStorage() {
            val database = File(container.database.openHelper.writableDatabase.path).canonicalFile
            check(database.name == databaseName) {
                "expected only the per-test database, got ${database.name}"
            }
            check(context.applicationContext === context) { "applicationContext escaped the fixture wrapper" }
            check(context.filesDir.canonicalPath.startsWith(root.canonicalPath)) {
                "app files escaped the unique test root"
            }
            check(context.cacheDir.canonicalPath.startsWith(root.canonicalPath)) {
                "app cache escaped the unique test root"
            }
            val privateFiles = root.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(root).path }
                .toList()
            check(privateFiles.any { it.contains("pulsez_settings") }) {
                "settings preferences were not created under the unique test root; files=$privateFiles"
            }
        }

        override fun close() {
            val safeRoot = root.canonicalFile
            if (safeRoot.parentFile == fixtureParent && safeRoot.name.startsWith("intro-")) {
                safeRoot.deleteRecursively()
            }
            val databaseFile = File(context.getDatabasePath("pulsez.db").absolutePath).canonicalFile
            if (databaseFile.name == databaseName) {
                listOf("", "-journal", "-wal", "-shm").forEach { suffix ->
                    File(databaseFile.absolutePath + suffix).delete()
                }
            }
            AttachmentStore.resetForTest()
        }
    }

    internal class IsolatedImageContext(
        baseContext: Context,
        private val testRoot: File,
        private val randomDatabaseName: String,
    ) : ContextWrapper(baseContext) {
        override fun getApplicationContext(): Context = this

        private fun isolatedDatabaseName(name: String): String =
            if (name == "pulsez.db") randomDatabaseName else "${testRoot.name}-$name"

        override fun getDatabasePath(name: String): File =
            super.getDatabasePath(isolatedDatabaseName(name)).also { path ->
                // Room opens the resolved absolute path directly. Create only its random parent
                // inside this Robolectric sandbox so SQLite can create the test database file.
                path.parentFile?.mkdirs()
            }

        @Suppress("UNUSED_PARAMETER")
        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = super.openOrCreateDatabase(isolatedDatabaseName(name), mode, factory)

        @Suppress("UNUSED_PARAMETER")
        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = super.openOrCreateDatabase(isolatedDatabaseName(name), mode, factory, errorHandler)

        override fun getFilesDir(): File = File(testRoot, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(testRoot, "no-backup").apply { mkdirs() }
        override fun getCacheDir(): File = File(testRoot, "cache").apply { mkdirs() }
        override fun getDir(name: String, mode: Int): File = File(testRoot, "app-$name").apply { mkdirs() }
    }
}

/**
 * A separate Robolectric sandbox keeps the SettingsStore DataStore delegate bound to this
 * fixture's ContextWrapper. The single test method shares one random root across all race cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = android.app.Application::class,
    instrumentedPackages = ["cc.pulsez.app.ui.chat.imageintroisolated"],
)
class ChatImageSendViewModelIsolationTest {
    @Test
    fun imageSendAcceptanceStaysBoundToItsSessionAndWipeEpoch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ChatKeyboardBehaviorTest.IsolatedImageSendFixture(
            ApplicationProvider.getApplicationContext(),
        )
        val store = ViewModelStore()
        val viewModels = mutableListOf<ChatViewModel>()
        try {
            fixture.settings.setChatEndpoint("https://api.deepseek.com", "deepseek-chat")
            fixture.settings.setVisionProbe("deepseek-chat", supported = true)

            val (textCharacter, _) = fixture.container.characters.createWithThread(
                name = "Synthetic text-send character",
                persona = "Synthetic fixture only",
            )
            val textVm = ChatViewModel(fixture.container, textCharacter.id).also {
                viewModels += it
                store.put("chat-image-attempt-text", it)
            }
            awaitMainCondition("text-send chat load") { textVm.state.value.ready }
            val textAccepted = AtomicInteger()
            textVm.onInputChange("synthetic text send")
            textVm.send { textAccepted.incrementAndGet() }
            awaitMainCondition("ordinary no-image send acceptance") { textAccepted.get() == 1 }
            assertTrue("ordinary text send does not show the image explanation", !textVm.state.value.showImageIntro)
            assertEquals("ordinary send appends the original user text", "synthetic text send", textVm.state.value.messages
                .takeLast(2).first().text)
            textVm.send { textAccepted.incrementAndGet() }
            runCurrent()
            assertEquals("an already accepted turn is not accepted twice", 1, textAccepted.get())
            awaitMainCondition("no-secret text turn completion") { !textVm.state.value.streaming }

            val (blankCharacter, _) = fixture.container.characters.createWithThread(
                name = "Synthetic blank-send character",
                persona = "Synthetic fixture only",
            )
            val blankVm = ChatViewModel(fixture.container, blankCharacter.id).also {
                viewModels += it
                store.put("chat-image-attempt-blank", it)
            }
            awaitMainCondition("blank-send chat load") { blankVm.state.value.ready }
            val blankAccepted = AtomicInteger()
            blankVm.onInputChange("  \n ")
            blankVm.send { blankAccepted.incrementAndGet() }
            runCurrent()
            assertEquals("blank input does not invoke acceptance", 0, blankAccepted.get())
            assertTrue("blank input appends no user message", blankVm.state.value.messages.isEmpty())

            val (character, originalThread) = fixture.container.characters.createWithThread(
                name = "Synthetic image attempt character",
                persona = "Synthetic fixture only",
            )
            val originalVm = ChatViewModel(fixture.container, character.id).also {
                viewModels += it
                store.put("chat-image-attempt-original", it)
            }
            awaitMainCondition("original chat load") { originalVm.state.value.ready }

            originalVm.onCameraCaptured(fixture.createSyntheticJpeg("confirm"))
            awaitMainCondition("first synthetic image import") {
                originalVm.state.value.drafts.singleOrNull()?.status in setOf(DraftStatus.READY, DraftStatus.FAILED)
            }
            val firstDraft = requireNotNull(originalVm.state.value.drafts.singleOrNull())
            assertEquals("synthetic image processing result ${firstDraft.errorCode}", DraftStatus.READY, firstDraft.status)
            val accepted = AtomicInteger()
            val stateAtAcceptance = AtomicReference<ChatViewModel.UiState?>()
            originalVm.send {
                stateAtAcceptance.set(originalVm.state.value)
                accepted.incrementAndGet()
            }
            awaitMainCondition("first-image explanation") { originalVm.state.value.showImageIntro }
            assertEquals("the pending explanation has not accepted a send", 0, accepted.get())

            val originalJob = requireNotNull(
                originalVm.viewModelScope.coroutineContext[kotlinx.coroutines.Job],
            )
            val beforeConfirmation = originalJob.children.toSet()
            originalVm.acknowledgeImageIntro()
            val confirmationJob = originalJob.children.firstOrNull { it !in beforeConfirmation }
                ?: error("image-intro continuation was not launched")
            awaitMainCondition("valid image send acceptance") { confirmationJob.isCompleted }
            assertEquals("confirmation accepts the valid image send exactly once", 1, accepted.get())
            val acceptedState = requireNotNull(stateAtAcceptance.get())
            assertTrue("the send commits the local user turn", acceptedState.streaming)
            assertTrue(
                "the committed user message owns the synthetic image",
                acceptedState.messages.takeLast(2).first().imagePaths.isNotEmpty(),
            )
            originalVm.acknowledgeImageIntro()
            runCurrent()
            assertEquals("repeated confirmation does not accept twice", 1, accepted.get())
            awaitMainCondition("no-secret synthetic turn completion") { !originalVm.state.value.streaming }

            // Re-arm the one-time intro in isolated preferences and queue ack before advancing
            // wipeEpoch. The same attempt must keep its pre-wipe token across the suspended edit.
            fixture.settings.setImageIntroSeen(false)
            originalVm.onCameraCaptured(fixture.createSyntheticJpeg("wipe"))
            awaitMainCondition("second synthetic image import") {
                originalVm.state.value.drafts.singleOrNull()?.status == DraftStatus.READY
            }
            originalVm.send { accepted.incrementAndGet() }
            awaitMainCondition("second first-image explanation") { originalVm.state.value.showImageIntro }
            val imageMessageCountBeforeWipe = originalVm.state.value.messages.count {
                it.role == "user" && it.imagePaths.isNotEmpty()
            }
            val beforeQueuedAck = originalJob.children.toSet()
            originalVm.acknowledgeImageIntro()
            val queuedAckJob = originalJob.children.firstOrNull { it !in beforeQueuedAck }
                ?: error("queued image-intro continuation was not launched")
            fixture.container.notifyUserDataWiped()
            awaitMainCondition("queued pre-wipe acknowledgement completion") { queuedAckJob.isCompleted }
            originalVm.acknowledgeImageIntro()
            assertEquals("wipe prevents an old callback from accepting", 1, accepted.get())
            assertEquals(
                "wipe prevents a stale local image message from being appended",
                imageMessageCountBeforeWipe,
                originalVm.state.value.messages.count { it.role == "user" && it.imagePaths.isNotEmpty() },
            )

            // A callback arriving after a wipe is rejected synchronously too; repeated callbacks
            // cannot resurrect the old attempt.
            val currentEpochVm = ChatViewModel(fixture.container, character.id).also {
                viewModels += it
                store.put("chat-image-attempt-current-epoch", it)
            }
            awaitMainCondition("current-epoch chat load") { currentEpochVm.state.value.ready }
            currentEpochVm.onCameraCaptured(fixture.createSyntheticJpeg("late-ack"))
            awaitMainCondition("late-ack synthetic image import") {
                currentEpochVm.state.value.drafts.singleOrNull()?.status == DraftStatus.READY
            }
            currentEpochVm.send { accepted.incrementAndGet() }
            awaitMainCondition("late-ack image explanation") { currentEpochVm.state.value.showImageIntro }
            val countBeforeSecondWipe = currentEpochVm.state.value.messages.count {
                it.role == "user" && it.imagePaths.isNotEmpty()
            }
            fixture.container.notifyUserDataWiped()
            currentEpochVm.acknowledgeImageIntro()
            currentEpochVm.acknowledgeImageIntro()
            runCurrent()
            assertEquals("late and repeated acknowledgement after wipe are inert", 1, accepted.get())
            assertEquals(
                "late acknowledgement after wipe appends no image message",
                countBeforeSecondWipe,
                currentEpochVm.state.value.messages.count { it.role == "user" && it.imagePaths.isNotEmpty() },
            )

            // Route disposal invalidates a pending old-thread attempt before another thread opens.
            val leavingVm = ChatViewModel(fixture.container, character.id).also {
                viewModels += it
                store.put("chat-image-attempt-leaving", it)
            }
            awaitMainCondition("leaving-thread chat load") { leavingVm.state.value.ready }
            leavingVm.onCameraCaptured(fixture.createSyntheticJpeg("leave"))
            awaitMainCondition("leaving-thread synthetic image import") {
                leavingVm.state.value.drafts.singleOrNull()?.status == DraftStatus.READY
            }
            leavingVm.send { accepted.incrementAndGet() }
            awaitMainCondition("leaving-thread image explanation") { leavingVm.state.value.showImageIntro }
            val leavingThreadImageCount = leavingVm.state.value.messages.count {
                it.role == "user" && it.imagePaths.isNotEmpty()
            }
            leavingVm.onVoicePageLeft()

            val (nextCharacter, nextThread) = fixture.container.characters.createWithThread(
                name = "Synthetic next session character",
                persona = "Synthetic fixture only",
            )
            assertTrue("the next route has a distinct thread", originalThread.id != nextThread.id)
            val nextVm = ChatViewModel(fixture.container, nextCharacter.id).also {
                viewModels += it
                store.put("chat-image-attempt-next-session", it)
            }
            awaitMainCondition("next-session chat load") { nextVm.state.value.ready }
            leavingVm.acknowledgeImageIntro()
            runCurrent()
            assertEquals("leaving the old route does not accept its pending send", 1, accepted.get())
            assertEquals(
                "leaving the old route appends no image message to its thread",
                leavingThreadImageCount,
                leavingVm.state.value.messages.count { it.role == "user" && it.imagePaths.isNotEmpty() },
            )
            assertTrue("the next route has no message from the old attempt", nextVm.state.value.messages.isEmpty())
            fixture.assertIsolatedStorage()
        } finally {
            viewModels.forEach { it.stop() }
            store.clear()
            runCatching { fixture.container.database.close() }
            fixture.close()
            Dispatchers.resetMain()
        }
    }
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
