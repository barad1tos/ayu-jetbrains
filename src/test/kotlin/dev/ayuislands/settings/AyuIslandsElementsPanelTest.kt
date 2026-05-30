package dev.ayuislands.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.licensing.LicenseChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AyuIslandsElementsPanelTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings
    private var isIslandsUi: Boolean = false

    @BeforeTest
    fun setUp() {
        state =
            AyuIslandsState().apply {
                accentElementsGroupExpanded = true
                glowTabMode = GlowTabMode.OFF.name
                bracketScopeEnabled = true
            }
        settings = mockk(relaxed = true)
        every { settings.state } returns state
        every { settings.getAccentForVariant(AyuVariant.MIRAGE) } returns AyuVariant.MIRAGE.defaultAccent
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(ConflictRegistry)
        every { ConflictRegistry.detectConflicts() } returns emptyList()
        every { ConflictRegistry.getConflictFor(any()) } returns null

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.isIslandsUi() } answers { isIslandsUi }

        wireUiDslServices()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `unlicensed accent elements keep controls visible but locked`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val elementsPanel = AyuIslandsElementsPanel()

        val dialogPanel = buildDialogPanel(elementsPanel)
        val toggleCheckboxes = toggleCheckboxes(elementsPanel).values.toList()
        val syncCheckbox = field<JCheckBox>(elementsPanel, "syncCheckbox")
        val bracketCheckbox = field<JCheckBox>(elementsPanel, "bracketScopeCheckbox")
        val thicknessSegmented = modelField<SegmentedButton<Int>>(elementsPanel, "thicknessSegmented")
        val thicknessLabel = descendants(dialogPanel, JLabel::class.java).first { it.text == "Underline thickness" }
        val previewComponent =
            descendants(dialogPanel, JComponent::class.java)
                .first { it.javaClass.simpleName == "AccentPreviewComponent" }

        assertTrue(toggleCheckboxes.isNotEmpty(), "Locked Accent Elements preview must render toggles")
        assertTrue(
            toggleCheckboxes.all { it.isEffectivelyVisibleWithin(dialogPanel) && !it.isEnabled },
            "Locked Accent Elements toggles must stay visible but not mutable",
        )
        assertTrue(
            syncCheckbox.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Accent Elements preview must show tab underline sync controls",
        )
        assertFalse(syncCheckbox.isEnabled, "Locked tab underline sync must not be mutable")
        assertTrue(
            thicknessLabel.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Accent Elements preview must show underline thickness controls in classic UI",
        )
        thicknessSegmented.selectedItem = AyuIslandsState.DEFAULT_TAB_UNDERLINE_HEIGHT + 1
        assertTrue(
            bracketCheckbox.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Accent Elements preview must show bracket scope controls",
        )
        assertFalse(bracketCheckbox.isEnabled, "Locked bracket scope control must not be mutable")
        assertTrue(
            previewComponent.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Accent Elements preview must show the visual mock preview",
        )

        toggleCheckboxes.first().isSelected = !toggleCheckboxes.first().isSelected
        syncCheckbox.isSelected = !syncCheckbox.isSelected
        bracketCheckbox.isSelected = !bracketCheckbox.isSelected

        assertFalse(
            elementsPanel.isModified(),
            "Programmatic changes to locked Accent Elements controls must not dirty Settings",
        )
    }

    @Test
    fun `unlicensed accent elements keep islands tab style selector visible but locked`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        isIslandsUi = true
        val elementsPanel = AyuIslandsElementsPanel()

        val dialogPanel = buildDialogPanel(elementsPanel)
        val tabModeSegmented = modelField<SegmentedButton<GlowTabMode>>(elementsPanel, "tabModeSegmented")
        val tabModeLabel = descendants(dialogPanel, JLabel::class.java).first { it.text == "Tab accent style" }

        assertTrue(
            tabModeLabel.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Accent Elements preview must show tab accent style controls in Islands UI",
        )
        tabModeSegmented.selectedItem = GlowTabMode.FULL
        assertFalse(
            elementsPanel.isModified(),
            "Rendering locked Islands tab style controls must not dirty Settings",
        )
    }

    private fun buildDialogPanel(elementsPanel: AyuIslandsElementsPanel): DialogPanel =
        panel {
            elementsPanel.buildPanel(this, AyuVariant.MIRAGE)
        }

    private fun wireUiDslServices() {
        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        val actionManagerMock = mockk<ActionManagerEx>(relaxed = true)
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
                experimentalUiClass -> experimentalUiMock
                else -> mockkClass(serviceClass.kotlin, relaxed = true)
            }
        }
        every { appMock.getService(ActionManager::class.java) } returns actionManagerMock
        every { appMock.getService(ActionManagerEx::class.java) } returns actionManagerMock
        every { appMock.getServiceIfCreated(ActionManager::class.java) } returns actionManagerMock
    }

    @Suppress("UNCHECKED_CAST")
    private fun toggleCheckboxes(panel: AyuIslandsElementsPanel): Map<AccentElementId, JCheckBox> {
        val field = AyuIslandsElementsPanel::class.java.getDeclaredField("checkboxes")
        field.isAccessible = true
        return field.get(panel) as? Map<AccentElementId, JCheckBox>
            ?: error("Accent element checkboxes must be created")
    }

    private fun <T : Component> field(
        panel: AyuIslandsElementsPanel,
        fieldName: String,
    ): T {
        val field = AyuIslandsElementsPanel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(panel) as? T ?: error("$fieldName must be created")
    }

    private fun <T : Any> modelField(
        panel: AyuIslandsElementsPanel,
        fieldName: String,
    ): T {
        val field = AyuIslandsElementsPanel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(panel) as? T ?: error("$fieldName must be created")
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

    private fun Component.isEffectivelyVisibleWithin(root: Component): Boolean {
        var current: Component? = this
        while (current != null && current !== root) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return current === root && root.isVisible
    }
}
