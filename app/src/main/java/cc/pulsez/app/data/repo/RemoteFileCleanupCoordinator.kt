package cc.pulsez.app.data.repo

import cc.pulsez.app.AppContainer
import cc.pulsez.app.net.DeleteFileResult
import cc.pulsez.app.net.FilesClient
import cc.pulsez.app.net.isDeepSeekEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Narrow seam for offline tests; production adapts a session-bound [FilesClient] to this interface. */
internal fun interface RemoteFileDeleteGateway {
    suspend fun deleteFile(fileId: String): DeleteFileResult
}

/**
 * Process-scoped retry worker for durable remote-file tombstones.
 *
 * Retry signals are conflated while the worker is busy; the worker serializes scans and resolves one immutable
 * credential session per scan. The database is queried before network work and updated only after a confirmed
 * delete, so no Room transaction is held across HTTP. A null/unknown binding, non-DeepSeek endpoint, missing
 * credential, failed request, 404, or cancellation never clears the tombstone.
 *
 * Basis: `RemoteFileCleanupRepository.kt` `pendingFor` and `markDeleted` enforce the exact binding,
 * no-live-reference, and permanent-ID-tombstone rules; `FilesClient.kt` returns [DeleteFileResult.Deleted] only
 * for a matching service ID and `deleted=true` (https://api-docs.deepseek.com/guides/files_api/). The Kotlin
 * [Channel.CONFLATED] API retains at most one pending signal while the worker is busy
 * (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-channel/-factory/-c-o-n-f-l-a-t-e-d.html).
 */
internal class RemoteFileCleanupCoordinator(
    scope: CoroutineScope,
    private val resolveSession: suspend () -> AppContainer.ChatEndpointSession?,
    private val pendingFor: suspend (credentialBindingId: String) -> List<String>,
    private val markDeleted: suspend (remoteFileId: String, credentialBindingId: String) -> Boolean,
    private val createGateway: (session: AppContainer.ChatEndpointSession) -> RemoteFileDeleteGateway,
) {
    private val retrySignals = Channel<Unit>(capacity = Channel.CONFLATED)
    private val scanMutex = Mutex()

    init {
        scope.launch {
            while (true) {
                retrySignals.receive()
                // A failed DB read or client construction is retryable on the next signal; do not log any details.
                try {
                    retryOnce()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Cleanup is best-effort and the permanent tombstone remains available for a later retry.
                }
            }
        }
    }

    /** Enqueue a background scan without blocking the caller or exposing credential material. */
    fun requestRetry() {
        retrySignals.trySend(Unit)
    }

    /** A single serialized pass, exposed internally for deterministic coroutine tests. */
    internal suspend fun retryOnce() = scanMutex.withLock {
        val session = resolveSession() ?: return@withLock
        val bindingId = session.remoteCredentialBindingId?.takeIf(String::isNotBlank) ?: return@withLock
        if (session.apiKey.isBlank() || !isDeepSeekEndpoint(session.baseUrl)) return@withLock

        // The exact session is captured before querying candidates and remains fixed through all HTTP calls.
        val fileIds = pendingFor(bindingId).asSequence().filter(String::isNotBlank).distinct().toList()
        if (fileIds.isEmpty()) return@withLock
        val gateway = createGateway(session)

        for (fileId in fileIds) {
            val result = try {
                gateway.deleteFile(fileId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A transport/client failure leaves this file pending and does not prevent later IDs from trying.
                continue
            }
            if (result is DeleteFileResult.Deleted && result.fileId == fileId) {
                // Repository performs a second atomic live-reference check before clearing the exact binding.
                markDeleted(fileId, bindingId)
            }
        }
    }
}
