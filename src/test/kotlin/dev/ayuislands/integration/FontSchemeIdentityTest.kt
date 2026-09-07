package dev.ayuislands.integration

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontSettings
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
import javax.swing.SwingUtilities
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontSchemeIdentityTest {
    @get:Rule val applicationRule = ApplicationRule()

    private lateinit var activeScheme: EditorColorsSchemeImpl
    private var settings = AyuIslandsSettings()
    private val schemes = mutableListOf<EditorColorsScheme>()
    private val notices = mutableListOf<Notification>()

    @Before
    fun setup() {
        activeScheme =
            EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme).apply {
                name = "Personal scheme"
                fontPreferences = FontPreferencesImpl().apply { register("Dialog", 17f) }
            }
        schemes.add(activeScheme)
        val manager = mockk<EditorColorsManager>()
        every { manager.globalScheme } answers { activeScheme }
        every { manager.allSchemes } answers { schemes.toTypedArray() }
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns manager
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } answers { settings }
        mockkStatic(Notifications.Bus::class)
        every { Notifications.Bus.notify(any<Notification>(), isNull<Project>()) } answers
            {
                notices.add(firstArg())
            }
    }

    @After fun cleanup() = unmockkAll()

    @Test
    fun sameObjectRenameRestoresBaseline() =
        onEdt {
            val original = fonts()
            FontPresetApplicator.apply(customFonts())
            activeScheme.name = "Renamed personal scheme"

            FontPresetApplicator.revert()

            assertEquals(original, fonts())
            assertTrue(settings.state.fontOwnershipSnapshots.isEmpty())
        }

    @Test
    fun reusedNameCannotAuthorizeRestore() =
        onEdt {
            FontPresetApplicator.apply(customFonts())
            val backup = settings.state.fontOwnershipSnapshots.toMap()
            activeScheme = activeScheme.clone() as EditorColorsSchemeImpl
            assertNull(activeScheme.metaProperties.getProperty(IDENTITY_KEY))
            schemes.clear()
            schemes.add(activeScheme)
            val unrelated = fonts()

            FontPresetApplicator.revert()

            assertEquals(unrelated, fonts())
            assertEquals(backup, settings.state.fontOwnershipSnapshots)
            assertEquals(1, notices.size)
        }

    @Test
    fun nativeRenameRequiresExplicitApply() =
        onEdt {
            FontPresetApplicator.apply(customFonts())
            val backup = settings.state.fontOwnershipSnapshots.toMap()
            activeScheme = (activeScheme.clone() as EditorColorsSchemeImpl).apply { name = "Native rename" }
            schemes.clear()
            schemes.add(activeScheme)
            val renamedFonts = fonts()
            settings.state.fontPresetEnabled = true
            settings.state.fontPresetName = FontPreset.CUSTOM.name

            FontPresetApplicator.applyFromState()
            assertEquals(renamedFonts, fonts())
            assertEquals(backup, settings.state.fontOwnershipSnapshots)
            FontPresetApplicator.revert()

            assertEquals(renamedFonts, fonts())
            assertEquals(backup, settings.state.fontOwnershipSnapshots)
            FontPresetApplicator.apply(customFonts().copy(fontFamily = "Monospaced"))
            FontPresetApplicator.revert()
            assertEquals(renamedFonts, fonts())
            assertEquals(backup, settings.state.fontOwnershipSnapshots)
        }

    @Test
    fun nativeCopyCapturesItsOwnBaseline() =
        onEdt {
            val original = activeScheme
            FontPresetApplicator.apply(customFonts())
            activeScheme = (original.clone() as EditorColorsSchemeImpl).apply { name = "Writing copy" }
            schemes.add(activeScheme)
            val copyBaseline = fonts()
            FontPresetApplicator.apply(customFonts().copy(fontFamily = "Monospaced"))

            FontPresetApplicator.revert()

            assertEquals(copyBaseline, fonts())
            assertEquals("Dialog", original.editorFontName)
            assertTrue(settings.state.fontOwnershipSnapshots.isEmpty())
        }

    @Test
    fun xmlReloadRetainsIdentity() =
        onEdt {
            FontPresetApplicator.apply(customFonts())
            val identity = assertNotNull(activeScheme.metaProperties.getProperty(IDENTITY_KEY))
            val keys =
                settings.state.fontOwnershipSnapshots.keys
                    .toSet()
            assertEquals(2, settings.state.fontOwnershipVersion)
            assertTrue(keys.all { it == "v2:EDITOR:$identity" })
            activeScheme = xmlCopy()
            schemes[0] = activeScheme
            reloadSettings()

            assertEquals(identity, activeScheme.metaProperties.getProperty(IDENTITY_KEY))
            FontPresetApplicator.apply(customFonts())
            assertEquals(keys, settings.state.fontOwnershipSnapshots.keys)
        }

    @Test fun duplicateIdentityRemainsSuspended() = onEdt { checkCollision(oneShot = false) }

    @Test fun duplicateOneShotRemainsSuspended() = onEdt { checkCollision(oneShot = true) }

    @Test
    fun legacyBackupsStayOpaque() =
        onEdt {
            FontPresetApplicator.apply(customFonts())
            val legacy =
                settings.state.fontOwnershipSnapshots.values
                    .single()
                    .replace("version=\"2\"", "version=\"1\"")
            settings.state.fontOwnershipSnapshots.clear()
            settings.state.fontOwnershipSnapshots["EDITOR:${activeScheme.name}"] = legacy
            settings.state.fontOwnershipVersion = 1
            activeScheme.metaProperties.remove(IDENTITY_KEY)
            val baseline = fonts()
            val opaque = settings.state.fontOwnershipSnapshots.toMap()

            FontPresetApplicator.revert()
            assertEquals(baseline, fonts())
            FontPresetApplicator.apply(customFonts().copy(fontFamily = "Monospaced"))
            reloadSettings()
            FontPresetApplicator.revert()

            assertEquals(baseline, fonts())
            assertEquals(opaque, settings.state.fontOwnershipSnapshots)
            assertEquals(2, settings.state.fontOwnershipVersion)
        }

    @Test
    fun invalidIdentityBlocksExplicitApply() =
        onEdt {
            activeScheme.metaProperties.setProperty(IDENTITY_KEY, "future:identity")
            val baseline = fonts()

            FontPresetApplicator.apply(customFonts())

            assertEquals(baseline, fonts())
            assertEquals("future:identity", activeScheme.metaProperties.getProperty(IDENTITY_KEY))
            assertTrue(settings.state.fontOwnershipSnapshots.isEmpty())
            assertEquals(0, settings.state.fontOwnershipVersion)
            assertEquals(1, notices.size)
        }

    @Test
    fun futureStateBlocksMetadataAdoption() =
        onEdt {
            settings.state.fontOwnershipVersion = 99
            settings.state.fontOwnershipSnapshots["future:record"] = " exact opaque data "
            val stateXml = JDOMUtil.writeElement(XmlSerializer.serialize(settings.state))
            val baseline = fonts()

            FontPresetApplicator.apply(customFonts())
            FontPresetApplicator.applyInstalled(customFonts())
            FontPresetApplicator.revert()

            assertEquals(baseline, fonts())
            assertNull(activeScheme.metaProperties.getProperty(IDENTITY_KEY))
            assertEquals(stateXml, JDOMUtil.writeElement(XmlSerializer.serialize(settings.state)))
        }

    @Test
    fun invalidSettingsCannotAdoptIdentity() =
        onEdt {
            assertFailsWith<IllegalArgumentException> {
                FontPresetApplicator.apply(customFonts().copy(fontSize = Float.NaN))
            }
            assertNull(activeScheme.metaProperties.getProperty(IDENTITY_KEY))
            assertEquals(0, settings.state.fontOwnershipVersion)
            assertTrue(settings.state.fontOwnershipSnapshots.isEmpty())
        }

    @Test
    fun automaticApplyCannotAdoptConsole() =
        onEdt {
            activeScheme.consoleFontPreferences =
                FontPreferencesImpl().apply { register("Monospaced", 15f) }
            FontPresetApplicator.apply(customFonts())
            val console = FontData.capture(activeScheme.consoleFontPreferences)
            settings.state.fontPresetEnabled = true
            settings.state.fontPresetName = FontPreset.CUSTOM.name
            settings.state.fontApplyToConsole = true

            FontPresetApplicator.applyFromState()

            assertEquals(console, FontData.capture(activeScheme.consoleFontPreferences))
            assertEquals(1, settings.state.fontOwnershipSnapshots.size)
            assertEquals(1, notices.size)
        }

    private fun checkCollision(oneShot: Boolean) {
        if (oneShot) {
            FontPresetApplicator.applyInstalled(customFonts())
        } else {
            FontPresetApplicator.apply(customFonts())
        }
        val originalFonts = fonts()
        val copied = xmlCopy().apply { name = "Imported copy" }
        schemes.add(copied)

        FontPresetApplicator.apply(customFonts().copy(fontFamily = "Monospaced"))

        assertEquals(originalFonts, fonts())
        assertEquals(1, notices.size)
        assertContains(notices.single().content, "Apply remains blocked")
        assertContains(notices.single().content, "turning presets off")
        assertTrue(
            settings.state.fontOwnershipSnapshots.values
                .all { "status=\"SUSPENDED\"" in it },
        )
        schemes.remove(copied)
        reloadSettings()
        FontPresetApplicator.revert("Serif")
        assertEquals(originalFonts, fonts())
        assertTrue(settings.state.fontOwnershipSnapshots.isNotEmpty())
    }

    private fun xmlCopy(): EditorColorsSchemeImpl =
        EditorColorsSchemeImpl(activeScheme.parentScheme).apply {
            readExternal(JDOMUtil.load(JDOMUtil.writeElement(activeScheme.writeScheme())))
        }

    private fun reloadSettings() {
        val serialized = XmlSerializer.serialize(settings.state)
        settings =
            AyuIslandsSettings().apply {
                loadState(XmlSerializer.deserialize(serialized, AyuIslandsState::class.java))
            }
    }

    private fun fonts(): FontData = FontData.capture(activeScheme.fontPreferences)

    private fun customFonts(): FontSettings =
        FontSettings.fromPreset(FontPreset.CUSTOM).copy(fontFamily = "Serif", fontSize = 19f)

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private companion object {
        const val IDENTITY_KEY = "dev.ayuislands.fontOwnershipId"
    }
}
