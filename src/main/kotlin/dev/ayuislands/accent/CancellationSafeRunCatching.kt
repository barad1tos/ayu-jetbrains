package dev.ayuislands.accent

/**
 * [kotlin.runCatching] variant that rethrows
 * [kotlin.coroutines.cancellation.CancellationException] instead of capturing it in the
 * returned [Result].
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
 * Package-internal because every caller today sits in `dev.ayuislands.accent`; lift
 * further if a caller from another package ever needs the same contract.
 */
internal inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> {
    val result = runCatching(block)
    val cause = result.exceptionOrNull()
    if (cause is kotlin.coroutines.cancellation.CancellationException) throw cause
    return result
}
