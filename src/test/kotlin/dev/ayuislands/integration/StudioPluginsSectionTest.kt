package dev.ayuislands.integration

import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.TitledSeparator
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.accent.toolbar.popup.PopupSwatch
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AccentSwatchGrid
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.IgnorePreviewInput
import dev.ayuislands.settings.PluginsAvailability
import dev.ayuislands.settings.PluginsControls
import dev.ayuislands.settings.PluginsPanelEvent
import dev.ayuislands.settings.PluginsPanelView
import dev.ayuislands.settings.PluginsPreview
import dev.ayuislands.settings.PluginsPreviewInput
import dev.ayuislands.settings.PluginsPreviewView
import dev.ayuislands.settings.PluginsSettings
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioPluginsEditing
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.ui.StudioPluginsSection
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxPreset
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JSlider
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioPluginsSectionTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Before
    fun forbidAmbientEffects() {
        mockkObject(
            AyuIslandsSettings.Companion,
            LicenseChecker,
            ConflictRegistry,
            SyntaxIntensityService.Companion,
            GlowOverlayManager,
        )
        every { AyuIslandsSettings.getInstance() } throws AssertionError("Settings must be supplied explicitly")
        every { LicenseChecker.isLicensedOrGrace() } throws AssertionError("Entitlement must be supplied explicitly")
        every { LicenseChecker.requestLicense(any()) } throws AssertionError("Unlock belongs to the host")
        every { ConflictRegistry.isCodeGlanceProDetected() } throws AssertionError("Detection must be an explicit fact")
        every { ConflictRegistry.isIndentRainbowDetected() } throws AssertionError("Detection must be an explicit fact")
        every { SyntaxIntensityService.getInstance() } throws
            AssertionError("Local samples must not prepare live targets")
        every { GlowOverlayManager.syncGlowForAllProjects() } throws AssertionError("Effects belong to Apply")
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } throws AssertionError("Sample styles must already be detached")
    }

    @After
    fun cleanup() {
        SwingUtilities.invokeAndWait {}
        unmockkAll()
    }

    @Test
    fun `render keeps the familiar controls and groups without reads events or preview computation`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<PluginsPanelEvent>()
            val requested = settings()
            val prepared = preview(requested)
            mockkObject(SyntaxIntensityApplicator)
            try {
                every { SyntaxIntensityApplicator.compute(any()) } answers { callOriginal() }
                val controls = PluginsControls(events::add)
                controls.render(view(requested), prepared)
                controls.render(view(requested), prepared)
                pixels(sample(controls, "Ignore colors sample"))
                verify(exactly = 0) { SyntaxIntensityApplicator.compute(any()) }

                assertEquals(11, descendants(controls).filterIsInstance<JCheckBox>().size)
                assertEquals(1, descendants(controls).filterIsInstance<JSlider>().size)
                assertEquals(
                    listOf("External Theme Support", "Accent Inheritance", "Plugin Integrations"),
                    descendants(controls).filterIsInstance<TitledSeparator>().map { it.text },
                )
                assertEquals(12, descendants(controls).filterIsInstance<PopupSwatch>().size)
                assertTrue(events.isEmpty())
            } finally {
                unmockkObject(SyntaxIntensityApplicator)
            }
        }
    }

    @Test
    fun `native edits stage only exact shared fields and custom color requests remain host actions`() {
        SwingUtilities.invokeAndWait {
            val fields = fields()
            val requested = StudioPluginsEditing.settings(fields)
            val staged = mutableListOf<List<SessionIntent>>()
            val actions = mutableListOf<PluginsPanelEvent>()
            val section = StudioPluginsSection(staged::add, actions::add)
            section.render(fields, view(requested), preview(requested), preview(requested))
            val component = section.content.component
            expandInheritance(component)

            val routes =
                listOf(
                    "Enable Ayu enhancements on other themes" to "externalThemeEnhancementsEnabled",
                    "Quick switcher" to "externalThemeQuickSwitcherEnabled",
                    "Glow" to "externalThemeGlowEnabled",
                    "CodeGlance Pro" to "externalThemeCodeGlanceProEnabled",
                    "Indent Rainbow" to "externalThemeIndentRainbowEnabled",
                    "Chrome tint" to "externalThemeChromeTintEnabled",
                    "Override native theme accent" to "externalThemeAccentOverrideEnabled",
                    ".ignore syntax colors" to "ignorePluginSyntaxColorsEnabled",
                    "CodeGlance Pro viewport" to "cgpIntegrationEnabled",
                    "Indent Rainbow guides" to "irIntegrationEnabled",
                    "Highlight indent errors" to "irErrorHighlightEnabled",
                )
            for ((title, name) in routes) {
                button(component, title).doClick()
                assertStagedFields(staged.removeLast(), fields, setOf(name))
            }
            sourceCombo(component).selectedItem = "Manual"
            assertStagedFields(staged.removeLast(), fields, setOf("externalThemeAccentSource"))
            descendants(component).filterIsInstance<ActionButton>().single { it.presentation.text == "Neon" }.click()
            assertStagedFields(staged.removeLast(), fields, setOf("indentPresetName"))
            alpha(component).value = 77
            assertStagedFields(staged.removeLast(), fields, setOf("indentCustomAlpha"))

            val grid = descendants(component).filterIsInstance<AccentSwatchGrid>().single()
            val swatch = descendants(grid).filterIsInstance<PopupSwatch>().first()
            click(swatch)
            assertStagedFields(staged.removeLast(), fields, setOf("externalThemeAccent", "externalThemeAccentSource"))
            button(grid, "Custom…").doClick()
            assertEquals(listOf<PluginsPanelEvent>(PluginsPanelEvent.CustomAccent), actions)
            assertTrue(staged.isEmpty())
        }
    }

    @Test
    fun `Current Draft switches all feature samples together while controls keep working values`() {
        SwingUtilities.invokeAndWait {
            val fields = fields()
            val working = StudioPluginsEditing.settings(fields)
            val current =
                working.copy(
                    integrations =
                        working.integrations.copy(
                            ignoreColorsEnabled = false,
                            highlightErrors = false,
                            indentAlpha = 13,
                        ),
                )
            val staged = mutableListOf<List<SessionIntent>>()
            val actions = mutableListOf<PluginsPanelEvent>()
            val section = StudioPluginsSection(staged::add, actions::add)
            section.render(fields, view(working), preview(current, "#AB1234"), preview(working, "#12AB34"))
            val component = section.content.component
            val samples = listOf("Ignore colors sample", "CodeGlance Pro sample", "Indent Rainbow sample")
            val currentPixels = samples.associateWith { pixels(sample(component, it)) }
            val draftSelections = descendants(component).filterIsInstance<JCheckBox>().map { it.isSelected }

            section.content.preview(PreviewMode.DRAFT)
            for (name in samples) {
                assertFalse(
                    currentPixels.getValue(name).contentEquals(pixels(sample(component, name))),
                    "$name did not use Draft",
                )
            }
            assertEquals(draftSelections, descendants(component).filterIsInstance<JCheckBox>().map { it.isSelected })
            section.content.preview(PreviewMode.CURRENT)
            for (name in samples) {
                assertTrue(
                    currentPixels.getValue(name).contentEquals(pixels(sample(component, name))),
                    "$name did not restore Current",
                )
            }
            assertTrue(staged.isEmpty())
            assertTrue(actions.isEmpty())
        }
    }

    @Test
    fun `locked integrations retain free choices and read only controls cannot emit edits`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<PluginsPanelEvent>()
            val requested = settings()
            val controls = PluginsControls(events::add)
            val locked =
                view(
                    requested,
                ).copy(
                    availability =
                        PluginsAvailability(
                            isLicensed = false,
                            hasCodeGlance = true,
                            hasIndentRainbow = true,
                        ),
                )
            controls.render(locked, preview(requested))
            expandInheritance(controls)
            for (title in listOf(
                "Enable Ayu enhancements on other themes",
                "Quick switcher",
                "Glow",
                "CodeGlance Pro",
                "Indent Rainbow",
                "Override native theme accent",
                ".ignore syntax colors",
            )) {
                assertTrue(button(controls, title).isEnabled, "$title must remain free")
            }
            assertTrue(sourceCombo(controls).isEnabled)
            for (title in listOf(
                "Chrome tint",
                "CodeGlance Pro viewport",
                "Indent Rainbow guides",
                "Highlight indent errors",
            )) {
                assertFalse(button(controls, title).isEnabled, "$title must remain locked")
                button(controls, title).doClick()
            }
            assertFalse(alpha(controls).isEnabled)
            alpha(controls).value = 77
            assertTrue(events.isEmpty())
            button(controls, "Unlock").doClick()
            assertEquals(listOf<PluginsPanelEvent>(PluginsPanelEvent.Unlock), events)

            events.clear()
            controls.render(view(requested).copy(isEditable = false), preview(requested))
            descendants(controls).filterIsInstance<JCheckBox>().forEach {
                assertFalse(it.isEnabled)
                it.doClick()
            }
            sourceCombo(controls).selectedItem = "Manual"
            alpha(controls).value = 78
            click(descendants(controls).filterIsInstance<PopupSwatch>().first())
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `missing integrations preserve stored choices and leave Ignore usable`() {
        SwingUtilities.invokeAndWait {
            val events = mutableListOf<PluginsPanelEvent>()
            val requested = settings()
            val controls = PluginsControls(events::add)
            val missing =
                view(
                    requested,
                ).copy(
                    availability =
                        PluginsAvailability(
                            isLicensed = true,
                            hasCodeGlance = false,
                            hasIndentRainbow = false,
                        ),
                )
            controls.render(missing, preview(requested))

            assertTrue(visibleWithin(button(controls, ".ignore syntax colors"), controls))
            assertTrue(button(controls, ".ignore syntax colors").isEnabled)
            assertFalse(visibleWithin(button(controls, "CodeGlance Pro viewport"), controls))
            assertFalse(visibleWithin(button(controls, "Indent Rainbow guides"), controls))
            val guidance =
                descendants(controls).filter { visibleWithin(it, controls) }.mapNotNull {
                    when (it) {
                        is JLabel -> it.text
                        is JEditorPane -> it.text
                        else -> null
                    }
                }
            assertTrue(
                guidance.any {
                    it.contains("Install") &&
                        it.contains("CodeGlance Pro") &&
                        it.contains("Indent Rainbow")
                },
            )

            controls.render(view(requested), preview(requested))
            assertTrue(button(controls, "CodeGlance Pro viewport").isSelected)
            assertTrue(button(controls, "Indent Rainbow guides").isSelected)
            assertEquals(91, alpha(controls).value)
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `Indent rows preserve v2 visibility and Accent Inheritance expansion across renders`() {
        SwingUtilities.invokeAndWait {
            val controls = PluginsControls { error("Rendering visibility must not dispatch") }
            val requested = settings()
            controls.render(view(requested), preview(requested))
            assertFalse(visibleWithin(sourceCombo(controls), controls))
            expandInheritance(controls)
            assertTrue(visibleWithin(sourceCombo(controls), controls))

            val disabled = requested.copy(integrations = requested.integrations.copy(indentEnabled = false))
            controls.render(view(disabled), preview(disabled))
            assertFalse(visibleWithin(button(controls, "Highlight indent errors"), controls))
            assertTrue(visibleWithin(alpha(controls), controls), "Custom Alpha retains its independent visibility")
            assertTrue(visibleWithin(sourceCombo(controls), controls))
            val ambient = disabled.copy(integrations = disabled.integrations.copy(indentPreset = "AMBIENT"))
            controls.render(view(ambient), preview(ambient))
            assertFalse(visibleWithin(alpha(controls), controls))
            controls.render(
                view(
                    ambient,
                ).copy(
                    availability =
                        PluginsAvailability(
                            isLicensed = false,
                            hasCodeGlance = true,
                            hasIndentRainbow = true,
                        ),
                ),
                preview(ambient),
            )
            assertTrue(visibleWithin(alpha(controls), controls))
            assertTrue(visibleWithin(button(controls, "Highlight indent errors"), controls))
            controls.render(view(requested), preview(requested))
            assertEquals(91, alpha(controls).value)
            assertTrue(visibleWithin(sourceCombo(controls), controls))
        }
    }

    @Test
    fun `allowed features and full two by six palette fit narrow and wide settings widths`() {
        SwingUtilities.invokeAndWait {
            val controls = PluginsControls { error("Layout must not dispatch") }
            val requested = settings()
            controls.render(view(requested), preview(requested))
            expandInheritance(controls)
            for (width in listOf(360, 480, 720, 1080)) {
                controls.setSize(width, controls.preferredSize.height)
                layout(controls)
                val swatches = descendants(controls).filterIsInstance<PopupSwatch>()
                assertEquals(AYU_ACCENT_PRESETS.map { it.hex }, swatches.map { it.hex.value })
                val rows = swatches.groupBy { SwingUtilities.convertPoint(it.parent, it.location, controls).y }
                assertEquals(listOf(6, 6), rows.values.map { it.size })
                for (swatch in swatches) {
                    val bounds = SwingUtilities.convertRectangle(swatch.parent, swatch.bounds, controls)
                    assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= width, "Palette outside content at $width")
                }
                val left = button(controls, "Quick switcher")
                val right = button(controls, "Glow")
                val leftPoint = SwingUtilities.convertPoint(left.parent, left.location, controls)
                val rightPoint = SwingUtilities.convertPoint(right.parent, right.location, controls)
                if (width >= 480) {
                    assertEquals(leftPoint.y, rightPoint.y)
                    assertTrue(rightPoint.x > leftPoint.x && rightPoint.x - leftPoint.x < width / 2)
                } else {
                    assertTrue(rightPoint.y >= leftPoint.y, "Narrow columns must retain their reading order")
                }
                for (checkbox in descendants(
                    controls,
                ).filterIsInstance<JCheckBox>().filter { visibleWithin(it, controls) }) {
                    val bounds = SwingUtilities.convertRectangle(checkbox.parent, checkbox.bounds, controls)
                    assertTrue(checkbox.width >= checkbox.preferredSize.width, "Clipped ${checkbox.text}")
                    assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= width, "Outside content: ${checkbox.text}")
                }
            }
        }
    }

    @Test
    fun `named indent preset sample ignores dormant custom alpha in both bands and caption`() {
        SwingUtilities.invokeAndWait {
            val controls = PluginsControls { error("Comparing samples must not dispatch") }
            val initial = settings()
            val lowCustom =
                initial.copy(
                    integrations = initial.integrations.copy(indentPreset = "NEON", indentAlpha = 13),
                )
            controls.render(view(lowCustom), preview(lowCustom))
            val first = pixels(sample(controls, "Indent Rainbow sample"))

            val highCustom = initial.copy(integrations = lowCustom.integrations.copy(indentAlpha = 153))
            controls.render(view(highCustom), preview(highCustom))

            assertTrue(
                first.contentEquals(pixels(sample(controls, "Indent Rainbow sample"))),
                "Neon always uses alpha 77; the sample caption must not report the dormant Custom alpha",
            )
            assertEquals(153, alpha(controls).value, "The stored Custom value must remain available unchanged")
        }
    }

    @Test
    fun `long raw source and preset values remain inspectable without escaping sample bounds`() {
        SwingUtilities.invokeAndWait {
            val controls = PluginsControls { error("Raw preview rendering must not dispatch") }
            val initial = settings()
            val rawPreset = "FUTURE_INDENT_".repeat(30)
            val rawSource = "FUTURE_SOURCE_".repeat(30)
            val unknown =
                initial.copy(
                    external = initial.external.copy(accentSource = rawSource),
                    integrations = initial.integrations.copy(indentPreset = rawPreset, indentAlpha = 217),
                )
            controls.render(view(unknown), preview(unknown))
            expandInheritance(controls)
            assertTrue(
                descendants(
                    controls,
                ).filterIsInstance<JComponent>().any { it.toolTipText.orEmpty().contains(rawSource) },
            )
            assertTrue(
                descendants(
                    controls,
                ).filterIsInstance<JComponent>().any { it.toolTipText.orEmpty().contains(rawPreset) },
            )
            assertTrue(descendants(controls).filterIsInstance<JLabel>().any { it.text == "217" })
            for (name in listOf("Ignore colors sample", "CodeGlance Pro sample", "Indent Rainbow sample")) {
                assertSampleBounds(sample(controls, name))
            }
        }
    }

    private fun fields(): StudioFields {
        val original = sessionDocument().shared.plugins
        val replacements =
            mapOf(
                "cgpIntegrationEnabled" to RawValue.BooleanValue(true),
                "irIntegrationEnabled" to RawValue.BooleanValue(true),
                "irErrorHighlightEnabled" to RawValue.BooleanValue(true),
                "ignorePluginSyntaxColorsEnabled" to RawValue.BooleanValue(true),
                "externalThemeAccentSource" to RawValue.Text("AUTOMATIC"),
                "externalThemeAccent" to RawValue.Text("#123456"),
                "indentPresetName" to RawValue.Text("CUSTOM"),
                "indentCustomAlpha" to RawValue.Integer(91),
            )
        return original.copy(
            records =
                StudioList(
                    original.records.map { record ->
                        replacements[record.key.name]?.let { record.copy(value = it) }
                            ?: record
                    },
                ),
        )
    }

    private fun settings(): PluginsSettings = StudioPluginsEditing.settings(fields())

    private fun view(requested: PluginsSettings): PluginsPanelView =
        PluginsPanelView(
            requested,
            PluginsAvailability(isLicensed = true, hasCodeGlance = true, hasIndentRainbow = true),
            isEditable = true,
            pendingBadges = emptySet(),
        )

    private fun preview(
        requested: PluginsSettings,
        accent: String = "#FFCC66",
    ): PluginsPreviewView {
        val key = TextAttributesKey.createTextAttributesKey("IGNORE.COMMENT")
        val request =
            SyntaxIntensityApplicator.Request(
                SyntaxPreset.AMBIENT,
                "Mirage",
                Color(0x1F, 0x24, 0x30),
                mapOf(key to TextAttributes(Color.ORANGE, null, null, null, Font.PLAIN)),
                emptyMap(),
            )
        return PluginsPreview.prepare(
            PluginsPreviewInput(
                requested,
                accent,
                AyuVariant.MIRAGE,
                IgnorePreviewInput(
                    "# Native ignore patterns\nbuild/",
                    request,
                    mapOf(key to TextAttributes(Color.GRAY, null, null, null, Font.ITALIC)),
                ),
            ),
        )
    }

    private fun assertStagedFields(
        intents: List<SessionIntent>,
        before: StudioFields,
        names: Set<String>,
    ) {
        val edits = intents.map { assertIs<SharedIntent.Field>(assertIs<SessionIntent.Shared>(it).intent) }
        assertEquals(names, edits.map { it.after.key.name }.toSet())
        assertEquals(names.size, edits.size)
        edits.forEach { assertEquals(before.records.single { field -> field.key == it.before.key }, it.before) }
    }

    private fun sourceCombo(container: Container): JComboBox<*> =
        descendants(container).filterIsInstance<JComboBox<*>>().single {
            it.accessibleContext.accessibleName ==
                "Accent source"
        }

    private fun alpha(container: Container): JSlider = descendants(container).filterIsInstance<JSlider>().single()

    private fun button(
        container: Container,
        title: String,
    ): AbstractButton = descendants(container).filterIsInstance<AbstractButton>().single { it.text == title }

    private fun sample(
        container: Container,
        name: String,
    ): JComponent = descendants(container).filterIsInstance<JComponent>().single { it.toolTipText == name }

    private fun expandInheritance(container: Container) {
        val title =
            descendants(
                container,
            ).filterIsInstance<TitledSeparator>().single { it.text == "Accent Inheritance" }
        title.dispatchEvent(MouseEvent(title, MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false))
    }

    private fun click(component: JComponent) {
        component.size = component.preferredSize
        component.dispatchEvent(
            MouseEvent(component, MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1),
        )
        component.dispatchEvent(
            MouseEvent(component, MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1),
        )
    }

    private fun pixels(component: JComponent): IntArray {
        component.setSize(320, 120)
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private fun assertSampleBounds(component: JComponent) {
        component.setSize(320, 120)
        val image = BufferedImage(352, 152, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.MAGENTA
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.translate(16, 16)
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        for (x in 336 until image.width) {
            for (y in 0 until image.height) {
                assertEquals(Color.MAGENTA.rgb, image.getRGB(x, y), "Sample painted outside its right edge")
            }
        }
    }

    private fun layout(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layout)
    }

    private fun visibleWithin(
        component: Component,
        root: Component,
    ): Boolean {
        var current: Component? = component
        while (current != null && current !== root) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return current === root && root.isVisible
    }

    private fun descendants(root: Container): List<Component> =
        buildList {
            for (child in root.components) {
                add(child)
                if (child is Container) addAll(descendants(child))
            }
        }
}
