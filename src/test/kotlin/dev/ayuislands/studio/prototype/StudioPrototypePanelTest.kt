package dev.ayuislands.studio.prototype

import com.intellij.ui.JBColor
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.syntax.SyntaxPreset
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioPrototypePanelTest {
    @Test
    fun `profile validation retains partial and rejected outcomes`() =
        onEdt {
            val outcomes =
                listOf(
                    StudioPrototypeOutcome.FONT_UNAVAILABLE to "Partially available",
                    StudioPrototypeOutcome.REJECTED to "could not be applied",
                )
            for ((simulation, expectedMessage) in outcomes) {
                val panel = StudioPrototypePanel(StudioPrototypeSession())
                control<JList<*>>(panel, "Surface selection").selectedIndex = 3
                control<JSpinner>(panel, "Font size").value = 21.0
                control<JComboBox<*>>(panel, "Preview result").selectedItem = simulation
                panel.applyPreview()

                control<JButton>(panel, "Create profile").doClick()

                val message = control<JTextArea>(panel, "Preview status").text
                assertTrue(message.contains(expectedMessage), "The preview outcome must survive name validation")
                assertTrue(message.contains("Enter a profile name"))
                assertTrue(panel.canApply)
            }
        }

    @Test
    fun `reverted glow and membership edits preserve partial retry`() =
        onEdt {
            val panel = StudioPrototypePanel(StudioPrototypeSession())
            control<JList<*>>(panel, "Surface selection").selectedIndex = 3
            control<JSpinner>(panel, "Font size").value = 21.0
            control<JComboBox<*>>(panel, "Preview result").selectedItem = StudioPrototypeOutcome.FONT_UNAVAILABLE
            panel.applyPreview()

            control<JList<*>>(panel, "Surface selection").selectedIndex = 0
            control<JCheckBox>(panel, "Glow enabled").doClick()
            control<JCheckBox>(panel, "Glow enabled").doClick()
            control<JCheckBox>(panel, "Include Glow").doClick()
            control<JCheckBox>(panel, "Include Glow").doClick()

            assertFalse(panel.session.hasPendingChanges)
            assertTrue(panel.canApply)
            assertTrue(control<JTextArea>(panel, "Preview status").text.contains("Partially available"))
            control<JComboBox<*>>(panel, "Preview result").selectedItem = StudioPrototypeOutcome.SUCCESS
            panel.applyPreview()
            assertEquals(21f, panel.session.current.fontSize)
            assertFalse(panel.canApply)
        }

    @Test
    fun `pending differences use the accepted desired font after partial apply`() =
        onEdt {
            val panel = StudioPrototypePanel(StudioPrototypeSession())
            control<JList<*>>(panel, "Surface selection").selectedIndex = 3
            control<JSpinner>(panel, "Font size").value = 21.0
            control<JComboBox<*>>(panel, "Preview result").selectedItem = StudioPrototypeOutcome.FONT_UNAVAILABLE
            panel.applyPreview()

            control<JSpinner>(panel, "Font size").value = 14.0

            assertTrue(panel.session.hasPendingChanges)
            assertTrue(control<JTextArea>(panel, "Pending differences").text.contains("Font: 21.0 → 14.0"))
            control<JRadioButton>(panel, "Current").doClick()
            assertTrue(control<JTextArea>(panel, "Appearance details").text.contains("Font: 14.0"))
            assertEquals(14f, panel.session.current.fontSize)
            panel.discardPending()
            assertEquals(21f, panel.session.draft.fontSize)
            assertTrue(control<JTextArea>(panel, "Pending differences").text.contains("No pending changes"))
        }

    @Test
    fun `native toggle selection keeps a valid surface and allows refresh`() =
        onEdt {
            val panel = StudioPrototypePanel(StudioPrototypeSession())
            val surfaces = control<JList<*>>(panel, "Surface selection")
            val toggle = assertNotNull(surfaces.actionMap.get("toggleAndAnchor"))
            for (index in listOf(3, 1)) {
                surfaces.selectedIndex = index
                val chosen = surfaces.selectedValue

                toggle.actionPerformed(ActionEvent(surfaces, ActionEvent.ACTION_PERFORMED, "toggleAndAnchor"))

                assertEquals(index, surfaces.selectedIndex)
                assertEquals(chosen, surfaces.selectedValue)
                panel.discardPending()
                assertFalse(panel.session.hasPendingChanges)
                assertEquals(StudioPrototypeAppearance(), panel.session.current)
            }
        }

    @Test
    fun `surface navigation and comparison never modify the session`() =
        onEdt {
            val session = StudioPrototypeSession()
            val panel = StudioPrototypePanel(session)
            val navigation = components(panel).filterIsInstance<JList<*>>()
            assertTrue(navigation.isNotEmpty(), "The scene needs keyboard-accessible surface selection")
            val surfaces = navigation.single()
            assertEquals(5, surfaces.model.size)
            for (index in 0 until surfaces.model.size) {
                surfaces.selectedIndex = index
            }
            control<JRadioButton>(panel, "Current").doClick()
            control<JRadioButton>(panel, "Draft").doClick()
            control<JComboBox<*>>(panel, "Profile browser").selectedIndex = 1
            assertFalse(session.hasPendingChanges)
            assertEquals(StudioPrototypeAppearance(), session.current)
            assertEquals(null, session.activeProfileName)
            assertTrue(surfaces.isFocusable)
            assertSame(surfaces, panel.preferredFocusComponent)
        }

    @Test
    fun `font edits stay in draft and compare describes the displayed sample`() =
        onEdt {
            val session = StudioPrototypeSession()
            val panel = StudioPrototypePanel(session)
            control<JList<*>>(panel, "Surface selection").selectedIndex = 3
            control<JSpinner>(panel, "Font size").value = 19.0
            assertEquals(19f, session.draft.fontSize)
            assertEquals(14f, session.current.fontSize)
            assertTrue(panel.canApply)
            assertTrue(control<JTextArea>(panel, "Appearance details").text.contains("19"))
            control<JRadioButton>(panel, "Current").doClick()
            assertTrue(control<JTextArea>(panel, "Appearance details").text.contains("14"))
            assertEquals(19f, session.draft.fontSize)
        }

    @Test
    fun `discard and apply reread existing controls without replacing them`() =
        onEdt {
            val session = StudioPrototypeSession()
            val panel = StudioPrototypePanel(session)
            val size = control<JSpinner>(panel, "Font size")
            size.value = 19.0
            panel.discardPending()
            assertEquals(14f, (size.value as Number).toFloat())
            assertFalse(panel.canApply)
            size.value = 20.0
            assertEquals(StudioPrototypeResult.APPLIED, panel.applyPreview())
            assertEquals(20f, session.current.fontSize)
            size.value = 22.0
            panel.discardPending()
            assertEquals(20f, (size.value as Number).toFloat())
            assertSame(size, control<JSpinner>(panel, "Font size"))
        }

    @Test
    fun `glow enable and style changes retain each style intensity`() =
        onEdt {
            val session = StudioPrototypeSession()
            val panel = StudioPrototypePanel(session)
            val intensity = control<JSlider>(panel, "Glow intensity")
            val style = control<JComboBox<*>>(panel, "Glow style")
            intensity.value = 71
            control<JCheckBox>(panel, "Glow enabled").doClick()
            assertFalse(session.draft.glowEnabled)
            style.selectedIndex = 1
            assertEquals(35, intensity.value)
            intensity.value = 82
            style.selectedIndex = 0
            control<JCheckBox>(panel, "Glow enabled").doClick()
            assertEquals(71, intensity.value)
            assertEquals(82, session.draft.glowIntensities.getValue(GlowStyle.SHARP_NEON))
            assertEquals(25, session.draft.glowIntensities.getValue(GlowStyle.GRADIENT))
            assertTrue(session.current.glowEnabled)
            assertSame(intensity, control<JSlider>(panel, "Glow intensity"))
        }

    @Test
    fun `membership is independent from feature enablement and survives apply discard`() =
        onEdt {
            val session = StudioPrototypeSession()
            val panel = StudioPrototypePanel(session)
            val membership = control<JCheckBox>(panel, "Include Glow")
            membership.doClick()
            assertTrue(membership.text.contains("Keep mine"))
            assertFalse(StudioPrototypeSection.GLOW in session.includedSections)
            assertTrue(session.draft.glowEnabled)
            panel.applyPreview()
            membership.doClick()
            panel.discardPending()
            assertFalse(membership.isSelected)
            assertTrue(session.draft.glowEnabled)
        }

    @Test
    fun `accent validation and syntax vcs edits change only the draft`() =
        onEdt {
            val session = StudioPrototypeSession()
            val panel = StudioPrototypePanel(session)
            val accent = control<JTextField>(panel, "Accent HEX")
            accent.text = "not a color"
            control<JButton>(panel, "Use HEX").doClick()
            assertFalse(session.hasPendingChanges)
            accent.text = "#123ABC"
            control<JButton>(panel, "Use HEX").doClick()
            control<JComboBox<*>>(panel, "Syntax preset").selectedIndex = 2
            control<JSlider>(panel, "VCS diff intensity").value = 66
            assertEquals("#123ABC", session.draft.accentHex)
            assertEquals(SyntaxPreset.NEON, session.draft.syntaxPreset)
            assertEquals(66, session.draft.vcsDiffIntensity)
            assertEquals(StudioPrototypeAppearance(), session.current)
            panel.discardPending()
            assertEquals("#FFCD66", accent.text)
            assertEquals(1, control<JComboBox<*>>(panel, "Syntax preset").selectedIndex)
            assertEquals(33, control<JSlider>(panel, "VCS diff intensity").value)
        }

    @Test
    fun `profile creation is staged until apply and validation is visible`() =
        onEdt {
            val session = StudioPrototypeSession()
            val panel = StudioPrototypePanel(session)
            val create = control<JButton>(panel, "Create profile")
            create.doClick()
            assertTrue(control<JTextArea>(panel, "Preview status").text.contains("name"))
            control<JTextField>(panel, "Profile name").text = "My review"
            create.doClick()
            assertEquals(3, session.profiles.size)
            assertEquals(4, session.pendingProfiles.size)
            assertTrue(control<JTextArea>(panel, "Preview status").text.contains("Unsaved profile"))
            assertTrue(panel.canApply)
            panel.applyPreview()
            assertEquals("My review", session.profiles.last().name)
            assertFalse(panel.canApply)
            create.doClick()
            assertTrue(control<JTextArea>(panel, "Preview status").text.contains("exists"))
        }

    @Test
    fun `profile matching the base label stays separately selectable`() =
        onEdt {
            val session = StudioPrototypeSession()
            val panel = StudioPrototypePanel(session)
            val intensity = control<JSlider>(panel, "Glow intensity")
            intensity.value = 71
            control<JTextField>(panel, "Profile name").text = "Yours (sample)"
            control<JButton>(panel, "Create profile").doClick()
            panel.applyPreview()

            intensity.value = 24
            panel.applyPreview()
            val browser = control<JComboBox<*>>(panel, "Profile browser")
            val savedIndex = browser.itemCount - 1
            browser.selectedIndex = savedIndex
            control<JButton>(panel, "Activate profile").doClick()

            assertEquals("Yours (sample)", session.activeProfileName)
            assertEquals(71, session.current.glowIntensities.getValue(GlowStyle.SOFT))
            assertEquals(savedIndex, browser.selectedIndex)
            browser.selectedIndex = 0
            control<JButton>(panel, "Activate profile").doClick()
            assertEquals(null, session.activeProfileName)
            assertEquals(24, session.current.glowIntensities.getValue(GlowStyle.SOFT))
        }

    @Test
    fun `profile activation and update use the selected accepted profile`() =
        onEdt {
            val session = StudioPrototypeSession()
            val panel = StudioPrototypePanel(session)
            val browser = control<JComboBox<*>>(panel, "Profile browser")
            browser.selectedIndex = 2
            assertEquals(null, session.activeProfileName)
            control<JButton>(panel, "Activate profile").doClick()
            assertEquals("Evening review", session.activeProfileName)
            control<JSlider>(panel, "VCS diff intensity").value = 57
            control<JButton>(panel, "Update profile").doClick()
            assertEquals(
                20,
                session.profiles
                    .single { it.name == "Evening review" }
                    .appearance.vcsDiffIntensity,
            )
            panel.applyPreview()
            assertEquals(
                57,
                session.profiles
                    .single { it.name == "Evening review" }
                    .appearance.vcsDiffIntensity,
            )
        }

    @Test
    fun `quick switcher blocks dirty activation and offers review changes`() =
        onEdt {
            val session = StudioPrototypeSession()
            val panel = StudioPrototypePanel(session)
            control<JSpinner>(panel, "Font size").value = 19.0
            val switcher = control<JButton>(panel, "Preview Quick Switcher")
            switcher.doClick()
            val items = components(switcher.componentPopupMenu).filterIsInstance<JMenuItem>()
            assertTrue(items.any { it.text.contains("Review changes") })
            items.single { it.name == "Activate Quiet coding" }.doClick()
            assertEquals(StudioPrototypeResult.DIRTY_BLOCKED, session.lastResult)
            assertEquals(14f, session.current.fontSize)
            assertEquals(null, session.activeProfileName)
            switcher.doClick()
            components(switcher.componentPopupMenu)
                .filterIsInstance<JMenuItem>()
                .single { it.text.contains("Review changes") }
                .doClick()
            assertTrue(session.hasPendingChanges)
            panel.discardPending()
            switcher.doClick()
            components(switcher.componentPopupMenu)
                .filterIsInstance<JMenuItem>()
                .single { it.name == "Activate Quiet coding" }
                .doClick()
            assertEquals("Quiet coding", session.activeProfileName)
        }

    @Test
    fun `no project explains unavailable profile activation`() =
        onEdt {
            val session = StudioPrototypeSession(isProjectAvailable = false)
            val panel = StudioPrototypePanel(session)
            control<JComboBox<*>>(panel, "Profile browser").selectedIndex = 1
            assertFalse(control<JButton>(panel, "Activate profile").isEnabled)
            val switcher = control<JButton>(panel, "Preview Quick Switcher")
            switcher.doClick()
            val profile =
                components(switcher.componentPopupMenu)
                    .filterIsInstance<JMenuItem>()
                    .single { it.name == "Activate Quiet coding" }
            assertFalse(profile.isEnabled)
            assertTrue(profile.toolTipText.contains("project"))
            assertFalse(session.hasPendingChanges)
        }

    @Test
    fun `partial and rejected preview outcomes remain visible and can retry`() =
        onEdt {
            val session = StudioPrototypeSession()
            var notifications = 0
            val panel = StudioPrototypePanel(session) { notifications++ }
            assertEquals(0, notifications)
            val outcome = control<JComboBox<*>>(panel, "Preview result")
            outcome.selectedIndex = 1
            control<JSpinner>(panel, "Font size").value = 21.0
            assertEquals(StudioPrototypeResult.PARTIAL, panel.applyPreview())
            assertEquals(14f, session.current.fontSize)
            assertEquals(21f, session.draft.fontSize)
            assertFalse(session.hasPendingChanges)
            assertTrue(panel.canApply)
            assertTrue(control<JTextArea>(panel, "Preview status").text.contains("in preview"))
            outcome.selectedIndex = 0
            assertEquals(StudioPrototypeResult.APPLIED, panel.applyPreview())
            assertEquals(21f, session.current.fontSize)
            outcome.selectedIndex = 2
            assertEquals(StudioPrototypeResult.REJECTED, panel.applyPreview())
            assertTrue(panel.canApply)
            assertTrue(control<JTextArea>(panel, "Preview status").text.contains("in preview"))
            assertTrue(notifications > 0)
        }

    @Test
    fun `pending differences expose changed values membership and staged profile before apply`() =
        onEdt {
            val panel = StudioPrototypePanel(StudioPrototypeSession())
            control<JSpinner>(panel, "Font size").value = 19.0
            control<JSlider>(panel, "Glow intensity").value = 61
            control<JCheckBox>(panel, "Include Accent").doClick()
            control<JTextField>(panel, "Profile name").text = "My sample"
            control<JButton>(panel, "Create profile").doClick()
            val visibleText = components(panel).filterIsInstance<JTextArea>().joinToString("\n") { it.text }
            assertTrue(visibleText.contains("Font: 14.0 → 19.0"))
            assertTrue(visibleText.contains("Soft intensity: 20 → 61"))
            assertTrue(visibleText.contains("Accent: Keep mine → Included"))
            assertTrue(visibleText.contains("Create profile: My sample"))
            panel.discardPending()
            assertTrue(control<JTextArea>(panel, "Pending differences").text.contains("No pending changes"))
        }

    @Test
    fun `native panel fits wide and narrow layouts and renders light dark samples`() =
        onEdt {
            val originalDark = !JBColor.isBright()
            val controls =
                listOf(
                    "Panel",
                    "Label",
                    "TextArea",
                    "TextField",
                    "CheckBox",
                    "RadioButton",
                    "Button",
                    "ComboBox",
                    "List",
                    "Spinner",
                    "Slider",
                )
            val backgroundKeys = controls.map { "$it.background" }
            val foregroundKeys = controls.map { "$it.foreground" }
            val keys = backgroundKeys + foregroundKeys
            val originalColors = keys.associateWith(UIManager::getColor)
            try {
                for (dark in listOf(false, true)) {
                    JBColor.setDark(dark)
                    val background = Color(if (dark) 0x242A33 else 0xF5F5F5)
                    val foreground = Color(if (dark) 0xE6E9EF else 0x23272E)
                    backgroundKeys.forEach { UIManager.put(it, background) }
                    foregroundKeys.forEach { UIManager.put(it, foreground) }
                    for (width in listOf(1040, 600)) {
                        val panel = StudioPrototypePanel(StudioPrototypeSession())
                        panel.size = Dimension(width, 720)
                        repeat(3) { layoutTree(panel) }
                        assertHorizontalContainment(panel)
                        val scroll = components(panel).filterIsInstance<JScrollPane>().single()
                        assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, scroll.horizontalScrollBarPolicy)
                        val scene = control<JPanel>(panel, "Sample workspace")
                        val inspector = control<JPanel>(panel, "Studio inspector")
                        assertTrue(scene.width > 0 && inspector.width > 0)
                        assertTrue(scene.bounds.intersection(inspector.bounds).isEmpty)
                        assertTrue(inspector.x + inspector.width <= inspector.parent.width)
                        assertTrue(scene.x + scene.width <= scene.parent.width)
                        if (width == 600) assertTrue(inspector.y > scene.y)
                        val image = BufferedImage(width, 720, BufferedImage.TYPE_INT_ARGB)
                        val graphics = image.createGraphics()
                        try {
                            panel.printAll(graphics)
                        } finally {
                            graphics.dispose()
                        }
                        val directory = Path.of("build/reports/studio-prototype")
                        Files.createDirectories(directory)
                        val theme = if (dark) "dark" else "light"
                        ImageIO.write(image, "png", directory.resolve("$theme-$width.png").toFile())
                    }
                }
            } finally {
                originalColors.forEach { (key, color) -> UIManager.put(key, color) }
                JBColor.setDark(originalDark)
            }
        }

    private inline fun <reified T : JComponent> control(
        root: Container,
        name: String,
    ): T = components(root).filterIsInstance<T>().single { it.name == name }

    private fun components(root: Container): List<Component> =
        root.components.flatMap { child -> listOf(child) + if (child is Container) components(child) else emptyList() }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.filterIsInstance<Container>().forEach(::layoutTree)
    }

    private fun assertHorizontalContainment(root: Container) {
        for (component in components(root)) {
            val isVisible = generateSequence(component) { it.parent }.all { it.isVisible }
            if (isVisible && component.width > 0) {
                assertTrue(
                    component.x >= 0,
                    "${component.name ?: component.javaClass.simpleName} starts outside its parent",
                )
                assertTrue(
                    component.x + component.width <= component.parent.width,
                    "${component.name ?: component.javaClass.simpleName} overflows its parent horizontally",
                )
            }
        }
    }

    private fun onEdt(block: () -> Unit) {
        SwingUtilities.invokeAndWait(block)
    }
}
