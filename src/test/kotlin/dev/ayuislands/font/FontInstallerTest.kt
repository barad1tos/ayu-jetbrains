package dev.ayuislands.font

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [FontInstaller] helpers. The full [FontInstaller.install] pipeline
 * requires a real [com.intellij.openapi.project.Project] and live HTTP, so it is
 * tested only indirectly by exercising the internal helpers that `runPipeline`
 * delegates to.
 */
class FontInstallerTest {
    private val tmpRoot: File = createTempDirectory("ayu-font-installer-test").toFile()

    @AfterTest
    fun cleanup() {
        unmockkAll()
        tmpRoot.deleteRecursively()
    }

    private fun makeZip(
        name: String,
        entries: Map<String, ByteArray>,
    ): File {
        val file = File(tmpRoot, name)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((entryName, data) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `platformFontDir returns OS-specific path for current platform`() {
        val dir = FontInstaller.platformFontDir()
        val path = dir.absolutePath
        when {
            SystemInfo.isMac -> {
                assertTrue(
                    path.contains("Library/Fonts"),
                    "macOS platform font dir should contain 'Library/Fonts', got: $path",
                )
            }
            SystemInfo.isWindows -> {
                assertTrue(
                    path.contains("Fonts", ignoreCase = true),
                    "Windows platform font dir should contain 'Fonts', got: $path",
                )
            }
            else -> {
                assertTrue(
                    path.contains(".local/share/fonts"),
                    "Linux platform font dir should contain '.local/share/fonts', got: $path",
                )
            }
        }
    }

    @Test
    fun `cachedZipFile derives filename from URL`() {
        mockkStatic(PathManager::class)
        every { PathManager.getTempPath() } returns tmpRoot.absolutePath

        val url = "https://github.com/x/y/releases/download/v1/font.zip"
        val file = FontInstaller.cachedZipFile(url, FontPreset.AMBIENT)

        assertEquals("font.zip", file.name)
        assertTrue(file.parentFile.absolutePath.endsWith("ayu-fonts"))
    }

    @Test
    fun `cachedZipFile falls back to preset name for URL without filename`() {
        mockkStatic(PathManager::class)
        every { PathManager.getTempPath() } returns tmpRoot.absolutePath

        val url = "https://example.com/path/"
        val file = FontInstaller.cachedZipFile(url, FontPreset.WHISPER)

        assertEquals("WHISPER.zip", file.name)
    }

    @Test
    fun `downloadFailureKind maps UnknownHostException to OFFLINE`() {
        val kind = FontInstaller.downloadFailureKind(UnknownHostException("no DNS"))
        assertEquals(FontInstaller.FailureKind.OFFLINE, kind)
    }

    @Test
    fun `downloadFailureKind maps SocketException to OFFLINE`() {
        val kind = FontInstaller.downloadFailureKind(SocketException("connection refused"))
        assertEquals(FontInstaller.FailureKind.OFFLINE, kind)
    }

    @Test
    fun `downloadFailureKind maps SSLException to OFFLINE`() {
        val kind = FontInstaller.downloadFailureKind(SSLException("handshake failed"))
        assertEquals(FontInstaller.FailureKind.OFFLINE, kind)
    }

    @Test
    fun `downloadFailureKind maps generic IOException to HTTP_ERROR`() {
        val kind = FontInstaller.downloadFailureKind(IOException("500 server error"))
        assertEquals(FontInstaller.FailureKind.HTTP_ERROR, kind)
    }

    @Test
    fun `extractionFailureKind maps No matching files cause to ASSET_NOT_FOUND`() {
        val cause = FontArchiveException("No matching files in archive")
        val exception = IOException(cause.message, cause)
        val zipFile = File(tmpRoot, "dummy.zip").apply { writeBytes(ByteArray(4)) }

        val kind = FontInstaller.extractionFailureKind(exception, zipFile)

        assertEquals(FontInstaller.FailureKind.ASSET_NOT_FOUND, kind)
        // ASSET_NOT_FOUND path must not touch the cached zip
        assertTrue(zipFile.exists(), "ASSET_NOT_FOUND must not delete cached zip")
    }

    @Test
    fun `extractionFailureKind on ZipException deletes corrupt cache`() {
        val zipFile = File(tmpRoot, "corrupt.zip").apply { writeBytes(ByteArray(8)) }
        assertTrue(zipFile.exists())

        val exception = IOException("wrap", ZipException("bad central directory"))
        val kind = FontInstaller.extractionFailureKind(exception, zipFile)

        assertEquals(FontInstaller.FailureKind.EXTRACTION_FAILED, kind)
        assertFalse(zipFile.exists(), "Corrupt cache zip should be deleted on ZipException")
    }

    @Test
    fun `extractionFailureKind on generic IOException keeps cache`() {
        val zipFile = File(tmpRoot, "ok.zip").apply { writeBytes(ByteArray(4)) }
        val exception = IOException("transient IO glitch")

        val kind = FontInstaller.extractionFailureKind(exception, zipFile)

        assertEquals(FontInstaller.FailureKind.EXTRACTION_FAILED, kind)
        assertTrue(zipFile.exists(), "Non-zip IOException should not delete cache")
    }

    @Test
    fun `extractFonts extracts only matching font files`() {
        val payload = "fontdata".toByteArray()
        val zip =
            makeZip(
                "maple.zip",
                mapOf(
                    "MapleMono-Regular.ttf" to payload,
                    "README.md" to "docs".toByteArray(),
                ),
            )
        val extractDir = File(tmpRoot, "extracted").apply { mkdirs() }
        val entry = FontCatalog.forPreset(FontPreset.AMBIENT)

        val extracted = FontInstaller.extractFonts(zip, extractDir, entry)

        assertEquals(1, extracted.size)
        assertEquals("MapleMono-Regular.ttf", extracted[0].name)
        assertTrue(extracted[0].exists())
    }

    @Test
    fun `extractFonts wraps No matching files in IOException with FontArchiveException cause`() {
        val zip =
            makeZip(
                "empty.zip",
                mapOf("README.md" to "docs".toByteArray()),
            )
        val extractDir = File(tmpRoot, "extracted-empty").apply { mkdirs() }
        val entry = FontCatalog.forPreset(FontPreset.AMBIENT)

        val thrown =
            runCatching { FontInstaller.extractFonts(zip, extractDir, entry) }
                .exceptionOrNull()

        assertTrue(thrown is IOException, "Expected IOException, got: $thrown")
        assertTrue(
            thrown.cause is FontArchiveException,
            "IOException cause should be FontArchiveException, got: ${thrown.cause}",
        )
        // Feed the wrapped exception back into extractionFailureKind to verify the full loop.
        assertEquals(
            FontInstaller.FailureKind.ASSET_NOT_FOUND,
            FontInstaller.extractionFailureKind(thrown, zip),
        )
    }

    @Test
    fun `copyToPlatformFontDir copies source files into dest dir`() {
        val destDir = File(tmpRoot, "fake-platform-fonts").apply { mkdirs() }
        val source = File(tmpRoot, "test-font.ttf").apply { writeBytes(ByteArray(16) { it.toByte() }) }

        val copied = FontInstaller.copyToPlatformFontDir(listOf(source), destDir = destDir)

        assertEquals(1, copied.size)
        assertEquals("test-font.ttf", copied[0].name)
        assertEquals(destDir.absolutePath, copied[0].parentFile.absolutePath)
        assertTrue(copied[0].exists(), "Copied file must exist in dest dir")
        assertEquals(source.length(), copied[0].length())
    }

    @Test
    fun `copyToPlatformFontDir throws AccessDeniedException when dest not writable`() {
        val destDir =
            File(tmpRoot, "readonly-dest").apply {
                mkdirs()
                setWritable(false)
            }
        val source = File(tmpRoot, "src.ttf").apply { writeBytes(ByteArray(4)) }

        try {
            val thrown =
                runCatching { FontInstaller.copyToPlatformFontDir(listOf(source), destDir = destDir) }
                    .exceptionOrNull()
            assertTrue(
                thrown is java.nio.file.AccessDeniedException,
                "Expected AccessDeniedException, got: $thrown",
            )
        } finally {
            destDir.setWritable(true)
        }
    }

    @Test
    fun `cleanupQuietly removes directory recursively`() {
        val dir =
            File(tmpRoot, "to-cleanup").apply {
                mkdirs()
                File(this, "nested.txt").writeText("hi")
            }
        assertTrue(dir.exists())

        FontInstaller.cleanupQuietly(dir)

        assertFalse(dir.exists(), "Directory should be removed")
    }

    @Test
    fun `applyOnly invokes FontPresetApplicator inside invokeLater`() {
        mockkStatic(ApplicationManager::class)
        mockkObject(FontPresetApplicator)
        val appMock = mockk<Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { FontPresetApplicator.apply(any()) } answers { /* no-op */ }

        FontInstaller.applyOnly(FontPreset.AMBIENT, project = null)

        verify(exactly = 1) { FontPresetApplicator.apply(any()) }
    }

    @Test
    fun `applyOnly notifies user when apply fails (regression for silent warn)`() {
        mockkStatic(ApplicationManager::class)
        mockkStatic(Notifications.Bus::class)
        mockkObject(FontPresetApplicator)
        val appMock = mockk<Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { FontPresetApplicator.apply(any()) } throws RuntimeException("boom")
        every { Notifications.Bus.notify(any(), null) } answers { }

        FontInstaller.applyOnly(FontPreset.AMBIENT, project = null)

        verify(exactly = 1) {
            Notifications.Bus.notify(
                match<Notification> { it.content.contains("Editor → Font") },
                null,
            )
        }
    }
}
