package dev.ayuislands.settings

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.progress.ProcessCanceledException
import dev.ayuislands.syntax.NativeProfile
import kotlin.coroutines.cancellation.CancellationException

internal sealed interface NativePreviewResolution {
    data class Resolved(
        val fileType: LanguageFileType,
    ) : NativePreviewResolution

    data object Unavailable : NativePreviewResolution

    data class LookupFailed(
        val failure: RuntimeException,
    ) : NativePreviewResolution
}

private data class CandidateLookup(
    val fileTypes: List<FileType>,
    val failure: RuntimeException? = null,
)

internal class NativePreviewResolver(
    private val languageById: (String) -> Language? = Language::findLanguageByID,
    private val fileTypes: () -> FileTypeManager = FileTypeManager::getInstance,
) {
    fun resolve(
        fileName: String,
        profile: NativeProfile,
    ): NativePreviewResolution {
        val manager = fileTypes()
        val lookupSuppliers =
            buildList {
                add { listOf(manager.getFileTypeByFileName(fileName)) }
                profile.fileTypeNames.mapTo(this) { standardName ->
                    { listOf(manager.getStdFileType(standardName)) }
                }
                add {
                    manager.registeredFileTypes
                        .filterIsInstance<LanguageFileType>()
                        .filter { fileType -> profile.matches(fileType) }
                }
                profile.languageIds.mapTo(this) { languageId ->
                    { listOfNotNull(languageById(languageId)?.associatedFileType) }
                }
            }
        var firstFailure: RuntimeException? = null
        lookupSuppliers.forEach { lookup ->
            val result = captureLookup(lookup)
            if (firstFailure == null) firstFailure = result.failure
            val resolved =
                result.fileTypes
                    .asSequence()
                    .filterIsInstance<LanguageFileType>()
                    .filterNot(::isFallbackFileType)
                    .firstOrNull { fileType -> profile.matches(fileType) }
            if (resolved != null) return NativePreviewResolution.Resolved(resolved)
        }

        return firstFailure?.let(NativePreviewResolution::LookupFailed)
            ?: NativePreviewResolution.Unavailable
    }

    private fun captureLookup(block: () -> List<FileType>): CandidateLookup =
        try {
            CandidateLookup(block())
        } catch (failure: RuntimeException) {
            rethrowPreviewCancellation(failure)
            CandidateLookup(emptyList(), failure)
        }

    private fun isFallbackFileType(fileType: LanguageFileType): Boolean =
        fileType === PlainTextFileType.INSTANCE || fileType === UnknownFileType.INSTANCE

    private fun NativeProfile.matches(fileType: LanguageFileType): Boolean {
        if (fileTypeNames.none { it.equals(fileType.name, ignoreCase = true) }) return false
        val nativeLanguageNames = setOf(fileType.language.id, fileType.language.displayName)
        return languageIds.any { expected ->
            nativeLanguageNames.any { it.equals(expected, ignoreCase = true) }
        }
    }
}

internal fun rethrowPreviewCancellation(failure: RuntimeException) {
    if (failure is ProcessCanceledException || failure is CancellationException) throw failure
}
