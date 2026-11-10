package dev.ayuislands.font

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File
import java.io.IOException

/**
 * Deletes recorded curated-font files after caller-owned native consent.
 * Rejects the entire request if a path escapes the platform
 * font directory. File work runs in [Task.Backgroundable]; completion runs on EDT.
 *
 * [uninstallResource] consumes explicit accepted paths and
 * returns the native outcome for Studio's metadata writer without changing appearance.
 */
object FontUninstaller {
    private val LOG = logger<FontUninstaller>()
    private const val NOTIFICATION_GROUP = "Ayu Islands"
    private const val NOTIFICATION_TITLE = "Ayu Islands — Font remove"
    private const val GH_ISSUES_URL = "https://github.com/AyuIslands/ayu-jetbrains/issues"

    sealed class UninstallResult {
        abstract val familyName: String

        data class Success(
            override val familyName: String,
            val filesRemoved: Int,
        ) : UninstallResult()

        /**
         * Some files could not be deleted (file lock, permission denied).
         * The inventory owner removes the family and records an explicit uninstall.
         * Failed paths remain in this outcome; the JVM releases font registrations
         * after the next IDE restart.
         */
        data class Partial(
            override val familyName: String,
            val failedPaths: List<String>,
        ) : UninstallResult() {
            init {
                require(failedPaths.isNotEmpty()) { "Partial with no failed paths is a Success" }
            }
        }

        /**
         * Uninstall could not proceed (e.g., path-traversal guard rejected the persisted
         * paths). State was NOT mutated.
         */
        data class Failure(
            override val familyName: String,
            val message: String,
        ) : UninstallResult()
    }

    /** Deletes only the accepted installation paths; the caller records the resource outcome. */
    fun uninstallResource(
        entry: FontCatalog.Entry,
        familyName: String,
        paths: List<String>,
        project: Project?,
        onComplete: (UninstallResult) -> Unit,
    ) {
        FontCatalog.requireCanonicalEntry(entry)
        val removal = Removal(entry, familyName, paths.toList(), project)
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Removing ${entry.displayName}…", true) {
                override fun run(indicator: ProgressIndicator) = removeFiles(removal, indicator, onComplete)
            },
        )
    }

    private data class Removal(
        val entry: FontCatalog.Entry,
        val family: String,
        val paths: List<String>,
        val project: Project?,
    )

    private fun removeFiles(
        removal: Removal,
        indicator: ProgressIndicator,
        onComplete: (UninstallResult) -> Unit,
    ) {
        indicator.text = "Locating installed files…"
        val safePaths = acceptedPaths(removal, onComplete) ?: return
        indicator.text = "Removing files…"
        val failed = deleteSafely(safePaths)
        ApplicationManager.getApplication().invokeLater {
            FontDetector.invalidateCache()
            val result =
                if (failed.isEmpty()) {
                    UninstallResult.Success(removal.family, safePaths.size)
                } else {
                    UninstallResult.Partial(removal.family, failed)
                }
            val message = uninstallMessage(removal.entry, safePaths.size, failed.size)
            val type = if (failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING
            notifyUninstall(removal.project, message, type)
            onComplete(result)
        }
    }

    private fun acceptedPaths(
        removal: Removal,
        onComplete: (UninstallResult) -> Unit,
    ): List<String>? {
        val platformDirectory =
            try {
                FontInstaller.platformFontDir().canonicalPath
            } catch (failure: IOException) {
                LOG.warn("Could not canonicalize platformFontDir", failure)
                finishUninstallFailure(removal, onComplete, "Could not resolve platform font directory")
                return null
            }
        val (safePaths, rejectedPaths) = partitionSafePaths(removal.paths, platformDirectory)
        if (rejectedPaths.isEmpty()) return safePaths
        LOG.warn(
            "Path-traversal guard rejected ${rejectedPaths.size} path(s) for family ${removal.family}: $rejectedPaths",
        )
        finishUninstallFailure(
            removal,
            onComplete,
            "Installation record contained paths outside the font directory. " +
                "State was not modified. Please report at $GH_ISSUES_URL.",
        )
        return null
    }

    private fun partitionSafePaths(
        rawPaths: List<String>,
        platformDirCanonical: String,
    ): Pair<List<String>, List<String>> =
        rawPaths.partition { rawPath ->
            val canonical =
                try {
                    File(rawPath).canonicalPath
                } catch (e: IOException) {
                    LOG.warn("Could not canonicalize $rawPath", e)
                    return@partition false
                }
            canonical.startsWith(platformDirCanonical + File.separator)
        }

    private fun deleteSafely(safePaths: List<String>): List<String> {
        val failed = mutableListOf<String>()
        for (path in safePaths) {
            val file = File(path)
            try {
                if (file.exists() && !file.delete()) {
                    failed.add(path)
                }
            } catch (e: SecurityException) {
                LOG.warn("SecurityException deleting $path", e)
                failed.add(path)
            }
        }
        return failed
    }

    private fun finishUninstallFailure(
        removal: Removal,
        onComplete: (UninstallResult) -> Unit,
        message: String,
    ) {
        ApplicationManager.getApplication().invokeLater {
            notifyUninstall(removal.project, message, NotificationType.ERROR)
            onComplete(UninstallResult.Failure(removal.family, message))
        }
    }

    private fun notifyUninstall(
        project: Project?,
        message: String,
        type: NotificationType,
    ) {
        val notification = Notification(NOTIFICATION_GROUP, NOTIFICATION_TITLE, message, type)
        Notifications.Bus.notify(notification, project)
    }

    private fun uninstallMessage(
        entry: FontCatalog.Entry,
        totalPaths: Int,
        failedCount: Int,
    ): String {
        val removed = totalPaths - failedCount
        return if (failedCount == 0) {
            val plural = if (removed == 1) "" else "s"
            "${entry.displayName} removed ($removed file$plural). " +
                "Restart the IDE to fully release the JVM font registration."
        } else {
            "${entry.displayName}: $removed of $totalPaths files removed. " +
                "Remaining file(s) are in use — restart the IDE to complete removal."
        }
    }
}
