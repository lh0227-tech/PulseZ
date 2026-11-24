package cc.pulsez.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * [TtsEngine] 的真实实现：包装平台 `android.speech.tts.TextToSpeech`。
 *
 * 这一层只做「平台 API 直译」，不放业务逻辑（清洗/状态机/触发条件都在 [Reader] 与
 * [TtsTextCleaner]，因此可以纯 JVM 单测）。真实引擎行为（能否绑上、中文音色、离线与否）
 * 只能在真机验，标「未真机验证」。
 *
 * 官方依据：
 * - 初始化完成前不能合成；完成后要 `shutdown()`：
 *   https://developer.android.com/reference/android/speech/tts/TextToSpeech
 * - manifest 需要 `<queries>` 声明 `android.intent.action.TTS_SERVICE`（targetSdk 30+）：
 *   同一页面原文；已加在 `app/src/main/AndroidManifest.xml`。
 * - `speak(text, QUEUE_FLUSH, params, utteranceId)` 后可以 `stop()` 打断。
 */
class AndroidTtsEngine(context: Context) : TtsEngine {

    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null

    /** `shutdown()` 在构造返回前被调用（官方文档：初始化失败时 listener 可能同步回调）时， */
    private var releaseRequested = false

    override fun initialize(onInit: (status: Int) -> Unit) {
        // 每次新建：Reader.shutdown() 之后再次初始化时，旧的 TextToSpeech 已经释放。
        releaseRequested = false
        val created = TextToSpeech(appContext) { status -> onInit(status) }
        tts = created
        // 如果回调是同步的（失败路径），Reader 可能在赋值前就调了 shutdown()；
        // 这里补一次释放，避免引擎漏掉。
        if (releaseRequested) {
            tts = null
            runCatching { created.shutdown() }
        }
    }

    override fun setUtteranceListener(
        onDone: (utteranceId: String) -> Unit,
        onError: (utteranceId: String) -> Unit,
    ) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let(onDone)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let(onError)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let(onError)
            }
        })
    }

    override fun isLanguageAvailable(locale: Locale): Int =
        tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED

    override fun setLanguage(locale: Locale): Int =
        tts?.setLanguage(locale) ?: TextToSpeech.ERROR

    override fun setSpeechRate(rate: Float): Int =
        tts?.setSpeechRate(rate) ?: TextToSpeech.ERROR

    override fun setVoice(voiceId: String): Int {
        val engine = tts ?: return TextToSpeech.ERROR
        val voice = runCatching { engine.voices?.firstOrNull { it.name == voiceId } }.getOrNull()
            ?: return TextToSpeech.ERROR
        return engine.setVoice(voice)
    }

    override fun speak(text: String, utteranceId: String): Int =
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ?: TextToSpeech.ERROR

    override fun stop(): Int = tts?.stop() ?: TextToSpeech.ERROR

    override fun shutdown() {
        releaseRequested = true
        val engine = tts ?: return
        tts = null
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
    }

    override fun maxSpeechInputLength(): Int = TextToSpeech.getMaxSpeechInputLength()

    override fun voices(): List<ReadVoice> =
        runCatching {
            tts?.voices.orEmpty().map { voice ->
                ReadVoice(
                    id = voice.name,
                    languageTag = voice.locale?.toLanguageTag().orEmpty(),
                    requiresNetwork = voice.isNetworkConnectionRequired,
                )
            }
        }.getOrDefault(emptyList())

    override fun defaultVoiceId(): String? =
        runCatching { tts?.defaultVoice?.name }.getOrNull()
}
