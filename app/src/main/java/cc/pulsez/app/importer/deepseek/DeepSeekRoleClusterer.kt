package cc.pulsez.app.importer.deepseek

import cc.pulsez.app.net.ChatMessage
import cc.pulsez.app.net.ChatRequest
import cc.pulsez.app.net.LlmClient
import cc.pulsez.app.net.asInternalRequest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 归并后的单角色分拣结果。
 *
 * @property name 角色名（已排除“新对话”“未命名”等占位词）
 * @property userName TA 对用户的称呼（无明确线索则为 null）
 * @property persona 自然中文人设描述（代词用 TA 或角色名）
 * @property styleSamples 2～4 条代表台词
 * @property sessionIds 归入该角色的源会话 ID 列表
 */
data class ClusteredRole(
    val name: String,
    val userName: String?,
    val persona: String,
    val styleSamples: List<String>,
    val sessionIds: List<String>,
)

/**
 * 智能分角色总结果。
 *
 * @property roles 归并后的角色簇列表
 * @property noiseSessionIds 识别为查资料/纯工具杂项的源会话 ID（默认置为暂不导入）
 */
data class RoleClusterResult(
    val roles: List<ClusteredRole>,
    val noiseSessionIds: List<String>,
)

/**
 * DeepSeek 大包导入的轻量智能分角色器：分批（每批最多 35 段）把非损坏会话名片送入
 * [LlmClient.completeJson]（强制关思考、`temperature = 0.3`），按角色簇起名、写人设、
 * 提炼语气台词并筛除查资料杂项，多批同名角色自动合并。
 */
class DeepSeekRoleClusterer(
    private val completeJson: suspend (systemPrompt: String, userPrompt: String) -> String,
) {

    suspend fun cluster(previews: List<DeepSeekSessionPreview>): RoleClusterResult {
        val valid = previews
            .filter { it.damageCode == null }
            .distinctBy { it.sourceSessionId }
        if (valid.isEmpty()) {
            return RoleClusterResult(roles = emptyList(), noiseSessionIds = emptyList())
        }

        val mergedByName = LinkedHashMap<String, MutableMergedRole>()
        val allNoiseIds = LinkedHashSet<String>()
        var fallbackCounter = 1

        for (batch in valid.chunked(MAX_BATCH_SIZE)) {
            coroutineContext.ensureActive()
            val batchIds = batch.mapTo(LinkedHashSet()) { it.sourceSessionId }
            val knownRoles = mergedByName.values.map { role ->
                KnownRoleSummary(name = role.name, personaHint = role.persona.take(60))
            }
            val raw = completeJson(SYSTEM_PROMPT, buildUserPrompt(batch, knownRoles))
            val parsed = parseBatchResponse(raw)

            val assignedInBatch = HashSet<String>()
            for (roleDto in parsed.roles) {
                val filteredIds = roleDto.sessionIds
                    .map { it.trim() }
                    .filter { it in batchIds && assignedInBatch.add(it) }
                    .distinct()
                if (filteredIds.isEmpty()) continue

                val safeName = sanitizeRoleName(roleDto.name) {
                    "伙伴 ${fallbackCounter++}"
                }
                val key = safeName.lowercase()
                val cleanUserName = roleDto.userName?.trim()?.takeIf { it.isNotEmpty() }
                val cleanPersona = roleDto.persona?.trim().orEmpty()
                val cleanSamples = roleDto.styleSamples
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(MAX_STYLE_SAMPLES)

                val existing = mergedByName[key]
                if (existing == null) {
                    mergedByName[key] = MutableMergedRole(
                        name = safeName,
                        userName = cleanUserName,
                        persona = cleanPersona,
                        styleSamples = cleanSamples.toMutableList(),
                        sessionIds = filteredIds.toMutableList(),
                    )
                } else {
                    if (existing.userName == null && cleanUserName != null) {
                        existing.userName = cleanUserName
                    }
                    if (cleanPersona.length > existing.persona.length) {
                        existing.persona = cleanPersona
                    }
                    for (sample in cleanSamples) {
                        if (sample !in existing.styleSamples && existing.styleSamples.size < MAX_STYLE_SAMPLES) {
                            existing.styleSamples += sample
                        }
                    }
                    for (sessionId in filteredIds) {
                        if (sessionId !in existing.sessionIds) {
                            existing.sessionIds += sessionId
                        }
                    }
                }
            }

            for (noiseId in parsed.noiseSessionIds) {
                val id = noiseId.trim()
                if (id in batchIds && id !in assignedInBatch) {
                    allNoiseIds += id
                }
            }
        }

        val roles = mergedByName.values.map { role ->
            ClusteredRole(
                name = role.name,
                userName = role.userName,
                persona = role.persona,
                styleSamples = role.styleSamples.toList(),
                sessionIds = role.sessionIds.toList(),
            )
        }
        val assignedAll = roles.flatMapTo(HashSet()) { it.sessionIds }
        val finalNoise = allNoiseIds.filter { it !in assignedAll }
        return RoleClusterResult(roles = roles, noiseSessionIds = finalNoise)
    }

    private class MutableMergedRole(
        val name: String,
        var userName: String?,
        var persona: String,
        val styleSamples: MutableList<String>,
        val sessionIds: MutableList<String>,
    )

    private data class KnownRoleSummary(
        val name: String,
        val personaHint: String,
    )

    private data class ParsedBatchResponse(
        val roles: List<ParsedRoleDto>,
        val noiseSessionIds: List<String>,
    )

    private data class ParsedRoleDto(
        val name: String?,
        val userName: String?,
        val persona: String?,
        val styleSamples: List<String>,
        val sessionIds: List<String>,
    )

    private fun parseBatchResponse(raw: String): ParsedBatchResponse {
        val cleaned = stripCodeFence(raw)
        val root = json.parseToJsonElement(cleaned) as? JsonObject
            ?: throw IllegalStateException("Cluster response is not a JSON object")
        val rolesArray = root["roles"] as? JsonArray
        val noiseArray = root["noiseSessionIds"] as? JsonArray
        val roles = rolesArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            ParsedRoleDto(
                name = obj["name"].asStringOrNull(),
                userName = obj["userName"].asStringOrNull(),
                persona = obj["persona"].asStringOrNull(),
                styleSamples = obj["styleSamples"].asStringList(),
                sessionIds = obj["sessionIds"].asStringList(),
            )
        }
        val noiseIds = noiseArray.asStringList()
        return ParsedBatchResponse(roles = roles, noiseSessionIds = noiseIds)
    }

    private fun buildUserPrompt(
        batch: List<DeepSeekSessionPreview>,
        knownRoles: List<KnownRoleSummary>,
    ): String {
        val payload = buildJsonObject {
            if (knownRoles.isNotEmpty()) {
                put(
                    "existingRoles",
                    buildJsonArray {
                        knownRoles.forEach { role ->
                            add(
                                buildJsonObject {
                                    put("name", role.name)
                                    if (role.personaHint.isNotEmpty()) put("personaHint", role.personaHint)
                                },
                            )
                        }
                    },
                )
            }
            put(
                "sessions",
                buildJsonArray {
                    batch.forEach { preview ->
                        add(
                            buildJsonObject {
                                put("sessionId", preview.sourceSessionId)
                                put("title", preview.title.orEmpty())
                                put("messageCount", preview.messageCount)
                                put("looksLikeToolNoise", preview.looksLikeToolNoise)
                                put("firstUserSnippet", preview.firstUserSnippet)
                                put("firstAssistantSnippet", preview.firstAssistantSnippet)
                                put("lastAssistantSnippet", preview.lastAssistantSnippet)
                            },
                        )
                    }
                },
            )
        }
        return payload.toString()
    }

    companion object {
        const val MAX_BATCH_SIZE: Int = 35
        const val CLUSTER_TEMPERATURE: Double = 0.3
        private const val MAX_STYLE_SAMPLES: Int = 4

        private val FORBIDDEN_NAMES = setOf(
            "新对话",
            "未命名",
            "未命名对话",
            "未命名角色",
            "默认对话",
            "默认角色",
            "角色",
            "TA",
            "AI助手",
            "助手",
        )

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private val SYSTEM_PROMPT = """
            你是聊天归档整理助手。请阅读用户提供的多段对话轻量名片，完成四件事：
            1. 将属于同一个陪伴角色或同一套人设的对话归为一簇；
            2. 将日常查资料、写代码、翻译、纯工具问答等杂项对话的 sessionId 放入 noiseSessionIds；
            3. 为每个角色簇起一个有辨识度的中文名字 name（严禁使用“新对话”“未命名”“未命名对话”“TA”“AI助手”等空泛名称），识别 TA 对用户的称呼 userName（没有明确称呼时填 null），并用自然流畅的中文写出人设 persona；
            4. 为每个角色摘出 2 到 4 条最能体现说话语气的代表台词 styleSamples。

            硬性约束：
            - persona 中代词只用“TA”或角色名，严禁使用“她”或“他”；
            - 提取 persona 聚焦性格、说话方式与相处氛围，不要写入“AI 助手”“数字伙伴”“没有实体”等身份免责标签；
            - 严禁编造对话名片里不存在的经历或台词；
            - 若 existingRoles 中已有同一位角色，请保持完全一致的 name 以便合并；
            - 只输出合法 JSON 对象，格式为：
              {"roles":[{"name":"...","userName":null,"persona":"...","styleSamples":["...","..."],"sessionIds":["..."]}],"noiseSessionIds":["..."]}
        """.trimIndent()

        fun buildRequest(
            model: String,
            baseUrl: String,
            systemPrompt: String,
            userPrompt: String,
        ): ChatRequest = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt),
            ),
            stream = false,
            jsonMode = true,
            maxTokens = 4_096,
            temperature = CLUSTER_TEMPERATURE,
        ).asInternalRequest(baseUrl)

        fun forClient(
            client: LlmClient,
            model: String,
            baseUrl: String,
        ): DeepSeekRoleClusterer = DeepSeekRoleClusterer { system, user ->
            client.completeJson(buildRequest(model, baseUrl, system, user))
        }

        internal fun sanitizeRoleName(raw: String?, fallback: () -> String): String {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return fallback()
            if (trimmed in FORBIDDEN_NAMES) return fallback()
            if (trimmed.startsWith("新对话") || trimmed.startsWith("未命名")) return fallback()
            return trimmed
        }

        private fun stripCodeFence(raw: String): String {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("```")) return trimmed
            val firstNewline = trimmed.indexOf('\n')
            val body = if (firstNewline >= 0) trimmed.substring(firstNewline + 1) else trimmed.removePrefix("```")
            val endFence = body.lastIndexOf("```")
            return (if (endFence >= 0) body.substring(0, endFence) else body).trim()
        }

        private fun JsonElement?.asStringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonElement?.asStringList(): List<String> =
            (this as? JsonArray)?.mapNotNull { it.asStringOrNull() }.orEmpty()
    }
}
