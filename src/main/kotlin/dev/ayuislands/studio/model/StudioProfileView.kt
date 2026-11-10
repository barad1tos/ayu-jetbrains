package dev.ayuislands.studio.model

internal sealed interface StudioProfileChoice {
    data class Profile(
        val id: ProfileId,
    ) : StudioProfileChoice

    data class Draft(
        val id: DraftId,
    ) : StudioProfileChoice
}

internal enum class StudioProfileKind { FACTORY, USER, DETACHED }

internal enum class StudioProfileChange { CREATE, RENAME, UPDATE, DELETE }

/** Accepted name and staged presentation stay separate from profile definitions. */
internal data class StudioProfileLabel(
    val name: String,
    val pendingName: String? = null,
    val qualifier: String? = null,
)

internal data class StudioProfileRow(
    val choice: StudioProfileChoice,
    val label: StudioProfileLabel,
    val kind: StudioProfileKind,
    val pending: StudioList<StudioProfileChange>,
    val isCurrent: Boolean,
    val isEnabled: Boolean,
)

/** Derived presentation only; selected identities and accepted appearance remain session-owned. */
internal data class StudioProfileView(
    val rows: StudioList<StudioProfileRow>,
    val selected: StudioProfileChoice?,
    val currentName: String,
    val preview: PreviewMode,
    val isPreviewEnabled: Boolean,
    val hasPendingChanges: Boolean,
)
