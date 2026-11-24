package cc.pulsez.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * 网络失败的分类。
 *
 * 网络层只给分类和服务商原始片段，**不放用户文案**——文案按 kind 在 UI 层映射，
 * 出处是 （错误表格）。raw 可能包含服务商回显的聊天内容，
 * 因此**不要写进日志**，只在需要展示「原始片段」时使用。
 */
sealed class ApiError(
    val kind: Kind,
    val httpCode: Int? = null,
    /** 服务商错误体前 200 字；可能含用户内容，禁止落日志。 */
    val raw: String? = null,
) {
    enum class Kind {
        NO_KEY,
        AUTH,
        QUOTA,
        MODEL_NOT_FOUND,
        CONTEXT_OVERFLOW,
        FILTERED,
        SERVER,
        NETWORK,
        STREAM_BROKEN,
        BAD_RESPONSE,
    }

    /** 本地还没有密钥。06 §5.4「密钥好像不对」的前置情况，不重试。 */
    class NoKey(raw: String? = null) : ApiError(Kind.NO_KEY, raw = raw)

    /** 401 / 403。06 §5.4「密钥好像不对」；不重试，去改密钥。 */
    class Auth(httpCode: Int? = null, raw: String? = null) : ApiError(Kind.AUTH, httpCode, raw)

    /** 402 / 429。06 §5.4「额度用完」；429 可由调用方退避重试。 */
    class Quota(httpCode: Int? = null, raw: String? = null) : ApiError(Kind.QUOTA, httpCode, raw)

    /** 404 + body 含 model。去改模型或列可用模型；不重试。 */
    class ModelNotFound(httpCode: Int? = null, raw: String? = null) : ApiError(Kind.MODEL_NOT_FOUND, httpCode, raw)

    /** 400 + body 含 context / length / token。降预算、交接后重试一次（03 §2.3）。 */
    class ContextOverflow(httpCode: Int? = null, raw: String? = null) : ApiError(Kind.CONTEXT_OVERFLOW, httpCode, raw)

    /** 400 + content filter。06 §5.4「内容被拦」，换说法或换服务商。 */
    class Filtered(httpCode: Int? = null, raw: String? = null) : ApiError(Kind.FILTERED, httpCode, raw)

    /** 5xx。退避重试 2 次。*/
    class Server(httpCode: Int? = null, raw: String? = null) : ApiError(Kind.SERVER, httpCode, raw)

    /** IOException / 首字超时。06 §5.4「没网」「超时」。 */
    class Network(httpCode: Int? = null, raw: String? = null) : ApiError(Kind.NETWORK, httpCode, raw)

    /**
     * 流开始后断线或心跳超时。06 §5.4「回答中断」：保留已生成内容，
     * 让用户选继续或重试，**绝不自动重发**（会重复生成、重复计费）。
     */
    class StreamBroken(httpCode: Int? = null, raw: String? = null) : ApiError(Kind.STREAM_BROKEN, httpCode, raw)

    /** 非 SSE 错误体、解析失败、未归类 4xx。06 §5.4「地址错」或展示原始片段。 */
    class BadResponse(httpCode: Int? = null, raw: String? = null) : ApiError(Kind.BAD_RESPONSE, httpCode, raw)

    override fun toString(): String = buildString {
        append("ApiError(").append(kind)
        httpCode?.let { append(", http=").append(it) }
        raw?.let { append(", raw=").append(it) }
        append(')')
    }
}

/**
 * HTTP 状态码 / 异常 → [ApiError]。
 *
 * 规则严格按
 * 401/403 Auth；402/429 Quota；404+model ModelNotFound；
 * 400+context|length|token ContextOverflow；400+content filter Filtered；
 * 5xx Server；IOException Network；解析失败 BadResponse。
 */
object ApiErrorMapper {

    /** 06 §5.4 展示用「body 只保留前 200 字用于显示」。*/
    private const val MAX_RAW_CHARS = 200

    fun fromHttp(code: Int, body: String?): ApiError {
        val raw = errorSnippet(body)
        return when (code) {
            401, 403 -> ApiError.Auth(code, raw)
            402, 429 -> ApiError.Quota(code, raw)
            404 -> if (mentionsModel(raw)) ApiError.ModelNotFound(code, raw) else ApiError.BadResponse(code, raw)
            400, 422 -> fromBadRequest(code, raw)
            408 -> ApiError.Network(httpCode = code, raw = raw)
            in 500..599 -> ApiError.Server(code, raw)
            else -> ApiError.BadResponse(code, raw)
        }
    }

    fun fromThrowable(t: Throwable): ApiError = when (t) {
        is ApiException -> t.error
        is SocketTimeoutException -> ApiError.Network(raw = errorSnippet(t.message))
        is InterruptedIOException -> ApiError.Network(raw = errorSnippet(t.message))
        is IOException -> ApiError.Network(raw = errorSnippet(t.message))
        is kotlinx.serialization.SerializationException -> ApiError.BadResponse(raw = errorSnippet(t.message))
        is IllegalArgumentException -> ApiError.BadResponse(raw = errorSnippet(t.message))
        else -> ApiError.BadResponse(raw = errorSnippet(t.message))
    }

    private fun fromBadRequest(code: Int, raw: String?): ApiError {
        val text = raw.orEmpty().lowercase()
        return when {
            // 先判上下文，中文服务商常见的 "maximum context length is 8192 tokens" 同时命中 length/token。
            text.contains("context") ||
                text.contains("length") ||
                text.contains("token") ||
                text.contains("too long") ||
                text.contains("maximum") -> ApiError.ContextOverflow(code, raw)

            text.contains("content filter") ||
                text.contains("content_filter") ||
                text.contains("contentfilter") ||
                text.contains("moderation") ||
                text.contains("safety") ||
                text.contains("sensitive") ||
                text.contains("blocked") -> ApiError.Filtered(code, raw)

            else -> ApiError.BadResponse(code, raw)
        }
    }

    private fun mentionsModel(raw: String?): Boolean {
        val text = raw.orEmpty().lowercase()
        return text.contains("model")
    }
}

/**
 * 网络层统一的受检失败载体。失败一律抛这个，不抛 OkHttp / JSON 的原始异常。
 */
class ApiException(val error: ApiError) : Exception(
    buildString {
        append(error.kind.name)
        error.httpCode?.let { append(" (HTTP ").append(it).append(')') }
        error.raw?.let { append(": ").append(it.take(200)) }
    },
)

/** 错误体 / 错误帧的前 200 字。*/
internal fun errorSnippet(body: String?): String? {
    val text = body?.trim().orEmpty()
    return text.take(MAX_ERROR_RAW_CHARS).takeIf { it.isNotEmpty() }
}

private const val MAX_ERROR_RAW_CHARS = 200

/**
 * 从 `{"error":{"message":"..."}}` / `{"message":"..."}` / `event: error` 的 data 里取一句可读的原文。
 * 取不到就退回原始片段；**不猜分类**（「中文 400 错误体 原样显示摘要，别猜」）。
 */
internal fun messageFromErrorBody(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return errorSnippet(body)
    val error = obj["error"]
    val message = when (error) {
        is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
            ?: (error["msg"] as? JsonPrimitive)?.contentOrNull
            ?: error.toString()
        is JsonPrimitive -> error.contentOrNull
        else -> null
    } ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
    return errorSnippet(message) ?: errorSnippet(body)
}
