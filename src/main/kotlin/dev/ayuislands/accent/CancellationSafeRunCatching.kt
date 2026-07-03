package dev.ayuislands.accent

import com.intellij.openapi.progress.ProcessCanceledException
import kotlin.coroutines.cancellation.CancellationException

/**
 * [kotlin.runCatching] variant that rethrows platform and coroutine cancellation
 * instead of capturing it in the returned [Result].
 *
 * The resolver chain ([AccentResolver.projectKey], [ProjectLanguageDetector.dominant]) is
 * reachable from `AyuIslandsStartupActivity.execute`'s coroutine body; swallowing
 * cancellation there would let the coroutine continue past its scope's cancellation,
 * breaking structured concurrency. Other `Throwable`s (including `Error` subtypes like
 * `NoClassDefFoundError` from a mid-dispose race on a lazily-loaded service accessor) keep
 * the existing fall-back-to-failure-result behavior.
 *
 * Only the top-level exception is inspected. If an IntelliJ platform API catches a
 * `CancellationException` and rewraps it (e.g. `throw IllegalStateException("disposed",
 * cancellationException)`), the wrapper is NOT itself a `CancellationException` and the
 * cancellation signal is lost. No current caller sits behind such a rewrap, but if a
 * future call site does, walk the `cause` chain before returning the Result.
 *
 * Internal (module-wide) rather than private: callers include both `dev.ayuislands.accent`
 * and `dev.ayuislands.reapply.ThemeReapplication.runPlan`. Lift to `public` only if a caller
 * outside this module ever needs the same contract.
 */
internal inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> {
    val result = runCatching(block)
    val cause = result.exceptionOrNull()
    if (cause is ProcessCanceledException) throw cause
    if (cause is CancellationException) throw cause
    return result
}
