package cc.pulsez.app.ui.chat

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Offline Compose interaction checks for single-chat paging and scroll behavior.
 * Fixtures contain synthetic messages only. API basis: Android's Compose testing guide and
 * LazyListState reference (UI test dependency is configured in app/build.gradle.kts).
 * https://developer.android.com/develop/ui/compose/testing
 * `assertDoesNotExist` and `fetchSemanticsNode` are SemanticsNodeInteraction members, as
 * documented by https://developer.android.com/reference/kotlin/androidx/compose/ui/test/SemanticsNodeInteraction
 * https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatMessageListUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `initial load and reopen show latest without exposing old top or requesting older`() {
        val messages = mutableStateOf(emptyList<ChatUiMessage>())
        val hasMoreOlder = mutableStateOf(true)
        val screenVisible = mutableStateOf(true)
        val olderRequests = AtomicInteger()
        val history = syntheticMessages(30)

        composeRule.setContent {
            if (screenVisible.value) {
                MaterialTheme {
                    screen(
                        messages = messages.value,
                        hasMoreOlder = hasMoreOlder.value,
                        onLoadOlder = { olderRequests.incrementAndGet() },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Simulate the ViewModel's asynchronous tail(80) result arriving after the first frame.
        composeRule.runOnIdle { messages.value = history }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("message-29").assertIsDisplayed()
        composeRule.onNodeWithText("message-00").assertDoesNotExist()
        assertEquals("programmatic initial positioning must not request an older page", 0, olderRequests.get())

        // A programmatic scroll to the top also must not page; leaving and reopening starts at latest again.
        composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG).performScrollToIndex(0)
        composeRule.waitForIdle()
        assertEquals("programmatic scroll must not request an older page", 0, olderRequests.get())
        composeRule.runOnIdle { screenVisible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { screenVisible.value = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("message-29").assertIsDisplayed()
        composeRule.onNodeWithText("message-00").assertDoesNotExist()
    }

    @Test
    fun `first rendered frame hides the old top while the tail is being positioned`() {
        val history = syntheticMessages(30)
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread {
                composeRule.setContent {
                    MaterialTheme {
                        screen(messages = history, hasMoreOlder = true)
                    }
                }
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithText("message-00").assertDoesNotExist()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("message-29").assertIsDisplayed()
    }

    @Test
    fun `long final message is initially aligned to its tail`() {
        val longText = (0 until 160).joinToString("\n") { "line-$it" } + "\nTAIL_MARKER"
        val messages = mutableStateOf(
            listOf(ChatUiMessage(id = "long-tail", role = "user", text = longText)),
        )
        composeRule.setContent {
            MaterialTheme {
                screen(messages = messages.value)
            }
        }
        composeRule.waitForIdle()

        assertTailLineVisible(longText, "TAIL_MARKER")
    }

    @Test
    fun `empty and short history remain usable and imported source title stays visible`() {
        val messages = mutableStateOf(emptyList<ChatUiMessage>())
        composeRule.setContent {
            MaterialTheme {
                screen(messages = messages.value)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG).assertIsDisplayed()

        composeRule.runOnIdle {
            messages.value = listOf(
                ChatUiMessage(
                    id = "short-imported",
                    role = "user",
                    text = "short-message-marker",
                    sourceSessionId = "source-1",
                    sourceTitle = "Synthetic source title",
                    sourceStartedAt = 1_700_000_000_000L,
                ),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("short-message-marker").assertIsDisplayed()
        composeRule.onNodeWithText("来自导入：Synthetic source title", substring = true).assertIsDisplayed()
    }

    @Test
    fun `terminal history does not request another older page`() {
        val olderRequests = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                screen(
                    messages = syntheticMessages(12),
                    hasMoreOlder = false,
                    onLoadOlder = { olderRequests.incrementAndGet() },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG).performScrollToIndex(0)
        repeat(3) {
            composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG).performTouchInput { swipeDown() }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("看看更早的").assertDoesNotExist()
        assertEquals("a terminal page must not request another older page", 0, olderRequests.get())
    }

    @Test
    fun `real downward drag requests one older page and keyed insertion preserves anchor pixels`() {
        val messages = mutableStateOf(syntheticMessages(30))
        val hasMoreOlder = mutableStateOf(true)
        val loadingOlder = mutableStateOf(false)
        val olderRequests = AtomicInteger()
        val olderPage = syntheticMessages(20, prefix = "older-")

        composeRule.setContent {
            MaterialTheme {
                screen(
                    messages = messages.value,
                    hasMoreOlder = hasMoreOlder.value,
                    loadingOlder = loadingOlder.value,
                    onLoadOlder = {
                        olderRequests.incrementAndGet()
                        loadingOlder.value = true
                    },
                )
            }
        }
        composeRule.waitForIdle()

        repeat(16) {
            composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG)
                .performTouchInput { swipeDown() }
        }
        composeRule.waitForIdle()
        assertEquals("one user gesture near the top should hold one request", 1, olderRequests.get())

        // A failed page leaves the visible retry action available; a programmatic scroll itself does
        // not retry, and the explicit button starts the next single request.
        composeRule.runOnIdle { loadingOlder.value = false }
        composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG).performScrollToIndex(0)
        composeRule.onNodeWithText("看看更早的").performClick()
        composeRule.waitForIdle()
        assertEquals("only the explicit retry should start a second request", 2, olderRequests.get())

        // The threshold is four rows from the beginning (including the load row), so this keyed row
        // is in the viewport before insertion. Record its actual pixel position, not just its index.
        val anchorText = "message-03"
        composeRule.onNodeWithText(anchorText).assertIsDisplayed()
        val beforeTop = composeRule.onNodeWithText(anchorText).fetchSemanticsNode().boundsInRoot.top

        composeRule.runOnIdle {
            messages.value = olderPage + messages.value
            loadingOlder.value = false
        }
        composeRule.waitForIdle()

        val afterTop = composeRule.onNodeWithText(anchorText).fetchSemanticsNode().boundsInRoot.top
        assertEquals("front insertion should preserve the visible keyed row's pixel offset", beforeTop, afterTop, 1f)
        assertEquals("front insertion must not start an additional request", 2, olderRequests.get())
        composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG).performScrollToIndex(1)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("older-00").assertIsDisplayed()
        assertEquals("programmatic top positioning must not page again", 2, olderRequests.get())
    }

    @Test
    fun `older page arriving during real drag keeps the visible message in the viewport`() {
        val messages = mutableStateOf(syntheticMessages(30))
        val olderRequests = AtomicInteger()
        val olderPage = syntheticMessages(20, prefix = "inline-older-")

        composeRule.setContent {
            MaterialTheme {
                screen(
                    messages = messages.value,
                    hasMoreOlder = true,
                    onLoadOlder = {
                        olderRequests.incrementAndGet()
                        // Model a fast page response while the triggering user drag is still active.
                        messages.value = olderPage + messages.value
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val list = composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG)
        list.performScrollToIndex(4)
        composeRule.waitForIdle()
        val anchor = requireNotNull(firstVisibleMessageMarker(30))
        val beforeTop = composeRule.onNodeWithText(anchor).fetchSemanticsNode().boundsInRoot.top

        // This is a real injected pointer drag; the page is prepended synchronously in its callback.
        // API basis: TouchInjectionScope.swipeDown parameters are documented at:
        // https://developer.android.com/reference/kotlin/androidx/compose/ui/test/TouchInjectionScope
        list.performTouchInput {
            swipeDown(
                startY = centerY - 30f,
                endY = centerY + 30f,
                durationMillis = 180,
            )
        }
        composeRule.waitForIdle()

        assertEquals("the real drag should request exactly one older page", 1, olderRequests.get())
        composeRule.onNodeWithText(anchor).assertIsDisplayed()
        val afterTop = composeRule.onNodeWithText(anchor).fetchSemanticsNode().boundsInRoot.top
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the previously visible message must stay inside the list while older rows arrive",
            afterTop in listBounds.top..listBounds.bottom,
        )
        assertTrue(
            "a downward drag should not move the visible message above its prior position",
            afterTop >= beforeTop - 1f,
        )
    }

    @Test
    fun `streaming reply during older load and later prepend keep the upscroll anchor`() {
        val messages = mutableStateOf(syntheticMessages(30))
        val isStreaming = mutableStateOf(false)
        val hasMoreOlder = mutableStateOf(true)
        val loadingOlder = mutableStateOf(false)
        val olderRequests = AtomicInteger()
        val olderPage = syntheticMessages(20, prefix = "pending-older-")

        composeRule.setContent {
            MaterialTheme {
                screen(
                    messages = messages.value,
                    hasMoreOlder = hasMoreOlder.value,
                    loadingOlder = loadingOlder.value,
                    isStreaming = isStreaming.value,
                    onLoadOlder = {
                        olderRequests.incrementAndGet()
                        loadingOlder.value = true
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val list = composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG)
        list.performScrollToIndex(4)
        composeRule.waitForIdle()
        // Programmatic positioning alone must not load; the following real user drag does.
        assertEquals(0, olderRequests.get())
        list.performTouchInput {
            swipeDown(
                startY = centerY - 30f,
                endY = centerY + 30f,
                durationMillis = 180,
            )
        }
        composeRule.waitForIdle()
        assertEquals("the real upscroll should start one older-page request", 1, olderRequests.get())
        assertTrue("the older-page request should remain in flight", loadingOlder.value)

        val anchor = requireNotNull(firstVisibleMessageMarker(30)) {
            "the user should still be reading an older message while the page is loading"
        }
        val anchorTop = composeRule.onNodeWithText(anchor).fetchSemanticsNode().boundsInRoot.top
        val firstReplyText = "streaming-reply-marker"
        composeRule.runOnIdle {
            isStreaming.value = true
            messages.value = messages.value + ChatUiMessage(
                id = "streaming-reply-during-older-load",
                role = "assistant",
                text = firstReplyText,
            )
        }
        composeRule.waitForIdle()
        assertEquals("a new reply must not move the upscroll anchor", anchorTop,
            composeRule.onNodeWithText(anchor).fetchSemanticsNode().boundsInRoot.top, 1f)
        composeRule.onNodeWithText(firstReplyText).assertDoesNotExist()

        val growingText = (0 until 18).joinToString("\n") { "streaming-line-$it" } + "\nSTREAMING_TAIL_MARKER"
        composeRule.runOnIdle {
            messages.value = messages.value.dropLast(1) + messages.value.last().copy(text = growingText)
        }
        composeRule.waitForIdle()
        assertEquals("streaming growth must not move the upscroll anchor", anchorTop,
            composeRule.onNodeWithText(anchor).fetchSemanticsNode().boundsInRoot.top, 1f)
        composeRule.onNodeWithText("STREAMING_TAIL_MARKER").assertDoesNotExist()

        composeRule.runOnIdle {
            messages.value = olderPage + messages.value
            loadingOlder.value = false
        }
        composeRule.waitForIdle()
        assertEquals("the old-page prepend must retain the same visible message pixels", anchorTop,
            composeRule.onNodeWithText(anchor).fetchSemanticsNode().boundsInRoot.top, 1f)
        composeRule.onNodeWithText("STREAMING_TAIL_MARKER").assertDoesNotExist()
        assertTrue("the older page should finish loading", !loadingOlder.value)
    }

    @Test
    fun `new message and streaming growth follow at bottom`() {
        val messages = mutableStateOf(syntheticMessages(30))
        val isStreaming = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                screen(messages = messages.value, isStreaming = isStreaming.value)
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            messages.value = messages.value + ChatUiMessage(
                id = "incoming",
                role = "assistant",
                text = "incoming-at-bottom",
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("incoming-at-bottom").assertIsDisplayed()

        val streamingText = (0 until 90).joinToString("\n") { "stream-$it" } + "\nSTREAM_TAIL"
        composeRule.runOnIdle {
            isStreaming.value = true
            messages.value = messages.value.dropLast(1) + messages.value.last().copy(text = streamingText)
        }
        composeRule.waitForIdle()
        assertTailLineVisible(streamingText, "STREAM_TAIL")
    }

    @Test
    fun `new reply does not take over after the user scrolls up`() {
        val messages = mutableStateOf(syntheticMessages(30))
        composeRule.setContent {
            MaterialTheme {
                screen(messages = messages.value)
            }
        }
        composeRule.waitForIdle()

        // Move toward older messages with a real drag, then append another reply. The first currently
        // visible marker must stay at the same pixel and the new reply must remain offscreen.
        repeat(3) {
            composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG).performTouchInput { swipeDown() }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("message-29").assertDoesNotExist()
        val anchor = requireNotNull(firstVisibleMessageMarker(30)) {
            "the drag should leave an older message visible"
        }
        val anchorBefore = composeRule.onNodeWithText(anchor).fetchSemanticsNode().boundsInRoot.top
        composeRule.runOnIdle {
            messages.value = messages.value + ChatUiMessage(
                id = "reply-while-upscrolling",
                role = "assistant",
                text = "reply-while-upscrolling-marker",
            )
        }
        composeRule.waitForIdle()
        val anchorAfter = composeRule.onNodeWithText(anchor).fetchSemanticsNode().boundsInRoot.top
        assertEquals("a reply must not move the user's upscroll anchor", anchorBefore, anchorAfter, 1f)
        composeRule.onNodeWithText("reply-while-upscrolling-marker").assertDoesNotExist()
    }

    @Test
    fun `scrolled up accepted send jumps to bottom and follows streaming while rejected send stays put`() {
        val messages = mutableStateOf(syntheticMessages(30))
        val input = mutableStateOf("new-user-prompt")
        val isStreaming = mutableStateOf(false)
        var acceptNextSend = false

        composeRule.setContent {
            MaterialTheme {
                screen(
                    messages = messages.value,
                    input = input.value,
                    isStreaming = isStreaming.value,
                    onInputChange = { input.value = it },
                    onSendWithAcceptance = { onAccepted ->
                        if (acceptNextSend) {
                            input.value = ""
                            isStreaming.value = true
                            messages.value = messages.value +
                                ChatUiMessage(id = "user-sent", role = "user", text = "user-sent-marker") +
                                ChatUiMessage(id = "ai-reply", role = "assistant", text = "")
                            onAccepted()
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        repeat(3) {
            composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG).performTouchInput { swipeDown() }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("message-29").assertDoesNotExist()
        val anchor = requireNotNull(firstVisibleMessageMarker(30))
        val anchorBefore = composeRule.onNodeWithText(anchor).fetchSemanticsNode().boundsInRoot.top

        // 被 ViewModel 拦截（未调用 onAccepted）时，保持当前阅读位置不动。
        composeRule.onNodeWithContentDescription("发送").performClick()
        composeRule.waitForIdle()
        val anchorAfterReject = composeRule.onNodeWithText(anchor).fetchSemanticsNode().boundsInRoot.top
        assertEquals("rejected send must not scroll away from reading position", anchorBefore, anchorAfterReject, 1f)
        composeRule.onNodeWithText("message-29").assertDoesNotExist()

        // 真正提交发送（调用 onAccepted）时，立即滚回底部并进入本轮流式跟随。
        acceptNextSend = true
        composeRule.onNodeWithContentDescription("发送").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("user-sent-marker").assertIsDisplayed()

        val streamingText = (0 until 90).joinToString("\n") { "sent-stream-$it" } + "\nSENT_STREAM_TAIL"
        composeRule.runOnIdle {
            messages.value = messages.value.dropLast(1) + messages.value.last().copy(text = streamingText)
        }
        composeRule.waitForIdle()
        assertTailLineVisible(streamingText, "SENT_STREAM_TAIL")
    }

    @Test
    fun `ime inset shrink keeps bottom pinned when sticky and does not steal when scrolled up`() {
        val messages = mutableStateOf(syntheticMessages(30))
        var composeHost: View? = null
        var density = 1f

        composeRule.setContent {
            val host = LocalView.current
            val currentDensity = LocalDensity.current.density
            SideEffect {
                composeHost = host
                density = currentDensity
            }
            MaterialTheme {
                Box(Modifier.width(420.dp).height(720.dp)) {
                    screen(messages = messages.value)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("message-29").assertIsDisplayed()

        // 贴底态下弹出键盘缩小视口：最新消息仍停留在视口底以内。
        dispatchImeInsets(composeHost ?: error("Compose host missing"), density, 220)
        composeRule.waitForIdle()
        val listBoundsWithIme = composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val tailBoundsWithIme = composeRule.onNodeWithText("message-29")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "latest message must remain inside the shrunk viewport when sticky",
            tailBoundsWithIme.bottom <= listBoundsWithIme.bottom + 2f &&
                tailBoundsWithIme.top >= listBoundsWithIme.top - 2f,
        )

        // 收起键盘后用户上翻离底，再弹出键盘绝不抢回底部。
        dispatchImeInsets(composeHost ?: error("Compose host missing"), density, 0)
        composeRule.waitForIdle()
        repeat(3) {
            composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG).performTouchInput { swipeDown() }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("message-29").assertDoesNotExist()
        val upscrollAnchor = requireNotNull(firstVisibleMessageMarker(30))

        dispatchImeInsets(composeHost ?: error("Compose host missing"), density, 220)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(upscrollAnchor).assertIsDisplayed()
        composeRule.onNodeWithText("message-29").assertDoesNotExist()
    }

    @Test
    fun `thinking reasoning growth and multi-step streaming follow at bottom`() {
        val messages = mutableStateOf(syntheticMessages(10))
        val isStreaming = mutableStateOf(false)

        composeRule.setContent {
            MaterialTheme {
                screen(messages = messages.value, isStreaming = isStreaming.value)
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            isStreaming.value = true
            messages.value = messages.value +
                ChatUiMessage(id = "turn-u", role = "user", text = "思考一下") +
                ChatUiMessage(id = "turn-a", role = "assistant", text = "")
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            messages.value = messages.value.dropLast(1) + messages.value.last().copy(
                reasoning = "第一步思考……",
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("正在深度思考……").assertIsDisplayed()

        val firstChunk = (0 until 25).joinToString("\n") { "chunk1-$it" }
        composeRule.runOnIdle {
            messages.value = messages.value.dropLast(1) + messages.value.last().copy(text = firstChunk)
        }
        composeRule.waitForIdle()

        val finalText = "$firstChunk\n" + (0 until 75).joinToString("\n") { "chunk2-$it" } + "\nTHINKING_STREAM_TAIL"
        composeRule.runOnIdle {
            messages.value = messages.value.dropLast(1) + messages.value.last().copy(text = finalText)
            isStreaming.value = false
        }
        composeRule.waitForIdle()
        assertTailLineVisible(finalText, "THINKING_STREAM_TAIL")
    }

    @Test
    fun `scroll to latest button appears when scrolled up meets touch size and restores follow`() {
        val messages = mutableStateOf(syntheticMessages(30))
        val isStreaming = mutableStateOf(false)
        var density = 1f

        composeRule.setContent {
            density = LocalDensity.current.density
            MaterialTheme {
                screen(messages = messages.value, isStreaming = isStreaming.value)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TEST_TAG).assertDoesNotExist()

        repeat(3) {
            composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG).performTouchInput { swipeDown() }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("回到最新").assertIsDisplayed()
        val buttonBounds = composeRule.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val minTouchPx = 48f * density - 0.5f
        assertTrue("scroll-to-latest button width must be >= 48dp", buttonBounds.width >= minTouchPx)
        assertTrue("scroll-to-latest button height must be >= 48dp", buttonBounds.height >= minTouchPx)

        composeRule.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("message-29").assertIsDisplayed()
        composeRule.onNodeWithTag(CHAT_SCROLL_TO_LATEST_TEST_TAG).assertDoesNotExist()

        // 点击「回到最新」后恢复后续流式跟随。
        val streamed = (0 until 90).joinToString("\n") { "after-return-$it" } + "\nRETURN_FOLLOW_TAIL"
        composeRule.runOnIdle {
            isStreaming.value = true
            messages.value = messages.value + ChatUiMessage(
                id = "after-return",
                role = "assistant",
                text = streamed,
            )
        }
        composeRule.waitForIdle()
        assertTailLineVisible(streamed, "RETURN_FOLLOW_TAIL")
    }

    private fun dispatchImeInsets(host: View, density: Float, heightDp: Int) {
        val bottomPx = (heightDp * density).toInt()
        val imeInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottomPx))
            .setVisible(WindowInsetsCompat.Type.ime(), heightDp > 0)
            .build()
        ViewCompat.dispatchApplyWindowInsets(host, imeInsets)
    }

    private fun firstVisibleMessageMarker(messageCount: Int): String? {
        val listBounds = composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        return (0 until messageCount).asSequence()
            .map { "message-%02d".format(it) }
            .firstOrNull { text ->
                val bounds = runCatching {
                    composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
                }.getOrNull() ?: return@firstOrNull false
                bounds.bottom > listBounds.top && bounds.top < listBounds.bottom
            }
    }

    private fun assertTailLineVisible(fullText: String, marker: String) {
        val listBounds = composeRule.onNodeWithTag(CHAT_MESSAGE_LIST_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val messageNode = composeRule.onNodeWithText(marker, substring = true).fetchSemanticsNode()
        assertTrue("the final message should be taller than the viewport", messageNode.size.height.toFloat() > listBounds.height)
        val textLayouts = mutableListOf<TextLayoutResult>()
        messageNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(textLayouts)
        val textLayout = requireNotNull(textLayouts.firstOrNull()) { "Text layout result should be exposed to Compose tests" }
        val tailOffset = fullText.lastIndexOf(marker)
        val tailLine = textLayout.getLineForOffset(tailOffset)
        val tailBottomInRoot = messageNode.positionInRoot.y + textLayout.getLineBottom(tailLine)
        assertTrue(
            "the last text line must land inside the viewport",
            tailBottomInRoot in listBounds.top..listBounds.bottom,
        )
    }

    @Composable
    private fun screen(
        messages: List<ChatUiMessage>,
        input: String = "",
        hasMoreOlder: Boolean = false,
        loadingOlder: Boolean = false,
        isStreaming: Boolean = false,
        onInputChange: (String) -> Unit = {},
        onSend: () -> Unit = {},
        onSendWithAcceptance: ((onAccepted: () -> Unit) -> Unit)? = null,
        onLoadOlder: () -> Unit = {},
    ) {
        ChatScreen(
            characterName = "Synthetic character",
            messages = messages,
            input = input,
            isStreaming = isStreaming,
            showAiBadge = false,
            onInputChange = onInputChange,
            onSend = onSend,
            onSendWithAcceptance = onSendWithAcceptance,
            onStop = {},
            onBack = {},
            onOpenMemory = {},
            onDismissAiBadge = {},
            hasMoreOlder = hasMoreOlder,
            loadingOlder = loadingOlder,
            onLoadOlder = onLoadOlder,
        )
    }

    private fun syntheticMessages(count: Int, prefix: String = "message-"): List<ChatUiMessage> =
        (0 until count).map { index ->
            val marker = if (prefix == "message-") "message-%02d".format(index) else "$prefix%02d".format(index)
            ChatUiMessage(id = "$prefix$index", role = "user", text = marker)
        }
}
