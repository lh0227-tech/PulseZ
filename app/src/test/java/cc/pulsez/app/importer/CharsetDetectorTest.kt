package cc.pulsez.app.importer

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编码判定：BOM → 严格 UTF-8 → GB18030 → UTF-8 替换字符版。
 *。
 */
class CharsetDetectorTest {

    @Test
    fun `UTF-8 无 BOM 原样解出`() {
        val text = "阿淮，说话慢。"
        val result = CharsetDetector.decode(text.toByteArray(Charsets.UTF_8))
        assertEquals(text, result.text)
        assertEquals(CharsetDetector.UTF8, result.charset)
        assertFalse(result.lossy)
    }

    @Test
    fun `UTF-8 带 BOM 去掉 BOM`() {
        val text = "她叫阿淮。"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            text.toByteArray(Charsets.UTF_8)
        val result = CharsetDetector.decode(bytes)
        assertEquals(text, result.text)
        assertFalse(result.text.startsWith("\uFEFF"))
        assertEquals(CharsetDetector.UTF8, result.charset)
        assertFalse(result.lossy)
    }

    @Test
    fun `GB18030 中文能解出`() {
        // 用 GB18030 编码字节构造；"你"的 E3 是非法 UTF-8，不会被误判成 UTF-8。
        val text = "你叫阿淮，说话慢。"
        val bytes = text.toByteArray(Charset.forName("GB18030"))
        val result = CharsetDetector.decode(bytes)
        assertEquals(text, result.text)
        assertEquals(CharsetDetector.GB18030, result.charset)
        assertFalse(result.lossy)
    }

    @Test
    fun `UTF-16LE 带 BOM 能解出`() {
        val text = "阿淮"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(Charset.forName("UTF-16LE"))
        val result = CharsetDetector.decode(bytes)
        assertEquals(text, result.text)
        assertEquals("UTF-16LE", result.charset)
        assertFalse(result.lossy)
    }

    @Test
    fun `非法字节退化为替换字符并标 lossy`() {
        // 0x81 0x20 既不是合法 UTF-8，也不是合法 GB18030。
        val bytes = byteArrayOf(0x81.toByte(), 0x20, 0xFF.toByte())
        val result = CharsetDetector.decode(bytes)
        assertTrue(result.lossy)
        assertEquals(CharsetDetector.UTF8, result.charset)
        assertTrue(result.text.contains('\uFFFD'))
    }

    @Test
    fun `空字节返回空串且不标 lossy`() {
        val result = CharsetDetector.decode(ByteArray(0))
        assertEquals("", result.text)
        assertFalse(result.lossy)
    }

    @Test
    fun `GB18030 样本文件按字节解出`() {
        val bytes = resourceBytes("gbk_chinese.txt")
        val result = CharsetDetector.decode(bytes)
        assertEquals(CharsetDetector.GB18030, result.charset)
        assertFalse(result.lossy)
        assertEquals(
            "阿淮，南方人，说话慢。她不主动找你说话，习惯在句尾加一个「吧」字。\n",
            result.text,
        )
    }

    @Test
    fun `预览按字符截断`() {
        val bytes = "第一段文字。第二段文字。第三段文字。".toByteArray(Charsets.UTF_8)
        assertEquals("第一段文字", CharsetDetector.preview(bytes, maxChars = 5))
    }

    private fun resourceBytes(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/importer/$name")) { "缺少测试样本 $name" }
            .use { it.readBytes() }
}
