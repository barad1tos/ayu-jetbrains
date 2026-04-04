package dev.ayuislands.onboarding

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Provides the free onboarding wizard editor for [FreeOnboardingVirtualFile] instances. */
internal class FreeOnboardingEditorProvider :
    FileEditorProvider,
    DumbAware {
    override fun accept(
        project: Project,
        file: VirtualFile,
    ): Boolean = file is FreeOnboardingVirtualFile

    override fun createEditor(
        project: Project,
        file: VirtualFile,
    ): FileEditor = FreeOnboardingEditor(project, file)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID = "ayu-islands-free-onboarding"
    }
}
