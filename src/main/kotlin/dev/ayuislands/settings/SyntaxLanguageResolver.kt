package dev.ayuislands.settings

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.LanguageDetectionRules
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.syntax.SyntaxLanguageRegistry

internal class SyntaxLanguageResolver(
    private val selectedFileType: (Project) -> FileType? = ::readSelectedFileType,
    private val projectVerdict: (Project) -> ProjectLanguageVerdict = { project ->
        ProjectLanguageDetector.verdict(project)
    },
) {
    fun resolve(
        project: Project?,
        fallbackLanguage: String,
        supportedLanguages: List<SyntaxLanguageRegistry.LangTag> = SyntaxLanguageRegistry.supportedLanguages(),
    ): String {
        val displayNames = supportedLanguages.map { it.displayName }
        val contextualPreferences = project?.let { languagePreferences(it, supportedLanguages) }.orEmpty()
        return SyntaxPreviewComponent.preferredAvailableLanguage(
            languages = displayNames,
            preferred = contextualPreferences + fallbackLanguage,
        )
    }

    private fun languagePreferences(
        project: Project,
        supportedLanguages: List<SyntaxLanguageRegistry.LangTag>,
    ): List<String> =
        listOfNotNull(
            LanguageDetectionRules.resolveLanguageId(selectedFileType(project)),
            (projectVerdict(project) as? ProjectLanguageVerdict.Detected)?.languageId,
        ).mapNotNull { languageId ->
            supportedDisplayName(languageId, supportedLanguages)
        }.distinct()

    private fun supportedDisplayName(
        languageId: String,
        supportedLanguages: List<SyntaxLanguageRegistry.LangTag>,
    ): String? {
        supportedLanguages
            .firstOrNull { it.tag.equals(languageId, ignoreCase = true) }
            ?.let { return it.displayName }
        val platformDisplayName = LanguageDetectionRules.displayNameForLanguageId(languageId)
        return supportedLanguages
            .firstOrNull { it.displayName.equals(platformDisplayName, ignoreCase = true) }
            ?.displayName
    }

    private companion object {
        fun readSelectedFileType(project: Project): FileType? =
            FileEditorManager
                .getInstance(project)
                .selectedFiles
                .firstOrNull()
                ?.fileType
    }
}
