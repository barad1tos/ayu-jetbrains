package dev.ayuislands.font

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FontInstallResourceTest {
    private val directory = createTempDirectory("ayu-font-resource").toFile()
    private val entry = FontCatalog.requirePreset(FontPreset.WHISPER)
    private val state =
        AyuIslandsState().apply {
            fontPresetName = "CUSTOM"
            fontPresetEnabled = false
            fontApplyToConsole = true
            fontPresetCustomizations["CUSTOM"] = "17|1.7|false|Light|Personal Mono"
            explicitlyUninstalledFonts.add(entry.familyName)
        }
    private val tasks = mutableListOf<Task.Backgroundable>()
    private val callbacks = mutableListOf<Runnable>()
    private val results = mutableListOf<FontInstaller.InstallResult>()
    private val environment = mockk<GraphicsEnvironment>()

    @BeforeTest
    fun setUp() {
        mockkObject(LicenseChecker, FontInstaller, AyuIslandsSettings.Companion)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        val settings = mockk<AyuIslandsSettings>()
        every { AyuIslandsSettings.getInstance() } returns settings
        every { settings.state } answers {
            dev.ayuislands.studio.StudioFixtures
                .legacyView(state)
                .settings
        }
        every { FontInstaller.platformFontDir() } returns File(directory, "installed")
        every { FontInstaller.registerFont(any()) } returns entry.familyName
        mockkStatic(ApplicationManager::class, PathManager::class, ProgressManager::class)
        mockkStatic(GraphicsEnvironment::class, EditorColorsManager::class, Notifications.Bus::class)
        val application = mockk<Application>(relaxed = true)
        val progress = mockk<ProgressManager>(relaxed = true)
        val colors = mockk<EditorColorsManager>(relaxed = true)
        every { ApplicationManager.getApplication() } returns application
        every { application.invokeLater(any()) } answers { callbacks += firstArg<Runnable>() }
        every { PathManager.getTempPath() } returns directory.absolutePath
        every { ProgressManager.getInstance() } returns progress
        every { progress.run(any<Task.Backgroundable>()) } answers { tasks += firstArg<Task.Backgroundable>() }
        every { GraphicsEnvironment.getLocalGraphicsEnvironment() } returns environment
        every { environment.availableFontFamilyNames } returns arrayOf(entry.familyName)
        every { EditorColorsManager.getInstance() } returns colors
        every { colors.globalScheme.editorFontName } returns entry.familyName
        every { Notifications.Bus.notify(any<Notification>(), null) } answers { }
        val archive = FontInstaller.cachedZipFile(entry.fallbackUrl, entry.preset)
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("TTF/VictorMono-Light.ttf"))
            it.write("fixture-font".toByteArray())
            it.closeEntry()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        FontDetector.invalidateCache()
        directory.deleteRecursively()
    }

    @Test
    fun `resource install returns the family and files without recording inventory or applying a preset`() {
        FontInstaller.installResource(entry, consent(), null) { results += it }

        finishInstallation()

        val installed = assertIs<FontInstaller.InstallResult.Success>(results.single())
        assertEquals(entry.familyName, installed.familyName)
        assertTrue(installed.availableInJvm)
        val files = installed.installedPaths
        assertEquals(listOf(File(directory, "installed/VictorMono-Light.ttf").absolutePath), files)
        assertEquals("fixture-font", File(files.single()).readText())
        assertInventoryUnchanged()
        assertPreferencesUnchanged()
    }

    @Test
    fun `registered family missing from JVM dropdown remains installed without applying`() {
        every { environment.availableFontFamilyNames } returns emptyArray()
        FontInstaller.installResource(entry, consent(), null) { results += it }

        finishInstallation()

        val installed = assertIs<FontInstaller.InstallResult.Success>(results.single())
        assertFalse(installed.availableInJvm)
        assertTrue(installed.installedPaths.all { File(it).readText() == "fixture-font" })
        assertEquals(listOf(File(directory, "installed/VictorMono-Light.ttf").absolutePath), installed.installedPaths)
        assertInventoryUnchanged()
        assertPreferencesUnchanged()
    }

    @Test
    fun `resource install preserves the existing premium gate before background work`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false

        FontInstaller.installResource(entry, consent(), null) { results += it }
        callbacks.toList().forEach(Runnable::run)

        assertEquals(
            FontInstaller.FailureKind.PREMIUM_REQUIRED,
            assertIs<FontInstaller.InstallResult.Failure>(results.single()).kind,
        )
        assertTrue(tasks.isEmpty())
        assertTrue(state.installedFonts.isEmpty())
        assertTrue(entry.familyName in state.explicitlyUninstalledFonts)
        assertPreferencesUnchanged()
    }

    @Test
    fun `cancelled download finishes the resource job without applying or recording a font`() {
        val indicator = EmptyProgressIndicator()
        every { FontInstaller.downloadZip(any(), any(), indicator) } answers {
            indicator.cancel()
            indicator.checkCanceled()
        }
        FontInstaller.installResource(entry, consent(), null) { results += it }

        assertFailsWith<ProcessCanceledException> { tasks.single().run(indicator) }
        tasks.single().onCancel()

        assertEquals(listOf<FontInstaller.InstallResult>(FontInstaller.InstallResult.Cancelled), results)
        assertTrue(state.installedFonts.isEmpty())
        assertTrue(callbacks.isEmpty())
        assertPreferencesUnchanged()
    }

    @Test
    fun `cancellation after registration preserves the queued installation result exactly once`() {
        FontInstaller.installResource(entry, consent(), null) { results += it }
        tasks.single().run(mockk(relaxed = true))

        tasks.single().onCancel()
        assertTrue(results.isEmpty())
        callbacks.toList().forEach(Runnable::run)

        val installed = assertIs<FontInstaller.InstallResult.Success>(results.single())
        assertEquals(listOf(File(directory, "installed/VictorMono-Light.ttf").absolutePath), installed.installedPaths)
        assertEquals("fixture-font", File(installed.installedPaths.single()).readText())
        assertInventoryUnchanged()
        assertPreferencesUnchanged()
    }

    private fun assertInventoryUnchanged() {
        assertTrue(state.installedFonts.isEmpty())
        assertTrue(state.installedFontFiles.isEmpty())
        assertEquals(setOf(entry.familyName), state.explicitlyUninstalledFonts)
        verify(exactly = 0) { AyuIslandsSettings.getInstance() }
    }

    private fun finishInstallation() {
        tasks.single().run(mockk(relaxed = true))
        assertTrue(results.isEmpty())
        assertTrue(state.installedFonts.isEmpty())
        callbacks.toList().forEach(Runnable::run)
    }

    private fun assertPreferencesUnchanged() {
        assertFalse(state.fontPresetEnabled)
        assertEquals("CUSTOM", state.fontPresetName)
        assertTrue(state.fontApplyToConsole)
        assertEquals(mapOf("CUSTOM" to "17|1.7|false|Light|Personal Mono"), state.fontPresetCustomizations)
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
