package dev.ayuislands.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.licensing.LicenseChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Component
import java.awt.Container
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pattern U locks for [ProjectIconAccentSection]'s checkbox, pending/stored
 * state, license boundary, and persistence.
 */
class ProjectIconAccentSectionTest {
    private lateinit var state: AyuIslandsState

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `licensed checkbox click persists through apply and converges stored`() {
        val section = ProjectIconAccentSection()
        section.load()
        mockUiDslApplication()
        val form = panel { section.buildRow(this) }
        val checkbox = assertNotNull(findCheckBox(form))

        checkbox.doClick()

        assertTrue(section.isModified(), "pending change must dirty the section")
        section.apply()

        assertTrue(state.projectIconAccentEnabled)
        assertFalse(section.isModified(), "apply must converge stored onto pending")
    }

    @Test
    fun `unlicensed enable never persists the premium toggle`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val section = ProjectIconAccentSection()
        section.load()
        section.setPendingForTest(true)

        section.apply()

        assertFalse(state.projectIconAccentEnabled, "premium toggle must not persist for free users")
    }

    @Test
    fun `unlicensed disable still persists so users can always turn the feature off`() {
        state.projectIconAccentEnabled = true
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val section = ProjectIconAccentSection()
        section.load()
        section.setPendingForTest(false)

        section.apply()

        assertFalse(state.projectIconAccentEnabled)
    }

    @Test
    fun `reset drops the pending change`() {
        val section = ProjectIconAccentSection()
        section.load()
        mockUiDslApplication()
        val form = panel { section.buildRow(this) }
        val checkbox = assertNotNull(findCheckBox(form))

        checkbox.doClick()

        section.reset()

        assertFalse(checkbox.isSelected, "reset must restore the visible checkbox")
        assertFalse(section.isModified())
        section.apply()
        assertFalse(state.projectIconAccentEnabled, "reset pending must not leak into apply")
    }

    private fun mockUiDslApplication() {
        mockkStatic(ApplicationManager::class)
        val application = mockk<Application>(relaxed = true)
        val actionManager = mockk<ActionManager>(relaxed = true)
        every { ApplicationManager.getApplication() } returns application
        every { application.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { application.getService(ActionManager::class.java) } returns actionManager
        every { actionManager.getAction(any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val experimentalUiClass = Class.forName("com.intellij.ui.ExperimentalUI") as Class<Any>
        val experimentalUi = mockkClass(experimentalUiClass.kotlin, relaxed = true)
        every { application.getService(experimentalUiClass) } returns experimentalUi
    }

    private fun findCheckBox(root: Container): JBCheckBox? {
        val queue = ArrayDeque<Component>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current is JBCheckBox) return current
            if (current is Container) queue.addAll(current.components)
        }
        return null
    }
}
