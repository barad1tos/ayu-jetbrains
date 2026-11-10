package dev.ayuislands.glow

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import dev.ayuislands.glow.waveform.RouteEvent
import dev.ayuislands.glow.waveform.WaveformEngine
import java.awt.KeyboardFocusManager
import javax.swing.SwingUtilities

/** One acknowledged native restart; retries retain cleanup and never replay a successful start. */
internal class GlowPlaybackRestart(
    private val plan: GlowPlaybackPlan,
    private val hold: GlowUpdateGate.Hold,
    private val route: RouteController,
    private val owner: Disposable,
    private val readAnimator: () -> GlowAnimator?,
    private val writeAnimator: (GlowAnimator?) -> Unit,
    private val selectFocus: (String?) -> Unit,
) {
    init {
        checkPaused()
    }

    private val targets = plan.overlays.selected
    private val environment = plan.environment ?: capturePlaybackEnvironment(targets)
    private val panes = targets.associateWith { it.pane.Playback() }
    private val intent =
        GlowPlaybackRequest.Activate(
            plan.animation,
            plan.routeConfig,
            panes.map { (target, capture) -> GlowPlaybackTarget(target, capture.appearance, capture.failure) },
            environment,
        )
    private val selection = plan.routeConfig?.let { RoutePlayback(it, targets) }
    private val focused = targets.find { it.id == environment.focusedId }
    private var ownedAnimator =
        when {
            selection != null || plan.animation == GlowAnimation.NONE -> null
            focused == null || focused.pane.isWaveform -> null
            else -> GlowAnimator()
        }
    private var phase = GlowCheckpointPhase.PREPARED

    val record: GlowPlaybackRecord
        get() = GlowPlaybackRecord(intent, phase)

    fun start(
        onEngineCreated: (RouteOverlay, WaveformEngine) -> Unit = { _, _ -> },
        onRouteCreated: (RoutePlayback) -> Unit = {},
    ) {
        if (phase == GlowCheckpointPhase.APPLIED) {
            checkApplied()
            return
        }
        check(phase == GlowCheckpointPhase.PREPARED) { "Glow restart cannot be replayed" }
        checkPaused()
        check(environment == capturePlaybackEnvironment(targets)) { "Glow restart environment changed" }
        panes.values.forEach { it.checkPrepared() }
        phase = GlowCheckpointPhase.APPLYING
        selectFocus(environment.focusedId)
        for (target in panes.keys) {
            checkTargets()
            if (selection == null) target.pane.configureRouteMode(false)
            checkPaused()
            target.pane.animationAlpha = 1f
        }
        if (selection != null) {
            onRouteCreated(selection)
            checkPaused()
            panes.values.forEach { it.checkPrepared() }
            route.configure(
                selection,
                checkPrepared = { target ->
                    checkTargets()
                    panes.getValue(target).checkPrepared()
                },
                onFadeCreated = { target, timer -> panes.getValue(target).recordFade(timer) },
            )
            route.handle(RouteEvent.ApplicationActiveChanged(environment.isApplicationActive), requireCompletion = true)
        } else {
            checkPaused()
            for ((target, playback) in panes) {
                checkTargets()
                playback.start(
                    target === focused,
                    environment.isApplicationActive,
                    environment.isPowerSaveEnabled,
                ) { engine ->
                    onEngineCreated(target, engine)
                    checkTargets()
                }
            }
            ownedAnimator?.let { created ->
                checkTargets()
                check(readAnimator() == null) { "Glow animator was replaced during restart" }
                writeAnimator(created)
                Disposer.register(owner, created)
                checkTargets()
                val pane = checkNotNull(focused).pane
                created.start(plan.animation, isVisible = { readAnimator() === created && pane.isShowing }) { alpha ->
                    if (readAnimator() === created) pane.animationAlpha = alpha
                }
            }
        }
        check(environment == capturePlaybackEnvironment(targets)) { "Glow restart environment changed" }
        verifyOwned()
        phase = GlowCheckpointPhase.APPLIED
    }

    fun checkApplied() {
        check(phase == GlowCheckpointPhase.APPLIED) { "Glow restart has not completed" }
        check(environment == capturePlaybackEnvironment(targets)) { "Glow restart environment changed" }
        verifyOwned()
    }

    private fun verifyOwned() {
        checkTargets()
        check(readAnimator() === ownedAnimator && route.currentPlayback === selection) {
            "Glow restart lost its native owner"
        }
        check(route.isActive == (selection != null)) { "Glow route did not settle" }
        panes.values.forEach { it.checkCurrent(routeMode = selection != null) }
    }

    private fun checkPaused() {
        checkTargets()
        check(readAnimator() == null && !route.isActive && route.currentPlayback == null) {
            "Glow playback is not paused"
        }
    }

    private fun checkTargets() {
        check(hold.isActive) { "Glow playback hold is unavailable" }
        plan.overlays.checkCurrent()
    }

    fun stop() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow playback must stop on EDT" }
        check(phase != GlowCheckpointPhase.ACCEPTED) { "Accepted Glow playback cannot be stopped by recovery" }
        if (phase == GlowCheckpointPhase.RESTORED) return
        check(hold.isActive) { "Glow playback hold is unavailable" }
        phase = GlowCheckpointPhase.RESTORING
        val failures = mutableListOf<RuntimeException>()
        attempt(failures) {
            ownedAnimator?.let { retained ->
                retained.stop()
                Disposer.dispose(retained)
                if (readAnimator() === retained) writeAnimator(null)
                ownedAnimator = null
            }
        }
        attempt(failures) {
            if (selection != null && route.currentPlayback === selection) route.dispose(requireCompletion = true)
        }
        for (playback in panes.values.toList().asReversed()) attempt(failures, playback::stop)
        failures.firstOrNull()?.let { first ->
            failures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
            throw first
        }
        phase = GlowCheckpointPhase.RESTORED
    }

    fun accept() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow playback handoff must run on EDT" }
        if (phase == GlowCheckpointPhase.ACCEPTED) return
        check(phase == GlowCheckpointPhase.APPLIED) { "Glow restart has not completed" }
        verifyOwned()
        if (environment != capturePlaybackEnvironment(targets)) hold.defer(null)
        selection?.let(route::release)
        phase = GlowCheckpointPhase.ACCEPTED
    }

    private fun attempt(
        failures: MutableList<RuntimeException>,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (exception: RuntimeException) {
            failures.add(exception)
        }
    }
}

internal data class PlaybackEnvironment(
    val focusedId: String?,
    val isApplicationActive: Boolean,
    val isPowerSaveEnabled: Boolean,
)

internal fun capturePlaybackEnvironment(targets: List<RouteOverlay>): PlaybackEnvironment {
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().permanentFocusOwner
    return PlaybackEnvironment(
        focusedId =
            focusOwner?.let { component ->
                findOverlayId(component, targets.asSequence().map { target -> target.id to target.host })
            },
        isApplicationActive = ApplicationManager.getApplication().isActive,
        isPowerSaveEnabled = PowerSaveMode.isEnabled(),
    )
}

/** Validate late native observations against the intent retained before the application acknowledgement. */
internal fun checkPlaybackIntent(
    request: GlowPlaybackRequest.Activate,
    targets: List<RouteOverlay>,
    preparedEngines: Map<GlowGlassPane, WaveformEngine>,
) {
    check(request.targets.size == targets.size) { "Glow playback target count changed" }
    for ((expected, actual) in request.targets.zip(targets)) {
        check(sameRouteTarget(actual, expected.overlay)) { "Glow playback target identity or order changed" }
        val appearance = actual.pane.captureAppearance()
        check(GlowPaneField.entries.all { GlowPaneFields.matches(it, appearance, expected.appearance) }) {
            "Glow playback appearance changed before preparation"
        }
        check(actual.pane.waveformFailure === expected.failure) { "Glow playback health changed before preparation" }
        check(actual.pane.currentWaveformEngine === preparedEngines[actual.pane]) {
            "Glow playback engine is not owned by this application"
        }
    }
    request.routeConfig?.let { config ->
        check(request.targets.all { it.failure == null }) { "Glow route target has an unresolved failure" }
        qualifyRouteAppearance(config, request.targets.map { it.appearance })
    }
    check(request.environment == capturePlaybackEnvironment(targets)) { "Glow playback environment changed" }
}
