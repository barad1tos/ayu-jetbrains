package dev.ayuislands.studio

import dev.ayuislands.font.FontWeight
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.fields.StudioMetadataPolicy
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioFontRequest
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.session.StudioFontProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class StudioColdMigrationTest {
    @Test
    fun `legacy welcome marker adapts once and later license rearm survives document reload`() {
        val input =
            StudioFixtures.capture(
                AyuIslandsState().apply {
                    trialWelcomeShown = true
                    premiumOnboardingShown = false
                    lastConfirmedEntitlement = "UNLICENSED"
                },
            )
        val migrated = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(input)).document
        assertEquals(RawValue.BooleanValue(true), migrated.auxiliary.ui.value("premiumOnboardingShown"))
        assertEquals(RawValue.BooleanValue(false), input.fields.value("premiumOnboardingShown"))
        assertEquals(input.source, migrated.header.migration)
        val rearmed = requireNotNull(StudioMetadataPolicy.apply(migrated, StudioMetadataCommand.Entitlement(true, 10)))
        val reloaded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(rearmed))).document
        assertEquals(RawValue.BooleanValue(false), reloaded.auxiliary.ui.value("premiumOnboardingShown"))
        assertEquals(RawValue.BooleanValue(true), reloaded.auxiliary.ui.value("trialWelcomeShown"))
        assertEquals(input.source, reloaded.header.migration)
    }

    @Test
    fun `legacy width flags adapt only default modes while preserving explicit modern modes and archive`() {
        val input =
            StudioFixtures.capture(
                AyuIslandsState().apply {
                    autoFitProjectPanelWidth = true
                    projectPanelWidthMode = "DEFAULT"
                    autoFitCommitPanelWidth = true
                    commitPanelWidthMode = "FIXED"
                },
            )
        val migrated = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(input)).document
        assertEquals(RawValue.Text("AUTO_FIT"), migrated.shared.workspace.value("projectPanelWidthMode"))
        assertEquals(RawValue.Text("FIXED"), migrated.shared.workspace.value("commitPanelWidthMode"))
        assertEquals(RawValue.BooleanValue(true), migrated.shared.workspace.value("autoFitProjectPanelWidth"))
        assertEquals(RawValue.Text("DEFAULT"), input.fields.value("projectPanelWidthMode"))
        assertEquals(input.source, migrated.header.migration)
        assertEquals(
            migrated,
            assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(migrated))).document,
        )
    }

    @Test
    fun `legacy font customization follows canonical alias without rewriting stored map`() {
        val input =
            StudioFixtures.capture(
                AyuIslandsState().apply {
                    fontPresetEnabled = true
                    fontPresetName = "CLEAN"
                    fontPresetCustomizations["FUTURE"] = " untouched "
                    fontPresetCustomizations["CLEAN"] = "19|1.7|false|MEDIUM"
                },
            )
        val appearance =
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(input),
            ).document.currents.global.appearance
        val selected = assertIs<StudioFontRequest.Enabled>(StudioFontProjection.request(appearance)).settings
        assertEquals(19f, selected.fontSize)
        assertEquals(1.7f, selected.lineSpacing)
        assertFalse(selected.enableLigatures)
        assertEquals(FontWeight.MEDIUM, selected.weight)
        assertEquals(input.fields.value("fontPresetCustomizations"), appearance.font.value("fontPresetCustomizations"))
        assertEquals(RawValue.Text("CLEAN"), appearance.font.value("fontPresetName"))
    }

    @Test
    fun `canonical font customization wins alias collision without changing dormant order`() {
        val input =
            StudioFixtures.capture(
                AyuIslandsState().apply {
                    fontPresetEnabled = true
                    fontPresetName = "CLEAN"
                    fontPresetCustomizations["CLEAN"] = "19|1.7|false|MEDIUM"
                    fontPresetCustomizations["FUTURE"] = " untouched "
                    fontPresetCustomizations["AMBIENT"] = "15|1.2|true|REGULAR"
                },
            )
        val appearance =
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(input),
            ).document.currents.global.appearance
        val selected = assertIs<StudioFontRequest.Enabled>(StudioFontProjection.request(appearance)).settings
        assertEquals(15f, selected.fontSize)
        assertEquals(1.2f, selected.lineSpacing)
        assertEquals(input.fields.value("fontPresetCustomizations"), appearance.font.value("fontPresetCustomizations"))
    }
}
