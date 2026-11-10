package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.conflict.ConflictType
import dev.ayuislands.settings.mappings.MappingsControls
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JPanel

/** The Accent section composes existing native views and dispatches only explicit draft gestures. */
internal class AccentControls(
    expanded: Set<AccentPanelGroup>,
    mappings: MappingsControls,
    private val onEdit: (AccentPanelEvent) -> Unit,
    private val onAction: (AccentPanelAction) -> Unit,
) : JPanel(BorderLayout()) {
    private var rendered: AccentPanelView? = null
    private var isRendering = false
    private val sample = AyuIslandsPreviewPanel().apply { previewGlowEnabled = false }
    private val appearance = AccentAppearanceControls(::edit)
    private val elements = AccentElementControls(sample, ::edit)
    private val locked = AtomicBooleanProperty(false)
    private val palette =
        AccentSwatchGrid(
            { hex -> rendered?.context?.variant?.let { edit(AccentPanelEvent.Color(it, hex)) } },
            { rendered?.context?.variant?.let { action(AccentPanelAction.Custom(it)) } },
        )
    private val source = JLabel()
    private val rawAccent = JLabel()
    private val shuffle =
        ActionLink("Shuffle") {
            rendered?.context?.variant?.let { action(AccentPanelAction.Shuffle(it)) }
        }
    private val reset =
        ActionLink("Reset accent to default…") {
            rendered?.context?.variant?.let { action(AccentPanelAction.Reset(it)) }
        }

    init {
        mappings.setTrailing(
            panel {
                row { cell(appearance.shared.getValue(AccentSharedToggle.PROJECT_ICON_ACCENT)) }
                row {
                    comment(
                        "Projects without an override take their accent from .idea/icon.png when they open. " +
                            "Delete a row above to re-derive it on the next open.",
                    )
                }
            },
        )
        add(
            panel {
                group("Accent Color") {
                    row { cell(sample.createComponent()).resizableColumn().align(Align.FILL) }
                    row { cell(palette).resizableColumn().align(Align.FILL) }
                    row { cell(shuffle) }
                    row { cell(rawAccent).resizableColumn().align(Align.FILL) }
                    row { cell(source).resizableColumn().align(Align.FILL) }
                }
                group("Theme Synchronization") {
                    row {
                        cell(appearance.shared.getValue(AccentSharedToggle.SYNC_EDITOR_SCHEME))
                            .comment("Off respects your custom editor color scheme.")
                    }
                }
                disclosure(AccentPanelGroup.SYSTEM, expanded, appearance::buildSystem)
                row { cell(mappings).resizableColumn().align(Align.FILL) }
                row { comment("Chrome tinting, accent rotation and element customization require Ayu Islands Pro.") }
                    .visibleIf(locked)
                row { link("Unlock") { action(AccentPanelAction.Unlock) } }.visibleIf(locked)
                disclosure(AccentPanelGroup.CHROME, expanded, elements::buildChrome)
                collapsibleGroup("Quick Switcher") {
                    row { cell(appearance.shared.getValue(AccentSharedToggle.QUICK_SWITCHER_WIDGET)) }
                    row { comment("The widget in the main toolbar switches theme variants and accents.") }
                }
                disclosure(AccentPanelGroup.ROTATION, expanded, appearance::buildRotation)
                disclosure(AccentPanelGroup.ELEMENTS, expanded, elements::buildElements)
                row { cell(reset) }
            },
            BorderLayout.CENTER,
        )
        minimumSize = Dimension(0, 0)
    }

    fun render(
        view: AccentPanelView,
        preview: AccentPreviewInput,
    ) {
        isRendering = true
        try {
            rendered = view
            locked.set(!view.isLicensed)
            appearance.render(view)
            elements.render(view)
            val canChoose = view.isEditable && !view.settings.effectiveFollowSystem
            palette.render(view.settings.colors[view.context.variant], canChoose)
            shuffle.isEnabled = canChoose
            reset.isEnabled = view.isEditable
            rawAccent.text = "Stored accent: ${view.settings.colors[view.context.variant] ?: "Theme default"}"
            source.text = "Accent source: ${AccentResolver.sourceLabel(view.mappings.context.resolution.activeSource)}"
            retainCaption(rawAccent)
            retainCaption(source)
            sample.previewAccentHex = preview.hex
            sample.previewToggles = preview.elements
            sample.previewConflicts = preview.conflicts
            sample.updatePreview()
            revalidate()
            repaint()
        } finally {
            isRendering = false
        }
    }

    /** Host-confirmed color/reset results use the same latest-view guard as native gestures. */
    fun edit(event: AccentPanelEvent) {
        val view = rendered ?: return
        if (!isRendering && accepts(view, event)) onEdit(event)
    }

    private fun action(request: AccentPanelAction) {
        if (isRendering) return
        val view = rendered ?: return
        val allowed =
            when (request) {
                is AccentPanelAction.Expanded, AccentPanelAction.Unlock -> {
                    true
                }

                is AccentPanelAction.Custom, is AccentPanelAction.Shuffle -> {
                    view.isEditable &&
                        !view.settings.effectiveFollowSystem
                }

                else -> {
                    view.isEditable
                }
            }
        if (allowed) onAction(request)
    }

    private fun Panel.disclosure(
        group: AccentPanelGroup,
        expanded: Set<AccentPanelGroup>,
        build: (Panel) -> Unit,
    ) {
        val disclosure = collapsibleGroup(group.title) { build(this) }
        disclosure.expanded = group in expanded
        disclosure.addExpandedListener { action(AccentPanelAction.Expanded(group, it)) }
    }
}

private fun accepts(
    view: AccentPanelView,
    event: AccentPanelEvent,
): Boolean {
    if (!view.isEditable) return false
    return when (event) {
        is AccentPanelEvent.FollowSystem, is AccentPanelEvent.SystemPolicy -> {
            view.context.isSystemAccentSupported
        }

        is AccentPanelEvent.Color -> {
            !view.settings.effectiveFollowSystem
        }

        is AccentPanelEvent.ResetColor -> {
            true
        }

        is AccentPanelEvent.SharedToggle -> {
            !event.target.isPremium || view.isLicensed
        }

        is AccentPanelEvent.NightTheme -> {
            view.context.isSystemAppearanceSupported
        }

        is AccentPanelEvent.Element -> {
            acceptsElement(view, event.id)
        }

        is AccentPanelEvent.ChromeSurface -> {
            acceptsElement(view, event.id)
        }

        is AccentPanelEvent.UnderlineSync -> {
            view.isLicensed && view.context.glowWidth != null
        }

        else -> {
            view.isLicensed
        }
    }
}

private fun acceptsElement(
    view: AccentPanelView,
    id: AccentElementId,
): Boolean =
    view.isLicensed &&
        view.context.conflicts[id]?.type != ConflictType.BLOCK &&
        (id != AccentElementId.MAIN_TOOLBAR || view.context.isCustomHeaderActive)
