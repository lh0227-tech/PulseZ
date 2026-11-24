package cc.pulsez.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.AttachmentEntity
import cc.pulsez.app.data.db.AttachmentStatus
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
import cc.pulsez.app.data.repo.ImportRepository
import cc.pulsez.app.importer.BackupInspection
import cc.pulsez.app.importer.BackupParser
import cc.pulsez.app.importer.Source
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证导出字段契约与孤儿过滤。
 *
 * Robolectric + 内存 Room（设施照 `AttachmentExportTest`）。覆盖：
 * - 新增字段逐项与实体一致（含可空字段 null/有值两态、`styleSamples` 的 `\u0001` 串与 null 两态）；
 * - 单聊 `characterId` = 角色 id / 群聊 = JSON `null`（不是 `""`）；既有 `""`/`0` 哨兵零变化；
 * - 外键孤儿会话整段跳过、其附件不导出、`skippedOrphanThreads` 计数；
 * - `exportTo` 把孤儿跳过数随 `Success` 上抛、文件完整；
 * - **端到端**：真实 `buildJson` 输出直接喂 `BackupParser.inspect` → Preview（禁止手写 JSON 冒充）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExportBackupFieldsTest {

    private lateinit var context: Context
    private lateinit var db: PulsezDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(APP_DB_NAME)
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(APP_DB_NAME)
    }

    // ---------------------------------------------------------------- 种子

    private suspend fun seedCharacter(id: String, styleSamples: String?, deletedAt: Long? = null) {
        db.characterDao().upsert(
            CharacterEntity(
                id = id,
                name = "角色-$id",
                avatarUri = null,
                persona = "人设-$id",
                greeting = null,
                styleSamples = styleSamples,
                userName = null,
                systemPromptOverride = null,
                protocolEnabled = true,
                sourceType = "MANUAL",
                sourceRef = null,
                rawImported = null,
                emoAssetDir = null,
                createdAt = 1L,
                updatedAt = 2L,
                deletedAt = deletedAt,
            ),
        )
    }

    private suspend fun seedThread(id: String, characterId: String?, kind: String, lastMessageAt: Long) {
        db.threadDao().upsert(
            ThreadEntity(
                id = id,
                characterId = characterId,
                title = null,
                createdAt = 10L,
                lastMessageAt = lastMessageAt,
                activeSegmentId = null,
                segmentOrdinal = 0,
                totalMessages = 0,
                totalTokens = 0,
                archived = false,
                kind = kind,
            ),
        )
    }

    private suspend fun seedMessage(
        id: String,
        threadId: String,
        segmentId: String,
        createdAt: Long,
        speakerCharacterId: String? = null,
    ) {
        db.messageDao().upsert(
            MessageEntity(
                id = id,
                threadId = threadId,
                segmentId = segmentId,
                role = "assistant",
                content = "内容-$id",
                tokens = 3,
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

    private suspend fun seedAttachment(id: String, messageId: String?, fileName: String?) {
        db.attachmentDao().upsert(
            AttachmentEntity(
                id = id,
                messageId = messageId,
                kind = "IMAGE",
                mime = "image/jpeg",
                localPath = "attachments/$id.jpg",
                fileName = fileName,
                sizeBytes = 10L,
                width = null,
                height = null,
                status = AttachmentStatus.SENT,
                errorKind = null,
                attempts = 1,
                remoteFileId = null,
                expiresAt = null,
                deletedAt = null,
                createdAt = 10L,
            ),
        )
    }

    /**
 * 核心种子：两个活跃角色（styleSamples 有值/为 null 两态）、两条单聊、一个群聊、
 * 一段含全部新增字段的 segment、两条记忆（null/有值两态）、消息、附件。
 *
 * @param withOrphan 追加「软删角色 + 其会话 + 消息 + 附件」的外键孤儿子种。
 */
    private suspend fun seed(withOrphan: Boolean) {
        seedCharacter("char-a", styleSamples = "样一\u0001样二")
        seedCharacter("char-b", styleSamples = null)

        // 单聊 A
        seedThread("thread-a", "char-a", ThreadKind.SINGLE, lastMessageAt = 300L)
        db.segmentDao().upsert(
            SegmentEntity(
                id = "seg-a",
                threadId = "thread-a",
                ordinal = 0,
                summary = null,
                styleNotes = null,
                firstMessageAt = 100L,
                lastMessageAt = 200L,
                tokenSum = 7,
                state = "OPEN",
                batonReason = "换段原因",
                createdAt = 10L,
            ),
        )
        seedMessage("m-a1", "thread-a", "seg-a", createdAt = 100L)
        seedMessage("m-a2", "thread-a", "seg-a", createdAt = 200L)
        db.memoryDao().insertAll(listOf(
            MemoryItemEntity(
                id = "mem-full",
                threadId = "thread-a",
                kind = "FACT",
                key = "喜好",
                value = "喜欢下雨天",
                confidence = 0.5f,
                importance = 4,
                happenedAt = 1_000L,
                dueAt = 2_000L,
                validFrom = 900L,
                validTo = 1_500L,
                firstSeenAt = 800L,
                lastSeenAt = 1_600L,
                sourceSegmentId = null,
                sourceMessageIds = null,
                status = "ACTIVE",
                supersededBy = null,
                characterId = "char-a",
            ),
        ))
        db.memoryDao().insertAll(listOf(
            MemoryItemEntity(
                id = "mem-null",
                threadId = "thread-a",
                kind = "PREF",
                key = null,
                value = "晚上更精神",
                confidence = 0.25f,
                importance = 2,
                happenedAt = null,
                dueAt = null,
                validFrom = 1_000L,
                validTo = null,
                firstSeenAt = 1_100L,
                lastSeenAt = 1_200L,
                sourceSegmentId = null,
                sourceMessageIds = null,
                status = "ACTIVE",
                supersededBy = null,
                characterId = null,
            ),
        ))
        seedAttachment("att-a", messageId = "m-a1", fileName = null)

        // 单聊 B
        seedThread("thread-b", "char-b", ThreadKind.SINGLE, lastMessageAt = 400L)
        seedMessage("m-b1", "thread-b", "seg-b", createdAt = 400L)

        // 群聊（characterId = null；成员与发言人归属）
        seedThread("thread-g", null, ThreadKind.GROUP, lastMessageAt = 500L)
        db.threadMemberDao().insertAll(
            listOf(
                ThreadMemberEntity(threadId = "thread-g", characterId = "char-a", ordinal = 0, joinedAt = 10L),
                ThreadMemberEntity(threadId = "thread-g", characterId = "char-b", ordinal = 1, joinedAt = 10L),
            ),
        )
        seedMessage("m-g1", "thread-g", "seg-g", createdAt = 500L, speakerCharacterId = "char-b")
        seedAttachment("att-g", messageId = "m-g1", fileName = "群图.jpg")

        if (withOrphan) {
            // 软删角色 + 其会话：threadDao.active 不 join 角色，导出时必须按角色集合过滤掉。
            seedCharacter("char-orphan", styleSamples = null, deletedAt = 123L)
            seedThread("thread-orphan", "char-orphan", ThreadKind.SINGLE, lastMessageAt = 50L)
            seedMessage("m-orphan", "thread-orphan", "seg-orphan", createdAt = 50L)
            seedAttachment("att-orphan", messageId = "m-orphan", fileName = "孤儿.jpg")
        }
    }

    private suspend fun seedImportBatch(memoryFingerprint: String) {
        db.importBatchDao().upsertBatch(
            ImportBatchEntity(
                id = "batch-a",
                fingerprint = "archive.zip:123",
                zipName = "archive.zip",
                createdAt = 20L,
                status = ImportRepository.BATCH_RUNNING,
                postProcessStatus = ImportRepository.POST_PROCESS_PENDING,
            ),
        )
        db.importBatchDao().upsertGroup(
            ImportBatchGroupEntity(
                id = "group-a",
                batchId = "batch-a",
                targetCharacterId = "char-a",
                targetThreadId = "thread-a",
                isNewCharacter = false,
                name = "角色-char-a",
                persona = null,
                status = ImportRepository.GROUP_SUCCESS,
                memoryFingerprint = memoryFingerprint,
                beforeLastMessageAt = 100L,
                beforeLastExtractAt = 0L,
                beforeCharacterUpdatedAt = 2L,
                importLastMessageAt = 200L,
                importMessageCount = 2,
                errorCode = null,
            ),
        )
        db.importBatchDao().upsertWindows(
            listOf(
                ImportBatchWindowEntity(
                    batchId = "batch-a",
                    sourceSessionId = "source-a",
                    entryName = "chat_sessions/source-a.json",
                    title = "窗口标题",
                    sourceStartedAt = 100L,
                    messageCount = 2,
                    damageCode = null,
                    groupId = "group-a",
                    status = ImportRepository.WINDOW_IMPORTED,
                    errorCode = null,
                ),
            ),
        )
        db.importBatchDao().upsertSources(
            listOf(
                ImportSourceSessionEntity(
                    id = "source-row-a",
                    batchId = "batch-a",
                    targetCharacterId = "char-a",
                    targetThreadId = "thread-a",
                    sourceSessionId = "source-a",
                    title = "窗口标题",
                    sourceStartedAt = 100L,
                    messageCount = 2,
                    segmentId = "seg-a",
                    indexStatus = ImportRepository.INDEX_DONE,
                ),
            ),
        )
    }

    // ---------------------------------------------------------------- 辅助

    private fun root(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun thread(root: JsonObject, id: String): JsonObject =
        root.getValue("thread").jsonArray.map { it.jsonObject }.first { it.getValue("id").jsonPrimitive.content == id }

    private fun message(thread: JsonObject, id: String): JsonObject =
        thread.getValue("messages").jsonArray.map { it.jsonObject }.first { it.getValue("id").jsonPrimitive.content == id }

    private fun memory(thread: JsonObject, value: String): JsonObject =
        thread.getValue("memories").jsonArray.map { it.jsonObject }
            .first { it.getValue("value").jsonPrimitive.content == value }

    // ---------------------------------------------------------------- / ：新增字段与既有空值

    @Test
    fun `新增字段逐项与实体一致 既有空值表示零变化`() = runBlocking {
        seed(withOrphan = false)
        val json = ExportService.buildJson(db)
        val root = root(json)

        val charA = root.getValue("character").jsonArray.map { it.jsonObject }
            .first { it.getValue("id").jsonPrimitive.content == "char-a" }
        assertEquals("styleSamples 的 u0001 串要原样导出", "样一\u0001样二", charA.getValue("styleSamples").jsonPrimitive.content)
        assertEquals("既有 greeting 仍写空串", "", charA.getValue("greeting").jsonPrimitive.content)
        assertEquals("既有 userName 仍写空串", "", charA.getValue("userName").jsonPrimitive.content)

        val charB = root.getValue("character").jsonArray.map { it.jsonObject }
            .first { it.getValue("id").jsonPrimitive.content == "char-b" }
        assertEquals("styleSamples 为 null 时写 JSON null（不是空串）", JsonNull, charB.getValue("styleSamples"))

        val threadA = thread(root, "thread-a")
        val msg = message(threadA, "m-a1")
        assertEquals("seg-a", msg.getValue("segmentId").jsonPrimitive.content)
        assertEquals(3, msg.getValue("tokens").jsonPrimitive.int)
        assertEquals("既有 speakerCharacterId 仍写空串", "", msg.getValue("speakerCharacterId").jsonPrimitive.content)

        val segment = threadA.getValue("segments").jsonArray.single().jsonObject
        assertEquals(100L, segment.getValue("firstMessageAt").jsonPrimitive.long)
        assertEquals(200L, segment.getValue("lastMessageAt").jsonPrimitive.long)
        assertEquals(7, segment.getValue("tokenSum").jsonPrimitive.int)
        assertEquals("换段原因", segment.getValue("batonReason").jsonPrimitive.content)
        assertEquals("既有 summary 仍写空串", "", segment.getValue("summary").jsonPrimitive.content)

        val full = memory(threadA, "喜欢下雨天")
        assertEquals(0.5, full.getValue("confidence").jsonPrimitive.double, 0.0)
        assertEquals(4, full.getValue("importance").jsonPrimitive.int)
        assertEquals(1_000L, full.getValue("happenedAt").jsonPrimitive.long)
        assertEquals(2_000L, full.getValue("dueAt").jsonPrimitive.long)
        assertEquals(800L, full.getValue("firstSeenAt").jsonPrimitive.long)
        assertEquals(1_600L, full.getValue("lastSeenAt").jsonPrimitive.long)
        assertEquals("char-a", full.getValue("characterId").jsonPrimitive.content)
        assertEquals("既有 key 有值时原样", "喜好", full.getValue("key").jsonPrimitive.content)

        val nullMemory = memory(threadA, "晚上更精神")
        assertEquals("happenedAt 缺省写 JSON null（不写 0）", JsonNull, nullMemory.getValue("happenedAt"))
        assertEquals("dueAt 缺省写 JSON null（不写 0）", JsonNull, nullMemory.getValue("dueAt"))
        assertEquals("既有 validTo null → 0 哨兵不变", 0, nullMemory.getValue("validTo").jsonPrimitive.int)
        assertEquals("既有 key null → 空串不变", "", nullMemory.getValue("key").jsonPrimitive.content)
        assertEquals("既有 memory.characterId null → 空串不变", "", nullMemory.getValue("characterId").jsonPrimitive.content)

        // 附件既有空值：fileName null → ""。
        val attachment = root.getValue("attachments").jsonArray.map { it.jsonObject }
            .first { it.getValue("messageId").jsonPrimitive.content == "m-a1" }
        assertEquals("", attachment.getValue("fileName").jsonPrimitive.content)

        // 群聊标题 null → 空串。
        assertEquals("", thread(root, "thread-g").getValue("title").jsonPrimitive.content)
    }

    // ---------------------------------------------------------------- ：thread.characterId 两态

    @Test
    fun `单聊 characterId 是角色 id 群聊是 JSON null`() = runBlocking {
        seed(withOrphan = false)
        val root = root(ExportService.buildJson(db))

        assertEquals("char-a", thread(root, "thread-a").getValue("characterId").jsonPrimitive.content)
        val group = thread(root, "thread-g")
        assertTrue("群聊必须保留 characterId 键", group.containsKey("characterId"))
        assertEquals("群聊 characterId 必须是 JSON null（不是空串）", JsonNull, group.getValue("characterId"))
        assertFalse(
            "群聊 characterId 不得被统一成空串（JSON 文本里不能是 \"\"）",
            group.getValue("characterId").toString() == "\"\"",
        )
    }

    // ---------------------------------------------------------------- / ：schema、孤儿过滤、附件

    @Test
    fun `schema 仍为 3 且顶层必需键保留`() = runBlocking {
        seed(withOrphan = false)
        val root = root(ExportService.buildJson(db))
        assertEquals(3, ExportService.SCHEMA)
        assertEquals(3, root.getValue("schema").jsonPrimitive.int)
        assertEquals("pulsez", root.getValue("app").jsonPrimitive.content)
        assertTrue(root.containsKey("exportedAt"))
        listOf("character", "thread", "attachments", "importBatches", "skippedOrphanThreads").forEach {
            assertTrue("顶层必须有 $it", root.containsKey(it))
        }
        assertEquals(0, root.getValue("skippedOrphanThreads").jsonPrimitive.int)
    }

    @Test
    fun `导出批次分组窗口来源且运行态转为可续导状态`() = runBlocking {
        seed(withOrphan = false)
        val fingerprint = ImportRepository(db).memoryFingerprintForThread("thread-a")
        seedImportBatch(fingerprint)

        val root = root(ExportService.buildJson(db))
        val batch = root.getValue("importBatches").jsonArray.single().jsonObject
        assertEquals("batch-a", batch.getValue("id").jsonPrimitive.content)
        assertEquals("archive.zip:123", batch.getValue("fingerprint").jsonPrimitive.content)
        assertEquals("CANCELLED", batch.getValue("status").jsonPrimitive.content)
        val group = batch.getValue("groups").jsonArray.single().jsonObject
        assertEquals("char-a", group.getValue("targetCharacterId").jsonPrimitive.content)
        assertEquals("thread-a", group.getValue("targetThreadId").jsonPrimitive.content)
        assertEquals("true", group.getValue("memoryUndoSafe").jsonPrimitive.content)
        assertEquals("窗口标题", batch.getValue("windows").jsonArray.single().jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("seg-a", batch.getValue("sources").jsonArray.single().jsonObject.getValue("segmentId").jsonPrimitive.content)
        assertFalse("批次元数据不应复制聊天正文", batch.toString().contains("内容-m-a1"))
    }

    @Test
    fun `源记忆指纹不匹配时导出撤销不安全标记`() = runBlocking {
        seed(withOrphan = false)
        seedImportBatch("stale-memory-fingerprint")

        val root = root(ExportService.buildJson(db))
        val group = root.getValue("importBatches").jsonArray.single().jsonObject
            .getValue("groups").jsonArray.single().jsonObject
        assertEquals("false", group.getValue("memoryUndoSafe").jsonPrimitive.content)
    }

    @Test
    fun `已无活动消息的导入来源导出为已撤销且不留下悬挂归属`() = runBlocking {
        seed(withOrphan = false)
        val fingerprint = ImportRepository(db).memoryFingerprintForThread("thread-a")
        seedImportBatch(fingerprint)
        db.messageDao().allForThread("thread-a")
            .filter { it.segmentId == "seg-a" }
            .forEach { db.messageDao().upsert(it.copy(deletedAt = 999L)) }

        val batch = root(ExportService.buildJson(db)).getValue("importBatches").jsonArray.single().jsonObject
        assertTrue(batch.getValue("sources").jsonArray.isEmpty())
        assertEquals(
            ImportRepository.WINDOW_UNDONE,
            batch.getValue("windows").jsonArray.single().jsonObject.getValue("status").jsonPrimitive.content,
        )
        val group = batch.getValue("groups").jsonArray.single().jsonObject
        assertEquals(0, group.getValue("importMessageCount").jsonPrimitive.int)
        assertEquals(JsonNull, group.getValue("importLastMessageAt"))
    }

    @Test
    fun `孤儿会话整段跳过 附件不导出 群聊与正常单聊不受影响`() = runBlocking {
        seed(withOrphan = true)
        val json = ExportService.buildJson(db)
        val root = root(json)

        assertFalse("孤儿会话不得出现在导出里", json.contains("thread-orphan"))
        assertFalse("孤儿会话的消息不得出现", json.contains("m-orphan"))
        assertFalse("孤儿会话的附件字幕（文件名）不得出现", json.contains("孤儿.jpg"))
        assertEquals("跳过数必须正确", 1, root.getValue("skippedOrphanThreads").jsonPrimitive.int)
        assertEquals("正常会话（2 个单聊 + 1 个群聊）仍要导出", 3, root.getValue("thread").jsonArray.size)
        assertEquals("群聊不受影响", 1, root.getValue("thread").jsonArray.map { it.jsonObject }
            .count { it.getValue("kind").jsonPrimitive.content == ThreadKind.GROUP })
        assertTrue("正常单聊不受影响", root.getValue("thread").jsonArray.map { it.jsonObject }
            .any { it.getValue("id").jsonPrimitive.content == "thread-a" })
        // 附件只保留「消息确实被导出」的两条。
        val messageIds = root.getValue("attachments").jsonArray.map {
            it.jsonObject.getValue("messageId").jsonPrimitive.content
        }
        assertTrue("正常附件保留", messageIds.contains("m-a1") && messageIds.contains("m-g1"))
        assertFalse("孤儿附件被过滤（messageId 不在导出消息集合）", messageIds.contains("m-orphan"))
    }

    // ---------------------------------------------------------------- X1：Success 上抛 + 文件完整

    @Test
    fun `exportTo 把孤儿跳过数随 Success 上抛 且文件完整`() = runBlocking {
        seed(withOrphan = true)
        val out = ByteArrayOutputStream()
        val result = ExportService.exportTo(db) { out }
        val success = result as? ExportResult.Success
        requireNotNull(success) { "应导出成功：$result" }
        assertEquals("超限不应误报", emptyList<ExportOverLimit>(), success.overLimit)
        assertEquals("孤儿跳过数必须随结果上抛（X1）", 1, success.skippedOrphanThreads)

        val json = out.toString(Charsets.UTF_8.name())
        val root = root(json)
        assertEquals(3, root.getValue("thread").jsonArray.size)
        assertEquals(1, root.getValue("skippedOrphanThreads").jsonPrimitive.int)
        // 文件完整：能再次解析且是预览。
        assertTrue(BackupParser.inspect(json, Source.FILE) is BackupInspection.Preview)
    }

    @Test
    fun `无孤儿时 skippedOrphanThreads 为 0`() = runBlocking {
        seed(withOrphan = false)
        val out = ByteArrayOutputStream()
        val success = ExportService.exportTo(db) { out } as? ExportResult.Success
        requireNotNull(success) { "应导出成功" }
        assertEquals(0, success.skippedOrphanThreads)
    }

    // ---------------------------------------------------------------- / ：真实导出 → 识别

    @Test
    fun `真实 buildJson 输出可被 BackupParser 识别为预览 计数与种子一致`() = runBlocking {
        seed(withOrphan = true)
        val json = ExportService.buildJson(db)

        val inspection = BackupParser.inspect(json, Source.FILE)
        val preview = (inspection as? BackupInspection.Preview)?.preview
        requireNotNull(preview) { "真实导出必须被识别为备份预览，而不是 Normal/Rejected：$inspection" }
        assertEquals(3, preview.schema)
        assertEquals("两个活跃角色（软删的不算）", 2, preview.characterCount)
        assertEquals("3 段会话（2 单聊 + 1 群聊）", 3, preview.threadCount)
        assertEquals("m-a1/m-a2/m-b1/m-g1 = 4 条", 4, preview.messageCount)
        assertEquals("att-a/att-g = 2 条", 2, preview.attachmentCount)
        assertEquals("孤儿跳过数随备份带出", 1, preview.skippedOrphanThreads)

        // ExportPayload 的计数与真实字节数（UTF-8 字节，不是 String.length）。
        // 注意：exportedAt 是「导出当时」的时间，另跑一次 buildPayload 内容会差这一项，
        // 所以这里只比规模与字节数、不比整串。
        val payload = ExportService.buildPayload(db)
        assertEquals(json.toByteArray(Charsets.UTF_8).size, payload.byteSize)
        assertTrue(
            "byteSize 必须是 UTF-8 字节数，不是 String.length（种子含中文，字节数应更大）",
            payload.byteSize > payload.json.length,
        )
        assertEquals(2, payload.characterCount)
        assertEquals(3, payload.threadCount)
        assertEquals(4, payload.messageCount)
        assertEquals(1, payload.skippedOrphanThreads)
        assertEquals(3, root(payload.json).getValue("schema").jsonPrimitive.int)
    }

    private companion object {
        /** `AppContainer` 里 Room 的库名是写死的（`PulsezDatabase.create`）。 */
        const val APP_DB_NAME = "pulsez.db"
    }
}
