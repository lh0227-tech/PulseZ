package cc.pulsez.app.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * SillyTavern 角色卡 JSON。见 。
 *
 * 认两种形状：
 * - v1：字段平铺在顶层（`name/description/personality/first_mes/mes_example/scenario`）
 * - v2/v3：`spec` 是 `chara_card_v2/v3`，字段在 `data` 节点（`spec: chara_card_v3` 取 `data`）
 *
 * 缺字段不抛异常：给 null，并在 warnings 里说一句人话。字段全都没有时返回 null，
 * 让上层退化成纯文本——比把一堆空字段塞进卡片模板好。
 */
object JsonCardParser {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val CARD_KEYS = listOf(
        "\"name\"",
        "\"personality\"",
        "\"first_mes\"",
        "\"spec\"",
        "\"description\"",
    )

    /** 文字层面的判别，容忍 JSON 尾部不合法——真正解析失败时上层会退化。 */
    fun detect(text: String): Boolean {
        val t = text.trimStart()
        if (!t.startsWith("{")) return false
        return CARD_KEYS.any { t.contains(it) }
    }

    fun parse(text: String): ParsedImport? {
        val root = runCatching { json.parseToJsonElement(text.trim()) }
            .getOrNull() as? JsonObject ?: return null

        // v2/v3：字段在 data 节点；老卡没有 data 或 data 不是对象时用顶层。
        val data = root["data"] as? JsonObject
        val spec = root.string("spec").orEmpty()
        val node = when {
            data != null && spec.contains("chara_card", ignoreCase = true) -> data
            data != null && root.string("name") == null -> data
            else -> root
        }

        val name = node.string("name")
        val description = node.string("description")
        val personality = node.string("personality")
        val scenario = node.string("scenario")
        val firstMes = node.string("first_mes") ?: node.string("first_message")
        val greeting = firstMes ?: node.stringList("alternate_greetings").firstOrNull()
        val mesExample = node.string("mes_example") ?: node.string("example_dialogue")

        // 一个角色卡字段都没有：多半不是角色卡。
        if (name == null && description == null && personality == null && greeting == null) return null

        val persona = listOfNotNull(description, personality, scenario)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n\n")
            .takeIf { it.isNotEmpty() }

        val warnings = mutableListOf<String>()
        if (name == null) warnings += ImportWarnings.MISSING_NAME
        if (persona == null) warnings += ImportWarnings.MISSING_PERSONA
        if (greeting == null) warnings += ImportWarnings.MISSING_GREETING
        if (scenario == null) warnings += ImportWarnings.MISSING_SCENARIO
        if (mesExample.isNullOrBlank()) warnings += ImportWarnings.MISSING_EXAMPLES

        return ParsedImport(
            name = name,
            persona = persona,
            greeting = greeting,
            styleSamples = StyleSamples.fromMesExample(mesExample.orEmpty()),
            turns = emptyList(),
            format = Format.ST_CARD_JSON,
            warnings = warnings,
        )
    }

    // ---- JSON 取值 ----

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.stringList(key: String): List<String> =
        (get(key) as? JsonArray)
            ?.mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() }
            }
            .orEmpty()
}
