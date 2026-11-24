package cc.pulsez.app.importer.deepseek

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * DeepSeek 导出 v3 的纯解析层：JSON 文本 → 结构化会话。
 *
 * 不碰 ZIP、不碰数据库，只做四件事：
 * 1. 版本 / 索引 / 会话 DTO 解析与校验；
 * 2. 消息树线性化（只保留 `current_message_id` 回溯到根的链，断裂则退化为 id 升序）；
 * 3. fragment 与 status 映射（§4.3 表，逐行有单测）；
 * 4. 时间解析 + `(inserted_at, id)` 排序 + 严格递增钳制 + 未来时间整体拉回。
 *
 * 依据：
 * - `（格式、字段、fragment 与状态语义）
 * - `（DTO/Json 配置）、§4.3（映射表，语义不得改）
 * - ` （时间口径：严格递增 > 不晚于 now）
 *
 * 错误一律用 [DeepSeekParseException] 表达，由调用方按会话跳过并计数；不返回"看起来成功"的空结果。
 */
object DeepSeekParser {

    // ---- §4.3 映射表用到的常量 ----

    /** 文本 fragment：用户提问。 */
    private const val FRAGMENT_REQUEST = "REQUEST"

    /** 文本 fragment：回答正文。 */
    private const val FRAGMENT_RESPONSE = "RESPONSE"

    /** 附件 fragment：v0.4 不落图（§1.2）。*/
    private const val FRAGMENT_FILE = "FILE"

    /** 模板提示（如「服务器繁忙」）：不是 TA 说的话，绝不能当正文（§4.3）。 */
    private const val FRAGMENT_TEMPLATE_RESPONSE = "TEMPLATE_RESPONSE"

    /** 静默丢弃的 fragment 类型：思考、界面提示、免责声明、部分读取（§4.3 首行）。 */
    private val SILENT_FRAGMENTS = setOf("THINK", "TIP", "CONTENT_RISK", "PARTIAL_FILE_READ")

    /** 静默丢弃的工具片段前缀（`TOOL_SEARCH` / `TOOL_OPEN` / `TOOL_FIND` …）。 */
    private const val TOOL_FRAGMENT_PREFIX = "TOOL_"

    /** 可保留的消息状态。*/
    private val KEPT_STATUSES = setOf("FINISHED", "INCOMPLETE", "WIP")

    /** 时间钳制步长：1 秒（§2 「+1s」）。*/
    private const val CLAMP_STEP_MS = 1_000L

    private const val PREVIEW_USER_SNIPPET_CHARS = 280
    private const val PREVIEW_ASSISTANT_SNIPPET_CHARS = 160
    private const val NOISE_MAX_TURNS = 2
    private const val NOISE_MAX_TOTAL_CHARS = 100
    private const val NOISE_MAX_CODE_RATIO = 0.45
    private val FENCED_CODE_REGEX = Regex("```[\\s\\S]*?(?:```|$)")

    // ---- 入口 1：manifest ----

    /**
     * 解析 `manifest.json`，版本必须是 [ManifestVersion.SUPPORTED]（3）。
     *
     * `export_format_version` 兼容数字 `3` 与字符串 `"3"`（§4.2）。
     *
     * @throws DeepSeekParseException `MALFORMED_JSON`（不是合法 JSON）或
     *   `VERSION_UNSUPPORTED`（版本缺失 / 不是 3）。
     */
    fun parseManifest(text: String): ManifestVersion {
        val dto = decodeOrThrow(text, DeepSeekManifestDto.serializer(), "manifest")
        val version = (dto.exportFormatVersion as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.toIntOrNull()
            ?: throw DeepSeekParseException(
                DeepSeekParseException.VERSION_UNSUPPORTED,
                "export_format_version 缺失或不是整数",
            )
        if (version != ManifestVersion.SUPPORTED) {
            throw DeepSeekParseException(
                DeepSeekParseException.VERSION_UNSUPPORTED,
                "export_format_version=${version}（只支持 ${ManifestVersion.SUPPORTED}）",
            )
        }
        return ManifestVersion(version)
    }

    // ---- 入口 2：会话索引 ----

    /**
     * 解析 `chat_session_index.json`（顶层数组）。
     *
     * 容错：坏行（不是对象 / 缺 id / 类型不对）逐行跳过，不整段失败（§4.2 验收）；
     * 时间解析失败的行保留，对应字段为 null（索引只用于预检）。
     *
     * @throws DeepSeekParseException `MALFORMED_JSON`（不是合法 JSON 或顶层不是数组）。
     */
    fun parseIndex(text: String): List<SessionIndexRow> {
        val root = parseElement(text, "chat_session_index")
        val array = root as? JsonArray
            ?: throw DeepSeekParseException(
                DeepSeekParseException.MALFORMED_JSON,
                "chat_session_index 顶层不是数组",
            )
        val rows = ArrayList<SessionIndexRow>(array.size)
        for (element in array) {
            val dto = try {
                deepSeekJson.decodeFromJsonElement(SessionIndexRowDto.serializer(), element)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            val id = dto?.id?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            rows += SessionIndexRow(
                id = id,
                insertedAt = parseTimestamp(dto.insertedAt),
                updatedAt = parseTimestamp(dto.updatedAt),
            )
        }
        return rows
    }

    // ---- 入口 3：单段会话 ----

    /**
     * 解析一段会话文件，产出可直接落库的线性消息列表。
     *
     * @param expectedSessionId ZIP 内文件名（不含 `.json`）推出的会话 id；必须等于 `session.id`，
     *   否则抛 `SESSION_ID_MISMATCH`。
     * @param fallbackUpdatedAt 会话内时间戳都解析不出来时的兜底（通常取索引行的 `updated_at`）；
     *   仍无则抛 `BAD_TIMESTAMP`。
     * @param now 未来时间整体拉回的参照点（默认系统当前时间）。留参数是为了单测可固定。
     *
     * @throws DeepSeekParseException `MALFORMED_JSON` / `SESSION_ID_MISMATCH` / `BAD_TIMESTAMP` /
     *   `EMPTY_SESSION`；调用方按会话跳过，不静默变成空结果。
     */
    fun parseSession(
        text: String,
        expectedSessionId: String,
        fallbackUpdatedAt: Long? = null,
        fallbackStartedAt: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): ParsedDeepSeekSession {
        val session = decodeOrThrow(text, DeepSeekSessionDto.serializer(), "会话文件")

        val actualId = session.id?.trim().orEmpty()
        if (actualId != expectedSessionId) {
            throw DeepSeekParseException(
                DeepSeekParseException.SESSION_ID_MISMATCH,
                "session.id 与文件名不一致",
            )
        }

        // 会话时间（fallback = 调用方从 index 行传来的 updated_at）：
        // - inserted_at 缺失/非法：用 fallback，仍无则 BAD_TIMESTAMP；
        // - updated_at 存在：解析成功用它，解析失败用 fallback，仍无则 BAD_TIMESTAMP；
        // - updated_at 缺失：优先 fallback，没有才用 inserted_at 兜底（§4.2）。
        val insertedAt = parseTimestamp(session.insertedAt)
            ?: fallbackUpdatedAt
            ?: throw DeepSeekParseException(
                DeepSeekParseException.BAD_TIMESTAMP,
                "会话 inserted_at 无法解析",
            )
        val updatedAt = if (session.updatedAt == null) {
            fallbackUpdatedAt ?: insertedAt
        } else {
            parseTimestamp(session.updatedAt)
                ?: fallbackUpdatedAt
                ?: throw DeepSeekParseException(
                    DeepSeekParseException.BAD_TIMESTAMP,
                    "会话 updated_at 无法解析",
                )
        }

        val issues = mutableListOf<Issue>()
        val linear = linearize(session.messages, session.currentMessageId, issues)
        // §4.3「时间」行：选中链上的消息时间戳必须都能解析，否则整段会话跳过（BAD_TIMESTAMP）。
        val atByMessageId = HashMap<Long, Long>(linear.size)
        for (message in linear) {
            atByMessageId[message.id] = parseTimestamp(message.message.insertedAt)
                ?: throw DeepSeekParseException(
                    DeepSeekParseException.BAD_TIMESTAMP,
                    "消息 inserted_at 无法解析",
                )
        }
        val candidates = linear.mapNotNull { mapMessage(it, issues, atByMessageId) }
        if (candidates.isEmpty()) {
            throw DeepSeekParseException(
                DeepSeekParseException.EMPTY_SESSION,
                "过滤后没有可导入的消息",
            )
        }

        return ParsedDeepSeekSession(
            sourceSessionId = actualId,
            title = session.title?.trim()?.takeIf { it.isNotEmpty() },
            insertedAt = insertedAt,
            updatedAt = updatedAt,
            turns = buildTurns(candidates, now),
            issues = issues,
            sourceStartedAt = parseTimestamp(session.insertedAt) ?: fallbackStartedAt,
            sourceMessageCount = session.messages.size,
            // 依据189-191. Use raw source message times for retained candidates;
            // buildTurns adjusts stored times, while session/index timestamps are not message dates.
            lastMessageAt = candidates.maxOfOrNull { it.at },
        )
    }

    /**
     * Body-free preview for the confirmation screen. Parse errors remain attached to the source row;
     * malformed windows can still show their stable filename-derived ID and any readable metadata.
     */
    fun previewSession(
        text: String,
        expectedSessionId: String,
        fallbackUpdatedAt: Long? = null,
        fallbackStartedAt: Long? = null,
    ): DeepSeekSessionPreview {
        val dto = try {
            decodeOrThrow(text, DeepSeekSessionDto.serializer(), "会话文件")
        } catch (e: DeepSeekParseException) {
            return DeepSeekSessionPreview(
                entryName = "${ZipEntryNames.SESSION_DIR}$expectedSessionId.json",
                sourceSessionId = expectedSessionId,
                title = null,
                sourceStartedAt = fallbackStartedAt,
                messageCount = 0,
                damageCode = e.code,
            )
        }
        val parseResult = runCatching {
            parseSession(text, expectedSessionId, fallbackUpdatedAt, fallbackStartedAt)
        }
        val parsed = parseResult.getOrNull()
        val parseFailure = parseResult.exceptionOrNull()
        val actualId = dto.id?.trim()?.takeIf { it.isNotEmpty() } ?: expectedSessionId
        val turns = parsed?.turns.orEmpty()
        val firstUserSnippet = turns
            .firstOrNull { it.role == DeepSeekTurn.ROLE_USER }
            ?.text
            ?.trim()
            ?.take(PREVIEW_USER_SNIPPET_CHARS)
            .orEmpty()
        val firstAssistantSnippet = turns
            .firstOrNull { it.role == DeepSeekTurn.ROLE_ASSISTANT }
            ?.text
            ?.trim()
            ?.take(PREVIEW_ASSISTANT_SNIPPET_CHARS)
            .orEmpty()
        val lastAssistantSnippet = turns
            .lastOrNull { it.role == DeepSeekTurn.ROLE_ASSISTANT }
            ?.text
            ?.trim()
            ?.take(PREVIEW_ASSISTANT_SNIPPET_CHARS)
            .orEmpty()
        return DeepSeekSessionPreview(
            entryName = "${ZipEntryNames.SESSION_DIR}$expectedSessionId.json",
            sourceSessionId = expectedSessionId,
            title = dto.title?.trim()?.takeIf { it.isNotEmpty() },
            sourceStartedAt = parseTimestamp(dto.insertedAt) ?: fallbackStartedAt,
            messageCount = dto.messages.size,
            damageCode = when {
                actualId != expectedSessionId -> DeepSeekParseException.SESSION_ID_MISMATCH
                parseFailure == null -> null
                else -> (parseFailure as? DeepSeekParseException)?.code ?: DeepSeekParseException.MALFORMED_JSON
            },
            sourceMessageIds = turns.map { it.sourceMessageId },
            // A failed/invalid parse deliberately stays unknown; no session, index, or local-time fallback.
            lastMessageAt = parsed?.lastMessageAt,
            firstUserSnippet = firstUserSnippet,
            firstAssistantSnippet = firstAssistantSnippet,
            lastAssistantSnippet = lastAssistantSnippet,
            looksLikeToolNoise = isLikelyToolNoise(turns),
        )
    }

    private fun isLikelyToolNoise(turns: List<DeepSeekTurn>): Boolean {
        if (turns.isEmpty()) return false
        val totalChars = turns.sumOf { it.text.length }
        if (turns.size <= NOISE_MAX_TURNS && totalChars < NOISE_MAX_TOTAL_CHARS) return true
        if (totalChars == 0) return false
        val codeChars = turns.sumOf { turn ->
            FENCED_CODE_REGEX.findAll(turn.text).sumOf { it.value.length }
        }
        return codeChars.toDouble() / totalChars.toDouble() > NOISE_MAX_CODE_RATIO
    }

    // ---- 线性化 ----

    /** 线性化后的一条消息：DTO + 它在源 `messages` 数组里的下标（issue 只用下标，不带正文）。 */
    private class LinearMessage(
        val message: DeepSeekMessageDto,
        val sourceIndex: Int,
        val id: Long,
    )

    /**
     * 从 `current_message_id` 沿 `parent_id` 回溯到根，反转成时间序（§4.3「分支」行）。
     *
     * - `current_message_id` 缺失 → 从最大 id 回溯；
     * - `current_message_id` 有值但找不到 → 回落最大 id，记 [DeepSeekIssueCode.CURRENT_MESSAGE_NOT_FOUND]；
     * - `parent_id` 指向不存在的消息 / 成环 → **退化为全部消息按 id 升序**，
     *   记 [DeepSeekIssueCode.BRANCH_BROKEN]，不崩（验收）；
     * - 根不是 USER → 保留并记 [DeepSeekIssueCode.ROOT_NOT_USER]（§4.3）。
     */
    private fun linearize(
        messages: List<DeepSeekMessageDto>,
        currentMessageId: Long?,
        issues: MutableList<Issue>,
    ): List<LinearMessage> {
        val byId = LinkedHashMap<Long, LinearMessage>()
        messages.forEachIndexed { index, message ->
            val id = message.id
            if (id == null) {
                issues += Issue(DeepSeekIssueCode.MISSING_MESSAGE_ID, index)
            } else if (byId.containsKey(id)) {
                issues += Issue(DeepSeekIssueCode.DUPLICATE_MESSAGE_ID, index)
            } else {
                byId[id] = LinearMessage(message, index, id)
            }
        }
        if (byId.isEmpty()) return emptyList()

        var end: LinearMessage? = currentMessageId?.let { byId[it] }
        if (currentMessageId != null && end == null) {
            issues += Issue(DeepSeekIssueCode.CURRENT_MESSAGE_NOT_FOUND, null)
        }
        if (end == null) {
            end = byId.values.maxByOrNull { it.id }
        }

        val chain = ArrayList<LinearMessage>()
        val visited = HashSet<Long>()
        var cursor: LinearMessage? = end
        var brokenAt: Int? = null
        while (cursor != null) {
            if (!visited.add(cursor.id)) {
                // 成环：已经走过的节点又出现。
                brokenAt = cursor.sourceIndex
                break
            }
            chain += cursor
            val parentId = cursor.message.parentId ?: break
            val parent = byId[parentId]
            if (parent == null) {
                // 父节点不存在：链断在这里。
                brokenAt = cursor.sourceIndex
                break
            }
            cursor = parent
        }

        if (brokenAt != null) {
            // 回退：全部消息按 id 升序（§4.3「parent 链断裂时回退」）。
            issues += Issue(DeepSeekIssueCode.BRANCH_BROKEN, brokenAt)
            return byId.values.sortedBy { it.id }
        }

        val root = chain.last()
        if (!root.message.role.equals("USER", ignoreCase = true)) {
            issues += Issue(DeepSeekIssueCode.ROOT_NOT_USER, root.sourceIndex)
        }
        chain.reverse() // 回溯得到的是 叶→根，反转成 根→叶
        return chain
    }

    // ---- §4.3 映射表（逐行落地） ----

    /** 映射后、尚未排序/钳制的候选消息。 */
    private class Candidate(
        val id: Long,
        val role: String,
        val text: String,
        val at: Long,
    )

    /**
     * 单条消息 → 一条 turn（或丢弃）。
     *
     * 映射表（`语义不得改）：
     * - fragment `REQUEST`  → user 文本（`content`）
     * - fragment `RESPONSE` → assistant 文本（`content`）
     * - `THINK` / `TIP` / `CONTENT_RISK` / `TOOL_*` / `PARTIAL_FILE_READ` → 丢弃（静默）
     * - `TEMPLATE_RESPONSE` / 未知类型 → 丢弃 + issue（只记原因码）
     * - `FILE` → 丢弃 + issue（不落图）
     * - status ∈ {`FINISHED`,`INCOMPLETE`,`WIP`} → 保留（正文为空则丢）
     * - status 其它 → 整条丢弃 + issue（**不写 FAILED**，§4.3）
     *
     * 同一消息多个同方向文本 fragment 用 `\n` 连接；文本方向由 fragment 类型决定（与真实导出
     * "USER 消息带 REQUEST、ASSISTANT 消息带 RESPONSE" 一致），`role` 只在两种正文同时出现时兜底。
     */
    private fun mapMessage(
        linear: LinearMessage,
        issues: MutableList<Issue>,
        atByMessageId: Map<Long, Long>,
    ): Candidate? {
        val message = linear.message
        val index = linear.sourceIndex

        val status = message.status.trim().uppercase()
        if (status !in KEPT_STATUSES) {
            issues += Issue(DeepSeekIssueCode.STATUS_UNSUPPORTED, index)
            return null
        }

        val requestParts = ArrayList<String>()
        val responseParts = ArrayList<String>()
        var droppedFile = false
        var droppedTemplate = false
        var droppedUnknown = false

        for (fragment in message.fragments) {
            when (val type = fragment.type?.trim()?.uppercase()) {
                FRAGMENT_REQUEST ->
                    fragment.content?.takeIf { it.isNotBlank() }?.let { requestParts += it }

                FRAGMENT_RESPONSE ->
                    fragment.content?.takeIf { it.isNotBlank() }?.let { responseParts += it }

                FRAGMENT_FILE -> droppedFile = true
                FRAGMENT_TEMPLATE_RESPONSE -> droppedTemplate = true
                else -> {
                    val silent = type != null &&
                        (type in SILENT_FRAGMENTS || type.startsWith(TOOL_FRAGMENT_PREFIX))
                    if (!silent) droppedUnknown = true
                }
            }
        }
        if (droppedFile) issues += Issue(DeepSeekIssueCode.FILE_DROPPED, index)
        if (droppedTemplate) issues += Issue(DeepSeekIssueCode.TEMPLATE_RESPONSE, index)
        if (droppedUnknown) issues += Issue(DeepSeekIssueCode.UNKNOWN_FRAGMENT, index)

        val requestText = requestParts.joinToString("\n")
        val responseText = responseParts.joinToString("\n")
        val role = when {
            requestText.isNotEmpty() && responseText.isEmpty() -> DeepSeekTurn.ROLE_USER
            responseText.isNotEmpty() && requestText.isEmpty() -> DeepSeekTurn.ROLE_ASSISTANT
            requestText.isNotEmpty() && responseText.isNotEmpty() -> {
                issues += Issue(DeepSeekIssueCode.MIXED_FRAGMENTS, index)
                if (message.role.equals("USER", ignoreCase = true)) {
                    DeepSeekTurn.ROLE_USER
                } else {
                    DeepSeekTurn.ROLE_ASSISTANT
                }
            }
            // 正文为空（含只有 THINK / FILE / TOOL_* 的消息）：丢弃，不算导入。
            else -> return null
        }
        val text = if (role == DeepSeekTurn.ROLE_USER) requestText else responseText
        val at = atByMessageId.getValue(linear.id)
        return Candidate(id = linear.id, role = role, text = text, at = at)
    }

    // ---- 时间 ----

    /**
     * 时间排序与钳制：
     * 1. 按 `(inserted_at, id)` 升序；
     * 2. 严格递增钳制：`at <= prev` 时改为 `prev + 1000`；
     * 3. 最后一条不早于 `now` 时（含整段都在未来），整体平移回 now 之前：
     *    保证最后一条 `< now` 且保持递增/间距。
     */
    private fun buildTurns(candidates: List<Candidate>, now: Long): List<DeepSeekTurn> {
        val sorted = candidates.sortedWith(compareBy<Candidate> { it.at }.thenBy { it.id })
        val times = LongArray(sorted.size)
        for (i in sorted.indices) {
            val at = sorted[i].at
            times[i] = if (i > 0 && at <= times[i - 1]) times[i - 1] + CLAMP_STEP_MS else at
        }
        val last = times.last()
        if (last >= now) {
            // 整体平移：最后一条落到 now - 1，前面的保持原有间距与严格递增。
            val delta = last - now + 1
            for (i in times.indices) times[i] -= delta
        }
        return sorted.mapIndexed { i, candidate ->
            DeepSeekTurn(
                role = candidate.role,
                text = candidate.text,
                at = times[i],
                sourceMessageId = candidate.id,
            )
        }
    }

    /**
     * 时间戳解析：`Instant.parse` → 失败回退 `OffsetDateTime.parse` → 仍失败返回 null
     * （§4.3「时间」行；会话/消息层据此决定 fallback 或 BAD_TIMESTAMP）。
     */
    private fun parseTimestamp(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                OffsetDateTime.parse(text).toInstant().toEpochMilli()
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    // ---- JSON 基础设施 ----

    private fun parseElement(text: String, what: String): JsonElement = try {
        deepSeekJson.parseToJsonElement(text.trim())
    } catch (e: SerializationException) {
        throw malformed(what, e)
    } catch (e: IllegalArgumentException) {
        throw malformed(what, e)
    }

    private fun <T> decodeOrThrow(
        text: String,
        deserializer: DeserializationStrategy<T>,
        what: String,
    ): T = try {
        deepSeekJson.decodeFromString(deserializer, text.trim())
    } catch (e: SerializationException) {
        throw malformed(what, e)
    } catch (e: IllegalArgumentException) {
        throw malformed(what, e)
    }

    private fun malformed(what: String, cause: Throwable): DeepSeekParseException =
        DeepSeekParseException(
            DeepSeekParseException.MALFORMED_JSON,
            "$what 不是合法 JSON",
            cause,
        )
}
