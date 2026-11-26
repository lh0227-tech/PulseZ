package cc.pulsez.app.mem

/**
 * 对话消息的最小表示，与持久层解耦。
 *
 * 对应文档：03 §3.1（Prompt 五段布局）、02 §3（message 表）。
 * 本模块是纯 Kotlin/JVM，不依赖 Android 与 Room，因此只保留组装、估算、抽取所需的字段。
 *
 * @property role 角色，取 [ROLE_SYSTEM] / [ROLE_USER] / [ROLE_ASSISTANT] / [ROLE_LATEST_REMINDER]
 * @property content 消息正文
 * @property createdAt 创建时间（epochMillis，0 表示未知）
 * @property sourceId 对应持久层 `MessageEntity.id`（可选，供调用方精准挂载多模态附件，脱离下标猜测）
 * @property reasoningContent DeepSeek 历史助手轮思考链内容（可选，仅 DeepSeek 端点回传）
 * @property serverToolCallsJson DeepSeek 历史助手轮服务端工具调用 JSON 数组串（可选，仅 DeepSeek 端点回传）
 * @property latestReminder DeepSeek 历史用户轮对应的 `latest_reminder` 文本（可选，仅 DeepSeek 端点回传）
 */
data class MemMessage(
    val role: String,
    val content: String,
    val createdAt: Long = 0L,
    val sourceId: String? = null,
    val reasoningContent: String? = null,
    val serverToolCallsJson: String? = null,
    val latestReminder: String? = null,
) {
    companion object {
        /** system 消息的 role 常量。 */
        const val ROLE_SYSTEM: String = "system"

        /** user 消息的 role 常量。 */
        const val ROLE_USER: String = "user"

        /** assistant 消息的 role 常量。 */
        const val ROLE_ASSISTANT: String = "assistant"

        /** DeepSeek 场景感知消息的 role 常量（紧贴 user 消息正前方）。 */
        const val ROLE_LATEST_REMINDER: String = "latest_reminder"

        /** 构造一条 system 消息（对应 03 §3.1 的 ①②④ 段）。 */
        fun system(content: String, createdAt: Long = 0L): MemMessage =
            MemMessage(ROLE_SYSTEM, content, createdAt)

        /** 构造一条 user 消息（对应 03 §3.1 的 ③⑤ 段）。 */
        fun user(
            content: String,
            createdAt: Long = 0L,
            sourceId: String? = null,
            latestReminder: String? = null,
        ): MemMessage = MemMessage(
            role = ROLE_USER,
            content = content,
            createdAt = createdAt,
            sourceId = sourceId,
            latestReminder = latestReminder,
        )

        /** 构造一条 assistant 消息（对应 03 §3.1 的 ③ 段）。 */
        fun assistant(
            content: String,
            createdAt: Long = 0L,
            sourceId: String? = null,
            reasoningContent: String? = null,
            serverToolCallsJson: String? = null,
        ): MemMessage = MemMessage(
            role = ROLE_ASSISTANT,
            content = content,
            createdAt = createdAt,
            sourceId = sourceId,
            reasoningContent = reasoningContent,
            serverToolCallsJson = serverToolCallsJson,
        )

        /** 构造一条 latest_reminder 消息（DeepSeek 官方场景感知标记）。 */
        fun latestReminder(content: String, createdAt: Long = 0L): MemMessage =
            MemMessage(ROLE_LATEST_REMINDER, content, createdAt)
    }
}

/**
 * 记忆条目状态枚举（02 §3 memory_item.status、03 §5.3–§5.4）。
 *
 * 双时态下"有效"由 [TemporalEntry.isActive] 判定：`validTo == null` 且状态不是
 * [SUPERSEDED] / [DELETED]。[UNVERIFIED] 仍然渲染，但会标注"她可能记混了"。
 */
object MemoryStatus {
    /** 当前有效。 */
    const val ACTIVE: String = "ACTIVE"

    /** 已被更新的事实覆盖（validTo 非空）。 */
    const val SUPERSEDED: String = "SUPERSEDED"

    /** 用户主动删除。 */
    const val DELETED: String = "DELETED"

    /** 用户置顶。 */
    const val PINNED: String = "PINNED"

    /** 来源消息被删除，降级为"可能记混"。 */
    const val UNVERIFIED: String = "UNVERIFIED"

    private val KNOWN: Set<String> = setOf(ACTIVE, SUPERSEDED, DELETED, PINNED, UNVERIFIED)

    /**
     * 归一化状态字符串；未知值回落到 [ACTIVE]（03 §5.4 的容错要求）。
     */
    fun normalize(raw: String?): String {
        val value = raw?.trim()?.uppercase().orEmpty()
        return if (value in KNOWN) value else ACTIVE
    }
}

/**
 * 双时态记忆条目的公共视图（03 §5.3、02 §3 memory_item）。
 *
 * 覆盖只写 `validTo` + 状态，不物理删除；渲染时只取 [isActive] 的条目。
 */
interface TemporalEntry {
    /** 正文（≤ 200 字）。 */
    val value: String

    /** 重要度 1–5，事件/线索必填。 */
    val importance: Int

    /** 置信度 0–1，低于 0.6 不写入（03 §5.4）。 */
    val conf: Double

    /** 双时态：从何时起为真（epochMillis，0 = 未知）。 */
    val validFrom: Long

    /** 双时态：到何时不再为真（null = 仍然有效）。 */
    val validTo: Long?

    /** 状态，取 [MemoryStatus]。 */
    val status: String

    /** 首次出现时间（epochMillis）。 */
    val firstSeenAt: Long

    /** 最近一次出现时间（epochMillis），用于新鲜度排序。 */
    val lastSeenAt: Long

    /** 归一化键，用于"同 key 新覆盖旧"；null 表示按键以外的规则去重。 */
    val key: String?

    /** 当前是否有效（渲染/合并只看有效的条目）。 */
    fun isActive(): Boolean =
        validTo == null && status != MemoryStatus.DELETED && status != MemoryStatus.SUPERSEDED

    /**
     * 标记被覆盖：写入 `validTo` 与 [MemoryStatus.SUPERSEDED]（03 §5.3，02 §4.4）。
     *
     * @param validTo 失效时刻（通常等于新条目的 validFrom）
     */
    fun markSuperseded(validTo: Long, status: String = MemoryStatus.SUPERSEDED): TemporalEntry

    /**
     * 同一条事实再次被确认：刷新 conf / importance / lastSeenAt，不产生新条目。
     *
     * conf 取本次抽取值（允许被新证据降低），importance 取两者较大值。
     */
    fun refreshed(now: Long, conf: Double, importance: Int): TemporalEntry

    /**
     * 生成一条"新写入"的条目：清空 validTo、置为 ACTIVE、补齐首末次出现时间。
     */
    fun activate(validFrom: Long, now: Long): TemporalEntry
}

/**
 * 事实类记忆（03 §5.1 `facts`，02 §3 `FACT`，上限 120 条）。
 *
 * @property key 归一化键（如"称呼"），同 key 新值覆盖旧值
 * @property value 正文，≤ 200 字
 */
data class Fact(
    override val key: String = "",
    override val value: String,
    override val conf: Double = 1.0,
    override val importance: Int = 3,
    override val validFrom: Long = 0L,
    override val validTo: Long? = null,
    override val status: String = MemoryStatus.ACTIVE,
    override val firstSeenAt: Long = 0L,
    override val lastSeenAt: Long = 0L,
    val sourceMessageIds: List<String> = emptyList(),
) : TemporalEntry {
    override fun markSuperseded(validTo: Long, status: String): TemporalEntry =
        copy(validTo = validTo, status = status)

    override fun refreshed(now: Long, conf: Double, importance: Int): TemporalEntry = copy(
        conf = conf,
        importance = maxOf(this.importance, importance),
        lastSeenAt = maxOf(lastSeenAt, now),
    )

    override fun activate(validFrom: Long, now: Long): TemporalEntry = copy(
        validFrom = validFrom,
        validTo = null,
        status = MemoryStatus.ACTIVE,
        firstSeenAt = if (firstSeenAt > 0L) firstSeenAt else now,
        lastSeenAt = now,
    )
}

/**
 * 事件类记忆（03 §5.1 `events`，02 §3 `EVENT`，上限 100 条）。
 *
 * @property value 正文，≤ 200 字
 * @property happenedAt 事件发生时间（epochMillis，可空）
 */
data class Event(
    override val value: String,
    val happenedAt: Long? = null,
    override val conf: Double = 1.0,
    override val importance: Int = 3,
    override val validFrom: Long = 0L,
    override val validTo: Long? = null,
    override val status: String = MemoryStatus.ACTIVE,
    override val firstSeenAt: Long = 0L,
    override val lastSeenAt: Long = 0L,
    val sourceMessageIds: List<String> = emptyList(),
) : TemporalEntry {
    override val key: String? get() = null

    override fun markSuperseded(validTo: Long, status: String): TemporalEntry =
        copy(validTo = validTo, status = status)

    override fun refreshed(now: Long, conf: Double, importance: Int): TemporalEntry = copy(
        conf = conf,
        importance = maxOf(this.importance, importance),
        lastSeenAt = maxOf(lastSeenAt, now),
    )

    override fun activate(validFrom: Long, now: Long): TemporalEntry = copy(
        validFrom = validFrom,
        validTo = null,
        status = MemoryStatus.ACTIVE,
        firstSeenAt = if (firstSeenAt > 0L) firstSeenAt else now,
        lastSeenAt = now,
    )
}

/**
 * 未完结线索（03 §5.1 `loops`，02 §3 `LOOP`，上限 20 条）。
 *
 * @property value 正文，≤ 200 字
 * @property dueAt 到期时间（epochMillis，可空）
 */
data class Loop(
    override val value: String,
    val dueAt: Long? = null,
    override val conf: Double = 1.0,
    override val importance: Int = 4,
    override val validFrom: Long = 0L,
    override val validTo: Long? = null,
    override val status: String = MemoryStatus.ACTIVE,
    override val firstSeenAt: Long = 0L,
    override val lastSeenAt: Long = 0L,
    val sourceMessageIds: List<String> = emptyList(),
) : TemporalEntry {
    override val key: String? get() = null

    override fun markSuperseded(validTo: Long, status: String): TemporalEntry =
        copy(validTo = validTo, status = status)

    override fun refreshed(now: Long, conf: Double, importance: Int): TemporalEntry = copy(
        conf = conf,
        importance = maxOf(this.importance, importance),
        lastSeenAt = maxOf(lastSeenAt, now),
    )

    override fun activate(validFrom: Long, now: Long): TemporalEntry = copy(
        validFrom = validFrom,
        validTo = null,
        status = MemoryStatus.ACTIVE,
        firstSeenAt = if (firstSeenAt > 0L) firstSeenAt else now,
        lastSeenAt = now,
    )
}

/**
 * 偏好类记忆（03 §5.1 `prefs`，02 §3 `PREF`，上限 60 条）。
 */
data class Pref(
    override val value: String,
    override val conf: Double = 1.0,
    override val importance: Int = 3,
    override val validFrom: Long = 0L,
    override val validTo: Long? = null,
    override val status: String = MemoryStatus.ACTIVE,
    override val firstSeenAt: Long = 0L,
    override val lastSeenAt: Long = 0L,
    val sourceMessageIds: List<String> = emptyList(),
) : TemporalEntry {
    override val key: String? get() = null

    override fun markSuperseded(validTo: Long, status: String): TemporalEntry =
        copy(validTo = validTo, status = status)

    override fun refreshed(now: Long, conf: Double, importance: Int): TemporalEntry = copy(
        conf = conf,
        importance = maxOf(this.importance, importance),
        lastSeenAt = maxOf(lastSeenAt, now),
    )

    override fun activate(validFrom: Long, now: Long): TemporalEntry = copy(
        validFrom = validFrom,
        validTo = null,
        status = MemoryStatus.ACTIVE,
        firstSeenAt = if (firstSeenAt > 0L) firstSeenAt else now,
        lastSeenAt = now,
    )
}

/**
 * 边界类记忆（03 §5.1 `boundaries`，02 §3 `BOUNDARY`，上限 20 条）。
 *
 * 边界永远全量渲染（03 §5.2），且不允许被"记忆不该说"规则越过（03 §5.5）。
 */
data class Boundary(
    override val value: String,
    override val conf: Double = 1.0,
    override val importance: Int = 5,
    override val validFrom: Long = 0L,
    override val validTo: Long? = null,
    override val status: String = MemoryStatus.ACTIVE,
    override val firstSeenAt: Long = 0L,
    override val lastSeenAt: Long = 0L,
    val sourceMessageIds: List<String> = emptyList(),
) : TemporalEntry {
    override val key: String? get() = null

    override fun markSuperseded(validTo: Long, status: String): TemporalEntry =
        copy(validTo = validTo, status = status)

    override fun refreshed(now: Long, conf: Double, importance: Int): TemporalEntry = copy(
        conf = conf,
        importance = maxOf(this.importance, importance),
        lastSeenAt = maxOf(lastSeenAt, now),
    )

    override fun activate(validFrom: Long, now: Long): TemporalEntry = copy(
        validFrom = validFrom,
        validTo = null,
        status = MemoryStatus.ACTIVE,
        firstSeenAt = if (firstSeenAt > 0L) firstSeenAt else now,
        lastSeenAt = now,
    )
}

/**
 * 关系状态（03 §5.1 `relation`，02 §3 `thread_state`，全局唯一一条）。
 *
 * @property stage 关系阶段：陌生/熟悉/暧昧/疏远/吵架中
 * @property nicknameByMe 她怎么叫用户
 * @property nicknameForMe 用户怎么叫她
 * @property mood 当前气氛
 */
data class Relation(
    val stage: String? = null,
    val nicknameByMe: String? = null,
    val nicknameForMe: String? = null,
    val mood: String? = null,
    val updatedAt: Long = 0L,
) {
    /** 四个字段全空视为无效关系（解析时返回 null）。 */
    fun isBlank(): Boolean =
        stage.isNullOrBlank() && nicknameByMe.isNullOrBlank() &&
            nicknameForMe.isNullOrBlank() && mood.isNullOrBlank()
}

/**
 * 记忆表：03 §5.1 的六个顶层字段（02 §3 memory_item 的聚合视图）。
 *
 * 列表保存的是**双时态全集**（含已被覆盖的条目），渲染只取有效条目；
 * 存储上限见 [companion object][Companion]。
 */
data class MemoryTable(
    val facts: List<Fact> = emptyList(),
    val events: List<Event> = emptyList(),
    val loops: List<Loop> = emptyList(),
    val relation: Relation? = null,
    val boundaries: List<Boundary> = emptyList(),
    val prefs: List<Pref> = emptyList(),
) {
    /** 当前有效的事实条目。 */
    fun activeFacts(): List<Fact> = facts.filter { it.isActive() }

    /** 当前有效的事件条目。 */
    fun activeEvents(): List<Event> = events.filter { it.isActive() }

    /** 当前有效的未完结线索。 */
    fun activeLoops(): List<Loop> = loops.filter { it.isActive() }

    /** 当前有效的边界条目。 */
    fun activeBoundaries(): List<Boundary> = boundaries.filter { it.isActive() }

    /** 当前有效的偏好条目。 */
    fun activePrefs(): List<Pref> = prefs.filter { it.isActive() }

    /** 除关系外的全部有效条目，供渲染排序使用。 */
    fun activeEntries(): List<TemporalEntry> =
        activeFacts() + activeEvents() + activeLoops() + activeBoundaries() + activePrefs()

    /** 有效条目总数（存储上限按此计数，03 §5.2）。 */
    fun activeCount(): Int = activeEntries().size

    /** 是否完全为空（不影响人设，仅用于组装时跳过空块）。 */
    fun isEmpty(): Boolean = activeCount() == 0 && (relation == null || relation.isBlank())

    companion object {
        /** facts 存储上限（03 §5.2）。 */
        const val MAX_FACTS: Int = 120

        /** events 存储上限（03 §5.2）。 */
        const val MAX_EVENTS: Int = 100

        /** loops 存储上限（03 §5.2）。 */
        const val MAX_LOOPS: Int = 20

        /** relation 存储上限（单条，03 §5.2）。 */
        const val MAX_RELATION: Int = 1

        /** boundaries 存储上限（03 §5.2）。 */
        const val MAX_BOUNDARIES: Int = 20

        /** prefs 存储上限（03 §5.2）。 */
        const val MAX_PREFS: Int = 60

        /** 合计存储上限 321 条（03 §5.2）。 */
        const val MAX_TOTAL: Int = MAX_FACTS + MAX_EVENTS + MAX_LOOPS + MAX_RELATION + MAX_BOUNDARIES + MAX_PREFS
    }
}


// 共享字符工具（TokenEstimator / BigramIndex 共用）


/** 判断一个 code point 是否属于 CJK 区（含中文标点、全角字符、扩展 A/兼容区）。 */
internal fun isCjkCodePoint(codePoint: Int): Boolean = when (codePoint) {
    in 0x3000..0x303F -> true // CJK 标点与符号（，。、《》等）
    in 0x3400..0x4DBF -> true // 扩展 A
    in 0x4E00..0x9FFF -> true // 基本区
    in 0xF900..0xFAFF -> true // 兼容表意文字
    in 0xFF00..0xFFEF -> true // 全角形式
    in 0x20000..0x2FA1F -> true // 扩展 B–F
    else -> false
}

/** UTF-16 编码下该 code point 占用的 char 数（1 或 2）。 */
internal fun charCountOf(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1
