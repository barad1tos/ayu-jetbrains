package dev.ayuislands.onboarding

import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FreeOnboardingEditorProviderTest {
    private val provider = FreeOnboardingEditorProvider()
    private val project = mockk<Project>(relaxed = true)

    @Test
    fun `accepts FreeOnboardingVirtualFile`() {
        assertTrue(provider.accept(project, FreeOnboardingVirtualFile()))
    }

    @Test
    fun `rejects other virtual files`() {
        assertFalse(provider.accept(project, LightVirtualFile("other")))
    }

    @Test
    fun `rejects OnboardingVirtualFile`() {
        assertFalse(provider.accept(project, OnboardingVirtualFile()))
    }

    @Test
    fun `editor type id is distinct from premium`() {
        assertNotEquals(
            OnboardingEditorProvider.EDITOR_TYPE_ID,
            FreeOnboardingEditorProvider.EDITOR_TYPE_ID,
        )
    }

    @Test
    fun `policy hides default editor`() {
        assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.policy)
    }
}
