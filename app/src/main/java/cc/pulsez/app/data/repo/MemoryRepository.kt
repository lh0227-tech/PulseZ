package cc.pulsez.app.data.repo

import androidx.room.withTransaction
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.ChunkTermEntity
import cc.pulsez.app.data.db.MemoryItemEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.ThreadStateEntity
import cc.pulsez.app.mem.Boundary
import cc.pulsez.app.mem.Event
import cc.pulsez.app.mem.Fact
import cc.pulsez.app.mem.Loop
import cc.pulsez.app.mem.MemorySchema
import cc.pulsez.app.mem.MemoryStatus
import cc.pulsez.app.mem.MemoryTable
import cc.pulsez.app.mem.Pref
import cc.pulsez.app.mem.Relation
import cc.pulsez.app.util.Ids

/** 向量服务的最小抽象；net 层实现，避免数据层直接依赖 HTTP。 */
interface Embedder {
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/**
 * 记忆与检索块的持久化适配（memory_item / chunk / chunk_term–§7）。
 *
 * 设计要点：
 * - 记忆表保存**双时态全集**（含被覆盖条目），渲染只取 active（03 §5.3）；
 * - 每次合并后整表重写该 thread 的行：行数很小（≤ 数百），换来实现简单与事务原子；
 * - chunk 的删除要传播（02 §4 规则 6）。
 */
class MemoryRepository(private val db: PulsezDatabase) {

    // ---------------------------------------------------------------- 记忆表

    /**
     * 读某个 owner 维度的记忆表。
     *
     * @param ownerId 群聊传该角色 id；单聊传 null（沿用 thread 维度）。
     *   旧单聊调用 `load(threadId)` 不受影响（ownerId 默认 null）。
     */
    suspend fun load(threadId: String, ownerId: String? = null): MemoryTable {
        val rows = db.memoryDao().forOwner(threadId, ownerId)
        if (rows.isEmpty()) return MemoryTable()
        val facts = rows.filter { it.kind == "FACT" }.map { row ->
            Fact(
                key = row.key.orEmpty(),
                value = row.value,
                conf = row.confidence.toDouble(),
                importance = row.importance,
                validFrom = row.validFrom,
                validTo = row.validTo,
                status = row.status,
                firstSeenAt = row.firstSeenAt,
                lastSeenAt = row.lastSeenAt,
                sourceMessageIds = splitSources(row.sourceMessageIds),
            )
        }
        val events = rows.filter { it.kind == "EVENT" }.map { row ->
            Event(
                value = row.value,
                happenedAt = row.happenedAt,
                conf = row.confidence.toDouble(),
                importance = row.importance,
                validFrom = row.validFrom,
                validTo = row.validTo,
                status = row.status,
                firstSeenAt = row.firstSeenAt,
                lastSeenAt = row.lastSeenAt,
                sourceMessageIds = splitSources(row.sourceMessageIds),
            )
        }
        val loops = rows.filter { it.kind == "LOOP" }.map { row ->
            Loop(
                value = row.value,
                dueAt = row.dueAt,
                conf = row.confidence.toDouble(),
                importance = row.importance,
                validFrom = row.validFrom,
                validTo = row.validTo,
                status = row.status,
                firstSeenAt = row.firstSeenAt,
                lastSeenAt = row.lastSeenAt,
                sourceMessageIds = splitSources(row.sourceMessageIds),
            )
        }
        val prefs = rows.filter { it.kind == "PREF" }.map { row ->
            Pref(
                value = row.value,
                conf = row.confidence.toDouble(),
                importance = row.importance,
                validFrom = row.validFrom,
                validTo = row.validTo,
                status = row.status,
                firstSeenAt = row.firstSeenAt,
                lastSeenAt = row.lastSeenAt,
                sourceMessageIds = splitSources(row.sourceMessageIds),
            )
        }
        val boundaries = rows.filter { it.kind == "BOUNDARY" }.map { row ->
            Boundary(
                value = row.value,
                conf = row.confidence.toDouble(),
                importance = row.importance,
                validFrom = row.validFrom,
                validTo = row.validTo,
                status = row.status,
                firstSeenAt = row.firstSeenAt,
                lastSeenAt = row.lastSeenAt,
                sourceMessageIds = splitSources(row.sourceMessageIds),
            )
        }
        return MemoryTable(
            facts = facts,
            events = events,
            loops = loops,
            relation = loadRelation(threadId, ownerId),
            boundaries = boundaries,
            prefs = prefs,
        )
    }

    suspend fun activeCount(threadId: String, ownerId: String? = null): Int =
        db.memoryDao().activeCountForOwner(threadId, ownerId)

    /** 合并一次抽取补丁并落库；返回是否写入了东西。 */
    suspend fun applyPatch(
        threadId: String,
        patchJson: String,
        segmentId: String?,
        now: Long = System.currentTimeMillis(),
        ownerId: String? = null,
    ): Boolean {
        val patch = MemorySchema.parse(patchJson) ?: return false
        return db.withTransaction {
            // A model reply may arrive after batch undo. Check all cited messages and write under the same
            // SQLite transaction: if undo deleted any imported source first, this late patch is discarded;
            // if this patch commits first, undo observes the changed memory fingerprint and fails closed.
            val citedIds = buildList {
                patch.facts.forEach { addAll(it.sourceMessageIds) }
                patch.events.forEach { addAll(it.sourceMessageIds) }
                patch.loops.forEach { addAll(it.sourceMessageIds) }
                patch.prefs.forEach { addAll(it.sourceMessageIds) }
                patch.boundaries.forEach { addAll(it.sourceMessageIds) }
            }.filter(String::isNotBlank).distinct()
            // A tombstoned import can only be undone exactly if delayed derived-memory writes remain
            // attributable. On a thread with an undone batch, source-less model patches are rejected;
            // manual memory edits use save() and are unaffected.
            if (citedIds.isEmpty() && db.importBatchDao().hasUndoneBatchForThread(threadId)) {
                return@withTransaction false
            }
            val existing = citedIds.chunked(ImportRepository.SQLITE_VARIABLE_BATCH)
                .flatMap { db.messageDao().existingIdsForThread(threadId, it) }
                .toSet()
            if (existing.size != citedIds.size) return@withTransaction false
            val current = load(threadId, ownerId)
            val merged = MemorySchema.merge(current, patch, now)
            save(threadId, merged, segmentId, now, ownerId)
            merged != current
        }
    }

    /**
     * 覆盖式写某个 owner 维度的记忆表：只替换该 `(threadId, characterId)` 的行，
     * 不会碰同 thread 下其它角色的记忆（「隔离原则」）。
     *
     * @param ownerId 群聊传角色 id；单聊传 null。旧调用 `save(threadId, table, segmentId, now)` 不变。
     */
    suspend fun save(
        threadId: String,
        table: MemoryTable,
        segmentId: String?,
        now: Long,
        ownerId: String? = null,
    ) {
        val rows = buildList {
            table.facts.forEach { entry -> add(toRow(ownerId, threadId, "FACT", entry.key, entry.value, entry.conf, entry.importance, null, null, entry.validFrom, entry.validTo, entry.status, entry.firstSeenAt, entry.lastSeenAt, entry.sourceMessageIds, segmentId)) }
            table.events.forEach { entry -> add(toRow(ownerId, threadId, "EVENT", null, entry.value, entry.conf, entry.importance, entry.happenedAt, null, entry.validFrom, entry.validTo, entry.status, entry.firstSeenAt, entry.lastSeenAt, entry.sourceMessageIds, segmentId)) }
            table.loops.forEach { entry -> add(toRow(ownerId, threadId, "LOOP", null, entry.value, entry.conf, entry.importance, null, entry.dueAt, entry.validFrom, entry.validTo, entry.status, entry.firstSeenAt, entry.lastSeenAt, entry.sourceMessageIds, segmentId)) }
            table.prefs.forEach { entry -> add(toRow(ownerId, threadId, "PREF", null, entry.value, entry.conf, entry.importance, null, null, entry.validFrom, entry.validTo, entry.status, entry.firstSeenAt, entry.lastSeenAt, entry.sourceMessageIds, segmentId)) }
            table.boundaries.forEach { entry -> add(toRow(ownerId, threadId, "BOUNDARY", null, entry.value, entry.conf, entry.importance, null, null, entry.validFrom, entry.validTo, entry.status, entry.firstSeenAt, entry.lastSeenAt, entry.sourceMessageIds, segmentId)) }
        }
        // Memory rows and the separately stored relation state are one write unit. This makes the
        // import undo fingerprint check atomic against every memory mutation path.
        db.withTransaction {
            db.memoryDao().replaceForOwner(threadId, ownerId, rows)
            table.relation?.let { saveRelation(threadId, it, now, ownerId) }
        }
    }

    /** 用户删掉一条消息后：来源失效的记忆降级为"可能记混了"（03 §5.4）。 */
    suspend fun onMessageDeleted(threadId: String, messageId: String, now: Long) {
        db.memoryDao().markUnverifiedBySource(threadId, messageId)
    }

    /**
     * 用户手动"忘掉"一条记忆：置 DELETED，不物理删除，也不复活更早的版本（02 §4 规则 5）。
     *
     * @param kind FACT / EVENT / LOOP / PREF / BOUNDARY
     * @param ownerId 群聊传角色 id（只动该角色的记忆）；单聊传 null，旧调用签名不变。
     */
    suspend fun deleteEntry(threadId: String, kind: String, value: String, ownerId: String? = null) {
        val now = System.currentTimeMillis()
        val table = load(threadId, ownerId)
        val updated = when (kind) {
            "FACT" -> table.copy(facts = table.facts.map { if (it.value == value && it.isActive()) it.copy(status = MemoryStatus.DELETED) else it })
            "EVENT" -> table.copy(events = table.events.map { if (it.value == value && it.isActive()) it.copy(status = MemoryStatus.DELETED) else it })
            "LOOP" -> table.copy(loops = table.loops.map { if (it.value == value && it.isActive()) it.copy(status = MemoryStatus.DELETED) else it })
            "PREF" -> table.copy(prefs = table.prefs.map { if (it.value == value && it.isActive()) it.copy(status = MemoryStatus.DELETED) else it })
            "BOUNDARY" -> table.copy(boundaries = table.boundaries.map { if (it.value == value && it.isActive()) it.copy(status = MemoryStatus.DELETED) else it })
            "RELATION" -> table.copy(relation = null)
            else -> table
        }
        if (kind == "RELATION" && ownerId == null) {
            // thread_state 目前只有 threadId 单行主键，没有角色维度；单聊照旧清空。
            // 群聊的 RELATION 暂不落 thread_state（避免 A 的关系状态串到 B），见类注释的已知缺口。
            db.threadStateDao().upsert(
                ThreadStateEntity(threadId, null, null, null, null, now),
            )
        }
        save(threadId, updated, segmentId = null, now = now, ownerId = ownerId)
    }

    /**
     * 新增一条「别再提这件事」的边界（BOUNDARY）——用户对记忆的直接控制（唯一新增 API）。
     *
     * 语义：用户说过「不要提这件事 / 别再聊这个」之后，把它写进 `boundaries`。
     * ` 的「许可」判据要求「不在 boundaries 里」才允许自然提起；渲染时
     * `boundaries` 还会**全量渲染**（§5.2），所以写进来就能立刻生效。
     *
     * 实现要点：
     * - 走 [MemorySchema.merge]，不手拼列表：同一句话重复添加只会刷新 `lastSeenAt`（**去重**，
     *   不会出现两条有效记录），且自动遵守 `boundaries` 存储上限 20 条（§5.2，超出按
     *   importance × 新鲜度淘汰最弱的一条，仍然只标记不物理删除）；
     * - `conf = 1.0`、`importance = 5`（边界是最高优先级，不允许被其它记忆规则越过，§5.5）；
     * - `validFrom = now`、`status = ACTIVE`，`segmentId = null`（不是模型抽取产物，不挂段）；
     * - 空文本直接忽略（UI 侧不应产生，但防御脏输入）。
     *
     * 依据（记忆「可见/可改/可删 + 不想被提起清单」是控制权）、
     * §2.4（禁止场景「在 boundaries 里」） （用户控制/撤销口径）、§8
     * （许可）、§5.2（boundaries 上限 20 且全量渲染）。
     *
     * @param ownerId 群聊传角色 id（只影响该角色的记忆）；单聊传 null（默认）
     */
    suspend fun addBoundary(threadId: String, text: String, ownerId: String? = null) {
        val value = text.trim().take(MemorySchema.MAX_VALUE_CHARS)
        if (value.isEmpty()) return
        val now = System.currentTimeMillis()
        val current = load(threadId, ownerId)
        val merged = MemorySchema.merge(
            old = current,
            patch = MemoryTable(
                boundaries = listOf(
                    Boundary(
                        value = value,
                        conf = 1.0,
                        // 5 = 最高重要度 boundaries 全量渲染、§5.5 优先级最高。
                        importance = 5,
                        validFrom = now,
                        status = MemoryStatus.ACTIVE,
                        firstSeenAt = now,
                        lastSeenAt = now,
                    ),
                ),
            ),
            now = now,
        )
        save(threadId, merged, segmentId = null, now = now, ownerId = ownerId)
    }

    /**
     * 读关系状态（thread_state）。
     *
     * 已知缺口（v2 不在 契约内）：`thread_state` 只有 threadId 单行主键，
     * 没有 characterId 维度。群聊里若读共享行，会把 A 的关系状态塞给 B，违反「记忆按角色隔离」。
     * 因此群聊 owner 维度一律返回 null（安全降级），单聊行为不变。
     */
    private suspend fun loadRelation(threadId: String, ownerId: String?): Relation? {
        if (ownerId != null) return null
        val row = db.threadStateDao().get(threadId) ?: return null
        val relation = Relation(
            stage = row.relationStage,
            nicknameByMe = row.nicknameByMe,
            nicknameForMe = row.nicknameForMe,
            mood = row.mood,
            updatedAt = row.updatedAt,
        )
        return relation.takeUnless { it.isBlank() }
    }

    /** 仅单聊维度写 thread_state；群聊维度不写（同上，避免跨角色串味）。 */
    private suspend fun saveRelation(threadId: String, relation: Relation, now: Long, ownerId: String?) {
        if (ownerId != null) return
        db.threadStateDao().upsert(
            ThreadStateEntity(
                threadId = threadId,
                relationStage = relation.stage,
                nicknameByMe = relation.nicknameByMe,
                nicknameForMe = relation.nicknameForMe,
                mood = relation.mood,
                updatedAt = maxOf(relation.updatedAt, now),
            ),
        )
    }

    private fun toRow(
        ownerId: String?,
        threadId: String,
        kind: String,
        key: String?,
        value: String,
        conf: Double,
        importance: Int,
        happenedAt: Long?,
        dueAt: Long?,
        validFrom: Long,
        validTo: Long?,
        status: String,
        firstSeenAt: Long,
        lastSeenAt: Long,
        sourceIds: List<String>,
        segmentId: String?,
    ) = MemoryItemEntity(
        id = Ids.new(),
        threadId = threadId,
        kind = kind,
        key = key,
        value = value.take(200),
        confidence = conf.toFloat(),
        importance = importance,
        happenedAt = happenedAt,
        dueAt = dueAt,
        validFrom = validFrom,
        validTo = validTo,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        sourceSegmentId = segmentId,
        sourceMessageIds = sourceIds.take(5).joinToString(SOURCE_SEPARATOR),
        status = MemoryStatus.normalize(status),
        supersededBy = null,
        characterId = ownerId,
    )

    private fun splitSources(raw: String?): List<String> =
        raw?.split(SOURCE_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    // ---------------------------------------------------------------- 检索块

    suspend fun saveChunks(chunks: List<ChunkEntity>, terms: List<ChunkTermEntity>) {
        if (chunks.isEmpty()) return
        db.chunkDao().insertAll(chunks)
        if (terms.isNotEmpty()) db.chunkDao().insertTerms(terms)
    }

    suspend fun recentChunks(threadId: String, limit: Int = 2000): List<ChunkEntity> =
        db.chunkDao().recent(threadId, limit)

    suspend fun chunksInRange(threadId: String, from: Long, to: Long, limit: Int = 200): List<ChunkEntity> =
        db.chunkDao().inRange(threadId, from, to, limit)

    suspend fun keywordSearch(threadId: String, terms: List<String>, limit: Int = 6): List<String> =
        if (terms.isEmpty()) emptyList() else db.chunkDao().searchTerms(threadId, terms, limit).map { it.chunkId }

    /** 按 id 批量取检索块原文（关键词检索出 id 后再取，保持打分顺序由调用方决定）。 */
    suspend fun chunksByIds(ids: List<String>): List<ChunkEntity> =
        if (ids.isEmpty()) emptyList() else db.chunkDao().byIds(ids)

    suspend fun invalidateChunksByMessage(threadId: String, messageId: String, at: Long = System.currentTimeMillis()) {
        db.chunkDao().invalidateByMessage(threadId, messageId, at)
    }

    private companion object {
        const val SOURCE_SEPARATOR = "\u0001"
    }
}
