package cc.pulsez.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.CharacterEntity
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.SegmentEntity
import cc.pulsez.app.data.db.ThreadEntity
import cc.pulsez.app.data.db.ThreadKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v4 → current v9 keeps existing rows and leaves pre-fingerprint vectors unknown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV4ToV5Test {

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
    fun v4DataSurvivesFullChainAndActivityRemainsNullInsteadOfUsingMessageTime() = runBlocking {
        val latest = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("夹具先从当前 v9 schema 还原", 9, latest.openHelper.writableDatabase.version)
            latest.characterDao().upsert(
                CharacterEntity(
                    id = "legacy-role",
                    name = "旧角色",
                    avatarUri = null,
                    persona = "人设",
                    greeting = null,
                    styleSamples = null,
                    userName = null,
                    systemPromptOverride = null,
                    protocolEnabled = true,
                    sourceType = "MANUAL",
                    sourceRef = null,
                    rawImported = null,
                    emoAssetDir = null,
                    createdAt = 1L,
                    updatedAt = 2L,
                ),
            )
            latest.threadDao().upsert(
                ThreadEntity(
                    id = "legacy-thread",
                    characterId = "legacy-role",
                    title = null,
                    createdAt = 3L,
                    lastMessageAt = 99_999L,
                    activeSegmentId = "legacy-segment",
                    segmentOrdinal = 0,
                    totalMessages = 1,
                    totalTokens = 4L,
                    archived = false,
                    kind = ThreadKind.SINGLE,
                ),
            )
            latest.segmentDao().upsert(
                SegmentEntity(
                    id = "legacy-segment",
                    threadId = "legacy-thread",
                    ordinal = 0,
                    summary = null,
                    styleNotes = null,
                    firstMessageAt = 3L,
                    lastMessageAt = 99_999L,
                    tokenSum = 4,
                    state = "OPEN",
                    batonReason = null,
                    createdAt = 3L,
                ),
            )
            latest.chunkDao().insertAll(
                listOf(
                    ChunkEntity(
                        id = "legacy-vector-chunk",
                        threadId = "legacy-thread",
                        segmentId = "legacy-segment",
                        text = "synthetic block",
                        firstMessageAt = 3L,
                        lastMessageAt = 99_999L,
                        messageIds = "legacy-message",
                        dim = 2,
                        vec = byteArrayOf(3, 4),
                        scale = 0.5f,
                        model = "model-a",
                        createdAt = 3L,
                        embeddingConfigFingerprint = "v7-only-fixture-value",
                    ),
                ),
            )
            latest.messageDao().upsert(
                MessageEntity(
                    id = "legacy-message",
                    threadId = "legacy-thread",
                    segmentId = "legacy-segment",
                    role = "user",
                    content = "fixture message",
                    tokens = 4,
                    emotion = null,
                    status = "OK",
                    draft = null,
                    usageIn = null,
                    usageOut = null,
                    previousContent = null,
                    createdAt = 99_999L,
                ),
            )
        } finally {
            latest.close()
        }

        // Rewind every v5/v6/v7-only schema change to the checked-in v4 shape before Room upgrades it.
        // This keeps the fixture real: attachment.ownerThreadId and the v7 chunk fingerprint must be absent.
        downgradeLatestDatabaseToV4()
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
            assertEquals("v4 旧库必须沿生产链升级到当前 v9", 9, migrated.openHelper.writableDatabase.version)
            assertEquals("legacy-role", migrated.characterDao().byId("legacy-role")?.id)
            assertEquals(listOf("legacy-message"), migrated.messageDao().allForThread("legacy-thread").map { it.id })
            assertEquals(99_999L, migrated.threadDao().byId("legacy-thread")?.lastMessageAt)
            assertNull("迁移不可从历史消息或 lastMessageAt 推断活动", migrated.threadDao().byId("legacy-thread")?.lastSingleChatActivityAt)
            val chunk = migrated.chunkDao().forSegment("legacy-segment").single()
            assertEquals("重建旧版 chunk 表不能丢行", "legacy-vector-chunk", chunk.id)
            assertEquals(byteArrayOf(3, 4).toList(), chunk.vec.toList())
            assertEquals("旧向量没有可验证的配置指纹", "", chunk.embeddingConfigFingerprint)
        } finally {
            migrated.close()
        }
    }

    private fun downgradeLatestDatabaseToV4() {
        val file = context.getDatabasePath(DB_NAME)
        val raw = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
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
            raw.execSQL("DROP TABLE IF EXISTS `deleted_character`")
            raw.execSQL("DROP TABLE IF EXISTS `pending_attachment_delete`")
            // Exact v4 chunk shape from app/schemas/.../4.json; omit only the v7 field and preserve every row.
            raw.execSQL(
                "CREATE TABLE `chunk_v4` (" +
                    "`id` TEXT NOT NULL, `threadId` TEXT NOT NULL, `segmentId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                    "`firstMessageAt` INTEGER NOT NULL, `lastMessageAt` INTEGER NOT NULL, `messageIds` TEXT NOT NULL, " +
                    "`dim` INTEGER NOT NULL, `vec` BLOB NOT NULL, `scale` REAL NOT NULL, `model` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "INSERT INTO `chunk_v4` (`id`,`threadId`,`segmentId`,`text`,`firstMessageAt`,`lastMessageAt`,`messageIds`,`dim`,`vec`,`scale`,`model`,`createdAt`,`deletedAt`) " +
                    "SELECT `id`,`threadId`,`segmentId`,`text`,`firstMessageAt`,`lastMessageAt`,`messageIds`,`dim`,`vec`,`scale`,`model`,`createdAt`,`deletedAt` FROM `chunk`",
            )
            raw.execSQL("DROP TABLE `chunk`")
            raw.execSQL("ALTER TABLE `chunk_v4` RENAME TO `chunk`")
            raw.execSQL("CREATE INDEX `index_chunk_threadId_createdAt` ON `chunk` (`threadId`, `createdAt`)")
            raw.execSQL("CREATE INDEX `index_chunk_threadId_lastMessageAt` ON `chunk` (`threadId`, `lastMessageAt`)")
            raw.execSQL("CREATE INDEX `index_chunk_threadId_model` ON `chunk` (`threadId`, `model`)")
            val chunkColumns = buildSet {
                raw.rawQuery("PRAGMA table_info(`chunk`)", null).use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
            assertFalse("v4 夹具必须真实移除 v7 chunk 指纹列", chunkColumns.contains("embeddingConfigFingerprint"))
            // Exact v4 attachment table and indices from app/schemas/.../4.json; preserve all old rows.
            raw.execSQL(
                "CREATE TABLE `attachment_v4` (" +
                    "`id` TEXT NOT NULL, `messageId` TEXT, `kind` TEXT NOT NULL, `mime` TEXT NOT NULL, " +
                    "`localPath` TEXT NOT NULL, `fileName` TEXT, `sizeBytes` INTEGER NOT NULL, `width` INTEGER, " +
                    "`height` INTEGER, `status` TEXT NOT NULL, `errorKind` TEXT, `attempts` INTEGER NOT NULL, " +
                    "`remoteFileId` TEXT, `expiresAt` INTEGER, `deletedAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "INSERT INTO `attachment_v4` (`id`,`messageId`,`kind`,`mime`,`localPath`,`fileName`,`sizeBytes`,`width`,`height`,`status`,`errorKind`,`attempts`,`remoteFileId`,`expiresAt`,`deletedAt`,`createdAt`) " +
                    "SELECT `id`,`messageId`,`kind`,`mime`,`localPath`,`fileName`,`sizeBytes`,`width`,`height`,`status`,`errorKind`,`attempts`,`remoteFileId`,`expiresAt`,`deletedAt`,`createdAt` FROM `attachment`",
            )
            raw.execSQL("DROP TABLE `attachment`")
            raw.execSQL("ALTER TABLE `attachment_v4` RENAME TO `attachment`")
            raw.execSQL("CREATE INDEX `index_attachment_messageId` ON `attachment` (`messageId`)")
            raw.execSQL("CREATE INDEX `index_attachment_status` ON `attachment` (`status`)")

            raw.execSQL(
                "CREATE TABLE `thread_v4` (" +
                    "`id` TEXT NOT NULL, `characterId` TEXT, `title` TEXT, `createdAt` INTEGER NOT NULL, " +
                    "`lastMessageAt` INTEGER NOT NULL, `activeSegmentId` TEXT, `segmentOrdinal` INTEGER NOT NULL, " +
                    "`totalMessages` INTEGER NOT NULL, `totalTokens` INTEGER NOT NULL, " +
                    "`lastExtractAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, " +
                    "`kind` TEXT NOT NULL DEFAULT 'SINGLE', PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`characterId`) REFERENCES `character`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            raw.execSQL(
                "INSERT INTO `thread_v4` (`id`,`characterId`,`title`,`createdAt`,`lastMessageAt`," +
                    "`activeSegmentId`,`segmentOrdinal`,`totalMessages`,`totalTokens`,`lastExtractAt`,`archived`,`kind`) " +
                    "SELECT `id`,`characterId`,`title`,`createdAt`,`lastMessageAt`,`activeSegmentId`," +
                    "`segmentOrdinal`,`totalMessages`,`totalTokens`,`lastExtractAt`,`archived`,`kind` FROM `thread`",
            )
            raw.execSQL("DROP TABLE `thread`")
            raw.execSQL("ALTER TABLE `thread_v4` RENAME TO `thread`")
            raw.execSQL("CREATE INDEX `index_thread_characterId` ON `thread` (`characterId`)")
            raw.execSQL("CREATE INDEX `index_thread_lastMessageAt` ON `thread` (`lastMessageAt`)")
            // Exact v4 message shape from app/schemas/.../4.json; omit v8 columns.
            raw.execSQL("DROP INDEX IF EXISTS `index_message_threadId_createdAt`")
            raw.execSQL("DROP INDEX IF EXISTS `index_message_segmentId`")
            raw.execSQL("DROP INDEX IF EXISTS `index_message_status`")
            raw.execSQL(
                "CREATE TABLE `message_v4` (" +
                    "`id` TEXT NOT NULL, `threadId` TEXT NOT NULL, `segmentId` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `tokens` INTEGER NOT NULL, " +
                    "`emotion` TEXT, `status` TEXT NOT NULL, `draft` TEXT, `usageIn` INTEGER, " +
                    "`usageOut` INTEGER, `previousContent` TEXT, `createdAt` INTEGER NOT NULL, " +
                    "`deletedAt` INTEGER, `speakerCharacterId` TEXT, PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "INSERT INTO `message_v4` (`id`,`threadId`,`segmentId`,`role`,`content`,`tokens`,`emotion`,`status`,`draft`,`usageIn`,`usageOut`,`previousContent`,`createdAt`,`deletedAt`,`speakerCharacterId`) " +
                    "SELECT `id`,`threadId`,`segmentId`,`role`,`content`,`tokens`,`emotion`,`status`,`draft`,`usageIn`,`usageOut`,`previousContent`,`createdAt`,`deletedAt`,`speakerCharacterId` FROM `message`",
            )
            raw.execSQL("DROP TABLE `message`")
            raw.execSQL("ALTER TABLE `message_v4` RENAME TO `message`")
            raw.execSQL("CREATE INDEX `index_message_threadId_createdAt` ON `message` (`threadId`, `createdAt`)")
            raw.execSQL("CREATE INDEX `index_message_segmentId` ON `message` (`segmentId`)")
            raw.execSQL("CREATE INDEX `index_message_status` ON `message` (`status`)")
            raw.version = 4
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
            raw.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-v4-to-v5.db"
    }
}
