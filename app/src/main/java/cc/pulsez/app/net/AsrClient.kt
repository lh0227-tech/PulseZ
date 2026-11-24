package cc.pulsez.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DeepSeek 语音识别（ASR）客户端（v0.5.1 基建层）。
 *
 * ## 端点与形态（按官方，不按 OpenAI 直觉）
 * `POST {origin}/v1/tools/asr`，`multipart/form-data`，字段名 **`file`**，返回
 * `{request_id, text, duration_ms}`；**非流式**（一次上传完整音频、返回完整文本）。
 * 接受 16-bit PCM WAV 或 Ogg Opus（按文件内容判断），单文件 ≤ 8 MiB、时长 ≤ 120 秒。
 * 本工程录音器只会产出 16 kHz 单声道 16-bit WAV（见 `audio/VoiceRecorder.kt`）。
 *
 * 依据：
 * - （端点/字段/返回/限制/非流式）、
 *   §3.3.3（不是 OpenAI 的 `/audio/transcriptions`）、§3.5.1（`/v1/tools/...` 带 /v1）；
 * - 官方 ASR 指南：https://api-docs.deepseek.com/zh-cn/guides/asr
 * - 官方参考实现（multipart 字段与路径）：
 *   https://github.com/deepseek-ai/chat-demo/blob/main/src/services/api/speech.ts
 *
 * ## 独立 OkHttp 客户端（不改全局 HttpFactory 的流式语义）
 * 全局 [HttpFactory] 为长回答把 `readTimeout/callTimeout` 置 0、并允许重连。
 * ASR 是**一次短请求 + 可能 8 MiB 上传**，需要自己的超时：
 * - `writeTimeout` 120 s：覆盖慢网下 ≤ 8 MiB 的上传；
 * - `readTimeout` 120 s：服务端整段转写后才返回，中间不发数据；
 * - `callTimeout` 180 s：兜住整个请求（上传 + 转写），避免无限挂起；
 * - `retryOnConnectionFailure(false)`：**不自动重连、不自动重试**——重试会再发一次音频，
 *   可能重复计费；失败由用户手动重按（第 6 条「重连不重发」同一口径）。
 *
 * ## 错误映射（沿用现有 ApiError 体系，文案由 UI 的 ErrorCopy 落地）
 * 401/403 → `ApiError.Auth`；402/429 → `ApiError.Quota`；400/422 → 按错误体分类
 * （上下文超长/内容被拦/其它 → `BadResponse`）；413 → `BadResponse`（httpCode=413，
 * 本地前置拒绝已覆盖「文件 > 8 MiB」，这层是服务端兜底）；5xx（含 500/503/504）→
 * `ApiError.Server`；408/超时/网络异常 → `ApiError.Network`；返回体解析失败 → `BadResponse`。
 *
 * ## 可取消（v0.5.2）
 * 旧实现 `withContext(IO) { execute() }` 是**阻塞**调用，协程取消只能让调用方不再等，
 * HTTP 请求仍在后台跑（callTimeout 180s），音频照样上传、照样计费，与 /
 * 的「取消即停止、删音频」承诺冲突。现在改为
 * `enqueue` + `suspendCancellableCoroutine` + `invokeOnCancellation { call.cancel() }`：
 * 协程取消 → `Call.cancel()` → OkHttp 立刻断开，`onFailure` 里的 `IOException("Canceled")`
 * **不会再被映射成 `ApiError.Network`**（协程已取消时直接返回，由取消路径处理）。
 * 文件头校验/上传准备仍留在 `Dispatchers.IO`（调用方是 `viewModelScope`，不能把磁盘 IO 跑到 Main）。
 *
 * ## 隐私
 * 不安装任何打印正文的拦截器：音频字节、转写文本、密钥都不落日志；
 * [AsrResult.toString] 也刻意只打印文本长度。
 *
 * 构造参数可注入，便于 MockWebServer 测试（`AsrClientTest`）。
 */
class AsrClient(
    http: OkHttpClient,
    baseUrl: String,
    apiKey: String,
) {

    private val apiKey: String = apiKey.trim()

    /** 归一化到 origin；空串 = 用户填的地址无效（调用前会本地拒绝）。 */
    private val origin: String = BaseUrl.origin(baseUrl)

    private val client: OkHttpClient = http.newBuilder()
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 上传一段录音并返回整段转写文本。
     *
     * **不自动重试**：失败抛 [ApiException]（HTTP/网络/解析）或 [AsrRejectedException]
     * （本地前置拒绝，此时一次请求都没发、不产生费用）。调用方需要重试时由用户手动触发。
     *
     * **可取消**（v0.5.2）：协程取消 → `Call.cancel()`；取消导致的 `IOException("Canceled")`
     * 不会被映射成网络错误，而是让协程以 `CancellationException` 结束（调用方据此删音频、
     * 不弹失败）。
     *
     * @param file 录音器产出的 WAV 临时文件
     * @param durationMs 录音实际时长（毫秒）；用于 ≤120 s 前置拒绝
     * @throws AsrRejectedException 本地校验不通过（未发请求）
     * @throws ApiException 网络/HTTP/解析失败
     * @throws kotlinx.coroutines.CancellationException 调用方协程被取消（含 `Call.cancel()`）
     */
    suspend fun transcribe(file: File, durationMs: Long): AsrResult {
        // 文件头校验 / multipart 准备留在 IO 线程（调用方是 viewModelScope，不能把磁盘 IO 跑到 Main）。
        val request = withContext(Dispatchers.IO) {
            if (apiKey.isEmpty()) throw AsrRejectedException(AsrRejectReason.NO_KEY)
            if (origin.isEmpty()) throw AsrRejectedException(AsrRejectReason.BAD_BASE_URL)
            AsrPreflight.rejectionFor(file, durationMs)?.let { throw AsrRejectedException(it) }
            buildRequest(file)
        }
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            // 协程取消 → 真正断掉 HTTP；否则请求会在后台继续上传、继续计费（§7.2 C）。
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 取消后 OkHttp 会用 IOException("Canceled") 回调：协程已取消就直接返回，
                    // 绝不能映射成「没网了」的失败（§7.5 禁止项）。
                    if (!cont.isActive) return
                    cont.resumeWithException(ApiException(ApiErrorMapper.fromThrowable(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isActive) {
                        // 协程已取消：关掉响应体，结果丢弃（不发失败、不复活语音状态）。
                        response.close()
                        return
                    }
                    // 解析/映射都在回调线程完成并用 resume 交回协程，异常不抛到 OkHttp 线程。
                    val result = runCatching {
                        response.use { resp ->
                            val bodyText = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                            if (!resp.isSuccessful) {
                                throw ApiException(ApiErrorMapper.fromHttp(resp.code, bodyText))
                            }
                            parseResult(bodyText, resp.code)
                        }
                    }
                    result.fold(
                        onSuccess = { value -> if (cont.isActive) cont.resume(value) },
                        onFailure = { error -> if (cont.isActive) cont.resumeWithException(error) },
                    )
                }
            })
        }
    }

    /** 组装 multipart 请求（字段/文件名/类型见类注释）。 */
    private fun buildRequest(file: File): Request {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            // 字段名固定 `file`（官方 ASR 唯一字段）、文件名 voice.wav、类型 audio/wav；
            // 服务端按文件内容判断格式，文件名只作调试用。
            .addFormDataPart(
                FORM_FIELD,
                FILE_NAME,
                file.asRequestBody(AUDIO_MIME.toMediaType()),
            )
            .build()

        return Request.Builder()
            .url(endpointUrl(origin))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(multipart)
            .build()
    }

    private fun parseResult(body: String, httpCode: Int): AsrResult {
        val obj = JsonHttp.parseObject(body)
            ?: throw ApiException(ApiError.BadResponse(httpCode, errorSnippet(body)))
        if (obj["error"] != null) {
            throw ApiException(ApiError.BadResponse(httpCode, messageFromErrorBody(body)))
        }
        val text = obj.stringOrNull(TEXT_FIELD)
            ?: throw ApiException(ApiError.BadResponse(httpCode, errorSnippet(body)))
        val durationMs = obj.intOrNull(DURATION_FIELD)?.toLong()?.coerceAtLeast(0L) ?: 0L
        return AsrResult(text = text, durationMs = durationMs)
    }

    companion object {

        /** 官方 ASR 路径（根路径下带 /v1；与 `/chat/completions` 在根不同）。*/
        const val ASR_PATH: String = "/v1/tools/asr"

        /** multipart 字段名（官方唯一字段）。 */
        const val FORM_FIELD: String = "file"

        /** 上传文件名（服务端按内容判断格式，名字只作调试）。 */
        const val FILE_NAME: String = "voice.wav"

        /** 上传内容类型（16-bit PCM WAV）。 */
        const val AUDIO_MIME: String = "audio/wav"

        /** 单文件上限 8 MiB（官方 ASR 限制；音频再长也没用，见时长上限）。 */
        const val MAX_AUDIO_BYTES: Long = 8L * 1024L * 1024L

        /** 时长上限 120 秒（官方 ASR 限制）。 */
        const val MAX_AUDIO_DURATION_MS: Long = 120_000L

        /** 上传超时：覆盖慢网下 ≤ 8 MiB。 */
        const val WRITE_TIMEOUT_SECONDS: Long = 120L

        /** 读取超时：服务端整段转写后才返回。 */
        const val READ_TIMEOUT_SECONDS: Long = 120L

        /** 整请求超时（上传 + 转写）兜底。 */
        const val CALL_TIMEOUT_SECONDS: Long = 180L

        /** 拼完整 ASR 地址：origin + `/v1/tools/asr`（baseUrl 是 `.../v1` 也拼对）。*/
        fun endpointUrl(baseUrl: String): String = BaseUrl.origin(baseUrl) + ASR_PATH

        private const val TEXT_FIELD = "text"
        private const val DURATION_FIELD = "duration_ms"
    }
}

/**
 * 一次成功转写的结果。
 *
 * 刻意覆盖 [toString]：`data class` 默认会把 `text`（用户说的话）打进字符串，
 * 一旦有谁把它写进日志就泄密；这里只留长度（隐私纪律）。
 */
data class AsrResult(
    val text: String,
    val durationMs: Long,
) {
    override fun toString(): String = "AsrResult(textLength=${text.length}, durationMs=$durationMs)"
}

/** 本地前置拒绝的原因（一次请求都没发、不产生费用）。 */
enum class AsrRejectReason {
    /** 没有聊天密钥（门禁应已拦住；这里兜底，不把空 Bearer 发出去）。 */
    NO_KEY,

    /** base url 解析不出 origin（用户填错）。 */
    BAD_BASE_URL,

    /** 录音文件不存在/不是普通文件。 */
    FILE_MISSING,

    /** 文件为空（没录到任何数据）。 */
    FILE_EMPTY,

    /** 文件超过 8 MiB（官方上限）。 */
    FILE_TOO_LARGE,

    /** 时长非法（≤ 0）。 */
    DURATION_INVALID,

    /** 时长超过 120 秒（官方上限）。 */
    DURATION_TOO_LONG,

    /** 不是 WAV（本工程录音器只产出 WAV；Ogg Opus 暂不接，见类注释）。 */
    NOT_WAV,
}

/** 本地前置拒绝：没发请求、不花钱，UI 应按 [reason] 给人话与出路。 */
class AsrRejectedException(val reason: AsrRejectReason) : Exception("asr rejected: ${reason.name}")

/**
 * 上传前的前置校验（纯函数 + 读文件头，可 JVM 单测）。
 *
 * 目的：明显非法/超限的请求不要发出去——省一次网络往返，也避免服务端 400/413 时才暴露。
 * 依据（≤ 8 MiB、≤ 120 秒、只收 WAV/Ogg Opus）。
 */
internal object AsrPreflight {

    /** 返回拒绝原因；null = 通过。 */
    fun rejectionFor(file: File, durationMs: Long): AsrRejectReason? {
        if (!file.isFile) return AsrRejectReason.FILE_MISSING
        if (file.length() <= 0L) return AsrRejectReason.FILE_EMPTY
        if (file.length() > AsrClient.MAX_AUDIO_BYTES) return AsrRejectReason.FILE_TOO_LARGE
        if (durationMs <= 0L) return AsrRejectReason.DURATION_INVALID
        if (durationMs > AsrClient.MAX_AUDIO_DURATION_MS) return AsrRejectReason.DURATION_TOO_LONG
        if (!hasWavHeader(file)) return AsrRejectReason.NOT_WAV
        return null
    }

    /**
     * 按 RIFF 头判断是不是 WAV：前 4 字节 "RIFF"、第 8–11 字节 "WAVE"。
     *
     * 依据：RIFF 结构（"RIFF" FOURCC + fileSize + fileType "WAVE"）
     * https://learn.microsoft.com/en-us/windows/win32/xaudio2/resource-interchange-file-format--riff-
     */
    private fun hasWavHeader(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(12)
            var filled = 0
            while (filled < head.size) {
                val read = input.read(head, filled, head.size - filled)
                if (read <= 0) break
                filled += read
            }
            filled == head.size &&
                head.copyOfRange(0, 4).contentEquals(RIFF) &&
                head.copyOfRange(8, 12).contentEquals(WAVE)
        }
    }.getOrDefault(false)

    private val RIFF: ByteArray = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WAVE: ByteArray = "WAVE".toByteArray(Charsets.US_ASCII)
}
