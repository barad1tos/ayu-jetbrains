package dev.ayuislands.settings.mappings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton

/** Retains native view state; every settings mutation is an explicit edit against the latest host view. */
internal class MappingsControls(
    initialExpanded: Boolean,
    private val onAction: (MappingsPanelAction) -> Unit,
    private val onEdit: (MappingsEdit) -> Unit,
) : JPanel(BorderLayout()) {
    private var rendered: MappingsPanelView? = null
    private var isRendering = false
    private val locked = AtomicBooleanProperty(false)
    private val tables = MappingsTable.entries.associateWith(::MappingRowsTable)
    private val cards = JPanel(CardLayout())
    private val trailing = JPanel(BorderLayout()).apply { isOpaque = false }
    private val hasTrailing = AtomicBooleanProperty(false)
    private lateinit var disclosure: CollapsibleRow
    val isExpanded: Boolean get() = disclosure.expanded
    private val resolution =
        ProjectLanguageResolutionPanel(
            currentAccentHex = { rendered?.context?.currentAccentHex },
            onSetFallback = { focusedEdit { key -> MappingsEdit.Fallback(key, it) } },
            onSetForcedLanguage = { focusedEdit { key -> MappingsEdit.ForcedLanguage(key, it) } },
            onClearForcedLanguage = { focusedEdit { key -> MappingsEdit.ForcedLanguage(key, null) } },
            onClearFallback = { focusedEdit { key -> MappingsEdit.Fallback(key, null) } },
            onRescan = { emit(MappingsPanelAction.Rescan) },
            canRescanNow = { rendered?.let { it.isEditable && it.context.resolution.canRescan } == true },
            languageIconForId = { rendered?.observations?.languageIcons?.get(it) },
        ).apply { minimumSize = Dimension(0, 0) }

    init {
        cards.isOpaque = false
        cards.minimumSize = Dimension(0, 0)
        for ((kind, table) in tables) {
            cards.add(decorateMappingTable(table, actions(kind, table)), kind.name)
        }
        val selector = tableSelector()
        add(
            panel {
                disclosure =
                    collapsibleGroup("Overrides") {
                        row {
                            comment(
                                "Pin an accent color to a specific project or a programming language. " +
                                    "Project overrides win over language overrides; both win over the global accent.",
                            )
                        }
                        row {
                            comment(
                                "Accent overrides are a Pro feature. Preview project and language accent pins here.",
                            )
                        }.visibleIf(locked)
                        row { link("Unlock") { emit(MappingsPanelAction.Unlock) } }.visibleIf(locked)
                        row { cell(selector) }
                        row { cell(cards).resizableColumn().align(Align.FILL) }
                        row { cell(resolution).resizableColumn().align(Align.FILL) }
                        row { cell(trailing).resizableColumn().align(Align.FILL) }.visibleIf(hasTrailing)
                    }
                disclosure.expanded = initialExpanded
                disclosure.addExpandedListener { emit(MappingsPanelAction.Expanded(it)) }
            },
            BorderLayout.CENTER,
        )
        minimumSize = Dimension(0, 0)
    }

    /** Compose the existing trailing icon-accent row without rebuilding tables or their disclosure. */
    fun setTrailing(component: JComponent) {
        trailing.removeAll()
        trailing.add(component, BorderLayout.CENTER)
        hasTrailing.set(true)
    }

    fun render(view: MappingsPanelView) {
        isRendering = true
        try {
            rendered = view
            locked.set(!view.isLicensed)
            tables.getValue(MappingsTable.PROJECTS).render(view.settings.projects, view.observations)
            tables.getValue(MappingsTable.LANGUAGES).render(view.settings.languages, view.observations)
            resolution.refresh(
                view.context.resolution.copy(
                    canMutate = canEdit() && view.context.projectKey != null && view.context.resolution.canMutate,
                    canRescan = view.isEditable && view.context.resolution.canRescan,
                ),
                languageNames = view.observations.languageNames,
            )
            revalidate()
            repaint()
        } finally {
            isRendering = false
        }
    }

    private fun tableSelector(): JPanel {
        val group = ButtonGroup()
        return JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
            isOpaque = false
            for (kind in MappingsTable.entries) {
                val title = if (kind == MappingsTable.PROJECTS) "Projects" else "Languages"
                val button = JRadioButton(title, kind == MappingsTable.PROJECTS)
                group.add(button)
                button.addActionListener { (cards.layout as CardLayout).show(cards, kind.name) }
                add(button)
            }
        }
    }

    private fun actions(
        kind: MappingsTable,
        table: MappingRowsTable,
    ): MappingTableActions =
        MappingTableActions(
            add = { emit(MappingsPanelAction.Add(kind)) },
            edit = { table.selectedKey()?.let { emit(MappingsPanelAction.EditColor(kind, it)) } },
            remove = { table.selectedKey()?.let { edit(MappingsEdit.Remove(kind, it)) } },
            addEnabled = ::canEdit,
            editEnabled = { canEdit() && table.selectedKey() != null },
            removeEnabled = { canEdit() && table.selectedKey() != null },
            extraActions = if (kind == MappingsTable.PROJECTS) listOf(pinAction()) else emptyList(),
        )

    private fun pinAction(): AnAction =
        object : AnAction(
            "Pin Current Project",
            "Add the current project with the global accent",
            AllIcons.Actions.PinTab,
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = canPin()
            }

            override fun actionPerformed(event: AnActionEvent) {
                emit(MappingsPanelAction.PinCurrent)
            }
        }

    private fun canEdit(): Boolean = rendered?.let { it.isEditable && it.isLicensed } == true

    private fun canPin(): Boolean {
        val view = rendered ?: return false
        val key = view.context.projectKey ?: return false
        return canEdit() && view.context.canPinCurrent && view.settings.projects.none { it.key == key }
    }

    private fun focusedEdit(create: (String) -> MappingsEdit) {
        val context = rendered?.context ?: return
        if (context.resolution.canMutate) context.projectKey?.let { edit(create(it)) }
    }

    private fun edit(change: MappingsEdit) {
        if (!isRendering && canEdit()) onEdit(change)
    }

    private fun emit(action: MappingsPanelAction) {
        if (isRendering) return
        val view = rendered ?: return
        val allowed =
            when (action) {
                is MappingsPanelAction.Expanded, MappingsPanelAction.Unlock -> true
                MappingsPanelAction.Rescan -> view.isEditable && view.context.resolution.canRescan
                MappingsPanelAction.PinCurrent -> canPin()
                is MappingsPanelAction.Add, is MappingsPanelAction.EditColor -> canEdit()
            }
        if (allowed) onAction(action)
    }
}
