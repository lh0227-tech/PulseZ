package cc.pulsez.app.importer.deepseek

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `DeepSeekZip` 单测： 契约、 安全与限额、 项。
 *
 * 全部用 `ZipOutputStream` 现场造包（不提交二进制 fixture）、纯 JVM、不联网。
 * 断言只覆盖本项目自己的白名单/限额/CRC/编码，不断言平台 `ZipPathValidator` 行为
 * （Robolectric/JDK 与 libcore 不同）。
 */
class DeepSeekZipTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val manifestJson = """{"export_format_version":3}"""
    private val indexJson = """[{"id":"s1","updated_at":"2025-03-02T00:00:00.000Z"}]"""

    private fun sessionJson(id: String): String = """{"id":"$id","title":"会话$id"}"""

    // ---- fixtures ----

    private fun writeZip(
        fileName: String,
        entries: List<Pair<String, ByteArray>>,
        stored: Boolean = false,
    ): File {
        val file = File(tempFolder.root, fileName)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = content.size.toLong()
                    entry.compressedSize = content.size.toLong()
                    entry.crc = CRC32().apply { update(content) }.value
                }
                out.putNextEntry(entry)
                out.write(content)
                out.closeEntry()
            }
        }
        return file
    }

    private fun standardEntries(): List<Pair<String, ByteArray>> = listOf(
        ZipEntryNames.MANIFEST to manifestJson.toByteArray(),
        ZipEntryNames.SESSION_INDEX to indexJson.toByteArray(),
        "chat_sessions/s1.json" to sessionJson("s1").toByteArray(),
        "chat_sessions/s2.json" to sessionJson("s2").toByteArray(),
        "_files/pic.png" to byteArrayOf(1, 2, 3),
    )

    private fun standardZip(fileName: String = "export.zip"): File =
        writeZip(fileName, standardEntries())

    private fun codeOf(block: () -> Unit): String =
        assertThrows(ZipImportException::class.java) { block() }.code

    private fun isSignature(bytes: ByteArray, at: Int, vararg signature: Int): Boolean {
        if (at < 0 || at + signature.size > bytes.size) return false
        for (i in signature.indices) {
            if ((bytes[at + i].toInt() and 0xFF) != signature[i]) return false
        }
        return true
    }

    // ---- 正常包 ----

    @Test
    fun `正常包读取 manifest 索引 会话名与声明的尺寸`() {
        DeepSeekZip.open(standardZip()).use { zip ->
            assertEquals(manifestJson, zip.manifestText())
            assertEquals(indexJson, zip.indexText())
            assertEquals(
                listOf("chat_sessions/s1.json", "chat_sessions/s2.json"),
                zip.sessionEntryNames(),
            )

            val sizes = zip.sessionSizes()
            assertEquals(2, sizes.size)
            assertEquals("chat_sessions/s1.json", sizes[0].name)
            assertEquals(
                sessionJson("s1").toByteArray().size.toLong(),
                sizes[0].declaredSize,
            )
            assertTrue("压缩后大小来自中央目录且应 > 0", sizes[0].compressedSize > 0L)

            assertEquals(sessionJson("s1"), zip.sessionText("chat_sessions/s1.json"))
            assertEquals(sessionJson("s2"), zip.sessionText("chat_sessions/s2.json"))
        }
    }

    @Test
    fun `_files 与嵌套目录不进会话列表`() {
        val file = writeZip(
            "nested.zip",
            listOf(
                ZipEntryNames.MANIFEST to manifestJson.toByteArray(),
                "chat_sessions/s1.json" to sessionJson("s1").toByteArray(),
                "chat_sessions/_files/att.json" to "{}".toByteArray(),
                "chat_sessions/sub/deep.json" to "{}".toByteArray(),
                "_files/pic.png" to byteArrayOf(9),
            ),
        )
        DeepSeekZip.open(file).use { zip ->
            assertEquals(listOf("chat_sessions/s1.json"), zip.sessionEntryNames())
        }
    }

    @Test
    fun `索引缺失时 indexText 返回 null`() {
        val file = writeZip(
            "no-index.zip",
            listOf(
                ZipEntryNames.MANIFEST to manifestJson.toByteArray(),
                "chat_sessions/s1.json" to sessionJson("s1").toByteArray(),
            ),
        )
        DeepSeekZip.open(file).use { zip ->
            assertNull(zip.indexText())
            assertEquals(listOf("chat_sessions/s1.json"), zip.sessionEntryNames())
        }
    }

    // ---- 空包 / 非 ZIP / 截断 ----

    @Test
    fun `空归档打开成功但没有 manifest 与会话`() {
        val file = writeZip("empty.zip", emptyList())
        DeepSeekZip.open(file).use { zip ->
            assertTrue(zip.sessionEntryNames().isEmpty())
            assertTrue(zip.sessionSizes().isEmpty())
            assertNull(zip.indexText())
            assertEquals(ZipErrorCodes.NOT_FOUND, codeOf { zip.manifestText() })
        }
    }

    @Test
    fun `文件不存在报 NOT_FOUND`() {
        val missing = File(tempFolder.root, "missing.zip")
        assertEquals(ZipErrorCodes.NOT_FOUND, codeOf { DeepSeekZip.open(missing) })
    }

    @Test
    fun `不是 ZIP 报 NOT_ZIP`() {
        val file = File(tempFolder.root, "plain.txt")
        file.writeText("这不是压缩包")
        assertEquals(ZipErrorCodes.NOT_ZIP, codeOf { DeepSeekZip.open(file) })
    }

    @Test
    fun `超过 zipBytes 上限报 TOO_LARGE`() {
        val file = standardZip("too-big.zip")
        assertEquals(
            ZipErrorCodes.TOO_LARGE,
            codeOf { DeepSeekZip.open(file, ZipLimits(zipBytes = 10L)) },
        )
    }

    @Test
    fun `截断的 ZIP 报 BAD_ZIP`() {
        val file = standardZip("truncated.zip")
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        assertEquals(ZipErrorCodes.BAD_ZIP, codeOf { DeepSeekZip.open(file) })
    }

    // ---- 条目名安全 ----

    @Test
    fun `条目名规则拒绝危险形态并放行正常名`() {
        assertFalse(ZipEntryNames.isSafeFileEntryName(""))
        assertFalse(ZipEntryNames.isSafeFileEntryName("a\u0000b.json"))
        assertFalse(ZipEntryNames.isSafeFileEntryName("a\\b.json"))
        assertFalse(ZipEntryNames.isSafeFileEntryName("/abs.json"))
        assertFalse(ZipEntryNames.isSafeFileEntryName("C:/evil.json"))
        assertFalse(ZipEntryNames.isSafeFileEntryName("a/C:/evil.json"))
        assertFalse(ZipEntryNames.isSafeFileEntryName("chat_sessions/../evil.json"))
        assertFalse(ZipEntryNames.isSafeFileEntryName("chat_sessions/./evil.json"))
        assertFalse(ZipEntryNames.isSafeFileEntryName("a//b.json"))
        assertFalse(ZipEntryNames.isSafeFileEntryName("dir/"))
        assertTrue(ZipEntryNames.isSafeFileEntryName("manifest.json"))
        assertTrue(ZipEntryNames.isSafeFileEntryName("chat_sessions/abc.json"))
        assertTrue(ZipEntryNames.isSafeFileEntryName("_files/1234.png"))
    }

    @Test
    fun `含路径穿越条目名的整包被拒绝`() {
        val unsafeNames = listOf(
            "../evil.json",
            "/abs.json",
            "C:/evil.json",
            "a\\b.json",
            "chat_sessions/../evil.json",
            "a//b.json",
        )
        for (unsafe in unsafeNames) {
            val file = writeZip(
                "unsafe-${unsafe.hashCode()}.zip",
                listOf(
                    unsafe to "{}".toByteArray(),
                    ZipEntryNames.MANIFEST to manifestJson.toByteArray(),
                ),
            )
            assertEquals(
                "条目名 $unsafe 应让整包被拒",
                ZipErrorCodes.BAD_ZIP,
                codeOf { DeepSeekZip.open(file) },
            )
        }
    }

    // ---- 一层公共前缀 ----

    @Test
    fun `唯一顶层目录下的包剥掉一层公共前缀`() {
        val file = writeZip(
            "prefixed.zip",
            listOf(
                "deepseek_data/manifest.json" to manifestJson.toByteArray(),
                "deepseek_data/chat_session_index.json" to indexJson.toByteArray(),
                "deepseek_data/chat_sessions/s1.json" to sessionJson("s1").toByteArray(),
                "deepseek_data/_files/a.png" to byteArrayOf(7),
            ),
        )
        DeepSeekZip.open(file).use { zip ->
            assertEquals(manifestJson, zip.manifestText())
            assertEquals(indexJson, zip.indexText())
            assertEquals(listOf("chat_sessions/s1.json"), zip.sessionEntryNames())
            assertEquals(sessionJson("s1"), zip.sessionText("chat_sessions/s1.json"))
        }
    }

    @Test
    fun `根上有 manifest 时不剥前缀`() {
        val file = standardZip("root.zip")
        DeepSeekZip.open(file).use { zip ->
            assertEquals(manifestJson, zip.manifestText())
        }
    }

    // ---- 限额 ----

    @Test
    fun `条目数超限报 TOO_MANY_ENTRIES`() {
        val file = standardZip("many.zip")
        assertEquals(
            ZipErrorCodes.TOO_MANY_ENTRIES,
            codeOf { DeepSeekZip.open(file, ZipLimits(entries = 2)) },
        )
    }

    @Test
    fun `单条超过 entryBytes 报 ENTRY_TOO_LARGE`() {
        DeepSeekZip.open(standardZip(), ZipLimits(entryBytes = 10L)).use { zip ->
            assertEquals(
                ZipErrorCodes.ENTRY_TOO_LARGE,
                codeOf { zip.sessionText("chat_sessions/s1.json") },
            )
        }
    }

    @Test
    fun `累计读取超过 totalBytes 报 TOO_LARGE`() {
        val limit = sessionJson("s1").toByteArray().size.toLong() + 5L
        DeepSeekZip.open(standardZip(), ZipLimits(totalBytes = limit)).use { zip ->
            assertEquals(sessionJson("s1"), zip.sessionText("chat_sessions/s1.json"))
            assertEquals(
                ZipErrorCodes.TOO_LARGE,
                codeOf { zip.sessionText("chat_sessions/s2.json") },
            )
        }
    }

    @Test
    fun `压缩比超过 maxRatio 报 TOO_LARGE`() {
        val file = writeZip(
            "bomb.zip",
            listOf(
                ZipEntryNames.MANIFEST to manifestJson.toByteArray(),
                "chat_sessions/s1.json" to "A".repeat(64 * 1024).toByteArray(),
            ),
        )
        DeepSeekZip.open(file, ZipLimits(maxRatio = 2L)).use { zip ->
            assertEquals(
                ZipErrorCodes.TOO_LARGE,
                codeOf { zip.sessionText("chat_sessions/s1.json") },
            )
        }
    }

    @Test
    fun `sessionSizes 只读中央目录不解压`() {
        // entryBytes = 0：sizes 仍可读（不 inflate），一旦读正文就必然超限。
        DeepSeekZip.open(standardZip(), ZipLimits(entryBytes = 0L)).use { zip ->
            val sizes = zip.sessionSizes()
            assertEquals(2, sizes.size)
            assertEquals(
                sessionJson("s1").toByteArray().size.toLong(),
                sizes[0].declaredSize,
            )
            assertEquals(
                ZipErrorCodes.ENTRY_TOO_LARGE,
                codeOf { zip.sessionText("chat_sessions/s1.json") },
            )
        }
    }

    // ---- 编码 / CRC ----

    @Test
    fun `GBK 坏字节报 BAD_ENCODING`() {
        val gbk = "会话".toByteArray(charset("GBK"))
        val content = "{\"title\":\"".toByteArray() + gbk + "\"}".toByteArray()
        val file = writeZip(
            "gbk.zip",
            listOf(
                ZipEntryNames.MANIFEST to manifestJson.toByteArray(),
                "chat_sessions/s1.json" to content,
            ),
        )
        DeepSeekZip.open(file).use { zip ->
            assertEquals(
                ZipErrorCodes.BAD_ENCODING,
                codeOf { zip.sessionText("chat_sessions/s1.json") },
            )
        }
    }

    @Test
    fun `UTF-8 BOM 被剥掉`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val file = writeZip(
            "bom.zip",
            listOf(
                ZipEntryNames.MANIFEST to (bom + manifestJson.toByteArray()),
                "chat_sessions/s1.json" to (bom + sessionJson("s1").toByteArray()),
            ),
        )
        DeepSeekZip.open(file).use { zip ->
            assertEquals(manifestJson, zip.manifestText())
            assertEquals(sessionJson("s1"), zip.sessionText("chat_sessions/s1.json"))
        }
    }

    @Test
    fun `CRC 对不上报 CRC_MISMATCH`() {
        val marker = "UNIQUE-CRC-MARKER-XYZZY"
        val content = sessionJson("s1").replace("会话s1", marker).toByteArray()
        val file = writeZip(
            "bad-crc.zip",
            listOf(
                ZipEntryNames.MANIFEST to manifestJson.toByteArray(),
                "chat_sessions/s1.json" to content,
            ),
            stored = true,
        )
        // 直接在 STORED 数据里翻一个字节：大小不变，只有 CRC 会变。
        val bytes = file.readBytes()
        val markerBytes = marker.toByteArray()
        var at = -1
        outer@ for (i in 0..bytes.size - markerBytes.size) {
            for (j in markerBytes.indices) {
                if (bytes[i + j] != markerBytes[j]) continue@outer
            }
            at = i
            break
        }
        assertTrue("fixture 里应能定位到 STORED 内容", at >= 0)
        bytes[at] = (bytes[at].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        DeepSeekZip.open(file).use { zip ->
            assertEquals(
                ZipErrorCodes.CRC_MISMATCH,
                codeOf { zip.sessionText("chat_sessions/s1.json") },
            )
        }
    }

    @Test
    fun `声明大小与实际不符报 CRC_MISMATCH`() {
        val file = standardZip("bad-size.zip")
        // 中央目录结构：+16 CRC、+20 压缩大小、+24 未压缩大小。改 +24 各加 10：
        // CRC 不变、实际字节不变，只有声明大小变了。
        val bytes = file.readBytes()
        for (index in 0..bytes.size - 4) {
            if (isSignature(bytes, index, 0x50, 0x4B, 0x01, 0x02)) {
                val at = index + 24
                if (at + 4 <= bytes.size) {
                    val value = (bytes[at].toInt() and 0xFF) or
                        ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[at + 3].toInt() and 0xFF) shl 24)
                    val patched = value + 10
                    bytes[at] = patched.toByte()
                    bytes[at + 1] = (patched shr 8).toByte()
                    bytes[at + 2] = (patched shr 16).toByte()
                    bytes[at + 3] = (patched shr 24).toByte()
                }
            }
        }
        file.writeBytes(bytes)

        DeepSeekZip.open(file).use { zip ->
            assertEquals(
                ZipErrorCodes.CRC_MISMATCH,
                codeOf { zip.sessionText("chat_sessions/s1.json") },
            )
        }
    }

    @Test
    fun `坏 deflate 数据映射为 ENCRYPTED_OR_DAMAGED`() {
        val file = writeZip(
            "broken-deflate.zip",
            listOf("chat_sessions/s1.json" to "IMPORTED-REPEAT ".repeat(200).toByteArray()),
        )
        // 单条目包的本地文件头在偏移 0；把 deflate 首字节改成 0xFF（BTYPE=3 保留值）必炸。
        val bytes = file.readBytes()
        val nameLength = (bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0xFF) shl 8)
        val extraLength = (bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0xFF) shl 8)
        bytes[30 + nameLength + extraLength] = 0xFF.toByte()
        file.writeBytes(bytes)

        DeepSeekZip.open(file).use { zip ->
            assertEquals(
                ZipErrorCodes.ENCRYPTED_OR_DAMAGED,
                codeOf { zip.sessionText("chat_sessions/s1.json") },
            )
        }
    }

    @Test
    fun `带加密标记的包以平台错误码被拒`() {
        val file = standardZip("encrypted.zip")
        // 只改中央目录的 general purpose flag bit0。JDK 17 在 CEN 解析期就拒（BAD_ZIP）；
        // Android libcore 可能在读取期拒（ENCRYPTED_OR_DAMAGED）。两者都是「不静默读脏」。
        val bytes = file.readBytes()
        for (index in 0..bytes.size - 4) {
            if (isSignature(bytes, index, 0x50, 0x4B, 0x01, 0x02) && index + 10 <= bytes.size) {
                bytes[index + 8] = (bytes[index + 8].toInt() or 0x01).toByte()
            }
        }
        file.writeBytes(bytes)

        val code = codeOf { DeepSeekZip.open(file) }
        assertTrue(
            "加密包应映射到 BAD_ZIP 或 ENCRYPTED_OR_DAMAGED，实际 $code",
            code in setOf(ZipErrorCodes.BAD_ZIP, ZipErrorCodes.ENCRYPTED_OR_DAMAGED),
        )
    }

    // ---- 其它 ----

    @Test
    fun `非白名单或穿越路径的会话名读不到`() {
        DeepSeekZip.open(standardZip()).use { zip ->
            assertEquals(
                ZipErrorCodes.NOT_FOUND,
                codeOf { zip.sessionText("chat_sessions/nope.json") },
            )
            assertEquals(
                ZipErrorCodes.NOT_FOUND,
                codeOf { zip.sessionText("manifest.json") },
            )
            assertEquals(
                ZipErrorCodes.NOT_FOUND,
                codeOf { zip.sessionText("chat_sessions/../s1.json") },
            )
        }
    }

    @Test
    fun `close 后文件句柄释放可以删除文件`() {
        val file = standardZip("closable.zip")
        val zip = DeepSeekZip.open(file)
        zip.close()
        zip.close() // 幂等，不抛
        assertTrue("close 后应能删除文件（无 fd 泄漏）", file.delete())
    }
}
