package dev.ayuislands.settings

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the apply/reset/isModified bookkeeping for the new `syncEditorScheme`
 * field on `AyuIslandsAppearancePanel`. The pending/stored/state triple is the
 * codebase's standard two-phase commit (mirrors `followAppearance` and
 * `nightTheme` directly above it); each branch is a known-shape bug source per
 * the project's apply-then-reset patterns, so each gets a direct test.
 *
 * Reaches into private `pendingSyncEditorScheme` / `storedSyncEditorScheme`
 * via reflection rather than spinning up the full Swing UI DSL panel. The
 * full panel build needs the IntelliJ Application service registry — too
 * much setup for what is essentially a 3-field state machine.
 */
class AyuIslandsAppearancePanelSyncEditorSchemeTest {
    private val state = AyuIslandsState()
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private lateinit var panel: AyuIslandsAppearancePanel

    @BeforeTest
    fun setUp() {
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state
        panel = AyuIslandsAppearancePanel()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `apply persists pending syncEditorScheme to settings state`() {
        state.syncEditorScheme = true
        setStored(true)
        setPending(false)

        panel.apply()

        assertFalse(state.syncEditorScheme, "apply must flush pending to settings.state")
        assertEquals(
            false,
            getStored(),
            "apply must update stored to match pending so subsequent isModified returns false",
        )
    }

    @Test
    fun `reset rolls pending back to stored`() {
        state.syncEditorScheme = true
        setStored(true)
        setPending(false)

        panel.reset()

        assertEquals(true, getPending(), "reset must restore pending to stored")
    }

    @Test
    fun `isModified returns true when pending diverges from stored on syncEditorScheme alone`() {
        state.syncEditorScheme = true
        setStored(true)
        setPending(false)

        assertTrue(
            panel.isModified(),
            "syncEditorScheme divergence alone must mark the panel modified — guards Pattern G symmetry",
        )
    }

    @Test
    fun `isModified returns false when pending matches stored`() {
        state.syncEditorScheme = true
        setStored(true)
        setPending(true)

        assertFalse(
            panel.isModified(),
            "Pristine pending=stored state must NOT report modified",
        )
    }

    @Test
    fun `apply is a no-op when pending matches stored`() {
        state.syncEditorScheme = true
        setStored(true)
        setPending(true)

        panel.apply()

        assertEquals(
            true,
            state.syncEditorScheme,
            "apply must short-circuit when isModified is false",
        )
    }

    private fun setPending(value: Boolean) {
        val field = AyuIslandsAppearancePanel::class.java.getDeclaredField("pendingSyncEditorScheme")
        field.isAccessible = true
        field.set(panel, value)
    }

    private fun setStored(value: Boolean) {
        val field = AyuIslandsAppearancePanel::class.java.getDeclaredField("storedSyncEditorScheme")
        field.isAccessible = true
        field.set(panel, value)
    }

    private fun getPending(): Boolean {
        val field = AyuIslandsAppearancePanel::class.java.getDeclaredField("pendingSyncEditorScheme")
        field.isAccessible = true
        return field.getBoolean(panel)
    }

    private fun getStored(): Boolean {
        val field = AyuIslandsAppearancePanel::class.java.getDeclaredField("storedSyncEditorScheme")
        field.isAccessible = true
        return field.getBoolean(panel)
    }
}
