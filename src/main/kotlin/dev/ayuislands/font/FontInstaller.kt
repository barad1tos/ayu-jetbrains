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
import org.jetbrains.annotations.TestOnly
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
        project: Project?,
    ) {
        val entry = FontCatalog.forPreset(preset)
        ApplicationManager.getApplication().invokeLater {
            try {
                FontPresetApplicator.apply(FontSettings.decode(null, preset))
            } catch (exception: RuntimeException) {
                LOG.warn("FontPresetApplicator.apply failed (applyOnly)", exception)
                notify(entry, project, FailureKind.APPLY_FAILED, NotificationType.WARNING)
            }
        }
    }

    private fun runPipeline(
        entry: FontCatalog.Entry,
        preset: FontPreset,
        project: Project?,
        indicator: ProgressIndicator,
        onComplete: (InstallResult) -> Unit,
    ) {
        indicator.text = "Resolving download URL…"
        val url = resolveDownloadUrl(entry)

        indicator.text = "Downloading ${entry.displayName}…"
        indicator.isIndeterminate = false
        val zipFile = cachedZipFile(url, preset)
        try {
            downloadZip(url, zipFile, indicator)
        } catch (e: IOException) {
            return fail(entry, project, downloadFailureKind(e), onComplete, e)
        }

        indicator.text = "Unpacking…"
        val extractDir =
            File(PathManager.getTempPath(), "ayu-fonts/extracted-${preset.name}").apply { mkdirs() }
        val extracted: List<File>
        try {
            extracted = extractFonts(zipFile, extractDir, entry)
        } catch (e: IOException) {
            return fail(entry, project, extractionFailureKind(e, zipFile), onComplete, e)
        }

        indicator.text = "Installing…"
        val installedFiles: List<File>
        try {
            installedFiles = copyToPlatformFontDir(extracted)
        } catch (e: IOException) {
            return fail(entry, project, FailureKind.PERMISSION_DENIED, onComplete, e)
        }

        cleanupQuietly(extractDir)

        val canonicalFamily: String
        try {
            canonicalFamily = registerFont(installedFiles)
        } catch (e: java.awt.FontFormatException) {
            return fail(entry, project, FailureKind.REGISTER_FAILED, onComplete, e)
        } catch (e: IOException) {
            return fail(entry, project, FailureKind.REGISTER_FAILED, onComplete, e)
        }

        LOG.info("Font installed: $canonicalFamily (preset=${preset.name})")
        persistAndApply(entry, project, canonicalFamily, installedFiles, onComplete)
    }

    @TestOnly
    internal fun resolveDownloadUrl(entry: FontCatalog.Entry): String =
        try {
            FontAssetResolver().resolve(entry)
        } catch (e: RuntimeException) {
            LOG.warn("FontAssetResolver threw unexpectedly", e)
            entry.fallbackUrl
        }

    @TestOnly
    internal fun cachedZipFile(
        url: String,
        preset: FontPreset,
    ): File {
        val cacheDir = File(PathManager.getTempPath(), "ayu-fonts").apply { mkdirs() }
        return File(cacheDir, url.substringAfterLast('/').ifBlank { "${preset.name}.zip" })
    }

    /** Downloads the zip unless a valid cached copy already exists. */
    @TestOnly
    @Throws(IOException::class)
    internal fun downloadZip(
        url: String,
        zipFile: File,
        indicator: ProgressIndicator,
    ) {
        if (!zipFile.exists() || zipFile.length() == 0L) {
            HttpRequests
                .request(url)
                .productNameAsUserAgent()
                .connect<Any?> { request ->
                    request.saveToFile(zipFile, indicator)
                    null
                }
        }
    }

    @TestOnly
    internal fun downloadFailureKind(exception: IOException): FailureKind =
        when (exception) {
            is UnknownHostException, is SocketException, is SSLException -> FailureKind.OFFLINE
            else -> FailureKind.HTTP_ERROR
        }

    /** Extracts font files from the archive. */
    @TestOnly
    @Throws(IOException::class)
    internal fun extractFonts(
        zipFile: File,
        extractDir: File,
        entry: FontCatalog.Entry,
    ): List<File> =
        try {
            FontArchiveExtractor.extract(zipFile, extractDir, entry.filesToKeep)
        } catch (e: FontArchiveException) {
            throw IOException(e.message, e)
        }

    @TestOnly
    internal fun extractionFailureKind(
        exception: IOException,
        zipFile: File,
    ): FailureKind {
        val archiveCause = exception.cause
        if (archiveCause is FontArchiveException &&
            archiveCause.message?.contains("No matching files") == true
        ) {
            return FailureKind.ASSET_NOT_FOUND
        }
        if ((exception is ZipException || exception.cause is ZipException) && !zipFile.delete()) {
            LOG.warn("Failed to delete corrupt cache: $zipFile")
        }
        return FailureKind.EXTRACTION_FAILED
    }

    /**
     * Copies extracted font files into a destination directory.
     * Defaults to the platform user-level font directory; tests pass a temp dir
     * to avoid polluting the user's real font folder.
     */
    @TestOnly
    @Throws(IOException::class)
    internal fun copyToPlatformFontDir(
        extracted: List<File>,
        destDir: File = platformFontDir(),
    ): List<File> {
        if (!destDir.exists()) destDir.mkdirs()
        if (!destDir.canWrite()) {
            throw AccessDeniedException(destDir.absolutePath)
        }
        return extracted.map { source ->
            val target = File(destDir, source.name)
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target
        }
    }

    /** Registers the first installed font file with the JVM's graphics environment. */
    @TestOnly
    @Throws(java.awt.FontFormatException::class, IOException::class)
    internal fun registerFont(installedFiles: List<File>): String {
        val first = installedFiles.first()
        val font = Font.createFont(Font.TRUETYPE_FONT, first)
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)
        return font.family
    }

    @TestOnly
    internal fun cleanupQuietly(directory: File) {
        try {
            directory.deleteRecursively()
        } catch (exception: IOException) {
            LOG.debug("Failed to clean up extraction directory: $directory", exception)
        }
    }

    private fun persistAndApply(
        entry: FontCatalog.Entry,
        project: Project?,
        canonicalFamily: String,
        installedFiles: List<File>,
        onComplete: (InstallResult) -> Unit,
    ) {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val dropdownStale =
            !ge.availableFontFamilyNames.contains(entry.familyName) &&
                !ge.availableFontFamilyNames.contains(canonicalFamily)

        if (dropdownStale) {
            notify(entry, project, FailureKind.DROPDOWN_STALE, NotificationType.WARNING)
            ApplicationManager.getApplication().invokeLater {
                persistFontState(canonicalFamily, installedFiles)
                onComplete(InstallResult.Success(canonicalFamily))
            }
            return
        }

        ApplicationManager.getApplication().invokeLater {
            try {
                persistFontState(canonicalFamily, installedFiles)
                FontPresetApplicator.apply(FontSettings.decode(null, entry.preset))
                verifyApplied(entry, canonicalFamily, project)
                onComplete(InstallResult.Success(canonicalFamily))
            } catch (e: RuntimeException) {
                LOG.warn("FontPresetApplicator.apply failed", e)
                notify(entry, project, FailureKind.APPLY_FAILED, NotificationType.WARNING)
                onComplete(InstallResult.Success(canonicalFamily))
            }
        }
    }

    @TestOnly
    internal fun persistFontState(
        canonicalFamily: String,
        installedFiles: List<File>,
    ) {
        val state = AyuIslandsSettings.getInstance().state
        state.installedFonts.add(canonicalFamily)
        state.installedFontFiles[canonicalFamily] =
            installedFiles.joinToString("\n") { it.absolutePath }
        // D-09: reinstalling a previously-deleted font clears the guard so future
        // seeder runs treat this family as first-class again.
        state.explicitlyUninstalledFonts.remove(canonicalFamily)
        FontDetector.invalidateCache()
    }

    private fun verifyApplied(
        entry: FontCatalog.Entry,
        canonicalFamily: String,
        project: Project?,
    ) {
        val active =
            com.intellij.openapi.editor.colors.EditorColorsManager
                .getInstance()
                .globalScheme
                .editorFontName
        if (active != entry.familyName && active != canonicalFamily) {
            notify(entry, project, FailureKind.APPLY_FAILED, NotificationType.WARNING)
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

    @TestOnly
    internal fun platformFontDir(): File {
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
