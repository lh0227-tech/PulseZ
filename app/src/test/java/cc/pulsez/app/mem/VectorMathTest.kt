package cc.pulsez.app.mem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.system.measureNanoTime

/**
 * [VectorMath] 的 int8 量化、点积、余弦与性能基准测试。
 *
 * 对应文档：02 chunk（vec / scale）、03、10（召回层 JVM 单测）。
 */
class VectorMathTest {

    @Test
    fun quantizeRoundTripWithinScale() {
        val vector = floatArrayOf(0.1f, -0.5f, 0.9f, 0.0f, 0.33f)
        val quantized = VectorMath.quantizeInt8(vector)
        assertEquals(vector.size, quantized.dim)
        assertEquals(vector.size, quantized.bytes.size)
        val restored = VectorMath.dequantize(quantized)
        for (index in vector.indices) {
            assertTrue(
                "第 $index 维误差过大：${abs(restored[index] - vector[index])} > ${quantized.scale}",
                abs(restored[index] - vector[index]) <= quantized.scale + 1e-5f,
            )
        }
    }

    @Test
    fun scaleIsMaxAbsOver127() {
        val quantized = VectorMath.quantizeInt8(floatArrayOf(1f, -1f, 0.5f))
        assertEquals(1f / 127f, quantized.scale, 1e-7f)
    }

    @Test
    fun quantizedDotMatchesDequantizedDot() {
        val random = Random(7)
        val a = FloatArray(64) { random.nextFloat() * 2f - 1f }
        val b = FloatArray(64) { random.nextFloat() * 2f - 1f }
        val quantizedA = VectorMath.quantizeInt8(a)
        val quantizedB = VectorMath.quantizeInt8(b)
        val expected = VectorMath.dot(VectorMath.dequantize(quantizedA), VectorMath.dequantize(quantizedB))
        val actual = VectorMath.dot(quantizedA, quantizedB)
        assertEquals(expected, actual, 1e-3f)
    }

    @Test
    fun cosineOfIdenticalVectorsIsOne() {
        val random = Random(11)
        val vector = FloatArray(128) { random.nextFloat() * 2f - 1f }
        val quantized = VectorMath.quantizeInt8(vector)
        assertEquals(1f, VectorMath.cosine(quantized, quantized), 1e-4f)
        assertEquals(1f, VectorMath.cosine(vector, vector), 1e-5f)
        // 浮点查询向量 vs 量化文档向量
        assertEquals(1f, VectorMath.cosine(vector, quantized), 1e-3f)
    }

    @Test
    fun cosineOfOrthogonalVectorsIsZero() {
        val a = floatArrayOf(1f, 0f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f, 0f)
        assertEquals(0f, VectorMath.cosine(a, b), 1e-6f)
    }

    @Test
    fun cosineOfZeroVectorIsZero() {
        assertEquals(0f, VectorMath.cosine(FloatArray(4), FloatArray(4)), 1e-6f)
        val zero = VectorMath.quantizeInt8(FloatArray(4))
        assertEquals(0f, VectorMath.cosine(zero, zero), 1e-6f)
        assertEquals(0f, VectorMath.dot(zero, zero), 1e-6f)
    }

    @Test
    fun emptyVectorIsSupported() {
        val empty = VectorMath.quantizeInt8(FloatArray(0))
        assertEquals(0, empty.dim)
        assertEquals(0f, VectorMath.dot(empty, empty), 0f)
        assertEquals(0f, VectorMath.cosine(empty, empty), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dimensionMismatchThrows() {
        VectorMath.dot(VectorMath.quantizeInt8(floatArrayOf(1f)), VectorMath.quantizeInt8(floatArrayOf(1f, 2f)))
    }

    @Test
    fun dotPerformanceTwoThousandVectorsBy512Dims() {
        val random = Random(42)
        // 构造一次，避免把量化时间算进基准
        val docs = List(2000) { VectorMath.quantizeInt8(FloatArray(512) { random.nextFloat() * 2f - 1f }) }
        val query = VectorMath.quantizeInt8(FloatArray(512) { random.nextFloat() * 2f - 1f })

        fun scoreAll(): Float {
            var accumulator = 0f
            for (doc in docs) {
                accumulator += VectorMath.dot(query, doc)
            }
            return accumulator
        }

        // 预热 JIT
        repeat(3) { scoreAll() }

        var bestNanos = Long.MAX_VALUE
        repeat(3) {
            val elapsed = measureNanoTime {
                val accumulator = scoreAll()
                check(accumulator.isFinite())
            }
            if (elapsed < bestNanos) bestNanos = elapsed
        }
        val bestMillis = bestNanos / 1_000_000.0
        assertTrue("2000×512 int8 点积耗时 ${bestMillis}ms，超过 20ms 目标", bestNanos < 20_000_000L)
    }
}
