package dev.ayuislands.settings

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.LanguageDetectionRules
import dev.ayuislands.syntax.SyntaxLanguageRegistry

internal class SyntaxLanguageResolver(
    private val selectedFileType: (Project) -> FileType? = ::readSelectedFileType,
) {
    fun resolve(
        project: Project?,
        fallbackLanguage: String,
    ): String {
        val activeFileType = project?.let(selectedFileType)
        val activeLanguageId = LanguageDetectionRules.resolveLanguageId(activeFileType)
        return activeLanguageId?.let(SyntaxLanguageRegistry::resolveAlias)?.displayName
            ?: SyntaxLanguageRegistry.resolveAlias(fallbackLanguage)?.displayName
            ?: SyntaxLanguageRegistry.findByStorageId(fallbackLanguage)?.displayName
            ?: DEFAULT_LANGUAGE
    }

    private companion object {
        private const val DEFAULT_LANGUAGE = "Kotlin"

        fun readSelectedFileType(project: Project): FileType? =
            FileEditorManager
                .getInstance(project)
                .selectedFiles
                .firstOrNull()
                ?.fileType
    }
}
