package dev.ayuislands.settings

import dev.ayuislands.licensing.LicenseChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pattern U locks for [ProjectIconAccentSection]'s pending/stored logic —
 * the UI row itself is IDE glue exercised via the Accent tab; the license
 * boundary and persistence live here.
 */
class ProjectIconAccentSectionTest {
    private lateinit var state: AyuIslandsState

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(AyuIslandsSettings.Companion)
        unmockkObject(LicenseChecker)
    }

    @Test
    fun `licensed enable persists through apply and converges stored`() {
        val section = ProjectIconAccentSection()
        section.load()
        section.setPendingForTest(true)

        assertTrue(section.isModified(), "pending change must dirty the section")
        section.apply()

        assertTrue(state.projectIconAccentEnabled)
        assertFalse(section.isModified(), "apply must converge stored onto pending")
    }

    @Test
    fun `unlicensed enable never persists the premium toggle`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val section = ProjectIconAccentSection()
        section.load()
        section.setPendingForTest(true)

        section.apply()

        assertFalse(state.projectIconAccentEnabled, "premium toggle must not persist for free users")
    }

    @Test
    fun `unlicensed disable still persists so users can always turn the feature off`() {
        state.projectIconAccentEnabled = true
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val section = ProjectIconAccentSection()
        section.load()
        section.setPendingForTest(false)

        section.apply()

        assertFalse(state.projectIconAccentEnabled)
    }

    @Test
    fun `reset drops the pending change`() {
        val section = ProjectIconAccentSection()
        section.load()
        section.setPendingForTest(true)

        section.reset()

        assertFalse(section.isModified())
        section.apply()
        assertFalse(state.projectIconAccentEnabled, "reset pending must not leak into apply")
    }
}
