package cc.pulsez.app.data.repo

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import cc.pulsez.app.data.db.PulsezDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One permanent tombstone; this intentionally contains only the remote file ID and nullable local binding ID. */
internal data class RemoteFileCleanupEntry(
    val remoteFileId: String,
    val credentialBindingId: String?,
)

/**
 * Durable tombstones for remote file IDs whose local references were removed, plus retry candidates.
 * There are no attempt counters, timestamps, or extra completion field. A confirmed delete changes the binding
 * to null while keeping the ID permanently reserved. Null also represents unknown/conflicting ownership; neither
 * state is returned to a caller for network use, and later enqueues cannot promote it back to a known binding.
 */
class RemoteFileCleanupRepository(private val db: PulsezDatabase) {

    /** Enqueue explicitly from a soft-delete transaction. Hard deletes are captured by the DB trigger. */
    suspend fun enqueue(remoteFileId: String?, credentialBindingId: String?): Boolean {
        if (remoteFileId.isNullOrBlank()) return false
        return db.withTransaction {
            enqueueInCurrentTransaction(remoteFileId, credentialBindingId)
            true
        }
    }

    /**
     * Only known records with an exact binding and no live attachment reference are eligible.
     * A live reference with the same remote ID blocks every binding, including a conflicting one. A null binding
     * means unknown, conflicting, or already confirmed and is never eligible.
     */
    suspend fun pendingFor(credentialBindingId: String): List<String> = withContext(Dispatchers.IO) {
        if (credentialBindingId.isBlank()) return@withContext emptyList()
        val query = SimpleSQLiteQuery(
            "SELECT q.`remoteFileId` FROM `remote_file_cleanup` q " +
                "WHERE q.`credentialBindingId` = ? " +
                "AND NOT EXISTS (SELECT 1 FROM `attachment` a " +
                "WHERE a.`remoteFileId` = q.`remoteFileId` AND a.`deletedAt` IS NULL) " +
                "ORDER BY q.`remoteFileId`",
            arrayOf(credentialBindingId),
        )
        db.openHelper.readableDatabase.query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    /**
     * Record a caller-confirmed successful remote delete by clearing only its exact binding. The two-field row
     * remains permanently as an ID reservation; null is terminal for cleanup and cannot be upgraded by enqueue.
     * The single UPDATE rechecks live references atomically, so a stale caller cannot confirm while any reference
     * remains. Call only after the service confirmed success; failures and 404 responses must not call this.
     */
    suspend fun markDeleted(remoteFileId: String, credentialBindingId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (remoteFileId.isBlank() || credentialBindingId.isBlank()) return@withContext false
            val statement = db.openHelper.writableDatabase.compileStatement(
                "UPDATE `remote_file_cleanup` SET `credentialBindingId` = NULL " +
                    "WHERE `remoteFileId` = ? AND `credentialBindingId` = ? " +
                    "AND NOT EXISTS(SELECT 1 FROM `attachment` a " +
                    "WHERE a.`remoteFileId` = ? AND a.`deletedAt` IS NULL)",
            )
            try {
                statement.bindString(1, remoteFileId)
                statement.bindString(2, credentialBindingId)
                statement.bindString(3, remoteFileId)
                statement.executeUpdateDelete() == 1
            } finally {
                statement.close()
            }
        }

    /**
     * A missing HMAC alias may be recreated only if there are no records whose binding it would validate.
     * Soft-deleted attachment rows count too, until physically removed.
     */
    suspend fun hasKnownBindingRecords(): Boolean = withContext(Dispatchers.IO) {
        val query = SimpleSQLiteQuery(
            "SELECT EXISTS(SELECT 1 FROM `attachment` WHERE `remoteCredentialBindingId` IS NOT NULL) " +
                "OR EXISTS(SELECT 1 FROM `remote_file_cleanup` WHERE `credentialBindingId` IS NOT NULL)",
        )
        db.openHelper.readableDatabase.query(query).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) != 0
        }
    }

    /** Raw enqueue used only inside the same Room transaction as a soft delete. */
    internal fun enqueueInCurrentTransaction(remoteFileId: String?, credentialBindingId: String?) {
        if (remoteFileId.isNullOrBlank()) return
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR IGNORE INTO `remote_file_cleanup` (`remoteFileId`, `credentialBindingId`) VALUES (?, ?)",
            arrayOf<Any?>(remoteFileId, credentialBindingId),
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE `remote_file_cleanup` SET `credentialBindingId` = " +
                "CASE WHEN `credentialBindingId` IS ? THEN `credentialBindingId` ELSE NULL END " +
                "WHERE `remoteFileId` = ?",
            arrayOf<Any?>(credentialBindingId, remoteFileId),
        )
    }

    /** Test-only visibility for unknown/conflicting rows; returns no data beyond the two stored fields. */
    internal suspend fun entries(): List<RemoteFileCleanupEntry> = withContext(Dispatchers.IO) {
        val query = SimpleSQLiteQuery(
            "SELECT `remoteFileId`, `credentialBindingId` FROM `remote_file_cleanup` ORDER BY `remoteFileId`",
        )
        db.openHelper.readableDatabase.query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RemoteFileCleanupEntry(
                            remoteFileId = cursor.getString(0),
                            credentialBindingId = if (cursor.isNull(1)) null else cursor.getString(1),
                        ),
                    )
                }
            }
        }
    }
}
