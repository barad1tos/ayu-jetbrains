package dev.ayuislands.settings.mappings

import com.intellij.openapi.ui.DialogWrapper
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
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
    fun `icon color shortcut appears only for projects with a usable icon`() =
        onEdt {
            val projectDir = Files.createDirectory(tempDir.resolve("icon-project"))
            val ideaDir = Files.createDirectories(projectDir.resolve(".idea"))
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until 16) {
                for (y in 0 until 16) {
                    image.setRGB(x, y, Color(0x5C, 0xCF, 0xE6).rgb)
                }
            }
            ImageIO.write(image, "png", ideaDir.resolve("icon.png").toFile())
            val plainDir = Files.createDirectory(tempDir.resolve("plain-project"))
            val dialog = AddProjectMappingDialog(parent = null, excludedPaths = emptySet())

            dialog.useDialog {
                it.setPathForTest(projectDir.toString())
                assertTrue(
                    it.iconLinkVisibleForTest(),
                    "icon shortcut must appear for a project with .idea/icon.png",
                )

                it.useIconColorForTest()
                assertEquals("#5CCFE6", it.resultHex, "shortcut must fill the icon's dominant color")
                assertNull(it.validationMessageForTest(), "icon-derived color satisfies color validation")

                it.setPathForTest(plainDir.toString())
                assertFalse(
                    it.iconLinkVisibleForTest(),
                    "icon shortcut must hide when the project has no icon",
                )
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
}
