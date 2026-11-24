package cc.pulsez.app.ui.copy

import cc.pulsez.app.audio.ReadVoice
import cc.pulsez.app.data.settings.normalizeReadRate
import kotlin.math.abs

/**
 * 「自动朗读（系统 TTS）」用户可见文案（v0.5）。
 *
 * 依据：
 * - 「朗读（首次开启自动朗读时，AlertDialog）」——标题 / 三段正文 / 按钮
 *   **逐字**锁定；§3.4 措辞「不写免费/离线的绝对承诺，写不经过你填的 AI、不按次收费；
 *   具体音色和是否离线取决于你的手机」；
 * - （设置页「声音」组、播放提示「正在朗读…/停下」）、
 *   §6.5.3（默认关、只读新回复）；
 * - （逐字文案、不制造不存在功能、不写「免费」、
 *   一步停止、无障碍）；
 * - 三问（是什么 / 要不要用 / 不用会怎样）。
 *
 * 收口成常量的原因同 `CreativeModeCopy` / `WindowTierCopy`：文案是产品定稿，
 * 纯 JVM 逐字断言 + 禁用词回归（`ReadCopyTest`），改动必须显式。
 */
internal object ReadCopy {

    // ------------------------------------------------------------ 首次说明（逐字）

    /** 首次说明标题。 */
    const val DIALOG_TITLE = "让 TA 读出来"

    /** 首次说明第 1 句：是什么 / 谁付钱（不经过你填的 AI、不按次收费）。 */
    const val INTRO_LINE_1 = "用你手机自带的朗读把 TA 的回复读出来，不经过你填的 AI，也不按次收费。"

    /** 首次说明第 2 句：音色/离线取决于手机，部分会联网（不承诺免费/离线）。 */
    const val INTRO_LINE_2 = "音色、好不好听、能不能离线，取决于你的手机；部分手机的朗读会联网。"

    /** 首次说明第 3 句：怎么关。 */
    const val INTRO_LINE_3 = "随时能在设置 → 声音里关掉。"

    /** 首次说明正文三行，顺序即展示顺序（测试断言恰好三行）。 */
    val INTRO_LINES: List<String> = listOf(INTRO_LINE_1, INTRO_LINE_2, INTRO_LINE_3)

    /** 主按钮：真正打开自动朗读。 */
    const val CONFIRM = "开启"

    /** 次按钮：不开启，但也不再提示（只提示一次）。 */
    const val DISMISS = "先不用"

    /** 回看说明时的唯一按钮（不重复触发开关）。 */
    const val CLOSE = "知道了"

    // ------------------------------------------------------------ 设置「声音」组

    /** 设置页分组标题。 */
    const val GROUP = "声音"

    /** 自动朗读开关标题：说清「读的是谁、读什么」。 */
    const val SETTING_TITLE = "自动朗读 TA 的回复"

    /** 开关下一行说明（是什么 / 不用会怎样）。 */
    const val SETTING_DESCRIPTION = "开着时，TA 每次回完就自动读出来；不读也能照常聊天。"

    /** 语速行标题。 */
    const val RATE_TITLE = "语速"

    /** 语速行说明。 */
    const val RATE_DESCRIPTION = "慢一点听得清，快一点更省时间；默认就行。"

    /** 音色行标题。 */
    const val VOICE_TITLE = "音色"

    /** 音色行说明（引擎没给列表时另有一句，见 [VOICE_DESCRIPTION_EMPTY]）。 */
    const val VOICE_DESCRIPTION = "用这台手机里的中文声音，点一下换一个。"

    /** 音色行说明（系统没给出可选音色时）。 */
    const val VOICE_DESCRIPTION_EMPTY = "这台手机没给出可选的音色，用系统默认的。"

    /** 音色值：系统默认 / 第一个可用。 */
    const val VOICE_SYSTEM_DEFAULT = "系统默认"

    /** 「当前方式」行：固定说明用的是系统朗读（不是云端、不额外计费）。 */
    const val METHOD_TITLE = "当前方式"

    /** 「当前方式」行右侧值。 */
    const val METHOD_VALUE = "系统朗读"

    /** 「当前方式」行说明：不经过用户填的 AI；音色/离线取决于手机（不写「免费」）。 */
    const val METHOD_DESCRIPTION = "用手机自带的声音，不经过你填的 AI，也不按次收费；音色和能不能离线，取决于你的手机。"

    /** 首用说明回看入口标题。 */
    const val INTRO_ENTRY_TITLE = "朗读说明"

    /** 首用说明回看入口右侧值。 */
    const val INTRO_ENTRY_VALUE = "再看一遍"

    // ------------------------------------------------------------ 状态 / 错误 / 播放提示

    /**
     * 初始化失败 / 没有中文语音数据时的说明 + 出路（ 验收 4：
     * 「初始化失败给『这台手机暂时不能朗读』」 要求给
     * 「去系统设置装语音包/换引擎」的下一步）。
     */
    const val UNAVAILABLE = "这台手机暂时不能朗读。去系统设置里装个中文语音包，或者换个系统朗读服务。"

    /** 播放提示（输入条上方）。 */
    const val READING = "正在朗读……"

    /** 播放提示的停止按钮（只停这一次，不关自动朗读）。 */
    const val STOP = "停下"

    /** 播放提示的读屏描述（整行播报一次，说清能做什么）。 */
    const val READING_CONTENT_DESCRIPTION = "正在朗读，点停下可以停止"

    /** 停止按钮的读屏描述。 */
    const val STOP_CONTENT_DESCRIPTION = "停下朗读"

    // ------------------------------------------------------------ 语速档位

    /** 语速档位（×100）：0.8 慢 / 1.0 正常 / 1.2 快 / 1.5 更快（范围 0.8–1.5）。*/
    val RATES_X100: List<Int> = listOf(80, 100, 120, 150)

    /** 默认语速 1.0 倍。 */
    const val DEFAULT_RATE_X100 = 100

    /** 语速档位名。 */
    fun rateLabel(rateX100: Int): String = when (nearestRate(rateX100)) {
        80 -> "慢"
        120 -> "快"
        150 -> "更快"
        else -> "正常"
    }

    /** 点击语速行：循环到下一档。 */
    fun nextRate(rateX100: Int): Int {
        val current = nearestRate(rateX100)
        val index = RATES_X100.indexOf(current)
        return RATES_X100[(index + 1) % RATES_X100.size]
    }

    /** 把任意存储值收敛到最近的档位（设置里可能存过非档位值）。 */
    fun nearestRate(rateX100: Int): Int {
        val clamped = normalizeReadRate(rateX100)
        return RATES_X100.minByOrNull { abs(it - clamped) } ?: DEFAULT_RATE_X100
    }

    // ------------------------------------------------------------ 音色行

    /**
     * 音色行右侧值：设置里存的 id → 人话。
     *
     * 音色名（`Voice.getName()`）是技术串，不能直接给用户看；这里按「第几个 + 离线/联网」
     * 描述。用户答得出「换了没有 / 会不会联网」，符合 。
     */
    fun voiceValue(voiceId: String, voices: List<ReadVoice>): String {
        if (voices.isEmpty() || voiceId.isBlank()) return VOICE_SYSTEM_DEFAULT
        val index = voices.indexOfFirst { it.id == voiceId }
        if (index < 0) return VOICE_SYSTEM_DEFAULT
        return voiceLabel(index, voices[index].requiresNetwork)
    }

    /** 音色行点击：系统默认 → 第 1 个 → 第 2 个 → … → 系统默认。 */
    fun nextVoiceId(voiceId: String, voices: List<ReadVoice>): String {
        if (voices.isEmpty()) return ""
        val index = voices.indexOfFirst { it.id == voiceId }
        val next = index + 1
        return if (next >= voices.size) "" else voices[next].id
    }

    /** 第 [index] 个音色的人话名（从 1 开始数）。 */
    fun voiceLabel(index: Int, requiresNetwork: Boolean): String =
        "音色 ${index + 1}（${if (requiresNetwork) "联网" else "离线"}）"
}
