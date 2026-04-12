package dev.ayuislands.font

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.progress.ProgressIndicator
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests that exercise the full font lifecycle as a user would
 * experience it. Each test simulates a complete user scenario end-to-end
 * rather than testing individual methods in isolation.
 */
class FontLifecycleIntegrationTest {
    @AfterTest
    fun cleanup() {
        unmockkAll()
        FontDetector.invalidateCache()
    }

    private fun stubPlatform(
        state: AyuIslandsState,
        platformDir: File,
        activeEditorFont: String = "JetBrains Mono",
        jvmFonts: Array<String> = arrayOf("JetBrains Mono"),
    ) {
        mockkObject(FontInstaller)
        every { FontInstaller.platformFontDir() } returns platformDir

        mockkObject(AyuIslandsSettings.Companion)
        val settings = AyuIslandsSettings().apply { loadState(state) }
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(FontPresetApplicator)
        every { FontPresetApplicator.revert() } just Runs

        mockkStatic(EditorColorsManager::class)
        val ecm = mockk<EditorColorsManager>()
        val scheme = mockk<EditorColorsScheme>()
        every { EditorColorsManager.getInstance() } returns ecm
        every { ecm.globalScheme } returns scheme
        every { scheme.editorFontName } returns activeEditorFont

        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>()
        every { ApplicationManager.getApplication() } returns app
        every { app.invokeLater(any<Runnable>()) } answers { firstArg<Runnable>().run() }

        mockkStatic(Notifications.Bus::class)
        every { Notifications.Bus.notify(any<Notification>(), null) } answers { }

        mockkStatic(GraphicsEnvironment::class)
        val ge = mockk<GraphicsEnvironment>()
        every { GraphicsEnvironment.getLocalGraphicsEnvironment() } returns ge
        every { ge.availableFontFamilyNames } returns jvmFonts

        FontDetector.invalidateCache()
    }

    /**
     * User installs Maple Mono from Settings → verifies it shows as installed →
     * deletes it → verifies it's gone → reinstalls → verifies it's back.
     * This is the golden path: install → delete → reinstall round-trip.
     */
    @Test
    fun `full install-delete-reinstall lifecycle round-trip`() {
        val platformDir = createTempDirectory("lifecycle-roundtrip").toFile()
        try {
            val state = AyuIslandsState()
            stubPlatform(state, platformDir)

            // Step 1: Simulate install — user clicked Install, font downloaded + copied.
            val fontFile = File(platformDir, "MapleMono-Regular.ttf").apply { writeText("font data") }
            FontInstaller.persistFontState("Maple Mono", listOf(fontFile))

            assertTrue(state.installedFonts.contains("Maple Mono"), "Font must be in installedFonts after install")
            assertEquals(fontFile.absolutePath, state.installedFontFiles["Maple Mono"], "File path must be tracked")

            // Step 2: Simulate delete via Settings panel.
            val entry = FontCatalog.forPreset(FontPreset.AMBIENT)
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            var result: FontUninstaller.UninstallResult? = null
            FontUninstaller.runUninstallPipeline(entry, null, indicator) { result = it }

            assertTrue(result is FontUninstaller.UninstallResult.Success, "Uninstall must succeed")
            assertFalse(fontFile.exists(), "Font file must be deleted from disk")
            assertFalse(state.installedFonts.contains("Maple Mono"), "Family must be removed from installedFonts")
            assertTrue(state.explicitlyUninstalledFonts.contains("Maple Mono"), "Family must be in uninstall guard")

            // Step 3: Reinstall — user clicked Install again.
            val newFile = File(platformDir, "MapleMono-Regular.ttf").apply { writeText("reinstalled") }
            FontInstaller.persistFontState("Maple Mono", listOf(newFile))

            assertTrue(state.installedFonts.contains("Maple Mono"), "Font must be back in installedFonts")
            assertFalse(
                state.explicitlyUninstalledFonts.contains("Maple Mono"),
                "Reinstall must clear the D-09 uninstall guard",
            )
        } finally {
            platformDir.deleteRecursively()
        }
    }

    /**
     * User has two fonts installed (Maple Mono + Victor Mono). They delete
     * Maple Mono. Victor Mono must remain completely unaffected — its
     * installedFonts entry, file paths, and files on disk must survive.
     */
    @Test
    fun `deleting one font does not affect another installed font`() {
        val platformDir = createTempDirectory("lifecycle-multi").toFile()
        try {
            val state = AyuIslandsState()
            stubPlatform(state, platformDir)

            // Install both fonts.
            val mapleFile = File(platformDir, "MapleMono-Regular.ttf").apply { writeText("maple") }
            val victorFile = File(platformDir, "VictorMono-Light.ttf").apply { writeText("victor") }
            FontInstaller.persistFontState("Maple Mono", listOf(mapleFile))
            FontInstaller.persistFontState("Victor Mono", listOf(victorFile))

            assertTrue(state.installedFonts.contains("Maple Mono"))
            assertTrue(state.installedFonts.contains("Victor Mono"))

            // Delete Maple Mono only.
            val entry = FontCatalog.forPreset(FontPreset.AMBIENT) // Maple Mono
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            FontUninstaller.runUninstallPipeline(entry, null, indicator) { }

            // Maple Mono gone.
            assertFalse(state.installedFonts.contains("Maple Mono"))
            assertFalse(mapleFile.exists())

            // Victor Mono untouched.
            assertTrue(state.installedFonts.contains("Victor Mono"), "Victor Mono must remain installed")
            assertTrue(victorFile.exists(), "Victor Mono file must survive")
            assertEquals(
                victorFile.absolutePath,
                state.installedFontFiles["Victor Mono"],
                "Victor Mono file paths must be intact",
            )
        } finally {
            platformDir.deleteRecursively()
        }
    }

    /**
     * User installs a font, then externally deletes the font file via Finder
     * or Terminal (not through the plugin). On next Settings open,
     * FontDetector.status() should detect the discrepancy.
     */
    @Test
    fun `external file deletion detected as corrupted when JVM forgets the family`() {
        val platformDir = createTempDirectory("lifecycle-external-del").toFile()
        try {
            val state = AyuIslandsState()
            stubPlatform(state, platformDir, jvmFonts = arrayOf("JetBrains Mono"))

            // Install via plugin.
            val fontFile = File(platformDir, "MapleMono-Regular.ttf").apply { writeText("font") }
            FontInstaller.persistFontState("Maple Mono", listOf(fontFile))

            // User deletes file externally.
            fontFile.delete()

            // Settings panel opens and calls FontDetector.status().
            FontDetector.invalidateCache()
            val status = FontDetector.status(FontPreset.AMBIENT)

            assertEquals(
                FontStatus.CORRUPTED,
                status,
                "Status must be CORRUPTED when file is gone and JVM does not see the family",
            )
        } finally {
            platformDir.deleteRecursively()
        }
    }

    /**
     * User deletes a font, then restarts the IDE. The seeder runs on startup.
     * Even if the JVM still sees the font (cached registration), the seeder
     * must NOT re-add it because of the D-09 explicit-uninstall guard.
     */
    @Test
    fun `deleted font survives IDE restart seeder`() {
        val platformDir = createTempDirectory("lifecycle-restart").toFile()
        try {
            val state = AyuIslandsState()
            stubPlatform(
                state,
                platformDir,
                jvmFonts = arrayOf("Maple Mono", "JetBrains Mono"),
            )

            // Install + delete via plugin.
            val fontFile = File(platformDir, "MapleMono-Regular.ttf").apply { writeText("font") }
            FontInstaller.persistFontState("Maple Mono", listOf(fontFile))
            val entry = FontCatalog.forPreset(FontPreset.AMBIENT)
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            FontUninstaller.runUninstallPipeline(entry, null, indicator) { }

            // Simulate IDE restart: seeder runs with JVM still seeing "Maple Mono".
            state.installedFontsSeeded = false
            val settings = AyuIslandsSettings().apply { loadState(state) }
            every { AyuIslandsSettings.getInstance() } returns settings
            settings.seedInstalledFontsFromDiskIfNeeded()

            assertFalse(
                state.installedFonts.contains("Maple Mono"),
                "Seeder must NOT re-add a font the user explicitly deleted (D-09)",
            )
            assertTrue(state.installedFontsSeeded, "Seeded flag must be set regardless")
        } finally {
            platformDir.deleteRecursively()
        }
    }

    /**
     * User deletes the currently active editor font. The editor must
     * automatically revert to JetBrains Mono so the user doesn't end up
     * with invisible/broken text.
     */
    @Test
    fun `deleting active editor font triggers automatic revert`() {
        val platformDir = createTempDirectory("lifecycle-revert").toFile()
        try {
            val state = AyuIslandsState()
            stubPlatform(state, platformDir, activeEditorFont = "Maple Mono")

            val fontFile = File(platformDir, "MapleMono-Regular.ttf").apply { writeText("font") }
            FontInstaller.persistFontState("Maple Mono", listOf(fontFile))

            val entry = FontCatalog.forPreset(FontPreset.AMBIENT)
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            FontUninstaller.runUninstallPipeline(entry, null, indicator) { }

            io.mockk.verify(exactly = 1) { FontPresetApplicator.revert() }
        } finally {
            platformDir.deleteRecursively()
        }
    }

    /**
     * User deletes a font they are NOT currently using in the editor.
     * The editor font must NOT change — revert should not fire.
     */
    @Test
    fun `deleting non-active font does not touch editor settings`() {
        val platformDir = createTempDirectory("lifecycle-no-revert").toFile()
        try {
            val state = AyuIslandsState()
            stubPlatform(state, platformDir, activeEditorFont = "JetBrains Mono")

            val fontFile = File(platformDir, "MapleMono-Regular.ttf").apply { writeText("font") }
            FontInstaller.persistFontState("Maple Mono", listOf(fontFile))

            val entry = FontCatalog.forPreset(FontPreset.AMBIENT)
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            FontUninstaller.runUninstallPipeline(entry, null, indicator) { }

            io.mockk.verify(exactly = 0) { FontPresetApplicator.revert() }
        } finally {
            platformDir.deleteRecursively()
        }
    }
}
