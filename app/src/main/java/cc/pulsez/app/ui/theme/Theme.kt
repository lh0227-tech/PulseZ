package cc.pulsez.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 配色方案（v0.5.2）：显式补全 `surfaceContainer*` 与反色面槽位。
 *
 * 为什么补：`AlertDialog` 的默认容器色是 `surfaceContainerHigh`（1.3 起），此前没设 →
 * 用 基线默认值（浅 `#ECE6F0`、深 `#2B2930`，偏紫），弹窗大面积偏紫。
 * 映射全部复用 `Color.kt` 现有中性色 token，**不新增任何十六进制值**、不改字号/间距。
 * 依据（`surface` = 卡片、
 * 输入框、弹层；`surfaceHigh` = 悬浮层配 1px 描边）。
 *
 * `private` → `internal` 只是让纯 JVM 单测（`ThemeColorSchemeTest`）能读这两个值；语义不变。
 */

internal val LightColors = lightColorScheme(
    primary = LightSignal,
    onPrimary = LightOnUserBubble,
    background = LightBg,
    onBackground = LightInk,
    surface = LightBg,
    onSurface = LightInk,
    surfaceVariant = LightSurface,
    onSurfaceVariant = LightInkMuted,
    outline = LightLine,
    outlineVariant = LightLine,
    error = LightDanger,
    // ---- v0.5.2 补全（依据 映射表）----
    // 页面底 = bg。
    surfaceContainerLowest = LightBg,
    // 卡片/输入框/弹层 = surface。
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurface,
    // 关键：AlertDialog 默认容器色 = surfaceContainerHigh → 弹层用项目 surface，不再是 紫。
    surfaceContainerHigh = LightSurface,
    // 悬浮层（配 1px 描边）= surfaceHigh。
    surfaceContainerHighest = LightSurfaceHigh,
    // 反色面（Snackbar 类）：避开 基线 #313033 偏紫。
    inverseSurface = LightInk,
    inverseOnSurface = LightBg,
    inversePrimary = LightSignal,
    // 防御性：项目无 tonalElevation 用法，置中性 = 不引入额外色调（不参与验收）。
    surfaceTint = LightSurface,
)

internal val DarkColors = darkColorScheme(
    primary = DarkSignal,
    onPrimary = DarkOnUserBubble,
    background = DarkBg,
    onBackground = DarkInk,
    surface = DarkBg,
    onSurface = DarkInk,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkInkMuted,
    outline = DarkLine,
    outlineVariant = DarkLine,
    error = DarkDanger,
    // ---- v0.5.2 补全（深色对称；依据 映射表）----
    surfaceContainerLowest = DarkBg,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurface,
    surfaceContainerHighest = DarkSurfaceHigh,
    inverseSurface = DarkInk,
    inverseOnSurface = DarkBg,
    inversePrimary = DarkSignal,
    surfaceTint = DarkSurface,
)

@Composable
fun PulsezTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PulsezTypography,
        content = content,
    )
}
