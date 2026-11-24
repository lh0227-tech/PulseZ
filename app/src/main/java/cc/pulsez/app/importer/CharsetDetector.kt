package cc.pulsez.app.importer

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 一次编码判定的结果。
 *
 * [charset] 是实际采用的编码名（"UTF-8" / "GB18030" / "UTF-16LE" / "UTF-32BE"），
 * 界面要显示"换了什么编码"时直接用它；[lossy] 为 true 表示没有一种编码能干净解出来，
 * 文本里已经有替换字符，预览时一眼能看出乱码。
 */
data class DecodeResult(
    val text: String,
    val charset: String,
    val lossy: Boolean,
)

/**
 * 编码判定。规则严格照
 *
 * 1. 有 BOM：按 BOM。
 * 2. 严格 UTF-8 解码成功 → 用。
 * 3. 否则 GB18030 解码成功 → 用。
 * 4. 都不行：返回 UTF-8 替换字符版，并标 [DecodeResult.lossy]，
 *    由界面提示"换成 UTF-8 或 GB18030 试试"并给 200 字预览（[preview]）。
 *
 * 这里的"严格"是指解码器开 REPORT：宁可解不出来，也不要静默替换。
 * 不做编码启发式猜测——规范定了顺序，而 GBK 字节碰巧凑成合法 UTF-8 的情况
 * 只能靠预览页让用户认出来，见 [ImportWarnings.GARBLED]。
 */
object CharsetDetector {

    const val UTF8: String = "UTF-8"
    const val GB18030: String = "GB18030"

    /**
     * 解一份文件字节。
     *
     * 纯 Kotlin/JVM，不碰 Android；文件读取由调用方完成。
     */
    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.isEmpty()) return DecodeResult("", UTF8, lossy = false)

        // ① BOM 优先。
        bomDecoded(bytes)?.let { return it }

        // ② 严格 UTF-8。
        strictDecode(bytes, 0, bytes.size, Charsets.UTF_8)
            ?.let { return DecodeResult(it, UTF8, lossy = false) }

        // ③ GB18030（部分 Android 设备只有 GBK，退一步用 GBK 也能解）。
        gb18030OrGbk()?.let { gb ->
            strictDecode(bytes, 0, bytes.size, gb)
                ?.let { return DecodeResult(it, GB18030, lossy = false) }
        }

        // ④ 都失败：UTF-8 替换字符版，标记 lossy 让界面提示换编码。
        return DecodeResult(String(bytes, Charsets.UTF_8), UTF8, lossy = true)
    }

    /**
     * §2.1 第 4 步要的"前 200 字预览"：用指定编码粗解一段，截到 [maxChars] 个字符。
     * 调用方在 UTF-8 / GB18030 之间让用户选时，把两份预览并排给出来。
     */
    fun preview(bytes: ByteArray, maxChars: Int = 200, charset: String = UTF8): String {
        val cs = runCatching { Charset.forName(charset) }.getOrDefault(Charsets.UTF_8)
        return String(bytes, cs).withoutBom().take(maxChars)
    }

    // ---- 内部实现 ----

    /** 按 BOM 解；没有 BOM 返回 null。UTF-32 的 BOM 以 UTF-16 的 BOM 开头，所以先判 32。 */
    private fun bomDecoded(bytes: ByteArray): DecodeResult? {
        val n = bytes.size
        fun at(i: Int): Int = bytes[i].toInt() and 0xFF

        if (n >= 4) {
            when {
                at(0) == 0xFF && at(1) == 0xFE && at(2) == 0x00 && at(3) == 0x00 ->
                    return decodeUtf32(bytes, offset = 4, littleEndian = true)

                at(0) == 0x00 && at(1) == 0x00 && at(2) == 0xFE && at(3) == 0xFF ->
                    return decodeUtf32(bytes, offset = 4, littleEndian = false)
            }
        }
        if (n >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) {
            val (text, lossy) = decodeWith(bytes, 3, n - 3, Charsets.UTF_8)
            return DecodeResult(text, UTF8, lossy)
        }
        if (n >= 2) {
            when {
                at(0) == 0xFF && at(1) == 0xFE -> {
                    val (text, lossy) = decodeWith(bytes, 2, n - 2, utf16le())
                    return DecodeResult(text, "UTF-16LE", lossy)
                }

                at(0) == 0xFE && at(1) == 0xFF -> {
                    val (text, lossy) = decodeWith(bytes, 2, n - 2, utf16be())
                    return DecodeResult(text, "UTF-16BE", lossy)
                }
            }
        }
        return null
    }

    /**
     * UTF-32 不依赖平台字符集实现（有些 Android 版本没有 UTF-32LE/BE），手工按码点解。
     * 结构不对就返回 null，交给后面的常规判定。
     */
    private fun decodeUtf32(bytes: ByteArray, offset: Int, littleEndian: Boolean): DecodeResult? {
        val length = bytes.size - offset
        if (length <= 0 || length % 4 != 0) return null
        val count = length / 4
        val codePoints = IntArray(count)
        for (i in 0 until count) {
            val p = offset + i * 4
            val b0 = bytes[p].toInt() and 0xFF
            val b1 = bytes[p + 1].toInt() and 0xFF
            val b2 = bytes[p + 2].toInt() and 0xFF
            val b3 = bytes[p + 3].toInt() and 0xFF
            val cp = if (littleEndian) {
                b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
            if (cp !in 0..0x10FFFF || cp in 0xD800..0xDFFF) return null
            codePoints[i] = cp
        }
        return DecodeResult(
            text = String(codePoints, 0, count),
            charset = if (littleEndian) "UTF-32LE" else "UTF-32BE",
            lossy = false,
        )
    }

    /** 先严格解；失败再接受替换字符版。返回 (文本, 是否 lossy)。 */
    private fun decodeWith(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        charset: Charset,
    ): Pair<String, Boolean> {
        strictDecode(bytes, offset, length, charset)?.let { return it to false }
        return String(bytes, offset, length, charset) to true
    }

    /**
     * 开 REPORT 的严格解码。任何异常都当"这个编码解不出来"处理——
     * 判定阶段不该因为某个平台的 charset 实现差异把整条导入链路弄崩。
     */
    private fun strictDecode(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String? {
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        } catch (_: Exception) {
            null
        }
    }

    private val gb18030Charset: Charset? by lazy {
        runCatching { Charset.forName(GB18030) }.getOrNull()
            ?: runCatching { Charset.forName("GBK") }.getOrNull()
    }

    private fun gb18030OrGbk(): Charset? = gb18030Charset

    private fun utf16le(): Charset =
        runCatching { Charset.forName("UTF-16LE") }.getOrDefault(Charsets.UTF_16LE)

    private fun utf16be(): Charset =
        runCatching { Charset.forName("UTF-16BE") }.getOrDefault(Charsets.UTF_16BE)
}
