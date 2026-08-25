package dev.ayuislands.settings

import com.intellij.openapi.diagnostic.logger
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
        val selected =
            SyntaxPreviewComponent.preferredAvailableLanguage(
                languages = displayNames,
                preferred = contextualPreferences + fallbackLanguage,
            )
        LOG.info(
            "Syntax context diagnostics: stage=language-resolution, " +
                "project=${project.diagnosticLabel()}, activeFileType=${activeFileType.diagnosticLabel()}, " +
                "activeLanguageId=${activeLanguageId ?: "<none>"}, " +
                "projectVerdict=${verdict.diagnosticLabel()}, " +
                "contextualPreferences=$contextualPreferences, fallback=$fallbackLanguage, selected=$selected",
        )
        return selected
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
        private val LOG = logger<SyntaxLanguageResolver>()
        private const val NOCTULE_SWIFT_ID = "NoctuleSwift"
        private const val SWIFT_ID = "Swift"

        fun readSelectedFileType(project: Project): FileType? {
            val selectedFiles = FileEditorManager.getInstance(project).selectedFiles
            LOG.info(
                "Syntax context diagnostics: stage=selected-files, " +
                    "project=${project.diagnosticLabel()}, " +
                    "selectedFiles=${selectedFiles.joinToString(prefix = "[", postfix = "]") { file ->
                        "${file.path}{fileType=${file.fileType.diagnosticLabel()}}"
                    }}",
            )
            return selectedFiles.firstOrNull()?.fileType
        }

        private fun Project?.diagnosticLabel(): String =
            this?.let { project ->
                "${project.name}@${System.identityHashCode(project)}" +
                    "(default=${project.isDefault},disposed=${project.isDisposed})"
            } ?: "<none>"

        private fun FileType?.diagnosticLabel(): String =
            this?.let { fileType ->
                "${fileType.name}[class=${fileType.javaClass.name},extension=${fileType.defaultExtension}]"
            } ?: "<none>"

        private fun ProjectLanguageVerdict?.diagnosticLabel(): String =
            when (this) {
                is ProjectLanguageVerdict.Detected -> "Detected(languageId=$languageId)"
                is ProjectLanguageVerdict.NoWinner -> "NoWinner(languages=${weights.keys.sorted()})"
                ProjectLanguageVerdict.Cold -> "Cold"
                ProjectLanguageVerdict.Empty -> "Empty"
                ProjectLanguageVerdict.Unavailable -> "Unavailable"
                null -> "<none>"
            }
    }
}
