package cc.pulsez.app.mem

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * int8 量化向量与点积 / 余弦（02 §3 chunk 的 `vec` + `scale`，03 §7.3–§7.4）。
 *
 * 存储形态：`bytes[i] = round(v[i] / scale)`，`scale = maxAbs / 127`。
 * 反量化：`v[i] ≈ bytes[i] * scale`。
 * 两个量化向量的点积 = 整数点积 × 两个 scale 之积；余弦与 scale 无关（可约掉）。
 *
 * @property dim 维度
 * @property bytes int8 分量（[-127, 127]）
 * @property scale 反量化系数（0 向量时为 1）
 */
data class QuantizedVector(
    val dim: Int,
    val bytes: ByteArray,
    val scale: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuantizedVector) return false
        return dim == other.dim && scale == other.scale && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = dim
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + scale.hashCode()
        return result
    }

    /** 反量化为浮点向量。 */
    fun toFloatArray(): FloatArray = VectorMath.dequantize(this)
}

/**
 * 向量运算工具。性能目标：2000 条 × 512 维 int8 打分 < 20ms（03 §7、10 §3 的召回层）。
 */
object VectorMath {

    /** int8 量化上限。 */
    const val INT8_MAX: Int = 127

    /**
     * 把浮点向量量化成 int8 + scale。
     *
     * 对应文档：02 §3 chunk（`vec` / `scale`）、03 §7.3（只 embed 打包文本）。
     *
     * @param vector 浮点向量
     */
    fun quantizeInt8(vector: FloatArray): QuantizedVector {
        if (vector.isEmpty()) return QuantizedVector(0, ByteArray(0), 1f)
        var maxAbs = 0f
        for (value in vector) {
            val magnitude = abs(value)
            if (magnitude > maxAbs) maxAbs = magnitude
        }
        if (maxAbs <= 0f || !maxAbs.isFinite()) {
            return QuantizedVector(vector.size, ByteArray(vector.size), 1f)
        }
        val scale = maxAbs / INT8_MAX.toFloat()
        val bytes = ByteArray(vector.size) { index ->
            (vector[index] / scale).roundToInt().coerceIn(-INT8_MAX, INT8_MAX).toByte()
        }
        return QuantizedVector(vector.size, bytes, scale)
    }

    /**
     * 反量化回浮点向量。
     *
     * 对应文档：02 §3 chunk（`vec` + `scale`）。
     */
    fun dequantize(vector: QuantizedVector): FloatArray =
        FloatArray(vector.dim) { index -> vector.bytes[index] * vector.scale }

    /**
     * 两个量化向量的点积（已乘入各自 scale）。
     *
     * 对应文档：03 §7.4（向量召回打分）。
     *
     * @throws IllegalArgumentException 维度不一致
     */
    fun dot(a: QuantizedVector, b: QuantizedVector): Float {
        require(a.dim == b.dim) { "向量维度不一致：${a.dim} vs ${b.dim}" }
        var accumulator = 0L
        for (index in 0 until a.dim) {
            accumulator += a.bytes[index].toInt() * b.bytes[index].toInt()
        }
        return accumulator.toFloat() * a.scale * b.scale
    }

    /** 两个浮点向量的点积。 */
    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "向量维度不一致：${a.size} vs ${b.size}" }
        var accumulator = 0.0
        for (index in a.indices) {
            accumulator += a[index] * b[index]
        }
        return accumulator.toFloat()
    }

    /** 浮点查询向量与量化文档向量的点积（召回主路径）。 */
    fun dot(query: FloatArray, document: QuantizedVector): Float {
        require(query.size == document.dim) { "向量维度不一致：${query.size} vs ${document.dim}" }
        var accumulator = 0.0
        for (index in query.indices) {
            accumulator += query[index] * (document.bytes[index] * document.scale)
        }
        return accumulator.toFloat()
    }

    /**
     * 余弦相似度（int8 量化后计算；scale 在分子分母中约掉）。
     *
     * 对应文档：03 §7.4（余弦阈值 0.35）。
     *
     * @return [-1, 1]；任一向量为零向量时返回 0
     */
    fun cosine(a: QuantizedVector, b: QuantizedVector): Float {
        require(a.dim == b.dim) { "向量维度不一致：${a.dim} vs ${b.dim}" }
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in 0 until a.dim) {
            val left = a.bytes[index].toDouble()
            val right = b.bytes[index].toDouble()
            dotProduct += left * right
            normA += left * left
            normB += right * right
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dotProduct / sqrt(normA * normB)).toFloat().coerceIn(-1f, 1f)
    }

    /** 两个浮点向量的余弦相似度。 */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "向量维度不一致：${a.size} vs ${b.size}" }
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in a.indices) {
            dotProduct += a[index] * b[index]
            normA += a[index] * a[index]
            normB += b[index] * b[index]
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dotProduct / sqrt(normA * normB)).toFloat().coerceIn(-1f, 1f)
    }

    /** 浮点查询向量与量化文档向量的余弦相似度（召回主路径）。 */
    fun cosine(query: FloatArray, document: QuantizedVector): Float {
        require(query.size == document.dim) { "向量维度不一致：${query.size} vs ${document.dim}" }
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in query.indices) {
            val left = query[index].toDouble()
            val right = document.bytes[index].toDouble() * document.scale
            dotProduct += left * right
            normA += left * left
            normB += right * right
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dotProduct / sqrt(normA * normB)).toFloat().coerceIn(-1f, 1f)
    }

    /** 浮点向量的 范数。*/
    fun norm(vector: FloatArray): Float {
        var sum = 0.0
        for (value in vector) {
            sum += value * value
        }
        return sqrt(sum).toFloat()
    }
}
