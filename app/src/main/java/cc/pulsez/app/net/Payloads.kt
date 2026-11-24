@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package cc.pulsez.app.net

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 请求体构造。集中在这里，保证 LlmClient / ModelProbe / EmbeddingClient 对
 * `temperature` / `stream_options` / `response_format` 的处理完全一致。
 *
 * 约定：temperature / topP 为 null 时**不传字段**（强制思考的模型不传）。
 *
 * DeepSeek 思考 / 联网搜索：
 * - `thinking` 只有调用方显式给了布尔值才发。**不传 ≠ 关**：服务端默认思考开，
 *   所以"关"必须发 `{"type":"disabled"}`。
 * - `reasoning_effort` 只在思考开着时发（关思考时同时发会和 `thinking.type` 语义打架，
 *   官方虽以 `thinking` 优先，但少一个歧义 第 2 条）。
 * - `webSearch` 为 true 时声明服务端工具 `deepseek_web_search`。
 */
internal fun buildChatPayload(
    request: ChatRequest,
    useJsonMode: Boolean,
    includeStreamOptions: Boolean,
): String = buildJsonObject {
    put("model", request.model)
    putJsonArray("messages") {
        request.messages.forEach { message ->
            addJsonObject {
                put("role", message.role)
                // v0.5.0 识图（依据 「additive parts」）：
                // - parts 为空 → 旧行为，content 仍是字符串（字节不变，第三方端点零影响）；
                // - parts 非空 → content 序列化成数组，**原样**按 parts 顺序输出。
                //   本层不做「正文 → text 块」的补全：那属于 prompt 层策略（纯图占位不能发给模型、
                //   只有当前轮 / 最近 1 轮带图），统一由 `ChatEngine.buildOutgoingMessages` 决定
                //   （阻断 修复，ChatEngine.kt` 里有理由说明）。
                //   所以生产形态是 `[{"type":"text","text":用户问题},{"type":"image_url",...}]`；
                //   纯图消息 parts 里只有 image，不会发出「[图片]」。
                // 官方格式：
                //   {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,<...>"}}
                // `detail:"low"`：官方会先把图缩到 512×512（更快更省），v0.5 默认走 low
                // （默认 low，先不默认 high）。
                // 单图 base64 ≤32 MiB、请求体 ≤48 MiB 是官方上限，
                // 前置校验在发送前由 media 层的体积口径（单图/合计）先挡，不靠服务端 413。
                if (message.parts.isEmpty()) {
                    put("content", message.content)
                } else {
                    putJsonArray("content") {
                        message.parts.forEach { part ->
                            when (part) {
                                is ContentPart.Text -> addJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                }
                                is ContentPart.Image -> addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:image/jpeg;base64," + part.base64Jpeg)
                                        put("detail", "low")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    put("stream", request.stream)
    request.temperature?.let { put("temperature", it) }
    request.topP?.let { put("top_p", it) }
    put("max_tokens", request.maxTokens)
    if (useJsonMode) {
        putJsonObject("response_format") { put("type", "json_object") }
    }
    if (request.stream && includeStreamOptions) {
        putJsonObject("stream_options") { put("include_usage", true) }
    }
    request.thinking?.let { enabled ->
        putJsonObject("thinking") { put("type", if (enabled) "enabled" else "disabled") }
        if (enabled) put("reasoning_effort", request.reasoningEffort)
    }
    if (request.webSearch) {
        putJsonArray("tools") { addJsonObject { put("type", "deepseek_web_search") } }
    }
}.toString()

/**
 * `POST {embed_base}/embeddings`。
 *
 * 不传 `dimensions`，用服务默认维度（少一类兼容问题）。
 */
internal fun buildEmbeddingPayload(model: String?, inputs: List<String>): String = buildJsonObject {
    model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
    putJsonArray("input") { inputs.forEach { add(it) } }
    put("encoding_format", "float")
}.toString()
