package dev.ayuislands.accent.toolbar

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.ComponentPopupBuilder
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the popup smoke contract:
 *   - early-return when `AyuVariant.detect() == null` (non-Ayu belt-and-braces),
 *   - the exact six-flag builder combination required by the JBPopup contract.
 *
 * Does NOT exercise variant/accent grid wiring — those have their own tests; this
 * file only verifies the popup envelope.
 */
class QuickSwitcherPopupTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `show is a no-op when AyuVariant detect returns null`() {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns null
        mockkStatic(JBPopupFactory::class)
        val factory = mockk<JBPopupFactory>(relaxed = true)
        every { JBPopupFactory.getInstance() } returns factory

        QuickSwitcherPopup.show(JLabel())

        verify(exactly = 0) { factory.createComponentPopupBuilder(any(), any()) }
    }

    @Test
    fun `show builds the popup with the exact six-flag JBPopup builder combo`() {
        stubPopupBodyDependencies()
        val (builder, popup) = stubPopupBuilder()

        QuickSwitcherPopup.show(JLabel())

        verify(exactly = 1) { builder.setRequestFocus(true) }
        verify(exactly = 1) { builder.setCancelOnClickOutside(true) }
        verify(exactly = 1) { builder.setCancelOnWindowDeactivation(false) }
        verify(exactly = 1) { builder.setMovable(false) }
        verify(exactly = 1) { builder.setResizable(false) }
        verify(exactly = 1) { builder.setCancelKeyEnabled(true) }
        verify(exactly = 1) { popup.showUnderneathOf(any()) }
    }

    @Test
    fun `show toggles chip popup-attached ring while popup is open`() {
        stubPopupBodyDependencies()
        val (_, popup) = stubPopupBuilder()
        val listenerSlot = slot<JBPopupListener>()
        every { popup.addListener(capture(listenerSlot)) } just Runs

        val chip = QuickSwitcherChipComponent()
        QuickSwitcherPopup.show(JLabel(), chip)

        verify(exactly = 1) { popup.addListener(any()) }
        assertFalse(chip.isPopupAttached, "Chip must stay detached until popup listener fires")

        listenerSlot.captured.beforeShown(mockk(relaxed = true))
        SwingUtilities.invokeAndWait { }
        assertTrue(chip.isPopupAttached, "Chip must render attached ring while popup is open")

        listenerSlot.captured.onClosed(mockk(relaxed = true))
        SwingUtilities.invokeAndWait { }
        assertFalse(chip.isPopupAttached, "Chip must clear attached ring after popup closes")
    }

    private fun stubPopupBodyDependencies() {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        // The grid resolves the current accent at construction — stub the chain.
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns null
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any()) } returns "#FFB454"
        // VariantSwitcherRow reads LafManager during construction — stub via relaxed mock.
        mockkStatic(LafManager::class)
        val lafManager = mockk<LafManager>(relaxed = true)
        every { LafManager.getInstance() } returns lafManager
        every { lafManager.currentUIThemeLookAndFeel } returns null
        // The premium block reads settings and license state while binding rows.
        val settings = mockk<AyuIslandsSettings>(relaxed = true)
        every { settings.state } returns AyuIslandsState()
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns false
        // The quick-actions row instantiates actions that query ApplicationManager.
        mockkStatic(ApplicationManager::class)
        val mockApp = mockk<Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.getService(ActionManager::class.java) } returns mockk<ActionManager>(relaxed = true)
    }

    private fun stubPopupBuilder(): Pair<ComponentPopupBuilder, JBPopup> {
        mockkStatic(JBPopupFactory::class)
        val factory = mockk<JBPopupFactory>(relaxed = true)
        val builder = mockk<ComponentPopupBuilder>(relaxed = true)
        val popup = mockk<JBPopup>(relaxed = true)
        every { JBPopupFactory.getInstance() } returns factory
        every { factory.createComponentPopupBuilder(any(), any()) } returns builder
        every { builder.setRequestFocus(any()) } returns builder
        every { builder.setCancelOnClickOutside(any()) } returns builder
        every { builder.setCancelOnWindowDeactivation(any()) } returns builder
        every { builder.setMovable(any()) } returns builder
        every { builder.setResizable(any()) } returns builder
        every { builder.setCancelKeyEnabled(any()) } returns builder
        every { builder.createPopup() } returns popup
        return builder to popup
    }
}
