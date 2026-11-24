package cc.pulsez.app.ui.importer

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportSuspendResultTest {

    @Test
    fun ordinarySuspendFailureKeepsBestEffortResultSemantics() = runBlocking {
        val result = runImportOperationCatchingFailure<Int> {
            throw IOException("synthetic failure")
        }

        assertTrue(result.isFailure)
        assertEquals(IOException::class.java, result.exceptionOrNull()?.javaClass)
    }

    @Test
    fun suspendCancellationIsRethrownWithoutBecomingAnOrdinaryFailure() = runBlocking {
        val cancelled = CancellationException("synthetic cancellation")
        var caught: CancellationException? = null
        try {
            runImportOperationCatchingFailure<Int> { throw cancelled }
        } catch (failure: CancellationException) {
            caught = failure
        }

        assertSame(cancelled, caught)
    }
}
