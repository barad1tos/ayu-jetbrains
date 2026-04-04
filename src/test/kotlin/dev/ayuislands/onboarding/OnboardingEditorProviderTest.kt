package dev.ayuislands.onboarding

import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingEditorProviderTest {
    private val provider = OnboardingEditorProvider()
    private val project = mockk<Project>(relaxed = true)

    @Test
    fun `accepts OnboardingVirtualFile`() {
        assertTrue(provider.accept(project, OnboardingVirtualFile()))
    }

    @Test
    fun `rejects other virtual files`() {
        assertFalse(provider.accept(project, LightVirtualFile("test.txt")))
    }

    @Test
    fun `editor type id is stable`() {
        assertEquals("ayu-islands-onboarding", provider.editorTypeId)
    }

    @Test
    fun `policy hides default editor`() {
        assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.policy)
    }
}
