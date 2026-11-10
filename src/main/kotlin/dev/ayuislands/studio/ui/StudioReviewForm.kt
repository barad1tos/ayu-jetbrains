package dev.ayuislands.studio.ui

import com.intellij.openapi.ui.ComboBox
import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.CatalogApprovals
import dev.ayuislands.studio.model.CatalogStage
import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.ExtensionResolution
import dev.ayuislands.studio.model.FieldResolution
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.NativeResolution
import dev.ayuislands.studio.model.PolicyResolution
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.session.StudioCatalogPolicy
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/** Native inputs are read once on confirmation; catalog validation reuses the domain policy. */
internal class StudioReviewForm(
    items: StudioList<AcceptanceReview>,
    private val session: StudioSessionRecord,
    private val document: StudioDocument,
    onChange: () -> Unit,
    edit: (DraftId) -> Unit,
) {
    val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    var error: String? = null
        private set
    private val fields = mutableListOf<() -> FieldResolution>()
    private val policies = mutableListOf<() -> PolicyResolution>()
    private val native = mutableListOf<() -> NativeResolution>()
    private val extensions = mutableListOf<() -> ExtensionResolution>()
    private val catalog = StudioCatalogReview(session, document, onChange, edit)

    init {
        for (item in items) {
            val row =
                when (item) {
                    is AcceptanceReview.HiddenDraft -> catalog.hidden(item.id)
                    is AcceptanceReview.Catalog -> catalog.item(item.item)
                    is AcceptanceReview.LiveSession -> catalog.liveSession()
                    is AcceptanceReview.Field -> {
                        val conflict = item.conflict
                        val (content, choice) =
                            conflictRow(
                                "Setting: ${settingName(conflict.address.field.name)}",
                                fieldText(conflict.latest),
                                fieldText(conflict.mine),
                            )
                        fields.add { FieldResolution(conflict, choice()) }
                        content
                    }
                    is AcceptanceReview.Native -> {
                        val change = item.change
                        val (content, choice) =
                            conflictRow(
                                "Editor setting: ${change.identity}",
                                fieldText(change.actual),
                                fieldText(change.requested),
                            )
                        native.add { NativeResolution(change, choice()) }
                        content
                    }
                    is AcceptanceReview.Policy -> {
                        val (content, choice) =
                            conflictRow(
                                "System accent policy",
                                item.conflict.latest.name,
                                item.conflict.mine.name,
                            )
                        policies.add { PolicyResolution(item.conflict, choice()) }
                        content
                    }
                    is AcceptanceReview.Extensions -> {
                        val (content, choice) =
                            conflictRow(
                                "Additional ${item.conflict.domain.name.lowercase()} settings",
                                "Keep the latest additional settings",
                                "Use the additional settings from this draft",
                            )
                        extensions.add { ExtensionResolution(item.conflict, choice()) }
                        content
                    }
                }
            panel.add(row)
        }
    }

    fun isResolved(): Boolean = catalog.isResolved()

    fun readDecision(): StudioReviewDecision.Continue? {
        if (!catalog.isResolved()) {
            error = "Choose a resolution for each item before continuing."
            return null
        }
        val intents = catalog.intents()
        var work = session.work
        for (intent in intents.filterIsInstance<SessionIntent.Catalog>()) {
            when (val staged = StudioCatalogPolicy.stage(document, work, intent.command)) {
                is CatalogStage.Staged -> work = staged.work
                is CatalogStage.Rejected -> {
                    error =
                        if (staged.error == dev.ayuislands.studio.model.CatalogError.INVALID_NAME) {
                            "Enter a nonempty, single-line profile name."
                        } else {
                            "This profile changed. Return to settings and review it again."
                        }
                    return null
                }
            }
        }
        error = null
        return StudioReviewDecision.Continue(
            AcceptanceChoices(
                catalog = CatalogApprovals(StudioList(catalog.deletions()), StudioList(catalog.retained())),
                fields = StudioList(fields.map { it() }),
                policy = policies.singleOrNull()?.invoke(),
                native = StudioList(native.map { it() }),
                extensions = StudioList(extensions.map { it() }),
            ),
            intents,
        )
    }
}

internal fun reviewRow(title: String): JPanel {
    val spacing = StudioUiTokens.spacing()
    return JPanel(BorderLayout(0, spacing.fieldGap)).apply {
        border = BorderFactory.createEmptyBorder(spacing.fieldGap, spacing.groupGap, spacing.groupGap, spacing.groupGap)
        add(JLabel(title), BorderLayout.NORTH)
    }
}

internal fun reviewActions(): JPanel = JPanel(FlowLayout(FlowLayout.LEADING, StudioUiTokens.spacing().fieldGap, 0))

internal fun editButton(
    draft: DraftId,
    edit: (DraftId) -> Unit,
): JButton = JButton("Edit").apply { addActionListener { edit(draft) } }

private const val COMPARISON_COLUMNS = 48

private fun conflictRow(
    title: String,
    latest: String,
    mine: String,
): Pair<JComponent, () -> ConflictChoice> {
    val choice = ComboBox(arrayOf("Keep latest", "Use my change"))
    val content = reviewRow(title)
    content.add(
        JTextArea("Latest: $latest\nYour change: $mine").apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = JLabel().font
            rows = 2
            columns = COMPARISON_COLUMNS
        },
        BorderLayout.CENTER,
    )
    content.add(choice, BorderLayout.SOUTH)
    choice.accessibleContext.accessibleName = title
    return content to { if (choice.selectedIndex == 1) ConflictChoice.USE_MINE else ConflictChoice.KEEP_LATEST }
}

private fun settingName(name: String): String =
    name.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar(Char::titlecase)

private fun fieldText(value: FieldValue): String =
    when (value) {
        FieldValue.Absent -> "Default"
        is FieldValue.Present -> rawText(value.value)
    }

private fun rawText(value: RawValue): String =
    when (value) {
        RawValue.Null -> "None"
        is RawValue.Text -> value.value
        is RawValue.BooleanValue -> if (value.value) "On" else "Off"
        is RawValue.Integer -> value.value.toString()
        is RawValue.LongValue -> value.value.toString()
        is RawValue.FloatBits -> Float.fromBits(value.bits).toString()
        is RawValue.OrderedMap -> value.entries.joinToString { "${it.key}: ${rawText(it.value)}" }
        is RawValue.OrderedSet -> value.values.joinToString()
        is RawValue.OrderedList -> value.values.joinToString { rawText(it) }
        is RawValue.Bytes -> "Saved custom value"
    }
