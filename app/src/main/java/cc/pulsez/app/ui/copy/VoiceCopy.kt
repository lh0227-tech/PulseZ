package cc.pulsez.app.ui.copy

import cc.pulsez.app.audio.VoiceInputError
import cc.pulsez.app.audio.VoiceQuotaReason
import cc.pulsez.app.audio.VoiceRecorderConfig
import cc.pulsez.app.core.UsageLimits
import cc.pulsez.app.net.ApiError
import cc.pulsez.app.net.ApiException
import cc.pulsez.app.net.AsrRejectReason
import cc.pulsez.app.net.AsrRejectedException
import java.util.Locale

/**
 * 「语音输入（按住说话 → 转成文字）」用户可见文案（v0.5.1）。
 *
 * 文案说明：
 * - 语音支持按住说话、松手结束、上滑取消，也能切回普通输入；
 * - 音频整段发送给用户填写的服务商，按录音时长计费，展示费用预估；
 * - 首次说明讲清用途、音频去向和关闭方法，失败与上限提示提供打字的替代方式；
 * - 音频会发送给服务商，隐私说明必须写清；不承诺服务商不保存；
 * - 转写结果进输入框、可编辑、**不自动发送**；空态主按钮先切模式、再按住。
 *
 * 收口成常量的原因同 `ImageCopy` / `ReadCopy`：文案是产品定稿，纯 JVM 逐字断言 + 禁用词回归
 * （`VoiceCopyTest`），改动必须显式。
 */
internal object VoiceCopy {

    // ------------------------------------------------------------ 模式 / 入口

    /** 普通模式输入框占位。 */
    const val PLACEHOLDER = "说点什么……"

    /**
     * 空输入主按钮的读屏（普通模式 = 切到语音模式）。
     *
     * 逐字对齐官方 APK 的 `switch_to_voice` 标签。按「先切模式再按」的交互，它不是录音键。
     */
    const val SWITCH_TO_VOICE = "切换至语音"

    /** 语音模式主按钮的读屏（点一下切回普通模式）。 */
    const val SWITCH_TO_KEYBOARD = "切换至键盘"

    /**
     * 第二行独立麦克风按钮的读屏（v0.5.2）。
     *
     * 「用语音说话」而不是「切换至语音」：它出现在**已有文字**的输入条上，用户意图是继续口述，
     * 不是换模式本身；读屏念出动作后要能预测结果。
     */
    const val USE_VOICE = "用语音说话"

    // ------------------------------------------------------------ 语音模式大按钮 / 录音中

    /** 语音模式大按钮（读屏与可见文字同一句）。 */
    const val HOLD_TO_TALK = "按住说话"

    /** 录音中可见主文案：松手结束并转文字（PulseZ 不自动发送，所以不写官方「松手发送」）。 */
    const val RECORDING_LABEL = "松手转文字"

    /** 录音中提示：上滑可以取消。 */
    const val SLIDE_CANCEL = "上滑取消"

    /** 上滑超过阈值后松手 = 取消（官方 `record_swiped_up_label`=松手取消）。 */
    const val SLIDE_CANCEL_ARMED = "松手取消"

    /** 转写中（非流式：松手后整段上传）。 */
    const val TRANSCRIBING = "正在转文字……"

    /** 到 60 秒自限自动停、开始转写时的轻提示（不是错误）。 */
    const val RECORDING_AUTO_STOPPED = "到最长了，先转这一段。"

    /**
     * 已上滑到「松手取消」后又到 60 秒自停：丢弃录音、未上传时的轻提示（v0.5.2）。
     *
     * 说清结果（没上传、没花钱），语气中性；不是错误，也不需要「再试一次」按钮。
     */
    const val CANCELED_NO_UPLOAD = "已经取消了，没有上传。"

    /** 读屏替代动作：开始录音（TalkBack 双击 / 失败后「再试一次」都走它）。 */
    const val RECORD_START_ACTION = "开始录音"

    /** 读屏替代动作：结束录音。 */
    const val RECORD_STOP_ACTION = "结束录音"

    /** 录音中整块的读屏描述（可见文字之外补一句状态，避免只有视觉在变）。 */
    const val RECORDING_CONTENT_DESCRIPTION = "正在录音。松手转文字，上滑可以取消"

    // ------------------------------------------------------------ 失败人话（每句都给出路）

    /** 录音不足 500 ms（状态机 TOO_SHORT）：丢弃、不上传、不产生费用。 */
    const val TOO_SHORT = "没听清。说一句长一点的，再试一次？"

    /** 转写成功但文本为空（服务商没听出内容）。 */
    const val EMPTY_TRANSCRIPT = "没听清。再说一次，或者直接打字？"

    /** 本机没有麦克风 / 录音初始化失败。 */
    const val UNAVAILABLE_NO_MIC = "这台手机没有可用的麦克风。打字也行。"

    /** 录音器报错（初始化/写文件失败等）。 */
    const val RECORDING_FAILED = "录音没能开始。再试一次，或者直接打字。"

    /** 当前服务商没有语音转文字能力（本地前置拒绝 / 地址无效）。 */
    const val SERVICE_UNSUPPORTED = "这个服务不能把语音转成文字。先打字吧。"

    /** 没网 / 超时。 */
    const val NO_NETWORK = "没网了，语音没转成。等会儿再试一次？"

    /** 服务商 5xx / 忙。 */
    const val BUSY = "服务商有点忙，语音没转成。再试一次？"

    /** 内容被服务商拦下（`ApiError.Kind.FILTERED`）。 */
    const val FILTERED = "这段语音服务商不收。换个说法，或者直接打字。"

    /** 额度用完 / 被限流。 */
    const val QUOTA = "这个密钥的额度用完了，或者被限流了。先打字吧。"

    /** 密钥不对。 */
    const val AUTH = "密钥好像不对。去设置里改一下，或者先打字。"

    /** 录音文件为空 / 时长非法（本地前置拒绝，未发请求）。 */
    const val EMPTY_AUDIO = "这段录音是空的。再试一次？"

    /** 录音太长（服务商上限；正常不会到，60 秒自限在前面）。 */
    const val TOO_LONG = "这段录音太长了。再录短一点，或者直接打字？"

    /** 兜底：转写失败（不自动重试，避免重复计费）。 */
    const val FAILED = "语音没转成。再试一次，或者直接打字。"

    /** 失败行里的重试按钮。 */
    const val RETRY = "再试一次"

    /** 失败行里的替代出路。 */
    const val TYPE_INSTEAD = "先打字"

    // ------------------------------------------------------------ 权限前置说明（系统权限框之前）

    /** 权限前置说明标题。 */
    const val PERMISSION_INTRO_TITLE = "用麦克风转文字"

    /** 权限前置说明第 1 句：用途 + 音频去哪（用户能答出「发给谁」）。 */
    const val PERMISSION_INTRO_LINE_1 = "录音只用来把你说的话转成文字，音频会发给你填的 AI 服务商。"

    /** 权限前置说明第 2 句：音频去留（本机临时、转完删）。 */
    const val PERMISSION_INTRO_LINE_2 = "转完就把录音删掉，不留在手机里。"

    /** 权限前置说明第 3 句：拒绝也能打字（不给退路的权限说明不合格）。 */
    const val PERMISSION_INTRO_LINE_3 = "不给麦克风权限也能打字聊天。"

    /** 权限前置说明正文，顺序即展示顺序。 */
    val PERMISSION_INTRO_LINES: List<String> = listOf(
        PERMISSION_INTRO_LINE_1,
        PERMISSION_INTRO_LINE_2,
        PERMISSION_INTRO_LINE_3,
    )

    /** 权限前置说明主按钮：继续 → 弹系统权限框。 */
    const val PERMISSION_CONTINUE = "继续"

    /** 权限前置说明次按钮：先不用 → 回普通模式，不弹系统框。 */
    const val PERMISSION_DECLINE = "先不用"

    // ------------------------------------------------------------ 权限被拒（给两条出路）

    /** 被拒弹窗标题。 */
    const val PERMISSION_DENIED_TITLE = "打不开麦克风"

    /** 被拒说明：事实 + 两条出路（去系统设置 / 先打字），不说「无法使用语音」这种没出路的话。 */
    const val PERMISSION_DENIED = "没拿到麦克风权限。可以到系统设置里开，也可以先打字。"

    /** 被拒弹窗主按钮：去系统设置。 */
    const val OPEN_SETTINGS = "去系统设置"

    // ------------------------------------------------------------ 首次使用费用 / 隐私说明

    /** 首次说明标题：说清这是什么。 */
    const val INTRO_TITLE = "用语音说话"

    /** 第 1 句：是什么 + 结果去哪 + **不自动发送**（用户决策）。 */
    const val INTRO_LINE_1 = "说出来的话会转成文字，放进输入框；你可以改完再发。"

    /** 第 2 句：说明发给谁，以及费用由谁承担。 */
    const val INTRO_LINE_2 = "录音会发给你填的 AI 服务商转文字，费用从你自己的账户扣。"

    /** 第 3 句：大概多少钱（按录音时长；10 秒 ≈ ¥0.0023 → 「十秒不到一分钱」），标预估。 */
    const val INTRO_LINE_3 = "按录音时长算，十秒不到一分钱；这是预估，以服务商账单为准。"

    /** 第 4 句：音频去留（本机临时、转完删）。 */
    const val INTRO_LINE_4 = "录音只临时存在手机里，转完就删。"

    /** 第 5 句：不用怎样 / 怎么关（不点麦克风就行）。 */
    const val INTRO_LINE_5 = "不想用就不点麦克风，打字也能照常聊天。"

    /** 首次说明正文五行，顺序即展示顺序（测试断言恰好五行）。 */
    val INTRO_LINES: List<String> = listOf(
        INTRO_LINE_1,
        INTRO_LINE_2,
        INTRO_LINE_3,
        INTRO_LINE_4,
        INTRO_LINE_5,
    )

    /** 首次说明主按钮：开始（继续去请求权限，**不自动开始录音**，等用户自己按住）。 */
    const val INTRO_CONFIRM = "开始"

    /** 首次说明次按钮：先不用（只记录看过，回普通模式，不录音、不花钱）。 */
    const val INTRO_DISMISS = "先不用"

    // ------------------------------------------------------------ 授权/说明流程结束（回到「按住说话」就绪态）

    /**
     * 系统权限框点「允许」后的轻提示。
     *
     * 授权成功**不自动录音**（用户点「允许」时早就松手了），提示用户自己再按住。
     */
    const val PERMISSION_GRANTED_READY = "权限好了。按住说话就能开始。"

    /** 首用说明看完、或失败后点「再试一次」后的轻提示（同样不自动录音，等用户自己按住）。 */
    const val READY_TO_HOLD = "按住说话就能开始。"

    // ------------------------------------------------------------ 门禁 / 上限

    /**
     * 非 DeepSeek 端点时的说明（入口隐藏；设置页说明沿用识图同一句口径）。
     *
     * 依据：`/v1/tools/asr` 是 DeepSeek 私有形状，第三方
     * OpenAI 兼容服务商没有这个端点。
     */
    const val OTHER_PROVIDER = "语音输入只对 DeepSeek 的地址有效。"

    /** DeepSeek 端点但还没填聊天密钥时的说明（入口隐藏，引导去设置填密钥）。 */
    const val NO_KEY = "先填上聊天密钥，才能用语音输入。"

    // ------------------------------------------------------------ 用量页

    /**
     * 用量页「其中语音」小项（与「其中图片」同构；只在 > 0 时显示）。
     *
     * 数值按服务端返回的音频时长估算，并标明「预估，以服务商账单为准」。
     */
    fun voiceUsageLine(yuan: Double): String =
        "其中语音 ¥%.4f（预估，以服务商账单为准）".format(Locale.US, yuan)

    // ------------------------------------------------------------ 录音计时（纯格式化）

    /** 录音计时显示（0:00–1:00，分:秒）。 */
    fun elapsedLabel(elapsedMs: Long): String {
        val seconds = (elapsedMs.coerceIn(0L, VoiceRecorderConfig.MAX_RECORD_MS) / 1000L).toInt()
        return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
    }

    /**
     * 剩余时间的倒数提示（只剩 10 秒内才提醒；其它时候返回 null，不占一条常驻文案）。
     *
     * 录音有时长上限；这里提示还剩几秒，不照搬其他应用的文案。
     */
    fun secondsLeftHint(elapsedMs: Long): String? {
        val remainingMs = VoiceRecorderConfig.MAX_RECORD_MS - elapsedMs.coerceIn(0L, VoiceRecorderConfig.MAX_RECORD_MS)
        val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt()
        return if (remainingSeconds in 1..10) "还能说 $remainingSeconds 秒" else null
    }

    // ------------------------------------------------------------ 映射（错误 / 上限）

    /**
     * 录音错误码 → 人话（状态机 [VoiceInputError]；文案按码给，基建层不写用户文案）。
     */
    fun recordingError(code: VoiceInputError?): String = when (code) {
        VoiceInputError.PERMISSION_DENIED -> PERMISSION_DENIED
        VoiceInputError.TOO_SHORT -> TOO_SHORT
        VoiceInputError.RECORDING_UNAVAILABLE -> RECORDING_FAILED
        VoiceInputError.RECORDING_FAILED -> RECORDING_FAILED
        VoiceInputError.TRANSCRIBE_FAILED -> FAILED
        null -> FAILED
    }

    /** 网络错误分类转换为提示语，并提供语音专属的打字出路。 */
    fun transcribeFailure(error: ApiError): String = when (error.kind) {
        ApiError.Kind.NETWORK -> NO_NETWORK
        ApiError.Kind.QUOTA -> QUOTA
        ApiError.Kind.AUTH, ApiError.Kind.NO_KEY -> AUTH
        ApiError.Kind.FILTERED -> FILTERED
        ApiError.Kind.SERVER -> BUSY
        else -> FAILED
    }

    /**
     * 转写异常 → 人话。
     *
     * - [AsrRejectedException]：本地前置拒绝（一次请求都没发、不花钱），按原因给出路；
     * - [ApiException]：网络/HTTP/解析失败，按 [ApiError] 分类；
     * - 其它：兜底「语音没转成」，不把异常文本（可能含服务商回显）给用户看。
     */
    fun transcribeFailure(throwable: Throwable): String = when (throwable) {
        is AsrRejectedException -> when (throwable.reason) {
            AsrRejectReason.NO_KEY -> AUTH
            AsrRejectReason.BAD_BASE_URL, AsrRejectReason.NOT_WAV -> SERVICE_UNSUPPORTED
            AsrRejectReason.FILE_MISSING, AsrRejectReason.FILE_EMPTY,
            AsrRejectReason.DURATION_INVALID,
            -> EMPTY_AUDIO
            AsrRejectReason.FILE_TOO_LARGE, AsrRejectReason.DURATION_TOO_LONG -> TOO_LONG
        }
        is ApiException -> transcribeFailure(throwable.error)
        else -> FAILED
    }

    /**
     * 录音前额度预检被拦住 → 人话。
     *
     * 直接复用聊天页既有的「到上限了」文案（`UsageLimits`），用户看到的措辞与打字发送被拦时
     * 完全一致，不需要学第二套说法；用户可以从现有入口调高上限。
     */
    fun quotaMessage(reason: VoiceQuotaReason): String = when (reason) {
        VoiceQuotaReason.DAILY_LIMIT -> UsageLimits.DAILY_LIMIT_MESSAGE
        VoiceQuotaReason.MONTHLY_LIMIT -> UsageLimits.MONTHLY_LIMIT_MESSAGE
    }
}
