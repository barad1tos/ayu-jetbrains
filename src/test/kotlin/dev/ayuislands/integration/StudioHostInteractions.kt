package dev.ayuislands.integration

import com.intellij.ui.TitledSeparator
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioProfileRow
import dev.ayuislands.studio.model.StudioSettingsSection
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.lang.reflect.InvocationTargetException
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTabbedPane
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal fun onHostEdt(action: () -> Unit) {
    try {
        SwingUtilities.invokeAndWait(action)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}

internal fun hostDescendants(root: Container): List<Component> =
    root.components.flatMap { child -> listOf(child) + if (child is Container) hostDescendants(child) else emptyList() }

internal fun StudioHostFixture.tabTitles(): List<String> =
    tabs().let { pane ->
        (0 until pane.tabCount).map(pane::getTitleAt)
    }

internal fun StudioHostFixture.select(id: ProfileId) {
    val picker = picker()
    picker.selectedItem = pickerRows().single { it.choice == StudioProfileChoice.Profile(id) }
}

internal fun StudioHostFixture.pickerRows(): List<StudioProfileRow> =
    picker().let { combo ->
        (0 until combo.itemCount).map { assertIs<StudioProfileRow>(combo.getItemAt(it)) }
    }

internal fun StudioHostFixture.pickerSelection(): StudioProfileRow = assertIs(picker().selectedItem)

internal fun StudioHostFixture.selectedDraft(): DraftRecord {
    val work = requireNotNull(binding.controller.state.record).work
    return work.drafts.single { it.id == work.selected }
}

internal fun StudioHostFixture.click(text: String) {
    val button = hostDescendants(root).filterIsInstance<AbstractButton>().single { it.text == text }
    val pane = tabs()
    val page =
        (0 until pane.tabCount).firstOrNull {
            SwingUtilities.isDescendingFrom(button, pane.getComponentAt(it))
        }
    if (page != null) pane.selectedIndex = page
    assertTrue(button.isEnabled, text)
    assertTrue(button.isShowing, text)
    button.doClick()
}

internal fun StudioHostFixture.menu(text: String) {
    val item = profileMenu().components.filterIsInstance<JMenuItem>().single { it.text == text }
    assertTrue(item.isEnabled, text)
    item.doClick()
}

internal fun StudioHostFixture.profileMenu(): JPopupMenu {
    val action =
        hostDescendants(root).filterIsInstance<AbstractButton>().single {
            it.accessibleContext.accessibleName == "Profile actions"
        }
    action.doClick()
    return MenuSelectionManager
        .defaultManager()
        .selectedPath
        .map { it.component }
        .filterIsInstance<JPopupMenu>()
        .single()
}

internal fun StudioHostFixture.toggle(separator: TitledSeparator) {
    assertTrue(SwingUtilities.isDescendingFrom(separator, root))
    separator.dispatchEvent(
        MouseEvent(separator, MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1),
    )
}

private fun StudioHostFixture.picker(): JComboBox<*> =
    hostDescendants(root).filterIsInstance<JComboBox<*>>().single {
        it.itemCount > 0 && it.getItemAt(0) is StudioProfileRow
    }

private fun StudioHostFixture.tabs(): JTabbedPane =
    hostDescendants(root).filterIsInstance<JTabbedPane>().single {
        it.tabCount == StudioSettingsSection.entries.size && it.getTitleAt(0) == StudioSettingsSection.ACCENT.title
    }
