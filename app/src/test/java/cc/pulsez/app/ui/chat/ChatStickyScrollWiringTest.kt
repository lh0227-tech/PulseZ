package cc.pulsez.app.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **粘性贴底、解耦滚动执行器、主动发送回底与「回到最新」入口**的 Compose 接线源码扫描。
 *
 * 单聊沿用 [ChatAutoScrollTest] 覆盖的几何偏移状态机；群聊使用 `reverseLayout = true`，以最新项
 * `index == 0 && offset <= BOTTOM_TOLERANCE_PX` 作为贴底位置。两页的 Compose 接线由源码扫描锁住：
 * - 两页都以 `interactionSource` 的 `DragInteraction.Start/Stop/Cancel` 维护 `activeDrag`，在 `!dragging && !scrolling` 时刷新粘性；
 * - 两页都接入 `onSendWithAcceptance` 递增会话隔离的 `acceptedSendToken`，主动发送时强制回底；
 * - 单聊判定端与执行端解耦（`followSignal` + `withFrameNanos {}` + `tailOffsetForFollow` + 键盘缩视口补偿）；
 * - 群聊「回到最新」使用 `listState.canScrollBackward`（向索引 0 滚），单聊使用 `listState.canScrollForward`。
 *
 * 滚动位置和视口变化共同决定是否跟随。
 */
class ChatStickyScrollWiringTest {

    private companion object {
        const val CHAT_SCREEN_PATH = "src/main/java/cc/pulsez/app/ui/chat/ChatScreen.kt"
        const val GROUP_SCREEN_PATH = "src/main/java/cc/pulsez/app/ui/group/GroupChatScreen.kt"
    }

    private val chatScreenText: String by lazy { File(CHAT_SCREEN_PATH).readText(Charsets.UTF_8) }
    private val groupScreenText: String by lazy { File(GROUP_SCREEN_PATH).readText(Charsets.UTF_8) }

    @Test
    fun `单聊接线 粘性贴底 会话隔离 视口补偿与回到最新齐全`() {
        assertWiring(page = "单聊", text = chatScreenText)
    }

    @Test
    fun `群聊反向布局以最新项索引跟随 发送回底且保留旧页手势门槛`() {
        assertGroupReverseLayoutWiring(groupScreenText)
    }

    @Test
    fun `单聊跟随解耦判定与合并滚动执行器并在未布局时等待帧测量`() {
        assertFollowOffsetWiring(page = "单聊", text = chatScreenText)
    }

    private fun assertWiring(page: String, text: String) {
        // 拖拽起手与结束/取消：维护 activeDrag，在静止边沿刷新粘性并复位 userScrolling。
        assertContains(page, text, "listState.interactionSource.interactions.collect")
        assertContains(page, text, "is DragInteraction.Start ->")
        assertContains(page, text, "is DragInteraction.Stop, is DragInteraction.Cancel ->")
        assertContains(page, text, "activeDrag = true")
        assertContains(page, text, "activeDrag = false")
        assertContains(page, text, "userScrolling = true")
        assertContains(page, text, "!obs.dragging && !obs.scrolling")
        // 刷新用偏移判据（真实布局几何），不是索引判据。
        assertContains(page, text, "stickyAtBottom = StickyBottom.update(")
        assertContains(page, text, "ChatAutoScroll.isAtBottomByGeometry(")
        // 初值与会话隔离：绑定 sendSessionKey。
        assertContains(page, text, "var stickyAtBottom by remember(sendSessionKey) { mutableStateOf(true) }")
        assertContains(page, text, "var userScrolling by remember(sendSessionKey) { mutableStateOf(false) }")
        assertContains(page, text, "var acceptedSendToken by remember(sendSessionKey) { mutableStateOf(0L) }")
        // 调用点：把 sticky/userScrolling/userSent/reasoning/citations/isStreaming 交给纯函数。
        assertContains(page, text, "stickyAtBottom = stickyAtBottom,")
        assertContains(page, text, "userScrolling = userScrolling,")
        assertContains(page, text, "userSent = userSent,")
        assertContains(page, text, "followAuxiliaryRow = true,")
        assertContains(page, text, "lastReasoningLength = lastMessage?.reasoning?.length ?: 0,")
        assertContains(page, text, "lastCitationsCount = lastMessage?.citations?.size ?: 0,")
        assertContains(page, text, "isStreaming = isStreaming,")
        assertContains(page, text, "stickyAtBottom = true")
        // 视口缩小（软键盘弹出等）贴底补偿与「回到最新」入口。
        assertContains(page, text, "info.viewportSize.height")
        assertContains(page, text, "ScrollToLatestButton(")
        assertContains(page, text, "CHAT_SCROLL_TO_LATEST_TEST_TAG")
        assertContains(page, text, "listState.canScrollForward")
        // 粘性状态机本体只能来自同包纯 Kotlin。
        assertContains(page, text, "import androidx.compose.foundation.interaction.DragInteraction")
        assertContains(page, text, "import androidx.compose.runtime.snapshotFlow")
    }

    private fun assertGroupReverseLayoutWiring(text: String) {
        assertContains("群聊", text, "reverseLayout = true")
        assertContains("群聊", text, "rememberLazyListState(initialFirstVisibleItemIndex = 0)")
        assertContains("群聊", text, "var acceptedSendToken by remember(state.historySessionId) { mutableStateOf(0L) }")
        assertContains("群聊", text, "var stickyAtLatest by remember { mutableStateOf(true) }")
        assertContains("群聊", text, "var userScrolling by remember { mutableStateOf(false) }")
        assertContains("群聊", text, "is DragInteraction.Start ->")
        assertContains("群聊", text, "is DragInteraction.Stop, is DragInteraction.Cancel ->")
        assertContains("群聊", text, "!obs.dragging && !obs.scrolling")
        assertContains("群聊", text, "stickyAtLatest = listState.firstVisibleItemIndex == 0 &&")
        assertContains("群聊", text, "listState.firstVisibleItemScrollOffset <= ChatAutoScroll.BOTTOM_TOLERANCE_PX")
        assertContains("群聊", text, "if ((newTail || typingStarted) && stickyAtLatest && !userScrolling)")
        assertContains("群聊", text, "listState.animateScrollToItem(0)")
        assertContains("群聊", text, "if (userScrolling)")
        assertContains("群聊", text, "movedTowardOlderMessages(")
        assertContains("群聊", text, "movedTowardOlder &&")
        assertContains("群聊", text, "lastVisibleIndex >= (newestFirstRows.size - OLDER_PREFETCH_ROWS).coerceAtLeast(0)")
        assertContains("群聊", text, "state.hasOlderHistory")
        assertContains("群聊", text, "!state.loadingOlderHistory")
        assertContains("群聊", text, "verticalArrangement = Arrangement.spacedBy(10.dp, alignment = Alignment.Bottom)")
        assertContains("群聊", text, ".onSizeChanged { size ->")
        assertContains("群聊", text, "pendingLatestHeightCorrection += heightDelta")
        assertContains("群聊", text, "firstVisibleItem?.key == pendingAnchorKey")
        assertContains("群聊", text, "firstVisibleItem?.key == row.message.id")
        assertContains("群聊", text, "anchorKey = row.message.id")
        assertContains("群聊", text, "anchorKey = pendingAnchorKey")
        assertContains("群聊", text, "scrollOffset = position.second + heightDelta")
        // 群聊 reverseLayout = true 下，向索引 0 滚动对应 canScrollBackward。
        assertContains("群聊", text, "ScrollToLatestButton(")
        assertContains("群聊", text, "listState.canScrollBackward")
        assertContains("群聊", text, "GROUP_SCROLL_TO_LATEST_TEST_TAG")
    }

    private fun assertFollowOffsetWiring(page: String, text: String) {
        assertContains(page, text, "snapshotFlow { followSignal }.collect")
        assertContains(page, text, "withFrameNanos {}")
        assertContains(page, text, "tailOffsetForFollow(")
        val animateToItemWithOffset = Regex(
            """listState\.animateScrollToItem\(\s*targetItemIndex,\s*scrollOffset\s*=\s*requestedItemOffset,""",
        )
        assertTrue(
            "$page 跟随时 animateScrollToItem 没传目标偏移",
            animateToItemWithOffset.containsMatchIn(text),
        )
        assertContains(page, text, "listState.animateScrollToItem(targetItemIndex)")
        assertContains(page, text, "listState.animateScrollBy(tailOffset.toFloat())")
        assertContains(page, text, "currentCoroutineContext().ensureActive()")
        assertContains(page, text, "if (reachedBottom) {")
        assertContains(page, text, "pendingFollow = false")
    }

    private fun assertContains(page: String, text: String, needle: String) {
        assertTrue("$page 接线缺少：$needle", text.contains(needle))
    }
}
