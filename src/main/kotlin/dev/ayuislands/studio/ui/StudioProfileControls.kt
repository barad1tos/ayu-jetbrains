package dev.ayuislands.studio.ui

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.ayuislands.settings.newFeatureBadgeLabel
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.StudioProfileChange
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioProfileRow
import dev.ayuislands.studio.model.StudioProfileView
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton

/** Native controls emit identities only; the host allocates new drafts and dispatches session intents. */
internal class StudioProfileControls(
    private val select: (StudioProfileChoice) -> Unit,
    private val preview: (PreviewMode) -> Unit,
    actions: (() -> Unit)? = null,
) : JPanel() {
    private val picker = ProfilePicker()
    private val current = JToggleButton("Current")
    private val draft = JToggleButton("Draft")
    private val profileBadge = newFeatureBadgeLabel(true).apply { isVisible = false }
    private val previewBadge = newFeatureBadgeLabel(true).apply { isVisible = false }
    private var badgesInitialized = false
    private var rendered: StudioProfileView? = null
    private var isRendering = false

    init {
        val spacing = StudioUiTokens.spacing()
        layout = BorderLayout(0, spacing.groupGap)
        val modes = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0))
        val selection = ButtonGroup()
        for (button in listOf(current, draft)) {
            selection.add(button)
            modes.add(button)
        }
        val fields = JPanel(StudioResponsiveLayout(spacing))
        fields.add(heading("Profile", picker, profileBadge))
        fields.add(picker)
        fields.add(heading("Preview", current, previewBadge))
        fields.add(modes)
        add(fields, BorderLayout.CENTER)
        if (actions != null) {
            val menu =
                JButton("…").apply {
                    accessibleContext.accessibleName = "Profile actions"
                    toolTipText = "Profile actions"
                    addActionListener { actions() }
                }
            add(menu, BorderLayout.EAST)
        }
        picker.renderer = ProfileRenderer()
        picker.addItemListener { event ->
            if (!isRendering && event.stateChange == ItemEvent.SELECTED) selected()
        }
        current.addActionListener { selectPreview(PreviewMode.CURRENT) }
        draft.addActionListener { selectPreview(PreviewMode.DRAFT) }
    }

    fun render(view: StudioProfileView) {
        isRendering = true
        try {
            rendered = view
            picker.model = DefaultComboBoxModel(view.rows.toTypedArray())
            picker.selectedItem = view.rows.singleOrNull { it.choice == view.selected }
            picker.isEnabled = view.rows.any { it.isEnabled }
            current.isSelected = view.preview == PreviewMode.CURRENT
            draft.isSelected = view.preview == PreviewMode.DRAFT
            current.isEnabled = view.isPreviewEnabled
            draft.isEnabled = view.isPreviewEnabled
            describeSelection()
            picker.accessibleContext.accessibleDescription = "Current appearance: ${view.currentName}"
        } finally {
            isRendering = false
        }
    }

    /** Row indicators remain for this dialog after accepted metadata retires the navigation badges. */
    fun renderBadges(pending: Set<String>) {
        if (badgesInitialized) return
        profileBadge.isVisible = "work-profiles" in pending
        previewBadge.isVisible = "settings-draft-preview" in pending
        badgesInitialized = true
    }

    private fun heading(
        title: String,
        control: JComponent,
        badge: JLabel,
    ): JPanel =
        JPanel(FlowLayout(FlowLayout.LEADING, StudioUiTokens.spacing().fieldGap, 0)).apply {
            isOpaque = false
            add(JLabel(title).apply { labelFor = control })
            add(badge)
        }

    private fun selected() {
        val row = picker.selectedItem as? StudioProfileRow ?: return
        val view = rendered ?: return
        if (!picker.isEnabled || !row.isEnabled) {
            render(view)
            return
        }
        describeSelection()
        if (row.choice != view.selected) select(row.choice)
    }

    private fun selectPreview(mode: PreviewMode) {
        val view = rendered ?: return
        if (!isRendering && view.isPreviewEnabled && mode != view.preview) preview.invoke(mode)
    }

    private fun describeSelection() {
        val row = picker.selectedItem as? StudioProfileRow
        val description = row?.let(::profileDescription) ?: "Profile"
        picker.accessibleContext.accessibleName = description
        // The fixed prefix also keeps HTML-shaped user names literal in native tooltips.
        picker.toolTipText = description
    }
}

/** Bound the visible name in native font columns; full names remain in the model and accessibility text. */
private class ProfilePicker : JComboBox<StudioProfileRow>() {
    override fun getMinimumSize(): Dimension = measuredSize(super.getMinimumSize(), MINIMUM_NAME_COLUMNS)

    override fun getPreferredSize(): Dimension = measuredSize(super.getPreferredSize(), PREFERRED_NAME_COLUMNS)

    private fun measuredSize(
        native: Dimension,
        columns: Int,
    ): Dimension {
        val chrome = native.height + insets.left + insets.right
        val name = getFontMetrics(font).charWidth('m') * columns
        return Dimension(minOf(native.width, name + chrome), native.height)
    }

    private companion object {
        const val MINIMUM_NAME_COLUMNS = 8
        const val PREFERRED_NAME_COLUMNS = 20
    }
}

private class ProfileRenderer : ColoredListCellRenderer<StudioProfileRow>() {
    override fun getListCellRendererComponent(
        list: JList<out StudioProfileRow>,
        value: StudioProfileRow?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ): Component {
        isEnabled = value?.isEnabled == true
        return super.getListCellRendererComponent(list, value, index, selected, hasFocus)
    }

    override fun customizeCellRenderer(
        list: JList<out StudioProfileRow>,
        value: StudioProfileRow?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) return
        append(displayName(value))
        value.label.qualifier?.let { append(" · $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        if (value.isCurrent) append(" · Current", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        for (change in value.pending) append(" · ${pendingName(change)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        toolTipText = profileDescription(value)
    }
}

private fun displayName(row: StudioProfileRow): String = row.label.pendingName ?: row.label.name

private fun profileDescription(row: StudioProfileRow): String =
    "Profile: ${displayName(row)}${row.label.qualifier?.let { " · $it" }.orEmpty()}"

private fun pendingName(change: StudioProfileChange): String =
    when (change) {
        StudioProfileChange.CREATE -> "Create pending"
        StudioProfileChange.RENAME -> "Rename pending"
        StudioProfileChange.UPDATE -> "Update pending"
        StudioProfileChange.DELETE -> "Delete pending"
    }
