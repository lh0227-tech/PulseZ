package cc.pulsez.app.mem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 记忆表 JSON 的解析与双时态合并。
 *
 * 对应文档：
 * - 03 §5.1（六个顶层字段的 JSON 契约）
 * - 03 §5.2（存储上限与渲染上限分离）
 * - 03 §5.3（双时态：覆盖写 validTo，不物理删除）
 * - 03 §5.4（conf < 0.6 不写、v ≤ 200 字、append-only）
 * - 03 §6.2–§6.3（分片抽取结果的合并规则）
 * - 02 §3 memory_item（schema）、02 §4.4（同 key 覆盖规则）
 *
 * 解析是"宁可漏、不可炸"的：任何坏输入都返回 null 或空表，绝不抛异常。
 */
object MemorySchema {

    /** 单条 `v` 的最大字数，超出截断（03 §5.1、§6.3）。 */
    const val MAX_VALUE_CHARS: Int = 200

    /** 低于该置信度的条目不写入（03 §5.4）。 */
    const val MIN_CONFIDENCE: Double = 0.6

    /** 每条记忆最多保留的来源消息 id 数量（02 §3）。 */
    const val MAX_SOURCE_MESSAGE_IDS: Int = 5

    private val JSON = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }
    private val FENCE_PATTERN = Regex("```(?:json|JSON)?")
    private val WHITESPACE_PATTERN = Regex("\\s+")

    /**
     * 解析抽取模型输出的记忆表 JSON。
     *
     * 容错行为（03 §5.1、§6.3）：
     * - 容忍 markdown 代码块包裹与前后解释文字（取第一个 `{` 到最后一个 `}`）
     * - 字段缺失 → 该列表为空；未知字段丢弃；类型不符的条目丢弃
     * - `conf < 0.6` 的条目丢弃
     * - 单条 `v` 超过 [MAX_VALUE_CHARS] 字 → 截断
     *
     * @return 解析成功返回 [MemoryTable]（可能为空表）；完全无法解析返回 null
     */
    fun parse(json: String): MemoryTable? {
        val root = extractObject(json) ?: return null
        return MemoryTable(
            facts = parseList(root, "facts") { parseFact(it) },
            events = parseList(root, "events") { parseEvent(it) },
            loops = parseList(root, "loops") { parseLoop(it) },
            relation = parseRelation(root["relation"]),
            boundaries = parseList(root, "boundaries") { parseBoundary(it) },
            prefs = parseList(root, "prefs") { parsePref(it) },
        )
    }

    /**
     * 双时态合并：`old` 为已存的全集（含历史版本），`patch` 为本次新抽取的补丁。
     *
     * 规则（03 §5.3–§5.4、§6.2，02 §4.4）：
     * 1. 同 key 且同值：刷新 conf / importance / lastSeenAt，不新增条目（保证"同一事实不出现两条有效记录"）。
     * 2. 同 key 不同值：旧条目写 `validTo = 新条目 validFrom` + `SUPERSEDED`，新条目追加为 ACTIVE；**不物理删除**。
     * 3. 不同 key：直接追加。
     * 4. events / prefs / boundaries 以归一化后的 value 作为去重键；loops 在 value 相同时更新 dueAt，
     *    dueAt 变化视为状态变更（旧的 superseded）。
     * 5. relation 按字段覆盖（新值非空优先），只保留当前值。
     * 6. 合并后按 03 §5.2 的存储上限淘汰最弱（importance × 新鲜度最低）的有效条目（同样只标记、不删除）。
     * 7. `patch` 中 conf < 0.6 或空 value 的条目直接忽略。
     *
     * @param old 已存记忆表
     * @param patch 本次抽取的记忆补丁
     * @param now 合并时刻（epochMillis），作为新条目的 validFrom / 旧条目的 validTo 基准
     */
    fun merge(old: MemoryTable, patch: MemoryTable, now: Long): MemoryTable {
        val facts = mergeTemporal(old.facts, sanitizeFacts(patch.facts), now, MemoryTable.MAX_FACTS) {
            normalizeKey(it.key.ifBlank { it.value })
        }
        val events = mergeTemporal(old.events, sanitizeEvents(patch.events), now, MemoryTable.MAX_EVENTS) {
            normalizeText(it.value)
        }
        val prefs = mergeTemporal(old.prefs, sanitizePrefs(patch.prefs), now, MemoryTable.MAX_PREFS) {
            normalizeText(it.value)
        }
        val boundaries =
            mergeTemporal(old.boundaries, sanitizeBoundaries(patch.boundaries), now, MemoryTable.MAX_BOUNDARIES) {
                normalizeText(it.value)
            }
        val loops = mergeLoops(old.loops, patch.loops, now)
        val relation = mergeRelation(old.relation, patch.relation, now)
        return MemoryTable(
            facts = facts,
            events = events,
            loops = loops,
            relation = relation,
            boundaries = boundaries,
            prefs = prefs,
        )
    }


    // 解析


    private fun extractObject(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        val cleaned = FENCE_PATTERN.replace(raw, " ")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val element = runCatching { JSON.parseToJsonElement(cleaned.substring(start, end + 1)) }.getOrNull()
        return element as? JsonObject
    }

    private fun <T> parseList(
        root: JsonObject,
        key: String,
        mapper: (JsonObject) -> T?,
    ): List<T> {
        val array = root[key] as? JsonArray ?: return emptyList()
        val result = ArrayList<T>(array.size)
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val parsed = runCatching { mapper(obj) }.getOrNull() ?: continue
            result.add(parsed)
        }
        return result
    }

    private fun parseFact(obj: JsonObject): Fact? {
        val text = obj.stringValue("v", "value") ?: return null
        val conf = confidenceOf(obj)
        if (conf < MIN_CONFIDENCE) return null
        val value = truncateValue(text)
        val key = obj.stringValue("k", "key")?.let { normalizeKey(it) }
            ?: normalizeKey(value)
        return Fact(
            key = key,
            value = value,
            conf = conf,
            importance = importanceOf(obj, default = 3),
            validFrom = obj.timeValue("valid_from", "validFrom") ?: 0L,
            validTo = obj.timeValue("valid_to", "validTo"),
            status = MemoryStatus.normalize(obj.stringValue("status")),
            firstSeenAt = obj.timeValue("first_seen_at", "firstSeenAt") ?: 0L,
            lastSeenAt = obj.timeValue("last_seen_at", "lastSeenAt") ?: 0L,
            sourceMessageIds = obj.stringList("source_message_ids", "sourceMessageIds"),
        )
    }

    private fun parseEvent(obj: JsonObject): Event? {
        val text = obj.stringValue("v", "value") ?: return null
        val conf = confidenceOf(obj)
        if (conf < MIN_CONFIDENCE) return null
        val happenedAt = obj.timeValue("t", "happened_at", "happenedAt", "date", "valid_from")
        return Event(
            value = truncateValue(text),
            happenedAt = happenedAt,
            conf = conf,
            importance = importanceOf(obj, default = 3),
            validFrom = happenedAt ?: 0L,
            status = MemoryStatus.normalize(obj.stringValue("status")),
            firstSeenAt = obj.timeValue("first_seen_at", "firstSeenAt") ?: 0L,
            lastSeenAt = obj.timeValue("last_seen_at", "lastSeenAt") ?: 0L,
            sourceMessageIds = obj.stringList("source_message_ids", "sourceMessageIds"),
        )
    }

    private fun parseLoop(obj: JsonObject): Loop? {
        val text = obj.stringValue("v", "value") ?: return null
        val conf = confidenceOf(obj)
        if (conf < MIN_CONFIDENCE) return null
        return Loop(
            value = truncateValue(text),
            dueAt = obj.timeValue("due", "due_at", "dueAt"),
            conf = conf,
            importance = importanceOf(obj, default = 4),
            validFrom = obj.timeValue("valid_from", "validFrom") ?: 0L,
            status = MemoryStatus.normalize(obj.stringValue("status")),
            firstSeenAt = obj.timeValue("first_seen_at", "firstSeenAt") ?: 0L,
            lastSeenAt = obj.timeValue("last_seen_at", "lastSeenAt") ?: 0L,
            sourceMessageIds = obj.stringList("source_message_ids", "sourceMessageIds"),
        )
    }

    private fun parseBoundary(obj: JsonObject): Boundary? {
        val text = obj.stringValue("v", "value") ?: return null
        val conf = confidenceOf(obj)
        if (conf < MIN_CONFIDENCE) return null
        return Boundary(
            value = truncateValue(text),
            conf = conf,
            importance = importanceOf(obj, default = 5),
            validFrom = obj.timeValue("valid_from", "validFrom") ?: 0L,
            status = MemoryStatus.normalize(obj.stringValue("status")),
            firstSeenAt = obj.timeValue("first_seen_at", "firstSeenAt") ?: 0L,
            lastSeenAt = obj.timeValue("last_seen_at", "lastSeenAt") ?: 0L,
            sourceMessageIds = obj.stringList("source_message_ids", "sourceMessageIds"),
        )
    }

    private fun parsePref(obj: JsonObject): Pref? {
        val text = obj.stringValue("v", "value") ?: return null
        val conf = confidenceOf(obj)
        if (conf < MIN_CONFIDENCE) return null
        return Pref(
            value = truncateValue(text),
            conf = conf,
            importance = importanceOf(obj, default = 3),
            validFrom = obj.timeValue("valid_from", "validFrom") ?: 0L,
            status = MemoryStatus.normalize(obj.stringValue("status")),
            firstSeenAt = obj.timeValue("first_seen_at", "firstSeenAt") ?: 0L,
            lastSeenAt = obj.timeValue("last_seen_at", "lastSeenAt") ?: 0L,
            sourceMessageIds = obj.stringList("source_message_ids", "sourceMessageIds"),
        )
    }

    private fun parseRelation(element: JsonElement?): Relation? {
        val obj = element as? JsonObject ?: return null
        val relation = Relation(
            stage = obj.stringValue("stage")?.take(MAX_VALUE_CHARS),
            nicknameByMe = obj.stringValue("nickname_by_me", "nicknameByMe")?.take(MAX_VALUE_CHARS),
            nicknameForMe = obj.stringValue("nickname_for_me", "nicknameForMe")?.take(MAX_VALUE_CHARS),
            mood = obj.stringValue("mood")?.take(MAX_VALUE_CHARS),
            updatedAt = obj.timeValue("updated_at", "updatedAt") ?: 0L,
        )
        return if (relation.isBlank()) null else relation
    }

    private fun confidenceOf(obj: JsonObject): Double =
        (obj.doubleValue("conf", "confidence") ?: 1.0).coerceIn(0.0, 1.0)

    private fun importanceOf(obj: JsonObject, default: Int): Int =
        (obj.intValue("imp", "importance") ?: default).coerceIn(1, 5)

    private fun truncateValue(text: String): String = text.trim().take(MAX_VALUE_CHARS)


    // 合并


    private fun sanitizeFacts(entries: List<Fact>): List<Fact> = entries
        .filter { it.isActive() && it.conf >= MIN_CONFIDENCE && it.value.isNotBlank() }
        .map { it.copy(key = it.key.trim(), value = truncateValue(it.value)) }

    private fun sanitizeEvents(entries: List<Event>): List<Event> = entries
        .filter { it.isActive() && it.conf >= MIN_CONFIDENCE && it.value.isNotBlank() }
        .map { it.copy(value = truncateValue(it.value)) }

    private fun sanitizePrefs(entries: List<Pref>): List<Pref> = entries
        .filter { it.isActive() && it.conf >= MIN_CONFIDENCE && it.value.isNotBlank() }
        .map { it.copy(value = truncateValue(it.value)) }

    private fun sanitizeBoundaries(entries: List<Boundary>): List<Boundary> = entries
        .filter { it.isActive() && it.conf >= MIN_CONFIDENCE && it.value.isNotBlank() }
        .map { it.copy(value = truncateValue(it.value)) }

    /**
     * 通用双时态合并。`old` 里的重复有效 key 先在本次合并中收敛为一条（保证唯一有效记录）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : TemporalEntry> mergeTemporal(
        old: List<T>,
        patch: List<T>,
        now: Long,
        cap: Int,
        keyOf: (T) -> String,
    ): List<T> {
        val result = ArrayList<TemporalEntry>(old.size + patch.size)
        val activeIndex = HashMap<String, Int>()

        for (entry in old) {
            if (entry.isActive()) {
                val key = keyOf(entry)
                val previous = activeIndex[key]
                if (previous != null) {
                    result[previous] = result[previous].markSuperseded(now)
                }
                activeIndex[key] = result.size
            }
            result.add(entry)
        }

        for (incoming in patch) {
            if (!incoming.isActive() || incoming.value.isBlank()) continue
            val key = keyOf(incoming)
            val validFrom = if (incoming.validFrom > 0L) incoming.validFrom else now
            val existingIndex = activeIndex[key]
            if (existingIndex != null) {
                val existing = result[existingIndex]
                if (normalizeText(existing.value) == normalizeText(incoming.value)) {
                    result[existingIndex] = existing.refreshed(now, incoming.conf, incoming.importance)
                    continue
                }
                result[existingIndex] = existing.markSuperseded(validFrom)
                activeIndex.remove(key)
            }
            result.add(incoming.activate(validFrom, now))
            activeIndex[key] = result.lastIndex
        }

        enforceCap(result, now, cap)
        return result as List<T>
    }

    private fun mergeLoops(old: List<Loop>, patch: List<Loop>, now: Long): List<Loop> {
        val result = ArrayList<Loop>(old.size + patch.size)
        val activeIndex = HashMap<String, Int>()

        for (loop in old) {
            if (loop.isActive()) {
                val key = normalizeText(loop.value)
                val previous = activeIndex[key]
                if (previous != null) {
                    result[previous] = result[previous].copy(validTo = now, status = MemoryStatus.SUPERSEDED)
                }
                activeIndex[key] = result.size
            }
            result.add(loop)
        }

        for (candidate in patch) {
            if (!candidate.isActive() || candidate.value.isBlank() || candidate.conf < MIN_CONFIDENCE) continue
            val incoming = candidate.copy(value = truncateValue(candidate.value))
            val key = normalizeText(incoming.value)
            val existingIndex = activeIndex[key]
            val validFrom = if (incoming.validFrom > 0L) incoming.validFrom else now
            if (existingIndex != null) {
                val current = result[existingIndex]
                val dueUnchanged = incoming.dueAt == null || current.dueAt == incoming.dueAt
                if (dueUnchanged) {
                    result[existingIndex] = current.copy(
                        dueAt = incoming.dueAt ?: current.dueAt,
                        conf = maxOf(current.conf, incoming.conf),
                        importance = maxOf(current.importance, incoming.importance),
                        lastSeenAt = maxOf(current.lastSeenAt, now),
                    )
                    continue
                }
                result[existingIndex] = current.copy(validTo = validFrom, status = MemoryStatus.SUPERSEDED)
                activeIndex.remove(key)
            }
            result.add(incoming.activate(validFrom, now) as Loop)
            activeIndex[key] = result.lastIndex
        }

        val active = result.indices.filter { result[it].isActive() }
        if (active.size > MemoryTable.MAX_LOOPS) {
            val victims = active.map { it to result[it] }
                .sortedWith(compareBy<Pair<Int, Loop>>({ it.second.lastSeenAt }, { it.second.validFrom }))
                .take(active.size - MemoryTable.MAX_LOOPS)
            for ((index, loop) in victims) {
                result[index] = loop.copy(validTo = now, status = MemoryStatus.SUPERSEDED)
            }
        }
        return result
    }

    private fun mergeRelation(old: Relation?, patch: Relation?, now: Long): Relation? {
        if (patch == null) return old
        val merged = Relation(
            stage = pick(patch.stage, old?.stage),
            nicknameByMe = pick(patch.nicknameByMe, old?.nicknameByMe),
            nicknameForMe = pick(patch.nicknameForMe, old?.nicknameForMe),
            mood = pick(patch.mood, old?.mood),
            updatedAt = now,
        )
        return if (merged.isBlank()) null else merged
    }

    private fun pick(incoming: String?, fallback: String?): String? =
        incoming?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }

    /**
     * 存储上限：有效条目超过 cap 时，按 importance 低 → 更久未被看到 的顺序标记失效
     * （仍然不物理删除，符合 append-only + 双时态）。
     */
    private fun enforceCap(result: MutableList<TemporalEntry>, now: Long, cap: Int) {
        val active = result.indices.filter { result[it].isActive() }
        if (active.size <= cap) return
        val victims = active.map { it to result[it] }
            .sortedWith(
                compareBy<Pair<Int, TemporalEntry>>(
                    { it.second.importance },
                    { it.second.lastSeenAt },
                    { it.second.validFrom },
                ),
            )
            .take(active.size - cap)
        for ((index, entry) in victims) {
            result[index] = entry.markSuperseded(now)
        }
    }

    private fun normalizeKey(raw: String): String = normalizeText(raw)

    private fun normalizeText(raw: String): String =
        WHITESPACE_PATTERN.replace(raw.trim(), " ").lowercase()


    // JSON 取值小工具（全部容错，类型不符返回 null）


    private fun JsonObject.stringValue(vararg keys: String): String? {
        for (key in keys) {
            val element = this[key]
            if (element == null || element is JsonNull) continue
            val primitive = element as? JsonPrimitive ?: continue
            if (!primitive.isString) continue
            val text = primitive.content
            if (text.isNotBlank()) return text
        }
        return null
    }

    private fun JsonObject.doubleValue(vararg keys: String): Double? {
        for (key in keys) {
            val element = this[key]
            if (element == null || element is JsonNull) continue
            val primitive = element as? JsonPrimitive ?: continue
            primitive.doubleOrNull?.let { return it }
        }
        return null
    }

    private fun JsonObject.intValue(vararg keys: String): Int? {
        val value = doubleValue(*keys) ?: return null
        return value.toInt()
    }

    private fun JsonObject.timeValue(vararg keys: String): Long? {
        for (key in keys) {
            val element = this[key]
            if (element == null || element is JsonNull) continue
            val primitive = element as? JsonPrimitive ?: continue
            if (primitive.isString) {
                MemTime.parse(primitive.content)?.let { return it }
            } else {
                primitive.longOrNull?.let { return MemTime.normalizeEpoch(it) }
            }
        }
        return null
    }

    private fun JsonObject.stringList(vararg keys: String): List<String> {
        for (key in keys) {
            val array = this[key] as? JsonArray ?: continue
            val result = ArrayList<String>(minOf(array.size, MAX_SOURCE_MESSAGE_IDS))
            for (element in array) {
                val primitive = element as? JsonPrimitive ?: continue
                if (!primitive.isString) continue
                val text = primitive.content.trim()
                if (text.isNotEmpty()) result.add(text)
                if (result.size >= MAX_SOURCE_MESSAGE_IDS) break
            }
            return result
        }
        return emptyList()
    }
}
