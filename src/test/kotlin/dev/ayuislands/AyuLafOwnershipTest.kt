package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AyuLafOwnershipTest {
    @Test
    fun `owned theme callback matches only its native manager and target`() {
        SwingUtilities.invokeAndWait {
            val manager = mockk<LafManager>(relaxed = true)
            val otherManager = mockk<LafManager>(relaxed = true)
            val target = mockk<UIThemeLookAndFeelInfo>()
            val foreign = mockk<UIThemeLookAndFeelInfo>()
            var selected = target
            every { manager.currentUIThemeLookAndFeel } answers { selected }
            every { otherManager.currentUIThemeLookAndFeel } returns target
            every { manager.updateUI() } answers {
                assertTrue(AyuLaf.ownsThemeChange(manager))
                assertFalse(AyuLaf.ownsThemeChange(otherManager))
                selected = foreign
                assertFalse(AyuLaf.ownsThemeChange(manager))
            }

            AyuLaf.switchOwnedTheme(target, manager)

            assertFalse(AyuLaf.ownsThemeChange(manager))
            verify(exactly = 1) { manager.setCurrentLookAndFeel(target, true) }
            verify(exactly = 1) { manager.updateUI() }
        }
    }

    @Test
    fun `failed owned refresh releases callback ownership for the next ordinary change`() {
        SwingUtilities.invokeAndWait {
            val manager = mockk<LafManager>(relaxed = true)
            val target = mockk<UIThemeLookAndFeelInfo>()
            every { manager.currentUIThemeLookAndFeel } returns target
            every { manager.updateUI() } answers {
                assertTrue(AyuLaf.ownsThemeChange(manager))
                error("Refresh failed after native selection")
            }

            assertFailsWith<IllegalStateException> { AyuLaf.switchOwnedTheme(target, manager) }

            assertFalse(AyuLaf.ownsThemeChange(manager))
            every { manager.updateUI() } answers { assertFalse(AyuLaf.ownsThemeChange(manager)) }
            AyuLaf.switchToTheme(target, shouldLockEditorScheme = true, lafManager = manager)
            verify(exactly = 2) { manager.updateUI() }
        }
    }
}
