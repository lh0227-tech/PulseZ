package cc.pulsez.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * 网络层内部共用的 JSON / OkHttp 小工具。
 *
 * 隐私约束：这里**不**安装任何打印正文的拦截器，
 * 错误体只在内存里截前 N 字，绝不写日志。
 */
internal object JsonHttp {

    val MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private const val MAX_ERROR_BODY_CHARS = 4096

    fun post(
        url: String,
        apiKey: String,
        body: String,
        accept: String = "application/json",
    ): Request = builder(url, apiKey, accept).post(body.toRequestBody(MEDIA_TYPE)).build()

    fun get(
        url: String,
        apiKey: String,
        accept: String = "application/json",
    ): Request = builder(url, apiKey, accept).get().build()

    private fun builder(url: String, apiKey: String, accept: String): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", accept)
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return builder
    }

    /**
     * 读错误体（调用会消费并关闭 body）。最多留 [MAX_ERROR_BODY_CHARS]，
     * 之后再经 [errorSnippet] 截到 200 字给 UI。
     */
    fun readErrorBody(response: Response): String? = runCatching {
        response.body?.string()?.take(MAX_ERROR_BODY_CHARS)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun parseObject(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }
}

/** 宽松取字段：服务商可能把数字序列化成字符串，也可能少字段。 */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.intOrNull(key: String): Int? =
    stringOrNull(key)?.trim()?.toDoubleOrNull()?.toInt()

internal fun JsonObject.doubleOrNull(key: String): Double? =
    stringOrNull(key)?.trim()?.toDoubleOrNull()

internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray
