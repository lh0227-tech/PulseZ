package cc.pulsez.app.ui.importer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.repo.RestoreResult
import cc.pulsez.app.importer.BackupDocument
import cc.pulsez.app.importer.BackupInspection
import cc.pulsez.app.importer.BackupPreview
import cc.pulsez.app.importer.Format
import cc.pulsez.app.importer.ParsedImport
import cc.pulsez.app.importer.Source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 清空开始后「旧导入预览必须不可再恢复」的行为测试（ +；
 * 扩到「在途识别跨清空」）。
 *
 * ## 这个测试证明什么
 * 1. `ImportViewModel.cancelJobsForWipe` 在 `container.wipeEpoch` 变化（用户开始清空）时，
 * 除了取消在途恢复/创建，还必须把**待恢复文档缓存**与**整页预览态**复位到初始
 * （识别**已完成**后清空 → 预览复位）；
 * 2. ：识别**挂起期间**清空时，挂起返回**不得**写回 `pendingBackupDoc`/
 * `pendingBackupText` 或预览（否则旧备份在清空后重新变成「待恢复」，`restore` 按点击时
 * 代际取基准的守卫拦不住）——① `onShareText` 的识别 job 被跟踪、清空时取消；
 * ② 即使调用未经过 [ImportViewModel] 的 `inspectJobs`（`AppRoot` 文件入口直调
 * `inspectAndParse`），发起时代际复核也会拦下；
 * 3. 清空后**重新**识别 / 恢复仍正常（复位不误伤合法操作）。
 *
 * ## 设施与边界
 * Robolectric + `Dispatchers.setMain(StandardTestDispatcher(testScheduler))`（与
 * `ImportInspectEntryTest` / `ImportRestoreFlowTest` 同口径），`workDispatcher` 也注入同一
 * scheduler；识别/解析/恢复都走注入缝，不碰网络与真实备份文件。
 * `AppContainer` 用 Robolectric 的 Application Context 构造（与既有导入测试一致）。
 *
 * **未覆盖**：这里直接调用 `container.notifyUserDataWiped` 模拟清空代际，不跑真实
 * `ExportService.clearAll`；`AppRoot` 侧「全局代际换代 + 分享事件流按文本去重」的接线
 * 由 `AppRootWipeWiringSourceScanTest` / `ShareEventWiringSourceScanTest` 的源码扫描与
 * `ShareConsumptionTest` 的纯函数测试覆盖，三者合起来才是完整证据链（真机「双 Activity 并存
 * + 在导入页识别挂起时从另一应用再分享、清空、返回旧实例」仍未验，已标注）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportWipeResetTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = AppContainer(context)
    }

    @After
    fun tearDown() {
        runCatching { Dispatchers.resetMain() }
    }

    // ================================================================== 设施

    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun preview(): BackupPreview = BackupPreview(
        schema = 3,
        exportedAt = 1L,
        characterCount = 2,
        threadCount = 1,
        messageCount = 3,
        attachmentCount = 0,
        skippedOrphanThreads = 0,
    )

    private fun document(): BackupDocument = BackupDocument(
        schema = 3,
        exportedAt = 1L,
        skippedOrphanThreads = 0,
        characters = emptyList(),
        threads = emptyList(),
        attachmentCount = 0,
    )

    private fun restored(): RestoreResult.Restored = RestoreResult.Restored(
        characterCount = 2,
        threadCount = 1,
        segmentCount = 2,
        messageCount = 3,
        memoryCount = 1,
        memberCount = 0,
        skippedOrphanThreads = 0,
        ignoredCharacters = 0,
        ignoredMembers = 0,
        ignoredSpeakers = 0,
        ignoredMemories = 0,
        attachmentCount = 0,
        chunks = 1,
        indexSkipped = 0,
        firstCharacterId = "new-char",
    )

    private fun newVm(
        dispatcher: CoroutineDispatcher,
        onRestore: () -> Unit,
    ): ImportViewModel = ImportViewModel(
        container = container,
        inspector = { _, _ -> BackupInspection.Preview(preview()) },
        pipelineParse = { _, _ -> parsedImport() },
        workDispatcher = dispatcher,
        backupParser = { document() },
        restorer = { _, _ ->
            onRestore()
            restored()
        },
    )

    // ================================================================== 1 清空后旧预览不可恢复

    @Test
    fun `清空开始后 待恢复文档与整页预览态复位 不能再一键恢复`() = vmTest {
        var restorerCalls = 0
        val vm = newVm(
            dispatcher = StandardTestDispatcher(testScheduler),
            onRestore = { restorerCalls += 1 },
        )

        // 前置：清空前已经建立「待恢复的备份预览」（这是 的现场）。
        vm.inspectAndParse("PulseZ 备份文本", Source.SHARE)
        assertEquals("前置：应进入备份预览态", preview(), vm.state.value.backup)
        assertNull("前置：预览态不该有恢复结果", vm.state.value.restore)
        assertEquals("前置：还没人点恢复", 0, restorerCalls)

        // 用户确认清空 → AppRoot.runClear 调用 notifyUserDataWiped（清空执行起点）。
        container.notifyUserDataWiped()
        advanceUntilIdle()

        // 断言：整页状态复位到初始（备份两态/解析草稿/名字人设/恢复结果/createdId 都不能留）。
        assertEquals("清空后整页状态必须回到初始态", ImportUiState(), vm.state.value)
        assertNull("清空前的备份预览不得留在旧实例里", vm.state.value.backup)
        assertNull(vm.state.value.backupRejected)
        assertNull(vm.state.value.parsed)
        assertNull("清空前的恢复结果不得留", vm.state.value.restore)
        assertNull("「创建完成」信号不得留（否则会导航到已删角色）", vm.state.value.createdId)
        assertEquals("", vm.state.value.raw)

        // 旧实例即使被复用并点「恢复」，也没有待恢复文档可写 → 不打开事务、不写回。
        vm.restore()
        advanceUntilIdle()
        assertEquals("清空后点恢复不得调用恢复仓库（无待恢复文档）", 0, restorerCalls)
        assertNull(vm.state.value.restore)
    }

    // ================================================================== 2 正常识别语义不被改坏

    @Test
    fun `清空后重新识别仍正常（复位不影响后续合法操作）`() = vmTest {
        var restorerCalls = 0
        val vm = newVm(
            dispatcher = StandardTestDispatcher(testScheduler),
            onRestore = { restorerCalls += 1 },
        )

        vm.inspectAndParse("第一份备份文本", Source.SHARE)
        assertEquals(preview(), vm.state.value.backup)

        container.notifyUserDataWiped()
        advanceUntilIdle()
        assertEquals(ImportUiState(), vm.state.value)

        // 清空后再来一次合法识别（用户重新选文件/分享新内容）：恢复正常可用。
        vm.inspectAndParse("第二份备份文本", Source.FILE)
        advanceUntilIdle()
        assertNotNull("清空后的新识别仍要能建立预览", vm.state.value.backup)
        assertEquals(preview(), vm.state.value.backup)

        // 新预览可以正常恢复（代际已更新到当前值，不误伤合法操作）。
        vm.restore()
        advanceUntilIdle()
        assertEquals("清空后的新预览应能恢复", 1, restorerCalls)
        assertNotNull(vm.state.value.restore)
    }

    // ================================================================== 3 识别挂起跨清空

    /**
 * 未经 `inspectJobs` 的识别（`AppRoot` 文件入口直调 `inspectAndParse`）在 `inspector` 里
 * 挂起时清空：挂起返回后必须靠**发起时代际复核**直接返回——不写 `pendingBackupDoc`/
 * `pendingBackupText`、不写预览、不再调 `backupParser`。之后即使旧实例被复用点「恢复」，
 * 也没有待恢复文档可写。
 */
    @Test
    fun `识别挂起期间清空 挂起返回后不写预览与待恢复缓存`() = vmTest {
        var restorerCalls = 0
        var parserCalls = 0
        val inspectorGate = CompletableDeferred<Unit>()
        val vm = ImportViewModel(
            container = container,
            inspector = { _, _ ->
                inspectorGate.await()
                BackupInspection.Preview(preview())
            },
            pipelineParse = { _, _ -> parsedImport() },
            workDispatcher = StandardTestDispatcher(testScheduler),
            backupParser = {
                parserCalls += 1
                document()
            },
            restorer = { _, _ ->
                restorerCalls += 1
                restored()
            },
        )

        // 刻意直接调 inspectAndParse：这条路径没有 inspectJobs 可取消（旧缺口就是「识别未被跟踪」），
        // 所以这里验证的是第二道防线——挂起返回后的发起时代际复核。
        val job = launch { vm.inspectAndParse("PulseZ 备份文本", Source.SHARE) }
        advanceUntilIdle()
        assertTrue("前置：识别应挂起在 inspector 里（busy=true）", vm.state.value.busy)

        // 用户确认清空 → 清空执行起点自增代际；wipe 收口复位页面状态。
        container.notifyUserDataWiped()
        advanceUntilIdle()
        assertEquals("清空收口后整页应回到初始态", ImportUiState(), vm.state.value)

        // 旧识别从挂起中返回：不得写任何预览/原文/待恢复缓存。
        inspectorGate.complete(Unit)
        advanceUntilIdle()
        job.join()

        assertEquals("清空后返回的旧识别不得改任何页面状态", ImportUiState(), vm.state.value)
        assertNull("不得留下清空前的备份预览", vm.state.value.backup)
        assertEquals("清空后返回的旧识别不得再解析待恢复文档", 0, parserCalls)

        // 旧实例被复用点「恢复」：无待恢复文档 → 不打开事务、不写回。
        vm.restore()
        advanceUntilIdle()
        assertEquals("清空后点恢复不得调用恢复仓库", 0, restorerCalls)
        assertNull(vm.state.value.restore)
    }

    /**
 * `onShareText` 的识别 job 现在被 [ImportViewModel] 持有（`inspectJobs`）：清空时**取消**，
 * 页面回到初始态、不卡在 busy；取消后即使注入缝被放开也不会再写预览。
 */
    @Test
    fun `分享入口的识别 job 被跟踪 清空时取消且不写预览`() = vmTest {
        val inspectorGate = CompletableDeferred<Unit>()
        var inspectorCalls = 0
        var parserCalls = 0
        val vm = ImportViewModel(
            container = container,
            inspector = { _, _ ->
                inspectorCalls += 1
                inspectorGate.await()
                BackupInspection.Preview(preview())
            },
            pipelineParse = { _, _ -> parsedImport() },
            workDispatcher = StandardTestDispatcher(testScheduler),
            backupParser = {
                parserCalls += 1
                document()
            },
            restorer = { _, _ -> restored() },
        )

        vm.onShareText("分享的备份文本")
        advanceUntilIdle()
        assertEquals("前置：识别应已进入 inspector", 1, inspectorCalls)
        assertTrue("前置：识别中 busy=true", vm.state.value.busy)
        vm.onShareText("识别中到达的第二份分享")
        advanceUntilIdle()
        assertEquals("识别忙时第二份分享应直接丢弃，不启动或覆盖 job", 1, inspectorCalls)
        assertTrue("丢弃第二份分享后第一份仍保持 busy", vm.state.value.busy)

        container.notifyUserDataWiped()
        advanceUntilIdle() // cancelJobsForWipe → 取消所有 inspectJobs（gate.await 抛取消）

        assertEquals("取消后页面必须复位、不卡在 busy", ImportUiState(), vm.state.value)
        assertNull("取消后不得留下预览", vm.state.value.backup)

        // 取消之后再放开注入缝：已取消的协程不会继续写状态。
        inspectorGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("被取消的旧识别不得再写状态", ImportUiState(), vm.state.value)
        assertEquals("被取消的旧识别不得再解析待恢复文档", 0, parserCalls)
    }

    @Test
    fun `清空后旧识别异常返回不写 busy 或 error`() = vmTest {
        val inspectorGate = CompletableDeferred<Unit>()
        val vm = ImportViewModel(
            container = container,
            inspector = { _, _ ->
                inspectorGate.await()
                throw IllegalStateException("模拟清空后迟到的识别失败")
            },
            pipelineParse = { _, _ -> parsedImport() },
            workDispatcher = StandardTestDispatcher(testScheduler),
            backupParser = { document() },
            restorer = { _, _ -> restored() },
        )

        // 直接调用识别入口，确保此错误返回依赖 startEpoch 复核而非 job 取消。
        val job = launch { vm.inspectAndParse("PulseZ 备份文本", Source.SHARE) }
        advanceUntilIdle()
        assertTrue("前置：识别挂起时 busy=true", vm.state.value.busy)

        container.notifyUserDataWiped()
        advanceUntilIdle()
        assertEquals("清空收口后页面复位", ImportUiState(), vm.state.value)

        inspectorGate.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertEquals("清空后迟到的异常不得写 busy/error", ImportUiState(), vm.state.value)
    }
}

private fun parsedImport(): ParsedImport = ParsedImport(
    name = "阿淮",
    persona = "人设草稿",
    greeting = null,
    styleSamples = emptyList(),
    turns = emptyList(),
    format = Format.TEXT,
    warnings = emptyList(),
)
