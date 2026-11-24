package cc.pulsez.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 温启动分享事件流接线源码扫描。
 *
 * ## 这个测试是什么、不是什么
 * 这是**接线扫描**（与 `AppRootWipeWiringSourceScanTest` / `ImportWiringSourceScanTest` 同类做法）：
 * 本仓库没有 Compose UI 测试基建，`MainActivity.onNewIntent` → `StateFlow` →
 * `AppRoot` 的 `LaunchedEffect` 压栈/注入这条链跑不到纯 JVM 里。它机器可判定地锁住：
 *
 * 1. `MainActivity` 用 Activity 级 `MutableStateFlow<String?>` 持有分享文本，`onCreate` 用
 * `extractSharedText(intent)` 初始化、`onNewIntent` 先 `setIntent(intent)` 再更新事件流
 * （`setIntent` 是平台文档要求的配套动作，否则 Activity 重建会从旧 Intent 读取上一次分享）；
 * 2. `AppRoot` 观察该事件流，收到非空文本且**当前不在导入页**时 `nav.push(Route.Import)`
 * （push 不是 reset —— 保留「首页为根、返回手势回首页」的返回栈语义）；冷启动分享在导航初始化
 * 里就压栈（首帧即导入页，不闪一帧首页），温启动分享由效应压栈；
 * 3. 文本注入在导入页组合内按 `ShareConsumption.shouldInject` 去重（同一份文本只注入一次）；
 * 4. 旧的 `initialShareText` 一次性参数接线已删除。
 *
 * **不等于行为验证**：`ShareConsumptionTest` 锁纯判定表（含温启动不同文本 / 同一文本去重 /
 * 压栈判定），两者合起来才是本轮证据链；真机双 Activity 并存的系统路由仍未验（UNCOVERED，
 * 已标注）。
 *
 * 先断言源码非空，防「路径/读取失败 → 空串上断言恒真」静默变绿。cwd = `app/`。
 */
class ShareEventWiringSourceScanTest {

    private companion object {
        const val MAIN_ACTIVITY_PATH = "src/main/java/cc/pulsez/app/MainActivity.kt"
        const val APP_ROOT_PATH = "src/main/java/cc/pulsez/app/ui/AppRoot.kt"
        const val SHARE_CONSUMPTION_PATH = "src/main/java/cc/pulsez/app/ui/ShareConsumption.kt"
        const val IMPORT_VM_PATH = "src/main/java/cc/pulsez/app/ui/importer/ImportViewModel.kt"
    }

    private val mainActivityText: String by lazy { File(MAIN_ACTIVITY_PATH).readText(Charsets.UTF_8) }
    private val appRootText: String by lazy { File(APP_ROOT_PATH).readText(Charsets.UTF_8) }
    private val shareConsumptionText: String by lazy { File(SHARE_CONSUMPTION_PATH).readText(Charsets.UTF_8) }
    private val importVmText: String by lazy { File(IMPORT_VM_PATH).readText(Charsets.UTF_8) }

    private fun occurrences(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

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

    /** 函数体区块（签名锚点 → 参数列表配平 → 函数体花括号配平）。 */
    private fun functionBodyOf(text: String, signatureAnchor: String): String {
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

    // ---------------------------------------------------------------- 前置

    @Test
    fun `四份源码都读到了`() {
        assertTrue("MainActivity.kt 读取失败/为空", mainActivityText.isNotBlank())
        assertTrue("AppRoot.kt 读取失败/为空", appRootText.isNotBlank())
        assertTrue("ShareConsumption.kt 读取失败/为空", shareConsumptionText.isNotBlank())
        assertTrue("ImportViewModel.kt 读取失败/为空", importVmText.isNotBlank())
    }

    // ---------------------------------------------------------------- MainActivity：事件流

    @Test
    fun `MainActivity 用 Activity 级 StateFlow 持有分享文本`() {
        assertTrue(
            "MainActivity 必须有 Activity 级事件流：private val shareEvents = MutableStateFlow<String?>(null)",
            mainActivityText.contains("private val shareEvents = MutableStateFlow<String?>(null)"),
        )
        assertTrue(
            "MainActivity 必须导入 MutableStateFlow",
            mainActivityText.contains("import kotlinx.coroutines.flow.MutableStateFlow"),
        )
        // onCreate：用本次 Intent 初始化事件流（冷启动分享）。
        val onCreate = functionBodyOf(mainActivityText, "override fun onCreate(")
        assertTrue(
            "onCreate 必须用 extractSharedText(intent) 初始化事件流：$onCreate",
            onCreate.contains("shareEvents.value = extractSharedText(intent)"),
        )
        assertTrue(
            "onCreate 必须把同一个事件流传给 AppRoot",
            onCreate.contains("AppRoot(container = container, shareEvents = shareEvents)"),
        )
    }

    @Test
    fun `onNewIntent 先 setIntent 再更新事件流`() {
        val onNewIntent = functionBodyOf(mainActivityText, "override fun onNewIntent(")
        assertTrue(
            "onNewIntent 必须更新事件流（温启动分享的可观察变化）：$onNewIntent",
            onNewIntent.contains("shareEvents.value = extractSharedText(intent)"),
        )
        assertTrue(
            "onNewIntent 必须调用 setIntent：否则 Activity 重建会从旧 Intent 读到上一次分享：$onNewIntent",
            onNewIntent.contains("setIntent(intent)"),
        )
        // 顺序：setIntent 必须在写事件流之前（重建时 onCreate 读到的是**最近一次**分享）。
        val setIntentIndex = onNewIntent.indexOf("setIntent(intent)")
        val writeIndex = onNewIntent.indexOf("shareEvents.value = extractSharedText(intent)")
        assertTrue("setIntent 必须在事件流写入之前：$onNewIntent", setIntentIndex in 0 until writeIndex)
        // 不得再有旧的一次性字段/参数形态。
        assertFalse("MainActivity 不得再持有普通字段 sharedText", mainActivityText.contains("private var sharedText"))
        assertFalse("MainActivity 不得再传 initialShareText 参数", mainActivityText.contains("initialShareText"))
    }

    // ---------------------------------------------------------------- AppRoot：观察 + 压栈

    @Test
    fun `AppRoot 观察事件流且在新分享到达时压入导入页`() {
        assertTrue(
            "AppRoot 参数必须是 Activity 级事件流 shareEvents: StateFlow<String?>",
            appRootText.contains("shareEvents: StateFlow<String?>"),
        )
        assertTrue(
            "AppRoot 必须观察分享事件流",
            appRootText.contains("val shareText by shareEvents.collectAsState()"),
        )
        // 冷启动：导航初始化里把导入页压在首页之上（首帧就是导入页，不闪一帧首页）。
        val navBlock = braceBlockOf(appRootText, "val nav = remember {")
        assertTrue("导航栈根必须是首页：$navBlock", navBlock.contains("NavState(Route.Threads)"))
        assertTrue(
            "冷启动分享必须在导航初始化里压入导入页（合成返回栈）：$navBlock",
            navBlock.contains("ShareConsumption.shouldOpenImportPage(shareText, alreadyOnImport = false)") &&
                navBlock.contains("push(Route.Import)"),
        )
        // 温启动：分享到达 → 压栈判定 → push（不是 reset：保留返回栈语义）。
        val effect = braceBlockOf(appRootText, "LaunchedEffect(shareText)")
        assertTrue(
            "分享压栈必须经过纯函数 shouldOpenImportPage（当前已在导入页则不重复压栈）：$effect",
            effect.contains("ShareConsumption.shouldOpenImportPage(shareText, nav.current == Route.Import)"),
        )
        assertTrue("分享到达必须压入导入页：$effect", effect.contains("nav.push(Route.Import)"))
        assertFalse("分享不得用 reset 替换整栈（返回手势必须回首页）：$effect", effect.contains("nav.reset("))
        // 只在导入页组合内注入；key 带 vm，清空换代后对新 VM 重新判定。
        val inject = braceBlockOf(appRootText, "LaunchedEffect(shareText, vm)")
        assertTrue(
            "导入页组合内的注入必须先经 ShareConsumption.shouldInject：$inject",
            inject.contains("ShareConsumption.shouldInject(share, lastConsumedShareText)"),
        )
        val checkIndex = inject.indexOf("ShareConsumption.shouldInject(share, lastConsumedShareText)")
        val injectIndex = inject.indexOf("vm.onShareText(share)")
        assertTrue("去重判定必须先于注入：$inject", checkIndex in 0 until injectIndex)
        assertFalse("AppRoot 不得再把分享文本当成一次性参数", appRootText.contains("initialShareText: String?"))
    }

    // ---------------------------------------------------------------- 纯函数存在性与判定表位置

    /**
 * 的第二层：`ImportViewModel.onShareText` 原来用实例布尔值
 * `shareConsumed` 一次性拦截，同一 VM 上第二条**不同**文本会被静默丢掉。改成按**文本内容**
 * 比较后，同一份文本仍只识别一次、不同文本照常识别。行为测试见
 * `ui/importer/ImportShareTextWarmStartTest`。
 */
    @Test
    fun `导入 VM 的分享去重按文本比较（温启动第二条不同文本）`() {
        assertTrue(
            "onShareText 必须按文本内容去重（同一份文本只识别一次）",
            importVmText.contains("if (text == lastSharedText) return"),
        )
        assertTrue(
            "识别前必须记下已消费的分享文本",
            importVmText.contains("lastSharedText = text"),
        )
        assertFalse(
            "不得再用一次性布尔标记（会把同一 VM 上第二条不同文本静默丢掉）",
            importVmText.contains("private var shareConsumed") ||
                importVmText.contains("if (shareConsumed) return"),
        )
    }

    @Test
    fun `压栈判定是纯函数（可 JVM 单测）`() {
        assertTrue(
            "ShareConsumption.kt 必须定义 shouldOpenImportPage(text, alreadyOnImport)",
            shareConsumptionText.contains("fun shouldOpenImportPage(text: String?, alreadyOnImport: Boolean)"),
        )
        assertTrue(
            "ShareConsumption.kt 必须定义 shouldInject(text, lastConsumed)",
            shareConsumptionText.contains("fun shouldInject(text: String?, lastConsumed: String?)"),
        )
        // 温启动事件流口径必须写进注释（避免又出现「同一 Activity 内不同文本照常生效」与实现不符）。
        assertTrue(
            "ShareConsumption 注释必须说明温启动（onNewIntent 事件流）也生效",
            shareConsumptionText.contains("onNewIntent"),
        )
        assertEquals(
            "AppRoot 只应在导入页组合里调用 onShareText（1 处）",
            1,
            occurrences(appRootText, "vm.onShareText("),
        )
    }
}
