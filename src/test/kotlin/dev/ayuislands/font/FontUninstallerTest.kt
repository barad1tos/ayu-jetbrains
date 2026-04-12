package dev.ayuislands.font

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [FontUninstaller.runUninstallPipeline] and the public
 * [FontUninstaller.uninstall] dispatcher. Covers D-07 uninstall pipeline,
 * D-08 path-traversal guard (T-25-01), D-09 explicit-uninstall guard.
 */
class FontUninstallerTest {
    @AfterTest
    fun cleanup() {
        unmockkAll()
    }

    /**
     * Stub the heavy dependencies of [FontUninstaller.runUninstallPipeline]:
     * [AyuIslandsSettings], [FontPresetApplicator], [EditorColorsManager],
     * [ApplicationManager.invokeLater] (runs synchronously so test assertions
     * can inspect post-state), and [FontInstaller.platformFontDir] pinned to
     * a controlled temp directory.
     */
    private fun stubUninstallPipeline(
        state: AyuIslandsState,
        platformDir: File,
        activeEditorFont: String,
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
    }

    @Test
    fun `uninstall_deletesOnlyTrackedFiles`() {
        val platformDir = createTempDirectory("uninst-only-tracked").toFile()
        try {
            val tracked1 = File(platformDir, "MapleMono-Regular.ttf").apply { writeText("t1") }
            val tracked2 = File(platformDir, "MapleMono-Italic.ttf").apply { writeText("t2") }
            val bystander = File(platformDir, "UnrelatedFont.ttf").apply { writeText("leave me") }

            val state =
                AyuIslandsState().apply {
                    installedFonts.add("Maple Mono")
                    installedFontFiles["Maple Mono"] =
                        "${tracked1.absolutePath}\n${tracked2.absolutePath}"
                }
            stubUninstallPipeline(state, platformDir, activeEditorFont = "JetBrains Mono")

            val entry = FontCatalog.forPreset(FontPreset.AMBIENT)
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            var result: FontUninstaller.UninstallResult? = null
            FontUninstaller.runUninstallPipeline(entry, null, indicator) { result = it }

            assertFalse(tracked1.exists(), "tracked1 should be deleted")
            assertFalse(tracked2.exists(), "tracked2 should be deleted")
            assertTrue(bystander.exists(), "unrelated file must remain")
            assertTrue(result is FontUninstaller.UninstallResult.Success)
            assertEquals(2, (result as FontUninstaller.UninstallResult.Success).filesRemoved)
        } finally {
            platformDir.deleteRecursively()
        }
    }

    @Test
    fun `uninstall_mutatesStateCorrectly`() {
        val platformDir = createTempDirectory("uninst-state").toFile()
        try {
            val trackedFile = File(platformDir, "MapleMono.ttf").apply { writeText("x") }
            val state =
                AyuIslandsState().apply {
                    installedFonts.add("Maple Mono")
                    installedFontFiles["Maple Mono"] = trackedFile.absolutePath
                }
            stubUninstallPipeline(state, platformDir, activeEditorFont = "JetBrains Mono")

            val entry = FontCatalog.forPreset(FontPreset.AMBIENT)
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            FontUninstaller.runUninstallPipeline(entry, null, indicator) { }

            assertFalse(state.installedFonts.contains("Maple Mono"), "family must be removed")
            assertFalse(state.installedFontFiles.containsKey("Maple Mono"), "path entry must be cleared")
            assertTrue(
                state.explicitlyUninstalledFonts.contains("Maple Mono"),
                "family must be added to D-09 guard",
            )
        } finally {
            platformDir.deleteRecursively()
        }
    }

    @Test
    fun `uninstall_revertsActiveFont`() {
        val platformDir = createTempDirectory("uninst-revert").toFile()
        try {
            val trackedFile = File(platformDir, "MapleMono.ttf").apply { writeText("x") }
            val state =
                AyuIslandsState().apply {
                    installedFonts.add("Maple Mono")
                    installedFontFiles["Maple Mono"] = trackedFile.absolutePath
                }
            stubUninstallPipeline(state, platformDir, activeEditorFont = "Maple Mono")

            val entry = FontCatalog.forPreset(FontPreset.AMBIENT)
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            FontUninstaller.runUninstallPipeline(entry, null, indicator) { }

            verify(exactly = 1) { FontPresetApplicator.revert() }
        } finally {
            platformDir.deleteRecursively()
        }
    }

    @Test
    fun `uninstall_leavesInactiveFontAlone`() {
        val platformDir = createTempDirectory("uninst-leave-alone").toFile()
        try {
            val trackedFile = File(platformDir, "MapleMono.ttf").apply { writeText("x") }
            val state =
                AyuIslandsState().apply {
                    installedFonts.add("Maple Mono")
                    installedFontFiles["Maple Mono"] = trackedFile.absolutePath
                }
            stubUninstallPipeline(state, platformDir, activeEditorFont = "JetBrains Mono")

            val entry = FontCatalog.forPreset(FontPreset.AMBIENT)
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            FontUninstaller.runUninstallPipeline(entry, null, indicator) { }

            verify(exactly = 0) { FontPresetApplicator.revert() }
        } finally {
            platformDir.deleteRecursively()
        }
    }

    @Test
    fun `uninstall_rejectsPathOutsidePlatformDir`() {
        val platformDir = createTempDirectory("uninst-traversal").toFile()
        val outsideDir = createTempDirectory("uninst-outside").toFile()
        try {
            val outsideFile = File(outsideDir, "should-survive.ttf").apply { writeText("safe") }
            val escapePath = outsideFile.absolutePath
            val state =
                AyuIslandsState().apply {
                    installedFonts.add("Maple Mono")
                    installedFontFiles["Maple Mono"] = escapePath
                }
            stubUninstallPipeline(state, platformDir, activeEditorFont = "JetBrains Mono")

            val entry = FontCatalog.forPreset(FontPreset.AMBIENT)
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            var result: FontUninstaller.UninstallResult? = null
            FontUninstaller.runUninstallPipeline(entry, null, indicator) { result = it }

            assertTrue(
                result is FontUninstaller.UninstallResult.Failure,
                "Expected Failure, got $result",
            )
            assertTrue(outsideFile.exists(), "File outside platformDir must not be deleted")
            assertTrue(
                state.installedFonts.contains("Maple Mono"),
                "State must not mutate on guard rejection",
            )
            assertFalse(
                state.explicitlyUninstalledFonts.contains("Maple Mono"),
                "Guard must only be set on successful/partial uninstall, never rejection",
            )
        } finally {
            platformDir.deleteRecursively()
            outsideDir.deleteRecursively()
        }
    }

    @Test
    fun `uninstall_handlesPermissionDenied`() {
        val platformDir = createTempDirectory("uninst-permission").toFile()
        try {
            val stubbornDir = File(platformDir, "locked").apply { mkdirs() }
            val stubbornFile = File(stubbornDir, "Maple-Locked.ttf").apply { writeText("x") }
            stubbornDir.setWritable(false)

            val state =
                AyuIslandsState().apply {
                    installedFonts.add("Maple Mono")
                    installedFontFiles["Maple Mono"] = stubbornFile.absolutePath
                }
            stubUninstallPipeline(state, platformDir, activeEditorFont = "JetBrains Mono")

            val entry = FontCatalog.forPreset(FontPreset.AMBIENT)
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            var result: FontUninstaller.UninstallResult? = null
            try {
                FontUninstaller.runUninstallPipeline(entry, null, indicator) { result = it }
            } finally {
                stubbornDir.setWritable(true)
            }

            // State MUST still mutate even on partial failure — user is not stuck.
            assertFalse(state.installedFonts.contains("Maple Mono"))
            assertTrue(state.explicitlyUninstalledFonts.contains("Maple Mono"))
            assertTrue(
                result is FontUninstaller.UninstallResult.Partial ||
                    result is FontUninstaller.UninstallResult.Success,
                "Expected Partial or Success, got $result",
            )
        } finally {
            platformDir.setWritable(true)
            platformDir.deleteRecursively()
        }
    }

    @Test
    fun `uninstall_emptyFileList_stillMutatesState`() {
        val platformDir = createTempDirectory("uninst-legacy").toFile()
        try {
            val state =
                AyuIslandsState().apply {
                    installedFonts.add("Maple Mono")
                }
            stubUninstallPipeline(state, platformDir, activeEditorFont = "JetBrains Mono")

            val entry = FontCatalog.forPreset(FontPreset.AMBIENT)
            val indicator = mockk<ProgressIndicator>(relaxed = true)
            var result: FontUninstaller.UninstallResult? = null
            FontUninstaller.runUninstallPipeline(entry, null, indicator) { result = it }

            assertFalse(state.installedFonts.contains("Maple Mono"))
            assertTrue(state.explicitlyUninstalledFonts.contains("Maple Mono"))
            assertTrue(result is FontUninstaller.UninstallResult.Success)
            assertEquals(0, (result as FontUninstaller.UninstallResult.Success).filesRemoved)
        } finally {
            platformDir.deleteRecursively()
        }
    }

    @Test
    fun `uninstall_publicEntryPoint_queuesTaskBackgroundable`() {
        mockkObject(FontInstaller)
        every { FontInstaller.platformFontDir() } returns createTempDirectory("uninst-public").toFile()

        mockkStatic(ProgressManager::class)
        val progressMgr = mockk<ProgressManager>(relaxed = true)
        every { ProgressManager.getInstance() } returns progressMgr

        FontUninstaller.uninstall(FontPreset.AMBIENT, null) { }

        verify(exactly = 1) { progressMgr.run(any<Task.Backgroundable>()) }
    }
}
