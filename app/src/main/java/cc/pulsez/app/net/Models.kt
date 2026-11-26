package cc.pulsez.app.net

/**
 * 聊天消息里的一段内容（v0.5.0 识图，`）。
 *
 * 为什么是"加法式"：`ChatMessage.content: String` 保留不动，只在有图时才用 [ChatMessage.parts]，
 * 这样所有旧调用点（记忆抽取、群聊、导入整理）与第三方端点的请求体字节不变
 * （依据：` 「additive parts」）。
 */
sealed interface ContentPart {
    /** 普通文本。 */
    data class Text(val text: String) : ContentPart

    /**
     * 一张图片：**已经压缩好的 JPEG** 的 base64（不带 `data:` 前缀，前缀由 [Payloads] 拼）。
     *
     * 只放 JPEG 的原因：v0.5 的图片流水线一律转成 JPEG 0.8（长边 ≤1536），
     * PNG/GIF/WebP 都在本地先转码，网络层不需要关心原始格式。
     * 依据：`（`image_url` + base64 data URL）、
     * ` （透明铺白、动图只取第一帧）。
     */
    data class Image(val base64Jpeg: String) : ContentPart

    /**
     * DeepSeek 文件上传后拿到的文件引用（`{"type":"file","file_id":"..."}`）。
     * DeepSeek API docs: `https://api-docs.deepseek.com/`（Files API / document_parsing）。
     */
    data class File(val fileId: String, val fileName: String? = null) : ContentPart
}

/**
 * 一条对话消息。
 *
 * role 由调用方保证是 `system` / `user` / `assistant`；网络层不校验、不改写内容。
 *
 * v0.5.0（识图）新增 [parts]，默认空 = 旧行为：`content` 是纯字符串时请求体与 v0.4 完全一致。
 * 有 parts 时 [Payloads] 把 `content` 序列化成数组（text + `image_url`），见 `Payloads.kt`。
 * 约定：**assistant 消息永远不带 parts**（官方限制：assistant 里放图片会 400，
 * 依据：` 「唯一 API 不支持的硬边界」）。
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val parts: List<ContentPart> = emptyList(),
    val reasoningContent: String? = null,
    val serverToolCallsJson: String? = null,
)

/**
 * 一条联网搜索引用（`message.annotations[].url_citation`）。
 *
 * v0.2 只做"当轮展示"，不落库：换页/重启后来源会丢（接受，注释见 ChatViewModel）。
 */
data class Citation(
    val url: String,
    val title: String? = null,
    val startIndex: Int? = null,
    val endIndex: Int? = null,
)

/**
 * 一次 `POST {base}/chat/completions` 的请求参数。
 *
 * 默认值来自 与
 * - `temperature` / `topP`：普通模型 0.85 / 0.95；**强制思考的模型传 null**（不传字段）。
 * - `maxTokens`：普通模型 800；思考开时调用方提到 4000（思考内容会吃掉输出预算）。
 * - `streamOptions.include_usage` 并非所有服务商都支持：LlmClient 遇到 400 会自动去掉重发并记住。
 *
 * 思考 / 联网搜索（仅 DeepSeek 官方端点使用，其它服务商保持 null/false）：
 * - [thinking]：`null` = 不传字段（跟随服务端）；`true` = `enabled`；`false` = 显式 `disabled`。
 *   **不传 ≠ 关**：服务端默认思考是开的，所以"关"必须显式发 `disabled`。
 * - [reasoningEffort]：只在 [thinking] 为 `true` 时随请求下发（关思考时不要同时发）。
 *   输入框只做布尔开关，强度档位在设置页（low / high / max，默认 low；
 *   不暴露 medium，因为官方映射里 medium 实际落到 high，做了会骗用户）。
 * - [webSearch]：`true` 时请求体加 `tools:[{"type":"deepseek_web_search"}]`；该工具是 DeepSeek
 *   私有类型，只能对 DeepSeek 端点开。
 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = 1.0,
    val topP: Double? = 1.0,
    val maxTokens: Int = 4_096,
    val stream: Boolean = true,
    val jsonMode: Boolean = false,
    val includeUsage: Boolean = true,
    val thinking: Boolean? = null,
    val reasoningEffort: String = "low",
    val webSearch: Boolean = false,
    val readUrl: Boolean = false,
)

/**
 * 一次响应读到的用量。
 *
 * DeepSeek 风格会拆出 [cacheHitTokens] / [cacheMissTokens]（计费）；
 * 没有拆分字段时两者为 null，调用方把 promptTokens 全部按未命中价算（保守）。
 */
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheHitTokens: Int? = null,
    val cacheMissTokens: Int? = null,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

/**
 * 流式对话事件。网络层只做解析，不负责持久化与 UI。
 *
 * 约定：
 * - 一次 [LlmClient.stream] 正常结束**恰好**发一个 [Finished] 或 [Failed]。
 * - [Reasoning] 是思考增量（`delta.reasoning_content`），**不进正文**；UI 折叠展示、不落库。
 * - [Finished.citations] 是联网搜索引用（`annotations[].url_citation`），v0.2 只当轮展示。
 * - 断线绝不自动重发上一条，只发 [Failed] + [ApiError.StreamBroken]（第 6 条）。
 */
sealed interface LlmEvent {
    data class Delta(val text: String) : LlmEvent
    data class Reasoning(val text: String) : LlmEvent
    data class ToolProgress(
        val kind: String,
        val status: String,
        val queries: List<String> = emptyList(),
        val url: String? = null,
        val sourceCount: Int = 0,
    ) : LlmEvent
    data class Finished(
        val usage: Usage?,
        val truncated: Boolean,
        val citations: List<Citation> = emptyList(),
        /**
         * 这次请求带了联网搜索工具（`tools`），但服务端以 400 拒绝，客户端已自动去掉 tools
         * 重试并成功（降级）。UI 据此给一句轻提示：「这次没能联网，先按普通回答」。
         * 依据（带 tools 时历史必须回传 reasoning_content，否则 400）、§3.4、§5.3。
         */
        val webSearchDropped: Boolean = false,
        val reasoningContent: String? = null,
        val serverToolCallsJson: String? = null,
    ) : LlmEvent

    data class Failed(val error: ApiError) : LlmEvent
}
