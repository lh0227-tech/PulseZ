package cc.pulsez.app.data.repo

import androidx.room.withTransaction
import cc.pulsez.app.core.ImportIndexer
import cc.pulsez.app.data.db.CharacterEntity
import cc.pulsez.app.data.db.ImportBatchEntity
import cc.pulsez.app.data.db.ImportBatchGroupEntity
import cc.pulsez.app.data.db.ImportBatchWindowEntity
import cc.pulsez.app.data.db.ImportSourceSessionEntity
import cc.pulsez.app.data.db.MemoryItemEntity
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.SegmentEntity
import cc.pulsez.app.data.db.ThreadEntity
import cc.pulsez.app.data.db.ThreadKind
import cc.pulsez.app.data.db.ThreadMemberEntity
import cc.pulsez.app.importer.BackupCharacter
import cc.pulsez.app.importer.BackupDocument
import cc.pulsez.app.importer.BackupMemory
import cc.pulsez.app.importer.BackupMessage
import cc.pulsez.app.importer.BackupImportBatch
import cc.pulsez.app.importer.BackupSegment
import cc.pulsez.app.importer.BackupThread
import cc.pulsez.app.mem.MemoryStatus
import cc.pulsez.app.mem.TokenEstimator
import cc.pulsez.app.util.Ids
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 恢复结果的唯一表示（v0.5.2 后半）。
 *
 * 可见性：public —— `ImportUiState`（public）要持有 `RestoreUi.Done(RestoreResult.Restored)`，
 * public 类型不得暴露 internal 类型（同 `ExportResult` 的可见性口径，`）。
 * 文案全部在 `ui/copy/BackupCopy`，这里只给计数。
 */
sealed interface RestoreResult {

    /** 恢复成功（数据已在库；索引是否完整另看 [indexSkipped]）。 */
    data class Restored(
        val characterCount: Int,
        val threadCount: Int,
        val segmentCount: Int,
        val messageCount: Int,
        val memoryCount: Int,
        val memberCount: Int,
        /** `thread.characterId` 映射不到导出角色而整段跳过的会话数（不整包回滚）。 */
        val skippedOrphanThreads: Int,
        /** `character.id` 缺失/空的角色数。 */
        val ignoredCharacters: Int,
        /** 群成员指向未导出角色而丢弃的成员行数。 */
        val ignoredMembers: Int,
        /** 群消息发言人指向未导出角色（已置 null）的条数。 */
        val ignoredSpeakers: Int,
        /** 群记忆归属角色指向未导出角色而整行丢弃的条数。 */
        val ignoredMemories: Int,
        /** 备份里有、但恢复**不写** attachment 表的附件元数据条数。 */
        val attachmentCount: Int,
        /** 关键词块写成功数。 */
        val chunks: Int,
        /**
         * **块数**口径：逐会话累加 `ImportIndexer.Result.skipped`。
         * 不把「未索引会话数」折算进来，也不额外加会话数；结果页 `restoreIndexMissing(n)` 的 n 就是它。
         */
        val indexSkipped: Int,
        /** 结果页留用（本版不自动跳转，避免与 `createdId` 导航冲突）。*/
        val firstCharacterId: String?,
    ) : RestoreResult

    /** 失败：只有「事务前/事务内」失败才会到这里（索引阶段异常已在 [BackupRestoreRepository] 内收敛）。 */
    data object Failed : RestoreResult
}

/** 事务阶段（测试故障注入用；**不**暴露给 UI 文案）。 */
internal enum class RestoreStage { CHARACTER, THREAD, SEGMENT, MESSAGE, MEMORY, MEMBER, IMPORT_METADATA }

/**
 * 将 `BackupDocument` 写回 Room。
 *
 * ## 为什么单独一层、不复用 `ImportRepository`
 * `ImportRepository` 是 DeepSeek ZIP 语义（确定性 id 幂等、每会话一个 SEALED 段、`summary=null`、
 * 不还原记忆/成员/段摘要/时间），强套会破坏段不变量（`data/repo/ImportRepository.kt:105-136`）。
 * 本仓库只借用它的**不变量模板**：先父行后子行、还原消息只进 SEALED、另建恰好 1 个空 OPEN、
 * ordinal 连续、收尾 `lastExtractAt` 隔离。
 *
 * ## 事务与失败语义
 * - **整包一个 Room 事务**（`db.withTransaction`），阶段顺序固定：
 *   character → thread → segment → message → memory_item → thread_member → import batch provenance；
 * - 事务内失败 → `RestoreResult.Failed`（Room 自动整包回滚，不留半份）；
 * - `CancellationException` 必须重抛（不得吞成 Failed）；
 * - **事务提交之后**才建关键词索引（派生数据）；索引失败不回滚用户数据，
 *   异常在 [indexAll] 内收敛成 `indexSkipped`（块数口径）。
 *
 * ## ID 与归属
 * - 所有主键用 `Ids.new()` 重新生成，源 id 只在本次恢复内做映射键、不写库；
 * - `""`/JSON null 已由 `BackupParser.parseBackup` 归一成 null，本层不再判空串；
 * - 对成员、发言人和记忆 owner 的归属缺失，按固定策略处理并计数。
 *
 * @param db 目标库
 * @param indexer 生产 = `AppContainer.importIndexer`；恢复固定 `embedEnabled = false`（零网络）
 * @param ioDispatcher 恢复整体在 IO 上执行（不占主线程做 JSON 解析/分块/批量写）
 * @param onStage 测试注入缝：每个阶段写入前调用一次；抛出即验证整包回滚（生产恒为空实现）
 */
internal class BackupRestoreRepository(
    private val db: PulsezDatabase,
    private val indexer: ImportIndexer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 测试注入缝：每个阶段写入前调用一次；抛出即验证整包回滚（生产恒为空实现）。 */
    private val onStage: suspend (RestoreStage) -> Unit = {},
) {

    /**
     * 恢复一份备份（整包一个事务 + 提交后建关键词索引）。
     *
     * @param onProgress 消息阶段每写完一个会话回调一次 `(done, total)`；total = 实际写入的会话数
     *   （已排除被跳过的孤儿会话）。**不预填 total**：首个回调前 UI 显示「正在恢复…」。
     */
    suspend fun restore(
        document: BackupDocument,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RestoreResult = withContext(ioDispatcher) {
        try {
            // 纯计算：映射/分组/跳过计数，不写库；id 全在这里生成一次（写入阶段不再生成）。
            val plan = buildPlan(document)
            db.withTransaction { writeAll(plan, onProgress) }
            // 事务已提交，派生数据失败不回滚用户数据（A-4 / G-3）。
            val index = indexAll(plan)
            RestoreResult.Restored(
                characterCount = plan.characters.size,
                threadCount = plan.threads.size,
                segmentCount = plan.threads.sumOf { it.segments.size },
                messageCount = plan.threads.sumOf { it.messages.size },
                memoryCount = plan.threads.sumOf { it.memories.size },
                memberCount = plan.members.size,
                skippedOrphanThreads = plan.skippedOrphanThreads,
                ignoredCharacters = plan.ignoredCharacters,
                ignoredMembers = plan.ignoredMembers,
                ignoredSpeakers = plan.ignoredSpeakers,
                ignoredMemories = plan.ignoredMemories,
                attachmentCount = plan.attachmentCount,
                chunks = index.chunks,
                indexSkipped = index.indexSkipped,
                firstCharacterId = plan.characters.firstOrNull()?.entity?.id,
            )
        } catch (ce: CancellationException) {
            // 取消必须向上传播（工程既有纪律：ImportRepository.kt:195-197；禁止 runCatching）。
            throw ce
        } catch (t: Throwable) {
            // 事务前/事务内失败才有资格说「原来的数据没有动」（RESTORE_FAILED 文案口径）。
            RestoreResult.Failed
        }
    }

    // ------------------------------------------------------------------ plan（纯计算）

    private class Planned<T>(val sourceId: String, val entity: T)

    private class PlannedSegment(
        val entity: SegmentEntity,
        val messageIds: List<String>,
        /** Source segment ID used only to remap import provenance; never written back as a DB ID. */
        val sourceId: String?,
    )

    private class PlannedThread(
        val sourceId: String,
        val entity: ThreadEntity,
        /** SEALED 段（按还原顺序，含 messageIds 供索引）+ 恰好 1 个空 OPEN 段（最后）。 */
        val segments: List<PlannedSegment>,
        val messages: List<MessageEntity>,
        val memories: List<MemoryItemEntity>,
    )

    private class RestorePlan(
        val characters: List<Planned<CharacterEntity>>,
        val threads: List<PlannedThread>,
        val importBatches: List<PlannedImportBatch>,
        /** 全局收集，MEMBER 阶段一次写（父行 thread 先落）。 */
        val members: List<ThreadMemberEntity>,
        val skippedOrphanThreads: Int,
        val ignoredCharacters: Int,
        val ignoredMembers: Int,
        val ignoredSpeakers: Int,
        val ignoredMemories: Int,
        val attachmentCount: Int,
    )

    private class PlannedImportGroup(
        val entity: ImportBatchGroupEntity,
        val memoryUndoSafe: Boolean,
    )

    private class PlannedImportBatch(
        val entity: ImportBatchEntity,
        val groups: List<PlannedImportGroup>,
        val windows: List<ImportBatchWindowEntity>,
        val sources: List<ImportSourceSessionEntity>,
    )

    private class IndexStats(val chunks: Int, val indexSkipped: Int)

    /** `buildPlan` 的计数（恢复层不静默丢数据：每一类丢弃都要有计数，结果页要如实说）。 */
    private class Counting {
        var skippedOrphanThreads = 0
        var ignoredCharacters = 0
        var ignoredMembers = 0
        var ignoredSpeakers = 0
        var ignoredMemories = 0
    }

    /**
     * 恢复计划的一条消息中间态（还没有新 id / 新 segmentId，分组完成后再生成）。
     */
    private class PlannedMessage(
        val role: String,
        val content: String,
        val createdAt: Long,
        val tokens: Int,
        val speakerId: String?,
    )

    private fun buildPlan(document: BackupDocument): RestorePlan {
        val now = System.currentTimeMillis()
        val counting = Counting()

        // ---- 角色（character）：先父行；id 缺失/空的角色不写、其会话按孤儿处理（E-6） ----
        val charMap = LinkedHashMap<String, CharacterEntity>(document.characters.size)
        document.characters.forEach { source ->
            if (source.sourceId.isBlank()) {
                counting.ignoredCharacters += 1
                return@forEach
            }
            // 同一源 id 重复出现时保留第一条（源库主键唯一；重复属坏数据，不静默造两个 TA）。
            if (charMap.containsKey(source.sourceId)) return@forEach
            charMap[source.sourceId] = buildCharacter(source, now)
        }
        val characters = charMap.map { (sourceId, entity) -> Planned(sourceId, entity) }

        // ---- 会话（thread）：逐条判定归属/两态/段/消息/记忆 ----
        val threads = ArrayList<PlannedThread>(document.threads.size)
        val members = ArrayList<ThreadMemberEntity>()
        document.threads.forEach { source ->
            val planned = buildThread(source, charMap, members, counting, now)
            if (planned != null) threads += planned
        }

        val importedBatches = buildImportBatches(document.importBatches, charMap, threads)

        return RestorePlan(
            characters = characters,
            threads = threads,
            importBatches = importedBatches,
            members = members,
            skippedOrphanThreads = counting.skippedOrphanThreads,
            ignoredCharacters = counting.ignoredCharacters,
            ignoredMembers = counting.ignoredMembers,
            ignoredSpeakers = counting.ignoredSpeakers,
            ignoredMemories = counting.ignoredMemories,
            attachmentCount = document.attachmentCount,
        )
    }

    /**
     * Remap every provenance reference to the new rows produced by this restore. A source row is kept
     * only when its target character, thread, and imported segment all survived the normal restore rules.
     */
    private fun buildImportBatches(
        sourceBatches: List<BackupImportBatch>,
        characters: Map<String, CharacterEntity>,
        threads: List<PlannedThread>,
    ): List<PlannedImportBatch> {
        val threadBySource = threads.associateBy { it.sourceId }
        val segmentBySource = buildMap<Pair<String, String>, SegmentEntity> {
            threads.forEach { thread ->
                thread.segments.forEach { segment ->
                    segment.sourceId?.let { put(thread.sourceId to it, segment.entity) }
                }
            }
        }
        val seenBatchIds = HashSet<String>()
        return sourceBatches.mapNotNull { sourceBatch ->
            if (sourceBatch.sourceId.isBlank() || !seenBatchIds.add(sourceBatch.sourceId)) return@mapNotNull null
            val batchId = Ids.new()
            val groupIdMap = LinkedHashMap<String, ImportBatchGroupEntity>()
            val groups = ArrayList<PlannedImportGroup>()
            val seenGroupIds = HashSet<String>()
            sourceBatch.groups.forEach { sourceGroup ->
                if (sourceGroup.sourceId.isBlank() || !seenGroupIds.add(sourceGroup.sourceId)) return@forEach
                val targetCharacter = sourceGroup.targetCharacterSourceId?.let(characters::get)
                if (sourceGroup.targetCharacterSourceId != null && targetCharacter == null) return@forEach
                val targetThread = sourceGroup.targetThreadSourceId?.let(threadBySource::get)
                if (sourceGroup.targetThreadSourceId != null && targetThread == null) return@forEach
                if (
                    targetThread != null && targetThread.entity.characterId != targetCharacter?.id
                ) return@forEach
                if (sourceGroup.status == ImportRepository.GROUP_SUCCESS && targetThread == null) return@forEach

                val group = ImportBatchGroupEntity(
                    id = Ids.new(),
                    batchId = batchId,
                    targetCharacterId = targetCharacter?.id,
                    targetThreadId = targetThread?.entity?.id,
                    isNewCharacter = sourceGroup.isNewCharacter,
                    name = sourceGroup.name,
                    persona = sourceGroup.persona,
                    status = sourceGroup.status.ifBlank { ImportRepository.GROUP_FAILED },
                    // The original hash is tied to old primary keys. It is rebased after all restored
                    // memory rows are written, but only if the backup proved that the old snapshot matched.
                    memoryFingerprint = null,
                    beforeLastMessageAt = sourceGroup.beforeLastMessageAt,
                    beforeLastExtractAt = sourceGroup.beforeLastExtractAt,
                    beforeCharacterUpdatedAt = sourceGroup.beforeCharacterUpdatedAt,
                    importLastMessageAt = sourceGroup.importLastMessageAt,
                    importMessageCount = sourceGroup.importMessageCount.coerceAtLeast(0),
                    errorCode = sourceGroup.errorCode,
                )
                groupIdMap[sourceGroup.sourceId] = group
                groups += PlannedImportGroup(group, sourceGroup.memoryUndoSafe)
            }

            val windows = sourceBatch.windows
                .distinctBy { it.sourceSessionId }
                .filter { it.sourceSessionId.isNotBlank() && (it.groupSourceId == null || it.groupSourceId in groupIdMap) }
                .map { sourceWindow ->
                    ImportBatchWindowEntity(
                        batchId = batchId,
                        sourceSessionId = sourceWindow.sourceSessionId,
                        entryName = sourceWindow.entryName,
                        title = sourceWindow.title,
                        sourceStartedAt = sourceWindow.sourceStartedAt,
                        messageCount = sourceWindow.messageCount.coerceAtLeast(0),
                        damageCode = sourceWindow.damageCode,
                        groupId = sourceWindow.groupSourceId?.let { groupIdMap[it]?.id },
                        status = sourceWindow.status.ifBlank { ImportRepository.WINDOW_FAILED },
                        errorCode = sourceWindow.errorCode,
                    )
                }

            val groupsById = groups.associate { it.entity.id to it.entity }
            val importedWindowGroupsBySession = windows.mapNotNull { window ->
                if (window.status != ImportRepository.WINDOW_IMPORTED) return@mapNotNull null
                val group = window.groupId?.let(groupsById::get)
                    ?.takeIf { it.status == ImportRepository.GROUP_SUCCESS }
                    ?: return@mapNotNull null
                window.sourceSessionId to group
            }.toMap()
            val sources = sourceBatch.sources
                .distinctBy { it.sourceSessionId }
                .mapNotNull { source ->
                    val character = characters[source.targetCharacterSourceId] ?: return@mapNotNull null
                    val thread = threadBySource[source.targetThreadSourceId] ?: return@mapNotNull null
                    val group = importedWindowGroupsBySession[source.sourceSessionId]
                        ?: return@mapNotNull null
                    if (
                        thread.entity.id != group.targetThreadId ||
                        character.id != group.targetCharacterId ||
                        thread.entity.characterId != character.id
                    ) {
                        return@mapNotNull null
                    }
                    val segment = segmentBySource[source.targetThreadSourceId to source.segmentSourceId]
                        ?: return@mapNotNull null
                    ImportSourceSessionEntity(
                        id = Ids.new(),
                        batchId = batchId,
                        targetCharacterId = character.id,
                        targetThreadId = thread.entity.id,
                        sourceSessionId = source.sourceSessionId,
                        title = source.title,
                        sourceStartedAt = source.sourceStartedAt,
                        messageCount = source.messageCount.coerceAtLeast(0),
                        segmentId = segment.id,
                        indexStatus = source.indexStatus.ifBlank { ImportRepository.INDEX_FAILED },
                    )
                }

            // Recompute undo counters from the exact message rows that will be restored. This keeps
            // malformed or incomplete provenance from subtracting older messages during a later undo.
            val adjustedGroups = groups.map { planned ->
                val group = planned.entity
                if (group.status != ImportRepository.GROUP_SUCCESS) {
                    planned
                } else {
                    val importedSessions = windows.asSequence()
                        .filter { it.groupId == group.id && it.status == ImportRepository.WINDOW_IMPORTED }
                        .map { it.sourceSessionId }
                        .toHashSet()
                    val segmentIds = sources.asSequence()
                        .filter { it.sourceSessionId in importedSessions && it.targetThreadId == group.targetThreadId }
                        .map { it.segmentId }
                        .toHashSet()
                    val targetThread = threads.firstOrNull { it.entity.id == group.targetThreadId }
                    val importedMessages = targetThread?.messages.orEmpty().filter { it.segmentId in segmentIds }
                    PlannedImportGroup(
                        entity = group.copy(
                            importLastMessageAt = importedMessages.maxOfOrNull { it.createdAt },
                            importMessageCount = importedMessages.size,
                        ),
                        memoryUndoSafe = planned.memoryUndoSafe,
                    )
                }
            }
            val retainedSourceSessions = sources.mapTo(HashSet()) { it.sourceSessionId }
            val normalizedWindows = windows.map { window ->
                if (window.status == ImportRepository.WINDOW_IMPORTED && window.sourceSessionId !in retainedSourceSessions) {
                    window.copy(status = ImportRepository.WINDOW_UNDONE, errorCode = null)
                } else {
                    window
                }
            }

            val restoredStatus = if (sourceBatch.status == ImportRepository.BATCH_RUNNING) {
                ImportRepository.BATCH_CANCELLED
            } else {
                sourceBatch.status.ifBlank { ImportRepository.BATCH_CANCELLED }
            }
            PlannedImportBatch(
                entity = ImportBatchEntity(
                    id = batchId,
                    fingerprint = sourceBatch.fingerprint,
                    zipName = sourceBatch.zipName,
                    createdAt = sourceBatch.createdAt,
                    status = restoredStatus,
                    postProcessStatus = sourceBatch.postProcessStatus.ifBlank { ImportRepository.POST_PROCESS_PENDING },
                ),
                groups = adjustedGroups,
                windows = normalizedWindows,
                sources = sources,
            )
        }
    }

    /**
     * 角色写回规则：
     * - `persona` = 导出值**原样**（`ExportService.kt:61` 原样写出；不 trim、不重算、不拼接）；
     * - `greeting/userName/styleSamples` 已被 parseBackup 归一（null 就写 null，非空原样写回）；
     * - `styleSamples` 的 `\u0001` 分隔串**原样整串**写入（`Repositories.kt:49-51`）。
     */
    private fun buildCharacter(source: BackupCharacter, now: Long): CharacterEntity = CharacterEntity(
        id = Ids.new(),
        name = source.name.ifBlank { ImportRepository.DEFAULT_CHARACTER_NAME },
        avatarUri = null,
        persona = source.persona,
        greeting = source.greeting,
        styleSamples = source.styleSamples,
        userName = source.userName,
        systemPromptOverride = null,
        // 现行为：新建角色默认开启（`Repositories.kt:54`）。
        protocolEnabled = true,
        sourceType = source.sourceType.ifBlank { SOURCE_TYPE_BACKUP },
        sourceRef = null,
        rawImported = null,
        emoAssetDir = null,
        createdAt = source.createdAt.takeIf { it > 0 } ?: now,
        // 保留导出 updatedAt（保真；首页按 updatedAt DESC 排序 已接受多副本排序取舍）。
        updatedAt = source.updatedAt.takeIf { it > 0 } ?: now,
        deletedAt = null,
    )

    /** 建一个会话的完整计划；返回 null = 该会话被整段跳过（孤儿，E-1）。 */
    private fun buildThread(
        source: BackupThread,
        charMap: Map<String, CharacterEntity>,
        members: MutableList<ThreadMemberEntity>,
        counting: Counting,
        now: Long,
    ): PlannedThread? {
        val kind = if (source.kind == ThreadKind.GROUP) ThreadKind.GROUP else ThreadKind.SINGLE

        // ---- 归属（A-2 / E-1）：单聊映射不到导出角色 → 整段跳过 + 计数；群聊 characterId 强制 null ----
        val characterId = if (kind == ThreadKind.GROUP) {
            null
        } else {
            val mapped = source.characterSourceId?.let { charMap[it] }
            if (mapped == null) {
                counting.skippedOrphanThreads += 1
                return null
            }
            mapped.id
        }

        val threadId = Ids.new()
        // 时间 0/负值 → 恢复时刻（导出值优先，保真；只对坏值兜底）。
        val threadCreatedAt = source.createdAt.takeIf { it > 0 } ?: now

        // ---- 消息（逐条归一）：新 id 在段分组后生成；时间乱序用 maxOf/last 钳制 ----
        var lastCreatedAt: Long? = null
        val plannedMessages = source.messages.map { message ->
            val createdAt = when {
                message.createdAt > 0 -> message.createdAt
                // createdAt<=0 → 同会话内前一条时间；首条用 thread.createdAt，仍 0 用 now。
                lastCreatedAt != null -> lastCreatedAt!!
                else -> threadCreatedAt
            }
            lastCreatedAt = createdAt
            toPlannedMessage(message, charMap, counting, createdAt)
        }

        // ---- 段分组（C：新备份按 segmentId；老备份/混合/悬空 → 合成段，绝不丢消息） ----
        val sealed = planSealedSegments(source, plannedMessages, threadId)

        // ---- 消息实体：新 id + 新 threadId + 新 segmentId ----
        val segmentOfIndex = IntArray(plannedMessages.size)
        sealed.forEachIndexed { segmentIndex, planned ->
            planned.messageIndexes.forEach { segmentOfIndex[it] = segmentIndex }
        }
        val messages = plannedMessages.mapIndexed { index, planned ->
            MessageEntity(
                id = Ids.new(),
                threadId = threadId,
                segmentId = sealed[segmentOfIndex[index]].entity.id,
                role = planned.role,
                content = planned.content,
                tokens = planned.tokens,
                emotion = null,
                // 导出不含 status → 统一 OK（ImportRepository.kt:488-489；PulsezDatabase.kt:458-459 只读 OK）。
                status = ImportRepository.MESSAGE_STATUS_OK,
                draft = null,
                usageIn = null,
                usageOut = null,
                previousContent = null,
                createdAt = planned.createdAt,
                deletedAt = null,
                speakerCharacterId = planned.speakerId,
            )
        }

        // ---- 段实体：SEALED 在前（ordinal 0..n-1）+ 恰好 1 个空 OPEN（ordinal=n，B） ----
        val maxMessageAt = plannedMessages.maxOfOrNull { it.createdAt }
        val threadLastMessageAt = maxOf(source.lastMessageAt, maxMessageAt ?: 0L)
            .takeIf { it > 0 } ?: threadCreatedAt
        val openOrdinal = sealed.size
        val openFirstMessageAt = maxMessageAt ?: threadLastMessageAt
        val openSegment = SegmentEntity(
            id = Ids.new(),
            threadId = threadId,
            ordinal = openOrdinal,
            summary = null,
            styleNotes = null,
            firstMessageAt = openFirstMessageAt,
            lastMessageAt = openFirstMessageAt,
            tokenSum = 0,
            state = ImportRepository.SEGMENT_STATE_OPEN,
            batonReason = null,
            createdAt = openFirstMessageAt,
        )
        val segmentPlans = ArrayList<PlannedSegment>(sealed.size + 1)
        sealed.forEachIndexed { ordinal, planned ->
            val entity = planned.entity.copy(ordinal = ordinal)
            segmentPlans += PlannedSegment(
                entity = entity,
                messageIds = planned.messageIndexes.map { messages[it].id },
                sourceId = planned.sourceId,
            )
        }
        segmentPlans += PlannedSegment(entity = openSegment, messageIds = emptyList(), sourceId = null)

        // ---- 记忆（D/E-4）：白名单 + 空白丢弃 + owner 映射不到整行丢弃（绝不置 null 串味） ----
        val firstMessageAt = plannedMessages.firstOrNull()?.createdAt ?: threadCreatedAt
        val memories = source.memories.mapNotNull { memory ->
            buildMemory(memory, threadId, charMap, counting, firstMessageAt)
        }

        // ---- 群成员（E-2）：映射不到的角色丢弃 + 计数；存活成员 ordinal 连续重排 0..k-1 ----
        if (kind == ThreadKind.GROUP) {
            val mappedMembers = source.members.mapNotNull { member ->
                val mapped = charMap[member.characterSourceId]
                if (mapped == null) {
                    counting.ignoredMembers += 1
                    null
                } else {
                    mapped.id to member
                }
            }.sortedBy { (_, member) -> member.ordinal }
                // 复合主键 (threadId, characterId)：坏备份里重复成员只留第一条（不静默覆盖）。
                .distinctBy { (characterId, _) -> characterId }
            mappedMembers.forEachIndexed { ordinal, (mappedId, member) ->
                members += ThreadMemberEntity(
                    threadId = threadId,
                    characterId = mappedId,
                    ordinal = ordinal,
                    joinedAt = member.joinedAt.takeIf { it > 0 } ?: threadCreatedAt,
                )
            }
        }

        // ---- 会话实体（A-2 / B-4 / F） ----
        val threadEntity = ThreadEntity(
            id = threadId,
            // 群聊显式 null；单聊是映射后的新角色 id（绝不写 ""，parseBackup 已归一）。
            characterId = characterId,
            title = source.title,
            createdAt = threadCreatedAt,
            lastMessageAt = threadLastMessageAt,
            activeSegmentId = openSegment.id,
            segmentOrdinal = openOrdinal,
            totalMessages = messages.size,
            totalTokens = messages.sumOf { it.tokens.toLong() },
            // F：lastExtractAt = 还原消息最大 createdAt；没有消息为 0。
            // ChatEngine.extractIfDue 用 after(thread.lastExtractAt)（core/ChatEngine.kt:474），
            // 不这样做第一次维护会把全部恢复历史当新对话送去抽取（花用户的钱）。
            lastExtractAt = maxMessageAt ?: 0L,
            // 导出侧只导未归档会话（PulsezDatabase.kt:319），恢复一律 false。
            archived = false,
            kind = kind,
            // Preserve only an explicit event from a modern backup; old backups remain NULL.
            lastSingleChatActivityAt = if (kind == ThreadKind.SINGLE) source.lastSingleChatActivityAt else null,
        )

        return PlannedThread(
            sourceId = source.sourceId,
            entity = threadEntity,
            segments = segmentPlans,
            messages = messages,
            memories = memories,
        )
    }

    private fun toPlannedMessage(
        message: BackupMessage,
        charMap: Map<String, CharacterEntity>,
        counting: Counting,
        createdAt: Long,
    ): PlannedMessage {
        // E-3：发言人指向未导出角色 → 写 null + 计数（不制造悬空引用；UI 本来回落「TA」）。
        val speakerId = message.speakerSourceId?.let { charMap[it]?.id }
        if (message.speakerSourceId != null && speakerId == null) {
            counting.ignoredSpeakers += 1
        }
        return PlannedMessage(
            role = message.role,
            content = message.content,
            createdAt = createdAt,
            // 导出缺失 → 本地估算（`ImportRepository.kt:399` 同口径）。
            tokens = message.tokens ?: TokenEstimator.estimate(message.content),
            speakerId = speakerId,
        )
    }

    /** 段分组结果：段实体（ordinal 尚未重排）+ 归属该段的消息下标。 */
    private class SegmentDraft(
        val entity: SegmentEntity,
        val messageIndexes: List<Int>,
        val sourceId: String?,
    )

    /**
     * 两态判定（C，**每个会话独立判定**）：
     * 1. 新备份 = 会话**所有**消息都有非空 `segmentId` → 按 `segmentId` 分组；
     *    只还原被至少一条消息引用的段（未被引用的源空 OPEN 不还原）；
     *    组顺序按导出 `ordinal` 升序，查不到的段排最后（组内首条 createdAt，再 sourceId），
     *    段数重排由调用方 `ordinal = index` 完成。
     * 2. 老备份（任一条缺 `segmentId`，含混合/悬空）→ **全部消息进 1 个合成 SEALED 段**，
     *    summary = 导出非空 summary 按 ordinal 用 "\n" 拼接（全部为空则 null），
     *    其余源段不单独还原（避免空段/重复摘要），悬空段消息也归入它（绝不丢消息）。
     *
     * 段头三列在两种格式中都按组内消息推导并钳制：导出值只能把区间和总量往大扩，
     * 绝不能把组内消息挡在段外；依据：普通段 `tokenSum` 恒 0、OPEN 段 `lastMessageAt` 停在
     * 建段时刻（`Repositories.kt:96-108`、`:149-162`、`:226-260`），导出值不可信。
     */
    private fun planSealedSegments(
        source: BackupThread,
        messages: List<PlannedMessage>,
        threadId: String,
    ): List<SegmentDraft> {
        if (messages.isEmpty()) return emptyList()
        val allHaveSegment = source.messages.all { it.segmentSourceId != null }
        val segmentBySource = source.segments.associateBy { it.sourceId }

        if (!allHaveSegment) {
            // ---- 老备份/混合：1 个合成 SEALED 段 ----
            val summary = source.segments
                .sortedWith(compareBy({ it.ordinal }, { it.sourceId }))
                .mapNotNull { it.summary?.takeIf(String::isNotBlank) }
                .joinToString("\n")
                .takeIf { it.isNotEmpty() }
            val first = messages.first().createdAt
            val last = messages.last().createdAt
            return listOf(
                SegmentDraft(
                    entity = SegmentEntity(
                        id = Ids.new(),
                        threadId = threadId,
                        ordinal = 0,
                        summary = summary,
                        styleNotes = null,
                        firstMessageAt = first,
                        lastMessageAt = last,
                        tokenSum = messages.sumOf { it.tokens },
                        state = ImportRepository.SEGMENT_STATE_SEALED,
                        // C-3：统一写 IMPORT（记忆面板补建/「接着聊」都只认这个标注）。
                        batonReason = ImportRepository.BATON_REASON_IMPORT,
                        createdAt = first,
                    ),
                    messageIndexes = messages.indices.toList(),
                    sourceId = null,
                ),
            )
        }

        // ---- 新备份：按 segmentId 分组，按导出 ordinal 排序，悬空组排最后 ----
        val groups = LinkedHashMap<String, MutableList<Int>>()
        source.messages.forEachIndexed { index, message ->
            val key = message.segmentSourceId!!
            groups.getOrPut(key) { ArrayList() } += index
        }
        val ordered = groups.entries.sortedWith(Comparator { a, b ->
            val segmentA = segmentBySource[a.key]
            val segmentB = segmentBySource[b.key]
            val knownA = segmentA != null && segmentA.ordinal != Int.MAX_VALUE
            val knownB = segmentB != null && segmentB.ordinal != Int.MAX_VALUE
            when {
                knownA && knownB -> segmentA!!.ordinal.compareTo(segmentB!!.ordinal)
                    .takeIf { it != 0 } ?: a.key.compareTo(b.key)
                knownA != knownB -> if (knownA) -1 else 1
                else -> {
                    // 导出里查不到的段：按组内首条消息 createdAt，再 sourceId 兜底（C-1）。
                    val byTime = messages[a.value.first()].createdAt
                        .compareTo(messages[b.value.first()].createdAt)
                    if (byTime != 0) byTime else a.key.compareTo(b.key)
                }
            }
        })
        return ordered.map { (segmentSourceId, indexes) ->
            val exported = segmentBySource[segmentSourceId]
            val first = messages[indexes.first()].createdAt
            val last = messages[indexes.last()].createdAt
            val groupTokens = indexes.sumOf { messages[it].tokens }
            val exportedFirst = exported?.firstMessageAt?.takeIf { it > 0 }
            val exportedLast = exported?.lastMessageAt?.takeIf { it > 0 }
            val firstMessageAt = minOf(first, exportedFirst ?: first)
            val lastMessageAt = maxOf(last, exportedLast ?: last)
            SegmentDraft(
                entity = SegmentEntity(
                    id = Ids.new(),
                    threadId = threadId,
                    ordinal = 0,
                    summary = exported?.summary,
                    styleNotes = null,
                    firstMessageAt = firstMessageAt,
                    lastMessageAt = lastMessageAt,
                    // 导出 tokenSum=0 是坏值，钳制后必须 ≥ 组内消息 tokens 之和。
                    tokenSum = maxOf(groupTokens, exported?.tokenSum ?: 0),
                    state = ImportRepository.SEGMENT_STATE_SEALED,
                    batonReason = ImportRepository.BATON_REASON_IMPORT,
                    // 导出不含段 createdAt → 取 firstMessageAt（C-4）。
                    createdAt = firstMessageAt,
                ),
                messageIndexes = indexes.toList(),
                sourceId = segmentSourceId,
            )
        }
    }

    /**
     * 记忆写回规则（D / E-4）：
     * - `kind` 不在五种记忆类型内 → 跳过 + 计数；
     * - `value` 空白 → 跳过 + 计数；
     * - `characterId` 为 null → 会话级（单聊维度）；非空但映射不到导出角色 → **整行丢弃** + 计数
     *   （**不允许置 null**：null 在数据模型里表示会话级，把成员私有记忆改成会话级会在群聊里串味，
     *   `PulsezDatabase.kt:501-509`、`GroupChatViewModel.kt:594-620`）；
     * - `validTo` 已由 parseBackup 把 0 哨兵转 null（`TemporalEntry.isActive()` 只认 null）。
     */
    private fun buildMemory(
        memory: BackupMemory,
        threadId: String,
        charMap: Map<String, CharacterEntity>,
        counting: Counting,
        firstMessageAt: Long,
    ): MemoryItemEntity? {
        if (memory.kind !in MEMORY_KINDS || memory.value.isBlank()) {
            counting.ignoredMemories += 1
            return null
        }
        val characterId = if (memory.characterSourceId == null) {
            null
        } else {
            val mapped = charMap[memory.characterSourceId]
            if (mapped == null) {
                counting.ignoredMemories += 1
                return null
            }
            mapped.id
        }
        val validFrom = memory.validFrom.takeIf { it > 0 } ?: firstMessageAt
        return MemoryItemEntity(
            id = Ids.new(),
            threadId = threadId,
            kind = memory.kind,
            key = memory.key,
            value = memory.value,
            // 导出缺失 → 1.0（mem/MemorySchema.kt:239 的同一默认值）。
            confidence = memory.confidence ?: 1.0f,
            importance = memory.importance ?: importanceFor(memory.kind),
            happenedAt = memory.happenedAt,
            dueAt = memory.dueAt,
            validFrom = validFrom,
            validTo = memory.validTo,
            firstSeenAt = memory.firstSeenAt ?: validFrom,
            lastSeenAt = memory.lastSeenAt ?: validFrom,
            // 导出不含来源关联（H）：记忆本身回来，但「删某句话→那条记忆降级」的联动失效。
            sourceSegmentId = null,
            sourceMessageIds = null,
            status = MemoryStatus.normalize(memory.status),
            supersededBy = null,
            characterId = characterId,
        )
    }

    // ------------------------------------------------------------------ writeAll（一个事务）

    /** 阶段顺序固定、不可换；所有聊天父行先落，批次归属在聊天行之后一起恢复。 */
    private suspend fun writeAll(plan: RestorePlan, onProgress: (done: Int, total: Int) -> Unit) {
        onStage(RestoreStage.CHARACTER)
        plan.characters.forEach { db.characterDao().upsert(it.entity) }

        onStage(RestoreStage.THREAD)
        plan.threads.forEach { db.threadDao().upsert(it.entity) }

        onStage(RestoreStage.SEGMENT)
        plan.threads.forEach { thread ->
            thread.segments.forEach { db.segmentDao().upsert(it.entity) }
        }

        onStage(RestoreStage.MESSAGE)
        plan.threads.forEachIndexed { index, thread ->
            thread.messages.chunked(ImportRepository.SQLITE_VARIABLE_BATCH).forEach { batch ->
                db.messageDao().insertAllIgnore(batch)
            }
            // 进度按会话数（消息是最耗时阶段）；total = 实际 plan 会话数（跳过孤儿后更小）。
            onProgress(index + 1, plan.threads.size)
        }

        onStage(RestoreStage.MEMORY)
        plan.threads.forEach { thread ->
            thread.memories.chunked(ImportRepository.SQLITE_VARIABLE_BATCH).forEach { batch ->
                db.memoryDao().insertAll(batch)
            }
        }

        onStage(RestoreStage.MEMBER)
        if (plan.members.isNotEmpty()) db.threadMemberDao().insertAll(plan.members)

        onStage(RestoreStage.IMPORT_METADATA)
        val batchDao = db.importBatchDao()
        val importRepository = ImportRepository(db)
        plan.importBatches.forEach { batch ->
            batchDao.upsertBatch(batch.entity)
            val groups = batch.groups.map { planned ->
                val group = planned.entity
                val memoryFingerprint = if (group.status == ImportRepository.GROUP_SUCCESS) {
                    if (planned.memoryUndoSafe) {
                        val threadId = group.targetThreadId
                            ?: throw IllegalStateException("成功批次组缺少目标会话")
                        // IDs are regenerated during restore, so rebase the undo guard only when export
                        // verified the source snapshot still matched. Otherwise persist a non-hash sentinel
                        // that can never match the SHA-256 computed by undoBatch (fail closed).
                        importRepository.memoryFingerprintForThread(threadId)
                    } else {
                        UNSAFE_UNDO_FINGERPRINT
                    }
                } else {
                    null
                }
                group.copy(memoryFingerprint = memoryFingerprint)
            }
            if (groups.isNotEmpty()) batchDao.upsertGroups(groups)
            if (batch.windows.isNotEmpty()) batchDao.upsertWindows(batch.windows)
            if (batch.sources.isNotEmpty()) batchDao.upsertSources(batch.sources)
        }
    }

    // ------------------------------------------------------------------ indexAll（提交后）

    /**
     * 事务提交后为每个有消息的还原会话建关键词块（G）。
     *
     * - **硬编码 `embedEnabled = false`** → `ImportIndexer` 走 `dim=0 / vec 空 / model="none"`，
     *   结构上一次网络调用都不会发（`core/ImportIndexer.kt:157-164`、`:279-280`）；向量交用户在
     *   记忆面板手动补建（`ui/memory/MemoryViewModel.kt:773-783`）。
     * - `chunk.segmentId` = 新 SEALED 段 id、`messageIds` = 新消息 id（删消息可传播）。
     * - **整段一个 try**：非取消异常一律计入 `indexSkipped`（不回滚已提交事务）；
     *   `CancellationException` 必须重抛（`ImportIndexer.index` 内部 `runCatching` 会吞掉取消，
     *   取消可见性由本函数每轮的 `ensureActive()` 负责）。
     * - `indexSkipped` 统计 `ImportIndexer.Result.skipped` 的**块数**，按会话累加。
     */
    private suspend fun indexAll(plan: RestorePlan): IndexStats {
        var chunks = 0
        var indexSkipped = 0
        val targets = plan.threads.filter { it.messages.isNotEmpty() }
        try {
            for (thread in targets) {
                val segments = thread.segments
                    .filter { it.entity.state == ImportRepository.SEGMENT_STATE_SEALED }
                    .map { planned ->
                        ImportedSegment(
                            sourceSessionId = thread.sourceId,
                            segmentId = planned.entity.id,
                            // 还原顺序（= 导出消息顺序）。
                            messageIds = planned.messageIds,
                            firstMessageAt = planned.entity.firstMessageAt,
                            lastMessageAt = planned.entity.lastMessageAt,
                        )
                    }
                val result = indexer.index(
                    threadId = thread.entity.id,
                    segments = segments,
                    // 恢复固定零网络（禁止项 3）；真正联网与否还取决于配置，本参数只是意愿。
                    embedEnabled = false,
                    progress = { _, _ -> },
                )
                // ImportIndexer 内部用 runCatching 逐块兜底、会把取消一起吞掉；不补这一行，
                // 用户在索引阶段取消会被静默忽略、restore() 还会返回 Restored（语义谎报）。
                coroutineContext.ensureActive()
                chunks += result.chunks
                indexSkipped += result.skipped // 块数，不是会话数
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // 任一会话抛出非取消异常：中止后续索引。未走到的会话**不**折算成 N——
            // N 表示「ImportIndexer.Result.skipped 的块数」；
            // 数据已在库，用户可再恢复一次补上（restoreIndexMissing 文案）。
        }
        return IndexStats(chunks = chunks, indexSkipped = indexSkipped)
    }

    private companion object {
        /** 备份恢复写出的角色来源（区别于 MANUAL / DEEPSEEK）。 */
        const val SOURCE_TYPE_BACKUP: String = "PULSEZ_BACKUP"

        /** Not a SHA-256 digest, so ImportRepository.undoBatch will always fail closed on this group. */
        const val UNSAFE_UNDO_FINGERPRINT: String = "BACKUP_UNDO_UNSAFE"

        /** 恢复层接受的五种记忆类型（与 `MemoryRepository.load` 映射的 kind 一致，`:45-112`）。 */
        val MEMORY_KINDS: Set<String> = setOf("FACT", "EVENT", "LOOP", "PREF", "BOUNDARY")

        /**
         * 记忆重要度兜底（导出缺失时按 kind；与 `mem/MemorySchema.kt:149,168,185,201,217`
         * 的 `importanceOf(default=…)` 逐项一致：FACT/EVENT/PREF=3、LOOP=4、BOUNDARY=5）。
         */
        fun importanceFor(kind: String): Int = when (kind) {
            "LOOP" -> 4
            "BOUNDARY" -> 5
            else -> 3
        }
    }
}
