package cc.pulsez.app.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.core.ConsolidatePlan
import cc.pulsez.app.core.ConsolidateRange
import cc.pulsez.app.core.ConsolidateResult
import cc.pulsez.app.core.ImportIndexer
import cc.pulsez.app.core.ImportMemoryConsolidator
import cc.pulsez.app.core.ImportMessage
import cc.pulsez.app.data.db.CharacterEntity
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.SegmentEntity
import cc.pulsez.app.data.db.ThreadEntity
import cc.pulsez.app.data.db.ThreadKind
import cc.pulsez.app.data.settings.ImportPrefs
import cc.pulsez.app.importer.deepseek.DeepSeekImporter
import cc.pulsez.app.importer.deepseek.DeepSeekSessionPreview
import cc.pulsez.app.importer.deepseek.DeepSeekTurn
import cc.pulsez.app.importer.deepseek.ParsedDeepSeekSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportStyleAndPlanTest {

    private lateinit var db: PulsezDatabase
    private lateinit var repo: ImportRepository
    private lateinit var importPrefs: ImportPrefs
    private val tempFiles = mutableListOf<File>()

    private val baseAt = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ImportRepository(db)
        importPrefs = ImportPrefs(context)
        runBlocking { importPrefs.clearAll() }
    }

    @After
    fun tearDown() {
        db.close()
        tempFiles.forEach { it.delete() }
    }

    private fun parsedSession(
        id: String,
        assistantLines: List<String>,
    ): ParsedDeepSeekSession {
        val turns = mutableListOf<DeepSeekTurn>()
        assistantLines.forEachIndexed { idx, line ->
            val msgId = (idx * 2 + 1).toLong()
            turns += DeepSeekTurn(DeepSeekTurn.ROLE_USER, "问 $idx", baseAt + idx * 2_000L, msgId)
            turns += DeepSeekTurn(DeepSeekTurn.ROLE_ASSISTANT, line, baseAt + idx * 2_000L + 1_000L, msgId + 1)
        }
        return ParsedDeepSeekSession(
            sourceSessionId = id,
            title = "会话-$id",
            insertedAt = baseAt,
            updatedAt = baseAt + 10_000L,
            turns = turns,
            issues = emptyList(),
            sourceStartedAt = baseAt,
            sourceMessageCount = turns.size,
            lastMessageAt = turns.last().at,
        )
    }

    private fun preview(id: String) = DeepSeekSessionPreview(
        entryName = "chat_sessions/$id.json",
        sourceSessionId = id,
        title = "会话-$id",
        sourceStartedAt = baseAt,
        messageCount = 2,
        lastMessageAt = baseAt + 1_000L,
    )

    @Test
    fun `commitBatchGroup writes userName and explicit or extracted styleSamples and stays undoable`() = runBlocking {
        val plan = ImportBatchPlan(
            id = "batch-style-1",
            fingerprint = "fp-1",
            zipName = "export.zip",
            groups = listOf(
                ImportBatchGroupPlan(
                    id = "g-explicit",
                    name = "沈淮",
                    persona = "理性温和",
                    userName = "阿宁",
                    styleSamples = "先坐下。\u0001别着急，慢慢来。",
                ),
                ImportBatchGroupPlan(
                    id = "g-extract",
                    name = "江澈",
                    persona = "话少护短",
                    userName = null,
                    styleSamples = null,
                ),
            ),
            windows = listOf(
                ImportBatchWindowPlan(preview("s-explicit"), "g-explicit"),
                ImportBatchWindowPlan(preview("s-extract"), "g-extract"),
            ),
            awakenOnImport = true,
        )
        repo.beginBatch(plan)

        val explicitRes = repo.commitBatchGroup(
            batchId = plan.id,
            groupId = "g-explicit",
            sessions = listOf(parsedSession("s-explicit", listOf("回声一句"))),
            userName = "阿宁",
            styleSamples = "先坐下。\u0001别着急，慢慢来。",
        )
        val extractRes = repo.commitBatchGroup(
            batchId = plan.id,
            groupId = "g-extract",
            sessions = listOf(parsedSession("s-extract", listOf("第一句台词。", "第二句台词。", "第三句台词。"))),
            userName = null,
            styleSamples = null,
        )
        repo.finishBatch(plan.id, ImportRepository.BATCH_COMPLETE, ImportRepository.POST_PROCESS_DONE)

        val explicitChar = db.characterDao().byId(explicitRes.characterId!!)!!
        assertEquals("阿宁", explicitChar.userName)
        assertEquals("先坐下。\u0001别着急，慢慢来。", explicitChar.styleSamples)

        val extractChar = db.characterDao().byId(extractRes.characterId!!)!!
        assertNull(extractChar.userName)
        assertEquals("第一句台词。\u0001第二句台词。\u0001第三句台词。", extractChar.styleSamples)

        repo.undoBatch(plan.id)
        assertNull(db.characterDao().byId(explicitRes.characterId!!))
        assertNull(db.characterDao().byId(extractRes.characterId!!))
    }

    @Test
    fun `commitBatchGroup preserves non-blank existing character fields and undo keeps existing chat intact`() = runBlocking {
        val existingCharId = "existing-char"
        val existingThreadId = "existing-thread"
        val openSegId = "existing-open-seg"
        db.characterDao().upsert(
            CharacterEntity(
                id = existingCharId,
                name = "旧角色",
                avatarUri = null,
                persona = "原人设",
                greeting = null,
                styleSamples = "已有样本",
                userName = null,
                systemPromptOverride = null,
                protocolEnabled = true,
                sourceType = "MANUAL",
                sourceRef = null,
                rawImported = null,
                emoAssetDir = null,
                createdAt = baseAt,
                updatedAt = baseAt,
                deletedAt = null,
            ),
        )
        db.threadDao().upsert(
            ThreadEntity(
                id = existingThreadId,
                characterId = existingCharId,
                title = null,
                createdAt = baseAt,
                lastMessageAt = baseAt,
                activeSegmentId = openSegId,
                segmentOrdinal = 0,
                totalMessages = 1,
                totalTokens = 10,
                lastExtractAt = 0L,
                archived = false,
                kind = ThreadKind.SINGLE,
            ),
        )
        db.segmentDao().upsert(
            SegmentEntity(
                id = openSegId,
                threadId = existingThreadId,
                ordinal = 0,
                summary = null,
                styleNotes = null,
                firstMessageAt = baseAt,
                lastMessageAt = baseAt,
                tokenSum = 10,
                state = ImportRepository.SEGMENT_STATE_OPEN,
                batonReason = null,
                createdAt = baseAt,
            ),
        )
        db.messageDao().insertAllIgnore(
            listOf(
                MessageEntity(
                    id = "existing-msg-1",
                    threadId = existingThreadId,
                    segmentId = openSegId,
                    role = "user",
                    content = "原有聊天",
                    tokens = 4,
                    emotion = null,
                    status = "OK",
                    draft = null,
                    usageIn = null,
                    usageOut = null,
                    previousContent = null,
                    createdAt = baseAt,
                    deletedAt = null,
                    speakerCharacterId = null,
                ),
            ),
        )

        val plan = ImportBatchPlan(
            id = "batch-existing-1",
            fingerprint = "fp-existing",
            zipName = "export.zip",
            groups = listOf(
                ImportBatchGroupPlan(
                    id = "g-existing",
                    targetCharacterId = existingCharId,
                    name = "旧角色",
                    userName = "小周",
                    styleSamples = "不该覆盖已有样本",
                ),
            ),
            windows = listOf(ImportBatchWindowPlan(preview("s-add"), "g-existing")),
        )
        repo.beginBatch(plan)
        repo.commitBatchGroup(
            batchId = plan.id,
            groupId = "g-existing",
            sessions = listOf(parsedSession("s-add", listOf("新增回复"))),
            userName = "小周",
            styleSamples = "不该覆盖已有样本",
        )
        repo.finishBatch(plan.id, ImportRepository.BATCH_COMPLETE, ImportRepository.POST_PROCESS_DONE)

        val updated = db.characterDao().byId(existingCharId)!!
        assertEquals("小周", updated.userName)
        assertEquals("已有样本", updated.styleSamples)
        assertNotNull(db.threadDao().byId(existingThreadId))
        assertEquals(1, db.messageDao().bySegment(openSegId).size)

        repo.undoBatch(plan.id)
        assertNotNull(db.characterDao().byId(existingCharId))
        assertEquals(1, db.messageDao().bySegment(openSegId).size)
        assertEquals(1, db.threadDao().byId(existingThreadId)!!.totalMessages)
    }

    @Test
    fun `DeepSeekImporter importBatch propagates userName styleSamples and awakenOnImport into ImportBatchResult`() = runBlocking {
        val sessionJson = """
            {
              "id": "sess-zip-1",
              "title": "夜晚通话",
              "current_message_id": 2,
              "inserted_at": "2026-11-20T10:00:00Z",
              "messages": [
                {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED", "inserted_at": "2026-11-20T10:00:00Z", "fragments": [{"type": "REQUEST", "content": "睡了吗"}]},
                {"id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED", "inserted_at": "2026-11-20T10:01:00Z", "fragments": [{"type": "RESPONSE", "content": "还没，在等你消息。"}]}
              ]
            }
        """.trimIndent()
        val zipFile = createZip(
            "manifest.json" to """{"export_format_version":3}""",
            "chat_sessions/sess-zip-1.json" to sessionJson,
        )
        val importer = DeepSeekImporter(
            repo = repo,
            prefs = importPrefs,
            indexer = ImportIndexer(
                loadMessages = { emptyList() },
                saveChunks = { _, _ -> },
            ),
            consolidator = ImportMemoryConsolidator(
                loadMessages = { emptyList<ImportMessage>() },
                applyPatch = { _, _ -> false },
                completeJson = { _, _ -> null },
                recordUsage = { _, _ -> },
                costOf = { _, _ -> 0.0 },
                loadProgress = { null },
                saveProgress = {},
                clearProgress = {},
            ),
            database = db,
        )
        val inspect = importer.inspect(zipFile, "export.zip")
        val plan = ImportBatchPlan(
            id = "batch-importer-1",
            fingerprint = inspect.contentFingerprint,
            zipName = "export.zip",
            groups = listOf(
                ImportBatchGroupPlan(
                    id = "g-1",
                    name = "程野",
                    persona = "直率热心",
                    userName = "阿星",
                    styleSamples = "别熬太晚。\u0001明天我提醒你。",
                ),
            ),
            windows = listOf(ImportBatchWindowPlan(inspect.sourceWindows.single(), "g-1")),
            awakenOnImport = true,
        )

        val outcome = importer.importBatch(
            zip = zipFile,
            plan = plan,
            onProgress = {},
            onIndexProgress = { _, _ -> },
            isCancelled = { false },
        )

        val batch = outcome.batch!!
        assertTrue(batch.awakenOnImport)
        assertEquals(1, batch.createdOrUpdatedCharacterIds.size)
        val created = db.characterDao().byId(batch.createdOrUpdatedCharacterIds.single())!!
        assertEquals("程野", created.name)
        assertEquals("阿星", created.userName)
        assertEquals("别熬太晚。\u0001明天我提醒你。", created.styleSamples)
    }

    private fun createZip(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("pulsez-wp2-", ".zip")
        tempFiles += file
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        file.writeBytes(bytes.toByteArray())
        return file
    }
}
