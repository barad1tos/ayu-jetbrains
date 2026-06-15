package dev.ayuislands.settings.mappings

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.LanguageDetectionRules
import dev.ayuislands.accent.ProjectLanguageVerdict
import org.jetbrains.annotations.TestOnly
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Focused diagnostics row for project-language accent resolution.
 *
 * The panel is deliberately state-only: [OverridesGroupBuilder] owns project
 * lookup, pending override maps, license gates, and detector reads. This class
 * formats that snapshot into one summary label plus optional action labels.
 */
internal class ProjectLanguageResolutionPanel(
    private val currentAccentHex: () -> String?,
    private val onSetFallback: (String) -> Unit,
    private val onSetForcedLanguage: (String) -> Unit,
    private val onClearForcedLanguage: () -> Unit,
    private val onClearFallback: () -> Unit,
    private val onRescan: () -> Unit,
    private val canRescanNow: () -> Boolean,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(ENTRY_GAP_PX), 0)) {
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
        isOpaque = false
        border = null
    }

    fun refresh(nextState: State) {
        state = nextState
        removeAll()
        add(JBLabel(summaryFor(nextState)).apply { foreground = UIUtil.getContextHelpForeground() })
        actionSpecsFor(nextState).forEach { action ->
            add(buildActionLabel(action))
        }
        revalidate()
        repaint()
    }

    @TestOnly
    internal fun currentSummaryForTest(): String = summaryFor(state)

    @TestOnly
    internal fun labelsForTest(): List<Triple<Icon?, String, String?>> =
        components.filterIsInstance<JBLabel>().map { label ->
            Triple(label.icon, label.text, label.toolTipText)
        }

    @TestOnly
    internal fun clickLabelForTest(text: String) {
        val label =
            components
                .filterIsInstance<JBLabel>()
                .firstOrNull { it.text == text }
                ?: error("Label '$text' is not rendered")
        val event =
            MouseEvent(
                label,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                0,
                0,
                1,
                false,
            )
        label.mouseListeners
            .filter { it.javaClass.name.startsWith("dev.ayuislands") }
            .forEach { it.mouseClicked(event) }
    }

    private fun summaryFor(state: State): String {
        val source = sourceText(state)
        state.forcedLanguageId?.let { languageId ->
            return "Forced language: ${displayName(languageId)} - using $source"
        }
        return when (val verdict = state.verdict) {
            is ProjectLanguageVerdict.Detected ->
                "Detected: ${detectedText(verdict)} - using $source"
            is ProjectLanguageVerdict.NoWinner ->
                "No dominant language: ${proportionsText(verdict.weights)} - using $source"
            ProjectLanguageVerdict.Cold -> "Detection pending - using $source"
            ProjectLanguageVerdict.Empty -> "No project languages detected - using $source"
            ProjectLanguageVerdict.Unavailable -> "Project language detection unavailable - using $source"
        }
    }

    private fun detectedText(verdict: ProjectLanguageVerdict.Detected): String {
        val percent =
            verdict.weights
                ?.let { weights -> percentFor(verdict.languageId, weights) }
                ?.let { " $it%" }
                ?: ""
        return displayName(verdict.languageId) + percent
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
        return if (state.activeSource == AccentResolver.Source.PROJECT_FALLBACK && state.fallbackHex != null) {
            "$label ${state.fallbackHex}"
        } else {
            label
        }
    }

    private fun actionSpecsFor(state: State): List<ActionSpec> {
        val specs = mutableListOf<ActionSpec>()
        if (state.canMutate) {
            specs += mutationActionSpecs(state)
        }
        if (state.canRescan) {
            specs +=
                ActionSpec(RESCAN_LABEL) {
                    if (canRescanNow()) {
                        onRescan()
                    }
                }
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
                noWinnerActionSpecs(verdict, state.fallbackHex)
            ProjectLanguageVerdict.Cold,
            ProjectLanguageVerdict.Empty,
            ProjectLanguageVerdict.Unavailable,
            -> emptyList()
        }

    private fun noWinnerActionSpecs(
        verdict: ProjectLanguageVerdict.NoWinner,
        fallbackHex: String?,
    ): List<ActionSpec> {
        val specs = mutableListOf<ActionSpec>()
        if (fallbackHex == null && currentAccentHex() != null) {
            specs +=
                ActionSpec(SET_FALLBACK_LABEL) {
                    currentAccentHex()?.let(onSetFallback)
                }
        }
        topLanguageId(verdict.weights)?.let { languageId ->
            specs += forceLanguageSpec(languageId)
        }
        return specs
    }

    private fun forceLanguageSpec(languageId: String): ActionSpec =
        ActionSpec("Force ${displayName(languageId)}") {
            onSetForcedLanguage(languageId)
        }

    private fun topLanguageId(weights: Map<String, Long>): String? =
        LanguageDetectionRules
            .pickDisplayEntries(weights)
            .firstOrNull { it.id != null }
            ?.id

    private fun buildActionLabel(action: ActionSpec): JBLabel =
        JBLabel(action.text, null, SwingConstants.LEADING).apply {
            foreground = UIUtil.getContextHelpForeground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        action.onClick()
                    }
                },
            )
        }

    private fun displayName(languageId: String): String = LanguageDetectionRules.displayNameForLanguageId(languageId)

    internal data class State(
        val verdict: ProjectLanguageVerdict,
        val forcedLanguageId: String?,
        val fallbackHex: String?,
        val activeSource: AccentResolver.Source,
        val canMutate: Boolean,
        val canRescan: Boolean,
    )

    private data class ActionSpec(
        val text: String,
        val onClick: () -> Unit,
    )

    companion object {
        const val SET_FALLBACK_LABEL: String = "Set fallback to current accent"
        const val CLEAR_FORCED_LANGUAGE_LABEL: String = "Clear forced language"
        const val CLEAR_FALLBACK_LABEL: String = "Clear fallback"
        const val RESCAN_LABEL: String = "Rescan"

        private const val ENTRY_GAP_PX: Int = 12
        private const val ENTRY_SEPARATOR: String = " - "
    }
}
