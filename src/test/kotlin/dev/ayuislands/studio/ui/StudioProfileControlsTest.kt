package dev.ayuislands.studio.ui

import com.intellij.ui.SimpleColoredComponent
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProfileChange
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioProfileKind
import dev.ayuislands.studio.model.StudioProfileLabel
import dev.ayuislands.studio.model.StudioProfileRow
import dev.ayuislands.studio.model.StudioProfileView
import org.junit.Test
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StudioProfileControlsTest {
    @Test
    fun `rendering and replacing the model never emits selection or preview intents`(): Unit =
        onEdt {
            val selections = mutableListOf<StudioProfileChoice>()
            val previews = mutableListOf<PreviewMode>()
            val controls = StudioProfileControls(selections::add, previews::add)
            val initial = view()

            controls.render(initial)
            controls.render(initial)
            controls.render(initial.copy(selected = initial.rows.last().choice, preview = PreviewMode.DRAFT))

            assertTrue(selections.isEmpty())
            assertTrue(previews.isEmpty())
            assertEquals(initial.rows.last().choice, (picker(controls).selectedItem as StudioProfileRow).choice)
            assertTrue(draftButton(controls).isSelected)
        }

    @Test
    fun `native keyboard selection emits one typed choice and preview uses its own callback`(): Unit =
        onEdt {
            val selections = mutableListOf<StudioProfileChoice>()
            val previews = mutableListOf<PreviewMode>()
            val controls = StudioProfileControls(selections::add, previews::add)
            val initial = view()
            controls.render(initial)
            withPopup(controls) { picker ->
                invokeKey(picker, KeyEvent.VK_END)
                invokeKey(picker, KeyEvent.VK_ENTER)
                assertEquals(listOf(initial.rows.last().choice), selections)
                assertTrue(previews.isEmpty())
                draftButton(controls).doClick()
                assertEquals(listOf(PreviewMode.DRAFT), previews)
                assertEquals(listOf(initial.rows.last().choice), selections)
            }
        }

    @Test
    fun `native arrows skip unavailable profiles and reach the next enabled identity`(): Unit =
        onEdt {
            val selections = mutableListOf<StudioProfileChoice>()
            val controls = StudioProfileControls(selections::add, {})
            val initial = view()
            val last = initial.rows.last().copy(choice = StudioProfileChoice.Draft(DraftId("editable")))
            val rows = listOf(initial.rows.first(), initial.rows.last().copy(isEnabled = false), last)
            controls.render(initial.copy(rows = StudioList(rows)))
            withPopup(controls) { picker ->
                invokeKey(picker, KeyEvent.VK_DOWN)
                invokeKey(picker, KeyEvent.VK_ENTER)
                assertEquals(listOf(last.choice), selections)
            }
        }

    @Test
    fun `native preview only navigation skips disabled edges and Escape keeps the accepted selection`(): Unit =
        onEdt {
            val policy = "ComboBox.noActionOnKeyNavigation"
            val previous = UIManager.get(policy)
            try {
                UIManager.put(policy, true)
                val selections = mutableListOf<StudioProfileChoice>()
                val controls = StudioProfileControls(selections::add, {})
                val initial = view()
                val first = initial.rows.first()
                val last = initial.rows.last()
                val disabled =
                    first.copy(
                        choice = StudioProfileChoice.Profile(ProfileId("unavailable")),
                        isEnabled = false,
                    )
                val middle = disabled.copy(choice = StudioProfileChoice.Profile(ProfileId("middle")))
                val end = disabled.copy(choice = StudioProfileChoice.Profile(ProfileId("end")))
                val rows = StudioList(listOf(disabled, first, middle, last, end))
                val mixed = initial.copy(rows = rows)
                controls.render(mixed)
                withPopup(controls) { picker ->
                    invokeKey(picker, KeyEvent.VK_END)
                    assertTrue(selections.isEmpty())
                    invokeKey(picker, KeyEvent.VK_ENTER)
                    assertEquals(listOf(last.choice), selections)
                }
                controls.render(mixed.copy(selected = last.choice))
                withPopup(controls) { picker ->
                    invokeKey(picker, KeyEvent.VK_UP)
                    invokeKey(picker, KeyEvent.VK_ENTER)
                    assertEquals(first.choice, selections.last())
                }
                controls.render(mixed.copy(selected = last.choice))
                withPopup(controls) { picker ->
                    invokeKey(picker, KeyEvent.VK_HOME)
                    invokeKey(picker, KeyEvent.VK_ENTER)
                    assertEquals(first.choice, selections.last())
                }
                controls.render(mixed)
                selections.clear()
                withPopup(controls) { picker ->
                    invokeKey(picker, KeyEvent.VK_DOWN)
                    invokeKey(picker, KeyEvent.VK_ESCAPE)
                    assertEquals(first.choice, (picker.selectedItem as StudioProfileRow).choice)
                    assertTrue(selections.isEmpty())
                }
            } finally {
                if (previous == null) UIManager.getDefaults().remove(policy) else UIManager.put(policy, previous)
            }
        }

    @Test
    fun `HTML shaped and long names remain literal with full accessible and tooltip text`(): Unit =
        onEdt {
            val controls = StudioProfileControls({}, {})
            val name = "<html><b>My & profile</b></html>" + " long name".repeat(40)
            val initial = view()
            val named = initial.rows.first().copy(label = StudioProfileLabel(name))
            controls.render(initial.copy(rows = StudioList(listOf(named, initial.rows.last()))))
            val picker = picker(controls)
            assertTrue(picker.accessibleContext.accessibleName.contains(name))
            assertTrue(picker.toolTipText.contains(name))
            assertFalse(picker.toolTipText.startsWith("<html>", ignoreCase = true))
            val renderer = picker.renderer.getListCellRendererComponent(JList(), named, 0, true, false)
            assertTrue(renderer is SimpleColoredComponent)
            assertTrue(renderer.accessibleContext.accessibleName.contains(name))
            assertEquals(name, (picker.selectedItem as StudioProfileRow).label.name)
            assertTrue(controls.minimumSize.width < picker.getFontMetrics(picker.font).stringWidth(name))
        }

    @Test
    fun `disabled recovery selections cannot emit events while Current Draft remains usable`(): Unit =
        onEdt {
            val selections = mutableListOf<StudioProfileChoice>()
            val previews = mutableListOf<PreviewMode>()
            val controls = StudioProfileControls(selections::add, previews::add)
            val initial = view()
            controls.render(initial.copy(rows = StudioList(initial.rows.map { it.copy(isEnabled = false) })))
            val picker = picker(controls)
            assertFalse(picker.isEnabled)

            picker.selectedIndex = 1
            assertTrue(selections.isEmpty())
            draftButton(controls).doClick()
            assertEquals(listOf(PreviewMode.DRAFT), previews)
        }

    @Test
    fun `renderer restores enabled row colors after a disabled profile`(): Unit =
        onEdt {
            val controls = StudioProfileControls({}, {})
            val initial = view()
            controls.render(initial)
            val renderer = picker(controls).renderer
            val list = JList<StudioProfileRow>().apply { foreground = Color(12, 98, 142) }
            renderer.getListCellRendererComponent(list, initial.rows.first().copy(isEnabled = false), 0, false, false)

            val enabled =
                assertIs<SimpleColoredComponent>(
                    renderer.getListCellRendererComponent(list, initial.rows.last(), 1, false, false),
                )
            val fragments = enabled.iterator()
            fragments.next()
            assertEquals(list.foreground, fragments.textAttributes.fgColor)
        }

    @Test
    fun `duplicate identity qualifiers remain available outside the popup`(): Unit =
        onEdt {
            val controls = StudioProfileControls({}, {})
            val initial = view()
            val rows =
                initial.rows.mapIndexed { index, row ->
                    row.copy(
                        label = StudioProfileLabel("Same", qualifier = "User · profile-$index"),
                        kind = StudioProfileKind.USER,
                    )
                }
            controls.render(initial.copy(rows = StudioList(rows)))

            val picker = picker(controls)
            val qualifier = requireNotNull(rows.first().label.qualifier)
            assertTrue(picker.accessibleContext.accessibleName.contains(qualifier))
            assertTrue(picker.toolTipText.contains(qualifier))
        }

    @Test
    fun `detached drafts retain typed identity and staged names without rewriting accepted names`(): Unit =
        onEdt {
            val selections = mutableListOf<StudioProfileChoice>()
            val controls = StudioProfileControls(selections::add, {})
            val initial = view()
            val detached =
                initial.rows.last().copy(
                    choice = StudioProfileChoice.Draft(DraftId("detached")),
                    label = StudioProfileLabel("Accepted", pendingName = "Edited"),
                    kind = StudioProfileKind.DETACHED,
                    pending = StudioList(StudioProfileChange.entries),
                )
            controls.render(initial.copy(rows = StudioList(listOf(initial.rows.first(), detached))))
            val picker = picker(controls)
            picker.selectedIndex = 1

            assertEquals(listOf(detached.choice), selections)
            assertEquals("Accepted", detached.label.name)
            assertEquals("Profile: Edited", picker.accessibleContext.accessibleName)
            val renderer =
                assertIs<SimpleColoredComponent>(
                    picker.renderer.getListCellRendererComponent(JList(), detached, 1, false, false),
                )
            val text = renderer.iterator().asSequence().joinToString("")
            assertEquals("Edited · Create pending · Rename pending · Update pending · Delete pending", text)
        }

    @Test
    fun `disabled rows revert to the latest rendered selection and preview permissions are respected`(): Unit =
        onEdt {
            val selections = mutableListOf<StudioProfileChoice>()
            val previews = mutableListOf<PreviewMode>()
            val controls = StudioProfileControls(selections::add, previews::add)
            draftButton(controls).doClick()
            val initial = view()
            controls.render(
                initial.copy(
                    rows = StudioList(listOf(initial.rows.first(), initial.rows.last().copy(isEnabled = false))),
                    isPreviewEnabled = false,
                ),
            )
            val picker = picker(controls)
            picker.selectedIndex = 1
            draftButton(controls).doClick()

            assertEquals(initial.selected, (picker.selectedItem as StudioProfileRow).choice)
            assertTrue(selections.isEmpty())
            assertTrue(previews.isEmpty())
            assertFalse(draftButton(controls).isEnabled)
        }

    @Test
    fun `row badges remain until the dialog closes and new dialogs use current pending facts`(): Unit =
        onEdt {
            val controls = StudioProfileControls({}, {})
            controls.renderBadges(setOf("work-profiles", "settings-draft-preview"))
            controls.render(view())
            controls.renderBadges(emptySet())
            val retained = descendants(controls).filterIsInstance<JLabel>().filter { it.text == "New" }
            assertEquals(2, retained.size)
            assertTrue(retained.all { it.isVisible })

            val reopened = StudioProfileControls({}, {})
            reopened.renderBadges(emptySet())
            val current = descendants(reopened).filterIsInstance<JLabel>().filter { it.text == "New" }
            assertTrue(current.none { it.isVisible })
        }

    @Test
    fun `profile actions are emitted only by the explicit menu control`(): Unit =
        onEdt {
            var actions = 0
            val controls = StudioProfileControls({}, {}, { actions++ })
            controls.render(view())
            assertEquals(0, actions)
            descendants(controls)
                .filterIsInstance<AbstractButton>()
                .single { it.accessibleContext.accessibleName == "Profile actions" }
                .doClick()
            assertEquals(1, actions)
        }

    private fun view(): StudioProfileView {
        val rows =
            listOf("Whisper", "Ambient").map { name ->
                StudioProfileRow(
                    StudioProfileChoice.Profile(ProfileId(name.lowercase())),
                    StudioProfileLabel(name),
                    StudioProfileKind.FACTORY,
                    StudioList(emptyList()),
                    isCurrent = name == "Whisper",
                    isEnabled = true,
                )
            }
        return StudioProfileView(
            StudioList(rows),
            rows.first().choice,
            "Whisper",
            PreviewMode.CURRENT,
            isPreviewEnabled = true,
            hasPendingChanges = false,
        )
    }

    private fun picker(controls: StudioProfileControls): JComboBox<StudioProfileRow> =
        assertIs<JComboBox<StudioProfileRow>>(descendants(controls).filterIsInstance<JComboBox<*>>().single())

    private fun withPopup(
        controls: StudioProfileControls,
        action: (JComboBox<StudioProfileRow>) -> Unit,
    ) {
        val picker = picker(controls)
        val window = JFrame("Ayu Islands profile keyboard test")
        try {
            window.isUndecorated = true
            window.focusableWindowState = false
            window.isAutoRequestFocus = false
            window.contentPane = controls
            window.pack()
            window.setLocation(-10_000, -10_000)
            window.isVisible = true
            assertTrue(picker.isShowing)
            picker.showPopup()
            action(picker)
        } finally {
            try {
                picker.hidePopup()
            } finally {
                window.dispose()
            }
        }
    }

    private fun invokeKey(
        comboBox: JComboBox<StudioProfileRow>,
        keyCode: Int,
    ) {
        val key = KeyStroke.getKeyStroke(keyCode, 0)
        val actionKey =
            assertNotNull(
                comboBox.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(key),
                "Missing native keyboard mapping: $key",
            )
        val action = assertNotNull(comboBox.actionMap.get(actionKey))
        assertTrue(action.isEnabled)
        action.actionPerformed(ActionEvent(comboBox, ActionEvent.ACTION_PERFORMED, actionKey.toString()))
    }

    private fun draftButton(controls: StudioProfileControls): AbstractButton =
        descendants(controls).filterIsInstance<AbstractButton>().single { it.text == "Draft" }

    private fun descendants(container: Container): List<Component> =
        container.components.flatMap { child ->
            listOf(child) +
                if (child is Container) descendants(child) else emptyList()
        }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }
}
