package dev.ayuislands.studio

import dev.ayuislands.font.FontPanelEvent
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioFontEditing
import dev.ayuislands.studio.session.StudioFontProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioFontEditingTest {
    @Test
    fun `disabled selection retains its requested typography and unknown preset stays unavailable`() {
        val disabled =
            draft {
                fontPresetEnabled = false
                fontPresetName = "CUSTOM"
                fontPresetCustomizations["CUSTOM"] = "17.5|1.7|false|MEDIUM|Personal Mono"
            }
        val selection = StudioFontProjection.selection(disabled.appearance)

        assertEquals("CUSTOM", selection.rawName)
        val settings = requireNotNull(selection.settings)
        assertEquals("Personal Mono", settings.fontFamily)
        assertEquals(17.5f, settings.fontSize)
        assertFalse(settings.enableLigatures)
        val unknown = draft { fontPresetName = "FUTURE" }
        val unavailable = StudioFontProjection.selection(unknown.appearance)
        assertEquals("FUTURE", unavailable.rawName)
        assertNull(unavailable.settings)
    }

    @Test
    fun `changing one typography slot retains opaque tokens inactive values and customization order`() {
        val initial =
            draft {
                fontPresetName = "CUSTOM"
                fontPresetCustomizations["FUTURE"] = " untouched "
                fontPresetCustomizations["CUSTOM"] = "17.50|1.70|true|FUTURE_WEIGHT|Personal Mono|future=42"
                fontPresetCustomizations["WHISPER"] = "15.00|1.10|false|LIGHT|opaque-tail"
            }
        val changed = edit(initial, FontPanelEvent.Size(18f))

        assertEquals(customizations(initial).map { it.first }, customizations(changed).map { it.first })
        assertEquals(
            mapOf(
                "FUTURE" to " untouched ",
                "CUSTOM" to "18.0|1.70|true|FUTURE_WEIGHT|Personal Mono|future=42",
                "WHISPER" to "15.00|1.10|false|LIGHT|opaque-tail",
            ),
            customizations(changed).toMap(),
        )
        assertEquals(initial.appearance.accent, changed.appearance.accent)
        assertEquals(initial.appearance.glow, changed.appearance.glow)
        assertEquals(initial.appearance.syntax, changed.appearance.syntax)
        assertEquals(initial.appearance.vcs, changed.appearance.vcs)
    }

    @Test
    fun `disable reenable and preset switching keep the same sparse customization memory`() {
        val initial =
            draft {
                fontPresetEnabled = true
                fontPresetName = "CUSTOM"
                fontApplyToConsole = true
                fontPresetCustomizations["CUSTOM"] = "19|1.4|false|MEDIUM|Personal Mono"
                fontPresetCustomizations["UNKNOWN"] = "opaque"
            }
        val disabled = edit(initial, FontPanelEvent.Enable(false))
        val switched = edit(disabled, FontPanelEvent.Preset(FontPreset.NEON))
        val restored = edit(edit(switched, FontPanelEvent.Preset(FontPreset.CUSTOM)), FontPanelEvent.Enable(true))

        assertEquals(initial.appearance, restored.appearance)
        assertEquals(customizations(initial), customizations(disabled))
        assertEquals(customizations(initial), customizations(switched))
        assertEquals(RawValue.BooleanValue(true), switched.appearance.font.value("fontApplyToConsole"))
        assertTrue(restored.tracking.touched.isEmpty())
    }

    @Test
    fun `explicit font defaults preserve enabled state unknown entries and other domains`() {
        val initial =
            draft {
                fontPresetEnabled = false
                fontPresetName = "CUSTOM"
                fontApplyToConsole = true
                fontPresetCustomizations["FUTURE"] = "opaque"
                fontPresetCustomizations["WHISPER"] = "19|1.9|false|MEDIUM"
                fontPresetCustomizations["CUSTOM"] = "21|1.6|false|LIGHT|Personal Mono"
            }
        val changed = edit(initial, FontPanelEvent.Reset)

        assertEquals(RawValue.BooleanValue(false), changed.appearance.font.value("fontPresetEnabled"))
        assertEquals(RawValue.Text("AMBIENT"), changed.appearance.font.value("fontPresetName"))
        assertEquals(RawValue.BooleanValue(false), changed.appearance.font.value("fontApplyToConsole"))
        assertEquals(customizations(initial).map { it.first }, customizations(changed).map { it.first })
        assertEquals(
            mapOf(
                "FUTURE" to "opaque",
                "WHISPER" to FontSettings.fromPreset(FontPreset.WHISPER).encode(),
                "CUSTOM" to FontSettings.fromPreset(FontPreset.CUSTOM).encode(),
            ),
            customizations(changed).toMap(),
        )
        assertEquals(initial.appearance.accent, changed.appearance.accent)
        assertEquals(initial.appearance.glow, changed.appearance.glow)
        assertEquals(initial.appearance.syntax, changed.appearance.syntax)
        assertEquals(initial.appearance.vcs, changed.appearance.vcs)
    }

    @Test
    fun `font defaults retain opaque suffixes and the curated family slot while resetting supported values`() {
        val initial =
            draft {
                fontPresetName = "AMBIENT"
                fontPresetCustomizations["AMBIENT"] = "19.00|1.90|false|MEDIUM|Legacy family|future=42||"
                fontPresetCustomizations["CUSTOM"] = "21|1.6|false|LIGHT|Personal Mono|future=custom||"
            }

        val changed = edit(initial, FontPanelEvent.Reset)

        assertEquals(customizations(initial).map { it.first }, customizations(changed).map { it.first })
        assertEquals(
            mapOf(
                "AMBIENT" to "${FontSettings.fromPreset(FontPreset.AMBIENT).encode()}|Legacy family|future=42||",
                "CUSTOM" to "${FontSettings.fromPreset(FontPreset.CUSTOM).encode()}|future=custom||",
            ),
            customizations(changed).toMap(),
        )
    }

    @Test
    fun `reapply targets the current font domain without rewriting unchanged preferences`() {
        val initial =
            draft {
                fontPresetEnabled = true
                fontPresetName = "AMBIENT"
                fontPresetCustomizations["AMBIENT"] = "17.00|1.30|true|REGULAR"
            }
        val intent = assertIs<SessionIntent.Reapply>(StudioFontEditing.edits(initial, FontPanelEvent.Reapply).single())

        assertEquals(initial.id, intent.draft)
        assertEquals(
            initial.appearance.font.records
                .map { FieldAddress(it.key) },
            intent.addresses,
        )
        assertEquals("17.00|1.30|true|REGULAR", customizations(initial).single().second)
        assertTrue(
            StudioFontEditing.edits(edit(initial, FontPanelEvent.Enable(false)), FontPanelEvent.Reapply).isEmpty(),
        )
    }

    @Test
    fun `unavailable preset rejects stale typography and reapply while keeping explicit preset selection`() {
        val initial =
            draft {
                fontPresetName = "FUTURE"
                fontPresetCustomizations["FUTURE"] = "opaque|future|value"
            }

        assertTrue(StudioFontEditing.edits(initial, FontPanelEvent.Size(20f)).isEmpty())
        assertTrue(StudioFontEditing.edits(initial, FontPanelEvent.Reapply).isEmpty())
        val changed = edit(initial, FontPanelEvent.Preset(FontPreset.WHISPER))
        assertEquals(RawValue.Text("WHISPER"), changed.appearance.font.value("fontPresetName"))
        assertEquals(customizations(initial), customizations(changed))
    }

    private fun draft(configure: AyuIslandsState.() -> Unit): DraftRecord {
        val settings = AyuIslandsState().apply(configure)
        val document =
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(StudioFixtures.capture(settings)),
            ).document
        return sessionRecord(document).work.drafts.single()
    }

    private fun edit(
        initial: DraftRecord,
        event: FontPanelEvent,
    ): DraftRecord =
        StudioFontEditing.edits(initial, event).fold(initial) { current, intent ->
            val field = assertIs<SessionIntent.EditField>(intent)
            assertEquals(initial.id, field.draft)
            StudioDraftPolicy.edit(current, field.address, field.value)
        }

    private fun customizations(draft: DraftRecord): List<Pair<String, String>> =
        assertIs<RawValue.OrderedMap>(draft.appearance.font.value("fontPresetCustomizations"))
            .entries
            .map { it.key to assertIs<RawValue.Text>(it.value).value }
}
