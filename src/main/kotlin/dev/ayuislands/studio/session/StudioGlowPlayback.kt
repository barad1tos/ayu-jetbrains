package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowGlassPane
import dev.ayuislands.glow.GlowPlaybackPause
import dev.ayuislands.glow.GlowPlaybackRequest
import dev.ayuislands.glow.GlowPlaybackRestart
import dev.ayuislands.glow.RouteOverlay
import dev.ayuislands.glow.RoutePlayback
import dev.ayuislands.glow.waveform.WaveformEngine
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus

/** Retains one manager's playback writes; outer acceptance owns field ordering, acknowledgements and lease release. */
internal class StudioGlowPlayback(
    private val references: StudioGlowReferences,
    private val owner: HeldGlowManager,
    private val captured: GlowPlaybackPause,
    private val intent: GlowPlaybackRequest.Activate,
) {
    private val identity = references.identity(owner.manager)
    private val original =
        StudioGlowPlaybackReceipts.encode(
            identity,
            StudioGlowPlaybackReceipts.baseline(
                captured.animation,
                captured.routeConfig,
                captured.targets.map(::surface),
            ),
            activation(intent),
        )
    private var forward: GlowPlaybackRestart? = null
    private val attempts = mutableListOf<RestoreAttempt>()
    private var hasPaused = false
    private var isRestored = false

    val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                listOf(StudioGlowPlaybackReceipts.refreshOriginal(original, forward?.record?.phase, isRestored)) +
                    attempts.flatMap { it.receipts },
            )

    fun pause(blockedBy: List<PortableReceipt>) {
        check(forward == null && !isRestored) { "Glow playback application was already started" }
        checkUnblocked(blockedBy)
        hasPaused = true
        captured.stop()
    }

    fun start(
        preparedEngines: Map<GlowGlassPane, WaveformEngine>,
        onEngineCreated: (RouteOverlay, WaveformEngine) -> Unit,
        onRouteCreated: (RoutePlayback) -> Unit,
    ) {
        check(hasPaused && attempts.isEmpty() && !isRestored) { "Glow playback is not ready for application" }
        val restart =
            forward
                ?: owner.manager.preparePlayback(owner.hold, captured, intent, preparedEngines).also { forward = it }
        restart.start(onEngineCreated, onRouteCreated)
    }

    fun stopForward(blockedBy: List<PortableReceipt>) {
        if (!hasPaused) return
        checkUnblocked(blockedBy)
        captured.stop()
        forward?.stop()
    }

    /** Called only after outer appearance and membership restoration, before its next durable acknowledgement. */
    fun prepareRestore(blockedBy: List<PortableReceipt>) {
        if (isRestored || !hasPaused) return
        checkUnblocked(blockedBy)
        val current = attempts.lastOrNull()
        if (current != null && current.phase != RestorePhase.CLEANED) {
            current.prepareCleanup()
            return
        }
        val restart = owner.manager.preparePlayback(owner.hold, captured)
        val health =
            captured.targets.map { target ->
                StudioGlowPaneWrite(references, target, null, captured.overlays.captureFailure(target.id))
            }
        val receipt =
            StudioGlowPlaybackReceipts.encodeRestore(
                identity,
                attempts.size,
                activation(restart.record.intent),
            )
        attempts.add(RestoreAttempt(restart, receipt, health))
    }

    /** False means cleanup completed and a fresh capture/acknowledgement is needed before another startup. */
    fun restore(blockedBy: List<PortableReceipt>): Boolean {
        if (isRestored) return true
        if (!hasPaused) {
            isRestored = true
            return true
        }
        checkUnblocked(blockedBy)
        val attempt = checkNotNull(attempts.lastOrNull()) { "Glow restoration has not been prepared" }
        if (!attempt.restore(blockedBy)) return false
        isRestored = true
        return true
    }

    fun accept() {
        check(!isRestored && attempts.isEmpty()) { "Restoring Glow playback cannot be accepted" }
        checkNotNull(forward) { "Glow playback has not been applied" }.accept()
    }

    private fun checkUnblocked(blockedBy: List<PortableReceipt>) {
        check(!StudioGlowPlaybackReceipts.isBlocked(identity.resource, blockedBy)) {
            "A newer Glow receipt owns playback"
        }
    }

    private fun activation(request: GlowPlaybackRequest.Activate): RawValue =
        StudioGlowPlaybackReceipts.activation(
            request,
            request.targets.map { target ->
                GlowPlaybackTokens(
                    surface(target.overlay),
                    references.token(target.appearance.color),
                    references.failureTokens(target.failure),
                )
            },
        )

    private fun surface(target: RouteOverlay): GlowPlaybackSurface =
        GlowPlaybackSurface(
            target.id,
            references.token(target.pane),
            references.token(target.host),
            references.token(target.layeredPane),
        )

    private class RestoreAttempt(
        private val native: GlowPlaybackRestart,
        private val original: PortableReceipt,
        private val health: List<StudioGlowPaneWrite>,
    ) {
        var phase = RestorePhase.PREPARED
            private set

        val receipts: List<PortableReceipt>
            get() =
                listOf(StudioGlowPlaybackReceipts.refreshRestore(original, native.record.phase)) +
                    health.flatMap { write ->
                        write.receipts.map { receipt ->
                            if (phase == RestorePhase.ACCEPTED && !StudioNativeEvidence.isTerminal(receipt.status)) {
                                receipt.copy(status = RestoreStatus.RESTORED)
                            } else {
                                receipt
                            }
                        }
                    }

        fun prepareCleanup() {
            if (phase == RestorePhase.FAILED) health.forEach { it.prepareRestore() }
        }

        fun restore(blockedBy: List<PortableReceipt>): Boolean {
            when (phase) {
                RestorePhase.CLEANED -> {
                    return false
                }

                RestorePhase.FAILED -> {
                    native.stop()
                    health.asReversed().forEach { it.restore(blockedBy) }
                    phase = RestorePhase.CLEANED
                    return false
                }

                RestorePhase.PREPARED -> {
                    start()
                }

                RestorePhase.ACCEPTED -> Unit
            }
            health.forEach { it.accept() }
            return true
        }

        private fun start() {
            try {
                native.start(
                    onEngineCreated = { target, engine ->
                        health.single { it.playbackTarget.overlay.pane === target.pane }.recordEngine(engine)
                    },
                    onRouteCreated = { route -> health.forEach { it.recordRoute(route) } },
                )
                native.accept()
                phase = RestorePhase.ACCEPTED
            } catch (exception: RuntimeException) {
                phase = RestorePhase.FAILED
                throw exception
            }
        }
    }

    private enum class RestorePhase { PREPARED, FAILED, CLEANED, ACCEPTED }
}
