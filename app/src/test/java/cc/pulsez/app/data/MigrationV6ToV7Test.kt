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
            .addMigrations(PulsezDatabase.MIGRATION_6_7)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(7, migrated.openHelper.writableDatabase.version)
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
