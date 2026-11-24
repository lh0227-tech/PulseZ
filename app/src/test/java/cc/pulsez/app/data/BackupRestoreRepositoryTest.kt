package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.core.EmbedPort
import cc.pulsez.app.core.ImportIndexer
import cc.pulsez.app.core.ImportMessage
import cc.pulsez.app.data.db.AttachmentEntity
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.db.CharacterEntity
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
import cc.pulsez.app.data.db.ThreadMemberEntity
import cc.pulsez.app.data.db.ThreadStateEntity
import cc.pulsez.app.data.repo.BackupRestoreRepository
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ImportedSegment
import cc.pulsez.app.data.repo.ImportBatchException
import cc.pulsez.app.data.repo.ImportRepository
import cc.pulsez.app.data.repo.MemoryRepository
import cc.pulsez.app.data.repo.RestoreResult
import cc.pulsez.app.data.repo.RestoreStage
import cc.pulsez.app.importer.BackupDocument
import cc.pulsez.app.importer.BackupInspection
import cc.pulsez.app.importer.BackupParser
import cc.pulsez.app.importer.BackupThread
import cc.pulsez.app.importer.Source
import cc.pulsez.app.mem.BigramIndex
import cc.pulsez.app.mem.TokenEstimator
import cc.pulsez.app.ui.copy.BackupCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 备份恢复（ 02 后半）的 Robolectric + 内存 Room 主测试。
 *
 * 夹具纪律：
 * - **源库（A）手动种子、走既有实体字段**（不手写 JSON），形状按真实 App 行为：普通聊天消息
 * 写在 `thread.activeSegmentId` 指向的 OPEN 段里（`Repositories.kt:226-260`）；
 * - 然后用**真实** `ExportService.buildJson(A)` 产出 JSON → `inspect` → `parseBackup` → 恢复到目标库 B；
 * - 断言直接落在 SQL/DAO 上（不只看 Kotlin 对象）。
 *
 * 覆盖 A–L：单事务/回滚、ID 全量重生成与映射、段不变量、空串→null、外键/无外键归属、
 * `lastExtractAt`、关键词索引零网络、重复恢复、二次往返、取消传播、空会话、段头钳制。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreRepositoryTest {

    private lateinit var sourceDb: PulsezDatabase
    private lateinit var targetDb: PulsezDatabase

    /** 固定时间基准（2023 年，明显早于 `System.currentTimeMillis`；不吃「恢复时刻」）。 */
    private val base = 1_690_000_000_000L

    private val roleUser = "user"
    private val roleAssistant = "assistant"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sourceDb = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        targetDb = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
    }

    // ================================================================== 种子（源库 A）

    private suspend fun seedCharacter(
        id: String,
        name: String,
        persona: String = "人设-$id",
        greeting: String? = null,
        styleSamples: String? = null,
        userName: String? = null,
    ) {
        sourceDb.characterDao().upsert(
            CharacterEntity(
                id = id,
                name = name,
                avatarUri = null,
                persona = persona,
                greeting = greeting,
                styleSamples = styleSamples,
                userName = userName,
                systemPromptOverride = null,
                protocolEnabled = true,
                sourceType = "MANUAL",
                sourceRef = null,
                rawImported = null,
                emoAssetDir = null,
                createdAt = base,
                updatedAt = base + 1,
                deletedAt = null,
            ),
        )
    }

    private suspend fun seedSegment(
        id: String,
        threadId: String,
        ordinal: Int,
        state: String,
        summary: String?,
        firstMessageAt: Long,
        lastMessageAt: Long,
        tokenSum: Int,
    ) {
        sourceDb.segmentDao().upsert(
            SegmentEntity(
                id = id,
                threadId = threadId,
                ordinal = ordinal,
                summary = summary,
                styleNotes = null,
                firstMessageAt = firstMessageAt,
                lastMessageAt = lastMessageAt,
                tokenSum = tokenSum,
                state = state,
                batonReason = null,
                createdAt = firstMessageAt,
            ),
        )
    }

    private suspend fun seedThread(thread: ThreadEntity) {
        sourceDb.threadDao().upsert(thread)
    }

    private suspend fun seedMessage(
        id: String,
        threadId: String,
        segmentId: String,
        role: String,
        content: String,
        createdAt: Long,
        tokens: Int,
        speakerCharacterId: String? = null,
    ) {
        sourceDb.messageDao().upsert(
            MessageEntity(
                id = id,
                threadId = threadId,
                segmentId = segmentId,
                role = role,
                content = content,
                tokens = tokens,
                emotion = null,
                status = "OK",
                draft = null,
                usageIn = null,
                usageOut = null,
                previousContent = null,
                createdAt = createdAt,
                deletedAt = null,
                speakerCharacterId = speakerCharacterId,
            ),
        )
    }

    private suspend fun seedMemory(
        id: String,
        threadId: String,
        kind: String,
        key: String?,
        value: String,
        characterId: String?,
        validTo: Long? = null,
        confidence: Float = 0.9f,
        importance: Int = 3,
        happenedAt: Long? = null,
        dueAt: Long? = null,
    ) {
        sourceDb.memoryDao().insertAll(
            listOf(
                MemoryItemEntity(
                    id = id,
                    threadId = threadId,
                    kind = kind,
                    key = key,
                    value = value,
                    confidence = confidence,
                    importance = importance,
                    happenedAt = happenedAt,
                    dueAt = dueAt,
                    validFrom = base,
                    validTo = validTo,
                    firstSeenAt = base,
                    lastSeenAt = base + 1,
                    sourceSegmentId = "src-seg",
                    sourceMessageIds = "m1",
                    status = "ACTIVE",
                    supersededBy = null,
                    characterId = characterId,
                ),
            ),
        )
    }

    /** 主夹具：3 角色 / 单聊（2 SEALED + active OPEN，共 6 条）/ 群聊（3 条）/ 2 记忆 / 2 成员。 */
    private suspend fun seedMainFixture() {
        seedCharacter("char-a", "阿淮", persona = " 温和、话不多 ", styleSamples = "你好呀\u0001嗯")
        seedCharacter("char-b", "小北", persona = "话多")
        seedCharacter("char-c", "南枝", persona = "安静")

        // ---- 单聊：SEALED（2 条）、SEALED（2 条）、active OPEN（2 条真实聊天） ----
        seedSegment("seg-a-0", "thread-a", 0, "SEALED", "第一次见面", base + 1_000, base + 2_000, 12)
        seedSegment("seg-a-1", "thread-a", 1, "SEALED", "第二次见面", base + 3_000, base + 4_000, 12)
        // 生产形状：OPEN 段头停在「建段时刻」（早于段内消息），tokenSum 恒 0。
        seedSegment("seg-a-2", "thread-a", 2, "OPEN", null, base - 100, base - 100, 0)
        seedThread(
            ThreadEntity(
                id = "thread-a",
                characterId = "char-a",
                title = null,
                createdAt = base,
                lastMessageAt = base + 6_000,
                activeSegmentId = "seg-a-2",
                segmentOrdinal = 2,
                totalMessages = 6,
                totalTokens = 36,
                lastExtractAt = 0,
                archived = false,
                kind = ThreadKind.SINGLE,
            ),
        )
        seedMessage("m1", "thread-a", "seg-a-0", roleUser, "你好，我是阿淮", base + 1_000, 6)
        seedMessage("m2", "thread-a", "seg-a-0", roleAssistant, "嗯，记得你", base + 2_000, 6)
        seedMessage("m3", "thread-a", "seg-a-1", roleUser, "我们去看灯塔吧", base + 3_000, 6)
        seedMessage("m4", "thread-a", "seg-a-1", roleAssistant, "好，周六", base + 4_000, 6)
        seedMessage("m5", "thread-a", "seg-a-2", roleUser, "今天下雨了", base + 5_000, 6)
        seedMessage("m6", "thread-a", "seg-a-2", roleAssistant, "带伞", base + 6_000, 6)
        seedMemory("mem-a", "thread-a", "FACT", "称呼", "叫阿淮", characterId = null, confidence = 0.9f, importance = 4)

        // ---- 群聊（B+C）：active OPEN 段 3 条（2 assistant 带发言人 + 1 user） ----
        seedSegment("seg-g", "thread-g", 0, "OPEN", null, base - 100, base - 100, 0)
        seedThread(
            ThreadEntity(
                id = "thread-g",
                characterId = null,
                title = "海边小组",
                createdAt = base,
                lastMessageAt = base + 9_000,
                activeSegmentId = "seg-g",
                segmentOrdinal = 0,
                totalMessages = 3,
                totalTokens = 12,
                lastExtractAt = 0,
                archived = false,
                kind = ThreadKind.GROUP,
            ),
        )
        seedMessage("g1", "thread-g", "seg-g", roleUser, "大家周末有空吗", base + 7_000, 4)
        seedMessage("g2", "thread-g", "seg-g", roleAssistant, "有空", base + 8_000, 4, speakerCharacterId = "char-b")
        seedMessage("g3", "thread-g", "seg-g", roleAssistant, "我也在", base + 9_000, 4, speakerCharacterId = "char-c")
        sourceDb.threadMemberDao().insertAll(
            listOf(
                ThreadMemberEntity("thread-g", "char-b", 0, base + 1),
                ThreadMemberEntity("thread-g", "char-c", 1, base + 2),
            ),
        )
        seedMemory("mem-g", "thread-g", "PREF", "爱好", "都喜欢海", characterId = "char-b")

        // ---- 附件元数据（只进备份、不恢复）与 thread_state（不导出、不恢复） ----
        sourceDb.attachmentDao().upsert(
            AttachmentEntity(
                id = "att-1",
                messageId = "m1",
                kind = "IMAGE",
                mime = "image/jpeg",
                localPath = "attachments/att-1.jpg",
                fileName = "a.jpg",
                sizeBytes = 10,
                width = 1,
                height = 1,
                status = AttachmentStatus.SENT,
                errorKind = null,
                attempts = 1,
                remoteFileId = null,
                expiresAt = null,
                deletedAt = null,
                createdAt = base,
            ),
        )
        sourceDb.threadStateDao().upsert(
            ThreadStateEntity(
                threadId = "thread-a",
                relationStage = "close",
                nicknameByMe = "阿淮",
                nicknameForMe = "你",
                mood = "calm",
                updatedAt = base,
            ),
        )
    }

    private suspend fun seedImportBatchForMainFixture(memoryFingerprint: String) {
        sourceDb.importBatchDao().upsertBatch(
            ImportBatchEntity(
                id = "batch-source",
                fingerprint = "deepseek.zip:456",
                zipName = "deepseek.zip",
                createdAt = base + 20,
                status = ImportRepository.BATCH_RUNNING,
                postProcessStatus = ImportRepository.POST_PROCESS_PENDING,
            ),
        )
        sourceDb.importBatchDao().upsertGroup(
            ImportBatchGroupEntity(
                id = "group-source",
                batchId = "batch-source",
                targetCharacterId = "char-a",
                targetThreadId = "thread-a",
                isNewCharacter = false,
                name = "阿淮",
                persona = null,
                status = ImportRepository.GROUP_SUCCESS,
                memoryFingerprint = memoryFingerprint,
                beforeLastMessageAt = base + 4_000,
                beforeLastExtractAt = 0L,
                beforeCharacterUpdatedAt = base + 1,
                importLastMessageAt = base + 2_000,
                importMessageCount = 2,
                errorCode = null,
            ),
        )
        sourceDb.importBatchDao().upsertWindows(
            listOf(
                ImportBatchWindowEntity(
                    batchId = "batch-source",
                    sourceSessionId = "deepseek-session-a",
                    entryName = "chat_sessions/deepseek-session-a.json",
                    title = "灯塔",
                    sourceStartedAt = base + 1_000,
                    messageCount = 2,
                    damageCode = null,
                    groupId = "group-source",
                    status = ImportRepository.WINDOW_IMPORTED,
                    errorCode = null,
                ),
            ),
        )
        sourceDb.importBatchDao().upsertSources(
            listOf(
                ImportSourceSessionEntity(
                    id = "source-row-a",
                    batchId = "batch-source",
                    targetCharacterId = "char-a",
                    targetThreadId = "thread-a",
                    sourceSessionId = "deepseek-session-a",
                    title = "灯塔",
                    sourceStartedAt = base + 1_000,
                    messageCount = 2,
                    segmentId = "seg-a-0",
                    indexStatus = ImportRepository.INDEX_DONE,
                ),
            ),
        )
    }

    private suspend fun seedFailedImportBatchForMainFixture() {
        sourceDb.importBatchDao().upsertBatch(
            ImportBatchEntity(
                id = "batch-failed-source",
                fingerprint = "failed.zip:789",
                zipName = "failed.zip",
                createdAt = base + 30,
                status = ImportRepository.BATCH_PARTIAL,
                postProcessStatus = ImportRepository.POST_PROCESS_PENDING,
            ),
        )
        sourceDb.importBatchDao().upsertGroup(
            ImportBatchGroupEntity(
                id = "group-failed-source",
                batchId = "batch-failed-source",
                targetCharacterId = null,
                targetThreadId = null,
                isNewCharacter = true,
                name = "新角色",
                persona = "人设",
                status = ImportRepository.GROUP_FAILED,
                memoryFingerprint = null,
                beforeLastMessageAt = null,
                beforeLastExtractAt = null,
                beforeCharacterUpdatedAt = null,
                importLastMessageAt = null,
                importMessageCount = 0,
                errorCode = "IMPORT_FAILED",
            ),
        )
        sourceDb.importBatchDao().upsertWindows(
            listOf(
                ImportBatchWindowEntity(
                    batchId = "batch-failed-source",
                    sourceSessionId = "failed-session",
                    entryName = "chat_sessions/failed-session.json",
                    title = "失败窗口",
                    sourceStartedAt = base + 1_500,
                    messageCount = 1,
                    damageCode = null,
                    groupId = "group-failed-source",
                    status = ImportRepository.WINDOW_FAILED,
                    errorCode = "IMPORT_FAILED",
                ),
            ),
        )
    }

    // ================================================================== 恢复设施

    private suspend fun documentOf(db: PulsezDatabase = sourceDb): BackupDocument {
        val json = ExportService.buildJson(db)
        val inspection = BackupParser.inspect(json, Source.FILE)
        assertTrue("真实导出必须被识别为备份，实际=$inspection", inspection is BackupInspection.Preview)
        return BackupParser.parseBackup(json)
    }

    private suspend fun loadImportMessagesSuspend(ids: List<String>): List<ImportMessage> =
        ids.chunked(500).flatMap { batch ->
            targetDb.messageDao().byIds(batch).map { message ->
                ImportMessage(message.id, message.role, message.content, message.createdAt)
            }
        }

    private fun indexer(
        embed: EmbedPort = EmbedPort { null },
        embedConfigured: suspend () -> Boolean = { false },
        embedModel: suspend () -> String = { "" },
        saveChunks: (suspend (List<cc.pulsez.app.data.db.ChunkEntity>, List<ChunkTermEntity>) -> Unit)? = null,
    ): ImportIndexer = ImportIndexer(
        loadMessages = { ids -> loadImportMessagesSuspend(ids) },
        saveChunks = saveChunks ?: { chunks, terms -> MemoryRepository(targetDb).saveChunks(chunks, terms) },
        embed = embed,
        embedModel = embedModel,
        embedConfigured = embedConfigured,
    )

    private fun repository(
        indexer: ImportIndexer = indexer(),
        onStage: suspend (RestoreStage) -> Unit = {},
    ): BackupRestoreRepository = BackupRestoreRepository(
        db = targetDb,
        indexer = indexer,
        ioDispatcher = Dispatchers.Unconfined,
        onStage = onStage,
    )

    private suspend fun restoreMain(
        repository: BackupRestoreRepository = repository(),
        document: BackupDocument? = null,
    ): RestoreResult.Restored {
        val doc = document ?: documentOf()
        val result = repository.restore(doc)
        assertTrue("恢复必须成功，实际=$result", result is RestoreResult.Restored)
        return result as RestoreResult.Restored
    }

    // ================================================================== 查询小工具

    private fun rawInt(sql: String): Int =
        targetDb.openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun rawLong(sql: String): Long =
        targetDb.openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun count(table: String): Int = rawInt("SELECT COUNT(*) FROM $table")

    /** 8 张表的完整主键快照（`thread_member` 复合主键， T1-10 的表达式）。 */
    private fun snapshotPks(): Map<String, List<String>> {
        val tables = linkedMapOf(
            "character" to "id",
            "thread" to "id",
            "segment" to "id",
            "message" to "id",
            "memory_item" to "id",
            "thread_member" to "threadId || ':' || characterId",
            "chunk" to "id",
            "attachment" to "id",
        )
        return tables.mapValues { (table, pkExpr) ->
            val ids = ArrayList<String>()
            targetDb.openHelper.readableDatabase
                .query("SELECT $pkExpr AS pk FROM $table ORDER BY pk")
                .use { cursor ->
                    while (cursor.moveToNext()) ids += cursor.getString(0)
                }
            ids
        }
    }

    private fun snapshotCounts(): Map<String, Int> =
        listOf("character", "thread", "segment", "message", "memory_item", "thread_member", "chunk", "attachment")
            .associateWith { table -> count(table) }

    private suspend fun restoredThread(kind: String, newCharacterId: String? = null): ThreadEntity {
        val threads = targetDb.threadDao().active().filter { it.kind == kind }
        return if (newCharacterId != null) {
            threads.single { it.characterId == newCharacterId }
        } else {
            threads.single()
        }
    }

    private suspend fun newCharacterId(sourceId: String): String {
        val source = sourceDb.characterDao().byId(sourceId)!!
        // 恢复出来的角色按「名字 + 人设」对齐源（persona 原样、不 trim）。
        return targetDb.characterDao().all()
            .single { it.name == source.name && it.persona == source.persona }
            .id
    }

    private class ExpectedSegment(
        val summary: String?,
        val firstMessageAt: Long,
        val lastMessageAt: Long,
        val tokenSum: Int,
    )

    /** 按 C-4 的钳制公式独立重算期望段头（测试侧第二份实现，用于交叉验证）。 */
    private fun expectedSealed(document: BackupDocument, sourceThreadId: String): List<ExpectedSegment> {
        val thread = document.threads.first { it.sourceId == sourceThreadId }
        val exported = thread.segments.associateBy { it.sourceId }
        val groups = LinkedHashMap<String, MutableList<cc.pulsez.app.importer.BackupMessage>>()
        thread.messages.forEach { message ->
            groups.getOrPut(message.segmentSourceId!!) { ArrayList() } += message
        }
        val ordered = groups.entries.sortedWith(Comparator { a, b ->
            val segmentA = exported[a.key]
            val segmentB = exported[b.key]
            val knownA = segmentA != null && segmentA.ordinal != Int.MAX_VALUE
            val knownB = segmentB != null && segmentB.ordinal != Int.MAX_VALUE
            when {
                knownA && knownB -> segmentA!!.ordinal.compareTo(segmentB!!.ordinal)
                    .takeIf { it != 0 } ?: a.key.compareTo(b.key)
                knownA != knownB -> if (knownA) -1 else 1
                else -> {
                    val byTime = a.value.first().createdAt.compareTo(b.value.first().createdAt)
                    if (byTime != 0) byTime else a.key.compareTo(b.key)
                }
            }
        })
        return ordered.map { (sourceSegmentId, messages) ->
            val exportedSegment = exported[sourceSegmentId]
            val first = messages.first().createdAt
            val last = messages.last().createdAt
            val sum = messages.sumOf { it.tokens ?: TokenEstimator.estimate(it.content) }
            val exportedFirst = exportedSegment?.firstMessageAt?.takeIf { it > 0 }
            val exportedLast = exportedSegment?.lastMessageAt?.takeIf { it > 0 }
            ExpectedSegment(
                summary = exportedSegment?.summary,
                firstMessageAt = minOf(first, exportedFirst ?: first),
                lastMessageAt = maxOf(last, exportedLast ?: last),
                tokenSum = maxOf(sum, exportedSegment?.tokenSum ?: 0),
            )
        }
    }

    private suspend fun assertSegmentClamped(threadId: String, expected: List<ExpectedSegment>) {
        val sealed = targetDb.segmentDao().forThread(threadId)
            .filter { it.state == "SEALED" }
            .sortedBy { it.ordinal }
        assertEquals("SEALED 段数必须等于被引用的源段数", expected.size, sealed.size)
        expected.forEachIndexed { index, want ->
            val segment = sealed[index]
            assertEquals("段 $index summary", want.summary, segment.summary)
            assertEquals("段 $index firstMessageAt 必须按组内消息钳制", want.firstMessageAt, segment.firstMessageAt)
            assertEquals("段 $index lastMessageAt 必须按组内消息钳制", want.lastMessageAt, segment.lastMessageAt)
            assertEquals("段 $index tokenSum 必须按组内消息钳制", want.tokenSum, segment.tokenSum)
            // 恒等式：首条 ≤ 末条，且段头必须包住组内消息（lastMessageAt 不得早于组内末条）。
            assertTrue(segment.firstMessageAt <= segment.lastMessageAt)
            val messages = targetDb.messageDao().bySegment(segment.id)
            if (messages.isNotEmpty()) {
                assertTrue(
                    "段头 firstMessageAt 不得晚于组内首条（${segment.firstMessageAt} > ${messages.first().createdAt}）",
                    segment.firstMessageAt <= messages.first().createdAt,
                )
                assertTrue(
                    "段头 lastMessageAt 不得早于组内末条（${segment.lastMessageAt} < ${messages.last().createdAt}）",
                    segment.lastMessageAt >= messages.last().createdAt,
                )
            }
        }
    }

    // ================================================================== T1-1 round-trip

    @Test
    fun roundTripRestoresCharactersThreadsMessagesMemoriesMembers() = runBlocking {
        seedMainFixture()
        // H1：恢复**前后** `attachment` 表行数相等（0 新增，原始 SQL 计数）。
        val attachmentsBefore = count("attachment")
        val stateRowsBefore = count("thread_state")
        val result = restoreMain()

        assertEquals(3, result.characterCount)
        assertEquals(2, result.threadCount)
        assertEquals(9, result.messageCount)
        assertEquals(2, result.memoryCount)
        assertEquals(2, result.memberCount)
        // 4 个被引用的 SEALED + 2 个新空 OPEN。
        assertEquals(6, result.segmentCount)
        assertEquals(0, result.skippedOrphanThreads)
        // 备份里有 1 条附件元数据（本包不恢复附件行）；附件表本身 0 行。
        assertEquals(1, result.attachmentCount)

        val charA = targetDb.characterDao().all().single { it.name == "阿淮" }
        // persona 与导出值逐字相等（不 trim）；styleSamples 的 \u0001 整串原样、不拆不重拼。
        assertEquals(" 温和、话不多 ", charA.persona)
        assertEquals("你好呀\u0001嗯", charA.styleSamples)
        assertNull(charA.greeting)
        assertNull(charA.userName)
        // 导出值原样保留（sourceType 非空就不改写；空才兜底 PULSEZ_BACKUP）。
        assertEquals("MANUAL", charA.sourceType)
        assertNull(charA.avatarUri)
        assertNull(charA.deletedAt)

        val singleThread = restoredThread(ThreadKind.SINGLE, charA.id)
        assertEquals("第一次见面", targetDb.segmentDao().forThread(singleThread.id).filter { it.ordinal == 0 }.single().summary)
        val singleContents = targetDb.messageDao().allForThread(singleThread.id).map { it.content }
        assertEquals(
            listOf("你好，我是阿淮", "嗯，记得你", "我们去看灯塔吧", "好，周六", "今天下雨了", "带伞"),
            singleContents,
        )

        val groupThread = restoredThread(ThreadKind.GROUP)
        assertEquals("海边小组", groupThread.title)
        assertEquals(3, targetDb.messageDao().allForThread(groupThread.id).size)
        assertEquals(2, targetDb.threadMemberDao().membersOf(groupThread.id).size)

        // 附件表 0 行（导出只有元数据）、thread_state 0 行（不导出/不恢复）。
        assertEquals("附件表恢复前后行数必须相等（一行都不新增）", attachmentsBefore, count("attachment"))
        assertEquals(stateRowsBefore, count("thread_state"))
    }

    @Test
    fun `明确活动事件经备份往返保留 旧备份不从历史推断`() = runBlocking {
        seedMainFixture()
        val sourceActivityAt = base + 50_000L
        sourceDb.threadDao().recordSingleChatActivity("thread-a", sourceActivityAt)

        val exportedDocument = documentOf()
        assertEquals(
            sourceActivityAt,
            exportedDocument.threads.single { it.sourceId == "thread-a" }.lastSingleChatActivityAt,
        )
        restoreMain(document = exportedDocument)
        val restoredCharacterId = newCharacterId("char-a")
        val restoredSingle = restoredThread(ThreadKind.SINGLE, restoredCharacterId)
        assertEquals(sourceActivityAt, restoredSingle.lastSingleChatActivityAt)

        // A legacy backup still has old user messages and lastMessageAt, but no explicit event field.
        targetDb.clearAllTables()
        val legacyDocument = BackupParser.parseBackup(
            """{"schema":3,"app":"pulsez","character":[{"id":"legacy-c","name":"旧角色","persona":"人设","sourceType":"MANUAL","createdAt":1,"updatedAt":2}],"thread":[{"id":"legacy-t","characterId":"legacy-c","kind":"SINGLE","createdAt":1,"lastMessageAt":99999,"messages":[{"id":"legacy-m","role":"user","content":"fixture","createdAt":99999,"segmentId":"legacy-s"}],"segments":[],"memories":[],"members":[]}],"attachments":[],"skippedOrphanThreads":0}""",
        )
        restoreMain(document = legacyDocument)
        val legacyRestored = restoredThread(
            ThreadKind.SINGLE,
            targetDb.characterDao().all().single().id,
        )
        assertNull("恢复旧备份不从 lastMessageAt 或用户消息回填", legacyRestored.lastSingleChatActivityAt)
    }

    @Test
    fun `批次来源往返重映射并允许安全撤销导入分段`() = runBlocking {
        seedMainFixture()
        val sourceFingerprint = ImportRepository(sourceDb).memoryFingerprintForThread("thread-a")
        seedImportBatchForMainFixture(sourceFingerprint)

        restoreMain()

        val restoredBatch = targetDb.importBatchDao().allBatches().single()
        assertEquals(ImportRepository.BATCH_CANCELLED, restoredBatch.status)
        val group = targetDb.importBatchDao().groups(restoredBatch.id).single()
        assertEquals(ImportRepository.GROUP_SUCCESS, group.status)
        assertEquals("SUCCESS", group.status)
        assertNotEquals("char-a", group.targetCharacterId)
        assertNotEquals("thread-a", group.targetThreadId)
        val restoredWindow = targetDb.importBatchDao().windows(restoredBatch.id).single()
        assertEquals(ImportRepository.WINDOW_IMPORTED, restoredWindow.status)
        assertNotEquals("group-source", restoredWindow.groupId)
        val source = targetDb.importBatchDao().sourcesForBatch(restoredBatch.id).single()
        assertEquals("deepseek-session-a", source.sourceSessionId)
        assertEquals("灯塔", source.title)
        assertNotEquals("source-row-a", source.id)
        assertNotEquals("seg-a-0", source.segmentId)
        assertTrue(targetDb.segmentDao().forThread(source.targetThreadId).any { it.id == source.segmentId })
        val resumed = ImportRepository(targetDb).resumableBatch(restoredBatch.fingerprint)
        assertNotNull("RUNNING 备份应恢复为可续导批次", resumed)
        assertEquals(restoredBatch.id, resumed!!.id)
        assertEquals(ImportRepository.GROUP_SUCCESS, resumed.groups.single().status)
        val importer = ImportRepository(targetDb)
        val messagesBeforeResume = targetDb.messageDao().allForThread(source.targetThreadId).size
        val sourcesBeforeResume = targetDb.importBatchDao().sourcesForBatch(restoredBatch.id).size
        importer.beginBatch(resumed)
        importer.commitBatchGroup(restoredBatch.id, resumed.groups.single().id, emptyList())
        assertEquals(messagesBeforeResume, targetDb.messageDao().allForThread(source.targetThreadId).size)
        assertEquals(sourcesBeforeResume, targetDb.importBatchDao().sourcesForBatch(restoredBatch.id).size)
        importer.finishBatch(
            restoredBatch.id,
            ImportRepository.BATCH_CANCELLED,
            ImportRepository.POST_PROCESS_PENDING,
        )

        // The rebased memory guard permits exact batch undo. Earlier messages and the existing TA remain.
        importer.undoBatch(restoredBatch.id)
        assertEquals(4, targetDb.messageDao().allForThread(source.targetThreadId).size)
        assertNotNull(targetDb.characterDao().byId(source.targetCharacterId))
        assertTrue(targetDb.importBatchDao().sourcesForBatch(restoredBatch.id).isEmpty())
        assertEquals(ImportRepository.GROUP_UNDONE, targetDb.importBatchDao().groups(restoredBatch.id).single().status)
    }

    @Test
    fun `备份恢复后聊天仍按段序显示重叠时间的来源会话`() = runBlocking {
        seedMainFixture()
        val sourceTimes = mapOf(
            "m1" to base + 5_000,
            "m2" to base + 6_000,
            "m3" to base + 1_000,
            "m4" to base + 4_000,
            "m5" to base + 2_000,
            "m6" to base + 3_000,
        )
        sourceDb.messageDao().byIds(sourceTimes.keys.toList()).forEach { message ->
            sourceDb.messageDao().upsert(message.copy(createdAt = sourceTimes.getValue(message.id)))
        }
        sourceDb.segmentDao().forThread("thread-a").forEach { segment ->
            val times = sourceDb.messageDao().bySegment(segment.id).map { it.createdAt }
            sourceDb.segmentDao().upsert(
                segment.copy(firstMessageAt = times.minOrNull()!!, lastMessageAt = times.maxOrNull()!!),
            )
        }
        val sourceFingerprint = ImportRepository(sourceDb).memoryFingerprintForThread("thread-a")
        seedImportBatchForMainFixture(sourceFingerprint)
        val expectedTimes = sourceDb.segmentDao().forThread("thread-a").sortedBy { it.ordinal }
            .flatMap { segment -> sourceDb.messageDao().bySegment(segment.id).map { it.createdAt } }

        restoreMain(document = documentOf())

        val restoredBatch = targetDb.importBatchDao().allBatches().single()
        val source = targetDb.importBatchDao().sourcesForBatch(restoredBatch.id).single()
        val restoredRows = ChatRepository(targetDb).chatTailInSegmentOrder(source.targetThreadId, limit = 20)
        assertEquals(expectedTimes, restoredRows.map { it.message.createdAt })
        assertEquals(listOf(0, 0, 1, 1, 2, 2), restoredRows.map { it.segmentOrdinal })
        val provenanceBySegment = ImportRepository(targetDb).importSourcesForThread(source.targetThreadId)
            .associateBy { it.segmentId }
        assertEquals(
            listOf("deepseek-session-a", "deepseek-session-a", null, null, null, null),
            restoredRows.map { provenanceBySegment[it.message.segmentId]?.sourceSessionId },
        )
    }

    @Test
    fun `恢复时缺失的来源行会撤销窗口归属且不误减旧消息计数`() = runBlocking {
        seedMainFixture()
        val sourceFingerprint = ImportRepository(sourceDb).memoryFingerprintForThread("thread-a")
        seedImportBatchForMainFixture(sourceFingerprint)
        val exported = documentOf()
        val incomplete = exported.copy(
            importBatches = exported.importBatches.map { it.copy(sources = emptyList()) },
        )

        restoreMain(document = incomplete)

        val batch = targetDb.importBatchDao().allBatches().single()
        val group = targetDb.importBatchDao().groups(batch.id).single()
        val window = targetDb.importBatchDao().windows(batch.id).single()
        assertEquals(ImportRepository.WINDOW_UNDONE, window.status)
        assertTrue(targetDb.importBatchDao().sourcesForBatch(batch.id).isEmpty())
        assertEquals(0, group.importMessageCount)
        assertNull(group.importLastMessageAt)

        val threadId = group.targetThreadId!!
        val before = targetDb.messageDao().allForThread(threadId).size
        val totalBefore = targetDb.threadDao().byId(threadId)!!.totalMessages
        ImportRepository(targetDb).undoBatch(batch.id)
        assertEquals(before, targetDb.messageDao().allForThread(threadId).size)
        assertEquals(totalBefore, targetDb.threadDao().byId(threadId)!!.totalMessages)
    }

    @Test
    fun `备份前记忆指纹已变化时批次撤销失败关闭且不删除消息`() = runBlocking {
        seedMainFixture()
        seedImportBatchForMainFixture("stale-fingerprint")
        restoreMain()

        val restoredBatch = targetDb.importBatchDao().allBatches().single()
        val threadId = targetDb.importBatchDao().groups(restoredBatch.id).single().targetThreadId!!
        val before = targetDb.messageDao().allForThread(threadId).size
        val failure = runCatching { ImportRepository(targetDb).undoBatch(restoredBatch.id) }.exceptionOrNull()
        assertTrue(failure is ImportBatchException)
        assertEquals(ImportBatchException.MEMORY_CHANGED, (failure as ImportBatchException).code)
        assertEquals(before, targetDb.messageDao().allForThread(threadId).size)
        assertEquals(ImportRepository.GROUP_SUCCESS, targetDb.importBatchDao().groups(restoredBatch.id).single().status)
        assertEquals(1, targetDb.importBatchDao().sourcesForBatch(restoredBatch.id).size)
    }

    @Test
    fun `部分失败批次恢复失败组窗口供重试且不创建孤儿角色`() = runBlocking {
        seedMainFixture()
        seedFailedImportBatchForMainFixture()
        restoreMain()

        assertEquals(3, targetDb.characterDao().all().size)
        val batch = targetDb.importBatchDao().allBatches().single()
        assertEquals(ImportRepository.BATCH_PARTIAL, batch.status)
        val group = targetDb.importBatchDao().groups(batch.id).single()
        assertEquals(ImportRepository.GROUP_FAILED, group.status)
        assertNull(group.targetCharacterId)
        assertNull(group.targetThreadId)
        assertEquals("IMPORT_FAILED", group.errorCode)
        val window = targetDb.importBatchDao().windows(batch.id).single()
        assertEquals(ImportRepository.WINDOW_FAILED, window.status)
        assertEquals(group.id, window.groupId)
        assertTrue(targetDb.importBatchDao().sourcesForBatch(batch.id).isEmpty())

        val retry = ImportRepository(targetDb).resumableBatch(batch.fingerprint)
        assertNotNull(retry)
        assertEquals(ImportRepository.GROUP_FAILED, retry!!.groups.single().status)
        assertNull(retry.groups.single().targetCharacterId)
        assertEquals(window.sourceSessionId, retry.windows.single().preview.sourceSessionId)
    }

    // ================================================================== T1-2 ID 与归属映射

    @Test
    fun allIdsAreRegeneratedAndMapped() = runBlocking {
        seedMainFixture()
        restoreMain()

        val sourceIds = setOf(
            "char-a", "char-b", "char-c",
            "thread-a", "thread-g",
            "seg-a-0", "seg-a-1", "seg-a-2", "seg-g",
            "m1", "m2", "m3", "m4", "m5", "m6", "g1", "g2", "g3",
            "mem-a", "mem-g",
        )
        val restoredIds = buildSet {
            targetDb.characterDao().all().forEach { add(it.id) }
            targetDb.threadDao().active().forEach { add(it.id) }
            targetDb.threadDao().active().forEach { thread ->
                targetDb.segmentDao().forThread(thread.id).forEach { add(it.id) }
                targetDb.messageDao().allForThread(thread.id).forEach { add(it.id) }
                targetDb.memoryDao().allForThread(thread.id).forEach { add(it.id) }
            }
        }
        assertTrue("所有新主键必须与源 id 不相交", restoredIds.intersect(sourceIds).isEmpty())

        val newCharIds = targetDb.characterDao().all().map { it.id }.toSet()
        val newB = newCharacterId("char-b")
        val newC = newCharacterId("char-c")

        // 单聊：characterId ∈ 新角色集；消息 speaker 与会话级记忆 owner 仍为 null。
        val charA = newCharacterId("char-a")
        val single = restoredThread(ThreadKind.SINGLE, charA)
        assertTrue(single.characterId in newCharIds)
        targetDb.messageDao().allForThread(single.id).forEach { assertNull(it.speakerCharacterId) }
        assertNull(targetDb.memoryDao().allForThread(single.id).single().characterId)

        // 群聊：thread.characterId 显式 null（不是 ""、不是新角色 id）。
        val group = restoredThread(ThreadKind.GROUP)
        assertNull(group.characterId)
        assertEquals(
            1,
            rawInt("SELECT COUNT(*) FROM thread WHERE id = '${group.id}' AND characterId IS NULL"),
        )
        // assistant speaker ∈ {新 B, 新 C} 且非空；user speaker 为 NULL。
        val groupMessages = targetDb.messageDao().allForThread(group.id)
        val assistantSpeakers = groupMessages.filter { it.role == roleAssistant }.map { it.speakerCharacterId }
        assertEquals(2, assistantSpeakers.size)
        assistantSpeakers.forEach { speaker ->
            assertNotNull(speaker)
            assertNotEquals("", speaker)
            assertTrue("assistant speaker 必须是映射后的新角色 id：$speaker", speaker in setOf(newB, newC))
        }
        assertNull(groupMessages.single { it.role == roleUser }.speakerCharacterId)
        // 群记忆映射到新 B（不是源 id）。
        val groupMemory = targetDb.memoryDao().allForThread(group.id).single()
        assertEquals(newB, groupMemory.characterId)
        // message.threadId / segmentId 必须属于新会话/新段。
        targetDb.threadDao().active().forEach { thread ->
            val segmentIds = targetDb.segmentDao().forThread(thread.id).map { it.id }.toSet()
            targetDb.messageDao().allForThread(thread.id).forEach { message ->
                assertEquals(thread.id, message.threadId)
                assertTrue("消息段必须属于本会话：${message.segmentId}", message.segmentId in segmentIds)
            }
        }
        // thread_member 指向新角色。
        targetDb.threadMemberDao().membersOf(group.id).forEach { member ->
            assertTrue(member.characterId in newCharIds)
        }
    }

    // ================================================================== T1-3 段不变量 + 段头钳制

    @Test
    fun segmentInvariantsHoldForEveryRestoredThread() = runBlocking {
        seedMainFixture()
        val document = documentOf()
        restoreMain(document = document)

        targetDb.threadDao().active().forEach { thread ->
            val segments = targetDb.segmentDao().forThread(thread.id)
            val openSegments = segments.filter { it.state == "OPEN" }
            assertEquals("每个会话恰好 1 个 OPEN", 1, openSegments.size)
            val open = openSegments.single()
            assertEquals("OPEN 必须是最大 ordinal", segments.maxOf { it.ordinal }, open.ordinal)
            assertEquals(open.id, thread.activeSegmentId)
            assertEquals(open.ordinal, thread.segmentOrdinal)
            assertEquals(open, targetDb.segmentDao().latest(thread.id))
            assertNull("OPEN 段 summary 必须 null", open.summary)
            assertNull("OPEN 段 batonReason 必须 null", open.batonReason)
            assertEquals(0, open.tokenSum)

            val messages = targetDb.messageDao().allForThread(thread.id)
            assertEquals(messages.size, thread.totalMessages)
            assertEquals(messages.sumOf { it.tokens.toLong() }, thread.totalTokens)
            assertFalse(thread.archived)
            // SEALED ordinal 连续 0..n-1，OPEN 在最后。
            val sealed = segments.filter { it.state == "SEALED" }.sortedBy { it.ordinal }
            sealed.forEachIndexed { index, segment -> assertEquals(index, segment.ordinal) }
            assertEquals(sealed.size, open.ordinal)
            // 还原消息只进 SEALED（源 active OPEN 段的消息也必须落在 SEALED 里）。
            sealed.forEach { segment ->
                targetDb.messageDao().bySegment(segment.id).forEach { message ->
                    assertEquals(segment.id, message.segmentId)
                }
            }
            assertTrue(targetDb.messageDao().bySegment(open.id).isEmpty())
        }

        // 单聊逐段断言钳制公式（源 OPEN 段头的坏值必须被组内消息钳住）。
        val single = restoredThread(ThreadKind.SINGLE, newCharacterId("char-a"))
        assertSegmentClamped(single.id, expectedSealed(document, "thread-a"))
        // 群聊同理。
        val group = restoredThread(ThreadKind.GROUP)
        assertSegmentClamped(group.id, expectedSealed(document, "thread-g"))
    }

    // ================================================================== T1-4 append 落 active

    @Test
    fun restoredMessagesLiveInSealedAndAppendLandsInActive() = runBlocking {
        seedMainFixture()
        restoreMain()

        val single = restoredThread(ThreadKind.SINGLE, newCharacterId("char-a"))
        val segments = targetDb.segmentDao().forThread(single.id)
        val sealedIds = segments.filter { it.state == "SEALED" }.map { it.id }.toSet()
        val open = segments.single { it.state == "OPEN" }
        val restoredMessages = targetDb.messageDao().allForThread(single.id)
        assertEquals(6, restoredMessages.size)
        restoredMessages.forEach { assertTrue(it.segmentId in sealedIds) }
        assertTrue(targetDb.messageDao().bySegment(open.id).isEmpty())

        val appended = ChatRepository(targetDb).append(
            threadId = single.id,
            segmentId = open.id,
            role = roleUser,
            text = "恢复后新聊的一句",
            tokens = 4,
            createdAt = base + 20_000,
        )
        assertEquals(open.id, appended.segmentId)
        val activeMessages = targetDb.messageDao().bySegment(open.id)
        assertEquals(1, activeMessages.size)
        assertEquals("恢复后新聊的一句", activeMessages.single().content)
        assertEquals(7, targetDb.threadDao().byId(single.id)!!.totalMessages)
        // 还原消息仍不在 active 段。
        assertEquals(6, targetDb.messageDao().allForThread(single.id).count { it.segmentId != open.id })
    }

    // ================================================================== T1-5 空串/哨兵 → null

    @Test
    fun emptyStringsRestoredAsNull() = runBlocking {
        seedMainFixture()
        restoreMain()

        val charA = targetDb.characterDao().all().single { it.name == "阿淮" }
        assertNull("greeting 空串必须写成 NULL", charA.greeting)
        assertNull(charA.userName)

        val single = restoredThread(ThreadKind.SINGLE, charA.id)
        assertNull("thread.title 空串必须写成 NULL", single.title)
        assertNull("线程级记忆 owner 必须 NULL", targetDb.memoryDao().allForThread(single.id).single().characterId)
        val singleMemories = targetDb.memoryDao().forOwner(single.id, null)
        assertTrue(singleMemories.isNotEmpty())
        assertTrue(targetDb.memoryDao().activeCountForOwner(single.id, null) > 0)
        assertTrue(targetDb.memoryDao().forOwner(single.id, "").isEmpty())
        targetDb.messageDao().allForThread(single.id).forEach { assertNull(it.speakerCharacterId) }

        // 源 active OPEN 段的 summary 导成 "" → 恢复成 NULL（不写空串）。
        val restoredOpenSource = targetDb.segmentDao().forThread(single.id)
            .filter { it.state == "SEALED" && it.summary == null }
        assertEquals(1, restoredOpenSource.size)

        // 群聊显式 null + speaker/记忆映射。
        val group = restoredThread(ThreadKind.GROUP)
        assertNull(group.characterId)
        assertEquals(1, rawInt("SELECT COUNT(*) FROM thread WHERE id = '${group.id}' AND characterId IS NULL"))
        val groupMessages = targetDb.messageDao().allForThread(group.id)
        groupMessages.filter { it.role == roleAssistant }.forEach { assertNotNull(it.speakerCharacterId) }
        assertNull(groupMessages.single { it.role == roleUser }.speakerCharacterId)
        assertNotNull(targetDb.memoryDao().allForThread(group.id).single().characterId)

        // 全库无 '' 哨兵（逐列 SQL 计数）。
        listOf(
            "character.greeting", "character.userName", "character.styleSamples",
            "character.avatarUri", "character.systemPromptOverride",
            "thread.title", "thread.characterId",
            "segment.summary", "segment.batonReason",
            "memory_item.key", "memory_item.characterId",
            "message.speakerCharacterId",
        ).forEach { column ->
            val (table, name) = column.split(".")
            assertEquals("列 $column 不得出现 '' 哨兵", 0, rawInt("SELECT COUNT(*) FROM $table WHERE $name = ''"))
        }
        // validTo 哨兵 0 必须还原成 NULL（否则恢复的记忆全部被判失效）。
        assertEquals(2, rawInt("SELECT COUNT(*) FROM memory_item"))
        assertEquals(2, rawInt("SELECT COUNT(*) FROM memory_item WHERE validTo IS NULL"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM memory_item WHERE validTo = 0"))
        // happenedAt/dueAt 空值行同样 NULL。
        assertEquals(2, rawInt("SELECT COUNT(*) FROM memory_item WHERE happenedAt IS NULL AND dueAt IS NULL"))
    }

    // ================================================================== T1-6 lastExtractAt 隔离

    @Test
    fun lastExtractAtIsolatesRestoredHistory() = runBlocking {
        seedMainFixture()
        restoreMain()

        targetDb.threadDao().active().forEach { thread ->
            val messages = targetDb.messageDao().allForThread(thread.id)
            val maxCreatedAt = messages.maxOf { it.createdAt }
            assertEquals("lastExtractAt 必须 = 消息最大 createdAt", maxCreatedAt, thread.lastExtractAt)
            assertTrue(
                "恢复后第一次维护不得把历史当新对话送回抽取",
                targetDb.messageDao().after(thread.id, thread.lastExtractAt).isEmpty(),
            )
        }
    }

    // ================================================================== T1-7 关键词索引 + 零网络

    @Test
    fun keywordChunksAreBuiltWithoutNetwork() = runBlocking {
        seedMainFixture()
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"embedding":[0.5,1.0]}]}"""))
            val client = OkHttpClient()
            var embedCalls = 0
            val fakeEmbed = EmbedPort { texts ->
                embedCalls += 1
                val request = Request.Builder()
                    .url(server.url("/v1/embeddings"))
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().close()
                texts.map { floatArrayOf(0.5f, 1.0f) }
            }
            val trapIndexer = indexer(
                embed = fakeEmbed,
                embedConfigured = { true },
                embedModel = { "bge-m3" },
            )
            restoreMain(repository = repository(indexer = trapIndexer))

            // 结构性零网络：恢复路径硬编码 embedEnabled=false → 陷阱一次都没被调用。
            assertEquals("恢复期间不得调用保底/向量端口", 0, embedCalls)
            assertEquals("恢复期间不得发任何网络请求", 0, server.requestCount)

            val single = restoredThread(ThreadKind.SINGLE, newCharacterId("char-a"))
            val chunks = MemoryRepository(targetDb).recentChunks(single.id)
            assertTrue("关键词块必须写入", chunks.isNotEmpty())
            val sealedIds = targetDb.segmentDao().forThread(single.id)
                .filter { it.state == "SEALED" }.map { it.id }.toSet()
            val messageIds = targetDb.messageDao().allForThread(single.id).map { it.id }.toSet()
            chunks.forEach { chunk ->
                assertEquals(0, chunk.dim)
                assertTrue(chunk.vec.isEmpty())
                assertEquals("none", chunk.model)
                assertTrue("chunk.segmentId 必须属于该会话的 SEALED 段", chunk.segmentId in sealedIds)
                chunk.messageIds.split(",").forEach { assertTrue(it in messageIds) }
            }
            // 关键词可召回：BigramIndex.terms 返回 Set，keywordSearch 收 List → 必须 .toList。
            val terms = BigramIndex.terms("灯塔").toList()
            assertTrue(
                "关键词检索必须能召回恢复的旧事",
                MemoryRepository(targetDb).keywordSearch(single.id, terms).isNotEmpty(),
            )

            // 正向对照：同一 fake 在 embedEnabled=true 时真的会发请求（防「零请求恒真」假绿）。
            val sealedSegments = targetDb.segmentDao().forThread(single.id)
                .filter { it.state == "SEALED" }
                .map { segment ->
                    ImportedSegment(
                        sourceSessionId = "control",
                        segmentId = segment.id,
                        messageIds = targetDb.messageDao().bySegment(segment.id).map { it.id },
                        firstMessageAt = segment.firstMessageAt,
                        lastMessageAt = segment.lastMessageAt,
                    )
                }
            val control = trapIndexer.index(single.id, sealedSegments, embedEnabled = true)
            assertTrue("正向对照必须有块", control.chunks > 0)
            assertTrue("正向对照必须真的发出网络请求", server.requestCount > 0)
        } finally {
            server.shutdown()
        }
    }

    // ================================================================== T1-8 索引失败不回滚

    @Test
    fun indexFailureDoesNotRollbackData() = runBlocking {
        seedMainFixture()
        val failingIndexer = indexer(
            saveChunks = { _, _ -> throw IllegalStateException("注入索引故障") },
        )
        val result = restoreMain(repository = repository(indexer = failingIndexer))

        // 数据完整（事务已提交）。
        assertEquals(3, result.characterCount)
        assertEquals(9, result.messageCount)
        assertEquals(9, targetDb.messageDao().let { dao ->
            targetDb.threadDao().active().sumOf { dao.allForThread(it.id).size }
        })
        assertEquals(0, count("chunk"))

        // indexSkipped 是**块数**口径：等于会写入的块总数（每个 SEALED 段按其消息切块）。
        val expectedBlocks = targetDb.threadDao().active().sumOf { thread ->
            targetDb.segmentDao().forThread(thread.id)
                .filter { it.state == "SEALED" }
                .sumOf { segment ->
                    val messages = targetDb.messageDao().bySegment(segment.id)
                    if (messages.isEmpty()) {
                        0
                    } else {
                        val segmentRef = ImportedSegment(
                            sourceSessionId = thread.id,
                            segmentId = segment.id,
                            messageIds = messages.map { it.id },
                            firstMessageAt = segment.firstMessageAt,
                            lastMessageAt = segment.lastMessageAt,
                        )
                        cc.pulsez.app.core.buildBlocks(
                            thread.id,
                            segmentRef,
                            messages.map { ImportMessage(it.id, it.role, it.content, it.createdAt) },
                        ).size
                    }
                }
        }
        assertTrue("夹具必须产生检索块", expectedBlocks > 0)
        assertEquals("indexSkipped 必须是块数（不是会话数）", expectedBlocks, result.indexSkipped)
        assertEquals(
            "有 ${BackupCopy.formatCount(expectedBlocks)} 处旧事这次没整理好，以后可能找不全。" +
                "再恢复一次这份备份（会新增副本），可能会补上；不会覆盖现在的内容。",
            BackupCopy.restoreIndexMissing(result.indexSkipped),
        )
    }

    // ================================================================== T1-9 重复恢复新增副本

    @Test
    fun repeatedRestoreCreatesNewCopy() = runBlocking {
        seedMainFixture()
        val document = documentOf()
        val first = restoreMain(repository = repository(), document = document)
        val firstCharacters = targetDb.characterDao().all()
        val firstThreads = targetDb.threadDao().active()
        val firstMessages = targetDb.messageDao().allForThread(firstThreads.first().id)
        val firstSnapshot = snapshotCounts()
        val firstIds = snapshotPks()

        val second = restoreMain(repository = repository(), document = document)

        // 行数翻倍、两次计数一致。
        val doubled = snapshotCounts()
        firstSnapshot.forEach { (table, rows) ->
            assertEquals("表 $table 行数必须翻倍", rows * 2, doubled[table])
        }
        assertEquals(first.characterCount, second.characterCount)
        assertEquals(first.threadCount, second.threadCount)
        assertEquals(first.messageCount, second.messageCount)
        assertEquals(first.memoryCount, second.memoryCount)
        assertEquals(first.memberCount, second.memberCount)
        // 两批主键不相交。
        snapshotPks().forEach { (table, ids) ->
            val before = firstIds.getValue(table).toSet()
            val newly = ids.toSet() - before
            assertEquals("表 $table 第二次应恰好新增同样多行", before.size, newly.size)
            assertTrue("表 $table 两批主键不得相交", before.intersect(newly).isEmpty())
        }
        // 旧行字段逐字不变（J2）。
        assertEquals(firstCharacters.toSet(), targetDb.characterDao().all().filter { it.id in firstCharacters.map { c -> c.id } }.toSet())
        assertEquals(firstThreads.toSet(), targetDb.threadDao().active().filter { it.id in firstThreads.map { t -> t.id } }.toSet())
        assertEquals(firstMessages.toSet(), targetDb.messageDao().allForThread(firstThreads.first().id).toSet())
    }

    // ================================================================== T1-10 整包回滚

    @Test
    fun failureRollsBackWholeRestore() = runBlocking {
        // 目标库先放一份「用户已有数据」。
        targetDb.characterDao().upsert(
            CharacterEntity(
                id = "mine-char", name = "已有", avatarUri = null, persona = "原有",
                greeting = null, styleSamples = null, userName = null, systemPromptOverride = null,
                protocolEnabled = true, sourceType = "MANUAL", sourceRef = null, rawImported = null,
                emoAssetDir = null, createdAt = 1, updatedAt = 2, deletedAt = null,
            ),
        )
        targetDb.segmentDao().upsert(
            SegmentEntity(
                id = "mine-seg", threadId = "mine-thread", ordinal = 0, summary = null, styleNotes = null,
                firstMessageAt = 1, lastMessageAt = 1, tokenSum = 0, state = "OPEN", batonReason = null, createdAt = 1,
            ),
        )
        targetDb.threadDao().upsert(
            ThreadEntity(
                id = "mine-thread", characterId = "mine-char", title = "我的对话", createdAt = 1, lastMessageAt = 1,
                activeSegmentId = "mine-seg", segmentOrdinal = 0, totalMessages = 1, totalTokens = 2,
                lastExtractAt = 0, archived = false, kind = ThreadKind.SINGLE,
            ),
        )
        targetDb.messageDao().upsert(
            MessageEntity(
                id = "mine-message", threadId = "mine-thread", segmentId = "mine-seg", role = "user",
                content = "原来的消息", tokens = 2, emotion = null, status = "OK", draft = null,
                usageIn = null, usageOut = null, previousContent = null, createdAt = 1, deletedAt = null,
            ),
        )
        seedMainFixture()
        val document = documentOf()
        val beforePks = snapshotPks()
        val beforeCounts = snapshotCounts()

        val failing = repository(
            onStage = { stage ->
                if (stage == RestoreStage.MESSAGE) throw IllegalStateException("注入故障")
            },
        )
        val result = failing.restore(document)

        assertTrue("注入故障必须返回 Failed", result is RestoreResult.Failed)
        assertEquals("8 张表主键快照必须逐项相等", beforePks, snapshotPks())
        assertEquals("8 张表行数必须相等", beforeCounts, snapshotCounts())
        // 用户已有数据一字不动。
        assertEquals("原来的消息", targetDb.messageDao().byIds(listOf("mine-message")).single().content)
        assertEquals(1, targetDb.characterDao().all().size)
    }

    // ================================================================== T1-11 二次往返

    @Test
    fun reExportOfRestoredDatabaseIsRecognized() = runBlocking {
        seedMainFixture()
        val result = restoreMain()

        val json2 = ExportService.buildJson(targetDb)
        val inspection = BackupParser.inspect(json2, Source.FILE)
        assertTrue("恢复出来的库必须还能被同一套契约识别", inspection is BackupInspection.Preview)
        val preview = (inspection as BackupInspection.Preview).preview
        assertEquals(result.characterCount, preview.characterCount)
        assertEquals(result.threadCount, preview.threadCount)
        assertEquals(result.messageCount, preview.messageCount)
        // 附件不恢复 → 二次导出附件元数据为 0。
        assertEquals(0, preview.attachmentCount)
        assertEquals(0, preview.skippedOrphanThreads)
        // 恢复出来的库还能被 parseBackup 读回（不抛）。
        val document2 = BackupParser.parseBackup(json2)
        assertEquals(result.characterCount, document2.characters.size)
        assertEquals(result.threadCount, document2.threads.size)
        assertEquals(result.messageCount, document2.threads.sumOf { it.messages.size })
    }

    // ================================================================== T1-12 取消传播

    @Test
    fun cancellationPropagates() = runBlocking {
        seedMainFixture()
        val document = documentOf()

        // (a) 事务内取消：必须向上抛（不是 Failed），整包回滚。
        // 用**嵌套 runBlocking** 隔离取消：手动抛出的 CancellationException 会结束所在协程，
        // 若直接在外层协程里抛，后续 DAO 断言也会被取消污染（测试设施问题，不是产品行为）。
        val beforePks = snapshotPks()
        val beforeCounts = snapshotCounts()
        val cancelling = repository(
            onStage = { stage ->
                if (stage == RestoreStage.MESSAGE) throw CancellationException("注入取消")
            },
        )
        val outcome = runCatching { runBlocking { cancelling.restore(document) } }
        assertTrue(
            "事务内 CancellationException 必须向上抛（不能吞成 Failed），实际=$outcome",
            outcome.exceptionOrNull() is CancellationException,
        )
        assertEquals("事务内取消必须整包回滚", beforePks, snapshotPks())
        assertEquals(beforeCounts, snapshotCounts())

        // (b) 索引阶段取消：已提交事务的数据仍在（回滚不了已提交事务），协程以取消结束。
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val blockingIndexer = indexer(
            saveChunks = { _, _ ->
                started.complete(Unit)
                gate.await()
            },
        )
        val indexCancelRepo = repository(indexer = blockingIndexer)
        val job = launch { indexCancelRepo.restore(document) }
        started.await()
        job.cancelAndJoin()
        assertTrue("索引阶段取消必须让协程处于取消态", job.isCancelled)
        // 数据已提交（索引取消回滚不了已提交事务，属预期）；不把 indexSkipped 当成功上报。
        assertTrue("事务数据仍在", targetDb.characterDao().all().size >= 3)
        assertTrue(targetDb.threadDao().active().size >= 2)
    }

    // ================================================================== T1-13 空会话

    @Test
    fun emptyThreadRestoresOnlyOpenSegment() = runBlocking {
        seedCharacter("char-only", "只有人设")
        seedSegment("seg-empty", "thread-empty", 0, "OPEN", null, base, base, 0)
        seedThread(
            ThreadEntity(
                id = "thread-empty",
                characterId = "char-only",
                title = null,
                createdAt = base,
                lastMessageAt = base,
                activeSegmentId = "seg-empty",
                segmentOrdinal = 0,
                totalMessages = 0,
                totalTokens = 0,
                lastExtractAt = 0,
                archived = false,
                kind = ThreadKind.SINGLE,
            ),
        )
        val result = restoreMain()
        assertEquals(1, result.characterCount)
        assertEquals(1, result.threadCount)
        assertEquals(0, result.messageCount)
        assertEquals(1, result.segmentCount)

        val newCharId = newCharacterId("char-only")
        val restored = restoredThread(ThreadKind.SINGLE, newCharId)
        val segments = targetDb.segmentDao().forThread(restored.id)
        assertEquals("空会话只有 1 个 OPEN 段", 1, segments.size)
        val open = segments.single()
        assertEquals("OPEN", open.state)
        assertEquals(0, open.ordinal)
        assertEquals(0, restored.segmentOrdinal)
        assertEquals(0, restored.totalMessages)
        assertEquals(0L, restored.totalTokens)
        assertEquals(0L, restored.lastExtractAt)
        assertEquals(open.id, restored.activeSegmentId)
        assertTrue(targetDb.messageDao().allForThread(restored.id).isEmpty())
    }

    // ================================================================== T1-14 段头钳制（真实 App 形状）

    @Test
    fun segmentHeaderIsClampedToGroupMessages() = runBlocking {
        // 走生产路径种子：建会话时 OPEN 段头 = 建段时刻 T0、tokenSum=0；
        // 再用 ChatRepository.append 追加 3 条（createdAt 均 > T0）。
        val (character, thread) = CharacterRepository(sourceDb).createWithThread(
            name = "阿淮",
            persona = "温和",
        )
        val t0 = thread.lastMessageAt
        val t1 = t0 + 1_000
        val t2 = t0 + 2_000
        val t3 = t0 + 3_000
        val chat = ChatRepository(sourceDb)
        chat.append(thread.id, thread.activeSegmentId!!, roleUser, "第一句", tokens = 5, createdAt = t1)
        chat.append(thread.id, thread.activeSegmentId!!, roleAssistant, "第二句", tokens = 7, createdAt = t2)
        chat.append(thread.id, thread.activeSegmentId!!, roleAssistant, "第三句", tokens = 11, createdAt = t3)

        // 真实导出（该段头仍是 T0/T0/0，只有一条被消息引用的段）。
        val document = documentOf()
        val sourceThread = document.threads.single()
        assertEquals(3, sourceThread.messages.size)
        val exportedSegment = sourceThread.segments.single()
        assertEquals("导出的段头 first 是建段时刻（坏值，恢复必须钳制）", t0, exportedSegment.firstMessageAt)
        assertEquals(0, exportedSegment.tokenSum)

        restoreMain(document = document)

        val restored = restoredThread(ThreadKind.SINGLE, newCharacterId(character.id))
        val sealed = targetDb.segmentDao().forThread(restored.id).single { it.state == "SEALED" }
        assertEquals("firstMessageAt == min(T1, 导出 T0) = T0", t0, sealed.firstMessageAt)
        assertEquals("lastMessageAt == max(T3, 导出 T0) = T3", t3, sealed.lastMessageAt)
        assertEquals("tokenSum == max(5+7+11, 导出 0) = 23", 23, sealed.tokenSum)
        // 显式锚点：不得出现 lastMessageAt < 组内末条、tokenSum == 0、firstMessageAt > 组内首条。
        assertTrue("lastMessageAt 不得早于组内末条", sealed.lastMessageAt >= t3)
        assertTrue("tokenSum 必须被组内消息撑起来", sealed.tokenSum > 0)
        assertTrue("firstMessageAt 不得晚于组内首条", sealed.firstMessageAt <= t1)
    }
}
