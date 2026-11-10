package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioProfileKind
import dev.ayuislands.studio.model.StudioProfileLabel

/** Shared profile and draft names; qualifiers distinguish collisions without changing stored names or order. */
internal object StudioProfileLabels {
    fun project(
        document: StudioDocument,
        work: SessionWork?,
    ): Map<StudioProfileChoice, StudioProfileLabel> {
        val intents = work?.catalog.orEmpty()
        val profiles = document.catalog + intents.filterIsInstance<CatalogIntent.Create>().map { it.definition }
        val profileNames =
            profiles.map { profile ->
                val pending = intents.filter { StudioCatalogStaging.profileId(it) == profile.id }
                ProfileName(
                    StudioProfileChoice.Profile(profile.id),
                    StudioProfileLabel(
                        profile.name,
                        pending.filterIsInstance<CatalogIntent.Rename>().singleOrNull()?.name,
                    ),
                    if (profile is ProfileDefinition.Factory) StudioProfileKind.FACTORY else StudioProfileKind.USER,
                )
            }
        val profileIds = profiles.map { it.id }.toSet()
        val detachedNames =
            work?.drafts.orEmpty().filter { it.source?.id !in profileIds }.map { draft ->
                ProfileName(
                    StudioProfileChoice.Draft(draft.id),
                    StudioProfileLabel(draft.sourceName ?: CURRENT_APPEARANCE),
                    StudioProfileKind.DETACHED,
                )
            }
        val entries = profileNames + detachedNames
        val names = entries.groupBy { it.label.pendingName ?: it.label.name }
        return entries.associate { entry ->
            val matches = names.getValue(entry.label.pendingName ?: entry.label.name)
            val label =
                if (matches.size == 1) entry.label else entry.label.copy(qualifier = qualifier(entry, matches))
            entry.choice to label
        }
    }

    private fun qualifier(
        entry: ProfileName,
        matches: List<ProfileName>,
    ): String {
        if (entry.kind == StudioProfileKind.FACTORY) return "Factory"
        val idText = identity(entry.choice)
        val peers = matches.filter { it.kind == entry.kind && it.choice != entry.choice }.map { identity(it.choice) }
        var length = minOf(SHORT_ID_LENGTH, idText.length)
        while (length < idText.length && peers.any { it.take(length) == idText.take(length) }) length++
        val kind = if (entry.kind == StudioProfileKind.DETACHED) "Draft" else "User"
        return "$kind · ${idText.take(length)}"
    }

    private fun identity(choice: StudioProfileChoice): String =
        when (choice) {
            is StudioProfileChoice.Profile -> choice.id.value
            is StudioProfileChoice.Draft -> choice.id.value
        }

    const val CURRENT_APPEARANCE = "Current appearance"
    private const val SHORT_ID_LENGTH = 8
}

private data class ProfileName(
    val choice: StudioProfileChoice,
    val label: StudioProfileLabel,
    val kind: StudioProfileKind,
)
