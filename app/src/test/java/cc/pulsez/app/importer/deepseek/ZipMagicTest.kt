package cc.pulsez.app.importer.deepseek

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ZipMagic` 单测：「魔数」行（`50 4B 03 04` / `50 4B 05 06`）。
 *
 * 纯 JVM、不联网、不落盘；用内存 `ZipOutputStream` 造真实归档头，避免手写怀疑。
 * 只应把「文件开头」交给 [ZipMagic]，不做偏移搜索（偏移搜索会误认嵌在其它文件里的 ZIP）。
 */
class ZipMagicTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun zipBytes(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `本地文件头签名识别为 ZIP`() {
        assertTrue(ZipMagic.isZip(bytes(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test
    fun `空归档 EOCD 签名识别为 ZIP`() {
        assertTrue(ZipMagic.isZip(bytes(0x50, 0x4B, 0x05, 0x06)))
    }

    @Test
    fun `中央目录签名不是合法归档开头`() {
        assertFalse(ZipMagic.isZip(bytes(0x50, 0x4B, 0x01, 0x02)))
    }

    @Test
    fun `不足四字节一律不是 ZIP`() {
        assertFalse(ZipMagic.isZip(ByteArray(0)))
        assertFalse(ZipMagic.isZip(bytes(0x50)))
        assertFalse(ZipMagic.isZip(bytes(0x50, 0x4B, 0x03)))
    }

    @Test
    fun `普通文本不是 ZIP`() {
        assertFalse(ZipMagic.isZip("manifest.json".toByteArray()))
    }

    @Test
    fun `内存造出的 ZIP 开头命中魔数`() {
        assertTrue(ZipMagic.isZip(zipBytes(mapOf("manifest.json" to """{"export_format_version":3}"""))))
    }

    @Test
    fun `内存造出的空归档（只有 EOCD）也命中魔数`() {
        assertTrue(ZipMagic.isZip(zipBytes(emptyMap())))
    }

    @Test
    fun `多余尾部字节不影响头部判定`() {
        assertTrue(ZipMagic.isZip(bytes(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)))
    }
}
