package dev.ayuislands.accent.toolbar

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.ComponentPopupBuilder
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import javax.swing.JLabel
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Plan 48-02 Task 3 — locks the popup smoke contract:
 *   - early-return when `AyuVariant.detect() == null` (WIDGET-11 belt-and-braces),
 *   - the exact six-flag builder combination from RESEARCH §3 (Pitfall 4 lock).
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
    fun `show is a no-op when AyuVariant detect returns null (WIDGET-11)`() {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns null
        mockkStatic(JBPopupFactory::class)
        val factory = mockk<JBPopupFactory>(relaxed = true)
        every { JBPopupFactory.getInstance() } returns factory

        QuickSwitcherPopup.show(JLabel())

        verify(exactly = 0) { factory.createComponentPopupBuilder(any(), any()) }
    }

    @Test
    fun `show builds the popup with the exact RESEARCH §3 flag combo (Pitfall 4 lock)`() {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        // VariantSwitcherRow reads LafManager during construction — stub via relaxed mock.
        mockkStatic(LafManager::class)
        val lafManager = mockk<LafManager>(relaxed = true)
        every { LafManager.getInstance() } returns lafManager
        every { lafManager.currentUIThemeLookAndFeel } returns null
        // Wave 4 premium block reads `AyuIslandsSettings.state` (related toggles
        // bindSelected backing) and `LicenseChecker.isLicensedOrGrace()` (premium
        // gate). Stub both so the popup body builds in a headless harness.
        val settings = mockk<AyuIslandsSettings>(relaxed = true)
        every { settings.state } returns AyuIslandsState()
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns false
        // Wave 4 quick-actions row instantiates the five DumbAwareAction subclasses
        // which reach for ApplicationManager.getApplication() during construction.
        mockkStatic(ApplicationManager::class)
        val mockApp = mockk<Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.getService(ActionManager::class.java) } returns mockk<ActionManager>(relaxed = true)

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

        QuickSwitcherPopup.show(JLabel())

        verify(exactly = 1) { builder.setRequestFocus(true) }
        verify(exactly = 1) { builder.setCancelOnClickOutside(true) }
        verify(exactly = 1) { builder.setCancelOnWindowDeactivation(false) }
        verify(exactly = 1) { builder.setMovable(false) }
        verify(exactly = 1) { builder.setResizable(false) }
        verify(exactly = 1) { builder.setCancelKeyEnabled(true) }
        verify(exactly = 1) { popup.showUnderneathOf(any()) }
    }
}
