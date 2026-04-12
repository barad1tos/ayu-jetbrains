package dev.ayuislands.font

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.io.IOException

/**
 * Filesystem delete pipeline for curated font presets (D-07).
 *
 * Separation from [FontInstaller] is deliberate — install and uninstall are
 * opposite lifecycle operations with independent test surfaces and different
 * failure modes. [FontInstaller.persistFontState] remains the sole writer of
 * `state.installedFonts` on the install path (D-14); this object is the sole
 * remover via [runUninstallPipeline].
 *
 * Pipeline:
 * 1. Consent dialog via [FontInstallConsent.confirmUninstall] — **caller responsibility**.
 *    This object ASSUMES consent has been obtained. Consent is NOT inside the
 *    pipeline because [Task.Backgroundable] queue indirection would surface the
 *    dialog from a background thread, which either deadlocks on EDT or silently
 *    skips. See threat T-25-10.
 * 2. Off-EDT filesystem work in [Task.Backgroundable], matching [FontInstaller.install]'s
 *    threading.
 * 3. Reads authoritative paths from `AyuIslandsState.installedFontFiles` — never
 *    re-derives from regex (D-08).
 * 4. Rejects any persisted path not canonically under [FontInstaller.platformFontDir]
 *    (path-traversal guard, T-25-01). Rejection returns [UninstallResult.Failure]
 *    WITHOUT mutating state.
 * 5. State mutation on EDT: removes from `AyuIslandsState.installedFonts`, adds to
 *    `AyuIslandsState.explicitlyUninstalledFonts` (D-09 guard), clears the
 *    `AyuIslandsState.installedFontFiles` entry, calls [FontDetector.invalidateCache].
 * 6. Conditionally calls [FontPresetApplicator.revert] when the deleted family
 *    matches the active editor font name.
 * 7. Notifies outcome — success, partial (file lock), or failure (path rejection).
 *
 * **Residual risk (T-25-11):** TOCTOU between canonical-path check and
 * `File.delete()` via symlink swap. Accepted — requires local fs write access
 * to race the delete in milliseconds, and the attacker can already delete the
 * target directly.
 *
 * No exception ever escapes to the caller; `onComplete` always fires with an
 * [UninstallResult].
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
         * State was still mutated — the family is removed from `AyuIslandsState.installedFonts`
         * and added to `AyuIslandsState.explicitlyUninstalledFonts`. The stale file(s) will
         * be fully released after the next IDE restart.
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

    fun uninstall(
        preset: FontPreset,
        project: Project?,
        onComplete: (UninstallResult) -> Unit,
    ) {
        val entry = FontCatalog.forPreset(preset)
        val task =
            object : Task.Backgroundable(project, "Removing ${entry.displayName}…", true) {
                override fun run(indicator: ProgressIndicator) {
                    runUninstallPipeline(entry, project, indicator, onComplete)
                }
            }
        ProgressManager.getInstance().run(task)
    }

    @TestOnly
    @Suppress("LongMethod")
    internal fun runUninstallPipeline(
        entry: FontCatalog.Entry,
        project: Project?,
        indicator: ProgressIndicator,
        onComplete: (UninstallResult) -> Unit,
    ) {
        indicator.text = "Locating installed files…"
        val state = AyuIslandsSettings.getInstance().state
        val family = entry.familyName

        val rawPaths = AyuIslandsState.decodeFontPaths(state.installedFontFiles[family])

        // D-08 path-traversal guard (T-25-01): reject any path that escapes
        // platformFontDir. Runs BEFORE any File.delete() call so a malicious
        // ayuIslands.xml can never make us touch /etc/passwd or anywhere else.
        val platformDirCanonical =
            try {
                FontInstaller.platformFontDir().canonicalPath
            } catch (e: IOException) {
                LOG.warn("Could not canonicalize platformFontDir", e)
                finishUninstallFailure(
                    entry,
                    project,
                    onComplete,
                    "Could not resolve platform font directory",
                )
                return
            }

        val (safePaths, rejectedPaths) = partitionSafePaths(rawPaths, platformDirCanonical)

        if (rejectedPaths.isNotEmpty()) {
            LOG.warn(
                "Path-traversal guard rejected ${rejectedPaths.size} path(s) " +
                    "for family $family: $rejectedPaths",
            )
            finishUninstallFailure(
                entry,
                project,
                onComplete,
                "Installation record contained paths outside the font directory. " +
                    "State was not modified. Please report at $GH_ISSUES_URL.",
            )
            return
        }

        indicator.text = "Removing files…"
        val failed = deleteSafely(safePaths)

        // State mutation + cache invalidation + fallback revert — all on EDT.
        ApplicationManager.getApplication().invokeLater {
            state.installedFonts.remove(family)
            state.installedFontFiles.remove(family)
            state.explicitlyUninstalledFonts.add(family)
            FontDetector.invalidateCache()

            try {
                val activeEditorFont =
                    EditorColorsManager
                        .getInstance()
                        .globalScheme
                        .editorFontName
                if (activeEditorFont.equals(family, ignoreCase = true)) {
                    FontPresetApplicator.revert()
                }
            } catch (e: RuntimeException) {
                LOG.warn("FontPresetApplicator.revert() failed during uninstall of $family", e)
            }

            val result: UninstallResult =
                if (failed.isEmpty()) {
                    UninstallResult.Success(family, safePaths.size)
                } else {
                    UninstallResult.Partial(family, failed)
                }

            val message = uninstallMessage(entry, safePaths.size, failed.size)
            val type = if (failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING
            notifyUninstall(project, message, type)

            onComplete(result)
        }
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
        entry: FontCatalog.Entry,
        project: Project?,
        onComplete: (UninstallResult) -> Unit,
        message: String,
    ) {
        ApplicationManager.getApplication().invokeLater {
            notifyUninstall(project, message, NotificationType.ERROR)
            onComplete(UninstallResult.Failure(entry.familyName, message))
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
