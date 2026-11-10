package dev.ayuislands.integration

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.components.ActionLink
import dev.ayuislands.AyuPlugin
import dev.ayuislands.onboarding.OnboardingUrls
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.accessibility.AccessibleRole
import javax.swing.JLabel
import javax.swing.JTabbedPane
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StudioSettingsBrandingTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @Before
    fun setUp() {
        mockkObject(AyuPlugin)
        val descriptor = mockk<PluginDescriptor> { every { version } returns "3.0.0-test" }
        every { AyuPlugin.findLoadedPlugin(AyuPlugin.ID) } returns descriptor
        mockkStatic(BrowserUtil::class)
        every { BrowserUtil.browse(any<String>()) } answers { }
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `native Settings keeps the Ayu logo and loaded version beside the seven familiar sections`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val component = fixture.host.createComponent()
                fixture.host.reset()
                val labels = hostDescendants(component).filterIsInstance<JLabel>()
                val brand = labels.single { it.text == "Ayu Islands" }
                assertNotNull(brand.icon)
                assertEquals("Ayu Islands", brand.accessibleContext.accessibleName)
                assertTrue(labels.any { it.text == "v3.0.0-test" })
                val tabs = hostDescendants(component).filterIsInstance<JTabbedPane>().single()
                assertEquals(
                    listOf("Accent", "Font", "Glow", "Syntax", "VCS", "Workspace", "Plugins"),
                    (0 until tabs.tabCount).map(tabs::getTitleAt),
                )
                assertTrue(fixture.apply.effects.all { it.writes == 0 })
            }
        }

    @Test
    fun `community links are native keyboard actions and keep appearance and selected section unchanged`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val component = fixture.host.createComponent()
                fixture.host.reset()
                val accepted = fixture.document
                val before = fixture.binding.controller.state.record
                val tabs = hostDescendants(component).filterIsInstance<JTabbedPane>().single()
                tabs.selectedIndex = 4
                val links = hostDescendants(component).filterIsInstance<ActionLink>()
                val share = links.single { it.text == "Share Your Setup" }
                val feature = links.single { it.text == "Request a Feature" }
                for (link in listOf(share, feature)) {
                    assertTrue(link.isFocusable)
                    assertEquals(AccessibleRole.HYPERLINK, link.accessibleContext.accessibleRole)
                    link.doClick()
                }
                verify(exactly = 1) { BrowserUtil.browse(OnboardingUrls.DISCUSSIONS_SHOW_SETUP) }
                verify(exactly = 1) { BrowserUtil.browse(OnboardingUrls.DISCUSSIONS_FEATURE_REQUESTS) }
                assertEquals(4, tabs.selectedIndex)
                assertEquals(before, fixture.binding.controller.state.record)
                assertEquals(accepted, fixture.document)
                assertTrue(fixture.apply.effects.all { it.writes == 0 })
            }
        }
}
