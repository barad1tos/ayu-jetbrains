package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.testFramework.ApplicationRule
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontInstaller
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontSettings
import dev.ayuislands.settings.AyuIslandsSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontOnboardingOwnershipTest {
    @get:Rule val applicationRule = ApplicationRule()

    private lateinit var settings: AyuIslandsSettings
    private lateinit var scheme: EditorColorsScheme

    @Before
    fun setup() {
        val parent = EditorColorsManager.getInstance().globalScheme
        scheme =
            EditorColorsSchemeImpl(parent).apply {
                name = "Onboarding editor"
                fontPreferences = preferences("Personal Mono", 17.5f)
            }
        settings =
            AyuIslandsSettings().apply {
                state.fontPresetEnabled = false
                state.fontPresetName = "FUTURE_PRESET"
                state.fontPresetCustomizations["FUTURE_PRESET"] = "opaque|future|customization"
            }

        mockkObject(AyuIslandsSettings.Companion, FontDetector)
        every { AyuIslandsSettings.getInstance() } answers { settings }
        every { FontDetector.resolveFamily(any()) } answers { firstArg<FontPreset>().fontFamily }

        val manager = mockk<EditorColorsManager>()
        every { manager.globalScheme } answers { scheme }
        every { manager.allSchemes } answers { arrayOf(scheme) }
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns manager
    }

    @After
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `disabled startup preserves a font applied from onboarding`() {
        val selectedPreset = settings.state.fontPresetName
        val customizations = settings.state.fontPresetCustomizations.toMap()

        applyInstalled()
        val applied = FontData.capture(scheme.fontPreferences)
        assertEquals(FontPreset.AMBIENT.fontFamily, scheme.editorFontName)
        reloadSettings()
        onEdt { FontPresetApplicator.applyFromState() }

        assertEquals(applied, FontData.capture(scheme.fontPreferences))
        assertFalse(settings.state.fontPresetEnabled)
        assertEquals(selectedPreset, settings.state.fontPresetName)
        assertEquals(customizations, settings.state.fontPresetCustomizations)
    }

    @Test
    fun `enabled startup does not take ownership of an onboarding choice`() {
        applyInstalled()
        val onboardingChoice = FontData.capture(scheme.fontPreferences)
        val ownership = settings.state.fontOwnershipSnapshots.toMap()
        settings.state.fontPresetEnabled = true
        settings.state.fontPresetName = FontPreset.WHISPER.name
        reloadSettings()

        onEdt { FontPresetApplicator.applyFromState() }

        assertEquals(onboardingChoice, FontData.capture(scheme.fontPreferences))
        assertEquals(ownership, settings.state.fontOwnershipSnapshots)
        assertTrue(settings.state.fontPresetEnabled)
        assertEquals(FontPreset.WHISPER.name, settings.state.fontPresetName)
    }

    @Test
    fun `managed disable restores the earlier onboarding choice`() {
        applyInstalled()
        val onboardingChoice = FontData.capture(scheme.fontPreferences)

        settings.state.fontPresetEnabled = true
        settings.state.fontPresetName = FontPreset.WHISPER.name
        onEdt { FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.WHISPER)) }
        settings.state.fontPresetEnabled = false
        onEdt { FontPresetApplicator.applyFromState() }

        assertEquals(onboardingChoice, FontData.capture(scheme.fontPreferences))
    }

    @Test
    fun `font uninstall restores an unchanged onboarding write`() {
        val original = FontData.capture(scheme.fontPreferences)
        applyInstalled()

        onEdt { FontPresetApplicator.revert(FontPreset.AMBIENT.fontFamily) }

        assertEquals(original, FontData.capture(scheme.fontPreferences))
        assertTrue(settings.state.fontOwnershipSnapshots.isEmpty())
    }

    @Test
    fun `uninstall preserves a manual change and never revives its backup`() {
        applyInstalled()
        val onboardingChoice = FontData.capture(scheme.fontPreferences)
        val ownership = settings.state.fontOwnershipSnapshots.toMap()
        onEdt { scheme.fontPreferences = preferences("Manual Mono", 19.5f) }
        val manualChoice = FontData.capture(scheme.fontPreferences)
        reloadSettings()

        onEdt { FontPresetApplicator.revert(FontPreset.WHISPER.fontFamily) }

        assertEquals(manualChoice, FontData.capture(scheme.fontPreferences))
        assertEquals(ownership, settings.state.fontOwnershipSnapshots)

        onEdt { FontPresetApplicator.revert(FontPreset.AMBIENT.fontFamily) }

        assertEquals(manualChoice, FontData.capture(scheme.fontPreferences))
        val backup = settings.state.fontOwnershipSnapshots.toMap()
        assertTrue(backup.isNotEmpty())
        reloadSettings()
        onEdt { scheme.fontPreferences = onboardingChoice.toPreferences() }
        onEdt { FontPresetApplicator.revert(FontPreset.AMBIENT.fontFamily) }

        assertEquals(onboardingChoice, FontData.capture(scheme.fontPreferences))
        assertEquals(backup, settings.state.fontOwnershipSnapshots)
    }

    @Test
    fun `font uninstall never restores a released record`() {
        settings.state.fontPresetEnabled = true
        onEdt { FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT)) }
        val applied = FontData.capture(scheme.fontPreferences)
        scheme.fontPreferences = preferences("Manual Mono", 19.5f)
        onEdt { FontPresetApplicator.revert() }
        val released = settings.state.fontOwnershipSnapshots.toMap()
        reloadSettings()
        scheme.fontPreferences = applied.toPreferences()

        onEdt { FontPresetApplicator.revert(FontPreset.AMBIENT.fontFamily) }

        assertEquals(applied, FontData.capture(scheme.fontPreferences))
        assertEquals(released, settings.state.fontOwnershipSnapshots)
    }

    @Test
    fun `installer apply remains managed while the preset toggle is enabled`() {
        val original = FontData.capture(scheme.fontPreferences)
        settings.state.fontPresetEnabled = true

        applyInstalled()
        settings.state.fontPresetEnabled = false
        onEdt { FontPresetApplicator.applyFromState() }

        assertEquals(original, FontData.capture(scheme.fontPreferences))
    }

    private fun applyInstalled() {
        onEdt { FontInstaller.applyOnly(FontPreset.AMBIENT, project = null) }
        onEdt { }
    }

    private fun reloadSettings() {
        val state = XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), settings.state.javaClass)
        settings = AyuIslandsSettings().apply { loadState(state) }
    }

    private fun preferences(
        family: String,
        size: Float,
    ): FontPreferencesImpl =
        FontPreferencesImpl().apply {
            setEffectiveFontFamilies(listOf(family))
            setRealFontFamilies(listOf(family))
            setTemplateFontSize(size)
            setFontSize(family, size)
        }

    private fun onEdt(action: () -> Unit) {
        SwingUtilities.invokeAndWait(action)
    }
}
