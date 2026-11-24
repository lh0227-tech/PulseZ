package cc.pulsez.app.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 填完密钥后的三步能力探测：
 *
 * 1. `GET /models`，8s 超时 → 模型列表（可选能力，失败不阻断）；
 * 2. `POST /chat/completions`，`max_tokens=1`、普通文本「在吗」→ 聊天是否可用；
 * 3. `POST /embeddings`，8 个字符 → 是否有向量能力（DeepSeek / Moonshot 通常没有）。
 *
 * **失败不抛异常**（只有协程取消会向上传播）：chat 与 `/models` 的失败在 chatOk=false 时
 * 归入 [ProbeResult.error]，chat 成功时不再报告 models 的失败；找旧事的失败只体现为
 * [ProbeResult.embeddingsOk] = false（缺失是正常情况，不算错误）。
 * models 与 embeddings 的探测失败不阻断耗时更重要的 chat 结论。
 *
 * 第 2/3 步可分别用 [probe] 的 `probeChat` / `probeEmbeddings` 关掉：聊天「测一下」与
 * 「测一下找旧事」各只探自己的端点，不再互相顺带发请求/等超时（依据：
 * ）。**默认两步都开**，未传开关的既有调用点行为不变。
 *
 * 说明：`/v1/models` 按 OpenAI 规范不返回上下文长度，也探测不到价格；
 * 窗口与单价一律查内置表（优化提示）。
 */
class ModelProbe(
    private val http: OkHttpClient,
    /**
     * 端点是否 DeepSeek 官方（默认走统一 host 判定，见 [isDeepSeekEndpoint]；测试可注入）。
     *
     * 能力探测的 `max_tokens=1` 在 DeepSeek 默认"思考开、effort=high"下会被思考全吃光，
     * 正文为空 → 把"能聊"误判成"不能聊"。所以探测请求在 DeepSeek 端点必须显式关思考。

     */
    private val deepSeekCheck: (String) -> Boolean = ::isDeepSeekEndpoint,
) {

    data class ProbeResult(
        val models: List<String>,
        /** 聊天是否可用；本次没有探测聊天（`probeChat=false`）时为 false。 */
        val chatOk: Boolean,
        /** 找旧事是否可用；本次没有探测找旧事（`probeEmbeddings=false`）时为 false。 */
        val embeddingsOk: Boolean,
        val error: ApiError?,
        /**
         * `GET /models` 返回的每模型能力位（`input_modalities`，v0.5.0 识图门禁用）。
         *
         * 官方 `List Models` 对 `deepseek-flash` 返回 `["text","image"]`、对 `deepseek-v4-pro`
         * 只返回 `["text"]`（依据 https://api-docs.deepseek.com/api/list-models）。
         * 探测失败/服务端不返回该字段时为空 map，门禁回退**模型 id 规则**（[VisionCapability]），
         * **绝不回退 host**。
         */
        val modelModalities: Map<String, Set<String>> = emptyMap(),
    )

    /**
     * @param model 聊天试探用的模型；不传则从 /models 里挑一个非 embedding 的（没有就取第一个）。
     * @param embeddingModel 向量试探用的模型；不传则不带 model 字段让服务端用默认。
     * @param probeChat 是否探测聊天端点（默认开，保持既有调用点行为）；关掉时连 `/models` 也一起跳过，
     *   因为模型列表只用于挑聊天模型 / 识图能力位，找旧事不需要。
     * @param probeEmbeddings 是否探测找旧事端点（默认开）。
     */
    suspend fun probe(
        baseUrl: String,
        apiKey: String,
        model: String? = null,
        embeddingModel: String? = null,
        probeChat: Boolean = true,
        probeEmbeddings: Boolean = true,
    ): ProbeResult = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            return@withContext ProbeResult(emptyList(), false, false, ApiError.NoKey())
        }
        var base = BaseUrl.initial(baseUrl)
        val configuredBase = BaseUrl.normalize(baseUrl)
        if (base.isEmpty()) {
            return@withContext ProbeResult(emptyList(), false, false, ApiError.BadResponse(raw = "base url is blank"))
        }

        val modelsClient = clientWithTimeout(MODELS_TIMEOUT_MS)
        val probeClient = clientWithTimeout(CHAT_TIMEOUT_MS)

        var models: List<String> = emptyList()
        var modelModalities: Map<String, Set<String>> = emptyMap()
        var modelsError: ApiError? = null
        var chatOk = false
        var chatError: ApiError? = null
        var embeddingsOk = false

        // ---- 1. GET /models（8s；失败只影响模型列表，不阻断后续） ----
        // 只有聊天探测需要：挑默认模型 + 拿 `input_modalities`（识图门禁）。
        // 找旧事探测的模型名由调用方显式给，不需要模型列表 → 跳过，省一次请求。
        if (probeChat) {
            try {
                val outcome = executeWithV1(modelsClient, base) { candidate ->
                    JsonHttp.get(BaseUrl.endpoint(candidate, "models"), key)
                }
                base = outcome.base
                val result = outcome.result
                if (result.code in 200..299) {
                    val parsed = parseModels(result.body)
                    models = parsed.map { it.id }
                    modelModalities = parsed.associate { it.id to it.modalities }
                } else {
                    modelsError = ApiErrorMapper.fromHttp(result.code, result.body)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                modelsError = e.error
            } catch (t: Throwable) {
                modelsError = ApiErrorMapper.fromThrowable(t)
            }
        }

        // ---- 2. 1 token 的 chat 试探（只有聊天测试需要） ----
        if (probeChat) {
            val chosenModel = model?.trim()?.takeIf { it.isNotEmpty() }
                ?: models.firstOrNull { !it.contains("embed", ignoreCase = true) }
                ?: models.firstOrNull()
            val chatRequest = ChatRequest(
                model = chosenModel.orEmpty(),
                messages = listOf(ChatMessage(role = "user", content = "在吗")),
                temperature = null,
                topP = null,
                maxTokens = 1,
                stream = false,
                jsonMode = false,
                includeUsage = false,
                // 内部请求显式关思考：探测只用 1 个输出 token，不能给思考留预算。
                thinking = internalThinkingFor(baseUrl, deepSeekCheck),
            )
            try {
                val outcome = executeWithV1(probeClient, base) { candidate ->
                    JsonHttp.post(
                        url = BaseUrl.endpoint(candidate, "chat/completions"),
                        apiKey = key,
                        body = buildChatPayload(chatRequest, useJsonMode = false, includeStreamOptions = false),
                    )
                }
                base = outcome.base
                val result = outcome.result
                if (result.code in 200..299) {
                    chatOk = isValidChatResponse(result.body)
                    if (!chatOk) chatError = ApiError.BadResponse(result.code, errorSnippet(result.body))
                } else {
                    chatError = ApiErrorMapper.fromHttp(result.code, result.body)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                chatError = e.error
            } catch (t: Throwable) {
                chatError = ApiErrorMapper.fromThrowable(t)
            }
        }

        // ---- 3. embeddings 8 字符试探（缺失是正常情况，不产生 error） ----
        // 只有找旧事测试需要；聊天测试不探。
        if (probeEmbeddings) {
            try {
                val outcome = executeWithV1(probeClient, base) { candidate ->
                    JsonHttp.post(
                        url = BaseUrl.endpoint(candidate, "embeddings"),
                        apiKey = key,
                        body = buildEmbeddingPayload(embeddingModel, listOf(PROBE_EMBED_TEXT)),
                    )
                }
                base = outcome.base
                val result = outcome.result
                embeddingsOk = result.code in 200..299 && hasEmbeddingVector(result.body)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                embeddingsOk = false
            }
        }

        // 探测成功后把真正可用的 base 记进进程级缓存（「成功后记住」）。
        if (probeChat || probeEmbeddings) BaseUrl.remember(configuredBase, base)
        val error = if (chatOk) null else chatError ?: modelsError
        ProbeResult(
            models = models,
            chatOk = chatOk,
            embeddingsOk = embeddingsOk,
            error = error,
            modelModalities = modelModalities,
        )
    }

    // ------------------------------------------------------------------ 内部

    private data class ProbeHttp(val code: Int, val body: String?)

    private data class ProbeOutcome(val base: String, val result: ProbeHttp)

    /**
     * 先按传入 base 请求；404 且 base 不以 /v1 结尾时补 /v1 重试一次。
     * 成功后把真正可用的 base 带回去，后续步骤复用。
     */
    private fun executeWithV1(
        client: OkHttpClient,
        base: String,
        requestFor: (String) -> Request,
    ): ProbeOutcome {
        val first = execute(client, requestFor(base))
        if (first.code != 404 || BaseUrl.isV1(base)) return ProbeOutcome(base, first)
        val candidate = BaseUrl.withV1(base)
        val retried = execute(client, requestFor(candidate))
        return ProbeOutcome(if (retried.code in 200..299) candidate else base, retried)
    }

    /** 同步执行；IOException 统一转成 [ApiException]，由调用方归类。 */
    private fun execute(client: OkHttpClient, request: Request): ProbeHttp {
        val call = client.newCall(request)
        val response = try {
            call.execute()
        } catch (e: IOException) {
            throw ApiException(ApiErrorMapper.fromThrowable(e))
        }
        val body = try {
            response.body?.string()
        } finally {
            runCatching { response.close() }
        }
        return ProbeHttp(response.code, body)
    }

    private fun clientWithTimeout(timeoutMs: Long): OkHttpClient =
        http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    /** `/models` 的一条：id + `input_modalities`（v0.5.0 识图门禁；老服务商没有该字段就是空集）。*/
    private data class ProbeModel(val id: String, val modalities: Set<String>)

    private fun parseModels(body: String?): List<ProbeModel> {
        if (body.isNullOrBlank()) return emptyList()
        val root = runCatching { JsonHttp.json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"]?.asArrayOrNull()
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val modalities = obj["input_modalities"]?.asArrayOrNull()
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.lowercase() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
            ProbeModel(id = id, modalities = modalities)
        }
    }

    private fun isValidChatResponse(body: String?): Boolean {
        val obj = JsonHttp.parseObject(body) ?: return false
        if (obj["error"] != null) return false
        val choices = obj["choices"]?.asArrayOrNull() ?: return false
        if (choices.isEmpty()) return false
        val first = choices.firstOrNull() as? JsonObject ?: return false
        return first["message"] is JsonObject || first["text"] != null
    }

    private fun hasEmbeddingVector(body: String?): Boolean {
        val obj = JsonHttp.parseObject(body) ?: return false
        if (obj["error"] != null) return false
        val data = obj["data"]?.asArrayOrNull() ?: return false
        val first = data.firstOrNull() as? JsonObject ?: return false
        return (first["embedding"]?.asArrayOrNull()?.isNotEmpty() == true)
    }

    private companion object {
        const val MODELS_TIMEOUT_MS: Long = 8_000L
        const val CHAT_TIMEOUT_MS: Long = 20_000L
        const val PROBE_EMBED_TEXT = "在吗在吗在吗在吗" // 8 个字符
    }
}

/**
 * 识图能力门禁（v0.5.0 ` §3.3；`）。
 *
 * 判定优先级（**绝不回退 host**）：
 * 1. 端点必须是 DeepSeek 官方（[isDeepSeekEndpoint]），否则入口隐藏——第三方端点对
 *    `image_url` 内容块的行为未核实，且产品口径是"这些功能只对 DeepSeek 的地址有效"
 *    ；
 * 2. `/models` 给了 `input_modalities` 且包含 `image` → 能看图；只含 `text` → 不能；
 * 3. 探测失败 / 无缓存 / 模型不在列表 → 回退**模型 id 规则**（[byModelId]）。
 *
 * 依据：官方 `GET /models` 返回 `input_modalities`（` #6；
 * https://api-docs.deepseek.com/api/list-models）；`deepseek-v4-pro` 只有 `["text"]`、
 * `deepseek-flash` 为 `["text","image"]`。
 *
 * 纯 Kotlin、无 Android 依赖：可直接 JVM 单测（`VisionCapabilityTest`）。
 */
object VisionCapability {

    /** `input_modalities` 里代表图片的取值（官方字段值）。 */
    const val MODALITY_IMAGE: String = "image"

    /**
     * 模型 id 回退规则：`deepseek-flash` 系列能看图（v0.5.0 唯一官方支持 vision 的模型）。
     *
     * 刻意**不**用"包含 deepseek 就允许"：`deepseek-v4-pro` / `deepseek-chat` 都不支持，
     * 按包含匹配会把不能看图的模型放进来（"假开关"）。
     * 依据「图片支持的模型：只有 deepseek-flash」。
     */
    fun byModelId(model: String): Boolean {
        val key = model.trim().lowercase()
        if (key.isEmpty()) return false
        return key == "deepseek-flash" ||
            key.startsWith("deepseek-flash-") ||
            key.startsWith("deepseek-v4-flash") ||
            key.startsWith("deepseek-flash.")
    }

    /**
     * 从 `/models` 的能力位判断某个模型是否支持看图。
     *
     * 匹配规则：先精确（大小写不敏感）；再允许"配置了带版本后缀的别名、服务端只给基础 id"
     * 的前缀匹配（如配置 `deepseek-flash`、列表给 `deepseek-flash-2026xx`）。
     *
     * @return null = 列表里查不到该模型（调用方回退 [byModelId]）
     */
    fun supportsVision(model: String, modalities: Map<String, Set<String>>): Boolean? {
        val key = model.trim()
        if (key.isEmpty()) return null
        val entry = modalities.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?: modalities.entries.firstOrNull { key.startsWith(it.key, ignoreCase = true) }
            ?: return null
        return entry.value.any { it.equals(MODALITY_IMAGE, ignoreCase = true) }
    }

    /**
     * 最终门禁：能不能给这个模型看图。
     *
     * @param deepSeekProvider 端点是否 DeepSeek 官方（`Settings.isDeepSeekProvider()`）
     * @param model 当前聊天模型（`Settings.chatModel`）
     * @param probedModel `/models` 探测时用的模型（设置缓存里的 `visionProbeModel`；没探过为空串）
     * @param probedSupported 缓存的能力结论（null = 没探过/探测失败）
     */
    fun isAvailable(
        deepSeekProvider: Boolean,
        model: String,
        probedModel: String = "",
        probedSupported: Boolean? = null,
    ): Boolean {
        if (!deepSeekProvider) return false
        // 缓存的结论只对"探测时那个模型"有效；用户换了模型就回退 id 规则，不拿旧结论冒充。
        if (probedSupported != null && probedModel.trim().equals(model.trim(), ignoreCase = true)) {
            return probedSupported
        }
        return byModelId(model)
    }
}
