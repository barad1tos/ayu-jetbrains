package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxReadabilityOptions
import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StudioSyntaxProjectionTest {
    @Test
    fun `all legacy presets and custom styles produce the same runtime config after migration`() {
        for (preset in SyntaxPreset.entries) {
            val state =
                SyntaxIntensityBaseState().apply {
                    selectedPreset = preset.name
                    subordinatePreset = "CYBERPUNK"
                    customOverrides["Kotlin|KEYWORD"] = "73"
                    customOverrides["Java|COMMENT"] = "19"
                    customStyles["Kotlin|KEYWORD"] = "BOLD_ITALIC"
                    customEmphasis["Java|COMMENT"] = "ITALIC"
                    dimComments = true
                    softenDocumentation = true
                    quietOperators = true
                    emphasizeDeclarations = true
                }
            val expected =
                SyntaxPresetConfig(
                    selectedPreset = preset.name,
                    subordinatePreset = "CYBERPUNK",
                    customOverrides = mapOf("Kotlin" to mapOf("KEYWORD" to 73), "Java" to mapOf("COMMENT" to 19)),
                    customStyles = mapOf("Kotlin" to mapOf("KEYWORD" to (Font.BOLD or Font.ITALIC))),
                    customEmphasis = mapOf("Java" to mapOf("COMMENT" to Font.ITALIC)),
                    readabilityOptions =
                        SyntaxReadabilityOptions(
                            dimComments = true,
                            softenDocumentation = true,
                            quietOperators = true,
                            emphasizeDeclarations = true,
                        ),
                )

            assertEquals(expected, StudioSyntaxProjection.config(migrate(state)), preset.name)
        }
    }

    @Test
    fun `opaque cells remain intact while the existing decoder selects usable runtime values`() {
        val state =
            SyntaxIntensityBaseState().apply {
                selectedPreset = "FUTURE"
                subordinatePreset = "FUTURE_BASE"
                customOverrides["bad key"] = " untouched "
                customOverrides["FutureLang|FUTURE_CATEGORY"] = "134"
                customOverrides["Kotlin|KEYWORD"] = "-9"
                customOverrides["Java|COMMENT"] = "not a number"
                customStyles["Kotlin|KEYWORD"] = "FUTURE_STYLE"
                customStyles["Java|COMMENT"] = "ITALIC"
                customEmphasis["Java|COMMENT"] = "BOLD"
                customEmphasis["bad key"] = "FUTURE_EMPHASIS"
                schemaVersion = 99
            }
        val original = StudioFixtures.fields(syntax = state)
        val appearance = migrate(state)
        val before = appearance.syntax.records.toList()
        val expected =
            SyntaxPresetConfig(
                selectedPreset = "FUTURE",
                subordinatePreset = "FUTURE_BASE",
                customOverrides =
                    mapOf(
                        "FutureLang" to mapOf("FUTURE_CATEGORY" to 134),
                        "Kotlin" to mapOf("KEYWORD" to -9),
                    ),
                customStyles = mapOf("Java" to mapOf("COMMENT" to Font.ITALIC)),
                customEmphasis = mapOf("Java" to mapOf("COMMENT" to Font.BOLD)),
            )

        assertEquals(expected, StudioSyntaxProjection.config(appearance))
        assertEquals(before, appearance.syntax.records.toList())
        assertEquals(original, StudioFixtures.fields(syntax = state))
    }

    @Test
    fun `absent preset names keep the legacy effective defaults without changing the raw snapshot`() {
        val state =
            SyntaxIntensityBaseState().apply {
                selectedPreset = null
                subordinatePreset = null
            }
        val appearance = migrate(state)
        val before = appearance.syntax.records.toList()

        assertEquals(SyntaxPresetConfig("AMBIENT", emptyMap()), StudioSyntaxProjection.config(appearance))
        assertNull(StudioSyntaxProjection.settings(appearance).selectedPreset)
        assertNull(StudioSyntaxProjection.settings(appearance).subordinatePreset)
        assertEquals(before, appearance.syntax.records.toList())
    }

    private fun migrate(state: SyntaxIntensityBaseState): AppearanceSnapshot {
        val capture = StudioFixtures.capture().copy(fields = StudioFixtures.fields(syntax = state))
        return assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture))
            .document.currents.global.appearance
    }
}
