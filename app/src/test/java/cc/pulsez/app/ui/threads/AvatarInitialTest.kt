package cc.pulsez.app.ui.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头像首字按 Unicode 码点取完整字符（ 15； /）。
 *
 * 纯 JVM：`avatarInitial` 是 `ThreadsViewModel.kt` 末尾的顶层纯函数，不依赖 Android/Compose。
 * 边界按 的 KDoc 逐条锁定；**不承诺 grapheme cluster**（组合符/ZWJ 只取第一个码点）。
 */
class AvatarInitialTest {

    /** 返回串里没有孤立代理项（每个代理项都成对出现）。 */
    private fun hasLoneSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (index + 1 >= value.length) return true
                    if (!Character.isLowSurrogate(value[index + 1])) return true
                    index += 2
                }
                Character.isLowSurrogate(ch) -> return true
                else -> index += 1
            }
        }
        return false
    }

    @Test
    fun `汉字取第一个完整字`() {
        assertEquals("阿", avatarInitial("阿淮"))
    }

    @Test
    fun `emoji 代理对取完整码点`() {
        val result = avatarInitial("🐳鲸鱼")
        assertEquals("🐳", result)
        assertEquals("emoji 由两个 UTF-16 码元组成", 2, result.length)
        assertEquals(1, result.codePointCount(0, result.length))
        assertTrue(Character.isSurrogatePair(result[0], result[1]))
    }

    @Test
    fun `组合附加符不跟随`() {
        // "e" + U+0301（组合尖音符）→ 只取 "e"（不承诺 grapheme cluster）。
        assertEquals("e", avatarInitial("e\u0301"))
    }

    @Test
    fun `ZWJ 家庭 emoji 只取第一个码点`() {
        // 👨‍👩‍👧 = 👨 + ZWJ + 👩 + ZWJ + 👧；按文档只保证「完整码点」。
        val result = avatarInitial("👨\u200D👩\u200D👧")
        assertEquals("👨", result)
        assertEquals(1, result.codePointCount(0, result.length))
        assertTrue(!hasLoneSurrogate(result))
    }

    @Test
    fun `空串 纯空白 控制空白都返回空串`() {
        assertEquals("", avatarInitial(""))
        assertEquals("", avatarInitial("   "))
        assertEquals("", avatarInitial("\t\n"))
    }

    @Test
    fun `首尾空白先 trim`() {
        assertEquals("🐳", avatarInitial("  🐳x"))
        assertEquals("阿", avatarInitial("阿淮 "))
    }

    @Test
    fun `孤立代理项输入绝不输出半个字符`() {
        assertEquals("", avatarInitial("\uD83D"))
        assertEquals("", avatarInitial("\uDC0D"))
    }

    @Test
    fun `任意样本的返回值都没有孤立代理项`() {
        listOf(
            "阿淮",
            "🐳鲸鱼",
            "e\u0301",
            "👨\u200D👩\u200D👧",
            "  🐳x",
            "𝄞音符",
            "Ω",
        ).forEach { name ->
            val result = avatarInitial(name)
            assertTrue("「$name」的结果不能含孤立代理项：$result", !hasLoneSurrogate(result))
            assertTrue(
                "「$name」的结果最多 1 个码点：$result",
                result.codePointCount(0, result.length) <= 1,
            )
        }
    }
}
