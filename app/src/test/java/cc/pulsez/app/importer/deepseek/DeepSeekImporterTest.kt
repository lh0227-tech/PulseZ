package cc.pulsez.app.importer.deepseek

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.core.ConsolidateRange
import cc.pulsez.app.core.EmbedPort
import cc.pulsez.app.core.ImportIndexer
import cc.pulsez.app.core.ImportMemoryConsolidator
import cc.pulsez.app.core.ImportMessage
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.ImportBatchGroupPlan
import cc.pulsez.app.data.repo.ImportBatchPlan
import cc.pulsez.app.data.repo.ImportBatchWindowPlan
import cc.pulsez.app.data.repo.ImportRepository
import cc.pulsez.app.data.repo.MemoryRepository
import cc.pulsez.app.data.settings.Consolidation
import cc.pulsez.app.data.settings.ImportPrefs
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DeepSeek 导入器测试：会话统计与费用估算、导入和续导、取消语义、部分失败和断点恢复。
 *
 * 测试使用本地数据与 fake，不触发外部网络。费用确认、失败会话跳过、索引落库和成功后标记
 * 都由本文件的用例覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepSeekImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: PulsezDatabase
    private lateinit var memory: MemoryRepository
    private lateinit var importPrefs: ImportPrefs

    private val completeJsonCalls = mutableListOf<Pair<String, String>>()
    private val recordedUsage = mutableListOf<Pair<Int, Int>>()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        memory = MemoryRepository(db)
        importPrefs = ImportPrefs(context)
        // Robolectric 同一进程内 DataStore 按文件名是单例：用例间要复位，否则
        // 「已整理」/断点断言会随执行顺序漂移。
        runBlocking { importPrefs.clearAll() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------ 夹具

    /** 2025-03 / 2025-04 的固定时间，保证「源时间」断言稳定。 */
    private val s1Start = "2025-03-01T00:00:00.000Z"
    private val s1End = "2025-03-01T00:10:00.000Z"
    private val s2Start = "2025-04-01T00:00:00.000Z"
    private val s2End = "2025-04-01T00:10:00.000Z"

    private val manifestJson = """{"export_format_version":3}"""

    private val indexJson = """[
        {"id":"s-1","inserted_at":"$s1Start","updated_at":"$s1End"},
        {"id":"s-2","inserted_at":"$s2Start","updated_at":"$s2End"}
    ]""".trimIndent()

    private fun sessionJson(
        id: String,
        start: String,
        end: String,
        userText: String = "你好，$id",
        assistantText: String = "收到，$id",
    ): String = """
        {
          "id": "$id",
          "title": "会话 $id",
          "pinned": false,
          "current_message_id": 2,
          "inserted_at": "$start",
          "updated_at": "$end",
          "messages": [
            {
              "id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
              "inserted_at": "$start",
              "fragments": [{"type": "REQUEST", "content": "$userText"}]
            },
            {
              "id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
              "inserted_at": "$end",
              "fragments": [{"type": "RESPONSE", "content": "$assistantText"}]
            }
          ]
        }
    """.trimIndent()

    private fun sessionEntries(): List<Pair<String, String>> = listOf(
        "chat_sessions/s-1.json" to sessionJson("s-1", s1Start, s1End),
        "chat_sessions/s-2.json" to sessionJson("s-2", s2Start, s2End),
    )

    private fun multiTurnSessionJson(id: String, start: String, pairs: Int): String {
        val firstAt = Instant.parse(start)
        val messages = (0 until pairs * 2).joinToString(",") { index ->
            val parent = if (index == 0) "null" else (index).toString()
            val role = if (index % 2 == 0) "USER" else "ASSISTANT"
            val fragment = if (role == "USER") "REQUEST" else "RESPONSE"
            val at = firstAt.plusSeconds(index.toLong()).toString()
            """{"id":${index + 1},"parent_id":$parent,"role":"$role","status":"FINISHED","inserted_at":"$at","fragments":[{"type":"$fragment","content":"synthetic-$id-message-$index"}]}"""
        }
        val end = firstAt.plusSeconds((pairs * 2L) - 1L).toString()
        return """
            {"id":"$id","title":"合成多轮会话","pinned":false,"current_message_id":${pairs * 2},
             "inserted_at":"$start","updated_at":"$end","messages":[$messages]}
        """.trimIndent()
    }

    private fun writeZip(
        fileName: String = "deepseek-export.zip",
        entries: List<Pair<String, String>>? = null,
    ): File {
        val allEntries = entries ?: (
            listOf(
                "manifest.json" to manifestJson,
                "chat_session_index.json" to indexJson,
            ) + sessionEntries()
            )
        val file = File(tempFolder.root, fileName)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, content) in allEntries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return file
    }

    private suspend fun loadMessages(ids: List<String>): List<ImportMessage> =
        db.messageDao().byIds(ids).map { message ->
            ImportMessage(
                id = message.id,
                role = message.role,
                text = message.content,
                at = message.createdAt,
            )
        }

    /**
 * 真 类 + 假 IO：索引写内存 Room；整理用假 `completeJson`（默认返回空补丁 `{}`），
 * `costOf` 由用例决定（默认为 0，避免计划 >¥1 触发确认）。
 *
 * @param costPerShard 每片估价（¥）；>1 会触发费用确认
 * @param consolidateMessages 整理侧读到的消息（默认 = 真 DB 读）；传 emptyList 模拟
 * 「范围内一条可整理的消息都没有」（没有可整理内容的场景）
 * @param json 假模型的返回；null = 全部失败（验证「失败不写已整理标记」）
 * @param progress 断点读写（默认不持久化）
 * @param embed 「找旧事」向量端口；默认返回 null（不建向量）
 * @param embedConfigured 「找旧事」是否配置齐全（默认 false = 零网络）
 * @param embedConsent 用户是否勾了「顺便让 TA 按意思找旧事」（默认 false = 行为）
 */
    private fun buildImporter(
        costPerShard: Double = 0.0,
        consolidateMessages: ((List<String>) -> List<ImportMessage>)? = null,
        json: String? = "{}",
        loadProgress: suspend (String) -> Consolidation? = { null },
        saveProgress: suspend (Consolidation) -> Unit = {},
        clearProgress: suspend (String) -> Unit = {},
        embed: EmbedPort = EmbedPort { null },
        embedConfigured: Boolean = false,
        embedConsent: Boolean = false,
        afterBatchChunkSaved: (List<ChunkEntity>) -> Unit = {},
        batchSourceIndexStatusWriter: (suspend (String, String, String) -> Boolean)? = null,
        loadBatchChunks: (suspend (String, String, List<String>) -> List<ChunkEntity>?)? = null,
    ): DeepSeekImporter {
        val repository = ImportRepository(db)
        val indexer = ImportIndexer(
            loadMessages = { ids -> loadMessages(ids) },
            saveChunks = { chunks, terms -> memory.saveChunks(chunks, terms) },
            embed = embed,
            embedModel = { TEST_EMBED_MODEL },
            embedConfigured = { embedConfigured },
            embedConfigFingerprint = { TEST_EMBED_CONFIG_FINGERPRINT },
            loadBatchChunks = { batchId, segmentId, chunkIds ->
                loadBatchChunks?.invoke(batchId, segmentId, chunkIds)
                    ?: repository.loadBatchChunksIfActive(batchId, segmentId, chunkIds)
            },
            saveBatchChunks = { batchId, chunks, terms, sourceIndexComplete ->
                repository.saveBatchChunksIfActive(batchId, chunks, terms, sourceIndexComplete).also { saved ->
                    if (saved) afterBatchChunkSaved(chunks)
                }
            },
        )
        val consolidator = ImportMemoryConsolidator(
            loadMessages = { ids -> consolidateMessages?.invoke(ids) ?: loadMessages(ids) },
            applyPatch = { threadId, patchJson ->
                memory.applyPatch(threadId, patchJson, segmentId = null, ownerId = null)
            },
            completeJson = { system, user ->
                completeJsonCalls += system to user
                json
            },
            recordUsage = { inputTokens, outputTokens ->
                recordedUsage += inputTokens to outputTokens
            },
            costOf = { _, _ -> costPerShard },
            loadProgress = loadProgress,
            saveProgress = saveProgress,
            clearProgress = clearProgress,
        )
        return DeepSeekImporter(
            repo = repository,
            prefs = importPrefs,
            indexer = indexer,
            consolidator = consolidator,
            database = db,
            embedConsent = { embedConsent },
            batchSourceIndexStatusWriter = batchSourceIndexStatusWriter
                ?: { batchId, segmentId, status -> repository.setBatchSourceIndexStatus(batchId, segmentId, status) },
        )
    }

    /** 记录调用、固定返回 4 维向量的向量端口 Fake。 */
    private class FakeEmbed(private val vectorDim: Int = 4) : EmbedPort {
        val calls = mutableListOf<List<String>>()

        override suspend fun embed(texts: List<String>): List<FloatArray>? {
            calls += texts
            return texts.map { text -> FloatArray(vectorDim) { position -> (text.length + position + 1).toFloat() } }
        }
    }

    private suspend fun doImport(
        importer: DeepSeekImporter,
        zip: File,
        consolidate: Boolean = false,
        range: ConsolidateRange = ConsolidateRange.ALL,
        isCancelled: () -> Boolean = { false },
        resumeCharacterId: String? = null,
    ): DeepSeekImporter.ImportOutcome = importer.import(
        zip = zip,
        zipName = "deepseek-export.zip",
        name = "小满",
        persona = "温和",
        consolidate = consolidate,
        range = range,
        resumeCharacterId = resumeCharacterId,
        onProgress = { _ -> },
        onIndexProgress = { _, _ -> },
        onConsolidateProgress = { _, _ -> },
        isCancelled = isCancelled,
        requiresConsent = { false },
    )

    // ------------------------------------------------------------------ 1. 预检

    @Test
    fun inspectReportsSessionsTimeRangeFingerprintAndEstimate() = runBlocking {
        val zip = writeZip()

        val inspect = buildImporter().inspect(zip, "deepseek-export.zip")

        assertEquals(2, inspect.sessions)
        assertEquals(
            Instant.parse(s1Start).toEpochMilli()..Instant.parse(s2End).toEpochMilli(),
            inspect.timeRange,
        )
        assertEquals(zip.length(), inspect.zipSize)
        assertEquals("deepseek-export.zip:${zip.length()}", inspect.fingerprint)

        // 粗估：tokens ≈ Σ声明字节 / 4；分片 = ⌈tokens / 40000⌉；¥0.096/片。
        val declaredBytes = sessionEntries().sumOf { (_, content) ->
            content.toByteArray(Charsets.UTF_8).size.toLong()
        }
        val expectedShards = ceil(declaredBytes.toDouble() / 4.0 / 40_000.0).toInt()
        assertEquals(expectedShards, inspect.estimatedShards)
        assertEquals(
            expectedShards * 0.096,
            inspect.estimatedYuan ?: -1.0,
            1e-12,
        )
    }

    @Test
    fun `无会话的包预检抛 NO_SESSIONS`() = runBlocking {
        val zip = writeZip(
            entries = listOf("manifest.json" to manifestJson),
        )

        val error = runCatching { buildImporter().inspect(zip, "empty.zip") }.exceptionOrNull()

        assertTrue("应是 ZipImportException", error is ZipImportException)
        assertEquals(ImportErrorCodes.NO_SESSIONS, (error as ZipImportException).code)
    }

    // ------------------------------------------------------------------ 2. 正常导入

    @Test
    fun importWritesSealedSegmentsIndexesAndReportsCounts() = runBlocking {
        val zip = writeZip()
        val outcome = doImport(buildImporter(), zip)

        val summary = outcome.summary
        assertEquals("新建 TA", 2, summary.sessionsImported)
        assertEquals(0, summary.sessionsSkippedExisting)
        assertEquals(0, summary.sessionsFailed)
        assertEquals(4, summary.messagesInserted)
        assertEquals(0, summary.messagesExisting)
        assertEquals(2, summary.segments.size)
        assertTrue("新建导入：createdNewCharacter=true，结果页可撤销", summary.createdNewCharacter)
        assertTrue("本地索引应成功", outcome.indexed)
        assertNull("没勾整理就不跑", outcome.consolidation)
        assertNull(outcome.consolidationPlan)
        assertFalse(outcome.consentPending)

        // 段不变量：2 SEALED + 1 OPEN；导入消息全部在 SEALED，active 段为空（第 4 条）。
        val thread = requireNotNull(db.threadDao().byId(summary.threadId))
        val segments = db.segmentDao().forThread(thread.id)
        assertEquals(3, segments.size)
        assertEquals(2, segments.count { it.state == ImportRepository.SEGMENT_STATE_SEALED })
        assertEquals(1, segments.count { it.state == ImportRepository.SEGMENT_STATE_OPEN })
        assertTrue(db.messageDao().bySegment(requireNotNull(thread.activeSegmentId)).isEmpty())
        assertEquals(4, db.messageDao().allForThread(thread.id).size)

        // 本地索引确实写进了 chunk（零 API）。
        assertTrue(
            "导入后应有本地检索块",
            db.chunkDao().recent(thread.id, limit = 100).isNotEmpty(),
        )
    }

    @Test
    fun resumeImportReportsCreatedNewCharacterFalse() = runBlocking {
        val zip = writeZip()
        val importer = buildImporter()
        val first = doImport(importer, zip)
        assertTrue(first.summary.createdNewCharacter)

        // 续导（即使有新增消息）：复用旧 TA，结果页不能提供整批撤销。
        val second = doImport(
            importer,
            zip,
            resumeCharacterId = first.summary.characterId,
        )

        assertEquals("同一个 TA", first.summary.characterId, second.summary.characterId)
        assertFalse(
            "续导：createdNewCharacter=false（即使补进了新消息）",
            second.summary.createdNewCharacter,
        )
    }

    // ------------------------------------------------------------------ 3. 单会话失败不阻断

    @Test
    fun brokenSessionIsSkippedAndCountedWhileOthersImport() = runBlocking {
        val zip = writeZip(
            entries = listOf(
                "manifest.json" to manifestJson,
                "chat_session_index.json" to indexJson,
                "chat_sessions/s-1.json" to sessionJson("s-1", s1Start, s1End),
                // 坏 JSON：整段会话读不出来，只能跳过并计数。
                "chat_sessions/s-2.json" to """{"id":"s-2","messages":[{"id":1,"""",
            ),
        )

        val outcome = doImport(buildImporter(), zip)
        val summary = outcome.summary

        assertEquals(1, summary.sessionsImported)
        assertEquals(1, summary.sessionsFailed)
        assertEquals(2, summary.messagesInserted)
        assertTrue(
            "issue 只含原因码（不带正文）",
            summary.issues.any { it.code == DeepSeekParseException.MALFORMED_JSON },
        )
        assertEquals(2, db.messageDao().allForThread(summary.threadId).size)
    }

    // ------------------------------------------------------------------ 4. 费用确认 + 继续整理

    @Test
    fun expensivePlanPausesForConsentThenResumes() = runBlocking {
        val zip = writeZip()
        // 每片估 ¥10 → 计划 > ¥1，必须停下来等确认。
        val importer = buildImporter(costPerShard = 10.0)

        val outcome = doImport(importer, zip, consolidate = true)

        assertTrue("计划超阈值应等确认", outcome.consentPending)
        assertNull("确认前不能花整理费用", outcome.consolidation)
        val plan = requireNotNull(outcome.consolidationPlan)
        assertTrue("预估必须 > ¥1", plan.estimatedYuan > 1.0)
        assertTrue("确认前不能调用模型", completeJsonCalls.isEmpty())
        assertTrue("确认前不能记账", recordedUsage.isEmpty())
        val pending = requireNotNull(importer.pendingConsolidation)
        assertEquals(outcome.summary.threadId, pending.threadId)

        // 用户确认后继续：运行整理流程（fake completeJson 返回空补丁）。
        val result = importer.resumeConsolidation(
            threadId = pending.threadId,
            segments = pending.segments,
            range = pending.range,
            plan = pending.plan,
            onProgress = { _, _ -> },
            isCancelled = { false },
        )

        assertEquals(1, result.shardsTotal)
        assertEquals(1, result.shardsDone)
        assertEquals(0, result.failed)
        assertFalse(result.cancelled)
        assertEquals("每片一次模型调用", 1, completeJsonCalls.size)
        assertEquals("每片记一次用量", 1, recordedUsage.size)
        assertNull("续跑完成后上下文应清掉", importer.pendingConsolidation)
    }

    // ------------------------------------------------------------------ 4b. 0 片回退 ALL / 不标记

    @Test
    fun emptyLast90DaysFallsBackToAllAndConsolidatesEverything() = runBlocking {
        val zip = writeZip()
        // fixture 的会话时间固定为 2025-03/2025-04，远早于「最近 90 天」→ 90 天计划 0 片。
        val importer = buildImporter()

        val outcome = doImport(importer, zip, consolidate = true, range = ConsolidateRange.LAST_90_DAYS)

        // 回退：计划不是 0 片，并且真的调了模型（否则就是「0 片空转」）。
        val plan = requireNotNull(outcome.consolidationPlan) { "应当给出（回退后的）整理计划" }
        assertTrue("90 天空时应回退全部，计划 > 0 片", plan.shards > 0)
        assertFalse(outcome.consentPending)
        assertEquals("回退后确实跑了全部范围", 1, completeJsonCalls.size)
        val result = requireNotNull(outcome.consolidation)
        assertEquals(plan.shards, result.shardsTotal)
        assertEquals(0, result.failed)
        assertEquals(plan.shards, result.shardsDone)
        // 全部成功且未取消 → 写「已整理」标记（Q5 不重复收费）。
        assertTrue(
            "整段成功要标记已整理",
            importPrefs.consolidatedThreads.first().contains(outcome.summary.threadId),
        )
    }

    @Test
    fun nothingToConsolidateAnywhereReturnsZeroResultAndIsNotMarked() = runBlocking {
        val zip = writeZip()
        // 整理侧读不到任何消息（等价于「全部范围也没有可整理的往事」）。
        val importer = buildImporter(consolidateMessages = { emptyList() })

        val outcome = doImport(importer, zip, consolidate = true, range = ConsolidateRange.LAST_90_DAYS)

        val result = requireNotNull(outcome.consolidation) {
            "0 片也要给结果（用于和「先不整理」区分），不能是 null"
        }
        assertEquals("没有可整理的分片", 0, result.shardsTotal)
        assertFalse(outcome.consentPending)
        assertTrue("0 片绝不能调用模型", completeJsonCalls.isEmpty())
        assertTrue("0 片不能记账", recordedUsage.isEmpty())
        assertFalse(
            "0 片不能写「已整理」标记（否则全部早于 90 天的档案再无入口）",
            importPrefs.consolidatedThreads.first().contains(outcome.summary.threadId),
        )
    }

    @Test
    fun failedConsolidationKeepsBreakpointAndIsNotMarked() = runBlocking {
        val zip = writeZip()
        val saved = mutableListOf<Consolidation>()
        // 模型全部失败（null）：不写记忆、不记账，断点保留，不能标记已整理。
        val importer = buildImporter(
            json = null,
            saveProgress = { state -> saved += state },
        )

        val outcome = doImport(importer, zip, consolidate = true, range = ConsolidateRange.ALL)

        val result = requireNotNull(outcome.consolidation)
        assertEquals(1, result.failed)
        assertEquals("失败片不算成功片数", 0, result.shardsDone)
        assertEquals(1, completeJsonCalls.size)
        assertTrue(recordedUsage.isEmpty())
        val last = requireNotNull(saved.lastOrNull())
        assertEquals(
            "失败片进 failedShards，下次只重试它",
            setOf(0),
            last.failedShards,
        )
        assertTrue(last.doneShards.isEmpty())
        assertFalse(
            "有失败片不能标记已整理",
            importPrefs.consolidatedThreads.first().contains(outcome.summary.threadId),
        )
    }

    // ------------------------------------------------------------------ 5. 取消

    @Test
    fun cancelledBeforeAnySessionReportsCancelledAndCreatesNoCharacter() = runBlocking {
        val zip = writeZip()

        val error = runCatching {
            doImport(buildImporter(), zip, isCancelled = { true })
        }.exceptionOrNull()

        assertTrue("取消应是 ZipImportException", error is ZipImportException)
        assertEquals(ImportErrorCodes.CANCELLED, (error as ZipImportException).code)
        assertTrue("一个会话都没提交就不该建 TA", db.characterDao().all().isEmpty())
    }

    // ------------------------------------------------------------------ 6. 纯函数：粗估公式

    @Test
    fun estimateFromDeclaredSizesHandlesUnknownAndRounding() {
        assertNull("没有内容不估", estimateFromDeclaredSizes(emptyList()))
        assertNull("任何一条未知就整体未知", estimateFromDeclaredSizes(listOf(100L, -1L)))
        assertEquals("空字节不凑片", 0, estimateFromDeclaredSizes(listOf(0L))!!.shards)

        // 4 字节 = 1 token；40_000 token = 1 片 → 160_000 字节。
        val one = requireNotNull(estimateFromDeclaredSizes(listOf(160_000L)))
        assertEquals(1, one.shards)
        assertEquals(0.096, one.estimatedYuan, 1e-12)

        // 400_000 字节 = 100_000 token → 2.5 片 → 向上取整 3 片。
        val three = requireNotNull(estimateFromDeclaredSizes(listOf(400_000L)))
        assertEquals(3, three.shards)
        assertEquals(3 * 0.096, three.estimatedYuan, 1e-12)
    }

    // ------------------------------------------------------------------ 7. 导入也建向量

    @Test
    fun importBuildsVectorsWhenServiceConfiguredAndUserConsented() = runBlocking {
        val zip = writeZip()
        val embed = FakeEmbed()
        val importer = buildImporter(embed = embed, embedConfigured = true, embedConsent = true)

        val outcome = doImport(importer, zip)

        assertTrue(outcome.indexed)
        assertTrue("配置齐 + 用户同意 → 必须调用用户的找旧事服务", embed.calls.isNotEmpty())
        val chunks = db.chunkDao().recent(outcome.summary.threadId, limit = 100)
        assertTrue("导入后必须有检索块", chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertTrue("导入 chunk 要带向量：dim>0", chunk.dim > 0)
            assertTrue("vec 非空", chunk.vec.isNotEmpty())
            assertTrue("messageIds 必须仍然非空（删除传播）", chunk.messageIds.isNotBlank())
            assertEquals("向量模型名写用户配置的那个", TEST_EMBED_MODEL, chunk.model)
        }
        assertFalse("导入结束后「正在建向量」信号必须复位", importer.semanticIndexing.value)
    }

    @Test
    fun importKeepsKeywordOnlyWhenUserTurnsEmbeddingOff() = runBlocking {
        val zip = writeZip()
        val embed = FakeEmbed()
        val importer = buildImporter(embed = embed, embedConfigured = true, embedConsent = false)

        val outcome = doImport(importer, zip)

        assertTrue(outcome.indexed)
        assertTrue("用户关掉时零调用（不静默花钱）", embed.calls.isEmpty())
        val chunks = db.chunkDao().recent(outcome.summary.threadId, limit = 100)
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertEquals("关掉时保持既有行为：dim=0", 0, chunk.dim)
            assertEquals(ImportIndexer.MODEL_NONE, chunk.model)
        }
    }

    @Test
    fun importKeepsKeywordOnlyWhenServiceNotConfigured() = runBlocking {
        val zip = writeZip()
        val embed = FakeEmbed()
        // 用户勾了（默认开），但「找旧事」没配好 → 零网络调用，行为保持不变。
        val importer = buildImporter(embed = embed, embedConfigured = false, embedConsent = true)

        val outcome = doImport(importer, zip)

        assertTrue(embed.calls.isEmpty())
        val chunks = db.chunkDao().recent(outcome.summary.threadId, limit = 100)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.dim == 0 && it.model == ImportIndexer.MODEL_NONE })
        assertFalse(importer.semanticIndexing.value)
    }

    @Test
    fun `batch retry indexes only unfinished sources and upgrades keyword chunk after vector failure`() = runBlocking {
        val zip = writeZip()
        val failSecondSource = mutableSetOf("s-2")
        val embedCalls = mutableListOf<List<String>>()
        val embed = EmbedPort { texts ->
            embedCalls += texts
            if (texts.any { text -> failSecondSource.any { sourceId -> text.contains(sourceId) } }) {
                null // Null triggers the indexer's documented bounded retry, then keyword-only fallback.
            } else {
                texts.mapIndexed { index, _ ->
                    FloatArray(4) { position -> (embedCalls.size + index + position + 1).toFloat() }
                }
            }
        }
        val importer = buildImporter(embed = embed, embedConfigured = true, embedConsent = true)
        val inspect = importer.inspect(zip, "deepseek-export.zip")
        val groupBySource = mapOf("s-1" to "group-s-1", "s-2" to "group-s-2")
        val plan = ImportBatchPlan(
            id = "batch-index-retry",
            fingerprint = inspect.contentFingerprint,
            zipName = "deepseek-export.zip",
            groups = listOf(
                ImportBatchGroupPlan(id = "group-s-1", name = "TA 1"),
                ImportBatchGroupPlan(id = "group-s-2", name = "TA 2"),
            ),
            windows = inspect.sourceWindows.map { preview ->
                ImportBatchWindowPlan(preview, groupBySource.getValue(preview.sourceSessionId))
            },
        )

        val first = importer.importBatch(
            zip = zip,
            plan = plan,
            onProgress = {},
            onIndexProgress = { _, _ -> },
            isCancelled = { false },
        )

        val repository = ImportRepository(db)
        val firstSources = db.importBatchDao().sourcesForBatch(plan.id).associateBy { it.sourceSessionId }
        assertFalse("向量未完成应明确报告后处理未完成", first.indexed)
        assertEquals(ImportRepository.INDEX_DONE, firstSources.getValue("s-1").indexStatus)
        assertEquals(ImportRepository.INDEX_PARTIAL, firstSources.getValue("s-2").indexStatus)
        assertEquals(ImportRepository.BATCH_PARTIAL, db.importBatchDao().batch(plan.id)?.status)
        assertEquals(
            "向量失败不回滚或重复写聊天",
            4,
            firstSources.values.map { it.targetThreadId }.distinct().sumOf { db.messageDao().allForThread(it).size },
        )
        val keywordOnly = db.chunkDao().forSegment(firstSources.getValue("s-2").segmentId).single()
        assertEquals(0, keywordOnly.dim)
        assertEquals(ImportIndexer.MODEL_NONE, keywordOnly.model)
        assertEquals(1, embedCalls.count { texts -> texts.any { it.contains("s-1") } })
        assertEquals(
            ImportIndexer.EMBED_MAX_RETRIES + 1,
            embedCalls.count { texts -> texts.any { it.contains("s-2") } },
        )

        val resumedPlan = requireNotNull(repository.resumableBatch(inspect.contentFingerprint))
        assertEquals("续导 plan 保留成功组，不重写已提交聊天", 2, resumedPlan.groups.size)
        failSecondSource.clear()
        val callsBeforeRetry = embedCalls.size
        val retried = importer.importBatch(
            zip = zip,
            plan = resumedPlan,
            onProgress = {},
            onIndexProgress = { _, _ -> },
            isCancelled = { false },
        )

        val retryCalls = embedCalls.drop(callsBeforeRetry)
        assertEquals("只为未完成来源发送一次成功请求", 1, retryCalls.size)
        assertTrue("已完成来源不能重复请求 embedding", retryCalls.all { texts -> texts.any { it.contains("s-2") } })
        assertTrue(retried.indexed)
        assertEquals(ImportRepository.BATCH_COMPLETE, db.importBatchDao().batch(plan.id)?.status)
        val finalSources = db.importBatchDao().sourcesForBatch(plan.id).associateBy { it.sourceSessionId }
        assertEquals(ImportRepository.INDEX_DONE, finalSources.getValue("s-1").indexStatus)
        assertEquals(ImportRepository.INDEX_DONE, finalSources.getValue("s-2").indexStatus)
        assertEquals(
            "续导成功组不重复写入聊天",
            4,
            finalSources.values.map { it.targetThreadId }.distinct().sumOf { db.messageDao().allForThread(it).size },
        )
        val upgraded = db.chunkDao().forSegment(finalSources.getValue("s-2").segmentId).single()
        assertTrue("重试必须把关键词-only chunk 替换为真实向量", upgraded.dim > 0)
        assertTrue(upgraded.vec.isNotEmpty())
        assertEquals(TEST_EMBED_MODEL, upgraded.model)
        assertEquals(first.summary.characterId, retried.summary.characterId)
    }

    @Test
    fun `batch invalid vectors persist keyword chunks as partial and resume with vectors`() = runBlocking {
        val sourceIds = listOf("s-bad-nan", "s-bad-infinite", "s-bad-zero")
        val starts = listOf(s1Start, s2Start, "2025-05-01T00:00:00.000Z")
        val ends = listOf(s1End, s2End, "2025-05-01T00:10:00.000Z")
        val index = sourceIds.indices.joinToString(prefix = "[", postfix = "]") { position ->
            """{"id":"${sourceIds[position]}","inserted_at":"${starts[position]}","updated_at":"${ends[position]}"}"""
        }
        val zip = writeZip(
            fileName = "batch-invalid-vectors.zip",
            entries = listOf(
                "manifest.json" to manifestJson,
                "chat_session_index.json" to index,
            ) + sourceIds.mapIndexed { position, id ->
                "chat_sessions/$id.json" to multiTurnSessionJson(id, starts[position], pairs = 1)
            },
        )
        val invalidVectors: Map<String, () -> FloatArray> = mapOf(
            "s-bad-nan" to { FloatArray(4) { position -> if (position == 0) Float.NaN else 1f } },
            "s-bad-infinite" to { FloatArray(4) { position -> if (position == 0) Float.POSITIVE_INFINITY else 1f } },
            "s-bad-zero" to { FloatArray(4) },
        )
        var returnInvalidVectors = true
        val embedCalls = mutableListOf<List<String>>()
        val embed = EmbedPort { texts ->
            embedCalls += texts
            if (returnInvalidVectors) {
                val sourceId = sourceIds.single { id -> texts.any { id in it } }
                val invalid = invalidVectors.getValue(sourceId).invoke()
                texts.map { invalid.copyOf() }
            } else {
                texts.map { FloatArray(4) { position -> (position + 1).toFloat() } }
            }
        }
        val importer = buildImporter(embed = embed, embedConfigured = true, embedConsent = true)
        val inspect = importer.inspect(zip, "batch-invalid-vectors.zip")
        val plan = ImportBatchPlan(
            id = "batch-invalid-vectors",
            fingerprint = inspect.contentFingerprint,
            zipName = "batch-invalid-vectors.zip",
            groups = listOf(ImportBatchGroupPlan(id = "group-invalid-vectors", name = "TA")),
            windows = inspect.sourceWindows.map { ImportBatchWindowPlan(it, "group-invalid-vectors") },
        )

        val first = importer.importBatch(zip, plan, {}, { _, _ -> }, isCancelled = { false })
        val repository = ImportRepository(db)
        val firstSources = db.importBatchDao().sourcesForBatch(plan.id).associateBy { it.sourceSessionId }
        assertFalse(first.indexed)
        assertEquals(ImportRepository.BATCH_PARTIAL, db.importBatchDao().batch(plan.id)?.status)
        assertEquals(ImportRepository.POST_PROCESS_PARTIAL, db.importBatchDao().batch(plan.id)?.postProcessStatus)
        assertEquals(sourceIds.size, firstSources.size)
        sourceIds.forEach { sourceId ->
            assertEquals(ImportRepository.INDEX_PARTIAL, firstSources.getValue(sourceId).indexStatus)
            val keywordChunk = db.chunkDao().forSegment(firstSources.getValue(sourceId).segmentId).single()
            assertEquals(0, keywordChunk.dim)
            assertTrue(keywordChunk.vec.isEmpty())
            assertEquals(ImportIndexer.MODEL_NONE, keywordChunk.model)
            assertEquals("", keywordChunk.embeddingConfigFingerprint)
            val sourceTerm = sourceId.substringAfterLast('-')
            assertEquals(
                "来源专属关键词仍可检索",
                setOf(keywordChunk.id),
                db.chunkDao().searchTerms(keywordChunk.threadId, listOf(sourceTerm), 10)
                    .map { it.chunkId }.toSet(),
            )
        }
        assertEquals("每个来源的非法批都按约定重试两次", sourceIds.size * (ImportIndexer.EMBED_MAX_RETRIES + 1), embedCalls.size)
        assertTrue(repository.resumableBatch(inspect.contentFingerprint) != null)
        val threadId = firstSources.values.first().targetThreadId
        val messageCountBeforeResume = db.messageDao().allForThread(threadId).size
        assertEquals(sourceIds.size * 2, messageCountBeforeResume)

        val resumedPlan = requireNotNull(repository.resumableBatch(inspect.contentFingerprint))
        returnInvalidVectors = false
        val callsBeforeResume = embedCalls.size
        val resumed = importer.importBatch(zip, resumedPlan, {}, { _, _ -> }, isCancelled = { false })

        val retryCalls = embedCalls.drop(callsBeforeResume)
        assertTrue(resumed.indexed)
        assertEquals("续导只为三个未完成来源各发一次请求", sourceIds.size, retryCalls.size)
        sourceIds.forEach { sourceId ->
            assertEquals(1, retryCalls.count { texts -> texts.any { sourceId in it } })
        }
        assertEquals(ImportRepository.BATCH_COMPLETE, db.importBatchDao().batch(plan.id)?.status)
        assertEquals(ImportRepository.POST_PROCESS_DONE, db.importBatchDao().batch(plan.id)?.postProcessStatus)
        val finalSources = db.importBatchDao().sourcesForBatch(plan.id).associateBy { it.sourceSessionId }
        assertTrue(finalSources.values.all { it.indexStatus == ImportRepository.INDEX_DONE })
        val finalChunks = finalSources.values.flatMap { db.chunkDao().forSegment(it.segmentId) }
        assertEquals(sourceIds.size, finalChunks.size)
        assertTrue(finalChunks.all { it.dim == 4 && it.vec.size == 4 && it.model == TEST_EMBED_MODEL })
        assertEquals("续导不能重复导入聊天消息", messageCountBeforeResume, db.messageDao().allForThread(threadId).size)
    }

    @Test
    fun `checkpoint read failure marks source resumable without repeating completed source`() = runBlocking {
        val zip = writeZip()
        val embedCalls = mutableListOf<List<String>>()
        val embed = EmbedPort { texts ->
            embedCalls += texts
            texts.mapIndexed { index, _ ->
                FloatArray(4) { position -> (embedCalls.size + index + position + 1).toFloat() }
            }
        }
        var checkpointReads = 0
        var failCheckpointRead = true
        val importer = buildImporter(
            embed = embed,
            embedConfigured = true,
            embedConsent = true,
            loadBatchChunks = { _, _, _ ->
                checkpointReads++
                if (failCheckpointRead && checkpointReads == 2) {
                    throw IllegalStateException("injected checkpoint read failure")
                }
                null // fall through to the real repository checkpoint read
            },
        )
        val inspect = importer.inspect(zip, "checkpoint-read-failure.zip")
        val groupBySource = mapOf("s-1" to "group-checkpoint-1", "s-2" to "group-checkpoint-2")
        val plan = ImportBatchPlan(
            id = "batch-checkpoint-read-failure",
            fingerprint = inspect.contentFingerprint,
            zipName = "checkpoint-read-failure.zip",
            groups = listOf(
                ImportBatchGroupPlan(id = "group-checkpoint-1", name = "TA 1"),
                ImportBatchGroupPlan(id = "group-checkpoint-2", name = "TA 2"),
            ),
            windows = inspect.sourceWindows.map { preview ->
                ImportBatchWindowPlan(preview, groupBySource.getValue(preview.sourceSessionId))
            },
        )

        val first = importer.importBatch(zip, plan, {}, { _, _ -> }, isCancelled = { false })
        val repository = ImportRepository(db)
        val firstSources = db.importBatchDao().sourcesForBatch(plan.id).associateBy { it.sourceSessionId }
        val completedSource = firstSources.values.single { it.indexStatus == ImportRepository.INDEX_DONE }
        val failedSource = firstSources.values.single { it.indexStatus == ImportRepository.INDEX_FAILED }
        assertFalse(first.indexed)
        assertEquals(ImportRepository.BATCH_PARTIAL, db.importBatchDao().batch(plan.id)?.status)
        assertEquals(ImportRepository.POST_PROCESS_PARTIAL, db.importBatchDao().batch(plan.id)?.postProcessStatus)
        assertTrue(db.chunkDao().forSegment(failedSource.segmentId).isEmpty())
        assertEquals(1, embedCalls.size)

        val resumedPlan = requireNotNull(repository.resumableBatch(inspect.contentFingerprint))
        failCheckpointRead = false
        val callsBeforeResume = embedCalls.size
        val resumed = importer.importBatch(zip, resumedPlan, {}, { _, _ -> }, isCancelled = { false })

        val retryCalls = embedCalls.drop(callsBeforeResume)
        assertTrue(resumed.indexed)
        assertEquals(1, retryCalls.size)
        assertTrue(retryCalls.single().any { it.contains(failedSource.sourceSessionId) })
        assertTrue(retryCalls.none { texts -> texts.any { it.contains(completedSource.sourceSessionId) } })
        val finalSources = db.importBatchDao().sourcesForBatch(plan.id).associateBy { it.sourceSessionId }
        assertEquals(ImportRepository.INDEX_DONE, finalSources.getValue(completedSource.sourceSessionId).indexStatus)
        assertEquals(ImportRepository.INDEX_DONE, finalSources.getValue(failedSource.sourceSessionId).indexStatus)
    }

    @Test
    fun `batch embedding cancellation stops retries and remains resumable without partial index writes`() = runBlocking {
        val zip = writeZip()
        val waitOnSecondSource = AtomicBoolean(true)
        val secondEmbeddingStarted = CompletableDeferred<Unit>()
        val embedCalls = mutableListOf<List<String>>()
        val embed = EmbedPort { texts ->
            embedCalls += texts
            if (waitOnSecondSource.get() && texts.any { it.contains("s-2") }) {
                secondEmbeddingStarted.complete(Unit)
                awaitCancellation()
            }
            texts.mapIndexed { index, _ ->
                FloatArray(4) { position -> (embedCalls.size + index + position + 1).toFloat() }
            }
        }
        val importer = buildImporter(embed = embed, embedConfigured = true, embedConsent = true)
        val inspect = importer.inspect(zip, "deepseek-export.zip")
        val plan = ImportBatchPlan(
            id = "batch-index-cancel",
            fingerprint = inspect.contentFingerprint,
            zipName = "deepseek-export.zip",
            groups = listOf(ImportBatchGroupPlan(id = "cancel-group", name = "TA 1")),
            windows = inspect.sourceWindows.map { preview ->
                ImportBatchWindowPlan(preview, "cancel-group")
            },
        )

        val importJob = launch {
            importer.importBatch(
                zip = zip,
                plan = plan,
                onProgress = {},
                onIndexProgress = { _, _ -> },
                isCancelled = { false },
            )
        }
        secondEmbeddingStarted.await()
        importJob.cancelAndJoin()
        assertTrue("正在等待 embedding 时取消协程", importJob.isCancelled)

        val repository = ImportRepository(db)
        val interruptedSources = db.importBatchDao().sourcesForBatch(plan.id).associateBy { it.sourceSessionId }
        assertEquals(ImportRepository.BATCH_CANCELLED, db.importBatchDao().batch(plan.id)?.status)
        assertEquals(ImportRepository.POST_PROCESS_PARTIAL, db.importBatchDao().batch(plan.id)?.postProcessStatus)
        assertEquals(ImportRepository.INDEX_DONE, interruptedSources.getValue("s-1").indexStatus)
        assertEquals(ImportRepository.INDEX_PENDING, interruptedSources.getValue("s-2").indexStatus)
        assertEquals(
            "同一 TA 的第一个来源已在第二个来源取消前完成并写入索引",
            1,
            db.chunkDao().forSegment(interruptedSources.getValue("s-1").segmentId).size,
        )
        assertEquals(
            "同组来源必须共享同一会话",
            1,
            interruptedSources.values.map { it.targetThreadId }.distinct().size,
        )
        assertEquals(1, embedCalls.count { texts -> texts.any { it.contains("s-1") } })
        assertEquals("取消必须打断重试", 1, embedCalls.count { texts -> texts.any { it.contains("s-2") } })
        assertTrue(
            "抛取消后不能保存该来源的关键词/向量块",
            db.chunkDao().forSegment(interruptedSources.getValue("s-2").segmentId).isEmpty(),
        )

        val resumedPlan = requireNotNull(repository.resumableBatch(inspect.contentFingerprint))
        waitOnSecondSource.set(false)
        val callsBeforeRetry = embedCalls.size
        val resumed = importer.importBatch(
            zip = zip,
            plan = resumedPlan,
            onProgress = {},
            onIndexProgress = { _, _ -> },
            isCancelled = { false },
        )

        val retryCalls = embedCalls.drop(callsBeforeRetry)
        assertEquals("续导只请求未完成来源", 1, retryCalls.size)
        assertTrue(retryCalls.all { texts -> texts.any { it.contains("s-2") } })
        assertTrue(resumed.indexed)
        assertEquals(ImportRepository.BATCH_COMPLETE, db.importBatchDao().batch(plan.id)?.status)
        val resumedSources = db.importBatchDao().sourcesForBatch(plan.id).associateBy { it.sourceSessionId }
        val resumedChunk = db.chunkDao().forSegment(resumedSources.getValue("s-2").segmentId).single()
        assertTrue(resumedChunk.dim > 0)
        assertEquals(ImportRepository.INDEX_DONE, resumedSources.getValue("s-2").indexStatus)
    }

    @Test
    fun `cooperative cancel checkpoints same source blocks and resume embeds only missing blocks`() = runBlocking {
        val sessionId = "s-many"
        val session = multiTurnSessionJson(sessionId, s1Start, pairs = ImportIndexer.EMBED_BATCH_SIZE + 1)
        val index = """[{"id":"$sessionId","inserted_at":"$s1Start","updated_at":"$s1End"}]"""
        val zip = writeZip(
            entries = listOf(
                "manifest.json" to manifestJson,
                "chat_session_index.json" to index,
                "chat_sessions/$sessionId.json" to session,
            ),
        )
        val shouldStop = AtomicBoolean(false)
        var savedBatches = 0
        val embedCalls = mutableListOf<List<String>>()
        val embed = EmbedPort { texts ->
            embedCalls += texts
            texts.mapIndexed { index, _ -> FloatArray(4) { position -> (embedCalls.size + index + position + 1).toFloat() } }
        }
        val importer = buildImporter(
            embed = embed,
            embedConfigured = true,
            embedConsent = true,
            afterBatchChunkSaved = {
                savedBatches++
                if (savedBatches == 1) shouldStop.set(true)
            },
        )
        val inspect = importer.inspect(zip, "multi-block.zip")
        val plan = ImportBatchPlan(
            id = "batch-same-source-cancel",
            fingerprint = inspect.contentFingerprint,
            zipName = "multi-block.zip",
            groups = listOf(ImportBatchGroupPlan(id = "group-many", name = "TA")),
            windows = inspect.sourceWindows.map { ImportBatchWindowPlan(it, "group-many") },
        )

        val first = importer.importBatch(zip, plan, {}, { _, _ -> }, isCancelled = { shouldStop.get() })
        val repository = ImportRepository(db)
        val firstSource = db.importBatchDao().sourcesForBatch(plan.id).single()
        val savedBeforeResume = db.chunkDao().forSegment(firstSource.segmentId)
        assertFalse(first.indexed)
        assertEquals(ImportRepository.BATCH_CANCELLED, db.importBatchDao().batch(plan.id)?.status)
        assertEquals(ImportRepository.INDEX_PENDING, firstSource.indexStatus)
        assertEquals(
            "已返回并提交的第一请求必须全部留作可靠检查点",
            ImportIndexer.EMBED_BATCH_SIZE,
            savedBeforeResume.size,
        )
        assertTrue(savedBeforeResume.all { it.dim > 0 })
        assertEquals(1, embedCalls.size)
        assertEquals(ImportIndexer.EMBED_BATCH_SIZE, embedCalls.single().size)

        shouldStop.set(false)
        val resumed = importer.importBatch(
            zip,
            requireNotNull(repository.resumableBatch(inspect.contentFingerprint)),
            {},
            { _, _ -> },
            isCancelled = { shouldStop.get() },
        )

        val retryCalls = embedCalls.drop(1)
        assertTrue(resumed.indexed)
        assertEquals("同来源已持久化向量块续跑不应再次请求", 1, retryCalls.size)
        assertEquals("只请求未完成块", 1, retryCalls.single().size)
        assertTrue(retryCalls.flatten().none { it.contains("message-0") || it.contains("message-1") })
        val finalSource = db.importBatchDao().sourcesForBatch(plan.id).single()
        assertEquals(ImportRepository.INDEX_DONE, finalSource.indexStatus)
        assertEquals(ImportIndexer.EMBED_BATCH_SIZE + 1, db.chunkDao().forSegment(finalSource.segmentId).size)
        assertTrue(db.chunkDao().forSegment(finalSource.segmentId).all { it.dim == 4 })
    }

    @Test
    fun `batch status write error closes committed chat as resumable partial`() = runBlocking {
        val zip = writeZip()
        val inspect = buildImporter().inspect(zip, "status-failure.zip")
        var writes = 0
        val importer = buildImporter(
            batchSourceIndexStatusWriter = { _, _, _ ->
                writes++
                throw IllegalStateException("synthetic status write failure")
            },
        )
        val plan = ImportBatchPlan(
            id = "batch-index-status-failure",
            fingerprint = inspect.contentFingerprint,
            zipName = "status-failure.zip",
            groups = listOf(ImportBatchGroupPlan(id = "group-status-failure", name = "TA")),
            windows = inspect.sourceWindows.map { ImportBatchWindowPlan(it, "group-status-failure") },
        )

        val outcome = importer.importBatch(zip, plan, {}, { _, _ -> }, isCancelled = { false })

        assertEquals(1, writes)
        assertFalse("索引状态落库失败必须报告索引未完成", outcome.indexed)
        assertEquals(ImportRepository.BATCH_PARTIAL, db.importBatchDao().batch(plan.id)?.status)
        assertEquals(ImportRepository.POST_PROCESS_PARTIAL, db.importBatchDao().batch(plan.id)?.postProcessStatus)
        val sources = db.importBatchDao().sourcesForBatch(plan.id)
        assertEquals(ImportRepository.INDEX_DONE, sources.first().indexStatus)
        assertEquals(ImportRepository.INDEX_PENDING, sources.last().indexStatus)
        assertEquals("已提交的聊天仍保留", 4, sources.map { it.targetThreadId }.distinct().sumOf { db.messageDao().allForThread(it).size })
    }

    private companion object {
        /** 测试用的「找旧事」模型名（与聊天模型分开，断言 chunk.model 用）。 */
        const val TEST_EMBED_MODEL: String = "test-embed"
        const val TEST_EMBED_CONFIG_FINGERPRINT: String = "synthetic-endpoint-and-model-fingerprint"
    }
}
