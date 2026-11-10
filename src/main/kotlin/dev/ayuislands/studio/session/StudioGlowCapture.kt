package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.glow.GlowFocusAppearance
import dev.ayuislands.glow.GlowOverlayManager.Companion.safeDecodeColor
import dev.ayuislands.glow.GlowRuntimeInput
import dev.ayuislands.glow.captureGlowFocus
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.model.StudioGlowSelection
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

/** Confirmed native inputs arrive after preceding owners settle; capture never resolves Accent again. */
internal data class StudioGlowInput(
    val owner: HeldGlowManager,
    val candidate: GlowRuntimeInput,
    val context: AccentContext?,
)

/** One synchronous capture prepares all managers and the shared focus border before any native write. */
internal class StudioGlowCapture(
    private val references: StudioGlowReferences,
    private val nativeContext: CoroutineContext,
) {
    fun capture(
        selection: StudioGlowSelection,
        inputs: List<StudioGlowInput>,
        focus: StudioGlowInput?,
        entitlement: LicenseEntitlement,
    ): StudioGlowOperation {
        check(SwingUtilities.isEventDispatchThread()) { "Glow preparation must run on EDT" }
        require(entitlement != LicenseEntitlement.UNKNOWN) { "Glow entitlement is unconfirmed" }
        val sources: List<StudioGlowInput> = java.util.List.copyOf(inputs)
        require(sources.map { it.owner.manager }.distinct().size == sources.size) { "Duplicate Glow manager" }
        require(focus != null || sources.all { it.context == null }) { "Global Glow focus source is unconfirmed" }
        focus?.let { selected ->
            require(
                sources.any { it.owner.manager === selected.owner.manager && it.owner.hold === selected.owner.hold },
            ) {
                "Global Glow focus holder is outside the captured census"
            }
        }
        val isCurrent = { sources.all { it.owner.hold.isActive } }
        check(isCurrent()) { "Glow holds are unavailable" }
        (sources + listOfNotNull(focus)).forEach {
            require(it.context == null || it.candidate.accent != null) { "Glow Accent handoff is unconfirmed" }
        }
        val surfaces =
            LicenseChecker.withConfirmedEntitlement(entitlement) {
                sources.map { StudioGlowSurfaces.capture(references, it, selection, isAvailable(it, entitlement)) }
            }
        val changes =
            captureGlowFocus(sources.map { it.owner.hold.focus }, isCurrent) { _, actual ->
                if (focus == null) {
                    if (StudioGlowMerge.selectsFocus(selection)) actual.copy(enabled = false) else actual
                } else {
                    StudioGlowMerge.mergeFocus(
                        actual,
                        selection,
                        focus.candidate.settings,
                        safeDecodeColor(checkNotNull(focus.candidate.accent).value),
                        isAvailable(focus, entitlement),
                    )
                }
            }.map { StudioGlowFocusWrite(references, it) }
        val additions = prepareFocus(selection, sources, focus, entitlement, isCurrent)
        return StudioGlowOperation(surfaces, changes, additions, nativeContext)
    }

    private fun isAvailable(
        input: StudioGlowInput,
        entitlement: LicenseEntitlement,
    ): Boolean =
        entitlement == LicenseEntitlement.LICENSED &&
            input.context != null &&
            (input.context != AccentContext.External || input.candidate.externalAllowed)

    private fun prepareFocus(
        selection: StudioGlowSelection,
        sources: List<StudioGlowInput>,
        focus: StudioGlowInput?,
        entitlement: LicenseEntitlement,
        isCurrent: () -> Boolean,
    ): List<StudioGlowFocusAdd> {
        if (focus == null || !StudioGlowMerge.selectsFocus(selection)) return emptyList()
        val settings = focus.candidate.settings
        if (!isAvailable(focus, entitlement) || !settings.enabled || !settings.focusRing) return emptyList()
        val planned =
            GlowFocusAppearance(
                safeDecodeColor(checkNotNull(focus.candidate.accent).value),
                settings.style,
                settings.intensity.getValue(settings.style),
                enabled = true,
            )
        val excluded = sources.flatMap { it.owner.hold.focus.components }
        return focus.owner.hold.focus
            .prepare(planned, excluded, isCurrent)
            .map { StudioGlowFocusAdd(references, it) }
    }
}
