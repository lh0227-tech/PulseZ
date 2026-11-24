package cc.pulsez.app.importer.deepseek

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * DeepSeek 导出 v3 的 DTO 与解析结果契约。
 *
 * 依据：
 * - `（ZIP 结构、会话与消息字段）
 * - `（fragment 类型与消息状态）
 * - `（字段默认值、Json 配置）、§4.3（映射表与输出结构）
 *
 * 三条硬约束：
 * 1. 字段全部可选或带默认值：缺字段不能让整段会话解析失败；
 * 2. 未知字段一律忽略：DeepSeek 以后加字段不应该影响导入；
 * 3. 不开启 lenient：坏 JSON 必须明确失败（[DeepSeekParseException]），不猜。
 */
internal val deepSeekJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

/**
 * `manifest.json`。
 *
 * `export_format_version` 保留为 [JsonElement]：官方目前给数字 `3`，但同一语义给字符串 `"3"`
 * 也能接受（§4.2）；最终必须等于 [ManifestVersion.SUPPORTED]。
 */
@Serializable
data class DeepSeekManifestDto(
    @SerialName("export_format_version") val exportFormatVersion: JsonElement? = null,
)

/**
 * `chat_session_index.json` 里的一行（会话索引）。
 *
 * 只保留下游实际用到的三个字段：Chatbox 与 DSH 两个官方实现都只校验这三项。
 */
@Serializable
data class SessionIndexRowDto(
    val id: String? = null,
    @SerialName("inserted_at") val insertedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * `chat_sessions/<id>.json`：一段会话 + 完整消息树（`parent_id` 连成树，支持重新生成/编辑产生的分支）。
 *
 * 字段名严格按 `没有出现在这里的一律忽略（`ignoreUnknownKeys`）。
 */
@Serializable
data class DeepSeekSessionDto(
    val id: String? = null,
    val title: String? = null,
    val pinned: Boolean = false,
    @SerialName("current_message_id") val currentMessageId: Long? = null,
    @SerialName("inserted_at") val insertedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val messages: List<DeepSeekMessageDto> = emptyList(),
)

/**
 * 一段会话里的一条消息。
 *
 * @property id 会话内自增；子节点 id 恒大于父节点
 * @property parentId 根为 null
 * @property role `USER` / `ASSISTANT`；文本方向以 fragment 类型为准（§4.3 映射表）
 * @property status `FINISHED` / `INCOMPLETE` / `WIP` 可保留，其它整条丢弃（§4.3）
 */
@Serializable
data class DeepSeekMessageDto(
    val id: Long? = null,
    @SerialName("parent_id") val parentId: Long? = null,
    val role: String? = null,
    val status: String = "FINISHED",
    @SerialName("inserted_at") val insertedAt: String? = null,
    val fragments: List<DeepSeekFragmentDto> = emptyList(),
)

/**
 * 消息内容片段。类型语义见 `映射表见 `。
 *
 * `elapsed_secs` / `files` 只做契约占位：pulsez 不持久化思考内容与图片，解析时丢弃。
 */
@Serializable
data class DeepSeekFragmentDto(
    val type: String? = null,
    val content: String? = null,
    @SerialName("elapsed_secs") val elapsedSecs: Double? = null,
    val files: List<DeepSeekFileDto> = emptyList(),
)

/** `FILE` fragment 里的附件描述。pulsez v0.4 不导图片（§1.2），只用于识别并计 issue。*/
@Serializable
data class DeepSeekFileDto(
    val id: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("is_image") val isImage: Boolean? = null,
    @SerialName("export_path") val exportPath: String? = null,
)

/** `parseManifest` 的返回值。v0.4 只支持版本 3。*/
data class ManifestVersion(val exportFormatVersion: Int) {
    companion object {
        const val SUPPORTED = 3
    }
}

/**
 * `parseIndex` 的一行：会话 id 与两个时间（毫秒）。
 *
 * 时间解析失败的字段为 null（索引只用于预检"会话数 / 时间范围"，不因此丢掉整行）；
 * 没有 id 的坏行不出现在结果里。
 */
data class SessionIndexRow(
    val id: String,
    val insertedAt: Long?,
    val updatedAt: Long?,
)

/**
 * 一条可入库的消息（已线性化、已过滤、时间已钳制）。
 *
 * 落库时映射为 `role=user/assistant`、`status="OK"`、`createdAt=at`，见 `。
 *
 * @property sourceMessageId 源会话里这条消息的 `messages[].id`。**只用于幂等键**：
 *   用 `UUID.nameUUIDFromBytes("deepseek:v3:<characterId>:<sessionId>:<sourceMessageId>")`
 *   生成确定性 messageId，重复导入同一条不会写重。不是正文，不落日志。
 */
data class DeepSeekTurn(
    val role: String,
    val text: String,
    val at: Long,
    val sourceMessageId: Long,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/**
 * 解析一段会话的结果（落库的输入）。
 *
 * 语义决定：**0 条可导入消息时抛 [DeepSeekParseException.EMPTY_SESSION]**，不返回空会话。
 * 理由：v0.4 结果页要区分"读不出来"与"导入 0 条"，让调用方按会话跳过并计数更直白，
 * 也避免空态悄悄流进后续段落/索引逻辑（不静默）。
 */
data class ParsedDeepSeekSession(
    val sourceSessionId: String,
    val title: String?,
    val insertedAt: Long,
    val updatedAt: Long,
    val turns: List<DeepSeekTurn>,
    val issues: List<Issue>,
    /** Original source start time; null stays unknown even when insertedAt used an index fallback. */
    val sourceStartedAt: Long? = insertedAt,
    /** Number of raw source messages, distinct from messages retained after filtering. */
    val sourceMessageCount: Int = turns.size,
    /** Maximum original timestamp among messages mapped into turns; null when it cannot be established. */
    val lastMessageAt: Long? = null,
)

/** Body-free preview row. A damage code means the source cannot be imported as part of a group. */
data class DeepSeekSessionPreview(
    val entryName: String,
    val sourceSessionId: String,
    val title: String?,
    val sourceStartedAt: Long?,
    val messageCount: Int,
    val damageCode: String? = null,
    /** Deterministic source message identifiers only; used to recognize pre-provenance imports. */
    val sourceMessageIds: List<Long> = emptyList(),
    /** Maximum original timestamp among messages that the parser will import; never a session/index fallback. */
    val lastMessageAt: Long? = null,
    /** First user message snippet (up to 280 chars) for on-device clustering/preview; omitted from toString(). */
    val firstUserSnippet: String = "",
    /** First assistant message snippet (up to 160 chars) for on-device clustering/preview; omitted from toString(). */
    val firstAssistantSnippet: String = "",
    /** Last assistant message snippet (up to 160 chars) for on-device clustering/preview; omitted from toString(). */
    val lastAssistantSnippet: String = "",
    /** Heuristic flag for short Q&A or heavy code-block lookup noise. */
    val looksLikeToolNoise: Boolean = false,
) {
    override fun toString(): String =
        "DeepSeekSessionPreview(" +
            "entryName=$entryName, " +
            "sourceSessionId=$sourceSessionId, " +
            "title=$title, " +
            "sourceStartedAt=$sourceStartedAt, " +
            "messageCount=$messageCount, " +
            "damageCode=$damageCode, " +
            "sourceMessageIds=$sourceMessageIds, " +
            "lastMessageAt=$lastMessageAt, " +
            "firstUserSnippetLength=${firstUserSnippet.length}, " +
            "firstAssistantSnippetLength=${firstAssistantSnippet.length}, " +
            "lastAssistantSnippetLength=${lastAssistantSnippet.length}, " +
            "looksLikeToolNoise=$looksLikeToolNoise)"
}

/**
 * 解析过程中的非致命问题。
 *
 * **只含原因码与消息下标，不含正文/标题**（` 隐私收敛、§5 隐私行）。
 *
 * @property messageIndex 该消息在源 `messages` 数组里的 0 基下标；与具体消息无关时为 null。
 */
data class Issue(val code: String, val messageIndex: Int? = null)

/** [Issue.code] 的取值。上游 UI 只按原因码给人话，不展示正文。 */
object DeepSeekIssueCode {
    /** 回溯到的根不是 USER：保留，但记一下（§4.3「根必须 USER」的软处理）。 */
    const val ROOT_NOT_USER = "ROOT_NOT_USER"

    /** `parent_id` 指向不存在的消息或成环：整段退化为按 id 升序，仍可导入（§4.3）。 */
    const val BRANCH_BROKEN = "BRANCH_BROKEN"

    /** `current_message_id` 有值但在 `messages` 里找不到：回落到最大 id。 */
    const val CURRENT_MESSAGE_NOT_FOUND = "CURRENT_MESSAGE_NOT_FOUND"

    /** 消息缺 id，无法参与树回溯。 */
    const val MISSING_MESSAGE_ID = "MISSING_MESSAGE_ID"

    /** 同一会话里 id 重复，只保留第一条。 */
    const val DUPLICATE_MESSAGE_ID = "DUPLICATE_MESSAGE_ID"

    /** 同一条消息里同时出现 REQUEST 与 RESPONSE 正文，方向按 role 兜底。 */
    const val MIXED_FRAGMENTS = "MIXED_FRAGMENTS"

    /** `TEMPLATE_RESPONSE`（如「服务器繁忙」）：不是 TA 说的话，丢弃并计 issue。 */
    const val TEMPLATE_RESPONSE = "TEMPLATE_RESPONSE"

    /** 未知 fragment 类型（含 `null`）：丢弃并计 issue，便于发现新版导出。 */
    const val UNKNOWN_FRAGMENT = "UNKNOWN_FRAGMENT"

    /** `FILE` 附件：v0.4 不落图，丢弃并计 issue。*/
    const val FILE_DROPPED = "FILE_DROPPED"

    /** 消息状态不在 FINISHED/INCOMPLETE/WIP：整条丢弃并计 issue。 */
    const val STATUS_UNSUPPORTED = "STATUS_UNSUPPORTED"
}

/**
 * 解析失败（包级或会话级）。**不吞异常变成空结果**：调用方（导入编排）按会话跳过并计数，
 * 结果页展示对应人话（` 错误码表）。
 *
 * @property code 原因码；UI 层按码给文案，不带任何用户正文。
 */
class DeepSeekParseException(
    val code: String,
    detail: String? = null,
    cause: Throwable? = null,
) : Exception(if (detail.isNullOrBlank()) code else "$code: $detail", cause) {
    companion object {
        /** JSON 本身坏掉 / 顶层结构不是预期形态。 */
        const val MALFORMED_JSON = "MALFORMED_JSON"

        /** `export_format_version` 缺失或不是 3（§2 只支持 v3）。*/
        const val VERSION_UNSUPPORTED = "VERSION_UNSUPPORTED"

        /** `session.id` 与 ZIP 内文件名不一致。*/
        const val SESSION_ID_MISMATCH = "SESSION_ID_MISMATCH"

        /** 会话或消息时间戳无法解析：整段会话跳过（§4.3）。 */
        const val BAD_TIMESTAMP = "BAD_TIMESTAMP"

        /** 过滤后没有任何可导入消息：整段会话跳过。 */
        const val EMPTY_SESSION = "EMPTY_SESSION"
    }
}
