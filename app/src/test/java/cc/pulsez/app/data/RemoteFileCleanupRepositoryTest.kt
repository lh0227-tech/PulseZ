package cc.pulsez.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentEntity
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.AttachmentRepository
import cc.pulsez.app.data.repo.RemoteFileCleanupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Offline-only coverage for the raw two-field queue, SQLite triggers, and live-reference guard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteFileCleanupRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: PulsezDatabase
    private lateinit var cleanup: RemoteFileCleanupRepository
    private lateinit var storeDir: File
    private lateinit var store: AttachmentStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = inMemoryDatabase()
        cleanup = RemoteFileCleanupRepository(db)
        storeDir = File(context.cacheDir, "remote-cleanup-${System.nanoTime()}").apply { mkdirs() }
        store = AttachmentStore(storeDir)
    }

    @After
    fun tearDown() {
        db.close()
        storeDir.deleteRecursively()
        context.deleteDatabase(PERSISTENT_DB_NAME)
    }

    @Test
    fun `pending rows wait until the last live reference is soft deleted`() = runBlocking {
        db.attachmentDao().upsert(attachment("a-1", "m-1", "remote-1", "binding-a"))
        db.attachmentDao().upsert(attachment("a-2", "m-2", "remote-1", "binding-a"))
        val repository = AttachmentRepository(db)

        repository.softDeleteForMessage("m-1", now = 10)
        assertTrue(cleanup.pendingFor("binding-a").isEmpty())
        assertEquals(1, cleanup.entries().size)

        repository.softDeleteForMessage("m-2", now = 20)
        assertEquals(listOf("remote-1"), cleanup.pendingFor("binding-a"))
        assertEquals("binding-a", cleanup.entries().single().credentialBindingId)
    }

    @Test
    fun `raw DAO soft deletes enqueue atomically and wait for shared references`() = runBlocking {
        db.attachmentDao().upsert(attachment("raw-soft-1", "m-raw-soft-1", "remote-raw-soft", "binding-a"))
        db.attachmentDao().upsert(attachment("raw-soft-2", "m-raw-soft-2", "remote-raw-soft", "binding-a"))

        db.withTransaction {
            assertEquals(1, db.attachmentDao().softDeleteByMessageId("m-raw-soft-1", 10))
        }
        assertEquals("raw DAO soft delete is captured", "binding-a", cleanup.entries().single().credentialBindingId)
        assertTrue("the other live reference blocks the request", cleanup.pendingFor("binding-a").isEmpty())

        db.withTransaction {
            assertEquals(1, db.attachmentDao().softDeleteById("raw-soft-2", 20))
        }
        assertEquals(listOf("remote-raw-soft"), cleanup.pendingFor("binding-a"))

        db.attachmentDao().upsert(attachment("raw-soft-rollback", "m-raw-rollback", "remote-raw-rollback", "binding-a"))
        val failure = runCatching {
            db.withTransaction {
                db.attachmentDao().softDeleteById("raw-soft-rollback", 30)
                throw IllegalStateException("simulate later message transaction failure")
            }
        }
        assertTrue(failure.isFailure)
        assertNull(db.attachmentDao().byId("raw-soft-rollback")?.deletedAt)
        assertTrue(cleanup.entries().none { it.remoteFileId == "remote-raw-rollback" })
    }

    @Test
    fun `soft delete and queue enqueue roll back together if enqueue fails`() = runBlocking {
        db.attachmentDao().upsert(attachment("atomic", "m-atomic", "remote-atomic", "binding-a"))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER `fail_remote_cleanup_enqueue` BEFORE INSERT ON `remote_file_cleanup` " +
                "BEGIN SELECT RAISE(ABORT, 'injected queue failure'); END",
        )

        val failure = runCatching { AttachmentRepository(db).softDeleteForMessage("m-atomic", now = 20) }
        assertTrue("trigger failure must propagate", failure.isFailure)
        assertNull("soft-delete marker must roll back with queue insert", db.attachmentDao().byId("atomic")?.deletedAt)
        assertTrue("failed transaction must not leave a partial queue row", cleanup.entries().isEmpty())
    }

    @Test
    fun `draft discard and stale sweep enqueue remote IDs in the soft delete transaction`() = runBlocking {
        val discarded = attachment("discard", null, "remote-discard", "binding-a")
        val stale = attachment("stale", null, "remote-stale", "binding-a").copy(
            status = AttachmentStatus.DRAFT,
            createdAt = 1,
        )
        db.attachmentDao().upsert(discarded)
        db.attachmentDao().upsert(stale)
        val repository = AttachmentRepository(db)

        assertTrue(repository.discardDraft("discard", store))
        assertEquals(1, repository.sweepStaleDrafts(
            store = store,
            now = AttachmentRepository.STALE_DRAFT_TTL_MS + 10,
            ttlMs = AttachmentRepository.STALE_DRAFT_TTL_MS,
        ))

        assertEquals(listOf("remote-discard", "remote-stale"), cleanup.pendingFor("binding-a"))
    }

    @Test
    fun `AttachmentRepository clearAll also retains only remote cleanup metadata`() = runBlocking {
        db.attachmentDao().upsert(attachment("clear", "m-clear", "remote-clear", "binding-a"))

        val count = AttachmentRepository(db).clearAll(store)

        assertEquals(1, count)
        assertEquals(listOf("remote-clear"), cleanup.pendingFor("binding-a"))
        assertTrue(db.attachmentDao().byId("clear")?.deletedAt != null)
    }

    @Test
    fun `hard deletion trigger captures only the final remote reference`() = runBlocking {
        db.attachmentDao().upsert(attachment("a-1", "m-1", "remote-2", "binding-a"))
        db.attachmentDao().upsert(attachment("a-2", "m-2", "remote-2", "binding-a"))

        db.attachmentDao().deleteByIds(listOf("a-1"))
        assertTrue(cleanup.pendingFor("binding-a").isEmpty())
        db.attachmentDao().deleteByIds(listOf("a-2"))
        assertEquals(listOf("remote-2"), cleanup.pendingFor("binding-a"))
    }

    @Test
    fun `remote ID replacement queues the old binding and pending lookup is binding isolated`() = runBlocking {
        db.attachmentDao().upsert(attachment("replace", "m-replace", "remote-old", "binding-a"))
        val repository = AttachmentRepository(db)

        assertEquals(1, repository.setRemoteFile("replace", "remote-new", null, "binding-b"))

        assertEquals(listOf("remote-old"), cleanup.pendingFor("binding-a"))
        assertTrue(cleanup.pendingFor("binding-b").isEmpty())
        assertFalse(cleanup.markDeleted("remote-old", "binding-b"))
        assertTrue(cleanup.markDeleted("remote-old", "binding-a"))
        assertEquals(
            "successful remote deletion keeps the ID tombstone but clears its eligible binding",
            listOf(RemoteFileCleanupEntryForTest("remote-old", null)),
            cleanup.entries().map { RemoteFileCleanupEntryForTest(it.remoteFileId, it.credentialBindingId) },
        )
        assertTrue(cleanup.pendingFor("binding-a").isEmpty())
        assertTrue(runCatching {
            db.attachmentDao().upsert(attachment("after-delete", "m-after-delete", "remote-old", "binding-a"))
        }.isFailure)
        assertFalse(cleanup.markDeleted("remote-old", "binding-a"))
    }

    @Test
    fun `confirmed deletion cannot be marked while any live shared reference remains`() = runBlocking {
        db.attachmentDao().upsert(attachment("still-live", "m-live", "remote-shared-confirm", "binding-a"))
        assertTrue(cleanup.enqueue("remote-shared-confirm", "binding-a"))

        assertTrue(cleanup.pendingFor("binding-a").isEmpty())
        assertFalse(cleanup.markDeleted("remote-shared-confirm", "binding-a"))
        assertEquals("a stale caller cannot clear the eligible binding", "binding-a", cleanup.entries().single().credentialBindingId)

        db.attachmentDao().deleteByIds(listOf("still-live"))
        assertTrue(cleanup.markDeleted("remote-shared-confirm", "binding-a"))
        assertNull(cleanup.entries().single().credentialBindingId)
        assertTrue(cleanup.pendingFor("binding-a").isEmpty())
    }

    @Test
    fun `Room REPLACE captures displaced IDs atomically and preserves shared references`() = runBlocking {
        db.attachmentDao().upsert(attachment("rebind", "m-rebind", "remote-rebind", "binding-a"))
        assertTrue(runCatching {
            AttachmentRepository(db).setRemoteFile("rebind", "remote-rebind", null, "binding-b")
        }.isFailure)
        assertEquals("binding-a", db.attachmentDao().byId("rebind")?.remoteCredentialBindingId)
        assertTrue(cleanup.entries().none { it.remoteFileId == "remote-rebind" })

        val rollbackOldId = "remote-rollback-old"
        db.attachmentDao().upsert(attachment("replace-rollback", "m-rollback", rollbackOldId, "binding-a"))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER `fail_attachment_replace_after_insert` AFTER INSERT ON `attachment` " +
                "WHEN NEW.`id` = 'replace-rollback' " +
                "BEGIN SELECT RAISE(ABORT, 'injected replace failure'); END",
        )

        assertTrue(runCatching {
            db.attachmentDao().upsert(
                attachment("replace-rollback", "m-rollback", "remote-rollback-new", "binding-a"),
            )
        }.isFailure)
        assertEquals(rollbackOldId, db.attachmentDao().byId("replace-rollback")?.remoteFileId)
        assertTrue("failed REPLACE must roll back the trigger's tombstone too", cleanup.entries().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER `fail_attachment_replace_after_insert`")

        val sharedOldId = "remote-shared-replace-old"
        db.attachmentDao().upsert(attachment("replace-origin", "m-origin", sharedOldId, "binding-a"))
        db.attachmentDao().upsert(attachment("replace-shared", "m-shared", sharedOldId, "binding-a"))

        // A same-value Room REPLACE is an ordinary update and remains usable.
        db.attachmentDao().upsert(attachment("replace-origin", "m-origin", sharedOldId, "binding-a"))
        assertEquals(sharedOldId, db.attachmentDao().byId("replace-origin")?.remoteFileId)

        // Replacing A with B queues A even when recursive DELETE triggers are disabled.
        db.attachmentDao().upsert(
            attachment("replace-origin", "m-origin", "remote-shared-replace-new", "binding-a"),
        )
        assertEquals("remote-shared-replace-new", db.attachmentDao().byId("replace-origin")?.remoteFileId)
        assertEquals("binding-a", cleanup.entries().single().credentialBindingId)
        assertTrue("the surviving shared reference must block A", cleanup.pendingFor("binding-a").isEmpty())

        db.attachmentDao().deleteByIds(listOf("replace-shared"))
        assertEquals(listOf(sharedOldId), cleanup.pendingFor("binding-a"))
    }

    @Test
    fun `permanent tombstones block new references but preserve existing shared references`() = runBlocking {
        val retiredId = "remote-retired"
        db.attachmentDao().upsert(attachment("origin", "m-origin", retiredId, "binding-a"))
        db.attachmentDao().upsert(attachment("shared-live", "m-shared", retiredId, "binding-a"))
        db.attachmentDao().upsert(attachment("revive", "m-revive", retiredId, "binding-a"))
        db.attachmentDao().upsert(attachment("change", "m-change", "remote-other", "binding-a"))
        db.attachmentDao().deleteByIds(listOf("origin"))

        assertTrue("the existing shared reference must block cleanup", cleanup.pendingFor("binding-a").isEmpty())

        // An exact replacement/update of a row that was already live before the tombstone is harmless.
        db.attachmentDao().upsert(attachment("shared-live", "m-shared", retiredId, "binding-a"))
        assertEquals(1, AttachmentRepository(db).setRemoteFile("shared-live", retiredId, null, "binding-a"))
        assertTrue(runCatching {
            AttachmentRepository(db).setRemoteFile("shared-live", retiredId, null, "binding-b")
        }.isFailure)
        assertEquals("binding-a", db.attachmentDao().byId("shared-live")?.remoteCredentialBindingId)

        // Room upsert is INSERT OR REPLACE: it may keep the exact live row but cannot create/revive a reference.
        AttachmentRepository(db).softDeleteForMessage("m-revive", now = 20)
        assertTrue(runCatching {
            db.attachmentDao().upsert(attachment("new-ref", "m-new", retiredId, "binding-a"))
        }.isFailure)
        assertTrue(runCatching {
            db.attachmentDao().upsert(attachment("revive", "m-revive", retiredId, "binding-a"))
        }.isFailure)
        assertTrue(runCatching {
            AttachmentRepository(db).setRemoteFile("change", retiredId, null, "binding-a")
        }.isFailure)
        assertEquals("remote-other", db.attachmentDao().byId("change")?.remoteFileId)

        // A soft-deleted row cannot be resurrected through a direct UPDATE either.
        assertTrue(runCatching {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE `attachment` SET `deletedAt` = NULL WHERE `id` = ?",
                arrayOf("revive"),
            )
        }.isFailure)
        assertTrue("shared live reference still blocks cleanup", cleanup.pendingFor("binding-a").isEmpty())

        // The rejected tombstone reuse aborts its statement and Room rolls back the surrounding transaction.
        assertTrue(runCatching {
            db.withTransaction {
                db.attachmentDao().upsert(attachment("rolled-back", "m-rollback", "remote-safe", "binding-a"))
                db.attachmentDao().upsert(attachment("blocked", "m-blocked", retiredId, "binding-a"))
            }
        }.isFailure)
        assertNull(db.attachmentDao().byId("rolled-back"))

        // An unrelated remote ID remains usable after the failed transaction.
        db.attachmentDao().upsert(attachment("safe", "m-safe", "remote-safe", "binding-a"))
        assertEquals("remote-safe", db.attachmentDao().byId("safe")?.remoteFileId)

        db.attachmentDao().deleteByIds(listOf("shared-live"))
        assertEquals(listOf(retiredId), cleanup.pendingFor("binding-a"))
        assertEquals(setOf("remoteFileId", "credentialBindingId"), columnNames("remote_file_cleanup"))
    }

    @Test
    fun `any live reference blocks pending deletion even under a conflicting binding`() = runBlocking {
        db.attachmentDao().upsert(attachment("origin", "m-origin", "remote-shared-live", "binding-a"))
        db.attachmentDao().upsert(attachment("live-other", "m-live", "remote-shared-live", "binding-b"))
        db.attachmentDao().deleteByIds(listOf("origin"))

        assertTrue(cleanup.pendingFor("binding-a").isEmpty())
        db.attachmentDao().deleteByIds(listOf("live-other"))
        assertTrue(cleanup.pendingFor("binding-a").isEmpty())
        assertNull("different binding collision must downgrade queued ID", cleanup.entries().single().credentialBindingId)
    }

    @Test
    fun `different bindings for the same file permanently downgrade the row to unknown`() = runBlocking {
        assertTrue(cleanup.enqueue("remote-shared", "binding-a"))
        assertTrue(cleanup.enqueue("remote-shared", "binding-b"))

        val entry = cleanup.entries().single()
        assertEquals("remote-shared", entry.remoteFileId)
        assertNull(entry.credentialBindingId)
        assertTrue(cleanup.pendingFor("binding-a").isEmpty())
        assertTrue(cleanup.pendingFor("binding-b").isEmpty())
        assertFalse(cleanup.hasKnownBindingRecords())
        assertFalse("unknown ownership cannot be confirmed under an old binding", cleanup.markDeleted("remote-shared", "binding-a"))
        assertTrue(cleanup.enqueue("remote-shared", "binding-a"))
        assertNull("a later known enqueue cannot promote a NULL tombstone", cleanup.entries().single().credentialBindingId)
        assertTrue(cleanup.pendingFor("binding-a").isEmpty())
    }

    @Test
    fun `legacy unknown file IDs are retained but never returned for network deletion`() = runBlocking {
        db.attachmentDao().upsert(attachment("legacy", "m-legacy", "remote-legacy", null))
        db.attachmentDao().deleteByIds(listOf("legacy"))

        assertNull(cleanup.entries().single().credentialBindingId)
        assertTrue(cleanup.pendingFor("a-new-binding").isEmpty())
        assertTrue(cleanup.enqueue("remote-legacy", "a-new-binding"))
        assertNull("unknown legacy ownership cannot be promoted", cleanup.entries().single().credentialBindingId)
        assertTrue(cleanup.pendingFor("a-new-binding").isEmpty())
    }

    @Test
    fun `clearAllTables leaves its minimal cleanup rows and a failed transaction rolls them back`() = runBlocking {
        db.attachmentDao().upsert(attachment("wipe", "m-wipe", "remote-wipe", "binding-a"))

        withContext(Dispatchers.IO) { db.clearAllTables() }
        assertEquals(listOf("remote-wipe"), cleanup.pendingFor("binding-a"))
        assertNull(db.attachmentDao().byId("wipe"))
        assertTrue(runCatching {
            db.attachmentDao().upsert(attachment("wipe-reuse", "m-wipe-reuse", "remote-wipe", "binding-a"))
        }.isFailure)
        assertEquals("clearAllTables must leave the ID tombstone", "binding-a", cleanup.entries().single().credentialBindingId)

        db.attachmentDao().upsert(attachment("rollback", "m-rollback", "remote-rollback", "binding-a"))
        try {
            db.withTransaction {
                db.openHelper.writableDatabase.execSQL("DELETE FROM `attachment`")
                throw IllegalStateException("simulate interruption before commit")
            }
        } catch (_: IllegalStateException) {
            // Expected: Room rolls back both attachment deletion and trigger enqueue.
        }
        assertTrue(cleanup.entries().none { it.remoteFileId == "remote-rollback" })
        assertTrue(db.attachmentDao().byId("rollback")?.deletedAt == null)
    }

    @Test
    fun `confirmed tombstone survives reopen without pending retry`() = runBlocking {
        db.close()
        context.deleteDatabase(PERSISTENT_DB_NAME)
        var persistent = persistentDatabase()
        try {
            val queue = RemoteFileCleanupRepository(persistent)
            queue.enqueue("remote-persist", "binding-a")
            assertTrue(queue.markDeleted("remote-persist", "binding-a"))
            assertFalse(queue.markDeleted("remote-persist", "binding-a"))
            assertTrue("confirmed NULL tombstones cannot be promoted by re-enqueue", queue.enqueue("remote-persist", "binding-a"))
            assertTrue(queue.pendingFor("binding-a").isEmpty())
        } finally {
            persistent.close()
        }

        persistent = persistentDatabase()
        try {
            val reopenedQueue = RemoteFileCleanupRepository(persistent)
            assertTrue("a confirmed tombstone must not be pending after restart", reopenedQueue.pendingFor("binding-a").isEmpty())
            assertNull(reopenedQueue.entries().single().credentialBindingId)
            assertTrue(runCatching {
                persistent.attachmentDao().upsert(attachment("reused-after-restart", "m-reuse", "remote-persist", "binding-a"))
            }.isFailure)
        } finally {
            persistent.close()
        }
        db = inMemoryDatabase()
        cleanup = RemoteFileCleanupRepository(db)
    }

    @Test
    fun `clearAllTables tombstone survives persistent reopen and still blocks reuse`() = runBlocking {
        context.deleteDatabase(PERSISTENT_DB_NAME)
        var persistent = persistentDatabase()
        try {
            persistent.attachmentDao().upsert(attachment("wipe-persist", "m-wipe-persist", "remote-wipe-persist", "binding-a"))
            withContext(Dispatchers.IO) { persistent.clearAllTables() }
            assertEquals(
                "binding-a",
                RemoteFileCleanupRepository(persistent).entries().single().credentialBindingId,
            )
        } finally {
            persistent.close()
        }

        persistent = persistentDatabase()
        try {
            val reopenedQueue = RemoteFileCleanupRepository(persistent)
            assertEquals(listOf("remote-wipe-persist"), reopenedQueue.pendingFor("binding-a"))
            assertTrue(runCatching {
                persistent.attachmentDao().upsert(
                    attachment("wipe-persist-reuse", "m-reuse", "remote-wipe-persist", "binding-a"),
                )
            }.isFailure)
            assertEquals(1, reopenedQueue.entries().size)
        } finally {
            persistent.close()
        }
    }

    private fun inMemoryDatabase(): PulsezDatabase =
        Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(queueSchemaCallback())
            .build()

    private fun persistentDatabase(): PulsezDatabase =
        Room.databaseBuilder(context, PulsezDatabase::class.java, PERSISTENT_DB_NAME)
            .allowMainThreadQueries()
            .addCallback(queueSchemaCallback())
            .build()

    private fun queueSchemaCallback() = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            PulsezDatabase.installRemoteFileCleanupQueue(db)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            PulsezDatabase.installRemoteFileCleanupQueue(db)
        }
    }

    private fun attachment(
        id: String,
        messageId: String?,
        remoteFileId: String?,
        bindingId: String?,
    ) = AttachmentEntity(
        id = id,
        messageId = messageId,
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
        deletedAt = null,
        createdAt = 1,
        ownerThreadId = null,
        remoteCredentialBindingId = bindingId,
    )

    private fun columnNames(table: String): Set<String> =
        db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                val index = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(index))
            }
        }

    private data class RemoteFileCleanupEntryForTest(val remoteFileId: String, val credentialBindingId: String?)

    private companion object {
        const val PERSISTENT_DB_NAME = "remote_file_cleanup_test.db"
    }
}
