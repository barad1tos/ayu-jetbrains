package dev.ayuislands.onboarding

import com.intellij.openapi.vfs.VirtualFile
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.QuickSwitcherFixture
import dev.ayuislands.font.FontPreset
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.model.FontJobStatus
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.session.StudioQuickCommand
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.coroutines.Job
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.AncestorEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PremiumOnboardingPanelTest {
    @Test fun `font click after license downgrade cannot start resource installation`() {
        withPanel { fixture, panel ->
            fixture.licensed = false
            every { LicenseChecker.requestLicense("Unlock font installation") } returns Unit
            clickMapleMonoCard(panel)
            verify(exactly = 1) { LicenseChecker.requestLicense("Unlock font installation") }
            verify(exactly = 0) { fixture.service.installFont(any(), any(), any()) }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun `installed font submits one accepted-target choice without downloading or changing preferences`() {
        QuickSwitcherFixture().use { fixture ->
            fixture.state.installedFonts.add("Maple Mono")
            fixture.licensed = false
            val panel = createRenderedPanel(fixture)
            clickMapleMonoCard(panel)
            assertEquals(fixture.target, fixture.requests.single().first)
            assertEquals(StudioQuickCommand.Onboarding(FontPreset.AMBIENT, null), fixture.requests.single().second)
            verify(exactly = 0) { LicenseChecker.requestLicense(any()) }
            verify(exactly = 0) { fixture.service.installFont(any(), any(), any()) }
            assertTrue(!fixture.state.fontPresetEnabled)
        }
    }

    @Test fun `completed download retains the original target and accepted baseline for admission`() {
        withPanel { fixture, panel ->
            val baseline = requireNotNull(fixture.service.document(fixture.target))
            var completion: ((FontJobStatus) -> Unit)? = null
            every { fixture.service.installFont(fixture.project, FontPreset.AMBIENT, any()) } answers {
                completion = thirdArg()
                Job().apply { complete() }
            }
            var submitted: Triple<TargetObservation, StudioQuickCommand, StudioDocument>? = null
            every { fixture.service.submitQuick(any(), any(), any<StudioDocument>(), any()) } answers {
                submitted = Triple(firstArg(), secondArg(), thirdArg())
                Job().apply { complete() }
            }
            clickMapleMonoCard(panel)
            assertEquals(null, submitted)
            fixture.available = false
            SwingUtilities.invokeAndWait { requireNotNull(completion)(FontJobStatus.INSTALLED) }
            val request = requireNotNull(submitted)
            assertEquals(fixture.target, request.first)
            assertEquals(StudioQuickCommand.Onboarding(FontPreset.AMBIENT, null), request.second)
            assertSame(baseline, request.third)
        }
    }

    @Test fun `closing the onboarding view cancels delayed appearance intent but not the resource job`() {
        withPanel { fixture, panel ->
            var completion: ((FontJobStatus) -> Unit)? = null
            every { fixture.service.installFont(any(), any(), any()) } answers {
                completion = thirdArg()
                Job().apply { complete() }
            }
            clickMapleMonoCard(panel)
            SwingUtilities.invokeAndWait {
                panel.ancestorListeners.forEach {
                    it.ancestorRemoved(AncestorEvent(panel, AncestorEvent.ANCESTOR_REMOVED, panel, panel))
                }
                requireNotNull(completion)(FontJobStatus.INSTALLED)
            }
            verify(exactly = 0) { fixture.service.submitQuick(any(), any(), any<StudioDocument>(), any()) }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun `restart-required installation never submits an unavailable appearance`() {
        withPanel { fixture, panel ->
            var completion: ((FontJobStatus) -> Unit)? = null
            every { fixture.service.installFont(any(), any(), any()) } answers {
                completion = thirdArg()
                Job().apply { complete() }
            }
            clickMapleMonoCard(panel)
            SwingUtilities.invokeAndWait { requireNotNull(completion)(FontJobStatus.RESTART_REQUIRED) }
            verify(exactly = 0) { fixture.service.submitQuick(any(), any(), any<StudioDocument>(), any()) }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    private fun withPanel(test: (QuickSwitcherFixture, PremiumOnboardingPanel) -> Unit) {
        QuickSwitcherFixture().use { fixture -> test(fixture, createRenderedPanel(fixture)) }
    }

    private fun createRenderedPanel(fixture: QuickSwitcherFixture): PremiumOnboardingPanel {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val baseline = StudioFixtures.legacyDocument(fixture.state)
        every { fixture.service.document(fixture.target) } returns baseline
        lateinit var panel: PremiumOnboardingPanel
        SwingUtilities.invokeAndWait {
            panel = PremiumOnboardingPanel(fixture.project, mockk<VirtualFile>(relaxed = true))
            panel.ancestorListeners.forEach {
                it.ancestorAdded(AncestorEvent(panel, AncestorEvent.ANCESTOR_ADDED, panel, panel))
            }
        }
        SwingUtilities.invokeAndWait {}
        return panel
    }

    private fun clickMapleMonoCard(panel: PremiumOnboardingPanel) {
        SwingUtilities.invokeAndWait {
            val card =
                panel.descendants().filterIsInstance<JPanel>().first {
                    it.toolTipText?.contains("Maple Mono") == true
                }
            val event = MouseEvent(card, MouseEvent.MOUSE_CLICKED, 0L, 0, 1, 1, 1, false)
            card.mouseListeners.forEach { it.mouseClicked(event) }
        }
    }

    private fun java.awt.Container.descendants(): Sequence<java.awt.Component> =
        components.asSequence().flatMap { component ->
            sequenceOf(component) + if (component is java.awt.Container) component.descendants() else emptySequence()
        }
}
