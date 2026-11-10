package dev.ayuislands.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import dev.ayuislands.studio.persistence.StudioStateCodec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.assertIs

class StateRoundTripIntegrationTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            unmockkObject(StudioService.Companion)
        } finally {
            super.tearDown()
        }
    }

    fun testSettingsServiceHasNoInventedStateBeforeAuthorityIsReady() {
        val settings = AyuIslandsSettings.getInstance()
        assertNotNull("AyuIslandsSettings service must be registered", settings)
        val service = mockk<StudioService>()
        mockkObject(StudioService.Companion)
        every { StudioService.getInstance() } returns service
        every { service.acceptedView } returns null

        assertNull(settings.state)
        assertNull(settings.getAccentForVariant(AyuVariant.MIRAGE))
    }

    fun testPersistedChoicesReachTheReadOnlySettingsService() {
        val state =
            AyuIslandsState().apply {
                mirageAccent = "#FF0000"
                glowEnabled = true
            }
        val migrated =
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(StudioFixtures.capture(state)),
            ).document
        val reloaded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(migrated))).document
        val service = mockk<StudioService>()
        mockkObject(StudioService.Companion)
        every { StudioService.getInstance() } returns service
        every { service.acceptedView } returns
            requireNotNull(StudioLegacyProjection.project(reloaded, StudioTarget.Global))

        val accepted = requireNotNull(AyuIslandsSettings.getInstance().state)

        assertEquals("#FF0000", accepted.mirageAccent)
        assertTrue(accepted.glowEnabled)
        assertEquals(migrated, reloaded)
    }
}
