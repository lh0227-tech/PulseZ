package cc.pulsez.app.ui.group

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊发送按钮配色（第三方 ：启用时仍用 `primary` 填整枚圆钮）。
 *
 * 依据 （输入条主按钮黑白化， `dfbf59f`）与单聊实现
 * `ui/chat/ChatComponents.kt:1064-1074`（`ComposerMainButtonView`：只判 enabled，
 * 可用 = `onBackground` 实心 + `background` 反色图标，禁用 = 透明底 + `onSurfaceVariant`）。
 *
 * 三件事：
 * 1. 三态取色逐角色断言（可用 / 生成中 / 禁用）；
 * 2. `primary` 结构上不可能出现：角色枚举里没有强调色角色，全部文案扫一遍名字也不行；
 * 3. 源码扫描：`GroupSendButton` 真的走纯函数取色，且全文件只剩 `@名字` 高亮一处 `primary`
 * （强调色仍按 只给「可点的动作 / 正在发生的事」）。
 */
class GroupSendButtonColorsTest {

    private companion object {
        /** 纯 JVM 源码扫描（cwd = `app/`，同 `既有源码扫描测试`/`BackupCopyTest` 口径）。 */
        const val SOURCE_PATH = "src/main/java/cc/pulsez/app/ui/group/GroupMentionComposer.kt"
    }

    private val source: String by lazy { File(SOURCE_PATH).readText(Charsets.UTF_8) }

    // ---------------------------------------------------------------- 三态取色

    @Test
    fun `可用态 深色实心 反色图标`() {
        val colors = GroupSendButtonColors.resolve(enabled = true, streaming = false)
        assertEquals(GroupSendButtonRole.ON_BACKGROUND, colors.background)
        assertEquals(GroupSendButtonRole.BACKGROUND, colors.foreground)
    }

    @Test
    fun `生成中 与可用同一套配色 不走强调色`() {
        val colors = GroupSendButtonColors.resolve(enabled = true, streaming = true)
        assertEquals(GroupSendButtonRole.ON_BACKGROUND, colors.background)
        assertEquals(GroupSendButtonRole.BACKGROUND, colors.foreground)
        assertEquals(
            "生成中「停下」与可用发送共用同一套配色（单聊 STOP 也是实心可点按钮）",
            GroupSendButtonColors.resolve(enabled = true, streaming = false),
            colors,
        )
    }

    @Test
    fun `禁用态 透明底 弱化灰图标`() {
        val colors = GroupSendButtonColors.resolve(enabled = false, streaming = false)
        assertEquals(GroupSendButtonRole.TRANSPARENT, colors.background)
        assertEquals(GroupSendButtonRole.ON_SURFACE_VARIANT, colors.foreground)
    }

    @Test
    fun `三态只出现黑白灰四种角色 没有强调色角色`() {
        GroupSendButtonRole.values().forEach { role ->
            assertFalse("发送按钮不得有强调色角色：$role", role.name.contains("PRIMARY"))
            assertFalse("发送按钮不得有强调色角色：$role", role.name.contains("SIGNAL"))
        }
        val used = listOf(
            GroupSendButtonColors.resolve(enabled = true, streaming = false),
            GroupSendButtonColors.resolve(enabled = true, streaming = true),
            GroupSendButtonColors.resolve(enabled = false, streaming = false),
        ).flatMap { listOf(it.background, it.foreground) }.toSet()
        assertEquals(
            "三态合计只应出现四种黑白灰角色（不可能有第五种强调色）",
            setOf(
                GroupSendButtonRole.ON_BACKGROUND,
                GroupSendButtonRole.BACKGROUND,
                GroupSendButtonRole.ON_SURFACE_VARIANT,
                GroupSendButtonRole.TRANSPARENT,
            ),
            used,
        )
    }

    // ---------------------------------------------------------------- 源码扫描（接线）

    @Test
    fun `源码非空前置`() {
        assertTrue("GroupMentionComposer.kt 源码为空（cwd 应为 app/）：$SOURCE_PATH", source.isNotBlank())
        assertTrue("源码应包含 GroupSendButton 声明", source.contains("private fun GroupSendButton("))
    }

    @Test
    fun `GroupSendButton 通过纯函数取色 不直接引 primary`() {
        val block = functionOf(source, "private fun GroupSendButton(")
        assertTrue(
            "必须通过 GroupSendButtonColors.resolve 取色：$block",
            block.contains("GroupSendButtonColors.resolve("),
        )
        assertTrue("必须把 enabled 传进取色函数", block.contains("enabled = enabled"))
        assertTrue("必须把 streaming 传进取色函数", block.contains("streaming = isStreaming"))
        assertTrue("必须经颜色角色映射到 Compose 颜色", block.contains(".toComposeColor()"))
        assertFalse("发送键不得再直接引用 primary：$block", block.contains("colorScheme.primary"))
        assertFalse("发送键不得再直接引用 onPrimary：$block", block.contains("colorScheme.onPrimary"))
    }

    @Test
    fun `颜色角色映射覆盖全部角色且不含 primary`() {
        val block = functionOf(source, "private fun GroupSendButtonRole.toComposeColor(")
        GroupSendButtonRole.values().forEach { role ->
            assertTrue("映射必须覆盖角色 $role：$block", block.contains(role.name))
        }
        assertTrue("透明角色必须映射到 Color.Transparent", block.contains("Color.Transparent"))
        assertFalse("映射不得落回 primary：$block", block.contains("colorScheme.primary"))
    }

    @Test
    fun `全文件只剩 @名字高亮一处 primary`() {
        assertEquals(
            "@名字高亮仍用 primary，主按钮不得再用主色",
            1,
            Regex(Regex.escape("MaterialTheme.colorScheme.primary")).findAll(source).count(),
        )
    }

    /**
 * 函数声明 → 函数体区块（含头与体）。
 *
 * 与 `既有源码扫描测试.functionOf` 同一做法：先括号配平跳过参数列表，再从函数体开括号
 * 开始配平花括号（参数默认值里的 `{}` 不会让边界提前结束）。
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
}
