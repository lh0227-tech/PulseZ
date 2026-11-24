package cc.pulsez.app.ui.importer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.ExportService
import cc.pulsez.app.data.db.CharacterEntity
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.SegmentEntity
import cc.pulsez.app.data.db.ThreadEntity
import cc.pulsez.app.data.db.ThreadKind
import cc.pulsez.app.importer.BackupInspection
import cc.pulsez.app.importer.BackupIssue
import cc.pulsez.app.importer.BackupPreview
import cc.pulsez.app.importer.BackupParser
import cc.pulsez.app.importer.Format
import cc.pulsez.app.importer.ParsedImport
import cc.pulsez.app.importer.Source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * VM 三个入口与状态语义。
 *
 * Robolectric + `Dispatchers.setMain(StandardTestDispatcher(testScheduler))`，并**显式**把
 * `workDispatcher` 注入成同一 scheduler 的 dispatcher。注入 `inspector`/
 * `pipelineParse` 计数，锁住：
 * - 文件/粘贴/分享三入口的备份识别：Preview/Rejected 时 `raw == ""`、`pipelineParse` 0 次；
 * - `Normal` 分支仍写 `raw`、调用一次解析；两个方向都清旧状态；
 * - 分享入口一次性消费（二次调用不再 inspect、不再改状态）；
 * - `inspectAndParse` 首行空白守卫（状态零变化 + 两个注入缝 0 次）；
 * - `parse` 空 raw 回归、`resetToEdit` 清理清单；
 * - 防重入（suspend 注入缝挂起时 `busy == true`，第二次直调立即返回）；
 * - **端到端**：真实 `ExportService.buildJson` 输出 → VM 识别为备份预览（禁止手写 JSON 冒充）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportInspectEntryTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer

    /** 只有端到端用例会碰真库；@After 只在真用过时关，避免每个用例都建一次文件库。 */
    private var usedDatabase = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(APP_DB_NAME)
        container = AppContainer(context)
        usedDatabase = false
    }

    @After
    fun tearDown() {
        if (usedDatabase) runCatching { container.database.close() }
        context.deleteDatabase(APP_DB_NAME)
    }

    // ---------------------------------------------------------------- 设施

    private class Fakes(
        var inspection: BackupInspection = BackupInspection.Normal,
        var parsed: ParsedImport = parsedImport(),
    ) {
        var inspectCalls = 0
        var pipelineCalls = 0
        val pipelineTexts = mutableListOf<String>()
        val inspectSources = mutableListOf<Source>()

        suspend fun inspect(text: String, source: Source): BackupInspection {
            inspectCalls += 1
            inspectSources += source
            return inspection
        }

        suspend fun pipeline(text: String, source: Source): ParsedImport {
            pipelineCalls += 1
            pipelineTexts += text
            return parsed
        }
    }

    private fun newVm(fakes: Fakes, dispatcher: CoroutineDispatcher): ImportViewModel =
        ImportViewModel(
            container = container,
            inspector = fakes::inspect,
            pipelineParse = fakes::pipeline,
            workDispatcher = dispatcher,
        )

    /** 统一：runTest + Main 换成同一 scheduler 的 StandardTestDispatcher。 */
    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun preview(skipped: Int = 0) = BackupPreview(
        schema = 3,
        exportedAt = 1L,
        characterCount = 2,
        threadCount = 1,
        messageCount = 3,
        attachmentCount = 0,
        skippedOrphanThreads = skipped,
    )

    // ---------------------------------------------------------------- 三入口：备份识别

    @Test
    fun `FILE 入口 备份 预览态 raw 为空 解析 0 次`() = vmTest {
        val fakes = Fakes(inspection = BackupInspection.Preview(preview()))
        val vm = newVm(fakes, StandardTestDispatcher(testScheduler))

        vm.inspectAndParse("{\"schema\":3,...}", Source.FILE)

        assertNotNull("文件入口必须识别为备份预览", vm.state.value.backup)
        assertEquals(preview(), vm.state.value.backup)
        assertEquals("备份原文不得进输入框", "", vm.state.value.raw)
        assertNull(vm.state.value.parsed)
        assertEquals(0, fakes.pipelineCalls)
        assertEquals(1, fakes.inspectCalls)
        assertEquals(listOf(Source.FILE), fakes.inspectSources)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `FILE 入口 备份被拒 拒绝态 raw 为空 解析 0 次`() = vmTest {
        val fakes = Fakes(inspection = BackupInspection.Rejected(BackupIssue.DAMAGED))
        val vm = newVm(fakes, StandardTestDispatcher(testScheduler))

        vm.inspectAndParse("{\"schema\":3,...}", Source.FILE)

        assertEquals(BackupIssue.DAMAGED, vm.state.value.backupRejected)
        assertNull(vm.state.value.backup)
        assertEquals("", vm.state.value.raw)
        assertNull(vm.state.value.parsed)
        assertEquals(0, fakes.pipelineCalls)
    }

    @Test
    fun `PASTE 入口 备份 走 parse 包装 预览态 raw 为空`() = vmTest {
        val fakes = Fakes(inspection = BackupInspection.Preview(preview(skipped = 1)))
        val vm = newVm(fakes, StandardTestDispatcher(testScheduler))

        vm.onRawChange("{\"schema\":3,...}")
        vm.parse()
        advanceUntilIdle()

        assertEquals(preview(skipped = 1), vm.state.value.backup)
        assertEquals("备份原文不得留在输入框", "", vm.state.value.raw)
        assertNull(vm.state.value.parsed)
        assertEquals(0, fakes.pipelineCalls)
        assertEquals(listOf(Source.PASTE), fakes.inspectSources)
    }

    @Test
    fun `普通 PASTE 仍写 raw 并调用一次解析`() = vmTest {
        val fakes = Fakes(inspection = BackupInspection.Normal)
        val vm = newVm(fakes, StandardTestDispatcher(testScheduler))

        vm.onRawChange("一段普通人设")
        vm.parse()
        advanceUntilIdle()

        assertEquals("一段普通人设", vm.state.value.raw)
        assertEquals(1, fakes.pipelineCalls)
        assertEquals("一段普通人设", fakes.pipelineTexts.single())
        assertNotNull(vm.state.value.parsed)
        assertNull(vm.state.value.backup)
        assertNull(vm.state.value.backupRejected)
    }

    @Test
    fun `先普通卡再备份 清空解析态；先备份再普通文本 清空备份态`() = vmTest {
        val fakes = Fakes(inspection = BackupInspection.Normal)
        val vm = newVm(fakes, StandardTestDispatcher(testScheduler))

        // 普通卡 → 预览
        vm.onRawChange("普通人设")
        vm.parse()
        advanceUntilIdle()
        assertNotNull(vm.state.value.parsed)

        // 再识别备份：解析态必须清空
        fakes.inspection = BackupInspection.Preview(preview())
        vm.inspectAndParse("backup", Source.FILE)
        assertNull(vm.state.value.parsed)
        assertEquals(preview(), vm.state.value.backup)
        assertEquals("", vm.state.value.raw)

        // 再普通文本：备份态必须清空
        fakes.inspection = BackupInspection.Normal
        vm.onRawChange("另一段人设")
        vm.parse()
        advanceUntilIdle()
        assertNull("普通文本必须清掉旧备份预览", vm.state.value.backup)
        assertNull(vm.state.value.backupRejected)
        assertEquals("另一段人设", vm.state.value.raw)
        assertNotNull(vm.state.value.parsed)
    }

    @Test
    fun `分享入口二次调用只识别一次 状态只应用一次`() = vmTest {
        val fakes = Fakes(inspection = BackupInspection.Preview(preview()))
        val vm = newVm(fakes, StandardTestDispatcher(testScheduler))

        vm.onShareText("分享内容")
        advanceUntilIdle()
        val afterFirst = vm.state.value
        vm.onShareText("分享内容")
        advanceUntilIdle()

        assertEquals("第二次不得重复 inspect", 1, fakes.inspectCalls)
        assertEquals(listOf(Source.SHARE), fakes.inspectSources)
        assertEquals("第二次不得重复改状态", afterFirst, vm.state.value)
    }

    @Test
    fun `普通 PASTE 6MB 无形状文本 仍是 Normal 并进入解析`() = vmTest {
        // 真实 inspector（默认 BackupParser），只替换 pipeline 计数；6MB 用例见 H3。
        // 内存：约 6.3M 字符 ≈ 13 MB（用例内注明）。
        val pipelineCount = intArrayOf(0)
        val vm = ImportViewModel(
            container = container,
            pipelineParse = { _, _ ->
                pipelineCount[0] += 1
                parsedImport()
            },
            workDispatcher = StandardTestDispatcher(testScheduler),
        )
        val bigText = "x".repeat(6 * 1024 * 1024)
        vm.onRawChange(bigText)
        vm.parse()
        advanceUntilIdle()

        assertEquals("普通大文本不得被判成备份", 1, pipelineCount[0])
        assertNull(vm.state.value.backup)
        assertNull(vm.state.value.backupRejected)
    }

    // ---------------------------------------------------------------- 空白守卫

    @Test
    fun `空白文本守卫 静默 状态零变化 两个注入缝 0 次`() = vmTest {
        val fakes = Fakes()
        val vm = newVm(fakes, StandardTestDispatcher(testScheduler))
        val before = vm.state.value

        vm.inspectAndParse("  ", Source.FILE)
        vm.inspectAndParse("", Source.FILE)
        vm.inspectAndParse(" ", Source.SHARE)

        assertEquals("空白文本必须状态零变化", before, vm.state.value)
        assertEquals(0, fakes.inspectCalls)
        assertEquals(0, fakes.pipelineCalls)
        assertNull(vm.state.value.backup)
        assertNull(vm.state.value.backupRejected)
        assertNull(vm.state.value.parsed)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
    }

    // ---------------------------------------------------------------- parse 空 raw 回归

    @Test
    fun `parse 空 raw 保持既有提示 不调注入缝`() = vmTest {
        val fakes = Fakes()
        val vm = newVm(fakes, StandardTestDispatcher(testScheduler))

        vm.onRawChange("")
        vm.parse()

        assertEquals("没读到内容。再看看是不是复制漏了？", vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertEquals(0, fakes.inspectCalls)
        assertEquals(0, fakes.pipelineCalls)
        assertNull(vm.state.value.backup)
        assertNull(vm.state.value.parsed)
    }

    // ---------------------------------------------------------------- resetToEdit

    @Test
    fun `resetToEdit 清草稿与备份两态 并复位 busy createdId`() = vmTest {
        val fakes = Fakes(inspection = BackupInspection.Preview(preview()))
        val vm = newVm(fakes, StandardTestDispatcher(testScheduler))
        vm.onRawChange("草稿")
        vm.onNameChange("名字")
        vm.onPersonaChange("人设")
        vm.inspectAndParse("backup", Source.FILE)

        vm.resetToEdit()

        assertEquals("", vm.state.value.raw)
        assertNull(vm.state.value.backup)
        assertNull(vm.state.value.backupRejected)
        assertNull(vm.state.value.parsed)
        assertEquals("", vm.state.value.name)
        assertEquals("", vm.state.value.persona)
        assertTrue(vm.state.value.warnings.isEmpty())
        assertNull(vm.state.value.error)
        assertFalse("必须复位 busy", vm.state.value.busy)
        assertNull("必须清 createdId 防旧 id 重导航", vm.state.value.createdId)
    }

    // ---------------------------------------------------------------- 防重入

    @Test
    fun `busy 防重入 第二次直调立即返回 状态只应用一次`() = vmTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var inspectCount = 0
        var pipelineCount = 0
        val vm = ImportViewModel(
            container = container,
            inspector = { _, _ ->
                inspectCount += 1
                started.complete(Unit)
                gate.await()
                BackupInspection.Normal
            },
            pipelineParse = { _, _ ->
                pipelineCount += 1
                parsedImport()
            },
            workDispatcher = StandardTestDispatcher(testScheduler),
        )

        val first = launch { vm.inspectAndParse("第一段", Source.FILE) }
        runCurrent()
        assertTrue("注入的 suspend inspector 必须已进入并挂起", started.isCompleted)
        assertTrue("第一段解析期间 busy 必须为 true", vm.state.value.busy)

        // 第二次直调（FILE 路径绕过 parse 的守卫）：必须立即 return。
        vm.inspectAndParse("第二段", Source.FILE)
        assertEquals("第二次不得再进入 inspector", 1, inspectCount)

        gate.complete(Unit)
        advanceUntilIdle()
        first.join()

        assertEquals(1, inspectCount)
        assertEquals(1, pipelineCount)
        assertEquals("状态只按第一次文本应用", "第一段", vm.state.value.raw)
        assertFalse(vm.state.value.busy)
    }

    // ---------------------------------------------------------------- 端到端

    @Test
    fun `真实 buildJson 输出经 VM 识别为备份预览 不走人设解析`() {
        // 真库种一份最小数据并跑真实导出（同步完成后，再把 JSON 交给 VM）。
        val json = runBlocking {
            usedDatabase = true
            seedMinimalDatabase()
            ExportService.buildJson(container.database)
        }

        vmTest {
            val pipelineCount = intArrayOf(0)
            val vm = ImportViewModel(
                container = container,
                pipelineParse = { _, _ ->
                    pipelineCount[0] += 1
                    parsedImport()
                },
                workDispatcher = StandardTestDispatcher(testScheduler),
            )

            vm.inspectAndParse(json, Source.PASTE)
            val backup = vm.state.value.backup
            requireNotNull(backup) { "真实导出必须被识别为备份（而不是人设），实际 state=${vm.state.value}" }
            assertEquals(1, backup.characterCount)
            assertEquals(1, backup.threadCount)
            assertEquals(1, backup.messageCount)
            assertEquals("备份原文不得进 raw", "", vm.state.value.raw)
            assertEquals("备份不得进普通解析", 0, pipelineCount[0])

            // 同一份 JSON 走 FILE 入口语义相同。
            vm.resetToEdit()
            vm.inspectAndParse(json, Source.FILE)
            assertNotNull(vm.state.value.backup)
            assertEquals(0, pipelineCount[0])
            assertEquals("", vm.state.value.raw)
        }
    }

    private suspend fun seedMinimalDatabase() {
        val db = container.database
        db.characterDao().upsert(
            CharacterEntity(
                id = "char-seed",
                name = "阿淮",
                avatarUri = null,
                persona = "温和、话不多",
                greeting = null,
                styleSamples = null,
                userName = null,
                systemPromptOverride = null,
                protocolEnabled = true,
                sourceType = "MANUAL",
                sourceRef = null,
                rawImported = null,
                emoAssetDir = null,
                createdAt = 1L,
                updatedAt = 2L,
                deletedAt = null,
            ),
        )
        db.threadDao().upsert(
            ThreadEntity(
                id = "thread-seed",
                characterId = "char-seed",
                title = null,
                createdAt = 1L,
                lastMessageAt = 2L,
                activeSegmentId = "seg-seed",
                segmentOrdinal = 0,
                totalMessages = 1,
                totalTokens = 2,
                archived = false,
                kind = ThreadKind.SINGLE,
            ),
        )
        db.segmentDao().upsert(
            SegmentEntity(
                id = "seg-seed",
                threadId = "thread-seed",
                ordinal = 0,
                summary = null,
                styleNotes = null,
                firstMessageAt = 1L,
                lastMessageAt = 2L,
                tokenSum = 2,
                state = "OPEN",
                batonReason = null,
                createdAt = 1L,
            ),
        )
        db.messageDao().upsert(
            MessageEntity(
                id = "m-seed",
                threadId = "thread-seed",
                segmentId = "seg-seed",
                role = "assistant",
                content = "在的",
                tokens = 2,
                emotion = null,
                status = "OK",
                draft = null,
                usageIn = null,
                usageOut = null,
                previousContent = null,
                createdAt = 2L,
                deletedAt = null,
                speakerCharacterId = null,
            ),
        )
    }

    private companion object {
        const val APP_DB_NAME = "pulsez.db"
    }
}

/** 普通解析结果（默认空 turns，走 TEXT 路径）。 */
private fun parsedImport(persona: String = "人设草稿"): ParsedImport = ParsedImport(
    name = "阿淮",
    persona = persona,
    greeting = null,
    styleSamples = emptyList(),
    turns = emptyList(),
    format = Format.TEXT,
    warnings = emptyList(),
)
