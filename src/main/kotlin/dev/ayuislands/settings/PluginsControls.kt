package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.properties.AtomicProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.indent.IndentPreset
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import com.intellij.ui.dsl.builder.panel as dialogPanel

/** Native controls consume explicit facts; persistence, chooser dialogs and plugin actions stay with adapters. */
internal class PluginsControls(
    private val onEvent: (PluginsPanelEvent) -> Unit,
) : JPanel(BorderLayout()) {
    private var rendered: PluginsPanelView? = null
    private var isRendering = false
    private val codeGlanceVisible = AtomicBooleanProperty(false)
    private val indentVisible = AtomicBooleanProperty(false)
    private val indentDetails = AtomicBooleanProperty(false)
    private val alphaVisible = AtomicBooleanProperty(false)
    private val locked = AtomicBooleanProperty(false)
    private val missing = AtomicBooleanProperty(false)
    private val preset = AtomicProperty<IndentPreset?>(null)
    private val checkboxes = PluginsToggle.entries.associateWith { JCheckBox(it.title) }
    private val sourceCombo = ComboBox<String>()
    private val palette =
        AccentSwatchGrid({ emit(PluginsPanelEvent.Accent(it)) }, { emit(PluginsPanelEvent.CustomAccent) })
    private val alpha = JSlider(MIN_ALPHA, MAX_ALPHA, IndentPreset.DEFAULT_ALPHA)
    private val alphaValue = JLabel()
    private val rawPreset = JLabel()
    private var presetButtons: SegmentedButton<IndentPreset?>? = null
    private var chromeBadge: Cell<JLabel>? = null
    private val ignoreSample = PluginsSample(PluginSampleKind.IGNORE)
    private val codeGlanceSample = PluginsSample(PluginSampleKind.CODE_GLANCE)
    private val indentSample = PluginsSample(PluginSampleKind.INDENT)

    init {
        isOpaque = false
        sourceCombo.accessibleContext.accessibleName = "Accent source"
        alpha.accessibleContext.accessibleName = "Indent Rainbow alpha"
        alpha.paintTicks = true
        alpha.majorTickSpacing = ALPHA_TICK
        checkboxes.forEach { (target, checkbox) ->
            checkbox.addActionListener { emit(PluginsPanelEvent.Toggle(target, checkbox.isSelected)) }
        }
        sourceCombo.addActionListener {
            ExternalAccentSource.entries.firstOrNull { it.displayName == sourceCombo.selectedItem }?.let {
                emit(PluginsPanelEvent.AccentSource(it))
            }
        }
        preset.afterChange { selected -> selected?.let { emit(PluginsPanelEvent.Preset(it)) } }
        alpha.addChangeListener { emit(PluginsPanelEvent.Alpha(alpha.value)) }
        add(dialogPanel { buildContent() }.apply { isOpaque = false }, BorderLayout.CENTER)
    }

    fun render(
        view: PluginsPanelView,
        preview: PluginsPreviewView,
    ) {
        isRendering = true
        try {
            rendered = view
            val availability = view.availability
            val requested = view.settings
            val selectedPreset = IndentPreset.entries.firstOrNull { it.name == requested.integrations.indentPreset }
            renderAvailability(view, selectedPreset)
            checkboxes.forEach { (target, checkbox) ->
                checkbox.isSelected = PluginsEditing.selected(requested, target)
                checkbox.isEnabled = view.isEditable && (!target.premium || availability.isLicensed)
            }
            renderSource(requested.external.accentSource)
            sourceCombo.isEnabled = view.isEditable
            palette.render(requested.external.accent, view.isEditable)
            preset.set(selectedPreset)
            presetButtons?.enabled(view.isEditable && availability.isLicensed)
            rawPreset.text = if (selectedPreset == null) "Unrecognized preset" else ""
            rawPreset.toolTipText = requested.integrations.indentPreset ?: "Preset is not set"
            alpha.value = requested.integrations.indentAlpha.coerceIn(MIN_ALPHA, MAX_ALPHA)
            alpha.isEnabled = view.isEditable && availability.isLicensed
            alphaValue.text = requested.integrations.indentAlpha.toString()
            alpha.toolTipText = "Alpha: ${requested.integrations.indentAlpha} (supported range $MIN_ALPHA–$MAX_ALPHA)"
            val badgePending = CHROME_BADGE in view.pendingBadges
            chromeBadge?.component?.text = if (badgePending) "New" else ""
            chromeBadge?.visible(badgePending)
            ignoreSample.render(preview)
            codeGlanceSample.render(preview)
            indentSample.render(preview)
        } finally {
            isRendering = false
        }
    }

    private fun renderAvailability(
        view: PluginsPanelView,
        selectedPreset: IndentPreset?,
    ) {
        val availability = view.availability
        codeGlanceVisible.set(availability.hasCodeGlance)
        indentVisible.set(availability.hasIndentRainbow)
        indentDetails.set(
            availability.hasIndentRainbow && (view.settings.integrations.indentEnabled || !availability.isLicensed),
        )
        alphaVisible.set(
            availability.hasIndentRainbow &&
                (selectedPreset == null || selectedPreset == IndentPreset.CUSTOM || !availability.isLicensed),
        )
        locked.set(!availability.isLicensed && (availability.hasCodeGlance || availability.hasIndentRainbow))
        missing.set(!availability.hasCodeGlance && !availability.hasIndentRainbow)
    }

    private fun renderSource(raw: String?) {
        val known = ExternalAccentSource.entries.firstOrNull { it.name == raw }
        val selected = known?.displayName ?: raw ?: "Not set"
        val choices = ExternalAccentSource.entries.map { it.displayName }.toMutableList()
        if (selected !in choices) choices.add(selected)
        sourceCombo.model = DefaultComboBoxModel(choices.toTypedArray())
        sourceCombo.selectedItem = selected
        sourceCombo.toolTipText = raw ?: "Accent source is not set"
    }

    private fun Panel.buildContent() {
        row { comment("Tune how Ayu colors integrate with compatible plugins.") }
        group("External Theme Support") {
            row {
                cell(
                    checkboxes.getValue(PluginsToggle.EXTERNAL_ENABLED),
                ).comment("Use selected Ayu features when the active IDE theme is not Ayu.")
            }
            row { label("Allowed features") }
            buildAllowedFeatures()
            collapsibleGroup("Accent Inheritance") {
                row {
                    cell(checkboxes.getValue(PluginsToggle.OVERRIDE_ACCENT))
                        .comment("Keep native colors when off. Selected Ayu features still use Accent source.")
                }
                row("Accent source") { cell(sourceCombo) }
                row {
                    comment(
                        "Automatic uses project/language pins, Material Theme accent, IDE accent, " +
                            "then External accent fallback.",
                    )
                }
                row("External accent") {
                    cell(palette).comment("Used in Manual mode and as the final Automatic fallback")
                }
            }.expanded = false
        }
        group("Plugin Integrations") {
            row {
                comment(
                    "Plugin integrations are a Pro feature. " +
                        "Preview CodeGlance Pro and Indent Rainbow sync controls here.",
                )
            }.visibleIf(locked)
            row { cell(ActionLink("Unlock") { emit(PluginsPanelEvent.Unlock) }) }.visibleIf(locked)
            row { cell(checkboxes.getValue(PluginsToggle.IGNORE_COLORS)).comment("Use Ayu colors for .ignore files") }
            row { cell(ignoreSample).align(Align.FILL).resizableColumn() }
            row {
                cell(checkboxes.getValue(PluginsToggle.CODE_GLANCE))
                cell(ActionLink("Plugin page") { emit(PluginsPanelEvent.PluginPage(PluginTarget.CODE_GLANCE)) })
            }.visibleIf(codeGlanceVisible)
            row { cell(codeGlanceSample).align(Align.FILL).resizableColumn() }.visibleIf(codeGlanceVisible)
            row {
                cell(checkboxes.getValue(PluginsToggle.INDENT_RAINBOW))
                cell(ActionLink("Plugin page") { emit(PluginsPanelEvent.PluginPage(PluginTarget.INDENT_RAINBOW)) })
            }.visibleIf(indentVisible)
            row("Preset") {
                presetButtons =
                    segmentedButton<IndentPreset?>(
                        IndentPreset.entries,
                    ) { text = it?.displayName.orEmpty() }.bind(preset)
            }.visibleIf(indentDetails)
            row { cell(rawPreset) }.visibleIf(indentDetails)
            row {
                cell(checkboxes.getValue(PluginsToggle.INDENT_ERRORS))
                    .comment("When off, lines with irregular indentation blend into the color gradient")
            }.visibleIf(indentDetails)
            row("Alpha") {
                cell(alpha)
                cell(alphaValue)
            }.visibleIf(alphaVisible)
            row { cell(indentSample).align(Align.FILL).resizableColumn() }.visibleIf(indentVisible)
            row {
                comment(
                    "Install CodeGlance Pro or Indent Rainbow for premium accent color sync.",
                )
            }.visibleIf(missing)
        }
    }

    private fun Panel.buildAllowedFeatures() {
        val columns =
            listOf(
                dialogPanel {
                    row { cell(checkboxes.getValue(PluginsToggle.EXTERNAL_QUICK_SWITCHER)) }
                    row { cell(checkboxes.getValue(PluginsToggle.EXTERNAL_CODE_GLANCE)) }
                    row {
                        cell(checkboxes.getValue(PluginsToggle.EXTERNAL_CHROME_TINT))
                            .comment("Use the chrome surfaces configured in Accent (Pro)", FEATURE_COMMENT_WIDTH)
                        chromeBadge = newFeatureBadge(false)
                    }
                },
                dialogPanel {
                    row { cell(checkboxes.getValue(PluginsToggle.EXTERNAL_GLOW)) }
                    row { cell(checkboxes.getValue(PluginsToggle.EXTERNAL_INDENT_RAINBOW)) }
                },
            )
        val columnHeight = columns.maxOf { it.preferredSize.height }
        val wrapping =
            JPanel(WrapLayout(FlowLayout.LEADING, JBUI.scale(FEATURE_GAP), 0)).apply {
                isOpaque = false
                columns.forEach { column ->
                    add(
                        JPanel(BorderLayout()).apply {
                            isOpaque = false
                            add(column, BorderLayout.NORTH)
                            preferredSize = Dimension(column.preferredSize.width, columnHeight)
                            minimumSize = preferredSize
                        },
                    )
                }
            }
        row { cell(wrapping).resizableColumn().align(Align.FILL) }
    }

    private fun emit(event: PluginsPanelEvent) {
        if (isRendering) return
        val view = rendered ?: return
        val allowed =
            when (event) {
                PluginsPanelEvent.Unlock, is PluginsPanelEvent.PluginPage -> {
                    true
                }

                is PluginsPanelEvent.Toggle -> {
                    view.isEditable &&
                        (!event.target.premium || view.availability.isLicensed)
                }

                is PluginsPanelEvent.Preset, is PluginsPanelEvent.Alpha -> {
                    view.isEditable &&
                        view.availability.isLicensed
                }

                else -> {
                    view.isEditable
                }
            }
        if (allowed) onEvent(event)
    }

    private companion object {
        const val MIN_ALPHA = 13
        const val MAX_ALPHA = 153
        const val ALPHA_TICK = 26
        const val FEATURE_GAP = 12
        const val FEATURE_COMMENT_WIDTH = 24
        const val CHROME_BADGE = "chrome-tint-external-themes"
    }
}
