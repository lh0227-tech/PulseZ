package cc.pulsez.app.audio

import cc.pulsez.app.data.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只读新完成的回复」判定。纯 JVM。
 *
 * 「只读新完成的 assistant 回复；流式过程中不读」、
 * 4； （把判定抽成纯类，ViewModel 只做接线）；
 * （ 自动朗读仅 1:1）；
 * （离开聊天页/退后台/切角色之后完成的新回复不读）。
 */
class ReadPolicyTest {

    private fun settings(
        autoRead: Boolean = true,
        rateX100: Int = 120,
        voiceId: String = "zh-offline",
    ) = Settings(autoRead = autoRead, readRateX100 = rateX100, readVoiceId = voiceId)

    /** 基线：就在本角色聊天页且 App 在前台。 */
    private val visiblePage = ReadPolicy.PageContext.VISIBLE

    @Test
    fun `reads a finished new reply when enabled`() {
        val plan = ReadPolicy.plan(
            settings = settings(),
            replyText = "今天也在等你。",
            replyFinished = true,
            page = visiblePage,
        )
        assertNotNull(plan)
        assertEquals("今天也在等你。", plan!!.text)
        assertEquals(120, plan.rateX100)
        assertEquals("zh-offline", plan.voiceId)
    }

    @Test
    fun `does not read while streaming`() {
        assertFalse(
            ReadPolicy.shouldRead(
                autoRead = true,
                isGroupChat = false,
                replyText = "说到一半",
                replyFinished = false,
                page = visiblePage,
            ),
        )
        assertNull(
            ReadPolicy.plan(
                settings = settings(),
                replyText = "说到一半",
                replyFinished = false,
                page = visiblePage,
            ),
        )
    }

    @Test
    fun `does not read when the switch is off`() {
        assertFalse(
            ReadPolicy.shouldRead(
                autoRead = false,
                isGroupChat = false,
                replyText = "说完了。",
                replyFinished = true,
                page = visiblePage,
            ),
        )
        assertNull(
            ReadPolicy.plan(
                settings = settings(autoRead = false),
                replyText = "说完了。",
                replyFinished = true,
                page = visiblePage,
            ),
        )
    }

    @Test
    fun `does not read blank replies`() {
        assertFalse(ReadPolicy.shouldRead(true, false, "", true, visiblePage))
        assertFalse(ReadPolicy.shouldRead(true, false, "   ", true, visiblePage))
        assertNull(
            ReadPolicy.plan(settings = settings(), replyText = "", replyFinished = true, page = visiblePage),
        )
    }

    @Test
    fun `does not read group chats in v0_5_0`() {
        // 自动朗读仅 1:1；群聊不碰。
        assertFalse(
            ReadPolicy.shouldRead(
                autoRead = true,
                isGroupChat = true,
                replyText = "大家好。",
                replyFinished = true,
                page = visiblePage,
            ),
        )
        assertNull(
            ReadPolicy.plan(
                settings = settings(),
                replyText = "大家好。",
                replyFinished = true,
                page = visiblePage,
                isGroupChat = true,
            ),
        )
    }

    // ------------------------------------------------------------ ：可见性门

    @Test
    fun `does not read a reply finished in the background`() {
        // 退后台/锁屏之后才完成的回复：即使还在本角色聊天页，也不能开读。
        val background = ReadPolicy.PageContext(pageVisible = true, appForeground = false)
        assertFalse(ReadPolicy.shouldRead(true, false, "你还在吗？", true, background))
        assertNull(
            ReadPolicy.plan(settings = settings(), replyText = "你还在吗？", replyFinished = true, page = background),
        )
    }

    @Test
    fun `does not read a reply finished on another page or another character`() {
        // 离开聊天页（回首页/进设置/进记忆/进群聊）→ pageVisible=false。
        val awayFromPage = ReadPolicy.PageContext(pageVisible = false, appForeground = true)
        // 切到另一个角色的聊天页：对“上一个角色缓存的 ViewModel”来说也是 pageVisible=false。
        assertFalse(ReadPolicy.shouldRead(true, false, "我回来了。", true, awayFromPage))
        assertNull(
            ReadPolicy.plan(settings = settings(), replyText = "我回来了。", replyFinished = true, page = awayFromPage),
        )
    }

    @Test
    fun `reads only when the page is visible and the app is in the foreground`() {
        // 三组对照，唯一允许读的是「在页且前台」。
        assertFalse(ReadPolicy.shouldRead(true, false, "好。", true, ReadPolicy.PageContext(false, false)))
        assertFalse(ReadPolicy.shouldRead(true, false, "好。", true, ReadPolicy.PageContext(false, true)))
        assertFalse(ReadPolicy.shouldRead(true, false, "好。", true, ReadPolicy.PageContext(true, false)))
        assertTrue(ReadPolicy.shouldRead(true, false, "好。", true, ReadPolicy.PageContext(true, true)))
    }

    /**
 * 接线级断言（ 修法 5 / S10②）：
 * `ChatViewModel.maybeAutoRead` 调用的就是 [ReadPolicy.planForFinishedReply]。
 *
 * 这个用例是「删掉可见性门会红」的证据：如果 [ReadPolicy.planForFinishedReply] 忽略
 * [ReadVisibility.Snapshot]（不按当前角色算 `pageVisible`），或 [ReadPolicy.shouldRead]
 * 少了「前台」/「本角色页」任一条件，下面的 null 断言就会失败。
 * ViewModel 里那一行快速门即使被删，这条权威判定仍在；反过来若把这里的判定删掉，本用例立刻变红。
 */
    @Test
    fun `finished reply gate follows the latest visibility snapshot`() {
        val visibility = ReadVisibility()
        val characterId = "char-a"
        val settings = settings()

        // 还没进聊天页/还没标记前台：不读。
        assertNull(ReadPolicy.planForFinishedReply(settings, "在吗。", characterId, visibility.snapshot))

        // 在页且前台：照常读（回归： 修复不能把正常路径也挡住）。
        visibility.setAppForeground(true)
        visibility.setVisibleChatCharacter(characterId)
        val plan = ReadPolicy.planForFinishedReply(settings, "在吗。", characterId, visibility.snapshot)
        assertNotNull(plan)
        assertEquals("在吗。", plan!!.text)

        // 切到另一个角色：旧会话的这条回复不读。
        visibility.setVisibleChatCharacter("char-b")
        assertNull(ReadPolicy.planForFinishedReply(settings, "在吗。", characterId, visibility.snapshot))

        // 回首页/进设置（没有聊天页）：不读。
        visibility.setVisibleChatCharacter(null)
        assertNull(ReadPolicy.planForFinishedReply(settings, "在吗。", characterId, visibility.snapshot))

        // 退后台/锁屏：不读。
        visibility.setVisibleChatCharacter(characterId)
        visibility.setAppForeground(false)
        assertNull(ReadPolicy.planForFinishedReply(settings, "在吗。", characterId, visibility.snapshot))

        // 回到前台且在本角色页：又能读。
        visibility.setAppForeground(true)
        assertNotNull(ReadPolicy.planForFinishedReply(settings, "在吗。", characterId, visibility.snapshot))
    }

    @Test
    fun `dirty rate is clamped into the supported range`() {
        val high = ReadPolicy.plan(
            settings = settings(rateX100 = 999),
            replyText = "好。",
            replyFinished = true,
            page = visiblePage,
        )
        assertEquals(150, high!!.rateX100)
        val low = ReadPolicy.plan(
            settings = settings(rateX100 = 1),
            replyText = "好。",
            replyFinished = true,
            page = visiblePage,
        )
        assertEquals(80, low!!.rateX100)
    }

    @Test
    fun `default voice stays empty and means system default`() {
        val plan = ReadPolicy.plan(
            settings = settings(voiceId = ""),
            replyText = "好。",
            replyFinished = true,
            page = visiblePage,
        )
        assertEquals("", plan!!.voiceId)
        assertTrue(plan.voiceId.isBlank())
    }
}
