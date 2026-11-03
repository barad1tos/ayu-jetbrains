package dev.ayuislands.studio.prototype

import dev.ayuislands.syntax.SyntaxPreset
import java.util.Collections

/**
 * In-memory interaction model for the design preview, with no IDE dependencies or writes.
 *
 * Edits/create/update make the draft dirty; discard restores its latest accepted baseline.
 * Apply accepts both local drafts unless rejected; partial acceptance retains the desired
 * font separately from the actual current font. Retrying Apply can finish that local change.
 * Profile activation requires a clean draft and a project; rejection changes neither target
 * nor appearance. A successful switch overlays the selected sections onto the user's base.
 * Selection and Current/Draft comparison are view state and never enter this machine.
 */
internal class StudioPrototypeSession(
    initial: StudioPrototypeAppearance = StudioPrototypeAppearance(),
    val isProjectAvailable: Boolean = true,
) {
    private var baseAppearance = initial.ownedCopy()
    var acceptedDraft: StudioPrototypeAppearance = baseAppearance
        private set
    private var baseInclusion = defaultInclusion()
    private var acceptedResult = StudioPrototypeResult.READY

    var current: StudioPrototypeAppearance = baseAppearance
        private set
    var draft: StudioPrototypeAppearance = acceptedDraft
        private set
    var currentIncludedSections: Set<StudioPrototypeSection> = baseInclusion
        private set
    var includedSections: Set<StudioPrototypeSection> = baseInclusion
        private set
    var profiles: List<StudioPrototypeProfile> = sampleProfiles(baseAppearance)
        private set
    var pendingProfiles: List<StudioPrototypeProfile> = profiles
        private set
    var activeProfileName: String? = null
        private set
    var lastResult: StudioPrototypeResult = StudioPrototypeResult.READY
        private set

    val hasAppearanceChanges: Boolean
        get() = draft != acceptedDraft
    val hasCatalogChanges: Boolean
        get() = pendingProfiles != profiles
    val hasPendingChanges: Boolean
        get() = hasAppearanceChanges || hasCatalogChanges || includedSections != currentIncludedSections
    val isPartiallyApplied: Boolean
        get() = acceptedResult == StudioPrototypeResult.PARTIAL
    val statusMessage: String
        get() {
            val messages =
                buildList {
                    if (isPartiallyApplied) add(StudioPrototypeResult.PARTIAL.message)
                    if (lastResult != StudioPrototypeResult.READY) add(lastResult.message)
                    if (hasAppearanceChanges || includedSections != currentIncludedSections) add("Modified")
                    if (hasCatalogChanges) add("Unsaved profile")
                }.distinct()
            return if (messages.isEmpty()) StudioPrototypeResult.READY.message else messages.joinToString(" · ")
        }

    fun editAppearance(transform: (StudioPrototypeAppearance) -> StudioPrototypeAppearance) {
        draft = transform(draft).ownedCopy()
        lastResult = StudioPrototypeResult.READY
    }

    fun setIncluded(
        section: StudioPrototypeSection,
        included: Boolean,
    ) {
        val updated = if (included) includedSections + section else includedSections - section
        includedSections = Collections.unmodifiableSet(updated)
        lastResult = StudioPrototypeResult.READY
    }

    fun stageCreateProfile(name: String): StudioPrototypeEditResult {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return StudioPrototypeEditResult.NAME_REQUIRED
        if (pendingProfiles.any { it.name.equals(trimmedName, ignoreCase = true) }) {
            return StudioPrototypeEditResult.NAME_EXISTS
        }
        val profile = StudioPrototypeProfile(trimmedName, includedSections, draft)
        pendingProfiles = Collections.unmodifiableList(pendingProfiles + profile)
        lastResult = StudioPrototypeResult.READY
        return StudioPrototypeEditResult.STAGED
    }

    fun stageUpdateProfile(): StudioPrototypeEditResult {
        val selectedName = activeProfileName ?: return StudioPrototypeEditResult.NO_ACTIVE_PROFILE
        pendingProfiles =
            Collections.unmodifiableList(
                pendingProfiles.map { profile ->
                    if (profile.name == selectedName) {
                        profile.copy(includedSections = includedSections, appearance = draft)
                    } else {
                        profile
                    }
                },
            )
        lastResult = StudioPrototypeResult.READY
        return StudioPrototypeEditResult.STAGED
    }

    fun apply(outcome: StudioPrototypeOutcome = StudioPrototypeOutcome.SUCCESS): StudioPrototypeResult {
        if (outcome == StudioPrototypeOutcome.REJECTED) return recordResult(StudioPrototypeResult.REJECTED)
        val isFontUnavailable =
            outcome == StudioPrototypeOutcome.FONT_UNAVAILABLE &&
                (draft.fontSize != current.fontSize || isPartiallyApplied)
        val actual = if (isFontUnavailable) draft.copy(fontSize = current.fontSize) else draft
        val activeSections =
            pendingProfiles.firstOrNull { it.name == activeProfileName }?.includedSections ?: emptySet()
        val baseSections = StudioPrototypeSection.entries.toSet() - activeSections
        baseAppearance = baseAppearance.withSections(actual, baseSections)
        if (activeProfileName == null) baseInclusion = includedSections
        profiles = pendingProfiles
        val result = if (isFontUnavailable) StudioPrototypeResult.PARTIAL else StudioPrototypeResult.APPLIED
        acceptAppearance(actual, draft, includedSections, result)
        return result
    }

    fun discardPending() {
        draft = acceptedDraft
        includedSections = currentIncludedSections
        pendingProfiles = profiles
        lastResult = acceptedResult
    }

    fun activateProfile(
        name: String?,
        outcome: StudioPrototypeOutcome = StudioPrototypeOutcome.SUCCESS,
    ): StudioPrototypeResult {
        if (hasPendingChanges) return recordResult(StudioPrototypeResult.DIRTY_BLOCKED)
        if (name != null && !isProjectAvailable) return recordResult(StudioPrototypeResult.NO_PROJECT)
        val profile = profiles.firstOrNull { it.name == name }
        if (name != null && profile == null) return recordResult(StudioPrototypeResult.UNKNOWN_PROFILE)
        if (outcome == StudioPrototypeOutcome.REJECTED) return recordResult(StudioPrototypeResult.REJECTED)

        val membership = profile?.includedSections ?: baseInclusion
        val desired =
            if (profile == null) {
                baseAppearance
            } else {
                baseAppearance.withSections(profile.appearance, profile.includedSections)
            }
        val isFontUnavailable =
            outcome == StudioPrototypeOutcome.FONT_UNAVAILABLE &&
                profile != null &&
                StudioPrototypeSection.FONT in membership
        val actual = if (isFontUnavailable) desired.copy(fontSize = baseAppearance.fontSize) else desired
        activeProfileName = name
        val result = if (isFontUnavailable) StudioPrototypeResult.PARTIAL else StudioPrototypeResult.APPLIED
        acceptAppearance(actual, desired, membership, result)
        return result
    }

    private fun acceptAppearance(
        actual: StudioPrototypeAppearance,
        desired: StudioPrototypeAppearance,
        membership: Set<StudioPrototypeSection>,
        result: StudioPrototypeResult,
    ) {
        current = actual
        acceptedDraft = desired
        draft = desired
        currentIncludedSections = membership
        includedSections = membership
        acceptedResult = result
        lastResult = result
    }

    private fun recordResult(result: StudioPrototypeResult): StudioPrototypeResult {
        lastResult = result
        return result
    }

    private fun defaultInclusion(): Set<StudioPrototypeSection> =
        Collections.unmodifiableSet(
            setOf(StudioPrototypeSection.SYNTAX, StudioPrototypeSection.GLOW, StudioPrototypeSection.VCS),
        )

    private fun sampleProfiles(appearance: StudioPrototypeAppearance): List<StudioPrototypeProfile> =
        Collections.unmodifiableList(
            listOf(
                StudioPrototypeProfile(
                    "Quiet coding",
                    Collections.unmodifiableSet(setOf(StudioPrototypeSection.SYNTAX)),
                    appearance.copy(syntaxPreset = SyntaxPreset.WHISPER),
                ),
                StudioPrototypeProfile(
                    "Evening review",
                    defaultInclusion(),
                    appearance.copy(syntaxPreset = SyntaxPreset.WHISPER, vcsDiffIntensity = 20),
                ),
                StudioPrototypeProfile(
                    "Presentation",
                    Collections.unmodifiableSet(
                        setOf(StudioPrototypeSection.FONT, StudioPrototypeSection.GLOW, StudioPrototypeSection.SYNTAX),
                    ),
                    appearance.copy(fontSize = 18f, syntaxPreset = SyntaxPreset.NEON),
                ),
            ),
        )
}
