package cc.pulsez.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM 源码扫描：锁住 ui 下的主按钮统一走显式配色。cwd = `app/`。 */
class PrimaryButtonWiringSourceScanTest {

    private companion object {
        const val UI_SOURCE_PATH = "src/main/java/cc/pulsez/app/ui"
        const val COMPONENT_PATH = "src/main/java/cc/pulsez/app/ui/components/PulsezPrimaryButton.kt"
        val BUTTON_CALL = Regex("\\bButton\\s*\\(")
        val COLORS_ARGUMENT = Regex("\\bcolors\\s*=")
    }

    @Test
    fun `ui 下每个主按钮调用都显式传入 colors`() {
        val root = File(UI_SOURCE_PATH)
        assertTrue("ui 源码目录不存在：$UI_SOURCE_PATH", root.isDirectory)
        val sourceFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("ui 源码目录为空：$UI_SOURCE_PATH", sourceFiles.isNotEmpty())

        val uncoloredCalls = mutableListOf<String>()
        sourceFiles.forEach { file ->
            if (file.path.replace('\\', '/').endsWith("/components/PulsezPrimaryButton.kt")) return@forEach
            val source = file.readText(Charsets.UTF_8)
            BUTTON_CALL.findAll(source).forEach { match ->
                val arguments = callArguments(source, match.range.first)
                if (!COLORS_ARGUMENT.containsMatchIn(arguments)) {
                    val line = source.take(match.range.first).count { it == '\n' } + 1
                    uncoloredCalls += "${file.path}:$line"
                }
            }
        }

        assertTrue(
            "ui 下的 Button(...) 必须传 colors，或改用 PulsezPrimaryButton；未配色位置：$uncoloredCalls",
            uncoloredCalls.isEmpty(),
        )
    }

    @Test
    fun `共享主按钮使用设计规范的启用与禁用颜色`() {
        val component = File(COMPONENT_PATH).readText(Charsets.UTF_8)
        assertTrue("共享主按钮源码为空", component.isNotBlank())
        assertTrue("共享主按钮必须包装 Material 3 Button", component.contains("Button("))
        assertTrue("必须通过 ButtonDefaults.buttonColors 设置颜色", component.contains("ButtonDefaults.buttonColors("))
        listOf(
            "containerColor = MaterialTheme.colorScheme.onBackground",
            "contentColor = MaterialTheme.colorScheme.background",
            "disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant",
            "disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant",
        ).forEach { color ->
            assertTrue("缺少共享按钮颜色：$color", component.contains(color))
        }
    }

    /** 从按钮调用开括号开始配平参数括号，避免 modifier 等内嵌调用截断扫描。 */
    private fun callArguments(source: String, callStart: Int): String {
        val open = source.indexOf('(', callStart)
        assertTrue("找不到 Button 调用的开括号：${source.substring(callStart).take(40)}", open >= 0)
        var depth = 0
        var inString = false
        var escaped = false
        for (index in open until source.length) {
            val char = source[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        throw AssertionError("Button 调用参数没有闭合：${source.substring(callStart).take(80)}")
    }
}
