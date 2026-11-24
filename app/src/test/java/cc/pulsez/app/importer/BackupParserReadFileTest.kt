package cc.pulsez.app.importer

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `BackupParser.readFile` 的有界读取行为。
 *
 * 纯 JVM。核心纪律：`OpenableColumns.SIZE` **只用于提前拒绝**，缺失/谎报都不放宽实际字节上限；
 * 读到上限 + 1 即 TooLarge（不读完）；不关闭调用方的流。
 */
class BackupParserReadFileTest {

    /** 只吐出 [total] 字节的计数流；[failAt] ≥ 0 时在该偏移抛 IOException。 */
    private class CountingStream(
        private val total: Int,
        private val failAt: Int = -1,
        private val throwOnRead: Boolean = false,
    ) : InputStream() {
        var bytesRead = 0L
            private set
        var closed = false
            private set

        override fun read(): Int {
            if (throwOnRead) throw IOException("不该读")
            if (bytesRead >= total) return -1
            if (failAt >= 0 && bytesRead >= failAt) throw IOException("模拟读失败")
            bytesRead += 1
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (throwOnRead) throw IOException("不该读")
            if (bytesRead >= total) return -1
            if (failAt >= 0 && bytesRead >= failAt) throw IOException("模拟读失败")
            val n = minOf(len.toLong(), total - bytesRead).toInt()
            java.util.Arrays.fill(b, off, off + n, 0)
            bytesRead += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `SIZE 超上限 直接 TooLarge 且不读一个字节`() {
        val stream = CountingStream(total = 10, throwOnRead = true)
        assertEquals(
            BoundedReadResult.TooLarge,
            BackupParser.readFile(BackupParser.FILE_MAX_BYTES + 1, stream),
        )
        assertEquals("声明超限时不应读取流", 0L, stream.bytesRead)
    }

    @Test
    fun `SIZE 缺失 实际超限 读到上限加一块即 TooLarge`() {
        val stream = CountingStream(total = BackupParser.FILE_MAX_BYTES.toInt() + 1)
        assertEquals(BoundedReadResult.TooLarge, BackupParser.readFile(null, stream))
        // 最多读到「上限 + 一个 64 KiB 块」就停，不能把整个流读完。
        assertTrue(
            "实际读取 ${stream.bytesRead} 超出上限 + 块大小",
            stream.bytesRead <= BackupParser.FILE_MAX_BYTES + 64 * 1024,
        )
    }

    @Test
    fun `SIZE 谎报为 1 但实际超限 仍 TooLarge`() {
        val stream = CountingStream(total = BackupParser.FILE_MAX_BYTES.toInt() + 10)
        assertEquals(BoundedReadResult.TooLarge, BackupParser.readFile(1L, stream))
    }

    @Test
    fun `恰好上限 Ok 且字节数精确`() {
        val bytes = ByteArray(BackupParser.FILE_MAX_BYTES.toInt())
        // 内存：16 MiB 输入 + 16 MiB 结果（的边界用例，允许）。
        val result = BackupParser.readFile(BackupParser.FILE_MAX_BYTES, ByteArrayInputStream(bytes))
        val ok = result as? BoundedReadResult.Ok
        requireNotNull(ok) { "恰好上限应 Ok：$result" }
        assertEquals(BackupParser.FILE_MAX_BYTES.toInt(), ok.bytes.size)
    }

    @Test
    fun `空流 SIZE null 或 0 都 Ok 空`() {
        listOf(null, 0L).forEach { size ->
            val ok = BackupParser.readFile(size, ByteArrayInputStream(ByteArray(0))) as? BoundedReadResult.Ok
            requireNotNull(ok) { "空流应 Ok（size=$size）" }
            assertEquals(0, ok.bytes.size)
        }
    }

    @Test
    fun `流抛 IOException 返回 IoError 且不关闭调用方的流`() {
        val stream = CountingStream(total = Int.MAX_VALUE, failAt = 10)
        val result = BackupParser.readFile(null, stream)
        assertTrue("应 IoError：$result", result is BoundedReadResult.IoError)
        assertFalse("readFile 不得关闭调用方的流（由调用方 use）", stream.closed)
    }
}
