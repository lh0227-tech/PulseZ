package cc.pulsez.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.AttachmentEntity
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.CharacterRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v5 → current v9 preserves old attachment and vector rows without inventing unknown metadata. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV5ToV6Test {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun v5UnboundAttachmentAndRoleSurviveWithUnknownOwner() = runBlocking {
        val latest = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        val (role, thread) = try {
            val result = CharacterRepository(latest).createWithThread("旧角色", "人设")
            latest.attachmentDao().upsert(
                AttachmentEntity(
                    id = "old-unbound-draft",
                    messageId = null,
                    kind = "IMAGE",
                    mime = "image/jpeg",
                    localPath = "attachments/old-unbound-draft.jpg",
                    fileName = null,
                    sizeBytes = 4,
                    width = 2,
                    height = 2,
                    status = AttachmentStatus.READY,
                    errorKind = null,
                    attempts = 0,
                    remoteFileId = null,
                    expiresAt = null,
                    deletedAt = null,
                    createdAt = 1L,
                    ownerThreadId = null,
                ),
            )
            latest.chunkDao().insertAll(
                listOf(
                    ChunkEntity(
                        id = "old-vector-chunk",
                        threadId = result.second.id,
                        segmentId = "legacy-segment",
                        text = "synthetic block",
                        firstMessageAt = 1L,
                        lastMessageAt = 2L,
                        messageIds = "synthetic-message",
                        dim = 2,
                        vec = byteArrayOf(5, 6),
                        scale = 0.5f,
                        model = "model-a",
                        createdAt = 1L,
                        embeddingConfigFingerprint = "v7-only-fixture-value",
                    ),
                ),
            )
            result
        } finally {
            latest.close()
        }

        downgradeToV5()
        val migrated = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .addMigrations(
                PulsezDatabase.MIGRATION_1_2,
                PulsezDatabase.MIGRATION_2_3,
                PulsezDatabase.MIGRATION_3_4,
                PulsezDatabase.MIGRATION_4_5,
                PulsezDatabase.MIGRATION_5_6,
                PulsezDatabase.MIGRATION_6_7,
                PulsezDatabase.MIGRATION_7_8,
                PulsezDatabase.MIGRATION_8_9,
            )
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("v5 旧库必须沿生产链升级到当前 v9", 9, migrated.openHelper.writableDatabase.version)
            assertEquals(role.id, migrated.characterDao().byId(role.id)?.id)
            assertEquals(thread.id, migrated.threadDao().byId(thread.id)?.id)
            val migratedAttachment = migrated.attachmentDao().byId("old-unbound-draft")
            assertNotNull("v5 旧附件行必须保留", migratedAttachment)
            assertNull("迁移不可猜测旧草稿属于哪个角色", migratedAttachment!!.ownerThreadId)
            assertTrue("未归属草稿保留待后续清理，不能被迁移全局删除", migrated.pendingAttachmentDeleteDao().all().isEmpty())
            val chunk = migrated.chunkDao().forSegment("legacy-segment").single()
            assertEquals("重建旧版 chunk 表不能丢行", "old-vector-chunk", chunk.id)
            assertEquals(byteArrayOf(5, 6).toList(), chunk.vec.toList())
            assertEquals("旧向量没有可验证的配置指纹", "", chunk.embeddingConfigFingerprint)

            migrated.deletedCharacterDao().tombstone(cc.pulsez.app.data.db.DeletedCharacterEntity(role.id, 10L))
            assertTrue("迁移后同角色 id 的迟到写入受保护", runCatching { migrated.characterDao().upsert(role) }.isFailure)
            assertFalse(migrated.deletedCharacterDao().contains("another-role"))
        } finally {
            migrated.close()
        }
    }

    private fun downgradeToV5() {
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(DB_NAME).path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            raw.execSQL("PRAGMA foreign_keys=OFF")
            raw.beginTransaction()
            raw.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger'", null).use { cursor ->
                val triggerNames = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                triggerNames.forEach { raw.execSQL("DROP TRIGGER IF EXISTS `$it`") }
            }
            raw.execSQL("DROP INDEX IF EXISTS `index_attachment_ownerThreadId`")
            raw.execSQL("DROP TABLE `deleted_character`")
            raw.execSQL("DROP TABLE `pending_attachment_delete`")
            // Exact v5 chunk shape from app/schemas/.../5.json; omit only the v7 field and preserve every row.
            raw.execSQL(
                "CREATE TABLE `chunk_v5` (" +
                    "`id` TEXT NOT NULL, `threadId` TEXT NOT NULL, `segmentId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                    "`firstMessageAt` INTEGER NOT NULL, `lastMessageAt` INTEGER NOT NULL, `messageIds` TEXT NOT NULL, " +
                    "`dim` INTEGER NOT NULL, `vec` BLOB NOT NULL, `scale` REAL NOT NULL, `model` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "INSERT INTO `chunk_v5` (`id`,`threadId`,`segmentId`,`text`,`firstMessageAt`,`lastMessageAt`,`messageIds`,`dim`,`vec`,`scale`,`model`,`createdAt`,`deletedAt`) " +
                    "SELECT `id`,`threadId`,`segmentId`,`text`,`firstMessageAt`,`lastMessageAt`,`messageIds`,`dim`,`vec`,`scale`,`model`,`createdAt`,`deletedAt` FROM `chunk`",
            )
            raw.execSQL("DROP TABLE `chunk`")
            raw.execSQL("ALTER TABLE `chunk_v5` RENAME TO `chunk`")
            raw.execSQL("CREATE INDEX `index_chunk_threadId_createdAt` ON `chunk` (`threadId`, `createdAt`)")
            raw.execSQL("CREATE INDEX `index_chunk_threadId_lastMessageAt` ON `chunk` (`threadId`, `lastMessageAt`)")
            raw.execSQL("CREATE INDEX `index_chunk_threadId_model` ON `chunk` (`threadId`, `model`)")
            val chunkColumns = buildSet {
                raw.rawQuery("PRAGMA table_info(`chunk`)", null).use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
            assertFalse("v5 夹具必须真实移除 v7 chunk 指纹列", chunkColumns.contains("embeddingConfigFingerprint"))
            raw.execSQL(
                "CREATE TABLE `attachment_v5` (" +
                    "`id` TEXT NOT NULL, `messageId` TEXT, `kind` TEXT NOT NULL, `mime` TEXT NOT NULL, " +
                    "`localPath` TEXT NOT NULL, `fileName` TEXT, `sizeBytes` INTEGER NOT NULL, `width` INTEGER, " +
                    "`height` INTEGER, `status` TEXT NOT NULL, `errorKind` TEXT, `attempts` INTEGER NOT NULL, " +
                    "`remoteFileId` TEXT, `expiresAt` INTEGER, `deletedAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "INSERT INTO `attachment_v5` (`id`,`messageId`,`kind`,`mime`,`localPath`,`fileName`,`sizeBytes`,`width`,`height`,`status`,`errorKind`,`attempts`,`remoteFileId`,`expiresAt`,`deletedAt`,`createdAt`) " +
                    "SELECT `id`,`messageId`,`kind`,`mime`,`localPath`,`fileName`,`sizeBytes`,`width`,`height`,`status`,`errorKind`,`attempts`,`remoteFileId`,`expiresAt`,`deletedAt`,`createdAt` FROM `attachment`",
            )
            raw.execSQL("DROP TABLE `attachment`")
            raw.execSQL("ALTER TABLE `attachment_v5` RENAME TO `attachment`")
            raw.execSQL("CREATE INDEX `index_attachment_messageId` ON `attachment` (`messageId`)")
            raw.execSQL("CREATE INDEX `index_attachment_status` ON `attachment` (`status`)")
            // Exact v5 message shape from app/schemas/.../5.json; omit v8 columns.
            raw.execSQL("DROP INDEX IF EXISTS `index_message_threadId_createdAt`")
            raw.execSQL("DROP INDEX IF EXISTS `index_message_segmentId`")
            raw.execSQL("DROP INDEX IF EXISTS `index_message_status`")
            raw.execSQL(
                "CREATE TABLE `message_v5` (" +
                    "`id` TEXT NOT NULL, `threadId` TEXT NOT NULL, `segmentId` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `tokens` INTEGER NOT NULL, " +
                    "`emotion` TEXT, `status` TEXT NOT NULL, `draft` TEXT, `usageIn` INTEGER, " +
                    "`usageOut` INTEGER, `previousContent` TEXT, `createdAt` INTEGER NOT NULL, " +
                    "`deletedAt` INTEGER, `speakerCharacterId` TEXT, PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "INSERT INTO `message_v5` (`id`,`threadId`,`segmentId`,`role`,`content`,`tokens`,`emotion`,`status`,`draft`,`usageIn`,`usageOut`,`previousContent`,`createdAt`,`deletedAt`,`speakerCharacterId`) " +
                    "SELECT `id`,`threadId`,`segmentId`,`role`,`content`,`tokens`,`emotion`,`status`,`draft`,`usageIn`,`usageOut`,`previousContent`,`createdAt`,`deletedAt`,`speakerCharacterId` FROM `message`",
            )
            raw.execSQL("DROP TABLE `message`")
            raw.execSQL("ALTER TABLE `message_v5` RENAME TO `message`")
            raw.execSQL("CREATE INDEX `index_message_threadId_createdAt` ON `message` (`threadId`, `createdAt`)")
            raw.execSQL("CREATE INDEX `index_message_segmentId` ON `message` (`segmentId`)")
            raw.execSQL("CREATE INDEX `index_message_status` ON `message` (`status`)")
            raw.version = 5
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
            raw.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-v5-to-v6.db"
    }
}
