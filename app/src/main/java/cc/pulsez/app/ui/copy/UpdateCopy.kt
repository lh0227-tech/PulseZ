package cc.pulsez.app.ui.copy

import cc.pulsez.app.update.UpdateConfig
import cc.pulsez.app.update.UpdateUiState

/**
 * 「检查更新」用户可见文案（v0.5.1只检查 + 非阻断提示 + 跳浏览器下载）。
 *
 * 依据：
 * - （设置页顶部一行：状态与文案草案）、§4.5（对话框文案草案）、
 *   §4.6（手动检查失败一句「这次没连上发布页，过一会儿再试。」）；
 * - 说事实 + 下一步；不出现 token / 上下文 / 窗口 / 向量 / 缓存等禁用词；
 *   不承诺不存在的功能——本轮**不承诺 App 内下载/安装**，只说「用浏览器打开下载页」；
 * - 三问齐全（这是什么 / 要不要用 / 不用会怎样），角色代词只用 TA，按钮 ≤20 字。
 *
 * v0.5.2 13——新增「已跳过」状态文案与
 * 「浏览器打不开 → 复制下载地址」的可执行出路；剪贴板标签也收口在本文件（Android 13+
 * 系统「已复制」浮层会把它显示给用户，属用户可见文案）。
 *
 * 收口成常量/纯函数的原因同 `ReadCopy`、`CreativeModeCopy`：Compose 页面里的字面量没法在
 * 纯 JVM 里反射收集；逐字断言 + 禁用词回归只能靠这个常量入口（`UpdateCopyTest`）。
 *
 * 本文件**不出现**任何设备信息、URL 内容或密钥：文案只说「发布页」「下载页」。
 */
internal object UpdateCopy {

    // ------------------------------------------------------------ 设置页顶部一行

    /** 行标题：就是功能名，用户能复述。 */
    const val ROW_TITLE = "检查更新"

    /** 检查中的值（跟在当前版本后面）。 */
    const val CHECKING_VALUE = "正在看……"

    /** 已是最新的值（跟在当前版本后面）。只用于确实没有更新的版本。 */
    const val UP_TO_DATE_VALUE = "已是最新"

    /**
     * 有新版但用户点过「稍后」的值（跟在当前版本后面；版本事实仍然可见）。
     *

     */
    const val IGNORED_VALUE = "已跳过"

    /** 已跳过时的行下说明：说清「不会自动提醒」+ 可执行的下一步（点一下再查）。 */
    fun ignoredDescription(installedVersionName: String): String =
        "现在装的是 $installedVersionName。这版你点过「稍后」，不会自动提醒；点一下再查一次。"

    /** 手动检查失败的值（跟在当前版本后面；具体一句在 [MANUAL_FAILED]）。 */
    const val FAILED_VALUE = "没连上"

    /** 有新版时的值：明确说出新版本号（线框「0.5.0 · 有新版本 0.5.1」）。*/
    fun availableValue(versionName: String): String = "有新版本 $versionName"

    /**
     * 手动检查失败的一句人话（逐字）。
     *
     * 与 `UpdateConfig.MANUAL_FAILED_MESSAGE` 是**同一个常量**：状态里的 message 与 UI 显示
     * 只有一份字面量，改文案只改 `UpdateConfig` 一处。
     */
    val MANUAL_FAILED: String = UpdateConfig.MANUAL_FAILED_MESSAGE

    /** 从没查过时的行下说明：是什么（看有没有新版）+ 要不要用（不查也能用）。 */
    fun idleDescription(installedVersionName: String): String =
        "现在装的是 $installedVersionName。点一下看看有没有新版；不查也能照常用。"

    /** 检查中的行下说明。 */
    const val CHECKING_DESCRIPTION = "正在看有没有新版……"

    /** 已是最新的行下说明。 */
    fun upToDateDescription(installedVersionName: String): String =
        "现在装的是 $installedVersionName。上次看过，是最新的。"

    /** 有新版本时的行下说明：点一下能看这一版改了什么。 */
    fun availableDescription(installedVersionName: String): String =
        "现在装的是 $installedVersionName。点一下看看这一版改了什么。"

    /**
     * 行的右侧值：**当前版本始终可见**，状态跟在后面。
     *
     * 这样无论处于哪个状态，用户都能回答「我现在装的是哪一版」；有新版时同一个值里就说清
     * 「有新版本 X」（线框）。
     */
    fun rowValue(state: UpdateUiState, installedVersionName: String): String = when (state) {
        UpdateUiState.Idle -> installedVersionName
        UpdateUiState.Checking -> "$installedVersionName · $CHECKING_VALUE"
        is UpdateUiState.UpToDate -> "$installedVersionName · $UP_TO_DATE_VALUE"
        is UpdateUiState.Available -> "$installedVersionName · ${availableValue(state.manifest.versionName)}"
        is UpdateUiState.Ignored ->
            "$installedVersionName · ${availableValue(state.manifest.versionName)} · $IGNORED_VALUE"
        is UpdateUiState.ManualFailed -> "$installedVersionName · $FAILED_VALUE"
    }

    /** 行的行下说明（整行可点范围内，点它也能触发同一动作）。 */
    fun rowDescription(state: UpdateUiState, installedVersionName: String): String = when (state) {
        UpdateUiState.Idle -> idleDescription(installedVersionName)
        UpdateUiState.Checking -> CHECKING_DESCRIPTION
        is UpdateUiState.UpToDate -> upToDateDescription(installedVersionName)
        is UpdateUiState.Available -> availableDescription(installedVersionName)
        is UpdateUiState.Ignored -> ignoredDescription(installedVersionName)
        is UpdateUiState.ManualFailed -> state.message
    }

    /**
     * 检查中不可点（避免重复请求）；其余状态都可点：Idle/UpToDate/失败 = 重新检查，
     * 有新版 = 开对话框，已跳过 = 手动再查一次（不新增第三个入口 已定不做）。
     */
    fun rowClickable(state: UpdateUiState): Boolean = state !is UpdateUiState.Checking

    /**
     * 有新版本时行尾一个 8dp 圆点（无数字、不制造待办压力）。
     *
     * [UpdateUiState.Ignored] **不显示**圆点：用户已经选了「稍后」，圆点会变成催更压力
     * 此提示文案保持简短。
     */
    fun rowHasBadge(state: UpdateUiState): Boolean = state is UpdateUiState.Available

    // ------------------------------------------------------------ 非阻断对话框

    /** 更新内容最多展示 3 条。*/
    const val MAX_CHANGELOG_LINES: Int = 3

    /** 对话框标题：有新版本 0.5.2。 */
    fun dialogTitle(versionName: String): String = "有新版本 $versionName"

    /** 对话框第一行：先说清「你现在装的是哪一版」。 */
    fun installedLine(installedVersionName: String): String =
        "你现在装的是 $installedVersionName。"

    /** 更新内容的引导句；没有 changelog 时整段省略。 */
    const val CHANGELOG_PREFIX = "这一版里："

    /**
     * 不更新会怎样：不吓人、不索取、不承诺「必须更新」。
     *
     * 依据「不更新也能接着用。只是新版本里修好的问题，这一版还没有。」；
     * 不制造亏欠。
     */
    const val AFTER_CHANGELOG = "不更新也能接着用。只是新版本里修好的问题，这一版还没有。"

    /**
     * 下载方式：说清「用浏览器打开下载页、不会自己装」（不承诺 App 内下载/安装）。
     *
     * 依据「若跳浏览器，正文补一句：会用浏览器打开下载页，装好回 PulseZ 就是新版。」
     * + 「不制造不存在的功能」（本轮没有 App 内安装，所以明说「不会自己装」）。
     */
    const val DOWNLOAD_METHOD = "点「去下载」会用浏览器打开下载页，装好回 PulseZ 就是新版。PulseZ 不会自己装。"

    /**
     * 费用与隐私口径：更新不收费；出网只有一次静态文件请求，不带聊天内容/设备信息
     * 。
     */
    const val FEE_PRIVACY = "检查更新不收费，也不会带上你的聊天内容或设备信息。"

    /** 主按钮：去下载（跳浏览器）。 */
    const val DOWNLOAD = "去下载"

    /** 次按钮：稍后（记忽略版本，同版本不再自动提示）。 */
    const val LATER = "稍后"

    /**
     * 浏览器打不开时的一段说明：必须与界面行为一致（下方显示地址 + 「复制地址」按钮）。
     * 依据 「给一句人话出路（复制地址）」。
     */
    const val BROWSER_UNAVAILABLE = "这台手机没找到能打开网页的浏览器。下载地址在下面，复制后粘到浏览器里打开。"

    /** 失败态里的动作按钮：把下载地址复制到剪贴板。 */
    const val COPY_URL = "复制地址"

    /** 复制成功后的确认（Toast）。 */
    const val URL_COPIED = "地址已复制"

    /**
     * 剪贴板标签：Android 13+ 系统「已复制」浮层会把它显示给用户，属**用户可见文案**，
     * 必须与其它文案一起收口进本文件（不许硬编码在 `SettingsScreen.kt`）。
     * 依据「用户可见更新文案只走 UpdateCopy」、§10 #20。
     */
    const val CLIP_LABEL = "PulseZ 下载地址"

    /**
     * 对话框正文行（按展示顺序）。
     *
     * `changelog` 为空 → 省略「这一版里」这一段；超过 [MAX_CHANGELOG_LINES] 条只取前 3 条。
     * 返回的行都是纯文本（不用富文本/markdown，聊天里没人这样说话，更新说明也一样）。
     */
    fun dialogLines(installedVersionName: String, changelog: List<String>): List<String> {
        val lines = mutableListOf(installedLine(installedVersionName))
        val items = changelog
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_CHANGELOG_LINES)
        if (items.isNotEmpty()) {
            lines += CHANGELOG_PREFIX
            lines += items
        }
        lines += AFTER_CHANGELOG
        lines += DOWNLOAD_METHOD
        lines += FEE_PRIVACY
        return lines
    }
}
