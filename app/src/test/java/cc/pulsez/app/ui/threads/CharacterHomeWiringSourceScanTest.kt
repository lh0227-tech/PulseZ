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
    private val repositoriesText by lazy { File(REPOSITORIES_PATH).readText(Charsets.UTF_8) }
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
    fun onlyThePersistedLiveSingleChatUserPathOptsIntoActivityAndUsesOneSession() {
        val runTurnStart = chatViewModelText.indexOf("private suspend fun runTurn(")
        val runTurnEnd = chatViewModelText.indexOf("private suspend fun executeTurnForUserRow(", runTurnStart)
        assertTrue("发送轮次和执行请求的源码区块必须存在", runTurnStart >= 0 && runTurnEnd > runTurnStart)
        val runTurnBlock = chatViewModelText.substring(runTurnStart, runTurnEnd)
        val appendAt = runTurnBlock.indexOf("container.chat.appendUserForTurn(")
        val persistedRowCheck = runTurnBlock.indexOf("if (userRow == null) return", appendAt)
        val requestAt = runTurnBlock.indexOf("executeTurnForUserRow(", persistedRowCheck)
        assertTrue("live user append 必须存在", appendAt >= 0)
        assertTrue("返回 null 时不能启动请求", persistedRowCheck > appendAt)
        assertTrue("用户消息先持久化、再启动请求", requestAt > persistedRowCheck)
        val runTurnCall = runTurnBlock.substring(requestAt)
        assertTrue("请求沿用发送时捕获的 attempt", runTurnCall.contains("acceptedSendAttempt = attempt"))

        val appendMethodStart = repositoriesText.indexOf("suspend fun appendUserForTurn(")
        val appendMethodEnd = repositoriesText.indexOf("suspend fun appendAssistantForTurn(", appendMethodStart)
        assertTrue("事务性用户 append 仓储必须存在", appendMethodEnd > appendMethodStart)
        val appendMethod = repositoriesText.substring(appendMethodStart, appendMethodEnd)
        assertTrue("该仓储只创建 user 行", appendMethod.contains("role = \"user\""))
        val rowWriteAt = appendMethod.indexOf("db.messageDao().upsert(message)")
        val activityAt = appendMethod.indexOf("recordSingleChatActivity(threadId", rowWriteAt)
        assertTrue("单聊活动必须随用户行同事务写入", rowWriteAt >= 0 && activityAt > rowWriteAt)

        val executeStart = chatViewModelText.indexOf("private suspend fun executeTurnForUserRow(")
        val executeEnd = chatViewModelText.indexOf("private fun restoreRegeneratedAssistant(", executeStart)
        assertTrue("请求执行源码区块必须存在", executeEnd > executeStart)
        val executeBlock = chatViewModelText.substring(executeStart, executeEnd)
        val sessionAt = executeBlock.indexOf("val session = resolveChatEndpoint()")
        val clientAt = executeBlock.indexOf("LlmClient(container.http, session.baseUrl, session.apiKey)")
        val prepareAt = executeBlock.indexOf("container.engine.prepare(")
        assertTrue("请求客户端必须使用已解析 session", sessionAt >= 0 && clientAt > sessionAt)
        assertTrue("上下文组装必须使用同一 session", prepareAt > sessionAt)
        assertTrue(executeBlock.contains("requestSettings = turnSettings"))
        assertTrue(executeBlock.contains("remoteCredentialBindingId = session.remoteCredentialBindingId"))
        assertTrue(executeBlock.contains("uploadedFileBindingId != session.remoteCredentialBindingId?.takeIf"))
        assertFalse("历史导入不得显式推进真实活动", importViewModelText.contains("recordSingleChatActivity = true"))
        assertFalse("群聊不得显式推进单聊活动", groupViewModelText.contains("recordSingleChatActivity = true"))
    }

    private companion object {
        const val VIEW_MODEL_PATH = "src/main/java/cc/pulsez/app/ui/threads/ThreadsViewModel.kt"
        const val SCREEN_PATH = "src/main/java/cc/pulsez/app/ui/threads/ThreadsScreen.kt"
        const val APP_ROOT_PATH = "src/main/java/cc/pulsez/app/ui/AppRoot.kt"
        const val CHAT_VIEW_MODEL_PATH = "src/main/java/cc/pulsez/app/ui/chat/ChatViewModel.kt"
        const val REPOSITORIES_PATH = "src/main/java/cc/pulsez/app/data/repo/Repositories.kt"
        const val IMPORT_VIEW_MODEL_PATH = "src/main/java/cc/pulsez/app/ui/importer/ImportViewModel.kt"
        const val GROUP_VIEW_MODEL_PATH = "src/main/java/cc/pulsez/app/ui/group/GroupChatViewModel.kt"
    }
}
