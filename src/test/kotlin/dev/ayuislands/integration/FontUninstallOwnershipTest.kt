package dev.ayuislands.integration

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ApplicationRule
import com.intellij.util.ui.UIUtil
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontInstaller
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontUninstaller
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FontUninstallOwnershipTest {
    @get:Rule val applicationRule = ApplicationRule()

    private lateinit var native: FontNativeFixture
    private var activeScheme: EditorColorsScheme
        get() = native.scheme
        set(value) {
            native.scheme = value
        }
    private lateinit var fontDirectory: Path
    private val schemes get() = native.schemes

    @Before
    fun setup() {
        native = FontNativeFixture()
        schemes.clear()
        val parent = EditorColorsManager.getInstance().globalScheme
        activeScheme = EditorColorsSchemeImpl(parent).apply { name = "Personal editor" }
        activeScheme.fontPreferences = preferences("Dialog", 17.5f)
        activeScheme.consoleFontPreferences = preferences("Monospaced", 15.5f)
        schemes.add(activeScheme)
        fontDirectory = createTempDirectory("ayu-uninstall-ownership")
        mockkObject(FontDetector, FontInstaller)
        every { FontInstaller.platformFontDir() } returns fontDirectory.toFile()
        every { FontDetector.invalidateCache() } returns Unit
        every { FontDetector.resolveFamily(any()) } answers { firstArg<FontPreset>().fontFamily }
        val manager = mockk<EditorColorsManager>()
        every { manager.globalScheme } answers { activeScheme }
        every { manager.allSchemes } answers { schemes.toTypedArray() }
        mockkStatic(EditorColorsManager::class, ProgressManager::class, Notifications.Bus::class)
        every { EditorColorsManager.getInstance() } returns manager
        val progress = mockk<ProgressManager>()
        every { ProgressManager.getInstance() } returns progress
        // Run the real uninstall task against temporary files; only host scheduling is replaced.
        every { progress.run(any<Task>()) } answers {
            firstArg<Task>().run(mockk<ProgressIndicator>(relaxed = true))
        }
        every { Notifications.Bus.notify(any<Notification>(), isNull<Project>()) } returns Unit
    }

    @After
    fun cleanup() {
        try {
            SwingUtilities.invokeAndWait { UIUtil.dispatchAllInvocationEvents() }
        } finally {
            unmockkAll()
            fontDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun resourceRemovalPreservesFontsUntilExplicitDisabledCleanup() {
        SwingUtilities.invokeAndWait {
            val baseline = fonts(activeScheme)
            native.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            val inactiveScheme = activeScheme
            activeScheme = (inactiveScheme.clone() as EditorColorsScheme).apply { name = "Other editor" }
            activeScheme.fontPreferences = preferences("DialogInput", 18.5f)
            activeScheme.consoleFontPreferences = preferences("Serif", 16.5f)
            schemes.add(activeScheme)
            val otherBaseline = fonts(activeScheme)
            native.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            // The user independently changes the active editor, leaving its console owned by Ayu.
            activeScheme.fontPreferences = preferences("Personal unavailable mono", 21.5f)
            val manualEditor = FontData.capture(activeScheme.fontPreferences)
            reloadSettings()

            val beforeRemoval = schemes.associateWith(::fonts)
            val ownership = native.state.fontOwnershipSnapshots.toMap()
            uninstallMaple()
            assertEquals(beforeRemoval, schemes.associateWith(::fonts))
            assertEquals(ownership, native.state.fontOwnershipSnapshots)
            native.disable()

            assertEquals(baseline, fonts(inactiveScheme))
            assertEquals(manualEditor, FontData.capture(activeScheme.fontPreferences))
            assertEquals(otherBaseline.second, FontData.capture(activeScheme.consoleFontPreferences))
            reloadSettings()
            native.disable()
            assertEquals(baseline, fonts(inactiveScheme))
            assertEquals(manualEditor, FontData.capture(activeScheme.fontPreferences))
        }
    }

    @Test
    fun preservesOtherOwnedFamilies() {
        SwingUtilities.invokeAndWait {
            val mapleScheme = activeScheme
            native.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            val mapleRecords =
                native.state.fontOwnershipSnapshots.keys
                    .toSet()
            activeScheme = (mapleScheme.clone() as EditorColorsScheme).apply { name = "Writing editor" }
            schemes.add(activeScheme)
            native.apply(FontSettings.fromPreset(FontPreset.WHISPER).copy(applyToConsole = true))
            val writingFonts = fonts(activeScheme)
            val writingOwnership = native.state.fontOwnershipSnapshots.filterKeys { it !in mapleRecords }
            activeScheme = mapleScheme

            val mapleFonts = fonts(mapleScheme)
            val allOwnership = native.state.fontOwnershipSnapshots.toMap()
            uninstallMaple()

            assertEquals(mapleFonts, fonts(mapleScheme))
            assertEquals(writingFonts, fonts(schemes.last()))
            assertTrue(writingOwnership.isNotEmpty())
            assertEquals(allOwnership, native.state.fontOwnershipSnapshots)
        }
    }

    private fun uninstallMaple() {
        val fontFile = fontDirectory.resolve("MapleMono-Regular.ttf").toFile().apply { writeText("font fixture") }
        var outcome: FontUninstaller.UninstallResult? = null
        FontUninstaller.uninstallResource(
            dev.ayuislands.font.FontCatalog
                .requirePreset(FontPreset.AMBIENT),
            "Maple Mono",
            listOf(fontFile.absolutePath),
            null,
        ) {
            outcome =
                it
        }
        UIUtil.dispatchAllInvocationEvents()
        val removed = assertIs<FontUninstaller.UninstallResult.Success>(outcome)
        assertFalse(fontFile.exists())
        assertEquals("Maple Mono", removed.familyName)
    }

    private fun reloadSettings() {
        native.reload()
    }

    private fun preferences(
        family: String,
        size: Float,
    ): FontPreferencesImpl =
        FontPreferencesImpl().apply {
            register(family, size)
            addFontFamily("Unavailable personal fallback")
            setFontSize("Unavailable personal fallback", 19.25f)
            regularSubFamily = "Medium"
            boldSubFamily = "ExtraBold"
            setUseLigatures(true)
            lineSpacing = 1.45f
        }

    private fun fonts(scheme: EditorColorsScheme): Pair<FontData, FontData> =
        FontData.capture(scheme.fontPreferences) to FontData.capture(scheme.consoleFontPreferences)
}
