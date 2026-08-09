package dev.ayuislands.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JComboBox
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Locks in the [AyuIslandsAccentPanel.applyWithFallback] failure-recovery contract:
 *  - happy path: applyForFocusedProject runs; no fallback triggered
 *  - corrupted override: applyForFocusedProject throws, fallback applies the global hex
 *    AND syncs the swap cache (the swap-cache-sync omission was the original bug — the
 *    fallback used to skip it, silently reintroducing the stale-cache → redundant-apply
 *    pattern applyForFocusedProject was created to prevent)
 *  - corrupted global: BOTH paths throw; the panel stays operational, second LOG.error
 *    fires with "also failed" context, no exception escapes — avoids the generic
 *    "Settings can't save" dialog a hand-edited global hex would otherwise trigger
 *
 * The immutable System and Chrome build slots are covered through a real UI
 * DSL panel and its visible separator order rather than private fields or
 * compiled bytecode.
 */
class AyuIslandsAccentPanelTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings
    private lateinit var swapService: ProjectAccentSwapService

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        settings = mockk(relaxed = true)
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } answers {
            firstArg<AyuVariant>().defaultAccent
        }
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns null
        swapService = mockk(relaxed = true)
        mockkObject(ProjectAccentSwapService.Companion)
        every { ProjectAccentSwapService.getInstance() } returns swapService
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `applyWithFallback happy path delegates to applyForFocusedProject and skips fallback`() {
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } returns "#ABCDEF"

        val panel = AyuIslandsAccentPanel()
        panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")

        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) }
        // Fallback path's apply(effectiveAccent) and notifyExternalApply must NOT fire.
        verify(exactly = 0) { AccentApplicator.apply(any()) }
        verify(exactly = 0) { swapService.notifyExternalApply(any()) }
    }

    @Test
    fun `applyWithFallback corrupted override falls back to global AND syncs swap cache`() {
        // Regression guard: a previous fallback applied the global accent but forgot to
        // call ProjectAccentSwapService.notifyExternalApply, leaving the swap cache stale
        // and silently re-introducing the exact bug applyForFocusedProject was created
        // to prevent (next WINDOW_ACTIVATED would redundantly re-apply).
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } throws
            IllegalStateException("override hex corrupted")
        every { AccentApplicator.applyFromHexString("#FFCC66") } returns true

        val panel = AyuIslandsAccentPanel()
        LoggedErrorProcessor.executeWith<Throwable>(suppressLoggedErrors()) {
            panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")
        }

        verify(exactly = 1) { AccentApplicator.applyFromHexString("#FFCC66") }
        verify(exactly = 1) { swapService.notifyExternalApply("#FFCC66") }
    }

    @Test
    fun `applyWithFallback corrupted global ALSO does not propagate exception`() {
        // Regression guard: the fallback's own apply(effectiveAccent) can throw when
        // the GLOBAL hex is corrupted (hand-edited XML, legacy writer). Without the
        // second try/catch, the Settings "OK" path would bubble up as a generic
        // "Can't save" dialog. The catch logs and leaves the visible accent unchanged.
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } throws
            IllegalStateException("override hex corrupted")
        every { AccentApplicator.applyFromHexString("#FFCC66") } throws
            IllegalStateException("global hex also corrupted")

        val panel = AyuIslandsAccentPanel()
        // No exception escapes — both throws are caught and logged.
        LoggedErrorProcessor.executeWith<Throwable>(suppressLoggedErrors()) {
            panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")
        }
        // notifyExternalApply must NOT be reached when the global-fallback apply throws.
        verify(exactly = 0) { swapService.notifyExternalApply(any()) }
    }

    @Test
    fun `applyWithFallback logs WARN when swap cache sync throws after successful global apply`() {
        // Regression guard for the notifyExternalApply-after-successful-fallback-apply
        // stage: applyForFocusedProject throws, the global-fallback apply(effectiveAccent)
        // succeeds, but notifyExternalApply throws (swap service mid-dispose, corrupted
        // cache). The visible accent has already changed; only the focus-swap cache is
        // stale. The panel must log at WARN (not ERROR, since apply actually worked) and
        // must NOT rethrow — otherwise the Settings OK path degrades to a generic "Can't
        // save" dialog on a path where the user's intent was actually applied.
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } throws
            IllegalStateException("override hex corrupted")
        every { AccentApplicator.applyFromHexString("#FFCC66") } returns true
        every { swapService.notifyExternalApply("#FFCC66") } throws
            IllegalStateException("swap service disposed mid-save")

        val expectedWarnSubstring = "swap-cache sync failed"
        val capturedWarns = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    throwable: Throwable?,
                ): Set<Action> = java.util.EnumSet.noneOf(Action::class.java)

                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (!message.contains(expectedWarnSubstring)) return true
                    capturedWarns += message
                    return false
                }
            }

        val panel = AyuIslandsAccentPanel()
        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")
        }

        verify(exactly = 1) { AccentApplicator.applyFromHexString("#FFCC66") }
        verify(exactly = 1) { swapService.notifyExternalApply("#FFCC66") }
        kotlin.test.assertEquals(
            1,
            capturedWarns.size,
            "notifyExternalApply throw must produce exactly one WARN (not ERROR); got: $capturedWarns",
        )
    }

    private fun suppressLoggedErrors(): LoggedErrorProcessor =
        object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<out String>,
                throwable: Throwable?,
            ): Set<Action> = java.util.EnumSet.noneOf(Action::class.java)
        }

    @Test
    fun `active source description includes language detail when available`() {
        kotlin.test.assertEquals(
            "Language override (Kotlin, 82%)",
            describeAccentSource(AccentResolver.Source.LANGUAGE_OVERRIDE, null, "Kotlin, 82%"),
        )
        kotlin.test.assertEquals(
            "Language override (Kotlin, manual)",
            describeAccentSource(AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE, null, "Kotlin, manual"),
        )
    }

    @Test
    fun `buildPanel renders immutable System and Chrome sections around Overrides`() {
        wireUiDslServices()
        val accentPanel = AyuIslandsAccentPanel()
        val dialogPanel =
            panel {
                accentPanel.buildPanel(
                    panel = this,
                    variant = AyuVariant.MIRAGE,
                    buildSystemSection = { group("System Marker") {} },
                    buildChromeSection = { group("Chrome Marker") {} },
                )
            }

        val titles =
            descendants(dialogPanel, TitledSeparator::class.java)
                .mapNotNull { it.text }
        val expectedOrder = listOf("Accent Color", "System Marker", "Overrides", "Chrome Marker", "Accent Rotation")

        kotlin.test.assertEquals(
            expectedOrder,
            titles.filter(expectedOrder::contains),
        )
    }

    @Test
    fun `reset accent default preserves a pending rotation change`() {
        val storedAccent = "#F28779"
        state.mirageAccent = storedAccent
        state.accentRotationEnabled = true
        every { settings.getAccentForVariant(AyuVariant.MIRAGE) } returns storedAccent
        every { AccentApplicator.revertAll() } just Runs
        val rotationService = mockk<AccentRotationService>(relaxed = true)
        mockkObject(AccentRotationService.Companion)
        every { AccentRotationService.getInstance() } returns rotationService
        val accentPanel = AyuIslandsAccentPanel()
        val dialogPanel = buildDialogPanel(accentPanel)
        val rotationCheckbox =
            descendants(dialogPanel, JCheckBox::class.java)
                .single { it.text == "Enable accent rotation" }

        rotationCheckbox.doClick()
        accentPanel.resetToDefault()
        accentPanel.apply()

        kotlin.test.assertFalse(state.accentRotationEnabled)
        verify(exactly = 1) { rotationService.stopRotation() }
    }

    @Test
    fun `reset default overrides system accent`() {
        state.followSystemAccent = true
        every { AccentApplicator.revertAll() } just Runs
        val accentPanel = AyuIslandsAccentPanel()
        wireUiDslServices()
        val dialogPanel =
            panel {
                accentPanel.buildPanel(
                    panel = this,
                    variant = AyuVariant.MIRAGE,
                    buildSystemSection = {
                        accentPanel.installSystemAccentCheckbox(this, isSupportedPlatform = true)
                    },
                )
            }
        val colorPanel = descendants(dialogPanel, AccentColorPanel::class.java).single()
        val followSystemCheckbox =
            descendants(dialogPanel, JCheckBox::class.java)
                .single { it.text == "Follow system accent color" }
        kotlin.test.assertTrue(colorPanel.componentCount > 0)
        kotlin.test.assertTrue(colorPanel.components.all { !it.isEnabled })
        kotlin.test.assertTrue(followSystemCheckbox.isSelected)

        accentPanel.resetToDefault()

        kotlin.test.assertTrue(accentPanel.isModified())
        kotlin.test.assertTrue(colorPanel.components.all { it.isEnabled })
        kotlin.test.assertFalse(followSystemCheckbox.isSelected)
        accentPanel.apply()
        kotlin.test.assertFalse(state.followSystemAccent)
        verify(exactly = 1) { AccentApplicator.revertAll() }
    }

    @Test
    fun `unlicensed accent rotation keeps mode and interval controls visible`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        state.accentRotationEnabled = false
        state.accentRotationMode = AccentRotationMode.PRESET.name
        state.accentRotationIntervalHours = AyuIslandsState.DEFAULT_ROTATION_INTERVAL_HOURS
        val accentPanel = AyuIslandsAccentPanel()

        val dialogPanel = buildDialogPanel(accentPanel)
        val comboBoxes = descendants(dialogPanel, JComboBox::class.java)
        val modeCombo = comboBoxes.first { it.containsItem("Preset cycle") }
        val intervalCombo = comboBoxes.first { it.containsItem("1 hour") }

        kotlin.test.assertTrue(
            modeCombo.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Accent Rotation preview must show the mode selector even when the toggle is off",
        )
        kotlin.test.assertFalse(
            modeCombo.isEnabled,
            "Locked Accent Rotation mode selector must be visible but not mutable",
        )
        kotlin.test.assertTrue(
            intervalCombo.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Accent Rotation preview must show the interval selector even when the toggle is off",
        )
        kotlin.test.assertFalse(
            intervalCombo.isEnabled,
            "Locked Accent Rotation interval selector must be visible but not mutable",
        )
        kotlin.test.assertFalse(
            accentPanel.isModified(),
            "Rendering locked Accent Rotation controls must not dirty Settings",
        )
    }

    @Test
    fun `initial accent preview follows selected preset instead of stale shuffle color`() {
        val selectedPreset = "#F28779"
        state.mirageAccent = selectedPreset
        state.lastShuffleColor = "#5CCFE6"
        every { settings.getAccentForVariant(AyuVariant.MIRAGE) } returns selectedPreset

        val accentPanel = AyuIslandsAccentPanel()
        val dialogPanel = buildDialogPanel(accentPanel)
        val colorPanel = descendants(dialogPanel, AccentColorPanel::class.java).single()

        kotlin.test.assertEquals(
            selectedPreset,
            colorPanel.selectedPreset,
            "Accent selector must initialize from the stored preset",
        )
        kotlin.test.assertEquals(
            selectedPreset,
            thirteenthSwatchColorHex(colorPanel),
            "Large accent preview must not reuse a stale lastShuffleColor from an older pending shuffle",
        )
    }

    private fun buildDialogPanel(accentPanel: AyuIslandsAccentPanel): DialogPanel {
        wireUiDslServices()
        return panel {
            accentPanel.buildPanel(this, AyuVariant.MIRAGE)
        }
    }

    private fun wireUiDslServices() {
        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        val actionManagerMock = mockk<ActionManagerEx>(relaxed = true)
        val mappingsSettings = AccentMappingsSettings()
        mockkStatic(ActionManager::class)
        every { ActionManager.getInstance() } returns actionManagerMock
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { actionManagerMock.getAction(any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val experimentalUiClass = Class.forName("com.intellij.ui.ExperimentalUI") as Class<Any>
        val experimentalUiMock = mockkClass(experimentalUiClass.kotlin, relaxed = true)
        every { appMock.getService(any<Class<*>>()) } answers {
            when (val serviceClass = firstArg<Class<*>>()) {
                ActionManager::class.java,
                ActionManagerEx::class.java,
                -> actionManagerMock
                AccentMappingsSettings::class.java -> mappingsSettings
                experimentalUiClass -> experimentalUiMock
                else -> mockkClass(serviceClass.kotlin, relaxed = true)
            }
        }
        every { appMock.getService(ActionManager::class.java) } returns actionManagerMock
        every { appMock.getService(ActionManagerEx::class.java) } returns actionManagerMock
        every { appMock.getServiceIfCreated(ActionManager::class.java) } returns actionManagerMock
    }

    private fun <T : Component> descendants(
        container: Container,
        type: Class<T>,
    ): List<T> =
        buildList {
            fun visit(component: Component) {
                if (type.isInstance(component)) add(type.cast(component))
                if (component is Container) {
                    component.components.forEach(::visit)
                }
            }
            visit(container)
        }

    private fun thirteenthSwatchColorHex(panel: AccentColorPanel): String? {
        val swatch =
            descendants(panel, Component::class.java)
                .firstOrNull { it.javaClass.simpleName == "ThirteenthSwatch" }
                ?: error("AccentColorPanel must contain a ThirteenthSwatch preview component")
        val field = swatch.javaClass.getDeclaredField("colorHex")
        field.isAccessible = true
        return field.get(swatch) as String?
    }

    private fun JComboBox<*>.containsItem(item: String): Boolean = (0 until itemCount).any { getItemAt(it) == item }

    private fun Component.isEffectivelyVisibleWithin(root: Component): Boolean {
        var current: Component? = this
        while (current != null && current !== root) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return current === root && root.isVisible
    }
}
