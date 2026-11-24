package cc.pulsez.app.data.repo

import androidx.room.withTransaction
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.DeletedCharacterEntity
import cc.pulsez.app.data.db.PendingAttachmentDeleteEntity
import cc.pulsez.app.data.db.PulsezDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CharacterDeletionResult(
    val deletedCount: Int,
    val pendingAttachmentFiles: Int,
)

/** Permanent, role-scoped deletion. Shared group history is retained; late writes are blocked by DB guards. */
class CharacterDeletionRepository(
    private val db: PulsezDatabase,
    private val onCharactersDeleted: (suspend (List<String>) -> Unit)? = null,
    private val deleteFile: suspend (AttachmentStore, String) -> Unit = { store, path ->
        withContext(Dispatchers.IO) { store.delete(path) }
        Unit
    },
) {
    suspend fun deleteCharacters(
        characterIds: Collection<String>,
        store: AttachmentStore,
        at: Long = System.currentTimeMillis(),
    ): CharacterDeletionResult {
        val ids = characterIds.asSequence().filter(String::isNotBlank).distinct().toList()
        if (ids.isEmpty()) return CharacterDeletionResult(0, 0)

        val prepared = db.withTransaction {
            val liveIds = ids.filter { db.characterDao().byId(it) != null }
            if (liveIds.isEmpty()) return@withTransaction PreparedDeletion(emptyList(), emptyList())

            liveIds.forEach { id -> db.deletedCharacterDao().tombstone(DeletedCharacterEntity(id, at)) }

            val attachments = liveIds.flatMap { id -> db.attachmentDao().forCharacterSingleChats(id) }
                .distinctBy { it.id }
            val cleanupRows = attachments.map { attachment ->
                PendingAttachmentDeleteEntity(
                    attachmentId = attachment.id,
                    localPath = attachment.localPath,
                    requestedAt = at,
                )
            }
            if (cleanupRows.isNotEmpty()) {
                db.pendingAttachmentDeleteDao().upsertAll(cleanupRows)
                db.attachmentDao().deleteByIds(cleanupRows.map { it.attachmentId })
            }

            for (characterId in liveIds) {
                val roleThreads = db.threadDao().singleThreadIdsForCharacter(characterId)
                val groups = db.importBatchDao().groupsForCharacter(characterId)
                groups.forEach { group ->
                    db.importBatchDao().deleteWindowsForGroup(group.batchId, group.id)
                }
                db.importBatchDao().deleteSourcesForCharacter(characterId)
                db.importBatchDao().deleteGroupsForCharacter(characterId)

                // Group messages and group thread state stay. Only this role's private memory and membership go.
                db.memoryDao().deleteForCharacterOwner(characterId)
                db.threadMemberDao().removeForCharacter(characterId)

                roleThreads.forEach { threadId ->
                    db.threadStateDao().deleteForThread(threadId)
                    db.memoryDao().deleteForThread(threadId)
                    db.chunkDao().deleteTermsForThread(threadId)
                    db.chunkDao().deleteForThread(threadId)
                    db.messageDao().deleteForThread(threadId)
                    db.segmentDao().deleteForThread(threadId)
                    db.threadDao().deleteById(threadId)
                }
                db.characterDao().deleteById(characterId)
            }
            PreparedDeletion(liveIds, cleanupRows.map { it.attachmentId })
        }

        val unresolved = retryPendingAttachmentFiles(store, prepared.attachmentIds.toSet())
        if (prepared.characterIds.isNotEmpty()) {
            try {
                onCharactersDeleted?.invoke(prepared.characterIds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Best-effort cleanup of auxiliary awakening cache.
            }
        }
        return CharacterDeletionResult(prepared.characterIds.size, unresolved)
    }

    /** Retry all previously recorded file removals. Called at startup and after a deletion attempt. */
    suspend fun retryPendingAttachmentFiles(store: AttachmentStore): Int =
        retryPendingAttachmentFiles(store, onlyIds = null)

    private suspend fun retryPendingAttachmentFiles(
        store: AttachmentStore,
        onlyIds: Set<String>?,
    ): Int {
        val rows = db.pendingAttachmentDeleteDao().all().filter { onlyIds == null || it.attachmentId in onlyIds }
        for (row in rows) {
            try {
                deleteFile(store, row.localPath)
                db.pendingAttachmentDeleteDao().delete(row.attachmentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                db.pendingAttachmentDeleteDao().recordAttempt(row.attachmentId, System.currentTimeMillis())
            }
        }
        return db.pendingAttachmentDeleteDao().all().count { onlyIds == null || it.attachmentId in onlyIds }
    }

    private data class PreparedDeletion(
        val characterIds: List<String>,
        val attachmentIds: List<String>,
    )
}
