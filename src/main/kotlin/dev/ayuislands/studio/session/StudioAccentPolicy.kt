package dev.ayuislands.studio.session

import dev.ayuislands.accent.LanguageDetectionRules
import dev.ayuislands.settings.mappings.toStoredDouble
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.SystemAccentPolicy

/** Global compatibility policy gates the profile choice without replacing it. */
internal object StudioAccentPolicy {
    fun followsSystem(
        appearance: AppearanceSnapshot,
        policy: SystemAccentPolicy,
    ): Boolean =
        when (policy) {
            SystemAccentPolicy.FORCE_ON -> true
            SystemAccentPolicy.FORCE_OFF -> false
            SystemAccentPolicy.BY_PROFILE ->
                appearance.accent.records
                    .single { it.key.name == "followSystemAccent" }
                    .value == RawValue.BooleanValue(true)
        }

    /** Existing language weights need reclassification only when the mapping keys or effective rules change. */
    fun reclassifiesLanguage(
        before: StudioFields,
        after: StudioFields,
    ): Boolean {
        if (before.records == after.records) return false
        val previous = before.records.associate { it.key.name to it.value }
        val proposed = after.records.associate { it.key.name to it.value }
        return languageKeys(previous) != languageKeys(proposed) || rules(previous) != rules(proposed)
    }

    private fun languageKeys(values: Map<String, RawValue>): Set<String> =
        (values.getValue("languageAccents") as RawValue.OrderedMap).entries.map { it.key }.toSet()

    private fun rules(values: Map<String, RawValue>): LanguageDetectionRules.ResolutionPolicy =
        LanguageDetectionRules.ResolutionPolicy.fromStored(
            number(values.getValue("dominanceThreshold")),
            number(values.getValue("dominanceMarginRatio")),
            number(values.getValue("dominanceFloor")),
            number(values.getValue("tiebreakMinShare")),
        )

    private fun number(value: RawValue): Double = Float.fromBits((value as RawValue.FloatBits).bits).toStoredDouble()
}
