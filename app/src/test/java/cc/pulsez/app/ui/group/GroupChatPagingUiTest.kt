package cc.pulsez.app.ui.group

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import cc.pulsez.app.ui.theme.PulsezTheme

/** Real Compose/Robolectric coverage for the first-frame, gesture and keyed-anchor behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupChatPagingUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun asynchronousInitialPageAndReentryShowOnlyTheFreshSessionTail() {
        val firstPage = CompletableDeferred<GroupChatUiState>()
        val reentryPage = CompletableDeferred<GroupChatUiState>()
        val screenState = mutableStateOf(loadingState("entry-1"))
        val loadRequests = AtomicInteger()

        compose.setContent {
            val current = screenState.value
            LaunchedEffect(current.historySessionId, current.historyLoaded) {
                if (!current.historyLoaded) {
                    val page = when (current.historySessionId) {
                        "entry-1" -> firstPage.await()
                        else -> reentryPage.await()
                    }
                    screenState.value = page
                }
            }
            PulsezTheme {
                GroupChatScreen(
                    state = current,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiNotice = {},
                    onRetry = {},
                    onLoadOlder = { loadRequests.incrementAndGet() },
                )
            }
        }
        compose.waitForIdle()
        assertTrue("the list stays empty while the first database page is pending", !hasMessage("entry-1-latest"))
        assertEquals(0, loadRequests.get())

        firstPage.complete(
            loadedState(
                listOf(userMessage("entry-1-latest", "first entry latest")),
                historySessionId = "entry-1",
                hasOlder = true,
            ),
        )
        compose.waitUntil(5_000) { hasMessage("entry-1-latest") }
        compose.waitForIdle()
        compose.onNodeWithTag("group-message-entry-1-latest").assertIsDisplayed()
        assertLatestNearBottom("entry-1-latest")
        assertEquals("initial placement is programmatic and does not request older rows", 0, loadRequests.get())

        compose.runOnIdle { screenState.value = loadingState("entry-2") }
        compose.waitUntil(5_000) { !hasMessage("entry-1-latest") }
        assertEquals("the previous route's list is removed while its fresh page loads", 0, loadRequests.get())
        reentryPage.complete(
            loadedState(
                listOf(userMessage("entry-2-latest", "reentered latest")),
                historySessionId = "entry-2",
                hasOlder = true,
            ),
        )
        compose.waitUntil(5_000) { hasMessage("entry-2-latest") }
        compose.waitForIdle()
        compose.onNodeWithTag("group-message-entry-2-latest").assertIsDisplayed()
        assertLatestNearBottom("entry-2-latest")
        assertTrue("reentry does not retain the prior session's row", !hasMessage("entry-1-latest"))
        assertEquals("a fresh session also starts without an older-page request", 0, loadRequests.get())
    }

    @Test
    fun firstFrameShowsLatestAndLongTailEndsAtViewportBottomWithoutPaging() {
        val requests = AtomicInteger()
        val longText = (0 until 160).joinToString("\n") { "tail-line-$it" } + "\nTAIL_MARKER"
        val messages = (0 until 24).map { index ->
            userMessage("msg-%02d".format(index), "synthetic row $index")
        } + userMessage("latest", longText)

        compose.setContent {
            PulsezTheme {
                GroupChatScreen(
                    state = loadedState(messages, historySessionId = "long-tail", hasOlder = true),
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiNotice = {},
                    onRetry = {},
                    onLoadOlder = { requests.incrementAndGet() },
                )
            }
        }
        compose.waitForIdle()

        assertTailLineVisible(longText, "TAIL_MARKER")
        compose.onNodeWithTag("group-message-latest").assertIsDisplayed()
        assertEquals("programmatic initial placement must not load older history", 0, requests.get())
    }

    @Test
    fun longLatestMessageGrowthAndNewMessageDoNotStealAfterUserScrollsInsideIt() {
        val initialText = (0 until 260).joinToString("\n") { "stream-line-$it" } + "\nTAIL_BEFORE"
        val state = androidx.compose.runtime.mutableStateOf(
            loadedState(
                listOf(userMessage("long-latest", initialText)),
                historySessionId = "long-unpinned",
                hasOlder = false,
            ),
        )
        compose.setContent {
            PulsezTheme {
                GroupChatScreen(
                    state = state.value,
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
        compose.waitForIdle()
        assertTailLineVisible(initialText, "TAIL_BEFORE")

        val list = compose.onNodeWithTag(GROUP_HISTORY_LIST_TEST_TAG)
        repeat(6) {
            if (isTextLineVisible(initialText, "TAIL_BEFORE")) {
                list.performTouchInput { swipeDown() }
                compose.waitForIdle()
            }
        }
        assertFalse("the user drag must leave the long message tail before testing follow", isTextLineVisible(initialText, "TAIL_BEFORE"))
        val anchor = visibleLineAnchor(initialText, "stream-line-")

        val streamedText = "$initialText\nSTREAMED_TAIL"
        compose.runOnIdle {
            state.value = state.value.copy(
                messages = listOf(userMessage("long-latest", streamedText)),
            )
        }
        compose.waitForIdle()
        assertFalse("streaming growth must not pull an unpinned reader to the tail", isTextLineVisible(streamedText, "STREAMED_TAIL"))
        assertEquals(
            "streaming growth preserves the visible text line",
            anchor.bottom,
            lineBottomInRoot(streamedText, anchor.text),
            2f,
        )

        compose.runOnIdle {
            state.value = state.value.copy(
                messages = state.value.messages + userMessage("arrived-later", "A new message"),
            )
        }
        compose.waitForIdle()
        assertEquals("the new message is now the data tail", "arrived-later", state.value.messages.last().id)
        assertEquals(
            "a new message preserves the reader's visible text line",
            anchor.bottom,
            lineBottomInRoot(streamedText, anchor.text),
            2f,
        )
    }

    @Test
    fun longLatestMessageGrowthWhilePinnedKeepsTheNewTailVisible() {
        val initialText = (0 until 240).joinToString("\n") { "pinned-line-$it" } + "\nTAIL_BEFORE_STREAM"
        val state = androidx.compose.runtime.mutableStateOf(
            loadedState(
                listOf(userMessage("pinned-long-latest", initialText)),
                historySessionId = "long-pinned",
                hasOlder = false,
            ),
        )
        compose.setContent {
            PulsezTheme {
                GroupChatScreen(
                    state = state.value,
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
        compose.waitForIdle()
        assertTailLineVisible(initialText, "TAIL_BEFORE_STREAM")

        val streamedText = "$initialText\nTAIL_AFTER_STREAM"
        compose.runOnIdle {
            state.value = state.value.copy(
                messages = listOf(userMessage("pinned-long-latest", streamedText)),
            )
        }
        compose.waitForIdle()
        assertTailLineVisible(streamedText, "TAIL_AFTER_STREAM")
    }

    @Test
    fun growthDuringDragThenCrossingLatestRowDoesNotJumpOnRelease() {
        val initialText = (0 until 34).joinToString("\n") { "drag-line-$it" } + "\nDRAG_TAIL_BEFORE"
        val streamedText = "$initialText\nDRAG_TAIL_DURING"
        val olderMessages = (0 until 40).map { index ->
            userMessage("drag-older-%02d".format(index), "older row $index")
        }
        val state = mutableStateOf(
            loadedState(
                listOf(userMessage("drag-long-latest", initialText)),
                historySessionId = "drag-growth-cross-row",
                hasOlder = true,
            ),
        )
        val loadRequests = AtomicInteger()

        compose.setContent {
            PulsezTheme {
                GroupChatScreen(
                    state = state.value,
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiNotice = {},
                    onRetry = {},
                    onLoadOlder = {
                        if (loadRequests.compareAndSet(0, 1)) {
                            state.value = state.value.copy(
                                messages = olderMessages + userMessage("drag-long-latest", streamedText),
                                hasOlderHistory = false,
                            )
                        }
                    },
                )
            }
        }
        compose.waitForIdle()
        assertTailLineVisible(initialText, "DRAG_TAIL_BEFORE")
        val list = compose.onNodeWithTag(GROUP_HISTORY_LIST_TEST_TAG)

        // Leave the pointer down while a real older-page response grows the latest row.
        // Compose test input retains unfinished gestures across calls (Android Developers:
        // https://developer.android.com/reference/kotlin/androidx/compose/ui/test/SemanticsNodeInteraction(kotlin.Function1)).
        list.performTouchInput {
            down(center)
            moveBy(Offset(0f, 42f), delayMillis = 80)
        }
        compose.waitUntil(5_000) {
            loadRequests.get() == 1 && state.value.messages.size == olderMessages.size + 1
        }
        compose.waitForIdle()
        assertTrue("the latest row grew while the user still held the drag", state.value.messages.last().text == streamedText)

        list.performTouchInput {
            repeat(34) { moveBy(Offset(0f, 70f), delayMillis = 50) }
            // Let velocity settle while the pointer remains down; the following call only releases it.
            moveBy(Offset.Zero, delayMillis = 300)
        }
        compose.waitForIdle()

        val viewport = nodeBounds(GROUP_HISTORY_LIST_TEST_TAG)
        assertFalse(
            "the continuing drag crosses the tall latest row before release",
            isMessageIntersectingViewport("drag-long-latest", viewport),
        )
        val olderAnchorId = visibleMessageIds(olderMessages.map { it.id }, viewport).firstOrNull()
        assertTrue("the held drag reaches an older message row", olderAnchorId != null)
        val anchor = requireNotNull(olderAnchorId)
        val beforeRelease = nodeBounds("group-message-$anchor").top

        list.performTouchInput { up() }
        compose.waitForIdle()

        val afterRelease = nodeBounds("group-message-$anchor").top
        assertEquals(
            "releasing after crossing the changing row must not replay its height delta on the older anchor",
            beforeRelease,
            afterRelease,
            1f,
        )
    }

    @Test
    fun onlyARealDragTowardOlderBoundaryRequestsAnotherPage() {
        val requests = AtomicInteger()
        val messages = (0 until 100).map { index ->
            userMessage("msg-%03d".format(index), "synthetic row $index")
        }
        compose.setContent {
            PulsezTheme {
                GroupChatScreen(
                    state = loadedState(messages, historySessionId = "gesture", hasOlder = true),
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiNotice = {},
                    onRetry = {},
                    onLoadOlder = { requests.incrementAndGet() },
                )
            }
        }
        compose.waitForIdle()
        val list = compose.onNodeWithTag(GROUP_HISTORY_LIST_TEST_TAG)

        list.performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals("a gesture away from older history does not request a page", 0, requests.get())

        repeat(14) {
            list.performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        assertTrue("a user drag reaches the older boundary and requests a page", requests.get() > 0)
    }

    @Test
    fun prependingOlderRowsKeepsVisibleKeyAndOffset() {
        val main = memberMessage("turn-main", "synthetic main", GroupMessageKind.MAIN, addressed = true)
        val reaction = memberMessage("turn-reaction", "synthetic reaction", GroupMessageKind.REACTION, addressed = true)
        val current = listOf(main, reaction) + (0 until 32).map { index ->
            userMessage("current-%02d".format(index), "synthetic row $index")
        }
        val state = androidx.compose.runtime.mutableStateOf(
            loadedState(current, historySessionId = "anchor", hasOlder = true),
        )
        compose.setContent {
            PulsezTheme {
                GroupChatScreen(
                    state = state.value,
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
        compose.waitForIdle()
        val list = compose.onNodeWithTag(GROUP_HISTORY_LIST_TEST_TAG)
        list.performScrollToIndex(32)
        compose.waitForIdle()
        val before = nodeBounds("group-message-turn-main")

        compose.runOnIdle {
            state.value = state.value.copy(
                messages = listOf(userMessage("older-user", "synthetic earlier user")) + state.value.messages,
                hasOlderHistory = false,
            )
        }
        compose.waitForIdle()

        val after = nodeBounds("group-message-turn-main")
        assertEquals("the MAIN row keeps its stable key and visible top", before.top, after.top, 1f)
        assertEquals("the visible row keeps its measured bottom", before.bottom, after.bottom, 1f)
    }

    @Test
    fun newMessagesFollowAtLatestButDoNotStealAnOlderReadingPosition() {
        val initial = (0 until 70).map { index ->
            userMessage("msg-%03d".format(index), "synthetic row $index")
        }
        val state = androidx.compose.runtime.mutableStateOf(
            loadedState(initial, historySessionId = "follow", hasOlder = false),
        )
        compose.setContent {
            PulsezTheme {
                GroupChatScreen(
                    state = state.value,
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
        compose.waitForIdle()
        compose.runOnIdle {
            state.value = state.value.copy(messages = state.value.messages + userMessage("tail-follow", "synthetic latest"))
        }
        compose.waitForIdle()
        val listBounds = nodeBounds(GROUP_HISTORY_LIST_TEST_TAG)
        val followedTail = nodeBounds("group-message-tail-follow")
        assertTrue(listBounds.bottom - followedTail.bottom < 120f)

        val list = compose.onNodeWithTag(GROUP_HISTORY_LIST_TEST_TAG)
        repeat(3) {
            list.performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        val visibleIds = (0 until 70).map { "msg-%03d".format(it) }.filter { id ->
            compose.onAllNodesWithTag("group-message-$id").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        assertTrue("gesture scrolls away from the newest row", visibleIds.any { it != "msg-069" })
        val anchorId = visibleIds.first { it != "msg-069" }
        val before = nodeBounds("group-message-$anchorId")

        compose.runOnIdle {
            state.value = state.value.copy(messages = state.value.messages + userMessage("tail-after-scroll", "synthetic next"))
        }
        compose.waitForIdle()

        compose.onNodeWithTag("group-message-$anchorId").assertIsDisplayed()
        val after = nodeBounds("group-message-$anchorId")
        assertEquals("a new message does not pull the reader to the bottom", before.top, after.top, 1f)
    }

    @Test
    fun scrolledUpAcceptedSendReturnsToLatestWhileRejectedSendStaysPut() {
        val initial = (0 until 70).map { index ->
            userMessage("msg-%03d".format(index), "synthetic row $index")
        }
        val state = mutableStateOf(
            loadedState(initial, historySessionId = "send-return", hasOlder = false)
                .copy(input = "群里新发言"),
        )
        var acceptNextSend = false

        compose.setContent {
            PulsezTheme {
                GroupChatScreen(
                    state = state.value,
                    onInputChange = { state.value = state.value.copy(input = it) },
                    onSend = {},
                    onSendWithAcceptance = { onAccepted ->
                        if (acceptNextSend) {
                            state.value = state.value.copy(
                                input = "",
                                messages = state.value.messages + userMessage("sent-latest", "群里新发言"),
                            )
                            onAccepted()
                        }
                    },
                    onStop = {},
                    onBack = {},
                    onOpenMemory = {},
                    onDismissAiNotice = {},
                    onRetry = {},
                )
            }
        }
        compose.waitForIdle()

        val list = compose.onNodeWithTag(GROUP_HISTORY_LIST_TEST_TAG)
        repeat(3) {
            list.performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        val visibleIds = (0 until 70).map { "msg-%03d".format(it) }.filter { id ->
            hasMessage(id)
        }
        assertTrue("gesture scrolls away from the newest row", visibleIds.any { it != "msg-069" })
        val anchorId = visibleIds.first { it != "msg-069" }
        val anchorBefore = nodeBounds("group-message-$anchorId").top

        // 发送被拒（未回调 onAccepted）时，保留当前阅读位置。
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitForIdle()
        val anchorAfterReject = nodeBounds("group-message-$anchorId").top
        assertEquals("rejected group send must not jump to latest", anchorBefore, anchorAfterReject, 1f)

        // 发送被接受后，无视离底状态立即滚回索引 0（最新底部）。
        acceptNextSend = true
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("group-message-sent-latest").assertIsDisplayed()
        assertLatestNearBottom("sent-latest")
    }

    @Test
    fun scrollToLatestButtonAppearsWhenScrolledUpMeetsTouchSizeAndRestoresFollow() {
        val initial = (0 until 70).map { index ->
            userMessage("msg-%03d".format(index), "synthetic row $index")
        }
        val state = mutableStateOf(
            loadedState(initial, historySessionId = "return-button", hasOlder = false),
        )
        var density = 1f

        compose.setContent {
            density = LocalDensity.current.density
            PulsezTheme {
                GroupChatScreen(
                    state = state.value,
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
        compose.waitForIdle()
        compose.onNodeWithTag(GROUP_SCROLL_TO_LATEST_TEST_TAG).assertDoesNotExist()

        val list = compose.onNodeWithTag(GROUP_HISTORY_LIST_TEST_TAG)
        repeat(3) {
            list.performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        compose.onNodeWithTag(GROUP_SCROLL_TO_LATEST_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("回到最新").assertIsDisplayed()
        val buttonBounds = nodeBounds(GROUP_SCROLL_TO_LATEST_TEST_TAG)
        val minTouchPx = 48f * density - 0.5f
        assertTrue("group scroll-to-latest button width must be >= 48dp", buttonBounds.width >= minTouchPx)
        assertTrue("group scroll-to-latest button height must be >= 48dp", buttonBounds.height >= minTouchPx)

        compose.onNodeWithTag(GROUP_SCROLL_TO_LATEST_TEST_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("group-message-msg-069").assertIsDisplayed()
        assertLatestNearBottom("msg-069")
        compose.onNodeWithTag(GROUP_SCROLL_TO_LATEST_TEST_TAG).assertDoesNotExist()

        compose.runOnIdle {
            state.value = state.value.copy(
                messages = state.value.messages + userMessage("after-group-return", "following reply"),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("group-message-after-group-return").assertIsDisplayed()
        assertLatestNearBottom("after-group-return")
    }

    private fun nodeBounds(tag: String): Rect =
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertLatestNearBottom(id: String) {
        val listBounds = nodeBounds(GROUP_HISTORY_LIST_TEST_TAG)
        val latestBounds = nodeBounds("group-message-$id")
        assertTrue(latestBounds.bottom in listBounds.top..listBounds.bottom)
        assertTrue("the fresh tail begins at the viewport bottom", listBounds.bottom - latestBounds.bottom < 120f)
    }

    private fun assertTailLineVisible(fullText: String, marker: String) {
        val listBounds = nodeBounds(GROUP_HISTORY_LIST_TEST_TAG)
        val messageNode = textNode(marker)
        assertTrue("the final message should be taller than the viewport", messageNode.size.height.toFloat() > listBounds.height)
        val tailLine = textLinePosition(fullText, marker)
        assertTrue("the last text line must land inside the viewport", tailLine.bottom in listBounds.top..listBounds.bottom)
    }

    private fun isTextLineVisible(fullText: String, marker: String): Boolean {
        val listBounds = nodeBounds(GROUP_HISTORY_LIST_TEST_TAG)
        val line = textLinePosition(fullText, marker)
        return line.bottom > listBounds.top && line.top < listBounds.bottom
    }

    private fun visibleLineAnchor(fullText: String, searchableText: String): TextLineAnchor {
        val node = textNode(searchableText)
        val layout = textLayout(node)
        val viewport = nodeBounds(GROUP_HISTORY_LIST_TEST_TAG)
        val line = (0 until layout.lineCount).mapNotNull { index ->
            val top = node.positionInRoot.y + layout.getLineTop(index)
            val bottom = node.positionInRoot.y + layout.getLineBottom(index)
            if (top < viewport.top || bottom > viewport.bottom) return@mapNotNull null
            val start = layout.getLineStart(index).coerceIn(0, fullText.length)
            val end = layout.getLineEnd(index).coerceIn(start, fullText.length)
            val text = fullText.substring(start, end).trim()
            if (text.isBlank()) null else TextLineAnchor(text = text, bottom = bottom)
        }.minByOrNull { kotlin.math.abs((it.bottom - viewport.top) - viewport.height / 2f) }
        return requireNotNull(line) { "the scrolled viewport should contain a complete line from the long message" }
    }

    private fun lineBottomInRoot(fullText: String, marker: String): Float =
        textLinePosition(fullText, marker).bottom

    private fun textLinePosition(fullText: String, marker: String): TextLinePosition {
        val offset = fullText.indexOf(marker)
        require(offset >= 0) { "text fixture is missing marker: $marker" }
        val node = textNode(marker)
        val layout = textLayout(node)
        val line = layout.getLineForOffset(offset)
        return TextLinePosition(
            top = node.positionInRoot.y + layout.getLineTop(line),
            bottom = node.positionInRoot.y + layout.getLineBottom(line),
        )
    }

    private fun textNode(marker: String) =
        compose.onNodeWithText(marker, substring = true).fetchSemanticsNode()

    private fun textLayout(node: SemanticsNode): TextLayoutResult {
        val textLayouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(textLayouts)
        return requireNotNull(textLayouts.firstOrNull()) { "Text layout result should be exposed to Compose tests" }
    }

    private data class TextLinePosition(val top: Float, val bottom: Float)

    private data class TextLineAnchor(val text: String, val bottom: Float)

    private fun loadedState(
        messages: List<GroupMessageUi>,
        historySessionId: String,
        hasOlder: Boolean,
    ) = GroupChatUiState(
        historyLoaded = true,
        historySessionId = historySessionId,
        hasOlderHistory = hasOlder,
        showAiNotice = false,
        members = listOf(
            GroupMemberUi(id = "member-a", name = "甲"),
            GroupMemberUi(id = "member-b", name = "乙"),
        ),
        messages = messages,
    )

    private fun loadingState(sessionId: String) = GroupChatUiState(
        historyLoaded = false,
        historySessionId = sessionId,
        showAiNotice = false,
    )

    private fun hasMessage(id: String): Boolean =
        compose.onAllNodesWithTag("group-message-$id")
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()

    private fun isMessageIntersectingViewport(id: String, viewport: Rect): Boolean =
        compose.onAllNodesWithTag("group-message-$id")
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .any { node ->
                val bounds = node.boundsInRoot
                bounds.width > 0f && bounds.height > 0f && bounds.bottom > viewport.top && bounds.top < viewport.bottom
            }

    private fun visibleMessageIds(ids: List<String>, viewport: Rect): List<String> = ids.filter { id ->
        compose.onAllNodesWithTag("group-message-$id")
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .any { node ->
                val bounds = node.boundsInRoot
                bounds.width > 0f && bounds.height > 0f && bounds.bottom > viewport.top && bounds.top < viewport.bottom
            }
    }

    private fun userMessage(id: String, text: String) = GroupMessageUi(
        id = id,
        role = GroupMessageRole.USER,
        text = text,
    )

    private fun memberMessage(
        id: String,
        text: String,
        kind: GroupMessageKind,
        addressed: Boolean,
    ) = GroupMessageUi(
        id = id,
        role = GroupMessageRole.MEMBER,
        text = text,
        speakerId = "member-a",
        speakerName = "甲",
        kind = kind,
        addressed = addressed,
    )
}
