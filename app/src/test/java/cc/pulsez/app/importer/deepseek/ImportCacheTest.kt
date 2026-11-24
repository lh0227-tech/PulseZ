package cc.pulsez.app.importer.deepseek

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `ImportCache` 纯函数单测：限长拷贝、可用空间、魔数嗅探头、残留清理。
 *
 * `ContentResolver` 胶水层（[ImportCache.copyToCache]）无法纯 JVM 测，按
 * 把逻辑拆到纯函数验证；不依赖 Robolectric。
 */
class ImportCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun codeOf(block: () -> Unit): String =
        assertThrows(ZipImportException::class.java) { block() }.code

    // ---- copyStreamLimited ----

    @Test
    fun `限长拷贝正常返回字节数并完整写出`() {
        val source = ByteArray(100_000) { it.toByte() }
        val output = ByteArrayOutputStream()
        val copied = ImportCache.copyStreamLimited(ByteArrayInputStream(source), output, 1_000_000L)
        assertEquals(100_000L, copied)
        assertTrue(source.contentEquals(output.toByteArray()))
    }

    @Test
    fun `恰好等于上限不报错`() {
        val source = ByteArray(4_096) { 7 }
        val output = ByteArrayOutputStream()
        assertEquals(4_096L, ImportCache.copyStreamLimited(ByteArrayInputStream(source), output, 4_096L))
        assertEquals(4_096, output.size())
    }

    @Test
    fun `超过上限抛 TOO_LARGE 且不写出超出部分`() {
        val source = ByteArray(300_000) { 1 }
        val output = ByteArrayOutputStream()
        assertEquals(
            ZipErrorCodes.TOO_LARGE,
            codeOf { ImportCache.copyStreamLimited(ByteArrayInputStream(source), output, 150_000L) },
        )
        // 先判断再写：输出不会超过上限，且等于最后一次成功写块后的字节数（65536 * 2）。
        assertTrue(output.size() <= 150_000)
        assertEquals(131_072, output.size())
    }

    @Test
    fun `进度回调单调递增且最终等于总字节数`() {
        val source = ByteArray(200_000) { 3 }
        val progress = ArrayList<Long>()
        val copied = ImportCache.copyStreamLimited(
            ByteArrayInputStream(source),
            ByteArrayOutputStream(),
            1_000_000L,
            onProgress = { progress += it },
        )
        assertEquals(200_000L, copied)
        assertEquals(copied, progress.last())
        for (i in 1 until progress.size) {
            assertTrue("进度必须单调递增", progress[i] > progress[i - 1])
        }
    }

    @Test
    fun `空输入返回零字节`() {
        val copied = ImportCache.copyStreamLimited(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), 10L)
        assertEquals(0L, copied)
    }

    // ---- snapshotHead ----

    @Test
    fun `嗅探头默认只读前 64 字节`() {
        val file = File(tempFolder.root, "big.bin")
        file.writeBytes(ByteArray(200) { (it % 251).toByte() })
        val head = ImportCache.snapshotHead(file)
        assertEquals(64, head.size)
        assertTrue(head.contentEquals(file.readBytes().copyOf(64)))
    }

    @Test
    fun `嗅探头对短文件返回全部字节`() {
        val file = File(tempFolder.root, "short.bin")
        file.writeText("PK")
        assertEquals("PK", String(ImportCache.snapshotHead(file)))
    }

    @Test
    fun `嗅探头对空文件和缺失文件返回空数组`() {
        val empty = File(tempFolder.root, "empty.bin")
        empty.writeBytes(ByteArray(0))
        assertEquals(0, ImportCache.snapshotHead(empty).size)
        assertEquals(0, ImportCache.snapshotHead(File(tempFolder.root, "missing.bin")).size)
    }

    @Test
    fun `嗅探头可指定读取上限`() {
        val file = File(tempFolder.root, "head.bin")
        file.writeBytes(ByteArray(10) { it.toByte() })
        assertEquals(3, ImportCache.snapshotHead(file, maxBytes = 3).size)
        assertEquals(0, ImportCache.snapshotHead(file, maxBytes = 0).size)
    }

    // ---- 空间预检 ----

    @Test
    fun `可用空间对正常目录可读`() {
        val usable = ImportCache.usableSpaceOrNull(tempFolder.root)
        assertNotNull(usable)
        assertTrue((usable ?: 0L) > 0L)
    }

    @Test
    fun `可用空间对建不出的目录返回 null`() {
        val parentFile = File(tempFolder.root, "not-a-dir")
        parentFile.writeText("x")
        // 父路径是文件 → mkdirs 必失败。
        assertNull(ImportCache.usableSpaceOrNull(File(parentFile, "child")))
    }

    @Test
    fun `需要空间超过可用时报 LOW_STORAGE`() {
        assertEquals(
            ZipErrorCodes.LOW_STORAGE,
            codeOf { ImportCache.checkSpace(tempFolder.root, Long.MAX_VALUE / 2) },
        )
    }

    @Test
    fun `小需求空间预检通过`() {
        ImportCache.checkSpace(tempFolder.root, 1_024L)
        // 大小未知（0）时只要求目录可写。
        ImportCache.checkSpace(tempFolder.root, 0L)
    }

    @Test
    fun `目录建不出来时报 LOW_STORAGE`() {
        val parentFile = File(tempFolder.root, "blocked")
        parentFile.writeText("x")
        assertEquals(
            ZipErrorCodes.LOW_STORAGE,
            codeOf { ImportCache.checkSpace(File(parentFile, "child"), 1L) },
        )
    }

    // ---- 残留清理 ----

    @Test
    fun `只删 24 小时前的残留文件`() {
        val dir = File(tempFolder.root, "import").apply { mkdirs() }
        val now = 1_700_000_000_000L
        val stale = File(dir, "stale.zip").apply {
            writeText("old")
            setLastModified(now - ImportCache.STALE_MAX_AGE_MS - 1_000L)
        }
        val fresh = File(dir, "fresh.zip").apply {
            writeText("new")
            setLastModified(now - ImportCache.STALE_MAX_AGE_MS + 1_000L)
        }

        assertEquals(1, ImportCache.cleanupStale(dir, now))
        assertFalse("24h 前的残留应删除", stale.exists())
        assertTrue("年轻文件不能删（可能正在导入）", fresh.exists())
    }

    @Test
    fun `清理不碰子目录`() {
        val dir = File(tempFolder.root, "import-sub").apply { mkdirs() }
        val now = 1_700_000_000_000L
        val sub = File(dir, "nested").apply { mkdirs() }
        sub.setLastModified(now - ImportCache.STALE_MAX_AGE_MS - 60_000L)
        assertEquals(0, ImportCache.cleanupStale(dir, now))
        assertTrue(sub.isDirectory)
    }

    @Test
    fun `目录不存在时清理返回零`() {
        assertEquals(0, ImportCache.cleanupStale(File(tempFolder.root, "no-such-dir")))
    }

    @Test
    fun `清理的年龄阈值可注入`() {
        val dir = File(tempFolder.root, "import-custom").apply { mkdirs() }
        val now = 1_700_000_000_000L
        val file = File(dir, "a.zip").apply {
            writeText("x")
            setLastModified(now - 10_000L)
        }
        // 阈值 5 秒：10 秒前的文件应删。
        assertEquals(1, ImportCache.cleanupStale(dir, now, maxAgeMs = 5_000L))
        assertFalse(file.exists())
    }

    // ---- 其它 ----

    @Test
    fun `importDir 固定为 cacheDir 下的 import 子目录`() {
        val cacheDir = File(tempFolder.root, "cache")
        assertEquals(File(cacheDir, "import"), ImportCache.importDir(cacheDir))
        assertEquals("import", ImportCache.IMPORT_DIR_NAME)
    }

    @Test
    fun `deleteQuietly 删除文件且重复调用不抛`() {
        val file = File(tempFolder.root, "tmp.zip").apply { writeText("x") }
        ImportCache.deleteQuietly(file)
        assertFalse(file.exists())
        ImportCache.deleteQuietly(file) // 不抛
    }
}
