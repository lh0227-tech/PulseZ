package cc.pulsez.app.ui.copy

import cc.pulsez.app.BuildConfig
import cc.pulsez.app.update.UpdateConfig
import cc.pulsez.app.update.UpdateUiState
import cc.pulsez.app.update.VersionManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「检查更新」文案回归（-A；纯 JVM，不碰 Android/Compose）。
 *
 *
 * -（设置页顶部行状态与文案草案）、（对话框逐字草案）、
 *（手动检查失败一句）、（更新检查的隐私口径）；
 * -（禁用词、全角标点、按钮 ≤20 字）；（三问：是什么 /
 * 要不要用 / 不用会怎样；角色代词只用 TA；不制造不存在的功能）；
 * - ：不出现「必须/强制」；**不承诺 App 内下载安装**，只说打开浏览器下载。
 */
class UpdateCopyTest {

    private companion object {
        /** 禁用词 + 本轮额外口径（不承诺不存在的能力）。 */
        private val BANNED: List<String> = listOf(
            "token", "上下文", "窗口", "向量", "embedding", "Embedding", "缓存",
            "API Key", "记忆检索", "关键词",
            "必须", "强制", "务必",
            "免费", "永久", "绝对",
            "我们", "模型", "智能", "全新升级",
            "自动安装", "静默安装",
        )

        /** 角色代词只允许 TA。 */
        private val PRONOUN_BANNED: List<String> = listOf("她", "他")

        /** 便于批量断言的全部用户可见文案（名称 → 文本）。 */
        private fun allCopy(): List<Pair<String, String>> = listOf(
            "行标题" to UpdateCopy.ROW_TITLE,
            "检查中的值" to UpdateCopy.CHECKING_VALUE,
            "已是最新的值" to UpdateCopy.UP_TO_DATE_VALUE,
            "已跳过的值" to UpdateCopy.IGNORED_VALUE,
            "没连上的值" to UpdateCopy.FAILED_VALUE,
            "有新版本的值" to UpdateCopy.availableValue("0.5.2"),
            "手动失败说明" to UpdateCopy.MANUAL_FAILED,
            "没查过的说明" to UpdateCopy.idleDescription("0.5.0"),
            "检查中的说明" to UpdateCopy.CHECKING_DESCRIPTION,
            "已是最新的说明" to UpdateCopy.upToDateDescription("0.5.0"),
            "已跳过的说明" to UpdateCopy.ignoredDescription("0.5.0"),
            "有新版本的说明" to UpdateCopy.availableDescription("0.5.0"),
            "对话框标题" to UpdateCopy.dialogTitle("0.5.2"),
            "对话框第一行" to UpdateCopy.installedLine("0.5.0"),
            "更新内容引导" to UpdateCopy.CHANGELOG_PREFIX,
            "不更新的后果" to UpdateCopy.AFTER_CHANGELOG,
            "下载方式" to UpdateCopy.DOWNLOAD_METHOD,
            "费用与隐私" to UpdateCopy.FEE_PRIVACY,
            "按钮·去下载" to UpdateCopy.DOWNLOAD,
            "按钮·稍后" to UpdateCopy.LATER,
            "按钮·复制地址" to UpdateCopy.COPY_URL,
            "复制成功" to UpdateCopy.URL_COPIED,
            "剪贴板标签" to UpdateCopy.CLIP_LABEL,
            "浏览器打不开" to UpdateCopy.BROWSER_UNAVAILABLE,
        )
    }

    private fun manifest(versionCode: Long = 8L, versionName: String = "0.5.2", changelog: List<String> = emptyList()) =
        VersionManifest(
            schema = 1,
            channel = "stable",
            versionCode = versionCode,
            versionName = versionName,
            minSdk = 31,
            apkUrl = "https://releases.pulsez.cc/stable/pulsez-$versionName.apk",
            apkMirrors = emptyList(),
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            sizeBytes = 3_481_024L,
            apkCertSha256 = null,
            publishedAt = null,
            mandatory = false,
            minSupportedVersionCode = 0L,
            changelog = changelog,
            notes = null,
        )

    // ------------------------------------------------------------ 行：状态 → 文案

    @Test
    fun `行标题是功能名本身`() {
        assertEquals("检查更新", UpdateCopy.ROW_TITLE)
    }

    @Test
    fun `当前版本来自调用方传入 不写死`() {
        // 设置页传的是 BuildConfig.VERSION_NAME；这里用任意值验证「文案跟着传入版本走」。
        assertEquals("9.9.9", UpdateCopy.rowValue(UpdateUiState.Idle, "9.9.9"))
        assertTrue(UpdateCopy.idleDescription("9.9.9").contains("9.9.9"))
        assertTrue(UpdateCopy.upToDateDescription("9.9.9").contains("9.9.9"))
        assertTrue(UpdateCopy.availableDescription("9.9.9").contains("9.9.9"))

        // 传真实构建版本也要原样出现（防止将来有人把版本号写成常量）。
        assertTrue(UpdateCopy.rowValue(UpdateUiState.Idle, BuildConfig.VERSION_NAME).contains(BuildConfig.VERSION_NAME))
    }

    @Test
    fun `六种状态的值都带当前版本 有新版时才说有新版本`() {
        val installed = "0.5.0"
        assertEquals(installed, UpdateCopy.rowValue(UpdateUiState.Idle, installed))
        assertEquals("0.5.0 · 正在看……", UpdateCopy.rowValue(UpdateUiState.Checking, installed))
        assertEquals("0.5.0 · 已是最新", UpdateCopy.rowValue(UpdateUiState.UpToDate(1L), installed))
        assertEquals(
            "0.5.0 · 有新版本 0.5.2",
            UpdateCopy.rowValue(UpdateUiState.Available(manifest()), installed),
        )
        // 07：已跳过必须如实显示「有新版本 X · 已跳过」，不得显示「已是最新」。
        assertEquals(
            "0.5.0 · 有新版本 0.5.2 · 已跳过",
            UpdateCopy.rowValue(UpdateUiState.Ignored(manifest(), 1L), installed),
        )
        assertEquals("0.5.0 · 没连上", UpdateCopy.rowValue(UpdateUiState.ManualFailed(UpdateCopy.MANUAL_FAILED), installed))

        // 所有状态都能回答「我现在装的是哪一版」。
        listOf(
            UpdateUiState.Idle,
            UpdateUiState.Checking,
            UpdateUiState.UpToDate(1L),
            UpdateUiState.Available(manifest()),
            UpdateUiState.Ignored(manifest(), 1L),
            UpdateUiState.ManualFailed(UpdateCopy.MANUAL_FAILED),
        ).forEach { state ->
            assertTrue(
                "状态 $state 的值里必须看得到当前版本",
                UpdateCopy.rowValue(state, installed).contains(installed),
            )
        }
    }

    @Test
    fun `已跳过不复用已是最新的值或说明`() {
        val installed = "0.5.0"
        val ignored = UpdateUiState.Ignored(manifest(), 1L)
        // 逐字对照：值里必须有「有新版本 0.5.2」与「已跳过」，且**不含**「已是最新」。
        assertEquals("已跳过", UpdateCopy.IGNORED_VALUE)
        assertEquals("0.5.0 · 有新版本 0.5.2 · 已跳过", UpdateCopy.rowValue(ignored, installed))
        assertFalse(
            "已跳过的行值不能出现「已是最新」（对用户谎报）",
            UpdateCopy.rowValue(ignored, installed).contains(UpdateCopy.UP_TO_DATE_VALUE),
        )
        assertTrue(UpdateCopy.rowDescription(ignored, installed).contains("点过「稍后」"))
        assertTrue(UpdateCopy.rowDescription(ignored, installed).contains("点一下再查一次"))
        assertFalse(
            "已跳过的说明不能复用「是最新的」",
            UpdateCopy.rowDescription(ignored, installed).contains("是最新的"),
        )
        assertEquals(
            "现在装的是 0.5.0。这版你点过「稍后」，不会自动提醒；点一下再查一次。",
            UpdateCopy.rowDescription(ignored, installed),
        )
    }

    @Test
    fun `已跳过可点 不显示圆点`() {
        val ignored = UpdateUiState.Ignored(manifest(), 1L)
        assertTrue("已跳过的行可点 = 手动再查一次", UpdateCopy.rowClickable(ignored))
        assertFalse("已跳过不显示 8dp 圆点（不催更，§13-2）", UpdateCopy.rowHasBadge(ignored))
    }

    @Test
    fun `手动再查后的行值仍是有新版本`() {
        // 已跳过 → 手动再查 → 状态变 Available，行值回到「有新版本 X」（不带「已跳过」）。
        assertEquals(
            "0.5.0 · 有新版本 0.5.2",
            UpdateCopy.rowValue(UpdateUiState.Available(manifest()), "0.5.0"),
        )
    }

    @Test
    fun `行说明按状态切换 失败时显示那一句人话`() {
        val installed = "0.5.0"
        assertTrue(UpdateCopy.rowDescription(UpdateUiState.Idle, installed).contains("点一下看看有没有新版"))
        assertTrue(UpdateCopy.rowDescription(UpdateUiState.Idle, installed).contains("不查也能照常用"))
        assertEquals(UpdateCopy.CHECKING_DESCRIPTION, UpdateCopy.rowDescription(UpdateUiState.Checking, installed))
        assertTrue(UpdateCopy.rowDescription(UpdateUiState.UpToDate(1L), installed).contains("是最新的"))
        assertTrue(UpdateCopy.rowDescription(UpdateUiState.Available(manifest()), installed).contains("这一版改了什么"))
        assertEquals(
            UpdateCopy.MANUAL_FAILED,
            UpdateCopy.rowDescription(UpdateUiState.ManualFailed(UpdateCopy.MANUAL_FAILED), installed),
        )
        assertEquals("这次没连上发布页，过一会儿再试。", UpdateCopy.MANUAL_FAILED)
        assertEquals(UpdateConfig.MANUAL_FAILED_MESSAGE, UpdateCopy.MANUAL_FAILED)
    }

    @Test
    fun `检查中不可点 其余状态可点 只有有新版才显示圆点`() {
        assertFalse(UpdateCopy.rowClickable(UpdateUiState.Checking))
        assertTrue(UpdateCopy.rowClickable(UpdateUiState.Idle))
        assertTrue(UpdateCopy.rowClickable(UpdateUiState.UpToDate(1L)))
        assertTrue(UpdateCopy.rowClickable(UpdateUiState.Available(manifest())))
        assertTrue("已跳过可点 = 手动再查一次", UpdateCopy.rowClickable(UpdateUiState.Ignored(manifest(), 1L)))
        assertTrue(UpdateCopy.rowClickable(UpdateUiState.ManualFailed(UpdateCopy.MANUAL_FAILED)))

        assertTrue(UpdateCopy.rowHasBadge(UpdateUiState.Available(manifest())))
        assertFalse(UpdateCopy.rowHasBadge(UpdateUiState.Idle))
        assertFalse(UpdateCopy.rowHasBadge(UpdateUiState.UpToDate(1L)))
        assertFalse(UpdateCopy.rowHasBadge(UpdateUiState.Checking))
        assertFalse("已跳过不显示圆点（不催更，§13-2）", UpdateCopy.rowHasBadge(UpdateUiState.Ignored(manifest(), 1L)))
        assertFalse(UpdateCopy.rowHasBadge(UpdateUiState.ManualFailed(UpdateCopy.MANUAL_FAILED)))
    }

    // ------------------------------------------------------------ 对话框

    @Test
    fun `对话框标题与新版本号逐字`() {
        assertEquals("有新版本 0.5.2", UpdateCopy.dialogTitle("0.5.2"))
        assertEquals("你现在装的是 0.5.0。", UpdateCopy.installedLine("0.5.0"))
        assertEquals("去下载", UpdateCopy.DOWNLOAD)
        assertEquals("稍后", UpdateCopy.LATER)
    }

    @Test
    fun `更新内容最多 3 条 没有就省略整段`() {
        val withChange = UpdateCopy.dialogLines(
            installedVersionName = "0.5.0",
            changelog = listOf("修了图片发不出去的问题", "设置里多了检查更新", "语音输入更快了", "第四条不该出现"),
        )
        assertTrue(withChange.contains(UpdateCopy.CHANGELOG_PREFIX))
        assertTrue(withChange.contains("修了图片发不出去的问题"))
        assertTrue(withChange.contains("设置里多了检查更新"))
        assertTrue(withChange.contains("语音输入更快了"))
        assertFalse("最多 3 条", withChange.contains("第四条不该出现"))

        val withoutChange = UpdateCopy.dialogLines("0.5.0", emptyList())
        assertFalse("没有 changelog 就省略「这一版里」整段", withoutChange.contains(UpdateCopy.CHANGELOG_PREFIX))
        assertTrue(withoutChange.contains(UpdateCopy.installedLine("0.5.0")))
        assertTrue(withoutChange.contains(UpdateCopy.AFTER_CHANGELOG))
    }

    @Test
    fun `对话框正文行顺序固定 且每行非空`() {
        val lines = UpdateCopy.dialogLines("0.5.0", listOf("修复一"))
        assertEquals(UpdateCopy.installedLine("0.5.0"), lines.first())
        assertEquals(UpdateCopy.FEE_PRIVACY, lines.last())
        assertEquals(
            listOf(
                UpdateCopy.installedLine("0.5.0"),
                UpdateCopy.CHANGELOG_PREFIX,
                "修复一",
                UpdateCopy.AFTER_CHANGELOG,
                UpdateCopy.DOWNLOAD_METHOD,
                UpdateCopy.FEE_PRIVACY,
            ),
            lines,
        )
        lines.forEach { line -> assertTrue("对话框每一行都不能为空", line.isNotBlank()) }
    }

    // ------------------------------------------------------------ 三问 / 口径

    @Test
    fun `三问齐全 是什么 要不要用 不用会怎样`() {
        // 是什么：标题就是功能名；正文说清「现在装的是哪一版、这一版改了什么」。
        assertTrue(UpdateCopy.ROW_TITLE.contains("检查更新"))
        assertTrue(UpdateCopy.dialogTitle("0.5.2").contains("新版本"))
        assertTrue(UpdateCopy.idleDescription("0.5.0").contains("现在装的是 0.5.0。"))
        assertTrue(UpdateCopy.CHANGELOG_PREFIX.contains("这一版里"))

        // 要不要用：可选、不查也能用。
        assertTrue(UpdateCopy.idleDescription("0.5.0").contains("不查也能照常用"))

        // 不用会怎样：能继续用，只是拿不到这版修好的问题。
        assertTrue(UpdateCopy.AFTER_CHANGELOG.contains("不更新也能接着用"))
        assertTrue(UpdateCopy.AFTER_CHANGELOG.contains("修好的问题"))
    }

    @Test
    fun `只承诺打开浏览器下载 不承诺 App 内安装`() {
        assertTrue("下载方式必须说清是浏览器", UpdateCopy.DOWNLOAD_METHOD.contains("浏览器"))
        assertTrue("必须明说不会自己装（本轮 D1-A 不做 App 内安装）", UpdateCopy.DOWNLOAD_METHOD.contains("不会自己装"))
        assertFalse("不能出现「自动安装」式承诺", UpdateCopy.DOWNLOAD_METHOD.contains("自动安装"))
    }

    @Test
    fun `费用与隐私口径如实 不收集设备信息`() {
        assertTrue("更新不收费", UpdateCopy.FEE_PRIVACY.contains("不收费"))
        assertTrue("不带聊天内容", UpdateCopy.FEE_PRIVACY.contains("聊天内容"))
        assertTrue("不带设备信息", UpdateCopy.FEE_PRIVACY.contains("设备信息"))
    }

    @Test
    fun `浏览器打不开给可执行的下一步`() {
        // 13：文案必须与界面行为一致——地址就在对话框里显示（下面），旁边能复制；
        // 不再让用户「去复制」却找不到入口（旧的「试试」式无入口指令）。
        assertEquals(
            "这台手机没找到能打开网页的浏览器。下载地址在下面，复制后粘到浏览器里打开。",
            UpdateCopy.BROWSER_UNAVAILABLE,
        )
        assertTrue(UpdateCopy.BROWSER_UNAVAILABLE.contains("浏览器"))
        assertTrue(UpdateCopy.BROWSER_UNAVAILABLE.contains("下载地址"))
        assertTrue(UpdateCopy.BROWSER_UNAVAILABLE.contains("下面"))
        assertTrue(UpdateCopy.BROWSER_UNAVAILABLE.contains("复制"))
        assertFalse("不得再出现要求用户自己找地址的「试试」式说法", UpdateCopy.BROWSER_UNAVAILABLE.contains("试试"))
        // 失败态动作按钮与复制确认逐字。
        assertEquals("复制地址", UpdateCopy.COPY_URL)
        assertEquals("地址已复制", UpdateCopy.URL_COPIED)
        assertEquals("PulseZ 下载地址", UpdateCopy.CLIP_LABEL)
    }

    // ------------------------------------------------------------ 文案纪律

    @Test
    fun `没有禁用词 不出现强制更新和绝对承诺`() {
        allCopy().forEach { (name, text) ->
            BANNED.forEach { word ->
                assertFalse("「$name」出现禁用词「$word」：$text", text.contains(word))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("「$name」出现角色代词「$pronoun」（只许 TA）：$text", text.contains(pronoun))
            }
        }
    }

    @Test
    fun `按钮不超过 20 字 中文标点 不用 ASCII 省略号`() {
        listOf(UpdateCopy.DOWNLOAD, UpdateCopy.LATER, UpdateCopy.COPY_URL).forEach { button ->
            assertTrue("按钮文案 ≤20 字：$button", button.length <= 20)
        }
        allCopy().forEach { (name, text) ->
            assertFalse("「$name」用了 ASCII 省略号 ...", text.contains("..."))
            //：省略号用 `……`；不允许单点 `…`。
            if (text.contains("…")) {
                assertTrue("「$name」省略号必须是 ……", text.contains("……"))
            }
        }
        assertTrue("检查中要带省略号", UpdateCopy.CHECKING_VALUE.contains("……"))
    }
}
