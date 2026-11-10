package dev.ayuislands.studio.session

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import dev.ayuislands.font.FontApplyOrigin
import dev.ayuislands.font.FontIdentityCheckpoint
import dev.ayuislands.font.FontIdentityPlan
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontOwnership
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.font.FontSurface
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioFontRequest
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.StudioFontOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Participates in outer acceptance without child commits or temporary legacy settings swaps. */
internal class StudioFontParticipant(
    private val host: StudioFontHost = IdeFontHost(),
    private val nativeContext: CoroutineContext = Dispatchers.EDT,
) : StudioEffectParticipant {
    override val owner: EffectOwner = EffectOwner.FONT

    override suspend fun prepare(
        input: StudioEffectInput,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation {
        val current = checkNotNull(StudioTargets.current(input.candidate, input.target))
        val request = StudioFontProjection.request(current.appearance)
        check(request !is StudioFontRequest.Unavailable) { "Requested Font preset is unavailable" }
        val family =
            if (request is StudioFontRequest.Enabled) {
                checkNotNull(host.resolveFamily(request.settings)) { "Requested font family is unavailable" }
            } else {
                null
            }
        return withContext(nativeContext) { capture(input, request, family) }
    }

    override suspend fun recover(
        input: StudioEffectInput,
        receipts: StudioList<PortableReceipt>,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            val token = input.candidate.header.transactionId
            require(receipts.all { it.owner == owner && it.identity.ownerToken == token })
            require(receipts.all { FontIdentityRules.canonicalId(it.identity.resource) != null })
            val metadata = receipts.filter { it.identity.field == StudioSchemeIdentityReceipt.FIELD }
            require(metadata.size <= 1)
            val adoption =
                metadata.singleOrNull()?.let {
                    FontIdentityCheckpoint.recover(
                        census(host.observe()),
                        StudioSchemeIdentityReceipt.decode(EffectOwner.FONT, token, it),
                    )
                }
            val fonts =
                receipts.filter { it.identity.field != StudioSchemeIdentityReceipt.FIELD }.groupBy { receipt ->
                    val surface =
                        FontSurface.entries.single {
                            receipt.identity.field.startsWith("font/${it.name}/")
                        }
                    FontReceiptIdentity(receipt.identity.resource, surface, token)
                }
            StudioFontOperation(
                host,
                nativeContext,
                token,
                fonts.map { (identity, records) -> StudioFontWrite.recover(identity, records) },
                adoption,
                null,
                null,
                emptySet(),
            )
        }

    private fun capture(
        input: StudioEffectInput,
        request: StudioFontRequest,
        family: String?,
    ): StudioFontOperation {
        val observed = host.observe()
        val census = census(observed)
        val ownership = StudioFontOwnership(input.candidate.runtime)
        val prepared = StudioFontCapture(input, ownership)
        var adoption: FontIdentityCheckpoint? = null
        var active: EditorColorsScheme? = null
        if (ownership.version in 0..FontOwnership.VERSION) prepared.suspendDuplicates(census.duplicateIds)
        when (request) {
            StudioFontRequest.Disabled -> {
                prepareRestoration(observed, census, ownership, prepared)
            }
            is StudioFontRequest.Enabled -> {
                check(ownership.version in 0..FontOwnership.VERSION) { "Unsupported Font ownership version" }
                active = observed.active
                var identity: FontIdentityPlan
                do {
                    val freshId = if (input.fontOrigin == FontApplyOrigin.AUTOMATIC) "" else host.newIdentity()
                    identity = census.planIdentity(active, input.fontOrigin, freshId, ownership.keys)
                } while (identity == FontIdentityPlan.Collision)
                val id =
                    when (identity) {
                        is FontIdentityPlan.Adopt -> {
                            adoption = FontIdentityCheckpoint(checkNotNull(active as? AbstractColorsScheme), identity)
                            identity.id
                        }
                        is FontIdentityPlan.Existing -> identity.id
                        else -> {
                            check(
                                input.fontOrigin == FontApplyOrigin.AUTOMATIC,
                            ) { "Font scheme identity is unconfirmed" }
                            null
                        }
                    }
                if (id == null) {
                    active = null
                } else {
                    prepared.apply(active, id, request.settings, checkNotNull(family))
                }
            }
            is StudioFontRequest.Unavailable -> error("Unavailable request cannot prepare native Font writes")
        }
        return StudioFontOperation(
            host,
            nativeContext,
            input.candidate.header.transactionId,
            prepared.writes.toList(),
            adoption,
            prepared.changes(),
            active,
            ownership.keys,
        )
    }

    private fun prepareRestoration(
        observed: FontSchemeView,
        census: FontSchemeCensus,
        ownership: StudioFontOwnership,
        prepared: StudioFontCapture,
    ) {
        if (ownership.version != FontOwnership.VERSION) return
        for (scheme in observed.schemes) {
            val identity = census.planIdentity(scheme, null, "", ownership.keys)
            if (identity is FontIdentityPlan.Existing) prepared.restore(scheme, identity.id)
        }
    }

    private fun census(view: FontSchemeView): FontSchemeCensus = FontSchemeCensus(view.schemes.toTypedArray())
}
