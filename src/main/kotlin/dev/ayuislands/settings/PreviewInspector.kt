package dev.ayuislands.settings

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.NativeProfile
import dev.ayuislands.syntax.PreviewFileSpec
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

internal fun interface PreviewInspector {
    fun inspect(
        specification: LanguageSpecification,
        generation: Long,
    ): SyntaxProbeResult
}

internal class IdePreviewInspector(
    private val project: Project,
    private val recovery: PluginRecoveryResolver = PluginRecoveryResolver(),
) : PreviewInspector {
    private val warnedFailures = ConcurrentHashMap.newKeySet<String>()

    override fun inspect(
        specification: LanguageSpecification,
        generation: Long,
    ): SyntaxProbeResult =
        try {
            inspectBundle(specification, generation)
        } catch (failure: RuntimeException) {
            propagateProbeCancellation(failure)
            warnOnce(specification.storageId, failure)
            SyntaxProbeResult.Deferred(
                languageId = specification.storageId,
                generation = generation,
                reason = TEMPORARY_FAILURE_MESSAGE,
            )
        }

    private fun inspectBundle(
        specification: LanguageSpecification,
        generation: Long,
    ): SyntaxProbeResult {
        val evidence = mutableListOf<HighlightEvidence>()
        for (previewFile in specification.preview.files) {
            val profile = specification.profile(previewFile.profileId)
            val fileType =
                resolveFileType(previewFile, profile)
                    ?: return recovery.unavailable(specification, generation)
            val code =
                loadPreview(previewFile.resourceName)
                    ?: error("Missing bundled syntax preview '${previewFile.resourceName}'")
            evidence += inspectFile(specification.storageId, previewFile, fileType, code)
        }
        return SyntaxProbeAssessment.assess(specification, generation, evidence)
    }

    private fun inspectFile(
        languageId: String,
        previewFile: PreviewFileSpec,
        fileType: FileType,
        code: String,
    ): HighlightEvidence {
        val virtualFile = LightVirtualFile(previewFile.fileName, fileType, code)
        val highlighter =
            checkNotNull(SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, virtualFile)) {
                "No syntax highlighter for bundled preview '${previewFile.fileName}'"
            }
        return HighlightEvidenceCollector.collect(
            languageId,
            highlighter,
            code,
            HighlightEvidenceCollector.descriptorKeys(highlighter),
        )
    }

    private fun resolveFileType(
        previewFile: PreviewFileSpec,
        profile: NativeProfile,
    ): FileType? {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(previewFile.fileName)
        if (fileType === PlainTextFileType.INSTANCE || fileType === UnknownFileType.INSTANCE) return null
        if (fileType !is LanguageFileType) return null
        if (profile.fileTypeNames.none { it.equals(fileType.name, ignoreCase = true) }) return null
        val nativeLanguageNames = setOf(fileType.language.id, fileType.language.displayName)
        val matchesLanguage =
            profile.languageIds.any { expected ->
                nativeLanguageNames.any { it.equals(expected, ignoreCase = true) }
            }
        if (!matchesLanguage) {
            return null
        }
        return fileType
    }

    private fun loadPreview(resourceName: String): String? {
        val path = "$PREVIEW_RESOURCE_ROOT/$resourceName"
        val stream = IdePreviewInspector::class.java.getResourceAsStream(path) ?: return null
        return stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText().trimIndent() }
    }

    private fun warnOnce(
        languageId: String,
        failure: RuntimeException,
    ) {
        if (warnedFailures.add(languageId)) {
            LOG.warn("Native syntax capability probe failed for bundled '$languageId' preview", failure)
        }
    }

    private fun LanguageSpecification.profile(profileId: String): NativeProfile =
        checkNotNull(nativeProfiles.firstOrNull { it.id == profileId }) {
            "Unknown native profile '$profileId' for '$storageId'"
        }

    private companion object {
        private val LOG = logger<IdePreviewInspector>()
        private const val PREVIEW_RESOURCE_ROOT = "/dev/ayuislands/settings/syntax-preview"
        private const val TEMPORARY_FAILURE_MESSAGE = "Native syntax analysis is temporarily unavailable. Retry."
    }
}

private fun propagateProbeCancellation(failure: RuntimeException) {
    if (failure is ProcessCanceledException || failure is CancellationException) throw failure
}
