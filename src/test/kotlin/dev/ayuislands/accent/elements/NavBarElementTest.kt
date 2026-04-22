package dev.ayuislands.accent.elements

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * User-space + algorithmic coverage for [NavBarElement] per CHROME-04.
 *
 * NavBar has no foreground contrast contract — its breadcrumbs inherit from the
 * editor scheme — so these tests additionally lock the "no contrastForeground"
 * invariant so a future dev can't silently copy the StatusBar block here.
 */
class NavBarElementTest {
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()

    private val accent = Color(255, 0, 0)
    private val blended = Color(0x12, 0x34, 0x56)

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state

        mockkStatic(UIManager::class)
        every { UIManager.put(any<String>(), any()) } returns null

        mockkObject(ChromeTintBlender)
        every { ChromeTintBlender.blend(any(), any(), any()) } returns blended
        every { ChromeTintBlender.contrastForeground(any()) } returns Color.WHITE
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `apply writes blended color to NavBar background and border`() {
        state.chromeTintIntensity = 30

        NavBarElement().apply(accent)

        verify { UIManager.put("NavBar.background", blended) }
        verify { UIManager.put("NavBar.borderColor", blended) }
    }

    @Test
    fun `revert nulls both NavBar keys`() {
        NavBarElement().revert()

        verify { UIManager.put("NavBar.background", null) }
        verify { UIManager.put("NavBar.borderColor", null) }
    }

    @Test
    fun `apply passes intensity from state to blender per D-03`() {
        state.chromeTintIntensity = 62

        val intensitySlot = slot<Int>()
        every {
            ChromeTintBlender.blend(any<Color>(), any<String>(), capture(intensitySlot))
        } returns blended

        NavBarElement().apply(accent)

        assertTrue(intensitySlot.isCaptured, "intensity must flow from state into blender")
        assertEquals(62, intensitySlot.captured)
    }

    @Test
    fun `revert symmetry — every key apply writes is nulled on revert`() {
        state.chromeTintIntensity = 40

        val appliedKeys = mutableListOf<String>()
        every {
            UIManager.put(capture(appliedKeys), any<Any>())
        } returns null

        val element = NavBarElement()
        element.apply(accent)
        val writtenDuringApply = appliedKeys.toSet()
        appliedKeys.clear()

        element.revert()
        val writtenDuringRevert = appliedKeys.toSet()

        assertTrue(writtenDuringApply.isNotEmpty(), "apply should have written at least one key")
        assertEquals(
            writtenDuringApply,
            writtenDuringRevert,
            "revert must null exactly the keys apply wrote",
        )
    }

    @Test
    fun `id and displayName match the CHROME registry entry`() {
        val element = NavBarElement()

        assertEquals(AccentElementId.NAV_BAR, element.id)
        assertEquals("Navigation bar", element.displayName)
    }

    @Test
    fun `apply never writes a foreground contrast color — NavBar owns no foreground keys`() {
        state.chromeTintIntensity = 40
        state.chromeTintKeepForegroundReadable = true

        NavBarElement().apply(accent)

        verify(exactly = 0) { ChromeTintBlender.contrastForeground(any()) }
    }
}
