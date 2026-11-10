package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowGlassPane
import dev.ayuislands.glow.GlowHosts
import dev.ayuislands.glow.GlowIslandIds
import dev.ayuislands.glow.GlowOverlayAttachment
import dev.ayuislands.glow.GlowOverlayManager.Companion.safeDecodeColor
import dev.ayuislands.glow.GlowOverlayRemoval
import dev.ayuislands.glow.GlowOverlaySnapshot
import dev.ayuislands.glow.GlowPaneCheckpoint
import dev.ayuislands.glow.GlowPlaybackRequest
import dev.ayuislands.glow.GlowPlaybackTarget
import dev.ayuislands.glow.RouteOverlay
import dev.ayuislands.glow.capturePlaybackEnvironment
import dev.ayuislands.glow.waveform.WaveformEngine
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.persistence.PortableReceipt

/** Native surfaces of one manager; shared focus and the outer hold lifetime belong to the operation. */
internal class StudioGlowSurfaces private constructor(
    private val input: StudioGlowInput,
    private val snapshot: GlowOverlaySnapshot,
    private val writes: List<StudioGlowPaneWrite>,
    private val additions: List<Addition>,
    private val retirements: List<Retirement>,
    private val changedIds: List<String>,
    private val playback: StudioGlowPlayback?,
) {
    val receipts: List<PortableReceipt>
        get() =
            buildList {
                writes.forEach { addAll(it.receipts) }
                additions.forEach { addAll(it.binding.receipts) }
                retirements.forEach { add(it.receipt) }
                addAll(playback?.receipts.orEmpty())
            }

    fun pause() {
        playback?.pause(emptyList())
    }

    fun apply() {
        input.owner.hold.select(input.candidate)
        writes.forEach { it.apply() }
        additions.forEach { it.binding.apply() }
        changedIds.forEach(snapshot::refreshBounds)
    }

    fun start() {
        val engines = mutableMapOf<GlowGlassPane, WaveformEngine>()
        for (write in writes) write.preparedEngine?.let { engines[write.playbackTarget.overlay.pane] = it }
        for (addition in additions) addition.engine?.let { engines[addition.target.overlay.pane] = it }
        playback?.start(
            engines,
            onEngineCreated = { target, engine ->
                writes.find { it.playbackTarget.overlay.pane === target.pane }?.recordEngine(engine)
            },
            onRouteCreated = { route ->
                writes
                    .filter { write -> route.targets.any { it.pane === write.playbackTarget.overlay.pane } }
                    .forEach { it.recordRoute(route) }
            },
        )
    }

    fun prepareFieldsRestore() {
        writes.asReversed().forEach { it.prepareRestore() }
    }

    fun stop(blockedBy: List<PortableReceipt>) {
        playback?.stopForward(blockedBy)
    }

    fun restoreFields(blockedBy: List<PortableReceipt>) {
        additions.asReversed().forEach { it.binding.restore(blockedBy) }
        writes.asReversed().forEach { it.restore(blockedBy) }
        changedIds.forEach(snapshot::refreshBounds)
        input.owner.hold.select(input.owner.hold.before)
        retirements.forEach { it.cancel() }
    }

    fun preparePlaybackRestore(blockedBy: List<PortableReceipt>) {
        playback?.prepareRestore(blockedBy)
    }

    fun restorePlayback(blockedBy: List<PortableReceipt>): Boolean = playback?.restore(blockedBy) ?: true

    fun acceptPlayback() {
        playback?.accept()
    }

    fun acceptFields() {
        writes.forEach { it.accept() }
        additions.forEach { it.binding.accept() }
        retirements.forEach { it.accept() }
    }

    fun registerCallbacks() {
        input.owner.manager.registerCallbacks(input.owner.hold)
    }

    private class Addition(
        references: StudioGlowReferences,
        id: String,
        attachment: GlowOverlayAttachment,
    ) {
        val binding = StudioGlowOverlayAdd(references, attachment)
        val target =
            attachment.entry.let { entry ->
                GlowPlaybackTarget(
                    RouteOverlay(id, entry.glassPane, entry.host, entry.layeredPane),
                    entry.glassPane.captureAppearance(),
                    entry.glassPane.waveformFailure,
                )
            }
        val engine = attachment.entry.glassPane.currentWaveformEngine
    }

    private class Retirement(
        references: StudioGlowReferences,
        private val native: GlowOverlayRemoval,
    ) {
        private val original =
            StudioGlowMembershipReceipts.encodeRemoval(
                references.identity(native.entry.glassPane),
                references.overlayTokens(native.entry),
            )
        private var isCancelled = false
        val receipt: PortableReceipt
            get() = StudioGlowMembershipReceipts.refreshRemoval(original, isCancelled || native.isComplete)

        fun cancel() {
            check(!native.isComplete) { "Accepted Glow removal cannot be cancelled" }
            isCancelled = true
        }

        fun accept() {
            check(!isCancelled) { "Cancelled Glow removal cannot be accepted" }
            native.cleanup()
        }
    }

    companion object {
        fun capture(
            references: StudioGlowReferences,
            input: StudioGlowInput,
            selection: StudioGlowSelection,
            isAvailable: Boolean,
        ): StudioGlowSurfaces {
            val owner = input.owner
            val settings = input.candidate.settings
            val paused = owner.manager.capturePlayback(owner.hold)
            val scope = StudioGlowMerge.membershipScope(selection)
            val retired =
                paused.targets.filter {
                    StudioGlowMerge.retiresIsland(it.id, scope, settings, isAvailable)
                }
            val changes = captureChanges(input, selection, paused.overlays, retired)
            val additions = prepareAdditions(references, input, paused.targets, scope, isAvailable)
            val appearances =
                paused.targets.filterNot { it in retired }.map { target ->
                    changes.getValue(target)?.planned ?: target.pane.captureAppearance()
                } + additions.map { it.target.appearance }
            val isCold =
                paused.targets.isEmpty() &&
                    paused.routeConfig == null &&
                    isAvailable &&
                    settings.enabled &&
                    (additions.isNotEmpty() || StudioGlowMerge.selectsAvailability(selection))
            val animation = StudioGlowMerge.mergeAnimation(selection, settings, paused.animation, isCold)
            val effectiveSettings = settings.copy(enabled = isAvailable && settings.enabled)
            val route =
                StudioGlowMerge.mergeRoute(
                    selection,
                    effectiveSettings,
                    paused.routeConfig,
                    appearances,
                    isCold,
                )
            val hasEffects =
                changes.values.any { checkpoint: GlowPaneCheckpoint? -> checkpoint != null } ||
                    additions.isNotEmpty() ||
                    retired.isNotEmpty() ||
                    animation != paused.animation ||
                    route != paused.routeConfig
            val writes =
                if (hasEffects) {
                    paused.targets.map { target ->
                        StudioGlowPaneWrite(
                            references,
                            target,
                            changes.getValue(target),
                            paused.overlays.captureFailure(target.id),
                        )
                    }
                } else {
                    emptyList()
                }
            val targets =
                writes.filterNot { it.playbackTarget.overlay in retired }.map { it.playbackTarget } +
                    additions.map { it.target }
            val playback =
                if (hasEffects) {
                    val intent =
                        GlowPlaybackRequest.Activate(
                            animation,
                            route,
                            targets,
                            capturePlaybackEnvironment(targets.map { it.overlay }),
                        )
                    StudioGlowPlayback(references, owner, paused, intent)
                } else {
                    null
                }
            return StudioGlowSurfaces(
                input,
                paused.overlays,
                writes,
                additions,
                retired.map { Retirement(references, paused.overlays.prepareRemoval(it.id)) },
                changes.filterValues { it != null }.keys.map { it.id },
                playback,
            )
        }

        private fun captureChanges(
            input: StudioGlowInput,
            selection: StudioGlowSelection,
            snapshot: GlowOverlaySnapshot,
            retired: List<RouteOverlay>,
        ): Map<RouteOverlay, GlowPaneCheckpoint?> =
            snapshot.selected.associateWith { target ->
                val actual = target.pane.captureAppearance()
                val planned =
                    if (target in retired) {
                        actual.copy(isVisible = false)
                    } else if (input.context == null) {
                        actual
                    } else {
                        StudioGlowMerge.mergePane(
                            actual,
                            selection,
                            input.candidate.settings,
                            safeDecodeColor(checkNotNull(input.candidate.accent).value),
                            target.pane.isEditorOverlay,
                        )
                    }
                snapshot.capturePane(target.id, planned)
            }

        private fun prepareAdditions(
            references: StudioGlowReferences,
            input: StudioGlowInput,
            existing: List<RouteOverlay>,
            scope: Set<String>,
            isAvailable: Boolean,
        ): List<Addition> {
            if (!isAvailable || scope.isEmpty()) return emptyList()
            val owner = input.owner
            return GlowHosts
                .findMissing(
                    owner.manager.project,
                    input.candidate.settings,
                    existing.map { it.id }.toSet(),
                ).filter { GlowIslandIds.canonical(it.id) in scope }
                .mapNotNull { host ->
                    owner.manager
                        .prepareOverlay(host, input.candidate, input.context, owner.hold)
                        ?.let { Addition(references, host.id, it) }
                }
        }
    }
}
