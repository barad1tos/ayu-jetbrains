package dev.ayuislands.accent.statusbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.ComponentPopupBuilder
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.AccentChangedTopic
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentResolutionChain
import dev.ayuislands.accent.AccentResolutionStep
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ProjectLanguageDetectionListener
import dev.ayuislands.accent.StepOutcome
import dev.ayuislands.licensing.LicenseChecker
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JScrollPane
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccentStatusBarSurfaceTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `popup shows winner header and each resolution reason`() {
        val chain = fullDiagnosticsChain()
        val contentSlot = slot<JComponent>()
        val popup = stubPopupBuilder(contentSlot)
        val anchor = JLabel("status bar anchor")

        AccentStatusBarPopup.show(anchor, chain)

        verify(exactly = 1) { popup.showUnderneathOf(anchor) }
        val texts = contentSlot.captured.visibleTexts()
        assertTrue(
            texts.containsText("Project fallback", "#FFB454"),
            "Winner header must expose the active source and hex value",
        )
        assertTrue(
            texts.containsText("Project override", "not set"),
            "Users must see why a configured project override did not win",
        )
        assertTrue(
            texts.containsText("Forced language override", "license required"),
            "Users must see when a premium language override is blocked",
        )
        assertTrue(
            texts.containsText("Language override", "no accent mapping"),
            "Users must see when the detected language has no configured accent",
        )
        assertTrue(
            texts.containsText("Language fallback override", "Kotlin 52%, Java 48%"),
            "Users must see polyglot fallback reasoning",
        )
        assertTrue(
            texts.containsText("Material Theme", "not applicable"),
            "External integration rows must stay visible when they are skipped",
        )
        assertTrue(
            texts.containsText("IDE accent", "scanner unavailable"),
            "Unavailable sources must stay distinguishable from unset sources",
        )
        assertTrue(
            texts.containsText("Global", "#D4BFFF"),
            "Non-winning rows with candidate colors must expose their hex value",
        )
        assertTrue(
            contentSlot.captured.descendants().any { it is JScrollPane },
            "Long diagnostics must stay scrollable instead of making the popup too tall",
        )
    }

    @Test
    fun `factory exposes status widget metadata and respects the license gate`() {
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        val project = mockk<Project>(relaxed = true)
        val factory = AccentStatusBarWidgetFactory()

        val widget = factory.createWidget(project)

        assertEquals(AccentStatusBarWidget.WIDGET_ID, factory.id)
        assertEquals("Ayu Accent Source", factory.displayName)
        assertEquals(true, factory.isEnabledByDefault)
        assertEquals(AccentStatusBarWidget.WIDGET_ID, widget.ID())
        assertIs<AccentStatusBarPanel>(widget.component)
        assertEquals(true, factory.isAvailable(project))

        every { LicenseChecker.isLicensedOrGrace() } returns false

        assertEquals(false, factory.isAvailable(project))
    }

    @Test
    fun `widget refresh shows the resolved source in the status bar`() {
        val project = mockProject(isDisposed = false)
        val chain = projectOverrideChain()
        mockkObject(AccentContext.Companion)
        every { AccentContext.detect() } returns AccentContext.Ayu(AyuVariant.MIRAGE)
        mockkObject(AccentResolver)
        every {
            AccentResolver.resolveChain(project, AccentContext.Ayu(AyuVariant.MIRAGE))
        } returns chain
        val widget = AccentStatusBarWidget(project)

        widget.refreshFromProject()

        val panel = widget.component as AccentStatusBarPanel
        assertEquals(
            "Project override · Pinned accent for this project",
            label(panel).text,
        )
    }

    @Test
    fun `widget install subscribes refresh listeners and dispose disconnects them`() {
        val connection = mockk<MessageBusConnection>(relaxed = true)
        val project = mockProject(isDisposed = true, connection = connection)
        val widget = AccentStatusBarWidget(project)

        widget.install(mockk<StatusBar>(relaxed = true))
        widget.dispose()

        verify(exactly = 1) {
            connection.subscribe(AccentChangedTopic.TOPIC, any())
        }
        verify(exactly = 1) {
            connection.subscribe(ProjectLanguageDetectionListener.TOPIC, any())
        }
        verify(exactly = 1) { connection.disconnect() }
    }

    private fun stubPopupBuilder(contentSlot: CapturingSlot<JComponent>): JBPopup {
        mockkStatic(JBPopupFactory::class)
        val factory = mockk<JBPopupFactory>(relaxed = true)
        val builder = mockk<ComponentPopupBuilder>(relaxed = true)
        val popup = mockk<JBPopup>(relaxed = true)
        every { JBPopupFactory.getInstance() } returns factory
        every {
            factory.createComponentPopupBuilder(capture(contentSlot), any())
        } returns builder
        every { builder.setTitle(any()) } returns builder
        every { builder.setResizable(any()) } returns builder
        every { builder.setMovable(any()) } returns builder
        every { builder.setRequestFocus(any()) } returns builder
        every { builder.createPopup() } returns popup
        return popup
    }

    private fun mockProject(
        isDisposed: Boolean,
        connection: MessageBusConnection = mockk(relaxed = true),
    ): Project {
        val messageBus = mockk<MessageBus>(relaxed = true)
        every { messageBus.connect(any<Disposable>()) } returns connection
        return mockk<Project>(relaxed = true) {
            every { this@mockk.isDisposed } returns isDisposed
            every { this@mockk.messageBus } returns messageBus
        }
    }

    private fun projectOverrideChain(): AccentResolutionChain {
        val winner =
            AccentResolutionStep(
                source = AccentResolver.Source.PROJECT_OVERRIDE,
                hex = "#5CCFE6",
                outcome = StepOutcome.WON,
                detail = "Pinned accent for this project",
            )
        return AccentResolutionChain(listOf(winner), winner, verdict = null)
    }

    private fun fullDiagnosticsChain(): AccentResolutionChain {
        val steps =
            listOf(
                AccentResolutionStep(
                    source = AccentResolver.Source.PROJECT_OVERRIDE,
                    hex = null,
                    outcome = StepOutcome.NOT_SET,
                    detail = "not set",
                ),
                AccentResolutionStep(
                    source = AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE,
                    hex = null,
                    outcome = StepOutcome.LICENSE_BLOCKED,
                    detail = "license required",
                ),
                AccentResolutionStep(
                    source = AccentResolver.Source.LANGUAGE_OVERRIDE,
                    hex = "#F07178",
                    outcome = StepOutcome.NO_MAPPING,
                    detail = "no accent mapping",
                ),
                AccentResolutionStep(
                    source = AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE,
                    hex = null,
                    outcome = StepOutcome.NOT_DOMINANT,
                    detail = "Kotlin 52%, Java 48%",
                ),
                AccentResolutionStep(
                    source = AccentResolver.Source.PROJECT_FALLBACK,
                    hex = "#FFB454",
                    outcome = StepOutcome.WON,
                    detail = "Project fallback for polyglot project",
                ),
                AccentResolutionStep(
                    source = AccentResolver.Source.MATERIAL_THEME,
                    hex = null,
                    outcome = StepOutcome.NOT_APPLICABLE,
                    detail = "not applicable",
                ),
                AccentResolutionStep(
                    source = AccentResolver.Source.IDE_ACCENT,
                    hex = null,
                    outcome = StepOutcome.UNAVAILABLE,
                    detail = "scanner unavailable",
                ),
                AccentResolutionStep(
                    source = AccentResolver.Source.GLOBAL,
                    hex = "#D4BFFF",
                    outcome = StepOutcome.NOT_SET,
                    detail = "global stored accent",
                ),
            )
        return AccentResolutionChain(steps = steps, winner = steps[4], verdict = null)
    }

    private fun label(panel: AccentStatusBarPanel) = panel.components.last() as JLabel

    private fun Component.visibleTexts(): List<String> =
        descendants()
            .filter { it.isVisible }
            .mapNotNull { component ->
                when (component) {
                    is AbstractButton -> component.text
                    is JLabel -> component.text
                    else -> null
                }?.takeIf { it.isNotBlank() }
            }.toList()

    private fun List<String>.containsText(
        first: String,
        second: String,
    ): Boolean =
        any { text ->
            text.contains(first) && text.contains(second)
        }

    private fun Component.descendants(): Sequence<Component> =
        sequence {
            yield(this@descendants)
            if (this@descendants is Container) {
                components.forEach { yieldAll(it.descendants()) }
            }
        }
}
