package cc.pulsez.app.ui.chat

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.pulsez.app.AppContainer
import cc.pulsez.app.audio.AsrAvailability
import cc.pulsez.app.audio.ReadPolicy
import cc.pulsez.app.audio.VoiceInputEffect
import cc.pulsez.app.audio.VoiceInputEvent
import cc.pulsez.app.audio.VoiceInputMachine
import cc.pulsez.app.audio.VoiceInputState
import cc.pulsez.app.audio.VoiceQuotaGate
import cc.pulsez.app.audio.VoiceRecorderFiles
import cc.pulsez.app.audio.VoiceRecorderResult
import cc.pulsez.app.audio.VoiceRecorderStart
import cc.pulsez.app.audio.VoiceRecorderStatus
import cc.pulsez.app.core.AwakeningCommitResult
import cc.pulsez.app.core.AwakeningStatus
import cc.pulsez.app.core.CharacterAwakeningManager
import cc.pulsez.app.core.LatestReminder
import cc.pulsez.app.core.UsageLimits
import cc.pulsez.app.data.attachments.AttachmentErrorCodes
import cc.pulsez.app.data.attachments.AttachmentException
import cc.pulsez.app.data.db.AttachmentKind
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.db.ImportSourceSessionEntity
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.repo.UsageCategory
import cc.pulsez.app.data.settings.ImageEntryState
import cc.pulsez.app.data.settings.Settings
import cc.pulsez.app.data.settings.imageEntryState
import cc.pulsez.app.data.settings.isDeepSeekProvider
import cc.pulsez.app.media.DraftImage
import cc.pulsez.app.media.DraftRules
import cc.pulsez.app.media.DraftStatus
import cc.pulsez.app.media.ImageErrorCodes
import cc.pulsez.app.media.ImageMath
import cc.pulsez.app.media.ImagePipeline
import cc.pulsez.app.media.ImageProcessingException
import cc.pulsez.app.net.ApiError
import cc.pulsez.app.net.ChatMessage
import cc.pulsez.app.net.ChatRequest
import cc.pulsez.app.net.Citation
import cc.pulsez.app.net.FilesClient
import cc.pulsez.app.net.LlmEvent
import cc.pulsez.app.net.LlmClient
import cc.pulsez.app.net.UploadedFile
import cc.pulsez.app.net.isDeepSeekEndpoint
import cc.pulsez.app.ui.copy.ChatActionCopy
import cc.pulsez.app.ui.copy.ImageCopy
import cc.pulsez.app.ui.copy.VoiceCopy
import cc.pulsez.app.util.Ids
import cc.pulsez.app.wire.ErrorCopy
import cc.pulsez.app.wire.Pricing
import cc.pulsez.app.wire.ProtocolTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A remote ID is reusable only when both sides carry the same known local credential binding.
 * Local HMAC bindings are specific to an endpoint and credential; DeepSeek Files API IDs belong to an account:
 * https://api-docs.deepseek.com/guides/files_api/
 */
internal fun canReuseRemoteFile(
    remoteFileId: String?,
    storedBindingId: String?,
    sessionBindingId: String?,
): Boolean = !remoteFileId.isNullOrBlank() &&
    !storedBindingId.isNullOrBlank() &&
    !sessionBindingId.isNullOrBlank() &&
    storedBindingId == sessionBindingId

/** A short commit fence: Stop can cancel before mutation starts; after it starts, the result is synchronized. */
internal class MessageActionFence {
    private enum class State { ACTIVE, COMMITTING, CANCELLED, DONE }

    private val state = AtomicReference(State.ACTIVE)

    fun beginCommit(isStillAllowed: () -> Boolean): Boolean {
        if (!state.compareAndSet(State.ACTIVE, State.COMMITTING)) return false
        if (isStillAllowed()) return true
        state.compareAndSet(State.COMMITTING, State.CANCELLED)
        return false
    }

    /** Returns false once a transaction owns the commit boundary. */
    fun cancelBeforeCommit(): Boolean =
        state.compareAndSet(State.ACTIVE, State.CANCELLED) || state.get() == State.CANCELLED

    fun canContinue(): Boolean = state.get() == State.COMMITTING

    fun isCommitting(): Boolean = state.get() == State.COMMITTING

    fun complete() {
        state.compareAndSet(State.COMMITTING, State.DONE)
    }
}

/**
 * 聊天页状态机（基线）。
 *
 * 这一版先跑通"发出去 → 流式回来 → 落库"，记忆与上下文组装在 接 mem-core。
 * 关键约定：
 * - 停止生成 = cancel 协程，已生成内容保留；
 * - 失败不重发（避免重复计费），只给一次重试入口；
 * - AI 标识首次展示，用户收起后本次进程内不再展开（不可移除，见）。
 *
 * v0.5.0 识图：
 * - 草稿 ≤3 张、串行压缩（互斥锁），准备中禁止发送；失败态不阻塞打字；
 * - 首次带图发送前弹一次说明（逐字 `ImageCopy`，非阻断，DataStore 标记）；
 * - 发送时把草稿绑定到刚落库的用户消息，`ChatEngine.prepare` 从 DB 读出当轮图；
 * - 纯图消息正文落库占位「[图片]」；UI 始终显示本地缩略图；
 * - 图片相关失败换成专属人话（内容被拦 / 联网搜索冲突），其余走 `ErrorCopy`；
 * - 记账拆出「图片」分类，不污染消息条数。
 */
class ChatViewModel(
    private val container: AppContainer,
    private val characterId: String,
    private val beforeLoadPublishForTest: (suspend () -> Unit)? = null,
    private val beforeLoadOlderPublishForTest: (suspend () -> Unit)? = null,
) : ViewModel() {

    /** Narrow offline seams for credential-swap and upload/clear race tests; production leaves these null. */
    internal var endpointSessionResolverForTest: (suspend () -> AppContainer.ChatEndpointSession?)? = null
    internal var fileUploaderForTest: (suspend (File, String, String) -> UploadedFile)? = null
    internal var beforePersistUploadedFileForTest: (suspend (String) -> Unit)? = null

    data class UiState(
        val name: String = "",
        val messages: List<ChatUiMessage> = emptyList(),
        val input: String = "",
        val streaming: Boolean = false,
        val error: String? = null,
        val showAiBadge: Boolean = true,
        val ready: Boolean = false,
        /** 「可以在设置里调思考强度」行内提示：只在第一次打开深度思考时短暂出现。 */
        val thinkingHint: Boolean = false,
        /**
         * 轻提示（非错误）：例如"这次没能联网，先按普通回答"。
         *
         * 来源是 400 降级：请求带了联网工具被服务端拒绝，客户端已自动去掉 tools 重试成功，
         * 用户需要知道"这轮不是联网回答"。用中性色小字展示，不占用 [error]（红色错误行）。
         */
        val notice: String? = null,
        /**
         * 创作模式是否开着（v0.4）。
         *
         * 进页时由 [load] 读一次设置填入（**不**每轮发送都读）；开着时聊天页顶栏显示「出戏」，
         * 点它走 [exitCreativeMode]。设置页改了开关后回到本页时，由 AppRoot 调用
         * [syncCreativeMode] 事件驱动同步一次。
         */
        val creativeMode: Boolean = false,
        /** 还有更早的消息可以翻（v0.4 「查看更早」）。首屏 `tail(80)` 取满一页就为真。*/
        val hasMoreOlder: Boolean = false,
        /** 正在加载更早的一页：入口文字变「正在找…」并禁用，避免连点重复翻页。 */
        val loadingOlder: Boolean = false,
        /**
         * 图片入口是否显示（v0.5.0 识图；非 DeepSeek 端点隐藏，见 `Settings.imageEntryState`）。
         *
         * 进页读一次设置 + AppRoot 在设置变化时事件驱动 [syncImageGate]；
         * 不每轮轮询，但发送前仍会重读设置做防御性门禁。
         */
        val imageInputVisible: Boolean = false,
        /** 图片入口是否可用（DeepSeek 端点但模型看不了图时=false：入口置灰 + 点击说明）。 */
        val imageInputEnabled: Boolean = false,
        /** 文件入口是否可用（仅 DeepSeek 官方端点启用，不依赖是否支持识图）。 */
        val fileInputEnabled: Boolean = false,
        /** 草稿里的图（≤3；准备中/失败态由界面显示状态）。 */
        val drafts: List<DraftImage> = emptyList(),
        /** 草稿里的普通文件（≤3；准备中/就绪态）。 */
        val fileDrafts: List<DraftFile> = emptyList(),
        /** 正在上传文件到 DeepSeek Files API（发送中临时锁住，完成后转流式）。 */
        val uploadingFiles: Boolean = false,
        /** 底部「拍照 / 从相册选 / 发文件」面板是否打开。 */
        val showImageSource: Boolean = false,
        /** 首次带图发送前的说明弹窗（只弹一次，非阻断）。*/
        val showImageIntro: Boolean = false,
        /**
         * v0.5.1 语音输入的全部 UI 状态（模式 / 录音 / 转写 / 权限 / 首用说明 / 转写插入）。
         *
         * 门禁显隐由 [syncVoiceGate] 写（AppRoot 观察设置与麦克风可用性），其它字段都由本
         * ViewModel 的语音接线维护；纯状态结构见 `VoiceUiState`（JVM 可测）。
         */
        val voice: VoiceUiState = VoiceUiState(),
        val awakening: AwakeningUiState = AwakeningUiState(),
    ) {
        val isAwakening: Boolean get() = awakening.isAwakening
    }

    /** The identity captured synchronously when the user starts one send. */
    private data class SendAttempt(
        val id: Long,
        val characterId: String,
        val threadId: String,
        val wipeEpoch: Long,
        val onAccepted: (() -> Unit)?,
        var fileCredentialBindingId: String? = null,
    )

    private data class RegenerationRecovery(
        val actionId: Long,
        val assistantId: String,
        val original: ChatUiMessage,
        val userDbId: String,
    )

    private data class ActiveMessageFence(val actionId: Long, val fence: MessageActionFence)

    private data class ActiveUserAppendFence(
        val attemptId: Long,
        val fence: MessageActionFence,
        val stopAfterCommit: AtomicBoolean = AtomicBoolean(false),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var threadId: String = ""
    private var segmentId: String = ""
    private var streamJob: Job? = null
    private var awakeningJob: Job? = null
    private var reunionInProgress: Boolean = false

    /**
     * 本 ViewModel 创建时的清空代际（v0.5.4 `）。
     *
     * `AppContainer.wipeEpoch` 一旦变大 = 用户在本 VM 存活期间**开始执行**清空所有数据
     * （代际在进入 clearAll 前自增，所以 clearAll 执行期间的迟到写入也会被挡住）：
     * 1. 订阅处（[init]）立刻取消在途生成；
     * 2. 每轮生成在开始时把**当时**的代际传进 `runTurn`，迟到事件按它比对后整份丢弃。
     *
     * 为什么不用 `drop(1)` 跳过 StateFlow 的首帧：如果清空恰好发生在「VM 构造」与「收集器启动」
     * 之间，第一帧就是新代际，`drop(1)` 会把这次清空漏掉；与创建值比对则不会。
     */
    private val wipeEpochAtCreation: Long = container.wipeEpoch.value

    /** 已加载消息里最早一条的 `(segment ordinal, createdAt, id)`：聊天页翻页游标。 */
    private var oldestSegmentOrdinal: Int? = null
    private var oldestCreatedAt: Long? = null
    private var oldestId: String? = null

    /**
     * 图片导入串行锁：最多 3 张也要一张一张压缩，避免峰值内存 ×3（研究 §2.3.3）。
     * 用户连点相册「＋」时后一张排队，不会并发解码。
     */
    private val importMutex = Mutex()

    /** One in-flight send attempt; it remains bound to its synchronous session/wipe identity. */
    private var nextSendAttemptId: Long = 0L
    private var activeSendAttempt: SendAttempt? = null
    private var sendPreparationJob: Job? = null
    private var sendPreparationJobOwnerId: Long? = null
    private var fileSendAttemptId: Long? = null
    private var uploadRequestAttemptId: Long? = null
    private var filePickerGeneration: Long = 0L
    private var nextOlderLoadId: Long = 0L
    private var activeOlderLoadId: Long? = null
    /** Accepted send identity is read by Room transaction callbacks running on IO. */
    private val activeSendTurnAttempt = AtomicReference<SendAttempt?>(null)
    /** Main-thread owner for Stop; callbacks only touch its atomic fence and stop bit. */
    private var activeUserAppendFence: ActiveUserAppendFence? = null
    private var pendingImageIntroAttempt: SendAttempt? = null
    private var nextMessageActionId: Long = 0L
    private var activeMessageActionId: Long? = null
    private var activeMessageStreamActionId: Long? = null
    private var messageActionJobOwnerId: Long? = null
    private var messageActionJob: Job? = null
    private var regenerationRecovery: RegenerationRecovery? = null
    private var activeMessageFence: ActiveMessageFence? = null

    // ---------------------------------------------------------- v0.5.1 语音输入运行时

    /** 语音输入状态机（基建层；纯 Kotlin，副作用以枚举返回，这里负责执行）。 */
    private val voiceMachine = VoiceInputMachine()

    /** 正在进行的转写请求（离开页面/发送/取消时 cancel，丢弃结果）。 */
    private var voiceTranscribeJob: Job? = null

    /** 录音计时 + 60 秒自停监视线程（录音点「松手」前一直在跑）。 */
    private var voiceWatchJob: Job? = null

    /** 已录完、等转写的临时 WAV；转写成功/失败/取消后都必须删掉（本机不留音频）。 */
    private var voicePendingFile: File? = null

    /** 待转写录音的实际时长（给 `AsrClient.transcribe` 的前置校验用）。 */
    private var voicePendingDurationMs: Long = 0L

    /** 收尾去重：松手与「到 60 秒自停」可能同时到达，只处理一次。 */
    private var voiceFinishStarted: Boolean = false

    /**
     * 用户此刻是否真的按着「按住说话」（按下 = true，松手/取消 = false）。
     *
     * 用途：录音启动是异步的（要读设置、算额度），若用户在这段间隙已经松手，就不能再开录音——
     * 否则会变成「没按住却在录」，到 60 秒自停还会转写计费（` 同类风险，
     * v0.5.1 送检前修复）。
     */
    private var voiceHoldActive: Boolean = false

    /** 麦克风权限状态（UI 不缓存；每次点击由 ChatScreen 现查授权并回传）。 */
    private var voicePermissionState: VoicePermissionState = VoicePermissionState.NOT_REQUESTED

    /** 是否已经弹过一次系统权限框（用于区分「第一次拒绝」与「永久拒绝」）。 */
    private var voicePermissionRequestedBefore: Boolean = false

    /** 本机是否有麦克风（AppRoot 用 `PackageManager.FEATURE_MICROPHONE` 查）。 */
    private var voiceHasMicrophone: Boolean = false

    init {
        viewModelScope.launch { load() }
        // v0.5.4 清空代际一变就取消在途生成（清空前发起的回复不得在清空后落库）。
        // 本 VM 是 Activity 级、按角色缓存，清空后不一定立刻 onCleared —— 这一层是「主动停」，
        // 第二层是 `runTurn` 里的代际比对（应对「取消信号到写库之间」的窗口）。
        viewModelScope.launch {
            container.wipeEpoch.collect { epoch ->
                if (epoch != wipeEpochAtCreation) {
                    filePickerGeneration += 1L
                    cancelPendingFileSendAttempt(showUnconfirmedNotice = false)
                    invalidateSendAttempt()
                    activeUserAppendFence?.fence?.cancelBeforeCommit()
                    activeUserAppendFence = null
                    cancelPendingMessageAction()
                    activeMessageFence?.fence?.cancelBeforeCommit()
                    activeMessageFence = null
                    cancelStreamForWipe()
                }
            }
        }
    }

    /**
     * 清空**开始执行**后收口本 VM 的在途生成（v0.5.4 代际在清空进入执行时自增，
     * 见 `AppContainer.notifyUserDataWiped` 与 `AppRoot.runClear`）。
     *
     * 只取消生成本身：清空会删库，正在跑的回复已经没有可写的父会话；已显示的旧消息由
     * `AppRoot` 用清空 token 换全新 VM 负责。语音/图片等本机动作不在 范围（不写旧会话）。
     */
    private fun cancelStreamForWipe() {
        regenerationRecovery = null
        activeMessageStreamActionId = null
        streamJob?.cancel()
        streamJob = null
        awakeningJob?.cancel()
        awakeningJob = null
        reunionInProgress = false
        _state.value = _state.value.copy(
            streaming = false,
            uploadingFiles = false,
            awakening = AwakeningUiState(),
            voice = _state.value.voice.copy(awakeningBridge = null),
        )
    }

    private fun isCurrentSendAttempt(attempt: SendAttempt): Boolean =
        activeSendAttempt?.id == attempt.id &&
            attempt.characterId == characterId &&
            attempt.threadId.isNotBlank() &&
            attempt.threadId == threadId &&
            attempt.wipeEpoch == container.wipeEpoch.value

    /** Clear only the attempt that owns the current slot, so a stale coroutine cannot clear a newer send. */
    private fun invalidateSendAttempt(attempt: SendAttempt? = null) {
        val ownsUnacceptedAttempt = attempt == null || activeSendAttempt?.id == attempt.id
        if (attempt == null) {
            activeSendAttempt = null
            fileSendAttemptId = null
            uploadRequestAttemptId = null
            activeSendTurnAttempt.set(null)
            activeUserAppendFence?.fence?.cancelBeforeCommit()
            activeUserAppendFence = null
        } else {
            val accepted = activeSendTurnAttempt.get()?.takeIf { it.id == attempt.id }
            if (!ownsUnacceptedAttempt && accepted == null) return
            if (ownsUnacceptedAttempt) activeSendAttempt = null
            accepted?.let {
                activeSendTurnAttempt.compareAndSet(it, null)
            }
            if (activeUserAppendFence?.attemptId == attempt.id) {
                activeUserAppendFence?.fence?.cancelBeforeCommit()
                activeUserAppendFence = null
            }
            if (pendingImageIntroAttempt?.id == attempt.id) pendingImageIntroAttempt = null
            if (fileSendAttemptId == attempt.id) fileSendAttemptId = null
            if (uploadRequestAttemptId == attempt.id) uploadRequestAttemptId = null
        }
        if (ownsUnacceptedAttempt) {
            pendingImageIntroAttempt = null
            val snapshot = _state.value
            if (snapshot.showImageIntro || snapshot.uploadingFiles) {
                _state.value = snapshot.copy(showImageIntro = false, uploadingFiles = false)
            }
        }
    }

    /** Stop only the file-bearing preparation job; successful IDs are synchronized separately. */
    private fun cancelPendingFileSendAttempt(showUnconfirmedNotice: Boolean) {
        val attempt = activeSendAttempt?.takeIf { it.id == fileSendAttemptId } ?: return
        val requestMayHaveReachedService = uploadRequestAttemptId == attempt.id
        val job = sendPreparationJob.takeIf { sendPreparationJobOwnerId == attempt.id }
        invalidateSendAttempt(attempt)
        job?.cancel()
        if (showUnconfirmedNotice && requestMayHaveReachedService) {
            _state.value = _state.value.copy(notice = ChatActionCopy.FILE_UPLOAD_CANCELLED_UNCONFIRMED)
        }
    }

    private fun launchSendPreparation(attempt: SendAttempt, block: suspend () -> Unit) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (sendPreparationJobOwnerId == attempt.id) {
                    sendPreparationJobOwnerId = null
                    sendPreparationJob = null
                }
            }
        }
        sendPreparationJob = job
        sendPreparationJobOwnerId = attempt.id
        job.start()
    }

    private fun consumeSendAttempt(attempt: SendAttempt): Boolean {
        if (!isCurrentSendAttempt(attempt)) {
            invalidateSendAttempt(attempt)
            return false
        }
        activeSendAttempt = null
        pendingImageIntroAttempt = null
        if (fileSendAttemptId == attempt.id) fileSendAttemptId = null
        if (uploadRequestAttemptId == attempt.id) uploadRequestAttemptId = null
        return true
    }

    private fun isCurrentAcceptedSend(attempt: SendAttempt): Boolean =
        activeSendTurnAttempt.get()?.id == attempt.id &&
            attempt.wipeEpoch == container.wipeEpoch.value

    private fun finishAcceptedSend(attempt: SendAttempt, appendFence: ActiveUserAppendFence) {
        activeSendTurnAttempt.compareAndSet(attempt, null)
        if (activeUserAppendFence?.attemptId == appendFence.attemptId) {
            activeUserAppendFence = null
        }
    }

    /** Claim the shared message-action slot synchronously, before a DB/settings read can suspend. */
    private fun reserveMessageAction(snapshot: UiState): Long? {
        if (snapshot.isAwakening || snapshot.streaming || snapshot.uploadingFiles ||
            activeSendAttempt != null || activeUserAppendFence != null ||
            activeMessageActionId != null || activeMessageStreamActionId != null
        ) return null
        return (++nextMessageActionId).also { activeMessageActionId = it }
    }

    private fun ownsMessageAction(id: Long): Boolean = activeMessageActionId == id

    /** Release only the caller's reservation; a canceled older action cannot unlock a newer one. */
    private fun releaseMessageAction(id: Long) {
        if (activeMessageActionId != id) return
        activeMessageActionId = null
        if (messageActionJobOwnerId == id) {
            messageActionJobOwnerId = null
            messageActionJob = null
        }
    }

    private fun cancelPendingMessageAction() {
        val id = activeMessageActionId ?: return
        activeMessageActionId = null
        if (messageActionJobOwnerId == id) {
            val job = messageActionJob
            messageActionJobOwnerId = null
            messageActionJob = null
            job?.cancel()
        }
    }

    private fun launchMessageAction(
        id: Long,
        onFailure: (Throwable) -> Unit,
        action: suspend () -> Unit,
    ) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (ownsMessageAction(id)) onFailure(failure)
            } finally {
                releaseMessageAction(id)
            }
        }
        messageActionJobOwnerId = id
        messageActionJob = job
        job.start()
    }

    /** Transition the synchronous reservation to the streaming flag without opening a race window. */
    private fun transferMessageActionToStream(id: Long): Boolean {
        if (!ownsMessageAction(id)) return false
        activeMessageActionId = null
        if (messageActionJobOwnerId == id) {
            messageActionJobOwnerId = null
            messageActionJob = null
        }
        return true
    }

    /** 清空代际是否已不同于 [startEpoch]（v0.5.4 迟到写入判定）。*/
    private fun wipeChangedSince(startEpoch: Long): Boolean =
        container.wipeEpoch.value != startEpoch

    private suspend fun load() {
        // v0.5.4 进页加载也可能与清空并发（先取到角色 → 清空删库 → 这里再 ensureThread
        // 会给已删角色建一个孤儿会话）。写库前按代际收口；正常路径（没清空）恒为 false。
        val loadWipeEpoch = container.wipeEpoch.value
        val character = container.characters.get(characterId) ?: return
        if (wipeChangedSince(loadWipeEpoch)) return
        val thread = container.chat.ensureThreadForLoad(
            characterId = characterId,
            canContinue = { !wipeChangedSince(loadWipeEpoch) },
        ) ?: return
        if (wipeChangedSince(loadWipeEpoch)) return
        val history = container.chat.chatTailInSegmentOrder(thread.id, limit = INITIAL_PAGE_SIZE)
        if (wipeChangedSince(loadWipeEpoch)) return
        val importSourcesBySegment = container.importRepository
            .importSourcesForThread(thread.id)
            .associateBy { it.segmentId }
        if (wipeChangedSince(loadWipeEpoch)) return
        // 创作模式：进页读一次当前开关；**不**在每轮发送时轮询设置。
        // 设置通过 `Settings.creativeMode` 读取，并由 `SettingsStore` 保存。
        val settings = container.settings.flow.first()
        if (wipeChangedSince(loadWipeEpoch)) return
        val attachments = liveMessageAttachments()
        beforeLoadPublishForTest?.invoke()
        // Every read above can suspend. If wipe starts while this snapshot is being assembled,
        // leave the initial loading state untouched and do not install a stale thread cursor.
        if (wipeChangedSince(loadWipeEpoch)) return
        threadId = thread.id
        segmentId = thread.activeSegmentId ?: ""
        // 游标 = 已加载的最早一条（tail 返回正序，first 就是最早）。
        oldestSegmentOrdinal = history.firstOrNull()?.segmentOrdinal
        oldestCreatedAt = history.firstOrNull()?.message?.createdAt
        oldestId = history.firstOrNull()?.message?.id
        val entry = settings.imageEntryState()
        val isDeepSeek = settings.isDeepSeekProvider()
        _state.value = _state.value.copy(
            name = character.name,
            messages = history.map {
                val message = it.message
                message.toUiMessage(
                    attachments = attachments,
                    importSource = importSourcesBySegment[message.segmentId],
                )
            },
            ready = true,
            creativeMode = settings.creativeMode,
            imageInputVisible = entry != ImageEntryState.HIDDEN_OTHER_PROVIDER,
            imageInputEnabled = entry == ImageEntryState.ENABLED,
            fileInputEnabled = isDeepSeek,
            // 取满一页就假定还有更早的：多一次"空结果"的点击成本，比漏掉历史好；
            // 真正为空时 loadOlder 会立即把 hasMoreOlder 置 false（不重不漏由游标保证）。
            hasMoreOlder = history.size >= INITIAL_PAGE_SIZE,
            showAiBadge = !container.aiBadgeDismissed,
        )
        observeAwakening()
        // 一次性引导**不在进页时**出现：只有用户自己第一次操作「深度思考」开关时，
        // onToggleThinking 才会触发（产品决定 2026-11-20，任意方向都算）。
    }

    private fun observeAwakening() {
        awakeningJob?.cancel()
        awakeningJob = viewModelScope.launch {
            container.awakeningManager.onEnterChat(characterId)
            container.awakeningManager.observeCharacter(characterId).collect { entry ->
                if (reunionInProgress) return@collect
                val currentName = _state.value.name
                val next = when (entry?.status) {
                    null, AwakeningStatus.COMPLETED -> AwakeningUiState()
                    AwakeningStatus.QUEUED, AwakeningStatus.STREAMING -> AwakeningUiState(
                        phase = AwakeningUiPhase.WHISPERS,
                        whispers = entry.whispers,
                        streamCompleted = false,
                        characterName = currentName,
                        reunionSubtitle = entry.reunionSubtitle,
                    )
                    AwakeningStatus.READY_FOR_CEREMONY -> {
                        val candidateName = entry.profile?.name?.takeIf {
                            currentName.trim() in PLACEHOLDER_NAMES && it.isNotBlank()
                        } ?: currentName
                        AwakeningUiState(
                            phase = AwakeningUiPhase.WHISPERS,
                            whispers = entry.whispers,
                            streamCompleted = true,
                            characterName = candidateName,
                            reunionSubtitle = entry.reunionSubtitle,
                        )
                    }
                    AwakeningStatus.FAILED -> AwakeningUiState(
                        phase = AwakeningUiPhase.FAILED,
                        characterName = currentName,
                        errorMessage = entry.errorMessage ?: CharacterAwakeningManager.FAILURE_COPY,
                    )
                }
                applyAwakeningUi(next)
            }
        }
    }

    fun startReunionCeremony() {
        if (reunionInProgress) return
        val current = _state.value.awakening
        if (current.phase != AwakeningUiPhase.WHISPERS) return
        reunionInProgress = true
        applyAwakeningUi(current.copy(phase = AwakeningUiPhase.REUNION))
        viewModelScope.launch {
            val commit = runCatching {
                container.awakeningManager.commitAwakeningToRoom(characterId, includeGreeting = true)
            }.getOrNull()
            applyCommitResult(commit)
        }
    }

    fun completeReunionCeremony() {
        if (!reunionInProgress && _state.value.awakening.phase != AwakeningUiPhase.REUNION) return
        viewModelScope.launch {
            val commit = runCatching {
                container.awakeningManager.commitAwakeningToRoom(characterId, includeGreeting = true)
            }.getOrNull()
            applyCommitResult(commit)
            reunionInProgress = false
            applyAwakeningUi(AwakeningUiState())
        }
    }

    fun retryAwakening() {
        viewModelScope.launch {
            container.awakeningManager.retryCharacter(characterId)
        }
    }

    fun skipAwakening() {
        viewModelScope.launch {
            val commit = runCatching {
                container.awakeningManager.commitAwakeningToRoom(characterId, includeGreeting = true)
            }.getOrNull()
            applyCommitResult(commit)
            reunionInProgress = false
            applyAwakeningUi(AwakeningUiState())
        }
    }

    private fun applyAwakeningUi(awakening: AwakeningUiState) {
        if (awakening.isAwakening && _state.value.voice.mode == ComposerMode.VOICE) {
            cancelVoiceForLifecycle(clearMode = true)
        }
        val bridge = if (awakening.isAwakening) {
            AwakeningUiBridge(
                uiState = awakening,
                onRetry = ::retryAwakening,
                onSkip = ::skipAwakening,
                onStartReunion = ::startReunionCeremony,
                onCompleteReunion = ::completeReunionCeremony,
            )
        } else {
            null
        }
        _state.value = _state.value.copy(
            awakening = awakening,
            voice = _state.value.voice.copy(awakeningBridge = bridge),
        )
    }

    private fun applyCommitResult(commit: AwakeningCommitResult?) {
        if (commit == null) return
        val current = _state.value
        val nextName = commit.updatedName ?: current.name
        val greeting = commit.greetingMessage
        val nextMessages = if (greeting != null && current.messages.none { it.id == greeting.id }) {
            current.messages + greeting.toUiMessage(
                attachments = LiveMessageAttachments(),
                importSource = null,
            )
        } else {
            current.messages
        }
        if (nextName != current.name || nextMessages != current.messages) {
            val nextAwakening = current.awakening.copy(characterName = nextName)
            val nextBridge = current.voice.awakeningBridge?.copy(uiState = nextAwakening)
            _state.value = current.copy(
                name = nextName,
                messages = nextMessages,
                awakening = nextAwakening,
                voice = current.voice.copy(awakeningBridge = nextBridge),
            )
        }
    }

    /**
     * 「看看更早的」（v0.4）。
     *
     * 用已加载的最早一条 `(segment ordinal, createdAt, id)` 做游标，向 DB 取更早的一页（60 条）并**前插**到
     * 消息列表头部；返回空表示到头了，`hasMoreOlder` 置 false（入口消失）。
     *
     * 并发/重复点击：`loadingOlder` + `hasMoreOlder` 双闸；协程在 IO 里查库，不阻塞主线程。
     * 失败给一句可重试的人话，不影响已显示的消息。
     */
    fun loadOlder() {
        val snapshot = _state.value
        if (snapshot.loadingOlder || !snapshot.hasMoreOlder) return
        if (threadId.isEmpty()) return
        val loadWipeEpoch = container.wipeEpoch.value
        val loadThreadId = threadId
        val cursorOrdinal = oldestSegmentOrdinal ?: return
        val cursorAt = oldestCreatedAt ?: return
        val cursorId = oldestId ?: return
        val loadId = ++nextOlderLoadId
        activeOlderLoadId = loadId
        _state.value = snapshot.copy(loadingOlder = true, error = null)
        viewModelScope.launch {
            try {
                val older = withContext(Dispatchers.IO) {
                    container.chat.chatTailBeforeInSegmentOrder(
                        loadThreadId,
                        cursorOrdinal,
                        cursorAt,
                        cursorId,
                        OLDER_PAGE_SIZE,
                    )
                }
                val attachments = liveMessageAttachments()
                val importSourcesBySegment = container.importRepository
                    .importSourcesForThread(loadThreadId)
                    .associateBy { it.segmentId }
                beforeLoadOlderPublishForTest?.invoke()
                // AppContainer.notifyUserDataWiped 在清空入口递增代际；分页、附件、导入来源是
                // 分开的挂起读取，最终校验拒绝清空开始后读到的旧页写回 UI。
                if (activeOlderLoadId != loadId) return@launch
                if (threadId != loadThreadId) {
                    activeOlderLoadId = null
                    _state.value = _state.value.copy(loadingOlder = false)
                    return@launch
                }
                if (wipeChangedSince(loadWipeEpoch)) {
                    activeOlderLoadId = null
                    _state.value = _state.value.copy(loadingOlder = false)
                    return@launch
                }

                if (older.isEmpty()) {
                    activeOlderLoadId = null
                    _state.value = _state.value.copy(loadingOlder = false, hasMoreOlder = false)
                } else {
                    activeOlderLoadId = null
                    _state.value = _state.value.copy(
                        messages = older.map {
                            val message = it.message
                            message.toUiMessage(
                                attachments = attachments,
                                importSource = importSourcesBySegment[message.segmentId],
                            )
                        } + _state.value.messages,
                        loadingOlder = false,
                        hasMoreOlder = older.size >= OLDER_PAGE_SIZE,
                    )
                    oldestSegmentOrdinal = older.first().segmentOrdinal
                    oldestCreatedAt = older.first().message.createdAt
                    oldestId = older.first().message.id
                }
            } catch (cancelled: CancellationException) {
                if (activeOlderLoadId == loadId) {
                    activeOlderLoadId = null
                    _state.value = _state.value.copy(loadingOlder = false)
                }
                throw cancelled
            } catch (_: Throwable) {
                if (activeOlderLoadId == loadId) {
                    activeOlderLoadId = null
                    if (threadId == loadThreadId && !wipeChangedSince(loadWipeEpoch)) {
                        _state.value = _state.value.copy(
                            loadingOlder = false,
                            error = "更早的消息没找到。再试一次？",
                        )
                    } else {
                        _state.value = _state.value.copy(loadingOlder = false)
                    }
                }
            }
        }
    }

    fun onInputChange(value: String) {
        if (_state.value.isAwakening) return
        // 用户开始写下一句 = 这条轻提示已经看过了；清掉，避免常驻在输入条上方。
        _state.value = _state.value.copy(input = value, notice = null)
    }

    fun dismissAiBadge() {
        container.aiBadgeDismissed = true
        _state.value = _state.value.copy(showAiBadge = false)
    }

    /**
     * 「深度思考」胶囊：点击即时持久化；**用户第一次操作这个开关**（无论开还是关）时
     * 给一条行内提示（产品决定 2026-11-20：默认已开，不按「关切→开」触发；
     * 安装后只出现一次，且用户没在设置里改过强度）。
     *
     * 展示时序（300ms 延迟 / 4s / 系统建议时长 / 淡入淡出）由 `ChatScreen` 负责，
     * 这里只翻转一个布尔值，避免动画与计时散落在两边。
     */
    fun onToggleThinking(enabled: Boolean) {
        if (_state.value.isAwakening) return
        viewModelScope.launch {
            container.settings.setThinkingEnabled(enabled)
            if (container.settings.consumeThinkingHint()) {
                _state.value = _state.value.copy(thinkingHint = true)
            }
        }
    }

    fun dismissThinkingHint() {
        _state.value = _state.value.copy(thinkingHint = false)
    }

    /** 「智能搜索」胶囊：点击即时持久化。 */
    fun onToggleWebSearch(enabled: Boolean) {
        if (_state.value.isAwakening) return
        viewModelScope.launch { container.settings.setWebSearchEnabled(enabled) }
    }

    /**
     * 「入戏」：一键开启创作模式（v0.6.9 WP-D）。
     *
     * 顺序：**先落设置、再改内存状态**（与 [exitCreativeMode] 对称，兼容 AppRoot 的 `syncCreativeMode`）。
     * 依据：app/src/main/java/cc/pulsez/app/ui/chat/ChatViewModel.kt:585
     */
    fun enterCreativeMode() {
        viewModelScope.launch {
            container.settings.setCreativeMode(true)
            _state.value = _state.value.copy(
                creativeMode = true,
                notice = ENTER_CREATIVE_MODE_NOTICE,
            )
        }
    }

    /**
     * 「出戏」：一键关掉创作模式（v0.4）。
     *
     * 顺序：**先落设置、再改内存状态**。反过来的话，用户出戏后马上杀掉 App 再进来，
     * 设置里还是旧的 `true`，会又回到创作模式。落盘成功后给一条轻提示（中性色，
     * 走既有 `notice` 通道，不作错误）；用户开始写下一句时 [onInputChange] 会照旧清掉它。
     */
    fun exitCreativeMode() {
        viewModelScope.launch {
            container.settings.setCreativeMode(false)
            _state.value = _state.value.copy(
                creativeMode = false,
                notice = EXIT_CREATIVE_MODE_NOTICE,
            )
        }
    }

    /**
     * 设置页改了「创作模式」后回到本页：把页内状态同步成设置里的值。
     *
     * 为什么要这一步：聊天 ViewModel 按角色缓存，切页回来**不会重新 [load]**，
     * 不补这一下就会看到"设置里开了、聊天页却没有「出戏」"的过期状态。
     * 它是事件驱动的（由 AppRoot 观察设置流后调用），**不是**每轮发送都去读设置。
     */
    fun syncCreativeMode(enabled: Boolean) {
        if (_state.value.creativeMode == enabled) return
        _state.value = _state.value.copy(creativeMode = enabled)
    }

    /**
     * 设置页/端点/模型变化后同步图片入口三态（v0.5.0 识图）与 DeepSeek 文件入口门禁（v0.6.10）。
     *
     * 与 [syncCreativeMode] 同一定位：事件驱动（AppRoot 观察设置流），不每轮轮询；
     * 真正发送前仍会用最新设置重算一次门禁（防御，见 [send]）。
     */
    fun syncImageGate(visible: Boolean, enabled: Boolean, fileEnabled: Boolean = visible) {
        val snapshot = _state.value
        if (snapshot.imageInputVisible == visible &&
            snapshot.imageInputEnabled == enabled &&
            snapshot.fileInputEnabled == fileEnabled
        ) {
            return
        }
        _state.value = snapshot.copy(
            imageInputVisible = visible,
            imageInputEnabled = enabled,
            fileInputEnabled = fileEnabled,
        )
    }

    // ---------------------------------------------------------------- v0.5.0 识图 / v0.6.10 文件：草稿

    /**
     * 点输入条内部右下角的「＋」。
     *
     * - 非 DeepSeek 端点（图与文件入口都关）：忽略；
     * - DeepSeek 端点但模型看不了图、且文件入口也未开：给去向轻提示；
     * - 否则打开底部面板（非识图 DeepSeek 端点下在面板内灰显拍照/相册，只让选文件）。
     */
    fun onAddImage() {
        val snapshot = _state.value
        if (snapshot.isAwakening) return
        if (!snapshot.imageInputVisible && !snapshot.fileInputEnabled) return
        if (!snapshot.imageInputEnabled && !snapshot.fileInputEnabled) {
            _state.value = snapshot.copy(notice = ImageCopy.VISION_MODEL_DISABLED)
            return
        }
        if (snapshot.imageInputEnabled && !snapshot.fileInputEnabled && !DraftRules.canAdd(snapshot.drafts)) {
            _state.value = snapshot.copy(notice = ImageCopy.LIMIT_REACHED)
            return
        }
        _state.value = snapshot.copy(showImageSource = true, notice = null)
    }

    fun showNotice(message: String) {
        _state.value = _state.value.copy(notice = message)
    }

    /**
     * 选择普通文件回调：拷贝进私有目录、按 DeepSeek Files API 上限提前卡控体积（文档 100 MiB / 纯文 32 MiB）。
     */
    fun onFilePicked(uri: Uri, contentResolver: ContentResolver) {
        val snapshot = _state.value
        if (snapshot.uploadingFiles || fileSendAttemptId != null) {
            _state.value = snapshot.copy(notice = ChatActionCopy.FILE_REMOVE_BLOCKED)
            return
        }
        if (activeSendAttempt != null) {
            _state.value = snapshot.copy(notice = ChatActionCopy.FILE_SEND_BLOCKED_PREPARING)
            return
        }
        if (snapshot.isAwakening) return
        if (!snapshot.fileInputEnabled) {
            _state.value = snapshot.copy(notice = ChatActionCopy.FILE_OTHER_PROVIDER)
            return
        }
        if (!FileDraftRules.canAdd(snapshot.fileDrafts)) {
            _state.value = snapshot.copy(notice = ChatActionCopy.FILE_LIMIT_REACHED)
            return
        }
        _state.value = snapshot.copy(showImageSource = false)
        val ownerThreadId = threadId
        val pickerGeneration = filePickerGeneration
        val pickerWipeEpoch = container.wipeEpoch.value
        val placeholderId = "file-draft-${Ids.new()}"
        var resolvedFileName = "文件"
        var resolvedMime = "application/octet-stream"
        _state.value = snapshot.copy(
            showImageSource = false,
            fileDrafts = snapshot.fileDrafts + DraftFile(
                id = placeholderId,
                localPath = "",
                fileName = "文件",
                mime = "application/octet-stream",
                sizeBytes = 0L,
                purpose = FilesClient.PURPOSE_USER_DATA,
                status = DraftStatus.PREPARING,
            ),
            notice = null,
        )
        viewModelScope.launch {
            var resolvedPurpose = FilesClient.PURPOSE_USER_DATA
            var createdAttachmentId: String? = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val metadata = queryUriMetadata(contentResolver, uri)
                    val fileName = metadata.displayName
                        ?.takeIf { it.isNotBlank() }
                        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                        ?: "文件"
                    val mime = contentResolver.getType(uri)
                        ?.takeIf { it.isNotBlank() }
                        ?: "application/octet-stream"
                    val purpose = FilesClient.purposeForFile(fileName, mime)
                    resolvedFileName = fileName
                    resolvedMime = mime
                    resolvedPurpose = purpose
                    // FilesClient.maxBytesForFile follows the official per-file category limits.
                    val maxBytes = FilesClient.maxBytesForFile(fileName, mime)
                    val declaredSize = metadata.sizeBytes
                    if (declaredSize != null && declaredSize > maxBytes) {
                        throw AttachmentException(AttachmentErrorCodes.TOO_LARGE, "file exceeds $maxBytes bytes")
                    }
                    val stream = contentResolver.openInputStream(uri)
                        ?: throw AttachmentException(AttachmentErrorCodes.STORE_IO, "cannot open input stream")
                    val created = stream.use { input ->
                        container.attachments.createFileDraft(
                            store = container.attachmentStore,
                            mime = mime,
                            stream = input,
                            fileName = fileName,
                            maxBytes = maxBytes,
                            ownerThreadId = ownerThreadId.takeIf { it.isNotBlank() },
                        )
                    }
                    createdAttachmentId = created.id
                    container.attachments.markReady(created.id)
                }
            }.onSuccess { entity ->
                if (!isCurrentFilePickerResult(pickerGeneration, ownerThreadId, pickerWipeEpoch)) {
                    withContext(Dispatchers.IO) { discardFileDraftAndRequestCleanup(entity.id) }
                    return@onSuccess
                }
                val applied = replaceFileDraft(placeholderId) {
                    DraftFile(
                        id = entity.id,
                        localPath = entity.localPath,
                        fileName = entity.fileName?.takeIf { it.isNotBlank() } ?: "文件",
                        mime = entity.mime,
                        sizeBytes = entity.sizeBytes,
                        purpose = resolvedPurpose,
                        status = DraftStatus.READY,
                        remoteFileId = entity.remoteFileId,
                    )
                }
                if (!applied) {
                    withContext(Dispatchers.IO) {
                        discardFileDraftAndRequestCleanup(entity.id)
                    }
                }
            }.onFailure { throwable ->
                createdAttachmentId?.let { createdId ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { discardFileDraftAndRequestCleanup(createdId) }
                    }
                }
                if (throwable is CancellationException) throw throwable
                if (!isCurrentFilePickerResult(pickerGeneration, ownerThreadId, pickerWipeEpoch)) {
                    return@onFailure
                }
                val noticeText = if ((throwable as? AttachmentException)?.code == AttachmentErrorCodes.TOO_LARGE) {
                    ChatActionCopy.fileTooLarge(resolvedFileName, resolvedMime)
                } else {
                    ChatActionCopy.FILE_READ_FAILED
                }
                _state.value = _state.value.copy(
                    fileDrafts = _state.value.fileDrafts.filterNot { it.id == placeholderId },
                    notice = noticeText,
                )
            }
        }
    }

    private fun isCurrentFilePickerResult(generation: Long, ownerThreadId: String, wipeEpoch: Long): Boolean =
        generation == filePickerGeneration && ownerThreadId == threadId && !wipeChangedSince(wipeEpoch)

    private suspend fun discardFileDraftAndRequestCleanup(id: String) {
        try {
            container.attachments.discardDraft(id, container.attachmentStore)
        } finally {
            // AttachmentRepository commits the tombstone and remote cleanup queue in one transaction.
            // Signal only after that suspend call has committed or failed; a harmless empty scan is safe.
            container.remoteFileCleanupCoordinator.requestRetry()
        }
    }

    private suspend fun resolveChatEndpoint(): AppContainer.ChatEndpointSession? {
        val resolver = endpointSessionResolverForTest
        return if (resolver == null) container.resolvedChatEndpoint() else resolver()
    }

    private suspend fun persistUploadedFileReference(
        attachmentId: String,
        uploadedId: String,
        expiresAt: Long?,
        bindingId: String,
    ): Boolean = withContext(NonCancellable) {
        beforePersistUploadedFileForTest?.invoke(uploadedId)
        var bound = false
        try {
            bound = withContext(Dispatchers.IO) {
                container.attachments.setRemoteFile(
                    id = attachmentId,
                    remoteFileId = uploadedId,
                    expiresAt = expiresAt,
                    remoteCredentialBindingId = bindingId,
                ) > 0
            }
        } catch (_: Throwable) {
            bound = false
        }
        try {
            if (!bound) {
                withContext(Dispatchers.IO) {
                    container.remoteFileCleanup.enqueue(uploadedId, bindingId)
                }
            }
        } catch (_: Throwable) {
            // A database outage can prevent both the bind and fallback queue; report failure without claiming cleanup.
        } finally {
            container.remoteFileCleanupCoordinator.requestRetry()
        }
        bound
    }

    private fun replaceFileDraft(id: String, transform: (DraftFile) -> DraftFile): Boolean {
        val snapshot = _state.value
        if (snapshot.fileDrafts.none { it.id == id }) return false
        _state.value = snapshot.copy(
            fileDrafts = snapshot.fileDrafts.map { if (it.id == id) transform(it) else it },
        )
        return true
    }

    fun removeFileDraft(id: String) {
        if (_state.value.uploadingFiles || fileSendAttemptId != null) {
            _state.value = _state.value.copy(notice = ChatActionCopy.FILE_REMOVE_BLOCKED)
            return
        }
        val target = _state.value.fileDrafts.firstOrNull { it.id == id } ?: return
        _state.value = _state.value.copy(
            fileDrafts = _state.value.fileDrafts.filterNot { it.id == id },
            notice = null,
        )
        if (target.status != DraftStatus.PREPARING && target.id.isNotBlank() && !target.id.startsWith("file-draft-")) {
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) { discardFileDraftAndRequestCleanup(target.id) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (_state.value.fileDrafts.none { it.id == target.id }) {
                        _state.value = _state.value.copy(
                            fileDrafts = _state.value.fileDrafts + target,
                            notice = ChatActionCopy.FILE_REMOVE_FAILED,
                        )
                    }
                }
            }
        }
    }

    private data class UriMetadata(
        val displayName: String? = null,
        val sizeBytes: Long? = null,
    )

    private fun queryUriMetadata(resolver: ContentResolver, uri: Uri): UriMetadata {
        return runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use UriMetadata()
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                UriMetadata(displayName = name, sizeBytes = size)
            } ?: UriMetadata()
        }.getOrDefault(UriMetadata())
    }

    /** 关掉「拍照 / 从相册选」面板（返回手势/点外面都会走到）。 */
    fun dismissImageSource() {
        if (!_state.value.showImageSource) return
        _state.value = _state.value.copy(showImageSource = false)
    }

    /**
     * 相册/文档选择器回调：回调里立刻交给管线（内部第一步就是拷贝到私有目录），
     * 再在 IO 线程压缩。不要在这里做任何重活（主线程）。
     */
    fun onImagePicked(uri: Uri) {
        _state.value = _state.value.copy(showImageSource = false)
        val ownerThreadId = threadId
        startImport { container.imagePipeline.importPicked(uri, ownerThreadId = ownerThreadId) }
    }

    /** 相机 `TakePicture` 成功回调：临时文件已经在私有 cache 里，直接压缩。 */
    fun onCameraCaptured(file: File) {
        _state.value = _state.value.copy(showImageSource = false)
        val ownerThreadId = threadId
        startImport { container.imagePipeline.importCamera(file, ownerThreadId = ownerThreadId) }
    }

    /** 相机取消/失败：删掉临时文件并给一句轻提示（不指责用户）。 */
    fun onCameraCancelled(file: File) {
        _state.value = _state.value.copy(showImageSource = false)
        viewModelScope.launch(Dispatchers.IO) { runCatching { file.delete() } }
    }

    private fun startImport(block: suspend () -> ImagePipeline.Imported) {
        val snapshot = _state.value
        if (!DraftRules.canAdd(snapshot.drafts)) {
            _state.value = snapshot.copy(notice = ImageCopy.LIMIT_REACHED)
            return
        }
        // 先插一条 PREPARING 占位：用户马上看到"正在处理"，发送按钮同时被禁用。
        val placeholderId = "draft-${Ids.new()}"
        _state.value = snapshot.copy(
            drafts = snapshot.drafts + DraftImage(placeholderId, localPath = "", status = DraftStatus.PREPARING),
            notice = null,
        )
        viewModelScope.launch {
            importMutex.withLock {
                runCatching { withContext(Dispatchers.IO) { block() } }
                    .onSuccess { imported ->
                        val applied = replaceDraft(placeholderId) {
                            DraftImage(
                                id = imported.attachment.id,
                                localPath = imported.attachment.localPath,
                                status = DraftStatus.READY,
                                firstFrameOnly = imported.firstFrameOnly,
                            )
                        }
                        // 处理期间用户删掉了占位：结果没人接收，必须把刚落的 DB 行 + 文件回收，
                        // 否则会变成永远不被引用、启动清理也删不掉的活附件。
                        if (!applied) {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    container.attachments.discardDraft(
                                        imported.attachment.id,
                                        container.attachmentStore,
                                    )
                                }
                            }
                        }
                    }
                    .onFailure { throwable ->
                        val code = (throwable as? ImageProcessingException)?.code
                            ?: (throwable as? AttachmentException)?.code
                            ?: ImageErrorCodes.BAD_IMAGE
                        replaceDraft(placeholderId) {
                            it.copy(status = DraftStatus.FAILED, errorCode = code)
                        }
                    }
            }
        }
    }

    /**
     * 把草稿列表里 [id] 的那一项替换成 [transform] 的结果。
     *
     * @return false = 列表里已经没有这个 id（用户在处理过程中删掉了占位）；调用方据此决定
     *   是否回收刚导入的附件（` 不能静默丢弃结果留下孤儿）。
     */
    private fun replaceDraft(id: String, transform: (DraftImage) -> DraftImage): Boolean {
        val snapshot = _state.value
        if (snapshot.drafts.none { it.id == id }) return false
        _state.value = snapshot.copy(
            drafts = snapshot.drafts.map { if (it.id == id) transform(it) else it },
        )
        return true
    }

    /**
     * 删掉一张草稿。
     *
     * 已落盘（READY / FAILED）的要同时软删 DB 行 + 删文件，否则会留下永不被引用的活附件
     * （只能等启动孤儿清理，白占空间；启动清理的 keep 集合包含活行，见）。
     * 准备中的没有 DB 行，从列表移除即可；导入完成时 [replaceDraft] 返回 false，那里会
     * 把刚落的行/文件回收（[startImport]）。
     *
     * 依据：` （草稿/失败态附件永久孤儿）。
     */
    fun removeDraft(id: String) {
        val target = _state.value.drafts.firstOrNull { it.id == id } ?: return
        _state.value = _state.value.copy(
            drafts = _state.value.drafts.filterNot { it.id == id },
            notice = null,
        )
        // PREPARING 占位的 id 形如 `draft-<uuid>`，此时还没有 DB 行（有行的是 READY/FAILED）。
        if (target.status != DraftStatus.PREPARING && target.id.isNotBlank() && !target.id.startsWith("draft-")) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { container.attachments.discardDraft(target.id, container.attachmentStore) }
            }
        }
    }

    /**
     * 「再用这张图问一次」：
     * 把旧消息里的本地图片重新放回草稿，不重新压缩（文件已经是 1536px JPEG）。
     *
     * 失败（文件被清）给一句人话；满 3 张/门禁关闭时同样给说明。
     */
    fun onResendImage(localPath: String) {
        val snapshot = _state.value
        if (snapshot.isAwakening) return
        if (!snapshot.imageInputVisible) return
        if (!snapshot.imageInputEnabled) {
            _state.value = snapshot.copy(notice = ImageCopy.VISION_MODEL_DISABLED)
            return
        }
        if (!DraftRules.canAdd(snapshot.drafts)) {
            _state.value = snapshot.copy(notice = ImageCopy.LIMIT_REACHED)
            return
        }
        val placeholderId = "draft-${Ids.new()}"
        _state.value = snapshot.copy(
            drafts = snapshot.drafts + DraftImage(placeholderId, localPath = "", status = DraftStatus.PREPARING),
            notice = null,
        )
        viewModelScope.launch {
            importMutex.withLock {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val file = container.attachmentStore.path(localPath)
                        if (!file.isFile) throw ImageProcessingException(ImageErrorCodes.IO)
                        val bytes = file.readBytes()
                        if (bytes.isEmpty()) throw ImageProcessingException(ImageErrorCodes.IO)
                        val entity = container.attachments.createDraft(
                            store = container.attachmentStore,
                            kind = AttachmentKind.IMAGE,
                            mime = "image/jpeg",
                            bytes = bytes,
                            ownerThreadId = threadId,
                        )
                        try {
                            container.attachments.markReady(entity.id)
                        } catch (t: Throwable) {
                            // markReady 失败：把刚落的行/文件回滚掉，不留孤儿。
                            // 失败态草稿的 id 仍是 placeholderId，没有 DB 行可删。
                            runCatching {
                                container.attachments.discardDraft(entity.id, container.attachmentStore)
                            }
                            throw t
                        }
                    }
                }.onSuccess { ready ->
                    val applied = replaceDraft(placeholderId) {
                        DraftImage(id = ready.id, localPath = ready.localPath, status = DraftStatus.READY)
                    }
                    // 处理期间用户删掉了占位：刚建的行/文件没人接收，回滚掉（同 [startImport]）。
                    if (!applied) {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                container.attachments.discardDraft(ready.id, container.attachmentStore)
                            }
                        }
                    }
                }.onFailure { throwable ->
                    val code = (throwable as? ImageProcessingException)?.code
                        ?: (throwable as? AttachmentException)?.code
                        ?: ImageErrorCodes.BAD_IMAGE
                    replaceDraft(placeholderId) {
                        it.copy(status = DraftStatus.FAILED, errorCode = code)
                    }
                }
            }
        }
    }

    /** 首次说明弹窗：任一按钮/点外面都算看过，并继续执行仍属于当前会话与清空代际的发送。 */
    fun acknowledgeImageIntro() {
        if (!_state.value.showImageIntro) return
        val attempt = pendingImageIntroAttempt
        _state.value = _state.value.copy(showImageIntro = false)
        pendingImageIntroAttempt = null
        if (attempt == null || !isCurrentSendAttempt(attempt)) {
            invalidateSendAttempt(attempt)
            return
        }
        launchSendPreparation(attempt) {
            if (!isCurrentSendAttempt(attempt)) {
                invalidateSendAttempt(attempt)
                return@launchSendPreparation
            }
            runCatching { container.settings.setImageIntroSeen(true) }
            if (!isCurrentSendAttempt(attempt)) {
                invalidateSendAttempt(attempt)
                return@launchSendPreparation
            }
            sendInternal(null, attempt)
        }
    }

    // ---------------------------------------------------------------- v0.5.1 语音输入

    /**
     * 同步语音入口门禁（AppRoot 在端点/密钥/麦克风可用性变化时事件驱动调用）。
     *
     * 与 [syncImageGate] 同一定位：不每轮轮询设置；入口只在该服务商真能做 ASR 且本机有麦克风时
     * 出现（不留死按钮）。门禁结论由 `VoiceGateUiResolver` 纯函数给出（JVM 可测）。
     */
    fun syncVoiceGate(availability: AsrAvailability, hasMicrophone: Boolean) {
        voiceHasMicrophone = hasMicrophone
        val gate = VoiceGateUiResolver.resolve(availability, hasMicrophone)
        val snapshot = _state.value
        if (snapshot.voice.visible == gate.entryVisible &&
            snapshot.voice.hiddenReason == gate.hiddenReason
        ) {
            return
        }
        _state.value = snapshot.copy(
            voice = snapshot.voice.copy(visible = gate.entryVisible, hiddenReason = gate.hiddenReason),
        )
        // 入口消失（设置被改掉/没有麦克风）：正在录音/转写的一律取消并清理，不留后台录音。
        if (!gate.entryVisible) cancelVoiceForLifecycle(clearMode = true)
    }

    /**
     * 点主按钮切到语音模式（用户决策：空输入主按钮 = 语音模式入口，**不是**按住即录）。
     *
     * 切模式本身不申请权限、不录音、不花钱；真正开始录音发生在「按住说话」按下时。
     */
    fun onVoiceModeEnter() {
        val snapshot = _state.value
        if (snapshot.isAwakening) return
        if (!snapshot.voice.visible) return
        if (snapshot.voice.mode == ComposerMode.VOICE) return
        // 开始语音交互前先停掉自动朗读（停止条件：TTS 与麦克风不抢音频焦点）。
        container.reader.stop()
        _state.value = snapshot.copy(
            notice = null,
            voice = snapshot.voice.copy(
                mode = ComposerMode.VOICE,
                phase = VoiceUiPhase.IDLE,
                elapsedMs = 0L,
                error = null,
            ),
        )
    }

    /** 点键盘切回普通模式（录音/转写中则先取消并删音频，不让语音结果混进文字模式）。 */
    fun onVoiceModeExit() {
        voiceHoldActive = false
        cancelVoiceForLifecycle(clearMode = true)
        _state.value = _state.value.copy(notice = null)
    }

    /**
     * 按下「按住说话」/ TalkBack 点按开始。
     *
     * 顺序（每道门都有单测，`VoiceStartGate` / `VoicePermissionPolicy`）：
     * 1. 额度预检（按最长 60 秒预估；超限不开始、不产生费用，走聊天页既有「到上限」文案）；
     * 2. 首次费用/隐私说明（`Settings.voiceIntroSeen`，只弹一次；「先不用」也算看过）；
     * 3. 权限：未请求 → App 内说明 → 系统权限框；被拒 → 「去系统设置 / 先打字」；
     * 4. 已授权 → 状态机 `Start(true)` → 录音。
     *
     * 注意：第 2/3 步会盖住手势、用户随后必须松手点按钮，所以说明/授权流程结束时**不自动录音**，
     * 只回到「按住说话」就绪态；录音只由本次真正按住的 `Start(true)` 开始。
     *
     * @param permissionGranted ChatScreen 在点击那一刻现查的 `RECORD_AUDIO` 结果（用户可能刚从设置回来）
     */
    fun onVoicePressStart(permissionGranted: Boolean) {
        if (_state.value.isAwakening) return
        // 05：每次按下先清「松手取消」状态（手势循环随后会逐帧同步真实位置）。
        // 启动窗口内快速上滑也必须能记住 armed，所以不能等 beginVoiceRecording 再清（§7.2 B）。
        resetVoiceSlideCancelArmed()
        // 用户真的按下了：后续异步启动录音前用它判断「按住的这段时间还在不在」。
        voiceHoldActive = true
        if (permissionGranted) voicePermissionState = VoicePermissionState.GRANTED
        viewModelScope.launch {
            val snapshot = _state.value
            if (!snapshot.voice.visible || snapshot.voice.mode != ComposerMode.VOICE) return@launch
            if (snapshot.voice.phase == VoiceUiPhase.RECORDING ||
                snapshot.voice.phase == VoiceUiPhase.TRANSCRIBING
            ) {
                return@launch
            }
            // 系统权限框还开着：第二次按下不再重复走权限流程（不重复弹框、不重复请求）。
            if (voiceMachine.state.value == VoiceInputState.RequestingPermission) return@launch
            if (snapshot.voice.phase == VoiceUiPhase.ERROR) {
                dispatchVoice(VoiceInputEvent.DismissError)
                _state.value = _state.value.copy(voice = _state.value.voice.copy(error = null))
            }
            val settings = runCatching { container.settings.flow.first() }.getOrNull() ?: return@launch
            voiceStartBlockedMessage(settings)?.let { message ->
                _state.value = _state.value.copy(voice = _state.value.voice.copy(error = message))
                return@launch
            }
            if (!settings.voiceIntroSeen) {
                _state.value = _state.value.copy(voice = _state.value.voice.copy(showIntro = true))
                return@launch
            }
            startVoiceForHold(permissionGranted)
        }
    }

    /** 松手：结束录音并上传转写（不足 500 ms 由状态机报太短、不上传）。 */
    fun onVoicePressEnd() {
        resetVoiceSlideCancelArmed()
        voiceHoldActive = false
        if (voiceMachine.state.value != VoiceInputState.Recording) return
        finishVoiceRecording(autoStopped = false)
    }

    /** 上滑取消：松手时丢弃录音（停麦克风 + 删临时文件，不上传、不花钱）。 */
    fun onVoiceCancel() {
        resetVoiceSlideCancelArmed()
        voiceHoldActive = false
        if (voiceMachine.state.value != VoiceInputState.Recording) return
        dispatchVoice(VoiceInputEvent.Cancel)
    }

    /**
     * 上滑「松手取消」状态上报（v0.5.2）。
     *
     * `ChatComponents` 的手势事件循环**同步**调用（按下起逐帧上报，松手时回 false）；
     * ViewModel 是权威状态，[finishVoiceRecording] 收尾时按它判定丢弃（[VoiceFinishPolicy]）。
     * 不做 phase 过滤：录音启动是异步的，按下后「启动窗口内快速上滑」也必须被记住，
     * 否则会显示「松手取消」却仍上传（` B）。
     */
    fun onVoiceSlideCancelArmedChange(armed: Boolean) {
        val snapshot = _state.value
        if (snapshot.voice.slideCancelArmed == armed) return
        _state.value = snapshot.copy(voice = snapshot.voice.copy(slideCancelArmed = armed))
    }

    /** 清掉「松手取消」状态（按下 / 松手 / 取消 / 离开页面 / 发送时都回到未 armed）。 */
    private fun resetVoiceSlideCancelArmed() = onVoiceSlideCancelArmedChange(false)

    /**
     * TalkBack / 开关控制的替代路径：点按开始、再点结束（` 的无障碍要求）。
     *
     * 触摸屏主路径仍是「按住说话」；这里只服务无法按住的无障碍操作。它是唯一允许「点一下就开始录音」
     * 的入口——因为读屏用户没法同时按住和操作按钮，所以做成显式的开始/结束两个动作；
     * 普通触摸点按钮不会走这里（只由 TalkBack / 开关控制触发），不受授权后不自动录音的约束影响。
     */
    fun onVoiceToggleAccessibility(permissionGranted: Boolean) {
        if (_state.value.isAwakening) return
        if (!_state.value.voice.isVoiceMode) return
        when (_state.value.voice.phase) {
            VoiceUiPhase.RECORDING -> onVoicePressEnd()
            VoiceUiPhase.TRANSCRIBING -> Unit
            else -> onVoicePressStart(permissionGranted)
        }
    }

    /**
     * 失败行「再试一次」：清掉错误、回到「按住说话」就绪态，**由用户重新按住**才开始录音。
     *
     * 不在点击时直接开录音：点一下不等于按住（用户没有可松手的目标，也没法松开结束录音），
     * 会重演「没按住却在录 + 60 秒自停后转写计费」（与 是同一类问题，
     * v0.5.1 送检前统一按「必须用户再次按住」处理）。不自动重试的既定口径不变：
     * 这里不发任何转写请求，只有下一次按住才会有新的一次录音。
     */
    fun onVoiceRetry(permissionGranted: Boolean) {
        if (_state.value.isAwakening) return
        dispatchVoice(VoiceInputEvent.DismissError)
        if (permissionGranted) voicePermissionState = VoicePermissionState.GRANTED
        showReadyForHold(VoiceCopy.READY_TO_HOLD)
    }

    /** 失败行「先打字」：回普通模式，清掉语音状态。 */
    fun onVoiceTypeInstead() {
        cancelVoiceForLifecycle(clearMode = true)
    }

    /**
     * 首次费用/隐私说明「开始」：记录已看过，然后继续走权限流程（若有）。
     *
     * **不自动录音**：用户点按钮时已经松手，自动录会变成「没按住却在录」，60 秒自停后还会转写计费。
     * 说明/授权流程结束后只回到「按住说话」就绪态并给一条轻提示，等用户自己再按住
     * （依据 v0.5.1 送检前修复）。
     */
    fun onVoiceIntroConfirm(permissionGranted: Boolean) {
        if (!_state.value.voice.showIntro) return
        voiceHoldActive = false
        viewModelScope.launch { runCatching { container.settings.setVoiceIntroSeen(true) } }
        _state.value = _state.value.copy(voice = _state.value.voice.copy(showIntro = false))
        if (permissionGranted) voicePermissionState = VoicePermissionState.GRANTED
        finishVoiceEntryFlow(permissionGranted)
    }

    /** 首次费用/隐私说明「先不用」：记录已看过，回普通模式；不录音、不花钱。 */
    fun onVoiceIntroDismiss() {
        if (!_state.value.voice.showIntro) return
        viewModelScope.launch { runCatching { container.settings.setVoiceIntroSeen(true) } }
        _state.value = _state.value.copy(voice = _state.value.voice.copy(showIntro = false))
        cancelVoiceForLifecycle(clearMode = true)
    }

    /** 权限前置说明「继续」：状态机产出 `REQUEST_PERMISSION`，由 ChatScreen 去弹系统权限框。 */
    fun onVoicePermissionContinue() {
        if (!_state.value.voice.showPermissionIntro) return
        voicePermissionRequestedBefore = true
        _state.value = _state.value.copy(voice = _state.value.voice.copy(showPermissionIntro = false))
        dispatchVoice(VoiceInputEvent.Start(permissionGranted = false))
    }

    /** 权限前置说明「先不用」：不弹系统框，回普通模式。 */
    fun onVoicePermissionDismiss() {
        if (!_state.value.voice.showPermissionIntro) return
        _state.value = _state.value.copy(voice = _state.value.voice.copy(showPermissionIntro = false))
        cancelVoiceForLifecycle(clearMode = true)
    }

    /**
     * 系统权限框结果（AppRoot 的 launcher 计算，ChatScreen 转过来）。
     *
     * 允许：记住已授权，状态机回 Idle（**不开始录音**），给一条轻提示等用户自己按住。
     * 授权弹窗期间用户早就松手了，自动开录音会变成「没按住却在录」，60 秒自停后还会转写计费
     * （依据 v0.5.1 送检前修复）。
     *
     * 拒绝：不崩、不重复弹系统框，只保留「去系统设置 / 先打字」两条出路。
     */
    fun onVoicePermissionResult(result: VoicePermissionResult) {
        if (result.granted) {
            voicePermissionState = VoicePermissionState.GRANTED
            // 状态机把 RequestingPermission 收到 Idle，不产生 START_RECORDING；录音只由下一次
            // 用户按住产生的 Start(已授权) 触发。
            dispatchVoice(VoiceInputEvent.PermissionResult(granted = true))
            voiceHoldActive = false
            if (_state.value.voice.isVoiceMode) {
                showReadyForHold(VoiceCopy.PERMISSION_GRANTED_READY)
            }
            return
        }
        voicePermissionState = VoicePermissionPolicy.afterSystemResult(
            granted = false,
            rationaleVisible = result.rationaleVisible,
            requestedBefore = voicePermissionRequestedBefore,
        )
        dispatchVoice(VoiceInputEvent.PermissionResult(granted = false))
        _state.value = _state.value.copy(
            voice = _state.value.voice.copy(showPermissionDenied = true),
        )
    }

    /** 系统权限框已经弹过（ChatScreen 回调）：把 signal 归零，避免回到聊天页时重复弹框。 */
    fun onVoicePermissionLaunchConsumed() {
        val snapshot = _state.value
        if (snapshot.voice.permissionLaunchToken == 0) return
        _state.value = snapshot.copy(voice = snapshot.voice.copy(permissionLaunchToken = 0))
    }

    /** 被拒说明「去系统设置」：关弹窗、留在语音模式（用户在系统里开了权限回来就能直接按）。 */
    fun onVoicePermissionDeniedSettings() {
        _state.value = _state.value.copy(voice = _state.value.voice.copy(showPermissionDenied = false))
    }

    /** 被拒说明「先打字」：回普通模式。 */
    fun onVoicePermissionDeniedType() {
        _state.value = _state.value.copy(voice = _state.value.voice.copy(showPermissionDenied = false))
        cancelVoiceForLifecycle(clearMode = true)
    }

    /** 转写文本已在输入框插入（ChatComposer 回调）：清掉待插入内容，避免重复插入。 */
    fun onVoiceTranscriptInserted() {
        val snapshot = _state.value
        if (snapshot.voice.insertText == null) return
        _state.value = snapshot.copy(voice = snapshot.voice.copy(insertText = null))
    }

    /** 退到后台 / 锁屏：取消在途录音与转写并删临时音频（不把录音留在后台）。 */
    fun onVoiceAppBackgrounded() {
        cancelVoiceForLifecycle(clearMode = false, event = VoiceInputEvent.AppBackgrounded)
    }

    /** 离开聊天页：取消在途录音与转写、删临时音频、退出语音模式。 */
    fun onVoicePageLeft() {
        // ChatScreen uses this existing disposal hook because chat ViewModels are cached per role
        // and may outlive the route. A pending image acknowledgement must not send after leaving it.
        filePickerGeneration += 1L
        cancelPendingFileSendAttempt(showUnconfirmedNotice = true)
        invalidateSendAttempt()
        cancelVoiceForLifecycle(clearMode = true)
    }

    // ------------------------------------------------------------ 语音内部执行

    /**
     * 用户按住放行后的最后一步（**只在 [onVoicePressStart] 的按住路径调用**）：
     * 已授权 → 立即录音；未请求 → 先说明再请求；被拒 → 给出路。
     */
    private fun startVoiceForHold(permissionGranted: Boolean) {
        if (permissionGranted) {
            voicePermissionState = VoicePermissionState.GRANTED
            beginVoiceRecording()
            return
        }
        when (voicePermissionState) {
            VoicePermissionState.GRANTED -> beginVoiceRecording()
            VoicePermissionState.NOT_REQUESTED -> showPermissionIntro()
            VoicePermissionState.DENIED, VoicePermissionState.PERMANENTLY_DENIED -> showPermissionDenied()
        }
    }

    /**
     * 首用说明/授权流程走完后的收尾（**绝不录音**）：
     * 已授权 → 回「按住说话」就绪态并提示；未请求 → 弹 App 内权限说明；被拒 → 给出路。
     *
     * 判定本身收在纯函数 [VoiceEntryFlowPolicy]（JVM 可测，且它的返回值里根本没有「开始录音」这一支）。
     * 依据 （用户点「开始/允许」时已松手，自动录音会变成没按住却在录）。
     */
    private fun finishVoiceEntryFlow(permissionGranted: Boolean) {
        when (VoiceEntryFlowPolicy.next(permissionGranted, voicePermissionState)) {
            VoiceEntryFlowNext.READY_TO_HOLD -> showReadyForHold(VoiceCopy.READY_TO_HOLD)
            VoiceEntryFlowNext.ASK_PERMISSION -> showPermissionIntro()
            VoiceEntryFlowNext.PERMISSION_DENIED -> showPermissionDenied()
        }
    }

    /** App 内权限用途说明（系统权限框之前那一步）。 */
    private fun showPermissionIntro() {
        _state.value = _state.value.copy(
            voice = _state.value.voice.copy(showPermissionIntro = true),
        )
    }

    /** 被拒说明（「去系统设置 / 先打字」两条出路）。 */
    private fun showPermissionDenied() {
        _state.value = _state.value.copy(
            voice = _state.value.voice.copy(showPermissionDenied = true),
        )
    }

    /**
     * 回到「语音模式 · 按住说话」就绪态：清录音/错误/权限弹窗，给一条中性轻提示。
     *
     * 与 [beginVoiceRecording] 的差别就是：这里**不产生 START_RECORDING**，等用户自己按住。
     */
    private fun showReadyForHold(notice: String) {
        val snapshot = _state.value
        _state.value = snapshot.copy(
            notice = notice,
            voice = snapshot.voice.copy(
                mode = ComposerMode.VOICE,
                phase = VoiceUiPhase.IDLE,
                elapsedMs = 0L,
                error = null,
                showIntro = false,
                showPermissionIntro = false,
                showPermissionDenied = false,
            ),
        )
    }

    /**
     * 开始录音：再用**最新设置**复算一次门禁 + 额度（说明弹窗停留期间用户可能去改了上限）。
     *
     * v0.5.2 门禁必须与 `asrClient()` 同一口径（DeepSeek + 绑定成立 + 密文可读），
     * 不能只看 `hasChatKey`；脏绑定/密文丢失时在**开始录音前**拦住，不等到转写才落
     * `SERVICE_UNSUPPORTED`。可用性由 [AppContainer.voiceAvailability] 现读（比本函数拿到的
     * 设置快照更新）；`settings` 形参保留给额度判定。
     *
     * @return 被拦住时的人话；null = 放行
     */
    private suspend fun voiceStartBlockedMessage(settings: Settings): String? {
        val availability = container.voiceAvailability()
        val quota = VoiceQuotaGate.check(
            snapshot = container.usage.flow.first(),
            dailyLimitYuanX100 = settings.dailyLimitYuanX100,
            monthlyLimitYuanX100 = settings.monthlyLimitYuanX100,
        )
        return VoiceStartGate.decide(availability, voiceHasMicrophone, quota).blockedMessage
    }

    private fun beginVoiceRecording() {
        viewModelScope.launch {
            val settings = runCatching { container.settings.flow.first() }.getOrNull() ?: return@launch
            voiceStartBlockedMessage(settings)?.let { message ->
                _state.value = _state.value.copy(voice = _state.value.voice.copy(error = message))
                return@launch
            }
            // 异步启动期间用户可能已经松手/取消：这时不能再开录音，否则会变成「没按住却在录」，
            // 到 60 秒自停还会转写计费（同类风险）。回到就绪态等用户再按。
            if (!voiceHoldActive) {
                showReadyForHold(VoiceCopy.READY_TO_HOLD)
                return@launch
            }
            dispatchVoice(VoiceInputEvent.Start(permissionGranted = true))
        }
    }

    /** 派发状态机事件并执行返回的副作用（状态机保持纯函数，Android 副作用都在这里落地）。 */
    private fun dispatchVoice(event: VoiceInputEvent) {
        val effects = voiceMachine.dispatch(event)
        applyVoicePhase()
        effects.forEach { effect -> handleVoiceEffect(effect) }
    }

    private fun applyVoicePhase() {
        val phase = VoicePhaseMapper.phaseOf(voiceMachine.state.value)
        val snapshot = _state.value
        _state.value = snapshot.copy(
            voice = snapshot.voice.copy(
                phase = phase,
                elapsedMs = if (phase == VoiceUiPhase.RECORDING) snapshot.voice.elapsedMs else 0L,
            ),
        )
    }

    private fun handleVoiceEffect(effect: VoiceInputEffect) {
        when (effect) {
            VoiceInputEffect.REQUEST_PERMISSION -> {
                val snapshot = _state.value
                _state.value = snapshot.copy(
                    voice = snapshot.voice.copy(
                        permissionLaunchToken = snapshot.voice.permissionLaunchToken + 1,
                    ),
                )
            }
            VoiceInputEffect.START_RECORDING -> viewModelScope.launch { startVoiceRecording() }
            VoiceInputEffect.CANCEL_RECORDING_DISCARD -> {
                cancelVoiceWatcher()
                runCatching { container.voiceRecorder.cancel() }
                deletePendingVoiceFile()
                voiceFinishStarted = false
            }
            VoiceInputEffect.START_TRANSCRIBE -> {
                voiceTranscribeJob?.cancel()
                voiceTranscribeJob = viewModelScope.launch { transcribeVoiceRecording() }
            }
            VoiceInputEffect.CANCEL_TRANSCRIBE -> {
                voiceTranscribeJob?.cancel()
                voiceTranscribeJob = null
                deletePendingVoiceFile()
            }
        }
    }

    private suspend fun startVoiceRecording() {
        // 松手/取消可能排在 START_RECORDING 副作用之前处理：状态机已离开录音态或手指已抬起时，
        // 不要真的去开麦克风（否则会留下一个没人停的录音，到 60 秒自停）。
        // 依据 同类风险，v0.5.1 送检前修复。
        if (voiceMachine.state.value != VoiceInputState.Recording || !voiceHoldActive) {
            // 状态机可能已经被「收尾/取消」改了，这里不再改状态，只确保麦克风没开。
            return
        }
        voiceFinishStarted = false
        beforeStartRecording()
        when (val started = container.voiceRecorder.start()) {
            VoiceRecorderStart.Started -> {
                watchVoiceRecorder()
                val snapshot = _state.value
                _state.value = snapshot.copy(
                    notice = null,
                    voice = snapshot.voice.copy(
                        phase = VoiceUiPhase.RECORDING,
                        elapsedMs = 0L,
                        error = null,
                    ),
                )
            }
            is VoiceRecorderStart.Failed -> {
                dispatchVoice(VoiceInputEvent.RecordingFailed(started.code))
                setVoiceRecordingError()
            }
        }
    }

    /** 录音计时 + 60 秒自停：录音器到自限会进 STOPPED，这里替它收尾并转写。 */
    private fun watchVoiceRecorder() {
        cancelVoiceWatcher()
        voiceWatchJob = viewModelScope.launch {
            while (isActive) {
                val state = _state.value
                if (state.voice.phase != VoiceUiPhase.RECORDING) break
                if (container.voiceRecorder.state.value.status == VoiceRecorderStatus.STOPPED) {
                    finishVoiceRecording(autoStopped = true)
                    break
                }
                val elapsed = container.voiceRecorder.elapsedMs()
                if (elapsed != state.voice.elapsedMs) {
                    _state.value = state.copy(voice = state.voice.copy(elapsedMs = elapsed))
                }
                delay(VOICE_TICK_MILLIS)
            }
        }
    }

    private fun cancelVoiceWatcher() {
        voiceWatchJob?.cancel()
        voiceWatchJob = null
    }

    /** 收尾一次录音：取结果 → 太短/失败给文案；有效则进入转写（`START_TRANSCRIBE` 副作用）。 */
    private fun finishVoiceRecording(autoStopped: Boolean) {
        if (voiceFinishStarted) return
        if (voiceMachine.state.value != VoiceInputState.Recording) return
        voiceFinishStarted = true
        // 05 收尾判定（` A）：界面已显示「松手取消」时，60 秒自停 /
        // 松手收尾一律丢弃——不上传、不计费、删临时音频；先判 armed 再考虑「到最长了」提示。
        // 无条件按 armed 判定（不只 autoStopped），让松手路径与自停路径同一口径。
        if (VoiceFinishPolicy.shouldDiscard(_state.value.voice.slideCancelArmed)) {
            discardArmedVoiceRecording()
            return
        }
        viewModelScope.launch {
            cancelVoiceWatcher()
            when (val result = container.voiceRecorder.stop()) {
                is VoiceRecorderResult.Recorded -> {
                    voicePendingFile = result.file
                    voicePendingDurationMs = result.durationMs
                    if (autoStopped || result.autoStopped) {
                        _state.value = _state.value.copy(notice = VoiceCopy.RECORDING_AUTO_STOPPED)
                    }
                    dispatchVoice(VoiceInputEvent.RecordingFinished(result.durationMs))
                }
                is VoiceRecorderResult.TooShort -> {
                    dispatchVoice(VoiceInputEvent.RecordingFinished(result.durationMs))
                    _state.value = _state.value.copy(
                        voice = _state.value.voice.copy(error = VoiceCopy.TOO_SHORT),
                    )
                }
                VoiceRecorderResult.Empty -> {
                    dispatchVoice(VoiceInputEvent.RecordingFinished(0L))
                    _state.value = _state.value.copy(
                        voice = _state.value.voice.copy(error = VoiceCopy.TOO_SHORT),
                    )
                }
                is VoiceRecorderResult.Failed -> {
                    dispatchVoice(VoiceInputEvent.RecordingFailed(result.code))
                    setVoiceRecordingError()
                }
            }
        }
    }

    /**
     * 已 armed（界面显示「松手取消」）时丢弃本次录音（v0.5.2）。
     *
     * 删除依赖 `dispatchVoice(Cancel)` 派生的 `CANCEL_RECORDING_DISCARD` 效果：
     * `VoiceRecorder.cancel()` 会停麦克风并删掉已写的临时 WAV（60 秒自停时文件已在录音器手里，
     * 调 `stop()` 取结果再删反而多一次「取到结果」的窗口）。此刻 `voicePendingFile` 还是 null，
     * **不要**在这里误调 `deletePendingVoiceFile()`（` A）。
     */
    private fun discardArmedVoiceRecording() {
        cancelVoiceWatcher()
        dispatchVoice(VoiceInputEvent.Cancel)
        val snapshot = _state.value
        _state.value = snapshot.copy(
            notice = VoiceCopy.CANCELED_NO_UPLOAD,
            voice = snapshot.voice.copy(slideCancelArmed = false),
        )
    }

    /**
     * 上传转写：成功 → 文本进输入框（`insertText`/`insertToken`，**不自动发送**）+ 切回普通模式；
     * 失败 → 留在语音模式给失败人话 + 两条出路（不自动重试）。
     *
     * 隐私：音频只是临时文件，转写结束（无论成败）立刻删；本方法不打日志、不打印转写文本。
     * 记账：优先用服务端返回的 `duration_ms`，缺失/非法时回退**函数开头保存的局部**录音时长
     * `durationMs`（v0.5.2禁止读已清零的 `voicePendingDurationMs`）；只加钱、不加消息条数
     *（`UsageCategory.VOICE`，` 同口径）。
     */
    private suspend fun transcribeVoiceRecording() {
        val file = voicePendingFile
        val durationMs = voicePendingDurationMs
        voicePendingFile = null
        voicePendingDurationMs = 0L
        if (file == null) {
            dispatchVoice(VoiceInputEvent.TranscribeFailed)
            _state.value = _state.value.copy(voice = _state.value.voice.copy(error = VoiceCopy.FAILED))
            return
        }
        val client = try {
            container.asrClient()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // 06 修正：`runCatching` 会把 `settings.flow.first()` 的取消信号一起吞掉，
            // 页面离开就被误报成 SERVICE_UNSUPPORTED 失败。取消必须原样上抛、音频照删
            //（` C「取消不弹失败」）。
            VoiceRecorderFiles.deleteQuietly(file)
            throw cancelled
        } catch (_: Throwable) {
            // 读设置/Keystore 失败：按「这个服务不能转文字」给同一句出路（与既有行为一致）。
            null
        }
        if (client == null) {
            VoiceRecorderFiles.deleteQuietly(file)
            dispatchVoice(VoiceInputEvent.TranscribeFailed)
            _state.value = _state.value.copy(
                voice = _state.value.voice.copy(error = VoiceCopy.SERVICE_UNSUPPORTED),
            )
            return
        }
        // v0.5.5 转写是网络请求，本身可能跨「清空所有数据」。记账代际必须在**请求发起前**
        // 捕获（而不是记账函数入口/协程内部现取），否则「清空前发起的转写、清空后才返回」或
        // 「记账任务排队期间清空」都会把清空前的预估费用写回已清的账本。
        val transcribeWipeEpoch = container.wipeEpoch.value
        val outcome = try {
            client.transcribe(file, durationMs)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // 页面离开/发送时取消转写：音频照删，但不要把「取消」当成失败（会复活已关闭的语音状态）。
            VoiceRecorderFiles.deleteQuietly(file)
            throw cancelled
        } catch (throwable: Throwable) {
            VoiceRecorderFiles.deleteQuietly(file)
            dispatchVoice(VoiceInputEvent.TranscribeFailed)
            _state.value = _state.value.copy(
                voice = _state.value.voice.copy(error = VoiceCopy.transcribeFailure(throwable)),
            )
            return
        }
        VoiceRecorderFiles.deleteQuietly(file)
        recordVoiceUsage(accountedDurationMs(outcome.durationMs, durationMs), transcribeWipeEpoch)
        dispatchVoice(VoiceInputEvent.TranscribeSucceeded)
        if (outcome.text.isBlank()) {
            // 服务商没听出内容：仍然记了这次费用（音频已上传），留在语音模式让用户重说。
            _state.value = _state.value.copy(
                voice = _state.value.voice.copy(error = VoiceCopy.EMPTY_TRANSCRIPT),
            )
            return
        }
        val snapshot = _state.value
        _state.value = snapshot.copy(
            voice = snapshot.voice.copy(
                mode = ComposerMode.TEXT,
                phase = VoiceUiPhase.IDLE,
                elapsedMs = 0L,
                error = null,
                insertText = outcome.text,
                insertToken = snapshot.voice.insertToken + 1,
            ),
        )
    }

    /**
     * 语音记账：按服务端返回的时长估算（`Pricing.asrEstimateYuan`），**只加钱、不加消息条数**。
     *
     * 用 App 级作用域：即使转写刚完成页面就被销毁，这一笔也要落下去（会不会重复记：一次转写只调一次）。
     *
     * **v0.5.5 **：这条 App 级写入可能在「清空所有数据」（含 `UsageStore.clear()`）之后才真正
     * 执行，把清空前的预估费用重新写回统计（证据）。因此块内**写库前**用
     * 发起转写时的代际复核，变了就整笔丢弃。不能在这里现取当前代际——现取永远等于自己。
     *
     * @param startWipeEpoch 发起这次转写时的清空代际（默认当前值，仅为兼容非转写调用）。
     */
    private fun recordVoiceUsage(
        durationMs: Long,
        startWipeEpoch: Long = container.wipeEpoch.value,
    ) {
        val cost = Pricing.asrEstimateYuan(durationMs)
        container.appScope.launch {
            // v0.5.5 调度窗口内可能出现清空；命中就不写，避免旧费用在已清账本里复活。
            if (wipeChangedSince(startWipeEpoch)) return@launch
            runCatching { container.usage.record(cost, 0.0, UsageCategory.VOICE) }
        }
    }

    private fun setVoiceRecordingError() {
        val code = (voiceMachine.state.value as? VoiceInputState.Error)?.code
        _state.value = _state.value.copy(
            voice = _state.value.voice.copy(error = VoiceCopy.recordingError(code)),
        )
    }

    private fun deletePendingVoiceFile() {
        VoiceRecorderFiles.deleteQuietly(voicePendingFile)
        voicePendingFile = null
        voicePendingDurationMs = 0L
    }

    /** 发送新消息时结束语音输入（`SendStarted` 事件口径）：取消录音/转写、删音频、回文字模式。 */
    private fun cancelVoiceForSend() {
        val voice = _state.value.voice
        if (voice.phase == VoiceUiPhase.IDLE && !voice.isVoiceMode && voice.error == null) return
        resetVoiceSlideCancelArmed()
        voiceHoldActive = false
        dispatchVoice(VoiceInputEvent.SendStarted)
        cancelVoiceWatcher()
        voiceTranscribeJob?.cancel()
        voiceTranscribeJob = null
        runCatching { container.voiceRecorder.cancel() }
        deletePendingVoiceFile()
        voiceFinishStarted = false
        val snapshot = _state.value
        _state.value = snapshot.copy(
            voice = snapshot.voice.copy(
                mode = ComposerMode.TEXT,
                phase = VoiceUiPhase.IDLE,
                elapsedMs = 0L,
                error = null,
            ),
        )
    }

    /**
     * 取消一切在途语音操作（离开页面 / 退后台 / 锁屏 / 入口消失 / 切回文字模式）。
     *
     * 复用状态机的 `PageLeft`（录音 → `CANCEL_RECORDING_DISCARD`；转写 → `CANCEL_TRANSCRIBE`），
     * 再补三件状态机管不到的事：计时线程、转写协程、临时文件。
     *
     * @param clearMode true = 同时退出语音模式（页面离开/先打字）；false = 保持当前模式（退后台）
     * @param event 取消原因（PageLeft / AppBackgrounded；状态机对两者同处理，但保留语义便于回溯）
     */
    private fun cancelVoiceForLifecycle(
        clearMode: Boolean,
        event: VoiceInputEvent = VoiceInputEvent.PageLeft,
    ) {
        // 05：离开页面 / 退后台 / 切回文字模式时「松手取消」状态一并失效（不留跨页残留）。
        resetVoiceSlideCancelArmed()
        voiceHoldActive = false
        val before = _state.value.voice
        val nothingInFlight = before.phase == VoiceUiPhase.IDLE &&
            !before.showIntro &&
            !before.showPermissionIntro &&
            !before.showPermissionDenied
        if (nothingInFlight && (!clearMode || before.mode == ComposerMode.TEXT)) {
            // 没有在途状态：只做幂等清理，不改 notice（避免设置同步顺手清掉聊天页的轻提示）。
            runCatching { container.voiceRecorder.cancel() }
            deletePendingVoiceFile()
            return
        }
        dispatchVoice(event)
        cancelVoiceWatcher()
        voiceTranscribeJob?.cancel()
        voiceTranscribeJob = null
        // 幂等兜底：状态机已经回 Idle 时也不允许麦克风还开着、临时 WAV 还留着。
        runCatching { container.voiceRecorder.cancel() }
        deletePendingVoiceFile()
        voiceFinishStarted = false
        val snapshot = _state.value
        _state.value = snapshot.copy(
            notice = null,
            voice = snapshot.voice.copy(
                mode = if (clearMode) ComposerMode.TEXT else snapshot.voice.mode,
                phase = VoiceUiPhase.IDLE,
                elapsedMs = 0L,
                error = null,
                showIntro = false,
                showPermissionIntro = false,
                showPermissionDenied = false,
            ),
        )
    }

    // ---------------------------------------------------------------- 发送

    fun send(onAccepted: (() -> Unit)? = null) {
        val snapshot = _state.value
        if (snapshot.isAwakening) return
        if (snapshot.streaming || snapshot.uploadingFiles || activeSendAttempt != null ||
            activeUserAppendFence != null ||
            activeMessageActionId != null || activeMessageStreamActionId != null
        ) return
        // v0.5.1：发送 = 本轮语音输入结束。录音/转写中一律取消并删音频（状态机 SendStarted 口径）。
        cancelVoiceForSend()
        val text = snapshot.input.trim()
        val readyDrafts = snapshot.drafts.filter { it.status == DraftStatus.READY }
        val readyFiles = snapshot.fileDrafts.filter { it.status == DraftStatus.READY }
        // 准备中：不要发（否则会先发文字、附件下一轮才到，模型对不上）。
        if (DraftRules.isPreparing(snapshot.drafts)) {
            _state.value = snapshot.copy(notice = ImageCopy.SEND_BLOCKED_PREPARING)
            return
        }
        if (FileDraftRules.isPreparing(snapshot.fileDrafts)) {
            _state.value = snapshot.copy(notice = ChatActionCopy.FILE_SEND_BLOCKED_PREPARING)
            return
        }
        if (text.isEmpty() && readyDrafts.isEmpty() && readyFiles.isEmpty()) return
        if (threadId.isEmpty()) return
        // Capture the attempt identity at the synchronous user action, before settings reads or
        // the optional first-image explanation can suspend. Basis: AppContainer.kt:457-495.
        val attempt = SendAttempt(
            id = ++nextSendAttemptId,
            characterId = characterId,
            threadId = threadId,
            wipeEpoch = container.wipeEpoch.value,
            onAccepted = onAccepted,
        )
        activeSendAttempt = attempt
        if (readyFiles.isNotEmpty()) {
            fileSendAttemptId = attempt.id
            // This is also the synchronous UI/method gate while settings and local ownership checks run.
            _state.value = _state.value.copy(
                uploadingFiles = true,
                error = null,
                notice = null,
            )
        }
        // 发送新消息会停止上一轮仍在读取的回复。
        // 放在最前面：即使这轮被花费上限拦住、没真正发出去，也已经不需要继续读了。
        container.reader.stop()

        launchSendPreparation(attempt) {
            if (!isCurrentSendAttempt(attempt)) {
                invalidateSendAttempt(attempt)
                return@launchSendPreparation
            }
            val settings = container.settings.flow.first()
            if (!isCurrentSendAttempt(attempt)) {
                invalidateSendAttempt(attempt)
                return@launchSendPreparation
            }
            // 防御性门禁（v0.5 识图）：设置可能在停留期间被改成"看不了图的模型"。
            if (readyDrafts.isNotEmpty() && settings.imageEntryState() != ImageEntryState.ENABLED) {
                if (isCurrentSendAttempt(attempt)) {
                    _state.value = _state.value.copy(notice = ImageCopy.VISION_MODEL_DISABLED)
                }
                invalidateSendAttempt(attempt)
                return@launchSendPreparation
            }
            if (readyFiles.isNotEmpty() && !settings.isDeepSeekProvider()) {
                if (isCurrentSendAttempt(attempt)) {
                    _state.value = _state.value.copy(notice = ChatActionCopy.FILE_OTHER_PROVIDER)
                }
                invalidateSendAttempt(attempt)
                return@launchSendPreparation
            }
            // 首用说明：**首次带图发送前**弹一次；两个按钮都不阻断发送。
            if (readyDrafts.isNotEmpty() && !settings.imageIntroSeen) {
                pendingImageIntroAttempt = attempt
                _state.value = _state.value.copy(showImageIntro = true)
                return@launchSendPreparation
            }
            sendInternal(settings, attempt)
        }
    }

    /**
     * 真正发起一轮（在 [send] 的门禁/首用说明之后）。
     *
     * @param gateSettings 已经读过的设置快照；null = 自己重新读（首用说明确认后）
     */
    private suspend fun sendInternal(gateSettings: Settings?, attempt: SendAttempt) {
        if (!isCurrentSendAttempt(attempt)) {
            invalidateSendAttempt(attempt)
            return
        }
        val current = gateSettings ?: container.settings.flow.first()
        if (!isCurrentSendAttempt(attempt)) {
            invalidateSendAttempt(attempt)
            return
        }
        val snapshot = _state.value
        val text = snapshot.input.trim()
        val readyDrafts = snapshot.drafts.filter { it.status == DraftStatus.READY }
        val readyFiles = snapshot.fileDrafts.filter { it.status == DraftStatus.READY }
        if (text.isEmpty() && readyDrafts.isEmpty() && readyFiles.isEmpty()) {
            invalidateSendAttempt(attempt)
            return
        }

        // 0) 花费上限（缺陷）：无图也查；带图再查一次
        //    （`「图片附件发送前再检一次」——带图更贵，别在最后一步漏）。
        val firstUsageLimitMessage = usageLimitMessage(current)
        if (!isCurrentSendAttempt(attempt)) {
            invalidateSendAttempt(attempt)
            return
        }
        firstUsageLimitMessage?.let { message ->
            _state.value = _state.value.copy(error = message)
            invalidateSendAttempt(attempt)
            return
        }
        if (readyDrafts.isNotEmpty()) {
            val imageUsageLimitMessage = usageLimitMessage(current)
            if (!isCurrentSendAttempt(attempt)) {
                invalidateSendAttempt(attempt)
                return
            }
            imageUsageLimitMessage?.let { message ->
                _state.value = _state.value.copy(error = message)
                invalidateSendAttempt(attempt)
                return
            }
            // 体积上限 的可触发口径（单图 >2MB / 合计 >8MB），不再用永不触发的 48MiB。
            // 文件大小要读盘：切 IO，别在发送主线程上做文件 IO。
            val sizes = withContext(Dispatchers.IO) {
                readyDrafts.map { container.attachmentStore.sizeOf(it.localPath) }
            }
            if (!isCurrentSendAttempt(attempt)) {
                invalidateSendAttempt(attempt)
                return
            }
            val problem = ImageMath.sendLimitProblem(sizes)
            if (problem != null) {
                _state.value = _state.value.copy(
                    notice = when (problem) {
                        ImageMath.SendLimitProblem.SINGLE_TOO_LARGE -> ImageCopy.SINGLE_TOO_LARGE
                        ImageMath.SendLimitProblem.TOTAL_TOO_LARGE -> ImageCopy.TOTAL_TOO_LARGE
                    },
                )
                invalidateSendAttempt(attempt)
                return
            }
        }
        if (attempt.threadId.isEmpty()) {
            invalidateSendAttempt(attempt)
            return
        }

        if (readyFiles.isNotEmpty()) {
            if (!current.isDeepSeekProvider()) {
                _state.value = _state.value.copy(notice = ChatActionCopy.FILE_OTHER_PROVIDER)
                invalidateSendAttempt(attempt)
                return
            }
            val session = resolveChatEndpoint()
            if (session == null) {
                _state.value = _state.value.copy(error = ChatActionCopy.FILE_NO_KEY)
                invalidateSendAttempt(attempt)
                return
            }
            if (!isDeepSeekEndpoint(session.baseUrl)) {
                _state.value = _state.value.copy(notice = ChatActionCopy.FILE_OTHER_PROVIDER)
                invalidateSendAttempt(attempt)
                return
            }
            if (session.settingsSnapshot?.isDeepSeekProvider() != true) {
                _state.value = _state.value.copy(notice = ChatActionCopy.FILE_SESSION_UNAVAILABLE)
                invalidateSendAttempt(attempt)
                return
            }
            val bindingId = session.remoteCredentialBindingId?.takeIf(String::isNotBlank)
            if (bindingId == null) {
                _state.value = _state.value.copy(notice = ChatActionCopy.FILE_BINDING_UNAVAILABLE)
                invalidateSendAttempt(attempt)
                return
            }
            attempt.fileCredentialBindingId = bindingId
            _state.value = _state.value.copy(uploadingFiles = true, error = null, notice = null)
            val filesClient = FilesClient(container.http, session.baseUrl, session.apiKey)
            for (fileDraft in readyFiles) {
                if (!isCurrentSendAttempt(attempt)) {
                    invalidateSendAttempt(attempt)
                    return
                }
                val attachment = withContext(Dispatchers.IO) {
                    container.database.attachmentDao().byId(fileDraft.id)
                }
                val attachmentIsCurrent = attachment != null &&
                    attachment.deletedAt == null &&
                    attachment.kind == AttachmentKind.FILE &&
                    attachment.status == AttachmentStatus.READY &&
                    attachment.messageId == null &&
                    attachment.localPath == fileDraft.localPath &&
                    (attachment.ownerThreadId == null || attachment.ownerThreadId == attempt.threadId)
                if (!attachmentIsCurrent) {
                    if (isCurrentSendAttempt(attempt)) {
                        _state.value = _state.value.copy(notice = ChatActionCopy.FILE_DRAFT_UNAVAILABLE)
                    }
                    invalidateSendAttempt(attempt)
                    return
                }
                attachment ?: run {
                    invalidateSendAttempt(attempt)
                    return
                }
                if (canReuseRemoteFile(
                        attachment.remoteFileId,
                        attachment.remoteCredentialBindingId,
                        bindingId,
                    )
                ) {
                    replaceFileDraft(fileDraft.id) { it.copy(remoteFileId = attachment.remoteFileId) }
                    continue
                }

                val uploaded = try {
                    val localFile = withContext(Dispatchers.IO) {
                        container.attachmentStore.path(attachment.localPath)
                    }
                    uploadRequestAttemptId = attempt.id
                    val uploadName = attachment.fileName?.takeIf(String::isNotBlank) ?: fileDraft.fileName
                    val uploadPurpose = FilesClient.purposeForFile(uploadName, attachment.mime)
                    val uploadOverride = fileUploaderForTest
                    if (uploadOverride == null) {
                        filesClient.uploadFile(localFile, uploadName, uploadPurpose)
                    } else {
                        uploadOverride(localFile, uploadName, uploadPurpose)
                    }
                } catch (err: Throwable) {
                    if (err is CancellationException) throw err
                    if (isCurrentSendAttempt(attempt)) {
                        _state.value = _state.value.copy(
                            uploadingFiles = false,
                            error = ChatActionCopy.fileUploadFailure(err),
                        )
                    }
                    invalidateSendAttempt(attempt)
                    return
                } finally {
                    if (uploadRequestAttemptId == attempt.id) uploadRequestAttemptId = null
                }

                // Once the server returned an ID, finish the tiny local bind-or-enqueue handoff even if Stop or
                // wipe invalidated the attempt. The DAO rejects deleted drafts; the fallback tombstone is durable.
                val bound = persistUploadedFileReference(
                    attachmentId = attachment.id,
                    uploadedId = uploaded.id,
                    expiresAt = uploaded.expiresAt,
                    bindingId = bindingId,
                )
                if (!bound) {
                    if (isCurrentSendAttempt(attempt)) {
                        _state.value = _state.value.copy(
                            uploadingFiles = false,
                            error = ChatActionCopy.FILE_BIND_SAVE_FAILED,
                        )
                    }
                    invalidateSendAttempt(attempt)
                    return
                }
                if (!isCurrentSendAttempt(attempt)) {
                    invalidateSendAttempt(attempt)
                    return
                }
                replaceFileDraft(fileDraft.id) { it.copy(remoteFileId = uploaded.id) }
            }
            if (!isCurrentSendAttempt(attempt)) {
                invalidateSendAttempt(attempt)
                return
            }
            val latestSession = resolveChatEndpoint()
            val latestBindingId = latestSession?.remoteCredentialBindingId?.takeIf(String::isNotBlank)
            if (latestSession == null ||
                !isDeepSeekEndpoint(latestSession.baseUrl) ||
                latestSession.settingsSnapshot?.isDeepSeekProvider() != true ||
                latestBindingId != bindingId
            ) {
                if (isCurrentSendAttempt(attempt)) {
                    _state.value = _state.value.copy(notice = ChatActionCopy.FILE_BINDING_CHANGED)
                }
                invalidateSendAttempt(attempt)
                return
            }
        }

        // 纯图/纯文件消息也要有可读正文（导出/降级/无附件时）；占位与 ChatEngine 约定一致。
        val content = when {
            text.isNotEmpty() -> text
            readyDrafts.isNotEmpty() -> ImageCopy.IMAGE_PLACEHOLDER
            else -> ChatActionCopy.FILE_PLACEHOLDER
        }
        val imageTokens = cc.pulsez.app.mem.TokenEstimator.estimateImages(readyDrafts.size)
        val imagePaths = DraftRules.readyPaths(readyDrafts)
        val fileNames = readyFiles.map { it.fileName }
        val allDraftIds = readyDrafts.map { it.id } + readyFiles.map { it.id }

        val userLocalId = "local-user-${System.currentTimeMillis()}"
        val userMessage = ChatUiMessage(
            id = userLocalId,
            role = "user",
            text = content,
            imagePaths = imagePaths,
            fileNames = fileNames,
        )
        val assistantId = "local-ai-${System.currentTimeMillis()}"
        // Recheck the identity captured when Send was tapped; never sample the post-wipe epoch here.
        if (!consumeSendAttempt(attempt)) {
            return
        }
        val appendFence = ActiveUserAppendFence(attempt.id, MessageActionFence())
        activeSendTurnAttempt.set(attempt)
        activeUserAppendFence = appendFence
        _state.value = _state.value.copy(
            input = "",
            drafts = emptyList(),
            fileDrafts = emptyList(),
            uploadingFiles = false,
            showImageSource = false,
            error = null,
            notice = null,
            streaming = true,
            messages = _state.value.messages + userMessage + ChatUiMessage(assistantId, "assistant", ""),
        )
        // 依据：当前轮的校验、额度和清空门禁已通过；本地用户消息已随状态一起提交。
        runCatching { attempt.onAccepted?.invoke() }
        streamJob = viewModelScope.launch {
            try {
                runTurn(
                    settings = current,
                    text = content,
                    readyDraftIds = allDraftIds,
                    failedImageDraftIds = readyDrafts.map { it.id },
                    failedFileDrafts = readyFiles,
                    imageCount = readyDrafts.size,
                    imageTokens = imageTokens,
                    imagePaths = imagePaths,
                    userLocalId = userLocalId,
                    assistantId = assistantId,
                    threadId = attempt.threadId,
                    wipeEpoch = attempt.wipeEpoch,
                    attempt = attempt,
                    appendFence = appendFence,
                )
            } finally {
                finishAcceptedSend(attempt, appendFence)
            }
        }
    }

    /** 一轮的完整执行：落库 → 绑附件 → 组装 → 流式 → 落账/索引/维护。 */
    private suspend fun runTurn(
        settings: Settings,
        text: String,
        readyDraftIds: List<String>,
        failedImageDraftIds: List<String>,
        failedFileDrafts: List<DraftFile>,
        imageCount: Int,
        imageTokens: Int,
        imagePaths: List<String>,
        userLocalId: String,
        assistantId: String,
        threadId: String,
        wipeEpoch: Long,
        attempt: SendAttempt,
        appendFence: ActiveUserAppendFence,
    ) {
        // Attempt identity and the captured clear generation are checked before any durable work.
        if (!isCurrentAcceptedSend(attempt) || wipeChangedSince(wipeEpoch)) {
            return
        }
        val initialLatestReminder = if (settings.isDeepSeekProvider()) {
            LatestReminder.format(System.currentTimeMillis())
        } else {
            null
        }
        // 用户消息先落库；返回值 `MessageEntity.id` 要传给索引（chunk.messageIds = 删除传播的来源）。
        // tokens 里把图片按 1024/张保守计入（交接口径：图片占上下文，宁可早交接也别顶爆窗口）。
        val userRow = try {
            // Once Room grants the transaction and the fence enters COMMITTING, Stop waits for
            // this result handoff; if Stop wins before then, the transaction gate rejects it.
            // NonCancellable also prevents a committed result from being lost during dispatcher
            // return. Wipe epoch checks inside the transaction still abort before commit.
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    container.chat.appendUserForTurn(
                        threadId = threadId,
                        segmentId = segmentId,
                        text = text,
                        tokens = text.length / 2 + 4 + imageTokens,
                        latestReminder = initialLatestReminder,
                        canBeginCommit = {
                            appendFence.fence.beginCommit {
                                isCurrentAcceptedSend(attempt) && !wipeChangedSince(wipeEpoch)
                            }
                        },
                        canContinue = {
                            appendFence.fence.canContinue() &&
                                isCurrentAcceptedSend(attempt) && !wipeChangedSince(wipeEpoch)
                        },
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            appendFence.fence.complete()
            throw cancelled
        } catch (_: Throwable) {
            appendFence.fence.complete()
            if (isCurrentAcceptedSend(attempt) && !wipeChangedSince(wipeEpoch)) {
                _state.value = _state.value.copy(
                    drafts = failedImageDraftIds.map { id ->
                        DraftImage(
                            id = id,
                            localPath = imagePaths.getOrNull(failedImageDraftIds.indexOf(id)).orEmpty(),
                            status = DraftStatus.FAILED,
                        )
                    },
                    fileDrafts = failedFileDrafts.map { it.copy(status = DraftStatus.FAILED) },
                )
                fail(assistantId, ChatActionCopy.RETRY_FAILED)
            }
            return
        }
        appendFence.fence.complete()
        if (userRow == null) return
        // No suspension between this epoch/attempt check and ID handoff: a wipe that races after
        // the transaction's final gate clears the committed row in its queued Room transaction.
        if (!isCurrentAcceptedSend(attempt) || wipeChangedSince(wipeEpoch)) return
        attachPersistedUserIds(
            userLocalId = userLocalId,
            userDbId = userRow.id,
            assistantId = assistantId,
        )
        // 草稿绑定到刚落的这条用户消息；若清空先完成，后续状态检查会阻止发请求。
        if (readyDraftIds.isNotEmpty()) {
            val bindError = try {
                withContext(Dispatchers.IO) {
                    container.attachments.bindToMessage(readyDraftIds, userRow.id)
                }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failure
            }
            if (!isCurrentAcceptedSend(attempt) || wipeChangedSince(wipeEpoch)) return
            if (bindError != null) {
                // 附件没绑上就别发这一轮：把草稿放回列表标失败，让用户能重试/删除，而不是静默丢附件。
                _state.value = _state.value.copy(
                    drafts = failedImageDraftIds.map { id ->
                        DraftImage(
                            id = id,
                            localPath = imagePaths.getOrNull(failedImageDraftIds.indexOf(id)).orEmpty(),
                            status = DraftStatus.FAILED,
                            errorCode = (bindError as? AttachmentException)?.code,
                        )
                    },
                    fileDrafts = failedFileDrafts.map { it.copy(status = DraftStatus.FAILED) },
                )
                fail(assistantId, ImageCopy.IMPORT_FAILED, targetUserDbId = userRow.id)
                return
            }
        }
        if (!isCurrentAcceptedSend(attempt) || wipeChangedSince(wipeEpoch)) return
        if (appendFence.stopAfterCommit.get()) {
            _state.value = _state.value.copy(streaming = false)
            return
        }
        if (activeUserAppendFence?.attemptId == attempt.id) activeUserAppendFence = null
        // Pure-image messages count only after the message row and attachment binding both persist.
        // Text-bearing messages already recorded their event atomically in ChatRepository.append.
        if (text.isBlank() && readyDraftIds.isNotEmpty()) {
            container.chat.recordActivityForImageMessage(threadId, userRow.id)
        }
        executeTurnForUserRow(
            settings = settings,
            userRow = userRow,
            imageCount = imageCount,
            assistantId = assistantId,
            threadId = threadId,
            wipeEpoch = wipeEpoch,
            acceptedSendAttempt = attempt,
        )
    }

    /**
     * 针对已落库的 [userRow] 组装上下文并发起流式回复（首轮、失败原地重试、重新生成共用）。
     */
    private suspend fun executeTurnForUserRow(
        settings: Settings,
        userRow: MessageEntity,
        imageCount: Int,
        assistantId: String,
        threadId: String,
        wipeEpoch: Long,
        replaceAssistantDbId: String? = null,
        preservedAssistant: ChatUiMessage? = null,
        messageActionId: Long? = null,
        messageActionFence: MessageActionFence? = null,
        acceptedSendAttempt: SendAttempt? = null,
    ) {
        if (wipeChangedSince(wipeEpoch) ||
            (acceptedSendAttempt != null && !isCurrentAcceptedSend(acceptedSendAttempt))
        ) return
        val text = userRow.content
        // Resolve one credential/settings snapshot for the whole request. It carries the local binding used
        // to filter historical DeepSeek file IDs; never pair a fresh key with settings from another snapshot.
        val session = resolveChatEndpoint()
        val turnSettings = session?.settingsSnapshot
        if (session == null || turnSettings == null) {
            val message = "还没连上 AI。去「设置 → 聊天」填一下？"
            if (preservedAssistant != null) {
                restoreRegeneratedAssistant(assistantId, preservedAssistant, userRow.id, message, messageActionId)
            } else {
                fail(assistantId, message, targetUserDbId = userRow.id)
            }
            clearActiveMessageStream(messageActionId)
            return
        }
        val uploadedFileBindingId = acceptedSendAttempt?.fileCredentialBindingId
        if (uploadedFileBindingId != null &&
            uploadedFileBindingId != session.remoteCredentialBindingId?.takeIf(String::isNotBlank)
        ) {
            if (preservedAssistant != null) {
                restoreRegeneratedAssistant(
                    assistantId,
                    preservedAssistant,
                    userRow.id,
                    ChatActionCopy.FILE_BINDING_CHANGED,
                    messageActionId,
                )
            } else {
                fail(assistantId, ChatActionCopy.FILE_BINDING_CHANGED, targetUserDbId = userRow.id)
            }
            clearActiveMessageStream(messageActionId)
            return
        }
        val client = LlmClient(container.http, session.baseUrl, session.apiKey)
        val thread = container.database.threadDao().byId(threadId)
        if (wipeChangedSince(wipeEpoch) ||
            (acceptedSendAttempt != null && !isCurrentAcceptedSend(acceptedSendAttempt))
        ) return
        if (thread == null) {
            if (preservedAssistant != null) {
                restoreRegeneratedAssistant(
                    assistantId,
                    preservedAssistant,
                    userRow.id,
                    ChatActionCopy.REGENERATE_FAILED,
                    messageActionId,
                )
            } else {
                fail(assistantId, ChatActionCopy.RETRY_FAILED, targetUserDbId = userRow.id)
            }
            clearActiveMessageStream(messageActionId)
            return
        }
        val prepared = try {
            // 把已落库的用户消息 id 传下去：`prepare` 读到的本段历史**已包含这条**，
            // 不排除的话 ⑤ 段会再追加一次；重试/重新生成复用同一 `latestReminder` 保缓存一致。
            container.engine.prepare(
                thread = thread,
                characterId = characterId,
                userText = text,
                excludeMessageId = userRow.id,
                reuseLatestReminder = userRow.latestReminder,
                additionalExcludedMessageIds = replaceAssistantDbId?.let { setOf(it) } ?: emptySet(),
                requestSettings = turnSettings,
                remoteCredentialBindingId = session.remoteCredentialBindingId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (wipeChangedSince(wipeEpoch) ||
            (acceptedSendAttempt != null && !isCurrentAcceptedSend(acceptedSendAttempt))
        ) return
        if (prepared == null) {
            if (preservedAssistant != null) {
                restoreRegeneratedAssistant(
                    assistantId,
                    preservedAssistant,
                    userRow.id,
                    ChatActionCopy.REGENERATE_FAILED,
                    messageActionId,
                )
            } else {
                fail(assistantId, "这轮对话刚刚没连稳，再试一次？", targetUserDbId = userRow.id)
            }
            clearActiveMessageStream(messageActionId)
            return
        }
        val deepSeek = isDeepSeekEndpoint(session.baseUrl)
        val thinking = deepSeek && turnSettings.thinkingEnabled
        val request = ChatRequest(
            model = prepared.model,
            messages = prepared.messages,
            temperature = if (turnSettings.creativeMode) CREATIVE_TEMPERATURE else DEFAULT_TEMPERATURE,
            topP = DEFAULT_TOP_P,
            stream = true,
            // 思考开着要给足输出空间：默认 800 会被思考内容吃光、正文变空；
            // 创作模式非思考放宽到 8,192，让角色能把画面感和剧情展开。
            maxTokens = when {
                thinking -> THINKING_MAX_TOKENS
                turnSettings.creativeMode -> CREATIVE_MAX_TOKENS
                else -> DEFAULT_MAX_TOKENS
            },
            // 思考 / 联网搜索是 DeepSeek 私有能力：只有 DeepSeek 端点才发这些字段，
            // 其它服务商保持"不传"的原行为，避免 400 或假开关。
            thinking = if (deepSeek) thinking else null,
            // 思考强度用设置页的值（低/高/极高，默认低）；只在 thinking=enabled 时下发。
            reasoningEffort = turnSettings.reasoningEffort,
            // 联网搜索与图片**不互斥**；服务端若 400，
            // 失败分支会用 ImageCopy.SEARCH_CONFLICT 提示"可以先把搜索关掉再试"。
            webSearch = deepSeek && turnSettings.webSearchEnabled,
            // DeepSeek 网页阅读：`readUrl = deepSeek && (webSearchEnabled || 消息含 http/https URL)`。
            readUrl = shouldEnableReadUrl(deepSeek, turnSettings.webSearchEnabled, text),
        )

        // Last synchronous guard before collection starts the network request. Main-thread wipe/Stop
        // actions cannot interleave between this check and collect unless a suspension occurs.
        if (wipeChangedSince(wipeEpoch) ||
            (acceptedSendAttempt != null && !isCurrentAcceptedSend(acceptedSendAttempt))
        ) return

        val buffer = StringBuilder()
        // 思考增量单独累积：不进正文。
        val reasoningBuffer = StringBuilder()
        var finished = false
        client.stream(request).collect { event ->
            when (event) {
                is LlmEvent.Delta -> {
                    buffer.append(event.text)
                    updateAssistant(assistantId, ProtocolTag.stripPartial(buffer.toString()))
                }
                is LlmEvent.Reasoning -> {
                    reasoningBuffer.append(event.text)
                    updateAssistantReasoning(assistantId, reasoningBuffer.toString())
                }
                is LlmEvent.ToolProgress -> {
                    updateAssistantToolProgress(assistantId, ChatActionCopy.toolProgressText(event))
                }
                is LlmEvent.Finished -> {
                    finished = true
                    // v0.5.4 第一层收口：流结束时清空已发生 → 这条回复一律丢弃。
                    // 不 append（不往已删 thread 写孤儿行）、不记 usage、不写索引、不朗读、
                    // 不跑后台维护；连 UI 状态也不必再改（AppRoot 已用 wipeToken 换新 VM）。
                    if (wipeChangedSince(wipeEpoch)) {
                        _state.value = _state.value.copy(streaming = false)
                        return@collect
                    }
                    val parsed = ProtocolTag.parse(buffer.toString())
                    var finalText = parsed.text.trim()
                    // 本地危机兜底：不阻断对话，但该轮必须出现"找个人聊聊/专业帮助"
                    val recentUser = _state.value.messages
                        .filter { it.role == "user" }
                        .takeLast(3)
                        .map { it.text }
                        .ifEmpty { listOf(text) }
                    val level = cc.pulsez.app.compliance.CrisisDetector.evaluateRecent(recentUser)
                    // 创作模式下 CRITICAL 用创作版兜底：先认"可能是戏里的台词"，
                    // 再给现实资源；CONCERN 句不受影响；依然**不阻断**（不锁输入、不停服务）。
                    cc.pulsez.app.compliance.CrisisDetector.fallbackLine(
                        level = level,
                        creativeMode = _state.value.creativeMode,
                    )?.let { line ->
                        if (!finalText.contains("找个人聊聊") &&
                            !finalText.contains("联系人") &&
                            !finalText.contains("12356")
                        ) {
                            finalText = finalText.trimEnd() + "\n\n" + line
                        }
                    }
                    val citationsJson = CitationCodec.encode(event.citations)
                    // 正文为空（只有思考/协议标签）时不落库，也就不索引：
                    // 没有 assistant 消息就没有可写的 messageId。
                    if (preservedAssistant != null && finalText.isEmpty()) {
                        restoreRegeneratedAssistant(
                            assistantId,
                            preservedAssistant,
                            userRow.id,
                            ChatActionCopy.REGENERATE_EMPTY,
                            messageActionId,
                        )
                        return@collect
                    }
                    val replacement = if (finalText.isNotEmpty() && !replaceAssistantDbId.isNullOrBlank()) {
                        // Keep the result of an entered Room transaction available for post-commit
                        // cleanup. Stop is fenced before mutation; wipe is checked inside the txn.
                        // Room queues transactions, so a last-moment commit completes before clearAllTables.
                        // NonCancellable protects only this transaction/result handoff; the callbacks
                        // still abort on stop/wipe before commit. Keep it as the sole context element
                        // and switch to IO inside, per Kotlin coroutine guidance.
                        // Coroutine context documentation: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html
                        withContext(NonCancellable) {
                            val committed = withContext(Dispatchers.IO) {
                                container.chat.replaceAssistantReplyWithPropagation(
                                    threadId = threadId,
                                    segmentId = segmentId,
                                    oldMessageId = replaceAssistantDbId,
                                    text = finalText,
                                    tokens = finalText.length / 2 + 4,
                                    emotion = parsed.emotion,
                                    usageIn = event.usage?.promptTokens,
                                    usageOut = event.usage?.completionTokens,
                                    reasoningContent = event.reasoningContent,
                                    serverToolCallsJson = event.serverToolCallsJson,
                                    citationsJson = citationsJson,
                                    canBeginCommit = {
                                        messageActionFence?.beginCommit {
                                            !wipeChangedSince(wipeEpoch)
                                        } ?: true
                                    },
                                    canContinue = {
                                        (messageActionFence?.canContinue() ?: true) &&
                                            !wipeChangedSince(wipeEpoch)
                                    },
                                )
                            }
                            messageActionFence?.complete()
                            committed?.deleted?.let { cleanupCommittedDeletion(it) }
                            committed
                        }
                    } else {
                        null
                    }
                    // The Room write and wipe transaction serialize on the same database. If a
                    // wipe won after our final in-transaction check, never republish this result
                    // into the old ViewModel; the wipe clears the committed row after this txn.
                    if (wipeChangedSince(wipeEpoch)) return@collect
                    if (preservedAssistant != null && replacement == null) {
                        restoreRegeneratedAssistant(
                            assistantId,
                            preservedAssistant,
                            userRow.id,
                            ChatActionCopy.REGENERATE_FAILED,
                            messageActionId,
                        )
                        return@collect
                    }
                    if (replacement != null) {
                        messageActionId?.let(::clearMessageFence)
                        updateAssistant(assistantId, finalText)
                        finishAssistantUi(
                            assistantId = assistantId,
                            dbId = replacement.message.id,
                            citations = event.citations,
                            combinedReasoning = ReasoningRestore.combine(
                                event.reasoningContent,
                                event.serverToolCallsJson,
                            ),
                        )
                        _state.value = _state.value.copy(
                            streaming = false,
                            error = if (event.truncated) "回答到一半断了。已经写出来的还在这里。" else null,
                            notice = if (event.webSearchDropped) WEB_SEARCH_FALLBACK_NOTICE else null,
                        )
                        clearRegenerationRecovery(messageActionId)
                        clearActiveMessageStream(messageActionId)
                    }
                    val assistantRow = if (finalText.isNotEmpty()) {
                        if (replacement != null) {
                            replacement.message
                        } else {
                            updateAssistant(assistantId, finalText)
                            val appendAssistantRow: suspend () -> MessageEntity? = {
                                withContext(Dispatchers.IO) {
                                    container.chat.appendAssistantForTurn(
                                        threadId = threadId,
                                        segmentId = segmentId,
                                        text = finalText,
                                        tokens = finalText.length / 2 + 4,
                                        emotion = parsed.emotion,
                                        usageIn = event.usage?.promptTokens,
                                        usageOut = event.usage?.completionTokens,
                                        reasoningContent = event.reasoningContent,
                                        serverToolCallsJson = event.serverToolCallsJson,
                                        citationsJson = citationsJson,
                                        canBeginCommit = {
                                            messageActionFence?.beginCommit {
                                                !wipeChangedSince(wipeEpoch)
                                            } ?: !wipeChangedSince(wipeEpoch)
                                        },
                                        canContinue = {
                                            (messageActionFence?.canContinue() ?: true) &&
                                                !wipeChangedSince(wipeEpoch)
                                        },
                                    )
                                }
                            }
                            // A fenced message action keeps the transaction result handoff intact;
                            // ordinary sends remain cancellable. The in-transaction epoch gate
                            // decides whether either path may write. NonCancellable is the only
                            // outer context element, with IO switched inside as Kotlin documents.
                            // Coroutine context documentation: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html
                            val appended = if (messageActionFence != null) {
                                withContext(NonCancellable) {
                                    appendAssistantRow().also { messageActionFence.complete() }
                                }
                            } else {
                                appendAssistantRow()
                            }
                            // This check follows the suspending insert. If a wipe changed after the
                            // last in-transaction gate, its queued clear runs after this Room txn.
                            if (wipeChangedSince(wipeEpoch) || appended == null) return@collect
                            appended
                        }
                    } else {
                        null
                    }
                    if (replacement == null) {
                        finishAssistantUi(
                            assistantId = assistantId,
                            dbId = assistantRow?.id,
                            citations = event.citations,
                            combinedReasoning = ReasoningRestore.combine(
                                event.reasoningContent,
                                event.serverToolCallsJson,
                            ),
                        )
                        _state.value = _state.value.copy(
                            streaming = false,
                            error = if (event.truncated) "回答到一半断了。已经写出来的还在这里。" else null,
                            // F2 降级成功的轻提示：这轮本来想联网，服务端不接受，已按普通回答重发成功。
                            notice = if (event.webSearchDropped) WEB_SEARCH_FALLBACK_NOTICE else null,
                        )
                        clearRegenerationRecovery(messageActionId)
                        clearActiveMessageStream(messageActionId)
                    }
                    // 自动朗读只读「刚完成的这条」最终回复，流式过程中不朗读。
                    // 放在正文定稿、危机兜底句追加之后：读到的就是用户看到的全文。
                    maybeAutoRead(finalText)
                    // 记账与后台维护：不阻塞 UI
                    container.appScope.launch {
                        // v0.5.4 第二层收口：从「Finished 处理完」到「本协程真正开始」之间还有
                        // 调度窗口，可能刚好被清空；这里再比对一次代际，命中就整块丢弃
                        // （不记 usage、不写索引、不跑维护——否则用量页会重新出现清空前的花费）。
                        if (wipeChangedSince(wipeEpoch)) return@launch
                        // v0.5：带图时把图片花费拆到「图片」分类（不计消息条数，§3.5）。
                        // 用 `prepared.imageCount`：当轮 + 最近 1 个带图轮的图**每轮都会重新计费**
                        // ，只按当轮 draft 数会低估图片花费。
                        runCatching { container.engine.recordUsage(prepared.model, event.usage, prepared.imageCount) }
                        // v0.5.5 recordUsage 是挂起点（DataStore 写），期间可能完成清空；
                        // 挂起返回后必须用**同一份**代际再复核一次，命中就整块丢弃。
                        // 修复前这里没有复核，且下面的 indexTurn 在 ChatEngine 内部重新取样当前代际
                        // （清空后的新代际），于是清空前的旧正文仍被写成 chunk/chunk_term
                        //（证据）。
                        if (wipeChangedSince(wipeEpoch)) return@launch
                        // 索引一轮对话：chunk.messageIds 写两条消息 id，供删除传播用。
                        // 没有 assistant 消息（正文为空未落库）就跳过索引，避免写出空的 messageIds；
                        // 记账与维护保持原样，不受这轮是否落库影响。
                        assistantRow?.let { aiRow ->
                            runCatching {
                                container.engine.indexTurn(
                                    threadId = threadId,
                                    segmentId = segmentId,
                                    userText = text,
                                    assistantText = finalText,
                                    userMessageId = userRow.id,
                                    assistantMessageId = aiRow.id,
                                    // v0.5.5 把本轮开始时的代际贯穿进引擎，内部所有代际
                                    // 判定都用这一代——不得在引擎里重新取样（否则窗口重新打开）。
                                    expectedWipeEpoch = wipeEpoch,
                                )
                            }
                        }
                        // v0.5.5 maintenance 的两段网络请求（抽取/交接）同样要用本轮代际
                        // 判定；引擎内不重新取样，否则清空后才返回的内容会写回已清空的库。
                        runCatching {
                            container.engine.maintenance(
                                threadId = threadId,
                                characterId = characterId,
                                expectedWipeEpoch = wipeEpoch,
                            )
                        }
                    }
                }
                is LlmEvent.Failed -> {
                    finished = true
                    // DeepSeek 可在检查完整输出后才拒绝；终态到达时撤下此前显示的内容。
                    // DeepSeek refusal handling: https://api-docs.deepseek.com/guides/safety/.
                    val refusedByDeepSeek = deepSeek && event.error.kind == ApiError.Kind.FILTERED
                    // v0.5 识图：内容被服务商拦下 / 带图+联网搜索 400 → 专属人话；
                    // 其余错误保持 `ErrorCopy` 原来的口径。
                    val copy = if (refusedByDeepSeek) {
                        ImageCopy.FILTERED_REPLY
                    } else {
                        ImageCopy.sendFailureOf(
                            error = event.error,
                            hasImages = imageCount > 0,
                            webSearchOn = request.webSearch,
                        ) ?: ErrorCopy.of(event.error)
                    }
                    if (preservedAssistant != null) {
                        restoreRegeneratedAssistant(assistantId, preservedAssistant, userRow.id, copy, messageActionId)
                    } else {
                        fail(
                            assistantId,
                            copy,
                            targetUserDbId = userRow.id,
                            clearRefusedContent = refusedByDeepSeek,
                        )
                    }
                    clearActiveMessageStream(messageActionId)
                }
            }
        }
        if (!finished) {
            if (preservedAssistant != null) {
                restoreRegeneratedAssistant(
                    assistantId,
                    preservedAssistant,
                    userRow.id,
                    ChatActionCopy.REGENERATE_CANCELLED,
                    messageActionId,
                )
            } else {
                _state.value = _state.value.copy(
                    streaming = false,
                    messages = _state.value.messages.map {
                        if (it.id == assistantId) it.copy(toolProgressText = null) else it
                    },
                )
                clearActiveMessageStream(messageActionId)
            }
        }
    }

    private fun restoreRegeneratedAssistant(
        assistantId: String,
        original: ChatUiMessage,
        userDbId: String,
        message: String,
        actionId: Long? = null,
    ) {
        val recovery = regenerationRecovery
        if (actionId != null && recovery != null && recovery.actionId != actionId) return
        if (actionId != null && recovery == null && !ownsMessageAction(actionId)) return
        if (actionId != null) clearMessageFence(actionId)
        clearRegenerationRecovery(actionId)
        clearActiveMessageStream(actionId)
        _state.value = _state.value.copy(
            streaming = false,
            error = message,
            notice = null,
            messages = _state.value.messages.map {
                if (it.id == assistantId) {
                    original.copy(
                        toolProgressText = null,
                        targetUserDbId = original.targetUserDbId ?: userDbId,
                    )
                } else {
                    it
                }
            },
        )
    }

    private fun clearRegenerationRecovery(actionId: Long?) {
        if (actionId != null && regenerationRecovery?.actionId == actionId) {
            regenerationRecovery = null
        }
    }

    private fun clearActiveMessageStream(actionId: Long?) {
        if (actionId != null && activeMessageStreamActionId == actionId) {
            activeMessageStreamActionId = null
        }
        if (actionId != null) clearMessageFence(actionId)
    }

    private fun clearMessageFence(actionId: Long) {
        if (activeMessageFence?.actionId == actionId) activeMessageFence = null
    }

    private fun startMessageActionStream(
        actionId: Long,
        settings: Settings,
        userRow: MessageEntity,
        imageCount: Int,
        assistant: ChatUiMessage,
        wipeEpoch: Long,
        preservedAssistant: ChatUiMessage? = null,
        replaceAssistantDbId: String? = null,
    ): Boolean {
        if (!ownsMessageAction(actionId) || wipeChangedSince(wipeEpoch)) return false
        val messageActionFence = MessageActionFence().also { fence ->
            activeMessageFence = ActiveMessageFence(actionId, fence)
        }
        if (preservedAssistant != null) {
            regenerationRecovery = RegenerationRecovery(
                actionId = actionId,
                assistantId = assistant.id,
                original = preservedAssistant,
                userDbId = userRow.id,
            )
        }
        activeMessageStreamActionId = actionId
        _state.value = _state.value.copy(
            streaming = true,
            error = null,
            notice = null,
            messages = _state.value.messages.map {
                if (it.id == assistant.id) {
                    it.copy(
                        text = "",
                        failed = false,
                        reasoning = null,
                        citations = emptyList(),
                        toolProgressText = null,
                        targetUserDbId = userRow.id,
                    )
                } else {
                    it
                }
            },
        )
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                executeTurnForUserRow(
                    settings = settings,
                    userRow = userRow,
                    imageCount = imageCount,
                    assistantId = assistant.id,
                    threadId = threadId,
                    wipeEpoch = wipeEpoch,
                    replaceAssistantDbId = replaceAssistantDbId,
                    preservedAssistant = preservedAssistant,
                    messageActionId = actionId,
                    messageActionFence = messageActionFence,
                )
            } catch (cancelled: CancellationException) {
                if (activeMessageStreamActionId == actionId && preservedAssistant != null &&
                    regenerationRecovery?.actionId == actionId &&
                    !wipeChangedSince(wipeEpoch)
                ) {
                    restoreRegeneratedAssistant(
                        assistant.id,
                        preservedAssistant,
                        userRow.id,
                        ChatActionCopy.REGENERATE_CANCELLED,
                        actionId,
                    )
                } else if (activeMessageStreamActionId == actionId && !wipeChangedSince(wipeEpoch)) {
                    _state.value = _state.value.copy(streaming = false)
                }
                throw cancelled
            } catch (_: Throwable) {
                if (activeMessageStreamActionId == actionId && !wipeChangedSince(wipeEpoch)) {
                    if (preservedAssistant != null && regenerationRecovery?.actionId == actionId) {
                        restoreRegeneratedAssistant(
                            assistant.id,
                            preservedAssistant,
                            userRow.id,
                            ChatActionCopy.REGENERATE_FAILED,
                            actionId,
                        )
                    } else if (_state.value.streaming) {
                        fail(assistant.id, ChatActionCopy.RETRY_FAILED, targetUserDbId = userRow.id)
                    }
                }
            } finally {
                if (activeMessageStreamActionId == actionId) activeMessageStreamActionId = null
                clearMessageFence(actionId)
            }
        }
        streamJob = job
        if (!transferMessageActionToStream(actionId)) {
            messageActionFence?.cancelBeforeCommit()
            clearMessageFence(actionId)
            clearRegenerationRecovery(actionId)
            clearActiveMessageStream(actionId)
            job.cancel()
            return false
        }
        job.start()
        return true
    }

    private suspend fun cleanupCommittedDeletion(deletion: cc.pulsez.app.data.repo.ChatRepository.MessagePropagationDelete) {
        withContext(NonCancellable + Dispatchers.IO) {
            deletion.localPaths.forEach { path -> runCatching { container.attachmentStore.delete(path) } }
        }
        if (deletion.remoteFileIds.isNotEmpty()) {
            // ChatRepository enqueues these IDs inside the same Room transaction as the soft delete.
            // The coordinator chooses a matching credential binding and rechecks live references before DELETE.
            container.remoteFileCleanupCoordinator.requestRetry()
        }
    }

    /**
     * 失败占位原地重试：不追加新的用户消息，复用原 [userRow] 及其 `latestReminder`。
     */
    fun retryMessage(messageId: String) {
        val snapshot = _state.value
        if (threadId.isEmpty()) return
        val targetIdx = snapshot.messages.indexOfFirst { it.id == messageId || it.dbId == messageId }
        if (targetIdx < 0) return
        val target = snapshot.messages[targetIdx]
        if (!target.failed) return
        val userDbId = resolveTargetUserDbId(snapshot.messages, targetIdx, target)
        if (userDbId.isNullOrBlank()) {
            _state.value = snapshot.copy(error = ChatActionCopy.RETRY_MISSING_USER)
            return
        }
        val actionId = reserveMessageAction(snapshot) ?: return
        val wipeEpoch = container.wipeEpoch.value
        container.reader.stop()
        launchMessageAction(actionId, onFailure = {
            _state.value = _state.value.copy(error = ChatActionCopy.RETRY_FAILED)
        }) {
            val userRow = withContext(Dispatchers.IO) {
                container.database.messageDao().byId(userDbId)?.takeIf {
                    it.deletedAt == null && it.role == "user"
                }
            }
            if (!ownsMessageAction(actionId) || wipeChangedSince(wipeEpoch)) return@launchMessageAction
            if (userRow == null) {
                _state.value = _state.value.copy(error = ChatActionCopy.RETRY_MISSING_USER)
                return@launchMessageAction
            }
            val settings = container.settings.flow.first()
            if (!ownsMessageAction(actionId) || wipeChangedSince(wipeEpoch)) return@launchMessageAction
            val imageCount = withContext(Dispatchers.IO) {
                runCatching {
                    container.attachments.listForMessage(userRow.id).count { it.kind == AttachmentKind.IMAGE }
                }.getOrDefault(0)
            }
            if (!ownsMessageAction(actionId) || wipeChangedSince(wipeEpoch)) return@launchMessageAction
            val firstLimit = usageLimitMessage(settings)
            if (firstLimit != null) {
                _state.value = _state.value.copy(error = firstLimit)
                return@launchMessageAction
            }
            if (imageCount > 0) {
                val imageLimit = usageLimitMessage(settings)
                if (imageLimit != null) {
                    _state.value = _state.value.copy(error = imageLimit)
                    return@launchMessageAction
                }
            }
            startMessageActionStream(
                actionId = actionId,
                settings = settings,
                userRow = userRow,
                imageCount = imageCount,
                assistant = target,
                wipeEpoch = wipeEpoch,
            )
        }
    }

    /**
     * 重新生成最后一条助手回复：先保留旧回复；新回复落库时再原子替换旧行及其派生数据。
     */
    fun regenerateMessage(messageId: String) {
        val snapshot = _state.value
        if (threadId.isEmpty()) return
        val targetIdx = snapshot.messages.indexOfFirst { it.id == messageId || it.dbId == messageId }
        if (targetIdx < 0) return
        val target = snapshot.messages[targetIdx]
        if (!ChatMessageActions.canRegenerate(snapshot.messages, target, snapshot.streaming, snapshot.isAwakening)) {
            return
        }
        if (target.failed) {
            retryMessage(target.id)
            return
        }
        val userDbId = resolveTargetUserDbId(snapshot.messages, targetIdx, target)
        if (userDbId.isNullOrBlank()) {
            _state.value = snapshot.copy(error = ChatActionCopy.RETRY_MISSING_USER)
            return
        }
        val actionId = reserveMessageAction(snapshot) ?: return
        val oldAssistantDbId = effectiveDbId(target)
        val wipeEpoch = container.wipeEpoch.value
        container.reader.stop()
        launchMessageAction(actionId, onFailure = {
            restoreRegeneratedAssistant(
                target.id,
                target,
                userDbId,
                ChatActionCopy.REGENERATE_FAILED,
                actionId,
            )
        }) {
            val userRow = withContext(Dispatchers.IO) {
                container.database.messageDao().byId(userDbId)?.takeIf {
                    it.deletedAt == null && it.role == "user"
                }
            }
            if (!ownsMessageAction(actionId) || wipeChangedSince(wipeEpoch)) return@launchMessageAction
            if (userRow == null) {
                _state.value = _state.value.copy(error = ChatActionCopy.RETRY_MISSING_USER)
                return@launchMessageAction
            }
            val settings = container.settings.flow.first()
            if (!ownsMessageAction(actionId) || wipeChangedSince(wipeEpoch)) return@launchMessageAction
            val imageCount = withContext(Dispatchers.IO) {
                runCatching {
                    container.attachments.listForMessage(userRow.id).count { it.kind == AttachmentKind.IMAGE }
                }.getOrDefault(0)
            }
            if (!ownsMessageAction(actionId) || wipeChangedSince(wipeEpoch)) return@launchMessageAction
            val firstLimit = usageLimitMessage(settings)
            if (firstLimit != null) {
                restoreRegeneratedAssistant(target.id, target, userRow.id, firstLimit, actionId)
                return@launchMessageAction
            }
            if (imageCount > 0) {
                val imageLimit = usageLimitMessage(settings)
                if (imageLimit != null) {
                    restoreRegeneratedAssistant(target.id, target, userRow.id, imageLimit, actionId)
                    return@launchMessageAction
                }
            }
            startMessageActionStream(
                actionId = actionId,
                settings = settings,
                userRow = userRow,
                imageCount = imageCount,
                assistant = target,
                wipeEpoch = wipeEpoch,
                preservedAssistant = target.takeIf { oldAssistantDbId != null },
                replaceAssistantDbId = oldAssistantDbId,
            )
        }
    }

    /**
     * 单条软删消息并传播到 chunk / chunk_term / memory_item / attachment，清理本地文件并 best-effort 删远端文件。
     */
    fun deleteMessage(messageId: String) {
        val snapshot = _state.value
        if (threadId.isEmpty()) return
        val target = snapshot.messages.firstOrNull { it.id == messageId || it.dbId == messageId } ?: return
        val dbId = effectiveDbId(target)
        if (dbId == null) {
            // Local optimistic rows have no DB transaction to protect them. Keep the same
            // synchronous busy gate as persisted message actions so a live send cannot lose its
            // bubble while its append/attachment binding is still in flight.
            if (snapshot.isAwakening || snapshot.streaming || snapshot.uploadingFiles ||
                activeSendAttempt != null || activeUserAppendFence != null ||
                activeMessageActionId != null || activeMessageStreamActionId != null
            ) return
            _state.value = snapshot.copy(
                messages = snapshot.messages.filterNot { it.id == target.id },
                notice = null,
            )
            return
        }
        val actionId = reserveMessageAction(snapshot) ?: return
        val wipeEpoch = container.wipeEpoch.value
        val fence = MessageActionFence()
        activeMessageFence = ActiveMessageFence(actionId, fence)
        container.reader.stop()
        launchMessageAction(actionId, onFailure = {
            _state.value = _state.value.copy(error = ChatActionCopy.DELETE_FAILED)
        }) {
            try {
                if (!ownsMessageAction(actionId) || wipeChangedSince(wipeEpoch)) return@launchMessageAction
                // Match regeneration's fenced transaction/result handoff; never publish UI after a wipe.
                val committed = withContext(NonCancellable) {
                    val deletion = withContext(Dispatchers.IO) {
                        container.chat.softDeleteMessageWithPropagation(
                            threadId = threadId,
                            messageId = dbId,
                            canBeginCommit = { fence.beginCommit { !wipeChangedSince(wipeEpoch) } },
                            canContinue = { fence.canContinue() && !wipeChangedSince(wipeEpoch) },
                        )
                    }
                    fence.complete()
                    if (deletion.deleted) cleanupCommittedDeletion(deletion)
                    deletion
                }
                // A wipe may follow the committed delete before this continuation resumes. The
                // write is durable, but the old ViewModel must not publish stale state afterward.
                if (!ownsMessageAction(actionId) || wipeChangedSince(wipeEpoch)) return@launchMessageAction
                if (!committed.deleted) {
                    _state.value = _state.value.copy(error = ChatActionCopy.DELETE_FAILED)
                    return@launchMessageAction
                }
                _state.value = _state.value.copy(
                    messages = _state.value.messages.filterNot {
                        it.id == target.id || (it.dbId != null && it.dbId == dbId)
                    }.map {
                        if (it.targetUserDbId == dbId) it.copy(targetUserDbId = null) else it
                    },
                    error = null,
                    notice = null,
                )
            } finally {
                fence.complete()
                clearMessageFence(actionId)
            }
        }
    }

    private fun resolveTargetUserDbId(
        messages: List<ChatUiMessage>,
        targetIdx: Int,
        target: ChatUiMessage,
    ): String? {
        target.targetUserDbId?.takeIf { it.isNotBlank() }?.let { return it }
        for (i in (targetIdx - 1) downTo 0) {
            val candidate = messages[i]
            if (candidate.role == "user") {
                return effectiveDbId(candidate)
            }
        }
        return null
    }

    private fun effectiveDbId(message: ChatUiMessage): String? {
        message.dbId?.takeIf { it.isNotBlank() }?.let { return it }
        if (message.failed || message.id.startsWith("local-")) return null
        return message.id.takeIf { it.isNotBlank() }
    }

    private fun attachPersistedUserIds(
        userLocalId: String,
        userDbId: String,
        assistantId: String,
    ) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map {
                when (it.id) {
                    userLocalId -> it.copy(dbId = userDbId)
                    assistantId -> it.copy(targetUserDbId = userDbId)
                    else -> it
                }
            },
        )
    }

    fun stop() {
        cancelPendingFileSendAttempt(showUnconfirmedNotice = true)
        val userAppend = activeUserAppendFence
        if (userAppend != null) {
            if (userAppend.fence.cancelBeforeCommit()) {
                if (activeUserAppendFence?.attemptId == userAppend.attemptId) {
                    activeUserAppendFence = null
                }
            } else if (userAppend.fence.isCommitting()) {
                // The row transaction owns its commit boundary. Let it finish, synchronize the
                // local bubble's DB id, then stop before preparing or sending a model request.
                userAppend.stopAfterCommit.set(true)
                _state.value = _state.value.copy(streaming = false)
                container.reader.stop()
                return
            }
        }
        val fence = activeMessageFence
        if (fence != null && !fence.fence.cancelBeforeCommit()) {
            // The transaction already owns the commit boundary; let it finish and publish its result.
            container.reader.stop()
            return
        }
        if (fence != null) clearMessageFence(fence.actionId)
        invalidateSendAttempt()
        cancelPendingMessageAction()
        activeMessageStreamActionId = null
        streamJob?.cancel()
        streamJob = null
        val recovery = regenerationRecovery
        if (recovery != null) {
            restoreRegeneratedAssistant(
                recovery.assistantId,
                recovery.original,
                recovery.userDbId,
                ChatActionCopy.REGENERATE_CANCELLED,
                recovery.actionId,
            )
        } else {
            _state.value = _state.value.copy(
                streaming = false,
                messages = _state.value.messages.map {
                    if (it.toolProgressText != null) it.copy(toolProgressText = null) else it
                },
            )
        }
        // 停止生成时停止朗读。
        container.reader.stop()
    }

    /**
     * 页面销毁：释放朗读器（官方要求「不再使用 TextToSpeech 时调 shutdown()」，
     * https://developer.android.com/reference/android/speech/tts/TextToSpeech）。
     *
     * 说明：`Reader` 是 `AppContainer` 里的 App 级单例，shutdown 之后下一次 speak/进设置
     * 会重建（见 `Reader.ensureInitialized`），所以 App 内其它页面不会因为这里释放而永久不可用。
     * 切页/切会话的 stop 由 `AppRoot` 负责（ViewModel 按角色缓存，切页不保证 onCleared）。
     *
     * v0.5 识图：顺手清掉缩略图内存缓存（页面销毁释放；`ThumbnailCache` 另有 onTrimMemory）。
     */
    override fun onCleared() {
        filePickerGeneration += 1L
        cancelPendingFileSendAttempt(showUnconfirmedNotice = false)
        invalidateSendAttempt()
        sendPreparationJob?.cancel()
        sendPreparationJob = null
        sendPreparationJobOwnerId = null
        cancelPendingMessageAction()
        activeMessageFence?.fence?.cancelBeforeCommit()
        activeMessageFence = null
        activeMessageStreamActionId = null
        regenerationRecovery = null
        streamJob?.cancel()
        streamJob = null
        container.reader.stop()
        container.reader.shutdown()
        cc.pulsez.app.media.ThumbnailCache.evictAll()
        // v0.5.1 语音：页面销毁时不能在录、不能留转写请求、不能留临时音频。
        dispatchVoice(VoiceInputEvent.PageLeft)
        cancelVoiceWatcher()
        voiceTranscribeJob?.cancel()
        voiceTranscribeJob = null
        runCatching { container.voiceRecorder.cancel() }
        deletePendingVoiceFile()
        super.onCleared()
    }

    /**
     * 开始录音前的接入点（v0.5.1 语音输入用）：录音前必须停读，避免 TTS 和麦克风抢音频焦点。
     *
     * v0.5.0 没有录音入口（语音输入后置），先留这个明确的调用点，别让 v0.5.1 忘掉
     * 的「开始录音 → stop()」。
     */
    fun beforeStartRecording() {
        container.reader.stop()
    }

    private data class LiveMessageAttachments(
        val imagePaths: Map<String, List<String>> = emptyMap(),
        val fileNames: Map<String, List<String>> = emptyMap(),
    )

    /**
     * 读消息 → 本地图片路径与普通文件名（只含活附件、排除失败态）。
     *
     * 图片刻意**不**在这里过滤"文件是否还在"：文件被清/丢失时要把路径交给 UI，
     * 让缩略图位显示「图片已删除」占位。
     */
    private suspend fun liveMessageAttachments(): LiveMessageAttachments = withContext(Dispatchers.IO) {
        runCatching {
            val rows = container.database.attachmentDao().allLive()
                .filter { !it.messageId.isNullOrBlank() && it.status != AttachmentStatus.FAILED }
            val images = mutableMapOf<String, MutableList<String>>()
            val files = mutableMapOf<String, MutableList<String>>()
            rows.forEach { row ->
                val messageId = row.messageId ?: return@forEach
                when (row.kind) {
                    AttachmentKind.IMAGE -> if (row.localPath.isNotBlank()) {
                        images.getOrPut(messageId) { mutableListOf() }.add(row.localPath)
                    }
                    AttachmentKind.FILE -> {
                        val name = row.fileName?.takeIf { it.isNotBlank() } ?: "文件"
                        files.getOrPut(messageId) { mutableListOf() }.add(name)
                    }
                }
            }
            LiveMessageAttachments(imagePaths = images, fileNames = files)
        }.getOrDefault(LiveMessageAttachments())
    }

    private fun MessageEntity.toUiMessage(
        attachments: LiveMessageAttachments,
        importSource: ImportSourceSessionEntity?,
    ): ChatUiMessage = ChatUiMessage(
        id = id,
        dbId = id,
        role = role,
        text = content,
        reasoning = ReasoningRestore.combine(reasoningContent, serverToolCallsJson),
        citations = CitationCodec.decode(citationsJson),
        imagePaths = attachments.imagePaths[id].orEmpty(),
        fileNames = attachments.fileNames[id].orEmpty(),
        sourceSessionId = importSource?.sourceSessionId,
        sourceTitle = importSource?.title,
        sourceStartedAt = importSource?.sourceStartedAt,
    )

    /**
     * 自动朗读一条刚完成的回复（v0.5）。
     *
     * 只在 `LlmEvent.Finished` 里调；「开关关 / 群聊 / 流式未完成 / 空正文」的判定收在
     * [ReadPolicy]（纯函数，可单测）。这里每次重新读一次设置：用户中途进设置改了语速/音色/开关，
     * 下一句立即生效，不缓存。
     *
     * 本 ViewModel 按角色缓存，离开页面/退后台/切到另一个角色后
     * 流仍会跑完并到这里，所以必须过「可见性门」：
     * 1. 先查 [AppContainer.readVisibility] 的快速门——不满足就连设置都不读（离开页面/后台/
     *    切角色后到达的回复直接作废）；
     * 2. 读设置是个挂起点（期间用户可能刚好退出页面/锁屏），所以再把**最新**可见性快照交给
     *    [ReadPolicy.planForFinishedReply]，由纯函数做权威判定（这条接线级规则有单测）。
     * 不在「本角色 1:1 聊天页且前台」时，什么都不读；在的话照旧。
     */
    private suspend fun maybeAutoRead(replyText: String) {
        val visibility = container.readVisibility
        if (!visibility.canAutoRead(characterId)) return
        val latest = runCatching { container.settings.flow.first() }.getOrNull() ?: return
        val plan = ReadPolicy.planForFinishedReply(
            settings = latest,
            replyText = replyText,
            characterId = characterId,
            // 挂起点之后重新取快照：在这期间离开页面/锁屏也要拦住。
            visibility = visibility.snapshot,
        ) ?: return
        container.reader.speak(plan.text, plan.rateX100, plan.voiceId)
    }

    private fun updateAssistant(id: String, text: String) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map {
                if (it.id == id) it.copy(text = text, toolProgressText = null) else it
            },
        )
    }

    /**
     * 思考增量：只更新消息的 reasoning 字段，绝不进正文
     * UI 显示为可折叠内容，并在生成完成时与正文一起保存。
     */
    private fun updateAssistantReasoning(id: String, reasoning: String) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { if (it.id == id) it.copy(reasoning = reasoning) else it },
        )
    }

    private fun updateAssistantToolProgress(id: String, progressText: String) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map {
                if (it.id == id) it.copy(toolProgressText = progressText) else it
            },
        )
    }

    private fun finishAssistantUi(
        assistantId: String,
        dbId: String?,
        citations: List<Citation>,
        combinedReasoning: String?,
    ) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map {
                if (it.id == assistantId) {
                    it.copy(
                        dbId = dbId ?: it.dbId,
                        citations = if (citations.isNotEmpty()) citations else it.citations,
                        reasoning = combinedReasoning ?: it.reasoning,
                        toolProgressText = null,
                    )
                } else {
                    it
                }
            },
        )
    }

    private fun fail(
        assistantId: String,
        message: String,
        targetUserDbId: String? = null,
        clearRefusedContent: Boolean = false,
    ) {
        _state.value = _state.value.copy(
            streaming = false,
            error = message,
            notice = null,
            messages = _state.value.messages.map {
                if (it.id == assistantId) {
                    if (clearRefusedContent) {
                        refusedMessageState(it, targetUserDbId)
                    } else {
                        it.copy(
                            text = REPLY_FAILED_PLACEHOLDER,
                            failed = true,
                            toolProgressText = null,
                            targetUserDbId = targetUserDbId ?: it.targetUserDbId,
                        )
                    }
                } else {
                    it
                }
            },
        )
    }

    /**
     * 发送前的日/月上限检查（缺陷）。
     *
     * 读 `settings.flow` / `usage.flow` 的**当前值**，不缓存：调高上限后下一句立刻能发。
     *
     * @return 超限时给 UI 的提示文案；没超限返回 null
     */
    private suspend fun usageLimitMessage(settings: Settings): String? {
        val snapshot = container.usage.flow.first()
        return UsageLimits.message(
            snapshot = snapshot,
            dailyLimitYuanX100 = settings.dailyLimitYuanX100,
            monthlyLimitYuanX100 = settings.monthlyLimitYuanX100,
        )
    }

    companion object {
        /** 首屏加载条数：与旧实现 `tail(80)` 一致，只是抽成常量供分页判断。 */
        private const val INITIAL_PAGE_SIZE = 80

        /** 「查看更早」每页条数（加载 60 条）。*/
        private const val OLDER_PAGE_SIZE = 60

        /** 默认输出上限（与 [ChatRequest] 默认值一致）。 */
        private const val DEFAULT_MAX_TOKENS = 4_096

        /** 日常非思考模式采样温度（对齐 DeepSeek 官方 General Conversation 推荐值 1.3）。 */
        internal const val DEFAULT_TEMPERATURE = 1.3

        /** 创作非思考模式采样温度（对齐 DeepSeek 官方 Creative Writing / Roleplay 推荐值 1.5）。 */
        internal const val CREATIVE_TEMPERATURE = 1.5

        private val PLACEHOLDER_NAMES = setOf("", "TA", "未命名", "新对话", "未命名角色")

        /** 非思考模式采样 top_p（对齐 DeepSeek 官方默认值）。 */
        private const val DEFAULT_TOP_P = 1.0

        /** 创作模式非思考输出上限：放宽篇幅让角色能充分展开画面感。 */
        private const val CREATIVE_MAX_TOKENS = 8_192

        /** 思考开时的输出上限：800 会被思考内容吃光、正文变空（建议 ≥4000）。*/
        private const val THINKING_MAX_TOKENS = 8_192

        /** 录音计时刷新间隔（只更新文字，不触发 IO；100ms 对秒级计时足够）。 */
        private const val VOICE_TICK_MILLIS = 100L

        /**
         * 联网降级成功的轻提示（给的例句）。
         *
         * 说人话、不出现"工具/参数/字段"，只告诉用户这一轮发生了什么（文案纪律）。
         */
        internal const val WEB_SEARCH_FALLBACK_NOTICE = "这次没能联网，先按普通回答。"

        /**
         * 回复失败时替掉 TA 那条消息正文的占位文案（v0.4.1 文案修复）。
         *
         * 原句「没发出去。」与事实不符：用户自己的消息**已经落库**，失败的是 TA 的回复
         * （` 第 4 条）。改成只说「TA 没回上来」，不让人以为自己的话丢了。
         */
        internal const val REPLY_FAILED_PLACEHOLDER = "TA 没回上来。"

        /** DeepSeek 终态拒绝时丢弃流式前缀和所有随正文展示的派生内容。 */
        internal fun refusedMessageState(
            message: ChatUiMessage,
            targetUserDbId: String? = null,
        ): ChatUiMessage = message.copy(
            dbId = null,
            text = REPLY_FAILED_PLACEHOLDER,
            failed = true,
            reasoning = null,
            citations = emptyList(),
            toolProgressText = null,
            targetUserDbId = targetUserDbId ?: message.targetUserDbId,
        )

        internal const val ENTER_CREATIVE_MODE_NOTICE = "进入创作模式了。"

        /**
         * 「出戏」后的轻提示（v0.4 定稿文案）。
         *
         * 说人话、不用"已退出创作模式"这类系统腔；走 `notice` 通道意味着
         * 中性色小字、不挡消息、不弹窗，用户下一句输入时自动清掉。
         */
        internal const val EXIT_CREATIVE_MODE_NOTICE = "回到平常聊天了。"

        private val URL_REGEX = Regex("https?://", RegexOption.IGNORE_CASE)

        internal fun shouldEnableReadUrl(
            deepSeek: Boolean,
            webSearchEnabled: Boolean,
            userText: String,
        ): Boolean = deepSeek && (webSearchEnabled || URL_REGEX.containsMatchIn(userText))

        fun factory(container: AppContainer, characterId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ChatViewModel(container, characterId) }
            }
    }
}

internal object CitationCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun encode(citations: List<Citation>): String? {
        if (citations.isEmpty()) return null
        val array = buildJsonArray {
            citations.forEach { citation ->
                if (citation.url.isBlank()) return@forEach
                add(
                    buildJsonObject {
                        put("url", citation.url)
                        citation.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                        citation.startIndex?.let { put("startIndex", it) }
                        citation.endIndex?.let { put("endIndex", it) }
                    },
                )
            }
        }
        return array.takeIf { it.isNotEmpty() }?.toString()
    }

    fun decode(rawJson: String?): List<Citation> {
        if (rawJson.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(rawJson) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val startIndex = ((obj["startIndex"] ?: obj["start_index"]) as? JsonPrimitive)?.intOrNull
                ?.takeIf { it >= 0 }
            val endIndex = ((obj["endIndex"] ?: obj["end_index"]) as? JsonPrimitive)?.intOrNull
                ?.takeIf { it >= 0 }
            Citation(
                url = url,
                title = title,
                startIndex = startIndex,
                endIndex = endIndex,
            )
        }
    }
}

internal object ReasoningRestore {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun combine(reasoningContent: String?, serverToolCallsJson: String?): String? {
        val parts = mutableListOf<String>()
        if (!serverToolCallsJson.isNullOrBlank()) {
            val array = runCatching { json.parseToJsonElement(serverToolCallsJson) as? JsonArray }.getOrNull()
            array?.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val stepReasoning = ((obj["reasoning_content"] ?: obj["reasoning"]) as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (stepReasoning != null && stepReasoning !in parts) {
                    parts.add(stepReasoning)
                }
            }
        }
        val finalPart = reasoningContent?.trim()?.takeIf { it.isNotEmpty() }
        if (finalPart != null && parts.lastOrNull() != finalPart) {
            parts.add(finalPart)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }
}
