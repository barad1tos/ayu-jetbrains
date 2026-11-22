package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowIsland
import dev.ayuislands.glow.GlowOverlayAddition
import dev.ayuislands.glow.GlowOverlayAttachment
import dev.ayuislands.glow.GlowOverlayChange
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowOverlayRetirement
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.awt.Color

/** Journals native pane membership separately from playback and appearance. */
internal class StudioGlowMembership private constructor(
    private val changes: List<Change>,
    private val nativeDispatcher: CoroutineDispatcher,
) : StudioOwnerOperation {
    data class Request(
        val selection: StudioGlowMembershipPlanning.Selection,
        val settings: GlowSettings,
        val accent: Color?,
    )

    private data class Change(
        val overlay: GlowOverlayChange,
        val hold: GlowOverlayManager.UpdateHold,
        val original: PortableReceipt,
    )

    override val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                changes.map { (overlay, _, original) ->
                    original.copy(status = glowReceiptStatus(overlay.phase, overlay.state))
                },
            )

    override suspend fun apply(writes: StudioNativeWrite): Boolean =
        withContext(nativeDispatcher) {
            changes.all { (overlay, hold) ->
                writes.execute(nativeDispatcher) {
                    hold.pausePlayback()
                    overlay.apply()
                }
            }
        }

    override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation =
        withContext(nativeDispatcher) {
            settleGlowActions(changes.asReversed()) { (overlay, _, original) ->
                overlay.restore(blockedGlowFields(mapOf(overlay.id to original), blocked).isNotEmpty())
            }
            RestoreContinuation.COMPLETE
        }

    override suspend fun accept() =
        withContext(nativeDispatcher) {
            settleGlowActions(changes) { it.overlay.accept() }
        }

    companion object {
        fun capture(
            targets: List<StudioGlowTarget>,
            holds: StudioGlowHolds,
            process: StudioProcess,
            transactionId: String,
            nativeDispatcher: CoroutineDispatcher,
        ): StudioGlowMembership? {
            val owner = "glow-membership/$transactionId"
            val tokens = StudioNativeTokens(owner)
            val changes =
                targets.flatMap { target ->
                    val request = target.membership ?: return@flatMap emptyList()
                    val hold = holds.hold(target.manager)
                    prepareChanges(hold, request).map { overlay ->
                        Change(overlay, hold, receipt(overlay, owner, process, tokens))
                    }
                }
            return changes.takeIf { it.isNotEmpty() }?.let { StudioGlowMembership(it, nativeDispatcher) }
        }

        private fun prepareChanges(
            hold: GlowOverlayManager.UpdateHold,
            request: Request,
        ): List<GlowOverlayChange> {
            val accent = request.accent
            val observed = hold.observeMembership(if (accent == null) emptySet() else request.selection.enabled)
            val delta = request.selection.plan(observed.mounted, observed.hosts.keys)
            val additions =
                if (accent == null) {
                    emptyList()
                } else {
                    delta.additions.map { id ->
                        val isEditor = id == GlowIsland.EDITOR.id
                        val appearance = StudioGlowPanePlanning.newAppearance(request.settings, accent, isEditor)
                        val addition = GlowOverlayAddition(id, observed.hosts.getValue(id), appearance, isEditor)
                        hold.prepareAttachment(addition)
                    }
                }
            return additions + delta.retirements.map(hold::prepareRetirement)
        }

        private fun receipt(
            overlay: GlowOverlayChange,
            owner: String,
            process: StudioProcess,
            tokens: StudioNativeTokens,
        ): PortableReceipt {
            val entry = overlay.entry
            val resource = tokens.token(entry.glassPane)
            val identity = NativeFieldIdentity(resource, "glow/overlay/membership", owner)
            val boundsToken = entry.hierarchyBoundsListener?.let(tokens::token)
            val members =
                listOf(
                    "id" to RawValue.Text(overlay.id),
                    "host" to StudioNativeEvidence.reference(tokens.token(entry.host)),
                    "layer" to StudioNativeEvidence.reference(tokens.token(entry.layeredPane)),
                    "componentListener" to StudioNativeEvidence.reference(entry.componentListener?.let(tokens::token)),
                    "boundsListener" to StudioNativeEvidence.reference(boundsToken),
                )
            val (before, planned) =
                when (overlay) {
                    is GlowOverlayAttachment -> RawValue.Null to StudioNativeEvidence.record(members)
                    is GlowOverlayRetirement -> {
                        val visible = RawValue.BooleanValue(overlay.wasVisible)
                        StudioNativeEvidence.record(members + ("visible" to visible)) to
                            StudioNativeEvidence.record(members + ("visible" to RawValue.BooleanValue(false)))
                    }
                }
            return PortableReceipt(
                id = "$resource/membership",
                owner = EffectOwner.GLOW,
                identity = identity,
                before = StudioNativeEvidence.envelope(process, before),
                lastWritten = StudioNativeEvidence.envelope(process, planned),
                status = RestoreStatus.PENDING,
            )
        }
    }
}
