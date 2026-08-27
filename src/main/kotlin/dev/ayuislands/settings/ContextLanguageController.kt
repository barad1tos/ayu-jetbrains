package dev.ayuislands.settings

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.ProjectLanguageDetectionListener

internal class ContextLanguageController(
    private val resolve: (Project?, FileType?, String) -> String,
    private val subscribe: (Project, () -> Unit) -> (() -> Unit),
) {
    private var stopUpdates: (() -> Unit)? = null
    private var currentLanguage = ""
    private var hasUserSelection = false
    private var isApplyingDetection = false

    fun start(
        project: Project?,
        fileType: FileType?,
        fallback: String,
        applyDetected: (String) -> Unit,
    ): String {
        dispose()
        hasUserSelection = false
        if (project != null && fileType == null) {
            stopUpdates =
                subscribe(project) {
                    if (hasUserSelection) return@subscribe
                    val detected = resolve(project, null, fallback)
                    if (detected == currentLanguage) return@subscribe
                    LOG.info(
                        "[SYNTAX-CONTEXT] project-refresh project=${project.name}, " +
                            "from=$currentLanguage, to=$detected",
                    )
                    currentLanguage = detected
                    isApplyingDetection = true
                    try {
                        applyDetected(detected)
                    } finally {
                        isApplyingDetection = false
                    }
                }
        }
        currentLanguage = resolve(project, fileType, fallback)
        LOG.info(
            "[SYNTAX-CONTEXT] language-start project=${project?.name}, " +
                "fileType=${fileType?.name}, selected=$currentLanguage",
        )
        return currentLanguage
    }

    fun select(language: String) {
        if (!isApplyingDetection) hasUserSelection = true
        currentLanguage = language
    }

    fun dispose() {
        stopUpdates?.invoke()
        stopUpdates = null
    }

    private companion object {
        private val LOG = logger<ContextLanguageController>()
    }
}

internal fun observeProjectLanguage(
    project: Project,
    refresh: () -> Unit,
): () -> Unit {
    val connection = project.messageBus.connect()
    connection.subscribe(
        ProjectLanguageDetectionListener.TOPIC,
        ProjectLanguageDetectionListener { refresh() },
    )
    return connection::disconnect
}
