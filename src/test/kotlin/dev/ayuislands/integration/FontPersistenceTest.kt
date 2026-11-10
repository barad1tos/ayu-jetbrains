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
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontWeight
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
import kotlin.test.assertTrue

class FontPersistenceTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var native: FontNativeFixture
    private var activeScheme: EditorColorsSchemeImpl
        get() = native.scheme as EditorColorsSchemeImpl
        set(value) {
            native.replace(value)
        }
    private lateinit var schemeManager: SchemeManager<EditorColorsSchemeImpl>
    private lateinit var factory: SchemeManagerFactory

    @Before
    fun setup() {
        native = FontNativeFixture()
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
    }

    @After
    fun cleanup() {
        if (::schemeManager.isInitialized) factory.dispose(schemeManager)
    }

    @Test
    fun ownershipSurvivesReload() {
        val requested = customFonts().copy(applyToConsole = false, weight = FontWeight.REGULAR)
        native.state.fontPresetEnabled = true
        native.state.fontPresetName = FontPreset.CUSTOM.name
        native.state.fontPresetCustomizations[FontPreset.CUSTOM.name] = requested.encode()
        SwingUtilities.invokeAndWait { native.apply(requested) }
        val before = native.state.fontOwnershipSnapshots.toMap()
        schemeManager.save()
        activeScheme = reloadScheme()
        schemeManager.addScheme(activeScheme)
        val xml = JDOMUtil.writeElement(XmlSerializer.serialize(native.state))
        native.state = XmlSerializer.deserialize(JDOMUtil.load(xml), native.state.javaClass)

        SwingUtilities.invokeAndWait { native.apply(requested) }
        assertEquals(before, native.state.fontOwnershipSnapshots)
        assertEquals(19f, activeScheme.editorFontSize2D)
        assertEquals("Serif", activeScheme.editorFontName)
        assertTrue(
            native.state.fontOwnershipSnapshots.values.all {
                FontOwnershipCodec.decode(it)?.status == FontOwnershipStatus.OWNED
            },
        )
        SwingUtilities.invokeAndWait { native.disable() }
        assertEquals("Dialog", activeScheme.editorFontName)
        assertEquals(17f, activeScheme.editorFontSize2D)
        assertTrue(native.state.fontOwnershipSnapshots.isEmpty())
    }

    private fun reloadRegularPreset(): FontSettings {
        val requested = customFonts().copy(applyToConsole = false, weight = FontWeight.REGULAR)
        native.state.fontPresetEnabled = true
        native.state.fontPresetName = FontPreset.CUSTOM.name
        native.state.fontPresetCustomizations[FontPreset.CUSTOM.name] = requested.encode()
        SwingUtilities.invokeAndWait { native.apply(requested) }
        schemeManager.save()
        activeScheme = reloadScheme()
        schemeManager.addScheme(activeScheme)
        return requested
    }

    @Test
    fun manualEditsSurviveReload() {
        val requested = reloadRegularPreset()
        val state = native.state
        val key = state.fontOwnershipSnapshots.keys.single()
        val before = assertNotNull(FontOwnershipCodec.decode(state.fontOwnershipSnapshots.getValue(key)))
        SwingUtilities.invokeAndWait {
            activeScheme.setEditorFontSize(23f)
            assertFailsWith<IllegalStateException> { native.apply(requested) }
        }
        val suspended = assertNotNull(FontOwnershipCodec.decode(state.fontOwnershipSnapshots.getValue(key)))
        assertEquals(23f, activeScheme.editorFontSize2D)
        assertEquals(before, suspended)
        assertEquals(before.baseline, suspended.baseline)
        SwingUtilities.invokeAndWait { native.disable() }
        val released = assertNotNull(FontOwnershipCodec.decode(state.fontOwnershipSnapshots.getValue(key)))
        assertEquals(23f, activeScheme.editorFontSize2D)
        assertEquals(FontOwnershipStatus.RELEASED, released.status)
        assertEquals(before.baseline, released.baseline)
    }

    @Test
    fun explicitRecoverySurvivesReload() {
        val requested = reloadRegularPreset()
        val state = native.state
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
        SwingUtilities.invokeAndWait { assertFailsWith<IllegalStateException> { native.apply(requested) } }
        assertEquals(oldRaw, state.fontOwnershipSnapshots.getValue(key))
        assertEquals(19f, activeScheme.editorFontSize2D)
        SwingUtilities.invokeAndWait {
            state.fontPresetEnabled = false
            native.disable()
            state.fontPresetEnabled = true
            native.apply(requested)
        }
        val recovered = assertNotNull(FontOwnershipCodec.decode(state.fontOwnershipSnapshots.getValue(key)))
        assertEquals(FontOwnershipStatus.OWNED, recovered.status)
        assertNull(assertIs<FontSnapshot.Explicit>(recovered.applied).preferences.regularSubFamily)
        val beforeReload = state.fontOwnershipSnapshots.toMap()
        schemeManager.save()
        activeScheme = reloadScheme()
        schemeManager.addScheme(activeScheme)
        SwingUtilities.invokeAndWait { native.apply(requested) }
        assertEquals(beforeReload, state.fontOwnershipSnapshots)
    }

    @Test
    fun appliedFontsSurviveReload() {
        SwingUtilities.invokeAndWait {
            native.apply(customFonts())
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
    fun onboardingAcceptedFontsSurviveReload() {
        SwingUtilities.invokeAndWait {
            native.apply(customFonts())
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
        SwingUtilities.invokeAndWait { native.apply(customFonts()) }
        val state = native.state
        state.fontOwnershipSnapshots.clear()
        activeScheme.metaProperties.remove("dev.ayuislands.fontOwnershipId")
        activeScheme.setSaveNeeded(true)
        schemeManager.save()
        assertNull(reloadScheme().metaProperties.getProperty("dev.ayuislands.fontOwnershipId"))

        SwingUtilities.invokeAndWait { native.apply(customFonts()) }
        schemeManager.save()

        val identity = assertNotNull(activeScheme.metaProperties.getProperty("dev.ayuislands.fontOwnershipId"))
        assertEquals(identity, reloadScheme().metaProperties.getProperty("dev.ayuislands.fontOwnershipId"))
        assertEquals(setOf("v2:EDITOR:$identity", "v2:CONSOLE:$identity"), state.fontOwnershipSnapshots.keys)
        assertEquals(19f, reloadScheme().editorFontSize2D)
    }

    @Test
    fun restoredFontsSurviveReload() {
        SwingUtilities.invokeAndWait {
            native.apply(customFonts())
        }
        // Seed an already saved preset independently of Apply's persistence behavior.
        activeScheme.setSaveNeeded(true)
        schemeManager.save()
        assertEquals("Serif", reloadScheme().editorFontName)

        SwingUtilities.invokeAndWait { native.disable() }
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
        SwingUtilities.invokeAndWait { native.apply(customFonts()) }
        activeScheme.setSaveNeeded(true)
        schemeManager.save()

        SwingUtilities.invokeAndWait { native.disable() }
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
            val thrown = assertFailsWith<IllegalStateException> { native.apply(customFonts()) }
            assertEquals(failure.message, thrown.message)
            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure })
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
