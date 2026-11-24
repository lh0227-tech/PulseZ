package cc.pulsez.app.ui.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.core.ConsolidatePlan
import cc.pulsez.app.core.ConsolidateRange
import cc.pulsez.app.core.ConsolidateResult
import cc.pulsez.app.data.repo.ImportException
import cc.pulsez.app.data.repo.ImportSummary
import cc.pulsez.app.data.repo.ImportedSegment
import cc.pulsez.app.data.settings.ImportPrefs
import cc.pulsez.app.importer.deepseek.DeepSeekImporter
import cc.pulsez.app.importer.deepseek.ImportErrorCodes
import cc.pulsez.app.importer.deepseek.ZipErrorCodes
import cc.pulsez.app.importer.deepseek.ZipImportException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ImportTaskStore] 状态机测试（ 建议； /）。
 *
 * 覆盖：
 * 1. `Idle → Inspecting → Ready → Running → Done`（成功终态同时删 cache ZIP）；
 * 2. 双击「开始导入」只起一次（fake 记录调用次数）；
 * 3. 失败 → `Failed`（普通失败**保留** ZIP，供「再试一次」）；
 * 4. `AwaitingConsent → confirm(true)` 跑整理并回 Done；`confirm(false)` 回 Done（不花整理钱）；
 * 5. 取消 → `Failed(CANCELLED)`，已写指纹保留、ZIP 删除；
 * 6. `reset` 回 Idle 并删 ZIP（Ready 未开始时离开页面的路径）。
 *
 * 用 fake [ImportRunner] 驱动，不构造 Room / AppContainer / 网络；`ImportPrefs` 需要 Context，
 * 因此用 Robolectric（项目无 `coroutines-test` 之外的新依赖；这里也不用虚拟时间，用
 * `runBlocking` + `first { 终态 }`，避免 DataStore 真实 IO 与虚拟时间互相死等）。
 *
 * /、。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportTaskStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var prefs: ImportPrefs
    private lateinit var scope: CoroutineScope
    private lateinit var fake: FakeRunner
    private lateinit var store: ImportTaskStore
    private lateinit var zip: File

    @Before
    fun setUp() = runBlocking {
        // DataStore 按文件名进程内单例：不复位会让指纹断言随用例顺序漂移（参照 ImportPrefsTest）。
        prefs = ImportPrefs(context)
        prefs.clearAll()
        fake = FakeRunner()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = ImportTaskStore(scope = scope, importer = fake, prefs = prefs)
        zip = tempFolder.newFile("export.zip").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
        }
        // 默认给一个「导入成功、不整理」的结果；需要的用例再覆盖。
        fake.outcome = outcome(plan = null, consentPending = false)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------------ 夹具

    private fun params(): ImportStartParams = ImportStartParams(
        zip = zip,
        zipName = "export.zip",
        name = "阿淮",
        persona = null,
        consolidate = false,
        range = ConsolidateRange.LAST_90_DAYS,
        resumeCharacterId = null,
    )

    /** 等一个状态（真实时间，最多 5s）；返回命中的状态。 */
    private suspend fun awaitState(predicate: (ImportTaskState) -> Boolean): ImportTaskState =
        withTimeout(5_000) { store.state.first(predicate) }

    private suspend fun awaitReady() {
        store.inspect(zip, "export.zip")
        val ready = awaitState { it is ImportTaskState.Ready } as ImportTaskState.Ready
        assertEquals("export.zip", ready.zipName)
        assertEquals(2, ready.inspect.sessions)
    }

    // ------------------------------------------------------------------ 1. 正常路径

    @Test
    fun inspectStartAndDoneDeletesCachedZip() = runBlocking {
        awaitReady()
        store.start(params())

        val running = awaitState { it is ImportTaskState.Running } as ImportTaskState.Running
        assertEquals(ImportTaskState.Running.Phase.IMPORT, running.phase)

        fake.importGate.complete(Unit)
        val done = awaitState { it is ImportTaskState.Done } as ImportTaskState.Done
        assertEquals(3, done.summary.messagesInserted)
        assertTrue(done.indexed)
        assertEquals(1, fake.importCalls.size)
        assertFalse("成功终态必须删掉 cache ZIP", zip.exists())
        // 成功写续导指纹，retry/续导靠它。
        assertEquals("export.zip", prefs.lastImport.first()?.zipName)
    }

    // ------------------------------------------------------------------ 2. 双击只起一次

    @Test
    fun doubleStartOnlyRunsImporterOnce() = runBlocking {
        awaitReady()
        store.start(params())
        store.start(params())
        store.start(params())

        // 第一轮 job 会卡在 fake 的 gate 上；后两次 start 被单槽拒绝（busy）。
        fake.importGate.complete(Unit)
        awaitState { it is ImportTaskState.Done }

        assertEquals("双击「开始导入」只允许起一次", 1, fake.importCalls.size)
    }

    // ------------------------------------------------------------------ 3. 失败保留 ZIP

    @Test
    fun ordinaryFailureKeepsCachedZipForRetry() = runBlocking {
        awaitReady()
        fake.importError = ImportException(ImportException.SESSION_FAILED)
        store.start(params())
        fake.importGate.complete(Unit)

        val failed = awaitState { it is ImportTaskState.Failed } as ImportTaskState.Failed
        assertEquals(ImportException.SESSION_FAILED, failed.code)
        assertTrue("普通失败可重试，ZIP 必须保留（retry 用同一份包原样重跑）", zip.exists())
    }

    @Test
    fun inspectFailureIsFailedButKeepsZipUntilReset() = runBlocking {
        fake.inspectError = ZipImportException(ZipErrorCodes.NOT_ZIP, "不是压缩包")
        store.inspect(zip, "export.zip")

        val failed = awaitState { it is ImportTaskState.Failed } as ImportTaskState.Failed
        assertEquals(ZipErrorCodes.NOT_ZIP, failed.code)
        assertTrue("预检失败也是可重试的普通失败，保留 ZIP 给用户重选/重试", zip.exists())
    }

    // ------------------------------------------------------------------ 4. 费用二次确认

    @Test
    fun consentDeclinedGoesDoneWithoutConsolidation() = runBlocking {
        awaitReady()
        fake.outcome = outcome(plan = samplePlan(), consentPending = true)
        fake.pendingConsolidation = samplePending()
        store.start(params())
        fake.importGate.complete(Unit)

        awaitState { it is ImportTaskState.AwaitingConsent }
        store.confirmConsolidation(proceed = false)

        val done = awaitState { it is ImportTaskState.Done } as ImportTaskState.Done
        assertNull("放弃整理 = Done(consolidation = null)，不花整理钱", done.consolidation)
        assertEquals("只消费一次、不跑整理", 0, fake.resumeCalls)
        assertFalse("放弃整理也是终态，ZIP 删除", zip.exists())
    }

    @Test
    fun consentAcceptedRunsConsolidationAndGoesDone() = runBlocking {
        awaitReady()
        fake.outcome = outcome(plan = samplePlan(), consentPending = true)
        fake.pendingConsolidation = samplePending()
        store.start(params())
        fake.importGate.complete(Unit)

        awaitState { it is ImportTaskState.AwaitingConsent }
        store.confirmConsolidation(proceed = true)
        fake.resumeGate.complete(Unit)

        val done = awaitState { it is ImportTaskState.Done } as ImportTaskState.Done
        assertNotNull("确认后必须带回整理结果（有 consolidation）", done.consolidation)
        assertEquals(1, fake.resumeCalls)
        assertFalse(zip.exists())
    }

    // ------------------------------------------------------------------ 5. 取消

    @Test
    fun cancelDuringImportGoesCancelledAndDeletesZip() = runBlocking {
        awaitReady()
        store.start(params())
        awaitState { it is ImportTaskState.Running }

        store.cancel()
        fake.importGate.complete(Unit)

        val failed = awaitState { it is ImportTaskState.Failed } as ImportTaskState.Failed
        assertEquals(ImportErrorCodes.CANCELLED, failed.code)
        assertFalse("取消是终态（结果页无「再试一次」），cache ZIP 必须删", zip.exists())
        // 已提交会话的指纹仍要写（续导/结果文案依赖）。
        assertEquals("export.zip", prefs.lastImport.first()?.zipName)
    }

    @Test
    fun cancellationWhileSavingLastImportMustNotPublishDone() = runBlocking {
        store = ImportTaskStore(
            scope = scope,
            importer = fake,
            prefs = prefs,
            saveLastImport = { _, _, _, _ -> throw CancellationException("synthetic preference cancellation") },
        )
        awaitReady()

        store.start(params())
        fake.importGate.complete(Unit)

        val failed = awaitState { it is ImportTaskState.Failed } as ImportTaskState.Failed
        assertEquals("挂起保存指纹时取消不能继续发布成功终态", ImportErrorCodes.IMPORT_FAILED, failed.code)
        assertTrue("非用户主动停止时普通失败路径保留 ZIP", zip.exists())
    }

    // ------------------------------------------------------------------ 6. 重置

    @Test
    fun resetFromReadyDeletesZipAndReturnsIdle() = runBlocking {
        awaitReady()
        assertTrue(zip.exists())

        store.reset()

        assertEquals(ImportTaskState.Idle, store.state.value)
        assertFalse("离开页面（Ready 未开始）由 reset 删 cache ZIP", zip.exists())
    }

    @Test
    fun pickingAnotherZipCleansPreviousCachedFile() = runBlocking {
        // 「离开页面 Ready 未开始、没人调 reset」的兜底：下次选包时 store 删掉上一个临时 ZIP
        // （VM 被销毁后它已不知道旧文件；store 是进程级单例，仍持有引用）。
        awaitReady()
        val second = tempFolder.newFile("export-2.zip").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
        }

        store.inspect(second, "export-2.zip")
        val ready = awaitState {
            it is ImportTaskState.Ready && it.zipName == "export-2.zip"
        } as ImportTaskState.Ready

        assertEquals("export-2.zip", ready.zipName)
        assertFalse("换包后上一个 cache ZIP 必须删掉", zip.exists())
        assertTrue("当前包还在（等确认/导入用）", second.exists())
    }

    @Test
    fun resetDuringRunningSuppressesLateResult() = runBlocking {
        awaitReady()
        store.start(params())
        awaitState { it is ImportTaskState.Running }

        store.reset()
        fake.importGate.complete(Unit)
        // 给迟到结果一点时间；suppressResults 必须把它挡住（状态停在 Idle）。
        delay(300)

        assertEquals(ImportTaskState.Idle, store.state.value)
        assertFalse(zip.exists())
    }

    // ------------------------------------------------------------------ 7. 清空所有数据

    /**
 * ：清空成功后 `AppRoot` 调 `importTasks.reset`，
 * 在途导入必须**真的被取消**并回初始态，不能残留 Running/Done（旧实现只抑制结果，任务照跑）。
 *
 * 断言三件事：
 * 1. fake importer 的挂起点（`importGate.await`）收到 `CancellationException`（= 真取消，
 * 不是等它自己跑完）；
 * 2. 状态停在 [ImportTaskState.Idle]（不残留 Running/Done/Failed）；
 * 3. cache ZIP 删掉（清空后不该再留一份待恢复的导入包）。
 */
    @Test
    fun resetDuringRunningCancelsJobAndReturnsIdle() = runBlocking {
        awaitReady()
        store.start(params())
        awaitState { it is ImportTaskState.Running }

        store.reset()

        // 取消是异步传播的：等 fake 确认收到取消（最多 5s），再断言状态。
        withTimeout(5_000) { fake.importCancelled.await() }
        // 迟到结果（即使 importer 之后又吐了东西）不得覆盖 Idle：给一点时间确认没有回写。
        delay(100)

        assertEquals("reset 后必须回到初始态，不残留 Running/Done", ImportTaskState.Idle, store.state.value)
        assertFalse("reset 必须删掉待恢复的 cache ZIP", zip.exists())
    }

    @Test
    fun resetThenNewTaskStillWorks() = runBlocking {
        // reset 现在「无条件抑制结果」（防止迟到写入覆盖 Idle）；后续正常任务必须能开始
        // ——beginTask 会复位 suppressResults。这条锁「不要为了让 reset 更安全而卡死导入」。
        awaitReady()
        store.start(params())
        awaitState { it is ImportTaskState.Running }
        store.reset()
        withTimeout(5_000) { fake.importCancelled.await() }

        val second = tempFolder.newFile("export-2.zip").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
        }
        // 单槽 busy 由被取消 job 的 finally 异步释放：小步重试 inspect，直到它被接受。
        withTimeout(5_000) {
            while (store.state.value !is ImportTaskState.Inspecting &&
                store.state.value !is ImportTaskState.Ready
            ) {
                store.inspect(second, "export-2.zip")
                delay(10)
            }
        }
        val ready = awaitState { it is ImportTaskState.Ready } as ImportTaskState.Ready
        assertEquals("export-2.zip", ready.zipName)
    }

    // ------------------------------------------------------------------ 夹具构造

    private fun outcome(
        plan: ConsolidatePlan?,
        consentPending: Boolean,
    ): DeepSeekImporter.ImportOutcome = DeepSeekImporter.ImportOutcome(
        summary = summary(),
        indexed = true,
        consolidation = null,
        consolidationPlan = plan,
        consentPending = consentPending,
    )

    private fun summary(): ImportSummary = ImportSummary(
        characterId = "c-1",
        threadId = "t-1",
        sessionsImported = 1,
        sessionsSkippedExisting = 0,
        sessionsFailed = 0,
        messagesInserted = 3,
        messagesExisting = 0,
        segments = emptyList(),
        issues = emptyList(),
        lastImportedAt = 1_000L,
    )

    private fun samplePlan(): ConsolidatePlan = ConsolidatePlan(
        shards = 2,
        inputTokens = 60_000,
        messageCount = 40,
        estimatedYuan = 0.19,
    )

    private fun samplePending(): DeepSeekImporter.PendingConsolidation =
        DeepSeekImporter.PendingConsolidation(
            threadId = "t-1",
            segments = listOf(
                ImportedSegment(
                    sourceSessionId = "s-1",
                    segmentId = "seg-1",
                    messageIds = listOf("m-1", "m-2"),
                    firstMessageAt = 1_000L,
                    lastMessageAt = 2_000L,
                ),
            ),
            range = ConsolidateRange.LAST_90_DAYS,
            plan = samplePlan(),
        )

    /**
 * 假执行器：只返回 canned 结果并记录调用次数；`importGate` 让测试能停在 Running
 * 断言「双击只起一次」/「取消」等中间态。
 */
    private class FakeRunner : ImportRunner {

        val importCalls = mutableListOf<String>()
        val inspectCalls = mutableListOf<String>()
        var resumeCalls: Int = 0

        var inspectError: Throwable? = null
        var importError: Throwable? = null
        var outcome: DeepSeekImporter.ImportOutcome? = null

        val importGate = CompletableDeferred<Unit>()
        val resumeGate = CompletableDeferred<Unit>()
        /**
 * 在途导入是否真的被取消：`reset` 现在会 `activeJob.cancel`，
 * fake 在挂起点 [importGate] 收到 `CancellationException` 时 complete 这里，
 * 测试据此确认「是取消」而不是「等 importer 自己跑完」。
 */
        val importCancelled = CompletableDeferred<Unit>()

        override var pendingConsolidation: DeepSeekImporter.PendingConsolidation? = null

        override suspend fun inspect(zip: File, zipName: String): DeepSeekImporter.Inspect {
            inspectCalls += zipName
            inspectError?.let { throw it }
            return DeepSeekImporter.Inspect(
                sessions = 2,
                timeRange = 1_000L..2_000L,
                zipSize = zip.length(),
                fingerprint = DeepSeekImporter.fingerprintOf(zipName, zip.length()),
                estimatedYuan = 0.19,
                estimatedShards = 2,
            )
        }

        override suspend fun import(
            zip: File,
            zipName: String,
            name: String,
            persona: String?,
            consolidate: Boolean,
            range: ConsolidateRange,
            resumeCharacterId: String?,
            onProgress: (DeepSeekImporter.Progress) -> Unit,
            onIndexProgress: (Int, Int) -> Unit,
            onConsolidateProgress: (Int, Int) -> Unit,
            isCancelled: () -> Boolean,
            requiresConsent: suspend (ConsolidatePlan) -> Boolean,
        ): DeepSeekImporter.ImportOutcome {
            importCalls += zipName
            onProgress(DeepSeekImporter.Progress(done = 1, total = 2, messages = 0))
            try {
                importGate.await()
            } catch (ce: CancellationException) {
                // ：reset 的 job.cancel 在挂起点生效；记下来给测试断言。
                importCancelled.complete(Unit)
                throw ce
            }
            importError?.let { throw it }
            onProgress(DeepSeekImporter.Progress(done = 2, total = 2, messages = 3))
            return requireNotNull(outcome) {
                // 测试忘了给 outcome 时快速失败，避免空指针藏在协程里。
                "FakeRunner.outcome 未设置"
            }
        }

        override suspend fun resumeConsolidation(
            threadId: String,
            segments: List<ImportedSegment>,
            range: ConsolidateRange,
            plan: ConsolidatePlan,
            onProgress: (Int, Int) -> Unit,
            isCancelled: () -> Boolean,
        ): ConsolidateResult {
            resumeCalls++
            resumeGate.await()
            return ConsolidateResult(
                shardsTotal = 2,
                shardsDone = 2,
                failed = 0,
                estimatedYuan = 0.19,
                cancelled = false,
            )
        }
    }
}
