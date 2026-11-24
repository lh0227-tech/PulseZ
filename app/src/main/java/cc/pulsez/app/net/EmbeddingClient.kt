package cc.pulsez.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * 向量服务客户端。
 *
 * 与聊天服务**分开配置**：DeepSeek、Moonshot 都没有 embeddings，用户常常要另配一家。
 *
 * 规则：
 * - 批大小 16；
 * - 单条超 3000 字符先截断（口径；把所有 chunk 塞爆对检索没有帮助）；
 * - 429 退避 2s 重试 2 次；其余失败直接抛 [ApiException]（浮层负责"跳过这条、不影响聊天"）；
 * - 直接返回 float 数组，量化（int8 + scale）由 mem 层负责（02 数据模型）。
 */
class EmbeddingClient(
    private val http: OkHttpClient,
    baseUrl: String,
    apiKey: String,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) {

    private val apiKey: String = apiKey.trim()

    /** 用户配置的归一化 base（缓存键，不随 /v1 解析改变）。*/
    private val configuredBaseUrl: String = BaseUrl.normalize(baseUrl)

    @Volatile
    private var resolvedBaseUrl: String = BaseUrl.initial(baseUrl)

    val baseUrl: String get() = resolvedBaseUrl

    /**
     * 批量向量化。返回顺序与 [texts] 一一对应。
     *
     * @param model 服务端的 embedding 模型名，例如 `BAAI/bge-m3`；空串则不传 model 字段。
     * @throws ApiException 任一子批最终失败（含重试后仍 429 的情况）。
     */
    suspend fun embed(texts: List<String>, model: String): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        if (apiKey.isEmpty()) throw ApiException(ApiError.NoKey())
        if (resolvedBaseUrl.isEmpty()) throw ApiException(ApiError.BadResponse(raw = "base url is blank"))

        val prepared = texts.map { it.truncateForEmbedding() }
        val result = ArrayList<FloatArray>(prepared.size)
        val size = batchSize.coerceAtLeast(1)
        prepared.chunked(size).forEach { batch ->
            result += requestBatch(batch, model)
        }
        return result
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun requestBatch(batch: List<String>, model: String): List<FloatArray> =
        withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                try {
                    return@withContext executeBatch(batch, model)
                } catch (e: ApiException) {
                    val rateLimited = e.error.kind == ApiError.Kind.QUOTA && e.error.httpCode == 429
                    if (!rateLimited || attempt >= MAX_RATE_LIMIT_RETRIES) throw e
                    attempt++
                    // 429 退避 2s 重试 2 次。
                    delay(retryDelayMs)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList()
        }

    private fun executeBatch(batch: List<String>, model: String): List<FloatArray> {
        var urlBase = resolvedBaseUrl
        var v1Tried = false
        while (true) {
            val request = try {
                JsonHttp.post(
                    url = BaseUrl.endpoint(urlBase, "embeddings"),
                    apiKey = apiKey,
                    body = buildEmbeddingPayload(model, batch),
                )
            } catch (e: IllegalArgumentException) {
                throw ApiException(ApiError.BadResponse(raw = "invalid base url"))
            }
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw ApiException(ApiErrorMapper.fromThrowable(e))
            }
            val text = try {
                response.body?.string()
            } finally {
                runCatching { response.close() }
            }
            if (!response.isSuccessful) {
                val error = ApiErrorMapper.fromHttp(response.code, text)
                // 404 且 base 不以 /v1 结尾：补 /v1 重试一次。
                if (response.code == 404 && !BaseUrl.isV1(urlBase) && !v1Tried) {
                    v1Tried = true
                    urlBase = BaseUrl.withV1(urlBase)
                    continue
                }
                throw ApiException(error)
            }
            // 404 补 /v1 成功后：实例内记住，并写入进程级缓存（下次新建 client 直接用）。
            rememberResolvedBase(urlBase)
            return parseEmbeddings(text, batch.size, response.code)
        }
        @Suppress("UNREACHABLE_CODE")
        emptyList<FloatArray>()
    }

    private fun rememberResolvedBase(working: String) {
        if (working == resolvedBaseUrl) return
        resolvedBaseUrl = working
        BaseUrl.remember(configuredBaseUrl, working)
    }

    private fun parseEmbeddings(text: String?, expectedSize: Int, httpCode: Int): List<FloatArray> {
        val obj = JsonHttp.parseObject(text)
            ?: throw ApiException(ApiError.BadResponse(httpCode, "invalid embedding response"))
        if (obj["error"] != null) {
            throw ApiException(ApiError.BadResponse(httpCode, messageFromErrorBody(text)))
        }
        val data = obj["data"]?.asArrayOrNull()
            ?: throw ApiException(ApiError.BadResponse(httpCode, "missing embedding data"))
        if (data.size != expectedSize) {
            throw ApiException(ApiError.BadResponse(httpCode, "embedding count does not match request"))
        }

        val vectors = arrayOfNulls<FloatArray>(expectedSize)
        var responseDimension: Int? = null
        data.forEachIndexed { fallbackIndex, element ->
            val item = element as? JsonObject ?: return@forEachIndexed
            // Keep the established OpenAI-compatible fallback for providers that omit index.
            val index = item.intOrNull("index") ?: fallbackIndex
            if (index !in vectors.indices || vectors[index] != null) return@forEachIndexed
            val raw = item["embedding"]?.asArrayOrNull() ?: return@forEachIndexed
            if (raw.isEmpty()) return@forEachIndexed
            // use the provider's dimension, but reject inconsistent dimensions within one batch.
            if (responseDimension == null) {
                responseDimension = raw.size
            } else if (responseDimension != raw.size) {
                throw ApiException(ApiError.BadResponse(httpCode, "inconsistent embedding dimensions"))
            }
            val vector = FloatArray(raw.size) { position ->
                val component = raw[position] as? JsonPrimitive
                val value = component
                    ?.takeUnless { it.isString }
                    ?.content
                    ?.toFloatOrNull()
                if (value == null || !value.isFinite()) {
                    throw ApiException(
                        ApiError.BadResponse(httpCode, raw = "invalid embedding component at index $index:$position"),
                    )
                }
                value
            }
            // Zero vectors cannot produce a meaningful similarity score; VectorMath.cosine returns 0 for them.
            if (vector.all { it == 0f }) {
                throw ApiException(ApiError.BadResponse(httpCode, raw = "zero embedding at index $index"))
            }
            vectors[index] = vector
        }
        return vectors.mapIndexed { index, vector ->
            vector ?: throw ApiException(
                ApiError.BadResponse(httpCode, raw = "missing embedding at index $index"),
            )
        }
    }

    private fun String.truncateForEmbedding(): String {
        if (length <= MAX_TEXT_CHARS) return this
        var end = MAX_TEXT_CHARS
        // 不要从代理对中间切开，否则会生成一个孤立的高位代理字符。
        if (this[end - 1].isHighSurrogate()) end--
        return substring(0, end)
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 16
        const val DEFAULT_RETRY_DELAY_MS: Long = 2_000L
        const val MAX_TEXT_CHARS = 3_000
        const val MAX_RATE_LIMIT_RETRIES = 2
    }
}
