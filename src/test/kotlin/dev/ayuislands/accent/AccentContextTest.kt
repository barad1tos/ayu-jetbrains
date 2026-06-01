package dev.ayuislands.accent

import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccentContextTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        settings = mockk(relaxed = true)
        every { settings.state } returns state

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(AyuVariant.Companion)
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(AyuVariant.Companion)
        unmockkObject(AyuIslandsSettings.Companion)
    }

    @Test
    fun `detect returns Ayu context for active Ayu theme even when external enhancements are disabled`() {
        state.externalThemeEnhancementsEnabled = false
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        val context = AccentContext.detect()

        assertEquals(AccentContext.Ayu(AyuVariant.MIRAGE), context)
        assertEquals(AyuVariant.MIRAGE, context?.variant)
    }

    @Test
    fun `detect returns null for non-Ayu theme when external enhancements are disabled`() {
        state.externalThemeEnhancementsEnabled = false
        every { AyuVariant.detect() } returns null

        assertNull(AccentContext.detect())
    }

    @Test
    fun `detect returns External for non-Ayu theme when external enhancements are enabled`() {
        state.externalThemeEnhancementsEnabled = true
        every { AyuVariant.detect() } returns null

        val context = AccentContext.detect()

        assertEquals(AccentContext.External, context)
        assertNull(context?.variant)
    }

    @Test
    fun `isAccentActive returns true for Ayu and external contexts`() {
        state.externalThemeEnhancementsEnabled = false
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        assertTrue(AccentContext.isAccentActive())

        state.externalThemeEnhancementsEnabled = true
        every { AyuVariant.detect() } returns null
        assertTrue(AccentContext.isAccentActive())

        state.externalThemeEnhancementsEnabled = false
        every { AyuVariant.detect() } returns null
        assertFalse(AccentContext.isAccentActive())
    }

    @Test
    fun `ExternalAccentSource fromName returns matching source or Automatic fallback`() {
        assertEquals(ExternalAccentSource.MANUAL, ExternalAccentSource.fromName("MANUAL"))
        assertEquals(ExternalAccentSource.AUTOMATIC, ExternalAccentSource.fromName(null))
        assertEquals(ExternalAccentSource.AUTOMATIC, ExternalAccentSource.fromName("Manual"))
    }
}
