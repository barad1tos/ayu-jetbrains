package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import dev.ayuislands.accent.toolbar.QuickSwitcherFixture
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals

class CopyHexActionTest {
    @Test fun `each invocation copies the current accepted color and never submits an appearance command`() {
        QuickSwitcherFixture().use { fixture ->
            val clipboard = mockk<CopyPasteManager>(relaxed = true)
            mockkStatic(CopyPasteManager::class)
            every { CopyPasteManager.getInstance() } returns clipboard
            val copied = mutableListOf<String>()
            every { clipboard.setContents(any()) } answers {
                copied += firstArg<Transferable>().getTransferData(DataFlavor.stringFlavor) as String
            }
            val action = CopyHexAction()
            action.actionPerformed(mockk<AnActionEvent>(relaxed = true))
            fixture.hex = "#123456"
            action.actionPerformed(mockk<AnActionEvent>(relaxed = true))
            assertEquals(listOf("#FFCC66", "#123456"), copied)
            assertEquals(emptyList(), fixture.requests)
            fixture.available = false
            action.actionPerformed(mockk<AnActionEvent>(relaxed = true))
            verify(exactly = 2) { clipboard.setContents(any()) }
        }
    }
}
