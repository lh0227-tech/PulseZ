package cc.pulsez.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 生成 44 字节 RIFF/WAVE 头（PCM、16-bit、单声道、给定采样率与数据长度）。
 *
 * ## 为什么要手写
 * DeepSeek ASR 只收 **16-bit PCM WAV 或 Ogg Opus**（按文件内容判断），而 Android 的
 * `MediaRecorder` 默认输出 AAC/AMR/MP4/3GP，不能直接上传。官方参考实现
 * `chat-demo/src/services/speech/recorder.ts` 的做法就是：浏览器采 16 kHz 单声道 PCM，
 * 再手写 44 字节 WAV 头（`encodeWav`）。Android 端等价做法是 `AudioRecord`
 * 采 PCM → 本文件拼头 → multipart 上传。
 *
 * 依据：
 * - （只收 16-bit PCM WAV / Ogg Opus、
 *   8 MiB、120 s、非流式）、§3.3.4（官方 demo 16 kHz 单声道 PCM + 手写 44 字节头；
 *   Android 等价实现 `AudioRecord` → 手写 WAV 头）；
 * - （`AudioRecord`、16000 Hz、MONO、16-bit、手写 44 字节 WAV 头）；
 * - 官方参考实现（MIT）`encodeWav()` 的字段与字节序：
 *   https://github.com/deepseek-ai/chat-demo/blob/main/src/services/speech/recorder.ts
 * - RIFF 结构（"RIFF" 的 fileSize 不含 "RIFF" 与自身 4 字节；chunk 均为小端）：
 *   https://learn.microsoft.com/en-us/windows/win32/xaudio2/resource-interchange-file-format--riff-
 * - 官方 ASR 指南：https://api-docs.deepseek.com/zh-cn/guides/asr
 *
 * 纯函数、不碰 Android、不碰文件：可直接 JVM 单测（`WavWriterTest`）。
 */
object WavWriter {

    /** 标准 44 字节 PCM WAV 头长度（12 字节 RIFF + 24 字节 fmt + 8 字节 data 头）。 */
    const val HEADER_SIZE: Int = 44

    /** ASR 与官方 demo 的采样率：16000 Hz。 */
    const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000

    /** 单声道（ASR 只要求 16-bit PCM WAV，官方 demo 用单声道）。 */
    const val DEFAULT_CHANNELS: Int = 1

    /** 16-bit PCM（样本范围 -32768..32767）。 */
    const val DEFAULT_BITS_PER_SAMPLE: Int = 16

    /** WAVE `fmt ` 块里的 audioFormat：1 = PCM。 */
    private const val PCM_AUDIO_FORMAT: Int = 1

    /** `fmt ` 块长度：PCM 固定 16 字节。 */
    private const val FMT_CHUNK_SIZE: Int = 16

    /** RIFF fileSize 上限：无符号 32 位。 */
    private const val MAX_UINT32: Long = 0xFFFF_FFFFL

    /**
     * 44 字节 WAV 头。
     *
     * @param dataBytes 后面实际要跟的 PCM 数据字节数（= 样本数 × 2）
     * @param sampleRateHz 采样率，默认 16000
     * @throws IllegalArgumentException 采样率非正、数据长度为负，或 RIFF 长度溢出 32 位
     */
    fun header(dataBytes: Long, sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ): ByteArray {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(dataBytes >= 0) { "dataBytes must not be negative" }
        // RIFF 的 fileSize = 36 + dataBytes，必须塞进无符号 32 位。
        require(dataBytes <= MAX_UINT32 - 36) { "audio data is too large for a WAV file" }

        val blockAlign = DEFAULT_CHANNELS * DEFAULT_BITS_PER_SAMPLE / 8
        val byteRate = sampleRateHz * blockAlign

        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(FOURCC_RIFF)
            putInt((36L + dataBytes).toInt()) // fileSize：不含 "RIFF" 与自身
            put(FOURCC_WAVE)
            put(FOURCC_FMT)
            putInt(FMT_CHUNK_SIZE)
            putShort(PCM_AUDIO_FORMAT.toShort()) // PCM
            putShort(DEFAULT_CHANNELS.toShort())
            putInt(sampleRateHz)
            putInt(byteRate) // 每秒字节数
            putShort(blockAlign.toShort()) // 每帧字节数
            putShort(DEFAULT_BITS_PER_SAMPLE.toShort())
            put(FOURCC_DATA)
            putInt(dataBytes.toInt())
        }.array()
    }

    /**
     * 把裸 PCM（小端 16-bit 单声道）封成完整 WAV：44 字节头 + PCM。
     *
     * 录音实现逐块追加写文件时不用这个（会多一次整段拷贝），它只给测试/小数据兜底。
     */
    fun pcm16MonoToWav(pcm: ByteArray, sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ): ByteArray {
        require(pcm.size % 2 == 0) { "16-bit PCM byte count must be even" }
        val header = header(pcm.size.toLong(), sampleRateHz)
        return ByteArray(header.size + pcm.size).also {
            header.copyInto(it, 0)
            pcm.copyInto(it, header.size)
        }
    }

    /** 每秒字节数（16000 Hz × 2 字节）。 */
    fun byteRate(sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ): Int =
        sampleRateHz * (DEFAULT_CHANNELS * DEFAULT_BITS_PER_SAMPLE / 8)

    /** 每帧字节数（单声道 16-bit = 2）。 */
    fun blockAlign(): Int = DEFAULT_CHANNELS * DEFAULT_BITS_PER_SAMPLE / 8

    private val FOURCC_RIFF: ByteArray = "RIFF".toByteArray(Charsets.US_ASCII)
    private val FOURCC_WAVE: ByteArray = "WAVE".toByteArray(Charsets.US_ASCII)
    private val FOURCC_FMT: ByteArray = "fmt ".toByteArray(Charsets.US_ASCII)
    private val FOURCC_DATA: ByteArray = "data".toByteArray(Charsets.US_ASCII)
}
