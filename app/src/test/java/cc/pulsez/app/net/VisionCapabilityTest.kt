package cc.pulsez.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识图能力门禁测试。
 *
 * 三条硬规则：
 * 1. 端点必须是 DeepSeek 官方 → 第三方端点永远不可用（**绝不回退 host**）；
 * 2. `/models` 的 `input_modalities` 优先：pro 只有 `["text"]` → 不可用；
 * 3. 探测失败/无缓存/换模型 → 回退模型 id 规则（`deepseek-flash` 系列），不拿旧结论冒充。
 *
 * 纯 JVM，不联网。
 */
class VisionCapabilityTest {

    private val flashWithImage = mapOf("deepseek-flash" to setOf("text", "image"))
    private val proTextOnly = mapOf("deepseek-v4-pro" to setOf("text"))

    @Test
    fun `input_modalities 决定结论 text-only 不可用`() {
        assertEquals(true, VisionCapability.supportsVision("deepseek-flash", flashWithImage))
        assertEquals(false, VisionCapability.supportsVision("deepseek-v4-pro", proTextOnly))
    }

    @Test
    fun `列表里查不到该模型时返回 null 表示需要回退模型 id 规则`() {
        assertNull(VisionCapability.supportsVision("deepseek-flash", proTextOnly))
        assertNull(VisionCapability.supportsVision("", flashWithImage))
    }

    @Test
    fun `模型别名前缀匹配`() {
        val list = mapOf("deepseek-flash" to setOf("text", "image"))
        assertEquals(
            true,
            VisionCapability.supportsVision("deepseek-flash-2026xx", list),
        )
    }

    @Test
    fun `模型 id 回退规则只放行 flash 系列`() {
        assertTrue(VisionCapability.byModelId("deepseek-flash"))
        assertTrue(VisionCapability.byModelId("deepseek-flash-2026"))
        assertTrue(VisionCapability.byModelId("deepseek-v4-flash"))
        assertFalse(VisionCapability.byModelId("deepseek-v4-pro"))
        assertFalse(VisionCapability.byModelId("deepseek-chat"))
        assertFalse(VisionCapability.byModelId(""))
    }

    @Test
    fun `第三方端点即使模型是 flash 也不可用`() {
        // 关键断言：endpoint 判定不通过时，模型名再像也不放行（「绝不回退 host」）。
        assertFalse(
            VisionCapability.isAvailable(
                deepSeekProvider = false,
                model = "deepseek-flash",
                probedModel = "deepseek-flash",
                probedSupported = true,
            ),
        )
    }

    @Test
    fun `缓存结论只对探测时那个模型有效`() {
        // 用户把模型从 flash 换成 pro，但缓存是 flash 的结论：必须回退 id 规则 → 不可用。
        assertFalse(
            VisionCapability.isAvailable(
                deepSeekProvider = true,
                model = "deepseek-v4-pro",
                probedModel = "deepseek-flash",
                probedSupported = true,
            ),
        )
        // 同一模型：用缓存的结论（pro 被探测为 false）。
        assertFalse(
            VisionCapability.isAvailable(
                deepSeekProvider = true,
                model = "deepseek-v4-pro",
                probedModel = "deepseek-v4-pro",
                probedSupported = false,
            ),
        )
    }

    @Test
    fun `无缓存时回退模型 id 规则`() {
        assertTrue(
            VisionCapability.isAvailable(
                deepSeekProvider = true,
                model = "deepseek-flash",
                probedModel = "",
                probedSupported = null,
            ),
        )
        assertFalse(
            VisionCapability.isAvailable(
                deepSeekProvider = true,
                model = "deepseek-v4-pro",
                probedModel = "",
                probedSupported = null,
            ),
        )
    }
}
