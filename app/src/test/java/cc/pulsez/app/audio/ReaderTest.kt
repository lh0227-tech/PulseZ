package cc.pulsez.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 朗读器状态机的纯 JVM 测试：使用 fake 引擎，不依赖 Robolectric 或真实 TTS。
 *
 * 检查 `INIT → READY / UNAVAILABLE` 状态、初始化完成后再调用 speak、停止与释放，
 * 并优先选择 zh-CN。`AndroidTtsEngine` 的平台胶水层仍需在设备上验证。
 */
class ReaderTest {

    /** 可编程的 fake 引擎：记录调用、可手动触发初始化/播报回调。 */
    private class FakeTtsEngine : TtsEngine {
        var initCallCount = 0
        /** true = `initialize` 直接抛异常（模拟个别设备 `TextToSpeech` 构造/绑定崩溃）。 */
        var initThrows = false
        private var initCallback: ((Int) -> Unit)? = null

        var languageStatus = 0 // LANG_AVAILABLE
        /** 按 Locale 决定可用性（默认用 [languageStatus]），用于测 zh-CN → zh 的回退。 */
        var languageStatusFor: (Locale) -> Int = { languageStatus }
        var setLanguageStatus = 0
        var speakStatus = 0
        var maxInputLength = 4_000

        var voicesList: List<ReadVoice> = emptyList()
        var defaultVoice: String? = null

        var stopCount = 0
        var shutdownCount = 0
        var lastRate: Float? = null
        var lastVoiceId: String? = null
        var lastLanguage: Locale? = null
        val spoken = mutableListOf<String>()
        val spokenIds = mutableListOf<String>()

        private var onDone: ((String) -> Unit)? = null
        private var onError: ((String) -> Unit)? = null

        override fun initialize(onInit: (status: Int) -> Unit) {
            initCallCount++
            if (initThrows) throw IllegalStateException("fake TTS engine exploded during init")
            initCallback = onInit
        }

        fun completeInit(status: Int) {
            initCallback?.invoke(status)
        }

        override fun setUtteranceListener(
            onDone: (utteranceId: String) -> Unit,
            onError: (utteranceId: String) -> Unit,
        ) {
            this.onDone = onDone
            this.onError = onError
        }

        override fun isLanguageAvailable(locale: Locale): Int = languageStatusFor(locale)

        override fun setLanguage(locale: Locale): Int {
            lastLanguage = locale
            return setLanguageStatus
        }

        override fun setSpeechRate(rate: Float): Int {
            lastRate = rate
            return 0
        }

        override fun setVoice(voiceId: String): Int {
            lastVoiceId = voiceId
            return 0
        }

        override fun speak(text: String, utteranceId: String): Int {
            spoken += text
            spokenIds += utteranceId
            return speakStatus
        }

        override fun stop(): Int {
            stopCount++
            return 0
        }

        override fun shutdown() {
            shutdownCount++
        }

        override fun maxSpeechInputLength(): Int = maxInputLength

        override fun voices(): List<ReadVoice> = voicesList

        override fun defaultVoiceId(): String? = defaultVoice

        fun finishLast() {
            spokenIds.lastOrNull()?.let { onDone?.invoke(it) }
        }

        fun finish(utteranceId: String) {
            onDone?.invoke(utteranceId)
        }

        fun fail(utteranceId: String) {
            onError?.invoke(utteranceId)
        }
    }

    private fun readerWith(engine: FakeTtsEngine): Reader = Reader { engine }

    private fun readyReader(engine: FakeTtsEngine): Reader {
        val reader = readerWith(engine)
        reader.ensureInitialized()
        engine.completeInit(0)
        return reader
    }

    // ------------------------------------------------------------ 初始化状态机

    @Test
    fun `init goes initializing then ready`() {
        val engine = FakeTtsEngine()
        val reader = readerWith(engine)
        assertEquals(ReaderStatus.IDLE, reader.state.value.status)

        reader.ensureInitialized()
        assertEquals(ReaderStatus.INITIALIZING, reader.state.value.status)
        assertEquals(1, engine.initCallCount)

        engine.completeInit(0)
        assertEquals(ReaderStatus.READY, reader.state.value.status)
        assertEquals("zh-CN 优先", Locale.SIMPLIFIED_CHINESE, engine.lastLanguage)
    }

    @Test
    fun `init failure becomes unavailable`() {
        val engine = FakeTtsEngine()
        val reader = readerWith(engine)
        reader.ensureInitialized()
        engine.completeInit(-1) // TextToSpeech.ERROR

        assertEquals(ReaderStatus.UNAVAILABLE, reader.state.value.status)
        // 初始化失败后 speak 不能假装在读，也不留待读文本。
        reader.speak("你好。")
        assertTrue(engine.spoken.isEmpty())
        assertEquals(ReaderStatus.UNAVAILABLE, reader.state.value.status)
    }

    @Test
    fun `missing chinese language data becomes unavailable`() {
        val engine = FakeTtsEngine().apply { languageStatus = -1 } // LANG_MISSING_DATA
        val reader = readerWith(engine)
        reader.ensureInitialized()
        engine.completeInit(0)

        assertEquals(ReaderStatus.UNAVAILABLE, reader.state.value.status)
        reader.speak("你好。")
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `unsupported chinese becomes unavailable`() {
        val engine = FakeTtsEngine().apply { languageStatus = -2 } // LANG_NOT_SUPPORTED
        val reader = readerWith(engine)
        reader.ensureInitialized()
        engine.completeInit(0)

        assertEquals(ReaderStatus.UNAVAILABLE, reader.state.value.status)
    }

    @Test
    fun `falls back to plain chinese when zh cn unsupported`() {
        val engine = FakeTtsEngine().apply {
            languageStatusFor = { locale ->
                if (locale == Locale.SIMPLIFIED_CHINESE) -2 else 0 // zh-CN 不支持，zh 可用
            }
        }
        val reader = readerWith(engine)
        reader.ensureInitialized()
        engine.completeInit(0)

        assertEquals(ReaderStatus.READY, reader.state.value.status)
        assertEquals("zh-CN 不可用时退回 zh", Locale.CHINESE, engine.lastLanguage)
    }

    @Test
    fun `retry from unavailable reinitializes`() {
        val engine = FakeTtsEngine()
        val reader = readerWith(engine)
        reader.ensureInitialized()
        engine.completeInit(-1)
        assertEquals(ReaderStatus.UNAVAILABLE, reader.state.value.status)

        // 用户去系统设置装完语音包再回来：ensureInitialized 要能重试，不用重启 App。
        reader.ensureInitialized()
        assertEquals(ReaderStatus.INITIALIZING, reader.state.value.status)
        assertEquals(2, engine.initCallCount)
        engine.completeInit(0)
        assertEquals(ReaderStatus.READY, reader.state.value.status)
    }

    @Test
    fun `set language failure becomes unavailable`() {
        // S10④：`isLanguageAvailable` 说可用、但 `setLanguage` 返回 ERROR
        // 的真实分支也要降级 UNAVAILABLE，而不是假装 READY（否则 speak 会静默不出声）。
        val engine = FakeTtsEngine().apply { setLanguageStatus = -1 } // TextToSpeech.ERROR
        val reader = readerWith(engine)
        reader.ensureInitialized()
        engine.completeInit(0)

        assertEquals(ReaderStatus.UNAVAILABLE, reader.state.value.status)
        assertTrue("失败后要释放引擎", engine.shutdownCount >= 1)
        reader.speak("你好。")
        assertTrue("UNAVAILABLE 不能假装在读", engine.spoken.isEmpty())
        assertEquals(ReaderStatus.UNAVAILABLE, reader.state.value.status)
    }

    @Test
    fun `initialize throwing degrades to unavailable instead of escaping`() {
        // ：`TextToSpeech` 构造/绑定在个别设备上可能直接抛异常；
        // 这里不能让它从 Compose LaunchedEffect / viewModelScope 逃逸（会崩 App），
        // 必须兜成「初始化失败 → UNAVAILABLE」。
        val engine = FakeTtsEngine().apply { initThrows = true }
        val reader = readerWith(engine)

        // 不抛异常 = 本用例通过；抛了这里直接红。
        reader.ensureInitialized()
        assertEquals(ReaderStatus.UNAVAILABLE, reader.state.value.status)
        assertTrue("失败后要释放刚建的引擎", engine.shutdownCount >= 1)
        reader.speak("你好。")
        assertTrue(engine.spoken.isEmpty())

        // 修好/换引擎后可以重试（ensureInitialized 本来就支持从 UNAVAILABLE 重建）。
        engine.initThrows = false
        reader.ensureInitialized()
        assertEquals(ReaderStatus.INITIALIZING, reader.state.value.status)
        assertEquals(2, engine.initCallCount)
        engine.completeInit(0)
        assertEquals(ReaderStatus.READY, reader.state.value.status)
    }

    // ------------------------------------------------------------ 未就绪时 speak

    @Test
    fun `speak while initializing is deferred until ready`() {
        val engine = FakeTtsEngine()
        val reader = readerWith(engine)
        reader.ensureInitialized()
        assertEquals(ReaderStatus.INITIALIZING, reader.state.value.status)

        // 初始化完成前不能 speak（官方类文档：A TextToSpeech instance can only be used
        // to synthesize text once it has completed its initialization）。
        reader.speak("我在呢。", rateX100 = 120)
        assertTrue(engine.spoken.isEmpty())

        engine.completeInit(0)
        assertEquals(ReaderStatus.READY, reader.state.value.status)
        assertEquals(listOf("我在呢。"), engine.spoken)
        assertEquals("语速要生效", 1.2f, engine.lastRate!!, 0.001f)
    }

    @Test
    fun `speak before ensure triggers initialization`() {
        val engine = FakeTtsEngine()
        val reader = readerWith(engine)
        reader.speak("先说的话。")
        assertEquals(ReaderStatus.INITIALIZING, reader.state.value.status)
        engine.completeInit(0)
        assertEquals(listOf("先说的话。"), engine.spoken)
    }

    @Test
    fun `only last pending reply is read after init`() {
        val engine = FakeTtsEngine()
        val reader = readerWith(engine)
        reader.ensureInitialized()
        reader.speak("旧的一句。")
        reader.speak("新的一句。")
        engine.completeInit(0)
        // 不排队补读旧回复：初始化期间只留最后一条。
        assertEquals(listOf("新的一句。"), engine.spoken)
    }

    @Test
    fun `blank feedback does not call speak`() {
        val engine = FakeTtsEngine()
        val reader = readyReader(engine)
        reader.speak("[[pz:emo=calm]]")
        reader.speak("   ")
        assertTrue(engine.spoken.isEmpty())
        assertFalse(reader.state.value.speaking)
    }

    // ------------------------------------------------------------ 清洗 / 上限

    @Test
    fun `cleans protocol tag and markdown before speaking`() {
        val engine = FakeTtsEngine()
        val reader = readyReader(engine)
        reader.speak("**今天**也在等你。[[pz:emo=calm]]")
        assertEquals(listOf("今天也在等你。"), engine.spoken)
    }

    @Test
    fun `honours the smaller of 2000 chars and engine limit`() {
        val engine = FakeTtsEngine().apply { maxInputLength = 20 }
        val reader = readyReader(engine)
        val text = "第一句话在这里。第二句话也很长很长，还有第三句呢。"
        reader.speak(text)
        val spoken = engine.spoken.single()
        assertTrue("不能超引擎上限：${spoken.length}", spoken.length <= 20)
        assertTrue("按整句截断：$spoken", spoken.endsWith("。"))
    }

    // ------------------------------------------------------------ 播报状态

    @Test
    fun `speaking turns on at speak and off at utterance done`() {
        val engine = FakeTtsEngine()
        val reader = readyReader(engine)
        reader.speak("你好。")
        assertTrue(reader.state.value.speaking)

        engine.finishLast()
        assertFalse(reader.state.value.speaking)
    }

    @Test
    fun `utterance error also clears speaking`() {
        val engine = FakeTtsEngine()
        val reader = readyReader(engine)
        reader.speak("你好。")
        engine.fail(engine.spokenIds.last())
        assertFalse(reader.state.value.speaking)
    }

    @Test
    fun `speak error does not mark as speaking`() {
        val engine = FakeTtsEngine().apply { speakStatus = -1 }
        val reader = readyReader(engine)
        reader.speak("你好。")
        assertFalse(reader.state.value.speaking)
    }

    // ------------------------------------------------------------ stop / shutdown

    @Test
    fun `stop clears speaking and is idempotent`() {
        val engine = FakeTtsEngine()
        val reader = readyReader(engine)
        reader.speak("你好。")
        assertTrue(reader.state.value.speaking)

        reader.stop()
        assertFalse(reader.state.value.speaking)
        // 幂等：再 stop 一次也不崩、状态稳定。
        reader.stop()
        assertFalse(reader.state.value.speaking)
        assertEquals(2, engine.stopCount)
    }

    @Test
    fun `stop then late done callback does not light speaking again`() {
        val engine = FakeTtsEngine()
        val reader = readyReader(engine)
        reader.speak("第一句。")
        val oldId = engine.spokenIds.last()
        reader.stop()
        assertFalse(reader.state.value.speaking)

        // stop 之后迟到的 onDone（Binder 线程时序）不能把「正在读」又点亮。
        engine.finish(oldId)
        assertFalse(reader.state.value.speaking)
    }

    @Test
    fun `speak after stop starts a new utterance and stale done is ignored`() {
        val engine = FakeTtsEngine()
        val reader = readyReader(engine)
        reader.speak("第一句。")
        val firstId = engine.spokenIds.last()
        reader.stop()
        reader.speak("第二句。")
        assertTrue(reader.state.value.speaking)

        engine.finish(firstId) // 第一句的迟到回调
        assertTrue("旧回调不能停掉新的一句", reader.state.value.speaking)

        engine.finish(engine.spokenIds.last())
        assertFalse(reader.state.value.speaking)
    }

    @Test
    fun `stop before ready clears pending`() {
        val engine = FakeTtsEngine()
        val reader = readerWith(engine)
        reader.ensureInitialized()
        reader.speak("还没轮到读。")
        reader.stop()
        engine.completeInit(0)
        assertTrue("stop 之后初始化成功也不补读", engine.spoken.isEmpty())
    }

    @Test
    fun `shutdown releases engine and is idempotent`() {
        val engine = FakeTtsEngine()
        val reader = readyReader(engine)
        reader.speak("你好。")

        reader.shutdown()
        assertEquals(ReaderStatus.SHUTDOWN, reader.state.value.status)
        assertFalse(reader.state.value.speaking)
        assertEquals(1, engine.shutdownCount)

        // 幂等：第二次不再重复 shutdown 引擎。
        reader.shutdown()
        assertEquals(1, engine.shutdownCount)
    }

    @Test
    fun `can reinitialize after shutdown`() {
        val engine = FakeTtsEngine()
        val reader = readyReader(engine)
        reader.shutdown()

        reader.speak("关掉之后再读。")
        assertEquals(ReaderStatus.INITIALIZING, reader.state.value.status)
        assertEquals(2, engine.initCallCount)
        engine.completeInit(0)
        assertEquals(listOf("关掉之后再读。"), engine.spoken)
    }

    // ------------------------------------------------------------ 音色

    @Test
    fun `filters chinese voices and prefers offline`() {
        val engine = FakeTtsEngine().apply {
            voicesList = listOf(
                ReadVoice(id = "en-us", languageTag = "en-US", requiresNetwork = false),
                ReadVoice(id = "zh-net", languageTag = "zh-CN", requiresNetwork = true),
                ReadVoice(id = "zh-offline", languageTag = "zh-CN", requiresNetwork = false),
            )
        }
        val reader = readyReader(engine)
        val voices = reader.state.value.voices
        assertEquals("只保留中文音色", listOf("zh-offline", "zh-net"), voices.map { it.id })
    }

    @Test
    fun `uses system default voice when it is chinese`() {
        val engine = FakeTtsEngine().apply {
            voicesList = listOf(
                ReadVoice(id = "zh-a", languageTag = "zh-CN", requiresNetwork = false),
                ReadVoice(id = "zh-b", languageTag = "zh-CN", requiresNetwork = false),
            )
            defaultVoice = "zh-b"
        }
        val reader = readyReader(engine)
        reader.speak("你好。", voiceId = "")
        assertEquals("系统默认是中文音色时用它", "zh-b", engine.lastVoiceId)
    }

    @Test
    fun `prefers offline chinese default when the engine default requires network`() {
        // ：引擎默认是「联网中文音色」，但列表里有离线中文音色时，
        // 默认音色也要离线优先（文字尽量不发到引擎厂商的云）。
        val engine = FakeTtsEngine().apply {
            voicesList = listOf(
                ReadVoice(id = "zh-net", languageTag = "zh-CN", requiresNetwork = true),
                ReadVoice(id = "zh-offline", languageTag = "zh-CN", requiresNetwork = false),
            )
            defaultVoice = "zh-net"
        }
        val reader = readyReader(engine)
        reader.speak("你好。", voiceId = "")
        assertEquals("默认音色也要离线优先", "zh-offline", engine.lastVoiceId)
    }

    @Test
    fun `keeps network engine default when there is no offline chinese voice`() {
        // 没有离线中文音色可退时，不能把用户/系统的默认音色也扔掉。
        val engine = FakeTtsEngine().apply {
            voicesList = listOf(
                ReadVoice(id = "zh-net-a", languageTag = "zh-CN", requiresNetwork = true),
                ReadVoice(id = "zh-net-b", languageTag = "zh-CN", requiresNetwork = true),
            )
            defaultVoice = "zh-net-b"
        }
        val reader = readyReader(engine)
        reader.speak("你好。", voiceId = "")
        assertEquals("zh-net-b", engine.lastVoiceId)
    }

    @Test
    fun `offline default does not override the user selected network voice`() {
        // 用户明确选了联网音色：尊重用户选择，不被「离线优先」改写。
        val engine = FakeTtsEngine().apply {
            voicesList = listOf(
                ReadVoice(id = "zh-offline", languageTag = "zh-CN", requiresNetwork = false),
                ReadVoice(id = "zh-net", languageTag = "zh-CN", requiresNetwork = true),
            )
            defaultVoice = "zh-offline"
        }
        val reader = readyReader(engine)
        reader.speak("你好。", voiceId = "zh-net")
        assertEquals("zh-net", engine.lastVoiceId)
    }

    @Test
    fun `falls back to first chinese voice when system default is not chinese`() {
        val engine = FakeTtsEngine().apply {
            voicesList = listOf(
                ReadVoice(id = "zh-net", languageTag = "zh-CN", requiresNetwork = true),
                ReadVoice(id = "zh-offline", languageTag = "zh-CN", requiresNetwork = false),
            )
            defaultVoice = "en-us"
        }
        val reader = readyReader(engine)
        reader.speak("你好。", voiceId = "")
        assertEquals("离线优先的第一个", "zh-offline", engine.lastVoiceId)
    }

    @Test
    fun `respects selected voice`() {
        val engine = FakeTtsEngine().apply {
            voicesList = listOf(
                ReadVoice(id = "zh-a", languageTag = "zh-CN", requiresNetwork = false),
                ReadVoice(id = "zh-b", languageTag = "zh-CN", requiresNetwork = true),
            )
        }
        val reader = readyReader(engine)
        reader.speak("你好。", voiceId = "zh-b")
        assertEquals("zh-b", engine.lastVoiceId)
    }

    @Test
    fun `unknown selected voice falls back to default`() {
        val engine = FakeTtsEngine().apply {
            voicesList = listOf(ReadVoice(id = "zh-a", languageTag = "zh-CN", requiresNetwork = false))
        }
        val reader = readyReader(engine)
        reader.speak("你好。", voiceId = "voice-installed-before")
        assertEquals("音色已不存在时退回默认，而不是静默不读", "zh-a", engine.lastVoiceId)
    }

    @Test
    fun `rate is clamped into the 0_8 to 1_5 range`() {
        val engine = FakeTtsEngine()
        val reader = readyReader(engine)
        reader.speak("你好。", rateX100 = 999)
        assertEquals(1.5f, engine.lastRate!!, 0.001f)

        reader.speak("你好。", rateX100 = 1)
        assertEquals(0.8f, engine.lastRate!!, 0.001f)
    }

    @Test
    fun `default rate is normal speed`() {
        val engine = FakeTtsEngine()
        val reader = readyReader(engine)
        reader.speak("你好。")
        assertEquals(1.0f, engine.lastRate!!, 0.001f)
    }
}
