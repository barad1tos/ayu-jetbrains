package dev.ayuislands.whatsnew

import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhatsNewEditorProviderTest {
    private val provider = WhatsNewEditorProvider()
    private val project = mockk<Project>(relaxed = true)

    @Test
    fun `accepts WhatsNewVirtualFile`() {
        assertTrue(provider.accept(project, WhatsNewVirtualFile()))
    }

    @Test
    fun `rejects unrelated virtual files`() {
        // Any LightVirtualFile that's not our marker class — covers regressions
        // where someone widens accept() to a string-name match etc.
        assertFalse(provider.accept(project, LightVirtualFile("test.txt")))
        assertFalse(provider.accept(project, LightVirtualFile("What's New in Ayu Islands")))
    }

    @Test
    fun `editor type id is stable`() {
        // Other code paths may persist this id (workspace.xml, FileEditorManager
        // restoration). Changing it would break editor reopen on IDE restart.
        assertEquals("ayu-islands-whatsnew", provider.editorTypeId)
    }

    @Test
    fun `policy hides default editor`() {
        assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.policy)
    }
}
