package dev.ayuislands.studio.session

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.UiDefaultsTarget
import dev.ayuislands.accent.canForceOverride
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.accent.elements.BracketFadeAppearance
import dev.ayuislands.accent.elements.BracketFadeManager
import dev.ayuislands.accent.elements.BracketMatchElement
import dev.ayuislands.accent.elements.CaretRowElement
import dev.ayuislands.accent.elements.InlayHintsElement
import dev.ayuislands.accent.elements.LinksElement
import dev.ayuislands.accent.elements.MatchingTagElement
import dev.ayuislands.accent.elements.ProgressBarElement
import dev.ayuislands.accent.elements.ScrollbarElement
import dev.ayuislands.accent.elements.SearchResultsElement
import dev.ayuislands.accent.isElementEnabled
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AccentElementSettings
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.theme.AyuEditorSchemeBinder
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeCheckpoints.EnabledCheckpoint
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.theme.SchemeSource
import java.awt.Color
import javax.swing.SwingUtilities

/** Adds element-owned scheme requests and retains native surfaces under the enclosing Accent transaction. */
internal class StudioAccentElements(
    private val references: StudioAccentReferences,
    private val schemes: StudioSchemeOperation,
    private val elements: List<AccentElement>,
) {
    fun capture(
        settings: AccentElementSettings,
        context: AccentContext?,
        accent: Color?,
        view: FontSchemeView,
        entitlement: LicenseEntitlement,
    ): StudioAccentWrite {
        check(SwingUtilities.isEventDispatchThread()) { "Accent elements must be captured on EDT" }
        require(entitlement != LicenseEntitlement.UNKNOWN) { "Accent entitlement is unconfirmed" }
        val target = UiDefaultsTarget()
        val selected = elements.filter { it.id.group != AccentGroup.CHROME }
        require(selected.map { it.id }.distinct().size == selected.size) { "Duplicate Accent element" }
        val licensed = entitlement == LicenseEntitlement.LICENSED
        val active =
            view.active.takeIf {
                context?.variant?.let { variant -> AyuEditorSchemeBinder.matchesVariant(it.name, variant) } ==
                    true
            }
        // Native provider getters run before capturing raw defaults that any element may overwrite.
        val plans = selected.map { plan(it, settings, context, accent, licensed) }
        check(target.isCurrent()) { "Accent element target changed during observation" }
        if (context == null) return captureInactive(plans, view, target)
        val fields = mutableListOf<StudioAccentWrite>()
        for (plan in plans) {
            if (context == AccentContext.External && plan.element is SearchResultsElement) continue
            val enabled =
                EditorSchemeOverrides.checkpoints.prepareEnabled(
                    plan.element.id,
                    settings.toggles.getValue(plan.element.id),
                )
            val editorFields = captureEditor(plan, enabled, active, view, accent)
            fields += StudioAccentCell.enabled(references, enabled, editorFields)
            plan.ui.forEach { (key, value) ->
                fields += StudioAccentDefaults.prepareRaw(references, key, value, target)
            }
            if (plan.element !is BracketMatchElement || plan.action == ElementAction.OBSERVE) continue
            if (plan.action != ElementAction.CLEAR && active == null) continue
            val color = accent.takeIf { plan.action == ElementAction.APPLY }
            val bracket = BracketFadeManager.prepare(color, BracketFadeAppearance(settings.bracketScope))
            fields += StudioAccentCell.bracket(references, bracket, editorFields)
        }
        return StudioAccentFields.captured(fields, target::isCurrent)
    }

    private fun captureInactive(
        plans: List<ElementPlan>,
        view: FontSchemeView,
        target: UiDefaultsTarget,
    ): StudioAccentWrite {
        val fields = mutableListOf<StudioAccentWrite>()
        for (plan in plans) {
            val previous = schemes.receipts.mapTo(hashSetOf()) { it.id }
            for (scheme in view.schemes) {
                schemes.capture(
                    scheme,
                    emptyMap(),
                    emptySet(),
                    schemeOwner = EditorSchemeOwner.Element(plan.element.id),
                )
            }
            plan.ui.forEach { (key, value) ->
                fields += StudioAccentDefaults.prepareRaw(references, key, value, target)
            }
            if (plan.element is BracketMatchElement) {
                fields +=
                    StudioAccentCell.bracket(
                        references,
                        BracketFadeManager.prepare(null),
                        schemes.receipts.filter { it.id !in previous },
                    )
            }
        }
        return StudioAccentFields.captured(fields, target::isCurrent)
    }

    private fun plan(
        element: AccentElement,
        settings: AccentElementSettings,
        context: AccentContext?,
        accent: Color?,
        licensed: Boolean,
    ): ElementPlan {
        require(
            element is InlayHintsElement ||
                element is CaretRowElement ||
                element is ProgressBarElement ||
                element is ScrollbarElement ||
                element is LinksElement ||
                element is BracketMatchElement ||
                element is SearchResultsElement ||
                element is MatchingTagElement,
        ) { "Accent element has no detached capture: ${element.id}" }
        val action =
            when {
                context == null -> ElementAction.CLEAR

                context == AccentContext.External -> ElementAction.OBSERVE

                !isElementEnabled(settings.toggles.getValue(element.id), licensed) -> ElementAction.CLEAR

                ConflictRegistry.getConflictFor(element.id) != null &&
                    !canForceOverride(settings.forceOverrides, element.id, licensed) -> ElementAction.CLEAR

                else -> ElementAction.APPLY
            }
        return ElementPlan(element, action, uiValues(element, action, accent))
    }

    private fun captureEditor(
        plan: ElementPlan,
        enabled: EnabledCheckpoint,
        active: EditorColorsScheme?,
        view: FontSchemeView,
        accent: Color?,
    ): List<PortableReceipt> {
        if (plan.element is SearchResultsElement) return emptyList()
        val previous = schemes.receipts.mapTo(hashSetOf()) { it.id }
        for (scheme in view.schemes) {
            val requested =
                when (plan.action) {
                    ElementAction.CLEAR -> {
                        emptyMap()
                    }

                    ElementAction.APPLY -> {
                        if (scheme === active) {
                            editorValues(plan, scheme, checkNotNull(accent) { "Accent handoff is unconfirmed" })
                        } else {
                            null
                        }
                    }

                    ElementAction.OBSERVE -> {
                        null
                    }
                }
            if (requested == null && !enabled.rearms) continue
            schemes.captureElement(
                scheme,
                enabled,
                requested,
                if (scheme === active) SchemeSource.Active else SchemeSource.Named(scheme.name),
            )
        }
        return schemes.receipts.filter { it.id !in previous }
    }

    private fun uiValues(
        element: AccentElement,
        action: ElementAction,
        accent: Color?,
    ): Map<String, Color?> {
        if (action == ElementAction.OBSERVE) return emptyMap()
        if (action == ElementAction.CLEAR) {
            val keys =
                when (element) {
                    is ProgressBarElement -> element.uiKeys
                    is ScrollbarElement -> element.uiKeys
                    is LinksElement -> element.uiKeys
                    is SearchResultsElement -> element.uiKeys
                    else -> emptyList()
                }
            return keys.associateWith { null }
        }
        val color = checkNotNull(accent) { "Accent handoff is unconfirmed" }
        return when (element) {
            is ProgressBarElement -> element.uiColors(color)
            is ScrollbarElement -> element.uiColors(color)
            is LinksElement -> element.uiColors(color)
            is SearchResultsElement -> element.uiColors(color, element.observeBackgrounds(color))
            else -> emptyMap()
        }
    }

    private fun editorValues(
        plan: ElementPlan,
        scheme: EditorColorsScheme,
        accent: Color,
    ): Map<SchemeEntry, SchemeValue> =
        when (val element = plan.element) {
            is InlayHintsElement -> {
                attributes(element.editorAttributes(scheme, accent))
            }

            is CaretRowElement -> {
                colors(element.editorColors(accent))
            }

            is ProgressBarElement -> {
                colors(element.editorColors(accent))
            }

            is ScrollbarElement -> {
                colors(element.editorColors(plan.ui.mapValues { checkNotNull(it.value) }))
            }

            is LinksElement -> {
                colors(element.editorColors(accent)) +
                    attributes(element.editorAttributes(scheme, accent))
            }

            is BracketMatchElement -> {
                attributes(element.editorAttributes(scheme, accent))
            }

            is MatchingTagElement -> {
                attributes(element.editorAttributes(scheme, accent))
            }

            else -> {
                error("Accent element has no editor values: ${element.id}")
            }
        }

    private fun colors(values: Sequence<Pair<ColorKey, Color>>): Map<SchemeEntry, SchemeValue> =
        values.associate { (key, value) -> SchemeEntry.ColorEntry(key) to SchemeValue.ColorValue(value) }

    private fun colors(values: Map<ColorKey, Color>): Map<SchemeEntry, SchemeValue> =
        colors(values.asSequence().map { it.toPair() })

    private fun attributes(values: Sequence<Pair<TextAttributesKey, TextAttributes>>): Map<SchemeEntry, SchemeValue> =
        values.associate { (key, value) -> SchemeEntry.AttributesEntry(key) to SchemeValue.AttributesValue(value) }

    private fun attributes(values: Map<TextAttributesKey, TextAttributes>): Map<SchemeEntry, SchemeValue> =
        attributes(values.asSequence().map { it.toPair() })

    private enum class ElementAction { APPLY, CLEAR, OBSERVE }

    private class ElementPlan(
        val element: AccentElement,
        val action: ElementAction,
        val ui: Map<String, Color?>,
    )
}
