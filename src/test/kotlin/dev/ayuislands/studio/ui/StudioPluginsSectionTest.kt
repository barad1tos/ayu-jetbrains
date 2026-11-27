package dev.ayuislands.studio.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.ui.TitledSeparator
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.studio.catalogTestDocument
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioPluginsEditing
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JTabbedPane
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioPluginsSectionTest {
    private val installed = StudioPluginsAvailability(isLicensed = true, hasCodeGlance = true, hasIndentRainbow = true)

    @Before
    fun forbidAmbientEffects() {
        mockkStatic(ActionManager::class)
        every { ActionManager.getInstance() } returns mockk(relaxed = true)
        mockkObject(AyuIslandsSettings.Companion, LicenseChecker)
        every { AyuIslandsSettings.getInstance() } throws AssertionError("Plugins controls must use explicit facts")
        every { LicenseChecker.isLicensedOrGrace() } throws AssertionError("Entitlement belongs to the host")
        every { LicenseChecker.requestLicense(any()) } throws AssertionError("Unlock belongs to the host")
    }

    @After
    fun cleanup() {
        SwingUtilities.invokeAndWait {}
        unmockkObject(AyuIslandsSettings.Companion, LicenseChecker)
        unmockkStatic(ActionManager::class)
    }

    @Test
    fun `mounted Plugins tab keeps groups and balanced swatches and edits only the host draft`() {
        val initial = fields("externalThemeAccentSource" to RawValue.Text("AUTOMATIC"))
        SwingUtilities.invokeAndWait {
            val draft = PluginDraft(initial)
            val section = section(stage = draft::stage)
            val panel = mount(section)
            val root = section.content.component
            section.render(draft.working, installed, isEditable = true)
            assertEquals("Plugins", panel.getTitleAt(panel.selectedIndex))
            assertTrue(SwingUtilities.isDescendingFrom(root, panel.selectedComponent))
            assertEquals(
                listOf("External Theme Support", "Accent Inheritance", "Plugin Integrations"),
                descendants(root).filterIsInstance<TitledSeparator>().map { it.text },
            )
            assertEquals(11, descendants(root).filterIsInstance<JCheckBox>().size)
            assertFalse(visibleWithin(source(root), root))
            discloseInheritance(root)
            assertTrue(visibleWithin(source(root), root))
            val palette = descendants(root).filterIsInstance<StudioAccentPalette>().single()
            val swatches = descendants(palette).filterIsInstance<JToggleButton>()
            assertEquals(12, swatches.size)
            palette.size = palette.preferredSize
            layout(palette)
            val swatchesPerRow =
                swatches
                    .groupingBy { it.y }
                    .eachCount()
                    .values
                    .sorted()
            assertEquals(listOf(6, 6), swatchesPerRow)
            assertTrue(swatches.all { it.accessibleContext.accessibleName.isNotBlank() })
            val paletteButtons = descendants(palette).filterIsInstance<AbstractButton>()
            assertTrue(paletteButtons.none { it.text == "Shuffle" })

            val first = draft.working
            checkbox(root, "CodeGlance Pro").doClick()
            assertEquals(listOf("externalThemeCodeGlanceProEnabled"), draft.lastChanged)
            assertEquals(value(first, "cgpIntegrationEnabled"), value(draft.working, "cgpIntegrationEnabled"))
            val swatch = swatches.first { !it.isSelected }
            swatch.doClick()
            assertEquals(setOf("externalThemeAccent", "externalThemeAccentSource"), draft.lastChanged.toSet())
            val settings = StudioPluginsEditing.settings(draft.working)
            assertEquals("MANUAL", settings.externalAccentSource)
            assertTrue(swatch.accessibleContext.accessibleName.contains(checkNotNull(settings.externalAccent)))

            section.render(draft.working, installed, isEditable = true)
            assertEquals(ExternalAccentSource.MANUAL, source(root).selectedItem)
            assertSame(swatch, descendants(palette).filterIsInstance<JToggleButton>().first { it.isSelected })
            assertEquals(2, draft.batches)
            assertTrue(visibleWithin(source(root), root))
            source(root).selectedItem = ExternalAccentSource.AUTOMATIC
            assertEquals(listOf("externalThemeAccentSource"), draft.lastChanged)
            assertEquals(settings.externalAccent, StudioPluginsEditing.settings(draft.working).externalAccent)
        }
    }

    @Test
    fun `installation and Pro gates hide unavailable rows and preserve raw settings`() {
        val initial =
            fields(
                "indentPresetName" to RawValue.Text("CUSTOM"),
                "indentCustomAlpha" to RawValue.Integer(310),
            )
        SwingUtilities.invokeAndWait {
            val draft = PluginDraft(initial)
            var unlocks = 0
            val links = mutableListOf<StudioPluginTarget>()
            val section = section(stage = draft::stage, openPlugin = { links += it }, unlock = { unlocks++ })
            val root = section.content.component
            val indent = checkbox(root, "Indent Rainbow guides")
            val chrome = checkbox(root, "Chrome tint")
            val alpha = descendants(root).filterIsInstance<JSlider>().single()

            section.render(initial, StudioPluginsAvailability(false, false, false), isEditable = true)
            assertTrue(checkbox(root, ".ignore syntax colors").isEnabled)
            assertFalse(visibleWithin(indent, root))
            assertFalse(chrome.isEnabled)
            assertTrue(
                descendants(root).filterIsInstance<JEditorPane>().any { guidance ->
                    val text = guidance.document.getText(0, guidance.document.length)
                    visibleWithin(guidance, root) && text.contains("Install CodeGlance Pro or Indent Rainbow")
                },
            )

            section.render(initial, installed.copy(isLicensed = false), isEditable = true)
            assertTrue(visibleWithin(indent, root))
            assertFalse(indent.isEnabled)
            assertTrue(visibleWithin(alpha, root))
            assertFalse(alpha.isEnabled)
            assertEquals(153, alpha.value)
            alpha.value = 51
            button(root, "Unlock Pro").doClick()
            val link =
                descendants(root).filterIsInstance<AbstractButton>().single {
                    it.accessibleContext.accessibleName == "Indent Rainbow plugin page"
                }
            link.doClick()
            assertEquals(1, unlocks)
            assertEquals(listOf(StudioPluginTarget.INDENT_RAINBOW), links)
            assertEquals("https://plugins.jetbrains.com/plugin/13308-indent-rainbow", links.single().url)
            assertEquals(0, draft.batches)
            assertEquals(initial, draft.working)

            section.render(initial, installed, isEditable = true)
            indent.doClick()
            assertEquals(listOf("irIntegrationEnabled"), draft.lastChanged)
            section.render(draft.working, installed, isEditable = true)
            alpha.value = 119
            assertEquals(listOf("indentCustomAlpha"), draft.lastChanged)
            assertEquals(RawValue.Integer(119), value(draft.working, "indentCustomAlpha"))
            section.render(draft.working, installed, isEditable = false)
            assertFalse(checkbox(root, ".ignore syntax colors").isEnabled)
            assertFalse(indent.isEnabled)
            assertFalse(alpha.isEnabled)
            assertEquals(2, draft.batches)
        }
    }

    @Test
    fun `custom color cancellation stages nothing and selection uses the same Manual edit`() {
        val savedAccent = AccentHex.require("#A15E33")
        val initial =
            fields(
                "externalThemeAccentSource" to RawValue.Text("AUTOMATIC"),
                "externalThemeAccent" to RawValue.Text(savedAccent.value),
            )
        SwingUtilities.invokeAndWait {
            val draft = PluginDraft(initial)
            val seen = mutableListOf<AccentHex?>()
            var chosen: AccentHex? = null
            val section =
                section(stage = draft::stage, chooseCustom = { color ->
                    seen += color
                    chosen
                })
            val root = section.content.component
            section.render(initial, installed, isEditable = true)
            discloseInheritance(root)
            button(root, "Custom…").doClick()
            assertEquals(0, draft.batches)
            assertEquals(listOf<AccentHex?>(savedAccent), seen)
            chosen = AccentHex.require("#112233")
            button(root, "Custom…").doClick()
            assertEquals(1, draft.batches)
            assertEquals(setOf("externalThemeAccent", "externalThemeAccentSource"), draft.lastChanged.toSet())
            assertEquals(RawValue.Text("#112233"), value(draft.working, "externalThemeAccent"))
        }
    }

    @Test
    fun `Current and Draft repaint each installed sample while controls keep the working values`() {
        val current = previewFields(ayuIgnore = false, alpha = 46)
        val draft = previewFields(ayuIgnore = true, alpha = 113)
        val observations =
            StudioPluginsComparison(
                current,
                previewFacts("#59C2FF"),
                previewFacts("#F28779"),
            )
        SwingUtilities.invokeAndWait {
            val section = section(stage = { error("Comparing samples must not stage an edit") })
            section.render(draft, installed, isEditable = true, comparison = observations)
            val root = section.content.component
            val samples = descendants(root).filterIsInstance<StudioPluginSample>()
            assertEquals(3, samples.size)
            assertTrue(samples.all { visibleWithin(it, root) })
            assertTrue(checkbox(root, ".ignore syntax colors").isSelected)
            val currentPixels = samples.map(::pixels)

            section.content.preview(PreviewMode.DRAFT)
            for ((sample, baseline) in samples.zip(currentPixels)) assertFalse(baseline.contentEquals(pixels(sample)))
            assertTrue(checkbox(root, ".ignore syntax colors").isSelected)

            section.content.preview(PreviewMode.CURRENT)
            for ((sample, baseline) in samples.zip(currentPixels)) assertTrue(baseline.contentEquals(pixels(sample)))
            val missing = installed.copy(hasCodeGlance = false, hasIndentRainbow = false)
            section.render(draft, missing, isEditable = true, comparison = observations)
            val visible = samples.filter { visibleWithin(it, root) }
            assertEquals(listOf(".ignore color sample"), visible.map { it.accessibleContext.accessibleName })
        }
    }

    @Test
    fun `preview derives the native indent color ramp without normalizing the raw draft`() {
        val custom = previewFields(ayuIgnore = true, alpha = 310)
        val available = previewFacts("#59C2FF")
        val view = StudioPluginsPreview.prepare(StudioPluginsEditing.settings(custom), available)
        assertEquals(255, view.indentColors.maxOf { it.alpha })
        assertEquals(RawValue.Integer(310), value(custom, "indentCustomAlpha"))
        val unspecifiedPreset = previewFields(ayuIgnore = false, alpha = 310, preset = RawValue.Null)
        val fallback = StudioPluginsPreview.prepare(StudioPluginsEditing.settings(unspecifiedPreset), available)
        assertEquals(46, fallback.indentColors.maxOf { it.alpha })
        val unavailable =
            StudioPluginsPreview.prepare(StudioPluginsEditing.settings(custom), available.copy(accent = null))
        assertTrue(unavailable.indentColors.isEmpty())
        assertEquals(null, unavailable.minimapAccent)
    }

    private fun previewFields(
        ayuIgnore: Boolean,
        alpha: Int,
        preset: RawValue = RawValue.Text("CUSTOM"),
    ): StudioFields =
        fields(
            "ignorePluginSyntaxColorsEnabled" to RawValue.BooleanValue(ayuIgnore),
            "cgpIntegrationEnabled" to RawValue.BooleanValue(true),
            "irIntegrationEnabled" to RawValue.BooleanValue(true),
            "indentPresetName" to preset,
            "indentCustomAlpha" to RawValue.Integer(alpha),
        )

    private fun previewFacts(hex: String): StudioPluginsPreviewFacts =
        StudioPluginsPreviewFacts(
            AccentHex.require(hex),
            AyuVariant.MIRAGE,
            StudioList(listOf(StudioPluginCodeLine("# Generated files", Color.GRAY))),
            StudioList(listOf(StudioPluginCodeLine("# Generated files", Color.GREEN))),
        )

    private fun pixels(component: JComponent): IntArray {
        val size = component.preferredSize
        component.size = size
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(0, 0, size.width, size.height, null, 0, size.width)
    }

    private fun mount(section: StudioPluginsSection): JTabbedPane {
        val shell =
            StudioSettingsPanel(
                StudioSettingsSection.entries.map { id ->
                    if (id == StudioSettingsSection.PLUGINS) section.content else StudioSectionContent(id, JPanel()) {}
                },
                version = "3.0.0",
                selectProfile = {},
                selectPreview = {},
                selectSection = {},
                openCommunityLink = {},
            )
        return descendants(shell).filterIsInstance<JTabbedPane>().single().apply {
            selectedIndex = StudioSettingsSection.PLUGINS.ordinal
        }
    }

    private fun section(
        stage: ((StudioFields) -> List<SharedIntent.Field>) -> Unit,
        chooseCustom: (AccentHex?) -> AccentHex? = { error("Unexpected accent chooser") },
        openPlugin: (StudioPluginTarget) -> Unit = { error("Unexpected plugin link") },
        unlock: () -> Unit = { error("Unexpected unlock request") },
    ): StudioPluginsSection = StudioPluginsSection(stage, chooseCustom, openPlugin, unlock)

    private fun discloseInheritance(root: Container) {
        val group = descendants(root).filterIsInstance<TitledSeparator>().single { it.text == "Accent Inheritance" }
        group.dispatchEvent(MouseEvent(group, MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1))
    }

    private fun checkbox(
        root: Container,
        label: String,
    ): JCheckBox = descendants(root).filterIsInstance<JCheckBox>().single { it.text == label }

    private fun button(
        root: Container,
        label: String,
    ): AbstractButton = descendants(root).filterIsInstance<AbstractButton>().single { it.text == label }

    private fun source(root: Container): JComboBox<*> =
        descendants(root).filterIsInstance<JComboBox<*>>().single {
            it.accessibleContext.accessibleName == "Accent source"
        }

    private fun fields(vararg overrides: Pair<String, RawValue>): StudioFields {
        val fields = catalogTestDocument().shared.plugins
        val changes = overrides.toMap()
        val records = fields.records.map { it.copy(value = changes[it.key.name] ?: it.value) }
        return fields.copy(records = StudioList(records))
    }

    private fun value(
        fields: StudioFields,
        name: String,
    ): RawValue = fields.records.single { it.key.name == name }.value

    private fun visibleWithin(
        component: Component,
        root: JComponent,
    ): Boolean = component.isVisible && (component === root || visibleWithin(checkNotNull(component.parent), root))

    private fun layout(root: Container) {
        root.doLayout()
        root.components.filterIsInstance<Container>().forEach(::layout)
    }

    private fun descendants(root: Container): List<Component> =
        buildList {
            for (child in root.components) {
                add(child)
                if (child is Container) addAll(descendants(child))
            }
        }

    private class PluginDraft(
        initial: StudioFields,
    ) {
        var working = initial
            private set
        var batches = 0
            private set
        var lastChanged = emptyList<String>()
            private set

        fun stage(edit: (StudioFields) -> List<SharedIntent.Field>) {
            val requested = edit(working)
            val changed = requested.associate { it.after.key to it.after }
            lastChanged = requested.map { it.after.key.name }
            working = working.copy(records = StudioList(working.records.map { changed[it.key] ?: it }))
            batches++
        }
    }
}
