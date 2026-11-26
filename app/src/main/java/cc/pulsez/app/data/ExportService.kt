package cc.pulsez.app.data

import cc.pulsez.app.AppContainer
import cc.pulsez.app.audio.VoiceRecorderFiles
import cc.pulsez.app.core.ChatEngine
import cc.pulsez.app.data.attachments.AttachmentErrorCodes
import cc.pulsez.app.data.attachments.AttachmentException
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.ImportBatchGroupEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.ImportRepository
import cc.pulsez.app.importer.BackupParser
import cc.pulsez.app.importer.deepseek.ImportCache
import cc.pulsez.app.media.ImagePipeline
import cc.pulsez.app.media.ThumbnailCache
import java.io.File
import java.io.OutputStream
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** 一次导出 payload 的完整结果与规模计数（internal，不是对外契约）。*/
internal data class ExportPayload(
    val json: String,
    val byteSize: Int,
    val characterCount: Int,
    val threadCount: Int,
    val messageCount: Int,
    val skippedOrphanThreads: Int,
)

/** 导出超限原因（顺序即报出优先级；文案见 `ui/copy/BackupCopy.exportOverLimit`）。 */
internal enum class ExportOverLimit { BYTES, MESSAGES, THREADS, CHARACTERS }

/** 导出结果：成功（可能带超限/孤儿跳过回执）/ 写失败 / 数据读取失败。 */
internal sealed interface ExportResult {
    data class Success(
        val overLimit: List<ExportOverLimit>,
        /** 本次因「角色已软删/不存在」整段跳过的会话数；0 表示没有跳过。*/
        val skippedOrphanThreads: Int = 0,
    ) : ExportResult
    data object WriteFailed : ExportResult
    data object DataFailed : ExportResult
}

/**
 * 导出备份（不含图片本体、用量与费用、服务商地址与模型）。
 *
 * 只导文本与元数据：不含向量（可由原文重建）、不含密钥、不含紧急联系人。
 * 带 schema 版本号，给以后的导入留兼容位。
 *
 * v0.2 起导出 schema=2：`thread.kind/title`、`thread_member`、
 * `message.speakerCharacterId`、`memory_item.characterId` 一并带上，否则多角色/群聊数据
 * 导出去就丢了归属。仍然**不导密钥**（密钥在 SecretStore，不在本服务读取范围）。
 *
 * v0.5.0 起导出 **schema=3**：
 * 顶层新增 `attachments` 数组，**只导元数据**——
 * `messageId / kind / mime / fileName / sizeBytes / width / height / createdAt`；
 * **绝不导** `localPath`（本机私有目录路径，换机/泄漏无意义）与 `remoteFileId`
 * （服务商文件句柄，属于账号内隐私，不能带出）。
 * `messageId` 让恢复方知道"哪条消息当时带过图"（` 决策）；
 * 只导**已绑定消息**的附件（草稿/孤儿不导出）。
 * 图片/文件**本体不进 JSON**：导出后新设备拿不到原图，只能看到「这条消息当时有图」的元数据。
 * 契约变更由文档代理同步 `。
 *
 * v0.5.2（前半）：
 * - 补齐恢复所需字段（message.segmentId/tokens、segment 时间跨度与换段原因、memory 可信度/重要度/
 *   发生与到期/首末见时间、character.styleSamples），schema 仍 3，既有字段名/含义/空值表示零变化；
 * - **角色已删除的整段会话不导出**（外键孤儿，只在配置回执里计数 `skippedOrphanThreads`；
 *   数据仍保留在本机库，不删用户数据）；
 * - 导出失败/超限有确定性回执（[ExportResult]），不再静默；
 * - 明确「可导出、暂不可恢复」：**本版恢复不支持**——头像；图片/附件本体（元数据会导出但不恢复）；
 *   向量（只重建关键词，"按意思找旧事"需用户重新补建）；已删除的消息/角色；会话关系状态
 *   （`thread_state` 不导出）；用量与费用记录（本就不导出）；服务商地址与模型（不导出，要重新填）。
 *   恢复时按这份清单给用户逐项说明。
 *
 * v0.6 多窗口导入：顶层可选 `importBatches` 保存批次、分组、窗口和来源归属；该数据只含状态/来源元数据，
 * 不复制聊天正文。依据：`
 * `data/db/PulsezDatabase.kt:140-205`、`data/repo/ImportRepository.kt:414-586,642-695`。
 */
object ExportService {

    /** 当前导出 schema 版本（v0.5.0 起 = 3）。*/
    const val SCHEMA: Int = 3

    suspend fun buildJson(container: AppContainer): String = buildPayload(container.database).json

    /**
     * 纯数据层入口：只依赖 Room，不构造 `AppContainer`（`
     * 导出/迁移这类可自动测的逻辑别被 AppContainer 卡住）。
     *
     * 生产入口是 [buildJson] 的 `container` 重载，两者输出完全一致。
     */
    suspend fun buildJson(db: PulsezDatabase): String = buildPayload(db).json

    /**
     * 组装一份完整导出（v0.5.2 前半）：
     * 补齐恢复所需字段、过滤「角色已软删/不存在」的外键孤儿会话、统计规模与真实 UTF-8 字节数。
     *
     * 字段约定（写死，只增不改；schema 仍 3）：
     * - 既有可空 string 仍写 `""`（greeting/userName/title/summary/key/speakerCharacterId/
     *   memory.characterId/fileName），`thread.characterId` 单聊=角色 id、群聊=JSON `null`；
     * - `memory.validTo` 的 `null → 0` 哨兵不变；
     * - 新增可空字段（segment.batonReason、memory.happenedAt/dueAt）缺省写 JSON `null`，
     *   **不写 `""`/`0`**；`character.styleSamples` 为 null 时写 `null`；
     * - 顶层新增 `skippedOrphanThreads`（≥0）。
     */
    internal suspend fun buildPayload(db: PulsezDatabase): ExportPayload = db.withTransaction {
        buildPayloadFromSnapshot(db)
    }

    /**
     * All rows are read inside one Room transaction so a committed import group cannot be half-exported.
     * 依据：`ImportRepository.kt:414-586` commits messages, source rows, and group/window states together;
     * Room queues additional transactions while `withTransaction` runs
     * (`https://developer.android.com/reference/kotlin/androidx/room/RoomDatabase?hl=en`).
     */
    private suspend fun buildPayloadFromSnapshot(db: PulsezDatabase): ExportPayload {
        val characters = db.characterDao().all()
        val characterIds = characters.mapTo(mutableSetOf()) { it.id }
        val characterArray = buildJsonArray {
            characters.forEach { character ->
                add(
                    buildJsonObject {
                        put("id", character.id)
                        put("name", character.name)
                        put("persona", character.persona)
                        put("greeting", character.greeting ?: "")
                        put("userName", character.userName ?: "")
                        // v0.5.2：语气样本必须带上，否则恢复后 TA 说话的样子丢了。
                        // 实体是 `\u0001` 分隔串（Repositories.kt:49-51）；null 仍写 JSON null，不写 ""。
                        put("styleSamples", character.styleSamples)
                        put("sourceType", character.sourceType)
                        put("createdAt", character.createdAt)
                        put("updatedAt", character.updatedAt)
                    },
                )
            }
        }

        var skippedOrphanThreads = 0
        var threadCount = 0
        var messageCount = 0
        val exportedThreadIds = HashSet<String>()
        val exportedThreadCharacterIds = HashMap<String, String?>()
        val exportedMessageCountBySegment = HashMap<String, Int>()
        val exportedLastMessageAtBySegment = HashMap<String, Long>()
        // 只导出「消息确实进本次导出」的附件元数据：被跳过的孤儿会话，其附件也不得带出来。
        val exportedMessageIds = HashSet<String>()

        val threadArray = buildJsonArray {
            val threads = db.threadDao().active()
            threads.forEach { thread ->
                // v0.5.2：外键孤儿会话——单聊指向的角色已软删/不存在 → 整条跳过
                // （其 messages/segments/memories/members 都不输出），并把跳过数带给 UI 回执。
                // 群聊 characterId 为 null（Repositories.kt:138），保留。
                val ownerId = thread.characterId
                if (ownerId != null && ownerId !in characterIds) {
                    skippedOrphanThreads += 1
                    return@forEach
                }
                threadCount += 1
                exportedThreadIds += thread.id
                exportedThreadCharacterIds[thread.id] = ownerId
                val messages = db.messageDao().allForThread(thread.id)
                messageCount += messages.size
                messages.forEach { exportedMessageIds += it.id }
                messages.groupBy { it.segmentId }.forEach { (segmentId, segmentMessages) ->
                    exportedMessageCountBySegment[segmentId] = segmentMessages.size
                    exportedLastMessageAtBySegment[segmentId] = segmentMessages.maxOf { it.createdAt }
                }
                val messageArray = buildJsonArray {
                    messages.forEach { message ->
                        add(
                            buildJsonObject {
                                put("id", message.id)
                                put("threadId", message.threadId)
                                put("role", message.role)
                                put("content", message.content)
                                put("createdAt", message.createdAt)
                                // v2：群聊里这条是谁说的；用户消息/单聊为空串
                                put("speakerCharacterId", message.speakerCharacterId ?: "")
                                // v0.5.2：段归属与用量，恢复时要还原到对应段。
                                put("segmentId", message.segmentId)
                                put("tokens", message.tokens)
                                message.citationsJson?.let { put("citationsJson", it) }
                                message.latestReminder?.let { put("latestReminder", it) }
                            },
                        )
                    }
                }
                val segments = db.segmentDao().forThread(thread.id)
                val segmentArray = buildJsonArray {
                    segments.forEach { segment ->
                        add(
                            buildJsonObject {
                                put("id", segment.id)
                                put("ordinal", segment.ordinal)
                                put("summary", segment.summary ?: "")
                                put("state", segment.state)
                                // v0.5.2：段的时间跨度与换段原因，恢复后段头信息不丢。
                                put("firstMessageAt", segment.firstMessageAt)
                                put("lastMessageAt", segment.lastMessageAt)
                                put("tokenSum", segment.tokenSum)
                                put("batonReason", segment.batonReason)
                            },
                        )
                    }
                }
                val memoryRows = db.memoryDao().allForThread(thread.id)
                val memoryArray = buildJsonArray {
                    memoryRows.forEach { memory ->
                        add(
                            buildJsonObject {
                                put("kind", memory.kind)
                                put("key", memory.key ?: "")
                                put("value", memory.value)
                                put("status", memory.status)
                                put("validFrom", memory.validFrom)
                                put("validTo", memory.validTo ?: 0L)
                                // v2：群聊里该角色自己的记忆；单聊为空串（按 thread 维度）
                                put("characterId", memory.characterId ?: "")
                                // v0.5.2：可信度/重要度/发生与到期/首末见时间。
                                // confidence 实体是 Float，直接 put(Number)；JSON 数字不区分 float/double，
                                // 按 Double 读。
                                put("confidence", memory.confidence)
                                put("importance", memory.importance)
                                put("happenedAt", memory.happenedAt)
                                put("dueAt", memory.dueAt)
                                put("firstSeenAt", memory.firstSeenAt)
                                put("lastSeenAt", memory.lastSeenAt)
                            },
                        )
                    }
                }

                // v2：群成员（ordinal 即发言顺序）
                val memberArray = buildJsonArray {
                    db.threadMemberDao().membersOf(thread.id).forEach { member ->
                        add(
                            buildJsonObject {
                                put("characterId", member.characterId)
                                put("ordinal", member.ordinal)
                                put("joinedAt", member.joinedAt)
                            },
                        )
                    }
                }

                add(
                    buildJsonObject {
                        put("id", thread.id)
                        put("characterId", thread.characterId)
                        // v2：SINGLE / GROUP；旧数据导出时也是 SINGLE
                        put("kind", thread.kind)
                        put("title", thread.title ?: "")
                        put("createdAt", thread.createdAt)
                        put("lastMessageAt", thread.lastMessageAt)
                        put("lastSingleChatActivityAt", thread.lastSingleChatActivityAt)
                        put("messages", messageArray)
                        put("segments", segmentArray)
                        put("memories", memoryArray)
                        put("members", memberArray)
                    },
                )
            }
        }

        // v3：附件元数据。只带白名单字段；localPath/remoteFileId 永不出现。
        // v0.5.0 识图：补 `messageId` 建立"这条消息当时带过哪张图"的
        // 关联；并且**只导已绑定到消息的附件**——草稿/孤儿附件不属于任何消息，导出后也无从还原，
        // 只会让使用者拿到一堆对不上号的行。仍然不导图片本体/base64。
        // v0.5.2：再加一层——`messageId` 必须属于**本次真的导出了**的消息（孤儿会话被跳过时，
        // 它的附件元数据也不能出现，否则恢复侧会拿到指向不存在消息的行）。
        val attachmentArray = buildJsonArray {
            db.attachmentDao().allLive()
                .filter { attachment ->
                    val messageId = attachment.messageId
                    !messageId.isNullOrBlank() && messageId in exportedMessageIds
                }
                .forEach { attachment ->
                    add(
                        buildJsonObject {
                            put("messageId", attachment.messageId.orEmpty())
                            put("kind", attachment.kind)
                            put("mime", attachment.mime)
                            put("fileName", attachment.fileName ?: "")
                            put("sizeBytes", attachment.sizeBytes)
                            put("width", attachment.width ?: 0)
                            put("height", attachment.height ?: 0)
                            put("createdAt", attachment.createdAt)
                        },
                    )
                }
        }

        // Import metadata contains source IDs and assignment/status fields only; never copy chat bodies.
        // Deleted/missing owners follow the existing backup rule and are excluded with their provenance.
        val importBatchItems = ArrayList<JsonObject>()
        val batchDao = db.importBatchDao()
        val importRepository = ImportRepository(db)
        for (batch in batchDao.allBatches()) {
            val groups = batchDao.groups(batch.id).filter { group ->
                val targetCharacterExists = group.targetCharacterId == null || group.targetCharacterId in characterIds
                val targetThreadExists = group.targetThreadId == null ||
                    (group.targetThreadId in exportedThreadIds &&
                        exportedThreadCharacterIds[group.targetThreadId] == group.targetCharacterId)
                val successfulTargetExists = group.status != ImportRepository.GROUP_SUCCESS ||
                    (group.targetCharacterId != null && group.targetThreadId != null)
                targetCharacterExists && targetThreadExists && successfulTargetExists
            }
            val groupIds = groups.mapTo(HashSet()) { it.id }
            val groupThreads = groups.asSequence()
                .filter { it.status == ImportRepository.GROUP_SUCCESS }
                .mapNotNull { it.targetThreadId }
                .toHashSet()
            val originalWindows = batchDao.windows(batch.id).filter { it.groupId == null || it.groupId in groupIds }
            val sources = batchDao.sourcesForBatch(batch.id).filter { source ->
                source.targetCharacterId in characterIds &&
                    source.targetThreadId in exportedThreadIds &&
                    exportedThreadCharacterIds[source.targetThreadId] == source.targetCharacterId &&
                    source.targetThreadId in groupThreads &&
                    (exportedMessageCountBySegment[source.segmentId] ?: 0) > 0
            }
            // allForThread() omits soft-deleted messages. If an imported segment has no live message left,
            // its segment cannot be reconstructed from the backup, so do not retain a dangling undo owner.
            val sourceSessionIds = sources.mapTo(HashSet()) { it.sourceSessionId }
            val windows = originalWindows.map { window ->
                if (window.status == ImportRepository.WINDOW_IMPORTED && window.sourceSessionId !in sourceSessionIds) {
                    window.copy(status = ImportRepository.WINDOW_UNDONE, errorCode = null)
                } else {
                    window
                }
            }
            val windowsBySession = windows.associateBy { it.sourceSessionId }
            val groupImportedStats = groups.associate { group ->
                if (group.status != ImportRepository.GROUP_SUCCESS) {
                    group.id to (group.importMessageCount to group.importLastMessageAt)
                } else {
                    val groupSources = sources.filter { source ->
                        windowsBySession[source.sourceSessionId]?.groupId == group.id
                    }
                    group.id to (
                        groupSources.sumOf { source -> exportedMessageCountBySegment[source.segmentId] ?: 0 } to
                            groupSources.mapNotNull { source -> exportedLastMessageAtBySegment[source.segmentId] }.maxOrNull()
                        )
                }
            }
            val groupSnapshots = ArrayList<Pair<ImportBatchGroupEntity, Boolean>>(groups.size)
            for (group in groups) {
                val memoryUndoSafe = if (group.status != ImportRepository.GROUP_SUCCESS) {
                    true
                } else {
                    val threadId = group.targetThreadId
                    val expected = group.memoryFingerprint
                    if (threadId == null || expected == null) {
                        false
                    } else {
                        try {
                            importRepository.memoryFingerprintForThread(threadId) == expected
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
                groupSnapshots += group to memoryUndoSafe
            }
            importBatchItems += buildJsonObject {
                put("id", batch.id)
                put("fingerprint", batch.fingerprint)
                put("zipName", batch.zipName)
                put("createdAt", batch.createdAt)
                // A backup has no live worker. CANCELLED is the existing resumable state and is
                // also allowed by undoBatch; persisting RUNNING would falsely claim active work.
                put(
                    "status",
                    if (batch.status == ImportRepository.BATCH_RUNNING) {
                        ImportRepository.BATCH_CANCELLED
                    } else {
                        batch.status
                    },
                )
                put("postProcessStatus", batch.postProcessStatus)
                put(
                    "groups",
                    buildJsonArray {
                        groupSnapshots.forEach { (group, memoryUndoSafe) ->
                            add(
                                buildJsonObject {
                                    put("id", group.id)
                                    put("targetCharacterId", group.targetCharacterId)
                                    put("targetThreadId", group.targetThreadId)
                                    put("isNewCharacter", group.isNewCharacter)
                                    put("name", group.name)
                                    put("persona", group.persona)
                                    put("status", group.status)
                                    put("memoryUndoSafe", memoryUndoSafe)
                                    put("beforeLastMessageAt", group.beforeLastMessageAt)
                                    put("beforeLastExtractAt", group.beforeLastExtractAt)
                                    put("beforeCharacterUpdatedAt", group.beforeCharacterUpdatedAt)
                                    put("importLastMessageAt", groupImportedStats[group.id]?.second)
                                    put("importMessageCount", groupImportedStats[group.id]?.first ?: 0)
                                    put("errorCode", group.errorCode)
                                },
                            )
                        }
                    },
                )
                put(
                    "windows",
                    buildJsonArray {
                        windows.forEach { window ->
                            add(
                                buildJsonObject {
                                    put("sourceSessionId", window.sourceSessionId)
                                    put("entryName", window.entryName)
                                    put("title", window.title)
                                    put("sourceStartedAt", window.sourceStartedAt)
                                    put("messageCount", window.messageCount)
                                    put("damageCode", window.damageCode)
                                    put("groupId", window.groupId)
                                    put("status", window.status)
                                    put("errorCode", window.errorCode)
                                },
                            )
                        }
                    },
                )
                put(
                    "sources",
                    buildJsonArray {
                        sources.forEach { source ->
                            add(
                                buildJsonObject {
                                    put("id", source.id)
                                    put("targetCharacterId", source.targetCharacterId)
                                    put("targetThreadId", source.targetThreadId)
                                    put("sourceSessionId", source.sourceSessionId)
                                    put("title", source.title)
                                    put("sourceStartedAt", source.sourceStartedAt)
                                    put("messageCount", source.messageCount)
                                    put("segmentId", source.segmentId)
                                    put("indexStatus", source.indexStatus)
                                },
                            )
                        }
                    },
                )
            }
        }
        val importBatchArray = buildJsonArray { importBatchItems.forEach { add(it) } }

        val json = buildJsonObject {
            put("schema", SCHEMA)
            put("app", "pulsez")
            put("exportedAt", System.currentTimeMillis())
            put("character", characterArray)
            put("thread", threadArray)
            put("attachments", attachmentArray)
            put("importBatches", importBatchArray)
            // v0.5.2：因角色已删除而整段跳过、没有进备份的会话数（0 = 没有跳过）。
            put("skippedOrphanThreads", skippedOrphanThreads)
        }.toString()

        return ExportPayload(
            json = json,
            byteSize = json.toByteArray(Charsets.UTF_8).size,
            characterCount = characters.size,
            threadCount = threadCount,
            messageCount = messageCount,
            skippedOrphanThreads = skippedOrphanThreads,
        )
    }

    /**
     * 超限判据（**全部用 UTF-8 字节数 / 条数，不用 `String.length`**）。
     *
     * 顺序即优先级：BYTES → MESSAGES → THREADS → CHARACTERS；超限**仍完整导出、不截断用户数据**。
     * 常量唯一定义在 [BackupParser]，这里不写第二份数字。
     */
    internal fun overLimitReasons(payload: ExportPayload): List<ExportOverLimit> = buildList {
        if (payload.byteSize.toLong() > BackupParser.FILE_MAX_BYTES) add(ExportOverLimit.BYTES)
        if (payload.messageCount > BackupParser.MAX_MESSAGES) add(ExportOverLimit.MESSAGES)
        if (payload.threadCount > BackupParser.MAX_THREADS) add(ExportOverLimit.THREADS)
        if (payload.characterCount > BackupParser.MAX_CHARACTERS) add(ExportOverLimit.CHARACTERS)
    }

    /**
     * 把 JSON 写到 SAF 目标流。
     *
     * **固定顺序：先 `jsonProvider()` 生成完整 JSON，成功后才 `open()`**——否则数据读取失败
     * 会在 SAF 已创建的文档上留下 0 字节/半截内容（`CreateDocument` 在回调前就已建好文档）。
     * `open()`（云盘 provider 是阻塞 IPC）与 `write` **都在 `withContext(ioDispatcher)` 内**；
     * 失败语义：jsonProvider 抛错 → [ExportResult.DataFailed]；open 返回 null/抛错、write 抛错
     * → [ExportResult.WriteFailed]；成功 → `Success(emptyList(), 0)`。
     */
    internal suspend fun writeJsonTo(
        open: () -> OutputStream?,
        jsonProvider: suspend () -> String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ExportResult {
        // 先在调用者上下文生成完整 JSON（单测可在测试线程；生产只在 exportWith 的 IO 内被调）。
        val json = try {
            jsonProvider()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return ExportResult.DataFailed
        }
        return withContext(ioDispatcher) {
            val stream = try {
                open()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                null
            } ?: return@withContext ExportResult.WriteFailed
            try {
                stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ExportResult.Success(emptyList(), 0)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                ExportResult.WriteFailed
            }
        }
    }

    /**
     * 生产导出等价物：整体在 `ioDispatcher`（默认 [Dispatchers.IO]）内
     * 构建 payload（失败 → [ExportResult.DataFailed]，此时**不 open**）、算超限、再写流；
     * 成功时把 `overLimit` 与 `payload.skippedOrphanThreads` 一起上抛（跳过数不得丢）。
     */
    internal suspend fun exportWith(
        payloadProvider: suspend () -> ExportPayload,
        open: () -> OutputStream?,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ExportResult = withContext(ioDispatcher) {
        val payload = try {
            payloadProvider()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return@withContext ExportResult.DataFailed
        }
        val reasons = overLimitReasons(payload)
        when (val written = writeJsonTo(open, { payload.json }, ioDispatcher)) {
            is ExportResult.Success -> ExportResult.Success(reasons, payload.skippedOrphanThreads)
            else -> written
        }
    }

    /**
     * 生产导出入口：`AppRoot` 的导出 launcher 只调它（payload 构建/写出/线程都在这层）。
     *
     * 可见性必须是 `internal`（第 6 轮阻断-2）：`ExportResult` 是 internal，public 函数暴露
     * internal 返回类型在 Kotlin 2.1.0 编译不过；**禁止把 `ExportResult` 提 public 来绕**。
     */
    internal suspend fun exportTo(db: PulsezDatabase, open: () -> OutputStream?): ExportResult =
        exportWith({ buildPayload(db) }, open)

    /** 生产重载：`AppContainer` 版本（`AppRoot` 用这个）。 */
    internal suspend fun exportTo(container: AppContainer, open: () -> OutputStream?): ExportResult =
        exportWith({ buildPayload(container.database) }, open)

    /**
     * 清空全部数据：设置、导入偏好、群聊账本、库、密钥、用量、附件目录、三个缓存目录、
     * 同进程缩略图内存缓存。用户明确要求（确认框）时才调用。
     *
     * v0.5.0 修复：旧实现只清 Room 与密钥，
     * `filesDir/attachments` 里用户拍的照片会残留 —— 与「清空所有数据」的隐私承诺不符。
     *
     * v0.5.2 重写（` B / §4.3.5）：旧实现还漏了
     * **4 个 DataStore**（设置 / 导入偏好 / 群聊账本 / 用量里的设置类字段）和
     * **3 个缓存目录**（`cacheDir/voice`、`cacheDir/camera`、`cacheDir/import`），
     * 确认框的「会删」清单与实现不一致；且密钥清空成功与否没有校验。本版按固定顺序重写：
     *
     * 1. 【fail-fast】先取进程级 [AttachmentStore]（`PulsezApp.onCreate` 安装）；没装则**先抛**
     *    `STORE_NOT_INSTALLED`，避免「库清了、文件没清」的半清空状态；
     * 2. `database.clearAllTables()`（含附件表；物理清掉，v0.5 无回收站语义）；
     * 3. `settings.wipeUserData()`（单次 edit，保留协议同意记录）——**必须先于密钥删除**：
     *    标志先变不可用，即使后面密文删失败也不会出现「密钥没了但入口仍显示可用」；
     * 4. `importPrefs.clearAll()` + `groupLedger.clearAll()`；
     * 5. `secrets.clear()`：`SecretStore.clear()` 检查密文文件删除与 Keystore alias 残留；清理
     *    或随后的回读 ([secretStillPresent]) 出错 / 仍有密钥 → 映射为 `STORE_IO`；
     * 6. `usage.clear()`；
     * 7. `attachments.clearAll()`（删 `filesDir/attachments` 下全部文件）；
     * 8. 整目录删除 `cacheDir/voice`、`cacheDir/camera`、`cacheDir/import`——**只经
     *    [deleteTree]**（唯一实现、唯一删除路径），失败判据与 `AttachmentStore` 同口径：
     *    删完还 `exists()` 就 `listFiles()`（`null` = IO 失败），还有子项 → `STORE_IO`；
     * 9. `ThumbnailCache.evictAll()`：清同进程缩略图内存缓存，否则「清空」后已解码的
     *    缩略图位图仍驻留在内存里（磁盘文件已删，`load` 下次会失败，但内存图不会自己消失）。
     *
     * 除 ⑤ 的回读校验外，任一步异常都**向上抛**（不静默）；UI 层据此显示「没清干净」+「再试一次」。
     *
     * 线程：整个清空动作切到 `Dispatchers.IO`：`RoomDatabase.clearAllTables()` 会
     * `assertNotMainThread()`（Room 2.6.1 `PulsezDatabase_Impl.clearAllTables` 第一行），
     * 旧实现从 UI 主线程协程调用会抛 `IllegalStateException`（v0.5.0 已修，本版保留约束）。
     *
     * @param delete 目录删除动作（测试注入缝；生产默认 `File.deleteRecursively()`）。
     *   **唯一删除缝**：B ⑧ 的三个目录与 [deleteTree] 的判据全部走它。
     * @param secretStillPresent 清完密钥后的回读校验（默认读真实 `SecretStore`；测试可注入
     *   「仍在」或读异常以验证失败可见）。只用于密钥回读，不参与文件删除。
     * @param clearSecrets 密钥清理动作（默认调用真实 `SecretStore.clear()`；测试可注入假清理器，
     *   避免依赖 AndroidKeyStore）。
     */
    suspend fun clearAll(
        container: AppContainer,
        delete: (File) -> Boolean = { it.deleteRecursively() },
        secretStillPresent: (String) -> Boolean = { container.secrets.has(it) },
        clearSecrets: () -> Unit = { container.secrets.clear() },
    ) {
        withContext(Dispatchers.IO) {
            // ① 先 fail-fast，再动 DB（避免清了一半才发现附件目录没准备好）。
            val attachments = AttachmentStore.required()
            // ② Room 清 entity 表；SQLite AFTER DELETE trigger 把远端 file ID + 本机凭据绑定 ID
            // 原子转存到独立 raw queue。Room 的 clearAllTables 只清注册实体：
            // https://developer.android.com/reference/androidx/room/RoomDatabase#clearAllTables()
            container.database.clearAllTables()
            // ③ 设置：单次 edit，保留协议同意记录；先失效密钥标志，再删密文（B ③→⑤）。
            container.settings.wipeUserData()
            // ④ 导入偏好（续导线索/整理进度/「已整理」标记）+ 群聊账本。
            container.importPrefs.clearAll()
            container.groupLedger.clearAll()
            // ⑤ API 密文：删除与回读任一 IO 失败都按附件存储错误上抛，不能把未知状态当成「不存在」。
            // 独立的远端文件 HMAC 绑定 alias 不属于 SecretStore，必须留存以便重新填入同一端点/凭据后重试；
            // Keystore alias 丢失时 binding 退为未知，不生成替代 key 来匹配旧记录。
            // 依据：util/SecretStore.kt 的 clear() 核验文件/alias；AttachmentStore.kt:40-44 支持保留 cause。
            try {
                clearSecrets()
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                throw AttachmentException(AttachmentErrorCodes.STORE_IO, "密钥没清干净", error)
            }
            val chatLeft: Boolean
            val embedLeft: Boolean
            try {
                chatLeft = secretStillPresent(ChatEngine.CHAT_KEY)
                embedLeft = secretStillPresent(ChatEngine.EMBED_KEY)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                throw AttachmentException(AttachmentErrorCodes.STORE_IO, "密钥回读失败", error)
            }
            if (chatLeft || embedLeft) {
                throw AttachmentException(AttachmentErrorCodes.STORE_IO, "密钥没清干净")
            }
            // ⑥ 用量与花费记录。
            container.usage.clear()
            // ⑦ 本地附件目录。
            attachments.clearAll()
            // ⑧ 三个缓存目录（目录常量复用既有定义；不新增中间层函数）。
            val cacheDir = container.cacheDir
            deleteTree(VoiceRecorderFiles.directory(cacheDir), delete)
            deleteTree(File(cacheDir, ImagePipeline.TEMP_DIR_NAME), delete)
            deleteTree(ImportCache.importDir(cacheDir), delete)
            // ⑨ 同进程内存缓存：磁盘文件已删，但已解码的位图还在这张表里。
            ThumbnailCache.evictAll()
        }
    }

    /**
     * 删除一个缓存目录（v0.5.2，` B ⑧ / §4.3.5）。
     *
     * 这是清空流程里**唯一**的目录删除实现与判据；三个缓存目录都经它删除，测试也直接测它。
     *
     * 失败判据与 `AttachmentStore.clearAll()`（`data/attachments/AttachmentStore.kt:224-238`）
     * 同口径：
     * - 目录本来就不存在 / 已空 → **成功**（幂等；重试与「清两次」不能报错）；
     * - `delete(dir)` 后目录仍 `exists()` → 看 `listFiles()`：
     *   - `null` = **读不了目录**（IO 失败）→ 抛 `STORE_IO`，**不能当空目录**；
     *   - 非空 = **没删干净** → 抛 `STORE_IO`；
     *   - 空数组 = 目录已空（只是目录本身没删掉）→ 成功。
     *
     * @param dir 要删的目录
     * @param delete 删除动作（生产 = `deleteRecursively()`；测试注入 fake 以覆盖失败分支）
     * @throws AttachmentException `STORE_IO`（读不了 / 没删干净）
     */
    internal fun deleteTree(dir: File, delete: (File) -> Boolean = { it.deleteRecursively() }) {
        if (!dir.exists()) return
        delete(dir)
        if (!dir.exists()) return
        val children = dir.listFiles()
            ?: throw AttachmentException(AttachmentErrorCodes.STORE_IO, "读不了缓存目录")
        if (children.isNotEmpty()) {
            throw AttachmentException(AttachmentErrorCodes.STORE_IO, "缓存目录没删干净")
        }
    }
}
