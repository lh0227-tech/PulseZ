package cc.pulsez.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * 语音输入的录音参数与时长上限（v0.5.1 基建层）。
 *
 * 依据：
 * - ASR 只收 16-bit PCM WAV 或 Ogg Opus，
 *   单次上限 8 MiB、120 秒，非流式；
 * - 官方 demo 与 Android 等价实现都用 16000 Hz、单声道、
 *   ENCODING_PCM_16BIT、`AudioRecord`，录完拼 44 字节 WAV 头；
 * - 按住说话建议 60 秒自停（API 硬上限 120 秒，
 *   依据 https://api-docs.deepseek.com/zh-cn/guides/asr）；
 * - 官方参考实现 `MAX_SECONDS = 120`：
 *   https://github.com/deepseek-ai/chat-demo/blob/main/src/services/speech/recorder.ts
 */
object VoiceRecorderConfig {

    /** 采样率（ASR 官方 demo 同值）。 */
    const val SAMPLE_RATE_HZ: Int = WavWriter.DEFAULT_SAMPLE_RATE_HZ

    /** 声道数（单声道）。 */
    const val CHANNELS: Int = WavWriter.DEFAULT_CHANNELS

    /** 位深（16-bit PCM）。 */
    const val BITS_PER_SAMPLE: Int = WavWriter.DEFAULT_BITS_PER_SAMPLE

    /** 每样本字节数。 */
    const val BYTES_PER_SAMPLE: Int = BITS_PER_SAMPLE / 8

    /**
     * 自限 60 秒：到点自动停并转写。
     *
     * 60 秒是产品口径（建议值），比 API 硬上限 120 秒更短：
     * 转写费用按时长计，且一次说 60 秒已经远超普通一句话。
     */
    const val MAX_RECORD_MS: Long = 60_000L

    /** API 硬上限 120 秒；只用于 UI 提示/元数据，录音本身在 [MAX_RECORD_MS] 就停。 */
    const val HARD_MAX_RECORD_MS: Long = 120_000L

    /**
     * 最短有效录音 500 ms。低于此值：丢弃临时文件、不上传、返回明确结果。
     *
     * 口径：太短多半是误触/没说话，上传既浪费一次请求（会按时长计费）也可能拿到空文本。
     */
    const val MIN_RECORD_MS: Long = 500L

    /** 单次上传文件硬上限 8 MiB（与 `net/AsrClient` 的前置拒绝同口径）。*/
    const val MAX_FILE_BYTES: Long = 8L * 1024L * 1024L
}

/**
 * 录音时长/字节数的纯计算（可 JVM 单测，不碰 Android）。
 *
 * 全部按 16000 Hz × 单声道 × 2 字节 = 32000 字节/秒换算。
 */
object VoiceRecorderLimits {

    /** 每毫秒字节数（32000 / 1000 = 32，整除；非整除时向下取整）。 */
    private val BYTES_PER_MS: Long =
        VoiceRecorderConfig.SAMPLE_RATE_HZ.toLong() * VoiceRecorderConfig.BYTES_PER_SAMPLE *
            VoiceRecorderConfig.CHANNELS / 1000L

    /** 给定录制时长（毫秒）对应的 PCM 字节数；非正数返回 0。 */
    fun bytesForDuration(durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return durationMs * BYTES_PER_MS
    }

    /** 给定已录 16-bit 样本数对应的时长（毫秒，向下取整）；非正数返回 0。 */
    fun durationMsForShorts(shorts: Long): Long {
        if (shorts <= 0L) return 0L
        return shorts * 1000L / VoiceRecorderConfig.SAMPLE_RATE_HZ
    }

    /** 是否短于最短有效时长（< 500 ms）。 */
    fun isTooShort(durationMs: Long): Boolean =
        durationMs < VoiceRecorderConfig.MIN_RECORD_MS

    /** 是否超过 API 硬上限（> 120 s；正常录音不会到，只用于日志/元数据校验）。 */
    fun exceedsHardLimit(durationMs: Long): Boolean =
        durationMs > VoiceRecorderConfig.HARD_MAX_RECORD_MS

    /** 是否已达到自限（>= 60 s），录音循环据此自动停。 */
    fun reachedAutoStop(durationMs: Long): Boolean =
        durationMs >= VoiceRecorderConfig.MAX_RECORD_MS
}

/**
 * 录音临时文件管理（纯文件 IO，可 JVM 单测）。
 *
 * 落点：`cacheDir/voice/voice-<毫秒时间戳>.wav`。用 cacheDir 而不是 filesDir：
 * 录音只是「待转写的临时文件」，转写成功/取消都该删；进程被杀后残留也由启动/下次开录清理。
 *
 * 隐私：只保留本机临时文件；上传后是否删除由调用方负责（见 [deleteQuietly]）。
 */
object VoiceRecorderFiles {

    /** 缓存子目录名。 */
    const val DIR_NAME: String = "voice"

    /** 文件名前缀（白名单式清理只动这些文件）。 */
    const val FILE_PREFIX: String = "voice-"

    /** 文件名后缀。 */
    const val FILE_SUFFIX: String = ".wav"

    /** 超过 1 小时没动的录音临时文件视为残留（正常流程用不到 1 小时）。 */
    const val STALE_AFTER_MS: Long = 60L * 60L * 1000L

    /** 录音目录（不保证存在；需要时用 [ensureDirectory]）。 */
    fun directory(cacheDir: File): File = File(cacheDir, DIR_NAME)

    /** 确保录音目录存在；创建失败返回 false（调用方按 IO 错误处理）。 */
    fun ensureDirectory(cacheDir: File): Boolean {
        val dir = directory(cacheDir)
        if (dir.isDirectory) return true
        return runCatching { dir.mkdirs() || dir.isDirectory }.getOrDefault(false)
    }

    /** 新临时文件路径（不负责创建文件本身）。 */
    fun newFile(cacheDir: File, nowMs: Long = System.currentTimeMillis()): File =
        File(directory(cacheDir), "$FILE_PREFIX$nowMs$FILE_SUFFIX")

    /**
     * 清理超过 [maxAgeMs] 没动的录音残留文件；返回删除个数，失败静默。
     *
     * 只删本目录下的普通文件（不递归、不删目录），避免误伤 cacheDir 里的其它东西。
     */
    fun cleanupStale(
        dir: File,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = STALE_AFTER_MS,
    ): Int {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return 0
        var deleted = 0
        files.forEach { file ->
            if (!file.isFile) return@forEach
            val age = nowMs - file.lastModified()
            if (age < maxAgeMs) return@forEach
            if (runCatching { file.delete() }.getOrDefault(false)) deleted++
        }
        return deleted
    }

    /** 删除单个临时文件（不存在/失败都静默；不打印路径）。 */
    fun deleteQuietly(file: File?) {
        runCatching { file?.delete() }
    }
}

/** 录音失败的明确错误码（UI 层按码给文案；网络层不参与）。 */
enum class VoiceRecorderError {
    /** 本机没有麦克风硬件（`PackageManager.FEATURE_MICROPHONE` 为 false）。 */
    MIC_UNAVAILABLE,

    /** 没有 `RECORD_AUDIO` 运行时权限（用户拒绝，或系统已永久拒绝）。 */
    PERMISSION_DENIED,

    /** 初始化失败：`getMinBufferSize` 返回错误，或 `AudioRecord` 构造/状态不对。 */
    INIT_FAILED,

    /** `startRecording()` 失败或状态没有进入 RECORDING。 */
    START_FAILED,

    /** 正在录音时又调 `start()`（状态机应拦，这里兜底）。 */
    ALREADY_RECORDING,

    /** 写缓存文件失败 / 录音过程中读失败（含 `ERROR_DEAD_OBJECT`）。 */
    IO_ERROR,

    /** `stop()` 时既没有在录、也没有待取结果。 */
    NOT_RECORDING,
}

/** `start()` 的结果。 */
sealed interface VoiceRecorderStart {
    /** 已开始录音（录音线程已在后台跑）。 */
    data object Started : VoiceRecorderStart

    /** 没开始；给 UI 明确错误码。 */
    data class Failed(val code: VoiceRecorderError) : VoiceRecorderStart
}

/** `stop()` 的结果。 */
sealed interface VoiceRecorderResult {
    /**
     * 录到了一段有效音频。
     *
     * @param file cacheDir 下的临时 WAV；**转写完成/放弃后由调用方 `deleteQuietly`**。
     * @param durationMs 实际时长（毫秒，按样本数算）。
     * @param autoStopped 是否因为到了 60 秒自限而自动停（UI 可提示「到最长了」）。
     */
    data class Recorded(
        val file: File,
        val durationMs: Long,
        val autoStopped: Boolean,
    ) : VoiceRecorderResult

    /** 短于 500 ms：文件已删、未上传。 */
    data class TooShort(val durationMs: Long) : VoiceRecorderResult

    /** 一个样本都没读到（麦克风被占用/立刻停止）：文件已删、未上传。 */
    data object Empty : VoiceRecorderResult

    /** 录音失败：文件已删。 */
    data class Failed(val code: VoiceRecorderError) : VoiceRecorderResult
}

/** 录音器状态（只暴露 UI 需要的三个状态，不含样本/路径）。 */
enum class VoiceRecorderStatus {
    /** 没在录。 */
    IDLE,

    /** 正在录（`elapsedMs()` 给倒计时用）。 */
    RECORDING,

    /** 已到 60 秒自限并自动停，结果等 `stop()` 取走（UI 看到后应调 `stop()`）。 */
    STOPPED,
}

/** 录音器快照。 */
data class VoiceRecorderSnapshot(
    val status: VoiceRecorderStatus = VoiceRecorderStatus.IDLE,
    val autoStopped: Boolean = false,
)

/**
 * 录音器接口（依赖注入点）。
 *
 * 真实实现见 [AndroidVoiceRecorder]（`AudioRecord`，只能真机/模拟器跑）；状态机
 * [VoiceInputMachine] 只依赖本接口 + [VoiceRecorderResult]，因此纯 JVM 可测。
 *
 * 线程约定：`start()` / `stop()` 是挂起函数（内部 `Dispatchers.IO`，不阻塞主线程）；
 * `cancel()` 可在任意线程（含主线程）调用，内部只做「置停止标志 + 停麦克风 + 删文件」。
 */
interface VoiceRecorder {

    /** 当前状态；UI 用它显示录音中/待取结果。 */
    val state: StateFlow<VoiceRecorderSnapshot>

    /** 已录时长（毫秒）；未在录音时返回 0。给倒计时/波形用，不触发 IO。 */
    fun elapsedMs(): Long

    /** 开始录音（前置检查：麦克风硬件、运行时权限、`getMinBufferSize`）。 */
    suspend fun start(): VoiceRecorderStart

    /** 停止录音并产出结果；若已因 60 秒自限停好，直接取走结果。 */
    suspend fun stop(): VoiceRecorderResult

    /** 立即停止并删除临时文件（离开页面/锁屏/开始发送/用户取消）。幂等。 */
    fun cancel()
}

/**
 * 基于 `AudioRecord` 的真实录音器。
 *
 * ## 参数
 * `MediaRecorder.AudioSource.VOICE_RECOGNITION`、16000 Hz、`CHANNEL_IN_MONO`、
 * `ENCODING_PCM_16BIT`（与 的最小方案一致）。
 *
 * ## 官方 API 依据
 * - `AudioRecord.getMinBufferSize(int, int, int)`：返回最小缓冲区字节数；
 *   返回 `ERROR`(-1) / `ERROR_BAD_VALUE`(-2) 表示参数不支持，必须当初始化失败。
 * - `AudioRecord(int audioSource, int sampleRateInHz, int channelConfig, int audioFormat, int bufferSizeInBytes)`
 *   构造可能抛 `IllegalArgumentException`；权限缺失时可能抛 `SecurityException`（这里在构造前
 *   用 `Context.checkSelfPermission` 先查 `Manifest.permission.RECORD_AUDIO`）。
 * - `getState() == STATE_INITIALIZED`(1)、`getRecordingState() == RECORDSTATE_RECORDING`(3)。
 * - `read(short[], int, int)` 返回读到的样本数；负数是错误码（`ERROR_DEAD_OBJECT` 等）。
 * - `stop()` / `release()`：停止并释放麦克风。
 * 以上签名/常量在本机 Android 36 SDK 的 `android.jar` 上用 `javap` 核对过（2026-11-21）；
 * 官方文档：https://developer.android.com/reference/android/media/AudioRecord
 *
 * ## 文件形态
 * 先写 44 字节占位头，边录边在文件末尾追加 PCM；结束时 `seek(0)` 回填真实 data 长度
 * （与官方 demo「录完一次性拼头」等价，避免在内存里攒整段 PCM）。
 *
 * ## 时限
 * 录音循环数样本，到 60 秒自动停（[VoiceRecorderConfig.MAX_RECORD_MS]）；120 秒只是 API
 * 硬上限，正常不会到。短于 500 ms 丢弃文件并返回 [VoiceRecorderResult.TooShort]。
 */
class AndroidVoiceRecorder(context: Context) : VoiceRecorder {

    private val appContext: Context = context.applicationContext

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()

    private val _state = MutableStateFlow(VoiceRecorderSnapshot())
    override val state: StateFlow<VoiceRecorderSnapshot> = _state.asStateFlow()

    /** 当前代际：cancel/start 会 +1，落后的录音线程收尾时据此丢弃结果。 */
    @Volatile
    private var generation: Long = 0L

    private var job: Job? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var totalShorts: Long = 0L

    @Volatile
    private var startedAtNanos: Long = 0L

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var cancelRequested: Boolean = false

    @Volatile
    private var autoStopped: Boolean = false

    private var outputFile: File? = null

    private var result: VoiceRecorderResult? = null

    override fun elapsedMs(): Long {
        val bySamples = VoiceRecorderLimits.durationMsForShorts(totalShorts)
        if (bySamples > 0L) return bySamples
        val started = startedAtNanos
        if (started == 0L) return 0L
        return ((System.nanoTime() - started) / NANOS_PER_MILLI).coerceAtLeast(0L)
    }

    @SuppressLint("MissingPermission")
    override suspend fun start(): VoiceRecorderStart = withContext(Dispatchers.IO) {
        // 上面这行 SuppressLint：本方法在构造 AudioRecord 前已用
        // `Context.checkSelfPermission(RECORD_AUDIO)` 显式检查并拒绝（见下面的前置检查）；
        // lint 的 MissingPermission 不追踪这种「早退分支」，所以按官方推荐方式标注。
        synchronized(lock) {
            if (_state.value.status != VoiceRecorderStatus.IDLE) {
                return@synchronized VoiceRecorderStart.Failed(VoiceRecorderError.ALREADY_RECORDING)
            }
            if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                return@synchronized VoiceRecorderStart.Failed(VoiceRecorderError.MIC_UNAVAILABLE)
            }
            // `Context.checkSelfPermission` 是 Android 6.0+ 的运行时权限查询入口；
            // 构造 AudioRecord 前先查，避免把「权限缺失」误报成「初始化失败」。
            // 依据：https://developer.android.com/training/permissions/requesting
            if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return@synchronized VoiceRecorderStart.Failed(VoiceRecorderError.PERMISSION_DENIED)
            }
            if (!VoiceRecorderFiles.ensureDirectory(appContext.cacheDir)) {
                return@synchronized VoiceRecorderStart.Failed(VoiceRecorderError.IO_ERROR)
            }
            // 顺手清掉上次进程被杀留下的旧录音（隐私：临时音频不长期留在缓存里）。
            runCatching { VoiceRecorderFiles.cleanupStale(VoiceRecorderFiles.directory(appContext.cacheDir)) }

            val minBufferBytes = AudioRecord.getMinBufferSize(
                VoiceRecorderConfig.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferBytes <= 0) {
                return@synchronized VoiceRecorderStart.Failed(VoiceRecorderError.INIT_FAILED)
            }
            val bufferBytes = maxOf(minBufferBytes, MIN_BUFFER_BYTES)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    VoiceRecorderConfig.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (t: Throwable) {
                // IllegalArgumentException（参数不支持）/ SecurityException（权限被撤）都算初始化失败。
                return@synchronized VoiceRecorderStart.Failed(VoiceRecorderError.INIT_FAILED)
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                return@synchronized VoiceRecorderStart.Failed(VoiceRecorderError.INIT_FAILED)
            }
            try {
                recorder.startRecording()
            } catch (t: Throwable) {
                runCatching { recorder.release() }
                return@synchronized VoiceRecorderStart.Failed(VoiceRecorderError.START_FAILED)
            }
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                return@synchronized VoiceRecorderStart.Failed(VoiceRecorderError.START_FAILED)
            }

            val file = VoiceRecorderFiles.newFile(appContext.cacheDir)
            generation++
            val currentGeneration = generation
            audioRecord = recorder
            outputFile = file
            result = null
            totalShorts = 0L
            startedAtNanos = System.nanoTime()
            stopRequested = false
            cancelRequested = false
            autoStopped = false
            _state.value = VoiceRecorderSnapshot(status = VoiceRecorderStatus.RECORDING, autoStopped = false)
            // LAZY + 先赋值再 start：避免录音线程先跑完、finish() 把 job 置 null 之后
            // 这里又把已完成的 job 赋回去（stop() 会误判「还在录」）。
            val recordingJob = scope.launch(start = CoroutineStart.LAZY) {
                recordLoop(
                    generation = currentGeneration,
                    recorder = recorder,
                    file = file,
                    bufferBytes = bufferBytes,
                )
            }
            job = recordingJob
            recordingJob.start()
            VoiceRecorderStart.Started
        }
    }

    override suspend fun stop(): VoiceRecorderResult = withContext(Dispatchers.IO) {
        // result 与 job 在同一个临界区里成对读取：finish() 也是在同一临界区里把两者一起更新，
        // 避免「读到旧 result=null、又读到新 job=null」的窄竞态把正常收尾误判成 NOT_RECORDING。
        val snapshot = synchronized(lock) { result to job }
        val pending = snapshot.first
        if (pending != null) return@withContext consume(pending)

        val running = snapshot.second
        if (running == null) {
            return@withContext VoiceRecorderResult.Failed(VoiceRecorderError.NOT_RECORDING)
        }
        stopRequested = true
        // 从别的线程调用 AudioRecord.stop() 是官方允许的打断方式：能立刻让阻塞的 read() 返回。
        runCatching { audioRecord?.stop() }
        running.join()
        val outcome = synchronized(lock) { result }
        if (outcome != null) consume(outcome) else VoiceRecorderResult.Failed(VoiceRecorderError.NOT_RECORDING)
    }

    override fun cancel() {
        // 返回 (录音句柄, 待删文件)，避免在 inline lambda 里给 val 赋值。
        val handles = synchronized(lock) {
            generation++ // 让还在收尾的旧录音线程丢弃结果
            cancelRequested = true
            stopRequested = true
            val recordHandle = audioRecord
            val staleFile = outputFile
            outputFile = null
            result = null
            job = null
            totalShorts = 0L
            startedAtNanos = 0L
            autoStopped = false
            _state.value = VoiceRecorderSnapshot()
            recordHandle to staleFile
        }
        // 停麦克风让阻塞的 read() 尽快返回；删文件在 POSIX 上对已打开句柄也立即生效。
        runCatching { handles.first?.stop() }
        VoiceRecorderFiles.deleteQuietly(handles.second)
    }

    // ---------------------------------------------------------------- 录音线程

    private suspend fun recordLoop(
        generation: Long,
        recorder: AudioRecord,
        file: File,
        bufferBytes: Int,
    ) {
        var failure: VoiceRecorderError? = null
        var recordedShorts = 0L
        var randomAccessFile: RandomAccessFile? = null
        try {
            val wavFile = RandomAccessFile(file, "rw")
            randomAccessFile = wavFile
            wavFile.setLength(0)
            // 先占位 44 字节头；结束回填长度（官方 demo 是录完再拼，二者等价）。
            wavFile.write(ByteArray(WavWriter.HEADER_SIZE))

            val buffer = ShortArray((bufferBytes / 2).coerceAtLeast(MIN_BUFFER_SHORTS))
            val pcmBytes = ByteArray(buffer.size * 2)
            while (!stopRequested && !cancelRequested && generation == this.generation) {
                val read = try {
                    recorder.read(buffer, 0, buffer.size)
                } catch (t: Throwable) {
                    -1
                }
                if (read < 0) {
                    failure = VoiceRecorderError.IO_ERROR
                    break
                }
                if (read == 0) continue
                // 16-bit PCM 样本 → 小端字节（WAV 要求小端）。
                for (i in 0 until read) {
                    val sample = buffer[i].toInt()
                    pcmBytes[i * 2] = sample.toByte()
                    pcmBytes[i * 2 + 1] = (sample shr 8).toByte()
                }
                wavFile.write(pcmBytes, 0, read * 2)
                recordedShorts += read
                totalShorts = recordedShorts
                if (VoiceRecorderLimits.reachedAutoStop(
                        VoiceRecorderLimits.durationMsForShorts(recordedShorts),
                    )
                ) {
                    autoStopped = true
                    break
                }
            }
            // 回填真实长度（cancel/新一代录音已经开始时文件马上会被删，没必要再写）。
            if (failure == null && !cancelRequested && generation == this.generation) {
                wavFile.seek(0L)
                wavFile.write(WavWriter.header(recordedShorts * 2L))
                wavFile.setLength(WavWriter.HEADER_SIZE + recordedShorts * 2L)
            }
        } catch (t: Throwable) {
            failure = VoiceRecorderError.IO_ERROR
        } finally {
            runCatching { randomAccessFile?.close() }
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            if (audioRecord === recorder) audioRecord = null
        }
        finish(generation, file, recordedShorts, failure)
    }

    private fun finish(
        generation: Long,
        file: File,
        recordedShorts: Long,
        failure: VoiceRecorderError?,
    ) {
        synchronized(lock) {
            // cancel()/下一次 start() 已推进代际：这次收尾作废（文件由 cancel 负责删）。
            if (generation != this.generation) return
            job = null
            val durationMs = VoiceRecorderLimits.durationMsForShorts(recordedShorts)
            val outcome: VoiceRecorderResult = when {
                cancelRequested -> {
                    VoiceRecorderFiles.deleteQuietly(file)
                    VoiceRecorderResult.Failed(VoiceRecorderError.NOT_RECORDING)
                }
                failure != null -> {
                    VoiceRecorderFiles.deleteQuietly(file)
                    VoiceRecorderResult.Failed(failure)
                }
                recordedShorts <= 0L -> {
                    VoiceRecorderFiles.deleteQuietly(file)
                    VoiceRecorderResult.Empty
                }
                VoiceRecorderLimits.isTooShort(durationMs) -> {
                    VoiceRecorderFiles.deleteQuietly(file)
                    VoiceRecorderResult.TooShort(durationMs)
                }
                else -> VoiceRecorderResult.Recorded(
                    file = file,
                    durationMs = durationMs,
                    autoStopped = autoStopped,
                )
            }
            result = outcome
            totalShorts = 0L
            startedAtNanos = 0L
            // 自限自动停：状态停在 STOPPED，等 UI 调 stop() 取走结果；手动停直接回 IDLE。
            _state.value = if (outcome is VoiceRecorderResult.Recorded && outcome.autoStopped) {
                VoiceRecorderSnapshot(status = VoiceRecorderStatus.STOPPED, autoStopped = true)
            } else {
                VoiceRecorderSnapshot()
            }
        }
    }

    private fun consume(outcome: VoiceRecorderResult): VoiceRecorderResult {
        synchronized(lock) {
            result = null
            outputFile = null
            autoStopped = false
            _state.value = VoiceRecorderSnapshot()
        }
        return outcome
    }

    private companion object {
        private const val NANOS_PER_MILLI: Long = 1_000_000L

        /** 缓冲区下限 4096 字节（≈ 0.128 秒 @16k/16-bit）：小缓冲区会丢样本。 */
        private const val MIN_BUFFER_BYTES: Int = 4_096

        /** 样本缓冲区下限 2048 个 short（与 4096 字节缓冲对应）。 */
        private const val MIN_BUFFER_SHORTS: Int = 2_048
    }
}
