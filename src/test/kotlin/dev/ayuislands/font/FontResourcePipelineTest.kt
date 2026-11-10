package dev.ayuislands.font

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FontResourcePipelineTest {
    @TempDir lateinit var directory: Path

    private val tasks = mutableListOf<Task.Backgroundable>()
    private val entry = FontCatalog.requirePreset(FontPreset.WHISPER)

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        val application = mockk<Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns application
        every { application.invokeLater(any<Runnable>()) } answers { firstArg<Runnable>().run() }
        mockkStatic(ProgressManager::class)
        val progress = mockk<ProgressManager>(relaxed = true)
        every { ProgressManager.getInstance() } returns progress
        every { progress.run(any<Task.Backgroundable>()) } answers { tasks.add(firstArg()) }
        mockkStatic(PathManager::class)
        every { PathManager.getTempPath() } returns directory.toString()
        mockkStatic(Notifications.Bus::class)
        every { Notifications.Bus.notify(any<Notification>(), null) } answers { }
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        mockkObject(FontInstaller)
        every { FontInstaller.platformFontDir() } returns directory.resolve("platform-fonts").toFile()
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } throws
            AssertionError("Resource pipeline must not access editor schemes")
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } throws
            AssertionError("Resource pipeline must not access legacy state")
    }

    @AfterTest
    fun cleanup() = unmockkAll()

    @Test
    fun `resource installation returns actual family and copied paths without a legacy or appearance write`() {
        val bytes = byteArrayOf(1, 7, 4, 9)
        seedArchive(bytes)
        every { FontInstaller.registerFont(any()) } returns "Dialog"
        var completion: FontInstaller.InstallResult? = null

        FontInstaller.installResource(entry, consent(), null) { completion = it }
        tasks.single().run(mockk(relaxed = true))

        val installed = assertIs<FontInstaller.InstallResult.Success>(completion)
        val path = directory.resolve("platform-fonts/VictorMono-Light.ttf")
        assertEquals("Dialog", installed.familyName)
        assertTrue(installed.availableInJvm)
        assertEquals(listOf(path.toString()), installed.installedPaths)
        assertTrue(path.toFile().readBytes().contentEquals(bytes))
        verify(exactly = 0) { AyuIslandsSettings.getInstance() }
        verify(exactly = 0) { EditorColorsManager.getInstance() }
    }

    @Test
    fun `resource installation preserves exact files when the registered family still requires restart`() {
        seedArchive(byteArrayOf(2, 3))
        every { FontInstaller.registerFont(any()) } returns "Ayu Test Family Not Visible To JVM"
        var completion: FontInstaller.InstallResult? = null

        FontInstaller.installResource(entry, consent(), null) { completion = it }
        tasks.single().run(mockk(relaxed = true))

        val installed = assertIs<FontInstaller.InstallResult.Success>(completion)
        assertFalse(installed.availableInJvm)
        assertEquals(
            listOf(directory.resolve("platform-fonts/VictorMono-Light.ttf").toString()),
            installed.installedPaths,
        )
        assertTrue(installed.installedPaths.all { File(it).exists() })
        verify(exactly = 0) { AyuIslandsSettings.getInstance() }
        verify(exactly = 0) { EditorColorsManager.getInstance() }
    }

    @Test
    fun `resource removal deletes only explicit accepted paths and preserves native appearance ownership`() {
        val platform = directory.resolve("platform-fonts").toFile().apply { mkdirs() }
        val owned = File(platform, "owned.ttf").apply { writeText("owned") }
        val foreign = File(platform, "untracked.ttf").apply { writeText("foreign") }
        var completion: FontUninstaller.UninstallResult? = null

        FontUninstaller.uninstallResource(entry, "Actual Installed Alias", listOf(owned.absolutePath), null) {
            completion =
                it
        }
        tasks.single().run(mockk(relaxed = true))

        assertEquals(FontUninstaller.UninstallResult.Success("Actual Installed Alias", 1), completion)
        assertFalse(owned.exists())
        assertEquals("foreign", foreign.readText())
        verify(exactly = 0) { AyuIslandsSettings.getInstance() }
        verify(exactly = 0) { EditorColorsManager.getInstance() }
    }

    @Test
    fun `resource removal rejects the whole request before deleting any path outside the platform folder`() {
        val platform = directory.resolve("platform-fonts").toFile().apply { mkdirs() }
        val owned = File(platform, "owned.ttf").apply { writeText("owned") }
        val outside = directory.resolve("outside.ttf").toFile().apply { writeText("outside") }
        var completion: FontUninstaller.UninstallResult? = null

        FontUninstaller.uninstallResource(
            entry,
            entry.familyName,
            listOf(owned.absolutePath, outside.absolutePath),
            null,
        ) {
            completion =
                it
        }
        tasks.single().run(mockk(relaxed = true))

        assertIs<FontUninstaller.UninstallResult.Failure>(completion)
        assertEquals("owned", owned.readText())
        assertEquals("outside", outside.readText())
        verify(exactly = 0) { AyuIslandsSettings.getInstance() }
        verify(exactly = 0) { EditorColorsManager.getInstance() }
    }

    private fun seedArchive(bytes: ByteArray) {
        val archive = directory.resolve("ayu-fonts/VictorMonoAll.zip").toFile()
        archive.parentFile.mkdirs()
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("TTF/VictorMono-Light.ttf"))
            output.write(bytes)
            output.closeEntry()
        }
    }

    private fun consent(): FontInstallConsent.InstallConsent {
        mockkObject(MessageDialogBuilder.Companion)
        val project = mockk<Project>(relaxed = true)
        val dialog = mockk<MessageDialogBuilder.YesNo>(relaxed = true)
        every { MessageDialogBuilder.yesNo(any<String>(), any<String>()) } returns dialog
        every { dialog.yesText(any()) } returns dialog
        every { dialog.noText(any()) } returns dialog
        every { dialog.ask(project) } returns true
        return requireNotNull(FontInstallConsent.confirmInstall(entry, project))
    }
}
