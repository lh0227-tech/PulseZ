package cc.pulsez.app.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 一个系统音色（只暴露 App 需要的三个字段，不把平台 `android.speech.tts.Voice` 泄漏给 UI/测试）。
 *
 * 依据：https://developer.android.com/reference/android/speech/tts/Voice
 * （`Voice.getName()` / `Voice.getLocale()` / `Voice.isNetworkConnectionRequired()`）。
 */
data class ReadVoice(
    /** 音色唯一名（`Voice.getName()`），持久化到设置、下次用来恢复同一个音色。 */
    val id: String,
    /** BCP-47 语言标签（`Voice.getLocale().toLanguageTag()`），用来筛中文音色。 */
    val languageTag: String,
    /** 是否必须联网（`Voice.isNetworkConnectionRequired()`）；false = 引擎内嵌/离线音色。 */
    val requiresNetwork: Boolean,
)

/** 朗读引擎状态机（`INIT → READY / UNAVAILABLE`）。*/
enum class ReaderStatus {
    /** 还没初始化过（懒加载；没开自动朗读时不该占用 TTS 引擎）。 */
    IDLE,

    /** 已创建引擎、等 `OnInitListener` 回调。**这之前不能 speak**（官方类文档原文）。 */
    INITIALIZING,

    /** 初始化成功、中文可用，可以 speak。 */
    READY,

    /** 初始化失败 / 没有中文语音数据 / 没有可用中文语言。UI 给「这台手机暂时不能朗读」与出路。 */
    UNAVAILABLE,

    /** 已 `shutdown()` 释放引擎。下次 speak/ensureInitialized 会重建。 */
    SHUTDOWN,
}

/** 朗读器对 UI 暴露的状态。 */
data class ReaderState(
    val status: ReaderStatus = ReaderStatus.IDLE,
    /** 当前是否有一条正在读（用于输入条上方「正在朗读…/停下」）。 */
    val speaking: Boolean = false,
    /** 中文音色列表（已按「离线优先」排序）；空 = 引擎没给出列表，用系统默认。 */
    val voices: List<ReadVoice> = emptyList(),
)

/**
 * 平台 TTS 的最小接口（依赖注入点）。
 *
 * 真实实现见 [AndroidTtsEngine]；单测注入 fake，因此 [Reader] 本身不 import 任何 Android 类，
 * 可以在纯 JVM 上测状态机（ TTS 胶水层不做自动断言，
 * 但状态机/清洗/判定必须可测）。
 *
 * 常量来源（官方文档）：
 * - `TextToSpeech.SUCCESS = 0`、`ERROR = -1`；
 * - `isLanguageAvailable` 返回 `LANG_AVAILABLE=0` / `LANG_COUNTRY_AVAILABLE=1` /
 *   `LANG_COUNTRY_VAR_AVAILABLE=2` 表示可用；`LANG_MISSING_DATA=-1` / `LANG_NOT_SUPPORTED=-2` 不可用。
 *   https://developer.android.com/reference/android/speech/tts/TextToSpeech
 */
interface TtsEngine {

    /** 创建引擎并等初始化完成；`onInit` 参数是 SUCCESS(0) 或 ERROR(-1)。 */
    fun initialize(onInit: (status: Int) -> Unit)

    /** 设置播报回调；`utteranceId` 用于丢弃 stop 之后迟到的旧回调。 */
    fun setUtteranceListener(onDone: (utteranceId: String) -> Unit, onError: (utteranceId: String) -> Unit)

    /** `TextToSpeech.isLanguageAvailable`。 */
    fun isLanguageAvailable(locale: Locale): Int

    /** `TextToSpeech.setLanguage`；返回 SUCCESS/ERROR。 */
    fun setLanguage(locale: Locale): Int

    /** `TextToSpeech.setSpeechRate`；1.0 = 正常速度。 */
    fun setSpeechRate(rate: Float): Int

    /** 按 [ReadVoice.id] 选音色；返回 SUCCESS/ERROR（失败时保持引擎默认）。 */
    fun setVoice(voiceId: String): Int

    /** `TextToSpeech.speak(QUEUE_FLUSH)`；返回 SUCCESS/ERROR。 */
    fun speak(text: String, utteranceId: String): Int

    /** `TextToSpeech.stop`：打断当前并清空队列。 */
    fun stop(): Int

    /** `TextToSpeech.shutdown`：释放原生资源（官方文档要求不再使用时调用）。 */
    fun shutdown()

    /** `TextToSpeech.getMaxSpeechInputLength()`。 */
    fun maxSpeechInputLength(): Int

    /** `TextToSpeech.getVoices()`（可能为空/null）。 */
    fun voices(): List<ReadVoice>

    /** `TextToSpeech.getDefaultVoice()` 的 name；拿不到返回 null。 */
    fun defaultVoiceId(): String?
}

/**
 * 自动朗读器（v0.5）。
 *
 * 生命周期：
 * - 延迟初始化：UI 需要（进聊天页且开关开 / 进设置「声音」组）才 `ensureInitialized()`；
 * - `OnInitListener` 回调前状态是 INITIALIZING，**不 speak**；这期间来的 speak 只留最后一条，
 *   初始化成功后补读（不排队念旧回复）；
 * - `stop()` 可随时打断（发送/停止生成/切页/离开聊天页/开始录音/点「停下」/退到后台）；
 * - `shutdown()` 释放引擎；之后再次 `ensureInitialized()`/`speak()` 会重建（App 级单例，
 *   `ChatViewModel.onCleared()` 释放后 Settings 再进来仍可用）。
 *
 * 线程模型：调用方在任意线程调用都安全（内部 synchronized）；TTS 的播报回调来自 Binder 线程，
 * 用 utteranceId 代际过滤掉 stop 之后迟到的 onDone，避免「停掉了又被标成正在读」。
 * 状态用 [MutableStateFlow]，Compose 直接 `collectAsState()`。
 */
class Reader(
    private val engineFactory: () -> TtsEngine,
) {

    private val lock = Any()

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var engine: TtsEngine? = null

    /** 初始化/未就绪期间最多保留这一条待读文本（后来的覆盖先来的，不念过期回复）。 */
    private var pending: PendingSpeak? = null

    private var currentUtteranceId: String? = null
    private var utteranceSeq: Long = 0L

    /** 中文音色（离线优先）。 */
    private var voices: List<ReadVoice> = emptyList()

    /** 「系统默认」实际落到哪个音色：优先引擎默认（若是中文），否则第一个可用的中文音色。 */
    private var defaultVoiceId: String? = null

    /** 初始化成功后选中的语言（zh-CN 优先，退回 zh）。 */
    private var configuredLocale: Locale? = null

    private data class PendingSpeak(val text: String, val rateX100: Int, val voiceId: String)

    /**
     * 确保引擎已初始化（幂等）。
     *
     * - READY / INITIALIZING：什么都不做；
     * - IDLE / UNAVAILABLE / SHUTDOWN：创建引擎重试。UNAVAILABLE 也重试是有意的：
     *   用户去系统设置装完中文语音包再回来，不该重启 App 才能用。
     */
    fun ensureInitialized() {
        val created = synchronized(lock) {
            val status = _state.value.status
            if (status == ReaderStatus.READY || status == ReaderStatus.INITIALIZING) {
                return
            }
            engine?.let { stale ->
                runCatching { stale.shutdown() }
            }
            engine = null
            _state.value = ReaderState(status = ReaderStatus.INITIALIZING)
            val next = runCatching { engineFactory() }.getOrNull()
            if (next == null) {
                _state.value = ReaderState(status = ReaderStatus.UNAVAILABLE)
                null
            } else {
                engine = next
                next
            }
        }
        // created == null：已就绪 / 正在初始化 / 创建失败，三种都不需要再 initialize。
        if (created == null) return
        val target = created
        // 官方文档：初始化失败时 listener 可能在构造函数里就被同步调用，所以不能持锁调用 initialize。
        // 官方文档只保证“可能同步回调”，没保证个别设备的 `TextToSpeech` 构造/绑定不会直接抛异常；
        // 它可能在 Compose `LaunchedEffect` 或 `viewModelScope` 里被调用，异常逃逸会崩 App。
        // 所以这里兜住：抛异常 = 初始化失败，一律降级 UNAVAILABLE（与 `engineFactory()` 的
        // `runCatching` 同一口径 ）。
        runCatching {
            target.initialize { status -> onInitialized(target, status) }
        }.onFailure {
            synchronized(lock) {
                // 只处理「还是这台引擎、仍停在 INITIALIZING」的情况：若回调已经先到（极端实现），
                // 不能把已就绪的状态再打回 UNAVAILABLE。
                if (engine === target && _state.value.status == ReaderStatus.INITIALIZING) {
                    failInit(target)
                }
            }
        }
    }

    /**
     * 读一段文本（自动清洗 + 截断）。
     *
     * @param text 最终回复正文
     * @param rateX100 语速 ×100（80–150）；越界按范围收敛
     * @param voiceId 音色 id；空串 = 系统默认/第一个可用中文音色
     */
    fun speak(text: String, rateX100: Int = DEFAULT_RATE_X100, voiceId: String = "") {
        val maxChars = maxChars()
        val cleaned = TtsTextCleaner.clean(text, maxChars)
        if (cleaned.isBlank()) return
        val request = PendingSpeak(cleaned, clampRate(rateX100), voiceId)
        var needsInit = false
        synchronized(lock) {
            when (_state.value.status) {
                ReaderStatus.READY -> Unit // 由下面的 speakReady 处理
                ReaderStatus.INITIALIZING -> {
                    // 初始化中：只留最后一条，初始化成功后补读。
                    pending = request
                    return
                }
                ReaderStatus.UNAVAILABLE -> return // 读不了：不排队、不假装读（UI 显示原因）
                ReaderStatus.IDLE, ReaderStatus.SHUTDOWN -> {
                    pending = request
                    needsInit = true
                }
            }
        }
        if (needsInit) {
            ensureInitialized()
            return
        }
        speakReady(request)
    }

    /** 停止当前朗读并清空待读。可重复调用（幂等）。只停这一次，不关自动朗读。 */
    fun stop() {
        val current = synchronized(lock) {
            pending = null
            currentUtteranceId = null
            if (_state.value.speaking) {
                _state.value = _state.value.copy(speaking = false)
            }
            engine
        }
        // 不在锁内调用引擎：某些实现的 stop 可能同步回调进度。
        if (current != null) runCatching { current.stop() }
    }

    /**
     * 释放引擎（官方文档：不再使用时调 `shutdown()`）。
     *
     * 幂等：已经是 SHUTDOWN 时不再重复 shutdown 引擎（单测断言只调一次）。
     * 释放后 [ensureInitialized]/[speak] 会重建，所以 App 级单例可以「离开聊天页释放、再进来重建」。
     */
    fun shutdown() {
        val current = synchronized(lock) {
            if (_state.value.status == ReaderStatus.SHUTDOWN) return
            pending = null
            currentUtteranceId = null
            val released = engine
            engine = null
            voices = emptyList()
            defaultVoiceId = null
            configuredLocale = null
            _state.value = ReaderState(status = ReaderStatus.SHUTDOWN)
            released
        }
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.shutdown() }
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun onInitialized(target: TtsEngine, status: Int) {
        var flush: PendingSpeak? = null
        synchronized(lock) {
            // 已 shutdown / 已换新引擎 / 之前已 ready：迟到的旧回调直接丢弃。
            if (engine !== target) return
            if (_state.value.status == ReaderStatus.READY || _state.value.status == ReaderStatus.SHUTDOWN) return
            if (status != STATUS_SUCCESS) {
                failInit(target)
                return
            }
            val locale = pickLanguage(target)
            if (locale == null || !applyLanguage(target, locale)) {
                failInit(target)
                return
            }
            configuredLocale = locale
            val chinese = chineseVoices(target)
            voices = chinese
            defaultVoiceId = pickDefaultVoice(target, chinese)
            runCatching {
                target.setUtteranceListener(
                    onDone = { id -> onUtteranceDone(id) },
                    onError = { id -> onUtteranceDone(id) },
                )
            }
            _state.value = _state.value.copy(
                status = ReaderStatus.READY,
                speaking = false,
                voices = chinese,
            )
            flush = pending
            pending = null
        }
        // 初始化期间攒下的那条，成功后补读（锁外调用，避免回调重入死锁）。
        flush?.let { speakReady(it) }
    }

    private fun failInit(target: TtsEngine) {
        engine = null
        voices = emptyList()
        defaultVoiceId = null
        configuredLocale = null
        pending = null
        currentUtteranceId = null
        _state.value = ReaderState(status = ReaderStatus.UNAVAILABLE)
        runCatching { target.shutdown() }
    }

    private fun speakReady(request: PendingSpeak) {
        synchronized(lock) {
            val target = engine ?: return
            if (_state.value.status != ReaderStatus.READY) {
                pending = request
                return
            }
            val rate = clampRate(request.rateX100) / 100f
            runCatching { target.setSpeechRate(rate) }
            // 用户选的音色若已不存在（换手机/换引擎），退回默认音色而不是静默失败。
            val voiceId = request.voiceId.takeIf { id ->
                id.isNotBlank() && voices.any { it.id == id }
            } ?: defaultVoiceId
            if (voiceId != null) runCatching { target.setVoice(voiceId) }
            // 重新选一次语言：若上一次 speak 换过音色，空 voiceId 时要能回到默认音色。
            if (voiceId == null) {
                configuredLocale?.let { locale -> runCatching { target.setLanguage(locale) } }
            }
            val utteranceId = "read-${++utteranceSeq}"
            currentUtteranceId = utteranceId
            val result = runCatching { target.speak(request.text, utteranceId) }.getOrDefault(STATUS_ERROR)
            _state.value = _state.value.copy(speaking = result == STATUS_SUCCESS)
        }
    }

    private fun onUtteranceDone(utteranceId: String) {
        synchronized(lock) {
            // 代际过滤：stop() 之后的旧回调不能把「正在读」又点亮。
            if (utteranceId != currentUtteranceId) return
            currentUtteranceId = null
            if (_state.value.speaking) {
                _state.value = _state.value.copy(speaking = false)
            }
        }
    }

    private fun pickLanguage(target: TtsEngine): Locale? {
        // 依据「语言 zh-CN 优先并用 isLanguageAvailable 判断」。
        val zhCn = Locale.SIMPLIFIED_CHINESE
        if (target.isLanguageAvailable(zhCn) >= STATUS_SUCCESS) return zhCn
        Locale.CHINESE.let { zh ->
            if (target.isLanguageAvailable(zh) >= STATUS_SUCCESS) return zh
        }
        return null
    }

    private fun applyLanguage(target: TtsEngine, locale: Locale): Boolean =
        runCatching { target.setLanguage(locale) }.getOrDefault(STATUS_ERROR) >= STATUS_SUCCESS

    private fun chineseVoices(target: TtsEngine): List<ReadVoice> =
        runCatching { target.voices() }
            .getOrDefault(emptyList())
            .filter { isChinese(it) }
            // 离线优先：文字尽量不发到引擎厂商的云。
            .sortedWith(compareBy({ it.requiresNetwork }, { it.id }))

    private fun isChinese(voice: ReadVoice): Boolean {
        val tag = voice.languageTag.lowercase(Locale.ROOT)
        return tag.startsWith("zh") || tag.startsWith("cmn")
    }

    private fun pickDefaultVoice(target: TtsEngine, chinese: List<ReadVoice>): String? {
        val engineDefault = runCatching { target.defaultVoiceId() }.getOrNull()
        val chineseDefault = engineDefault?.let { id -> chinese.firstOrNull { it.id == id } }
        // 引擎默认本来就是离线中文音色：尊重系统默认（用户/系统已经选好了）。
        if (chineseDefault != null && !chineseDefault.requiresNetwork) return chineseDefault.id
        // 引擎默认是联网中文音色、或根本不是中文：优先离线中文音色，让文字尽量不发到引擎厂商的云。
        // 依据（部分手机的朗读会联网）；
        // （列表已离线优先，默认音色也要）。
        chinese.firstOrNull { !it.requiresNetwork }?.let { return it.id }
        // 没有任何离线中文音色：退回引擎默认（若是中文），否则列表第一个。
        return chineseDefault?.id ?: chinese.firstOrNull()?.id
    }

    private fun maxChars(): Int {
        // 依据：平台 API `TextToSpeech.getMaxSpeechInputLength()` 就是 `speak`/`synthesizeToFile`
        // 的输入上限（官方类文档 speak() 的 @param text 原文 “No longer than
        // getMaxSpeechInputLength() characters”）：https://developer.android.com/reference/android/speech/tts/TextToSpeech#getMaxSpeechInputLength()
        // AOSP 当前实现固定返回 4000（android.speech.tts.TextToSpeech#getMaxSpeechInputLength 直接 `return 4000`）：
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/tts/TextToSpeech.java
        // 产品侧另有「单条最多 2000 字」口径（「截断 2000 字」），
        // 所以两者取小；OEM 引擎可能不同，运行时以平台返回值为准。
        val platform = runCatching { engine?.maxSpeechInputLength() }.getOrNull()
        val limit = if (platform != null && platform > 0) {
            minOf(TtsTextCleaner.MAX_CHARS, platform)
        } else {
            TtsTextCleaner.MAX_CHARS
        }
        return limit.coerceAtLeast(1)
    }

    companion object {
        /** `TextToSpeech.SUCCESS` 的常量值（官方文档：Constant Value: 0）。 */
        private const val STATUS_SUCCESS = 0

        /** `TextToSpeech.ERROR` 的常量值（官方文档：Constant Value: -1）。 */
        private const val STATUS_ERROR = -1

        /** 语速范围 ×100：0.8–1.5 倍。*/
        const val MIN_RATE_X100 = 80
        const val MAX_RATE_X100 = 150

        /** 默认语速：1.0 倍。 */
        const val DEFAULT_RATE_X100 = 100

        /** 语速越界收敛（设置里被手改/老数据脏值时保护 `setSpeechRate`）。 */
        fun clampRate(rateX100: Int): Int = rateX100.coerceIn(MIN_RATE_X100, MAX_RATE_X100)
    }
}
