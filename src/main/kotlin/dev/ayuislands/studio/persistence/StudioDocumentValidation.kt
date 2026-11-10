package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.FactoryRecipe
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.session.StudioSessionValidation

internal object StudioDocumentValidation {
    fun validate(document: StudioDocument) {
        require(document.header.schema == 1 && document.header.revision >= 0)
        require(
            document.header.transactionId.isNotBlank() &&
                document.header.migration.id
                    .isNotBlank(),
        )
        val archives =
            document.header.migration.archives
                .map { it.store }
        require(archives.size == LegacyStore.entries.size && archives.toSet() == LegacyStore.entries.toSet())
        require(
            document.catalog
                .map { it.id }
                .distinct()
                .size == document.catalog.size,
        )
        require(
            document.currents.projects
                .map { it.key }
                .distinct()
                .size == document.currents.projects.size,
        )
        require(
            document.sessions
                .map { it.id }
                .distinct()
                .size == document.sessions.size,
        )
        val factories = document.catalog.filterIsInstance<ProfileDefinition.Factory>()
        require(factories.map { it.id }.toSet() == FactoryRecipe.entries.map { it.id }.toSet())
        for (profile in document.catalog) {
            require(profile.id.value.isNotBlank())
            when (profile) {
                is ProfileDefinition.Factory -> require(profile.recipeVersion > 0)
                is ProfileDefinition.User -> require(profile.revision >= 0 && !profile.id.value.startsWith("factory:"))
            }
        }
        StudioSessionValidation.validateAccepted(document.currents.global)
        for ((key, _, current) in document.currents.projects) {
            require(key.value.isNotBlank())
            StudioSessionValidation.validateAccepted(current)
        }
        document.sessions.forEach(StudioSessionValidation::validate)
        validateOwner(document.shared.plugins, StudioFieldOwner.PLUGINS)
        validateOwner(document.shared.workspace, StudioFieldOwner.WORKSPACE)
        validateOwner(document.shared.system, StudioFieldOwner.SYSTEM)
        validateOwner(document.shared.mappings, StudioFieldOwner.MAPPINGS)
        validateOwner(document.auxiliary.ui, StudioFieldOwner.UI)
        validateOwner(document.auxiliary.resources, StudioFieldOwner.RESOURCES)
        validateOwner(document.auxiliary.legacyMarkers, StudioFieldOwner.MIGRATION)
        validateOwner(document.runtime.legacy, StudioFieldOwner.RUNTIME)
    }

    private fun validateOwner(
        fields: StudioFields,
        owner: StudioFieldOwner,
    ) {
        val expected = StudioFieldCatalog.fields.filter { it.owner == owner }.map { LegacyFieldKey(it.source, it.name) }
        require(fields.records.map { it.key }.toSet() == expected.toSet()) { "Incomplete or misplaced owner fields" }
    }
}
