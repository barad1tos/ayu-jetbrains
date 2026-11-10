package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowFailureCheckpoint
import dev.ayuislands.glow.GlowFailureRestore
import dev.ayuislands.glow.GlowPaneCheckpoint
import dev.ayuislands.glow.GlowPlaybackTarget
import dev.ayuislands.glow.RouteOverlay
import dev.ayuislands.glow.RoutePlayback
import dev.ayuislands.glow.waveform.WaveformEngine
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt

/** Retained native pane ownership and immutable evidence; the outer operation acknowledges every write. */
internal class StudioGlowPaneWrite(
    private val references: StudioGlowReferences,
    target: RouteOverlay,
    private val change: GlowPaneCheckpoint?,
    private val health: GlowFailureCheckpoint,
) {
    private val identity = references.identity(target.pane)
    private val healthIdentity = identity.copy(ownerToken = references.token(health))
    private val paneReceipts =
        change?.record?.let { snapshot ->
            StudioGlowReceipts.encodePane(
                identity,
                snapshot,
                references.token(snapshot.before.color),
                references.token(snapshot.planned.color),
            )
        } ?: emptyList()
    private val healthReceipts =
        mutableListOf(
            StudioGlowHealthReceipts.encode(
                healthIdentity,
                health.record,
                change?.record,
                references.failureTokens(health.record.before),
            ),
        )
    private var lastRestore: GlowFailureRestore? = null

    val playbackTarget =
        GlowPlaybackTarget(
            target,
            change?.planned ?: target.pane.captureAppearance(),
            StudioGlowHealthReceipts.plannedFailure(health.record, change?.record),
        )

    var preparedEngine: WaveformEngine? = null
        private set

    val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                (change?.let { StudioGlowReceipts.refreshPane(paneReceipts, it.record) } ?: emptyList()) +
                    StudioGlowHealthReceipts.refresh(healthReceipts, health.record),
            )

    fun apply() {
        change?.apply(::recordEngine)
    }

    /** Retain the exact producer before configureWaveform publishes it into the pane. */
    fun recordEngine(engine: WaveformEngine) {
        health.recordEngine(engine)
        preparedEngine = engine
    }

    fun recordRoute(playback: RoutePlayback) {
        health.recordRoute(playback)
    }

    fun prepareRestore() {
        health.prepareRestore()
        val snapshot = health.record
        val restoration = snapshot.restoration
        if (restoration == null || restoration === lastRestore) return
        healthReceipts.add(
            StudioGlowHealthReceipts.encodeRestore(
                healthIdentity,
                healthReceipts.size - 1,
                snapshot,
                GlowHealthTokens(
                    references.failureTokens(snapshot.before),
                    references.failureTokens(restoration.observed),
                ),
            ),
        )
        lastRestore = restoration
    }

    fun restore(blockedBy: List<PortableReceipt>) {
        change?.restore(StudioGlowReceipts.blockedPaneFields(identity.resource, blockedBy), ::recordEngine)
        health.restore(StudioGlowHealthReceipts.isBlocked(identity.resource, blockedBy))
    }

    fun accept() {
        change?.accept()
        health.accept()
    }
}
