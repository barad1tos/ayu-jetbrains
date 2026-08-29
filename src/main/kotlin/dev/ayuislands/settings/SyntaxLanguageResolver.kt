package dev.ayuislands.settings

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.syntax.SyntaxLanguageRegistry

internal class SyntaxLanguageResolver(
    private val projectVerdict: (Project, Boolean) -> ProjectLanguageVerdict = ProjectLanguageDetector::verdict,
    private val activeLanguageMatcher: SyntaxLanguageMatcher = SyntaxLanguageMatcher(),
) {
    fun resolve(
        project: Project?,
        activeFile: ActiveFileContext?,
        fallbackLanguage: String,
    ): String {
        val activeSpecification = activeFile?.let(activeLanguageMatcher::match)
        val verdict =
            if (activeSpecification == null) {
                project?.let { context -> projectVerdict(context, true) }
            } else {
                null
            }
        val detectedProjectLanguageId = (verdict as? ProjectLanguageVerdict.Detected)?.languageId
        return activeSpecification?.displayName
            ?: detectedProjectLanguageId?.let(SyntaxLanguageRegistry::resolveAlias)?.displayName
            ?: SyntaxLanguageRegistry.resolveAlias(fallbackLanguage)?.displayName
            ?: SyntaxLanguageRegistry.findByStorageId(fallbackLanguage)?.displayName
            ?: DEFAULT_LANGUAGE
    }

    private companion object {
        private const val DEFAULT_LANGUAGE = "Kotlin"
    }
}
