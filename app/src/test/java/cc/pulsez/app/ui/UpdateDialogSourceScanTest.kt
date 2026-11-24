package cc.pulsez.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新对话框接线源码扫描。
 *
 * 纯 JVM 源码扫描（单测 cwd = `app/`，按单测工作目录约定 用相对 `build/...`）。
 * 只扫 `SettingsScreen.kt` / `AppRoot.kt` 两个文件（不扫全仓，避免随机红）；路径不存在时
 * 直接断言失败（不许静默跳过）。先断言源码非空，防「空串上断言恒真」静默变绿。
 *
 * 这类断言对重命名/搬文件/换行格式敏感——刻意「宁可红得早」。
 */
class UpdateDialogSourceScanTest {

    private companion object {
        const val APP_ROOT_PATH = "src/main/java/cc/pulsez/app/ui/AppRoot.kt"
        const val SETTINGS_SCREEN_PATH = "src/main/java/cc/pulsez/app/ui/settings/SettingsScreen.kt"
    }

    private val appRootText: String by lazy { File(APP_ROOT_PATH).readText(Charsets.UTF_8) }
    private val settingsScreenText: String by lazy { File(SETTINGS_SCREEN_PATH).readText(Charsets.UTF_8) }

    /**
 * 函数声明 → 函数体区块（含头与体）。
 *
 * 不能用「直接配平花括号」：`UpdateAvailableDialog` 的**参数默认值**里有 `{}`，直接配平会
 * 在参数列表处就以为「函数体结束」（实测踩到）。这里先括号配平跳过参数列表，再从函数体
 * 开括号开始匹配花括号。
 */
    private fun functionOf(text: String, signatureAnchor: String): String {
        val start = text.indexOf(signatureAnchor)
        assertTrue("源码里找不到函数声明：$signatureAnchor", start >= 0)
        val paramsOpen = text.indexOf('(', start)
        assertTrue("找不到参数列表开括号：$signatureAnchor", paramsOpen >= 0)
        var parenDepth = 0
        var index = paramsOpen
        var paramsClose = -1
        while (index < text.length) {
            when (text[index]) {
                '(' -> parenDepth += 1
                ')' -> {
                    parenDepth -= 1
                    if (parenDepth == 0) {
                        paramsClose = index
                        break
                    }
                }
            }
            index += 1
        }
        assertTrue("参数列表没有闭合：$signatureAnchor", paramsClose > paramsOpen)
        val bodyOpen = text.indexOf('{', paramsClose)
        assertTrue("函数没有函数体：$signatureAnchor", bodyOpen > paramsClose)
        var depth = 0
        index = bodyOpen
        while (index < text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
            index += 1
        }
        throw AssertionError("函数体没有闭合：$signatureAnchor")
    }

    private fun occurrences(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    // ---------------------------------------------------------------- 前置：源码真的读到了

    @Test
    fun `读取到的两份源码非空`() {
        assertTrue("AppRoot.kt 源码为空（路径/读取失败）：$APP_ROOT_PATH", appRootText.isNotBlank())
        assertTrue("SettingsScreen.kt 源码为空（路径/读取失败）：$SETTINGS_SCREEN_PATH", settingsScreenText.isNotBlank())
        assertTrue("AppRoot.kt 应包含 exportLauncher", appRootText.contains("exportLauncher"))
        assertTrue("SettingsScreen.kt 应包含 UpdateAvailableDialog", settingsScreenText.contains("UpdateAvailableDialog("))
    }

    // ---------------------------------------------------------------- / ：失败态做进对话框正文

    @Test
    fun `UpdateAvailableDialog 正文含地址与复制按钮 签名含 downloadFailed`() {
        val block = functionOf(settingsScreenText, "internal fun UpdateAvailableDialog(")
        assertTrue("对话框正文必须显示清单里的 apkUrl 原文：$block", block.contains("manifest.apkUrl"))
        assertTrue("对话框正文必须有复制动作 onCopyUrl", block.contains("onCopyUrl"))
        assertTrue("对话框签名必须有 downloadFailed 参数", block.contains("downloadFailed"))
        assertTrue("失败说明来自 UpdateCopy.BROWSER_UNAVAILABLE", block.contains("UpdateCopy.BROWSER_UNAVAILABLE"))
        assertTrue("复制按钮文案来自 UpdateCopy.COPY_URL", block.contains("UpdateCopy.COPY_URL"))
        assertTrue("复制按钮触控 ≥48dp", block.contains("heightIn(min = 48.dp)"))
        // 失败态只在 downloadFailed 时出现，避免常态多显示一段地址。
        assertTrue("正文追加内容必须受 downloadFailed 控制", block.contains("if (downloadFailed)"))
    }

    @Test
    fun `两个入口都传失败态`() {
        assertTrue(
            "设置页手动对话框必须传 downloadFailed = updateDownloadFailed",
            settingsScreenText.contains("downloadFailed = updateDownloadFailed"),
        )
        assertTrue(
            "AppRoot 启动提示框必须传 downloadFailed = startUpdateDownloadFailed",
            appRootText.contains("downloadFailed = startUpdateDownloadFailed"),
        )
        // 两个入口都接上复制 helper（不再各自造 Toast-only 分支）。
        assertTrue("设置页手动框接 onCopyUrl", settingsScreenText.contains("onCopyUrl = { copyDownloadUrl("))
        assertTrue("启动提示框接 onCopyUrl", appRootText.contains("onCopyUrl = { copyDownloadUrl("))
    }

    // ---------------------------------------------------------------- ：token 换代只在 RETURN_HOME

    @Test
    fun `AppRoot 清空成功的 token 换代恰好一次且在 RETURN_HOME 分支内`() {
        assertEquals(
            "homeRebuildToken += 1 只能出现 1 次（清空成功分支）",
            1,
            occurrences(appRootText, "homeRebuildToken += 1"),
        )
        val returnHome = appRootText.indexOf("ClearOutcome.RETURN_HOME")
        assertTrue("找不到 ClearOutcome.RETURN_HOME", returnHome >= 0)
        val nextOutcome = appRootText.indexOf("ClearOutcome.", returnHome + 1)
        assertTrue("RETURN_HOME 之后应还有其它 ClearOutcome 分支", nextOutcome > returnHome)
        val tokenIndex = appRootText.indexOf("homeRebuildToken += 1")
        assertTrue("token 换代必须在 RETURN_HOME 之后：$tokenIndex vs $returnHome", tokenIndex > returnHome)
        assertTrue("token 换代必须在下一个 ClearOutcome 分支之前（不能放错分支）", tokenIndex < nextOutcome)
        // key 随 token 变化；正常回首页 token 不变 → 不重建。
        assertTrue(
            "Route.Threads 的 viewModel 必须用 key = threads-home-\$homeRebuildToken",
            appRootText.contains("key = \"threads-home-\$homeRebuildToken\""),
        )
    }

    // ---------------------------------------------------------------- ：不再 Toast-only / 无旧私有函数

    @Test
    fun `AppRoot 不含 Toast-only 失败出路与旧 openUpdateDownload`() {
        assertFalse(
            "失败出路不能再是 Toast-only",
            appRootText.contains("Toast.makeText(context, UpdateCopy.BROWSER_UNAVAILABLE"),
        )
        assertEquals(
            "AppRoot 不得再引用 BROWSER_UNAVAILABLE（失败态只在对话框正文）",
            0,
            occurrences(appRootText, "BROWSER_UNAVAILABLE"),
        )
        assertEquals("旧的 openUpdateDownload 必须删除", 0, occurrences(appRootText, "openUpdateDownload"))
        // 仍保留的 Toast 用途（导出/导入回执）不受影响。
        assertTrue("AppRoot 其它 Toast 回执应还在（不是把 Toast 全删了）", appRootText.contains("Toast.makeText("))
    }

    // ---------------------------------------------------------------- 文案来源收口（含剪贴板标签）

    @Test
    fun `剪贴板标签只用 UpdateCopy 不硬编码`() {
        val helper = functionOf(settingsScreenText, "internal fun copyDownloadUrl(")
        assertTrue("copyDownloadUrl 必须写系统剪贴板", helper.contains("setPrimaryClip("))
        assertTrue("剪贴板标签必须来自 UpdateCopy.CLIP_LABEL", helper.contains("UpdateCopy.CLIP_LABEL"))
        assertTrue("复制成功确认来自 UpdateCopy.URL_COPIED", helper.contains("UpdateCopy.URL_COPIED"))
        assertTrue("空地址返回 false", helper.contains("isBlank()"))
        assertFalse(
            "不得硬编码剪贴板标签（newPlainText(\" 后面直接跟字面量）",
            settingsScreenText.contains("newPlainText(\""),
        )
    }

    // ---------------------------------------------------------------- A：markIgnored 传清单

    @Test
    fun `两个入口的稍后都传清单 不再传版本号`() {
        assertTrue(
            "设置页回调必须 markIgnored(manifest)",
            appRootText.contains("markIgnored(manifest)"),
        )
        assertTrue(
            "启动提示框必须 markIgnored(updatePrompt)",
            appRootText.contains("markIgnored(updatePrompt)"),
        )
        assertFalse(
            "不得再传裸版本号 markIgnored(versionCode) 形态",
            appRootText.contains("markIgnored(versionCode)"),
        )
        assertFalse(
            "不得再传 updatePrompt.versionCode",
            appRootText.contains("markIgnored(updatePrompt.versionCode)"),
        )
        assertFalse(
            "不得再传 manifest.versionCode",
            appRootText.contains("markIgnored(manifest.versionCode)"),
        )
    }
}
