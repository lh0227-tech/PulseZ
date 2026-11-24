package cc.pulsez.app.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出写入流与结果矩阵。
 *
 * 纯 JVM：只测注入缝（`jsonProvider`/`open`/`payloadProvider`/`ioDispatcher`），不碰 Room。
 * 重点锁三件容易写反的事：
 * 1. **顺序**：先 `jsonProvider` 后 `open`（否则数据读取失败会在 SAF 文档写空/半截）；
 * 2. **线程**：`open`/`write`/payload 构建都在注入的 `ioDispatcher` 上，不在测试主线程（K1/）；
 * 3. **失败语义**：DataFailed / WriteFailed 分清；取消必须重抛、不吞成 DataFailed。
 */
class ExportWriteResultTest {

    private class RecordingStream(
        private val writeFailure: IOException? = null,
    ) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var writeThreadName: String? = null
            private set
        var closeCalled = false
            private set

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (writeThreadName == null) writeThreadName = Thread.currentThread().name
            writeFailure?.let { throw it }
            bytes.write(b, off, len)
        }

        override fun close() {
            closeCalled = true
        }
    }

    private fun payload(
        json: String = "{\"schema\":3}",
        byteSize: Int = json.toByteArray(Charsets.UTF_8).size,
        characters: Int = 0,
        threads: Int = 0,
        messages: Int = 0,
        skipped: Int = 0,
    ) = ExportPayload(
        json = json,
        byteSize = byteSize,
        characterCount = characters,
        threadCount = threads,
        messageCount = messages,
        skippedOrphanThreads = skipped,
    )

    private fun namedDispatcher(): Pair<java.util.concurrent.ExecutorService, kotlinx.coroutines.CoroutineDispatcher> {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "export-io-test") }
        return executor to executor.asCoroutineDispatcher()
    }

    // ---------------------------------------------------------------- writeJsonTo 失败矩阵

    @Test
    fun `jsonProvider 抛错 返回 DataFailed 且一次都没 open`() = runBlocking {
        var openCount = 0
        val result = ExportService.writeJsonTo(
            open = {
                openCount += 1
                RecordingStream()
            },
            jsonProvider = { throw IllegalStateException("读数据失败") },
        )
        assertEquals(ExportResult.DataFailed, result)
        assertEquals("数据读取失败时不得打开 SAF 目标流（否则留空/半截文件）", 0, openCount)
    }

    @Test
    fun `open 返回 null 或抛错 返回 WriteFailed`() = runBlocking {
        assertEquals(
            ExportResult.WriteFailed,
            ExportService.writeJsonTo(open = { null }, jsonProvider = { "{}" }),
        )
        assertEquals(
            ExportResult.WriteFailed,
            ExportService.writeJsonTo(
                open = { throw IOException("打不开输出流") },
                jsonProvider = { "{}" },
            ),
        )
    }

    @Test
    fun `write 抛错 返回 WriteFailed`() = runBlocking {
        val stream = RecordingStream(writeFailure = IOException("写一半失败"))
        assertEquals(
            ExportResult.WriteFailed,
            ExportService.writeJsonTo(open = { stream }, jsonProvider = { "{\"schema\":3}" }),
        )
    }

    @Test
    fun `成功 写入字节等于 json 的 UTF-8 字节 且结果为空超限零孤儿`() = runBlocking {
        val json = "{\"schema\":3,\"app\":\"pulsez\",\"note\":\"阿淮\"}"
        val stream = RecordingStream()
        val result = ExportService.writeJsonTo(open = { stream }, jsonProvider = { json })
        assertEquals(ExportResult.Success(emptyList(), 0), result)
        assertTrue(
            "写入字节必须逐字节等于 json.toByteArray(UTF_8)",
            stream.bytes.toByteArray().contentEquals(json.toByteArray(Charsets.UTF_8)),
        )
        assertTrue("成功后必须关闭流（use）", stream.closeCalled)
    }

    // ---------------------------------------------------------------- 线程（K1 / ）

    @Test
    fun `open 与 write 都在注入的 ioDispatcher 上 不在测试主线程`() = runBlocking {
        val (executor, dispatcher) = namedDispatcher()
        try {
            val mainThread = Thread.currentThread().name
            var openThread: String? = null
            val stream = RecordingStream()
            val result = ExportService.writeJsonTo(
                open = {
                    openThread = Thread.currentThread().name
                    stream
                },
                jsonProvider = { "{\"schema\":3}" },
                ioDispatcher = dispatcher,
            )
            assertEquals(ExportResult.Success(emptyList(), 0), result)
            assertTrue(
                "open 必须在注入的 IO 线程（实际 ${openThread}）",
                openThread?.startsWith("export-io-test") == true,
            )
            assertTrue(
                "write 必须在注入的 IO 线程（实际 ${stream.writeThreadName}）",
                stream.writeThreadName?.startsWith("export-io-test") == true,
            )
            assertTrue("open 不得在测试主线程（$mainThread）", openThread != mainThread)
            assertTrue("write 不得在测试主线程（$mainThread）", stream.writeThreadName != mainThread)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `exportWith 的 payload 构建也在注入的 ioDispatcher 上`() = runBlocking {
        val (executor, dispatcher) = namedDispatcher()
        try {
            val mainThread = Thread.currentThread().name
            var payloadThread: String? = null
            var openThread: String? = null
            val stream = RecordingStream()
            val result = ExportService.exportWith(
                payloadProvider = {
                    payloadThread = Thread.currentThread().name
                    payload()
                },
                open = {
                    openThread = Thread.currentThread().name
                    stream
                },
                ioDispatcher = dispatcher,
            )
            assertEquals(ExportResult.Success(emptyList(), 0), result)
            assertTrue(
                "payload 构建必须在注入的 IO 线程（实际 $payloadThread）",
                payloadThread?.startsWith("export-io-test") == true,
            )
            assertTrue(
                "open 必须在注入的 IO 线程（实际 $openThread）",
                openThread?.startsWith("export-io-test") == true,
            )
            assertTrue(payloadThread != mainThread)
        } finally {
            executor.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- exportWith 结果

    @Test
    fun `payloadProvider 抛错 返回 DataFailed 且不 open`() = runBlocking {
        var openCount = 0
        val result = ExportService.exportWith(
            payloadProvider = { throw IllegalStateException("读数据失败") },
            open = {
                openCount += 1
                RecordingStream()
            },
        )
        assertEquals(ExportResult.DataFailed, result)
        assertEquals(0, openCount)
    }

    @Test
    fun `超限 payload 成功写出完整 JSON 并把孤儿跳过数上抛`() = runBlocking {
        val json = "{\"schema\":3,\"skippedOrphanThreads\":2}"
        val stream = RecordingStream()
        val result = ExportService.exportWith(
            payloadProvider = { payload(json = json, byteSize = FILE_MAX_BYTES_INT + 1, skipped = 2) },
            open = { stream },
        )
        assertEquals(ExportResult.Success(listOf(ExportOverLimit.BYTES), 2), result)
        assertTrue(
            "超限也要完整写出、不截断用户数据",
            stream.bytes.toByteArray().contentEquals(json.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `取消异常必须重抛 不被吞成 DataFailed`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                ExportService.writeJsonTo(
                    open = { RecordingStream() },
                    jsonProvider = { throw CancellationException("取消") },
                )
            }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                ExportService.exportWith(
                    payloadProvider = { throw CancellationException("取消") },
                    open = { RecordingStream() },
                )
            }
        }
    }

    // ---------------------------------------------------------------- overLimitReasons 边界与顺序

    @Test
    fun `overLimitReasons 边界等于不报 加一才报`() {
        assertEquals(emptyList<ExportOverLimit>(), ExportService.overLimitReasons(payload(byteSize = 0)))
        assertEquals(
            emptyList<ExportOverLimit>(),
            ExportService.overLimitReasons(payload(byteSize = FILE_MAX_BYTES_INT)),
        )
        assertEquals(
            listOf(ExportOverLimit.BYTES),
            ExportService.overLimitReasons(payload(byteSize = FILE_MAX_BYTES_INT + 1)),
        )
        assertEquals(
            emptyList<ExportOverLimit>(),
            ExportService.overLimitReasons(payload(messages = MAX_MESSAGES)),
        )
        assertEquals(
            listOf(ExportOverLimit.MESSAGES),
            ExportService.overLimitReasons(payload(messages = MAX_MESSAGES + 1)),
        )
        assertEquals(
            emptyList<ExportOverLimit>(),
            ExportService.overLimitReasons(payload(threads = MAX_THREADS)),
        )
        assertEquals(
            listOf(ExportOverLimit.THREADS),
            ExportService.overLimitReasons(payload(threads = MAX_THREADS + 1)),
        )
        assertEquals(
            emptyList<ExportOverLimit>(),
            ExportService.overLimitReasons(payload(characters = MAX_CHARACTERS)),
        )
        assertEquals(
            listOf(ExportOverLimit.CHARACTERS),
            ExportService.overLimitReasons(payload(characters = MAX_CHARACTERS + 1)),
        )
    }

    @Test
    fun `overLimitReasons 四类同时超限 按固定优先级报`() {
        val reasons = ExportService.overLimitReasons(
            payload(
                byteSize = FILE_MAX_BYTES_INT + 1,
                messages = MAX_MESSAGES + 1,
                threads = MAX_THREADS + 1,
                characters = MAX_CHARACTERS + 1,
            ),
        )
        assertEquals(
            listOf(
                ExportOverLimit.BYTES,
                ExportOverLimit.MESSAGES,
                ExportOverLimit.THREADS,
                ExportOverLimit.CHARACTERS,
            ),
            reasons,
        )
    }

    private companion object {
        /** `FILE_MAX_BYTES` 在 payload 里是 Int（16 MiB 不会溢出）。 */
        val FILE_MAX_BYTES_INT: Int = cc.pulsez.app.importer.BackupParser.FILE_MAX_BYTES.toInt()
        val MAX_MESSAGES: Int = cc.pulsez.app.importer.BackupParser.MAX_MESSAGES
        val MAX_THREADS: Int = cc.pulsez.app.importer.BackupParser.MAX_THREADS
        val MAX_CHARACTERS: Int = cc.pulsez.app.importer.BackupParser.MAX_CHARACTERS
    }
}
