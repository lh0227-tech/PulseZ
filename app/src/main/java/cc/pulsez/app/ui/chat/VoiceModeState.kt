package cc.pulsez.app.ui.chat

import cc.pulsez.app.audio.AsrAvailability
import cc.pulsez.app.audio.VoiceInputState
import cc.pulsez.app.audio.VoiceQuotaDecision
import cc.pulsez.app.media.DraftImage
import cc.pulsez.app.media.DraftRules
import cc.pulsez.app.ui.copy.VoiceCopy

/**
 * 输入条语音模式与主按钮的纯逻辑（v0.5.1）。
 *
 * 为什么单独一个文件：Compose 页面没法在离线单测里跑，但「什么时候显示麦克风 / 键盘 / 发送 / 停下」
 * 「权限状态对应哪两个出路」「转写文本插到哪」这些判定必须能被 JVM 单测锁死，避免 UI 与发送链路
 * 漂移成两套口径（同 `ComposerPrimaryState` 的既有做法）。
 *
 * 该纯 Kotlin 文件集中管理输入条按钮、权限流程和转写插入规则，便于独立验证：
 * - 空输入时主按钮切换语音模式；语音模式下可切回键盘或按住录音；
 * - 权限拒绝时可转到系统设置或继续打字；
 * - 转写结果插入输入框并保持可编辑，**不自动发送**。
 */

/** 输入条模式（官方：文字模式 ↔ 语音模式，同一位置的主按钮负责切换）。 */
enum class ComposerMode {
    /** 普通文字模式：空输入主按钮 = 语音入口。 */
    TEXT,

    /** 语音模式：第一行换成「按住说话」大按钮，主按钮 = 键盘（切回文字模式）。 */
    VOICE,
}

/** 主按钮画什么（自绘 Canvas：箭头 / 停止方块 / 语音波 / 键盘，不引图标库、不用 emoji）。 */
enum class ComposerMainIcon {
    ARROW_UP,
    STOP,

    /** 语音波（点 + 两段弧 + 外圈；对齐官方 `ic_voice_outline_26` 的语义，几何自绘）。 */
    MIC,

    /** 键盘（对齐官方 `ic_keyboard_outline_26` 的语义，几何自绘）。 */
    KEYBOARD,
}

/** 点下去做什么。 */
enum class ComposerMainAction {
    SEND,
    STOP,

    /** 切到语音模式（不开始录音：用户决策「先切模式，再按住」）。 */
    SWITCH_TO_VOICE,

    /** 切回文字模式。 */
    SWITCH_TO_KEYBOARD,
}

/**
 * 主按钮最终状态。
 *
 * @param contentDescription 读屏名称（发送 / 停下 / 切换至语音 / 切换至键盘）
 */
data class ComposerMainButton(
    val icon: ComposerMainIcon,
    val action: ComposerMainAction,
    val enabled: Boolean,
    val contentDescription: String,
)

/**
 * 主按钮状态解析（PulseZ 落地版）。
 *
 * 判定顺序：
 * 1. 生成中 → 停下（永远可点）；
 * 2. 有字，或草稿里有已就绪的图 → 发送 ↑（可点性沿用 [ComposerPrimary.resolve] 与
 *    `DraftRules.sendBlock` 的同一判定：图片处理中禁用）；
 * 3. 其余（空输入）：
 *    - 语音入口不可用（门禁隐藏）→ v0.5.0 的禁用发送箭头（不出现点不动的麦克风死按钮）；
 *    - 普通模式 → 语音入口（点一下切到语音模式，不是按住即录）；
 *    - 语音模式 → 键盘（点一下切回普通模式）。
 *
 * 说明：v0.5.2 起第二行有独立麦克风入口（`shouldShowVoiceMic`），用户可以**带着已有文字**
 * 进语音模式继续口述，所以语音模式里出现文字不再只有「转写成功」一条路径；第 2 条（有字优先发送）
 * 仍优先于语音模式切换。此时语音模式**没有**键盘主按钮，
 * 由 `ChatComposer` 在第二行同一位置补一个独立的「切换至键盘」48dp 按钮保证用户能切回（不推翻主按钮解析）。
 */
object ComposerMainButtonResolver {

    fun resolve(
        drafts: List<DraftImage>,
        hasText: Boolean,
        streaming: Boolean,
        mode: ComposerMode,
        voiceEntryVisible: Boolean = true,
        frozen: Boolean = false,
    ): ComposerMainButton {
        // 先复用既有发送/停止状态机（含「图片准备中禁用发送」），只改「空输入」这一支。
        val send = ComposerPrimary.resolve(drafts = drafts, hasText = hasText, streaming = streaming)
        if (frozen) {
            return ComposerMainButton(
                icon = ComposerMainIcon.ARROW_UP,
                action = ComposerMainAction.SEND,
                enabled = false,
                contentDescription = send.contentDescription,
            )
        }
        return when {
            send.action == ComposerPrimaryAction.STOP -> ComposerMainButton(
                icon = ComposerMainIcon.STOP,
                action = ComposerMainAction.STOP,
                enabled = true,
                contentDescription = send.contentDescription,
            )
            hasText || DraftRules.hasReady(drafts) -> ComposerMainButton(
                icon = ComposerMainIcon.ARROW_UP,
                action = ComposerMainAction.SEND,
                enabled = send.enabled,
                contentDescription = send.contentDescription,
            )
            // 语音入口被门禁隐藏（非 DeepSeek 端点 / 没聊天密钥 / 没麦克风）时，空输入退回
            // 空输入且语音入口不可用时显示禁用的发送箭头，不显示无法使用的麦克风按钮。
            !voiceEntryVisible -> ComposerMainButton(
                icon = ComposerMainIcon.ARROW_UP,
                action = ComposerMainAction.SEND,
                enabled = false,
                contentDescription = send.contentDescription,
            )
            mode == ComposerMode.VOICE -> ComposerMainButton(
                icon = ComposerMainIcon.KEYBOARD,
                action = ComposerMainAction.SWITCH_TO_KEYBOARD,
                enabled = true,
                contentDescription = VoiceCopy.SWITCH_TO_KEYBOARD,
            )
            else -> ComposerMainButton(
                icon = ComposerMainIcon.MIC,
                action = ComposerMainAction.SWITCH_TO_VOICE,
                enabled = true,
                contentDescription = VoiceCopy.SWITCH_TO_VOICE,
            )
        }
    }
}

/** 语音状态机状态 → UI 阶段（UI 只需要这五支）。 */
enum class VoiceUiPhase {
    /**
     * 空闲 / 待按：大按钮是「按住说话」就绪态，不录音、不计时。
     *
     * 首用说明或系统权限框结束且授权成功后回到这一支；授权不会自动开始录音，
     * 只有用户再次按住才会进入 [RECORDING]。
     */
    IDLE,

    /** 等权限（App 内说明 / 系统权限框 / 被拒说明）。 */
    REQUESTING_PERMISSION,
    RECORDING,
    TRANSCRIBING,
    ERROR,
}

/** [VoiceInputState] → [VoiceUiPhase]。 */
object VoicePhaseMapper {

    fun phaseOf(state: VoiceInputState): VoiceUiPhase = when (state) {
        VoiceInputState.Idle -> VoiceUiPhase.IDLE
        VoiceInputState.PermissionRequired, VoiceInputState.RequestingPermission ->
            VoiceUiPhase.REQUESTING_PERMISSION
        VoiceInputState.Recording -> VoiceUiPhase.RECORDING
        VoiceInputState.Transcribing -> VoiceUiPhase.TRANSCRIBING
        is VoiceInputState.Error -> VoiceUiPhase.ERROR
    }
}

// ---------------------------------------------------------------- 权限

/** 麦克风运行时权限的四种状态（纯状态位，不持有 Android 对象）。 */
enum class VoicePermissionState {
    /** 还没请求过。 */
    NOT_REQUESTED,

    /** 已授权。 */
    GRANTED,

    /** 被拒（还能在系统设置里开；也可能系统已不再弹框）。 */
    DENIED,

    /** 永久拒绝（系统不再弹框，只能去设置）。 */
    PERMANENTLY_DENIED,
}

/**
 * 权限状态对应的 UI 动作。
 *
 * @param requestSystemDialog 可以弹系统权限框（弹之前必须先在 App 内说明）
 * @param showAppIntroFirst 系统框前先展示 App 内用途说明
 * @param showOpenSettings 给「去系统设置」出路
 * @param showTypeInstead 给「先打字」出路
 */
data class VoicePermissionUi(
    val requestSystemDialog: Boolean,
    val showAppIntroFirst: Boolean,
    val showOpenSettings: Boolean,
    val showTypeInstead: Boolean,
)

/**
 * 权限状态映射与结果归档。
 *
 * App 内先说明用途再弹系统权限框；拒绝不崩、不重复弹框，
 * 只给「去系统设置」和「先打字」两条出路（不说「无法使用语音」这种没有出路的话）。
 */
object VoicePermissionPolicy {

    fun resolve(state: VoicePermissionState): VoicePermissionUi = when (state) {
        VoicePermissionState.NOT_REQUESTED -> VoicePermissionUi(
            requestSystemDialog = true,
            showAppIntroFirst = true,
            showOpenSettings = false,
            showTypeInstead = false,
        )
        VoicePermissionState.GRANTED -> VoicePermissionUi(
            requestSystemDialog = false,
            showAppIntroFirst = false,
            showOpenSettings = false,
            showTypeInstead = false,
        )
        VoicePermissionState.DENIED,
        VoicePermissionState.PERMANENTLY_DENIED,
        -> VoicePermissionUi(
            requestSystemDialog = false,
            showAppIntroFirst = false,
            showOpenSettings = true,
            showTypeInstead = true,
        )
    }

    /**
     * 系统权限框回调 → 新状态。
     *
     * 「永久拒绝」的判定：请求过一次、没给、且系统不再展示解释（`shouldShowRequestPermissionRationale`
     * 为 false）。**第一次拒绝时 rationale 也是 false**（Android 的已知行为），所以必须带
     * [requestedBefore] 这个前提，否则会把「第一次拒绝」误判成永久拒绝（两者 UI 出路相同，
     * 但状态语义要准）。
     */
    fun afterSystemResult(
        granted: Boolean,
        rationaleVisible: Boolean,
        requestedBefore: Boolean,
    ): VoicePermissionState = when {
        granted -> VoicePermissionState.GRANTED
        rationaleVisible -> VoicePermissionState.DENIED
        requestedBefore -> VoicePermissionState.PERMANENTLY_DENIED
        else -> VoicePermissionState.DENIED
    }
}

/** 系统权限框的回调结果（AppRoot 的 launcher 计算，ChatScreen 转给 ChatViewModel）。 */
data class VoicePermissionResult(
    val granted: Boolean,
    /** `shouldShowRequestPermissionRationale` 的返回值（false 可能是第一次拒绝，也可能是永久拒绝）。 */
    val rationaleVisible: Boolean,
)

// ---------------------------------------------------------------- 首用/授权流程的收尾

/**
 * 首用费用说明或系统权限框结束后，下一步做什么（纯逻辑，v0.5.1 送检前修复）。
 *
 * 关键：这里**没有「开始录音」这个分支**。用户点「开始 / 允许」时已经松手，说明/授权流程结束时
 * 若自动开录音，会变成「没按住却在录」，到 60 秒自停还会转写并计费
 * 。录音只能由用户下一次按住产生的
 * `Start(permissionGranted = true)` 开始（见 `audio/VoiceInputState.kt`）。
 */
enum class VoiceEntryFlowNext {
    /** 已授权：回「按住说话」就绪态并给一条轻提示（不录音）。 */
    READY_TO_HOLD,

    /** 还没请求过权限：继续弹 App 内用途说明（之后才弹系统权限框）。 */
    ASK_PERMISSION,

    /** 被拒 / 永久拒绝：给「去系统设置 / 先打字」两条出路。 */
    PERMISSION_DENIED,
}

/** 首用/授权流程收尾的判定（每支都有单测，`VoiceModeStateTest`）。 */
object VoiceEntryFlowPolicy {

    fun next(
        permissionGranted: Boolean,
        permissionState: VoicePermissionState,
    ): VoiceEntryFlowNext = when {
        permissionGranted -> VoiceEntryFlowNext.READY_TO_HOLD
        permissionState == VoicePermissionState.GRANTED -> VoiceEntryFlowNext.READY_TO_HOLD
        permissionState == VoicePermissionState.NOT_REQUESTED -> VoiceEntryFlowNext.ASK_PERMISSION
        else -> VoiceEntryFlowNext.PERMISSION_DENIED
    }
}

// ---------------------------------------------------------------- 门禁 / 额度

/** 语音入口为什么被隐藏（不出现死按钮）。 */
enum class VoiceGateHiddenReason {
    /** 非 DeepSeek 端点：`/v1/tools/asr` 是私有形状。*/
    NOT_DEEPSEEK,

    /** DeepSeek 端点但没填聊天密钥。 */
    NO_KEY,

    /** 本机没有麦克风。 */
    NO_MICROPHONE,
}

/** 语音入口三态（显示 / 隐藏原因）。 */
data class VoiceGateUi(
    val entryVisible: Boolean,
    val hiddenReason: VoiceGateHiddenReason?,
)

/**
 * 门禁 → 入口显隐。
 *
 * 依据 `audio/VoiceInputGate`：只有 DeepSeek 官方端点 + 聊天密钥已配置才可用；第三方端点隐藏入口
 * （不造假开关，`）。另外没有麦克风的设备也不显示入口（录音器只会返回
 * `MIC_UNAVAILABLE`，留着就是死按钮）。
 */
object VoiceGateUiResolver {

    fun resolve(availability: AsrAvailability, hasMicrophone: Boolean): VoiceGateUi = when {
        availability == AsrAvailability.NOT_DEEPSEEK ->
            VoiceGateUi(entryVisible = false, hiddenReason = VoiceGateHiddenReason.NOT_DEEPSEEK)
        availability == AsrAvailability.NO_KEY ->
            VoiceGateUi(entryVisible = false, hiddenReason = VoiceGateHiddenReason.NO_KEY)
        !hasMicrophone ->
            VoiceGateUi(entryVisible = false, hiddenReason = VoiceGateHiddenReason.NO_MICROPHONE)
        else -> VoiceGateUi(entryVisible = true, hiddenReason = null)
    }
}

/** 开始录音前的最终判定。 */
data class VoiceStartDecision(
    val allowed: Boolean,
    /** 不允许时给用户的人话；`null` = 允许。 */
    val blockedMessage: String?,
)

/**
 * 按下麦克风前的最后一道门：门禁 → 麦克风 → 额度预检。
 *
 * 顺序理由：先确认这个服务商到底能不能做（能力问题），再确认这台机器能不能录（硬件问题），
 * 最后才看钱够不够（`VoiceQuotaGate` 按最长 60 秒预估）。被拦住时不开始录音、不产生费用。
 */
object VoiceStartGate {

    fun decide(
        availability: AsrAvailability,
        hasMicrophone: Boolean,
        quota: VoiceQuotaDecision,
    ): VoiceStartDecision = when {
        availability == AsrAvailability.NOT_DEEPSEEK ->
            VoiceStartDecision(false, VoiceCopy.OTHER_PROVIDER)
        availability == AsrAvailability.NO_KEY ->
            VoiceStartDecision(false, VoiceCopy.NO_KEY)
        !hasMicrophone ->
            VoiceStartDecision(false, VoiceCopy.UNAVAILABLE_NO_MIC)
        quota is VoiceQuotaDecision.Blocked ->
            VoiceStartDecision(false, VoiceCopy.quotaMessage(quota.reason))
        else -> VoiceStartDecision(true, null)
    }
}

// ---------------------------------------------------------------- 按住手势

/**
 * 「按住说话 + 上滑取消」的手势判定（纯函数，阈值可单测）。
 *
 * 只把「滑多远算取消」抽出来；实际手指事件在 Compose 层（`VoiceHoldToTalkRow`）里读。
 * 阈值取 64dp：拇指自然上滑约 1–2cm（≈40–80dp），太小会误取消、太大会滑不动。
 */
object VoiceHoldGesture {

    /** 上滑取消阈值（dp）。 */
    const val CANCEL_THRESHOLD_DP: Float = 64f

    /** 手指相对按下的位置向上移动超过阈值 → 松手时取消（不转写、不上传、不花钱）。 */
    fun shouldCancel(dragUpPx: Float, thresholdPx: Float): Boolean =
        dragUpPx >= thresholdPx && thresholdPx > 0f
}

// ---------------------------------------------------------------- v0.5.2 语音收尾 / 时长记账

/**
 * 录音收尾时是否丢弃（v0.5.2「上滑取消后 60s 边界仍转写」）。
 *
 * `armed` = 界面已显示「松手取消」的 ViewModel 权威状态（`VoiceUiState.slideCancelArmed`，
 * 由手势事件循环**同步**上报，` B）。收尾判定**无条件**看 armed，
 * 不只 `autoStopped`：这样 60 秒自停与松手路径共用同一口径（§7.2 A）。
 *
 * 抽成纯函数的理由同其它语音判定：`finishVoiceRecording` 依赖它，且必须能被 JVM 单测锁死
 * （真机/Compose 手势没法在离线单测里跑）。
 */
object VoiceFinishPolicy {

    /** armed（已进入松手取消）→ 丢弃本次录音：不上传、不计费、删临时音频。 */
    fun shouldDiscard(armed: Boolean): Boolean = armed
}

/**
 * 第二行独立麦克风入口是否显示（v0.5.2「有文字后无法继续口述」）。
 *
 * 判定口径（`逐字）：
 * `voiceEntryVisible && mode == TEXT && !streaming && hasComposableContent`。
 *
 * - 空输入不显示：空输入时主按钮本身就是麦克风（再叠一个就是两个麦克风，§7.5 禁止）；
 * - 语音模式不显示：此时第一行已是「按住说话」，不需要第二个语音入口；
 * - 生成中不显示：主按钮是「停下」，此时切语音会与流式冲突；
 * - 门禁隐藏（非 DeepSeek / 没密钥 / 没麦克风）不显示：不造假入口。
 *
 * `hasComposableContent` = 输入非空白 **或** 草稿里有已就绪的图（`ChatScreen` 用
 * `input.isNotBlank() || DraftRules.hasReady(drafts)` 算，纯空白输入不算内容）。
 */
fun shouldShowVoiceMic(
    voiceEntryVisible: Boolean,
    mode: ComposerMode,
    streaming: Boolean,
    hasComposableContent: Boolean,
    frozen: Boolean = false,
): Boolean = !frozen && voiceEntryVisible && mode == ComposerMode.TEXT && !streaming && hasComposableContent

/**
 * 语音记账用的时长（v0.5.2「缺服务端时长记成零」）。
 *
 * 服务端返回 `duration_ms` 缺失/非法（≤ 0）时回退本地录音时长；两者都无效才记 0
 * （` E）。调用方必须传**函数开头保存的局部副本**
 * （`transcribeVoiceRecording` 的 `val durationMs`），不能读已清零的 `voicePendingDurationMs`。
 */
fun accountedDurationMs(serverMs: Long, localMs: Long): Long =
    if (serverMs > 0L) serverMs else localMs.coerceAtLeast(0L)

// ---------------------------------------------------------------- 转写文本插入

/** 插入结果：新文本 + 插入后光标位置。 */
data class VoiceTextInsertion(
    val text: String,
    val cursor: Int,
)

/**
 * 把转写文本插到输入框的当前光标处（选中一段就替换它）。
 *
 * 转写结果进输入框、**可编辑、不自动发送**，并插入当前光标处；因此这里只返回文本与光标，
 * 不涉及发送动作。
 *
 * 两个细节：
 * 1. 转写文本先 `trim()`：ASR 常带首尾空白/换行，直接插会让输入框出现空气泡；
 * 2. 中英混排时，若插入点两侧都是英文/数字，补一个空格（`hello` + `world` → `hello world`）；
 *    中文与标点直接拼接（中文不加空格）。
 */
object VoiceTranscriptInsert {

    fun insert(
        current: String,
        selectionStart: Int,
        selectionEnd: Int,
        transcript: String,
    ): VoiceTextInsertion {
        val inserted = transcript.trim()
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, current.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, current.length)
        if (inserted.isEmpty()) return VoiceTextInsertion(current, cursor = start)

        val before = current.substring(0, start)
        val after = current.substring(end)
        val needSpaceBefore = needsAsciiSpace(before.lastOrNull(), inserted.firstOrNull())
        val needSpaceAfter = needsAsciiSpace(inserted.lastOrNull(), after.firstOrNull())
        val prefix = if (needSpaceBefore) " " else ""
        val suffix = if (needSpaceAfter) " " else ""
        val text = before + prefix + inserted + suffix + after
        return VoiceTextInsertion(text = text, cursor = before.length + prefix.length + inserted.length)
    }

    /** 两个相邻字符都是 ASCII 字母/数字时才需要空格（中文、标点不加）。 */
    private fun needsAsciiSpace(left: Char?, right: Char?): Boolean =
        left != null && right != null && left.isAsciiWordChar() && right.isAsciiWordChar()

    private fun Char.isAsciiWordChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}

// ---------------------------------------------------------------- UI 状态 / 回调

/**
 * 聊天页语音输入的全部 UI 状态（放进 `ChatViewModel.UiState.voice`）。
 *
 * 字段含义：
 * - [mode]：文字 / 语音模式；
 * - [phase]：空闲 / 等权限 / 录音 / 转写 / 失败；
 * - [visible]：入口是否出现（门禁 + 麦克风）；
 * - [error]：失败人话（语音模式里显示一行 + 重试/打字）；
 * - [showIntro] / [showPermissionIntro] / [showPermissionDenied]：三个一次性/权限弹窗；
 * - [permissionLaunchToken]：每次 +1 让 UI 去弹一次系统权限框（不把 launcher 放进 ViewModel）；
 * - [insertText] / [insertToken]：转写结果与插入信号（UI 在光标处插入后回清）；
 * - [slideCancelArmed]：v0.5.2 上滑已过阈值、界面显示「松手取消」的权威状态（见 [VoiceFinishPolicy]）。
 */
enum class AwakeningUiPhase {
    NONE,
    WHISPERS,
    FAILED,
    REUNION,
}

data class AwakeningUiState(
    val phase: AwakeningUiPhase = AwakeningUiPhase.NONE,
    val whispers: List<String> = emptyList(),
    val streamCompleted: Boolean = false,
    val characterName: String = "",
    val reunionSubtitle: String = "",
    val errorMessage: String? = null,
) {
    val isAwakening: Boolean get() = phase != AwakeningUiPhase.NONE
}

data class AwakeningUiBridge(
    val uiState: AwakeningUiState = AwakeningUiState(),
    val onRetry: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onStartReunion: () -> Unit = {},
    val onCompleteReunion: () -> Unit = {},
)

const val AWAKENING_FROZEN_PLACEHOLDER: String = "TA 正在回忆你们的过往，马上就好……"

data class VoiceUiState(
    val visible: Boolean = false,
    val hiddenReason: VoiceGateHiddenReason? = null,
    val mode: ComposerMode = ComposerMode.TEXT,
    val phase: VoiceUiPhase = VoiceUiPhase.IDLE,
    val elapsedMs: Long = 0L,
    val error: String? = null,
    val showIntro: Boolean = false,
    val showPermissionIntro: Boolean = false,
    val showPermissionDenied: Boolean = false,
    val permissionLaunchToken: Int = 0,
    val insertText: String? = null,
    val insertToken: Int = 0,
    /**
     * 上滑是否已进入「松手取消」（v0.5.2）。
     *
     * ViewModel 是权威：`ChatComponents` 的手势事件循环**同步**回调
     * `onVoiceSlideCancelArmedChange(Boolean)`（禁止用 `LaunchedEffect`，避免与 100ms 轮询的
     * 帧级竞态，` B）。Compose 里的局部副本只用于即时标签与松手判定。
     */
    val slideCancelArmed: Boolean = false,
    val awakeningBridge: AwakeningUiBridge? = null,
) {
    val isVoiceMode: Boolean get() = mode == ComposerMode.VOICE
}

/**
 * 聊天页 → ViewModel 的语音回调集合。
 *
 * 收到 `permissionGranted` 的几个入口由 ChatScreen 在**点击那一刻**用
 * `Context.checkSelfPermission` 现查（用户可能刚从系统设置回来），不缓存在 ViewModel 里。
 */
class VoiceActions(
    val onModeEnter: () -> Unit = {},
    val onModeExit: () -> Unit = {},
    val onPressStart: (permissionGranted: Boolean) -> Unit = {},
    val onPressEnd: () -> Unit = {},
    val onCancel: () -> Unit = {},
    /**
     * 上滑是否已进入「松手取消」（v0.5.2）：由 `ChatComponents` 手势事件循环**同步**上报，
     * `AppRoot` 的 `VoiceActions` 构造块接线到 `ChatViewModel.onVoiceSlideCancelArmedChange`。
     */
    val onSlideCancelArmedChange: (armed: Boolean) -> Unit = {},
    val onAccessibilityToggle: (permissionGranted: Boolean) -> Unit = {},
    val onRetry: (permissionGranted: Boolean) -> Unit = {},
    val onTypeInstead: () -> Unit = {},
    val onIntroConfirm: (permissionGranted: Boolean) -> Unit = {},
    val onIntroDismiss: () -> Unit = {},
    val onPermissionContinue: () -> Unit = {},
    val onPermissionDismiss: () -> Unit = {},
    val onPermissionDeniedSettings: () -> Unit = {},
    val onPermissionDeniedType: () -> Unit = {},
    val onPermissionResult: (VoicePermissionResult) -> Unit = {},
    val onPermissionResultConsumed: () -> Unit = {},
    val onRequestPermission: () -> Unit = {},
    /** 系统权限框已经弹过：把 token 归零，避免回到聊天页时重复弹框。 */
    val onPermissionLaunchConsumed: () -> Unit = {},
    val onOpenSystemSettings: () -> Unit = {},
    val onTranscriptInserted: () -> Unit = {},
    val onAppBackgrounded: () -> Unit = {},
    val onPageLeft: () -> Unit = {},
)
