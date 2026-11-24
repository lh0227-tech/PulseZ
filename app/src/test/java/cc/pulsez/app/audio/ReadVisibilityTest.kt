package cc.pulsez.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动朗读的「页面 + 前台」可见性门（ 修 ）。纯 JVM。
 *
 * 覆盖三件事：
 * 1. 默认保守（没标记前台就不读）；
 * 2. 「本角色聊天页 + 前台」才允许，其余组合（后台 / 别的页面 / 别的角色）一律拒绝；
 * 3. 快照是不可变值：取出后再改状态，旧快照不会“自己变”（对应 `ChatViewModel` 在挂起点后
 * 重新取最新快照的用法）。
 */
class ReadVisibilityTest {

    private val visibility = ReadVisibility()
    private val characterId = "char-a"

    @Test
    fun `default state does not allow auto read`() {
        // 初始化后 ON_START / 导航还没跑：保守不读（ON_START 会在注册观察者时补发，不会漏正常路径）。
        assertFalse(visibility.canAutoRead(characterId))
        assertFalse(visibility.snapshot.appForeground)
        assertTrue(visibility.snapshot.visibleChatCharacterId == null)
    }

    @Test
    fun `allows auto read only on the same character page in the foreground`() {
        visibility.setAppForeground(true)
        visibility.setVisibleChatCharacter(characterId)
        assertTrue("本角色聊天页 + 前台 → 允许", visibility.canAutoRead(characterId))
        assertTrue(visibility.snapshot.canAutoRead(characterId))

        visibility.setVisibleChatCharacter("char-b")
        assertFalse("切到另一个角色 → 旧角色的回复不读", visibility.canAutoRead(characterId))
        assertTrue("新角色自己的页面可以读", visibility.canAutoRead("char-b"))

        visibility.setVisibleChatCharacter(null)
        assertFalse("不在任何聊天页（首页/设置/群聊…）→ 不读", visibility.canAutoRead(characterId))
    }

    @Test
    fun `background blocks auto read even on the visible chat page`() {
        visibility.setAppForeground(true)
        visibility.setVisibleChatCharacter(characterId)
        assertTrue(visibility.canAutoRead(characterId))

        // 退后台 / 锁屏：页面没变，但前台标记为 false。
        visibility.setAppForeground(false)
        assertFalse("后台完成的回复不读", visibility.canAutoRead(characterId))

        // 回到前台且仍在同一聊天页：恢复可读（正常路径不能被 修复挡住）。
        visibility.setAppForeground(true)
        assertTrue(visibility.canAutoRead(characterId))
    }

    @Test
    fun `page context splits the two conditions for the pure policy`() {
        visibility.setAppForeground(true)
        visibility.setVisibleChatCharacter(characterId)
        val onPage = visibility.snapshot.pageContext(characterId)
        assertTrue(onPage.pageVisible)
        assertTrue(onPage.appForeground)

        val otherCharacter = visibility.snapshot.pageContext("char-b")
        assertFalse("不是这个角色 → pageVisible=false", otherCharacter.pageVisible)
        assertTrue("App 仍在前台", otherCharacter.appForeground)

        visibility.setAppForeground(false)
        val background = visibility.snapshot.pageContext(characterId)
        assertTrue("页面还在", background.pageVisible)
        assertFalse("但不在前台", background.appForeground)
    }

    @Test
    fun `snapshot is immutable so a later change does not rewrite the old one`() {
        visibility.setAppForeground(true)
        visibility.setVisibleChatCharacter(characterId)
        val captured = visibility.snapshot
        assertTrue(captured.canAutoRead(characterId))

        // 用户在看设置/锁屏：门槛变化。已经取出的快照必须保持原样——
        // `ChatViewModel` 正是靠“挂起点后重新取最新快照”来避免旧值误判。
        visibility.setVisibleChatCharacter(null)
        visibility.setAppForeground(false)

        assertTrue("旧快照不会被原地改写", captured.canAutoRead(characterId))
        assertTrue("旧快照的字段也不变", captured.pageContext(characterId).pageVisible)
        assertFalse("最新快照已拒绝", visibility.snapshot.canAutoRead(characterId))
    }
}
