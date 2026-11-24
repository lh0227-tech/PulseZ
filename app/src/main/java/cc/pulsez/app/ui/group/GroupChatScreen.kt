package cc.pulsez.app.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import cc.pulsez.app.core.MentionParser
import cc.pulsez.app.ui.chat.AiNoticeBar
import cc.pulsez.app.ui.chat.ChatAutoScroll
import cc.pulsez.app.ui.chat.ChatScrollSnapshot
import cc.pulsez.app.ui.chat.KeyboardSendAcceptanceGate
import cc.pulsez.app.ui.chat.ScrollToLatestButton
import cc.pulsez.app.ui.chat.currentImeVisibility
import cc.pulsez.app.ui.chat.TypingDots
import cc.pulsez.app.ui.theme.PulsezTheme

/**
 * 「一起聊」群聊页（群聊语义按 定稿）。
 *
 * 这不是"一群人轮流刷屏"，是「轮流被宠」：
 * - 一条用户消息**默认只有 1 条主回**；偶尔 1 条 ≤20 字的短反应，视觉更轻；
 * - 角色消息无气泡，主回上方一行名字标签 `caption` + `onSurfaceVariant`；
 *   同一个人连续说两条时合并名字标签，减少视觉噪音；
 * - 沉默（`[PASS]`）与短反应失败**完全无痕**：状态里没有这条消息，页面就不渲染任何东西；
 * - 主回失败只有一行可点的系统小字「刚刚没接上，点这里重试」，不出现任何占位气泡；
 * - "正在写"永远只有一个当前发言者。
 *
 * 页面只吃 [GroupChatUiState] + 回调，方便纯内存 fake 渲染（见文件末尾 @Preview）。
 */
@Composable
fun GroupChatScreen(
    state: GroupChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendWithAcceptance: ((onAccepted: () -> Unit) -> Unit)? = null,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onDismissAiNotice: () -> Unit,
    onRetry: (String) -> Unit,
    onLoadOlder: () -> Unit = {},
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = currentImeVisibility()
    val currentImeVisibility by rememberUpdatedState(imeVisible)
    val keyboardHideGate = remember(state.historySessionId) { KeyboardSendAcceptanceGate() }
    var acceptedSendToken by remember(state.historySessionId) { mutableStateOf(0L) }
    var legacySendPending by remember(state.historySessionId) { mutableStateOf(false) }
    var legacySendBaselineId by remember(state.historySessionId) { mutableStateOf<String?>(null) }
    // This callback belongs to the current ViewModel send and arrives only after its local commit.
    // New input, IME dismissal, stop, session change, and disposal invalidate delayed callbacks.
    // 依据：GroupChatViewModel.kt:345-389; older-page loading prepends at :201-218.
    LaunchedEffect(imeVisible, keyboardHideGate) {
        if (!imeVisible) keyboardHideGate.invalidate()
    }
    DisposableEffect(keyboardHideGate) {
        onDispose { keyboardHideGate.invalidate() }
    }
    val requestSend = {
        val imeOnAccepted = if (imeVisible) {
            keyboardHideGate.callbackForCurrentIntent(
                isImeVisible = { currentImeVisibility },
                hideKeyboard = { keyboardController?.hide() },
            )
        } else {
            {}
        }
        val onAccepted = {
            imeOnAccepted()
            acceptedSendToken += 1L
        }
        if (onSendWithAcceptance != null) {
            onSendWithAcceptance(onAccepted)
        } else {
            legacySendBaselineId = state.messages.lastOrNull()?.id
            legacySendPending = true
            onSend()
        }
    }

    // 短反应合并进主回那一项渲染，所以列表行数 ≠ 消息条数
    val rows = remember(state.messages) { state.messages.toMessageRows() }
    val itemCount = rows.size + if (state.typingSpeakerName != null) 1 else 0
    // 发送前不拦：没匹配上的 @xxx 只提示一句「会当成普通文字」（规则 10、§5.4）
    val mentionHint = remember(state.input, state.members) {
        unknownMentionHint(state.input, state.members)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 与 1:1 聊天页一致：键盘内边距只在本页处理
            // 依据：https://developer.android.com/develop/ui/compose/system/insets-ui (`imePadding` applies `WindowInsets.ime`).
            .imePadding(),
    ) {
        GroupTopBar(
            title = state.title,
            members = state.members,
            onBack = onBack,
            onOpenMemory = onOpenMemory,
        )

        if (state.showAiNotice) {
            AiNoticeBar(
                text = GroupChatUiState.AI_NOTICE,
                onDismiss = onDismissAiNotice,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (!state.historyLoaded) {
                Box(Modifier.fillMaxSize())
            } else if (itemCount == 0) {
                GroupEmptyState()
            } else {
                key(state.historySessionId) {
                    GroupHistoryTimeline(
                        state = state,
                        rows = rows,
                        acceptedSendToken = acceptedSendToken,
                        legacySendPending = legacySendPending,
                        legacySendBaselineId = legacySendBaselineId,
                        onConsumeLegacySend = { legacySendPending = false },
                        onRetry = onRetry,
                        onLoadOlder = onLoadOlder,
                    )
                }
            }
        }

        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        GroupComposer(
            value = state.input,
            members = state.members,
            isStreaming = state.streaming,
            onValueChange = { newValue ->
                if (newValue != state.input) keyboardHideGate.invalidate()
                onInputChange(newValue)
            },
            onUserInteraction = keyboardHideGate::invalidate,
            onSend = requestSend,
            onStop = {
                keyboardHideGate.invalidate()
                onStop()
            },
        )

        mentionHint?.let { hint ->
            // 输入框下方一行 caption；发送后 state.input 清空，提示自然消失（不在发送后弹任何东西）
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
    }
}

/** Newest row is list item 0 at the bottom; this makes the first frame tail-aligned without a scroll. */
@Composable
private fun GroupHistoryTimeline(
    state: GroupChatUiState,
    rows: List<MessageRow>,
    acceptedSendToken: Long,
    legacySendPending: Boolean,
    legacySendBaselineId: String?,
    onConsumeLegacySend: () -> Unit,
    onRetry: (String) -> Unit,
    onLoadOlder: () -> Unit,
) {
    val newestFirstRows = remember(rows) { rows.asReversed() }
    val itemCount = newestFirstRows.size + if (state.typingSpeakerName != null) 1 else 0
    // reverseLayout + item 0 starts at the bottom, including when that item is taller than the viewport.
    // 依据：https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyColumn.composable
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 0)
    var snapshot by remember { mutableStateOf<ChatScrollSnapshot?>(null) }
    var stickyAtLatest by remember { mutableStateOf(true) }
    var userScrolling by remember { mutableStateOf(false) }
    var activeDrag by remember { mutableStateOf(false) }
    var programmaticScrolling by remember { mutableStateOf(false) }
    var programmaticScrollToken by remember { mutableStateOf(0L) }
    var followSignal by remember { mutableStateOf(0L) }
    var consumedSendToken by remember { mutableStateOf(acceptedSendToken) }
    var previousOldestIndex by remember { mutableStateOf(0) }
    var previousScrollOffset by remember { mutableStateOf(0) }
    var typingWasVisible by remember { mutableStateOf(state.typingSpeakerName != null) }
    val currentTypingPresent by rememberUpdatedState(state.typingSpeakerName != null)
    var lastKnownScrollPosition by remember { mutableStateOf(0 to 0) }
    var pendingLatestHeightCorrection by remember { mutableStateOf(0) }
    var pendingLatestHeightAnchorKey by remember { mutableStateOf<String?>(null) }
    val scrollCorrectionScope = rememberCoroutineScope()

    fun compensateLatestHeightChange(
        position: Pair<Int, Int>,
        anchorKey: String,
        heightDelta: Int,
    ) {
        scrollCorrectionScope.launch {
            val currentIndex = listState.firstVisibleItemIndex
            val firstVisibleItem = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == currentIndex }
            if (userScrolling || stickyAtLatest || firstVisibleItem?.key != anchorKey) return@launch
            programmaticScrolling = true
            try {
                listState.scrollToItem(
                    index = currentIndex,
                    scrollOffset = position.second + heightDelta,
                )
            } finally {
                programmaticScrolling = false
                programmaticScrollToken += 1L
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { lastKnownScrollPosition = it }
    }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    activeDrag = true
                    userScrolling = true
                    pendingLatestHeightCorrection = 0
                    pendingLatestHeightAnchorKey = null
                    previousOldestIndex = listState.firstVisibleItemIndex
                    previousScrollOffset = listState.firstVisibleItemScrollOffset
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    activeDrag = false
                }
            }
        }
    }

    LaunchedEffect(listState) {
        var sawUserScrollInProgress = false
        var lastObservedPos = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        var lastObservedTypingPresent = currentTypingPresent
        var lastObservedProgrammaticToken = programmaticScrollToken
        snapshotFlow {
            GroupScrollSettlementObservation(
                dragging = activeDrag,
                scrolling = listState.isScrollInProgress,
                pos = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
                typingPresent = currentTypingPresent,
                programmaticToken = programmaticScrollToken,
            )
        }.collect { obs ->
            if (obs.scrolling && !programmaticScrolling) {
                sawUserScrollInProgress = true
            }
            val typingChanged = obs.typingPresent != lastObservedTypingPresent
            val programmaticShift = obs.programmaticToken != lastObservedProgrammaticToken ||
                programmaticScrolling
            val nonProgrammaticPosShift = obs.pos != lastObservedPos &&
                !programmaticShift &&
                !typingChanged
            lastObservedPos = obs.pos
            lastObservedTypingPresent = obs.typingPresent
            lastObservedProgrammaticToken = obs.programmaticToken

            if (!obs.dragging && !obs.scrolling && (userScrolling || sawUserScrollInProgress || nonProgrammaticPosShift)) {
                sawUserScrollInProgress = false
                // 群聊贴底容差放宽至 <= BOTTOM_TOLERANCE_PX (2px)，与单聊对齐。依据：ChatScreen.kt:1951。
                stickyAtLatest = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset <= ChatAutoScroll.BOTTOM_TOLERANCE_PX
                userScrolling = false
                val pendingCorrection = pendingLatestHeightCorrection
                val pendingAnchorKey = pendingLatestHeightAnchorKey
                pendingLatestHeightCorrection = 0
                pendingLatestHeightAnchorKey = null
                val firstVisibleItem = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == listState.firstVisibleItemIndex }
                // A drag may move from the changing tail row to an older row before it ends. Never
                // replay that tail row's deferred delta against a different first-visible item.
                if (
                    pendingCorrection != 0 &&
                    !stickyAtLatest &&
                    pendingAnchorKey != null &&
                    firstVisibleItem?.key == pendingAnchorKey
                ) {
                    compensateLatestHeightChange(
                        position = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
                        anchorKey = pendingAnchorKey,
                        heightDelta = pendingCorrection,
                    )
                }
            }
        }
    }

    // 依据：https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState
    // reverseLayout 令前进方向指向旧消息；firstVisibleItemScrollOffset 前进时为正。
    LaunchedEffect(listState, state.hasOlderHistory, state.loadingOlderHistory, newestFirstRows.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisibleIndex = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, lastVisibleIndex)
        }.collect { (index, offset, lastVisibleIndex) ->
            if (userScrolling) {
                val movedTowardOlder = movedTowardOlderMessages(
                    previousIndex = previousOldestIndex,
                    previousOffset = previousScrollOffset,
                    currentIndex = index,
                    currentOffset = offset,
                )
                if (
                    movedTowardOlder &&
                    lastVisibleIndex >= (newestFirstRows.size - OLDER_PREFETCH_ROWS).coerceAtLeast(0) &&
                    state.hasOlderHistory &&
                    !state.loadingOlderHistory
                ) {
                    onLoadOlder()
                }
                previousOldestIndex = index
                previousScrollOffset = offset
            }
        }
    }

    LaunchedEffect(listState) {
        var processedSignal = 0L
        snapshotFlow { followSignal }.collect { latestSignal ->
            if (latestSignal <= processedSignal) return@collect
            while (processedSignal < followSignal) {
                processedSignal = followSignal
                if (userScrolling || activeDrag) break
                withFrameNanos {}
                if (userScrolling || activeDrag) break
                programmaticScrolling = true
                try {
                    // In reverse layout item 0 is the latest message (or the typing row).
                    listState.animateScrollToItem(0)
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    break
                } finally {
                    programmaticScrolling = false
                    programmaticScrollToken += 1L
                }
            }
        }
    }

    LaunchedEffect(
        itemCount,
        state.messages.lastOrNull()?.id,
        state.messages.lastOrNull()?.text?.length,
        state.messages.size,
        state.typingSpeakerName,
        acceptedSendToken,
        legacySendPending,
    ) {
        val last = state.messages.lastOrNull()
        val current = ChatScrollSnapshot(
            lastMessageId = last?.id,
            lastMessageTextLength = last?.text?.length ?: 0,
            messageCount = state.messages.size,
            itemCount = itemCount,
        )
        val hasAcceptedSend = acceptedSendToken > consumedSendToken
        if (hasAcceptedSend) {
            consumedSendToken = acceptedSendToken
        }
        val hasNewUserTailMessage = if (legacySendPending && current.lastMessageId != legacySendBaselineId) {
            val baselineIdx = legacySendBaselineId?.let { id ->
                state.messages.indexOfLast { it.id == id }
            } ?: -1
            val newTailSlice = if (baselineIdx >= 0) {
                state.messages.subList(baselineIdx + 1, state.messages.size)
            } else {
                state.messages
            }
            onConsumeLegacySend()
            newTailSlice.any { it.role == GroupMessageRole.USER }
        } else {
            false
        }
        val userSent = hasAcceptedSend || hasNewUserTailMessage
        val previous = snapshot
        val typingStarted = state.typingSpeakerName != null && !typingWasVisible
        typingWasVisible = state.typingSpeakerName != null
        snapshot = current
        if (userSent) {
            // 用户主动发送已提交：强制解除离底态并滚回索引 0。依据：GroupChatViewModel.kt:389。
            stickyAtLatest = true
            userScrolling = false
            pendingLatestHeightCorrection = 0
            pendingLatestHeightAnchorKey = null
            followSignal += 1L
            return@LaunchedEffect
        }
        // The first composition is already anchored at item 0; page prepends leave the tail unchanged.
        if (previous == null) return@LaunchedEffect
        val newTail = current.lastMessageId != previous.lastMessageId ||
            current.lastMessageTextLength > previous.lastMessageTextLength
        if ((newTail || typingStarted) && stickyAtLatest && !userScrolling) {
            followSignal += 1L
        }
    }

    // reverseLayout = true 下，向索引 0（最新底部）滚动对应 canScrollBackward。依据：GroupChatScreen.kt:487。
    val hasRows by rememberUpdatedState(newestFirstRows.isNotEmpty())
    val showScrollToLatest by remember {
        derivedStateOf {
            hasRows && !stickyAtLatest && listState.canScrollBackward
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().testTag(GROUP_HISTORY_LIST_TEST_TAG),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
            // Keep a short history at the bottom too; reverseLayout alone does not bottom-align custom arrangements.
            // 依据：https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyColumn.composable
            verticalArrangement = Arrangement.spacedBy(10.dp, alignment = Alignment.Bottom),
        ) {
        state.typingSpeakerName?.let { name ->
            item("typing") { TypingRow(name = name) }
        }
        // Stable keys keep the visible row and pixel offset when the older page is appended here.
        // 依据：https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListScope.html
        itemsIndexed(newestFirstRows, key = { _, row -> row.message.id }) { reverseIndex, row ->
            val chronologicalIndex = rows.lastIndex - reverseIndex
            val rowScrollIndex = reverseIndex + if (state.typingSpeakerName != null) 1 else 0
            var previousRowHeight by remember(row.message.id) { mutableStateOf<Int?>(null) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        val previousHeight = previousRowHeight
                        previousRowHeight = size.height
                        val scrollPosition = lastKnownScrollPosition
                        val heightDelta = previousHeight?.let { size.height - it } ?: 0
                        val firstVisibleItem = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == listState.firstVisibleItemIndex }
                        if (
                            reverseIndex == 0 &&
                            heightDelta != 0 &&
                            (userScrolling || !stickyAtLatest) &&
                            scrollPosition.first == rowScrollIndex &&
                            firstVisibleItem?.key == row.message.id &&
                            (pendingLatestHeightAnchorKey == null || pendingLatestHeightAnchorKey == row.message.id)
                        ) {
                            // In reverseLayout, positive scroll offset advances toward older rows. Offset the
                            // item-size change to keep the visible text line anchored. During an active drag,
                            // defer this correction until the gesture ends so it never competes with the user.
                            // 依据：https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState
                            if (userScrolling) {
                                pendingLatestHeightAnchorKey = row.message.id
                                pendingLatestHeightCorrection += heightDelta
                            } else {
                                compensateLatestHeightChange(
                                    position = scrollPosition,
                                    anchorKey = row.message.id,
                                    heightDelta = heightDelta,
                                )
                            }
                        }
                    }
                    .testTag("group-message-${row.message.id}"),
            ) {
                GroupMessageItem(
                    message = row.message,
                    reaction = row.reaction,
                    members = state.members,
                    showName = shouldShowName(
                        previous = rows.getOrNull(chronologicalIndex - 1)?.message,
                        message = row.message,
                    ),
                    onRetry = onRetry,
                )
            }
        }
        if (state.loadingOlderHistory) {
            item("older-loading") {
                Text(
                    text = "正在打开更早的消息……",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
    }

        if (showScrollToLatest) {
            ScrollToLatestButton(
                onClick = {
                    stickyAtLatest = true
                    userScrolling = false
                    pendingLatestHeightCorrection = 0
                    pendingLatestHeightAnchorKey = null
                    followSignal += 1L
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 12.dp)
                    .testTag(GROUP_SCROLL_TO_LATEST_TEST_TAG),
            )
        }
    }
}

private data class GroupScrollSettlementObservation(
    val dragging: Boolean,
    val scrolling: Boolean,
    val pos: Pair<Int, Int>,
    val typingPresent: Boolean,
    val programmaticToken: Long,
)

internal const val GROUP_HISTORY_LIST_TEST_TAG = "group-history-list"
internal const val GROUP_SCROLL_TO_LATEST_TEST_TAG = "group-scroll-to-latest"
internal fun movedTowardOlderMessages(
    previousIndex: Int,
    previousOffset: Int,
    currentIndex: Int,
    currentOffset: Int,
): Boolean = currentIndex > previousIndex ||
    (currentIndex == previousIndex && currentOffset > previousOffset)

private const val OLDER_PREFETCH_ROWS = 4

/**
 * 渲染行：一条主回（或用户消息）带一个可选的短反应。
 * 短反应跟在同轮主回下面，合并成一项，避免看起来像"两条回复"、也避免多留一个空行。
 */
private data class MessageRow(
    val message: GroupMessageUi,
    val reaction: GroupMessageUi?,
)

private fun List<GroupMessageUi>.toMessageRows(): List<MessageRow> {
    // 防御：空正文且不是"主回失败重试"的消息一律不进列表。
    // 引擎的 Passed / 短反应失败本来就不该落到 state；这里再挡一次，保证任何映射错误都只会"少一行"，
    // 绝不会出现空白名字标签、空行或灰色占位。
    val source = filter { it.failed || it.text.isNotBlank() }
    return buildList {
        var index = 0
        while (index < source.size) {
            val message = source[index]
            val reaction = source.getOrNull(index + 1)?.takeIf {
                message.kind == GroupMessageKind.MAIN &&
                    message.role == GroupMessageRole.MEMBER &&
                    it.kind == GroupMessageKind.REACTION
            }
            add(MessageRow(message = message, reaction = reaction))
            index += if (reaction != null) 2 else 1
        }
    }
}

/**
 * 名字标签合并：上一条已经是同一个人的主回时不再重复显示名字，
 * 连续两条读起来像一个人在接着说，而不是"两条回复"。
 */
private fun shouldShowName(previous: GroupMessageUi?, message: GroupMessageUi): Boolean {
    if (message.role != GroupMessageRole.MEMBER) return false
    if (previous == null) return true
    val sameSpeakerContinues = previous.role == GroupMessageRole.MEMBER &&
        previous.kind == GroupMessageKind.MAIN &&
        !previous.failed &&
        previous.speakerId == message.speakerId
    return !sameSpeakerContinues
}

/** 顶栏：返回、叠放头像（≤4、重叠 12dp）、标题、记忆入口。*/
@Composable
private fun GroupTopBar(
    title: String,
    members: List<GroupMemberUi>,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        if (members.isNotEmpty()) {
            StackedAvatars(members = members)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onOpenMemory, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Info, contentDescription = "TA 记得的事")
        }
    }
}

/**
 * 叠放头像：最多显示 4 个，相邻重叠 12dp。
 * 重叠处压一圈页面底色的 2dp 环把两个头像分开——这是"切开"，不是装饰描边/光晕（禁用清单）。
 */
@Composable
private fun StackedAvatars(members: List<GroupMemberUi>, modifier: Modifier = Modifier) {
    val shown = members.take(GroupRules.MAX_MEMBERS)
    if (shown.isNotEmpty()) {
        val avatarSize = 28.dp
        val overlap = 12.dp
        val step = avatarSize - overlap
        val totalWidth = avatarSize + step * (shown.size - 1)
        val description = "群成员：" + shown.joinToString("、") { it.name }

        Box(
            modifier = modifier
                .size(width = totalWidth, height = avatarSize)
                .semantics { contentDescription = description },
        ) {
            shown.forEachIndexed { index, member ->
                Box(
                    modifier = Modifier
                        .offset(x = step * index)
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = member.name.take(1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 一条消息（主回 / 短反应 / 用户 / 失败）。
 *
 * 视觉层级：
 * - 主回：名字标签 caption + 正文 bodyLarge，无气泡；
 * - 短反应：caption + onSurfaceVariant（字号与颜色都降一档），行内带名字，紧跟主回下方；
 * - 失败：只有一行可点的系统小字，没有名字、没有空行、没有占位气泡。
 */
@Composable
private fun GroupMessageItem(
    message: GroupMessageUi,
    reaction: GroupMessageUi?,
    members: List<GroupMemberUi>,
    showName: Boolean,
    onRetry: (String) -> Unit,
) {
    when {
        message.role == GroupMessageRole.USER -> {
            // 用户消息：右侧深色胶囊，与 1:1 一致；@名字 用同色加粗下划线高亮
            GroupUserMessageBubble(text = message.text, members = members)
        }

        message.failed -> {
            // 主回没接上：只给一次重试入口，不写"XX 暂时没说话"
            val retryText = message.failureHint
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it（点这里重试）" }
                ?: "刚刚没接上，点这里再试一次。"
            TextButton(
                onClick = { onRetry(message.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    text = retryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        message.kind == GroupMessageKind.REACTION -> {
            // 兜底：短反应若没有主回可挂（正常不该出现），单独按轻量行渲染
            ReactionLine(message = message)
        }

        else -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (showName) {
                    // 名字标签：caption + onSurfaceVariant
                    Text(
                        text = message.speakerName ?: "TA",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                if (message.addressed) {
                    // 被 @ 出来的那条回复才有的极轻标记：
                    // 一行 caption，无动画/音效/tooltip；未被 @ 的角色绝不能有这个标记
                    Text(
                        text = "点了 TA 的名",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                // 正文仍然无气泡（TA 的话不用气泡）
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 320.dp),
                )
                reaction?.let {
                    Spacer(Modifier.height(4.dp))
                    ReactionLine(message = it)
                }
            }
        }
    }
}

/**
 * 用户消息气泡（与 `ui/chat/UserMessageBubble` 同一套 token：深色 `onBackground` 底、
 * `background` 色文字、右下角小圆角）。群聊页要能对 `@名字` 高亮，所以这里用 [AnnotatedString] 重绘。
 *
 * 高亮方式按 的纪律：**不用彩色文字强调**（气泡底在浅/深色下恰好是反色，彩色不可控），
 * 用同色加粗 + 下划线，对比度天然与正文一致（允许「浅色下划线」）。
 */
@Composable
private fun GroupUserMessageBubble(text: String, members: List<GroupMemberUi>) {
    val annotated = highlightMentions(text, members)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 6.dp,
                    ),
                )
                .background(MaterialTheme.colorScheme.onBackground)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * 把用户正文里的 `@名字` 标成「同色加粗 + 下划线」；只在渲染时按当前成员名重算，
 * 不落库、不改写原文（规则 9：撤回/编辑/复制都要与原文一致）。
 */
private fun highlightMentions(text: String, members: List<GroupMemberUi>): AnnotatedString {
    val ranges = MentionParser.highlightRanges(text, members.toCoreMembers())
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val style = SpanStyle(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)
        for (range in ranges) {
            addStyle(style, range.first, range.last + 1)
        }
    }
}

/**
 * 输入框下方的「没找到」提示：发送前只提示、不拦截（防误触第 4 条）。
 *
 * @return 需要显示的 caption；没有未知 @ 时返回 null
 */
private fun unknownMentionHint(input: String, members: List<GroupMemberUi>): String? {
    if (input.isBlank()) return null
    val unknown = MentionParser.parse(input, members.toCoreMembers()).unknownTokens
    if (unknown.isEmpty()) return null
    return "没找到 " + unknown.joinToString("、") { "@$it" } + "，会当成普通文字"
}

/**
 * 短反应：更轻的一行（≤20 字、§5.7 caption + 行内名字）。
 *
 * 同轮被 @ 的第二个人给短反应时，也要有「被叫到」标记（的 2dp 圆点）。
 * 只加一个 2dp 圆点，无动画、无音效、无 tooltip。
 */
@Composable
private fun ReactionLine(message: GroupMessageUi) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 320.dp),
    ) {
        if (message.addressed) {
            // 被叫到标记：与主回名字旁的 2dp 圆点同一规格；落在名字前后的行首。
            Box(
                modifier = Modifier
                    .size(2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = "${message.speakerName ?: "TA"}：${message.text}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 生成中：只有**当前**发言者的名字 + 三点，绝不出现多个"正在写"。*/
@Composable
private fun TypingRow(name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$name 正在写",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        TypingDots(label = "$name 正在写")
    }
}

/** 空态：一句话，动作就是下面的输入条。*/
@Composable
private fun GroupEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "说点什么，TA 们都在（输入 @ 可点名）。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- 预览用假状态

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun GroupChatScreenPreview() {
    PulsezTheme(darkTheme = false) {
        GroupChatScreen(
            state = GroupChatUiState.preview(),
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

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun GroupChatEmptyPreview() {
    PulsezTheme(darkTheme = true) {
        GroupChatScreen(
            state = GroupChatUiState(
                historyLoaded = true,
                members = listOf(
                    GroupMemberUi(id = "m1", name = "阿淮"),
                    GroupMemberUi(id = "m2", name = "林野"),
                ),
            ),
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
