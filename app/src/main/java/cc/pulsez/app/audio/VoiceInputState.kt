package cc.pulsez.app.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音输入状态机（v0.5.1 基建层；纯 Kotlin，可与 UI 解耦地 JVM 单测）。
 *
 * 状态流转（「语音按钮怎么不跟发送/停下打架」）：
 *
 * ```
 * IDLE ──Start(已授权)──> RECORDING ──RecordingFinished──> TRANSCRIBING ──成功──> IDLE
 *   │                        │                                  │
 *   │                        ├─ Cancel / PageLeft / SendStarted ─┴──> IDLE（取消、丢弃）
 *   │                        └─ RecordingFailed ──> ERROR ──DismissError──> IDLE
 *   └──Start(未授权)──> REQUESTING ──拒绝──> PERMISSION_REQUIRED
 *                                   └──允许──> IDLE（回到待按，不自动录音）
 * ```
 *
 * 关键约束（+ +）：
 * 1. **TRANSCRIBING 时不能重录**：重复 Start 被忽略（不产生副作用）；
 * 2. **录音中离开页面 / 锁屏 / 开始发送 → 取消并丢弃**（删除临时文件、不插入文本）；
 * 3. **错误后能回 IDLE**：ERROR 是「可关闭的终态」，DismissError 或下一次 Start 都能离开；
 * 4. 转写成功**不自动发送**：状态机只回 IDLE，由 UI 把文字填进输入框（官方参考实现同此）；
 * 5. **授权成功不自动录音**：`PermissionResult(granted = true)` 只回 IDLE。用户点「允许」时
 *    已经松手，此时自动开录音会变成「没按住却在录」，到 60 秒自停还会转写并计费
 *    （` v0.5.1 送检前修复）。录音只能由用户**再次按住**
 *    产生的 `Start(permissionGranted = true)` 触发。
 *
 * 本文件不 import 任何 Android 类，也不持有录音器/网络客户端：副作用以 [VoiceInputEffect]
 * 的形式返回，由下一节点的 UI/ViewModel 执行。
 */
sealed interface VoiceInputState {

    /** 空闲：可以开始录音。 */
    data object Idle : VoiceInputState

    /**
     * 需要先取得麦克风权限（权限被拒 / 系统不再弹框）。
     *
     * UI 应展示一句「不授权也能打字聊天」并给出再次请求/去设置的出路。
     */
    data object PermissionRequired : VoiceInputState

    /** 已经发出系统权限请求，等回调。 */
    data object RequestingPermission : VoiceInputState

    /** 正在录音。 */
    data object Recording : VoiceInputState

    /** 录音结束、正在上传转写；此状态下不能重录。 */
    data object Transcribing : VoiceInputState

    /** 出错了，等 UI 展示后关闭（关闭后回 IDLE）。 */
    data class Error(val code: VoiceInputError) : VoiceInputState
}

/** 语音输入的错误码（文案由 UI 层按码给，本层不写用户可见文案）。 */
enum class VoiceInputError {
    /** 用户拒绝了麦克风权限。 */
    PERMISSION_DENIED,

    /** 有效录音不足 500 ms（误触/没说话）：已丢弃、未上传。 */
    TOO_SHORT,

    /** 本机没有麦克风 / 录音初始化或启动失败。 */
    RECORDING_UNAVAILABLE,

    /** 录音写文件/读取失败。 */
    RECORDING_FAILED,

    /** 上传转写失败（网络/服务端/超时；不自动重试，避免重复计费）。 */
    TRANSCRIBE_FAILED,
}

/** 状态机事件（由 UI/ViewModel 在真实事件发生时派发）。 */
sealed interface VoiceInputEvent {

    /**
     * 用户按下麦克风。
     *
     * @param permissionGranted 调用方先查 `RECORD_AUDIO` 运行时权限的结果；没授权则进入请求流程。
     */
    data class Start(val permissionGranted: Boolean) : VoiceInputEvent

    /**
     * 系统权限回调。
     *
     * **允许只回 IDLE，不产生 `START_RECORDING`**：授权弹窗期间用户已经松手，自动录音会变成
     * 「没按住却在录」（60 秒自停后还会转写计费，`）。录音只由用户再次按住产生的
     * `Start(permissionGranted = true)` 触发；拒绝进 PERMISSION_REQUIRED，可再次请求或去设置。
     */
    data class PermissionResult(val granted: Boolean) : VoiceInputEvent

    /**
     * 录音器停止并给出时长（[VoiceRecorder.stop] 的有效结果）。
     *
     * 低于 [VoiceRecorderConfig.MIN_RECORD_MS] 时状态机判 TOO_SHORT，不进转写。
     */
    data class RecordingFinished(val durationMs: Long) : VoiceInputEvent

    /** 录音失败（录音器返回的明确错误码）。 */
    data class RecordingFailed(val code: VoiceRecorderError) : VoiceInputEvent

    /** 转写成功；UI 负责把文字插入输入框（不自动发送）。 */
    data object TranscribeSucceeded : VoiceInputEvent

    /** 转写失败（不自动重试，由用户重按一次）。 */
    data object TranscribeFailed : VoiceInputEvent

    /** 用户取消（点取消/再点麦克风/上滑取消）。 */
    data object Cancel : VoiceInputEvent

    /** 离开聊天页；录音/转写中的状态一律取消并丢弃结果（不把文字插到别的页面）。 */
    data object PageLeft : VoiceInputEvent

    /** 锁屏/退到后台。与 [PageLeft] 同处理（录音中取消并丢弃）。 */
    data object AppBackgrounded : VoiceInputEvent

    /** 开始发送消息（打字发送或其它发送入口）。录音中取消并丢弃。 */
    data object SendStarted : VoiceInputEvent

    /** 关闭错误提示（UI 展示完调用）。 */
    data object DismissError : VoiceInputEvent
}

/**
 * 状态机要求 UI/ViewModel 执行的副作用。
 *
 * 用枚举而不是回调：状态机保持纯函数，测试可直接断言「这个事件产生了哪些动作」。
 */
enum class VoiceInputEffect {
    /** 弹系统麦克风权限请求。 */
    REQUEST_PERMISSION,

    /** 调 `VoiceRecorder.start()`。 */
    START_RECORDING,

    /** 调 `VoiceRecorder.cancel()`：立即停麦克风并删临时文件（不上传）。 */
    CANCEL_RECORDING_DISCARD,

    /** 取到录音结果后调 `AsrClient.transcribe(file, durationMs)`。 */
    START_TRANSCRIBE,

    /** 取消进行中的转写请求，并丢弃它的结果。 */
    CANCEL_TRANSCRIBE,
}

/** 一次状态迁移的结果。 */
data class VoiceInputTransition(
    val state: VoiceInputState,
    val effects: List<VoiceInputEffect> = emptyList(),
)

/**
 * 纯函数 reducer：`(当前状态, 事件) -> (新状态, 副作用)`。
 *
 * 所有「能不能转」的判定都收在这里；UI 只做两件事：派发事件、执行返回的副作用。
 */
object VoiceInputReducer {

    /** 对当前状态无意义的事件一律忽略：返回原状态、无副作用（不会卡死也不会误触发）。 */
    fun reduce(state: VoiceInputState, event: VoiceInputEvent): VoiceInputTransition {
        return when (event) {
            is VoiceInputEvent.Start -> onStart(state, event.permissionGranted)
            is VoiceInputEvent.PermissionResult -> onPermissionResult(state, event.granted)
            is VoiceInputEvent.RecordingFinished -> onRecordingFinished(state, event.durationMs)
            is VoiceInputEvent.RecordingFailed -> onRecordingFailed(state, event.code)
            VoiceInputEvent.TranscribeSucceeded -> onTranscribeSucceeded(state)
            VoiceInputEvent.TranscribeFailed -> onTranscribeFailed(state)
            VoiceInputEvent.Cancel -> onCancel(state)
            VoiceInputEvent.PageLeft -> onCancel(state)
            VoiceInputEvent.AppBackgrounded -> onCancel(state)
            VoiceInputEvent.SendStarted -> onCancel(state)
            VoiceInputEvent.DismissError -> onDismissError(state)
        }
    }

    private fun onStart(state: VoiceInputState, permissionGranted: Boolean): VoiceInputTransition {
        // 只有空闲/错误/等待授权可开新录音；录音中重复按下、转写中重按都不产生副作用（约束 1）。
        if (state !is VoiceInputState.Idle &&
            state !is VoiceInputState.Error &&
            state !is VoiceInputState.PermissionRequired
        ) {
            return VoiceInputTransition(state)
        }
        return if (permissionGranted) {
            VoiceInputTransition(VoiceInputState.Recording, listOf(VoiceInputEffect.START_RECORDING))
        } else {
            VoiceInputTransition(
                VoiceInputState.RequestingPermission,
                listOf(VoiceInputEffect.REQUEST_PERMISSION),
            )
        }
    }

    private fun onPermissionResult(
        state: VoiceInputState,
        granted: Boolean,
    ): VoiceInputTransition {
        if (state != VoiceInputState.RequestingPermission) return VoiceInputTransition(state)
        return if (granted) {
            // 授权成功只回 IDLE：用户点「允许」时已经松手，不能在这里自动开录音
            // （ 录音必须由用户再次按住产生的 Start(true) 开始）。
            VoiceInputTransition(VoiceInputState.Idle)
        } else {
            // 拒绝不影响其它功能：进入「需要权限」而非错误，用户可再次请求/去设置。
            VoiceInputTransition(VoiceInputState.PermissionRequired)
        }
    }

    private fun onRecordingFinished(
        state: VoiceInputState,
        durationMs: Long,
    ): VoiceInputTransition {
        if (state != VoiceInputState.Recording) return VoiceInputTransition(state)
        // 短于 500 ms：状态机与录音器双保险（录音器已删文件）；这里明确报 TOO_SHORT、不上传。
        if (VoiceRecorderLimits.isTooShort(durationMs)) {
            return VoiceInputTransition(VoiceInputState.Error(VoiceInputError.TOO_SHORT))
        }
        return VoiceInputTransition(
            VoiceInputState.Transcribing,
            listOf(VoiceInputEffect.START_TRANSCRIBE),
        )
    }

    private fun onRecordingFailed(
        state: VoiceInputState,
        code: VoiceRecorderError,
    ): VoiceInputTransition {
        if (state != VoiceInputState.Recording) return VoiceInputTransition(state)
        return VoiceInputTransition(VoiceInputState.Error(code.toVoiceInputError()))
    }

    private fun onTranscribeSucceeded(state: VoiceInputState): VoiceInputTransition {
        if (state != VoiceInputState.Transcribing) return VoiceInputTransition(state)
        // 只回 IDLE；文字由 UI 插入输入框，不自动发送。
        return VoiceInputTransition(VoiceInputState.Idle)
    }

    private fun onTranscribeFailed(state: VoiceInputState): VoiceInputTransition {
        if (state != VoiceInputState.Transcribing) return VoiceInputTransition(state)
        return VoiceInputTransition(VoiceInputState.Error(VoiceInputError.TRANSCRIBE_FAILED))
    }

    private fun onCancel(state: VoiceInputState): VoiceInputTransition = when (state) {
        VoiceInputState.Recording -> VoiceInputTransition(
            VoiceInputState.Idle,
            listOf(VoiceInputEffect.CANCEL_RECORDING_DISCARD),
        )
        VoiceInputState.Transcribing -> VoiceInputTransition(
            VoiceInputState.Idle,
            listOf(VoiceInputEffect.CANCEL_TRANSCRIBE),
        )
        // 权限请求/错误/空闲：直接回空闲，无副作用。
        else -> VoiceInputTransition(VoiceInputState.Idle)
    }

    private fun onDismissError(state: VoiceInputState): VoiceInputTransition =
        if (state is VoiceInputState.Error) VoiceInputTransition(VoiceInputState.Idle)
        else VoiceInputTransition(state)

    /** 录音错误码 → 语音输入错误码（文案由 UI 层按码给）。 */
    fun VoiceRecorderError.toVoiceInputError(): VoiceInputError = when (this) {
        VoiceRecorderError.PERMISSION_DENIED -> VoiceInputError.PERMISSION_DENIED
        VoiceRecorderError.MIC_UNAVAILABLE,
        VoiceRecorderError.INIT_FAILED,
        VoiceRecorderError.START_FAILED,
        VoiceRecorderError.ALREADY_RECORDING,
        VoiceRecorderError.NOT_RECORDING,
        -> VoiceInputError.RECORDING_UNAVAILABLE
        VoiceRecorderError.IO_ERROR -> VoiceInputError.RECORDING_FAILED
    }
}

/**
 * 状态机持有者：把 [VoiceInputReducer] 包成可直接观察的 [StateFlow]。
 *
 * 线程模型：`dispatch` 用 `synchronized` 串行化；副作用以返回值交给调用方执行
 * （不在状态机内部启动协程/调 Android API，方便下一节点决定在哪个 scope 执行）。
 */
class VoiceInputMachine(initialState: VoiceInputState = VoiceInputState.Idle) {

    private val lock = Any()

    private val _state = MutableStateFlow(initialState)

    /** 当前状态；Compose 可直接 `collectAsState()`。 */
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    /** 派发一个事件，返回需要 UI 执行的动作。 */
    fun dispatch(event: VoiceInputEvent): List<VoiceInputEffect> = synchronized(lock) {
        val transition = VoiceInputReducer.reduce(_state.value, event)
        if (transition.state != _state.value) _state.value = transition.state
        transition.effects
    }
}
