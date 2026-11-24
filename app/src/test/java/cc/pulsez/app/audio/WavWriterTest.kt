package cc.pulsez.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 44 字节 RIFF/WAVE 头（ 语音基建， /）。
 *
 * 逐字段锁死，防止「改了一处偏移量导致 ASR 收到坏文件」：
 * 字段布局与官方参考实现 `chat-demo/src/services/speech/recorder.ts` 的 `encodeWav`
 * 完全一致（RIFF / WAVE / fmt / data；小端；PCM=1；单声道；16-bit；byteRate=samplerate×2）。
 */
class WavWriterTest {

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    @Test
    fun `头永远是 44 字节`() {
        assertEquals(44, WavWriter.HEADER_SIZE)
        assertEquals(44, WavWriter.header(0L).size)
        assertEquals(44, WavWriter.header(1_920_000L).size)
    }

    @Test
    fun `四个 FOURCC 在正确偏移`() {
        val header = WavWriter.header(1_024L)
        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals("data", ascii(header, 36, 4))
    }

    @Test
    fun `fmt 块字段符合 PCM 16-bit 单声道`() {
        val header = WavWriter.header(dataBytes = 1_024L, sampleRateHz = 16_000)
        assertEquals("fmt 块长度固定 16", 16L, u32(header, 16))
        assertEquals("audioFormat=1 表示 PCM", 1, u16(header, 20))
        assertEquals("单声道", 1, u16(header, 22))
        assertEquals("采样率", 16_000L, u32(header, 24))
        assertEquals("每秒字节数 = 16000 × 2", 32_000L, u32(header, 28))
        assertEquals("每帧字节数 = 单声道 × 16-bit", 2, u16(header, 32))
        assertEquals("位深", 16, u16(header, 34))
    }

    @Test
    fun `长度字段是 RIFF fileSize 与 data 字节数`() {
        val header = WavWriter.header(dataBytes = 640_000L)
        // RIFF 的 fileSize = 36 + data（不含 "RIFF" 4 字节与自身 4 字节）。
        assertEquals(36L + 640_000L, u32(header, 4))
        assertEquals(640_000L, u32(header, 40))
    }

    @Test
    fun `字段用小端序`() {
        // 16000 = 0x00003E80 → 小端字节 80 3E 00 00。
        val header = WavWriter.header(0L, 16_000)
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0x3E, 0x00, 0x00),
            header.copyOfRange(24, 28),
        )
        // data 长度 1 = 01 00 00 00。
        assertArrayEquals(
            byteArrayOf(0x01, 0x00, 0x00, 0x00),
            WavWriter.header(1L).copyOfRange(40, 44),
        )
    }

    @Test
    fun `自定义采样率会同步 byteRate 与采样率字段`() {
        val header = WavWriter.header(dataBytes = 0L, sampleRateHz = 8_000)
        assertEquals(8_000L, u32(header, 24))
        assertEquals(16_000L, u32(header, 28))
    }

    @Test
    fun `空数据也生成合法头`() {
        val header = WavWriter.header(0L)
        assertEquals(36L, u32(header, 4))
        assertEquals(0L, u32(header, 40))
    }

    @Test
    fun `60 秒录音的数据量在 32 位长度内`() {
        val dataBytes = VoiceRecorderLimits.bytesForDuration(VoiceRecorderConfig.MAX_RECORD_MS)
        assertEquals(1_920_000L, dataBytes)
        assertEquals(1_920_036L, u32(WavWriter.header(dataBytes), 4))
    }

    @Test
    fun `非法参数抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { WavWriter.header(-1L) }
        assertThrows(IllegalArgumentException::class.java) { WavWriter.header(0L, 0) }
        assertThrows(IllegalArgumentException::class.java) { WavWriter.header(0L, -16_000) }
        // 超过无符号 32 位 RIFF 长度：必须拒绝，不能悄悄截断。
        assertThrows(IllegalArgumentException::class.java) {
            WavWriter.header(0xFFFF_FFFFL)
        }
    }

    @Test
    fun `pcm 封装是头加数据且长度一致`() {
        val pcm = ByteArray(32_000) { (it % 251).toByte() }
        val wav = WavWriter.pcm16MonoToWav(pcm)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals(pcm.size.toLong(), u32(wav, 40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        assertArrayEquals(WavWriter.header(pcm.size.toLong()), wav.copyOfRange(0, 44))
    }

    @Test
    fun `pcm 封装拒绝奇数字节`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavWriter.pcm16MonoToWav(ByteArray(3))
        }
    }

    @Test
    fun `byteRate 与 blockAlign 帮助函数`() {
        assertEquals(32_000, WavWriter.byteRate())
        assertEquals(16_000, WavWriter.byteRate(8_000))
        assertEquals(2, WavWriter.blockAlign())
    }
}
