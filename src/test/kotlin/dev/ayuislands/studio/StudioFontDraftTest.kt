package dev.ayuislands.studio

import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontWeight
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.migration.LegacySnapshot
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioDomain
import dev.ayuislands.studio.model.StudioFontEdit
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioLegacyFields
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.studio.session.StudioAcceptanceMerge
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioFontEditing
import dev.ayuislands.studio.session.StudioFontProjection
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioFontDraftTest {
    @Test
    fun `legacy aliases resolve without normalizing stored preset names or records`() {
        val source = customizedState()
        val document = migrate(source)
        val appearance = document.currents.global.appearance
        val font = StudioFontProjection.settings(appearance)
        val legacyRecords = source.fontPresetCustomizations.toMutableMap()
        FontPreset.migrateCustomizations(legacyRecords)
        val legacySettings = FontSettings.decode(legacyRecords.getValue("AMBIENT"), FontPreset.AMBIENT)

        assertEquals("CLEAN", font.presetName)
        assertEquals(legacySettings.copy(applyToConsole = true), font.resolved)
        assertEquals(source.fontPresetCustomizations.toList(), records(appearance))

        source.fontPresetCustomizations["AMBIENT"] = "19.5|1.25|false|MEDIUM"
        val canonical = StudioFontProjection.settings(migrate(source).currents.global.appearance)
        assertEquals(19.5f, canonical.resolved?.fontSize)
        assertEquals("CLEAN", canonical.presetName)
    }

    @Test
    fun `a typography edit touches only its own slot in the original ordered record`() {
        val document = migrate(customizedState())
        val draft = catalogTestWork(document).drafts.single()
        val size = edit(draft, StudioFontEdit.Size(17.25f))
        val spacing = edit(size, StudioFontEdit.Spacing(1.45f))
        val weight = edit(spacing, StudioFontEdit.Weight(FontWeight.MEDIUM))
        val ligatures = edit(weight, StudioFontEdit.Ligatures(false))
        val written = "17.25|1.45|false|MEDIUM|Historical Font| future "
        val expected = records(draft.appearance).map { (id, input) -> id to if (id == "CLEAN") written else input }
        assertEquals(expected, records(ligatures.appearance))
        assertEquals("CLEAN", StudioFontProjection.settings(ligatures.appearance).presetName)
        assertEquals(draft.appearance.copy(font = ligatures.appearance.font), ligatures.appearance)
        assertEquals(ligatures, edit(ligatures, StudioFontEdit.Family("Another Family")))

        val accepted = document.currents.global.copy(appearance = ligatures.appearance)
        val updated = document.copy(currents = document.currents.copy(global = accepted))
        val reloaded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(updated)))
        assertEquals(ligatures.appearance.font, reloaded.document.currents.global.appearance.font)
    }

    @Test
    fun `selecting Custom and toggling the preset keep per-preset customization and console independent`() {
        val draft = catalogTestWork(migrate(customizedState())).drafts.single()
        val disabled = edit(draft, StudioFontEdit.Enable(false))
        val original = StudioFontProjection.settings(draft.appearance)
        assertEquals(original.copy(enabled = false), StudioFontProjection.settings(disabled.appearance))
        val restored = edit(disabled, StudioFontEdit.Enable(true))
        assertEquals(draft, restored)
        assertFalse(StudioDraftPolicy.isDirty(restored))

        val custom = edit(restored, StudioFontEdit.Preset(FontPreset.CUSTOM))
        assertEquals(records(draft.appearance), records(custom.appearance))
        val family = edit(custom, StudioFontEdit.Family("JetBrains Mono"))
        assertEquals(
            "18|1.15|false|LIGHT|JetBrains Mono|custom extension",
            records(family.appearance).toMap().getValue("CUSTOM"),
        )
        val console = edit(family, StudioFontEdit.Console(false))
        assertEquals(records(family.appearance), records(console.appearance))
        assertEquals(false, StudioFontProjection.settings(console.appearance).resolved?.applyToConsole)
    }

    @Test
    fun `unavailable presets remain stored and disabled reapply leaves the draft unchanged`() {
        val source = customizedState().apply { fontPresetName = "FUTURE_FONT" }
        val draft = catalogTestWork(migrate(source)).drafts.single()
        val loaded = StudioFontProjection.settings(draft.appearance)
        assertEquals("FUTURE_FONT", loaded.presetName)
        assertNull(loaded.resolved)
        assertEquals(draft, edit(draft, StudioFontEdit.Size(20f)))
        assertEquals(draft, edit(draft, StudioFontEdit.Reapply))
        val disabled = edit(draft, StudioFontEdit.Enable(false))
        val selected = edit(disabled, StudioFontEdit.Preset(FontPreset.NEON))
        assertEquals(selected, edit(selected, StudioFontEdit.Reapply))

        source.fontPresetName = null
        val unset = StudioFontProjection.settings(migrate(source).currents.global.appearance)
        assertNull(unset.presetName)
        assertEquals(FontPreset.AMBIENT, unset.resolved?.preset)
    }

    @Test
    fun `reset restores the v2 defaults and preserves unavailable entries and extension slots`() {
        val draft = catalogTestWork(migrate(customizedState())).drafts.single()
        val reset = edit(draft, StudioFontEdit.Reset)
        val state = StudioFontProjection.settings(reset.appearance)
        val original = records(draft.appearance)
        val after = records(reset.appearance)
        assertEquals("AMBIENT", state.presetName)
        assertTrue(state.enabled)
        assertEquals("13.0|1.3|true|REGULAR|Historical Font| future ", after.toMap().getValue("CLEAN"))
        assertEquals(
            "${FontSettings.fromPreset(FontPreset.CUSTOM).encode()}|custom extension",
            after.toMap().getValue("CUSTOM"),
        )
        assertEquals(original.map { it.first }, after.map { it.first })
        for (key in listOf("FUTURE_FONT", "MODERN", "NEON")) {
            assertEquals(original.toMap().getValue(key), after.toMap().getValue(key))
        }
        assertEquals(false, state.resolved?.applyToConsole)
    }

    @Test
    fun `reapply stages the Font owner once and can be discarded without any appearance edits`() {
        val draft = catalogTestWork(migrate(customizedState())).drafts.single()
        val pending = edit(draft, StudioFontEdit.Reapply)
        assertTrue(StudioDraftPolicy.isDirty(pending))
        assertEquals(listOf(StudioDomain.FONT), StudioAcceptanceMerge.reapplyOwners(pending))
        assertEquals(draft.appearance, pending.appearance)
        assertEquals(pending, edit(pending, StudioFontEdit.Reapply))
        assertFalse(StudioDraftPolicy.isDirty(StudioDraftPolicy.discard(pending)))
    }

    private fun edit(
        draft: DraftRecord,
        edit: StudioFontEdit,
    ): DraftRecord = StudioFontEditing.edit(draft, edit)

    private fun records(appearance: AppearanceSnapshot): List<Pair<String, String>> {
        val field = appearance.font.records.single { it.key.name == "fontPresetCustomizations" }
        val records = assertIs<RawValue.OrderedMap>(field.value).entries
        return records.map { it.key to assertIs<RawValue.Text>(it.value).value }
    }

    private fun migrate(state: AyuIslandsState): StudioDocument {
        val archives = StudioList(LegacyStore.entries.map { LegacyArchive(it, LegacyFile.Absent) })
        return assertIs<MigrationResult.Ready>(
            StudioLegacyMigration.plan(
                LegacySnapshot(
                    MigrationSource("font", archives),
                    capture(state),
                    StudioFactoryProfiles.create(capture(freshSettingsState())),
                    "font-migration",
                ),
            ),
        ).document
    }

    private fun capture(state: AyuIslandsState) =
        StudioLegacyFields.capture(state, SyntaxIntensityBaseState(), AccentMappingsState(), origins = emptyMap())

    private fun customizedState(): AyuIslandsState =
        AyuIslandsState().apply {
            fontPresetEnabled = true
            fontPresetName = "CLEAN"
            fontApplyToConsole = true
            fontPresetCustomizations =
                linkedMapOf(
                    "FUTURE_FONT" to "keep exactly",
                    "CLEAN" to "13.75|1.30|true|REGULAR|Historical Font| future ",
                    "MODERN" to "15|1.25|false|THIN",
                    "NEON" to "13|1.20|true|REGULAR",
                    "CUSTOM" to "18|1.15|false|LIGHT|Original Family|custom extension",
                )
        }
}
