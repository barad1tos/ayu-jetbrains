package dev.ayuislands.integration

import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.openapi.options.SchemeManager
import com.intellij.openapi.options.SchemeManagerFactory
import com.intellij.openapi.options.SchemeProcessor
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.font.FontOwnershipCodec
import dev.ayuislands.font.FontOwnershipStatus
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontWeight
import dev.ayuislands.settings.AyuIslandsSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.jdom.Element
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FontPersistenceTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var activeScheme: EditorColorsSchemeImpl
    private lateinit var schemeManager: SchemeManager<EditorColorsSchemeImpl>
    private lateinit var factory: SchemeManagerFactory

    @Before
    fun setup() {
        val parent =
            requireNotNull(EditorColorsManager.getInstance().getScheme(EditorColorsManager.getDefaultSchemeName()))
        activeScheme =
            EditorColorsSchemeImpl(parent).apply {
                name = "Personal persisted fonts"
                fontPreferences = preferences("Dialog", 17f)
                consoleFontPreferences = preferences("Monospaced", 15f)
                setSaveNeeded(true)
            }
        factory = SchemeManagerFactory.getInstance()
        schemeManager =
            factory.create(
                "font-persistence",
                object : SchemeProcessor<EditorColorsSchemeImpl, EditorColorsSchemeImpl>() {
                    override fun writeScheme(scheme: EditorColorsSchemeImpl): Element = scheme.writeScheme()
                },
                null,
                temporaryFolder.newFolder("schemes").toPath(),
                SettingsCategory.UI,
            )
        schemeManager.addScheme(activeScheme)
        schemeManager.save()
        assertEquals(17f, reloadScheme().editorFontSize2D)

        val manager = mockk<EditorColorsManager>()
        every { manager.globalScheme } answers { activeScheme }
        every { manager.allSchemes } answers { arrayOf(activeScheme) }
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns manager
        val settings = AyuIslandsSettings()
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
    }

    @After
    fun cleanup() {
        if (::schemeManager.isInitialized) factory.dispose(schemeManager)
        unmockkAll()
    }

    @Test
    fun ownershipSurvivesReload() {
        val settings = AyuIslandsSettings.getInstance()
        val requested = customFonts().copy(applyToConsole = false, weight = FontWeight.REGULAR)
        settings.state.fontPresetEnabled = true
        settings.state.fontPresetName = FontPreset.CUSTOM.name
        settings.state.fontPresetCustomizations[FontPreset.CUSTOM.name] = requested.encode()
        SwingUtilities.invokeAndWait { FontPresetApplicator.apply(requested) }
        val before = settings.state.fontOwnershipSnapshots.toMap()
        schemeManager.save()
        activeScheme = reloadScheme()
        schemeManager.addScheme(activeScheme)
        val xml = JDOMUtil.writeElement(XmlSerializer.serialize(settings.state))
        settings.loadState(XmlSerializer.deserialize(JDOMUtil.load(xml), settings.state.javaClass))

        SwingUtilities.invokeAndWait { FontPresetApplicator.applyFromState() }
        assertEquals(before, settings.state.fontOwnershipSnapshots)
        assertEquals(19f, activeScheme.editorFontSize2D)
        assertEquals("Serif", activeScheme.editorFontName)
        assertTrue(
            settings.state.fontOwnershipSnapshots.values.all {
                FontOwnershipCodec.decode(it)?.status == FontOwnershipStatus.OWNED
            },
        )
        SwingUtilities.invokeAndWait { FontPresetApplicator.revert() }
        assertEquals("Dialog", activeScheme.editorFontName)
        assertEquals(17f, activeScheme.editorFontSize2D)
        assertTrue(settings.state.fontOwnershipSnapshots.isEmpty())
    }

    private fun reloadRegularPreset(): FontSettings {
        val settings = AyuIslandsSettings.getInstance()
        val requested = customFonts().copy(applyToConsole = false, weight = FontWeight.REGULAR)
        settings.state.fontPresetEnabled = true
        settings.state.fontPresetName = FontPreset.CUSTOM.name
        settings.state.fontPresetCustomizations[FontPreset.CUSTOM.name] = requested.encode()
        SwingUtilities.invokeAndWait { FontPresetApplicator.apply(requested) }
        schemeManager.save()
        activeScheme = reloadScheme()
        schemeManager.addScheme(activeScheme)
        return requested
    }

    @Test
    fun manualEditsSurviveReload() {
        reloadRegularPreset()
        val state = AyuIslandsSettings.getInstance().state
        val key = state.fontOwnershipSnapshots.keys.single()
        val before = assertNotNull(FontOwnershipCodec.decode(state.fontOwnershipSnapshots.getValue(key)))
        SwingUtilities.invokeAndWait {
            activeScheme.setEditorFontSize(23f)
            FontPresetApplicator.applyFromState()
        }
        val suspended = assertNotNull(FontOwnershipCodec.decode(state.fontOwnershipSnapshots.getValue(key)))
        assertEquals(23f, activeScheme.editorFontSize2D)
        assertEquals(FontOwnershipStatus.SUSPENDED, suspended.status)
        assertEquals(before.baseline, suspended.baseline)
        SwingUtilities.invokeAndWait { FontPresetApplicator.revert() }
        val released = assertNotNull(FontOwnershipCodec.decode(state.fontOwnershipSnapshots.getValue(key)))
        assertEquals(23f, activeScheme.editorFontSize2D)
        assertEquals(FontOwnershipStatus.RELEASED, released.status)
        assertEquals(before.baseline, released.baseline)
    }

    @Test
    fun explicitRecoverySurvivesReload() {
        val requested = reloadRegularPreset()
        val state = AyuIslandsSettings.getInstance().state
        val key = state.fontOwnershipSnapshots.keys.single()
        val record = assertNotNull(FontOwnershipCodec.decode(state.fontOwnershipSnapshots.getValue(key)))
        val applied = assertIs<FontSnapshot.Explicit>(record.applied)
        val oldRaw =
            FontOwnershipCodec.encode(
                record.copy(
                    status = FontOwnershipStatus.SUSPENDED,
                    applied = FontSnapshot.Explicit(applied.preferences.copy(regularSubFamily = "")),
                ),
            )
        state.fontOwnershipSnapshots[key] = oldRaw
        SwingUtilities.invokeAndWait { FontPresetApplicator.applyFromState() }
        assertEquals(oldRaw, state.fontOwnershipSnapshots.getValue(key))
        assertEquals(19f, activeScheme.editorFontSize2D)
        SwingUtilities.invokeAndWait {
            state.fontPresetEnabled = false
            FontPresetApplicator.revert()
            state.fontPresetEnabled = true
            FontPresetApplicator.apply(requested)
        }
        val recovered = assertNotNull(FontOwnershipCodec.decode(state.fontOwnershipSnapshots.getValue(key)))
        assertEquals(FontOwnershipStatus.OWNED, recovered.status)
        assertNull(assertIs<FontSnapshot.Explicit>(recovered.applied).preferences.regularSubFamily)
        val beforeReload = state.fontOwnershipSnapshots.toMap()
        schemeManager.save()
        activeScheme = reloadScheme()
        schemeManager.addScheme(activeScheme)
        SwingUtilities.invokeAndWait { FontPresetApplicator.applyFromState() }
        assertEquals(beforeReload, state.fontOwnershipSnapshots)
    }

    @Test
    fun appliedFontsSurviveReload() {
        SwingUtilities.invokeAndWait {
            FontPresetApplicator.apply(customFonts())
        }

        schemeManager.save()

        val reloaded = reloadScheme()
        assertEquals("Serif", reloaded.editorFontName)
        assertEquals(19f, reloaded.editorFontSize2D)
        assertEquals(1.25f, reloaded.lineSpacing)
        assertEquals("Serif", reloaded.consoleFontName)
        assertEquals(19f, reloaded.consoleFontSize2D)
    }

    @Test
    fun installedFontsSurviveReload() {
        SwingUtilities.invokeAndWait {
            FontPresetApplicator.applyInstalled(customFonts())
        }

        schemeManager.save()

        val reloaded = reloadScheme()
        assertEquals("Serif", reloaded.editorFontName)
        assertEquals(19f, reloaded.editorFontSize2D)
        assertEquals("Serif", reloaded.consoleFontName)
        assertEquals(19f, reloaded.consoleFontSize2D)
    }

    @Test
    fun unchangedFontsPersistNewIdentity() {
        SwingUtilities.invokeAndWait { FontPresetApplicator.apply(customFonts()) }
        val state = AyuIslandsSettings.getInstance().state
        state.fontOwnershipSnapshots.clear()
        activeScheme.metaProperties.remove("dev.ayuislands.fontOwnershipId")
        activeScheme.setSaveNeeded(true)
        schemeManager.save()
        assertNull(reloadScheme().metaProperties.getProperty("dev.ayuislands.fontOwnershipId"))

        SwingUtilities.invokeAndWait { FontPresetApplicator.apply(customFonts()) }
        schemeManager.save()

        val identity = assertNotNull(activeScheme.metaProperties.getProperty("dev.ayuislands.fontOwnershipId"))
        assertEquals(identity, reloadScheme().metaProperties.getProperty("dev.ayuislands.fontOwnershipId"))
        assertEquals(setOf("v2:EDITOR:$identity", "v2:CONSOLE:$identity"), state.fontOwnershipSnapshots.keys)
        assertEquals(19f, reloadScheme().editorFontSize2D)
    }

    @Test
    fun restoredFontsSurviveReload() {
        SwingUtilities.invokeAndWait {
            FontPresetApplicator.apply(customFonts())
        }
        // Seed an already saved preset independently of Apply's persistence behavior.
        activeScheme.setSaveNeeded(true)
        schemeManager.save()
        assertEquals("Serif", reloadScheme().editorFontName)

        SwingUtilities.invokeAndWait { FontPresetApplicator.revert() }
        schemeManager.save()

        val reloaded = reloadScheme()
        assertEquals("Dialog", reloaded.editorFontName)
        assertEquals(17f, reloaded.editorFontSize2D)
        assertEquals("Monospaced", reloaded.consoleFontName)
        assertEquals(15f, reloaded.consoleFontSize2D)
    }

    @Test
    fun restoredInheritanceSurvivesReload() {
        activeScheme.setUseAppFontPreferencesInEditor()
        activeScheme.setUseEditorFontPreferencesInConsole()
        activeScheme.setSaveNeeded(true)
        schemeManager.save()
        assertTrue(reloadScheme().isUseAppFontPreferencesInEditor)
        SwingUtilities.invokeAndWait { FontPresetApplicator.apply(customFonts()) }
        activeScheme.setSaveNeeded(true)
        schemeManager.save()

        SwingUtilities.invokeAndWait { FontPresetApplicator.revert() }
        schemeManager.save()

        val reloaded = reloadScheme()
        assertTrue(reloaded.isUseAppFontPreferencesInEditor)
        assertTrue(reloaded.isUseEditorFontPreferencesInConsole)
    }

    @Test
    fun partialWritesSurviveReload() {
        val failure = IllegalStateException("Font cache update failed")
        var failWrites = false
        val failingScheme =
            object : EditorColorsSchemeImpl(activeScheme.parentScheme) {
                override fun setFontPreferences(preferences: FontPreferences) {
                    super.setFontPreferences(preferences)
                    if (failWrites) throw failure
                }
            }
        activeScheme.copyTo(failingScheme)
        failingScheme.name = activeScheme.name
        failingScheme.setSaveNeeded(true)
        schemeManager.addScheme(failingScheme)
        activeScheme = failingScheme
        schemeManager.save()

        failWrites = true
        SwingUtilities.invokeAndWait {
            assertSame(
                failure,
                assertFailsWith<IllegalStateException> { FontPresetApplicator.apply(customFonts()) },
            )
        }
        schemeManager.save()

        val reloaded = reloadScheme()
        assertEquals("Serif", reloaded.editorFontName)
        assertEquals(19f, reloaded.editorFontSize2D)
        assertEquals("Monospaced", reloaded.consoleFontName)
        assertEquals(15f, reloaded.consoleFontSize2D)
    }

    private fun reloadScheme(): EditorColorsSchemeImpl {
        val savedFile =
            schemeManager.rootDirectory
                .listFiles()
                .orEmpty()
                .single()
        return EditorColorsSchemeImpl(activeScheme.parentScheme).apply {
            readExternal(JDOMUtil.load(savedFile))
        }
    }

    private fun preferences(
        family: String,
        size: Float,
    ): FontPreferencesImpl = FontPreferencesImpl().apply { register(family, size) }

    private fun customFonts(): FontSettings =
        FontSettings.fromPreset(FontPreset.CUSTOM).copy(
            fontFamily = "Serif",
            fontSize = 19f,
            lineSpacing = 1.25f,
            applyToConsole = true,
        )
}
