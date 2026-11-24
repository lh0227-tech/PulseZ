package cc.pulsez.app.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.pulsez.app.media.CameraCapture
import cc.pulsez.app.media.DraftImage
import cc.pulsez.app.media.DraftRules
import cc.pulsez.app.net.Citation
import cc.pulsez.app.ui.copy.ImageCopy
import cc.pulsez.app.ui.copy.VoiceCopy
import cc.pulsez.app.ui.settings.CreativeModeIntroDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import java.io.File

/**
 * 聊天页。
 *
 * 设计约定：
 * - TA 说话不用气泡，只有正文；用户的消息是右侧深色胶囊。
 * - 交接（baton）不发生任何界面变化。
 * - AI 标识首次完整展示，之后折叠为常驻图标，不可移除。
 *
 * 输入条 / 三点 / 气泡 / AI 提示条已抽到 ChatComponents.kt，
 * 群聊页（ui/group/GroupChatScreen.kt）复用同一套视觉；本页行为不变。
 *
 * DeepSeek 对齐（v0.2）：
 * - 输入条第二行左侧两个胶囊「深度思考」「智能搜索」，**只在 DeepSeek 端点显示**（避免假开关）；
 * - 思考内容不进正文，折叠成一行「已深度思考（N 字）」；
 * - 联网搜索来源显示「参考了 N 个来源」，点击展开标题 + 地址；
 *   来源只做当轮展示、**不落库**（换页/重启丢失 v0.2 口径）。
 * - 群聊不需要这些开关，本页参数只被 1:1 聊天页使用。
 *
 * 创作模式（v0.4 v0.6.9 WP-D 补充「入戏」入口）：
 * - 日常态（`creativeMode == false`）顶栏显示「入戏」，未看过首次说明时先弹说明，看过后直接开启；
 * - 创作态（`creativeMode == true`）顶栏显示「出戏」，点它关掉模式并给一条轻提示
 *   「回到平常聊天了。」（走既有 `notice` 通道，中性色，不作错误）；
 * - 本页不新增任何视觉/动效，入戏出戏都是同一套聊天视觉。
 */
data class ChatUiMessage(
    val id: String,
    val role: String, // "user" | "assistant"
    val text: String,
    val failed: Boolean = false,
    /** 思考正文：只用于折叠展示，不写进正文、不落库。*/
    val reasoning: String? = null,
    /** 联网搜索来源：v0.2 只当轮展示，不落库。*/
    val citations: List<Citation> = emptyList(),
    /**
     * 这条消息带的本地图片（DB 相对路径，v0.5 识图）。
     *
     * UI **始终**显示本地缩略图；prompt 里旧图才换「[图片]」占位
     * （` 两者是两个通道，别混用——）。
     */
    val imagePaths: List<String> = emptyList(),
    /** Source-session provenance; null for regular chat messages. */
    val sourceSessionId: String? = null,
    val sourceTitle: String? = null,
    val sourceStartedAt: Long? = null,
)

@Composable
fun ChatScreen(
    characterName: String,
    messages: List<ChatUiMessage>,
    input: String,
    isStreaming: Boolean,
    showAiBadge: Boolean,
    error: String? = null,
    /** 中性轻提示（不是错误）：如联网降级后「这次没能联网，先按普通回答」。*/
    notice: String? = null,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    /** Optional ViewModel path that reports acceptance only after it commits this send. */
    onSendWithAcceptance: ((onAccepted: () -> Unit) -> Unit)? = null,
    sendSessionKey: String = characterName,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onDismissAiBadge: () -> Unit,
    /** 是否 DeepSeek 端点：只有它为真才显示思考 / 搜索胶囊。*/
    deepSeekProvider: Boolean = false,
    thinkingEnabled: Boolean = false,
    webSearchEnabled: Boolean = false,
    onToggleThinking: (Boolean) -> Unit = {},
    onToggleWebSearch: (Boolean) -> Unit = {},
    /** 用户第一次操作「深度思考」开关（无论开/关）时的一次性行内提示：4s 淡出、可点关。 */
    thinkingHintVisible: Boolean = false,
    onDismissThinkingHint: () -> Unit = {},
    /** 「看看更早的」：还有更早的消息可翻（v0.4）。*/
    hasMoreOlder: Boolean = false,
    /** 正在加载更早一页：入口变「正在找…」并禁用，避免连点。 */
    loadingOlder: Boolean = false,
    onLoadOlder: () -> Unit = {},
    /**
     * 创作模式（v0.4）：开着时顶栏多一个「出戏」，关着时多一个「入戏」。
     *
     * 由 `ChatViewModel.UiState.creativeMode` 传入；进页读一次设置，不每轮轮询。
     */
    creativeMode: Boolean = false,
    /** 是否已看过创作模式首次说明（未看过时点「入戏」先弹说明）。 */
    creativeModeIntroSeen: Boolean = true,
    /** 点「入戏」：开启创作模式（设置落盘 + 轻提示由 ViewModel 负责）。 */
    onEnterCreativeMode: () -> Unit = {},
    /** 点「出戏」：关掉创作模式（设置落盘 + 一条轻提示都由 ViewModel 负责）。 */
    onExitCreativeMode: () -> Unit = {},
    /** 创作模式首次说明已看过（开启 / 先不用 / 点外面关闭都会触发）。 */
    onCreativeModeIntroSeen: () -> Unit = {},
    /**
     * 是否正在朗读（v0.5）。
     *
     * 由 `AppRoot` 从 `AppContainer.reader.state.speaking` 收集后传入；为真时在输入条上方
     * 显示「正在朗读… / 停下」。
     */
    reading: Boolean = false,
    /** 点「停下」：只停这一次，不关自动朗读。 */
    onStopReading: () -> Unit = {},
    /**
     * 朗读不可用时给用户的话（null = 不显示）。
     *
     * 触发条件由 AppRoot 判定：自动朗读开着、但引擎初始化失败/没有中文语音数据。
     * 文案见 `ReadCopy.UNAVAILABLE`（事实 + 出路）。
     */
    readUnavailableMessage: String? = null,
    // ------------------------------------------------ v0.5.0 识图
    /** 图片入口是否显示（非 DeepSeek 端点隐藏）。*/
    imageInputVisible: Boolean = false,
    /** 图片入口是否可用（模型看不了图时禁用并说明）。 */
    imageInputEnabled: Boolean = true,
    /** 草稿里的图（≤3；准备中/失败态由本页显示）。 */
    drafts: List<DraftImage> = emptyList(),
    /** 底部「拍照 / 从相册选」面板是否打开（状态在 ChatViewModel，方便统一收面板）。 */
    showImageSource: Boolean = false,
    /** 首次带图发送说明弹窗（只弹一次，非阻断）。*/
    showImageIntro: Boolean = false,
    onAddImage: () -> Unit = {},
    onDismissImageSource: () -> Unit = {},
    /** 相册/文档选择器回调（已拿到 Uri）。 */
    onImagePicked: (Uri) -> Unit = {},
    /** 相机拍完（直接给私有 cache 文件）。 */
    onCameraCaptured: (File) -> Unit = {},
    /** 相机取消/失败（给临时文件以便清理）。 */
    onCameraCancelled: (File) -> Unit = {},
    onRemoveDraft: (String) -> Unit = {},
    /** 旧图「再用这张图问一次」：把本地文件重新放回草稿。 */
    onResendImage: (String) -> Unit = {},
    /** 首次说明的任一按钮/点外面：标记看过并继续发送。 */
    onImageIntroAcknowledged: () -> Unit = {},
    // ------------------------------------------------ v0.5.1 语音输入
    /** 语音输入 UI 状态（模式 / 录音 / 转写 / 权限 / 首用说明；`ChatViewModel.UiState.voice`）。 */
    voice: VoiceUiState = VoiceUiState(),
    /** 语音动作接线（ViewModel 一侧；AppRoot 构造）。 */
    voiceActions: VoiceActions = VoiceActions(),
    /** 系统权限框的回调结果；消费后由 AppRoot 置回 null。 */
    voicePermissionResult: VoicePermissionResult? = null,
    // ------------------------------------------------ v0.6.6 进房回忆苏醒 + 脉冲星重逢仪式
    awakening: AwakeningUiState = voice.awakeningBridge?.uiState ?: AwakeningUiState(),
    onRetryAwakening: () -> Unit = { voice.awakeningBridge?.onRetry?.invoke() },
    onSkipAwakening: () -> Unit = { voice.awakeningBridge?.onSkip?.invoke() },
    onStartReunion: () -> Unit = { voice.awakeningBridge?.onStartReunion?.invoke() },
    onCompleteReunion: () -> Unit = { voice.awakeningBridge?.onCompleteReunion?.invoke() },
) {
    // rememberLazyListState uses rememberSaveable and would restore an old scroll position after
    // recreation. A fresh state makes every open/rebuild start from the newest message.
    // 依据：https://android.googlesource.com/platform/frameworks/support/+/2d5197729d110347604ba154a3006241717b54c8/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt
    val listState = remember(sendSessionKey) { LazyListState(0, 0) }
    var initialTailPositioned by remember(sendSessionKey) { mutableStateOf(false) }
    // 上一次的滚动快照（判「末条变没变」用；纯数据，不进组合状态之外的地方）。
    // v0.6.7：用户主动发送强制回底并跟随；外部新消息和流式正文只在贴底时跟随。依据：ChatScreen.kt:253。
    var scrollSnapshot by remember(sendSessionKey) { mutableStateOf<ChatScrollSnapshot?>(null) }

    // 粘性贴底不能只依赖「末项底边到视口底」的偏移判据，因为它无法区分
    // 「用户主动上翻」和「用户停在底部、流式正文把末项底边顶出视口底」——两者几何相同。
    // 若只加偏移判据，停在底部时正文一变长就会被判「非贴底」而停止跟随（回复越长越看不到
    // 新字，旧行为是每帧 animateScrollToItem 持续跟随）。这里像真实聊天软件一样记住
    // 「用户最后一次**自己**滚动停手时是否贴底」：
    // - 只有用户自己的拖拽/fling 结束（interactionSource 的 DragInteraction 起手，
    //   且 !activeDrag && !isScrollInProgress）或非程序化滚动停手才刷新粘性；内容增长 / 我们自己
    //   程序化滚动造成的滚动状态变化**不刷新**（否则流式增长会把粘性误翻成 false）；
    // - 用户滚动期间不自动跟随（不抢手势）；
    // - 用户没在滚时，「粘性贴底 或 当前真实偏移贴底」任一成立即视为贴底。
    // 粘性状态只在用户手动滚动结束时更新；程序化滚动和内容增长不会改变它。
    var stickyAtBottom by remember(sendSessionKey) { mutableStateOf(true) } // 初值 true：首屏本来就贴底
    var userScrolling by remember(sendSessionKey) { mutableStateOf(false) }
    var activeDrag by remember(sendSessionKey) { mutableStateOf(false) }
    var programmaticScrolling by remember(sendSessionKey) { mutableStateOf(false) }
    var programmaticScrollToken by remember(sendSessionKey) { mutableStateOf(0L) }
    var automaticOlderRequestInProgress by remember(sendSessionKey) { mutableStateOf(false) }
    val currentOnLoadOlder by rememberUpdatedState(onLoadOlder)
    val currentMessages by rememberUpdatedState(messages)
    val currentHasMoreOlder by rememberUpdatedState(hasMoreOlder)
    val currentIsAwakening by rememberUpdatedState(awakening.isAwakening)
    var pendingOlderAnchor by remember(sendSessionKey) { mutableStateOf<ChatOlderPageAnchor?>(null) }
    fun requestOlderPage() {
        val existingMessages = currentMessages
        if (existingMessages.isNotEmpty()) {
            val existingIds = existingMessages.map(ChatUiMessage::id)
            val existingIdSet = existingIds.toHashSet()
            val firstVisibleMessage = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                (item.key as? String)?.let { it in existingIdSet } == true
            }
            val messageId = firstVisibleMessage?.key as? String
            if (messageId != null) {
                pendingOlderAnchor = ChatOlderPageAnchor(
                    requestedMessageIds = existingIds,
                    messageId = messageId,
                    offsetPx = firstVisibleMessage.offset,
                )
            }
        }
        currentOnLoadOlder()
    }
    // 内容变化会取消上一轮判定 LaunchedEffect；保留已获准的跟随直到成功贴底，用户拖动时清掉。
    var pendingFollow by remember(sendSessionKey) { mutableStateOf(false) }
    var followSignal by remember(sendSessionKey) { mutableStateOf(0L) }
    var followTargetItemCount by remember(sendSessionKey) { mutableStateOf(0) }
    var acceptedSendToken by remember(sendSessionKey) { mutableStateOf(0L) }
    var consumedSendToken by remember(sendSessionKey) { mutableStateOf(0L) }
    var legacySendPending by remember(sendSessionKey) { mutableStateOf(false) }
    var legacySendBaselineId by remember(sendSessionKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(listState, sendSessionKey) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    activeDrag = true
                    userScrolling = true
                    pendingFollow = false
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    activeDrag = false
                }
            }
        }
    }
    // 在旧页请求等待期间持续更新锚点，避免用户继续拖动后仍恢复到请求开始时的旧位置。
    // 数据前插后停止采样；再依据实际拖动位移补偿。依据 LazyListItemInfo.offset 与 layoutInfo：
    // https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListItemInfo
    LaunchedEffect(listState, sendSessionKey) {
        snapshotFlow {
            val anchor = pendingOlderAnchor
            val currentIds = currentMessages.map(ChatUiMessage::id)
            val pageInserted = anchor != null &&
                olderPageInsertionIndex(anchor.requestedMessageIds, currentIds) != null
            val anchorItem = if (anchor != null && !pageInserted) {
                val requestedIds = anchor.requestedMessageIds.toHashSet()
                listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    (item.key as? String)?.let { it in requestedIds } == true
                }
            } else {
                null
            }
            Triple(anchor, pageInserted, anchorItem?.let { (it.key as String) to it.offset })
        }.collect { (anchor, pageInserted, visibleAnchor) ->
            if (anchor != null && !pageInserted && visibleAnchor != null) {
                pendingOlderAnchor = anchor.copy(
                    messageId = visibleAnchor.first,
                    offsetPx = visibleAnchor.second,
                )
            }
        }
    }
    // 成功前插后将原可见消息作为锚点。用户仍在拖动时先等手势结束，并把该消息在拖动中
    // 的实际位移加入目标 offset；若用户主动把它拖出视口，则保留当前位移，不把 TA 拉回去。
    // 依据：LazyListItemInfo.offset 相对列表容器起点；scrollToItem 的正 scrollOffset 会让项目向上移动：
    // https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState
    // https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListLayoutInfo
    LaunchedEffect(sendSessionKey, messages.firstOrNull()?.id, hasMoreOlder, loadingOlder) {
        val anchor = pendingOlderAnchor ?: return@LaunchedEffect
        val currentIds = messages.map(ChatUiMessage::id)
        val insertionIndex = olderPageInsertionIndex(anchor.requestedMessageIds, currentIds)
        if (insertionIndex == null) {
            if (loadingOlder && !anchor.loadingObserved) {
                pendingOlderAnchor = anchor.copy(loadingObserved = true)
            } else if (!loadingOlder && anchor.loadingObserved) {
                // 请求失败或返回空页：清掉临时锚点，等待显式重试重新取锚。
                pendingOlderAnchor = null
            }
            return@LaunchedEffect
        }

        val messageIndex = currentIds.indexOf(anchor.messageId)
        if (messageIndex < insertionIndex) {
            pendingOlderAnchor = null
            return@LaunchedEffect
        }
        val targetItemIndex = messageIndex + if (hasMoreOlder) 1 else 0
        val expectedItemCount = messages.size +
            (if (hasMoreOlder) 1 else 0) +
            (if (isStreaming && messages.lastOrNull()?.role == "user") 1 else 0)
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it >= expectedItemCount }

        val initialLayout = listState.layoutInfo
        val initialAnchorOffset = initialLayout.visibleItemsInfo
            .firstOrNull { it.key == anchor.messageId }
            ?.offset
        val firstVisibleKey = initialLayout.visibleItemsInfo.firstOrNull()?.key
        if (firstVisibleKey == anchor.messageId) {
            // LazyColumn 的 stable key 已经在本次重排中保持了消息锚点；不打断正在进行的用户滚动。
            pendingOlderAnchor = null
            return@LaunchedEffect
        }

        var targetOffsetPx = anchor.offsetPx
        if (userScrolling || listState.isScrollInProgress) {
            val firstIndexAtInsertion = listState.firstVisibleItemIndex
            val firstOffsetAtInsertion = listState.firstVisibleItemScrollOffset
            var anchorStayedVisible = initialAnchorOffset != null
            snapshotFlow {
                ChatAnchorScrollSample(
                    busy = userScrolling || listState.isScrollInProgress,
                    anchorOffset = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == anchor.messageId }
                        ?.offset,
                )
            }.takeWhile { it.busy }.collect { sample ->
                if (sample.anchorOffset == null) {
                    if (initialAnchorOffset != null) anchorStayedVisible = false
                }
            }

            val settledAnchorOffset = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == anchor.messageId }
                ?.offset
            val currentFirstIndex = listState.firstVisibleItemIndex
            val currentFirstOffset = listState.firstVisibleItemScrollOffset
            targetOffsetPx = when {
                initialAnchorOffset != null && anchorStayedVisible && settledAnchorOffset != null ->
                    anchor.offsetPx + (settledAnchorOffset - initialAnchorOffset)
                currentFirstIndex == firstIndexAtInsertion &&
                    currentFirstOffset == firstOffsetAtInsertion -> anchor.offsetPx
                else -> {
                    // 用户已经继续滚到另一个位置；不恢复旧锚点，避免覆盖这段新位移。
                    pendingOlderAnchor = null
                    return@LaunchedEffect
                }
            }
        }

        programmaticScrolling = true
        try {
            listState.scrollToItem(
                index = targetItemIndex,
                scrollOffset = -targetOffsetPx,
            )
        } finally {
            programmaticScrolling = false
            programmaticScrollToken += 1L
        }
        pendingOlderAnchor = null
    }
    LaunchedEffect(listState, sendSessionKey) {
        var sawUserScrollInProgress = false
        var lastObservedPos = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        var lastObservedFirstMsgId = currentMessages.firstOrNull()?.id
        var lastObservedHasMore = currentHasMoreOlder
        var lastObservedProgrammaticToken = programmaticScrollToken
        snapshotFlow {
            ChatScrollSettlementObservation(
                dragging = activeDrag,
                scrolling = listState.isScrollInProgress,
                pos = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
                firstMsgId = currentMessages.firstOrNull()?.id,
                hasMore = currentHasMoreOlder,
                programmaticToken = programmaticScrollToken,
            )
        }.collect { obs ->
            if (obs.scrolling && !programmaticScrolling) {
                sawUserScrollInProgress = true
            }
            val prependedOrHeaderChanged = obs.firstMsgId != lastObservedFirstMsgId ||
                obs.hasMore != lastObservedHasMore
            val programmaticShift = obs.programmaticToken != lastObservedProgrammaticToken ||
                programmaticScrolling
            val nonProgrammaticPosShift = obs.pos != lastObservedPos &&
                !programmaticShift &&
                !prependedOrHeaderChanged &&
                pendingOlderAnchor == null
            lastObservedPos = obs.pos
            lastObservedFirstMsgId = obs.firstMsgId
            lastObservedHasMore = obs.hasMore
            lastObservedProgrammaticToken = obs.programmaticToken

            // 仅在无活跃拖拽且滚动静止时刷新粘性并复位 userScrolling，
            // 消除边界轻触锁死及非拖拽/无障碍滚动漏刷新。依据：ChatScreen.kt:293-300,411-431。
            if (!obs.dragging && !obs.scrolling && (userScrolling || sawUserScrollInProgress || nonProgrammaticPosShift)) {
                sawUserScrollInProgress = false
                if (initialTailPositioned) {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()
                    stickyAtBottom = StickyBottom.update(
                        previous = stickyAtBottom,
                        scrollStopped = true,
                        rawAtBottom = ChatAutoScroll.isAtBottomByGeometry(
                            lastVisibleItemIndex = lastVisible?.index,
                            lastVisibleItemOffset = lastVisible?.offset,
                            lastVisibleItemSize = lastVisible?.size,
                            totalItemCount = info.totalItemsCount,
                            viewportEndOffset = info.viewportEndOffset,
                        ),
                    )
                }
                userScrolling = false
            }
        }
    }
    // 只有用户向列表开头实际拖动，并且接近旧页入口时才自动翻页。程序化定位、重建和内容
    // 前插都不会产生 DragInteraction，因此不会误发请求。失败后仍可点「看看更早的」重试。
    // 依据：LazyListState 的 lastScrolledBackward / firstVisibleItemIndex 官方定义：
    // https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState
    LaunchedEffect(listState, sendSessionKey, hasMoreOlder, loadingOlder) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.lastScrolledBackward,
            )
        }.collect { (firstIndex, firstOffset, scrolledBackward) ->
            val movedTowardOlder = firstIndex < previousIndex ||
                (firstIndex == previousIndex && firstOffset < previousOffset)
            if (firstIndex > AUTO_LOAD_OLDER_RESET_INDEX || !hasMoreOlder) {
                automaticOlderRequestInProgress = false
            }
            if (
                userScrolling &&
                movedTowardOlder &&
                scrolledBackward &&
                firstIndex <= AUTO_LOAD_OLDER_NEAR_TOP_INDEX &&
                hasMoreOlder &&
                !loadingOlder &&
                !automaticOlderRequestInProgress
            ) {
                automaticOlderRequestInProgress = true
                requestOlderPage()
            }
            previousIndex = firstIndex
            previousOffset = firstOffset
        }
    }

    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val reduceMotion = rememberReduceMotion(context)
    val isAwakening = awakening.isAwakening
    val imeVisible = currentImeVisibility()
    val currentImeVisibility by rememberUpdatedState(imeVisible)
    val keyboardHideGate = remember(sendSessionKey) { KeyboardSendAcceptanceGate() }

    LaunchedEffect(isAwakening) {
        if (isAwakening) {
            keyboardHideGate.invalidate()
            keyboardController?.hide()
        }
    }

    // The callback is request-specific and the generation is invalidated by later user intent.
    // This avoids inferring acceptance from unrelated list changes.依据：ChatViewModel.kt:1396-1504.
    LaunchedEffect(imeVisible, keyboardHideGate) {
        if (!imeVisible) keyboardHideGate.invalidate()
    }
    DisposableEffect(sendSessionKey, keyboardHideGate) {
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
            legacySendBaselineId = currentMessages.lastOrNull()?.id
            legacySendPending = true
            onSend()
        }
    }

    // ---- v0.5.1 语音：权限现查 / 系统 launcher 信号 / 生命周期清理 ----
    // 授权状态在**点击那一刻**现查（用户可能刚从系统设置回来），不缓存在 ViewModel 里。
    val recordPermissionGranted: () -> Boolean = {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    // ViewModel 让弹系统权限框时 permissionLaunchToken +1（launcher 不放进 ViewModel）；
    // 弹完立刻消费归零，避免离开再回来时重复弹框。
    LaunchedEffect(voice.permissionLaunchToken) {
        if (voice.permissionLaunchToken > 0) {
            voiceActions.onRequestPermission()
            voiceActions.onPermissionLaunchConsumed()
        }
    }
    // 系统权限框结果 → ViewModel → 清空 AppRoot 的待消费结果。
    LaunchedEffect(voicePermissionResult) {
        val result = voicePermissionResult ?: return@LaunchedEffect
        voiceActions.onPermissionResult(result)
        voiceActions.onPermissionResultConsumed()
    }
    // 退后台/锁屏：取消录音与转写、删临时音频；离开页面（返回/切页）再退出语音模式。
    // 录音或转写期间切页、退后台或锁屏时，取消操作并删除临时音频；
    // ChatViewModel.onCleared 是最后一道兜底（ViewModel 按角色缓存，切页不一定 onCleared）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) voiceActions.onAppBackgrounded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voiceActions.onPageLeft()
        }
    }

    // ---- v0.5.0 识图：选图/拍照的 ActivityResult 接线 ----
    // 相册走 Photo Picker（官方 API，免存储权限；设备没有 picker 时 activity 库自动回退
    // ACTION_OPEN_DOCUMENT）。依据：https://developer.android.com/training/data-storage/shared/photopicker
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        // 取消（null）什么都不做：不弹错误、不指责用户（postel 原则 失败态）。
        if (uri != null) onImagePicked(uri)
    }
    // 拍照走 TakePicture + FileProvider：**不声明 CAMERA** 就不需要相机权限；
    // manifest 里只暴露 cacheDir/camera/（res/xml/file_paths.xml）。
    // 用 rememberSaveable 存路径（不是 File）：拍照期间 Activity 被回收/旋转时，结果回调还能找回输出文件。
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { saved: Boolean ->
        val file = pendingCameraPath?.let { File(it) }
        pendingCameraPath = null
        if (file != null) {
            if (saved) onCameraCaptured(file) else onCameraCancelled(file)
        }
    }

    // 点开的大图（本地路径）。返回手势由 Dialog 自己处理：先关预览，不退聊天页。
    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }

    // 输入条右下角主按钮的状态机（v0.5.1 起含语音/键盘两态）：
    // 空输入 = 语音入口（普通模式）/ 键盘（语音模式）；有字 = 发送↑；生成中 = 停下■。
    // 纯函数 `ComposerMainButtonResolver` 内部复用 `ComposerPrimary.resolve`，本页不再抄一份判定
    // （v0.5.0 ` 两套口径会漂移成「按钮能点、发送却被拒」）。

    val primary = ComposerMainButtonResolver.resolve(
        drafts = drafts,
        hasText = input.isNotBlank(),
        streaming = isStreaming,
        mode = voice.mode,
        // 门禁隐藏时不留麦克风死按钮：空输入退回禁用发送箭头（非 DeepSeek / 没密钥 / 没麦克风）。
        voiceEntryVisible = voice.visible,
        frozen = isAwakening,
    )

    // v0.5.2：第二行独立麦克风入口——有文字（或已就绪草稿图）时也能继续口述。
    // `hasComposableContent` 用 `isNotBlank()`：纯空白输入不算内容，否则空输入会冒出第二个麦克风
    //（主按钮已经是麦克风）。纯函数在 `VoiceModeState.kt`，逐条真值表有单测。
    val hasComposableContent = input.isNotBlank() || DraftRules.hasReady(drafts)
    val showVoiceMic = shouldShowVoiceMic(
        voiceEntryVisible = voice.visible,
        mode = voice.mode,
        streaming = isStreaming,
        hasComposableContent = hasComposableContent,
        frozen = isAwakening,
    )
    // 语音模式 + 有内容时主按钮按「有字=发送」解析（禁止改主按钮解析），第二行补独立「切换至键盘」；
    // 空输入时主按钮本身就是键盘，不重复显示第二个切换按钮。
    val showKeyboardSwitch = voice.mode == ComposerMode.VOICE && hasComposableContent

    // 相机启动失败（无相机 App/ActivityNotFoundException）的轻提示：只在本页显示、5s 自动消失。
    // 用本页状态而不是 ViewModel.notice：它是纯 UI 失败，不涉及业务状态；
    // 用户取消（返回 false）不会走到这里，保持静默。
    var cameraUnavailable by remember { mutableStateOf(false) }
    LaunchedEffect(cameraUnavailable) {
        if (cameraUnavailable) {
            delay(CAMERA_UNAVAILABLE_NOTICE_MS)
            cameraUnavailable = false
        }
    }

    // 监听视口高度缩小（软键盘弹出 / 多行输入 / 草稿图 / 提示条）：
    // 若用户当前贴底且未手动上翻，立即同步贴底补偿；已上翻则绝不抢回底部。
    // 依据：ChatScreen.kt:812（imePadding 收缩 LazyColumn 视口）。
    LaunchedEffect(listState, sendSessionKey) {
        var previousViewportHeight: Int? = null
        snapshotFlow {
            val info = listState.layoutInfo
            info.viewportSize.height to info.totalItemsCount
        }.collect { (viewportHeight, totalItemsCount) ->
            val prev = previousViewportHeight
            previousViewportHeight = viewportHeight
            if (
                prev != null &&
                viewportHeight > 0 &&
                viewportHeight < prev &&
                initialTailPositioned &&
                stickyAtBottom &&
                !userScrolling &&
                !activeDrag &&
                totalItemsCount > 0
            ) {
                withFrameNanos {}
                if (initialTailPositioned && stickyAtBottom && !userScrolling && !activeDrag) {
                    val info = listState.layoutInfo
                    val targetIndex = info.totalItemsCount - 1
                    if (targetIndex >= 0) {
                        val lastVisible = info.visibleItemsInfo.lastOrNull()
                        val tailOffset = tailOffsetForFollow(
                            lastVisibleItemIndex = lastVisible?.index ?: -1,
                            lastVisibleItemOffset = lastVisible?.offset ?: 0,
                            lastVisibleItemSize = lastVisible?.size ?: 0,
                            viewportEndOffset = info.viewportEndOffset,
                            targetItemIndex = targetIndex,
                        )
                        programmaticScrolling = true
                        try {
                            if (lastVisible != null && lastVisible.index == targetIndex) {
                                if (tailOffset > 0) {
                                    listState.scroll { scrollBy(tailOffset.toFloat()) }
                                }
                            } else {
                                scrollToLatestWithoutAnimation(listState, targetIndex)
                            }
                        } finally {
                            programmaticScrolling = false
                            programmaticScrollToken += 1L
                        }
                    }
                }
            }
        }
    }

    // 消费端串行合并滚动执行器：不被每字 delta 取消，先等布局包含目标项并过一帧，
    // 再按最新 size 与 viewportEndOffset 平滑追底；若被用户手势打断则立即退出且不回写 stickyAtBottom。
    // 依据：ChatScreen.kt:683-804 历史每字取消问题；LazyListLayoutInfo.kt。
    LaunchedEffect(listState, sendSessionKey) {
        var processedSignal = 0L
        snapshotFlow { followSignal }.collect { latestSignal ->
            if (latestSignal <= processedSignal) return@collect
            while (processedSignal < followSignal) {
                processedSignal = followSignal
                if (userScrolling || activeDrag) {
                    pendingFollow = false
                    break
                }
                val expectedItemCount = followTargetItemCount
                if (expectedItemCount <= 0) break

                snapshotFlow {
                    listState.layoutInfo.totalItemsCount to followTargetItemCount
                }.first { (total, target) -> target <= 0 || total >= target }
                withFrameNanos {}

                if (userScrolling || activeDrag) {
                    pendingFollow = false
                    break
                }

                var attempts = 0
                while (attempts < 4 && !userScrolling && !activeDrag) {
                    attempts++
                    val latestTargetCount = followTargetItemCount
                    if (latestTargetCount <= 0) break
                    val info = listState.layoutInfo
                    if (info.totalItemsCount < latestTargetCount) {
                        // 有更新的消息刚进入状态，跳出内层让外层等待最新 totalItemsCount。
                        break
                    }
                    val targetItemIndex = (info.totalItemsCount - 1).coerceAtLeast(0)
                    val lastVisible = info.visibleItemsInfo.lastOrNull()
                    val tailOffset = tailOffsetForFollow(
                        lastVisibleItemIndex = lastVisible?.index ?: -1,
                        lastVisibleItemOffset = lastVisible?.offset ?: 0,
                        lastVisibleItemSize = lastVisible?.size ?: 0,
                        viewportEndOffset = info.viewportEndOffset,
                        targetItemIndex = targetItemIndex,
                    )
                    val reachedBottom = lastVisible != null &&
                        lastVisible.index == targetItemIndex &&
                        ChatAutoScroll.isAtBottomByGeometry(
                            lastVisibleItemIndex = lastVisible.index,
                            lastVisibleItemOffset = lastVisible.offset,
                            lastVisibleItemSize = lastVisible.size,
                            totalItemCount = info.totalItemsCount,
                            viewportEndOffset = info.viewportEndOffset,
                        )
                    if (reachedBottom) {
                        stickyAtBottom = true
                        pendingFollow = false
                        break
                    }

                    programmaticScrolling = true
                    try {
                        if (lastVisible != null && lastVisible.index == targetItemIndex) {
                            if (tailOffset > 0) {
                                if (lastVisible.size > info.viewportEndOffset && tailOffset > info.viewportEndOffset) {
                                    // animateScrollToItem 的 scrollOffset 是目标 item 落点，不是剩余距离；
                                    // 按当前 offset 换算后仍将长 item 底边对齐视口底。
                                    // 依据：https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState
                                    val requestedItemOffset = (
                                        tailOffset.toLong() - lastVisible.offset.toLong()
                                    ).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                                    listState.animateScrollToItem(
                                        targetItemIndex,
                                        scrollOffset = requestedItemOffset,
                                    )
                                } else {
                                    // 短距离或高频流式补齐，直接按剩余像素距离平滑滚动。
                                    // 依据：https://developer.android.com/reference/kotlin/androidx/compose/foundation/gestures/package-summary
                                    listState.animateScrollBy(tailOffset.toFloat())
                                }
                            } else {
                                break
                            }
                        } else {
                            // 末项还没进入布局时先把它带进来，再用新布局算剩余尾部距离。
                            listState.animateScrollToItem(targetItemIndex)
                        }
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        // 仅内层 MutatorMutex 被用户手势打断：不回写 stickyAtBottom = true，退出追底。
                        pendingFollow = false
                        break
                    } finally {
                        programmaticScrolling = false
                        programmaticScrollToken += 1L
                    }
                    withFrameNanos {}
                }
            }
        }
    }

    // 判定端 LaunchedEffect（v0.6.7 解耦跟随判定与执行）：仅更新快照、判定是否需要跟随并递增 followSignal；
    // 追踪 reasoning、citations 与 isStreaming，确保思考阶段与辅助行出现时也能跟随。
    // 依据：ChatViewModel.kt:1754,1893-1896；ChatScreen.kt:683-804。
    val lastMessage = messages.lastOrNull()
    LaunchedEffect(
        sendSessionKey,
        lastMessage?.id,
        lastMessage?.text?.length,
        lastMessage?.reasoning?.length,
        lastMessage?.citations?.size,
        messages.size,
        hasMoreOlder,
        isStreaming,
        acceptedSendToken,
    ) {
        val current = ChatScrollSnapshot(
            lastMessageId = lastMessage?.id,
            lastMessageTextLength = lastMessage?.text?.length ?: 0,
            messageCount = messages.size,
            // 总 item 数 ≠ 消息数：还有更早 + 流式时末尾的「正在写」各占一行。
            itemCount = messages.size +
                (if (hasMoreOlder) 1 else 0) +
                (if (isStreaming && lastMessage?.role == "user") 1 else 0),
            lastReasoningLength = lastMessage?.reasoning?.length ?: 0,
            lastCitationsCount = lastMessage?.citations?.size ?: 0,
            isStreaming = isStreaming,
        )
        val hasAcceptedSend = acceptedSendToken > consumedSendToken
        if (hasAcceptedSend) {
            consumedSendToken = acceptedSendToken
        }
        val hasNewUserTailMessage = if (legacySendPending && current.lastMessageId != legacySendBaselineId) {
            val baselineIdx = legacySendBaselineId?.let { id ->
                messages.indexOfLast { it.id == id }
            } ?: -1
            val newTailSlice = if (baselineIdx >= 0) {
                messages.subList(baselineIdx + 1, messages.size)
            } else {
                messages
            }
            legacySendPending = false
            newTailSlice.any { it.role == "user" }
        } else {
            false
        }
        val userSent = hasAcceptedSend || hasNewUserTailMessage
        if (userSent) {
            // 用户主动发送已提交：强制解除离底态并恢复本轮流式跟随。依据：ChatViewModel.kt:1757。
            stickyAtBottom = true
            userScrolling = false
            pendingFollow = true
        }
        if (!initialTailPositioned) {
            // 首次数据到达后直接放到最新一条，再显示列表。先隐藏列表，避免首个布局把旧页顶部
            // 短暂画出来；此路径不使用动画，也不让后续自动跟随 effect 重复滚动。
            scrollSnapshot = current
            if (messages.isNotEmpty()) {
                programmaticScrolling = true
                try {
                    scrollToLatestWithoutAnimation(listState, current.itemCount - 1)
                } finally {
                    programmaticScrolling = false
                    programmaticScrollToken += 1L
                }
                stickyAtBottom = true
                initialTailPositioned = true
            }
            return@LaunchedEffect
        }
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()
        val follow = ChatAutoScroll.shouldFollow(
            previous = scrollSnapshot,
            current = current,
            firstVisibleItemIndex = info.visibleItemsInfo.firstOrNull()?.index ?: -1,
            visibleItemCount = info.visibleItemsInfo.size,
            totalItemCount = info.totalItemsCount,
            followAuxiliaryRow = true,
            lastVisibleItemIndex = lastVisible?.index,
            lastVisibleItemOffset = lastVisible?.offset,
            lastVisibleItemSize = lastVisible?.size,
            viewportEndOffset = info.viewportEndOffset,
            stickyAtBottom = stickyAtBottom,
            userScrolling = userScrolling,
            userSent = userSent,
        )
        scrollSnapshot = current
        val continuePendingFollow = pendingFollow && !userScrolling
        if ((follow || continuePendingFollow) && messages.isNotEmpty()) {
            pendingFollow = true
            followTargetItemCount = current.itemCount
            followSignal += 1L
        }
    }

    var showCreativeIntro by rememberSaveable { mutableStateOf(false) }
    // 其他页面（如设置页）开启创作模式后，不在聊天页再次显示首次说明。
    if (creativeMode) {
        showCreativeIntro = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 键盘内边距放在聊天页：根容器只处理系统栏，避免整页被压缩
                // 依据：https://developer.android.com/develop/ui/compose/system/insets-ui (`imePadding` applies `WindowInsets.ime`).
                .imePadding(),
        ) {
            ChatTopBar(
                title = characterName,
                creativeMode = creativeMode,
                onBack = onBack,
                onOpenMemory = onOpenMemory,
                onEnterCreativeMode = {
                    if (!creativeModeIntroSeen) {
                        showCreativeIntro = true
                    } else {
                        onEnterCreativeMode()
                    }
                },
                onExitCreativeMode = onExitCreativeMode,
            )

            AwakeningTopBanner(
                state = awakening,
                reduceMotion = reduceMotion,
                onRetry = onRetryAwakening,
                onSkip = onSkipAwakening,
                onStartReunion = onStartReunion,
            )

        if (showAiBadge) {
            AiNoticeBar(
                text = "这是 AI 生成的。TA 说的话不是真人说的。",
                onDismiss = onDismissAiBadge,
            )
        }

        val showScrollToLatest by remember(sendSessionKey) {
            derivedStateOf {
                initialTailPositioned &&
                    currentMessages.isNotEmpty() &&
                    !currentIsAwakening &&
                    !stickyAtBottom &&
                    listState.canScrollForward
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (messages.isNotEmpty() && !initialTailPositioned) {
                            Modifier.alpha(0f).clearAndSetSemantics {}
                        } else {
                            Modifier
                        },
                    )
                    .testTag(CHAT_MESSAGE_LIST_TEST_TAG),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 16.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 「看看更早」（v0.4）：放在消息列表最上方。只在还有更早消息时出现；
                // 加载中显示「正在找…」并禁用（48dp 触控目标，整行可点）。
                if (hasMoreOlder) {
                    item("load-older") {
                        TextButton(
                            onClick = { requestOlderPage() },
                            enabled = !loadingOlder,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = if (loadingOlder) "正在找……" else "看看更早的",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    if (shouldShowImportSourceHeader(messages, index)) {
                        ImportSourceHeader(message.sourceTitle, message.sourceStartedAt)
                    }
                    if (message.role == "user") {
                        UserMessageBubble(
                            text = message.text,
                            imagePaths = message.imagePaths,
                            onOpenImage = { previewPath = it },
                        )
                    } else {
                        val newestMessageId = messages.lastOrNull()?.id
                        AssistantMessage(
                            message = message,
                            // 只有最后一条正在生成的消息显示"正在深度思考……"
                            thinkingInProgress = isStreaming &&
                                message.id == newestMessageId,
                            // v0.5.4：正文 liveRegion 只挂给「最新一条、且这一轮已结束」。
                            // 流式期间不挂 → TalkBack 不会每段 delta 重复播报；结束后挂上 →
                            // 整段播报一次。
                            announceBody = ChatAnnounce.shouldAnnounce(
                                streaming = isStreaming,
                                isNewestMessage = message.id == newestMessageId,
                            ),
                        )
                    }
                }
                if (isStreaming && messages.lastOrNull()?.role == "user") {
                    item("typing") { TypingDots() }
                }
            }

            if (showScrollToLatest) {
                ScrollToLatestButton(
                    onClick = {
                        val last = currentMessages.lastOrNull()
                        val currentCount = currentMessages.size +
                            (if (currentHasMoreOlder) 1 else 0) +
                            (if (isStreaming && last?.role == "user") 1 else 0)
                        stickyAtBottom = true
                        userScrolling = false
                        pendingFollow = true
                        followTargetItemCount = currentCount
                        followSignal += 1L
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 12.dp)
                        .testTag(CHAT_SCROLL_TO_LATEST_TEST_TAG),
                )
            }
        }

        // 深度思考 / 智能搜索：官方 App 把两个胶囊放在输入条**第二行左侧**
        // 。这里只渲染一次性引导，
        // 胶囊本身作为 `chips` 槽位传给 `ChatComposer`，与 [＋]/主按钮同一行。
        // 只对 DeepSeek 端点显示；生成中禁用（避免这一轮请求参数中途变化）。
        if (deepSeekProvider) {
            // 用户第一次操作「深度思考」开关（无论开/关）时的一次性引导（贴着开关行上方，不挡输入框）。
            ThinkingHintBanner(
                visible = thinkingHintVisible,
                onDismiss = onDismissThinkingHint,
            )
        }
        val composerChips: (@Composable () -> Unit)? = if (deepSeekProvider) {
            {
                DeepSeekToggleRow(
                    thinkingEnabled = thinkingEnabled,
                    webSearchEnabled = webSearchEnabled,
                    enabled = !isStreaming && !isAwakening,
                    onToggleThinking = onToggleThinking,
                    onToggleWebSearch = onToggleWebSearch,
                )
            }
        } else {
            null
        }

        // 出错 / 到花费上限时的提示（缺陷 给一句可操作的话，不是沉默）。
        // 与群聊页同一套渲染位置：输入条上方一行小字，不打断消息列表。
        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // 中性轻提示（联网降级）：不是错误，不用 error 色；用户开始写下一句时由 ViewModel 清掉。
        notice?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // 相机启动失败（这台手机没有可用的相机）：轻提示 + 出路（相册/打字）。
        // 用户取消拍照（返回 false）不显示；临时文件已在回调里清掉。
        // 依据：` 「失败态要有出路」。
        if (cameraUnavailable) {
            Text(
                text = ImageCopy.NO_CAMERA,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // 朗读不可用（自动朗读开着但手机没中文语音数据/引擎初始化失败）：说清事实 + 出路。
        // 放在输入条上方、与错误/轻提示同一区域，不打断消息列表（验收 4）。
        readUnavailableMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // 播放提示（v0.5 「播放时输入条上方『正在朗读…/停下』」）：
        // 只有真正读着时出现；「停下」只停本次，不关自动朗读。
        if (reading) {
            ReadingHintBar(onStop = onStopReading)
        }

        // 草稿行（v0.5 识图）：只在有附件时出现；准备中/失败态在这里可见。
        ImageDraftRow(drafts = drafts, onRemove = onRemoveDraft)

        // 输入区（官方同款两行输入条）：第一行文本；第二行左胶囊、右下 [＋][主按钮]。
        // 「＋」位于输入条右下角，与主按钮并排；不放在输入条左侧。
        // 非 DeepSeek 端点不显示图片入口（不出现假入口）；模型看不了图时置灰但仍可点，点了给说明。
        ChatComposer(
            value = input,
            onValueChange = { newValue ->
                if (newValue != input) keyboardHideGate.invalidate()
                onInputChange(newValue)
            },
            enabled = !isAwakening,
            placeholder = if (isAwakening) AWAKENING_FROZEN_PLACEHOLDER else VoiceCopy.PLACEHOLDER,
            onUserInteraction = keyboardHideGate::invalidate,
            onSend = requestSend,
            onStop = {
                keyboardHideGate.invalidate()
                onStop()
            },
            primary = primary,
            chips = composerChips,
            showAddImage = imageInputVisible,
            addImageEnabled = imageInputEnabled,
            addImageContentDescription = if (imageInputEnabled) ImageCopy.ADD else ImageCopy.ADD_DISABLED,
            onAddImage = onAddImage,
            // ---- v0.5.1 语音：模式切换 / 按住说话 / 失败出路 / 转写插入 ----
            // 切到语音模式时先收键盘（官方语音模式不弹输入法）；回来时由转写插入重新聚焦。
            onSwitchToVoice = {
                keyboardController?.hide()
                voiceActions.onModeEnter()
            },
            onSwitchToKeyboard = voiceActions.onModeExit,
            showVoiceMic = showVoiceMic,
            showKeyboardSwitch = showKeyboardSwitch,
            voiceMode = voice.mode == ComposerMode.VOICE,
            voicePhase = voice.phase,
            voiceElapsedMs = voice.elapsedMs,
            voiceError = voice.error,
            insertText = voice.insertText,
            insertToken = voice.insertToken,
            onTranscriptInserted = voiceActions.onTranscriptInserted,
            onVoicePressStart = { voiceActions.onPressStart(recordPermissionGranted()) },
            onVoicePressEnd = voiceActions.onPressEnd,
            onVoiceCancel = voiceActions.onCancel,
            // v0.5.2：手势里的「松手取消」状态同步给 ViewModel（权威状态）。
            onSlideCancelArmedChange = voiceActions.onSlideCancelArmedChange,
            onVoiceAccessibilityToggle = {
                voiceActions.onAccessibilityToggle(recordPermissionGranted())
            },
            onVoiceRetry = { voiceActions.onRetry(recordPermissionGranted()) },
            onVoiceTypeInstead = voiceActions.onTypeInstead,
        )
        }

        if (awakening.phase == AwakeningUiPhase.REUNION) {
            PulsarReunionOverlay(
                characterName = awakening.characterName.ifBlank { characterName },
                subtitle = awakening.reunionSubtitle,
                reduceMotion = reduceMotion,
                onComplete = onCompleteReunion,
            )
        }
    }

    // 「拍照 / 从相册选」底部面板（ModalBottomSheet：返回=收弹层，insets 由 处理）。
    if (showImageSource) {
        ImageSourceSheet(
            onCamera = {
                onDismissImageSource()
                // 先建私有目录里的输出文件，再把 content:// Uri 交给系统相机写。
                val file = runCatching { CameraCapture.newOutputFile(context) }.getOrNull()
                if (file != null) {
                    pendingCameraPath = file.absolutePath
                    val uri = runCatching { CameraCapture.uriFor(context, file) }.getOrNull()
                    if (uri == null) {
                        pendingCameraPath = null
                        onCameraCancelled(file)
                    } else {
                        // 系统没有相机 App 时 launch 可能抛 ActivityNotFoundException：
                        // 临时文件照旧清理，并给一条轻提示（取消返回 false 仍保持静默）。
                        // 依据：` 「失败态要有出路」。
                        runCatching { cameraLauncher.launch(uri) }.onFailure {
                            pendingCameraPath = null
                            onCameraCancelled(file)
                            cameraUnavailable = true
                        }
                    }
                }
            },
            onGallery = {
                onDismissImageSource()
                // ImageOnly：只选图片（官方 Photo Picker 支持该过滤）。
                runCatching {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
            },
            onDismiss = onDismissImageSource,
        )
    }

    // 首次带图发送说明（三段逐字 + 隐私补充第 4 行；只弹一次，两个按钮都不阻断发送）。
    // 第 4 行补充图片隐私提醒，提示用户遮挡他人信息并避免发送敏感证件。
    // 渲染直接遍历 `ImageCopy.INTRO_LINES`，行数随该列表（现在是 4 行），不在这里写死。
    if (showImageIntro) {
        AlertDialog(
            onDismissRequest = onImageIntroAcknowledged,
            title = { Text(ImageCopy.DIALOG_TITLE) },
            text = {
                Column {
                    ImageCopy.INTRO_LINES.forEachIndexed { index, line ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Text(text = line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onImageIntroAcknowledged) { Text(ImageCopy.CONFIRM) }
            },
            dismissButton = {
                TextButton(onClick = onImageIntroAcknowledged) { Text(ImageCopy.DISMISS) }
            },
        )
    }

    // ---- v0.5.1 语音：三个一次性/权限弹窗 ----
    // 只在语音模式下渲染：即使有迟到的权限回调落到别的会话页，也不会在文字模式弹出语音弹窗。
    // ① 首次费用/隐私说明（第一次真正录音前弹一次；任一按钮都算看过，不阻断打字）。
    if (voice.mode == ComposerMode.VOICE && voice.showIntro) {
        AlertDialog(
            onDismissRequest = voiceActions.onIntroDismiss,
            title = { Text(VoiceCopy.INTRO_TITLE) },
            text = {
                Column {
                    VoiceCopy.INTRO_LINES.forEachIndexed { index, line ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Text(text = line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { voiceActions.onIntroConfirm(recordPermissionGranted()) }) {
                    Text(VoiceCopy.INTRO_CONFIRM)
                }
            },
            dismissButton = {
                TextButton(onClick = voiceActions.onIntroDismiss) { Text(VoiceCopy.INTRO_DISMISS) }
            },
        )
    }
    // ② 权限前置说明：系统权限框之前先在 App 内说清用途 / 音频去哪 / 拒绝也能打字。
    if (voice.mode == ComposerMode.VOICE && voice.showPermissionIntro) {
        AlertDialog(
            onDismissRequest = voiceActions.onPermissionDismiss,
            title = { Text(VoiceCopy.PERMISSION_INTRO_TITLE) },
            text = {
                Column {
                    VoiceCopy.PERMISSION_INTRO_LINES.forEachIndexed { index, line ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Text(text = line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = voiceActions.onPermissionContinue) {
                    Text(VoiceCopy.PERMISSION_CONTINUE)
                }
            },
            dismissButton = {
                TextButton(onClick = voiceActions.onPermissionDismiss) {
                    Text(VoiceCopy.PERMISSION_DECLINE)
                }
            },
        )
    }
    // ③ 权限被拒：只给两条出路（去系统设置 / 先打字），不自动重复弹系统框。
    if (voice.mode == ComposerMode.VOICE && voice.showPermissionDenied) {
        AlertDialog(
            onDismissRequest = voiceActions.onPermissionDeniedType,
            title = { Text(VoiceCopy.PERMISSION_DENIED_TITLE) },
            text = { Text(VoiceCopy.PERMISSION_DENIED) },
            confirmButton = {
                TextButton(
                    onClick = {
                        voiceActions.onPermissionDeniedSettings()
                        voiceActions.onOpenSystemSettings()
                    },
                ) { Text(VoiceCopy.OPEN_SETTINGS) }
            },
            dismissButton = {
                TextButton(onClick = voiceActions.onPermissionDeniedType) {
                    Text(VoiceCopy.TYPE_INSTEAD)
                }
            },
        )
    }

    if (showCreativeIntro) {
        CreativeModeIntroDialog(
            onConfirm = {
                showCreativeIntro = false
                // 顺序：先开模式、再记"说明已看过"，与 SettingsScreen 保持一致。
                onEnterCreativeMode()
                onCreativeModeIntroSeen()
            },
            onDismiss = {
                showCreativeIntro = false
                onCreativeModeIntroSeen()
            },
        )
    }

    // 大图预览：返回先关预览；关闭按钮 48dp；可「再用这张图问一次」。
    previewPath?.let { path ->
        ImagePreviewDialog(
            localPath = path,
            onClose = { previewPath = null },
            onResend = {
                previewPath = null
                onResendImage(path)
            },
        )
    }
}

/** Rejects late acceptance callbacks after new input, IME dismissal, stop, or screen disposal. */
internal class KeyboardSendAcceptanceGate {
    private var generation: Long = 0L

    fun invalidate() {
        generation += 1L
    }

    fun callbackForCurrentIntent(
        isImeVisible: () -> Boolean,
        hideKeyboard: () -> Unit,
    ): () -> Unit {
        val requestGeneration = generation
        var consumed = false
        return {
            if (!consumed) {
                consumed = true
                if (generation == requestGeneration && isImeVisible()) {
                    generation += 1L
                    hideKeyboard()
                }
            }
        }
    }
}

// Test seam for the IME visibility edge. Production reads the Android WindowInsets state below.
internal val LocalImeVisibilityOverride = compositionLocalOf<Boolean?> { null }

// 依据：https://developer.android.com/reference/kotlin/androidx/compose/foundation/layout/package-summary
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun currentImeVisibility(): Boolean =
    LocalImeVisibilityOverride.current ?: WindowInsets.isImeVisible

internal const val CHAT_MESSAGE_LIST_TEST_TAG = "chat-message-list"
internal const val CHAT_SCROLL_TO_LATEST_TEST_TAG = "chat-scroll-to-latest"

/**
 * 「回到最新」悬浮按钮：黑白极简风格（白底/深色面 + 1dp 细描边 + 墨色向下箭头），
 * 触控目标严格 >= 48.dp。
 * 依据（黑白极简与 48dp 触控规范）。
 */
@Composable
internal fun ScrollToLatestButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onBackground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 2.dp,
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = "回到最新" },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private data class ChatScrollSettlementObservation(
    val dragging: Boolean,
    val scrolling: Boolean,
    val pos: Pair<Int, Int>,
    val firstMsgId: String?,
    val hasMore: Boolean,
    val programmaticToken: Long,
)

private data class ChatOlderPageAnchor(
    val requestedMessageIds: List<String>,
    val messageId: String,
    val offsetPx: Int,
    val loadingObserved: Boolean = false,
)

private data class ChatAnchorScrollSample(
    val busy: Boolean,
    val anchorOffset: Int?,
)

private fun olderPageInsertionIndex(
    previousMessageIds: List<String>,
    currentMessageIds: List<String>,
): Int? {
    if (previousMessageIds.isEmpty()) return null
    val insertionIndex = currentMessageIds.indexOf(previousMessageIds.first())
    if (insertionIndex <= 0 || insertionIndex + previousMessageIds.size > currentMessageIds.size) {
        return null
    }
    return insertionIndex.takeIf { index ->
        previousMessageIds.indices.all { offset ->
            currentMessageIds[index + offset] == previousMessageIds[offset]
        }
    }
}

private const val AUTO_LOAD_OLDER_NEAR_TOP_INDEX = 4
private const val AUTO_LOAD_OLDER_RESET_INDEX = AUTO_LOAD_OLDER_NEAR_TOP_INDEX + 2

/**
 * Place the latest list item without animation, then align its bottom when it is taller than the
 * viewport. Stable LazyColumn keys keep the visible message and offset when older rows are prepended.
 * 依据：https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState
 * https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListScope.html
 */
private suspend fun scrollToLatestWithoutAnimation(listState: LazyListState, targetItemIndex: Int) {
    if (targetItemIndex < 0) return
    snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > targetItemIndex }
    listState.scrollToItem(targetItemIndex)
    val info = listState.layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull()
    val tailOffset = tailOffsetForFollow(
        lastVisibleItemIndex = lastVisible?.index ?: -1,
        lastVisibleItemOffset = lastVisible?.offset ?: 0,
        lastVisibleItemSize = lastVisible?.size ?: 0,
        viewportEndOffset = info.viewportEndOffset,
        targetItemIndex = targetItemIndex,
    )
    if (tailOffset > 0 && lastVisible != null) {
        // scrollOffset is the item's final offset from the viewport start; positive values move it
        // farther upward. This leaves the end of an overlong final row visible on first display.
        // 依据：LazyListState.scrollToItem API reference (same URL above).
        val requestedOffset = (tailOffset.toLong() - lastVisible.offset.toLong())
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
        listState.scrollToItem(targetItemIndex, scrollOffset = requestedOffset)
    }
}

/** True at the start of every contiguous imported source block, including the first loaded page row. */
internal fun shouldShowImportSourceHeader(messages: List<ChatUiMessage>, index: Int): Boolean {
    if (index !in messages.indices) return false
    val sourceId = messages[index].sourceSessionId ?: return false
    return index == 0 || messages[index - 1].sourceSessionId != sourceId
}

@Composable
private fun ImportSourceHeader(title: String?, startedAt: Long?) {
    val date = startedAt?.let {
        DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
            .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: "开始时间未知"
    Text(
        text = "来自导入：${title?.takeIf(String::isNotBlank) ?: "未命名对话"} · $date",
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 顶栏：返回 48dp + 标题（占满剩余宽度、单行省略截断）+「入戏 / 出戏」+ 记忆入口 48dp。
 *
 * 日常态显示「入戏」、创作态显示「出戏」（v0.4 v0.6.9 WP-D）：文字按钮、48dp 触控，
 * 与「记忆」同一个右侧动作区、不挤占标题（标题 `weight(1f)` + `maxLines = 1` + `Ellipsis`）。
 * 返回手势不受影响：它只是页面内的可见入口，返回仍走 `PredictiveBackHandler`。
 */
@Composable
private fun ChatTopBar(
    title: String,
    creativeMode: Boolean,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onEnterCreativeMode: () -> Unit,
    onExitCreativeMode: () -> Unit,
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
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (creativeMode) {
            TextButton(
                onClick = onExitCreativeMode,
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(
                    text = "出戏",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        } else {
            TextButton(
                onClick = onEnterCreativeMode,
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(
                    text = "入戏",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onOpenMemory, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Info, contentDescription = "TA 记得的事")
        }
    }
}

/**
 * 一次性行内提示「深度思考可以调力度」（–§4.5；触发条件按产品决定 2026-11-20 调整）。
 *
 * 触发：**用户第一次操作「深度思考」开关**（无论开还是关；旧规则「关切→开」在默认开的配置下
 * 几乎看不到，已废弃）；安装后只出现一次，用户改过思考强度后不再出现。
 * 时序：开关变化后 **300ms** 再进场（先让开关自己的状态变化被看见）→
 * 高度 0→auto + 透明度 0→1 **200ms**；停留 **4000ms**；退场 **200ms**。
 * 读屏 / 触摸探索时停留时长改用 `AccessibilityManager.calculateRecommendedTimeoutMillis`
 * （通常 ≥10000ms），与 androidx Snackbar 同一套策略。
 *
 * 形态（task 规格 +）：贴开关行上方，**无底色、无阴影、1dp 描边**，不加独立背景；
 * 右侧「知道了」是 48dp 可聚焦控件，点整行也可关；不跳页、不改开关。
 * 读屏：整行 `liveRegion=Polite` 播报一次、不抢焦点；动效跟随系统「减少动态效果」。
 */
@Composable
private fun ThinkingHintBanner(visible: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion(context)
    // 视觉 4s；读屏/触摸探索时走系统建议时长（>=10s 是常见值，但不自己拍数字）。
    // 与 material3 SnackbarHost 用的是同一个 Compose AccessibilityManager。
    val accessibilityManager = LocalAccessibilityManager.current
    val stayMillis = remember(accessibilityManager) {
        accessibilityManager?.calculateRecommendedTimeoutMillis(HINT_STAY_MILLIS, false, true, true)
            ?: HINT_STAY_MILLIS
    }
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (!visible) {
            shown = false
            return@LaunchedEffect
        }
        delay(HINT_ENTER_DELAY_MILLIS)
        shown = true
        delay(stayMillis)
        shown = false
        // 到时自动消失也只记一次：flag 在展示时已写，之后不会再出现。
        onDismiss()
    }

    AnimatedVisibility(
        visible = shown,
        enter = if (reduceMotion) {
            EnterTransition.None
        } else {
            fadeIn(tween(HINT_ANIM_MILLIS, easing = HintEasing)) +
                expandVertically(tween(HINT_ANIM_MILLIS, easing = HintEasing))
        },
        exit = if (reduceMotion) {
            ExitTransition.None
        } else {
            fadeOut(tween(HINT_ANIM_MILLIS, easing = HintEasing)) +
                shrinkVertically(tween(HINT_ANIM_MILLIS, easing = HintEasing))
        },
    ) {
        val hintShape = RoundedCornerShape(12.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp)
                .heightIn(min = 48.dp)
                .clip(hintShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, hintShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "知道了，关闭提示",
                    onClick = onDismiss,
                )
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "深度思考可以调力度。一般用低就好，去「设置 → 聊天 → 思考强度」能改。",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "知道了，关闭提示" },
            ) {
                Text(
                    text = "知道了",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/** 提示进场动画的缓动曲线；与 Short4=200ms 时长相近。 */
private val HintEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** 开关变化到提示进场之间的延迟：先让开关变蓝被看见。*/
private const val HINT_ENTER_DELAY_MILLIS: Long = 300L

/** 进场 / 退场时长。*/
private const val HINT_ANIM_MILLIS: Int = 200

/** 视觉停留时长；读屏/触摸探索时会被系统建议时长替换。*/
private const val HINT_STAY_MILLIS: Long = 4_000L

/**
 * 「没有可用的相机」轻提示的停留时长。
 * 与一次性引导同一量级：够读完、不长期占位；用户下一次输入/操作不会被打断。
 */
private const val CAMERA_UNAVAILABLE_NOTICE_MS: Long = 5_000L

/** 系统「减少动态效果」（动画时长缩放为 0）时直接出现/消失。*/
@Composable
private fun rememberReduceMotion(context: Context): Boolean = remember(context) {
    android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}

/**
 * 两个开关胶囊：
 * 开 = signal 色浅底 + 描边，关 = 白底 + 灰描边 + 深色字。
 * 视觉高度约 34dp（对齐 DeepSeek 官网 `h-[34px]`），外层 48dp 保证触控目标。
 *
 * 现在作为 `ChatComposer` 第二行左侧槽位。窄屏或 200% 字号放不下时在胶囊内部横向滚动，
 * 不把右侧按钮挤出屏幕；滚动不改触控目标（每个胶囊仍是 48dp 高）。
 */
@Composable
private fun DeepSeekToggleRow(
    thinkingEnabled: Boolean,
    webSearchEnabled: Boolean,
    enabled: Boolean,
    onToggleThinking: (Boolean) -> Unit,
    onToggleWebSearch: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeepSeekTogglePill(
            label = "深度思考",
            selected = thinkingEnabled,
            enabled = enabled,
            onToggle = onToggleThinking,
        )
        DeepSeekTogglePill(
            label = "智能搜索",
            selected = webSearchEnabled,
            enabled = enabled,
            onToggle = onToggleWebSearch,
        )
    }
}

@Composable
private fun DeepSeekTogglePill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val signal = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .toggleable(
                value = selected,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = shape,
            color = if (selected) signal.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = if (selected) signal.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) signal else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * TA 的一条回复（正文 + 可选思考层 / 引用层）。
 *
 * 读屏播报（v0.5.4）：
 * - 流式期间正文**不挂** `liveRegion`：每次 delta 都会改文本，挂了 TalkBack 会按 delta 重复播报；
 * - 这条消息结束（不在流式）后由调用方把 [announceBody] 置真，正文挂 `liveRegion=Polite`，
 *   目标是「结束后整段播报一次」。TalkBack 在**已有节点上新挂** liveRegion 时的实际播报时机 /
 *   次数属平台行为，尚未在真机验证（详见 [ChatAnnounce]）。
 *
 * @param thinkingInProgress 只有最后一条正在生成的消息为真（显示「正在深度思考……」）。
 * @param announceBody 是否给正文挂 liveRegion（判定见 [ChatAnnounce.shouldAnnounce]）。
 */
@Composable
private fun AssistantMessage(
    message: ChatUiMessage,
    thinkingInProgress: Boolean,
    announceBody: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 320.dp),
    ) {
        // 思考层：折叠一行，展开是灰字思考正文。
        val reasoning = message.reasoning.orEmpty()
        if (reasoning.isNotEmpty()) {
            ReasoningBlock(
                id = message.id,
                reasoning = reasoning,
                inProgress = thinkingInProgress,
            )
            Spacer(Modifier.height(6.dp))
        }

        val renderedText = if (message.failed) {
            AnnotatedString(message.text)
        } else {
            remember(message.text) { ChatMarkdown.render(message.text) }
        }
        Text(
            text = renderedText,
            style = MaterialTheme.typography.bodyLarge,
            color = if (message.failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            modifier = if (announceBody) {
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            } else {
                Modifier
            },
        )

        // 引用层：气泡下方一行「参考了 N 个来源」，点击展开标题 + 地址。
        if (message.citations.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            CitationsBlock(id = message.id, citations = message.citations)
        }
    }
}

@Composable
private fun ReasoningBlock(id: String, reasoning: String, inProgress: Boolean) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (inProgress) {
                    "正在深度思考……"
                } else {
                    // 用户能理解的单位是"字"，不出现 token。
                    "已深度思考（${reasoning.length} 字）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                tint = muted,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun CitationsBlock(id: String, citations: List<Citation>) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "参考了 ${citations.size} 个来源",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = if (expanded) "收起来源" else "展开来源",
                tint = muted,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                citations.forEach { citation ->
                    Column {
                        citation.title?.let { title ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = citation.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                        )
                    }
                }
            }
        }
    }
}

// =====================================================================================
// v0.5.2 自动跟随滚动的纯判定

// 为什么放这里：本包写作用域只允许改 ChatScreen.kt（不新增主源码文件）；这两个声明是
// 顶层纯 Kotlin，不引用 Compose，编译产物独立于 ChatScreenKt，可直接在 JVM 单测里调用。

// v0.5.3：追加 item 时 slack 只在 layout 已反映新 item 时给——见 shouldFollow KDoc。
// v0.5.4：辅助行变化只在**新增**方向跟随；减少方向（「正在写」行消失）不滚——见 shouldFollow。
// =====================================================================================

/** 一次可比较的滚动输入快照。 */
internal data class ChatScrollSnapshot(
    val lastMessageId: String?,
    val lastMessageTextLength: Int,
    val messageCount: Int,
    val itemCount: Int,
    val lastReasoningLength: Int = 0,
    val lastCitationsCount: Int = 0,
    val isStreaming: Boolean = false,
)

internal object ChatAutoScroll {

    /** 追加一条消息的同一帧里，新 item 可能尚未进入 layoutInfo；允许少算一条。 */
    const val APPEND_LAYOUT_SLACK: Int = 1

    /**
     * 贴底**偏移判据**的容差（像素）。
     *
     * 取 2px 的依据：
     * - `LazyListItemInfo.offset/size` 和 `LazyListLayoutInfo.viewportEndOffset` 是同一次布局
     *   算出来的整数像素，真贴底时两者只差**底部内容留白**（本页 `contentPadding.bottom = 12dp`，
     *   见 ChatScreen.kt:396-400），不会漂移；容差只需吸收贴边时的取整误差（±1px）。
     * - 2px 远小于一行正文高度（`bodyLarge` 行高 27sp，1x 密度就是 27px，见
     *   app/src/main/java/cc/pulsez/app/ui/theme/Type.kt:32-37），不可能把「用户已经上翻一行」
     *   误判成贴底，也不会吞掉 的反例（末项内部上翻数百 px）。
     *
     * 依据：androidx-main `.../lazy/LazyListLayoutInfo.kt`（`viewportEndOffset` 与
     * `beforeContentPadding` 的关系）、`LazyListItemInfo.kt`（`offset` 定义）；
     * 。
     */
    const val BOTTOM_TOLERANCE_PX: Int = 2

    /**
     * 追加 item 的同一帧里，贴底判定允许少算几行（0 或 [APPEND_LAYOUT_SLACK]）。
     *
     * `current.itemCount` = 此刻列表按状态应有的行数；`totalItemCount` = 本帧从 layoutInfo 读到的行数。
     * 只有布局**已经**反映新增 item（`totalItemCount >= current.itemCount`）才需要容差；布局还停在
     * 追加前时给容差会把「距末项一行」的用户误判成贴底（v0.5.3 修复）。

     */
    private fun appendTrailingSlack(
        current: ChatScrollSnapshot,
        totalItemCount: Int,
    ): Int = if (totalItemCount >= current.itemCount) APPEND_LAYOUT_SLACK else 0

    /**
     * 列表是否（基本）贴底。
     *
     * 索引判据：`firstVisible + visible + trailingSlack >= total`（旧语义，保持不变）。
     * 传入偏移参数（`lastVisibleItem*` + `viewportEndOffset`）时再收紧一层**偏移判据**：
     * 视口里最后一项必须就是列表末项，且它的底边落在 `viewportEndOffset + [BOTTOM_TOLERANCE_PX]`
     * 以内（见 [isAtBottomWithOffsets]）。任一偏移参数为 null → 完全回退旧索引判据
     * （既有 24 例与旧调用点编译/语义不变 明确要求）。
     *
     * @param trailingSlack 允许视口下方还有几条没布局（追加消息的同一帧用 1）
     * @param lastVisibleItemIndex 视口里最后一项的 index（`visibleItemsInfo.last().index`）
     * @param lastVisibleItemOffset 视口里最后一项的主轴偏移（`visibleItemsInfo.last().offset`）
     * @param lastVisibleItemSize 视口里最后一项的主轴尺寸（`visibleItemsInfo.last().size`）
     * @param viewportEndOffset 可视区底边（`LazyListLayoutInfo.viewportEndOffset`）
     */
    fun isAtBottom(
        firstVisibleItemIndex: Int,
        visibleItemCount: Int,
        totalItemCount: Int,
        trailingSlack: Int = 0,
        lastVisibleItemIndex: Int? = null,
        lastVisibleItemOffset: Int? = null,
        lastVisibleItemSize: Int? = null,
        viewportEndOffset: Int? = null,
    ): Boolean {
        if (totalItemCount <= 0) return false
        if (firstVisibleItemIndex < 0) return true // 空列表/尚未布局：按贴底处理（首次载入贴底）
        if (firstVisibleItemIndex + visibleItemCount + trailingSlack < totalItemCount) return false
        if (lastVisibleItemIndex == null ||
            lastVisibleItemOffset == null ||
            lastVisibleItemSize == null ||
            viewportEndOffset == null
        ) {
            return true // 没传偏移信息：保持旧索引判据语义
        }
        // 视口里最后一项不是末项 → 列表下方还有 item，偏移判据不适用，交回索引判据：
        // 追加消息的同一帧布局已含新 item、视口还停在旧末项，靠 trailingSlack 判贴底
        //（c，v0.5.3）；这里若按偏移判据判「末项不在视口」会把它误判成离底。
        if (lastVisibleItemIndex < totalItemCount - 1) return true
        return isAtBottomWithOffsets(
            lastItemIndex = lastVisibleItemIndex,
            lastItemOffset = lastVisibleItemOffset,
            lastItemSize = lastVisibleItemSize,
            totalItemCount = totalItemCount,
            viewportEndOffset = viewportEndOffset,
        )
    }

    /**
     * 贴底的**偏移判据**（v0.5.6）：列表还能不能继续向下滚。
     *
     * 索引判据只说「最后一项在视口里」。末项独自占满整屏时，用户在该条内部上翻，末项仍是
     * 唯一可见项（`9 + 1 >= 10` 恒真），光靠索引会把 TA 误判成贴底、被流式增量拽回。
     * 这里用布局几何再判一次：
     *
     * `lastItemOffset + lastItemSize <= viewportEndOffset + tolerancePx`
     *
     * 即「最后一项的底边已经到视口底（含容差）」：
     * - `LazyListItemInfo.offset` 是 item 相对列表容器主轴方向的偏移，`size` 是主轴尺寸；
     * - `LazyListLayoutInfo.viewportEndOffset` 是可滚动到的最远主轴偏移；
     * - 真贴底时 `offset + size` 正好比 `viewportEndOffset` 小一个**底部内容留白**
     *   （本页 `contentPadding.bottom = 12dp`），所以在容差内成立；用户上翻后末项底边
     *   继续往视口外走，超过容差即判「还能向下滚」→ 不跟随。
     *
     * 依据：androidx-main `compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/
     * foundation/lazy/LazyListLayoutInfo.kt`（`viewportEndOffset` 与 `beforeContentPadding` 的
     * 关系、`offset` 定义）（ 反例
     * `total=10 / first=9 / visible=1`）（修复方向）。
     *
     * @return true=贴底；false=末项底边还没到视口底，或视口里最后一项不是末项（下方还有
     *   item）；`lastItemIndex < 0`（尚未布局）与 [isAtBottom] 同口径按贴底处理。
     */
    fun isAtBottomWithOffsets(
        lastItemIndex: Int,
        lastItemOffset: Int,
        lastItemSize: Int,
        totalItemCount: Int,
        viewportEndOffset: Int,
        tolerancePx: Int = BOTTOM_TOLERANCE_PX,
    ): Boolean {
        if (totalItemCount <= 0) return false
        if (lastItemIndex < 0) return true // 尚未布局：与 isAtBottom 同口径（首次载入贴底）
        if (lastItemIndex < totalItemCount - 1) return false // 末项不在视口里：还能向下滚
        return lastItemOffset + lastItemSize <= viewportEndOffset + tolerancePx
    }

    /**
     * 用布局几何算「用户此刻是否真贴底」（v0.5.6 残余②：粘性贴底刷新用）。
     *
     * 与 [isAtBottom] 的区别：这里**只走偏移判据**、不要求索引判据——用户在滚动停手的瞬间
     * 只需要知道「末项底边是否到视口底」，索引判据由 [shouldFollow] 继续当必要条件用。
     *
     * 参数缺失 / 尚未布局（`totalItemCount <= 0` 或没有可见项）→ true：未知时不要清掉
     * 粘性（与 [isAtBottom] 首帧按贴底处理同口径），避免旋转/首帧误判成「用户上翻」。
     */
    fun isAtBottomByGeometry(
        lastVisibleItemIndex: Int?,
        lastVisibleItemOffset: Int?,
        lastVisibleItemSize: Int?,
        totalItemCount: Int,
        viewportEndOffset: Int?,
    ): Boolean {
        if (totalItemCount <= 0) return true // 尚未布局：未知按贴底，不清粘性
        if (lastVisibleItemIndex == null ||
            lastVisibleItemOffset == null ||
            lastVisibleItemSize == null ||
            viewportEndOffset == null
        ) {
            return true // 没有可见项：同上
        }
        return isAtBottomWithOffsets(
            lastItemIndex = lastVisibleItemIndex,
            lastItemOffset = lastVisibleItemOffset,
            lastItemSize = lastVisibleItemSize,
            totalItemCount = totalItemCount,
            viewportEndOffset = viewportEndOffset,
        )
    }

    /**
     * 内容变化时的「要不要跟随」贴底判定（v0.5.6 残余②）。
     *
     * 1. 索引判据（含 trailingSlack）仍是**必要条件**：末项不在视口里一律不跟；
     * 2. 没传 [stickyAtBottom]/[userScrolling]（旧调用点、既有测试）→ 完全回退旧行为；
     * 3. 用户正在滚动 → false（不抢手势）；
     * 4. 否则「粘性贴底 或 当前真实偏移贴底」任一成立即视为贴底。
     */
    private fun atBottomForFollow(
        firstVisibleItemIndex: Int,
        visibleItemCount: Int,
        totalItemCount: Int,
        trailingSlack: Int,
        lastVisibleItemIndex: Int?,
        lastVisibleItemOffset: Int?,
        lastVisibleItemSize: Int?,
        viewportEndOffset: Int?,
        stickyAtBottom: Boolean?,
        userScrolling: Boolean?,
    ): Boolean {
        // 已接入粘性状态机的调用点：不再用易被软键盘缩视口/双条同步插入击穿的 isAtBottom(...)
        // 作为前置硬门禁，而是直接按「用户是否在拖拽 + (粘性贴底 || 当前几何贴底)」判定。
        // 依据：ChatScreen.kt:812（imePadding 缩视口）；ChatViewModel.kt:1754（双条同步插入）。
        if (stickyAtBottom != null && userScrolling != null) {
            if (userScrolling) return false
            val rawAtBottom = if (
                lastVisibleItemIndex == null ||
                lastVisibleItemOffset == null ||
                lastVisibleItemSize == null ||
                viewportEndOffset == null
            ) {
                isAtBottom(firstVisibleItemIndex, visibleItemCount, totalItemCount, trailingSlack)
            } else {
                isAtBottomByGeometry(
                    lastVisibleItemIndex = lastVisibleItemIndex,
                    lastVisibleItemOffset = lastVisibleItemOffset,
                    lastVisibleItemSize = lastVisibleItemSize,
                    totalItemCount = totalItemCount,
                    viewportEndOffset = viewportEndOffset,
                )
            }
            return StickyBottom.atBottom(stickyAtBottom, rawAtBottom, userScrolling)
        }
        // 旧无状态调用点/既有测试：完整保留旧的「索引 + APPEND_LAYOUT_SLACK + 偏移」判据。
        return isAtBottom(
            firstVisibleItemIndex,
            visibleItemCount,
            totalItemCount,
            trailingSlack,
            lastVisibleItemIndex = lastVisibleItemIndex,
            lastVisibleItemOffset = lastVisibleItemOffset,
            lastVisibleItemSize = lastVisibleItemSize,
            viewportEndOffset = viewportEndOffset,
        )
    }

    /**
     * 这一次变化要不要自动滚到底。
     *
     * 规则（顺序即优先级）：
     * 1. 无末条 → false；
     * 2. 用户主动发送（[userSent] = true）→ true（无视离底态，立即回底并恢复跟随）；
     * 3. 首次载入（或空 → 有，上一快照没有末条）→ true（进聊天页落在最新一条，保持现状；
     *    这一步必须放在 totalItemCount 判断**之前**：首帧 LazyColumn 可能还没布局，
     *    layoutInfo.totalItemsCount 仍是 0，不能因此不贴底）；
     * 4. 末条换了（外部追加新消息）：仅贴底时 true；用户上翻时不因新回复跳回末尾；
     * 5. 末条没换、正文/思考/来源增长（流式）→ 仅贴底时 true（用户上翻不抢位置）；
     * 6. 末条没换、消息数没变、item 数**增加**（如「正在写」行出现）或刚进入流式态 →
     *    [followAuxiliaryRow] 为真且贴底时 true；
     *    同条件但 item 数**减少**（「正在写」行消失）→ false（v0.5.4）；
     * 7. 其余（含前插更早消息：messageCount 变、末条没变）→ false（前插不跳底）。
     */
    fun shouldFollow(
        previous: ChatScrollSnapshot?,
        current: ChatScrollSnapshot,
        firstVisibleItemIndex: Int,
        visibleItemCount: Int,
        totalItemCount: Int,
        followAuxiliaryRow: Boolean,
        lastVisibleItemIndex: Int? = null,
        lastVisibleItemOffset: Int? = null,
        lastVisibleItemSize: Int? = null,
        viewportEndOffset: Int? = null,
        /** 用户最后一次**自己**滚动停手时是否贴底（粘性）；null = 回退旧行为。 */
        stickyAtBottom: Boolean? = null,
        /** 用户此刻是否正在拖拽/fling（不含我们自己的程序化滚动）；null = 回退旧行为。 */
        userScrolling: Boolean? = null,
        /** 本次变化是否由用户主动发送（且已获提交）触发。 */
        userSent: Boolean = false,
    ): Boolean {
        if (current.lastMessageId == null) return false
        if (userSent) return true
        // 首次载入/空→有：无条件贴底一次。必须在 layout 判断之前（进页首帧尚未布局）。
        if (previous == null || previous.lastMessageId == null) return true
        if (totalItemCount <= 0) return false

        if (current.lastMessageId != previous.lastMessageId) {
            return atBottomForFollow(
                firstVisibleItemIndex,
                visibleItemCount,
                totalItemCount,
                appendTrailingSlack(current, totalItemCount),
                lastVisibleItemIndex = lastVisibleItemIndex,
                lastVisibleItemOffset = lastVisibleItemOffset,
                lastVisibleItemSize = lastVisibleItemSize,
                viewportEndOffset = viewportEndOffset,
                stickyAtBottom = stickyAtBottom,
                userScrolling = userScrolling,
            )
        }
        val lastContentGrew = current.lastMessageTextLength > previous.lastMessageTextLength ||
            current.lastReasoningLength > previous.lastReasoningLength ||
            current.lastCitationsCount > previous.lastCitationsCount
        if (lastContentGrew) {
            return atBottomForFollow(
                firstVisibleItemIndex,
                visibleItemCount,
                totalItemCount,
                0,
                lastVisibleItemIndex = lastVisibleItemIndex,
                lastVisibleItemOffset = lastVisibleItemOffset,
                lastVisibleItemSize = lastVisibleItemSize,
                viewportEndOffset = viewportEndOffset,
                stickyAtBottom = stickyAtBottom,
                userScrolling = userScrolling,
            )
        }
        if (followAuxiliaryRow &&
            current.messageCount == previous.messageCount &&
            current.itemCount != previous.itemCount
        ) {
            // v0.5.4：辅助行变化分方向——只有 item 数**增加**（「正在写」行出现）才可能
            // 需要跟随；减少（「正在写」行消失）一律不滚：没有新内容要展示，而给容差会把
            // 「距末项一行」的用户误判成贴底、把正在阅读的用户拽到底部（第三方）。
            if (current.itemCount < previous.itemCount) return false
            return atBottomForFollow(
                firstVisibleItemIndex,
                visibleItemCount,
                totalItemCount,
                appendTrailingSlack(current, totalItemCount),
                lastVisibleItemIndex = lastVisibleItemIndex,
                lastVisibleItemOffset = lastVisibleItemOffset,
                lastVisibleItemSize = lastVisibleItemSize,
                viewportEndOffset = viewportEndOffset,
                stickyAtBottom = stickyAtBottom,
                userScrolling = userScrolling,
            )
        }
        if (current.isStreaming && !previous.isStreaming) {
            return atBottomForFollow(
                firstVisibleItemIndex,
                visibleItemCount,
                totalItemCount,
                appendTrailingSlack(current, totalItemCount),
                lastVisibleItemIndex = lastVisibleItemIndex,
                lastVisibleItemOffset = lastVisibleItemOffset,
                lastVisibleItemSize = lastVisibleItemSize,
                viewportEndOffset = viewportEndOffset,
                stickyAtBottom = stickyAtBottom,
                userScrolling = userScrolling,
            )
        }
        return false
    }
}

// =====================================================================================
// v0.5.6（残余②）：粘性贴底状态机（纯函数）

// 为什么需要：只靠「末项底边到视口底」的偏移判据，区分不了「用户主动上翻」和
// 「用户停在底部、流式正文把末项底边顶出视口底」——两者几何完全相同。旧行为（纯索引
// 判据）每帧都跟随，所以停在底部能一直追流；只加偏移判据后，正文一变长就被判「非贴底」
// 而停止跟随（回复越长越看不到新字）。

// 语义：像真实聊天软件一样记住「用户最后一次**自己**滚动停手时是否贴底」（sticky）：
// - 只有用户自己的拖拽/fling 结束才刷新 sticky（Compose 接线用 interactionSource 的
//   DragInteraction 起手 + isScrollInProgress 由真变假识别）；内容增长、程序化
//   animateScrollToItem 结束都不刷新——否则流式增长会把 sticky 误翻成 false；
// - 用户正在滚动时不自动跟随（不抢手势）；
// - 用户没在滚时，sticky 或当前真实偏移贴底任一成立即视为贴底。

// 纯函数集中在这里：JVM 单测直接调用（无 Compose 依赖）；Compose 接线在
// ChatScreen/GroupChatScreen 的 LaunchedEffect 里。
// 用户滚动结束时更新贴底状态，程序化滚动不修改该状态。
// =====================================================================================

internal object StickyBottom {

    /**
     * sticky 只在「用户滚动刚停」的边沿用真实贴底刷新，其它时候保持不变。
     *
     * @param previous 上一帧的粘性贴底
     * @param scrollStopped 这一帧是否是「用户滚动刚停」的边沿
     * @param rawAtBottom 用户停手那一刻的真实贴底（偏移判据，见
     *   [ChatAutoScroll.isAtBottomByGeometry]）
     */
    fun update(previous: Boolean, scrollStopped: Boolean, rawAtBottom: Boolean): Boolean =
        if (scrollStopped) rawAtBottom else previous

    /**
     * 这次内容变化要不要当作「贴底」处理（索引判据已通过的前提下）。
     *
     * 用户正在滚动 → false（不抢手势）；否则粘性贴底或当前真实贴底任一成立即 true。
     */
    fun atBottom(sticky: Boolean, rawAtBottom: Boolean, userScrolling: Boolean): Boolean =
        !userScrolling && (sticky || rawAtBottom)
}

/**
 * 跟随时末项还需要向前滚动的像素距离；只对当前可见末项计算。
 *
 * Long 中间值避免 offset + size 溢出；返回值限制到 animateScrollBy 可安全接收的 Int 范围。
 */
internal fun tailOffsetForFollow(
    lastVisibleItemIndex: Int,
    lastVisibleItemOffset: Int,
    lastVisibleItemSize: Int,
    viewportEndOffset: Int,
    targetItemIndex: Int,
): Int {
    if (targetItemIndex < 0 || lastVisibleItemIndex != targetItemIndex) return 0
    val remaining = lastVisibleItemOffset.toLong() +
        lastVisibleItemSize.toLong() - viewportEndOffset.toLong()
    return remaining.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

// =====================================================================================
// v0.5.4：流式正文的读屏播报判定

// 与 ChatAutoScroll 使用相同的状态约束，
// 顶层 internal object 不引用 Compose，JVM 单测可直接调用；Compose 语义接线在
// AssistantMessage 里；该 Compose 语义接线无法通过 JVM 单测验证。
// 依据（流式文本必须关闭自动播报，结束后整段播报一次）；
// （处置）。
// =====================================================================================

internal object ChatAnnounce {

    /**
     * 这条正文要不要挂 `liveRegion`（= 读屏在流式结束后整段播报一次）。
     *
     * 两个条件都要满足：
     * - `!streaming`：这条消息已经结束。流式期间挂 liveRegion 会让 TalkBack 每段 delta 重复
     *   播报（「流式文本必须关闭自动播报」）；
     * - `isNewestMessage`：只给最新一条「播报一次」。历史消息也挂 liveRegion 的话，翻历史 /
     *   加载更早消息时新进组合的旧正文可能被当成新内容再播报一遍。
     *
     * 已知未验证（无设备）：Compose 在已有节点上**新挂** liveRegion、以及消息节点首次进入
     * 组合时 TalkBack 的实际播报时机与次数，属平台 / 读屏器行为，尚未在真机验证。
     */
    fun shouldAnnounce(streaming: Boolean, isNewestMessage: Boolean): Boolean =
        isNewestMessage && !streaming
}
