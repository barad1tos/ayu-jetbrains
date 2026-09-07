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
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontInstaller
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontUninstaller
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
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

    private lateinit var settings: AyuIslandsSettings
    private lateinit var activeScheme: EditorColorsScheme
    private lateinit var fontDirectory: Path
    private val schemes = mutableListOf<EditorColorsScheme>()

    @Before
    fun setup() {
        val parent = EditorColorsManager.getInstance().globalScheme
        activeScheme = EditorColorsSchemeImpl(parent).apply { name = "Personal editor" }
        activeScheme.fontPreferences = preferences("Dialog", 17.5f)
        activeScheme.consoleFontPreferences = preferences("Monospaced", 15.5f)
        schemes.add(activeScheme)
        settings = AyuIslandsSettings()
        fontDirectory = createTempDirectory("ayu-uninstall-ownership")
        mockkObject(AyuIslandsSettings.Companion, FontDetector, FontInstaller)
        every { AyuIslandsSettings.getInstance() } answers { settings }
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
    fun restoresInactiveSurfacesAfterReload() {
        SwingUtilities.invokeAndWait {
            val baseline = fonts(activeScheme)
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            val inactiveScheme = activeScheme
            activeScheme = (inactiveScheme.clone() as EditorColorsScheme).apply { name = "Other editor" }
            activeScheme.fontPreferences = preferences("DialogInput", 18.5f)
            activeScheme.consoleFontPreferences = preferences("Serif", 16.5f)
            schemes.add(activeScheme)
            val otherBaseline = fonts(activeScheme)
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            // The user independently changes the active editor, leaving its console owned by Ayu.
            activeScheme.fontPreferences = preferences("Personal unavailable mono", 21.5f)
            val manualEditor = FontData.capture(activeScheme.fontPreferences)
            reloadSettings()

            uninstallMaple()

            assertEquals(baseline, fonts(inactiveScheme))
            assertEquals(manualEditor, FontData.capture(activeScheme.fontPreferences))
            assertEquals(otherBaseline.second, FontData.capture(activeScheme.consoleFontPreferences))
            reloadSettings()
            FontPresetApplicator.revert()
            assertEquals(baseline, fonts(inactiveScheme))
            assertEquals(manualEditor, FontData.capture(activeScheme.fontPreferences))
        }
    }

    @Test
    fun preservesOtherOwnedFamilies() {
        SwingUtilities.invokeAndWait {
            val mapleScheme = activeScheme
            val mapleBaseline = fonts(mapleScheme)
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            val mapleRecords =
                settings.state.fontOwnershipSnapshots.keys
                    .toSet()
            activeScheme = (mapleScheme.clone() as EditorColorsScheme).apply { name = "Writing editor" }
            schemes.add(activeScheme)
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.WHISPER).copy(applyToConsole = true))
            val writingFonts = fonts(activeScheme)
            val writingOwnership = settings.state.fontOwnershipSnapshots.filterKeys { it !in mapleRecords }
            activeScheme = mapleScheme

            uninstallMaple()

            assertEquals(mapleBaseline, fonts(mapleScheme))
            assertEquals(writingFonts, fonts(schemes.last()))
            assertTrue(writingOwnership.isNotEmpty())
            assertEquals(writingOwnership, settings.state.fontOwnershipSnapshots)
        }
    }

    private fun uninstallMaple() {
        val fontFile = fontDirectory.resolve("MapleMono-Regular.ttf").toFile().apply { writeText("font fixture") }
        FontInstaller.persistFontState("Maple Mono", listOf(fontFile))
        var outcome: FontUninstaller.UninstallResult? = null
        FontUninstaller.uninstall(FontPreset.AMBIENT, null) { outcome = it }
        UIUtil.dispatchAllInvocationEvents()
        assertIs<FontUninstaller.UninstallResult.Success>(outcome)
        assertFalse(fontFile.exists())
        assertFalse("Maple Mono" in settings.state.installedFonts)
        assertFalse("Maple Mono" in settings.state.installedFontFiles)
        assertTrue("Maple Mono" in settings.state.explicitlyUninstalledFonts)
    }

    private fun reloadSettings() {
        val xml = JDOMUtil.writeElement(XmlSerializer.serialize(settings.state))
        settings =
            AyuIslandsSettings().apply {
                loadState(XmlSerializer.deserialize(JDOMUtil.load(xml), AyuIslandsState::class.java))
            }
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
