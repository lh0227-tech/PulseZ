package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentEntity
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.db.CharacterEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.SegmentEntity
import cc.pulsez.app.data.db.ThreadEntity
import cc.pulsez.app.data.db.ThreadKind
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 测试附件导出契约：schema 版本、白名单元数据、敏感字段过滤和软删除过滤。
 *
 * 同时验证 `clearAll` 后 Room 无数据且附件目录为空。`buildJson(db)` 使用内存 Room；
 * `clearAll(container)` 使用 Robolectric、真实文件数据库和注入的临时附件目录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentExportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: PulsezDatabase

    /** 安装临时 `AttachmentStore` 前保存进程级实例，供 `@After` 恢复。 */
    private var previousStore: AttachmentStore? = null

    @Before
    fun setUp() {
        previousStore = AttachmentStore.installedOrNull()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(APP_DB_NAME)
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        // 测试卫生：复原进程级 AttachmentStore，避免临时目录的实例泄漏给同 sandbox 后续测试。
        val previous = previousStore
        if (previous != null) AttachmentStore.install(previous) else AttachmentStore.resetForTest()
        db.close()
        context.deleteDatabase(APP_DB_NAME)
    }

    // ------------------------------------------------------------------
    // 1. 导出 schema=3 + 附件只带白名单元数据（不含路径/服务商句柄）
    // ------------------------------------------------------------------

    @Test
    fun `导出 schema 为 3 且附件只带白名单元数据`() = runBlocking {
        seedCharacterThreadAndMessage(db)
        val now = 1_700_000_000_000L

        // 未软删、带 localPath 与 remoteFileId（后两者都不能出现在导出 JSON 里）
        db.attachmentDao().upsert(
            AttachmentEntity(
                id = "att-export",
                messageId = "m-export",
                kind = "IMAGE",
                mime = "image/jpeg",
                localPath = "attachments/secret-local-path.jpg",
                fileName = "猫.jpg",
                sizeBytes = 2_048L,
                width = 1_536,
                height = 1_024,
                status = AttachmentStatus.SENT,
                errorKind = null,
                attempts = 1,
                remoteFileId = "remote-file-handle-xyz",
                expiresAt = now + 86_400_000L,
                deletedAt = null,
                createdAt = now,
            ),
        )
        // 已软删：不应导出
        db.attachmentDao().upsert(
            AttachmentEntity(
                id = "att-deleted",
                messageId = "m-export",
                kind = "IMAGE",
                mime = "image/png",
                localPath = "attachments/deleted.jpg",
                fileName = null,
                sizeBytes = 1L,
                width = null,
                height = null,
                status = AttachmentStatus.DRAFT,
                errorKind = null,
                attempts = 0,
                remoteFileId = null,
                expiresAt = null,
                deletedAt = 2L,
                createdAt = now,
            ),
        )
        // 草稿/孤儿（messageId 为空）： 起**不导出**（不属于任何消息，恢复后对不上号）
        db.attachmentDao().upsert(
            AttachmentEntity(
                id = "att-draft",
                messageId = null,
                kind = "IMAGE",
                mime = "image/jpeg",
                localPath = "attachments/draft.jpg",
                fileName = "草稿.jpg",
                sizeBytes = 99L,
                width = null,
                height = null,
                status = AttachmentStatus.DRAFT,
                errorKind = null,
                attempts = 0,
                remoteFileId = null,
                expiresAt = null,
                deletedAt = null,
                createdAt = now,
            ),
        )

        val json = ExportService.buildJson(db)
        assertEquals("导出 schema 必须是 3", 3, ExportService.SCHEMA)

        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(3, root.getValue("schema").jsonPrimitive.int)
        assertEquals("pulsez", root.getValue("app").jsonPrimitive.content)
        assertTrue("角色/会话仍要导出", root.getValue("character").jsonArray.isNotEmpty())
        assertTrue(root.getValue("thread").jsonArray.isNotEmpty())

        val attachments = root.getValue("attachments").jsonArray
        assertEquals("软删/未绑定消息的附件都不导出", 1, attachments.size)
        val attachment = attachments.single().jsonObject
        assertEquals(
            "只允许白名单字段和 messageId",
            setOf("messageId", "kind", "mime", "fileName", "sizeBytes", "width", "height", "createdAt"),
            attachment.keys,
        )
        // / ：附件与消息的关联必须在导出里保留。
        assertEquals("m-export", attachment.getValue("messageId").jsonPrimitive.content)
        assertEquals("IMAGE", attachment.getValue("kind").jsonPrimitive.content)
        assertEquals("image/jpeg", attachment.getValue("mime").jsonPrimitive.content)
        assertEquals("猫.jpg", attachment.getValue("fileName").jsonPrimitive.content)
        assertEquals(2_048L, attachment.getValue("sizeBytes").jsonPrimitive.long)
        assertEquals(1_536, attachment.getValue("width").jsonPrimitive.int)
        assertEquals(1_024, attachment.getValue("height").jsonPrimitive.int)
        assertEquals(now, attachment.getValue("createdAt").jsonPrimitive.long)

        // 隐私硬断言：路径与服务商句柄连值都不能出现；草稿也不该出现。
        assertFalse("不能导出 localPath", json.contains("secret-local-path"))
        assertFalse("不能导出 remoteFileId", json.contains("remote-file-handle-xyz"))
        assertFalse("不能出现错误码字段以外的内部列名", json.contains("localPath"))
        assertFalse(json.contains("remoteFileId"))
        assertFalse("草稿未绑定消息，不导出", json.contains("草稿.jpg"))
    }

    // ------------------------------------------------------------------
    // 2. clearAll：库清空 + 本地附件目录为空
    // ------------------------------------------------------------------

    @Test
    fun `clearAll 清空数据库并删除本地附件文件`() = runBlocking {
        val store = AttachmentStore(tempFolder.newFolder("files"))
        AttachmentStore.install(store)
        val filePath = store.save(ByteArrayInputStream(ByteArray(64)), "wipe-1", "jpg")
        assertTrue(store.exists(filePath))

        val container = AppContainer(context)
        try {
            val (character, thread) = container.characters.createWithThread(
                name = "阿淮",
                persona = "温和、话不多",
            )
            container.database.attachmentDao().upsert(
                AttachmentEntity(
                    id = "wipe-1",
                    messageId = null,
                    kind = "IMAGE",
                    mime = "image/jpeg",
                    localPath = filePath,
                    fileName = null,
                    sizeBytes = 64L,
                    width = null,
                    height = null,
                    status = AttachmentStatus.DRAFT,
                    errorKind = null,
                    attempts = 0,
                    remoteFileId = null,
                    expiresAt = null,
                    deletedAt = null,
                    createdAt = 1L,
                ),
            )
            assertEquals(character.id, container.characters.list().single().id)
            assertTrue(container.database.threadDao().byId(thread.id) != null)

            // clearAllTables 会 assertNotMainThread；生产从主线程协程调用，所以这里也切 IO
            withContext(Dispatchers.IO) {
                ExportService.clearAll(container, clearSecrets = {
                    val secretFile = File(context.filesDir, "secrets.bin")
                    if (secretFile.exists() && !secretFile.delete() && secretFile.exists()) {
                        throw IOException("密文文件删除失败")
                    }
                })
            }

            assertTrue("清空后角色表应为空", container.characters.list().isEmpty())
            assertTrue(
                "清空后附件表应为空（clearAllTables 物理清）",
                container.database.attachmentDao().idsInUse().isEmpty(),
            )
            assertTrue(
                "清空后本地附件目录必须为空",
                store.dir.listFiles().isNullOrEmpty(),
            )
        } finally {
            runCatching { container.database.close() }
        }
    }

    // ------------------------------------------------------------------
    // 辅助：造一条角色/会话/段/消息（导出循环至少要有一条 active thread）
    // ------------------------------------------------------------------

    private suspend fun seedCharacterThreadAndMessage(db: PulsezDatabase) {
        val now = 1_600_000_000_000L
        db.characterDao().upsert(
            CharacterEntity(
                id = "char-export",
                name = "阿淮",
                avatarUri = null,
                persona = "温和、话不多",
                greeting = null,
                styleSamples = null,
                userName = null,
                systemPromptOverride = null,
                protocolEnabled = true,
                sourceType = "MANUAL",
                sourceRef = null,
                rawImported = null,
                emoAssetDir = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
        db.threadDao().upsert(
            ThreadEntity(
                id = "thread-export",
                characterId = "char-export",
                title = null,
                createdAt = now,
                lastMessageAt = now,
                activeSegmentId = "seg-export",
                segmentOrdinal = 0,
                totalMessages = 1,
                totalTokens = 2,
                archived = false,
                kind = ThreadKind.SINGLE,
            ),
        )
        db.segmentDao().upsert(
            SegmentEntity(
                id = "seg-export",
                threadId = "thread-export",
                ordinal = 0,
                summary = null,
                styleNotes = null,
                firstMessageAt = now,
                lastMessageAt = now,
                tokenSum = 2,
                state = "OPEN",
                batonReason = null,
                createdAt = now,
            ),
        )
        db.messageDao().upsert(
            cc.pulsez.app.data.db.MessageEntity(
                id = "m-export",
                threadId = "thread-export",
                segmentId = "seg-export",
                role = "assistant",
                content = "在的",
                tokens = 2,
                emotion = null,
                status = "OK",
                draft = null,
                usageIn = null,
                usageOut = null,
                previousContent = null,
                createdAt = now,
                deletedAt = null,
                speakerCharacterId = null,
            ),
        )
    }

    private companion object {
        /** `AppContainer` 里 Room 的库名是写死的（`PulsezDatabase.create`）。 */
        const val APP_DB_NAME = "pulsez.db"
    }
}
