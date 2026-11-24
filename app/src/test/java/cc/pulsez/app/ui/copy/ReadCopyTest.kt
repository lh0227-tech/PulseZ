package cc.pulsez.app.ui.copy

import cc.pulsez.app.audio.ReadVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动朗读文案回归（纯 JVM，不碰 Android / Compose / Room）。
 *
 *
 * -「朗读（首次开启自动朗读时，AlertDialog）」——标题 / 三段正文 /
 * 按钮**逐字**； 措辞口径；
 * -（禁用词表：token / 上下文 / 窗口 / 向量 / embedding / TTS / 语音合成 …）；
 * - （不写「免费/不额外收费」绝对承诺、不写「本版未提供」）、
 * （初始化失败要给「装语音包/换引擎」的出路）、（文案细则）、（无障碍描述说人话）；
 * - （角色代词只用 TA；三问：是什么 / 要不要用 / 不用会怎样）。
 *
 * 只测常量：Compose 页面里的字面量没法在纯 JVM 里反射收集（同 `CreativeModeCopyTest` 做法）。
 */
class ReadCopyTest {

    companion object {
        /** 禁用词（"模型""密钥""地址"是配置页允许的例外，不在此列）。 */
        private val TECH_BANNED: List<String> = listOf(
            "token", "上下文", "窗口", "向量", "embedding", "Embedding",
            "记忆检索", "关键词", "缓存", "TTS", "语音合成", "合成引擎",
        )

        /** 角色代词只允许 TA。 */
        private val PRONOUN_BANNED: List<String> = listOf("她", "他")

        /** 产品/合规额外禁用：绝对承诺、制造不存在功能、客服腔。 */
        private val PROMISE_BANNED: List<String> = listOf(
            "免费",
            "不额外收费",
            "永久",
            "绝对",
            "本版未提供",
            "我们",
            "智能朗读",
            "AI 朗读",
            "全新升级",
        )

        /** 本产出的全部用户可见文案（名称 → 文案），供批量断言。 */
        private fun allCopy(): List<Pair<String, String>> = listOf(
            "首次说明标题" to ReadCopy.DIALOG_TITLE,
            "首次说明第 1 句" to ReadCopy.INTRO_LINE_1,
            "首次说明第 2 句" to ReadCopy.INTRO_LINE_2,
            "首次说明第 3 句" to ReadCopy.INTRO_LINE_3,
            "按钮·开启" to ReadCopy.CONFIRM,
            "按钮·先不用" to ReadCopy.DISMISS,
            "按钮·知道了" to ReadCopy.CLOSE,
            "设置组标题" to ReadCopy.GROUP,
            "设置行标题" to ReadCopy.SETTING_TITLE,
            "设置行说明" to ReadCopy.SETTING_DESCRIPTION,
            "语速行标题" to ReadCopy.RATE_TITLE,
            "语速行说明" to ReadCopy.RATE_DESCRIPTION,
            "音色行标题" to ReadCopy.VOICE_TITLE,
            "音色行说明" to ReadCopy.VOICE_DESCRIPTION,
            "音色行说明（无列表）" to ReadCopy.VOICE_DESCRIPTION_EMPTY,
            "音色值·系统默认" to ReadCopy.VOICE_SYSTEM_DEFAULT,
            "当前方式标题" to ReadCopy.METHOD_TITLE,
            "当前方式值" to ReadCopy.METHOD_VALUE,
            "当前方式说明" to ReadCopy.METHOD_DESCRIPTION,
            "说明回看标题" to ReadCopy.INTRO_ENTRY_TITLE,
            "说明回看值" to ReadCopy.INTRO_ENTRY_VALUE,
            "不可用说明" to ReadCopy.UNAVAILABLE,
            "播放提示" to ReadCopy.READING,
            "停止按钮" to ReadCopy.STOP,
            "播放提示读屏" to ReadCopy.READING_CONTENT_DESCRIPTION,
            "停止按钮读屏" to ReadCopy.STOP_CONTENT_DESCRIPTION,
        )
    }

    // ------------------------------------------------------------ 逐字定稿

    @Test
    fun introDialogCopyIsVerbatimPerPlan() {
        //「朗读」：任何改动都必须先改文档再改这里。
        assertEquals("让 TA 读出来", ReadCopy.DIALOG_TITLE)
        assertEquals(
            "用你手机自带的朗读把 TA 的回复读出来，不经过你填的 AI，也不按次收费。",
            ReadCopy.INTRO_LINE_1,
        )
        assertEquals(
            "音色、好不好听、能不能离线，取决于你的手机；部分手机的朗读会联网。",
            ReadCopy.INTRO_LINE_2,
        )
        assertEquals("随时能在设置 → 声音里关掉。", ReadCopy.INTRO_LINE_3)
        assertEquals("开启", ReadCopy.CONFIRM)
        assertEquals("先不用", ReadCopy.DISMISS)
    }

    @Test
    fun introHasExactlyThreeLinesInOrder() {
        assertEquals("说明正文必须正好三行", 3, ReadCopy.INTRO_LINES.size)
        assertEquals(
            listOf(ReadCopy.INTRO_LINE_1, ReadCopy.INTRO_LINE_2, ReadCopy.INTRO_LINE_3),
            ReadCopy.INTRO_LINES,
        )
        ReadCopy.INTRO_LINES.forEach { line ->
            assertTrue("说明行不能为空", line.isNotBlank())
        }
    }

    @Test
    fun introAnswersWhoPaysAndWhereToTurnItOff() {
        // 三问 / 谁付钱 / 怎么关。
        assertTrue("要写清不经过你填的 AI", ReadCopy.INTRO_LINE_1.contains("不经过你填的 AI"))
        assertTrue("要写清不按次收费（不是「免费」）", ReadCopy.INTRO_LINE_1.contains("不按次收费"))
        assertTrue("音色/离线取决于手机", ReadCopy.INTRO_LINE_2.contains("取决于你的手机"))
        assertTrue("要提醒部分手机会联网", ReadCopy.INTRO_LINE_2.contains("会联网"))
        assertTrue("要说清在哪关", ReadCopy.INTRO_LINE_3.contains("设置 → 声音"))
        assertTrue(ReadCopy.INTRO_LINE_3.contains("关掉"))
    }

    // ------------------------------------------------------------ 文案纪律

    @Test
    fun copyAvoidsBannedWordsAndPromises() {
        allCopy().forEach { (name, text) ->
            TECH_BANNED.forEach { word ->
                assertFalse("「$name」出现技术词「$word」：$text", text.contains(word))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("「$name」出现角色代词「$pronoun」（只许 TA）：$text", text.contains(pronoun))
            }
            PROMISE_BANNED.forEach { phrase ->
                assertFalse("「$name」出现不该有的说法「$phrase」：$text", text.contains(phrase))
            }
        }
    }

    @Test
    fun unavailableExplainsTheProblemAndGivesAWayOut() {
        // 4 + ：不能只静默失败，要给「装语音包/换引擎」的下一步。
        assertTrue("要说明这台手机暂时不能朗读", ReadCopy.UNAVAILABLE.contains("这台手机暂时不能朗读"))
        assertTrue("要给出路：装语音包", ReadCopy.UNAVAILABLE.contains("语音包"))
        assertTrue("要给出路：换系统朗读服务", ReadCopy.UNAVAILABLE.contains("系统朗读服务"))
        assertTrue("要指到系统设置", ReadCopy.UNAVAILABLE.contains("系统设置"))
        assertFalse("不是用户做错了什么：不用「失败」", ReadCopy.UNAVAILABLE.contains("失败"))
    }

    @Test
    fun settingCopyAnswersWhatAndWhatIfNotUsed() {
        assertTrue("说清读的是 TA 的回复", ReadCopy.SETTING_TITLE.contains("TA"))
        assertTrue("说清会自动读", ReadCopy.SETTING_DESCRIPTION.contains("自动读"))
        assertTrue("说清不用也能聊天", ReadCopy.SETTING_DESCRIPTION.contains("不读也能照常聊天"))
        assertTrue("当前方式说清是系统朗读", ReadCopy.METHOD_VALUE == "系统朗读")
        assertTrue("当前方式说清不经过你填的 AI", ReadCopy.METHOD_DESCRIPTION.contains("不经过你填的 AI"))
    }

    @Test
    fun playbackHintIsAccessibleAndPlain() {
        assertEquals("正在朗读……", ReadCopy.READING)
        assertEquals("停下", ReadCopy.STOP)
        assertTrue("读屏描述就说这句是什么", ReadCopy.READING_CONTENT_DESCRIPTION.contains("正在朗读"))
        assertTrue("读屏描述要说能停下", ReadCopy.READING_CONTENT_DESCRIPTION.contains("停下"))
        assertTrue("停止按钮读屏要唯一、说目的", ReadCopy.STOP_CONTENT_DESCRIPTION.contains("停下"))
    }

    // ------------------------------------------------------------ 语音值 / 语速值

    @Test
    fun voiceValueIsPlainLanguage() {
        val voices = listOf(
            ReadVoice(id = "zh-a", languageTag = "zh-CN", requiresNetwork = false),
            ReadVoice(id = "zh-b", languageTag = "zh-CN", requiresNetwork = true),
        )
        assertEquals("空 id = 系统默认", "系统默认", ReadCopy.voiceValue("", voices))
        assertEquals("找不到的旧 id 退回系统默认", "系统默认", ReadCopy.voiceValue("gone", voices))
        assertEquals("第 1 个（离线）", "音色 1（离线）", ReadCopy.voiceValue("zh-a", voices))
        assertEquals("第 2 个（联网）", "音色 2（联网）", ReadCopy.voiceValue("zh-b", voices))
        assertEquals("没有可选音色时也是系统默认", "系统默认", ReadCopy.voiceValue("zh-a", emptyList()))
    }

    @Test
    fun nextVoiceCyclesBackToSystemDefault() {
        val voices = listOf(
            ReadVoice(id = "zh-a", languageTag = "zh-CN", requiresNetwork = false),
            ReadVoice(id = "zh-b", languageTag = "zh-CN", requiresNetwork = true),
        )
        assertEquals("默认 → 第 1 个", "zh-a", ReadCopy.nextVoiceId("", voices))
        assertEquals("第 1 个 → 第 2 个", "zh-b", ReadCopy.nextVoiceId("zh-a", voices))
        assertEquals("最后一个 → 默认", "", ReadCopy.nextVoiceId("zh-b", voices))
        assertEquals("没得选就保持默认", "", ReadCopy.nextVoiceId("", emptyList()))
    }

    @Test
    fun rateLabelsAndCycleStayInTheSupportedRange() {
        assertEquals("慢", ReadCopy.rateLabel(80))
        assertEquals("正常", ReadCopy.rateLabel(100))
        assertEquals("快", ReadCopy.rateLabel(120))
        assertEquals("更快", ReadCopy.rateLabel(150))
        assertEquals("非档位值按最近档显示", "正常", ReadCopy.rateLabel(110))

        assertEquals(120, ReadCopy.nextRate(100))
        assertEquals(150, ReadCopy.nextRate(120))
        assertEquals("最快之后回到最慢", 80, ReadCopy.nextRate(150))
        assertEquals("默认是 1.0 倍", 100, ReadCopy.DEFAULT_RATE_X100)
        ReadCopy.RATES_X100.forEach { rate ->
            assertTrue("语速档位必须在 0.8–1.5：$rate", rate in 80..150)
        }
    }
}
