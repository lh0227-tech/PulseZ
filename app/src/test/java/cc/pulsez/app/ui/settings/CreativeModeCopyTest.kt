package cc.pulsez.app.ui.settings

import cc.pulsez.app.ui.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 创作模式文案回归（纯 JVM，不碰 Android / Compose / Room）。
 *
 *
 * -（文案内容，**逐字**）、 //（模式边界）；
 * -（说明页结构与反例）；
 * -（禁用词表）、（角色代词只用 TA）。
 *
 * 为什么只测常量：Compose 页面里的字面量没法在纯 JVM 里反射收集（同 `CopyPolicyTest` 的做法）。
 * 所以 把用户可见文案收口到两个入口——[CreativeModeCopy]（设置行 + 首次说明）
 * 与 [ChatViewModel.EXIT_CREATIVE_MODE_NOTICE]（出戏轻提示）——本测试只断言这两处。
 *
 * 边界：说明页**不含**热线号码（12356 只在真的出现风险信号时的危机兜底里，见 `CrisisDetector`，
 * 由 覆盖）；本测试反过来断言"说明里没有号码、但说了会给找人的方式"。
 */
class CreativeModeCopyTest {

    companion object {
        /** 禁用词（"模型""密钥""地址"是允许的例外，这里也不该出现）。 */
        private val TECH_BANNED: List<String> =
            listOf("token", "上下文", "窗口", "向量", "embedding", "缓存", "关键词", "记忆检索")

        /** 角色代词只允许 TA。 */
        private val PRONOUN_BANNED: List<String> = listOf("她", "他")

        /** 产品/合规额外禁用：不做"成人/无限制"式暗示。 */
        private val TONE_BANNED: List<String> = listOf("成人", "无限制", "我们", "系统检测")

        /** 说明页不写号码（ 只给"能马上找到人的方式"）。 */
        private const val CRISIS_HOTLINE = "12356"

        /**
 * 说明每行的字数上限。
 *
 * 的三行说明实际是 21 / 32 / 17 字（第 2 句最长，32 字），
 * 这里给到 36 字的上限：既能拦住后续把说明写成长段落，又不用因为改个标点就红。
 */
        private const val MAX_LINE_CHARS = 36

        /** 本产出的全部用户可见文案（名称 → 文案），供批量断言禁用词。 */
        private fun allCopy(): List<Pair<String, String>> = listOf(
            "设置行标题" to CreativeModeCopy.SETTING_TITLE,
            "设置行说明" to CreativeModeCopy.SETTING_DESCRIPTION,
            "说明标题" to CreativeModeCopy.DIALOG_TITLE,
            "说明第 1 句" to CreativeModeCopy.INTRO_LINE_1,
            "说明第 2 句" to CreativeModeCopy.INTRO_LINE_2,
            "说明第 3 句" to CreativeModeCopy.INTRO_LINE_3,
            "按钮·开启" to CreativeModeCopy.CONFIRM,
            "按钮·先不用" to CreativeModeCopy.DISMISS,
            "出戏轻提示" to ChatViewModel.EXIT_CREATIVE_MODE_NOTICE,
        )
    }

    // ------------------------------------------------------------ 逐字文案

    @Test
    fun copyIsVerbatimPerPlan() {
        // 文案需与配置常量保持一致。
        assertEquals("创作模式", CreativeModeCopy.SETTING_TITLE)
        assertEquals("角色扮演、强冲突剧情或写长故事时打开：回复更长更入戏；平时闲聊不用开。", CreativeModeCopy.SETTING_DESCRIPTION)
        assertEquals("创作模式", CreativeModeCopy.DIALOG_TITLE)
        assertEquals("写长故事或强冲突角色扮演时，TA 会一直在戏里接住你。", CreativeModeCopy.INTRO_LINE_1)
        assertEquals(
            "只有你说要伤害自己时，这里会先停下来，给几个能马上找到人的方式。",
            CreativeModeCopy.INTRO_LINE_2,
        )
        assertEquals("平时闲聊不用开。随时能从聊天页右上角点「出戏」关掉。", CreativeModeCopy.INTRO_LINE_3)
        assertEquals("开启", CreativeModeCopy.CONFIRM)
        assertEquals("先不用", CreativeModeCopy.DISMISS)
        assertEquals("回到平常聊天了。", ChatViewModel.EXIT_CREATIVE_MODE_NOTICE)
    }

    @Test
    fun introHasExactlyThreeLinesInOrder() {
        assertEquals("说明正文必须正好三行", 3, CreativeModeCopy.INTRO_LINES.size)
        assertEquals(
            listOf(
                CreativeModeCopy.INTRO_LINE_1,
                CreativeModeCopy.INTRO_LINE_2,
                CreativeModeCopy.INTRO_LINE_3,
            ),
            CreativeModeCopy.INTRO_LINES,
        )
        CreativeModeCopy.INTRO_LINES.forEach { line ->
            assertTrue("说明行不能为空", line.isNotBlank())
        }
    }

    // ------------------------------------------------------------ 文案纪律

    @Test
    fun copyAvoidsTechnicalWordsAndWrongPronouns() {
        allCopy().forEach { (name, text) ->
            TECH_BANNED.forEach { word ->
                assertFalse("「$name」出现技术词「$word」：$text", text.contains(word))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("「$name」出现角色代词「$pronoun」（只许 TA）：$text", text.contains(pronoun))
            }
            TONE_BANNED.forEach { word ->
                assertFalse("「$name」出现不该有的说法「$word」：$text", text.contains(word))
            }
        }
    }

    @Test
    fun introUsesTaAndSaysItWillNotBreakCharacter() {
        // 第 1 句：TA 不出戏；代词必须是 TA。
        assertTrue("说明第 1 句要出现 TA", CreativeModeCopy.INTRO_LINE_1.contains("TA"))
        assertTrue("说明第 1 句要说明会一直在戏里", CreativeModeCopy.INTRO_LINE_1.contains("一直在戏里"))
    }

    @Test
    fun introExplainsTheOnlyInterruptionInPlainWordsWithoutHotline() {
        //：第 3 句结构 = "只有在你可能伤到自己时，我会停一下，给你找人的方式"；
        // 说明页不放号码（号码留给危机兜底），也不写"承诺不伤害自己"这类无效契约。
        val line = CreativeModeCopy.INTRO_LINE_2
        assertTrue("要说明什么情况下会打断：$line", line.contains("伤害自己"))
        assertTrue("要说会停下来：$line", line.contains("停下来"))
        assertTrue("要给'能马上找到人的方式'：$line", line.contains("能马上找到人的方式"))
        allCopy().forEach { (name, text) ->
            assertFalse("「$name」不该出现热线号码 $CRISIS_HOTLINE：$text", text.contains(CRISIS_HOTLINE))
            assertFalse("「$name」不该出现'承诺'说法：$text", text.contains("承诺"))
            assertFalse("「$name」不该出现'检测到'式审判词：$text", text.contains("检测到"))
        }
    }

    @Test
    fun introLinesAreShortEnough() {
        CreativeModeCopy.INTRO_LINES.forEach { line ->
            assertTrue(
                "说明行太长（${line.length} 字 > $MAX_LINE_CHARS）：$line",
                line.length <= MAX_LINE_CHARS,
            )
        }
        // 设置行说明是一行小字，比正文更短（"一句话能说完就别说两句"）。
        assertTrue(
            "设置行说明太长（${CreativeModeCopy.SETTING_DESCRIPTION.length} 字）",
            CreativeModeCopy.SETTING_DESCRIPTION.length <= MAX_LINE_CHARS,
        )
    }

    @Test
    fun exitNoticeIsPlainAndNeutral() {
        // 出戏提示：说人话、不出现"模式/设置/已退出"这类系统腔；也不挽留。
        val notice = ChatViewModel.EXIT_CREATIVE_MODE_NOTICE
        assertEquals("回到平常聊天了。", notice)
        assertFalse("出戏提示不该出现'模式'：$notice", notice.contains("模式"))
        assertFalse("出戏提示不该出现'已退出'：$notice", notice.contains("已退出"))
        assertFalse("出戏提示不该挽留：$notice", notice.contains("再") && notice.contains("等"))
    }
}
