package dev.ayuislands.settings.mappings

import com.intellij.openapi.ui.DialogWrapper
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.color.ProjectIconAccentExtractor
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddProjectMappingDialogTest {
    @field:TempDir
    lateinit var tempDir: Path

    @Test
    fun `validation rejects blank project path`() =
        onEdt {
            val dialog =
                AddProjectMappingDialog(parent = null, excludedPaths = emptySet(), initialHex = "#FFCC66")

            dialog.useDialog {
                it.setPathForTest("   ")

                assertEquals("Enter a project path.", it.validationMessageForTest())
            }
        }

    @Test
    fun `validation rejects missing project directory`() =
        onEdt {
            val missingPath = tempDir.resolve("missing-project")
            val dialog =
                AddProjectMappingDialog(parent = null, excludedPaths = emptySet(), initialHex = "#FFCC66")

            dialog.useDialog {
                it.setPathForTest(missingPath.toString())

                assertEquals("Path is not an existing directory.", it.validationMessageForTest())
            }
        }

    @Test
    fun `validation rejects duplicate project path after canonicalization`() =
        onEdt {
            val projectDir = Files.createDirectory(tempDir.resolve("existing-project")).toFile()
            val dialog =
                AddProjectMappingDialog(
                    parent = null,
                    excludedPaths = setOf(projectDir.canonicalPath.uppercase()),
                    initialHex = "#FFCC66",
                )

            dialog.useDialog {
                it.setPathForTest(projectDir.path)

                assertEquals("This project already has an override.", it.validationMessageForTest())
            }
        }

    @Test
    fun `validation rejects missing accent color`() =
        onEdt {
            val projectDir = Files.createDirectory(tempDir.resolve("project-without-color"))
            val dialog = AddProjectMappingDialog(parent = null, excludedPaths = emptySet())

            dialog.useDialog {
                it.setPathForTest(projectDir.toString())

                assertEquals("Choose an accent color.", it.validationMessageForTest())
            }
        }

    @Test
    fun `valid project override returns canonical path display name and color`() =
        onEdt {
            val projectDir = Files.createDirectory(tempDir.resolve("project-override")).toFile()
            val dialog = AddProjectMappingDialog(parent = null, excludedPaths = emptySet())
            var confirmed = false

            try {
                dialog.setPathForTest(projectDir.path)
                dialog.selectHexForTest("#FFCC66")

                assertNull(dialog.validationMessageForTest())

                dialog.confirmForTest()
                confirmed = true

                assertEquals(projectDir.canonicalPath, dialog.resultCanonicalPath)
                assertEquals(projectDir.name, dialog.resultDisplayName)
                assertEquals("#FFCC66", dialog.resultHex)
            } finally {
                if (!confirmed) {
                    dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
            }
        }

    @Test
    fun `icon color shortcut appears only for projects with a usable icon`() {
        val projectDir = Files.createDirectory(tempDir.resolve("icon-project"))
        writeIcon(projectDir, Color(0x5C, 0xCF, 0xE6))
        val plainDir = Files.createDirectory(tempDir.resolve("plain-project"))
        lateinit var dialog: AddProjectMappingDialog
        onEdt {
            dialog = AddProjectMappingDialog(parent = null, excludedPaths = emptySet())
            dialog.setPathForTest(projectDir.toString())
        }

        try {
            dialog.drainIconTasks()
            onEdt {
                assertTrue(
                    dialog.iconLinkVisibleForTest(),
                    "icon shortcut must appear for a project with .idea/icon.png",
                )
                dialog.useIconColorForTest()
            }
            dialog.drainIconTasks()
            onEdt {
                assertEquals("#5CCFE6", dialog.resultHex, "shortcut must fill the icon's dominant color")
                assertNull(dialog.validationMessageForTest(), "icon-derived color satisfies color validation")
                dialog.setPathForTest(plainDir.toString())
            }
            dialog.drainIconTasks()
            onEdt {
                assertFalse(
                    dialog.iconLinkVisibleForTest(),
                    "icon shortcut must hide when the project has no icon",
                )
            }
        } finally {
            onEdt { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
        }
    }

    @Test
    fun `late icon extraction cannot overwrite a newly selected project`() {
        val iconProject = Files.createDirectory(tempDir.resolve("slow-icon-project"))
        val iconFile = writeIcon(iconProject, Color(0x5C, 0xCF, 0xE6))
        val plainProject = Files.createDirectory(tempDir.resolve("replacement-project"))
        val extractionStarted = CountDownLatch(1)
        val allowExtraction = CountDownLatch(1)
        var openedDialog: AddProjectMappingDialog? = null
        mockkObject(ProjectIconAccentExtractor)
        every { ProjectIconAccentExtractor.extract(iconFile) } answers {
            extractionStarted.countDown()
            check(allowExtraction.await(5, TimeUnit.SECONDS)) { "Timed out waiting for the replacement path" }
            AccentHex.of("#5CCFE6")
        }

        try {
            onEdt {
                val dialog =
                    AddProjectMappingDialog(
                        parent = null,
                        excludedPaths = emptySet(),
                        initialHex = "#FFCC66",
                    )
                openedDialog = dialog
                dialog.setPathForTest(iconProject.toString())
            }
            val dialog = requireNotNull(openedDialog)
            dialog.drainIconTasks()
            onEdt { dialog.useIconColorForTest() }
            assertTrue(extractionStarted.await(5, TimeUnit.SECONDS), "Icon extraction did not start")

            onEdt { dialog.setPathForTest(plainProject.toString()) }
            allowExtraction.countDown()
            dialog.drainIconTasks()

            onEdt {
                assertFalse(dialog.iconLinkVisibleForTest())
                assertEquals("#FFCC66", dialog.resultHex, "stale icon color must not replace the user's selection")
            }
        } finally {
            allowExtraction.countDown()
            openedDialog?.let { dialog -> onEdt { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) } }
            unmockkObject(ProjectIconAccentExtractor)
        }
    }

    @Test
    fun `late icon extraction cannot overwrite a newer manual color`() {
        val iconProject = Files.createDirectory(tempDir.resolve("manual-color-project"))
        val iconFile = writeIcon(iconProject, Color(0x5C, 0xCF, 0xE6))
        val extractionStarted = CountDownLatch(1)
        val allowExtraction = CountDownLatch(1)
        var openedDialog: AddProjectMappingDialog? = null
        mockkObject(ProjectIconAccentExtractor)
        every { ProjectIconAccentExtractor.extract(iconFile) } answers {
            extractionStarted.countDown()
            check(allowExtraction.await(5, TimeUnit.SECONDS)) { "Timed out waiting for the manual color" }
            AccentHex.of("#5CCFE6")
        }

        try {
            onEdt {
                val dialog = AddProjectMappingDialog(parent = null, excludedPaths = emptySet())
                openedDialog = dialog
                dialog.setPathForTest(iconProject.toString())
            }
            val dialog = requireNotNull(openedDialog)
            dialog.drainIconTasks()
            onEdt { dialog.useIconColorForTest() }
            assertTrue(extractionStarted.await(5, TimeUnit.SECONDS), "Icon extraction did not start")

            onEdt { dialog.selectHexForTest("#FFCC66") }
            allowExtraction.countDown()
            dialog.drainIconTasks()

            onEdt {
                assertEquals("#FFCC66", dialog.resultHex, "a stale icon color must not replace a manual selection")
                assertTrue(dialog.iconLinkEnabledForTest(), "the icon shortcut must recover after stale extraction")
                assertNull(dialog.validationMessageForTest())
            }
        } finally {
            allowExtraction.countDown()
            openedDialog?.let { dialog -> onEdt { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) } }
            unmockkObject(ProjectIconAccentExtractor)
        }
    }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
            return
        }

        try {
            SwingUtilities.invokeAndWait(action)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
    }

    private inline fun AddProjectMappingDialog.useDialog(block: (AddProjectMappingDialog) -> Unit) {
        try {
            block(this)
        } finally {
            close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    private fun writeIcon(
        projectDir: Path,
        color: Color,
    ): File {
        val ideaDir = Files.createDirectories(projectDir.resolve(".idea"))
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until image.width) {
            for (y in 0 until image.height) image.setRGB(x, y, color.rgb)
        }
        return ideaDir.resolve("icon.png").toFile().also { ImageIO.write(image, "png", it) }
    }
}
