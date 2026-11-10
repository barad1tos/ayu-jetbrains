package dev.ayuislands.font

/** Attempt data contains no native references and never replaces the long-lived managed baseline. */
internal data class FontWriteRecord(
    val before: FontSnapshot,
    val target: FontSnapshot,
    val effectiveBefore: FontData,
    val applyPhase: FontWritePhase,
    val inheritanceState: FontRestoreState,
    val fields: Map<FontPreferenceField, FontRestoreState>,
    val origins: List<FontRestoreOrigin>,
)

/** Apply and each restore group advance independently; a failed native setter remains WRITING. */
internal enum class FontWritePhase { PREPARED, WRITING, SETTLED }

/** Terminal provenance prevents a later equal-looking foreign edit from being reclaimed on reopen. */
internal enum class FontRestoreState { PENDING, WRITING, RESTORED, FOREIGN }

/** Immutable pre-restore observations; each is appended before its native setter is authorized. */
internal sealed interface FontRestoreOrigin {
    data class Mode(
        val snapshot: FontSnapshot,
        val effective: FontData,
    ) : FontRestoreOrigin

    data class Field(
        val field: FontPreferenceField,
        val preferences: FontData,
    ) : FontRestoreOrigin
}

internal enum class FontRestoreDecision { RESTORED, OWNED, FOREIGN, UNRESOLVED }

/** Pure ownership evidence for one field; an armed origin and an interrupted write are distinct facts. */
internal data class FontFieldEvidence(
    val before: FontData,
    val target: FontData,
    val origin: FontData?,
    val isInterrupted: Boolean,
)
