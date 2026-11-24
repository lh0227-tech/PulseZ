package cc.pulsez.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 录音参数/时长换算/临时文件清理（ 语音基建）。
 *
 * 这里只测纯函数与文件 IO：真正的 `AudioRecord` 录音必须真机/模拟器验证，
 * 离线单测跑不了（见的「未真机」项）。
 *
 * （16-bit PCM WAV、≤ 8 MiB、≤ 120 s）、（16000/MONO/PCM16）、
 *（自限 60 秒）。
 */
class VoiceRecorderLimitsTest {

    @Test
    fun `录音参数与官方参考实现一致`() {
        assertEquals("ASR 只收 16k 单声道 PCM16（官方 demo 同值）", 16_000, VoiceRecorderConfig.SAMPLE_RATE_HZ)
        assertEquals(1, VoiceRecorderConfig.CHANNELS)
        assertEquals(16, VoiceRecorderConfig.BITS_PER_SAMPLE)
        assertEquals(2, VoiceRecorderConfig.BYTES_PER_SAMPLE)
    }

    @Test
    fun `时限常量与产品口径一致`() {
        assertEquals("自限 60 秒", 60_000L, VoiceRecorderConfig.MAX_RECORD_MS)
        assertEquals("API 硬上限 120 秒", 120_000L, VoiceRecorderConfig.HARD_MAX_RECORD_MS)
        assertEquals("最短 500 ms", 500L, VoiceRecorderConfig.MIN_RECORD_MS)
        assertEquals("单次上传上限 8 MiB", 8L * 1024L * 1024L, VoiceRecorderConfig.MAX_FILE_BYTES)
    }

    @Test
    fun `时长换算成 PCM 字节数`() {
        assertEquals(0L, VoiceRecorderLimits.bytesForDuration(0L))
        assertEquals(0L, VoiceRecorderLimits.bytesForDuration(-5L))
        assertEquals(16_000L, VoiceRecorderLimits.bytesForDuration(500L))
        assertEquals(32_000L, VoiceRecorderLimits.bytesForDuration(1_000L))
        assertEquals(1_920_000L, VoiceRecorderLimits.bytesForDuration(60_000L))
        assertEquals(3_840_000L, VoiceRecorderLimits.bytesForDuration(120_000L))
    }

    @Test
    fun `样本数换算成时长向下取整`() {
        assertEquals(0L, VoiceRecorderLimits.durationMsForShorts(0L))
        assertEquals(0L, VoiceRecorderLimits.durationMsForShorts(-1L))
        assertEquals(62L, VoiceRecorderLimits.durationMsForShorts(1_000L))
        assertEquals(500L, VoiceRecorderLimits.durationMsForShorts(8_000L))
        assertEquals(1_000L, VoiceRecorderLimits.durationMsForShorts(16_000L))
        assertEquals(60_000L, VoiceRecorderLimits.durationMsForShorts(960_000L))
    }

    @Test
    fun `最短时长的边界`() {
        assertTrue(VoiceRecorderLimits.isTooShort(499L))
        assertFalse("500 ms 本身是有效录音", VoiceRecorderLimits.isTooShort(500L))
        assertFalse(VoiceRecorderLimits.isTooShort(1_000L))
    }

    @Test
    fun `硬上限与自限的边界`() {
        assertFalse(VoiceRecorderLimits.exceedsHardLimit(120_000L))
        assertTrue(VoiceRecorderLimits.exceedsHardLimit(120_001L))

        assertFalse(VoiceRecorderLimits.reachedAutoStop(59_999L))
        assertTrue("到 60 秒就自动停", VoiceRecorderLimits.reachedAutoStop(60_000L))
    }

    @Test
    fun `临时文件在 cacheDir 的 voice 子目录且命名可识别`() {
        val cache = Files.createTempDirectory("voice-cache").toFile()
        try {
            val file = VoiceRecorderFiles.newFile(cache, nowMs = 1_700_000_000_000L)
            assertEquals("voice", file.parentFile?.name)
            assertEquals("voice-1700000000000.wav", file.name)
            assertTrue(VoiceRecorderFiles.ensureDirectory(cache))
            assertTrue(VoiceRecorderFiles.directory(cache).isDirectory)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun `清理只删超龄的普通文件`() {
        val dir = Files.createTempDirectory("voice-cleanup").toFile()
        try {
            val now = 1_700_000_000_000L
            val old = File(dir, "voice-1.wav").apply { writeText("old"); setLastModified(now - 2 * 60 * 60 * 1000L) }
            val fresh = File(dir, "voice-2.wav").apply { writeText("fresh"); setLastModified(now) }
            val nested = File(dir, "keep-dir").apply { mkdirs() }
            File(nested, "voice-3.wav").apply { writeText("nested"); setLastModified(now - 48 * 60 * 60 * 1000L) }

            val deleted = VoiceRecorderFiles.cleanupStale(dir, nowMs = now)

            assertEquals(1, deleted)
            assertFalse("超龄文件要删掉（隐私：临时音频不长期留缓存）", old.exists())
            assertTrue("新鲜文件不能删", fresh.exists())
            assertTrue("目录不递归删除", nested.isDirectory)
            assertTrue("嵌套目录里的文件不动", File(nested, "voice-3.wav").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `清理不存在的目录返回 0 且不抛`() {
        val missing = File(Files.createTempDirectory("voice-missing").toFile(), "nope")
        assertEquals(0, VoiceRecorderFiles.cleanupStale(missing))
    }

    @Test
    fun `deleteQuietly 对不存在文件也不抛`() {
        val file = File(Files.createTempDirectory("voice-delete").toFile(), "not-there.wav")
        VoiceRecorderFiles.deleteQuietly(file)
        VoiceRecorderFiles.deleteQuietly(null)
        assertFalse(file.exists())
    }
}
