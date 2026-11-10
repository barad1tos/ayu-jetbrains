package dev.ayuislands.studio.session

import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontIdentityPlan
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontOwnership
import dev.ayuislands.font.FontOwnershipPolicy
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontSurface
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.CapabilitySnapshot
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.FontObservation
import dev.ayuislands.studio.model.LimitationReason
import dev.ayuislands.studio.model.NativeSurfaceChange
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioFontRequest
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.persistence.StudioFontOwnership
import kotlinx.coroutines.withContext

/** Fresh admission evidence; synchronous views never perform font I/O or claim unobserved availability. */
internal class StudioAcceptanceCollector(
    private val native: StudioNativeHost,
    private val target: (StudioTarget) -> TargetObservation,
    private val authority: () -> AuthorityStatus,
    private val fonts: StudioFontHost = IdeFontHost(),
    private val dispatchers: StudioSessionDispatchers = StudioSessionDispatchers(),
) {
    suspend fun observe(
        request: AcceptanceRequest,
        document: StudioDocument,
    ): AcceptanceContext {
        val appearance = mergeAppearance(request.session, document, request.session.target, request.choices)
        val selected = appearance?.let(StudioFontProjection::request)
        val availability =
            if (selected is StudioFontRequest.Enabled) {
                val resources = document.auxiliary.resources
                StudioFonts.availability(selected, resources, StudioFonts.capture(resources, dispatchers.io))
            } else {
                FontAvailability.UNCONFIRMED
            }
        val family =
            if (selected is StudioFontRequest.Enabled && availability == FontAvailability.AVAILABLE) {
                fonts.resolveFamily(selected.settings)
            } else {
                null
            }
        val confirmed =
            if (availability == FontAvailability.AVAILABLE && family == null) {
                FontAvailability.UNCONFIRMED
            } else {
                availability
            }
        return withContext(dispatchers.native) {
            captureContext(
                request.session.target,
                FontObservation(appearance?.font ?: StudioFields(StudioList(emptyList())), confirmed),
                reviewFonts(document, selected, family),
            )
        }
    }

    /** Called on the native lane; the coordinator resolves FONT_REFRESH with observe before admission. */
    fun view(
        state: StudioSessionState,
        document: StudioDocument,
    ): AcceptanceContext {
        val appearance = mergeAppearance(state.record, document, state.identity.target, AcceptanceChoices())
        return captureContext(
            state.identity.target,
            FontObservation(appearance?.font ?: StudioFields(StudioList(emptyList())), FontAvailability.UNCONFIRMED),
            emptyList(),
        )
    }

    private fun captureContext(
        requestedTarget: StudioTarget,
        font: FontObservation,
        changes: List<NativeSurfaceChange>,
    ): AcceptanceContext {
        val observed = native.observe()
        val limitations =
            if (observed.entitlement == LicenseEntitlement.UNLICENSED) {
                listOf(EffectOwner.GLOW, EffectOwner.VCS, EffectOwner.WORKSPACE).map {
                    EffectLimitation(it, LimitationReason.PRO_REQUIRED, blocksEffect = false)
                }
            } else {
                emptyList()
            }
        return AcceptanceContext(
            authority(),
            target(requestedTarget),
            CapabilitySnapshot(observed.entitlement.ordinal.toLong(), StudioList(limitations)),
            font,
            StudioList(changes),
        )
    }

    private fun mergeAppearance(
        record: StudioSessionRecord?,
        document: StudioDocument,
        requestedTarget: StudioTarget,
        choices: AcceptanceChoices,
    ): AppearanceSnapshot? {
        val current = StudioTargets.current(document, requestedTarget)?.appearance ?: return null
        val draft = record?.work?.drafts?.singleOrNull { it.id == record.work.selected } ?: return current
        return StudioAcceptanceMerge.appearance(draft, current, choices, mutableListOf())
    }

    private fun reviewFonts(
        document: StudioDocument,
        request: StudioFontRequest?,
        family: String?,
    ): List<NativeSurfaceChange> {
        if (request !is StudioFontRequest.Enabled || family == null) return emptyList()
        val ownership = StudioFontOwnership(document.runtime)
        if (ownership.version !in 0..FontOwnership.VERSION) return emptyList()
        val observed = fonts.observe()
        val identity =
            FontSchemeCensus(observed.schemes.toTypedArray())
                .planIdentity(observed.active, null, "", ownership.keys) as? FontIdentityPlan.Existing
                ?: return emptyList()
        return FontSurface.entries.mapNotNull { surface ->
            if (surface == FontSurface.CONSOLE && !request.settings.applyToConsole) return@mapNotNull null
            if (!surface.isAvailable(observed.active)) return@mapNotNull null
            val key = FontIdentityRules.key(identity.id, surface)
            val record = ownership.read(key) ?: return@mapNotNull null
            val current = surface.captureExact(observed.active)
            val desired =
                FontOwnershipPolicy.target(
                    surface,
                    current == FontSnapshot.Inherited,
                    FontData.captureExact(surface.preferences(observed.active)),
                    request.settings,
                    family,
                )
            StudioFontPolicy.change(key, record, current, desired)
        }
    }
}
