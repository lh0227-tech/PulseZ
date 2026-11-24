package cc.pulsez.app.ui.importer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接线源码扫描（rev9/rev10 修正后）。
 *
 * 纯 JVM 源码扫描（单测 cwd = `app/`，依据 `DeepSeekRealApiTest.kt:477`）。机器可判定地锁住
 * `AppRoot` 的四处接线**真的**改成了新路径、且旧路径没有并存：
 * 1. 导出 launcher 调 `ExportService.exportTo(`（不是旧的 `buildJson` + 无回执）；
 * 2. 分享（ 起）由 Activity 级事件流 `shareText` 下发：导入页组合里的
 * `LaunchedEffect(shareText, vm)` 只调 `vm.onShareText(share)`，**不预写 raw**；
 * 3. 文件回调区块（`GetContent` → 其后第一个 `ImportScreen(`）走 `readPickedText` +
 * `vm.inspectAndParse(`，无 `readBytes`/`onRawChange`/`vm.parse(Source.FILE)`；
 * 4. `ImportScreen(` 调用点的 `onPickFile` 保持 `{ filePicker.launch }` 且**不包** `resetToEdit`；
 * `onPickAnotherFile`/`onCancelBackup` 各 1 次且含 `resetToEdit`（K2）。
 *
 * 先断言源码非空，防路径/读取失败导致「空串上断言恒真」静默变绿。
 */
class ImportWiringSourceScanTest {

    private companion object {
        val APP_ROOT_PATH = "src/main/java/cc/pulsez/app/ui/AppRoot.kt"
        val IMPORT_SCREEN_PATH = "src/main/java/cc/pulsez/app/ui/importer/ImportScreen.kt"
    }

    private val appRootText: String by lazy { File(APP_ROOT_PATH).readText(Charsets.UTF_8) }
    private val importScreenText: String by lazy { File(IMPORT_SCREEN_PATH).readText(Charsets.UTF_8) }

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

    /** 文件入口区块：`GetContent` → 其后第一个 `ImportScreen(`（含 `when` 整段）。 */
    private fun fileCallbackBlock(): String {
        val start = appRootText.indexOf("GetContent")
        assertTrue("AppRoot 里找不到 GetContent 文件回调锚点", start >= 0)
        val end = appRootText.indexOf("ImportScreen(", start)
        assertTrue("GetContent 之后找不到 ImportScreen( 作为右边界", end > start)
        return appRootText.substring(start, end)
    }

    /**
 * `ImportScreen(` 调用区块：用**括号配平**截取整个调用（到它的闭合 `)`）。
 *
 * 不能用 `Route.DeepSeekImport` 当右边界：`onOpenZipImport = { nav.push(Route.DeepSeekImport) }`
 * 出现在新增的 `onPickAnotherFile`/`onCancelBackup` 之前，会把区块截短（实测踩到）。
 */
    private fun importScreenCallBlock(): String {
        val start = appRootText.indexOf("ImportScreen(")
        assertTrue("AppRoot 里找不到 ImportScreen( 调用点", start >= 0)
        var depth = 0
        var index = start
        while (index < appRootText.length) {
            when (appRootText[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return appRootText.substring(start, index + 1)
                }
            }
            index += 1
        }
        throw AssertionError("ImportScreen( 调用没有闭合")
    }

    // ---------------------------------------------------------------- 前置：源码真的读到了

    @Test
    fun `读取到的两份源码非空`() {
        assertTrue("AppRoot.kt 源码为空（路径/读取失败）", appRootText.isNotBlank())
        assertTrue("ImportScreen.kt 源码为空（路径/读取失败）", importScreenText.isNotBlank())
        assertTrue("AppRoot.kt 应包含 exportLauncher", appRootText.contains("exportLauncher"))
    }

    // ---------------------------------------------------------------- AppRoot：导出 / 分享 / 文件入口

    @Test
    fun `AppRoot 导出与分享入口走新路径`() {
        assertTrue("导出 launcher 必须调 ExportService.exportTo(", appRootText.contains("ExportService.exportTo("))
        assertTrue("文件/分享入口必须走 vm.inspectAndParse(", appRootText.contains("vm.inspectAndParse("))
        assertTrue(
            "分享入口必须调 vm.onShareText(share)，文本来自事件流",
            appRootText.contains("vm.onShareText(share)"),
        )
        assertFalse("不得再有旧的预写 raw 分享路径", appRootText.contains("vm.onRawChange(initialShareText)"))
        assertFalse(
            "不得再用手写字段 initialShareText，改用 Activity 级事件流",
            appRootText.contains("initialShareText ="),
        )
    }

    @Test
    fun `文件回调区块 无无界读 无预写 raw 无旧文件解析路径`() {
        val block = fileCallbackBlock()
        assertTrue("文件回调必须用有界读 readPickedText(", block.contains("readPickedText("))
        assertTrue("文件回调必须无条件交 VM 识别", block.contains("vm.inspectAndParse("))
        assertFalse("AppRoot 全文不得再有 readBytes()", appRootText.contains(".readBytes()"))
        assertFalse("文件回调区块不得预写 raw", block.contains("onRawChange"))
        assertFalse("文件回调区块不得残留旧的 vm.parse(Source.FILE)", block.contains("vm.parse(Source.FILE)"))
    }

    @Test
    fun `分享 LaunchedEffect 区块 只有 onShareText 无旧守卫与预写`() {
        val block = braceBlockOf(appRootText, "LaunchedEffect(shareText, vm)")
        assertTrue("分享区块必须调 vm.onShareText(share)", block.contains("vm.onShareText(share)"))
        assertFalse("分享区块不得预写 raw", block.contains("onRawChange"))
        assertFalse("分享区块不得残留旧守卫 state.raw.isBlank()", block.contains("state.raw.isBlank()"))
        assertFalse("分享区块不得残留 vm.parse(Source.SHARE)", block.contains("vm.parse(Source.SHARE)"))
    }

    // ---------------------------------------------------------------- ImportScreen 调用点

    @Test
    fun `onPickFile 保持只打文件选择器 不包 resetToEdit`() {
        val block = importScreenCallBlock()
        assertEquals("调用区块内 onPickFile 应恰好 1 处", 1, Regex("onPickFile\\s*=").findAll(block).count())
        val fragment = requireNotNull(Regex("onPickFile\\s*=\\s*\\{[^}]*\\}").find(block)) {
            "找不到 onPickFile 参数片段"
        }.value
        assertTrue("onPickFile 必须仍是 filePicker.launch：$fragment", fragment.contains("filePicker.launch"))
        assertFalse("onPickFile 不得包 resetToEdit（K2：编辑步草稿不能被清）：$fragment", fragment.contains("resetToEdit"))
    }

    @Test
    fun `备份两态出口 各一次且都含 resetToEdit`() {
        val block = importScreenCallBlock()
        val pickAnotherCount = Regex("onPickAnotherFile\\s*=").findAll(block).count()
        assertEquals("调用区块内 onPickAnotherFile 应恰好 1 处", 1, pickAnotherCount)
        val pickAnother = requireNotNull(Regex("onPickAnotherFile\\s*=\\s*\\{[^}]*\\}").find(block)) {
            "找不到 onPickAnotherFile 参数片段"
        }.value
        assertTrue("换一个文件必须先 resetToEdit：$pickAnother", pickAnother.contains("resetToEdit"))
        assertTrue("换一个文件要再调起文件选择器：$pickAnother", pickAnother.contains("filePicker.launch"))

        val cancelCount = Regex("onCancelBackup\\s*=").findAll(block).count()
        assertEquals("调用区块内 onCancelBackup 应恰好 1 处", 1, cancelCount)
        assertTrue(
            "取消必须映射到 vm::resetToEdit",
            Regex("onCancelBackup\\s*=\\s*vm::resetToEdit").containsMatchIn(block),
        )
        // 注意：不能断言「整个 ImportScreen( 区块内 resetToEdit 0 次」——该区块必然有 2 次
        // 当选择其他文件或取消备份时，这种断言必须失败。
    }

    // ---------------------------------------------------------------- ImportScreen.kt

    @Test
    fun `ImportScreen 走纯状态函数 且无 VM 引用`() {
        assertTrue("ImportScreen 必须用 importStep(", importScreenText.contains("importStep("))
        assertTrue("ImportScreen 必须有 onPickAnotherFile 参数", importScreenText.contains("onPickAnotherFile"))
        assertTrue("ImportScreen 必须有 onCancelBackup 参数", importScreenText.contains("onCancelBackup"))
        // （点名的 1 条反转）：中间态文案已删除，预览态改为「恢复/取消」。
        assertFalse(
            "备份预览不得再残留 PREVIEW_NOT_READY",
            importScreenText.contains("BackupCopy.PREVIEW_NOT_READY"),
        )
        assertTrue("无状态 composable 不得引用 vm.", !Regex("\\bvm\\.").containsMatchIn(importScreenText))
    }
}
