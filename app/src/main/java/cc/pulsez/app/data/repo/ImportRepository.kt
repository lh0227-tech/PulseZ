package cc.pulsez.app.data.repo

import androidx.room.withTransaction
import cc.pulsez.app.data.db.CharacterEntity
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.ChunkTermEntity
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
import cc.pulsez.app.importer.Speaker
import cc.pulsez.app.importer.StyleSamples
import cc.pulsez.app.importer.Turn
import cc.pulsez.app.importer.deepseek.DeepSeekParseException
import cc.pulsez.app.importer.deepseek.DeepSeekSessionPreview
import cc.pulsez.app.importer.deepseek.DeepSeekTurn
import cc.pulsez.app.importer.deepseek.Issue
import cc.pulsez.app.importer.deepseek.ParsedDeepSeekSession
import cc.pulsez.app.mem.TokenEstimator
import cc.pulsez.app.util.Ids
import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * 导入目标（契约）。
 *
 * v0.4 只支持「新建 TA」（§2 追加到已有角色会污染记忆、破坏段不变量，明确砍掉）。
 * [NewCharacter.resumeCharacterId] 非空表示「同一份 ZIP 没导完，接着补」：复用上次的 TA 与
 * 它最新的 thread（§6「续导」），不会新建第二个 TA。
 */
sealed interface ImportTarget {
    data class NewCharacter(
        val name: String,
        val persona: String?,
        val zipName: String,
        val resumeCharacterId: String? = null,
        val userName: String? = null,
        val styleSamples: String? = null,
    ) : ImportTarget
}

/**
 * 一段导入会话落库后的结果。
 *
 * 供 建本地索引直接消费，**不要**再回查 DB（`bySegment` 按 `status='OK'` 过滤，语义脆）。
 *
 * @property sourceSessionId DeepSeek 源会话 id（`chat_sessions/<id>.json` 的 id）
 * @property segmentId 新建的 SEALED 段 id（导入往事专用，永不进 ③）
 * @property messageIds 本次真正写进该段的消息 id，**写入顺序**（= 源顺序，时间已严格递增）
 * @property firstMessageAt / lastMessageAt 段内消息的源时间（已由 钳制）
 */
data class ImportedSegment(
    val sourceSessionId: String,
    val segmentId: String,
    val messageIds: List<String>,
    val firstMessageAt: Long,
    val lastMessageAt: Long,
)

/**
 * 一次导入的汇总。
 *
 * 三类计数分开，结果页才能说清「已导入 / 之前导过（没重复）/ 读不出来」（产品）：
 * - [sessionsImported] / [messagesInserted]：这次真正新建的段与消息；
 * - [sessionsSkippedExisting] / [messagesExisting]：确定性 id 命中，之前已经导过；
 * - [sessionsFailed]：本仓库里单会话事务失败 / 空会话（解析失败由调用方按 `NO_SESSIONS` 等处理）。
 *
 * @property segments 本次新建的段（索引输入；幂等命中时为空）
 * @property issues 只含原因码，不含正文/标题（隐私收敛）
 * @property createdNewCharacter 本次导入是否**新建**了 TA（false = 续导复用了上次的 TA）。
 *   结果页据此决定是否提供「撤销这次导入」：撤销的语义是「软删本次新建的 TA」，
 *   续导时那个 TA 承载着之前几次导入的历史，整批撤销会把既往历史一起隐藏，
 *   所以续导（含「0 新增」的结果页）不提供整批撤销。
 * @property lastImportedAt 本次**新写入**消息中最晚的源时间；没有新消息时为 null
 *   （用于「继续上次导入」/UI 展示；与 `thread.lastExtractAt` 的「只前进」语义配合）
 */
data class ImportSummary(
    val characterId: String,
    val threadId: String,
    val sessionsImported: Int,
    val sessionsSkippedExisting: Int,
    val sessionsFailed: Int,
    val messagesInserted: Int,
    val messagesExisting: Int,
    val segments: List<ImportedSegment>,
    val issues: List<Issue>,
    val lastImportedAt: Long?,
    val createdNewCharacter: Boolean = false,
    /** Present for grouped imports; enables precise per-batch undo instead of hiding an entire TA. */
    val batchId: String? = null,
)

/** User-confirmed destination for a set of source windows. A null targetCharacterId means new TA. */
data class ImportBatchGroupPlan(
    val id: String,
    val targetCharacterId: String? = null,
    val name: String,
    val persona: String? = null,
    val status: String = "PENDING",
    val userName: String? = null,
    val styleSamples: String? = null,
)

/** Persistable preview row and explicit user choice; null groupId is an intentional skip. */
data class ImportBatchWindowPlan(
    val preview: DeepSeekSessionPreview,
    val groupId: String?,
)

data class ImportBatchPlan(
    val id: String,
    val fingerprint: String,
    val zipName: String,
    val groups: List<ImportBatchGroupPlan>,
    val windows: List<ImportBatchWindowPlan>,
    val awakenOnImport: Boolean = false,
)

data class ImportBatchGroupResult(
    val groupId: String,
    val characterId: String?,
    val threadId: String?,
    val sourceSessionsImported: Int,
    val sourceSessionsSkipped: Int,
    val messagesInserted: Int,
    val messagesExisting: Int = 0,
    val errorCode: String? = null,
)

data class ImportBatchResult(
    val batchId: String,
    val groups: List<ImportBatchGroupResult>,
    val skippedUnassigned: Int,
    val postProcessStatus: String,
    val createdOrUpdatedCharacterIds: List<String> = emptyList(),
    val awakenOnImport: Boolean = false,
)

class ImportBatchException(val code: String) : Exception(code) {
    companion object {
        const val BATCH_MISSING = "IMPORT_BATCH_MISSING"
        const val GROUP_MISSING = "IMPORT_GROUP_MISSING"
        const val TARGET_MISSING = "IMPORT_TARGET_MISSING"
        const val MEMORY_CHANGED = "IMPORT_UNDO_MEMORY_CHANGED"
        const val BATCH_ACTIVE = "IMPORT_BATCH_ACTIVE"
    }
}

/**
 * 导入落库失败。`code` 是人话映射的原因码（UI 按码给文案，不带任何用户正文）。
 *
 * 依据 契约、§5 错误码表。
 */
class ImportException(val code: String) : Exception(code) {
    companion object {
        /** 续导目标（角色或它最新的 thread）不存在 / 已被软删。 */
        const val RESUME_TARGET_MISSING = "RESUME_TARGET_MISSING"

        /** 目标 thread 不在了。 */
        const val THREAD_MISSING = "THREAD_MISSING"

        /** thread 上没有唯一的 OPEN 段（段不变量被破坏）：本次会话不写半截，整体回滚。 */
        const val OPEN_SEGMENT_MISSING = "OPEN_SEGMENT_MISSING"

        /** 单会话事务抛了未预期异常（库/磁盘），已回滚该会话并继续导入其它会话。 */
        const val SESSION_FAILED = "SESSION_FAILED"
    }
}

/**
 * DeepSeek 旧聊天导入的数据层：把解析好的会话写成「character + thread + SEALED 段 + 消息」。
 *
 * ## 段不变量（单测锁死）
 * 1. 每个单聊 thread **有且仅有一个** `state="OPEN"` 段，`ordinal` 最大且等于 `thread.segmentOrdinal`，
 *    `thread.activeSegmentId` 指向它；
 * 2. 新建角色时 character + thread + OPEN 段（ordinal=0）**一个事务**写入（先角色，后 thread，
 *    最后 OPEN 段；thread 对 character 有外键），保证「任何时候都有 active 段可落消息」；
 * 3. 之后每个导入事务：`SEALED` 段 ordinal = 当前 OPEN 段 ordinal（k），同事务把 OPEN 段 ordinal
 *    改为 k+1 并更新 `thread.segmentOrdinal = k+1`；
 * 4. 导入消息**只写 SEALED**，`summary=null`：导入往事不进 ③（`ChatEngine.prepare` 只读
 *    `activeSegmentId`），也不进 ② 编年史（只收非空 summary），只走 ④ 记忆/召回；
 * 5. 收尾事务：`lastExtractAt = max(旧值, 最后一条导入时间)`、`lastMessageAt = max(旧值, …)`、
 *    `totalMessages += 新增数`、`character.updatedAt = now`；
 * 6. 中断后（只提交了前 N 个会话）`activeSegmentId != null`，后续 `ChatRepository.append` 写进
 *    OPEN 段的消息能被 `bySegment(active)` 读到。
 *
 * ## 幂等
 * 消息 id 是确定性的：`UUID.nameUUIDFromBytes("deepseek:v3:<characterId>:<sessionId>:<messageId>")`。
 * 每个会话事务内先用 `SELECT id ... IN`（每批 ≤500，避开 SQLite 变量上限）查已存在集合；
 * **全部已存在 → 不建段、不推进 ordinal**，计入 `sessionsSkippedExisting`。
 * 已有行绝不覆盖（`insertAllIgnore` + 只写新 id），因此重复导入的消息/段/计数都不变。
 *
 * ## 不动用户已有数据
 * - 计数/时间一律用**定向 UPDATE**（`addTotalMessages` / `bumpLastMessageAt` /
 *   `bumpLastExtractAt` / `touchUpdatedAt`），不用整行 upsert：导入是长任务，用户可能同时在聊天，
 *   整行写回会用旧快照覆盖新写入；
 * - 续导只复用已有 character/thread，不改已有消息与段；
 * - [undoImport] 只软删角色（`deletedAt`），消息/段/thread 一条都不删。
 *
 * 依据 §4.4/§4.5/§6、§8 。
 */
class ImportRepository(private val db: PulsezDatabase) {

    /**
     * 把若干段已解析的会话写进目标 TA。
     *
     * 调用方（导入编排）负责：ZIP 读取、解析、按会话跳过解析失败（那类「读不出来」在编排层
     * 计数并换错误码）、进度展示、取消。本方法只做事务与契约保证。
     *
     * @param progress 每个会话处理完后回调 `(done, total, messages)`；`messages` 是累计新写入条数。
     *   方法一开始会先回调一次 `(0, total, 0)`，方便 UI 立刻显示总段数。
     * @return 导入汇总；单会话事务失败不会中断整次导入（回滚该会话、计入 `sessionsFailed`）。
     *   只有「续导目标不存在/已删」这类全局契约错误会抛 [ImportException]。
     */
    suspend fun importSessions(
        target: ImportTarget,
        sessions: List<ParsedDeepSeekSession>,
        progress: (done: Int, total: Int, messages: Int) -> Unit = { _, _, _ -> },
    ): ImportSummary {
        val resolved = when (target) {
            is ImportTarget.NewCharacter -> resolveTarget(target)
        }

        val issues = mutableListOf<Issue>()
        val importedSegments = mutableListOf<ImportedSegment>()
        val importedAssistantTexts = mutableListOf<String>()
        var sessionsImported = 0
        var sessionsSkippedExisting = 0
        var sessionsFailed = 0
        var messagesInserted = 0
        var messagesExisting = 0
        var lastInsertedAt: Long? = null
        var lastProcessedAt: Long? = null

        val total = sessions.size
        progress(0, total, 0)
        sessions.forEachIndexed { index, session ->
            issues += session.issues
            if (session.turns.isEmpty()) {
                // 解析层契约是「0 条就抛」，这里只做防御：不建空段，计入失败（结果页"读不出来"）。
                sessionsFailed++
                issues += Issue(DeepSeekParseException.EMPTY_SESSION)
            } else {
                try {
                    when (val outcome = importOneSession(resolved.characterId, resolved.threadId, session)) {
                        is SessionOutcome.Imported -> {
                            sessionsImported++
                            messagesInserted += outcome.segment.messageIds.size
                            messagesExisting += outcome.existingMessages
                            importedSegments += outcome.segment
                            session.turns.forEach { turn ->
                                if (turn.role == DeepSeekTurn.ROLE_ASSISTANT) {
                                    importedAssistantTexts += turn.text
                                }
                            }
                            lastInsertedAt = maxOf(lastInsertedAt ?: Long.MIN_VALUE, outcome.segment.lastMessageAt)
                            lastProcessedAt = maxOf(lastProcessedAt ?: Long.MIN_VALUE, session.lastTurnAt())
                        }

                        is SessionOutcome.SkippedExisting -> {
                            sessionsSkippedExisting++
                            messagesExisting += outcome.existingMessages
                            lastProcessedAt = maxOf(lastProcessedAt ?: Long.MIN_VALUE, session.lastTurnAt())
                        }
                    }
                } catch (cancelled: CancellationException) {
                    // 取消必须向上传播，不能吞成"失败会话"（可停止）。
                    throw cancelled
                } catch (failure: Exception) {
                    // 单会话失败已由 Room 回滚；不计入任何导入数，只记原因码后继续。
                    sessionsFailed++
                    issues += Issue(failureCode(failure))
                }
            }
            progress(index + 1, total, messagesInserted)
        }

        // 收尾事务（第 5 条）：只前进的时间 + 累加计数，全部定向 UPDATE。
        val now = System.currentTimeMillis()
        val insertedAt = lastInsertedAt
        val processedAt = lastProcessedAt
        val derivedStyleSamples = pickStyleSamples(importedAssistantTexts)
        db.withTransaction {
            // lastExtractAt 用「本轮处理过的全部会话（含幂等命中）」的最晚源时间做下限：
            // 上一次中断可能已经把消息写进库、却没来得及写 lastExtractAt；续导时这些会话
            // 全是幂等命中，只看新写入时间会漏掉它们，自动抽取就会把导入往事当新对话读走
            //（抽取隔离）。
            if (processedAt != null) {
                db.threadDao().bumpLastExtractAt(resolved.threadId, processedAt)
            }
            if (messagesInserted > 0 && insertedAt != null) {
                db.threadDao().bumpLastMessageAt(resolved.threadId, insertedAt)
                db.threadDao().addTotalMessages(resolved.threadId, messagesInserted)
            }
            if (derivedStyleSamples != null) {
                val currentChar = db.characterDao().byId(resolved.characterId)
                if (currentChar != null && currentChar.styleSamples.isNullOrBlank()) {
                    updateCharacterProfileIfBlank(currentChar, newUserName = null, newStyleSamples = derivedStyleSamples)
                }
            }
            db.characterDao().touchUpdatedAt(resolved.characterId, now)
        }

        return ImportSummary(
            characterId = resolved.characterId,
            threadId = resolved.threadId,
            sessionsImported = sessionsImported,
            sessionsSkippedExisting = sessionsSkippedExisting,
            sessionsFailed = sessionsFailed,
            messagesInserted = messagesInserted,
            messagesExisting = messagesExisting,
            segments = importedSegments,
            issues = issues,
            lastImportedAt = lastInsertedAt,
            createdNewCharacter = resolved.createdNewCharacter,
        )
    }

    /**
     * 撤销本次导入：**只软删角色**（`character.deletedAt = now`）。
     *
     * 消息、段、thread 全部保留：删除是最危险的操作，撤销的语义是「这个 TA 不再出现」，
     * 而不是物理清库；数据还在这台手机上，但**不会再进之后的备份/导出**——导出只带存活
     * 角色，角色已软删的整段会话会被跳过、只计数（`ExportService` 的 `skippedOrphanThreads`），
     * 恢复也不含（「被推翻/收窄的已发布表述」第 5 条）。
     * 清 `ImportPrefs` 指纹由调用方做。
     *
     * **只对「本次新建」的 TA 调用**：续导复用的 TA 承载着更早几次导入的历史，对它调用等于把
     * 既往历史整体隐藏。调用方（`DeepSeekImportViewModel`）先用
     * [ImportSummary.createdNewCharacter] 判 `canUndo`，本方法不做二次校验（角色删除是幂等的，
     * 且此处拿不到「哪一次导入新建了它」的信息）。
     */
    suspend fun undoImport(characterId: String) {
        db.characterDao().softDelete(characterId, System.currentTimeMillis())
    }

    /** Persist the user's entire selection before the first group transaction for crash recovery. */
    suspend fun beginBatch(plan: ImportBatchPlan) = db.withTransaction {
        require(plan.groups.map { it.id }.distinct().size == plan.groups.size)
        // One destination TA gets one group per batch; otherwise undo would subtract counts and
        // restore metadata more than once for the same thread. The existing TA id is the provenance
        // scope used by the unique (targetCharacterId, sourceSessionId) key.
        require(plan.groups.mapNotNull { it.targetCharacterId }.distinct().size == plan.groups.count { it.targetCharacterId != null })
        require(plan.windows.map { it.preview.sourceSessionId }.distinct().size == plan.windows.size)
        val groupIds = plan.groups.mapTo(HashSet()) { it.id }
        require(plan.windows.all { it.groupId == null || it.groupId in groupIds })
        val current = db.importBatchDao().batch(plan.id)
        if (current != null) {
            if (current.fingerprint != plan.fingerprint) throw ImportBatchException(ImportBatchException.BATCH_MISSING)
            db.importBatchDao().setBatchStatus(plan.id, BATCH_RUNNING, current.postProcessStatus)
            return@withTransaction
        }
        val now = System.currentTimeMillis()
        db.importBatchDao().upsertBatch(
            ImportBatchEntity(
                id = plan.id,
                fingerprint = plan.fingerprint,
                zipName = plan.zipName,
                createdAt = now,
                status = BATCH_RUNNING,
                postProcessStatus = POST_PROCESS_PENDING,
            ),
        )
        db.importBatchDao().upsertGroups(
            plan.groups.map { target ->
                ImportBatchGroupEntity(
                    id = target.id,
                    batchId = plan.id,
                    targetCharacterId = target.targetCharacterId,
                    targetThreadId = null,
                    isNewCharacter = target.targetCharacterId == null,
                    name = target.name,
                    persona = target.persona,
                    status = GROUP_PENDING,
                    memoryFingerprint = null,
                    beforeLastMessageAt = null,
                    beforeLastExtractAt = null,
                    beforeCharacterUpdatedAt = null,
                    importLastMessageAt = null,
                    importMessageCount = 0,
                    errorCode = null,
                )
            },
        )
        db.importBatchDao().upsertWindows(
            plan.windows.map { selection ->
                val preview = selection.preview
                ImportBatchWindowEntity(
                    batchId = plan.id,
                    sourceSessionId = preview.sourceSessionId,
                    entryName = preview.entryName,
                    title = preview.title,
                    sourceStartedAt = preview.sourceStartedAt,
                    messageCount = preview.messageCount,
                    damageCode = preview.damageCode,
                    groupId = selection.groupId,
                    status = if (selection.groupId == null) WINDOW_SKIPPED else WINDOW_PENDING,
                    errorCode = null,
                )
            },
        )
    }

    /** Return a saved assignment plan after process death; only unfinished groups need another run. */
    suspend fun resumableBatch(fingerprint: String): ImportBatchPlan? {
        val batch = db.importBatchDao().resumable(fingerprint) ?: return null
        val groups = db.importBatchDao().groups(batch.id).map { group ->
            ImportBatchGroupPlan(
                id = group.id,
                targetCharacterId = group.targetCharacterId.takeUnless { group.isNewCharacter },
                name = group.name,
                persona = group.persona,
                status = group.status,
            )
        }
        val windows = db.importBatchDao().windows(batch.id).map { window ->
            ImportBatchWindowPlan(
                preview = DeepSeekSessionPreview(
                    entryName = window.entryName,
                    sourceSessionId = window.sourceSessionId,
                    title = window.title,
                    sourceStartedAt = window.sourceStartedAt,
                    messageCount = window.messageCount,
                    damageCode = window.damageCode,
                ),
                groupId = window.groupId,
            )
        }
        return ImportBatchPlan(batch.id, batch.fingerprint, batch.zipName, groups, windows)
    }

    /** One TA group is the atomic commit boundary: role, thread, segments, messages and provenance. */
    suspend fun commitBatchGroup(
        batchId: String,
        groupId: String,
        sessions: List<ParsedDeepSeekSession>,
        isCancelled: () -> Boolean = { false },
        userName: String? = null,
        styleSamples: String? = null,
    ): ImportBatchGroupResult = db.withTransaction {
        if (isCancelled()) throw CancellationException("batch import cancelled before group commit")
        val batch = db.importBatchDao().batch(batchId) ?: throw ImportBatchException(ImportBatchException.BATCH_MISSING)
        if (batch.status != BATCH_RUNNING) throw ImportBatchException(ImportBatchException.BATCH_ACTIVE)
        val group = db.importBatchDao().group(groupId)?.takeIf { it.batchId == batchId }
            ?: throw ImportBatchException(ImportBatchException.GROUP_MISSING)
        if (group.status == GROUP_SUCCESS) return@withTransaction resultForGroup(batchId, group)
        val assigned = db.importBatchDao().windowsForGroup(batchId, groupId)
        if (sessions.map { it.sourceSessionId }.toSet() != assigned.map { it.sourceSessionId }.toSet()) {
            throw ImportBatchException(ImportException.SESSION_FAILED)
        }
        val ordered = sessions.sortedWith(
            compareBy<ParsedDeepSeekSession> { it.sourceStartedAt == null }
                .thenBy { it.sourceStartedAt ?: Long.MAX_VALUE }
                .thenBy { it.sourceSessionId },
        )
        // Both modern provenance and pre-batch deterministic message IDs participate in global de-dup.
        // Recheck after preview in the same group transaction that writes the new source rows.
        val importedSourceIds = importedSourceIdsForCandidates(
            ordered.map { it.sourceSessionId to it.turns.map { turn -> turn.sourceMessageId } },
        )
        val sourceRowsById = loadSourceRows(ordered.map { it.sourceSessionId }).groupBy { it.sourceSessionId }

        // A newly selected TA must not be created when every assigned source was already imported.
        // Persist the successful skip so a resumed batch won't create an empty TA later.
        if (group.isNewCharacter && ordered.isNotEmpty() && ordered.all { it.sourceSessionId in importedSourceIds }) {
            ordered.forEach { session ->
                db.importBatchDao().setWindowStatus(batchId, session.sourceSessionId, WINDOW_SKIPPED_EXISTING, null)
            }
            db.importBatchDao().upsertGroup(
                group.copy(
                    targetCharacterId = null,
                    targetThreadId = null,
                    status = GROUP_SUCCESS,
                    memoryFingerprint = null,
                    beforeLastMessageAt = null,
                    beforeLastExtractAt = null,
                    beforeCharacterUpdatedAt = null,
                    importLastMessageAt = null,
                    importMessageCount = 0,
                    errorCode = null,
                ),
            )
            return@withTransaction ImportBatchGroupResult(
                groupId = groupId,
                characterId = null,
                threadId = null,
                sourceSessionsImported = 0,
                sourceSessionsSkipped = ordered.size,
                messagesInserted = 0,
                messagesExisting = ordered.sumOf { session ->
                    val sourceRows = sourceRowsById[session.sourceSessionId].orEmpty()
                    if (sourceRows.isNotEmpty()) countMessagesForSourceRows(sourceRows)
                    else session.turns.map { it.sourceMessageId }.distinct().size
                },
            )
        }

        val planUserName = userName?.trim()?.takeIf { it.isNotEmpty() }
        val planStyleSamples = normalizeStyleSamples(styleSamples)
        val character: CharacterEntity
        val thread: ThreadEntity
        if (group.isNewCharacter) {
            val now = System.currentTimeMillis()
            character = CharacterEntity(
                id = Ids.new(),
                name = group.name.trim().ifBlank { DEFAULT_CHARACTER_NAME },
                avatarUri = null,
                persona = group.persona?.trim().orEmpty(),
                greeting = null,
                styleSamples = planStyleSamples,
                userName = planUserName,
                systemPromptOverride = null,
                protocolEnabled = true,
                sourceType = SOURCE_TYPE_DEEPSEEK,
                sourceRef = batch.zipName,
                rawImported = null,
                emoAssetDir = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
            db.characterDao().upsert(character)
            val openId = Ids.new()
            thread = ThreadEntity(
                id = Ids.new(),
                characterId = character.id,
                title = null,
                createdAt = now,
                lastMessageAt = now,
                activeSegmentId = openId,
                segmentOrdinal = 0,
                totalMessages = 0,
                totalTokens = 0,
                lastExtractAt = 0L,
                archived = false,
                kind = ThreadKind.SINGLE,
            )
            db.threadDao().upsert(thread)
            db.segmentDao().upsert(
                SegmentEntity(
                    id = openId,
                    threadId = thread.id,
                    ordinal = 0,
                    summary = null,
                    styleNotes = null,
                    firstMessageAt = now,
                    lastMessageAt = now,
                    tokenSum = 0,
                    state = SEGMENT_STATE_OPEN,
                    batonReason = null,
                    createdAt = now,
                ),
            )
        } else {
            val targetId = group.targetCharacterId ?: throw ImportBatchException(ImportBatchException.TARGET_MISSING)
            character = db.characterDao().byId(targetId) ?: throw ImportBatchException(ImportBatchException.TARGET_MISSING)
            thread = db.threadDao().latestForCharacter(targetId) ?: throw ImportBatchException(ImportBatchException.TARGET_MISSING)
        }
        if (isCancelled()) throw CancellationException("batch import cancelled while preparing group")

        // Memory is not modified by this batch. A snapshot detects later or concurrent changes so undo can
        // fail closed before touching chat, rather than replacing mixed old/new memory (MemoryRepository.save
        // currently replaces the complete owner set; see its implementation at MemoryRepository.kt:147-164).
        val memoryBefore = memoryFingerprintForThread(thread.id)
        val beforeLastMessageAt = thread.lastMessageAt
        val beforeLastExtractAt = thread.lastExtractAt
        val beforeCharacterUpdatedAt = character.updatedAt
        var insertedMessages = 0
        var insertedSessions = 0
        var skippedSessions = 0
        var existingMessages = 0
        var lastImportedAt: Long? = null
        var lastProcessedAt: Long? = null
        val importedAssistantTexts = mutableListOf<String>()

        for (session in ordered) {
            if (isCancelled()) throw CancellationException("batch import cancelled during group commit")
            val existingSourceRows = sourceRowsById[session.sourceSessionId].orEmpty()
            if (session.sourceSessionId in importedSourceIds) {
                db.importBatchDao().setWindowStatus(batchId, session.sourceSessionId, WINDOW_SKIPPED_EXISTING, null)
                skippedSessions++
                existingMessages += if (existingSourceRows.isNotEmpty()) countMessagesForSourceRows(existingSourceRows)
                else session.turns.map { it.sourceMessageId }.distinct().size
                continue
            }
            val idToTurn = LinkedHashMap<String, DeepSeekTurn>(session.turns.size)
            session.turns.forEach { turn ->
                val id = messageIdFor(character.id, session.sourceSessionId, turn.sourceMessageId)
                if (id !in idToTurn) idToTurn[id] = turn
            }
            val existingIds = existingIdsInBatches(idToTurn.keys.toList())
            val newPairs = idToTurn.entries.filter { it.key !in existingIds }.map { it.key to it.value }
            if (newPairs.isEmpty()) {
                db.importBatchDao().setWindowStatus(batchId, session.sourceSessionId, WINDOW_SKIPPED_EXISTING, null)
                skippedSessions++
                existingMessages += existingIds.size
                continue
            }
            val open = db.segmentDao().forThread(thread.id)
                .filter { it.state == SEGMENT_STATE_OPEN }
                .maxByOrNull { it.ordinal }
                ?: throw ImportException(ImportException.OPEN_SEGMENT_MISSING)
            val segmentId = Ids.new()
            val messages = newPairs.map { (id, turn) ->
                MessageEntity(
                    id = id,
                    threadId = thread.id,
                    segmentId = segmentId,
                    role = turn.role,
                    content = turn.text,
                    tokens = TokenEstimator.estimate(turn.text),
                    emotion = null,
                    status = MESSAGE_STATUS_OK,
                    draft = null,
                    usageIn = null,
                    usageOut = null,
                    previousContent = null,
                    createdAt = turn.at,
                    deletedAt = null,
                    speakerCharacterId = null,
                )
            }
            db.segmentDao().upsert(
                SegmentEntity(
                    id = segmentId,
                    threadId = thread.id,
                    ordinal = open.ordinal,
                    summary = null,
                    styleNotes = null,
                    firstMessageAt = messages.first().createdAt,
                    lastMessageAt = messages.last().createdAt,
                    tokenSum = messages.sumOf { it.tokens },
                    state = SEGMENT_STATE_SEALED,
                    batonReason = BATON_REASON_IMPORT,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            if (db.segmentDao().setOrdinalIfOpen(open.id, open.ordinal + 1) != 1) {
                throw ImportException(ImportException.OPEN_SEGMENT_MISSING)
            }
            db.threadDao().setActiveSegment(thread.id, open.id, open.ordinal + 1)
            db.messageDao().insertAllIgnore(messages)
            val source = ImportSourceSessionEntity(
                id = Ids.new(),
                batchId = batchId,
                targetCharacterId = character.id,
                targetThreadId = thread.id,
                sourceSessionId = session.sourceSessionId,
                title = session.title,
                sourceStartedAt = session.sourceStartedAt,
                messageCount = session.sourceMessageCount,
                segmentId = segmentId,
                indexStatus = INDEX_PENDING,
            )
            if (db.importBatchDao().insertSource(source) == -1L) {
                throw ImportException(ImportException.SESSION_FAILED)
            }
            db.importBatchDao().setWindowStatus(batchId, session.sourceSessionId, WINDOW_IMPORTED, null)
            newPairs.forEach { (_, turn) ->
                if (turn.role == DeepSeekTurn.ROLE_ASSISTANT) {
                    importedAssistantTexts += turn.text
                }
            }
            insertedMessages += messages.size
            insertedSessions++
            lastImportedAt = maxOf(lastImportedAt ?: Long.MIN_VALUE, messages.last().createdAt)
            lastProcessedAt = maxOf(lastProcessedAt ?: Long.MIN_VALUE, session.turns.maxOf { it.at })
        }
        val now = System.currentTimeMillis()
        if (isCancelled()) throw CancellationException("batch import cancelled before group commit")
        val derivedStyleSamples = pickStyleSamples(importedAssistantTexts)
        updateCharacterProfileIfBlank(
            character = character,
            newUserName = planUserName,
            newStyleSamples = planStyleSamples ?: derivedStyleSamples,
        )
        if (lastProcessedAt != null) db.threadDao().bumpLastExtractAt(thread.id, lastProcessedAt!!)
        if (insertedMessages > 0) {
            db.threadDao().addTotalMessages(thread.id, insertedMessages)
            db.threadDao().bumpLastMessageAt(thread.id, lastImportedAt!!)
        }
        db.importBatchDao().upsertGroup(
            group.copy(
                targetCharacterId = character.id,
                targetThreadId = thread.id,
                status = GROUP_SUCCESS,
                memoryFingerprint = memoryBefore,
                beforeLastMessageAt = beforeLastMessageAt,
                beforeLastExtractAt = beforeLastExtractAt,
                beforeCharacterUpdatedAt = beforeCharacterUpdatedAt,
                importLastMessageAt = lastImportedAt,
                importMessageCount = insertedMessages,
                errorCode = null,
            ),
        )
        ImportBatchGroupResult(
            groupId = groupId,
            characterId = character.id,
            threadId = thread.id,
            sourceSessionsImported = insertedSessions,
            sourceSessionsSkipped = skippedSessions,
            messagesInserted = insertedMessages,
            messagesExisting = existingMessages,
        )
    }

    suspend fun markBatchGroupFailed(batchId: String, groupId: String, code: String) = db.withTransaction {
        db.importBatchDao().setGroupStatus(groupId, GROUP_FAILED, code)
        db.importBatchDao().windowsForGroup(batchId, groupId).forEach { window ->
            db.importBatchDao().setWindowStatus(batchId, window.sourceSessionId, WINDOW_FAILED, code)
        }
    }

    suspend fun markBatchGroupPending(batchId: String, groupId: String) = db.withTransaction {
        db.importBatchDao().setGroupStatus(groupId, GROUP_PENDING, null)
        db.importBatchDao().windowsForGroup(batchId, groupId).forEach { window ->
            db.importBatchDao().setWindowStatus(batchId, window.sourceSessionId, WINDOW_PENDING, null)
        }
    }

    suspend fun finishBatch(batchId: String, status: String, postProcessStatus: String) {
        db.importBatchDao().setBatchStatus(batchId, status, postProcessStatus)
    }

    /**
     * Persist post-processing state only while its batch is still active. This serializes with
     * [undoBatch], whose delete transaction refuses a RUNNING batch, so a late index result cannot
     * recreate source state after undo. Source chunks/terms use the same RUNNING guard in
     * [saveBatchChunksIfActive].
     */
    suspend fun setBatchSourceIndexStatus(batchId: String, segmentId: String, status: String): Boolean =
        db.withTransaction {
            if (db.importBatchDao().batch(batchId)?.status != BATCH_RUNNING) return@withTransaction false
            val sources = db.importBatchDao().sourcesForBatch(batchId).filter { it.segmentId == segmentId }
            if (sources.isEmpty()) return@withTransaction false
            sources.forEach { db.importBatchDao().setSourceIndexStatus(it.id, status) }
            true
        }

    /**
     * Persist one batch index block only while the batch and its source segment are still live.
     * The importer passes one complete provider-response batch at a time. When [sourceIndexComplete]
     * is non-null, the source's terminal status is committed in the same Room transaction as every
     * chunk and term returned by that terminal response. This prevents a crash after the final chunk
     * commit from leaving a fully indexed source pending and charging for a duplicate embedding on resume.
     * The state check and both index tables share a transaction with [undoBatch], preventing a late
     * indexer from recreating chunks or terms after the undo has removed them.
     */
    suspend fun saveBatchChunksIfActive(
        batchId: String,
        chunks: List<ChunkEntity>,
        terms: List<ChunkTermEntity>,
        sourceIndexComplete: Boolean? = null,
    ): Boolean = db.withTransaction {
        if (db.importBatchDao().batchStatus(batchId) != BATCH_RUNNING) return@withTransaction false
        if (chunks.any { db.segmentDao().byId(it.segmentId) == null }) return@withTransaction false
        val sourceRows = if (sourceIndexComplete != null) {
            val segmentIds = chunks.map { it.segmentId }.distinct()
            if (segmentIds.size != 1) return@withTransaction false
            db.importBatchDao().sourcesForBatch(batchId).filter { it.segmentId == segmentIds.single() }
                .also { if (it.isEmpty()) return@withTransaction false }
        } else {
            emptyList()
        }
        if (chunks.isNotEmpty()) db.chunkDao().insertAll(chunks)
        if (terms.isNotEmpty()) db.chunkDao().insertTerms(terms)
        if (sourceIndexComplete != null) {
            val status = if (sourceIndexComplete) INDEX_DONE else INDEX_PARTIAL
            sourceRows.forEach { db.importBatchDao().setSourceIndexStatus(it.id, status) }
        }
        true
    }

    /** Load only durable, live checkpoint chunks for an active batch source; IDs are deterministic per block. */
    suspend fun loadBatchChunksIfActive(
        batchId: String,
        segmentId: String,
        chunkIds: List<String>,
    ): List<ChunkEntity> = db.withTransaction {
        if (chunkIds.isEmpty()) return@withTransaction emptyList()
        if (db.importBatchDao().batchStatus(batchId) != BATCH_RUNNING) return@withTransaction emptyList()
        if (db.segmentDao().byId(segmentId) == null) return@withTransaction emptyList()
        if (db.importBatchDao().sourcesForBatch(batchId).none { it.segmentId == segmentId }) {
            return@withTransaction emptyList()
        }
        chunkIds.distinct().chunked(SQLITE_VARIABLE_BATCH).flatMap { ids ->
            db.chunkDao().byIds(ids).filter { it.segmentId == segmentId }
        }
    }

    /** Exact batch undo. All memory checks happen before the first delete in the same Room transaction. */
    suspend fun undoBatch(batchId: String) = db.withTransaction {
        val batch = db.importBatchDao().batch(batchId) ?: throw ImportBatchException(ImportBatchException.BATCH_MISSING)
        if (batch.status == BATCH_RUNNING) throw ImportBatchException(ImportBatchException.BATCH_ACTIVE)
        if (batch.status == BATCH_UNDONE) return@withTransaction
        val groups = db.importBatchDao().groups(batchId)
        val sources = db.importBatchDao().sourcesForBatch(batchId)
        val sourcesByGroupThread = sources.groupBy { it.targetThreadId }
        for (group in groups.filter { it.status == GROUP_SUCCESS }) {
            val threadId = group.targetThreadId ?: continue
            if (db.threadDao().byId(threadId) == null) throw ImportBatchException(ImportBatchException.TARGET_MISSING)
            val expected = group.memoryFingerprint ?: memoryFingerprintForThread(threadId)
            if (memoryFingerprintForThread(threadId) != expected) {
                throw ImportBatchException(ImportBatchException.MEMORY_CHANGED)
            }
            // No batch memory consolidation is run. This additional relation check fails closed if a future
            // memory path starts attaching a memory directly to one of the imported segments.
            val importedSegmentIds = sourcesByGroupThread[threadId].orEmpty().mapTo(HashSet()) { it.segmentId }
            if (db.memoryDao().allForThread(threadId).any { it.sourceSegmentId in importedSegmentIds }) {
                throw ImportBatchException(ImportBatchException.MEMORY_CHANGED)
            }
        }
        // Mark undone before removing artifacts. The status and all deletions commit together; batch-aware
        // indexing writers check this status in their own transaction before inserting chunks/terms.
        db.importBatchDao().setBatchStatus(batchId, BATCH_UNDONE, POST_PROCESS_UNDONE)
        for (source in sources) {
            db.chunkDao().deleteTermsForSegment(source.segmentId)
            db.chunkDao().deleteForSegment(source.segmentId)
            db.messageDao().deleteForSegment(source.segmentId)
            db.segmentDao().deleteById(source.segmentId)
            db.importBatchDao().deleteSource(source.id)
            db.importBatchDao().setWindowStatus(batchId, source.sourceSessionId, WINDOW_UNDONE, null)
        }
        for (group in groups.filter { it.status == GROUP_SUCCESS }) {
            val characterId = group.targetCharacterId ?: continue
            val threadId = group.targetThreadId ?: continue
            val sourceRows = sources.filter { it.targetThreadId == threadId }
            val segmentIds = sourceRows.mapTo(HashSet()) { it.segmentId }
            if (group.isNewCharacter) {
                val remainingMessages = db.messageDao().allIncludingDeletedForThread(threadId)
                    .any { it.segmentId !in segmentIds }
                val hasThreadState = db.threadStateDao().get(threadId) != null
                if (!remainingMessages && !hasThreadState) {
                    db.threadStateDao().deleteForThread(threadId)
                    db.memoryDao().deleteForThread(threadId)
                    db.segmentDao().forThread(threadId).forEach { db.segmentDao().deleteById(it.id) }
                    db.threadDao().deleteById(threadId)
                    db.characterDao().deleteById(characterId)
                } else {
                    // Keep a newly created TA when it acquired later chat/state, but remove this
                    // batch's contribution from its counters and timestamps just as for an existing TA.
                    val current = db.threadDao().byId(threadId) ?: continue
                    val count = (current.totalMessages - group.importMessageCount).coerceAtLeast(0)
                    val maxRemainingAt = db.messageDao().maxLiveCreatedAt(threadId) ?: current.createdAt
                    val extractAt = if (
                        group.importLastMessageAt != null && current.lastExtractAt <= group.importLastMessageAt
                    ) {
                        group.beforeLastExtractAt ?: 0L
                    } else {
                        current.lastExtractAt
                    }
                    db.threadDao().restoreAfterImportUndo(threadId, count, maxRemainingAt, extractAt)
                }
            } else {
                val current = db.threadDao().byId(threadId) ?: continue
                val count = (current.totalMessages - group.importMessageCount).coerceAtLeast(0)
                val maxRemainingAt = db.messageDao().maxLiveCreatedAt(threadId) ?: current.createdAt
                val extractAt = if (
                    group.importLastMessageAt != null && current.lastExtractAt <= group.importLastMessageAt
                ) {
                    group.beforeLastExtractAt ?: 0L
                } else {
                    current.lastExtractAt
                }
                db.threadDao().restoreAfterImportUndo(threadId, count, maxRemainingAt, extractAt)
            }
            db.importBatchDao().upsertGroup(group.copy(status = GROUP_UNDONE, errorCode = null))
        }
        db.importBatchDao().windows(batchId).filter { it.status == WINDOW_PENDING || it.status == WINDOW_FAILED }
            .forEach { db.importBatchDao().setWindowStatus(batchId, it.sourceSessionId, WINDOW_UNDONE, null) }
    }

    /** Metadata used by the chat history header; message bodies are never included. */
    suspend fun importSourcesForThread(threadId: String): List<ImportSourceSessionEntity> =
        db.importBatchDao().sourcesForThread(threadId)

    /**
     * Source IDs already present in current provenance or recognizable legacy message IDs.
     * Preview passes only identifiers, never chat text. Legacy matching is exact against the current
     * deterministic ID recipe and includes soft-deleted roles/messages; it cannot claim coverage of
     * pre-batch imports whose IDs were not produced by that recipe.
     */
    suspend fun importedSourceSessionIds(previews: List<DeepSeekSessionPreview>): Set<String> =
        importedSourceIdsForCandidates(previews.distinctBy { it.sourceSessionId }.map { preview ->
            preview.sourceSessionId to preview.sourceMessageIds
        })

    private suspend fun importedSourceIdsForCandidates(sessions: List<Pair<String, List<Long>>>): Set<String> {
        if (sessions.isEmpty()) return emptySet()
        val allSourceIds = sessions.map { it.first }.distinct()
        val sourceRows = loadSourceRows(allSourceIds)
        val alreadyImported = sourceRows.mapTo(HashSet()) { it.sourceSessionId }
        val untracked = sessions.distinctBy { it.first }.filter { (sourceId, ids) ->
            sourceId !in alreadyImported && ids.isNotEmpty()
        }
        if (untracked.isEmpty()) return alreadyImported

        val candidates = mutableListOf<Pair<String, List<String>>>()
        val characters = db.characterDao().allIncludingDeleted()
        for ((sourceId, sourceMessageIds) in untracked) {
            val distinctMessageIds = sourceMessageIds.distinct()
            for (character in characters) {
                val ids = distinctMessageIds.map { messageIdFor(character.id, sourceId, it) }
                candidates += sourceId to ids
            }
        }
        val existingIds = existingIdsInBatches(candidates.flatMap { it.second })
        candidates.forEach { (sourceId, ids) ->
            if (ids.isNotEmpty() && ids.all { it in existingIds }) alreadyImported += sourceId
        }
        return alreadyImported
    }

    private suspend fun loadSourceRows(sourceSessionIds: List<String>): List<ImportSourceSessionEntity> {
        if (sourceSessionIds.isEmpty()) return emptyList()
        return sourceSessionIds.distinct().chunked(SQLITE_VARIABLE_BATCH).flatMap { ids ->
            db.importBatchDao().sourcesForSessionIds(ids)
        }
    }

    /** Historical backups may preserve duplicate source rows across TAs; count one best-backed copy. */
    private suspend fun countMessagesForSourceRows(rows: List<ImportSourceSessionEntity>): Int {
        var count = 0
        for (row in rows) {
            count = maxOf(count, db.messageDao().bySegment(row.segmentId).size)
        }
        return count
    }

    suspend fun importSegmentsForBatch(batchId: String, threadId: String): List<ImportedSegment> =
        db.importBatchDao().sourcesForBatch(batchId)
            .filter { it.targetThreadId == threadId }
            .mapNotNull { source ->
                val messages = db.messageDao().bySegment(source.segmentId)
                if (messages.isEmpty()) return@mapNotNull null
                ImportedSegment(
                    sourceSessionId = source.sourceSessionId,
                    segmentId = source.segmentId,
                    messageIds = messages.map { it.id },
                    firstMessageAt = messages.first().createdAt,
                    lastMessageAt = messages.last().createdAt,
                )
            }

    /** Only source segments whose keyword/vector post-processing has not reached INDEX_DONE. */
    suspend fun importSegmentsNeedingIndexForBatch(batchId: String, threadId: String): List<ImportedSegment> =
        db.importBatchDao().sourcesForBatch(batchId)
            .filter { it.targetThreadId == threadId && it.indexStatus != INDEX_DONE }
            .mapNotNull { source ->
                val messages = db.messageDao().bySegment(source.segmentId)
                if (messages.isEmpty()) return@mapNotNull null
                ImportedSegment(
                    sourceSessionId = source.sourceSessionId,
                    segmentId = source.segmentId,
                    messageIds = messages.map { it.id },
                    firstMessageAt = messages.first().createdAt,
                    lastMessageAt = messages.last().createdAt,
                )
            }

    private suspend fun resultForGroup(batchId: String, group: ImportBatchGroupEntity): ImportBatchGroupResult {
        val sources = db.importBatchDao().sourcesForBatch(batchId).filter { it.targetThreadId == group.targetThreadId }
        return ImportBatchGroupResult(
            groupId = group.id,
            characterId = group.targetCharacterId,
            threadId = group.targetThreadId,
            sourceSessionsImported = sources.size,
            sourceSessionsSkipped = db.importBatchDao().windowsForGroup(batchId, group.id).count { it.status == WINDOW_SKIPPED_EXISTING },
            messagesInserted = group.importMessageCount,
            messagesExisting = db.importBatchDao().windowsForGroup(batchId, group.id)
                .filter { it.status == WINDOW_SKIPPED_EXISTING }
                .sumOf { window ->
                    countMessagesForSourceRows(db.importBatchDao().sourcesForSessionIds(listOf(window.sourceSessionId)))
                },
            errorCode = group.errorCode,
        )
    }

    suspend fun memoryFingerprintForThread(threadId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String?) {
            val bytes = (value ?: "<null>").toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        db.memoryDao().allForThread(threadId).sortedBy { it.id }.forEach { row ->
            add("MEMORY_ITEM")
            add(row.id); add(row.threadId); add(row.kind); add(row.key); add(row.value)
            add(row.confidence.toString()); add(row.importance.toString()); add(row.happenedAt?.toString())
            add(row.dueAt?.toString()); add(row.validFrom.toString()); add(row.validTo?.toString())
            add(row.firstSeenAt.toString()); add(row.lastSeenAt.toString()); add(row.sourceSegmentId)
            add(row.sourceMessageIds); add(row.status); add(row.supersededBy); add(row.characterId)
        }
        db.threadStateDao().get(threadId)?.let { state ->
            // Relation state is stored outside memory_item but is also derived memory and must fail closed.
            add("THREAD_STATE")
            add(state.threadId); add(state.relationStage); add(state.nicknameByMe)
            add(state.nicknameForMe); add(state.mood); add(state.updatedAt.toString())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- 目标解析

    /** @property createdNewCharacter 本次是否新建了 TA（续导复用旧 TA 时为 false）。 */
    private class ResolvedTarget(
        val characterId: String,
        val threadId: String,
        val createdNewCharacter: Boolean,
    )

    /** 新建 TA（一个事务：character → thread → OPEN 段）或续导复用已有 TA 的最新 thread。 */
    private suspend fun resolveTarget(target: ImportTarget.NewCharacter): ResolvedTarget {
        val planUserName = target.userName?.trim()?.takeIf { it.isNotEmpty() }
        val planStyleSamples = normalizeStyleSamples(target.styleSamples)
        val resumeCharacterId = target.resumeCharacterId?.trim()?.takeIf { it.isNotEmpty() }
        if (resumeCharacterId != null) {
            // 续导：角色必须存在且未被软删；thread 取最新一条（不重建 OPEN 段，§4.5 第 6 条）。
            return db.withTransaction {
                val character = db.characterDao().byId(resumeCharacterId)
                    ?: throw ImportException(ImportException.RESUME_TARGET_MISSING)
                val thread = db.threadDao().latestForCharacter(character.id)
                    ?: throw ImportException(ImportException.RESUME_TARGET_MISSING)
                updateCharacterProfileIfBlank(character, planUserName, planStyleSamples)
                ResolvedTarget(character.id, thread.id, createdNewCharacter = false)
            }
        }

        val now = System.currentTimeMillis()
        val segmentId = Ids.new()
        return db.withTransaction {
            // 1) 先落角色（thread 对 character 有外键，顺序不能反）。
            val character = CharacterEntity(
                id = Ids.new(),
                name = target.name.trim().ifBlank { DEFAULT_CHARACTER_NAME },
                avatarUri = null,
                persona = target.persona?.trim().orEmpty(),
                greeting = null,
                styleSamples = planStyleSamples,
                userName = planUserName,
                systemPromptOverride = null,
                protocolEnabled = true,
                sourceType = SOURCE_TYPE_DEEPSEEK,
                sourceRef = target.zipName,
                // 不存原始 ZIP 原文（包大 + 隐私），只留 zip 文件名。
                rawImported = null,
                emoAssetDir = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
            db.characterDao().upsert(character)

            // 2) 再落 thread，activeSegmentId 直接指向下面的空 OPEN 段。
            val thread = ThreadEntity(
                id = Ids.new(),
                characterId = character.id,
                title = null,
                createdAt = now,
                lastMessageAt = now,
                activeSegmentId = segmentId,
                segmentOrdinal = 0,
                totalMessages = 0,
                totalTokens = 0,
                lastExtractAt = 0L,
                archived = false,
                kind = ThreadKind.SINGLE,
            )
            db.threadDao().upsert(thread)

            // 3) 最后一个空 OPEN 段（ordinal=0）：导入往事都插在它下面，它本身永远只放新聊内容。
            db.segmentDao().upsert(
                SegmentEntity(
                    id = segmentId,
                    threadId = thread.id,
                    ordinal = 0,
                    summary = null,
                    styleNotes = null,
                    firstMessageAt = now,
                    lastMessageAt = now,
                    tokenSum = 0,
                    state = SEGMENT_STATE_OPEN,
                    batonReason = null,
                    createdAt = now,
                ),
            )
            ResolvedTarget(character.id, thread.id, createdNewCharacter = true)
        }
    }

    // ---------------------------------------------------------------- 单会话事务

    private sealed interface SessionOutcome {
        data class Imported(val segment: ImportedSegment, val existingMessages: Int) : SessionOutcome
        data class SkippedExisting(val existingMessages: Int) : SessionOutcome
    }

    /**
     * 一个会话 = 一个事务（「事务」、§6）。
     *
     * 事务内顺序：算确定性 id → 查已存在集合（≤500/批）→ 全已存在则直接返回（不建段、不推进
     * ordinal）→ 建 SEALED 段（ordinal = 当前 OPEN ordinal）→ OPEN ordinal+1 且
     * `thread.segmentOrdinal` 同步 → 批量 `insertAllIgnore` 新消息。
     *
     * 只写「新」消息：已存在的行一律不动（不覆盖用户可见内容），因此「部分已存在」时新段里
     * 只含这次真正新增的消息（正常中断不会出现：每个会话事务是原子的）。
     */
    private suspend fun importOneSession(
        characterId: String,
        threadId: String,
        session: ParsedDeepSeekSession,
    ): SessionOutcome = db.withTransaction {
        val thread = db.threadDao().byId(threadId)
            ?: throw ImportException(ImportException.THREAD_MISSING)

        // 1) 确定性 id（幂等键；不含正文）。
        val idToTurn = LinkedHashMap<String, DeepSeekTurn>(session.turns.size)
        session.turns.forEach { turn ->
            val id = messageIdFor(characterId, session.sourceSessionId, turn.sourceMessageId)
            if (!idToTurn.containsKey(id)) idToTurn[id] = turn
        }
        val existingIds = existingIdsInBatches(idToTurn.keys.toList())

        // 2) 只留新消息；全部已存在 → 本会话跳过，不建段、不推进 ordinal（§6）。
        val newPairs = idToTurn.entries
            .filter { entry -> entry.key !in existingIds }
            .map { entry -> entry.key to entry.value }
        if (newPairs.isEmpty()) {
            return@withTransaction SessionOutcome.SkippedExisting(existingMessages = existingIds.size)
        }

        // 3) 当前 OPEN 段（ordinal 的唯一真相源）。
        val open = db.segmentDao().forThread(threadId)
            .filter { it.state == SEGMENT_STATE_OPEN }
            .maxByOrNull { it.ordinal }
            ?: throw ImportException(ImportException.OPEN_SEGMENT_MISSING)
        val sealedOrdinal = open.ordinal

        // 4) 导入往事只进 SEALED：summary=null（不进 ②），batonReason=IMPORT（仅标注）。
        val now = System.currentTimeMillis()
        val segmentId = Ids.new()
        val messages = newPairs.map { (id, turn) ->
            MessageEntity(
                id = id,
                threadId = threadId,
                segmentId = segmentId,
                role = turn.role,
                content = turn.text,
                tokens = TokenEstimator.estimate(turn.text),
                emotion = null,
                status = MESSAGE_STATUS_OK,
                draft = null,
                usageIn = null,
                usageOut = null,
                previousContent = null,
                createdAt = turn.at,
                deletedAt = null,
                speakerCharacterId = null,
            )
        }
        db.segmentDao().upsert(
            SegmentEntity(
                id = segmentId,
                threadId = threadId,
                ordinal = sealedOrdinal,
                summary = null,
                styleNotes = null,
                firstMessageAt = messages.first().createdAt,
                lastMessageAt = messages.last().createdAt,
                tokenSum = messages.sumOf { it.tokens },
                state = SEGMENT_STATE_SEALED,
                batonReason = BATON_REASON_IMPORT,
                createdAt = now,
            ),
        )

        // 5) 同事务把 OPEN 段 ordinal 上移 + thread.segmentOrdinal 同步（§4.5 第 3 条）。
        //    条件 UPDATE：若这一瞬间段被别的路径封存了，更新 0 行 → 抛错让 Room 回滚整个会话。
        val moved = db.segmentDao().setOrdinalIfOpen(open.id, sealedOrdinal + 1)
        if (moved != 1) {
            throw ImportException(ImportException.OPEN_SEGMENT_MISSING)
        }
        db.threadDao().setActiveSegment(thread.id, open.id, sealedOrdinal + 1)

        // 6) 批量插入（IGNORE 只是并发兜底；幂等计数已由上面的 existingIds 决定）。
        db.messageDao().insertAllIgnore(messages)

        SessionOutcome.Imported(
            segment = ImportedSegment(
                sourceSessionId = session.sourceSessionId,
                segmentId = segmentId,
                messageIds = messages.map { it.id },
                firstMessageAt = messages.first().createdAt,
                lastMessageAt = messages.last().createdAt,
            ),
            existingMessages = existingIds.size,
        )
    }

    /**
     * 会话里最晚的源消息时间（`lastExtractAt` 隔离下限用 已保证会话内严格递增，这里再兜一次）。
     */
    private fun ParsedDeepSeekSession.lastTurnAt(): Long = turns.maxOf { it.at }

    /**
     * 幂等判定用的批量查询：每批 ≤ [SQLITE_VARIABLE_BATCH] 个参数，避开 SQLite
     * `SQLITE_MAX_VARIABLE_NUMBER`（旧版 Android 上是 999）。
     */
    private suspend fun existingIdsInBatches(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val found = HashSet<String>(ids.size)
        ids.chunked(SQLITE_VARIABLE_BATCH).forEach { batch ->
            found += db.messageDao().existingIds(batch)
        }
        return found
    }

    private fun updateCharacterProfileIfBlank(
        character: CharacterEntity,
        newUserName: String?,
        newStyleSamples: String?,
    ) {
        val resolvedUserName = if (character.userName.isNullOrBlank()) {
            newUserName?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val resolvedStyleSamples = if (character.styleSamples.isNullOrBlank()) {
            normalizeStyleSamples(newStyleSamples)
        } else {
            null
        }
        val writable = db.openHelper.writableDatabase
        if (resolvedUserName != null) {
            writable.execSQL(
                "UPDATE `character` SET `userName` = ? WHERE `id` = ? AND (`userName` IS NULL OR TRIM(`userName`) = '')",
                arrayOf<Any>(resolvedUserName, character.id),
            )
        }
        if (resolvedStyleSamples != null) {
            writable.execSQL(
                "UPDATE `character` SET `styleSamples` = ? WHERE `id` = ? AND (`styleSamples` IS NULL OR TRIM(`styleSamples`) = '')",
                arrayOf<Any>(resolvedStyleSamples, character.id),
            )
        }
    }

    private fun normalizeStyleSamples(raw: String?): String? =
        raw?.split('\u0001')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("\u0001")

    private fun pickStyleSamples(assistantTexts: List<String>): String? {
        if (assistantTexts.isEmpty()) return null
        val picked = StyleSamples.pick(assistantTexts.map { Turn(Speaker.CHARACTER, it) })
        return picked.takeIf { it.isNotEmpty() }?.joinToString("\u0001")
    }

    private fun failureCode(failure: Exception): String = when (failure) {
        is ImportException -> failure.code
        is DeepSeekParseException -> failure.code
        else -> ImportException.SESSION_FAILED
    }

    companion object {
        /** `character.sourceType`：DeepSeek 旧聊天导入（§8 的文档口径）。*/
        const val SOURCE_TYPE_DEEPSEEK: String = "DEEPSEEK"

        /** 导入段标注（仅标注，不驱动逻辑）。*/
        const val BATON_REASON_IMPORT: String = "IMPORT"

        /** 新建 TA 名字为空时的兜底（§4.4：name 空则「未命名」）。 */
        const val DEFAULT_CHARACTER_NAME: String = "未命名"

        /** 段状态。*/
        const val SEGMENT_STATE_OPEN: String = "OPEN"
        const val SEGMENT_STATE_SEALED: String = "SEALED"

        /** 导入消息的流生命周期状态；**不写 FAILED**（§4.3：避免 tail 显示但 ③ 不读的状态分裂）。 */
        const val MESSAGE_STATUS_OK: String = "OK"

        /** `SELECT ... IN (...)` 每批参数上限（每批 ≤500）。*/
        const val SQLITE_VARIABLE_BATCH: Int = 500

        const val BATCH_RUNNING: String = "RUNNING"
        const val BATCH_COMPLETE: String = "COMPLETE"
        const val BATCH_PARTIAL: String = "PARTIAL"
        const val BATCH_CANCELLED: String = "CANCELLED"
        const val BATCH_UNDONE: String = "UNDONE"
        const val POST_PROCESS_PENDING: String = "PENDING"
        const val POST_PROCESS_DONE: String = "DONE"
        const val POST_PROCESS_PARTIAL: String = "PARTIAL"
        const val POST_PROCESS_UNDONE: String = "UNDONE"
        const val GROUP_PENDING: String = "PENDING"
        const val GROUP_SUCCESS: String = "SUCCESS"
        const val GROUP_FAILED: String = "FAILED"
        const val GROUP_UNDONE: String = "UNDONE"
        const val WINDOW_PENDING: String = "PENDING"
        const val WINDOW_IMPORTED: String = "IMPORTED"
        const val WINDOW_SKIPPED: String = "SKIPPED"
        const val WINDOW_SKIPPED_EXISTING: String = "SKIPPED_EXISTING"
        const val WINDOW_FAILED: String = "FAILED"
        const val WINDOW_UNDONE: String = "UNDONE"
        const val INDEX_PENDING: String = "PENDING"
        const val INDEX_DONE: String = "DONE"
        const val INDEX_PARTIAL: String = "PARTIAL"
        const val INDEX_FAILED: String = "FAILED"

        /**
         * 确定性 messageId（幂等键 原式）：
         * `UUID.nameUUIDFromBytes("deepseek:v3:<characterId>:<sourceSessionId>:<sourceMessageId>")`。
         *
         * 同一角色同一源消息重复导入必然得到同一个 id；换成新 TA 则 id 不同（各自独立历史）。
         */
        fun messageIdFor(
            characterId: String,
            sourceSessionId: String,
            sourceMessageId: Long,
        ): String = UUID.nameUUIDFromBytes(
            "deepseek:v3:$characterId:$sourceSessionId:$sourceMessageId".toByteArray(Charsets.UTF_8),
        ).toString()
    }
}
