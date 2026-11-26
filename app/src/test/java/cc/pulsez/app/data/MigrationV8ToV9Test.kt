package cc.pulsez.app.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.db.AttachmentEntity
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.RemoteFileCleanupRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v8 rows keep their remote IDs; soft-deleted IDs migrate as unknown tombstones without altering attachment rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationV8ToV9Test {

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
    fun v8SoftDeletedSharedAndUnsharedIDsMigrateAsUnknownTombstonesAndQueueIsInstalled() = runBlocking {
        val fresh = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .addCallback(queueCallback())
            .build()
        try {
            fresh.attachmentDao().upsert(attachment("old-attachment", "old-remote-id", null, "test-only-current-binding"))
            fresh.attachmentDao().upsert(attachment("legacy-shared-live", "legacy-shared-id", null, "test-binding"))
            fresh.attachmentDao().upsert(attachment("legacy-shared-soft", "legacy-shared-id", 10, "test-binding"))
            fresh.attachmentDao().upsert(attachment("legacy-soft-only-1", "legacy-soft-only-id", 20, "test-binding"))
            fresh.attachmentDao().upsert(attachment("legacy-soft-only-2", "legacy-soft-only-id", 30, "test-binding"))
        } finally {
            fresh.close()
        }

        downgradeAttachmentTableToV8()

        val migrated = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .addMigrations(PulsezDatabase.MIGRATION_8_9)
            .build()
        try {
            assertEquals(9, migrated.openHelper.writableDatabase.version)
            val row = migrated.attachmentDao().byId("old-attachment")!!
            assertEquals("old-remote-id", row.remoteFileId)
            assertNull("old rows have no verifiable local binding", row.remoteCredentialBindingId)
            val cleanup = RemoteFileCleanupRepository(migrated)
            val legacyTombstones = cleanup.entries().filter {
                it.remoteFileId == "legacy-shared-id" || it.remoteFileId == "legacy-soft-only-id"
            }
            assertEquals(setOf("legacy-shared-id", "legacy-soft-only-id"), legacyTombstones.map { it.remoteFileId }.toSet())
            assertTrue("v8 soft-deleted IDs have unknown ownership", legacyTombstones.all { it.credentialBindingId == null })
            assertTrue("migration must not tombstone an ID that has only a live v8 row", cleanup.entries().none { it.remoteFileId == "old-remote-id" })
            assertEquals("legacy-shared-id", migrated.attachmentDao().byId("legacy-shared-live")?.remoteFileId)
            assertNull(migrated.attachmentDao().byId("legacy-shared-live")?.deletedAt)
            assertTrue(migrated.attachmentDao().byId("legacy-shared-soft")?.deletedAt != null)
            assertTrue(migrated.attachmentDao().byId("legacy-soft-only-1")?.deletedAt != null)
            assertTrue(migrated.attachmentDao().byId("legacy-soft-only-2")?.deletedAt != null)
            assertTrue(cleanup.pendingFor("test-binding").isEmpty())
            assertEquals(
                setOf("id", "messageId", "kind", "mime", "localPath", "fileName", "sizeBytes", "width", "height", "status", "errorKind", "attempts", "remoteFileId", "expiresAt", "deletedAt", "createdAt", "ownerThreadId", "remoteCredentialBindingId"),
                columnNames(migrated.openHelper.writableDatabase, "attachment"),
            )
        } finally {
            migrated.close()
        }

        // Reopen without the repair callback: the migrated raw table and trigger themselves must persist.
        val reopened = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val cleanup = RemoteFileCleanupRepository(reopened)
            cleanup.enqueue("retired-after-migration", "binding-a")
            assertTrue(
                "migration-installed tombstone guards must survive database reopen",
                runCatching {
                    reopened.attachmentDao().upsert(
                        reopened.attachmentDao().byId("old-attachment")!!.copy(
                            id = "new-after-migration",
                            remoteFileId = "retired-after-migration",
                            remoteCredentialBindingId = "binding-a",
                        ),
                    )
                }.isFailure,
            )
            assertTrue(runCatching {
                reopened.attachmentDao().upsert(
                    attachment("new-soft-only-ref", "legacy-soft-only-id", null, "new-binding"),
                )
            }.isFailure)
            assertTrue(runCatching {
                reopened.attachmentDao().upsert(
                    attachment("new-shared-ref", "legacy-shared-id", null, "new-binding"),
                )
            }.isFailure)
            assertNull("existing migrated shared live reference must remain intact", reopened.attachmentDao().byId("legacy-shared-live")?.deletedAt)
            assertTrue(cleanup.pendingFor("new-binding").isEmpty())
            reopened.attachmentDao().deleteByIds(listOf("legacy-shared-live"))
            assertNull(cleanup.entries().single { it.remoteFileId == "legacy-shared-id" }.credentialBindingId)
            assertTrue("an unknown legacy tombstone is never blindly deleted", cleanup.pendingFor("new-binding").isEmpty())
            reopened.attachmentDao().deleteByIds(listOf("old-attachment"))
            val entry = cleanup.entries().single { it.remoteFileId == "old-remote-id" }
            assertEquals("old-remote-id", entry.remoteFileId)
            assertNull("migrated legacy ID must remain unknown after trigger capture", entry.credentialBindingId)
            assertTrue(cleanup.pendingFor("new-binding").isEmpty())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun existingRawQueueBindingIsDowngradedWhenSoftDeletedV8IDIsUnknown() = runBlocking {
        val fresh = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .addCallback(queueCallback())
            .build()
        try {
            fresh.attachmentDao().upsert(attachment("legacy-soft-only", "legacy-soft-only-id", 10, "test-binding"))
            fresh.openHelper.writableDatabase.execSQL(
                "INSERT INTO `remote_file_cleanup` (`remoteFileId`, `credentialBindingId`) VALUES (?, ?)",
                arrayOf("legacy-soft-only-id", "binding-from-leftover-queue"),
            )
        } finally {
            fresh.close()
        }

        // A v8 Room schema may also carry an extra raw table from an interrupted/early candidate build.
        downgradeAttachmentTableToV8(preserveCleanupQueue = true)

        val migrated = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .addMigrations(PulsezDatabase.MIGRATION_8_9)
            .build()
        try {
            val cleanup = RemoteFileCleanupRepository(migrated)
            assertNull(cleanup.entries().single().credentialBindingId)
            assertTrue(cleanup.pendingFor("binding-from-leftover-queue").isEmpty())
            assertTrue(cleanup.pendingFor("test-binding").isEmpty())
            assertTrue(migrated.attachmentDao().byId("legacy-soft-only")?.deletedAt != null)
        } finally {
            migrated.close()
        }

        val reopened = Room.databaseBuilder(context, PulsezDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val cleanup = RemoteFileCleanupRepository(reopened)
            assertNull(cleanup.entries().single().credentialBindingId)
            assertTrue(runCatching {
                reopened.attachmentDao().upsert(attachment("reuse", "legacy-soft-only-id", null, "new-binding"))
            }.isFailure)
            assertTrue(cleanup.pendingFor("new-binding").isEmpty())
        } finally {
            reopened.close()
        }
    }

    private fun attachment(id: String, remoteFileId: String, deletedAt: Long?, bindingId: String?) =
        AttachmentEntity(
            id = id,
            messageId = null,
            kind = "FILE",
            mime = "application/pdf",
            localPath = "attachments/$id.pdf",
            fileName = null,
            sizeBytes = 1,
            width = null,
            height = null,
            status = AttachmentStatus.SENT,
            errorKind = null,
            attempts = 0,
            remoteFileId = remoteFileId,
            expiresAt = null,
            deletedAt = deletedAt,
            createdAt = 1,
            ownerThreadId = null,
            remoteCredentialBindingId = bindingId,
        )

    private fun downgradeAttachmentTableToV8(preserveCleanupQueue: Boolean = false) {
        val raw = SQLiteDatabase.openDatabase(
            context.getDatabasePath(DB_NAME).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            raw.beginTransaction()
            try {
                raw.execSQL("DROP TRIGGER IF EXISTS `enqueue_remote_file_cleanup_after_attachment_delete`")
                raw.execSQL("DROP TRIGGER IF EXISTS `enqueue_remote_file_cleanup_after_attachment_remote_change`")
                raw.execSQL("DROP TRIGGER IF EXISTS `enqueue_remote_file_cleanup_after_attachment_soft_delete`")
                raw.execSQL("DROP TRIGGER IF EXISTS `capture_remote_file_cleanup_before_attachment_replace`")
                raw.execSQL("DROP TRIGGER IF EXISTS `guard_remote_file_tombstone_before_attachment_insert`")
                raw.execSQL("DROP TRIGGER IF EXISTS `guard_remote_file_tombstone_before_attachment_update`")
                if (!preserveCleanupQueue) raw.execSQL("DROP TABLE IF EXISTS `remote_file_cleanup`")
                raw.execSQL(
                    "CREATE TABLE `attachment_v8` (" +
                        "`id` TEXT NOT NULL, `messageId` TEXT, `kind` TEXT NOT NULL, `mime` TEXT NOT NULL, " +
                        "`localPath` TEXT NOT NULL, `fileName` TEXT, `sizeBytes` INTEGER NOT NULL, " +
                        "`width` INTEGER, `height` INTEGER, `status` TEXT NOT NULL, `errorKind` TEXT, " +
                        "`attempts` INTEGER NOT NULL, `remoteFileId` TEXT, `expiresAt` INTEGER, " +
                        "`deletedAt` INTEGER, `createdAt` INTEGER NOT NULL, `ownerThreadId` TEXT, " +
                        "PRIMARY KEY(`id`))",
                )
                raw.execSQL(
                    "INSERT INTO `attachment_v8` (`id`, `messageId`, `kind`, `mime`, `localPath`, `fileName`, " +
                        "`sizeBytes`, `width`, `height`, `status`, `errorKind`, `attempts`, `remoteFileId`, " +
                        "`expiresAt`, `deletedAt`, `createdAt`, `ownerThreadId`) " +
                        "SELECT `id`, `messageId`, `kind`, `mime`, `localPath`, `fileName`, `sizeBytes`, `width`, " +
                        "`height`, `status`, `errorKind`, `attempts`, `remoteFileId`, `expiresAt`, `deletedAt`, " +
                        "`createdAt`, `ownerThreadId` FROM `attachment`",
                )
                raw.execSQL("DROP TABLE `attachment`")
                raw.execSQL("ALTER TABLE `attachment_v8` RENAME TO `attachment`")
                raw.execSQL("CREATE INDEX `index_attachment_messageId` ON `attachment` (`messageId`)")
                raw.execSQL("CREATE INDEX `index_attachment_status` ON `attachment` (`status`)")
                raw.execSQL("CREATE INDEX `index_attachment_ownerThreadId` ON `attachment` (`ownerThreadId`)")
                raw.execSQL(
                    "UPDATE `room_master_table` SET `identity_hash` = '17c7caf5ab589d7e6a83dc9ba34648b4' WHERE `id` = 42",
                )
                raw.execSQL("PRAGMA user_version = 8")
                raw.setTransactionSuccessful()
            } finally {
                raw.endTransaction()
            }
        } finally {
            raw.close()
        }
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                val index = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(index))
            }
        }

    private fun queueCallback() = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            PulsezDatabase.installRemoteFileCleanupQueue(db)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            PulsezDatabase.installRemoteFileCleanupQueue(db)
        }
    }

    private companion object {
        const val DB_NAME = "remote_file_migration_v8_v9_test.db"
    }
}
