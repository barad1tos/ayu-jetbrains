package dev.ayuislands.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Focus-race protocol for opening a feature tab in exactly one project window.
 *
 * Shared by the What's New launcher and the onboarding wizard scheduler
 * (`WhatsNewLauncher`, `StartupLicenseHandler`), which previously copy-pasted
 * the same steps:
 *
 *  1. Hop to EDT (`Dispatchers.EDT` + [ModalityState.nonModal]).
 *  2. Bail when the [Project] is already disposed.
 *  3. Focus check — in merged-window mode (project tabs) all projects share
 *     one OS frame; `IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project`
 *     identifies which project tab the user is viewing, even when the IDE is
 *     in the background. Only the matching project proceeds; the rest defer.
 *     A `null` frame (cold start, IDE minimized) lets every caller through to
 *     the CAS fallback.
 *  4. CAS claim on [gate] — the first project to acquire wins the session slot.
 *  5. Open via the caller's open action; on success run the success callback,
 *     on failure release the claim so one failed open can't deadlock the
 *     whole JVM session.
 *
 * Manual triggers (user clicked a menu action) pass `bypassFocus` /
 * `bypassGate` = true: the click already names the right project, and the
 * auto-trigger one-shot must not block an explicit request. A bypassed gate
 * is never claimed, so a failed manual open never releases another window's
 * claim.
 */
internal class FocusWinningTabOpener(
    private val gate: SessionOneShot,
    private val log: Logger,
    private val logPrefix: String,
    private val subject: String,
) {
    /** EDT-hopping entry — see the class KDoc for the protocol steps. */
    suspend fun open(
        project: Project,
        bypassFocus: Boolean = false,
        bypassGate: Boolean = false,
        onSuccess: () -> Unit,
        openTab: (Project) -> Unit,
    ) {
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            openOnEdt(project, bypassFocus, bypassGate, onSuccess, openTab)
        }
    }

    /**
     * Protocol body, split from [open] so unit tests can exercise the focus /
     * claim / release branches directly — `Dispatchers.EDT` needs a live
     * platform application and cannot dispatch in a headless test.
     */
    @RequiresEdt
    fun openOnEdt(
        project: Project,
        bypassFocus: Boolean,
        bypassGate: Boolean,
        onSuccess: () -> Unit,
        openTab: (Project) -> Unit,
    ) {
        if (project.isDisposed) return
        if (!bypassFocus && !isFocusedProject(project)) return
        val claimed = !bypassGate && gate.tryAcquire()
        if (!bypassGate && !claimed) {
            log.info("$logPrefix: another project already claimed the $subject slot")
            return
        }
        log.info("$logPrefix: opening $subject in ${project.name} (bypassGate=$bypassGate)")
        try {
            openTab(project)
        } catch (cancellation: CancellationException) {
            // PCE (and its IndexNotReadyException subtype thrown by
            // FileEditorManager.openFile mid-indexing) extends
            // CancellationException extends RuntimeException on 2024.1+ —
            // it is control flow, never log.error material. Release the claim
            // so the cancelled open can retry, then let the signal propagate.
            if (claimed) gate.release()
            throw cancellation
        } catch (exception: RuntimeException) {
            log.error("$logPrefix: failed to open $subject in ${project.name}", exception)
            if (claimed && gate.release()) {
                log.info("$logPrefix: $subject claim released after failed open")
            }
            return
        }
        // Outside the try: the tab IS open at this point, so a throwing
        // success callback must neither release the claim nor log a
        // misleading "failed open" breadcrumb.
        onSuccess()
    }

    /**
     * Returns true iff this project is the right one to open in: either its
     * frame holds focus, or no frame is focused at all (cold start) and the
     * CAS fallback decides.
     */
    private fun isFocusedProject(project: Project): Boolean {
        val activeProject = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
        if (activeProject != null && activeProject != project) {
            log.info("$logPrefix: ${project.name} is not the active tab — deferring")
            return false
        }
        return true
    }
}
