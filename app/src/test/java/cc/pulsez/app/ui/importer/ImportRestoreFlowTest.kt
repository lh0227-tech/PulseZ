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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 备份恢复的 VM 状态机（ T4）。
 *
 * 固定设施同 的 `ImportInspectEntryTest`：Robolectric + `Dispatchers.setMain(StandardTestDispatcher)`，
 * `workDispatcher` 也注入同一 scheduler；所有注入缝都是 suspend 函数类型。
 * 锁住：预览→执行→结果、双击不二次执行、失败可重试、解析兜底、复位清干净、重新识别清旧结果、
 * 无待恢复文档时零变化、空白文本静默。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportRestoreFlowTest {

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

    private class Fakes {
        var inspection: BackupInspection = BackupInspection.Normal
        var backupParserCalls = 0
        var restorerCalls = 0
        var parsed: ParsedImport = parsedImport()
    }

    private fun newVm(
        fakes: Fakes,
        dispatcher: CoroutineDispatcher,
        backupParser: suspend (String) -> BackupDocument = { document() },
        restorer: suspend (BackupDocument, (Int, Int) -> Unit) -> RestoreResult = { _, _ ->
            fakes.restorerCalls += 1
            restored()
        },
    ): ImportViewModel = ImportViewModel(
        container = container,
        inspector = { _, _ -> fakes.inspection },
        pipelineParse = { _, _ -> fakes.parsed },
        workDispatcher = dispatcher,
        backupParser = { text ->
            fakes.backupParserCalls += 1
            backupParser(text)
        },
        restorer = restorer,
    )

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

    // ================================================================== 1 预览→恢复→结果

    @Test
    fun previewThenRestoreRunsOnce() = vmTest {
        val fakes = Fakes().apply { inspection = BackupInspection.Preview(preview()) }
        var receivedDocument: BackupDocument? = null
        var restoreCount = 0
        val vm = newVm(
            fakes = fakes,
            dispatcher = StandardTestDispatcher(testScheduler),
            restorer = { doc, _ ->
                restoreCount += 1
                receivedDocument = doc
                restored()
            },
        )

        vm.inspectAndParse("备份文本", Source.FILE)
        assertEquals(preview(), vm.state.value.backup)
        assertNull("还没点恢复：restore 必须为 null（预览态）", vm.state.value.restore)
        assertEquals("", vm.state.value.raw)

        vm.restore()
        advanceUntilIdle()

        assertEquals(document(), receivedDocument)
        assertEquals(1, restoreCount)
        assertFalse(vm.state.value.busy)
        val done = vm.state.value.restore as? RestoreUi.Done
        assertNotNull("成功后必须是 Done", done)
        assertEquals(restored(), done!!.result)
    }

    // ================================================================== 2 双击禁用

    @Test
    fun restoreDoubleClickIsIgnored() = vmTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var restoreCount = 0
        val fakes = Fakes().apply { inspection = BackupInspection.Preview(preview()) }
        val vm = newVm(
            fakes = fakes,
            dispatcher = StandardTestDispatcher(testScheduler),
            restorer = { _, _ ->
                restoreCount += 1
                started.complete(Unit)
                gate.await()
                restored()
            },
        )
        vm.inspectAndParse("备份文本", Source.FILE)

        vm.restore()
        runCurrent()
        assertTrue("恢复协程必须已启动并挂起", started.isCompleted)
        assertTrue("执行中 busy 必须为 true", vm.state.value.busy)
        assertTrue(vm.state.value.restore is RestoreUi.Running)

        vm.restore() // 第二次：必须被 busy 守卫挡住
        assertEquals("双击不得二次执行", 1, restoreCount)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, restoreCount)
        assertFalse(vm.state.value.busy)
        assertTrue("状态只应用一次 Done", vm.state.value.restore is RestoreUi.Done)
    }

    // ================================================================== 3 失败可重试

    @Test
    fun restoreFailureShowsRetry() = vmTest {
        var restoreCount = 0
        val fakes = Fakes().apply { inspection = BackupInspection.Preview(preview()) }
        val vm = newVm(
            fakes = fakes,
            dispatcher = StandardTestDispatcher(testScheduler),
            restorer = { _, _ ->
                restoreCount += 1
                RestoreResult.Failed
            },
        )
        vm.inspectAndParse("备份文本", Source.FILE)

        vm.restore()
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertTrue("失败态必须是 Failed", vm.state.value.restore is RestoreUi.Failed)

        vm.restore() // 失败态「再试一次」→ 第二次被调用
        advanceUntilIdle()
        assertEquals(2, restoreCount)
    }

    // ================================================================== 4 恢复时解析兜底

    @Test
    fun parseFailureAtRestoreFallsBackToFailed() = vmTest {
        var restoreCount = 0
        // inspector 返回 Preview（假文本），但**不注入** backupParser → 默认真解析会失败。
        val vm = ImportViewModel(
            container = container,
            inspector = { _, _ -> BackupInspection.Preview(preview()) },
            pipelineParse = { _, _ -> parsedImport() },
            workDispatcher = StandardTestDispatcher(testScheduler),
            restorer = { _, _ ->
                restoreCount += 1
                restored()
            },
        )

        vm.inspectAndParse("这不是 JSON 的假备份文本", Source.FILE)
        assertNotNull("已判定的备份不得降级（不因解析失败退回人设）", vm.state.value.backup)
        assertNull(vm.state.value.parsed)

        vm.restore()
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertTrue("解析失败 → Failed", vm.state.value.restore is RestoreUi.Failed)
        assertEquals("解析失败不得调用 restorer", 0, restoreCount)
    }

    // ================================================================== 5 复位清干净

    @Test
    fun resetToEditClearsRestoreState() = vmTest {
        var restoreCount = 0
        val fakes = Fakes().apply { inspection = BackupInspection.Preview(preview()) }
        val vm = newVm(
            fakes = fakes,
            dispatcher = StandardTestDispatcher(testScheduler),
            restorer = { _, _ ->
                restoreCount += 1
                restored()
            },
        )
        vm.inspectAndParse("备份文本", Source.FILE)
        vm.restore()
        advanceUntilIdle()
        assertTrue(vm.state.value.restore is RestoreUi.Done)

        vm.resetToEdit()
        assertNull(vm.state.value.restore)
        assertNull(vm.state.value.backup)
        assertEquals("", vm.state.value.raw)
        assertFalse(vm.state.value.busy)

        vm.restore() // 复位后无待恢复文档：无副作用
        advanceUntilIdle()
        assertEquals(1, restoreCount)
        assertNull(vm.state.value.restore)
    }

    // ================================================================== 6 重新识别清旧结果

    @Test
    fun newInspectionClearsPreviousRestore() = vmTest {
        val fakes = Fakes().apply { inspection = BackupInspection.Preview(preview()) }
        val vm = newVm(fakes = fakes, dispatcher = StandardTestDispatcher(testScheduler))
        vm.inspectAndParse("备份文本", Source.FILE)
        vm.restore()
        advanceUntilIdle()
        assertTrue(vm.state.value.restore is RestoreUi.Done)

        fakes.inspection = BackupInspection.Normal
        vm.inspectAndParse("一段普通人设", Source.PASTE)
        assertNull("重新识别后不得残留上次恢复结果", vm.state.value.restore)
        assertNull(vm.state.value.backup)
        assertNotNull(vm.state.value.parsed)
    }

    // ================================================================== 7 无待恢复文档

    @Test
    fun restoreWithoutPendingDocumentIsNoOp() = vmTest {
        var restoreCount = 0
        val vm = ImportViewModel(
            container = container,
            inspector = { _, _ -> BackupInspection.Normal },
            pipelineParse = { _, _ -> parsedImport() },
            workDispatcher = StandardTestDispatcher(testScheduler),
            restorer = { _, _ ->
                restoreCount += 1
                restored()
            },
        )
        val before = vm.state.value
        vm.restore()
        advanceUntilIdle()
        assertEquals("没有待恢复文档：状态必须逐字段不变", before, vm.state.value)
        assertEquals(0, restoreCount)
    }

    // ================================================================== 8 空白文本静默（X2 回归）

    @Test
    fun blankTextStillSilent() = vmTest {
        val fakes = Fakes()
        val vm = newVm(fakes = fakes, dispatcher = StandardTestDispatcher(testScheduler))
        val before = vm.state.value
        vm.inspectAndParse("  ", Source.FILE)
        advanceUntilIdle()
        assertEquals(before, vm.state.value)
        assertEquals("空白文本不得触发文档解析", 0, fakes.backupParserCalls)
        assertEquals(0, fakes.restorerCalls)
    }
}

/** 普通解析结果（与 测试同口径）。 */
private fun parsedImport(): ParsedImport = ParsedImport(
    name = "阿淮",
    persona = "人设草稿",
    greeting = null,
    styleSamples = emptyList(),
    turns = emptyList(),
    format = Format.TEXT,
    warnings = emptyList(),
)
