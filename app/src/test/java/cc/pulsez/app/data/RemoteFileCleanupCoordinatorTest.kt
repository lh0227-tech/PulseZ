package cc.pulsez.app.data

import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.repo.RemoteFileCleanupCoordinator
import cc.pulsez.app.data.repo.RemoteFileDeleteGateway
import cc.pulsez.app.net.DeleteFileResult
import cc.pulsez.app.net.FilesClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RemoteFileCleanupCoordinatorTest {

    @Test
    fun `credential rotation cleans only exact binding and reentry retries the old binding`() = runBlocking {
        // Saved session text may be scheme-less; the real FilesClient must still form an absolute HTTPS URL.
        assertEquals("https://api.deepseek.com/files", FilesClient.filesEndpointFor("api.deepseek.com"))
        assertEquals("https://api.deepseek.com/files", FilesClient.filesEndpointFor("api.deepseek.com/v1"))

        val queue = FakeQueue().apply {
            add("file-a", "binding-a")
            add("file-b", "binding-b")
        }
        var currentSession: AppContainer.ChatEndpointSession? = session("api.deepseek.com", "key-a", "binding-a")
        val calls = mutableListOf<DeleteCall>()
        val rootJob = SupervisorJob()
        val coordinator = coordinator(
            scope = CoroutineScope(rootJob + Dispatchers.Unconfined),
            session = { currentSession },
            queue = queue,
            gateway = { captured -> RemoteFileDeleteGateway { fileId ->
                calls += deleteCall(captured, fileId)
                DeleteFileResult.Deleted(fileId)
            } },
        )

        try {
            coordinator.requestRetry()
            assertEquals(listOf("file-b"), queue.pendingFor("binding-b"))
            assertEquals(listOf(deleteCall("api.deepseek.com", "key-a", "file-a")), calls)
            assertNull(queue.bindingOf("file-a"))

            currentSession = session("api.deepseek.com/v1", "key-b", "binding-b")
            coordinator.requestRetry()
            assertNull(queue.bindingOf("file-b"))
            assertEquals("each request retains the credential snapshot that selected its binding", listOf(
                deleteCall("api.deepseek.com", "key-a", "file-a"),
                deleteCall("api.deepseek.com/v1", "key-b", "file-b"),
            ), calls)

            currentSession = session("api.deepseek.com", "key-a", "binding-a")
            coordinator.requestRetry()
            assertEquals("confirmed IDs remain terminal and are not reused", 2, calls.size)
            assertTrue(queue.pendingFor("binding-a").isEmpty())
        } finally {
            rootJob.cancelAndJoin()
        }
    }

    @Test
    fun `missing credential waits and reentering the same credential retries`() = runBlocking {
        val queue = FakeQueue().apply { add("file-a", "binding-a") }
        var currentSession: AppContainer.ChatEndpointSession? = null
        val calls = mutableListOf<String>()
        val rootJob = SupervisorJob()
        val coordinator = coordinator(
            scope = CoroutineScope(rootJob + Dispatchers.Unconfined),
            session = { currentSession },
            queue = queue,
            gateway = { RemoteFileDeleteGateway { id ->
                calls += id
                DeleteFileResult.Deleted(id)
            } },
        )

        try {
            coordinator.requestRetry()
            assertTrue(calls.isEmpty())
            assertEquals(listOf("file-a"), queue.pendingFor("binding-a"))

            currentSession = session("api.deepseek.com", "key-a", "binding-a")
            coordinator.requestRetry()
            assertEquals(listOf("file-a"), calls)
            assertNull(queue.bindingOf("file-a"))
        } finally {
            rootJob.cancelAndJoin()
        }
    }

    @Test
    fun `404 failure leaves tombstone pending for a later retry`() = runBlocking {
        val queue = FakeQueue().apply { add("file-a", "binding-a") }
        val rootJob = SupervisorJob()
        var result: DeleteFileResult = DeleteFileResult.Failed(
            reason = DeleteFileResult.FailureReason.HTTP_ERROR,
            httpCode = 404,
        )
        var calls = 0
        val coordinator = coordinator(
            scope = CoroutineScope(rootJob + Dispatchers.Unconfined),
            session = { session("api.deepseek.com", "key-a", "binding-a") },
            queue = queue,
            gateway = { RemoteFileDeleteGateway { calls++; result } },
        )

        try {
            coordinator.requestRetry()
            assertEquals("binding-a", queue.bindingOf("file-a"))
            assertEquals(listOf("file-a"), queue.pendingFor("binding-a"))

            result = DeleteFileResult.Deleted("different-id")
            coordinator.requestRetry()
            assertEquals("a confirmation for another ID cannot clear this tombstone", "binding-a", queue.bindingOf("file-a"))

            result = DeleteFileResult.Deleted("file-a")
            coordinator.requestRetry()
            assertEquals(3, calls)
            assertNull(queue.bindingOf("file-a"))
        } finally {
            rootJob.cancelAndJoin()
        }
    }

    @Test
    fun `live reference blocks gateway creation`() = runBlocking {
        val queue = FakeQueue().apply { add("file-live", "binding-a", hasLiveReference = true) }
        val rootJob = SupervisorJob()
        var gatewayCount = 0
        val coordinator = coordinator(
            scope = CoroutineScope(rootJob + Dispatchers.Unconfined),
            session = { session("api.deepseek.com", "key-a", "binding-a") },
            queue = queue,
            gateway = {
                gatewayCount++
                RemoteFileDeleteGateway { DeleteFileResult.Deleted(it) }
            },
        )

        try {
            coordinator.requestRetry()
            assertEquals(0, gatewayCount)
            assertEquals("binding-a", queue.bindingOf("file-live"))
        } finally {
            rootJob.cancelAndJoin()
        }
    }

    @Test
    fun `non DeepSeek session never constructs delete gateway`() = runBlocking {
        val queue = FakeQueue().apply { add("file-a", "binding-a") }
        val rootJob = SupervisorJob()
        var gatewayCount = 0
        val coordinator = coordinator(
            scope = CoroutineScope(rootJob + Dispatchers.Unconfined),
            session = { session("api.openai.com/v1", "key-a", "binding-a") },
            queue = queue,
            gateway = {
                gatewayCount++
                RemoteFileDeleteGateway { DeleteFileResult.Deleted(it) }
            },
        )

        try {
            coordinator.requestRetry()
            assertEquals(0, gatewayCount)
            assertEquals(listOf("file-a"), queue.pendingFor("binding-a"))
        } finally {
            rootJob.cancelAndJoin()
        }
    }

    @Test
    fun `credential snapshot stays pinned while settings rotate during request`() = runBlocking {
        val queue = FakeQueue().apply {
            add("file-a", "binding-a")
            add("file-b", "binding-b")
        }
        var currentSession = session("api.deepseek.com", "key-a", "binding-a")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<DeleteCall>()
        val rootJob = SupervisorJob()
        val coordinator = coordinator(
            scope = CoroutineScope(rootJob + Dispatchers.Unconfined),
            session = { currentSession },
            queue = queue,
            gateway = { captured -> RemoteFileDeleteGateway { fileId ->
                calls += deleteCall(captured, fileId)
                started.complete(Unit)
                release.await()
                DeleteFileResult.Deleted(fileId)
            } },
        )

        try {
            coordinator.requestRetry()
            started.await()
            currentSession = session("api.deepseek.com/v1", "key-b", "binding-b")
            release.complete(Unit)
            withTimeout(2_000) {
                while (queue.bindingOf("file-a") != null) kotlinx.coroutines.yield()
            }
            assertEquals(listOf(deleteCall("api.deepseek.com", "key-a", "file-a")), calls)
            assertEquals("binding-b", queue.bindingOf("file-b"))
        } finally {
            rootJob.cancelAndJoin()
        }
    }

    @Test
    fun `overlapping retry signals are conflated and scans remain serial`() = runBlocking {
        val queue = FakeQueue().apply { add("file-a", "binding-a") }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gatewayCalls = AtomicInteger()
        val activeCalls = AtomicInteger()
        val maxActiveCalls = AtomicInteger()
        val sessionResolutions = AtomicInteger()
        val rootJob = SupervisorJob()
        val coordinator = coordinator(
            scope = CoroutineScope(rootJob + Dispatchers.Unconfined),
            session = {
                sessionResolutions.incrementAndGet()
                session("api.deepseek.com", "key-a", "binding-a")
            },
            queue = queue,
            gateway = { RemoteFileDeleteGateway { fileId ->
                gatewayCalls.incrementAndGet()
                val active = activeCalls.incrementAndGet()
                maxActiveCalls.updateAndGet { prior -> maxOf(prior, active) }
                started.complete(Unit)
                release.await()
                activeCalls.decrementAndGet()
                DeleteFileResult.Deleted(fileId)
            } },
        )

        try {
            coordinator.requestRetry()
            started.await()
            repeat(50) { coordinator.requestRetry() }
            release.complete(Unit)
            withTimeout(2_000) {
                while (queue.bindingOf("file-a") != null) kotlinx.coroutines.yield()
            }

            assertEquals(1, gatewayCalls.get())
            assertEquals(1, maxActiveCalls.get())
            assertTrue("the 50 overlapping triggers coalesce to at most one follow-up scan", sessionResolutions.get() <= 2)
        } finally {
            rootJob.cancelAndJoin()
        }
    }

    @Test
    fun `cancellation leaves pending tombstone intact`() = runBlocking {
        val queue = FakeQueue().apply { add("file-a", "binding-a") }
        val started = CompletableDeferred<Unit>()
        val blockForever = CompletableDeferred<Unit>()
        val rootJob = SupervisorJob()
        val coordinator = coordinator(
            scope = CoroutineScope(rootJob + Dispatchers.Unconfined),
            session = { session("api.deepseek.com", "key-a", "binding-a") },
            queue = queue,
            gateway = { RemoteFileDeleteGateway { _ ->
                started.complete(Unit)
                blockForever.await()
                error("unreachable")
            } },
        )

        try {
            val attempt = launch { coordinator.retryOnce() }
            started.await()
            attempt.cancelAndJoin()
            assertEquals("binding-a", queue.bindingOf("file-a"))
            assertEquals(listOf("file-a"), queue.pendingFor("binding-a"))
        } finally {
            rootJob.cancelAndJoin()
        }
    }

    private fun coordinator(
        scope: CoroutineScope,
        session: suspend () -> AppContainer.ChatEndpointSession?,
        queue: FakeQueue,
        gateway: (AppContainer.ChatEndpointSession) -> RemoteFileDeleteGateway,
    ) = RemoteFileCleanupCoordinator(
        scope = scope,
        resolveSession = session,
        pendingFor = queue::pendingFor,
        markDeleted = queue::markDeleted,
        createGateway = gateway,
    )

    private fun session(baseUrl: String, apiKey: String, bindingId: String) =
        AppContainer.ChatEndpointSession(baseUrl, apiKey, bindingId)

    private fun deleteCall(baseUrl: String, apiKey: String, fileId: String) =
        DeleteCall(baseUrl, FilesClient.filesEndpointFor(baseUrl), apiKey, fileId)

    private fun deleteCall(session: AppContainer.ChatEndpointSession, fileId: String) =
        deleteCall(session.baseUrl, session.apiKey, fileId)

    private data class DeleteCall(
        val sessionBaseUrl: String,
        val requestEndpoint: String,
        val apiKey: String,
        val fileId: String,
    )

    private class FakeQueue {
        private data class Entry(var bindingId: String?, var hasLiveReference: Boolean)

        private val entries = linkedMapOf<String, Entry>()

        fun add(id: String, bindingId: String, hasLiveReference: Boolean = false) {
            entries[id] = Entry(bindingId, hasLiveReference)
        }

        fun bindingOf(id: String): String? = entries[id]?.bindingId

        suspend fun pendingFor(bindingId: String): List<String> = entries
            .filter { (_, entry) -> entry.bindingId == bindingId && !entry.hasLiveReference }
            .keys
            .toList()

        suspend fun markDeleted(fileId: String, bindingId: String): Boolean {
            val entry = entries[fileId] ?: return false
            if (entry.bindingId != bindingId || entry.hasLiveReference) return false
            entry.bindingId = null
            return true
        }
    }
}
