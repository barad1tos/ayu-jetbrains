package dev.ayuislands.settings

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

internal class NativePreviewResolver(
    private val fileTypes: () -> FileTypeManager = FileTypeManager::getInstance,
) {
    fun resolve(
        fileName: String,
        profile: NativeProfile,
    ): NativePreviewResolution {
        val manager = fileTypes()
        val candidates = mutableListOf<FileType>()
        var lookupFailure: RuntimeException? = null
        try {
            candidates += manager.getFileTypeByFileName(fileName)
        } catch (failure: RuntimeException) {
            rethrowPreviewCancellation(failure)
            lookupFailure = failure
        }
        for (standardName in profile.fileTypeNames) {
            try {
                candidates += manager.getStdFileType(standardName)
            } catch (failure: RuntimeException) {
                rethrowPreviewCancellation(failure)
                if (lookupFailure == null) lookupFailure = failure
            }
        }

        for (candidate in candidates) {
            when {
                candidate === PlainTextFileType.INSTANCE || candidate === UnknownFileType.INSTANCE -> Unit
                candidate !is LanguageFileType -> Unit
                profile.matches(candidate) -> return NativePreviewResolution.Resolved(candidate)
                else -> Unit
            }
        }
        return lookupFailure?.let(NativePreviewResolution::LookupFailed)
            ?: NativePreviewResolution.Unavailable
    }

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
