package dev.ayuislands.settings

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelWidthStateTest {
    private lateinit var state: PanelWidthState

    @BeforeTest
    fun setUp() {
        state = PanelWidthState()
    }

    @Test
    fun `default state is not modified`() {
        assertFalse(state.isModified())
    }

    @Test
    fun `load sets both pending and stored`() {
        state.load(PanelWidthMode.AUTO_FIT, 500, 300)

        assertEquals(PanelWidthMode.AUTO_FIT, state.pendingMode)
        assertEquals(PanelWidthMode.AUTO_FIT, state.storedMode)
        assertEquals(500, state.pendingAutoFitMaxWidth)
        assertEquals(500, state.storedAutoFitMaxWidth)
        assertEquals(300, state.pendingFixedWidth)
        assertEquals(300, state.storedFixedWidth)
        assertFalse(state.isModified())
    }

    @Test
    fun `changing pending mode marks as modified`() {
        state.load(PanelWidthMode.DEFAULT, 400, 200)
        state.pendingMode = PanelWidthMode.FIXED

        assertTrue(state.isModified())
    }

    @Test
    fun `changing pending auto-fit width marks as modified`() {
        state.load(PanelWidthMode.AUTO_FIT, 400, 200)
        state.pendingAutoFitMaxWidth = 600

        assertTrue(state.isModified())
    }

    @Test
    fun `changing pending fixed width marks as modified`() {
        state.load(PanelWidthMode.FIXED, 400, 200)
        state.pendingFixedWidth = 350

        assertTrue(state.isModified())
    }

    @Test
    fun `commitStored syncs stored to pending`() {
        state.load(PanelWidthMode.DEFAULT, 400, 200)
        state.pendingMode = PanelWidthMode.FIXED
        state.pendingFixedWidth = 350
        assertTrue(state.isModified())

        state.commitStored()

        assertFalse(state.isModified())
        assertEquals(PanelWidthMode.FIXED, state.storedMode)
        assertEquals(350, state.storedFixedWidth)
    }

    @Test
    fun `reset reverts pending to stored`() {
        state.load(PanelWidthMode.AUTO_FIT, 500, 300)
        state.pendingMode = PanelWidthMode.FIXED
        state.pendingAutoFitMaxWidth = 700
        state.pendingFixedWidth = 400
        assertTrue(state.isModified())

        state.reset()

        assertFalse(state.isModified())
        assertEquals(PanelWidthMode.AUTO_FIT, state.pendingMode)
        assertEquals(500, state.pendingAutoFitMaxWidth)
        assertEquals(300, state.pendingFixedWidth)
    }

    @Test
    fun `widthSummary formats default mode`() {
        state.pendingMode = PanelWidthMode.DEFAULT

        assertEquals("Default", PanelWidthState.widthSummary(state))
    }

    @Test
    fun `widthSummary formats auto-fit mode with max width`() {
        state.pendingMode = PanelWidthMode.AUTO_FIT
        state.pendingAutoFitMaxWidth = 500

        assertEquals("Auto-fit \u00B7 max 500px", PanelWidthState.widthSummary(state))
    }

    @Test
    fun `widthSummary formats fixed mode with width`() {
        state.pendingMode = PanelWidthMode.FIXED
        state.pendingFixedWidth = 350

        assertEquals("Fixed \u00B7 350px", PanelWidthState.widthSummary(state))
    }
}
