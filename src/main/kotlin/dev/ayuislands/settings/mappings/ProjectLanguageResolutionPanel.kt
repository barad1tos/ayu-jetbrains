package dev.ayuislands.settings.mappings

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.LanguageDetectionRules
import dev.ayuislands.accent.ProjectLanguageVerdict
import org.jetbrains.annotations.TestOnly
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Focused diagnostics strip for project-language accent resolution.
 *
 * The panel is deliberately state-only: [OverridesGroupBuilder] owns project
 * lookup, pending override maps, license gates, and detector reads. This class
 * formats that snapshot into compact source, scan, and action rows.
 */
internal class ProjectLanguageResolutionPanel(
    private val currentAccentHex: () -> String?,
    private val onSetFallback: (String) -> Unit,
    private val onSetForcedLanguage: (String) -> Unit,
    private val onClearForcedLanguage: () -> Unit,
    private val onClearFallback: () -> Unit,
    private val onRescan: () -> Unit,
    private val canRescanNow: () -> Boolean,
    private val languageIconForId: (String) -> Icon? = LanguageDetectionRules::iconForLanguageId,
) : JPanel() {
    private var state: State =
        State(
            verdict = ProjectLanguageVerdict.Unavailable,
            forcedLanguageId = null,
            fallbackHex = null,
            activeSource = AccentResolver.Source.GLOBAL,
            canMutate = false,
            canRescan = false,
        )

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = null
    }

    fun refresh(nextState: State) {
        state = nextState
        removeAll()

        add(
            buildTextRow(
                prefix = SOURCE_PREFIX,
                value = sourceText(nextState),
                icon = null,
            ),
        )
        add(
            buildTextRow(
                prefix = SCAN_PREFIX,
                value = scanStatusText(nextState),
                icon = iconForState(nextState),
            ),
        )
        scanDetailText(nextState)?.let { detail ->
            val weights =
                when (val verdict = nextState.verdict) {
                    is ProjectLanguageVerdict.Detected ->
                        verdict.weights?.takeIf { LanguageDetectionRules.pickDisplayEntries(it).size > 1 }
                    is ProjectLanguageVerdict.NoWinner -> verdict.weights
                    ProjectLanguageVerdict.Cold,
                    ProjectLanguageVerdict.Empty,
                    ProjectLanguageVerdict.Unavailable,
                    -> null
                }
            add(buildDetailRow(detail, weights))
        }
        val actions = actionSpecsFor(nextState)
        if (actions.isNotEmpty()) {
            add(buildActionsRow(actions))
        }

        revalidate()
        repaint()
    }

    private fun iconForState(state: State): Icon? {
        state.forcedLanguageId?.let { return languageIconForId(it) }
        return when (val verdict = state.verdict) {
            is ProjectLanguageVerdict.Detected -> languageIconForId(verdict.languageId)
            else -> null
        }
    }

    @TestOnly
    internal fun currentSummaryForTest(): String = summaryLinesFor(state).joinToString("\n")

    @TestOnly
    internal fun labelsForTest(): List<Triple<Icon?, String, String?>> =
        descendants()
            .mapNotNull { component ->
                when (component) {
                    is JBLabel -> Triple(component.icon, component.text, component.toolTipText)
                    is ActionLink -> Triple(null, component.text, component.toolTipText)
                    else -> null
                }
            }.toList()

    @TestOnly
    internal fun triggerActionForTest(text: String) {
        val action =
            actionSpecsFor(state).firstOrNull { it.text == text }
                ?: error("Action '$text' is not rendered")
        action.onClick()
    }

    private fun summaryLinesFor(state: State): List<String> =
        buildList {
            add("$SOURCE_PREFIX: ${sourceText(state)}")
            add("$SCAN_PREFIX: ${scanStatusText(state)}")
            scanDetailText(state)?.let(::add)
        }

    private fun scanStatusText(state: State): String {
        state.forcedLanguageId?.let { languageId ->
            return "${displayName(languageId)} (manual)"
        }
        return when (val verdict = state.verdict) {
            is ProjectLanguageVerdict.Detected -> detectedText(verdict)
            is ProjectLanguageVerdict.NoWinner -> polyglotText(state)
            ProjectLanguageVerdict.Cold -> "Detection pending"
            ProjectLanguageVerdict.Empty -> "No project languages detected"
            ProjectLanguageVerdict.Unavailable -> "Project language detection unavailable"
        }
    }

    private fun scanDetailText(state: State): String? =
        when (val verdict = state.verdict) {
            is ProjectLanguageVerdict.Detected -> null
            is ProjectLanguageVerdict.NoWinner -> proportionsText(verdict.weights)
            else -> null
        }

    private fun detectedText(verdict: ProjectLanguageVerdict.Detected): String {
        val proportions =
            verdict.weights
                ?.let(LanguageDetectionRules::pickTopLanguagesForDisplay)
                ?.takeIf { it.isNotEmpty() }
        return proportions ?: displayName(verdict.languageId)
    }

    private fun percentFor(
        languageId: String,
        weights: Map<String, Long>,
    ): Int? =
        LanguageDetectionRules
            .pickDisplayEntries(weights)
            .firstOrNull { it.id == languageId }
            ?.percent

    private fun proportionsText(weights: Map<String, Long>): String {
        val entries = LanguageDetectionRules.pickDisplayEntries(weights)
        if (entries.isEmpty()) return "unresolved"
        return entries.joinToString(ENTRY_SEPARATOR) { entry ->
            "${entry.label} ${entry.percent}%"
        }
    }

    private fun sourceText(state: State): String {
        val label = AccentResolver.sourceLabel(state.activeSource)
        return when (state.activeSource) {
            AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE ->
                sourceWithDetail("Language override", manualLanguageDetail(state.forcedLanguageId))
            AccentResolver.Source.LANGUAGE_OVERRIDE ->
                sourceWithDetail(label, detectedLanguageDetail(state))
            AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE ->
                sourceWithDetail(label, manualLanguageDetail(state.forcedLanguageId) ?: detectedLanguageDetail(state))
            AccentResolver.Source.PROJECT_FALLBACK ->
                if (state.fallbackHex != null) "$label ${state.fallbackHex}" else label
            else -> label
        }
    }

    private fun detectedLanguageDetail(state: State): String? {
        val verdict = state.verdict as? ProjectLanguageVerdict.Detected ?: return null
        val percent =
            verdict.weights
                ?.let { weights -> percentFor(verdict.languageId, weights) }
                ?.let { ", $it%" }
                ?: ""
        return displayName(verdict.languageId) + percent
    }

    private fun actionSpecsFor(state: State): List<ActionSpec> {
        val specs = mutableListOf<ActionSpec>()
        if (state.canMutate) {
            specs += mutationActionSpecs(state)
        }
        if (state.canRescan) {
            specs +=
                ActionSpec(
                    text = RESCAN_LABEL,
                    tooltip = RESCAN_TOOLTIP,
                    onClick = {
                        if (canRescanNow()) {
                            onRescan()
                        }
                    },
                )
        }
        return specs
    }

    private fun mutationActionSpecs(state: State): List<ActionSpec> {
        val specs = mutableListOf<ActionSpec>()
        state.forcedLanguageId?.let {
            specs += ActionSpec(CLEAR_FORCED_LANGUAGE_LABEL, onClearForcedLanguage)
        }
        state.fallbackHex?.let {
            specs += ActionSpec(CLEAR_FALLBACK_LABEL, onClearFallback)
        }
        if (state.forcedLanguageId == null) {
            specs += verdictActionSpecs(state)
        }
        return specs
    }

    private fun verdictActionSpecs(state: State): List<ActionSpec> =
        when (val verdict = state.verdict) {
            is ProjectLanguageVerdict.Detected ->
                listOf(forceLanguageSpec(verdict.languageId))
            is ProjectLanguageVerdict.NoWinner ->
                noWinnerActionSpecs(verdict, state)
            ProjectLanguageVerdict.Cold,
            ProjectLanguageVerdict.Empty,
            ProjectLanguageVerdict.Unavailable,
            -> emptyList()
        }

    private fun noWinnerActionSpecs(
        verdict: ProjectLanguageVerdict.NoWinner,
        state: State,
    ): List<ActionSpec> {
        val specs = mutableListOf<ActionSpec>()
        if (state.fallbackHex == null && state.canSetFallbackToCurrentAccent) {
            specs +=
                ActionSpec(
                    text = SET_FALLBACK_LABEL,
                    tooltip = SET_FALLBACK_TOOLTIP,
                    onClick = {
                        currentAccentHex()?.let(onSetFallback)
                    },
                )
        }
        uniqueTopLanguageId(verdict.weights)?.let { languageId ->
            specs += forceLanguageSpec(languageId)
        }
        return specs
    }

    private fun forceLanguageSpec(languageId: String): ActionSpec =
        ActionSpec(
            text = "Force ${displayName(languageId)}",
            tooltip = "Treat this project as ${displayName(languageId)} for language accent resolution.",
            onClick = {
                onSetForcedLanguage(languageId)
            },
        )

    private fun uniqueTopLanguageId(weights: Map<String, Long>): String? {
        val entries =
            LanguageDetectionRules
                .pickDisplayEntries(weights)
                .filter { it.id != null }
        val topId = entries.firstOrNull()?.id ?: return null
        val topWeight = weights[topId] ?: return null
        val hasTopTie =
            entries.drop(1).any { entry ->
                entry.id?.let { languageId -> weights[languageId] == topWeight } == true
            }
        return topId.takeUnless { hasTopTie }
    }

    private fun buildTextRow(
        prefix: String,
        value: String,
        icon: Icon?,
    ): JPanel =
        buildRow().apply {
            add(
                JBLabel(safeLabelText("$prefix:")).apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
            )
            add(
                JBLabel(safeLabelText(value), icon, SwingConstants.LEADING).apply {
                    foreground = UIUtil.getLabelForeground()
                },
            )
        }

    private fun buildDetailRow(
        detail: String,
        weights: Map<String, Long>?,
    ): JPanel =
        buildRow().apply {
            val entries = weights?.let(LanguageDetectionRules::pickDisplayEntries).orEmpty()
            if (entries.isEmpty()) {
                add(
                    JBLabel(safeLabelText(detail), null, SwingConstants.LEADING).apply {
                        foreground = UIUtil.getContextHelpForeground()
                    },
                )
            } else {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        add(
                            JBLabel(ENTRY_SEPARATOR.trim()).apply {
                                foreground = UIUtil.getContextHelpForeground()
                            },
                        )
                    }
                    add(
                        JBLabel(
                            safeLabelText("${entry.label} ${entry.percent}%"),
                            entry.id?.let(languageIconForId),
                            SwingConstants.LEADING,
                        ).apply {
                            foreground = UIUtil.getContextHelpForeground()
                        },
                    )
                }
            }
        }

    private fun buildActionsRow(actions: List<ActionSpec>): JPanel =
        buildRow().apply {
            actions.forEach { action ->
                add(
                    ActionLink(safeLabelText(action.text)) {
                        action.onClick()
                    }.apply {
                        toolTipText = action.tooltip?.let(::safeLabelText)
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    },
                )
            }
        }

    private fun buildRow(): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(ENTRY_GAP_PX), 0)).apply {
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(ROW_GAP_PX)
            isOpaque = false
        }

    private fun displayName(languageId: String): String = LanguageDetectionRules.displayNameForLanguageId(languageId)

    internal data class State(
        val verdict: ProjectLanguageVerdict,
        val forcedLanguageId: String?,
        val fallbackHex: String?,
        val activeSource: AccentResolver.Source,
        val canMutate: Boolean,
        val canRescan: Boolean,
        val canSetFallbackToCurrentAccent: Boolean = true,
    )

    private data class ActionSpec(
        val text: String,
        val onClick: () -> Unit,
        val tooltip: String? = null,
    )

    companion object {
        const val SET_FALLBACK_LABEL: String = "Use current accent as fallback"
        const val CLEAR_FORCED_LANGUAGE_LABEL: String = "Clear forced language"
        const val CLEAR_FALLBACK_LABEL: String = "Clear fallback"
        const val RESCAN_LABEL: String = "Rescan"
        const val RESCAN_TOOLTIP: String = "Re-detect the dominant language of this project"

        private const val SOURCE_PREFIX: String = "Accent source"
        private const val SCAN_PREFIX: String = "Detected in this project"
        private const val SET_FALLBACK_TOOLTIP: String =
            "Use the current accent when this project has no dominant language."
        private const val ENTRY_GAP_PX: Int = 8
        private const val ENTRY_SEPARATOR: String = " · "
        private const val ROW_GAP_PX: Int = 2
    }
}

private fun polyglotText(state: ProjectLanguageResolutionPanel.State): String {
    val applied =
        when (state.activeSource) {
            AccentResolver.Source.PROJECT_FALLBACK -> "Project fallback applies."
            AccentResolver.Source.PROJECT_OVERRIDE -> "Project override applies."
            else -> "Global accent applies."
        }
    return "Polyglot — no single dominant language. $applied"
}

private fun manualLanguageDetail(languageId: String?): String? =
    languageId?.let { "${LanguageDetectionRules.displayNameForLanguageId(it)}, manual" }

private fun sourceWithDetail(
    label: String,
    detail: String?,
): String = detail?.let { "$label ($it)" } ?: label

private fun safeLabelText(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

private fun Component.descendants(): Sequence<Component> =
    sequence {
        yield(this@descendants)
        if (this@descendants is Container) {
            this@descendants.components.forEach { child ->
                yieldAll(child.descendants())
            }
        }
    }
