package dev.ayuislands.settings

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentApplicator

internal data class SettingsOpenContext(
    val project: Project?,
    val activeFileType: FileType?,
) {
    companion object {
        val EMPTY = SettingsOpenContext(project = null, activeFileType = null)
    }
}

internal class SettingsOpenContextSource(
    private val focusedProject: () -> Project? = AccentApplicator::resolveFocusedProject,
    private val focusedEditorContext: (Project) -> SettingsOpenContext? = { project ->
        FocusedEditorContext.getInstance().get(project)
    },
    private val liveFileType: (Project) -> FileType? = ::readSelectedFileType,
) {
    fun capture(): SettingsOpenContext {
        val project = focusedProject()
        val focusedContext = project?.let(focusedEditorContext)
        val liveType = if (focusedContext == null) project?.let(liveFileType) else null
        val source = if (focusedContext != null) "focused-editor" else "live-manager"
        val activeFileType = focusedContext?.activeFileType ?: liveType
        LOG.info(
            "[SYNTAX-CONTEXT] settings-open project=${project?.name}, " +
                "source=$source, fileType=${activeFileType?.name}",
        )
        return SettingsOpenContext(
            project = project,
            activeFileType = activeFileType,
        )
    }

    private companion object {
        private val LOG = logger<SettingsOpenContextSource>()

        fun readSelectedFileType(project: Project): FileType? {
            val manager = FileEditorManager.getInstance(project)
            return manager.selectedTextEditor?.virtualFile?.fileType
                ?: manager.selectedFiles.firstOrNull()?.fileType
        }
    }
}
