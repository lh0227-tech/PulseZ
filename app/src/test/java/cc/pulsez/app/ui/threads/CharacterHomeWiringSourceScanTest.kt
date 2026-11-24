package cc.pulsez.app.ui.threads

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring checks complement pure ordering tests where no Compose UI test harness exists. */
class CharacterHomeWiringSourceScanTest {

    private val viewModelText by lazy { File(VIEW_MODEL_PATH).readText(Charsets.UTF_8) }
    private val screenText by lazy { File(SCREEN_PATH).readText(Charsets.UTF_8) }
    private val appRootText by lazy { File(APP_ROOT_PATH).readText(Charsets.UTF_8) }
    private val chatViewModelText by lazy { File(CHAT_VIEW_MODEL_PATH).readText(Charsets.UTF_8) }
    private val importViewModelText by lazy { File(IMPORT_VIEW_MODEL_PATH).readText(Charsets.UTF_8) }
    private val groupViewModelText by lazy { File(GROUP_VIEW_MODEL_PATH).readText(Charsets.UTF_8) }

    @Test
    fun homeUsesReadOnlyBatchReadsAndCapsRoleCards() {
        assertTrue(viewModelText.isNotBlank())
        assertTrue(screenText.isNotBlank())
        assertFalse("打开首页不得为角色创建空会话", viewModelText.contains("ensureThread("))
        assertTrue("活动单聊必须批量读取", viewModelText.contains("activeSinglesByCreation()"))
        assertTrue("首页消息预览只批量读取可见段", viewModelText.contains("latestForSegments(segmentIds)"))
        assertTrue("目录排序仍完整保留全量角色", viewModelText.contains("directoryItems = directoryItems"))
        assertTrue("主页组件本身也强制不超过六张卡", screenText.contains("cards.take(HOME_CHARACTER_LIMIT)"))
        assertTrue("全部角色入口始终呈现", screenText.contains("CharacterDirectoryEntryButton("))
        assertTrue("首页管理按钮点击执行入口动作", screenText.contains("onClick = entry.onOpen"))
        assertTrue("角色管理入口接到全量目录回调", screenText.contains("onOpen = onOpenAllCharacters"))
    }

    @Test
    fun directoryRouteUsesTheExistingBackStackAndSystemBackHandler() {
        assertTrue(appRootText.isNotBlank())
        assertTrue(appRootText.contains("nav.push(Route.AllCharacters)"))
        assertTrue(appRootText.contains("Route.AllCharacters ->"))
        assertTrue(appRootText.contains("onBack = { nav.back() }"))
        assertTrue(appRootText.contains("PredictiveBackHandler(enabled = nav.canGoBack)"))
    }

    @Test
    fun directoryRefreshesItsSnapshotAfterReturningFromChat() {
        val directoryBranch = appRootText.substringAfter("Route.AllCharacters ->")
            .substringBefore("Route.Import ->")
        assertTrue("目录返回后按导航返回状态重新读取角色活动", directoryBranch.contains("LaunchedEffect(nav.lastMoveWasBack)"))
        assertTrue("返回目录时刷新角色目录", directoryBranch.contains("if (nav.lastMoveWasBack) vm.refresh()"))
    }

    @Test
    fun onlyThePersistedLiveSingleChatUserPathOptsIntoActivity() {
        val appendAt = chatViewModelText.indexOf("val userRow = container.chat.append(")
        val requestAt = chatViewModelText.indexOf("val client = container.llmClient()", appendAt)
        assertTrue("用户消息写入路径必须存在", appendAt >= 0)
        assertTrue("用户消息先落库、后启动请求", requestAt > appendAt)
        val persistBlock = chatViewModelText.substring(appendAt, requestAt)
        assertTrue(persistBlock.contains("role = \"user\""))
        assertTrue(persistBlock.contains("recordSingleChatActivity = true"))
        assertFalse("历史导入不得显式推进真实活动", importViewModelText.contains("recordSingleChatActivity = true"))
        assertFalse("群聊不得显式推进单聊活动", groupViewModelText.contains("recordSingleChatActivity = true"))
    }

    private companion object {
        const val VIEW_MODEL_PATH = "src/main/java/cc/pulsez/app/ui/threads/ThreadsViewModel.kt"
        const val SCREEN_PATH = "src/main/java/cc/pulsez/app/ui/threads/ThreadsScreen.kt"
        const val APP_ROOT_PATH = "src/main/java/cc/pulsez/app/ui/AppRoot.kt"
        const val CHAT_VIEW_MODEL_PATH = "src/main/java/cc/pulsez/app/ui/chat/ChatViewModel.kt"
        const val IMPORT_VIEW_MODEL_PATH = "src/main/java/cc/pulsez/app/ui/importer/ImportViewModel.kt"
        const val GROUP_VIEW_MODEL_PATH = "src/main/java/cc/pulsez/app/ui/group/GroupChatViewModel.kt"
    }
}
