package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.properties.AtomicProperty
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.conflict.ConflictType
import dev.ayuislands.glow.GlowTabMode
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider

/** Familiar Chrome and editor-element groups; raw choices remain independent of their capability masks. */
internal class AccentElementControls(
    private val sample: AyuIslandsPreviewPanel,
    private val onEdit: (AccentPanelEvent) -> Unit,
) {
    private val toggles =
        AccentElementId.entries.associateWith { id ->
            val title = if (id == AccentElementId.PANEL_BORDER) "Panel borders" else id.displayName
            JBCheckBox(title).apply {
                addActionListener {
                    if (isEnabled) {
                        onEdit(
                            if (id.group == AccentGroup.CHROME) {
                                AccentPanelEvent.ChromeSurface(id, isSelected)
                            } else {
                                AccentPanelEvent.Element(id, isSelected)
                            },
                        )
                    }
                }
                if (id.group != AccentGroup.CHROME) {
                    addMouseListener(
                        object : MouseAdapter() {
                            override fun mouseEntered(event: MouseEvent) {
                                sample.highlightedElement = id
                                sample.updatePreview()
                            }

                            override fun mouseExited(event: MouseEvent) {
                                sample.highlightedElement = null
                                sample.updatePreview()
                            }
                        },
                    )
                }
            }
        }
    private val master = JBCheckBox("Enable chrome tinting")
    private val intensity = JSlider(MIN_INTENSITY, MAX_INTENSITY)
    private val intensityValue = JLabel()
    private val toolbarReason = JLabel()
    private val tabSelection = AtomicProperty<GlowTabMode?>(null)
    private val heightSelection = AtomicProperty<Int?>(null)
    private val tabVisible = AtomicBooleanProperty(false)
    private val underlineVisible = AtomicBooleanProperty(false)
    private val editing = AtomicBooleanProperty(false)
    private val sync = JBCheckBox("Sync with glow width")
    private val scope = JBCheckBox("Highlight bracket scope on gutter")
    private val storedGeometry = JLabel()
    private var tab: SegmentedButton<GlowTabMode?>? = null
    private var height: SegmentedButton<Int?>? = null

    init {
        master.addActionListener { if (master.isEnabled) onEdit(AccentPanelEvent.ChromeEnabled(master.isSelected)) }
        intensity.addChangeListener {
            if (intensity.isEnabled) {
                onEdit(
                    AccentPanelEvent.ChromeIntensity(intensity.value),
                )
            }
        }
        tabSelection.afterChange { value -> value?.let { onEdit(AccentPanelEvent.TabMode(it)) } }
        heightSelection.afterChange { value -> value?.let { onEdit(AccentPanelEvent.UnderlineHeight(it)) } }
        sync.addActionListener { if (sync.isEnabled) onEdit(AccentPanelEvent.UnderlineSync(sync.isSelected)) }
        scope.addActionListener { if (scope.isEnabled) onEdit(AccentPanelEvent.BracketScope(scope.isSelected)) }
    }

    fun buildChrome(panel: Panel) {
        panel.row { cell(master) }
        for ((id, checkbox) in toggles.filterKeys { it.group == AccentGroup.CHROME }) {
            panel.row { cell(checkbox) }
            if (id == AccentElementId.MAIN_TOOLBAR) {
                panel.row { cell(toolbarReason).resizableColumn().align(Align.FILL) }
            }
        }
        panel.row("Intensity (%):") {
            cell(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(intensity, BorderLayout.CENTER)
                    add(intensityValue, BorderLayout.EAST)
                },
            ).resizableColumn().align(Align.FILL)
        }
    }

    fun buildElements(panel: Panel) {
        panel.row { label("Per-element toggles").bold() }
        for (group in listOf(AccentGroup.VISUAL, AccentGroup.INTERACTIVE)) {
            panel.row { label(if (group == AccentGroup.VISUAL) "Visual" else "Interactive").bold() }
            for (checkbox in toggles.filterKeys { it.group == group }.values) {
                panel.row { cell(checkbox) }
            }
        }
        panel
            .row {
                link("Enable all") { onEdit(AccentPanelEvent.AllElements(true)) }
                link("Disable all") { onEdit(AccentPanelEvent.AllElements(false)) }
            }.enabledIf(editing)
        panel.separator()
        panel.row { label("Tab underline").bold() }
        panel
            .row {
                comment("Minimal = underline only, Full = underline + tinted background, Off = neutral")
            }.visibleIf(tabVisible)
        panel
            .row("Tab accent style") {
                tab =
                    segmentedButton<GlowTabMode?>(GlowTabMode.entries) { text = it?.displayName.orEmpty() }
                        .bind(tabSelection)
            }.visibleIf(tabVisible)
        panel
            .row("Underline thickness") {
                height = segmentedButton<Int?>(HEIGHTS) { text = "${it}px" }.bind(heightSelection)
            }.visibleIf(underlineVisible)
        panel.row { cell(sync) }.visibleIf(underlineVisible)
        panel.row { cell(storedGeometry).resizableColumn().align(Align.FILL) }
        panel.separator()
        panel.row { label("Bracket scope").bold() }
        panel.row {
            cell(scope).comment("Show accent-colored scope stripe when caret is on a bracket")
        }
    }

    fun render(view: AccentPanelView) {
        val allowed = view.isEditable && view.isLicensed
        editing.set(allowed)
        master.isSelected = view.settings.chrome.enabled
        master.isEnabled = allowed
        intensity.isEnabled = allowed
        intensity.value = view.settings.chrome.intensity
        intensityValue.text =
            view.settings.chrome.intensity
                .toString()
        retainCaption(intensityValue)
        for ((id, checkbox) in toggles) {
            checkbox.isSelected =
                if (id.group == AccentGroup.CHROME) {
                    view.settings.chrome.surfaces
                        .getValue(id)
                } else {
                    view.settings.elements.toggles
                        .getValue(id)
                }
            val conflict = view.context.conflicts[id]?.takeIf { it.type == ConflictType.BLOCK }
            val nativeHeader = id == AccentElementId.MAIN_TOOLBAR && !view.context.isCustomHeaderActive
            checkbox.isEnabled = allowed && conflict == null && !nativeHeader
            checkbox.toolTipText =
                when {
                    !view.isLicensed -> "Requires Ayu Islands Pro"
                    conflict != null -> "Controlled by ${conflict.pluginDisplayName}"
                    nativeHeader -> view.context.toolbarUnavailableReason
                    else -> null
                }
        }
        toolbarReason.text =
            if (view.context.isCustomHeaderActive) "" else view.context.toolbarUnavailableReason.orEmpty()
        retainCaption(toolbarReason)
        renderGeometry(view, allowed)
    }

    private fun renderGeometry(
        view: AccentPanelView,
        allowed: Boolean,
    ) {
        val elements = view.settings.elements
        val mode = GlowTabMode.entries.firstOrNull { it.name == elements.tabMode }
        tabSelection.set(mode)
        heightSelection.set(elements.underlineHeight.takeIf { it in HEIGHTS })
        tabVisible.set(view.context.isIslandsUi)
        underlineVisible.set(!view.context.isIslandsUi && (mode != GlowTabMode.OFF || !view.isLicensed))
        tab?.enabled(allowed)
        height?.enabled(allowed && !elements.underlineSync)
        sync.isSelected = elements.underlineSync
        sync.isEnabled = allowed && view.context.glowWidth != null
        sync.toolTipText = if (view.context.glowWidth == null) "Enable glow to sync" else null
        scope.isSelected = elements.bracketScope
        scope.isEnabled = allowed
        storedGeometry.text =
            buildList {
                if (mode == null) add("Stored tab style: ${elements.tabMode ?: "none"}")
                if (elements.underlineHeight !in HEIGHTS) add("Stored underline: ${elements.underlineHeight}px")
            }.joinToString(" · ")
        retainCaption(storedGeometry)
    }

    private companion object {
        const val MIN_INTENSITY = 10
        const val MAX_INTENSITY = 50
        val HEIGHTS = listOf(2, 4, 6, 8)
    }
}
