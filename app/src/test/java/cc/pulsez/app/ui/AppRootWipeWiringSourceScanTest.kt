package cc.pulsez.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清空流程的一致性接线源码扫描。
 *
 * ## 测试范围
 * 这是纯 JVM 源码扫描。清空流程在 `AppRoot.runClear` 中，流式生成守卫在
 * `ChatViewModel` 回调中，现有测试环境无法运行 Compose UI 或 ViewModel 集成测试。
 * 本测试检查关键接线确实存在且位置正确；相关行为由 `UpdateRepositoryTest`、
 * `ImportTaskStoreTest`、`AppContainerWipeEpochTest` 和 `GroupReplyCallTest` 覆盖。
 *
 * 扫描验证：代际更新与状态复位先于 `ExportService.clearAll`；成功后才切换首页，失败时不导航；
 * `homeRebuildToken` 使用 `rememberSaveable`；分享文本按文本去重并先记录后注入；
 * 导入任务在清空时取消并复位缓存；各路由使用共享的 `container.wipeEpoch`；
 * Activity 分享事件可打开导入页并继续按文本去重。
 *
 * 测试先确认源码文件非空，避免读取失败后在空字符串上误判通过。工作目录为 `app/`。
 */
class AppRootWipeWiringSourceScanTest {

    private companion object {
        const val APP_ROOT_PATH = "src/main/java/cc/pulsez/app/ui/AppRoot.kt"
        const val CHAT_VM_PATH = "src/main/java/cc/pulsez/app/ui/chat/ChatViewModel.kt"
        const val GROUP_VM_PATH = "src/main/java/cc/pulsez/app/ui/group/GroupChatViewModel.kt"
        const val CHAT_ENGINE_PATH = "src/main/java/cc/pulsez/app/core/ChatEngine.kt"
        const val IMPORT_TASK_STORE_PATH = "src/main/java/cc/pulsez/app/ui/importer/ImportTaskStore.kt"
        const val IMPORT_VM_PATH = "src/main/java/cc/pulsez/app/ui/importer/ImportViewModel.kt"
    }

    private val appRootText: String by lazy { File(APP_ROOT_PATH).readText(Charsets.UTF_8) }
    private val chatVmText: String by lazy { File(CHAT_VM_PATH).readText(Charsets.UTF_8) }
    private val groupVmText: String by lazy { File(GROUP_VM_PATH).readText(Charsets.UTF_8) }
    private val chatEngineText: String by lazy { File(CHAT_ENGINE_PATH).readText(Charsets.UTF_8) }
    private val importTaskStoreText: String by lazy { File(IMPORT_TASK_STORE_PATH).readText(Charsets.UTF_8) }
    private val importVmText: String by lazy { File(IMPORT_VM_PATH).readText(Charsets.UTF_8) }

    private fun occurrences(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    /** `ClearOutcome.RETURN_HOME` 分支正文（到下一个 `ClearOutcome.` 为止）。 */
    private fun clearSuccessBranch(): String {
        val start = appRootText.indexOf("ClearOutcome.RETURN_HOME")
        assertTrue("AppRoot.kt 找不到 ClearOutcome.RETURN_HOME", start >= 0)
        val end = appRootText.indexOf("ClearOutcome.", start + 1)
        assertTrue("RETURN_HOME 之后应还有其它 ClearOutcome 分支", end > start)
        return appRootText.substring(start, end)
    }

    /** 从 [anchor] 开始，截到与它后面第一个 `{` 配对的闭合 `}`（含）。 */
    private fun braceBlockOf(text: String, anchor: String): String {
        val start = text.indexOf(anchor)
        assertTrue("源码里找不到区块锚点：$anchor", start >= 0)
        var depth = 0
        var seen = false
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '{' -> {
                    depth += 1
                    seen = true
                }
                '}' -> {
                    depth -= 1
                    if (seen && depth == 0) return text.substring(start, index + 1)
                }
            }
            index += 1
        }
        throw AssertionError("区块没有闭合：$anchor")
    }

    // ---------------------------------------------------------------- 前置

    @Test
    fun `六份源码都读到了`() {
        assertTrue("AppRoot.kt 读取失败/为空", appRootText.isNotBlank())
        assertTrue("ChatViewModel.kt 读取失败/为空", chatVmText.isNotBlank())
        assertTrue("GroupChatViewModel.kt 读取失败/为空", groupVmText.isNotBlank())
        assertTrue("ChatEngine.kt 读取失败/为空", chatEngineText.isNotBlank())
        assertTrue("ImportTaskStore.kt 读取失败/为空", importTaskStoreText.isNotBlank())
        assertTrue("ImportViewModel.kt 读取失败/为空", importVmText.isNotBlank())
    }

    // ---------------------------------------------------------------- ：清空成功分支

    /**
 * 代际更新和相关状态复位必须在
 * **`ExportService.clearAll` 之前**（进入执行的那一刻），这样 clearAll 执行期间的
 * 迟到写入/在途检查也会被代际挡住（收口「执行中窗口」）。
 *
 * 本测试锁三件事：①调用都在 `runClear` 里；②都在 clearAll **之前**；③顺序是
 * notify → importTasks.reset → updateRepository.resetAfterWipe。③的顺序不承担
 * 语义（三者互不依赖），但断言「都在」+「都在 clearAll 前」是本条验收的核心。
 *
 * VM key 使用共享的
 * **全局** `container.wipeEpoch`，`wipeToken` 连同其自增一起删除——代际自增本来就发生在
 * 这里的 `container.notifyUserDataWiped`（同一条 ① 调用），所以断言改为「自增在 clearAll
 * 前 + 不再有本地 token」，覆盖的语义不变（清空执行起点就换代）。
 */
    @Test
    fun `清空进入执行时就贴代际并复位导入与更新状态（早于 clearAll）`() {
        val runClearStart = appRootText.indexOf("val runClear: () -> Unit")
        assertTrue("AppRoot.kt 找不到 runClear 定义", runClearStart >= 0)
        val clearAllCall = appRootText.indexOf("ExportService.clearAll(container)", runClearStart)
        assertTrue("runClear 里找不到 ExportService.clearAll(container)", clearAllCall > runClearStart)

        val preClearBlock = appRootText.substring(runClearStart, clearAllCall)
        assertTrue(
            "清空执行前必须自增清空代际（执行中窗口的守卫信号）：$preClearBlock",
            preClearBlock.contains("container.notifyUserDataWiped()"),
        )
        assertTrue(
            "清空执行前必须复位进程级导入任务（取消在途 job 并回 Idle）：$preClearBlock",
            preClearBlock.contains("container.importTasks.reset()"),
        )
        assertTrue(
            "清空执行前必须复位更新检查内存状态（在途检查结果作废）：$preClearBlock",
            preClearBlock.contains("updateRepository.resetAfterWipe()"),
        )
        // ：不再有 AppRoot 本地 wipeToken——它只给发起清空的实例换代，
        // 多 Activity 并存时另一个实例仍用旧 key（旧导入 VM 的旧备份预览仍可恢复）。
        assertFalse(
            "AppRoot 不得再持有本地 wipeToken（改用全局 container.wipeEpoch）：$preClearBlock",
            preClearBlock.contains("wipeToken += 1"),
        )
        assertFalse("AppRoot 不得再声明本地 wipeToken 变量", appRootText.contains("var wipeToken"))
        // 顺序锁（notify 先于 clearAll 的另一半证据）
        assertTrue(
            "notifyUserDataWiped 必须在 clearAll 调用之前",
            appRootText.indexOf("container.notifyUserDataWiped()") < clearAllCall,
        )
    }

    @Test
    fun `清空成功后只做首页换代与回首页 失败分支不动首页`() {
        val branch = clearSuccessBranch()
        assertTrue("成功分支仍须 homeRebuildToken += 1", branch.contains("homeRebuildToken += 1"))
        assertTrue("成功分支仍须回首页", branch.contains("nav.reset(Route.Threads)"))
        assertFalse(
            "代际自增（notifyUserDataWiped）已提前到执行前，不得留在成功分支",
            branch.contains("notifyUserDataWiped"),
        )
        // 失败分支（SHOW_FAILURE 一行）不得做首页换代：数据还在，首页必须还能显示原角色卡。
        val failureIndex = appRootText.indexOf("ClearOutcome.SHOW_FAILURE")
        assertTrue("找不到 SHOW_FAILURE 分支", failureIndex >= 0)
        val failureTail = appRootText.substring(failureIndex, minOf(failureIndex + 200, appRootText.length))
        assertFalse("失败分支不得做首页换代：$failureTail", failureTail.contains("homeRebuildToken += 1"))
        assertFalse("失败分支不得导航回首页：$failureTail", failureTail.contains("nav.reset("))
    }

    // ---------------------------------------------------------------- ：VM key 换代

    /**
 * ：key 后缀从 AppRoot 本地 `wipeToken` 改为**全局** `container.wipeEpoch`。
 *
 * 为什么必须全局：`MainActivity` 无 launchMode（standard）+ MAIN/SEND/PROCESS_TEXT 三个入口，
 * 平台允许并存多个 Activity 实例；本地 token 只有发起清空的实例自增，另一个存活实例继续
 * 命中清空前的旧 VM（旧导入预览可恢复）。全局代际是进程级 StateFlow，所有实例的 AppRoot
 * 都观察它 → 清空一开始全部换代。
 */
    @Test
    fun `各路由 ViewModel 的 key 都用全局清空代际`() {
        assertTrue(
            "AppRoot 必须观察全局清空代际（container.wipeEpoch）",
            appRootText.contains("val wipeEpoch by container.wipeEpoch.collectAsState()"),
        )
        assertTrue(
            "Import 路由必须 key = \"import-\$wipeEpoch\"",
            appRootText.contains("key = \"import-\$wipeEpoch\""),
        )
        assertTrue(
            "DeepSeekImport 路由必须 key = \"deepseek-import-\$wipeEpoch\"",
            appRootText.contains("key = \"deepseek-import-\$wipeEpoch\""),
        )
        assertEquals(
            "Config 与 FindOldThings 两页共用同一个 key，都应带全局代际（各 1 处）",
            2,
            occurrences(appRootText, "key = \"config-\$wipeEpoch\""),
        )
        assertTrue(
            "Chat 的 key 必须同时含 characterId 与全局代际（清空后再进是全新 VM）",
            appRootText.contains("key = \"chat-\${route.characterId}-\$wipeEpoch\""),
        )
        assertTrue(
            "GroupChat 的 key 必须同时含 threadId 与全局代际",
            appRootText.contains("key = \"group-\${route.threadId}-\$wipeEpoch\""),
        )
        // Memory 也必须随清空代际更新，否则旧 MemoryViewModel
        // 可能被复用、带着清空前的整理/补建任务（该 VM 现在自己订阅 wipeEpoch，换代是第二道保险）。
        assertTrue(
            "Memory 的 key 必须同时含 characterId 与全局代际",
            appRootText.contains("key = \"memory-\${route.characterId}-\$wipeEpoch\""),
        )
        // 旧的无 key / 无代际写法不得残留（否则等于没换 VM）。
        assertFalse("Import 路由不得再是无 key 的 viewModel(factory=…)", appRootText.contains("viewModel(factory = ImportViewModel.factory(container))"))
        assertFalse("DeepSeekImport 不得再用固定 key", appRootText.contains("key = \"deepseek-import\","))
        assertFalse("Chat 不得再用不含代际的 key", appRootText.contains("key = \"chat-\${route.characterId}\","))
        assertFalse("GroupChat 不得再用不含代际的 key", appRootText.contains("key = \"group-\${route.threadId}\","))
        assertFalse("Memory 不得再用不含代际的 key", appRootText.contains("key = \"memory-\${route.characterId}\","))
        // 本地 wipeToken 全套形态不得残留（删除）。
        assertFalse(
            "AppRoot 不得再出现本地 wipeToken 的 key 形态",
            appRootText.contains("-\$wipeToken\""),
        )
    }

    // ---------------------------------------------------------------- ：门槛分支 insets

    @Test
    fun `门槛分支有 systemBars 与 displayCutout 内边距 且 Route_Legal 不重复加`() {
        val gateStart = appRootText.indexOf("if (SettingsStore.needsAgreement(current.agreedVersion))")
        assertTrue("找不到门槛分支", gateStart >= 0)
        // 右边界用「门槛分支之后的主容器起点」，**不能**用第一个 `return@Surface`：
        // 门槛分支自己的注释里也写了这个词（"注册位置必须在 return@Surface 之前"），
        // 用 indexOf 会截断在注释处（实测踩到：断言会拿到半截分支）。
        val gateEnd = appRootText.indexOf("val nav = remember", gateStart)
        assertTrue("门槛分支之后应回到主容器（val nav = remember）", gateEnd > gateStart)
        val gateBranch = appRootText.substring(gateStart, gateEnd)
        assertTrue("门槛分支必须以 return@Surface 结束", gateBranch.contains("return@Surface"))

        assertTrue(
            "门槛分支必须包一层 windowInsetsPadding(WindowInsets.systemBars)：$gateBranch",
            gateBranch.contains(".windowInsetsPadding(WindowInsets.systemBars)"),
        )
        assertTrue(
            "门槛分支必须包一层 windowInsetsPadding(WindowInsets.displayCutout)：$gateBranch",
            gateBranch.contains(".windowInsetsPadding(WindowInsets.displayCutout)"),
        )
        assertTrue("门槛分支内仍要有 GateScreen(", gateBranch.contains("GateScreen("))
        assertTrue("门槛分支内协议页仍要是 LegalScreen(", gateBranch.contains("LegalScreen("))
        assertTrue("门槛分支内协议页仍要注册 BackHandler", gateBranch.contains("BackHandler {"))

        // 全文件恰好两套（主容器 + 门槛分支）；Route.Legal 在主容器内，不得出现第三套。
        assertEquals(
            "WindowInsets.systemBars 只允许主容器与门槛分支各 1 处（Route.Legal 不得重复 padding）",
            2,
            occurrences(appRootText, "windowInsetsPadding(WindowInsets.systemBars)"),
        )
        assertEquals(
            "WindowInsets.displayCutout 只允许主容器与门槛分支各 1 处（Route.Legal 不得重复 padding）",
            2,
            occurrences(appRootText, "windowInsetsPadding(WindowInsets.displayCutout)"),
        )
        val legalRouteStart = appRootText.indexOf("Route.Legal ->")
        assertTrue("找不到 Route.Legal 路由分支", legalRouteStart >= 0)
        val legalRoute = appRootText.substring(
            legalRouteStart,
            appRootText.indexOf('\n', legalRouteStart),
        )
        assertFalse("Route.Legal 自身不得再包 insets：$legalRoute", legalRoute.contains("windowInsetsPadding"))
    }

    // ---------------------------------------------------------------- ：ChatViewModel / GroupChatViewModel / ChatEngine

    @Test
    fun `ChatViewModel 观察清空代际并在 Finished 落库前守卫`() {
        assertTrue(
            "ChatViewModel 必须订阅 container.wipeEpoch（取消在途生成）",
            chatVmText.contains("container.wipeEpoch.collect"),
        )
        val sendStart = chatVmText.indexOf("fun send(onAccepted: (() -> Unit)? = null)")
        val sendInternalStart = chatVmText.indexOf("private suspend fun sendInternal", sendStart)
        assertTrue(
            "ChatViewModel 必须找到 send() 到 sendInternal() 的源码区块",
            sendStart >= 0 && sendInternalStart > sendStart,
        )
        val sendBlock = chatVmText.substring(sendStart, sendInternalStart)
        val attemptStart = sendBlock.indexOf("val attempt = SendAttempt(")
        val activeAttemptIndex = sendBlock.indexOf("activeSendAttempt = attempt", attemptStart)
        val sendLaunchIndex = sendBlock.indexOf("viewModelScope.launch", activeAttemptIndex)
        assertTrue(
            "ChatViewModel send() 必须先同步构造发送尝试并保存，再启动异步发送",
            attemptStart >= 0 && activeAttemptIndex > attemptStart && sendLaunchIndex > activeAttemptIndex,
        )
        val attemptBlock = sendBlock.substring(attemptStart, activeAttemptIndex)
        val capturedEpochIndex = attemptBlock.indexOf("wipeEpoch = container.wipeEpoch.value")
        assertTrue(
            "ChatViewModel 必须在发送尝试构造时同步捕获清空代际，且发生在 viewModelScope.launch 前",
            capturedEpochIndex >= 0 && attemptStart + capturedEpochIndex < sendLaunchIndex,
        )
        val runTurnDefinitionStart = chatVmText.indexOf("private suspend fun runTurn(", sendInternalStart)
        assertTrue("找不到 runTurn 定义以界定 sendInternal 区块", runTurnDefinitionStart > sendInternalStart)
        val sendInternalBlock = chatVmText.substring(sendInternalStart, runTurnDefinitionStart)
        val runTurnCallStart = sendInternalBlock.indexOf("runTurn(")
        val streamLaunchStart = sendInternalBlock.lastIndexOf("streamJob = viewModelScope.launch", runTurnCallStart)
        val runTurnCallEnd = sendInternalBlock.indexOf("\n            )", runTurnCallStart)
        assertTrue(
            "sendInternal 必须在流任务内调用 runTurn，并传入同一发送尝试捕获的代际",
            streamLaunchStart >= 0 && runTurnCallStart > streamLaunchStart && runTurnCallEnd > runTurnCallStart &&
                sendInternalBlock.substring(runTurnCallStart, runTurnCallEnd)
                    .contains("wipeEpoch = attempt.wipeEpoch"),
        )
        // 取 Finished 分支正文：`is LlmEvent.Finished ->` 到 `is LlmEvent.Failed ->`。
        val finishedStart = chatVmText.indexOf("is LlmEvent.Finished ->")
        val failedStart = chatVmText.indexOf("is LlmEvent.Failed ->", finishedStart)
        assertTrue("找不到 LlmEvent.Finished / Failed 分支", finishedStart >= 0 && failedStart > finishedStart)
        val finishedBlock = chatVmText.substring(finishedStart, failedStart)
        val guardIndex = finishedBlock.indexOf("wipeChangedSince(wipeEpoch)")
        assertTrue("LlmEvent.Finished 分支内必须有清空代际守卫", guardIndex >= 0)
        val appendIndex = finishedBlock.indexOf("container.chat.append(")
        assertTrue("Finished 分支必须有 assistant 落库", appendIndex > guardIndex)
        assertTrue("守卫必须在落库之前（不能先写库再判断）", guardIndex < appendIndex)
        assertTrue(
            "记账/索引/维护的 appScope 启动块也要按代际收口（第二层）",
            chatVmText.contains("if (wipeChangedSince(wipeEpoch)) return@launch"),
        )
    }

    @Test
    fun `GroupChatViewModel 观察清空代际并守卫落库与后处理`() {
        assertTrue(
            "GroupChatViewModel 必须订阅 container.wipeEpoch（取消在途生成）",
            groupVmText.contains("container.wipeEpoch.collect"),
        )
        assertEquals(
            "群聊 send 与 retry 两个入口都要取本轮代际",
            2,
            occurrences(groupVmText, "turnWipeEpoch = container.wipeEpoch.value"),
        )
        assertTrue(
            "落库/索引/账本/记忆写之前都要 wipedDuringTurn() 守卫（至少 5 处）",
            occurrences(groupVmText, "wipedDuringTurn()") >= 5,
        )
        assertTrue(
            "群聊取消调用必须在本轮落库前出现",
            groupVmText.contains("if (wipedDuringTurn()) return@launch"),
        )
    }

    @Test
    fun `ChatEngine 索引与维护落库前也按代际守卫`() {
        assertTrue(
            "ChatEngine 必须有 wipeChangedSince 判定（container.wipeEpoch 比对）",
            chatEngineText.contains("container.wipeEpoch.value != startEpoch"),
        )
        assertTrue(
            "indexTurn 写 chunk 前必须守卫",
            chatEngineText.contains("if (wipeChangedSince(wipeEpoch)) return") && chatEngineText.contains("saveChunks"),
        )
        assertTrue(
            "maintenance 的两条写路径（抽取 applyPatch / 交接摘要）都要守卫",
            occurrences(chatEngineText, "wipeChangedSince(wipeEpoch)") >= 3,
        )
    }

    @Test
    fun `ImportTaskStore reset 真的取消在途 job`() {
        val resetStart = importTaskStoreText.indexOf("fun reset()")
        assertTrue("找不到 ImportTaskStore.reset()", resetStart >= 0)
        // 用 reset 之后紧跟的下一个函数当右边界（不依赖注释里的破折号数量）。
        val resetEnd = importTaskStoreText.indexOf("private fun beginTask()", resetStart)
        assertTrue("找不到 reset() 之后的 beginTask()", resetEnd > resetStart)
        val resetBlock = importTaskStoreText.substring(resetStart, resetEnd)
        assertTrue("reset 必须取消在途 job", resetBlock.contains("activeJob?.cancel()"))
        assertTrue("reset 必须抑制迟到结果", resetBlock.contains("suppressResults = true"))
        assertTrue("reset 必须回初始态", resetBlock.contains("_state.value = ImportTaskState.Idle"))
    }

    // ---------------------------------------------------------------- + 代际/rekey
    // `wipeToken` 改由全局 `container.wipeEpoch` 维护，
    // 所以原先「两个 token 都 rememberSaveable」的断言收窄为「homeRebuildToken 仍 rememberSaveable
    // + 全局代际来自 AppContainer 而不是本地 remember」——语义上仍锁「Activity 重建不回退到
    // 清空前的旧 VM」：homeRebuildToken 不再归零，清空代际本身来自进程级 StateFlow（重建后不变）。

    /**
 * 为什么必须 rememberSaveable（不是 remember）：`AndroidManifest.xml` 的 `configChanges`
 * 不含 fontScale/locale/density，这些配置变化会重建 Activity；重建时 ViewModelStore 保留，
 * 而 `remember` 归零 → `threads-home-0` 等旧 key 重新命中清空前的旧首页 VM。见
 * 的静态证据链。
 *
 * 清空代际不再需要本地 saveable：它直接从 `AppContainer.wipeEpoch`（进程级 StateFlow）读，
 * Activity 重建不会把它归零，也不存在「多个实例各自一份」的问题。
 */
    @Test
    fun `首页换代 token 用 rememberSaveable 清空代际来自全局容器`() {
        val token = "homeRebuildToken"
        assertTrue(
            "$token 必须声明为 rememberSaveable { mutableStateOf(0) }",
            appRootText.contains("var $token by rememberSaveable { mutableStateOf(0) }"),
        )
        assertFalse(
            "$token 不得再是 remember（重建归零会命中清空前的旧 VM）",
            appRootText.contains("var $token by remember { mutableStateOf(0) }"),
        )
        // ：本地 wipeToken 删除；代际必须读全局 StateFlow（不能是本地的 remember）。
        assertFalse("AppRoot 不得再声明本地 wipeToken", appRootText.contains("var wipeToken"))
        assertTrue(
            "清空代际必须读全局 container.wipeEpoch（所有 Activity 实例共享）",
            appRootText.contains("val wipeEpoch by container.wipeEpoch.collectAsState()"),
        )
        assertFalse(
            "清空代际不得是本地 remember，多 Activity 实例各一份",
            appRootText.contains("var wipeEpoch by remember"),
        )
        assertTrue(
            "AppRoot.kt 必须导入 rememberSaveable",
            appRootText.contains("import androidx.compose.runtime.saveable.rememberSaveable"),
        )
        // 分享消费记号同样必须跨 Activity 重建保留（否则重建后同一份旧分享会被再次注入）。
        assertTrue(
            "分享消费记号 lastConsumedShareText 必须用 rememberSaveable",
            appRootText.contains(
                "var lastConsumedShareText by rememberSaveable { mutableStateOf<String?>(null) }",
            ),
        )
    }

    /**
 * 的修法接线：分享注入必须**先**经过纯函数判定、**再**记录已消费、**最后**才交给 VM。
 * 只判非空（旧写法 `if (!initialShareText.isNullOrBlank)`）在清空换代/重建后会把同一份旧文本
 * 再次交给新 VM，同一份旧备份预览就又出现了。
 *
 * 输入来自 Activity 级事件流
 * `shareText`（`shareEvents.collectAsState`），因此扫描锚点与三个断言的操作数都换成
 * `shareText`；判定/记录/注入的**顺序语义未变**。事件流 + 压入导入页的接线细节由
 * `ShareEventWiringSourceScanTest` 锁。
 */
    @Test
    fun `分享注入经过纯函数按文本去重 且先记录再注入`() {
        val block = braceBlockOf(appRootText, "LaunchedEffect(shareText, vm)")
        val checkIndex = block.indexOf("ShareConsumption.shouldInject(share, lastConsumedShareText)")
        val recordIndex = block.indexOf("lastConsumedShareText = share")
        val injectIndex = block.indexOf("vm.onShareText(share)")
        assertTrue("分享注入必须经过纯函数 ShareConsumption.shouldInject：$block", checkIndex >= 0)
        assertTrue("必须记录已消费的文本：$block", recordIndex > checkIndex)
        assertTrue("注入必须在记录之后（同一次组合里只消费一次）：$block", injectIndex > recordIndex)
        assertFalse(
            "不得回到「只判非空就注入」的旧写法",
            block.contains("if (!initialShareText.isNullOrBlank())"),
        )
        // 纯函数本身必须是可 JVM 测试的 main 源码文件（行为断言见 ShareConsumptionTest）。
        val shareConsumption = File("src/main/java/cc/pulsez/app/ui/ShareConsumption.kt")
        assertTrue("缺少可测试的纯函数文件 ShareConsumption.kt", shareConsumption.isFile)
        val shareConsumptionText = shareConsumption.readText(Charsets.UTF_8)
        assertTrue(
            "ShareConsumption.kt 必须定义 shouldInject(text, lastConsumed)",
            shareConsumptionText.contains("fun shouldInject(text: String?, lastConsumed: String?)"),
        )
        assertTrue(
            "ShareConsumption.kt 必须定义 shouldOpenImportPage（温启动压栈判定）",
            shareConsumptionText.contains("fun shouldOpenImportPage(text: String?, alreadyOnImport: Boolean)"),
        )
    }

    /**
 * `ImportViewModel` 在清空代际变化时不仅取消在途 job/
 * 复位 busy，还要清掉 `pendingBackupDoc`/`pendingBackupText` 并把整页状态复位到初始——
 * 否则旧实例（万一因 key 命中被复用）仍能点「恢复」把清空前的备份写回。
 * 行为证据见 `ui/importer/ImportWipeResetTest`。
 */
    @Test
    fun `ImportViewModel 清空收口同时清待恢复缓存并复位整页状态`() {
        val start = importVmText.indexOf("private fun cancelJobsForWipe()")
        assertTrue("ImportViewModel 找不到 cancelJobsForWipe()", start >= 0)
        val end = importVmText.indexOf("private fun wipeChangedSince", start)
        assertTrue("找不到 cancelJobsForWipe() 之后的 wipeChangedSince()", end > start)
        val block = importVmText.substring(start, end)
        assertTrue("清空收口必须取消恢复 job", block.contains("restoreJob?.cancel()"))
        assertTrue("清空收口必须取消创建 job", block.contains("createJob?.cancel()"))
        // ：识别 job 存在集合中；清空时快照并逐个取消，避免只取消单个旧字段。
        assertTrue("清空收口必须同步访问识别 job 集合", block.contains("synchronized(inspectJobs)"))
        assertTrue("清空收口必须取消所有识别 job", block.contains("inspections.forEach { it.cancel() }"))
        assertTrue("清空收口必须清掉待恢复文档缓存", block.contains("clearPendingBackup()"))
        assertTrue(
            "清空收口必须把整页状态复位到初始（旧备份预览/草稿/createdId 不能留）",
            block.contains("_state.value = ImportUiState()"),
        )
    }

    /**
 * 识别/解析 job 必须由 `inspectJobs` 集合跟踪，清空时才能取消；识别路径
 * 要有**发起时代际复核**（覆盖未经集合跟踪的调用，如 `AppRoot` 文件入口直接调
 * `inspectAndParse`；只靠取消拦不住「挂起返回 → 写 pending」的窗口）。
 *
 * 行为证据：`ui/importer/ImportWipeResetTest` 的两条跨清空用例。
 */
    @Test
    fun `ImportViewModel 跟踪识别 job 且识别路径按发起代际复核`() {
        assertTrue(
            "parse() 必须通过 launchInspection 跟踪识别 job",
            importVmText.contains("launchInspection(raw, source)"),
        )
        assertTrue(
            "onShareText() 必须通过 launchInspection 跟踪识别 job",
            importVmText.contains("launchInspection(text, Source.SHARE)"),
        )
        assertTrue(
            "launchInspection 必须把 job 登记进 inspectJobs",
            importVmText.contains("synchronized(inspectJobs) { inspectJobs.add(job) }"),
        )
        assertTrue(
            "launchInspection 完成后必须从 inspectJobs 移除 job",
            importVmText.contains("synchronized(inspectJobs) { inspectJobs.remove(job) }"),
        )
        // 识别路径：入口取发起时代际；每个 withContext 挂起返回后都要 stopIfWiped 复核。
        val start = importVmText.indexOf("suspend fun inspectAndParse(")
        assertTrue("找不到 inspectAndParse(", start >= 0)
        val end = importVmText.indexOf("fun onShareText(", start)
        assertTrue("找不到 inspectAndParse 的右边界", end > start)
        val body = importVmText.substring(start, end)
        val capture = body.indexOf("val startEpoch = container.wipeEpoch.value")
        assertTrue("识别入口必须捕获发起时代际：$body", capture >= 0)
        assertEquals(
            "三个 withContext 挂起返回后都必须按发起时代际复核（识别 / 解析文档 / 普通解析）",
            3,
            occurrences(body, "if (stopIfWiped(startEpoch)) return"),
        )
        // 复核必须发生在 pending 缓存/预览写入之前。
        val pendingWrite = body.indexOf("pendingBackupDoc = doc")
        assertTrue("识别路径必须仍有 pendingBackupDoc 写入", pendingWrite >= 0)
        val firstCheck = body.indexOf("if (stopIfWiped(startEpoch)) return")
        assertTrue("代际复核必须在写 pending/预览之前", firstCheck in 0 until pendingWrite)
        // stopIfWiped 只发起代际是否变化；清缓存和整页复位由 cancelJobsForWipe 负责。
        val stopFn = braceBlockOf(importVmText, "private fun stopIfWiped(")
        assertTrue("复核必须基于发起时代际判断清空变化", stopFn.contains("wipeChangedSince(startEpoch)"))
        assertFalse("复核函数不得自行写回页面状态", stopFn.contains("_state.value"))
    }
}
