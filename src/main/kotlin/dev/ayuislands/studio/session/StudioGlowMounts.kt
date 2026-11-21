package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowOverlayAttachment
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Journals the membership of newly prepared panes without starting their playback. */
internal class StudioGlowMounts private constructor(
    private val mounts: List<Mount>,
    private val nativeDispatcher: CoroutineDispatcher,
) : StudioOwnerOperation {
    private data class Mount(
        val attachment: GlowOverlayAttachment,
        val hold: GlowOverlayManager.UpdateHold,
        val original: PortableReceipt,
    )

    override val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                mounts.map { (attachment, _, original) ->
                    original.copy(status = glowReceiptStatus(attachment.phase, attachment.state))
                },
            )

    override suspend fun apply(writes: StudioNativeWrite): Boolean =
        withContext(nativeDispatcher) {
            mounts.all { (attachment, hold) ->
                writes.execute(nativeDispatcher) {
                    hold.pausePlayback()
                    attachment.apply()
                }
            }
        }

    override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation =
        withContext(nativeDispatcher) {
            settleGlowActions(mounts.asReversed()) { (attachment, _, original) ->
                attachment.restore(blockedGlowFields(mapOf(attachment.id to original), blocked).isNotEmpty())
            }
            RestoreContinuation.COMPLETE
        }

    override suspend fun accept() =
        withContext(nativeDispatcher) {
            settleGlowActions(mounts) { it.attachment.accept() }
        }

    companion object {
        fun capture(
            targets: List<StudioGlowTarget>,
            holds: StudioGlowHolds,
            process: StudioProcess,
            transactionId: String,
            nativeDispatcher: CoroutineDispatcher,
        ): StudioGlowMounts? {
            val owner = "glow-mount/$transactionId"
            val tokens = StudioNativeTokens(owner)
            val mounts =
                targets.flatMap { target ->
                    val additions = target.additions
                    require(additions.distinctBy { it.id }.size == additions.size) {
                        "Duplicate Glow attachment target"
                    }
                    additions.map { addition ->
                        val hold = holds.hold(target.manager)
                        val attachment = hold.prepareAttachment(addition)
                        Mount(attachment, hold, receipt(attachment, owner, process, tokens))
                    }
                }
            return mounts.takeIf { it.isNotEmpty() }?.let { StudioGlowMounts(it, nativeDispatcher) }
        }

        private fun receipt(
            attachment: GlowOverlayAttachment,
            owner: String,
            process: StudioProcess,
            tokens: StudioNativeTokens,
        ): PortableReceipt {
            val entry = attachment.entry
            val resource = tokens.token(entry.glassPane)
            val identity = NativeFieldIdentity(resource, "glow/overlay/membership", owner)
            val boundsToken = entry.hierarchyBoundsListener?.let(tokens::token)
            val planned =
                StudioNativeEvidence.record(
                    "id" to RawValue.Text(attachment.id),
                    "host" to StudioNativeEvidence.reference(tokens.token(entry.host)),
                    "layer" to StudioNativeEvidence.reference(tokens.token(entry.layeredPane)),
                    "componentListener" to StudioNativeEvidence.reference(entry.componentListener?.let(tokens::token)),
                    "boundsListener" to StudioNativeEvidence.reference(boundsToken),
                )
            return PortableReceipt(
                id = "$resource/membership",
                owner = EffectOwner.GLOW,
                identity = identity,
                before = StudioNativeEvidence.envelope(process, RawValue.Null),
                lastWritten = StudioNativeEvidence.envelope(process, planned),
                status = RestoreStatus.PENDING,
            )
        }
    }
}
