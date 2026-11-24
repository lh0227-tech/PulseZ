package cc.pulsez.app.compliance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrisisDetectorTest {

    @Test
    fun `普通消息不触发`() {
        assertEquals(CrisisDetector.Level.NONE, CrisisDetector.evaluate("今天面试好紧张"))
        assertNull(CrisisDetector.fallbackLine(CrisisDetector.Level.NONE))
    }

    @Test
    fun `明确自伤词触发 critical`() {
        assertEquals(CrisisDetector.Level.CRITICAL, CrisisDetector.evaluate("我不想活了"))
        assertEquals(CrisisDetector.Level.CRITICAL, CrisisDetector.evaluate("I want to kill myself"))
    }

    @Test
    fun `负面词触发 concern`() {
        assertEquals(CrisisDetector.Level.CONCERN, CrisisDetector.evaluate("最近真的好累，感觉没意思"))
    }

    @Test
    fun `连续负面升级为 critical`() {
        val msgs = listOf("好累啊", "没希望了", "撑不住了")
        assertEquals(CrisisDetector.Level.CRITICAL, CrisisDetector.evaluateRecent(msgs))
    }

    @Test
    fun `连续两条负面是 concern`() {
        val msgs = listOf("今天有点崩溃", "感觉没人需要我")
        assertEquals(CrisisDetector.Level.CONCERN, CrisisDetector.evaluateRecent(msgs))
    }

    @Test
    fun `兜底话术必须包含找人`() {
        val line = CrisisDetector.fallbackLine(CrisisDetector.Level.CRITICAL)
        requireNotNull(line)
        // 不再接受"联系人"这个旧说法（应用没有紧急联系人功能）。
        assertTrue("兜底话术要给现实里的人：$line", line.contains("找个人"))
        assertTrue("也要给身边信得过的人：$line", line.contains("身边信得过的人"))
    }

    // ---------------------------------------------------------------- 危机兜底升级

    @Test
    fun `普通兜底有现实资源且不写24小时`() {
        val line = requireNotNull(CrisisDetector.fallbackLine(CrisisDetector.Level.CRITICAL))
        // 逐字断言： 修复后不再出现"你设的紧急联系人"。
        assertEquals(
            "如果这几天一直这样，还是找个人聊聊比较好，真的。" +
                "心理援助热线 12356；有马上危险就打 120。也可以联系你身边信得过的人。",
            line,
        )
        assertTrue("要给全国心理援助热线 12356：$line", line.contains("12356"))
        assertTrue("有马上危险要打 120：$line", line.contains("120"))
        assertTrue("要给可兑现的求助路径（身边信得过的人）：$line", line.contains("身边信得过的人"))
        assertFalse("不许提未实现的「紧急联系人」（B4）：$line", line.contains("紧急联系人"))
        assertTrue("原来那句'找个人聊聊'不能丢：$line", line.contains("找个人"))
        assertFalse("12356 官方口径是每日不少于 18 小时，不能写\"24 小时\"：$line", line.contains("24 小时"))
        assertFalse("不许承诺\"永远/绝对\"：$line", line.contains("永远") || line.contains("绝对"))
    }

    @Test
    fun `创作模式兜底先承认戏里的台词再给现实资源`() {
        val creative = requireNotNull(
            CrisisDetector.fallbackLine(CrisisDetector.Level.CRITICAL, creativeMode = true),
        )
        // 逐字断言。
        assertEquals(
            "如果这是戏里的台词，就当我没打断。" +
                "如果你真的很难受：心理援助热线 12356；有马上危险就打 120。" +
                "也可以联系你身边信得过的人。",
            creative,
        )
        assertTrue("先给沉浸一个台阶（不再把创作者当危机）：$creative", creative.contains("戏里的台词"))
        assertTrue("创作版同样要给 12356：$creative", creative.contains("12356"))
        assertTrue("创作版同样要给 120：$creative", creative.contains("120"))
        assertTrue("创作版同样要给可兑现的求助路径：$creative", creative.contains("身边信得过的人"))
        assertFalse("创作版也不许提未实现的「紧急联系人」（B4）：$creative", creative.contains("紧急联系人"))
        assertFalse("不能写\"24 小时\"：$creative", creative.contains("24 小时"))
        assertFalse("不许承诺\"永远/绝对\"：$creative", creative.contains("永远") || creative.contains("绝对"))
        assertNotEquals(
            "创作模式必须换文案，不能与普通模式相同",
            CrisisDetector.fallbackLine(CrisisDetector.Level.CRITICAL),
            creative,
        )
    }

    @Test
    fun `默认参数与旧调用完全一致`() {
        // 旧调用点（ChatViewModel / CrisisReplyGuard）不传 creativeMode，行为必须与 的签名语义一致。
        assertEquals(
            CrisisDetector.fallbackLine(CrisisDetector.Level.CRITICAL),
            CrisisDetector.fallbackLine(CrisisDetector.Level.CRITICAL, creativeMode = false),
        )
        assertEquals(
            "CONCERN 级不贴热线，每次回复都贴会脱敏，创作模式也不改它",
            CrisisDetector.fallbackLine(CrisisDetector.Level.CONCERN),
            CrisisDetector.fallbackLine(CrisisDetector.Level.CONCERN, creativeMode = true),
        )
        assertNull(CrisisDetector.fallbackLine(CrisisDetector.Level.NONE, creativeMode = true))
    }

    @Test
    fun `连续三条纯日常倾诉只判 concern 不升 critical`() {
        val msgs = listOf("今天好累", "感觉要崩溃了", "生活好没意思")
        assertEquals(CrisisDetector.Level.CONCERN, CrisisDetector.evaluateRecent(msgs))
    }

    @Test
    fun `戏词与口癖不误触危机`() {
        val harmless = listOf(
            "你去死吧",
            "去死吧",
            "让他去死",
            "想死你了",
            "困得想死",
            "笑得想死",
            "让你永远消失",
            "TA想死吗",
        )
        harmless.forEach { text ->
            assertEquals("「$text」不应误判为危机", CrisisDetector.Level.NONE, CrisisDetector.evaluate(text))
        }
    }

    @Test
    fun `遗书与计划词触发 critical`() {
        assertEquals(CrisisDetector.Level.CRITICAL, CrisisDetector.evaluate("写好了遗书"))
        assertEquals(CrisisDetector.Level.CRITICAL, CrisisDetector.evaluate("已经准备好告别，真的绝望了"))
        assertEquals(
            CrisisDetector.Level.CRITICAL,
            CrisisDetector.evaluateRecent(listOf("最近觉得没希望", "已经写完遗书了")),
        )
    }
}
