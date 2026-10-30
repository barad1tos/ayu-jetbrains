package dev.ayuislands.studio.prototype

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.settings.AyuIslandsPreviewPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.LayoutManager
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel

private const val STUDIO_GAP = 12
private const val SMALL_GAP = 6
private const val PROFILE_NAME_COLUMNS = 12
private const val SCENE_WIDTH = 600
private const val SCENE_HEIGHT = 360
private const val STACK_BREAKPOINT = 800
private const val SURFACE_WIDTH = 165
private const val DETAILS_ROWS = 3
private const val GLOW_WIDTH = 8
private const val SELECTION_LINE = 2
private const val YOURS_SAMPLE = "Yours (sample)"

/** Native design preview whose only editable state belongs to the supplied session. */
internal class StudioPrototypePanel(
    val session: StudioPrototypeSession,
    private val onStateChanged: () -> Unit = {},
) : JPanel(BorderLayout(0, JBUI.scale(STUDIO_GAP))) {
    private val sections =
        listOf(
            StudioPrototypeSection.GLOW,
            StudioPrototypeSection.SYNTAX,
            StudioPrototypeSection.VCS,
            StudioPrototypeSection.FONT,
            StudioPrototypeSection.ACCENT,
        )
    private val surfaces =
        named(
            JList(arrayOf("Editor frame / Glow", "Editor colors / Syntax", "Version control", "Typography", "Accent")),
            "Surface selection",
        )
    private val preview = AyuIslandsPreviewPanel()
    private val details = textArea("Appearance details", DETAILS_ROWS)
    private val status = textArea("Preview status", 2)
    private val pendingDifferences = textArea("Pending differences", DETAILS_ROWS)
    private val profileBrowser = named(JComboBox<String>(), "Profile browser")
    private val activate = named(JButton("Activate"), "Activate profile")
    private val profileName = named(JTextField(PROFILE_NAME_COLUMNS), "Profile name")
    private val update = named(JButton("Update profile"), "Update profile")
    private val quickSwitcher = named(JButton("Preview Quick Switcher"), "Preview Quick Switcher")
    private val outcome = named(JComboBox(StudioPrototypeOutcome.entries.toTypedArray()), "Preview result")
    private val profileDetails = textArea("Profile sections", 2)
    private val membership = sections.associateWith { named(JCheckBox(), "Include ${it.displayName}") }
    private val inspector = StudioPrototypeInspector(session, ::changed)
    private var isShowingCurrent = false
    private var selectedSurfaceIndex = 0
    private var isRefreshing = false
    private var editMessage: String? = null

    val preferredFocusComponent: JComponent = surfaces

    val canApply: Boolean
        get() =
            session.hasPendingChanges ||
                session.isPartiallyApplied ||
                session.lastResult == StudioPrototypeResult.REJECTED

    init {
        border = JBUI.Borders.empty(STUDIO_GAP)
        add(header(), BorderLayout.NORTH)
        val body = JPanel(StudioSceneLayout())
        body.add(scene())
        body.add(inspector)
        val scroll =
            JBScrollPane(body, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER)
        scroll.border = JBUI.Borders.empty()
        scroll.viewport.background = background
        add(scroll, BorderLayout.CENTER)
        add(footer(), BorderLayout.SOUTH)
        bindCommands()
        refresh()
    }

    fun applyPreview(): StudioPrototypeResult {
        val result = session.apply(StudioPrototypeOutcome.entries[outcome.selectedIndex])
        changed()
        return result
    }

    fun discardPending() {
        session.discardPending()
        changed()
    }

    private fun header(): JPanel {
        val title = JLabel("Ayu Studio")
        title.font = title.font.deriveFont(Font.BOLD, title.font.size2D + 2)
        val create = named(JButton("Create profile…"), "Create profile")
        create.addActionListener {
            val result = session.stageCreateProfile(profileName.text)
            editMessage = result.takeUnless { it == StudioPrototypeEditResult.STAGED }?.message
            refresh()
            onStateChanged()
        }
        return vertical(
            flow(title, JLabel("Design preview"), quickSwitcher),
            textArea("Preview boundary", 2).apply {
                text = "Changes stay in this preview. Your IDE settings are not changed."
            },
            flow(JLabel("Profile:"), profileBrowser, activate, update),
            flow(JLabel("New profile name:").apply { labelFor = profileName }, profileName, create),
            profileDetails,
        )
    }

    private fun scene(): JPanel {
        val current = named(JRadioButton("Current"), "Current")
        val draft = named(JRadioButton("Draft", true), "Draft")
        ButtonGroup().apply {
            add(current)
            add(draft)
        }
        current.addActionListener {
            isShowingCurrent = true
            refreshAppearance()
        }
        draft.addActionListener {
            isShowingCurrent = false
            refreshAppearance()
        }
        surfaces.selectionMode = ListSelectionModel.SINGLE_SELECTION
        surfaces.background = background
        surfaces.foreground = JLabel().foreground
        surfaces.cellRenderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component {
                    val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val appearance = if (isShowingCurrent) session.current else session.draft
                    border =
                        if (isSelected) {
                            JBUI.Borders.customLine(Color.decode(appearance.accentHex), 0, SELECTION_LINE, 0, 0)
                        } else {
                            JBUI.Borders.emptyLeft(SELECTION_LINE)
                        }
                    return component
                }
            }
        surfaces.selectedIndex = selectedSurfaceIndex
        surfaces.preferredSize = JBUI.size(SURFACE_WIDTH, 0)
        surfaces.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                if (surfaces.isSelectionEmpty) {
                    surfaces.selectedIndex = selectedSurfaceIndex
                } else {
                    selectedSurfaceIndex = surfaces.selectedIndex
                    inspector.refresh(sections[selectedSurfaceIndex])
                }
            }
        }
        val sample = JPanel(BorderLayout(0, JBUI.scale(STUDIO_GAP)))
        sample.add(preview.createComponent(), BorderLayout.CENTER)
        sample.add(details, BorderLayout.SOUTH)
        val composition = JPanel(BorderLayout(JBUI.scale(STUDIO_GAP), 0))
        composition.add(surfaces, BorderLayout.WEST)
        composition.add(sample, BorderLayout.CENTER)
        return named(JPanel(BorderLayout(0, JBUI.scale(STUDIO_GAP))), "Sample workspace").apply {
            preferredSize = JBUI.size(SCENE_WIDTH, SCENE_HEIGHT)
            minimumSize = Dimension(0, 0)
            border =
                BorderFactory.createCompoundBorder(
                    JBUI.Borders.customLine(JBColor.border()),
                    JBUI.Borders.empty(STUDIO_GAP),
                )
            add(flow(JLabel("Sample workspace"), current, draft), BorderLayout.NORTH)
            add(composition, BorderLayout.CENTER)
            add(membershipPanel(), BorderLayout.SOUTH)
        }
    }

    private fun membershipPanel(): JPanel {
        val choices = JPanel(GridLayout(0, 2, JBUI.scale(SMALL_GAP), 0))
        membership.forEach { (section, checkbox) ->
            checkbox.addActionListener {
                if (!isRefreshing) {
                    session.setIncluded(section, checkbox.isSelected)
                    changed()
                }
            }
            choices.add(checkbox)
        }
        return JPanel(BorderLayout()).apply {
            add(JLabel("Included in this profile"), BorderLayout.NORTH)
            add(choices, BorderLayout.CENTER)
        }
    }

    private fun footer(): JPanel {
        outcome.renderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component {
                    val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    text =
                        when (value) {
                            StudioPrototypeOutcome.SUCCESS -> "Success"
                            StudioPrototypeOutcome.FONT_UNAVAILABLE -> "Font unavailable"
                            StudioPrototypeOutcome.REJECTED -> "Rejected"
                            else -> ""
                        }
                    return component
                }
            }
        return vertical(
            flow(JLabel("Preview result:").apply { labelFor = outcome }, outcome),
            status,
            pendingDifferences,
        )
    }

    private fun bindCommands() {
        profileBrowser.addActionListener { if (!isRefreshing) refreshProfileDetails() }
        activate.addActionListener { activateProfile(profileBrowser.selectedIndex) }
        update.addActionListener {
            editMessage = session.stageUpdateProfile().takeUnless { it == StudioPrototypeEditResult.STAGED }?.message
            refresh()
            onStateChanged()
        }
        quickSwitcher.addActionListener {
            quickSwitcher.componentPopupMenu = switcherMenu()
            if (quickSwitcher.isShowing) {
                quickSwitcher.componentPopupMenu.show(quickSwitcher, 0, quickSwitcher.height)
            }
        }
    }

    private fun activateProfile(index: Int) {
        val name = if (index == 0) null else session.profiles[index - 1].name
        session.activateProfile(name, StudioPrototypeOutcome.entries[outcome.selectedIndex])
        changed()
    }

    private fun switcherMenu(): JPopupMenu {
        val menu = JPopupMenu("Ayu Studio — preview")
        if (session.hasPendingChanges) {
            menu.add(
                JMenuItem("Review changes in Studio").apply { addActionListener { surfaces.requestFocusInWindow() } },
            )
            menu.addSeparator()
        }
        for ((index, profile) in session.profiles.withIndex()) {
            val included = profile.includedSections.joinToString { it.displayName }
            val item = named(JMenuItem("${profile.name} — $included"), "Activate ${profile.name}")
            item.isEnabled = session.isProjectAvailable
            item.toolTipText =
                if (session.isProjectAvailable) {
                    "Activate this sample profile in preview"
                } else {
                    "Open a project to activate a profile in preview"
                }
            item.addActionListener { activateProfile(index + 1) }
            menu.add(item)
        }
        menu.addSeparator()
        menu.add(JMenuItem("Yours (sample)").apply { addActionListener { activateProfile(0) } })
        return menu
    }

    private fun changed() {
        editMessage = null
        refresh()
        onStateChanged()
    }

    private fun refresh() {
        isRefreshing = true
        try {
            val browsed = profileBrowser.selectedItem
            val names = listOf(YOURS_SAMPLE) + session.profiles.map { it.name }
            profileBrowser.model = DefaultComboBoxModel(names.toTypedArray())
            profileBrowser.selectedIndex = names.indexOf(browsed).coerceAtLeast(0)
            update.isEnabled = session.activeProfileName != null
            membership.forEach { (section, checkbox) ->
                checkbox.isSelected = section in session.includedSections
                checkbox.text = "${section.displayName} — ${if (checkbox.isSelected) "Included" else "Keep mine"}"
            }
            inspector.refresh(sections[selectedSurfaceIndex])
            refreshProfileDetails()
            refreshAppearance()
            refreshDifferences()
            val message = listOfNotNull(session.statusMessage, editMessage).distinct().joinToString(" · ")
            status.text =
                if (session.lastResult == StudioPrototypeResult.READY || message.contains("in preview")) {
                    message
                } else {
                    "$message (in preview)"
                }
        } finally {
            isRefreshing = false
        }
    }

    private fun refreshProfileDetails() {
        val selected = session.profiles.getOrNull(profileBrowser.selectedIndex - 1)
        val included = selected?.includedSections?.joinToString { it.displayName } ?: "Your sample choices"
        val activeName = session.activeProfileName ?: YOURS_SAMPLE
        profileDetails.text = "Active: $activeName · Browsing: ${selected?.name ?: YOURS_SAMPLE}\n$included"
        activate.isEnabled = selected == null || session.isProjectAvailable
        activate.toolTipText =
            if (activate.isEnabled) "Activate in preview" else "Open a project to activate sample profiles"
    }

    private fun refreshAppearance() {
        val appearance = if (isShowingCurrent) session.current else session.draft
        preview.previewAccentHex = appearance.accentHex
        preview.previewGlowEnabled = appearance.glowEnabled
        preview.previewGlowStyle = appearance.glowStyle
        preview.previewGlowIntensity = appearance.glowIntensities.getValue(appearance.glowStyle)
        preview.previewGlowWidth = GLOW_WIDTH
        preview.updatePreview()
        val compared = if (isShowingCurrent) "Current" else "Draft"
        details.text =
            "$compared sample · Textual details (not painted):\n" +
            "Font: ${appearance.fontSize} pt · Syntax: ${appearance.syntaxPreset.displayName}\n" +
            "VCS diff intensity: ${appearance.vcsDiffIntensity}%"
        surfaces.repaint()
    }

    private fun refreshDifferences() {
        val baseline = session.acceptedDraft
        val draft = session.draft
        val appearanceChanges =
            listOfNotNull(
                difference("Accent", baseline.accentHex, draft.accentHex),
                difference("Font", baseline.fontSize, draft.fontSize),
                difference("Glow enabled", baseline.glowEnabled, draft.glowEnabled),
                difference("Glow style", baseline.glowStyle.displayName, draft.glowStyle.displayName),
                difference("Syntax", baseline.syntaxPreset.displayName, draft.syntaxPreset.displayName),
                difference("VCS diff intensity", baseline.vcsDiffIntensity, draft.vcsDiffIntensity),
            ) +
                GlowStyle.entries.mapNotNull { style ->
                    difference(
                        "${style.displayName} intensity",
                        baseline.glowIntensities.getValue(style),
                        draft.glowIntensities.getValue(style),
                    )
                }
        val membershipChanges =
            sections.mapNotNull { section ->
                difference(
                    section.displayName,
                    if (section in session.currentIncludedSections) "Included" else "Keep mine",
                    if (section in session.includedSections) "Included" else "Keep mine",
                )
            }
        val catalogChanges =
            session.pendingProfiles.mapNotNull { profile ->
                val accepted = session.profiles.firstOrNull { it.name == profile.name }
                when {
                    accepted == null -> "Create profile: ${profile.name}"
                    accepted != profile -> "Update profile: ${profile.name}"
                    else -> null
                }
            }
        val changes = appearanceChanges + membershipChanges + catalogChanges
        pendingDifferences.text = changes.ifEmpty { listOf("No pending changes") }.joinToString(" · ")
        pendingDifferences.toolTipText = pendingDifferences.text
    }

    private fun <T> difference(
        label: String,
        before: T,
        after: T,
    ): String? = if (before == after) null else "$label: $before → $after"

    private fun vertical(vararg children: JComponent): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            for (child in children) add(child)
        }

    private fun flow(vararg children: JComponent): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(SMALL_GAP), 0)).apply {
            for (child in children) add(child)
        }

    private fun textArea(
        label: String,
        rows: Int,
    ): JTextArea =
        named(JTextArea(rows, 0), label).apply {
            isEditable = false
            isFocusable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            font = JLabel().font
        }

    private fun <T : JComponent> named(
        component: T,
        label: String,
    ): T =
        component.apply {
            name = label
            accessibleContext.accessibleName = label
        }
}

private class StudioSceneLayout : LayoutManager {
    override fun addLayoutComponent(
        name: String?,
        component: Component?,
    ) = Unit

    override fun removeLayoutComponent(component: Component?) = Unit

    override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, 0)

    override fun preferredLayoutSize(parent: Container): Dimension {
        val scene = parent.getComponent(0).preferredSize
        val inspector = parent.getComponent(1).preferredSize
        val availableWidth = parent.parent?.width ?: parent.width
        val gap = JBUI.scale(STUDIO_GAP)
        return if (availableWidth in 1 until JBUI.scale(STACK_BREAKPOINT)) {
            Dimension(availableWidth, scene.height + gap + inspector.height)
        } else {
            Dimension(scene.width + gap + inspector.width, maxOf(scene.height, inspector.height))
        }
    }

    override fun layoutContainer(parent: Container) {
        val scene = parent.getComponent(0)
        val inspector = parent.getComponent(1)
        val gap = JBUI.scale(STUDIO_GAP)
        if (parent.width < JBUI.scale(STACK_BREAKPOINT)) {
            val sceneHeight = scene.preferredSize.height
            scene.setBounds(0, 0, parent.width, sceneHeight)
            inspector.setBounds(0, sceneHeight + gap, parent.width, inspector.preferredSize.height)
        } else {
            val inspectorWidth = inspector.preferredSize.width
            val sceneWidth = (parent.width - gap - inspectorWidth).coerceAtLeast(0)
            scene.setBounds(0, 0, sceneWidth, parent.height)
            inspector.setBounds(sceneWidth + gap, 0, inspectorWidth, parent.height)
        }
    }
}
