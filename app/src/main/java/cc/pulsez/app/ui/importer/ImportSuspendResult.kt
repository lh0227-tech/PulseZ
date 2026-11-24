package cc.pulsez.app.ui.importer

import kotlinx.coroutines.CancellationException

/**
 * Keep best-effort suspend operations' ordinary failure fallback while allowing coroutine
 * cancellation to reach the caller. Evidence: ImportViewModel.kt:271 explicitly rethrows
 * CancellationException rather than reporting a cancelled suspend operation as an import error.
 */
internal suspend fun <T> runImportOperationCatchingFailure(
    operation: suspend () -> T,
): Result<T> = try {
    Result.success(operation())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}
