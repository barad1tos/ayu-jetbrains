package dev.ayuislands.font

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.io.HttpRequests
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipException
import javax.net.ssl.SSLException

/**
 * Runtime font installer. Downloads a font archive from GitHub (or a direct URL),
 * extracts the required TTF/OTF file, copies it into the platform user-level
 * font directory, registers it with the JVM so the editor can use it without a
 * restart, persists the family name in [AyuIslandsSettings] state, and delegates
 * the actual "apply" to [FontPresetApplicator].
 *
 * The entire pipeline (download + extract + copy + register) runs on a
 * [Task.Backgroundable] pool thread. Only the final apply step hops to the EDT.
 *
 * Every failure mode maps to a distinct notification; no exception ever escapes
 * to the caller.
 */
object FontInstaller {
    private val LOG = logger<FontInstaller>()

    private const val NOTIFICATION_GROUP = "Ayu Islands"
    private const val NOTIFICATION_TITLE = "Ayu Islands — Font install"
    private const val GH_ISSUES_URL = "https://github.com/AyuIslands/ayu-jetbrains/issues"

    enum class FailureKind {
        OFFLINE,
        HTTP_ERROR,
        ASSET_NOT_FOUND,
        EXTRACTION_FAILED,
        REGISTER_FAILED,
        PERMISSION_DENIED,
        DROPDOWN_STALE,
        APPLY_FAILED,
        UNKNOWN,
    }

    sealed class InstallResult {
        data class Success(
            val familyName: String,
        ) : InstallResult()

        data class Failure(
            val kind: FailureKind,
            val message: String,
        ) : InstallResult()
    }

    /**
     * Full install pipeline. Shows background progress, runs off-EDT, fires
     * the [onComplete] callback on the EDT with either a [InstallResult.Success]
     * or [InstallResult.Failure].
     */
    fun install(
        preset: FontPreset,
        project: Project?,
        onComplete: (InstallResult) -> Unit,
    ) {
        val entry = FontCatalog.forPreset(preset)
        val task =
            object : Task.Backgroundable(project, "Installing ${entry.displayName}…", true) {
                override fun run(indicator: ProgressIndicator) {
                    runPipeline(entry, preset, project, indicator, onComplete)
                }
            }
        ProgressManager.getInstance().run(task)
    }

    /** Apply a preset without any download work — used when the font is already installed. */
    fun applyOnly(
        preset: FontPreset,
        @Suppress("UNUSED_PARAMETER") project: Project?,
    ) {
        ApplicationManager.getApplication().invokeLater {
            FontPresetApplicator.apply(FontSettings.decode(null, preset))
        }
    }

    @Suppress("LongMethod", "ReturnCount", "ComplexMethod")
    private fun runPipeline(
        entry: FontCatalog.Entry,
        preset: FontPreset,
        project: Project?,
        indicator: ProgressIndicator,
        onComplete: (InstallResult) -> Unit,
    ) {
        // Step 1 — resolve URL
        indicator.text = "Resolving download URL…"
        val url =
            try {
                FontAssetResolver().resolve(entry)
            } catch (e: RuntimeException) {
                LOG.warn("FontAssetResolver threw unexpectedly", e)
                entry.fallbackUrl
            }

        // Step 2 — download (or reuse cached zip)
        indicator.text = "Downloading ${entry.displayName}…"
        indicator.isIndeterminate = false
        val cacheDir = File(PathManager.getTempPath(), "ayu-fonts").apply { mkdirs() }
        val zipFile = File(cacheDir, url.substringAfterLast('/').ifBlank { "${preset.name}.zip" })
        try {
            if (!zipFile.exists() || zipFile.length() == 0L) {
                HttpRequests
                    .request(url)
                    .productNameAsUserAgent()
                    .connect<Any?> { request ->
                        request.saveToFile(zipFile, indicator)
                        null
                    }
            }
        } catch (e: UnknownHostException) {
            return fail(entry, project, FailureKind.OFFLINE, onComplete, e)
        } catch (e: SocketException) {
            return fail(entry, project, FailureKind.OFFLINE, onComplete, e)
        } catch (e: SSLException) {
            return fail(entry, project, FailureKind.OFFLINE, onComplete, e)
        } catch (e: HttpRequests.HttpStatusException) {
            return fail(entry, project, FailureKind.HTTP_ERROR, onComplete, e)
        } catch (e: IOException) {
            return fail(entry, project, FailureKind.HTTP_ERROR, onComplete, e)
        }

        // Step 3 — extract
        indicator.text = "Unpacking…"
        val extractDir =
            File(PathManager.getTempPath(), "ayu-fonts/extracted-${preset.name}").apply { mkdirs() }
        val extracted: List<File> =
            try {
                FontArchiveExtractor.extract(zipFile, extractDir, entry.filesToKeep)
            } catch (e: FontArchiveException) {
                val kind =
                    if (e.message?.contains("No matching files") == true) {
                        FailureKind.ASSET_NOT_FOUND
                    } else {
                        FailureKind.EXTRACTION_FAILED
                    }
                return fail(entry, project, kind, onComplete, e)
            } catch (e: ZipException) {
                return fail(entry, project, FailureKind.EXTRACTION_FAILED, onComplete, e)
            } catch (e: IOException) {
                return fail(entry, project, FailureKind.EXTRACTION_FAILED, onComplete, e)
            }

        // Step 4 — copy to platform font dir
        indicator.text = "Installing…"
        val platformDir = platformFontDir()
        if (!platformDir.exists()) platformDir.mkdirs()
        if (!platformDir.canWrite()) {
            return fail(entry, project, FailureKind.PERMISSION_DENIED, onComplete, null)
        }
        val installedFiles: List<File> =
            try {
                extracted.map { src ->
                    val target = File(platformDir, src.name)
                    Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    target
                }
            } catch (e: AccessDeniedException) {
                return fail(entry, project, FailureKind.PERMISSION_DENIED, onComplete, e)
            } catch (e: IOException) {
                return fail(entry, project, FailureKind.PERMISSION_DENIED, onComplete, e)
            }

        // Step 5 — JVM register
        val canonicalFamily: String =
            try {
                val first = installedFiles.first()
                val font = Font.createFont(Font.TRUETYPE_FONT, first)
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)
                font.family
            } catch (e: java.awt.FontFormatException) {
                return fail(entry, project, FailureKind.REGISTER_FAILED, onComplete, e)
            } catch (e: IOException) {
                return fail(entry, project, FailureKind.REGISTER_FAILED, onComplete, e)
            }

        // Step 6 — persist
        val state = AyuIslandsSettings.getInstance().state
        state.installedFonts.add(canonicalFamily)
        FontDetector.invalidateCache()
        LOG.info("Font installed: $canonicalFamily (preset=${preset.name})")

        // Step 7 — dropdown stale probe (warning, still success)
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        if (!ge.availableFontFamilyNames.contains(entry.familyName) &&
            !ge.availableFontFamilyNames.contains(canonicalFamily)
        ) {
            notify(
                entry,
                project,
                FailureKind.DROPDOWN_STALE,
                NotificationType.WARNING,
            )
            ApplicationManager.getApplication().invokeLater {
                onComplete(InstallResult.Success(canonicalFamily))
            }
            return
        }

        // Step 8 — apply + verify
        ApplicationManager.getApplication().invokeLater {
            try {
                FontPresetApplicator.apply(FontSettings.decode(null, preset))
                val active =
                    com.intellij.openapi.editor.colors.EditorColorsManager
                        .getInstance()
                        .globalScheme
                        .editorFontName
                if (active != entry.familyName && active != canonicalFamily) {
                    notify(entry, project, FailureKind.APPLY_FAILED, NotificationType.WARNING)
                }
                onComplete(InstallResult.Success(canonicalFamily))
            } catch (e: RuntimeException) {
                LOG.warn("FontPresetApplicator.apply failed", e)
                notify(entry, project, FailureKind.APPLY_FAILED, NotificationType.WARNING)
                onComplete(InstallResult.Success(canonicalFamily))
            }
        }
    }

    private fun fail(
        entry: FontCatalog.Entry,
        project: Project?,
        kind: FailureKind,
        onComplete: (InstallResult) -> Unit,
        cause: Throwable?,
    ) {
        if (cause != null) {
            LOG.warn("Font install failed: ${entry.displayName} / $kind", cause)
        } else {
            LOG.warn("Font install failed: ${entry.displayName} / $kind")
        }
        val message = notify(entry, project, kind, NotificationType.ERROR)
        ApplicationManager.getApplication().invokeLater {
            onComplete(InstallResult.Failure(kind, message))
        }
    }

    private fun notify(
        entry: FontCatalog.Entry,
        project: Project?,
        kind: FailureKind,
        type: NotificationType,
    ): String {
        val base = messageFor(kind, entry)
        val full =
            if (SystemInfo.isMac && kind in BREW_HINT_KINDS) {
                "$base\n\nAlternative: run `brew install --cask ${entry.brewCaskSlug}` in Terminal."
            } else {
                base
            }
        val notification = Notification(NOTIFICATION_GROUP, NOTIFICATION_TITLE, full, type)
        Notifications.Bus.notify(notification, project)
        return full
    }

    private fun messageFor(
        kind: FailureKind,
        entry: FontCatalog.Entry,
    ): String =
        when (kind) {
            FailureKind.OFFLINE ->
                "Can't reach GitHub. Check your connection and try again."
            FailureKind.HTTP_ERROR ->
                "GitHub didn't return the expected asset. Report at $GH_ISSUES_URL — we'll ship a fix."
            FailureKind.ASSET_NOT_FOUND ->
                "GitHub didn't return the expected asset. Report at $GH_ISSUES_URL — we'll ship a fix."
            FailureKind.EXTRACTION_FAILED ->
                "Download was corrupted. Try again, or report at $GH_ISSUES_URL."
            FailureKind.REGISTER_FAILED ->
                "Font file rejected by the JVM. Please report at $GH_ISSUES_URL."
            FailureKind.PERMISSION_DENIED ->
                "Can't write to ${platformFontDir().absolutePath}. Check folder permissions."
            FailureKind.DROPDOWN_STALE ->
                "Font installed. Restart IDE to use it in the editor."
            FailureKind.APPLY_FAILED ->
                "Installed, but couldn't apply automatically. " +
                    "Open Settings → Editor → Font to pick ${entry.familyName}."
            FailureKind.UNKNOWN ->
                "Unexpected error. Please report at $GH_ISSUES_URL."
        }

    private val BREW_HINT_KINDS =
        setOf(
            FailureKind.OFFLINE,
            FailureKind.HTTP_ERROR,
            FailureKind.ASSET_NOT_FOUND,
            FailureKind.EXTRACTION_FAILED,
        )

    private fun platformFontDir(): File {
        val home = System.getProperty("user.home")
        return when {
            SystemInfo.isMac -> File(home, "Library/Fonts")
            SystemInfo.isWindows -> {
                val localAppData = System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local"
                File(localAppData, "Microsoft\\Windows\\Fonts")
            }
            else -> File(home, ".local/share/fonts")
        }
    }
}
