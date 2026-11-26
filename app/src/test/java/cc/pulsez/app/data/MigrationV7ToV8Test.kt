package cc.pulsez.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.AttachmentKind
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
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

/** v7 → v8 adds nullable DeepSeek metadata columns on `message` without dropping existing rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationV7ToV8Test {

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
    fun v7MessagesSurviveAndV8ColumnsAreNullableAndWritable() = runBlocking {
        val latest = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        val (threadId, segmentId) = try {
            val (_, thread) = CharacterRepository(latest).createWithThread("阿淮", "温和")
            val segId = thread.activeSegmentId!!
            latest.messageDao().upsert(
                MessageEntity(
                    id = "m-v7",
                    threadId = thread.id,
                    segmentId = segId,
                    role = "assistant",
                    content = "旧消息还在",
                    tokens = 4,
                    emotion = "平静",
                    status = "OK",
                    draft = null,
                    usageIn = 10,
                    usageOut = 4,
                    previousContent = null,
                    createdAt = 1_000L,
                    deletedAt = null,
                    speakerCharacterId = null,
                    reasoningContent = "removed-by-v7-downgrade",
                    serverToolCallsJson = "[{\"type\":\"deepseek_web_search\"}]",
                    citationsJson = "[{\"url\":\"https://example.com\"}]",
                    latestReminder = "2026-11-25,周三,,App,中文",
                ),
            )
            thread.id to segId
        } finally {
            latest.close()
        }

        downgradeToV7()
        val migrated = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .addMigrations(PulsezDatabase.MIGRATION_7_8, PulsezDatabase.MIGRATION_8_9)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
        try {
            val raw = migrated.openHelper.writableDatabase
            assertEquals(9, raw.version)

            val oldMessage = migrated.messageDao().byId("m-v7")
            assertNotNull(oldMessage)
            assertEquals("旧消息还在", oldMessage!!.content)
            assertNull(oldMessage.reasoningContent)
            assertNull(oldMessage.serverToolCallsJson)
            assertNull(oldMessage.citationsJson)
            assertNull(oldMessage.latestReminder)

            val columnTypes = LinkedHashMap<String, Pair<Boolean, String?>>()
            raw.query("PRAGMA table_info(`message`)").use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
                val defaultIdx = cursor.getColumnIndexOrThrow("dflt_value")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)
                    val notNull = cursor.getInt(notNullIdx) != 0
                    val defaultVal = if (cursor.isNull(defaultIdx)) null else cursor.getString(defaultIdx)
                    columnTypes[name] = notNull to defaultVal
                }
            }
            listOf("reasoningContent", "serverToolCallsJson", "citationsJson", "latestReminder").forEach { col ->
                assertTrue("message 必须包含 $col", columnTypes.containsKey(col))
                assertFalse("$col 必须可空", columnTypes.getValue(col).first)
                assertNull("$col 不应设 DB DEFAULT", columnTypes.getValue(col).second)
            }

            val chat = ChatRepository(migrated)
            val appended = chat.append(
                threadId = threadId,
                segmentId = segmentId,
                role = "assistant",
                text = "新消息",
                tokens = 3,
                reasoningContent = "思考过程",
                serverToolCallsJson = "[{\"id\":\"call-1\"}]",
                citationsJson = "[{\"url\":\"https://api-docs.deepseek.com/\",\"startIndex\":0,\"endIndex\":3}]",
                latestReminder = "2026-11-25,周三,,App,中文",
            )
            val reloaded = migrated.messageDao().byId(appended.id)!!
            assertEquals("思考过程", reloaded.reasoningContent)
            assertEquals("[{\"id\":\"call-1\"}]", reloaded.serverToolCallsJson)
            assertEquals(
                "[{\"url\":\"https://api-docs.deepseek.com/\",\"startIndex\":0,\"endIndex\":3}]",
                reloaded.citationsJson,
            )
            assertEquals("2026-11-25,周三,,App,中文", reloaded.latestReminder)
            assertEquals("FILE", AttachmentKind.FILE)
        } finally {
            migrated.close()
        }
    }

    private fun downgradeToV7() {
        val raw = SQLiteDatabase.openDatabase(
            context.getDatabasePath(DB_NAME).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            raw.execSQL("PRAGMA foreign_keys=OFF")
            raw.beginTransaction()
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
            raw.execSQL(
                "CREATE TABLE `attachment_v7` (" +
                    "`id` TEXT NOT NULL, `messageId` TEXT, `kind` TEXT NOT NULL, `mime` TEXT NOT NULL, " +
                    "`localPath` TEXT NOT NULL, `fileName` TEXT, `sizeBytes` INTEGER NOT NULL, `width` INTEGER, " +
                    "`height` INTEGER, `status` TEXT NOT NULL, `errorKind` TEXT, `attempts` INTEGER NOT NULL, " +
                    "`remoteFileId` TEXT, `expiresAt` INTEGER, `deletedAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                    "`ownerThreadId` TEXT, PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "INSERT INTO `attachment_v7` (`id`,`messageId`,`kind`,`mime`,`localPath`,`fileName`,`sizeBytes`,`width`,`height`,`status`,`errorKind`,`attempts`,`remoteFileId`,`expiresAt`,`deletedAt`,`createdAt`,`ownerThreadId`) " +
                    "SELECT `id`,`messageId`,`kind`,`mime`,`localPath`,`fileName`,`sizeBytes`,`width`,`height`,`status`,`errorKind`,`attempts`,`remoteFileId`,`expiresAt`,`deletedAt`,`createdAt`,`ownerThreadId` FROM `attachment`",
            )
            raw.execSQL("DROP TABLE `attachment`")
            raw.execSQL("ALTER TABLE `attachment_v7` RENAME TO `attachment`")
            raw.execSQL("CREATE INDEX `index_attachment_messageId` ON `attachment` (`messageId`)")
            raw.execSQL("CREATE INDEX `index_attachment_status` ON `attachment` (`status`)")
            raw.execSQL("CREATE INDEX `index_attachment_ownerThreadId` ON `attachment` (`ownerThreadId`)")
            raw.execSQL("DROP INDEX IF EXISTS `index_message_threadId_createdAt`")
            raw.execSQL("DROP INDEX IF EXISTS `index_message_segmentId`")
            raw.execSQL("DROP INDEX IF EXISTS `index_message_status`")
            raw.execSQL(
                "CREATE TABLE `message_v7` (" +
                    "`id` TEXT NOT NULL, `threadId` TEXT NOT NULL, `segmentId` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `tokens` INTEGER NOT NULL, " +
                    "`emotion` TEXT, `status` TEXT NOT NULL, `draft` TEXT, `usageIn` INTEGER, " +
                    "`usageOut` INTEGER, `previousContent` TEXT, `createdAt` INTEGER NOT NULL, " +
                    "`deletedAt` INTEGER, `speakerCharacterId` TEXT, PRIMARY KEY(`id`))",
            )
            raw.execSQL(
                "INSERT INTO `message_v7` (`id`,`threadId`,`segmentId`,`role`,`content`,`tokens`,`emotion`,`status`,`draft`,`usageIn`,`usageOut`,`previousContent`,`createdAt`,`deletedAt`,`speakerCharacterId`) " +
                    "SELECT `id`,`threadId`,`segmentId`,`role`,`content`,`tokens`,`emotion`,`status`,`draft`,`usageIn`,`usageOut`,`previousContent`,`createdAt`,`deletedAt`,`speakerCharacterId` FROM `message`",
            )
            raw.execSQL("DROP TABLE `message`")
            raw.execSQL("ALTER TABLE `message_v7` RENAME TO `message`")
            raw.execSQL("CREATE INDEX `index_message_threadId_createdAt` ON `message` (`threadId`, `createdAt`)")
            raw.execSQL("CREATE INDEX `index_message_segmentId` ON `message` (`segmentId`)")
            raw.execSQL("CREATE INDEX `index_message_status` ON `message` (`status`)")
            raw.version = 7
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
            raw.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-v7-to-v8.db"
    }
}
