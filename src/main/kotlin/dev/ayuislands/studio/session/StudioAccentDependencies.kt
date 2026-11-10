package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentMappingsView
import dev.ayuislands.studio.fields.StudioFieldValues
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.StudioAcceptedInput
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioGlowSources
import dev.ayuislands.studio.model.StudioList

/** Records exact accepted mapping reads while the existing resolver owns the priority ladder. */
internal class StudioAccentDependencies(
    private val fields: StudioFields,
    private val delegate: AccentMappingsView,
) : AccentMappingsView by delegate {
    private val observed = linkedSetOf<FieldAddress>()

    val inputs: StudioList<StudioAcceptedInput>
        get() =
            StudioList(
                observed.map { address ->
                    val field = fields.records.single { it.key == address.field }
                    StudioAcceptedInput(address, StudioFieldValues.value(field, address.part))
                },
            )

    override fun projectAccent(projectKey: String): String? {
        record("projectAccents", FieldPart.MapEntry(projectKey))
        return delegate.projectAccent(projectKey)
    }

    override fun forcedLanguageId(projectKey: String): String? {
        record("forcedProjectLanguages", FieldPart.MapEntry(projectKey))
        return delegate.forcedLanguageId(projectKey)
    }

    override fun hasForcedLanguageEntry(projectKey: String): Boolean {
        record("forcedProjectLanguages", FieldPart.MapEntry(projectKey))
        return delegate.hasForcedLanguageEntry(projectKey)
    }

    override fun languageAccent(languageId: String): String? {
        record("languageAccents", FieldPart.MapEntry(languageId))
        return delegate.languageAccent(languageId)
    }

    override val languageFallbackAccent: String?
        get() {
            record("languageFallbackAccent")
            return delegate.languageFallbackAccent
        }

    override fun projectFallbackAccent(projectKey: String): String? {
        record("projectFallbackAccents", FieldPart.MapEntry(projectKey))
        return delegate.projectFallbackAccent(projectKey)
    }

    override fun hasProjectFallbackCandidate(projectKey: String): Boolean {
        record("projectFallbackAccents", FieldPart.MapEntry(projectKey))
        return delegate.hasProjectFallbackCandidate(projectKey)
    }

    fun recordRules() {
        for (name in StudioGlowSources.languageRules) record(name)
    }

    private fun record(
        name: String,
        part: FieldPart = FieldPart.Value,
    ) {
        observed.add(FieldAddress(fields.records.single { it.key.name == name }.key, part))
    }
}
