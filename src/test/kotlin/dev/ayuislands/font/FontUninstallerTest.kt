package dev.ayuislands.font

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FontUninstallerTest {
    @TempDir lateinit var directory: Path
    private val entry = FontCatalog.requirePreset(FontPreset.AMBIENT)
    private val tasks = mutableListOf<Task.Backgroundable>()

    @AfterTest fun cleanup() = unmockkAll()

    @Test fun `public resource removal queues native background work and deletes only tracked files`() {
        val platform = directory.resolve("fonts").toFile().apply { mkdirs() }
        val first = File(platform, "first.ttf").apply { writeText("first") }
        val second = File(platform, "second.ttf").apply { writeText("second") }
        val foreign = File(platform, "foreign.ttf").apply { writeText("leave me") }
        stubPlatform(platform)
        var result: FontUninstaller.UninstallResult? = null
        FontUninstaller.uninstallResource(entry, "Maple Mono", listOf(first.path, second.path), null) { result = it }
        assertEquals(null, result)
        tasks.single().run(mockk(relaxed = true))
        assertEquals(FontUninstaller.UninstallResult.Success("Maple Mono", 2), result)
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertEquals("leave me", foreign.readText())
    }

    @Test fun `empty accepted path list completes with zero removed files`() {
        stubPlatform(directory.toFile())
        assertEquals(FontUninstaller.UninstallResult.Success("Maple Mono", 0), remove(emptyList()))
    }

    @Test fun `relative traversal and absolute outside paths reject the whole removal`() {
        val platform = directory.resolve("fonts").toFile().apply { mkdirs() }
        val tracked = File(platform, "first.ttf").apply { writeText("owned") }
        val outside = directory.resolve("outside.ttf").toFile().apply { writeText("foreign") }
        stubPlatform(platform)
        for (invalid in listOf(outside.path, File(platform, "../outside.ttf").path)) {
            tasks.clear()
            assertIs<FontUninstaller.UninstallResult.Failure>(remove(listOf(tracked.path, invalid)))
            assertEquals("owned", tracked.readText())
            assertEquals("foreign", outside.readText())
        }
    }

    @Test fun `failed deletion preserves exact failed path and still reports successful siblings`() {
        val platform = directory.resolve("fonts").toFile().apply { mkdirs() }
        val removable = File(platform, "first.ttf").apply { writeText("owned") }
        val nonempty = File(platform, "held.ttf").apply { mkdirs() }
        File(nonempty, "child").writeText("cannot delete nonempty directory")
        stubPlatform(platform)
        assertEquals(
            FontUninstaller.UninstallResult.Partial("Maple Mono", listOf(nonempty.path)),
            remove(listOf(removable.path, nonempty.path)),
        )
        assertFalse(removable.exists())
        assertEquals("cannot delete nonempty directory", File(nonempty, "child").readText())
    }

    @Test fun `canonical directory failure completes without deleting anything`() {
        val platform = mockk<File>()
        every { platform.canonicalPath } throws IOException("Unavailable directory")
        stubPlatform(platform)
        val tracked = directory.resolve("font.ttf").toFile().apply { writeText("owned") }
        assertIs<FontUninstaller.UninstallResult.Failure>(remove(listOf(tracked.path)))
        assertEquals("owned", tracked.readText())
    }

    private fun remove(paths: List<String>): FontUninstaller.UninstallResult {
        var result: FontUninstaller.UninstallResult? = null
        FontUninstaller.uninstallResource(entry, "Maple Mono", paths, null) { result = it }
        tasks.last().run(mockk(relaxed = true))
        return requireNotNull(result)
    }

    private fun stubPlatform(platform: File) {
        mockkObject(FontInstaller)
        every { FontInstaller.platformFontDir() } returns platform
        mockkStatic(ApplicationManager::class, ProgressManager::class, Notifications.Bus::class)
        val application = mockk<Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns application
        every { application.invokeLater(any<Runnable>()) } answers { firstArg<Runnable>().run() }
        val progress = mockk<ProgressManager>(relaxed = true)
        every { ProgressManager.getInstance() } returns progress
        every { progress.run(any<Task.Backgroundable>()) } answers { tasks += firstArg<Task.Backgroundable>() }
        every { Notifications.Bus.notify(any<Notification>(), null) } answers { }
    }
}
