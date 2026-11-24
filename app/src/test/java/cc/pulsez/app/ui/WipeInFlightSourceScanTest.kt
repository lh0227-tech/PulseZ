package cc.pulsez.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清空一致性「执行中」窗口收口后的守卫接线扫描（ 授权）。
 *
 * 覆盖 追加的残余 2/3/4/5：
 * - 残余 2：群聊取消轮次的 `NonCancellable` 记账必须按清空代际收口（`GroupPortsImpl.kt`）；
 * - 残余 4：记忆面板在途「整理往事 / 补建索引」必须取消 + 写库前丢弃（`MemoryViewModel.kt`）；
 * - 残余 5：导入页在途「恢复备份 / 创建角色」必须取消 + 写库前丢弃（`ImportViewModel.kt`）；
 * - 残余 3：`AppRoot` 的复位时机提前到 clearAll 之前（由 `AppRootWipeWiringSourceScanTest` 锁）。
 *
 * 追加（第三方 /）：
 * - （1:1）：`ChatViewModel` 的 appScope 块必须在 `recordUsage` 挂起返回后、调
 * `indexTurn`/`maintenance` 之前用**同一份**捕获代际复核，并把该代际传进 `ChatEngine`；
 * 引擎内部不得再对调用方传入的代际重新取样（否则「调用方已检查 → 引擎执行」之间的清空逃逸）；
 * - （群聊同类）：`GroupChatViewModel.onTurnLanded` 必须把本轮代际贯穿给 `engine.indexTurn`；
 * - ：`recordVoiceUsage` 必须按「发起转写时」捕获的代际守卫，块内写库前比对。
 * 行为部分见 `WipeLateIndexBehaviorTest`（Robolectric 真容器）；ViewModels 无集成测试
 * 基建，这里只做接线扫描（不能证明运行时行为，已如实标注）。
 *
 * ## 哪些是行为测试、哪些只是接线扫描（不得混写）
 * - `GroupReplyCall` 的清空守卫有**行为测试**（`data/GroupReplyCallTest.kt`：
 * `wipeAlreadyHappenedSkipsUsageRecordButStillReturnsReply` /
 * `wipeAlreadyHappenedSkipsEstimatedUsageOnCancellation`）；
 * - MemoryViewModel / ImportViewModel 需要真实 `AppContainer`（Room + DataStore + Android
 * Context）才能构造，本仓库没有 ViewModel/Compose 集成测试基建（ 未覆盖），
 * 所以这里只做**源码接线扫描**：锁「订阅存在、取消存在、写库前守卫存在、且顺序在写之前」。
 * 它不能证明运行时行为，里已如实标注未覆盖。
 */
class WipeInFlightSourceScanTest {

    private companion object {
        const val GROUP_PORTS_PATH = "src/main/java/cc/pulsez/app/data/group/GroupPortsImpl.kt"
        const val MEMORY_VM_PATH = "src/main/java/cc/pulsez/app/ui/memory/MemoryViewModel.kt"
        const val IMPORT_VM_PATH = "src/main/java/cc/pulsez/app/ui/importer/ImportViewModel.kt"
        const val CHAT_VM_PATH = "src/main/java/cc/pulsez/app/ui/chat/ChatViewModel.kt"
        const val CHAT_ENGINE_PATH = "src/main/java/cc/pulsez/app/core/ChatEngine.kt"
        const val GROUP_VM_PATH = "src/main/java/cc/pulsez/app/ui/group/GroupChatViewModel.kt"
    }

    private val groupPortsText: String by lazy { File(GROUP_PORTS_PATH).readText(Charsets.UTF_8) }
    private val memoryVmText: String by lazy { File(MEMORY_VM_PATH).readText(Charsets.UTF_8) }
    private val importVmText: String by lazy { File(IMPORT_VM_PATH).readText(Charsets.UTF_8) }
    private val chatVmText: String by lazy { File(CHAT_VM_PATH).readText(Charsets.UTF_8) }
    private val chatEngineText: String by lazy { File(CHAT_ENGINE_PATH).readText(Charsets.UTF_8) }
    private val groupVmText: String by lazy { File(GROUP_VM_PATH).readText(Charsets.UTF_8) }

    private fun occurrences(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    @Test
    fun `六份源码都读到了`() {
        assertTrue("GroupPortsImpl.kt 读取失败/为空", groupPortsText.isNotBlank())
        assertTrue("MemoryViewModel.kt 读取失败/为空", memoryVmText.isNotBlank())
        assertTrue("ImportViewModel.kt 读取失败/为空", importVmText.isNotBlank())
        assertTrue("ChatViewModel.kt 读取失败/为空", chatVmText.isNotBlank())
        assertTrue("ChatEngine.kt 读取失败/为空", chatEngineText.isNotBlank())
        assertTrue("GroupChatViewModel.kt 读取失败/为空", groupVmText.isNotBlank())
    }

    // ---------------------------------------------------------------- 残余 2：群聊用量记账

    @Test
    fun `群聊 NonCancellable 记账在写前按清空代际收口`() {
        assertTrue(
            "LlmGroupReplyPort 必须在发起本轮时捕获清空代际",
            groupPortsText.contains("val roundWipeEpoch = container.wipeEpoch.value"),
        )
        assertTrue(
            "GroupReplyCall 必须收到「代际未变才可记账」的判定",
            groupPortsText.contains("recordAllowed = { container.wipeEpoch.value == roundWipeEpoch }"),
        )
        assertTrue(
            "GroupReplyCall 的 recordAllowed 必须有默认值（正常路径/既有测试行为不变）",
            groupPortsText.contains("private val recordAllowed: () -> Boolean = { true }"),
        )
        // recordSafely 区块：守卫必须存在且在 NonCancellable 写之前。
        val start = groupPortsText.indexOf("private suspend fun recordSafely(")
        assertTrue("找不到 recordSafely", start >= 0)
        val tail = groupPortsText.substring(start)
        val guard = tail.indexOf("if (!recordAllowed()) return")
        val write = tail.indexOf("withContext(NonCancellable)")
        assertTrue("recordSafely 必须有代际守卫", guard >= 0)
        assertTrue("守卫必须在 NonCancellable 写之前", write > guard)
    }

    // ---------------------------------------------------------------- 残余 4：MemoryViewModel

    @Test
    fun `MemoryViewModel 订阅清空代际并取消在途整理与补建`() {
        assertTrue(
            "MemoryViewModel 必须订阅 container.wipeEpoch",
            memoryVmText.contains("container.wipeEpoch.collect"),
        )
        assertTrue(
            "代际变化必须调 cancelJobsForWipe()",
            memoryVmText.contains("if (epoch != wipeEpochAtCreation) cancelJobsForWipe()"),
        )
        assertTrue("取消整理 job", memoryVmText.contains("consolidateJob?.cancel()"))
        assertTrue("取消补建 job", memoryVmText.contains("backfillJob?.cancel()"))
        // 两条后台任务的分片/批边界都要把「清空」当成停止条件（双保险）。
        assertTrue(
            "整理的 isCancelled 必须包含清空判定",
            memoryVmText.contains("isCancelled = { cancelRequested.get() || wipeChangedSince(runWipeEpoch) }"),
        )
        assertTrue(
            "补建的 isCancelled 必须包含清空判定",
            memoryVmText.contains("isCancelled = { backfillCancelRequested.get() || wipeChangedSince(runWipeEpoch) }"),
        )
        // 写库点：补建的 saveChunks 必须在清空后跳过；整理的 markConsolidated 必须在写前收口。
        assertTrue(
            "补建 saveChunks 必须在清空后跳过",
            memoryVmText.contains("if (!wipeChangedSince(runWipeEpoch)) {") &&
                memoryVmText.contains("container.memory.saveChunks(rows, emptyList())"),
        )
        val markIndex = memoryVmText.indexOf("container.importPrefs.markConsolidated(threadId)")
        assertTrue("找不到 markConsolidated 写入", markIndex >= 0)
        val lastGuardBefore = memoryVmText.lastIndexOf("if (wipeChangedSince(runWipeEpoch)) return@launch", markIndex)
        assertTrue("markConsolidated 之前必须有清空代际守卫", lastGuardBefore >= 0 && lastGuardBefore < markIndex)
        // refresh 属于清空前那一代时不再写库（ensureThread 会给已删角色重建会话行）。
        assertTrue(
            "refresh 必须按创建代际拦住旧 VM 的写库",
            memoryVmText.contains("if (wipeChangedSince(wipeEpochAtCreation)) return@launch"),
        )
    }

    // ---------------------------------------------------------------- 残余 5：ImportViewModel

    @Test
    fun `ImportViewModel 订阅清空代际并取消在途恢复与创建`() {
        assertTrue(
            "ImportViewModel 必须订阅 container.wipeEpoch",
            importVmText.contains("container.wipeEpoch.collect"),
        )
        assertTrue(
            "代际变化必须调 cancelJobsForWipe()",
            importVmText.contains("if (epoch != wipeEpochAtCreation) cancelJobsForWipe()"),
        )
        assertTrue("取消恢复 job", importVmText.contains("restoreJob?.cancel()"))
        assertTrue("取消创建 job", importVmText.contains("createJob?.cancel()"))

        // restore：必须真的 assign restoreJob，且开事务（restorer）前有代际守卫。
        assertTrue("restore 的协程必须被持有（可取消）", importVmText.contains("restoreJob = viewModelScope.launch"))
        val restorerIndex = importVmText.indexOf("restorer(doc)")
        assertTrue("找不到 restorer(doc) 调用", restorerIndex >= 0)
        val restoreGuard = importVmText.lastIndexOf("if (wipeChangedSince(runWipeEpoch)) {", restorerIndex)
        assertTrue("restorer(doc) 之前必须有清空代际守卫（否则事务照开）", restoreGuard >= 0)

        // create：必须真的 assign createJob，且建角色（createWithThread）前有代际守卫。
        assertTrue("create 的协程必须被持有（可取消）", importVmText.contains("createJob = viewModelScope.launch"))
        val createThreadIndex = importVmText.indexOf("container.characters.createWithThread(")
        assertTrue("找不到 createWithThread 调用", createThreadIndex >= 0)
        val createGuard = importVmText.lastIndexOf("if (wipeChangedSince(runWipeEpoch)) {", createThreadIndex)
        assertTrue("createWithThread 之前必须有清空代际守卫", createGuard >= 0)
        // 逐条 append 也要守（清空后不再追加旧往事）。
        val appendIndex = importVmText.indexOf("container.chat.append(", createThreadIndex)
        assertTrue("找不到创建流程里的 append", appendIndex > createThreadIndex)
        val appendGuard = importVmText.lastIndexOf("if (wipeChangedSince(runWipeEpoch)) return@forEach", appendIndex)
        assertTrue("append 之前必须按代际跳过（清空后不追加旧往事）", appendGuard >= 0 && appendGuard < appendIndex)
    }

    @Test
    fun `两个 ViewModel 的取消都是幂等清理而不是依赖异常`() {
        // 取消后必须复位按钮/忙碌状态（否则旧实例的重组会显示「进行中」卡死）。
        assertTrue(
            "MemoryViewModel 取消后要清 consolidating/backfilling",
            memoryVmText.contains("it.copy(consolidating = false, backfilling = false)"),
        )
        assertTrue(
            "ImportViewModel 取消后要清 busy/restore",
            importVmText.contains("_state.value = _state.value.copy(busy = false, restore = null)"),
        )
        // 取消路径本身不写库：cancelJobsForWipe 里不应出现任何 DB/DataStore 写调用。
        val memoryCancel = memoryVmText.substring(
            memoryVmText.indexOf("private fun cancelJobsForWipe()"),
            memoryVmText.indexOf("private fun wipeChangedSince"),
        )
        listOf("applyPatch", "saveChunks", "markConsolidated", "recordUsage", "append(").forEach { writer ->
            assertFalse("MemoryViewModel 的取消路径不得写库：$writer", memoryCancel.contains(writer))
        }
        val importCancel = importVmText.substring(
            importVmText.indexOf("private fun cancelJobsForWipe()"),
            importVmText.indexOf("private fun wipeChangedSince"),
        )
        listOf("createWithThread", "chat.append(", "restorer(").forEach { writer ->
            assertFalse("ImportViewModel 的取消路径不得写库：$writer", importCancel.contains(writer))
        }
    }

    // ---------------------------------------------------------------- （1:1）

    /**
 * 1:1 迟到索引的窗口处理：
 * `container.appScope.launch { 旧代际检查 → recordUsage（挂起）→ indexTurn/maintenance }`
 * 修复前 `recordUsage` 返回后没有复核，且 `ChatEngine.indexTurn` 内部重新取样当前代际，
 * 清空期间恢复执行就用新代际通过校验，把旧正文写成 `chunk/chunk_term`。
 *
 * 锁三件事：①`recordUsage` 之前有第一道守卫；②`recordUsage` 之后、`indexTurn`/`maintenance`
 * 之前有**同一份**代际的第二道守卫；③两个入口都收到 `expectedWipeEpoch = wipeEpoch`。
 */
    @Test
    fun `单聊记账挂起后按同一代际复核并贯穿给索引与维护`() {
        val blockStart = chatVmText.indexOf("// 记账与后台维护：不阻塞 UI")
        assertTrue("ChatViewModel 找不到记账/维护的 appScope 块", blockStart >= 0)
        val blockEnd = chatVmText.indexOf("is LlmEvent.Failed ->", blockStart)
        assertTrue("找不到 Finished 分支之后的 Failed 分支（右边界）", blockEnd > blockStart)
        val block = chatVmText.substring(blockStart, blockEnd)

        val guardLiteral = "if (wipeChangedSince(wipeEpoch)) return@launch"
        assertEquals("appScope 块内必须有两道同一代际守卫（块入口 + recordUsage 挂起返回后）", 2, occurrences(block, guardLiteral))

        val guardBefore = block.indexOf(guardLiteral)
        val recordUsageIndex = block.indexOf("container.engine.recordUsage(")
        assertTrue("块内必须有 recordUsage", recordUsageIndex > guardBefore)
        val guardAfter = block.indexOf(guardLiteral, recordUsageIndex)
        assertTrue("recordUsage 挂起返回后必须再用同一代际复核一次", guardAfter > recordUsageIndex)

        val indexTurnIndex = block.indexOf("container.engine.indexTurn(")
        val maintenanceIndex = block.indexOf("container.engine.maintenance(")
        assertTrue("块内必须有 indexTurn", indexTurnIndex > guardAfter)
        assertTrue("第二道守卫必须在 indexTurn 之前", guardAfter < indexTurnIndex)
        assertTrue("块内必须有 maintenance，且排在 indexTurn 之后", maintenanceIndex > indexTurnIndex)

        assertEquals(
            "indexTurn 与 maintenance 都必须收到调用方捕获的同一代际（不得让引擎现取）",
            2,
            occurrences(block, "expectedWipeEpoch = wipeEpoch"),
        )
    }

    // ---------------------------------------------------------------- （群聊同类）

    @Test
    fun `群聊索引把本轮捕获代际贯穿给引擎`() {
        val start = groupVmText.indexOf("private fun onTurnLanded(")
        assertTrue("GroupChatViewModel 找不到 onTurnLanded", start >= 0)
        val end = groupVmText.indexOf("private suspend fun extractMemberMemory", start)
        assertTrue("找不到 onTurnLanded 的右边界", end > start)
        val body = groupVmText.substring(start, end)

        val captureIndex = body.indexOf("val landedWipeEpoch = turnWipeEpoch")
        assertTrue("onTurnLanded 必须把本轮代际固定成局部值再进后台协程", captureIndex >= 0)
        val launchIndex = body.indexOf("container.appScope.launch")
        assertTrue("onTurnLanded 必须有 appScope 后处理块", launchIndex > captureIndex)
        val indexTurnIndex = body.indexOf("container.engine.indexTurn(")
        assertTrue("后处理块里必须有 indexTurn", indexTurnIndex > launchIndex)
        val passIndex = body.indexOf("expectedWipeEpoch = landedWipeEpoch")
        assertTrue("indexTurn 必须收到本轮捕获的代际（引擎不得重新取样）", passIndex > indexTurnIndex)
    }

    // ---------------------------------------------------------------- （引擎）

    @Test
    fun `ChatEngine 不再重新取样调用方传入的代际`() {
        // indexTurnInternal：代际只来自参数，函数体内不得出现 container.wipeEpoch.value。
        val internalStart = chatEngineText.indexOf("private suspend fun indexTurnInternal(")
        assertTrue("ChatEngine 找不到 indexTurnInternal", internalStart >= 0)
        val internalEnd = chatEngineText.indexOf(
            "// ---------------------------------------------------------------- 记账",
            internalStart,
        )
        assertTrue("找不到 indexTurnInternal 的右边界", internalEnd > internalStart)
        val internalBody = chatEngineText.substring(internalStart, internalEnd)
        assertTrue(
            "indexTurnInternal 必须用调用方传入的 expectedWipeEpoch 作为守卫起点",
            internalBody.contains("val wipeEpoch = expectedWipeEpoch"),
        )
        assertFalse(
            "indexTurnInternal 不得重新取样当前代际，否则会重新打开竞态窗口",
            internalBody.contains("container.wipeEpoch.value"),
        )
        assertTrue(
            "写 chunk 前必须保留代际守卫与 saveChunks",
            internalBody.contains("if (wipeChangedSince(wipeEpoch)) return") &&
                internalBody.contains("saveChunks"),
        )

        // 两个 indexTurn 重载 + maintenance 都必须有 expectedWipeEpoch 参数（默认值只为兼容旧调用点）。
        assertEquals(
            "indexTurn 两个重载与 maintenance 都必须接收调用方代际",
            3,
            occurrences(chatEngineText, "expectedWipeEpoch: Long = container.wipeEpoch.value"),
        )
        assertEquals(
            "两个 indexTurn 重载都必须把代际转交内部实现",
            2,
            occurrences(chatEngineText, "expectedWipeEpoch = expectedWipeEpoch"),
        )

        // maintenance：从「val wipeEpoch = ...」开始到 extractIfDue 之间不得重新取样。
        val maintenanceStart = chatEngineText.indexOf("suspend fun maintenance(")
        assertTrue("ChatEngine 找不到 maintenance", maintenanceStart >= 0)
        val maintenanceBodyStart = chatEngineText.indexOf("val wipeEpoch = expectedWipeEpoch", maintenanceStart)
        assertTrue("maintenance 必须用调用方传入的 expectedWipeEpoch", maintenanceBodyStart > maintenanceStart)
        val maintenanceEnd = chatEngineText.indexOf("private suspend fun extractIfDue", maintenanceBodyStart)
        assertTrue("找不到 maintenance 的右边界", maintenanceEnd > maintenanceBodyStart)
        val maintenanceBody = chatEngineText.substring(maintenanceBodyStart, maintenanceEnd)
        assertFalse(
            "maintenance 不得重新取样当前代际",
            maintenanceBody.contains("container.wipeEpoch.value"),
        )
        assertTrue(
            "maintenance 的两条写路径都必须用同一代际",
            maintenanceBody.contains("extractIfDue(client, thread, settings, wipeEpoch)") &&
                maintenanceBody.contains("batonIfDue(client, thread, wipeEpoch)"),
        )
    }

    // ----------------------------------------------------------------

    @Test
    fun `语音记账按发起转写时的代际守卫`() {
        // 代际必须在网络请求（client.transcribe）之前捕获，并传给记账函数。
        val capture = "val transcribeWipeEpoch = container.wipeEpoch.value"
        val captureIndex = chatVmText.indexOf(capture)
        assertTrue("找不到转写发起时的代际捕获", captureIndex >= 0)
        val transcribeIndex = chatVmText.indexOf("client.transcribe(file, durationMs)")
        assertTrue("找不到转写请求", transcribeIndex > captureIndex)
        assertTrue(
            "记账调用必须带上发起转写时的代际",
            chatVmText.contains(
                "recordVoiceUsage(accountedDurationMs(outcome.durationMs, durationMs), transcribeWipeEpoch)",
            ),
        )

        // recordVoiceUsage 块：写库前必须用该代际比对。
        val usageStart = chatVmText.indexOf("private fun recordVoiceUsage(")
        assertTrue("找不到 recordVoiceUsage", usageStart >= 0)
        val usageEnd = chatVmText.indexOf("private fun setVoiceRecordingError", usageStart)
        assertTrue("找不到 recordVoiceUsage 的右边界", usageEnd > usageStart)
        val usageBody = chatVmText.substring(usageStart, usageEnd)

        assertTrue(
            "recordVoiceUsage 必须接收发起转写时的代际（默认值只为兼容其它调用）",
            usageBody.contains("startWipeEpoch: Long = container.wipeEpoch.value"),
        )
        assertTrue("语音记账必须走 appScope", usageBody.contains("container.appScope.launch"))
        val guardIndex = usageBody.indexOf("if (wipeChangedSince(startWipeEpoch)) return@launch")
        val writeIndex = usageBody.indexOf("container.usage.record(")
        assertTrue("recordVoiceUsage 必须有清空代际守卫", guardIndex >= 0)
        assertTrue("守卫必须在写库之前", writeIndex > guardIndex)
    }
}
