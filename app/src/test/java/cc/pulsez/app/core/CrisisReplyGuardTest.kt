package cc.pulsez.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrisisReplyGuard] 的纯逻辑测试。
 *
 * 回归点：1:1 在 `ChatViewModel` 里有危机兜底，群聊的 send / retry 完全没有 → 群聊可以绕过
 * 的兜底话术。这里只测追加逻辑这个纯函数，不启动 ViewModel / 不连模型。
 */
class CrisisReplyGuardTest {

    private val reply = "那就先别想太多，我在这儿。"

    @Test
    fun criticalMessageAppendsHotlineSuggestion() {
        val guarded = CrisisReplyGuard.guard(reply, listOf("我真的不想活了"))

        assertTrue("原正文要保留", guarded.startsWith(reply))
        assertTrue("必须追加「找个人 / 身边信得过的人」类兜底", guarded.contains("找个人"))
        // ：全仓没有「紧急联系人」功能，危机兜底文案改为可兑现的「身边信得过的人」。
        assertTrue("兜底句要给可兑现的求助路径：$guarded", guarded.contains("身边信得过的人"))
    }

    @Test
    fun concernMessageAppendsTheGentlerLine() {
        val guarded = CrisisReplyGuard.guard("嗯，我在。", listOf("今天好累，感觉撑不住"))

        assertTrue(guarded.startsWith("嗯，我在。"))
        assertTrue("CONCERN 用较轻的一句", guarded.contains("信得过的人"))
        assertFalse("CONCERN 不该直接甩出紧急联系人", guarded.contains("联系人"))
    }

    @Test
    fun neutralMessageKeepsReplyUnchanged() {
        assertEquals(reply, CrisisReplyGuard.guard(reply, listOf("今天吃了面", "在吗", "晚上好")))
    }

    @Test
    fun emptyHistoryKeepsReplyUnchanged() {
        assertEquals(reply, CrisisReplyGuard.guard(reply, emptyList()))
    }

    @Test
    fun existingCoveringSentenceIsNotDuplicated() {
        val already = "先找个人聊聊吧，我在这儿。"
        assertEquals(already, CrisisReplyGuard.guard(already, listOf("我想死")))

        val withContact = "你设的联系人可以联系一下。"
        assertEquals(withContact, CrisisReplyGuard.guard(withContact, listOf("我想死")))
    }

    @Test
    fun guardIsIdempotent() {
        val once = CrisisReplyGuard.guard(reply, listOf("我想死"))
        val twice = CrisisReplyGuard.guard(once, listOf("我想死"))
        assertEquals("重复调用不应追加第二遍", once, twice)
    }

    @Test
    fun onlyLastThreeUserMessagesCount() {
        // 危机消息在窗口外（4 条之前）：按 1:1 口径只看最近 3 条，不触发
        val guarded = CrisisReplyGuard.guard(
            reply,
            listOf("我想死", "今天吃了面", "在吗", "晚上好"),
        )
        assertEquals(reply, guarded)
    }

    @Test
    fun currentTurnIsIncludedInTheWindow() {
        // 窗口里最后一条就是本轮：必须触发
        val guarded = CrisisReplyGuard.guard(
            reply,
            listOf("今天吃了面", "在吗", "我想死"),
        )
        assertTrue(guarded.contains("找个人"))
    }

    @Test
    fun blankReplyStillCarriesTheFallbackLine() {
        val guarded = CrisisReplyGuard.guard("   ", listOf("我想死"))
        assertTrue(guarded.isNotBlank())
        assertTrue(guarded.contains("找个人"))
    }

    @Test
    fun creativeModeUsesCreativeFallbackAndRemainsIdempotent() {
        val guarded = CrisisReplyGuard.guard(reply, listOf("我想死"), creativeMode = true)
        assertTrue(guarded.contains("戏里的台词"))
        assertTrue(guarded.contains("12356"))
        assertEquals(guarded, CrisisReplyGuard.guard(guarded, listOf("我想死"), creativeMode = true))
    }

    @Test
    fun phraseContainingZhaoGeRenWithoutChatSuffixStillAppendsFallback() {
        val roleplayLine = "今晚先找个人搭把手把这事办了吧。"
        val guarded = CrisisReplyGuard.guard(roleplayLine, listOf("我想死"), creativeMode = true)
        assertTrue("戏词含「找个人」但非兜底话术，不应被误吞：$guarded", guarded.contains("12356"))
    }
}
