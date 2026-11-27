package dev.ayuislands.studio.ui

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.properties.AtomicProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.indent.IndentPreset
import dev.ayuislands.settings.PremiumFeatureGate
import dev.ayuislands.settings.applyPremiumLock
import dev.ayuislands.settings.newFeatureBadgeLabel
import dev.ayuislands.studio.model.StudioPluginsEdit
import dev.ayuislands.studio.model.StudioPluginsToggle
import dev.ayuislands.studio.model.StudioPluginsToggle.CODE_GLANCE_INTEGRATION
import dev.ayuislands.studio.model.StudioPluginsToggle.EXTERNAL_CHROME_TINT
import dev.ayuislands.studio.model.StudioPluginsToggle.EXTERNAL_CODE_GLANCE
import dev.ayuislands.studio.model.StudioPluginsToggle.EXTERNAL_ENHANCEMENTS
import dev.ayuislands.studio.model.StudioPluginsToggle.EXTERNAL_GLOW
import dev.ayuislands.studio.model.StudioPluginsToggle.EXTERNAL_INDENT_RAINBOW
import dev.ayuislands.studio.model.StudioPluginsToggle.EXTERNAL_QUICK_SWITCHER
import dev.ayuislands.studio.model.StudioPluginsToggle.IGNORE_SYNTAX_COLORS
import dev.ayuislands.studio.model.StudioPluginsToggle.INDENT_ERRORS
import dev.ayuislands.studio.model.StudioPluginsToggle.INDENT_RAINBOW_INTEGRATION
import dev.ayuislands.studio.model.StudioPluginsToggle.OVERRIDE_NATIVE_ACCENT
import dev.ayuislands.studio.session.StudioPluginsSettings
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSlider

/** Native controls consume draft and capability facts and emit only explicit gestures. */
internal class StudioPluginsControls(
    private val onEdit: (StudioPluginsEdit) -> Unit,
    private val chooseCustom: (AccentHex?) -> AccentHex?,
    private val openPlugin: (StudioPluginTarget) -> Unit,
    private val unlock: () -> Unit,
) : JPanel(BorderLayout()) {
    private val codeGlanceVisible = AtomicBooleanProperty(false)
    private val indentVisible = AtomicBooleanProperty(false)
    private val indentDetails = AtomicBooleanProperty(false)
    private val alphaVisible = AtomicBooleanProperty(false)
    private val unavailablePreset = AtomicBooleanProperty(false)
    private val ignoreSampleVisible = AtomicBooleanProperty(false)
    private val minimapSampleVisible = AtomicBooleanProperty(false)
    private val indentSampleVisible = AtomicBooleanProperty(false)
    private val locked = AtomicBooleanProperty(false)
    private val missing = AtomicBooleanProperty(false)
    private val indentEditable = AtomicBooleanProperty(false)
    private val preset = AtomicProperty<IndentPreset?>(null)
    private val presetNotice = JLabel("Saved preset unavailable")
    private val source = accentSourceCombo()
    private val alpha = JSlider(MIN_ALPHA, MAX_ALPHA, IndentPreset.DEFAULT_ALPHA)
    private val alphaReadout = JLabel()
    private val chromeBadge = newFeatureBadgeLabel(pending = false)
    private val toggles =
        TOGGLE_TITLES.mapValues { (target, title) ->
            JBCheckBox(title).apply {
                isEnabled = false
                addActionListener { if (isEnabled) emit(StudioPluginsEdit.Toggle(target, isSelected)) }
            }
        }
    private val palette = StudioAccentPalette(::selectAccent, ::selectCustom)
    private val ignoreSample = StudioPluginSample(StudioPluginSampleKind.IGNORE)
    private val minimapSample = StudioPluginSample(StudioPluginSampleKind.MINIMAP)
    private val indentSample = StudioPluginSample(StudioPluginSampleKind.INDENT)
    private var renderedAccent: AccentHex? = null
    private var isRendering = false
    private var isEditable = false

    init {
        isOpaque = false
        source.addActionListener {
            if (source.isEnabled) {
                (source.selectedItem as? ExternalAccentSource)?.let { emit(StudioPluginsEdit.AccentSource(it)) }
            }
        }
        preset.afterChange { selected ->
            if (indentEditable.get() && indentDetails.get() && selected != null) {
                emit(StudioPluginsEdit.IndentPresetChoice(selected))
            }
        }
        alpha.accessibleContext.accessibleName = "Indent Rainbow alpha"
        alpha.paintTicks = true
        alpha.majorTickSpacing = ALPHA_STEP
        alpha.addChangeListener {
            if (alpha.isEnabled && alphaVisible.get()) emit(StudioPluginsEdit.IndentAlpha(alpha.value))
        }
        add(
            panel {
                buildExternalThemeGroup()
                buildIntegrationGroup()
            },
            BorderLayout.CENTER,
        )
    }

    fun render(
        settings: StudioPluginsSettings,
        availability: StudioPluginsAvailability,
        editable: Boolean,
        chromeBadgePending: Boolean,
    ) {
        isRendering = true
        try {
            val gate = pluginGate(availability.isLicensed)
            val chosenPreset = IndentPreset.entries.firstOrNull { it.name == settings.indentPreset }
            isEditable = editable
            renderAvailability(availability, settings.isEnabled(INDENT_RAINBOW_INTEGRATION), chosenPreset, editable)
            presetNotice.toolTipText = settings.indentPreset?.let { "Saved preset: $it" }
            preset.set(chosenPreset)
            renderToggles(settings, gate, editable)
            val displayedAlpha = settings.indentAlpha.coerceIn(MIN_ALPHA, MAX_ALPHA)
            alpha.value = displayedAlpha
            alphaReadout.text = displayedAlpha.toString()
            alphaReadout.toolTipText =
                settings.indentAlpha.takeIf { it != displayedAlpha }?.let { "Saved alpha: $it" }
            alpha.applyPremiumLock(gate, indentEditable.get() && alphaVisible.get())
            source.selectedItem = ExternalAccentSource.entries.firstOrNull { it.name == settings.externalAccentSource }
            source.isEnabled = editable
            source.toolTipText = settings.externalAccentSource?.let { "Saved source: $it" }
            renderedAccent = AccentHex.of(settings.externalAccent)
            palette.render(renderedAccent, editable)
            chromeBadge.isVisible = chromeBadgePending
        } finally {
            isRendering = false
        }
    }

    private fun renderAvailability(
        availability: StudioPluginsAvailability,
        indentEnabled: Boolean,
        chosenPreset: IndentPreset?,
        editable: Boolean,
    ) {
        val integrationPresent = availability.hasCodeGlance || availability.hasIndentRainbow
        codeGlanceVisible.set(availability.hasCodeGlance)
        indentVisible.set(availability.hasIndentRainbow)
        locked.set(integrationPresent && !availability.isLicensed)
        missing.set(!integrationPresent)
        val showIndentDetails = indentEnabled || !availability.isLicensed
        indentDetails.set(availability.hasIndentRainbow && showIndentDetails)
        indentEditable.set(availability.hasIndentRainbow && availability.isLicensed && editable)
        val showAlpha = chosenPreset == null || chosenPreset == IndentPreset.CUSTOM || !availability.isLicensed
        alphaVisible.set(availability.hasIndentRainbow && showAlpha)
        unavailablePreset.set(indentDetails.get() && chosenPreset == null)
    }

    fun showPreview(view: StudioPluginsPreviewView?) {
        ignoreSampleVisible.set(view != null)
        minimapSampleVisible.set(view != null && codeGlanceVisible.get())
        indentSampleVisible.set(view != null && indentVisible.get())
        ignoreSample.render(view)
        minimapSample.render(view)
        indentSample.render(view)
    }

    private fun renderToggles(
        settings: StudioPluginsSettings,
        gate: PremiumFeatureGate,
        editable: Boolean,
    ) {
        for ((target, control) in toggles) {
            control.isSelected = settings.isEnabled(target)
            when (target) {
                EXTERNAL_CHROME_TINT -> control.applyPremiumLock(gate, editable)
                CODE_GLANCE_INTEGRATION -> control.applyPremiumLock(gate, editable && codeGlanceVisible.get())
                INDENT_RAINBOW_INTEGRATION -> control.applyPremiumLock(gate, editable && indentVisible.get())
                INDENT_ERRORS -> control.applyPremiumLock(gate, editable && indentDetails.get())
                else -> control.isEnabled = editable
            }
        }
    }

    private fun Panel.buildExternalThemeGroup() {
        group("External Theme Support") {
            row {
                cell(toggles.getValue(EXTERNAL_ENHANCEMENTS))
                    .comment("Use selected Ayu features when the active IDE theme is not Ayu.")
            }
            row { label("Allowed features") }
            row {
                cell(toggles.getValue(EXTERNAL_QUICK_SWITCHER)).widthGroup(ALLOWED_FEATURES)
                cell(toggles.getValue(EXTERNAL_GLOW))
            }
            row {
                cell(toggles.getValue(EXTERNAL_CODE_GLANCE)).widthGroup(ALLOWED_FEATURES)
                cell(toggles.getValue(EXTERNAL_INDENT_RAINBOW))
            }
            row {
                cell(toggles.getValue(EXTERNAL_CHROME_TINT)).widthGroup(ALLOWED_FEATURES)
                cell(chromeBadge)
            }
            row { comment("Configure tinted chrome surfaces on the Accent tab while an Ayu theme is active.") }
            collapsibleGroup("Accent Inheritance") {
                row {
                    cell(toggles.getValue(OVERRIDE_NATIVE_ACCENT))
                        .comment("Keep native colors when off. Selected Ayu features still use Accent source.")
                }
                row("Accent source") { cell(source) }
                row {
                    comment("Automatic uses project and language pins, then Material Theme, IDE, and External accents.")
                }
                row("External accent") {
                    cell(palette)
                        .align(Align.FILL)
                        .resizableColumn()
                        .comment("Used in Manual mode and as the final Automatic fallback")
                }
            }.expanded = false
        }
    }

    private fun Panel.buildIntegrationGroup() {
        group("Plugin Integrations") {
            row { comment(PLUGIN_LOCKED_DESCRIPTION) }.visibleIf(locked)
            row { link("Unlock Pro") { if (locked.get()) unlock() } }.visibleIf(locked)
            row { cell(toggles.getValue(IGNORE_SYNTAX_COLORS)).comment("Use Ayu colors for .ignore files") }
            row { cell(ignoreSample).align(Align.FILL).resizableColumn() }.visibleIf(ignoreSampleVisible)
            integrationRow(CODE_GLANCE_INTEGRATION, StudioPluginTarget.CODE_GLANCE, codeGlanceVisible)
            row { cell(minimapSample).align(Align.FILL).resizableColumn() }.visibleIf(minimapSampleVisible)
            integrationRow(INDENT_RAINBOW_INTEGRATION, StudioPluginTarget.INDENT_RAINBOW, indentVisible)
            row("Preset") {
                segmentedButton<IndentPreset?>(IndentPreset.entries) { text = it?.displayName.orEmpty() }.bind(preset)
            }.visibleIf(indentDetails).enabledIf(indentEditable)
            row { cell(presetNotice) }.visibleIf(unavailablePreset)
            row {
                cell(toggles.getValue(INDENT_ERRORS))
                    .comment("When off, irregular indentation blends into the color gradient")
            }.visibleIf(indentDetails)
            row("Alpha") {
                cell(alpha)
                cell(alphaReadout)
            }.visibleIf(alphaVisible)
            row { cell(indentSample).align(Align.FILL).resizableColumn() }.visibleIf(indentSampleVisible)
            row {
                comment("Install CodeGlance Pro or Indent Rainbow for premium accent color sync.")
            }.visibleIf(missing)
        }
    }

    private fun Panel.integrationRow(
        toggle: StudioPluginsToggle,
        plugin: StudioPluginTarget,
        visible: AtomicBooleanProperty,
    ) {
        row {
            cell(toggles.getValue(toggle))
            link("Plugin page") { if (visible.get()) openPlugin(plugin) }
                .component.accessibleContext.accessibleName = "${plugin.title} plugin page"
        }.visibleIf(visible)
    }

    private fun selectCustom() {
        if (!isRendering && isEditable) chooseCustom(renderedAccent)?.let(::selectAccent)
    }

    private fun selectAccent(color: AccentHex) = emit(StudioPluginsEdit.Accent(color))

    private fun emit(edit: StudioPluginsEdit) {
        if (!isRendering && isEditable) onEdit(edit)
    }

    private companion object {
        const val MIN_ALPHA = 13
        const val MAX_ALPHA = 153
        const val ALPHA_STEP = 26
        const val ALLOWED_FEATURES = "allowed-plugin-features"

        val TOGGLE_TITLES =
            mapOf(
                EXTERNAL_ENHANCEMENTS to "Enable Ayu enhancements on other themes",
                EXTERNAL_QUICK_SWITCHER to "Quick switcher",
                EXTERNAL_GLOW to "Glow",
                EXTERNAL_CODE_GLANCE to "CodeGlance Pro",
                EXTERNAL_INDENT_RAINBOW to "Indent Rainbow",
                EXTERNAL_CHROME_TINT to "Chrome tint",
                OVERRIDE_NATIVE_ACCENT to "Override native theme accent",
                CODE_GLANCE_INTEGRATION to "CodeGlance Pro viewport",
                INDENT_RAINBOW_INTEGRATION to "Indent Rainbow guides",
                INDENT_ERRORS to "Highlight indent errors",
                IGNORE_SYNTAX_COLORS to ".ignore syntax colors",
            )
    }
}

private fun accentSourceCombo(): ComboBox<ExternalAccentSource> =
    ComboBox(ExternalAccentSource.entries.toTypedArray()).apply {
        isEnabled = false
        accessibleContext.accessibleName = "Accent source"
        renderer =
            object : SimpleListCellRenderer<ExternalAccentSource>() {
                override fun customize(
                    list: JList<out ExternalAccentSource>,
                    value: ExternalAccentSource?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    text = value?.displayName ?: "Source unavailable"
                }
            }
    }

private fun pluginGate(isLicensed: Boolean): PremiumFeatureGate =
    PremiumFeatureGate(
        featureName = "Plugin integrations",
        lockedDescription = PLUGIN_LOCKED_DESCRIPTION,
        requestMessage = "Unlock plugin integrations",
        isUnlocked = isLicensed,
    )

private const val PLUGIN_LOCKED_DESCRIPTION = "Plugin integrations are a Pro feature. Preview their controls here."
