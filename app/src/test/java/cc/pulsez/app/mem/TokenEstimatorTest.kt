package cc.pulsez.app.mem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TokenEstimator] 的估算口径与校准边界测试。
 *
 * 对应文档：03 /、02、10。
 */
class TokenEstimatorTest {

    @Test
    fun emptyStringIsZero() {
        assertEquals(0, TokenEstimator.estimate(""))
        assertEquals(0, TokenEstimator.estimateMessages(emptyList()))
    }

    @Test
    fun pureChineseUsesSevenTenthsPerChar() {
        // 10 个汉字 × 0.7 = 7
        assertEquals(7, TokenEstimator.estimate("你好世界你好世界你好"))
        // 4 个汉字 × 0.7 = 2.8 → 向上取整 3
        assertEquals(3, TokenEstimator.estimate("你好世界"))
    }

    @Test
    fun pureLatinUsesFourCharsPerToken() {
        // "hello world"：10 个字母 × 0.25 = 2.5 → 3（空白不计）
        assertEquals(3, TokenEstimator.estimate("hello world"))
        // 100 个字母 = 25 token
        assertEquals(25, TokenEstimator.estimate("a".repeat(100)))
    }

    @Test
    fun digitsAndPunctuationCountedSeparately() {
        // 6 个数字 × 0.5 = 3
        assertEquals(3, TokenEstimator.estimate("123456"))
        // 3 个 ASCII 标点 × 0.25 = 0.75 → 1
        assertEquals(1, TokenEstimator.estimate(",.;"))
        // 中文标点属于 CJK：2 × 0.7 = 1.4 → 2
        assertEquals(2, TokenEstimator.estimate("，。"))
    }

    @Test
    fun mixedChineseAndLatin() {
        // "hello你好"：5×0.25 + 2×0.7 = 1.25 + 1.4 = 2.65 → 3
        assertEquals(3, TokenEstimator.estimate("hello你好"))
    }

    @Test
    fun messagesAddFourTokensOverheadEach() {
        val messages = listOf(
            MemMessage.user("你好世界"), // 3 + 4
            MemMessage.assistant("hello world"), // 3 + 4
        )
        assertEquals(14, TokenEstimator.estimateMessages(messages))
        assertEquals(7, TokenEstimator.estimateMessage(messages[0]))
    }

    @Test
    fun calibratedClampsFactorToRange() {
        assertEquals(100, TokenEstimator.calibrated(100, 1.0))
        assertEquals(150, TokenEstimator.calibrated(100, 1.5))
        // 超过上界的系数被钳位到 1.5
        assertEquals(150, TokenEstimator.calibrated(100, 3.0))
        // 低于下界的系数被钳位到 0.7
        assertEquals(70, TokenEstimator.calibrated(100, 0.1))
        // 非法系数回落 1.0
        assertEquals(100, TokenEstimator.calibrated(100, Double.NaN))
        assertEquals(0, TokenEstimator.calibrated(0, 1.2))
    }

    @Test
    fun calibrateStaysWithinBounds() {
        assertEquals(1.2, TokenEstimator.calibrate(100, 120, 1.0), 1e-9)
        // 实际用量远超估算 → 钳位 1.5
        assertEquals(1.5, TokenEstimator.calibrate(100, 300, 1.0), 1e-9)
        // 实际用量远低于估算 → 钳位 0.7
        assertEquals(0.7, TokenEstimator.calibrate(100, 10, 1.0), 1e-9)
        // 估算值为 0 时保持当前系数（同样受钳位）
        assertEquals(1.0, TokenEstimator.calibrate(0, 100, 1.0), 1e-9)
        assertEquals(1.5, TokenEstimator.calibrate(0, 100, 2.0), 1e-9)
        // 连续校准不会越界
        var factor = 1.0
        repeat(20) { factor = TokenEstimator.calibrate(100, 1000, factor) }
        assertTrue(factor <= TokenEstimator.MAX_CALIBRATION_FACTOR)
    }

    @Test
    fun truncateToTokensKeepsEstimateUnderCap() {
        val long = "今天天气很好我们出去走走吧".repeat(100)
        for (cap in intArrayOf(8, 16, 50, 120)) {
            val truncated = TokenEstimator.truncateToTokens(long, cap)
            assertTrue(
                "cap=$cap 但估算=${TokenEstimator.estimate(truncated)}",
                TokenEstimator.estimate(truncated) <= cap,
            )
        }
        // 未超预算的文本原样返回
        assertEquals("短文本", TokenEstimator.truncateToTokens("短文本", 100))
        assertEquals("", TokenEstimator.truncateToTokens("abc", 0))
        // 截断非空
        assertTrue(TokenEstimator.truncateToTokens(long, 50).isNotEmpty())
    }
}
