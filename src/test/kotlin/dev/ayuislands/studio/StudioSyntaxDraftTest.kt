package dev.ayuislands.studio

import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.migration.LegacySnapshot
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSyntaxEdit
import dev.ayuislands.studio.model.StudioSyntaxReadability
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioLegacyFields
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioSyntaxEditing
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.syntax.FontEmphasis
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxReadabilityOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class StudioSyntaxDraftTest {
    @Test
    fun `named presets change the inheritance base while Custom keeps remembered cells`() {
        val draft = draft()
        val saved = StudioSyntaxProjection.settings(draft.appearance)
        assertEquals("FUTURE_PRESET", saved.selectedPreset)
        assertEquals("CYBERPUNK", saved.subordinatePreset)
        assertEquals("FUTURE_STYLE", saved.customStyles["Java|COMMENT"])

        val named = edit(draft, StudioSyntaxEdit.Preset(SyntaxPreset.NEON))
        val namedSettings = saved.copy(selectedPreset = "NEON", subordinatePreset = "NEON")
        assertEquals(namedSettings, StudioSyntaxProjection.settings(named.appearance))

        val custom = edit(named, StudioSyntaxEdit.Preset(SyntaxPreset.CUSTOM))
        assertEquals(namedSettings.copy(selectedPreset = "CUSTOM"), StudioSyntaxProjection.settings(custom.appearance))
        assertEquals(draft.appearance.copy(syntax = custom.appearance.syntax), custom.appearance)
    }

    @Test
    fun `readability flags are independent and reversing each one restores the draft`() {
        val draft = draft()
        val saved = StudioSyntaxProjection.settings(draft.appearance)
        val options =
            mapOf(
                StudioSyntaxReadability.DIM_COMMENTS to SyntaxReadabilityOptions(dimComments = true),
                StudioSyntaxReadability.SOFTEN_DOCUMENTATION to SyntaxReadabilityOptions(softenDocumentation = true),
                StudioSyntaxReadability.QUIET_OPERATORS to SyntaxReadabilityOptions(quietOperators = true),
                StudioSyntaxReadability.EMPHASIZE_DECLARATIONS to
                    SyntaxReadabilityOptions(emphasizeDeclarations = true),
            )

        for ((option, readability) in options) {
            val enabled = edit(draft, StudioSyntaxEdit.Readability(option, true))
            assertEquals(saved.copy(readability = readability), StudioSyntaxProjection.settings(enabled.appearance))
            val restored = edit(enabled, StudioSyntaxEdit.Readability(option, false))
            assertEquals(draft.appearance, restored.appearance)
            assertFalse(StudioDraftPolicy.isDirty(restored))
        }
    }

    @Test
    fun `intensity edits replace one cell in place and the midpoint removes only that cell`() {
        val draft = draft()
        val saved = StudioSyntaxProjection.settings(draft.appearance)
        val edited = edit(draft, StudioSyntaxEdit.Intensity("Java", PrimitiveCategory.KEYWORD, 71))
        val editedSettings = StudioSyntaxProjection.settings(edited.appearance)
        assertEquals(saved.copy(customOverrides = saved.customOverrides + ("Java|KEYWORD" to "71")), editedSettings)
        assertEquals(saved.customOverrides.keys.toList(), editedSettings.customOverrides.keys.toList())

        val inherited = edit(edited, StudioSyntaxEdit.Intensity("Java", PrimitiveCategory.KEYWORD, 50))
        val inheritedSettings = StudioSyntaxProjection.settings(inherited.appearance)
        assertEquals(saved.copy(customOverrides = saved.customOverrides - "Java|KEYWORD"), inheritedSettings)
        assertEquals(
            saved.customOverrides.keys.filterNot { it == "Java|KEYWORD" },
            inheritedSettings.customOverrides.keys.toList(),
        )
    }

    @Test
    fun `font replacement and additive emphasis edit separate cells`() {
        val draft = draft()
        val saved = StudioSyntaxProjection.settings(draft.appearance)
        val styled = edit(draft, StudioSyntaxEdit.Style("Java", PrimitiveCategory.KEYWORD, FontStyleOverride.PLAIN))
        val styledSettings = saved.copy(customStyles = saved.customStyles + ("Java|KEYWORD" to "PLAIN"))
        assertEquals(styledSettings, StudioSyntaxProjection.settings(styled.appearance))

        val emphasized = edit(styled, StudioSyntaxEdit.Emphasis("Java", PrimitiveCategory.KEYWORD, FontEmphasis.ITALIC))
        assertEquals(
            styledSettings.copy(customEmphasis = styledSettings.customEmphasis + ("Java|KEYWORD" to "ITALIC")),
            StudioSyntaxProjection.settings(emphasized.appearance),
        )
        val inherited = edit(emphasized, StudioSyntaxEdit.Style("Java", PrimitiveCategory.KEYWORD, null))
        val cleared = edit(inherited, StudioSyntaxEdit.Emphasis("Java", PrimitiveCategory.KEYWORD, null))
        assertEquals(
            saved.copy(
                customStyles = saved.customStyles - "Java|KEYWORD",
                customEmphasis = saved.customEmphasis - "Java|KEYWORD",
            ),
            StudioSyntaxProjection.settings(cleared.appearance),
        )
    }

    @Test
    fun `reset removes only available cells of the selected language and keeps opaque values through reload`() {
        val document = migrate(legacy())
        val draft = catalogTestWork(document).drafts.single()
        val saved = StudioSyntaxProjection.settings(draft.appearance)
        val reset = edit(draft, StudioSyntaxEdit.Reset("Java", setOf(PrimitiveCategory.KEYWORD)))
        val resetSettings = StudioSyntaxProjection.settings(reset.appearance)
        assertEquals(
            saved.copy(
                customOverrides = saved.customOverrides - "Java|KEYWORD",
                customStyles = saved.customStyles - "Java|KEYWORD",
                customEmphasis = saved.customEmphasis - "Java|KEYWORD",
            ),
            resetSettings,
        )
        val maps =
            listOf(
                saved.customOverrides to resetSettings.customOverrides,
                saved.customStyles to resetSettings.customStyles,
                saved.customEmphasis to resetSettings.customEmphasis,
            )
        for ((original, remaining) in maps) {
            assertEquals(original.keys.filterNot { it == "Java|KEYWORD" }, remaining.keys.toList())
        }
        assertEquals(draft.appearance.copy(syntax = reset.appearance.syntax), reset.appearance)

        val accepted = document.currents.global.copy(appearance = reset.appearance)
        val updated = document.copy(currents = document.currents.copy(global = accepted))
        val reloaded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(updated)))
        assertEquals(reset.appearance.syntax, reloaded.document.currents.global.appearance.syntax)
    }

    @Test
    fun `Studio and legacy resolve named unknown and missing presets to the same effective settings`() {
        val selections = SyntaxPreset.entries.map { it.name } + listOf("FUTURE_PRESET", null)
        for (selection in selections) {
            val source =
                legacy().apply {
                    selectedPreset = selection
                    subordinatePreset = if (selection == null) null else "CYBERPUNK"
                    dimComments = true
                    quietOperators = true
                }
            val legacyConfig = SyntaxIntensityState().apply { loadState(source) }.toPresetConfig()
            val appearance = migrate(source).currents.global.appearance
            val studioConfig = StudioSyntaxProjection.config(appearance)
            assertEquals(legacyConfig, studioConfig, selection)
            assertEquals(
                SyntaxReadabilityOptions(dimComments = true, quietOperators = true),
                studioConfig.readabilityOptions,
                selection,
            )
            assertEquals(selection, StudioSyntaxProjection.settings(appearance).selectedPreset)
            assertEquals(selection ?: "AMBIENT", studioConfig.selectedPreset)
            assertEquals(if (selection == null) "AMBIENT" else "CYBERPUNK", studioConfig.subordinatePreset)
            assertEquals(134, studioConfig.customOverrides["Future"]?.get("FUTURE"))
            assertNull(studioConfig.customOverrides["Java"]?.get("COMMENT"))
            assertNull(studioConfig.customStyles["Java"]?.get("COMMENT"))
        }
    }

    private fun draft(): DraftRecord = catalogTestWork(migrate(legacy())).drafts.single()

    private fun edit(
        draft: DraftRecord,
        edit: StudioSyntaxEdit,
    ): DraftRecord = StudioSyntaxEditing.edit(draft, edit)

    private fun migrate(syntax: SyntaxIntensityBaseState): StudioDocument {
        val archives = StudioList(LegacyStore.entries.map { LegacyArchive(it, LegacyFile.Absent) })
        val fresh = capture(SyntaxIntensityBaseState())
        return assertIs<MigrationResult.Ready>(
            StudioLegacyMigration.plan(
                LegacySnapshot(
                    MigrationSource("syntax", archives),
                    capture(syntax),
                    StudioFactoryProfiles.create(fresh),
                    "syntax-migration",
                ),
            ),
        ).document
    }

    private fun capture(syntax: SyntaxIntensityBaseState) =
        StudioLegacyFields.capture(freshSettingsState(), syntax, AccentMappingsState(), origins = emptyMap())

    private fun legacy(): SyntaxIntensityBaseState =
        SyntaxIntensityBaseState().apply {
            selectedPreset = "FUTURE_PRESET"
            subordinatePreset = "CYBERPUNK"
            customOverrides["Future|FUTURE"] = "134"
            customOverrides["Java|KEYWORD"] = " 63 "
            customOverrides["Java|COMMENT"] = "not a number"
            customOverrides["Kotlin|KEYWORD"] = "-7"
            customOverrides["malformed"] = " untouched "
            customStyles["Java|KEYWORD"] = "BOLD"
            customStyles["Java|COMMENT"] = "FUTURE_STYLE"
            customStyles["Kotlin|KEYWORD"] = "ITALIC"
            customEmphasis["Java|KEYWORD"] = "BOLD"
            customEmphasis["Java|COMMENT"] = "FUTURE_EMPHASIS"
            customEmphasis["Kotlin|KEYWORD"] = "BOLD_ITALIC"
            schemaVersion = 99
        }
}
