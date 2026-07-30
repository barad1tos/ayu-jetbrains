package dev.ayuislands.onboarding

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontInstallConsent
import dev.ayuislands.font.FontInstaller
import dev.ayuislands.font.FontPreset
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.AncestorEvent
import kotlin.test.AfterTest
import kotlin.test.Test

class PremiumOnboardingPanelTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `font click after license downgrade cannot request consent or install`() {
        var licensed = true
        val panel = createRenderedPanel(AyuIslandsState()) { licensed }

        licensed = false
        clickMapleMonoCard(panel)

        verify(exactly = 1) { LicenseChecker.requestLicense("Unlock font installation") }
        verify(exactly = 0) { FontInstallConsent.confirmInstall(any<FontCatalog.Entry>(), any(), any()) }
        verify(exactly = 0) {
            FontInstaller.install(any(), any<FontInstallConsent.InstallConsent>(), any(), any())
        }
    }

    @Test
    fun `installed font remains applicable without a license`() {
        val state = AyuIslandsState().apply { installedFonts.add("Maple Mono") }
        val panel = createRenderedPanel(state) { false }

        clickMapleMonoCard(panel)

        verify(exactly = 1) { FontInstaller.applyOnly(FontPreset.AMBIENT, any()) }
        verify(exactly = 0) { LicenseChecker.requestLicense(any()) }
        verify(exactly = 0) { FontInstallConsent.confirmInstall(any<FontCatalog.Entry>(), any(), any()) }
        verify(exactly = 0) {
            FontInstaller.install(any(), any<FontInstallConsent.InstallConsent>(), any(), any())
        }
    }

    private fun createRenderedPanel(
        state: AyuIslandsState,
        licensed: () -> Boolean,
    ): PremiumOnboardingPanel {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } answers { licensed() }
        every { LicenseChecker.requestLicense(any()) } just Runs
        mockkObject(FontInstallConsent)
        mockkObject(FontInstaller)
        every { FontInstaller.applyOnly(any(), any()) } just Runs
        val settings = mockk<AyuIslandsSettings>()
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        every { settings.state } returns state
        val project = mockk<Project>(relaxed = true)
        every { project.isDisposed } returns false
        lateinit var panel: PremiumOnboardingPanel
        SwingUtilities.invokeAndWait {
            panel = PremiumOnboardingPanel(project, mockk<VirtualFile>(relaxed = true))
            panel.ancestorListeners.forEach {
                it.ancestorAdded(AncestorEvent(panel, AncestorEvent.ANCESTOR_ADDED, panel, panel))
            }
        }
        SwingUtilities.invokeAndWait {}
        return panel
    }

    private fun clickMapleMonoCard(panel: PremiumOnboardingPanel) {
        SwingUtilities.invokeAndWait {
            val fontCard =
                panel
                    .descendants()
                    .filterIsInstance<JPanel>()
                    .first { it.toolTipText?.contains("Maple Mono") == true }
            val event = MouseEvent(fontCard, MouseEvent.MOUSE_CLICKED, 0L, 0, 1, 1, 1, false)
            fontCard.mouseListeners.forEach { it.mouseClicked(event) }
        }
    }

    private fun java.awt.Container.descendants(): Sequence<java.awt.Component> =
        components.asSequence().flatMap { component ->
            sequenceOf(component) +
                if (component is java.awt.Container) component.descendants() else emptySequence()
        }
}
