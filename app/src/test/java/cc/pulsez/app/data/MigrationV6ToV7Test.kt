package cc.pulsez.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.CharacterRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v6 → v7 adds an unknown-by-default vector configuration fingerprint without dropping old chunks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV6ToV7Test {

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
    fun oldVectorChunkSurvivesAndGetsUnknownConfigurationMarker() = runBlocking {
        val latest = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        val threadId = try {
            val (_, thread) = CharacterRepository(latest).createWithThread("合成 TA", "合成人设")
            latest.chunkDao().insertAll(
                listOf(
                    ChunkEntity(
                        id = "legacy-vector-chunk",
                        threadId = thread.id,
                        segmentId = "legacy-segment",
                        text = "synthetic block",
                        firstMessageAt = 1L,
                        lastMessageAt = 2L,
                        messageIds = "synthetic-message-a,synthetic-message-b",
                        dim = 2,
                        vec = byteArrayOf(3, 4),
                        scale = 0.5f,
                        model = "model-a",
                        createdAt = 1L,
                        embeddingConfigFingerprint = "removed-by-v6-downgrade",
                    ),
                ),
            )
            thread.id
        } finally {
            latest.close()
        }

        downgradeToV6()
        val migrated = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .addMigrations(
                PulsezDatabase.MIGRATION_6_7,
                PulsezDatabase.MIGRATION_7_8,
                PulsezDatabase.MIGRATION_8_9,
            )
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(9, migrated.openHelper.writableDatabase.version)
            val chunk = migrated.chunkDao().forSegment("legacy-segment").single()
            assertEquals("旧向量仍保留", "legacy-vector-chunk", chunk.id)
            assertEquals(threadId, chunk.threadId)
            assertEquals(byteArrayOf(3, 4).toList(), chunk.vec.toList())
            assertEquals("旧配置无法证明，必须 fail-closed", "", chunk.embeddingConfigFingerprint)
        } finally {
            migrated.close()
        }
    }

    private fun downgradeToV6() {
        val raw = SQLiteDatabase.openDatabase(
            context.getDatabasePath(DB_NAME).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            raw.execSQL("PRAGMA foreign_keys=OFF")
            raw.beginTransaction()
            // 6.json has the historical 17-column attachment table; remove v9 triggers before rebuilding it.
            val attachmentTriggers = raw.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name='attachment'",
                null,
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            attachmentTriggers.forEach { raw.execSQL("DROP TRIGGER IF EXISTS `$it`") }
            raw.execSQL("DROP INDEX IF EXISTS `index_attachment_messageId`")
            raw.execSQL("DROP INDEX IF EXISTS `index_attachment_status`")
            raw.execSQL("DROP INDEX IF EXISTS `index_attachment_ownerThreadId`")
            // Exact v6 shape from app/schemas/.../6.json; copy every shared column and omit the v9-only binding.
            raw.execSQL(
                "CREATE TABLE `attachment_v6` (" +
                    "`id` TEXT NOT NULL, `messageId` TEXT, `kind` TEXT NOT NULL, `mime` TEXT NOT NULL, " +
                    "`localPath` TEXT NOT NULL, `fileName` TEXT, `sizeBytes` INTEGER NOT NULL, `width` INTEGER, " +
                    "`height` INTEGER, `status` TEXT NOT NULL, `errorKind` TEXT, `attempts` INTEGER NOT NULL, " +
                    "`remoteFileId` TEXT, `expiresAt` INTEGER, `deletedAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                    "`ownerThreadId` TEXT, PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "INSERT INTO `attachment_v6` (`id`,`messageId`,`kind`,`mime`,`localPath`,`fileName`,`sizeBytes`,`width`,`height`,`status`,`errorKind`,`attempts`,`remoteFileId`,`expiresAt`,`deletedAt`,`createdAt`,`ownerThreadId`) " +
                    "SELECT `id`,`messageId`,`kind`,`mime`,`localPath`,`fileName`,`sizeBytes`,`width`,`height`,`status`,`errorKind`,`attempts`,`remoteFileId`,`expiresAt`,`deletedAt`,`createdAt`,`ownerThreadId` FROM `attachment`",
            )
            raw.execSQL("DROP TABLE `attachment`")
            raw.execSQL("ALTER TABLE `attachment_v6` RENAME TO `attachment`")
            raw.execSQL("CREATE INDEX `index_attachment_messageId` ON `attachment` (`messageId`)")
            raw.execSQL("CREATE INDEX `index_attachment_status` ON `attachment` (`status`)")
            raw.execSQL("CREATE INDEX `index_attachment_ownerThreadId` ON `attachment` (`ownerThreadId`)")
            raw.execSQL("DROP INDEX IF EXISTS `index_chunk_threadId_createdAt`")
            raw.execSQL("DROP INDEX IF EXISTS `index_chunk_threadId_lastMessageAt`")
            raw.execSQL("DROP INDEX IF EXISTS `index_chunk_threadId_model`")
            raw.execSQL(
                "CREATE TABLE `chunk_v6` (" +
                    "`id` TEXT NOT NULL, `threadId` TEXT NOT NULL, `segmentId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                    "`firstMessageAt` INTEGER NOT NULL, `lastMessageAt` INTEGER NOT NULL, `messageIds` TEXT NOT NULL, " +
                    "`dim` INTEGER NOT NULL, `vec` BLOB NOT NULL, `scale` REAL NOT NULL, `model` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "INSERT INTO `chunk_v6` (`id`,`threadId`,`segmentId`,`text`,`firstMessageAt`,`lastMessageAt`,`messageIds`,`dim`,`vec`,`scale`,`model`,`createdAt`,`deletedAt`) " +
                    "SELECT `id`,`threadId`,`segmentId`,`text`,`firstMessageAt`,`lastMessageAt`,`messageIds`,`dim`,`vec`,`scale`,`model`,`createdAt`,`deletedAt` FROM `chunk`",
            )
            raw.execSQL("DROP TABLE `chunk`")
            raw.execSQL("ALTER TABLE `chunk_v6` RENAME TO `chunk`")
            raw.execSQL("CREATE INDEX `index_chunk_threadId_createdAt` ON `chunk` (`threadId`,`createdAt`)")
            raw.execSQL("CREATE INDEX `index_chunk_threadId_lastMessageAt` ON `chunk` (`threadId`,`lastMessageAt`)")
            raw.execSQL("CREATE INDEX `index_chunk_threadId_model` ON `chunk` (`threadId`,`model`)")
            // Exact v6 message shape from app/schemas/.../6.json; omit v8 columns.
            raw.execSQL("DROP INDEX IF EXISTS `index_message_threadId_createdAt`")
            raw.execSQL("DROP INDEX IF EXISTS `index_message_segmentId`")
            raw.execSQL("DROP INDEX IF EXISTS `index_message_status`")
            raw.execSQL(
                "CREATE TABLE `message_v6` (" +
                    "`id` TEXT NOT NULL, `threadId` TEXT NOT NULL, `segmentId` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `tokens` INTEGER NOT NULL, " +
                    "`emotion` TEXT, `status` TEXT NOT NULL, `draft` TEXT, `usageIn` INTEGER, " +
                    "`usageOut` INTEGER, `previousContent` TEXT, `createdAt` INTEGER NOT NULL, " +
                    "`deletedAt` INTEGER, `speakerCharacterId` TEXT, PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "INSERT INTO `message_v6` (`id`,`threadId`,`segmentId`,`role`,`content`,`tokens`,`emotion`,`status`,`draft`,`usageIn`,`usageOut`,`previousContent`,`createdAt`,`deletedAt`,`speakerCharacterId`) " +
                    "SELECT `id`,`threadId`,`segmentId`,`role`,`content`,`tokens`,`emotion`,`status`,`draft`,`usageIn`,`usageOut`,`previousContent`,`createdAt`,`deletedAt`,`speakerCharacterId` FROM `message`",
            )
            raw.execSQL("DROP TABLE `message`")
            raw.execSQL("ALTER TABLE `message_v6` RENAME TO `message`")
            raw.execSQL("CREATE INDEX `index_message_threadId_createdAt` ON `message` (`threadId`, `createdAt`)")
            raw.execSQL("CREATE INDEX `index_message_segmentId` ON `message` (`segmentId`)")
            raw.execSQL("CREATE INDEX `index_message_status` ON `message` (`status`)")
            raw.version = 6
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
            raw.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-v6-to-v7.db"
    }
}
