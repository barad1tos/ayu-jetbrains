package dev.ayuislands.integration

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ayuislands.onboarding.FreeOnboardingEditorProvider
import dev.ayuislands.onboarding.FreeOnboardingVirtualFile
import dev.ayuislands.onboarding.OnboardingEditorProvider
import dev.ayuislands.onboarding.OnboardingVirtualFile

class OnboardingEditorIntegrationTest : BasePlatformTestCase() {
    fun testFreeWizardFileOpensInEditor() {
        val file = FreeOnboardingVirtualFile()
        val editors = FileEditorManager.getInstance(project).openFile(file, true)
        assertTrue("Free wizard should open at least one editor", editors.isNotEmpty())
    }

    fun testPremiumWizardFileOpensInEditor() {
        val file = OnboardingVirtualFile()
        val editors = FileEditorManager.getInstance(project).openFile(file, true)
        assertTrue("Premium wizard should open at least one editor", editors.isNotEmpty())
    }

    fun testFreeEditorProviderAcceptsCorrectFile() {
        val provider = FreeOnboardingEditorProvider()
        assertTrue(provider.accept(project, FreeOnboardingVirtualFile()))
        assertFalse(provider.accept(project, OnboardingVirtualFile()))
    }

    fun testPremiumEditorProviderAcceptsCorrectFile() {
        val provider = OnboardingEditorProvider()
        assertTrue(provider.accept(project, OnboardingVirtualFile()))
        assertFalse(provider.accept(project, FreeOnboardingVirtualFile()))
    }

    fun testFreeEditorCreatesNonNullComponent() {
        val provider = FreeOnboardingEditorProvider()
        val file = FreeOnboardingVirtualFile()
        val editor = provider.createEditor(project, file)
        try {
            assertNotNull("Editor component must not be null", editor.component)
            assertEquals("Ayu Islands", editor.name)
        } finally {
            com.intellij.openapi.util.Disposer
                .dispose(editor)
        }
    }
}
