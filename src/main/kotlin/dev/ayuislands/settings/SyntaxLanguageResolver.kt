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
        val activeFileType = project?.let(selectedFileType)
        val activeLanguageId = LanguageDetectionRules.resolveLanguageId(activeFileType)
        val verdict = project?.let(projectVerdict)
        val contextualPreferences = languagePreferences(activeLanguageId, verdict, supportedLanguages)
        return SyntaxPreviewComponent.preferredAvailableLanguage(
            languages = displayNames,
            preferred = contextualPreferences + fallbackLanguage,
        )
    }

    private fun languagePreferences(
        activeLanguageId: String?,
        verdict: ProjectLanguageVerdict?,
        supportedLanguages: List<SyntaxLanguageRegistry.LangTag>,
    ): List<String> =
        listOfNotNull(
            activeLanguageId,
            (verdict as? ProjectLanguageVerdict.Detected)?.languageId,
        ).mapNotNull { languageId ->
            supportedDisplayName(languageId, supportedLanguages)
        }.distinct()

    private fun supportedDisplayName(
        languageId: String,
        supportedLanguages: List<SyntaxLanguageRegistry.LangTag>,
    ): String? {
        val canonicalId =
            if (languageId.equals(NOCTULE_SWIFT_ID, ignoreCase = true)) SWIFT_ID else languageId
        supportedLanguages
            .firstOrNull { it.tag.equals(canonicalId, ignoreCase = true) }
            ?.let { return it.displayName }
        val platformDisplayName = LanguageDetectionRules.displayNameForLanguageId(canonicalId)
        return supportedLanguages
            .firstOrNull { it.displayName.equals(platformDisplayName, ignoreCase = true) }
            ?.displayName
    }

    private companion object {
        private const val NOCTULE_SWIFT_ID = "NoctuleSwift"
        private const val SWIFT_ID = "Swift"

        fun readSelectedFileType(project: Project): FileType? =
            FileEditorManager
                .getInstance(project)
                .selectedFiles
                .firstOrNull()
                ?.fileType
    }
}
