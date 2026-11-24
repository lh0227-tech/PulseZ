package cc.pulsez.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 主题色位回归（ 14； /）。
 *
 * 纯 JVM：`LightColors`/`DarkColors` 是 `Theme.kt` 的 `internal` 顶层值，只读 `ColorScheme`
 * 字段；若本机 JVM 下 Compose Material3 类加载失败，按 允许改 Robolectric（断言不变）。
 *
 * 断言三件事：
 * 1. `surfaceContainerHigh` = 项目 `surface` token（AlertDialog 默认容器色 → 弹层不再偏紫）；
 * 2. 五个 `surfaceContainer*` + 反色面全部映射到 `Color.kt` 现有 token，且不再等于 M3 基线紫；
 * 3. 既有槽位（surface/background/primary/error…）取值零变化。
 */
class ThemeColorSchemeTest {

    // ---------------------------------------------------------------- /：surfaceContainer* 映射

    @Test
    fun `浅色 surfaceContainerHigh 等于项目 surface token`() {
        assertEquals(LightSurface, LightColors.surfaceContainerHigh)
        assertEquals(Color(0xFFF6F6F7), LightColors.surfaceContainerHigh)
    }

    @Test
    fun `深色 surfaceContainerHigh 等于项目 surface token`() {
        assertEquals(DarkSurface, DarkColors.surfaceContainerHigh)
        assertEquals(Color(0xFF141416), DarkColors.surfaceContainerHigh)
    }

    @Test
    fun `浅色五个 surfaceContainer 逐项等于项目 token`() {
        // 映射表（页面底=bg；卡片/输入框/弹层=surface；
        // 悬浮层=surfaceHigh）。
        assertEquals(LightBg, LightColors.surfaceContainerLowest)
        assertEquals(LightSurface, LightColors.surfaceContainerLow)
        assertEquals(LightSurface, LightColors.surfaceContainer)
        assertEquals(LightSurface, LightColors.surfaceContainerHigh)
        assertEquals(LightSurfaceHigh, LightColors.surfaceContainerHighest)
    }

    @Test
    fun `深色五个 surfaceContainer 逐项等于项目 token`() {
        assertEquals(DarkBg, DarkColors.surfaceContainerLowest)
        assertEquals(DarkSurface, DarkColors.surfaceContainerLow)
        assertEquals(DarkSurface, DarkColors.surfaceContainer)
        assertEquals(DarkSurface, DarkColors.surfaceContainerHigh)
        assertEquals(DarkSurfaceHigh, DarkColors.surfaceContainerHighest)
    }

    // ---------------------------------------------------------------- ：不再是 M3 基线紫

    @Test
    fun `不再是 M3 基线偏紫的容器色`() {
        assertFalse(
            "浅色容器色不得是 M3 基线 #ECE6F0",
            LightColors.surfaceContainerHigh == Color(0xFFECE6F0),
        )
        assertFalse(
            "深色容器色不得是 M3 基线 #2B2930",
            DarkColors.surfaceContainerHigh == Color(0xFF2B2930),
        )
    }

    // ---------------------------------------------------------------- 反色面

    @Test
    fun `反色面映射到项目 token`() {
        assertEquals(LightInk, LightColors.inverseSurface)
        assertEquals(LightBg, LightColors.inverseOnSurface)
        assertEquals(LightSignal, LightColors.inversePrimary)
        assertEquals(DarkInk, DarkColors.inverseSurface)
        assertEquals(DarkBg, DarkColors.inverseOnSurface)
        assertEquals(DarkSignal, DarkColors.inversePrimary)
    }

    // ---------------------------------------------------------------- ：既有槽位零变化

    @Test
    fun `浅色既有槽位取值零变化`() {
        assertEquals(LightSignal, LightColors.primary)
        assertEquals(LightOnUserBubble, LightColors.onPrimary)
        assertEquals(LightBg, LightColors.background)
        assertEquals(LightInk, LightColors.onBackground)
        assertEquals(LightBg, LightColors.surface)
        assertEquals(LightInk, LightColors.onSurface)
        assertEquals(LightSurface, LightColors.surfaceVariant)
        assertEquals(LightInkMuted, LightColors.onSurfaceVariant)
        assertEquals(LightLine, LightColors.outline)
        assertEquals(LightLine, LightColors.outlineVariant)
        assertEquals(LightDanger, LightColors.error)
    }

    @Test
    fun `深色既有槽位取值零变化`() {
        assertEquals(DarkSignal, DarkColors.primary)
        assertEquals(DarkOnUserBubble, DarkColors.onPrimary)
        assertEquals(DarkBg, DarkColors.background)
        assertEquals(DarkInk, DarkColors.onBackground)
        assertEquals(DarkBg, DarkColors.surface)
        assertEquals(DarkInk, DarkColors.onSurface)
        assertEquals(DarkSurface, DarkColors.surfaceVariant)
        assertEquals(DarkInkMuted, DarkColors.onSurfaceVariant)
        assertEquals(DarkLine, DarkColors.outline)
        assertEquals(DarkLine, DarkColors.outlineVariant)
        assertEquals(DarkDanger, DarkColors.error)
    }

    @Test
    fun `既有槽位的十六进制值逐项锁定`() {
        // 防止有人「顺手」调色却让测试跟着漂移：这里写死 色表的值。
        assertEquals(Color(0xFFFFFFFF), LightColors.surface)
        assertEquals(Color(0xFF0A0A0C), LightColors.onSurface)
        assertEquals(Color(0xFFFFFFFF), LightColors.background)
        assertEquals(Color(0xFF0A0A0C), LightColors.onBackground)
        assertEquals(Color(0xFFF6F6F7), LightColors.surfaceVariant)
        assertEquals(Color(0xFF6F7177), LightColors.onSurfaceVariant)
        assertEquals(Color(0xFFE8E8EB), LightColors.outline)
        assertEquals(Color(0xFF5B50F0), LightColors.primary)
        assertEquals(Color(0xFFB3261E), LightColors.error)

        assertEquals(Color(0xFF08080A), DarkColors.surface)
        assertEquals(Color(0xFFF2F2F3), DarkColors.onSurface)
        assertEquals(Color(0xFF08080A), DarkColors.background)
        assertEquals(Color(0xFFF2F2F3), DarkColors.onBackground)
        assertEquals(Color(0xFF141416), DarkColors.surfaceVariant)
        assertEquals(Color(0xFFA0A1A7), DarkColors.onSurfaceVariant)
        assertEquals(Color(0xFF2A2A2E), DarkColors.outline)
        assertEquals(Color(0xFF7C74FF), DarkColors.primary)
        assertEquals(Color(0xFFF2857B), DarkColors.error)
    }
}
