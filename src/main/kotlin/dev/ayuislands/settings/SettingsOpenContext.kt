package dev.ayuislands.settings

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentApplicator

internal data class ActiveFileContext(
    val fileName: String,
    val fileTypeName: String,
    val languageIds: Set<String>,
) {
    companion object {
        fun capture(
            fileName: String,
            fileType: FileType,
        ): ActiveFileContext {
            val languageIds =
                (fileType as? LanguageFileType)
                    ?.language
                    ?.let { language ->
                        setOf(language.id, language.displayName).filterTo(linkedSetOf(), String::isNotBlank)
                    }.orEmpty()
            return ActiveFileContext(
                fileName = fileName,
                fileTypeName = fileType.name,
                languageIds = languageIds,
            )
        }
    }
}

internal data class SettingsOpenContext(
    val project: Project?,
    val activeFile: ActiveFileContext?,
) {
    companion object {
        val EMPTY = SettingsOpenContext(project = null, activeFile = null)
    }
}

internal class SettingsOpenContextSource(
    private val focusedProject: () -> Project? = AccentApplicator::resolveFocusedProject,
    private val focusedEditorContext: (Project) -> SettingsOpenContext? = { project ->
        FocusedEditorContext.getInstance().get(project)
    },
    private val liveFileContext: (Project) -> ActiveFileContext? = ::selectedFileContext,
) {
    fun capture(): SettingsOpenContext {
        val project = focusedProject()
        val focusedContext = project?.let(focusedEditorContext)
        val liveContext = if (focusedContext == null) project?.let(liveFileContext) else null
        val source = if (focusedContext != null) "focused-editor" else "live-manager"
        val activeFile = focusedContext?.activeFile ?: liveContext
        LOG.info(
            "[SYNTAX-CONTEXT] settings-open project=${project?.name}, " +
                "source=$source, fileType=${activeFile?.fileTypeName}",
        )
        return SettingsOpenContext(
            project = project,
            activeFile = activeFile,
        )
    }

    private companion object {
        private val LOG = logger<SettingsOpenContextSource>()

        fun selectedFileContext(project: Project): ActiveFileContext? {
            val manager = FileEditorManager.getInstance(project)
            val file = manager.selectedTextEditor?.virtualFile ?: manager.selectedFiles.firstOrNull()
            return file?.let { activeFile -> ActiveFileContext.capture(activeFile.name, activeFile.fileType) }
        }
    }
}
