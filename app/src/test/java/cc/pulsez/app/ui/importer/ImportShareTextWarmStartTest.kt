package cc.pulsez.app.ui.importer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.importer.BackupInspection
import cc.pulsez.app.importer.Format
import cc.pulsez.app.importer.ParsedImport
import cc.pulsez.app.importer.Source
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 分享入口「按文本去重」的行为测试。
 *
 * ## 这个测试证明什么
 * 有两层：① `AppRoot` 事件流把温启动的不同文本送到导入页（接线由
 * `ShareEventWiringSourceScanTest` / `ShareConsumptionTest` 锁）；② `ImportViewModel.onShareText`
 * 的第二道去重**不能再是一个实例级布尔值**——旧实现 `shareConsumed=true` 后直接 return，
 * 同一 Activity/同一 VM 上第二条**不同**文本会被静默丢掉（的第二层）。
 * 现在按**文本内容**比较：同一份文本不重复识别，不同文本照常识别。
 *
 * 这与 `ImportInspectEntryTest`「分享入口二次调用只识别一次」不冲突：那条用例两次传的是
 * **相同**文本（本文件第 1 条），锁的是「同一份文本只识别一次」的既有语义。
 *
 * ## 设施与边界
 * Robolectric + `Dispatchers.setMain(StandardTestDispatcher(testScheduler))`（与
 * `ImportInspectEntryTest` / `ImportWipeResetTest` 同口径），识别/解析走注入缝，不碰网络与
 * 真实备份文件；`AppContainer` 用 Robolectric Application Context（依赖全 lazy）。
 *
 * **未覆盖**：真正的 `MainActivity.onNewIntent` → `StateFlow` → Compose 重组 → 压栈/注入这条
 * Android/Compose 端到端链路在这里跑不到（本仓库无 Compose UI 测试基建），由源码扫描锁接线；
 * 真机双 Activity 并存与温启动分享路由仍 UNCOVERED（已标注）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportShareTextWarmStartTest {

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

    // ---------------------------------------------------------------- 设施

    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class Recorder {
        var inspectCalls = 0
        val inspectTexts = mutableListOf<String>()
        val inspectSources = mutableListOf<Source>()

        suspend fun inspect(text: String, source: Source): BackupInspection {
            inspectCalls += 1
            inspectTexts += text
            inspectSources += source
            return BackupInspection.Normal
        }
    }

    private fun newVm(recorder: Recorder, dispatcher: CoroutineDispatcher): ImportViewModel =
        ImportViewModel(
            container = container,
            inspector = recorder::inspect,
            pipelineParse = { _, _ -> parsedImportForShareTest() },
            workDispatcher = dispatcher,
        )

    // ---------------------------------------------------------------- 同一份文本只识别一次

    @Test
    fun `同一份分享文本第二次不再识别（第二道防线不变）`() = vmTest {
        val recorder = Recorder()
        val vm = newVm(recorder, StandardTestDispatcher(testScheduler))

        vm.onShareText("{\"schema\":3} 同一份备份")
        advanceUntilIdle()
        assertEquals("第一次分享必须识别", 1, recorder.inspectCalls)

        vm.onShareText("{\"schema\":3} 同一份备份")
        advanceUntilIdle()
        assertEquals("同一份文本第二次不得重复识别", 1, recorder.inspectCalls)
        assertEquals(listOf(Source.SHARE), recorder.inspectSources)
    }

    // ---------------------------------------------------------------- 温启动不同文本

    @Test
    fun `同一 VM 上第二条不同文本照常识别（温启动事件流第二层）`() = vmTest {
        val recorder = Recorder()
        val vm = newVm(recorder, StandardTestDispatcher(testScheduler))

        vm.onShareText("第一份分享文本")
        advanceUntilIdle()
        assertEquals(1, recorder.inspectCalls)

        // 温启动再分享一份不同文本：必须照常识别（旧的实例布尔值会在这里静默丢弃）。
        vm.onShareText("第二份分享文本")
        advanceUntilIdle()

        assertEquals("不同文本必须再次识别", 2, recorder.inspectCalls)
        assertEquals(
            listOf(Source.SHARE, Source.SHARE),
            recorder.inspectSources,
        )
        assertEquals(
            "识别文本顺序必须与两次分享一致",
            listOf("第一份分享文本", "第二份分享文本"),
            recorder.inspectTexts,
        )
        // 普通内容路径：状态按第二条文本落地（证明第二次识别真的执行了）。
        assertEquals("第二份分享文本", vm.state.value.raw)
    }

    @Test
    fun `清空代际变化后同一份文本仍不重复识别 不同文本仍可识别`() = vmTest {
        val recorder = Recorder()
        val vm = newVm(recorder, StandardTestDispatcher(testScheduler))

        vm.onShareText("清空前的分享文本")
        advanceUntilIdle()
        assertEquals(1, recorder.inspectCalls)

        // 清空开始执行（代际自增）：页面复位，但已消费的分享文本记号刻意保留。
        container.notifyUserDataWiped()
        advanceUntilIdle()
        assertEquals(ImportUiState(), vm.state.value)

        vm.onShareText("清空前的分享文本")
        advanceUntilIdle()
        assertEquals("清空后同一份旧分享不得重新识别（否则旧备份重新变预览）", 1, recorder.inspectCalls)

        vm.onShareText("清空后的新分享文本")
        advanceUntilIdle()
        assertEquals("清空后不同文本仍可识别", 2, recorder.inspectCalls)
    }
}

/** 本文件自用的最小解析结果（`ImportInspectEntryTest` 的同名 helper 是 file-private）。 */
private fun parsedImportForShareTest(): ParsedImport = ParsedImport(
    name = "阿淮",
    persona = "人设草稿",
    greeting = null,
    styleSamples = emptyList(),
    turns = emptyList(),
    format = Format.TEXT,
    warnings = emptyList(),
)
