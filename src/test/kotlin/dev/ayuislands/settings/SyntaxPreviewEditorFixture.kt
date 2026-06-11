package dev.ayuislands.settings

import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic

internal class SyntaxPreviewEditorFixture {
    lateinit var kotlinFileType: FileType
        private set
    lateinit var javaFileType: FileType
        private set
    lateinit var fileTypeManager: FileTypeManager
        private set
    lateinit var previewProject: Project
        private set

    fun install() {
        previewProject = mockk(relaxed = true)
        val projectManager = mockk<ProjectManager>(relaxed = true)
        every { projectManager.defaultProject } returns previewProject
        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns projectManager

        kotlinFileType = mockFileType("Kotlin", "kt")
        javaFileType = mockFileType("JAVA", "java")
        fileTypeManager = mockk(relaxed = true)
        every { fileTypeManager.getStdFileType("Kotlin") } returns kotlinFileType
        every { fileTypeManager.getStdFileType("JAVA") } returns javaFileType
        mockkStatic(FileTypeManager::class)
        every { FileTypeManager.getInstance() } returns fileTypeManager

        val editorFactory = mockk<EditorFactory>(relaxed = true)
        every { editorFactory.createDocument(any<String>()) } answers { mockk<Document>(relaxed = true) }
        mockkStatic(EditorFactory::class)
        every { EditorFactory.getInstance() } returns editorFactory

        mockkStatic(Language::class)
        every { Language.getRegisteredLanguages() } returns emptyList()
    }

    fun mockFileType(
        name: String,
        defaultExtension: String,
    ): FileType =
        mockk<FileType>(relaxed = true).also { fileType ->
            every { fileType.name } returns name
            every { fileType.defaultExtension } returns defaultExtension
        }
}
