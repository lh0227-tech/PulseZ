package cc.pulsez.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享文本一次性消费与导入页压栈判定的纯 JVM 测试。
 *
 * 同一文本在清空换代或 Activity 重建后交给新的 `ImportViewModel` 时必须被拦下；不同文本
 * 仍可注入。温启动分享到达时，是否压入导入页与是否注入文本是两个独立判定；导航栈重建后
 * 仍需回到导入页。
 *
 * `MainActivity` 与 `AppRoot` 的事件接线由对应源码扫描测试检查；清空时取消恢复预览的行为
 * 由 `ImportWipeResetTest` 覆盖。
 */
class ShareConsumptionTest {

    // ---------------------------------------------------------------- 空文本

    @Test
    fun `null 文本永不注入`() {
        assertFalse(ShareConsumption.shouldInject(null, null))
        assertFalse(ShareConsumption.shouldInject(null, "上次的备份"))
    }

    @Test
    fun `空白文本永不注入（含全空白与换行）`() {
        assertFalse(ShareConsumption.shouldInject("", null))
        assertFalse(ShareConsumption.shouldInject("   ", null))
        assertFalse(ShareConsumption.shouldInject("\n\t ", null))
        // 即使与「上次已消费」不同也不注入。
        assertFalse(ShareConsumption.shouldInject("  ", "别的文本"))
    }

    // ---------------------------------------------------------------- 首次 / 不同文本

    @Test
    fun `还没消费过时 非空文本要注入`() {
        assertTrue(ShareConsumption.shouldInject("{\"schema\":3} 备份", null))
    }

    @Test
    fun `与上次不同的文本要注入（温启动再次分享不同内容经事件流仍有效）`() {
        assertTrue(ShareConsumption.shouldInject("第二份备份", "第一份备份"))
        assertTrue(ShareConsumption.shouldInject("普通文字", "备份 JSON"))
    }

    @Test
    fun `判定不 trim 也不改写文本：首尾空白不同即视为不同文本`() {
        // 注入给 VM 的是原文（不改写识别输入）；是否「同一份」按精确相等判定。
        assertTrue(ShareConsumption.shouldInject("备份", "备份 "))
    }

    // ---------------------------------------------------------------- 去重主路径

    @Test
    fun `同一份文本第二次不注入（去重主路径）`() {
        assertFalse(ShareConsumption.shouldInject("备份文本", "备份文本"))
    }

    @Test
    fun `清空换代后同一份旧分享仍不注入 换一份新文本才注入`() {
        val backupText = "{\"app\":\"pulsez\",\"schema\":3,\"characters\":[]}"

        // 第一次分享：注入并记录（AppRoot 的 lastConsumedShareText = text）。
        assertTrue("首次分享应注入", ShareConsumption.shouldInject(backupText, null))
        val lastConsumed: String? = backupText

        // 清空换代（全局 wipeEpoch 自增）/ Activity 因字体/语言/密度配置变化重建：同一份文本再次被交下来。
        assertFalse(
            "清空后同一份旧分享不得再次交给新 VM（否则旧备份预览可一键恢复）",
            ShareConsumption.shouldInject(backupText, lastConsumed),
        )

        // 用户之后主动分享**另一份**文本：仍要生效。
        val another = "另一份备份文本"
        assertTrue("不同文本必须仍能注入", ShareConsumption.shouldInject(another, lastConsumed))
        assertFalse("新文本消费一次后同样去重", ShareConsumption.shouldInject(another, another))
    }

    // ---------------------------------------------------------------- 压栈判定

    @Test
    fun `非空分享且不在导入页时压入导入页（冷启动与温启动都合成返回栈）`() {
        assertTrue(
            "冷启动：导航栈只有首页时必须压入导入页（返回手势回首页）",
            ShareConsumption.shouldOpenImportPage("一份备份", alreadyOnImport = false),
        )
        assertTrue(
            "温启动：不在导入页时新分享也要压入导入页",
            ShareConsumption.shouldOpenImportPage("另一份备份", alreadyOnImport = false),
        )
    }

    @Test
    fun `已在导入页时不重复压栈（一次分享只叠一层）`() {
        assertFalse(
            "已在导入页时只注入、不再压栈，否则返回要按两次才回首页",
            ShareConsumption.shouldOpenImportPage("一份备份", alreadyOnImport = true),
        )
    }

    @Test
    fun `空分享永不压栈`() {
        assertFalse(ShareConsumption.shouldOpenImportPage(null, alreadyOnImport = false))
        assertFalse(ShareConsumption.shouldOpenImportPage("", alreadyOnImport = false))
        assertFalse(ShareConsumption.shouldOpenImportPage("   \n", alreadyOnImport = false))
    }

    @Test
    fun `压栈判定与文本去重独立：同一份文本重建后仍压栈 但不重复注入`() {
        // Activity 重建后导航栈是新建的（只有首页）：同一份文本到达时仍需压回导入页，
        // 否则重建后用户会掉回首页；而注入由 shouldInject 单独拦下（已消费过）。
        val text = "同一份备份"
        assertTrue("重建后仍需压栈", ShareConsumption.shouldOpenImportPage(text, alreadyOnImport = false))
        assertFalse("但不得重复注入", ShareConsumption.shouldInject(text, text))
    }

    /**
 * 温启动事件流的完整判定序列：模拟 `AppRoot` 对两条判定的实际调用顺序。
 *
 * 冷启动分享 A → 压栈 + 注入一次；同一 Activity 温启动再分享 B → 已在导入页**不重复压栈**
 * 但**仍注入**；再次分享 B 时不重复注入。
 */
    @Test
    fun `温启动分享序列：不同文本依次注入 同一文本不重复`() {
        var lastConsumed: String? = null
        var onImport = false

        // 冷启动分享 A：导航栈只有首页 → 压栈；未消费过 → 注入一次。
        assertTrue("冷启动分享应压入导入页", ShareConsumption.shouldOpenImportPage("分享A", onImport))
        onImport = true
        assertTrue("冷启动分享应注入", ShareConsumption.shouldInject("分享A", lastConsumed))
        lastConsumed = "分享A"

        // 温启动再分享 B（同一 Activity，已在导入页）：只注入、不重复压栈。
        assertFalse("已在导入页时不得重复压栈", ShareConsumption.shouldOpenImportPage("分享B", onImport))
        assertTrue("温启动的不同文本必须仍能注入", ShareConsumption.shouldInject("分享B", lastConsumed))
        lastConsumed = "分享B"

        // 再次分享 B：同一份文本不重复注入。
        assertFalse("同一份文本第二次分享不得重复注入", ShareConsumption.shouldInject("分享B", lastConsumed))
    }
}
