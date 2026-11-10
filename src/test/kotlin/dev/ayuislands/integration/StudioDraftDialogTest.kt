package dev.ayuislands.integration

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.studio.editedSession
import dev.ayuislands.studio.session.StudioDraftChoice
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.ui.StudioDraftDialog
import org.junit.Rule
import org.junit.Test
import javax.swing.JList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioDraftDialogTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `native recovery dialog preserves source order and restores only the explicitly selected row`() =
        onHostEdt {
            val source = requireNotNull(editedSession(sessionDocument()).record)
            val records = listOf(source.copy(id = "second-name"), source.copy(id = "first-name"))
            val dialog = StudioDraftDialog(records)
            try {
                val rows = hostDescendants(dialog.recoveryContent).filterIsInstance<JList<*>>().single()
                assertEquals(records, (0 until rows.model.size).map(rows.model::getElementAt))
                assertEquals(-1, rows.selectedIndex)
                val restore = dialog.recoveryButtons.single { it.text == "Restore" }
                val discard = dialog.recoveryButtons.single { it.text == "Discard" }
                assertFalse(restore.isEnabled)
                assertFalse(discard.isEnabled)
                assertEquals(StudioDraftChoice.KeepForLater, dialog.choice)

                rows.selectedIndex = 1
                assertTrue(restore.isEnabled)
                restore.doClick()

                assertEquals(StudioDraftChoice.Restore(records[1].id), dialog.choice)
                assertEquals(records, (0 until rows.model.size).map(rows.model::getElementAt))
            } finally {
                Disposer.dispose(dialog.disposable)
            }
        }

    @Test
    fun `native discard returns the exact selected identity and keep for later leaves work untouched`() =
        onHostEdt {
            val record = requireNotNull(editedSession(sessionDocument()).record)
            val discardDialog = StudioDraftDialog(listOf(record))
            try {
                val rows = hostDescendants(discardDialog.recoveryContent).filterIsInstance<JList<*>>().single()
                rows.selectedIndex = 0
                discardDialog.recoveryButtons.single { it.text == "Discard" }.doClick()
                assertEquals(StudioDraftChoice.Discard(record.id), discardDialog.choice)
            } finally {
                Disposer.dispose(discardDialog.disposable)
            }
            val laterDialog = StudioDraftDialog(listOf(record))
            try {
                laterDialog.recoveryButtons.single { it.text == "Keep for Later" }.doClick()
                assertEquals(StudioDraftChoice.KeepForLater, laterDialog.choice)
            } finally {
                Disposer.dispose(laterDialog.disposable)
            }
        }
}
