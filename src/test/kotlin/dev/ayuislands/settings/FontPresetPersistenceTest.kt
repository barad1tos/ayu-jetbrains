package dev.ayuislands.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontStatus
import dev.ayuislands.font.FontWeight
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Preserve encoded preset preferences through Settings Apply and XML reload. */
class FontPresetPersistenceTest {
    private lateinit var settings: AyuIslandsSettings

    @BeforeTest
    fun setup() {
        settings = AyuIslandsSettings()
        mockkObject(AyuIslandsSettings.Companion, FontDetector, FontPresetApplicator)
        every { AyuIslandsSettings.getInstance() } answers { settings }
        every { FontDetector.invalidateCache() } returns Unit
        every { FontDetector.detectAll() } returns emptyMap()
        every { FontDetector.status(any()) } returns FontStatus.NOT_INSTALLED
        every { FontDetector.isFamilyInstalled(any()) } returns false
        every { FontPresetApplicator.apply(any()) } returns Unit
        every { FontPresetApplicator.revert() } returns Unit
    }

    @AfterTest
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `editing a known preset preserves other encoded preferences after reload`() {
        SwingUtilities.invokeAndWait {
            val original =
                mapOf(
                    "FUTURE_PRESET" to "21.00|1.37|true|FUTURE_WEIGHT|Unavailable Font|extension=42",
                    FontPreset.WHISPER.name to "15.00|1.10|false|FUTURE_WEIGHT|Unavailable Family",
                    FontPreset.AMBIENT.name to "14|1.2|true|REGULAR",
                )
            settings.state.fontPresetCustomizations.putAll(original)
            val panel = FontPresetPanel()
            panel.loadState()
            assertFalse(panel.isModified())

            editFontSize(panel)
            assertTrue(panel.isModified())
            panel.apply()
            assertFalse(panel.isModified())
            reloadSettings()

            assertEquals(
                original + (FontPreset.AMBIENT.name to "19.0|1.2|true|REGULAR"),
                settings.state.fontPresetCustomizations,
            )
            val reopened = FontPresetPanel()
            reopened.loadState()
            assertFalse(reopened.isModified())
        }
    }

    @Test
    fun `disable and reenable preserve sparse preferences through reload`() {
        SwingUtilities.invokeAndWait {
            val original = mapOf("UNAVAILABLE" to "opaque|future|value")
            settings.state.fontPresetCustomizations.putAll(original)
            settings.state.fontPresetEnabled = true
            val panel = FontPresetPanel()
            panel.loadState()
            setEnabled(panel, false)
            panel.apply()
            reloadSettings()
            assertFalse(settings.state.fontPresetEnabled)
            assertEquals(original, settings.state.fontPresetCustomizations)

            val reopened = FontPresetPanel()
            reopened.loadState()
            setEnabled(reopened, true)
            reopened.apply()
            reloadSettings()
            assertTrue(settings.state.fontPresetEnabled)
            assertEquals(original, settings.state.fontPresetCustomizations)
        }
    }

    @Test
    fun `reset discards pending edits without changing stored preferences`() {
        SwingUtilities.invokeAndWait {
            val original = mapOf("UNAVAILABLE" to "opaque|future|value")
            settings.state.fontPresetCustomizations.putAll(original)
            val panel = FontPresetPanel()
            panel.loadState()
            editFontSize(panel)
            panel.reset()
            assertFalse(panel.isModified())
            panel.apply()
            reloadSettings()
            assertEquals(original, settings.state.fontPresetCustomizations)
        }
    }

    @Test
    fun `apply preserves unknown entries added while settings are open`() {
        SwingUtilities.invokeAndWait {
            val panel = FontPresetPanel()
            panel.loadState()
            settings.state.fontPresetCustomizations["FUTURE_PRESET"] = "new|unavailable|value"
            editFontSize(panel)
            panel.apply()
            reloadSettings()
            assertEquals("new|unavailable|value", settings.state.fontPresetCustomizations["FUTURE_PRESET"])
        }
    }

    @Test
    fun `repeated apply preserves a known preset changed outside the panel`() {
        SwingUtilities.invokeAndWait {
            settings.state.fontPresetCustomizations[FontPreset.WHISPER.name] = "15|1.1|false|LIGHT"
            val panel = FontPresetPanel()
            panel.loadState()
            val externalValue = "21|1.8|true|SEMI_BOLD"
            settings.state.fontPresetCustomizations[FontPreset.WHISPER.name] = externalValue

            editFontSize(panel)
            panel.apply()

            assertEquals(externalValue, settings.state.fontPresetCustomizations[FontPreset.WHISPER.name])
            assertFalse(panel.isModified())
            panel.apply()
            reloadSettings()
            assertEquals(
                mapOf(
                    FontPreset.WHISPER.name to externalValue,
                    FontPreset.AMBIENT.name to "19.0|1.2|true|REGULAR",
                ),
                settings.state.fontPresetCustomizations,
            )
        }
    }

    private fun editFontSize(panel: FontPresetPanel) {
        val method =
            FontPresetPanel::class.java.getDeclaredMethod(
                "updateCurrentSettings",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                FontWeight::class.java,
            )
        method.isAccessible = true
        method.invoke(panel, 19f, 1.2f, true, FontWeight.REGULAR)
    }

    private fun setEnabled(
        panel: FontPresetPanel,
        enabled: Boolean,
    ) {
        val field = FontPresetPanel::class.java.getDeclaredField("pendingEnabled")
        field.isAccessible = true
        field.setBoolean(panel, enabled)
    }

    private fun reloadSettings() {
        val xml = JDOMUtil.writeElement(XmlSerializer.serialize(settings.state))
        val reloaded = XmlSerializer.deserialize(JDOMUtil.load(xml), AyuIslandsState::class.java)
        settings = AyuIslandsSettings().apply { loadState(reloaded) }
    }
}
