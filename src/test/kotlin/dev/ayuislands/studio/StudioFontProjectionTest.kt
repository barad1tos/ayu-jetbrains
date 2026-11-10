package dev.ayuislands.studio

import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontWeight
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioFontRequest
import dev.ayuislands.studio.session.StudioFontProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioFontProjectionTest {
    @Test
    fun `disabled fonts do not require a recognized preset or usable customization`() {
        val appearance =
            migrateAppearance(
                AyuIslandsState().apply {
                    fontPresetEnabled = false
                    fontPresetName = "FUTURE"
                    fontPresetCustomizations["FUTURE"] = "NaN|-2|future|unknown"
                },
            )

        assertEquals(StudioFontRequest.Disabled, StudioFontProjection.request(appearance))
        assertEquals(RawValue.Text("FUTURE"), appearance.font.value("fontPresetName"))
    }

    @Test
    fun `legacy alias selects existing decoder while raw names and unrelated entries retain their order`() {
        val appearance =
            migrateAppearance(
                AyuIslandsState().apply {
                    fontPresetEnabled = true
                    fontPresetName = "CLEAN"
                    fontApplyToConsole = true
                    fontPresetCustomizations["FUTURE"] = " untouched "
                    fontPresetCustomizations["AMBIENT"] = "14.5|1.4|false|MEDIUM"
                },
            )
        val request = assertIs<StudioFontRequest.Enabled>(StudioFontProjection.request(appearance))

        assertEquals(FontPreset.AMBIENT, request.settings.preset)
        assertEquals("Maple Mono", request.settings.fontFamily)
        assertEquals(14.5f, request.settings.fontSize)
        assertEquals(1.4f, request.settings.lineSpacing)
        assertFalse(request.settings.enableLigatures)
        assertEquals(FontWeight.MEDIUM, request.settings.weight)
        assertTrue(request.settings.applyToConsole)
        assertEquals(RawValue.Text("CLEAN"), appearance.font.value("fontPresetName"))
        val customizations = assertIs<RawValue.OrderedMap>(appearance.font.value("fontPresetCustomizations"))
        assertEquals(listOf("FUTURE", "AMBIENT"), customizations.entries.map { it.key })
        assertEquals(RawValue.Text(" untouched "), customizations.entries.first().value)
    }

    @Test
    fun `unknown enabled preset remains unavailable instead of falling back to Ambient`() {
        val appearance =
            migrateAppearance(
                AyuIslandsState().apply {
                    fontPresetEnabled = true
                    fontPresetName = "FUTURE"
                },
            )

        assertEquals(StudioFontRequest.Unavailable("FUTURE"), StudioFontProjection.request(appearance))
    }

    @Test
    fun `custom settings retain requested dimensions and a missing name keeps the legacy default`() {
        val custom =
            migrateAppearance(
                AyuIslandsState().apply {
                    fontPresetEnabled = true
                    fontPresetName = "CUSTOM"
                    fontPresetCustomizations["CUSTOM"] = "15|5|true|REGULAR|Personal font"
                },
            )
        val requested = assertIs<StudioFontRequest.Enabled>(StudioFontProjection.request(custom)).settings
        assertEquals("Personal font", requested.fontFamily)
        assertEquals(5f, requested.lineSpacing)
        assertEquals(15f, requested.fontSize)
        assertFalse(requested.applyToConsole)
        val absent =
            migrateAppearance(
                AyuIslandsState().apply {
                    fontPresetEnabled = true
                    fontPresetName = null
                },
            )
        val default = assertIs<StudioFontRequest.Enabled>(StudioFontProjection.request(absent)).settings
        assertEquals(FontPreset.AMBIENT, default.preset)
        assertEquals(FontPreset.AMBIENT.fontSize, default.fontSize)
        assertEquals(RawValue.Null, absent.font.value("fontPresetName"))
    }

    private fun migrateAppearance(state: AyuIslandsState): AppearanceSnapshot =
        assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture(state)))
            .document.currents.global.appearance
}
