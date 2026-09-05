package dev.ayuislands.integration

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontSettings
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.FontPresetPanel
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class FontPresetOwnershipTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    private lateinit var scheme: EditorColorsScheme
    private lateinit var settings: AyuIslandsSettings
    private val schemes = mutableListOf<EditorColorsScheme>()
    private val notifications = mutableListOf<Notification>()

    @Before
    fun setup() {
        val parent = EditorColorsManager.getInstance().globalScheme
        scheme = EditorColorsSchemeImpl(parent).apply { name = "Personal font scheme" }
        scheme.fontPreferences = preferences("Dialog", 17.5f)
        scheme.consoleFontPreferences = preferences("Monospaced", 15.5f)
        settings = AyuIslandsSettings()
        schemes.clear()
        schemes.add(scheme)
        val manager = mockk<EditorColorsManager>()
        every { manager.globalScheme } answers { scheme }
        every { manager.allSchemes } answers { schemes.toTypedArray() }
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns manager
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } answers { settings }
        mockkObject(FontDetector)
        every { FontDetector.resolveFamily(any()) } answers { firstArg<FontPreset>().fontFamily }
        notifications.clear()
        mockkStatic(Notifications.Bus::class)
        every { Notifications.Bus.notify(any<Notification>(), isNull<Project>()) } answers {
            notifications.add(firstArg())
        }
    }

    @After
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun restoreWithoutSnapshotPreservesPersonalFonts() {
        SwingUtilities.invokeAndWait {
            val original = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.revert()
            assertFontsEqual(original, scheme)
        }
    }

    @Test
    fun disablingPresetsRestoresExactEditorPreferences() {
        SwingUtilities.invokeAndWait {
            val original = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
            assertApplied(FontPreset.AMBIENT)
            assertPreferencesEqual(original.consoleFontPreferences, scheme.consoleFontPreferences)
            FontPresetApplicator.revert()
            assertFontsEqual(original, scheme)
        }
    }

    @Test
    fun disablingPresetsPreservesManualEditorChanges() {
        SwingUtilities.invokeAndWait {
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
            scheme.setEditorFontSize(23.5f)
            val manuallyEdited = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.revert()
            assertFontsEqual(manuallyEdited, scheme)
        }
    }

    @Test
    fun consoleOptInRestoresBothOriginalBundles() {
        SwingUtilities.invokeAndWait {
            val original = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            assertApplied(FontPreset.AMBIENT)
            assertEquals(FontPreset.AMBIENT.fontSize, scheme.consoleFontSize2D)
            assertEquals(FontPreset.AMBIENT.lineSpacing, scheme.consoleLineSpacing)
            FontPresetApplicator.revert()
            assertFontsEqual(original, scheme)
        }
    }

    @Test
    fun repeatedApplyKeepsOriginalBaselineAfterSettingsReload() {
        SwingUtilities.invokeAndWait {
            val original = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
            reloadSettings()
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.WHISPER))
            reloadSettings()
            FontPresetApplicator.revert()
            assertFontsEqual(original, scheme)
        }
    }

    @Test
    fun automaticReapplyPreservesManualChangesAfterReload() {
        SwingUtilities.invokeAndWait {
            settings.state.fontPresetEnabled = true
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
            scheme.setEditorFontSize(23.5f)
            val manuallyEdited = scheme.clone() as EditorColorsScheme
            reloadSettings()
            FontPresetApplicator.applyFromState()
            assertFontsEqual(manuallyEdited, scheme)
            assertEquals(1, notifications.size)
            reloadSettings()
            FontPresetApplicator.revert()
            assertFontsEqual(manuallyEdited, scheme)
            assertEquals(1, notifications.size)
        }
    }

    @Test
    fun legacyAutomaticApplyDoesNotGuessMissingBaseline() {
        SwingUtilities.invokeAndWait {
            settings.state.fontPresetEnabled = true
            val original = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.applyFromState()
            assertFontsEqual(original, scheme)
        }
    }

    @Test
    fun disablingConsoleRestoresOnlyItsOwnedPreferences() {
        SwingUtilities.invokeAndWait {
            val original = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.WHISPER))
            assertPreferencesEqual(original.consoleFontPreferences, scheme.consoleFontPreferences)
            FontPresetApplicator.revert()
            assertFontsEqual(original, scheme)
        }
    }

    @Test
    fun manualConsoleChangeSurvivesEditorRestoration() {
        SwingUtilities.invokeAndWait {
            val original = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            scheme.consoleFontPreferences = preferences("Serif", 21.75f)
            val manualConsole = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.revert()
            assertPreferencesEqual(original.fontPreferences, scheme.fontPreferences)
            assertPreferencesEqual(manualConsole.consoleFontPreferences, scheme.consoleFontPreferences)
        }
    }

    @Test
    fun restoresEditorAndConsoleInheritanceChoices() {
        SwingUtilities.invokeAndWait {
            scheme.setUseAppFontPreferencesInEditor()
            scheme.setUseEditorFontPreferencesInConsole()
            val original = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            assertEquals(true, scheme.isUseEditorFontPreferencesInConsole)
            reloadSettings()
            FontPresetApplicator.revert()
            assertFontsEqual(original, scheme)
        }
    }

    @Test
    fun applyingAlreadyDisabledPanelLeavesSchemeUntouched() {
        SwingUtilities.invokeAndWait {
            val original = scheme.clone() as EditorColorsScheme
            val panel = FontPresetPanel()
            panel.loadState()
            panel.apply()
            assertFontsEqual(original, scheme)
        }
    }

    @Test
    fun globalConsoleChoicePreservesHiddenSchemePreferences() {
        SwingUtilities.invokeAndWait {
            val original = scheme.clone() as EditorColorsScheme
            val global = preferences("Serif", 22.5f)
            val globalBaseline = FontPreferencesImpl().also { global.copyTo(it) }
            var useGlobal = true
            // SDK 2025.1 exposes global preferences through this getter while the native setter stays local.
            val native =
                object : EditorColorsSchemeImpl(scheme) {
                    override fun getConsoleFontPreferences(): FontPreferences =
                        if (useGlobal) global else super.getConsoleFontPreferences()
                }
            replaceScheme(native)
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            assertApplied(FontPreset.AMBIENT)
            useGlobal = false
            assertPreferencesEqual(original.consoleFontPreferences, scheme.consoleFontPreferences)
            useGlobal = true
            FontPresetApplicator.revert()
            assertPreferencesEqual(globalBaseline, global)
            useGlobal = false
            assertFontsEqual(original, scheme)
        }
    }

    @Test
    fun corruptBaselineCannotOverwriteCurrentPreferences() {
        SwingUtilities.invokeAndWait {
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
            val original = scheme.clone() as EditorColorsScheme
            val key =
                settings.state.fontOwnershipSnapshots.keys
                    .single()
            val raw = settings.state.fontOwnershipSnapshots.getValue(key)
            val corrupt = raw.replaceFirst("spacing=\"1.45\"", "spacing=\"NaN\"")
            assertNotEquals(raw, corrupt, "Fixture must corrupt the baseline spacing")
            settings.state.fontOwnershipSnapshots[key] = corrupt
            FontPresetApplicator.revert()
            assertFontsEqual(original, scheme)
            assertEquals(corrupt, settings.state.fontOwnershipSnapshots[key])
        }
    }

    @Test
    fun familyRestoreLeavesUnrelatedSchemeOwned() {
        SwingUtilities.invokeAndWait {
            val first = scheme
            val original = first.clone() as EditorColorsScheme
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
            scheme =
                EditorColorsSchemeImpl(first).apply {
                    name = "Second font scheme"
                    fontPreferences = preferences("Serif", 21f)
                    consoleFontPreferences = preferences("Dialog", 16f)
                }
            schemes.add(scheme)
            val secondBaseline = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.WHISPER))
            assertApplied(FontPreset.WHISPER)
            val secondApplied = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.revert(FontPreset.AMBIENT.fontFamily)
            assertFontsEqual(original, first)
            assertFontsEqual(secondApplied, scheme)
            FontPresetApplicator.revert()
            assertFontsEqual(secondBaseline, scheme)
        }
    }

    @Test
    fun repeatedApplyDoesNotPublishAnotherSchemeChange() {
        SwingUtilities.invokeAndWait {
            val lifetime = Disposer.newDisposable()
            var changes = 0
            try {
                ApplicationManager.getApplication().messageBus.connect(lifetime).subscribe(
                    EditorColorsManager.TOPIC,
                    EditorColorsListener { changes++ },
                )
                FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
                assertEquals(1, changes)
                FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
                assertEquals(1, changes)
            } finally {
                Disposer.dispose(lifetime)
            }
        }
    }

    @Test
    fun failedWriteRetainsBaselineForDisableAfterReload() {
        SwingUtilities.invokeAndWait {
            val failure = IllegalStateException("Font cache update failed")
            var failWrites = false
            val native =
                object : EditorColorsSchemeImpl(scheme) {
                    override fun setFontPreferences(preferences: FontPreferences) {
                        super.setFontPreferences(preferences)
                        if (failWrites) throw failure
                    }
                }
            replaceScheme(native)
            val original = native.clone() as EditorColorsScheme
            failWrites = true
            assertSame(
                failure,
                assertFailsWith<IllegalStateException> {
                    FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
                },
            )
            failWrites = false
            reloadSettings()
            FontPresetApplicator.revert()
            assertFontsEqual(original, scheme)
        }
    }

    @Test
    fun disableReenableCapturesNewManualBaselineWithoutChangingPresets() {
        SwingUtilities.invokeAndWait {
            val presets = mapOf("FUTURE" to "unknown|opaque|data", "WHISPER" to "16|1.15|false|FUTURE_WEIGHT")
            settings.state.fontPresetCustomizations.putAll(presets)
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
            scheme.setEditorFontSize(23.5f)
            FontPresetApplicator.revert()
            val manualBaseline = scheme.clone() as EditorColorsScheme
            reloadSettings()
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.WHISPER))
            assertApplied(FontPreset.WHISPER)
            reloadSettings()
            FontPresetApplicator.revert()
            assertFontsEqual(manualBaseline, scheme)
            assertEquals(presets, settings.state.fontPresetCustomizations)
        }
    }

    @Test
    fun unavailableActiveSchemeIsNotModified() {
        SwingUtilities.invokeAndWait {
            val original = scheme.clone() as EditorColorsScheme
            schemes.clear()
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
            assertFontsEqual(original, scheme)
            assertEquals(emptyMap(), settings.state.fontOwnershipSnapshots)
        }
    }

    @Test
    fun nativeReloadPreservesFontsAndRecoverySnapshot() {
        SwingUtilities.invokeAndWait {
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            val backup = recoveryBaselines()
            val native = scheme as AbstractColorsScheme
            val xml = JDOMUtil.writeElement(native.writeScheme())
            val reloaded = EditorColorsSchemeImpl(native.parentScheme)
            reloaded.readExternal(JDOMUtil.load(xml))
            schemes[schemes.indexOf(scheme)] = reloaded
            scheme = reloaded
            reloadSettings()
            val afterReload = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.revert()
            assertFontsEqual(afterReload, scheme)
            assertEquals(backup, recoveryBaselines())
            assertEquals(2, notifications.size)
            val released = settings.state.fontOwnershipSnapshots.toMap()
            reloadSettings()
            settings.state.fontPresetEnabled = true
            settings.state.fontPresetName = FontPreset.WHISPER.name
            FontPresetApplicator.applyFromState()
            FontPresetApplicator.revert()
            assertFontsEqual(afterReload, scheme)
            assertEquals(backup, recoveryBaselines())
            assertEquals(released, settings.state.fontOwnershipSnapshots)
            assertEquals(2, notifications.size)
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.WHISPER))
            assertApplied(FontPreset.WHISPER)
            FontPresetApplicator.revert()
            assertFontsEqual(afterReload, scheme)
        }
    }

    @Test
    fun disabledStartupRestoresSchemeThatWasUnavailableAtDisable() {
        SwingUtilities.invokeAndWait {
            val original = scheme.clone() as EditorColorsScheme
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
            schemes.clear()
            FontPresetApplicator.revert()
            assertApplied(FontPreset.AMBIENT)
            reloadSettings()
            schemes.add(scheme)
            FontPresetApplicator.applyFromState()
            assertFontsEqual(original, scheme)
        }
    }

    @Test
    fun manualConsoleInheritanceReleasesOldOwnership() {
        SwingUtilities.invokeAndWait {
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true))
            val applied = FontPreferencesImpl().also { scheme.consoleFontPreferences.copyTo(it) }
            val backup = recoveryBaselines().filterKeys { it.startsWith("CONSOLE:") }
            scheme.setUseEditorFontPreferencesInConsole()
            FontPresetApplicator.revert()
            assertEquals(backup, recoveryBaselines())
            reloadSettings()
            scheme.consoleFontPreferences = applied
            FontPresetApplicator.revert(FontPreset.AMBIENT.fontFamily)
            FontPresetApplicator.revert()
            assertPreferencesEqual(applied, scheme.consoleFontPreferences)
        }
    }

    @Test
    fun unrecognizedSnapshotsPreserveFontsAndRawData() {
        SwingUtilities.invokeAndWait {
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
            val applied = scheme.clone() as EditorColorsScheme
            val key =
                settings.state.fontOwnershipSnapshots.keys
                    .single()
            val raw = settings.state.fontOwnershipSnapshots.getValue(key)
            val unrecognized =
                listOf(
                    raw.replaceFirst("<font-ownership ", "<font-ownership xmlns:x=\"urn:future\" x:status=\"future\" "),
                    raw.replaceFirst("<font-ownership ", "<font-ownership xmlns=\"urn:future\" "),
                    raw.replaceFirst("<baseline ", "<baseline future=\"opaque\" "),
                    raw.replaceFirst("version=\"1\"", "version=\"99\""),
                    raw.replaceFirst("status=\"OWNED\"", "status=\"FUTURE\""),
                    raw.replaceFirst("mode=\"explicit\"", "mode=\"future\""),
                    raw.replaceFirst("ligatures=\"true\"", "ligatures=\"future\""),
                    raw.replaceFirst("spacing=\"1.45\"", "spacing=\"Infinity\""),
                    raw.replaceFirst("<baseline ", "opaque text<baseline "),
                    "<broken",
                )
            for (snapshot in unrecognized) {
                assertNotEquals(raw, snapshot)
                settings.state.fontOwnershipSnapshots[key] = snapshot
                FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.WHISPER))
                FontPresetApplicator.revert()
                assertFontsEqual(applied, scheme)
                assertEquals(snapshot, settings.state.fontOwnershipSnapshots[key])
            }
        }
    }

    @Test
    fun futureOwnershipVersionPreservesAllPreferences() {
        SwingUtilities.invokeAndWait {
            val original = scheme.clone() as EditorColorsScheme
            val snapshots = mapOf("FUTURE:scheme" to "opaque snapshot")
            settings.state.fontOwnershipVersion = 99
            settings.state.fontOwnershipSnapshots.putAll(snapshots)
            settings.state.fontPresetEnabled = true
            FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))
            FontPresetApplicator.applyFromState()
            FontPresetApplicator.revert()
            assertFontsEqual(original, scheme)
            assertEquals(99, settings.state.fontOwnershipVersion)
            assertEquals(snapshots, settings.state.fontOwnershipSnapshots)
        }
    }

    private fun recoveryBaselines(): Map<String, String> =
        settings.state.fontOwnershipSnapshots.mapValues { (_, raw) ->
            JDOMUtil.writeElement(requireNotNull(JDOMUtil.load(raw).getChild("baseline")))
        }

    private fun assertApplied(preset: FontPreset) {
        assertEquals(preset.fontFamily, scheme.fontPreferences.realFontFamilies.first())
        assertEquals(preset.fontSize, scheme.editorFontSize2D)
        assertEquals(preset.lineSpacing, scheme.lineSpacing)
        assertEquals(preset.enableLigatures, scheme.isUseLigatures)
        assertEquals(preset.defaultWeight.subFamily, scheme.fontPreferences.regularSubFamily)
        assertFalse(scheme.isUseAppFontPreferencesInEditor)
    }

    private fun replaceScheme(replacement: EditorColorsSchemeImpl) {
        (scheme as AbstractColorsScheme).copyTo(replacement)
        replacement.name = scheme.name
        schemes[schemes.indexOf(scheme)] = replacement
        scheme = replacement
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
            addFontFamily("Unavailable Personal Font")
            setFontSize("Unavailable Personal Font", 19.25f)
            regularSubFamily = "Medium"
            boldSubFamily = "ExtraBold"
            setUseLigatures(true)
            lineSpacing = 1.45f
        }

    private fun assertFontsEqual(
        expected: EditorColorsScheme,
        actual: EditorColorsScheme,
    ) {
        assertEquals(expected.isUseAppFontPreferencesInEditor, actual.isUseAppFontPreferencesInEditor)
        assertEquals(expected.isUseEditorFontPreferencesInConsole, actual.isUseEditorFontPreferencesInConsole)
        assertPreferencesEqual(expected.fontPreferences, actual.fontPreferences)
        assertPreferencesEqual(expected.consoleFontPreferences, actual.consoleFontPreferences)
    }

    private fun assertPreferencesEqual(
        expected: FontPreferences,
        actual: FontPreferences,
    ) {
        assertEquals(expected.realFontFamilies, actual.realFontFamilies)
        assertEquals(expected.effectiveFontFamilies, actual.effectiveFontFamilies)
        for (family in expected.realFontFamilies) {
            assertEquals(expected.hasSize(family), actual.hasSize(family))
            assertEquals(expected.getSize2D(family), actual.getSize2D(family))
        }
        assertEquals(expected.regularSubFamily, actual.regularSubFamily)
        assertEquals(expected.boldSubFamily, actual.boldSubFamily)
        assertEquals(expected.lineSpacing, actual.lineSpacing)
        assertEquals(expected.useLigatures(), actual.useLigatures())
    }
}
