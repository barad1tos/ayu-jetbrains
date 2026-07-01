package dev.ayuislands.accent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * Dedup'd background scheduler for [ProjectLanguageScanner] runs.
 *
 * [ProjectLanguageDetector.dominant] is reachable from the EDT via settings UI,
 * focus-swap listeners, and rotation ticks. A first-call scan over a 10 000-file
 * monorepo takes hundreds of milliseconds — unacceptable on EDT. This scheduler
 * lets the detector kick the scan onto a shared pool, return null immediately
 * (caller falls through to the global accent), and hit the warm cache on the
 * next call.
 *
 * Dedup gate: multiple simultaneous `dominant()` calls for the same project key
 * must not schedule redundant scans. The in-flight set is a keyset-backed
 * ConcurrentHashMap so `add` / `remove` are atomic without separate locking.
 */
internal object ProjectLanguageScanAsync {
    private val LOG = logger<ProjectLanguageScanAsync>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    internal enum class ScanResult {
        Completed,
        Unavailable,
    }

    /** True when a scan for [key] is currently scheduled or running. */
    fun isInFlight(key: String): Boolean = key in inFlight

    /**
     * Schedule [task] on the IDE's shared application-pool executor, gated on
     * [key] so duplicate schedulings for the same canonical project path become no-ops.
     *
     * Returns true when the task was actually scheduled, false when a prior scan
     * for the same key is still in flight — callers can use the return value to
     * decide whether to fall through to a synchronous path or simply bail out.
     */
    fun schedule(
        project: Project,
        key: String,
        task: () -> ScanResult,
    ): Boolean {
        if (!inFlight.add(key)) return false
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val result = runWithProjectProgress(project, key, task)
                if (result == ScanResult.Unavailable) {
                    LOG.debug("Background language scan for '$key' finished unavailable; cache unchanged")
                }
            } catch (exception: ProcessCanceledException) {
                LOG.debug("Background language scan for '$key' canceled; cache unchanged", exception)
            } catch (exception: kotlin.coroutines.cancellation.CancellationException) {
                // Pool-level cancellation: IDE is shutting down. Let it propagate.
                throw exception
            } catch (exception: RuntimeException) {
                // Detector already logs its own warnings; double-log at debug to
                // preserve the async scheduling context if someone greps for it.
                LOG.debug("Background language scan for '$key' threw; cache unchanged", exception)
            } finally {
                inFlight.remove(key)
            }
        }
        return true
    }

    private fun runWithProjectProgress(
        project: Project,
        key: String,
        task: () -> ScanResult,
    ): ScanResult {
        if (project.isDisposed) {
            LOG.debug("Background language scan for '$key' skipped: project disposed before scan body ran")
            return ScanResult.Unavailable
        }
        val indicator = EmptyProgressIndicator(ModalityState.nonModal())
        val disposalCancellation = ProjectDisposalCancellation(indicator)
        if (!registerDisposalCancellation(project, key, disposalCancellation)) {
            return ScanResult.Unavailable
        }
        return try {
            var result = ScanResult.Unavailable
            ProgressManager.getInstance().runProcess(
                Runnable {
                    if (project.isDisposed) {
                        indicator.cancel()
                        return@Runnable
                    }
                    result = task()
                },
                indicator,
            )
            result
        } catch (exception: ProcessCanceledException) {
            LOG.debug("Background language scan for '$key' canceled; cache unchanged", exception)
            ScanResult.Unavailable
        } finally {
            disposeCancellation(disposalCancellation)
        }
    }

    private fun registerDisposalCancellation(
        project: Project,
        key: String,
        disposalCancellation: Disposable,
    ): Boolean =
        try {
            Disposer.register(project, disposalCancellation)
            true
        } catch (exception: ProcessCanceledException) {
            LOG.debug("Background language scan for '$key' canceled while registering disposal hook", exception)
            false
        } catch (exception: kotlin.coroutines.cancellation.CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            LOG.debug("Background language scan for '$key' skipped: project disposal hook unavailable", exception)
            false
        }

    private fun disposeCancellation(disposalCancellation: ProjectDisposalCancellation) {
        try {
            disposalCancellation.disposeWithoutCancel()
        } catch (exception: ProcessCanceledException) {
            LOG.debug("Project language scan disposal hook was already canceled", exception)
        } catch (exception: kotlin.coroutines.cancellation.CancellationException) {
            throw exception
        } catch (exception: RuntimeException) {
            LOG.debug("Project language scan disposal hook cleanup failed", exception)
        }
    }

    private class ProjectDisposalCancellation(
        private val indicator: EmptyProgressIndicator,
    ) : Disposable {
        @Volatile
        private var shouldCancel = true

        override fun dispose() {
            if (shouldCancel) {
                indicator.cancel()
            }
        }

        fun disposeWithoutCancel() {
            shouldCancel = false
            Disposer.dispose(this)
        }
    }

    @TestOnly
    internal fun clearForTest() {
        inFlight.clear()
    }
}
