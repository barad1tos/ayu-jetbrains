package dev.ayuislands.accent

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.util.messages.MessageBus
import dev.ayuislands.accent.conflict.ConflictEntry
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.accent.conflict.ConflictType
import dev.ayuislands.indent.IndentRainbowSync
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import java.awt.Window
import java.lang.reflect.Method
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [AccentApplicator].
 *
 * Uses `mockkObject(AccentApplicator)` with `callOriginal()` to test the real
 * apply/revert logic while intercepting `applyElements` and `syncCodeGlanceProViewport`
 * which require the IntelliJ extension point system (unavailable in unit tests).
 */
class AccentApplicatorTest {
    private val mockScheme = mockk<EditorColorsScheme>(relaxed = true)
    private val mockColorsManager = mockk<EditorColorsManager>(relaxed = true)
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()
    private val mockApplication = mockk<com.intellij.openapi.application.Application>(relaxed = true)
    private val mockMessageBus = mockk<MessageBus>(relaxed = true)

    @BeforeTest
    fun setUp() {
        saveOriginalEpName()

        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns true

        mockkStatic(UIManager::class)

        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockColorsManager
        every { mockColorsManager.globalScheme } returns mockScheme
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns TextAttributes()

        // ApplicationManager must be mocked BEFORE AyuIslandsSettings.Companion,
        // because getInstance() calls ApplicationManager.getApplication().getService()
        // during MockK recording.
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.messageBus } returns mockMessageBus
        every { mockMessageBus.syncPublisher(EditorColorsManager.TOPIC) } returns mockk(relaxed = true)

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        mockkObject(ConflictRegistry)
        every { ConflictRegistry.getConflictFor(any()) } returns null

        mockkStatic(Window::class)
        every { Window.getWindows() } returns emptyArray()

        mockkStatic(PluginManagerCore::class)
        every { PluginManagerCore.getPlugin(any()) } returns null

        // D-15 plumbing: revertAll iterates ProjectManager.openProjects and calls
        // ComponentTreeRefresher.notifyOnly per usable project. Unit tests don't boot
        // the platform, so both must be stubbed or the new notifyOnly loop blows up
        // with "Can't get extension point" / a null ProjectManager.
        mockkStatic(com.intellij.openapi.project.ProjectManager::class)
        val mockProjectManager = mockk<com.intellij.openapi.project.ProjectManager>(relaxed = true)
        every {
            com.intellij.openapi.project.ProjectManager
                .getInstance()
        } returns mockProjectManager
        every { mockProjectManager.openProjects } returns emptyArray()

        mockkObject(dev.ayuislands.ui.ComponentTreeRefresher)
        every {
            dev.ayuislands.ui.ComponentTreeRefresher
                .notifyOnly(any())
        } returns Unit

        // Reset CGP cached state before each test
        resetCgpState()
    }

    @AfterTest
    fun tearDown() {
        restoreOriginalEpName()
        unmockkAll()
        clearAllMocks()
    }

    /**
     * Calls [AccentApplicator.apply] with the extension point methods stubbed out
     * (they require the IntelliJ platform extension registry, unavailable in unit tests).
     */
    private fun applyWithoutExtensions(accentHex: String) {
        val accent = Color.decode(accentHex)

        // Replicate the always-on logic from AccentApplicator.apply() without
        // applyElements (requires EP_NAME) and syncCodeGlanceProViewport (requires CGP).
        invokePrivate("applyAlwaysOnUiKeys", accent)
        invokePrivate("applyAlwaysOnEditorKeys", accent)
        val windows = Window.getWindows()
        invokePrivate("repaintAllWindows", windows)
    }

    /**
     * Calls [AccentApplicator.revertAll] with the extension point iteration stubbed out.
     */
    private fun revertWithoutExtensions() {
        // Replicate the always-on revert keys from AccentApplicator.revertAll()
        val alwaysOnUiKeys = getPrivateField<List<String>>("ALWAYS_ON_UI_KEYS")
        for (key in alwaysOnUiKeys) {
            UIManager.put(key, null)
        }
        UIManager.put("GotItTooltip.foreground", null)
        UIManager.put("GotItTooltip.Button.foreground", null)
        UIManager.put("GotItTooltip.Header.foreground", null)
        UIManager.put("Button.default.focusedBorderColor", null)
        UIManager.put("Button.default.startBorderColor", null)
        UIManager.put("Button.default.endBorderColor", null)
        UIManager.put("EditorTabs.underlinedTabBackground", null)

        invokePrivate("revertAlwaysOnEditorKeys")

        val windows = Window.getWindows()
        invokePrivate("repaintAllWindows", windows)
    }

    private fun invokePrivate(
        methodName: String,
        vararg args: Any,
    ) {
        val method =
            AccentApplicator::class.java.declaredMethods
                .first { it.name == methodName }
        method.isAccessible = true
        method.invoke(AccentApplicator, *args)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getPrivateField(fieldName: String): T {
        val field = AccentApplicator::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(AccentApplicator) as T
    }

    private fun setPrivateField(
        fieldName: String,
        value: Any?,
    ) {
        val field = AccentApplicator::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(AccentApplicator, value)
    }

    @Test
    fun `apply sets always-on UI keys`() {
        applyWithoutExtensions("#FFCC66")

        verify(atLeast = 13) { UIManager.put(any<String>(), any<Color>()) }
    }

    @Test
    fun `apply sets always-on editor color keys`() {
        applyWithoutExtensions("#FFCC66")

        verify(atLeast = 2) { mockScheme.setColor(any<ColorKey>(), any<Color>()) }
    }

    @Test
    fun `apply sets editor attribute overrides`() {
        applyWithoutExtensions("#FFCC66")

        verify(atLeast = 9) { mockScheme.setAttributes(any<TextAttributesKey>(), any<TextAttributes>()) }
    }

    @Test
    fun `revertAll clears always-on UI keys`() {
        revertWithoutExtensions()

        verify(atLeast = 13) { UIManager.put(any<String>(), null) }
    }

    @Test
    fun `revertAll clears editor color keys`() {
        revertWithoutExtensions()

        verify(atLeast = 2) { mockScheme.setColor(any<ColorKey>(), null) }
    }

    @Test
    fun `apply with FULL tab mode sets tinted background with alpha 50`() {
        state.glowTabMode = "FULL"

        applyWithoutExtensions("#FFCC66")

        verify {
            UIManager.put(
                "EditorTabs.underlinedTabBackground",
                match<Color> { color ->
                    color.alpha == 50
                },
            )
        }
    }

    @Test
    fun `apply with MINIMAL tab mode sets transparent background`() {
        state.glowTabMode = "MINIMAL"

        applyWithoutExtensions("#FFCC66")

        verify {
            UIManager.put(
                "EditorTabs.underlinedTabBackground",
                match<Color> { color ->
                    color.alpha == 0
                },
            )
        }
    }

    @Test
    fun `apply computes white foreground for dark accent`() {
        applyWithoutExtensions("#1F2430")

        verify {
            UIManager.put("GotItTooltip.foreground", Color.WHITE)
        }
    }

    @Test
    fun `apply computes dark foreground for light accent`() {
        applyWithoutExtensions("#FFCD66")

        verify {
            UIManager.put(
                "GotItTooltip.foreground",
                match<Color> { color ->
                    color != Color.WHITE
                },
            )
        }
    }

    @Test
    fun `revertAll repaints windows`() {
        val mockWindow = mockk<Window>(relaxed = true)
        every { Window.getWindows() } returns arrayOf(mockWindow)

        revertWithoutExtensions()

        verify { mockWindow.repaint() }
    }

    // Tab mode: OFF branch

    @Test
    fun `apply with OFF tab mode neutralizes underline and sets transparent background`() {
        state.glowTabMode = "OFF"

        applyWithoutExtensions("#FFCC66")

        verify {
            UIManager.put("EditorTabs.underlinedTabBackground", match<Color> { it.alpha == 0 })
        }
        // When OFF, the underline border color is set to neutral gray from variant
        verify {
            UIManager.put(
                "EditorTabs.underlinedBorderColor",
                match<Color> { color ->
                    // MIRAGE neutralGray is #445066
                    color == Color.decode("#445066")
                },
            )
        }
    }

    @Test
    fun `apply with OFF tab mode and null variant sets null underline border`() {
        state.glowTabMode = "OFF"
        every { AyuVariant.detect() } returns null

        applyWithoutExtensions("#FFCC66")

        verify {
            UIManager.put("EditorTabs.underlinedBorderColor", null)
        }
    }

    @Test
    fun `apply with null glowTabMode defaults to MINIMAL`() {
        state.glowTabMode = null

        applyWithoutExtensions("#FFCC66")

        // MINIMAL sets transparent background
        verify {
            UIManager.put("EditorTabs.underlinedTabBackground", match<Color> { it.alpha == 0 })
        }
    }

    @Test
    fun `apply with unknown glowTabMode defaults to MINIMAL`() {
        state.glowTabMode = "NONEXISTENT_MODE"

        applyWithoutExtensions("#FFCC66")

        verify {
            UIManager.put("EditorTabs.underlinedTabBackground", match<Color> { it.alpha == 0 })
        }
    }

    // Darkened accent button borders

    @Test
    fun `apply sets darkened accent for default button borders`() {
        applyWithoutExtensions("#FFCC66")

        verify { UIManager.put("Button.default.focusedBorderColor", any<Color>()) }
        verify { UIManager.put("Button.default.startBorderColor", any<Color>()) }
        verify { UIManager.put("Button.default.endBorderColor", any<Color>()) }
    }

    // GotItTooltip foreground keys

    @Test
    fun `apply sets all GotItTooltip foreground keys for dark accent`() {
        applyWithoutExtensions("#1F2430")

        verify { UIManager.put("GotItTooltip.foreground", Color.WHITE) }
        verify { UIManager.put("GotItTooltip.Button.foreground", Color.WHITE) }
        verify { UIManager.put("GotItTooltip.Header.foreground", Color.WHITE) }
    }

    @Test
    fun `apply sets all GotItTooltip foreground keys for light accent`() {
        val darkForeground = Color(0x1F2430)
        applyWithoutExtensions("#FFFFFF")

        verify { UIManager.put("GotItTooltip.foreground", darkForeground) }
        verify { UIManager.put("GotItTooltip.Button.foreground", darkForeground) }
        verify { UIManager.put("GotItTooltip.Header.foreground", darkForeground) }
    }

    // Specific always-on UI keys

    @Test
    fun `apply sets all specific always-on UI keys`() {
        applyWithoutExtensions("#FFCC66")

        val expectedKeys =
            listOf(
                "GotItTooltip.background",
                "GotItTooltip.borderColor",
                "Button.default.startBackground",
                "Button.default.endBackground",
                "Component.focusedBorderColor",
                "Component.focusColor",
                "DragAndDrop.borderColor",
                "TrialWidget.Alert.borderColor",
                "TrialWidget.Alert.foreground",
                "OnePixelDivider.background",
                "ToolWindow.HeaderTab.underlineColor",
                "TabbedPane.underlineColor",
                "EditorTabs.underlinedBorderColor",
            )

        for (key in expectedKeys) {
            verify { UIManager.put(key, any<Color>()) }
        }
    }

    // FULL tab mode tinted color verification

    @Test
    fun `apply with FULL tab mode uses accent RGB with alpha 50`() {
        state.glowTabMode = "FULL"
        val accent = Color.decode("#FFCC66")

        applyWithoutExtensions("#FFCC66")

        verify {
            UIManager.put(
                "EditorTabs.underlinedTabBackground",
                match<Color> { color ->
                    color.red == accent.red &&
                        color.green == accent.green &&
                        color.blue == accent.blue &&
                        color.alpha == 50
                },
            )
        }
    }

    // repaintAllWindows

    @Test
    fun `repaintAllWindows repaints multiple windows`() {
        val window1 = mockk<Window>(relaxed = true)
        val window2 = mockk<Window>(relaxed = true)
        val window3 = mockk<Window>(relaxed = true)

        invokePrivate("repaintAllWindows", arrayOf(window1, window2, window3))

        verify { window1.repaint() }
        verify { window2.repaint() }
        verify { window3.repaint() }
    }

    @Test
    fun `repaintAllWindows handles empty array without error`() {
        invokePrivate("repaintAllWindows", emptyArray<Window>())
        // No exception thrown = pass
    }

    // neutralizeOrRevert

    @Test
    fun `neutralizeOrRevert calls applyNeutral when variant is non-null`() {
        val element = mockk<AccentElement>(relaxed = true)

        invokeNeutralizeOrRevert(element, AyuVariant.MIRAGE)

        verify { element.applyNeutral(AyuVariant.MIRAGE) }
        verify(exactly = 0) { element.revert() }
    }

    @Test
    fun `neutralizeOrRevert calls revert when variant is null`() {
        val element = mockk<AccentElement>(relaxed = true)

        invokeNeutralizeOrRevert(element, null)

        verify { element.revert() }
        verify(exactly = 0) { element.applyNeutral(any()) }
    }

    @Test
    fun `neutralizeOrRevert catches RuntimeException from applyNeutral`() {
        val element = mockk<AccentElement>(relaxed = true)
        every { element.applyNeutral(any()) } throws RuntimeException("test error")
        every { element.displayName } returns "TestElement"

        // Should not throw
        invokeNeutralizeOrRevert(element, AyuVariant.DARK)
    }

    @Test
    fun `neutralizeOrRevert catches RuntimeException from revert`() {
        val element = mockk<AccentElement>(relaxed = true)
        every { element.revert() } throws RuntimeException("test error")
        every { element.displayName } returns "TestElement"

        // Should not throw
        invokeNeutralizeOrRevert(element, null)
    }

    // logCgpWarning

    @Test
    fun `logCgpWarning does not throw`() {
        invokePrivate("logCgpWarning", "test action", RuntimeException("test message"))
    }

    // syncCodeGlanceProViewport early return when disabled

    @Test
    fun `syncCodeGlanceProViewport returns early when cgpIntegrationEnabled is false`() {
        state.cgpIntegrationEnabled = false

        invokePrivate("syncCodeGlanceProViewport", "#FFCC66")

        // Should not throw — cgpMethodsResolved should remain false since we never
        // reach resolveCgpMethods
        // cgpMethodsResolved may be true from prior tests (static object state),
        // but the method exits before doing any work on the config
        val service = getPrivateField<Any?>("cgpService")
        // cgpService should be null (no CGP plugin installed in tests)
        assertEquals(null, service)
    }

    @Test
    fun `syncCodeGlanceProViewport with enabled flag but no CGP plugin does not throw`() {
        state.cgpIntegrationEnabled = true

        // resolveCgpMethods will try to find CGP plugin which doesn't exist —
        // cgpService stays null, method returns early
        invokePrivate("syncCodeGlanceProViewport", "#FFCC66")

        val service = getPrivateField<Any?>("cgpService")
        assertEquals(null, service)
    }

    // revertAlwaysOnEditorKeys

    @Test
    fun `revertAlwaysOnEditorKeys clears all editor color keys`() {
        invokePrivate("revertAlwaysOnEditorKeys")

        verify(atLeast = 2) { mockScheme.setColor(any<ColorKey>(), null) }
    }

    @Test
    fun `revertAlwaysOnEditorKeys resets all attribute overrides`() {
        invokePrivate("revertAlwaysOnEditorKeys")

        verify(atLeast = 9) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
    }

    // applyAlwaysOnEditorKeys detailed checks

    @Test
    fun `applyAlwaysOnEditorKeys sets foreground on attribute overrides marked with foreground`() {
        val accent = Color.decode("#FFCC66")
        val capturedAttrs = mutableListOf<TextAttributes>()
        every { mockScheme.setAttributes(any(), capture(capturedAttrs)) } returns Unit

        invokePrivate("applyAlwaysOnEditorKeys", accent)

        // At least one attribute should have accent as foreground
        val withForeground = capturedAttrs.filter { it.foregroundColor == accent }
        assertTrue(withForeground.isNotEmpty(), "Expected at least one attr with accent foreground")
    }

    @Test
    fun `applyAlwaysOnEditorKeys sets effectColor on attribute overrides marked with effectColor`() {
        val accent = Color.decode("#FFCC66")
        val capturedAttrs = mutableListOf<TextAttributes>()
        every { mockScheme.setAttributes(any(), capture(capturedAttrs)) } returns Unit

        invokePrivate("applyAlwaysOnEditorKeys", accent)

        val withEffect = capturedAttrs.filter { it.effectColor == accent }
        assertTrue(withEffect.isNotEmpty(), "Expected at least one attr with accent effectColor")
    }

    @Test
    fun `applyAlwaysOnEditorKeys sets errorStripeColor on attribute overrides marked with errorStripe`() {
        val accent = Color.decode("#FFCC66")
        val capturedAttrs = mutableListOf<TextAttributes>()
        every { mockScheme.setAttributes(any(), capture(capturedAttrs)) } returns Unit

        invokePrivate("applyAlwaysOnEditorKeys", accent)

        val withStripe = capturedAttrs.filter { it.errorStripeColor == accent }
        assertTrue(withStripe.isNotEmpty(), "Expected at least one attr with accent errorStripeColor")
    }

    @Test
    fun `applyAlwaysOnEditorKeys clones existing attributes instead of replacing`() {
        val existingAttrs = TextAttributes()
        existingAttrs.foregroundColor = Color.RED
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns existingAttrs

        val accent = Color.decode("#FFCC66")
        invokePrivate("applyAlwaysOnEditorKeys", accent)

        // Verify that setAttributes was called with cloned attributes
        verify(atLeast = 1) { mockScheme.setAttributes(any(), any()) }
    }

    @Test
    fun `applyAlwaysOnEditorKeys creates new TextAttributes when existing is null`() {
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns null

        val accent = Color.decode("#FFCC66")
        invokePrivate("applyAlwaysOnEditorKeys", accent)

        // Should still set attributes (new TextAttributes created)
        verify(atLeast = 9) { mockScheme.setAttributes(any(), any()) }
    }

    // ALWAYS_ON_UI_KEYS and ALWAYS_ON_EDITOR_COLOR_KEYS field verification

    @Test
    fun `ALWAYS_ON_UI_KEYS contains expected count of keys`() {
        val keys = getPrivateField<List<String>>("ALWAYS_ON_UI_KEYS")
        assertEquals(13, keys.size, "Expected 13 always-on UI keys")
    }

    @Test
    fun `ALWAYS_ON_EDITOR_COLOR_KEYS contains expected keys`() {
        val keys = getPrivateField<List<ColorKey>>("ALWAYS_ON_EDITOR_COLOR_KEYS")
        assertEquals(3, keys.size, "Expected 3 always-on editor color keys")
    }

    @Test
    fun `ALWAYS_ON_EDITOR_ATTR_OVERRIDES contains expected count`() {
        val overrides = getPrivateField<List<Any>>("ALWAYS_ON_EDITOR_ATTR_OVERRIDES")
        assertEquals(9, overrides.size, "Expected 9 attribute overrides")
    }

    // Apply with different variants

    @Test
    fun `apply with DARK variant sets correct neutral gray for OFF tab mode`() {
        state.glowTabMode = "OFF"
        every { AyuVariant.detect() } returns AyuVariant.DARK

        applyWithoutExtensions("#E6B450")

        verify {
            UIManager.put(
                "EditorTabs.underlinedBorderColor",
                match<Color> { color ->
                    color == Color.decode("#2C3342")
                },
            )
        }
    }

    @Test
    fun `apply with LIGHT variant sets correct neutral gray for OFF tab mode`() {
        state.glowTabMode = "OFF"
        every { AyuVariant.detect() } returns AyuVariant.LIGHT

        applyWithoutExtensions("#F29718")

        verify {
            UIManager.put(
                "EditorTabs.underlinedBorderColor",
                match<Color> { color ->
                    color == Color.decode("#CCC8B8")
                },
            )
        }
    }

    // Edge case: mid-range accent color

    @Test
    fun `apply computes correct foreground for mid-brightness accent`() {
        // A medium-dark color should produce white foreground
        applyWithoutExtensions("#555555")

        verify {
            UIManager.put("GotItTooltip.foreground", Color.WHITE)
        }
    }

    // revertAll clears specific extra keys

    @Test
    fun `revertAll clears GotItTooltip foreground keys`() {
        revertWithoutExtensions()

        verify { UIManager.put("GotItTooltip.foreground", null) }
        verify { UIManager.put("GotItTooltip.Button.foreground", null) }
        verify { UIManager.put("GotItTooltip.Header.foreground", null) }
    }

    @Test
    fun `revertAll clears button border keys`() {
        revertWithoutExtensions()

        verify { UIManager.put("Button.default.focusedBorderColor", null) }
        verify { UIManager.put("Button.default.startBorderColor", null) }
        verify { UIManager.put("Button.default.endBorderColor", null) }
    }

    @Test
    fun `revertAll clears tab background key`() {
        revertWithoutExtensions()

        verify { UIManager.put("EditorTabs.underlinedTabBackground", null) }
    }

    // resolveCgpMethods

    @Test
    fun `resolveCgpMethods sets cgpMethodsResolved to true even when plugin not found`() {
        // Reset the flag first via reflection
        resetCgpState()

        invokePrivate("resolveCgpMethods")

        val resolved = getPrivateField<Boolean>("cgpMethodsResolved")
        assertTrue(resolved, "cgpMethodsResolved should be true after first call")
    }

    @Test
    fun `resolveCgpMethods is idempotent after first call`() {
        resetCgpState()

        invokePrivate("resolveCgpMethods")
        invokePrivate("resolveCgpMethods")

        // Should not throw — second call exits immediately via the guard
        val resolved = getPrivateField<Boolean>("cgpMethodsResolved")
        assertTrue(resolved)
    }

    // AttrOverride data class

    @Test
    fun `AttrOverride data class accessible via reflection`() {
        val overrides = getPrivateField<List<Any>>("ALWAYS_ON_EDITOR_ATTR_OVERRIDES")
        val first = overrides.first()

        // Verify we can read properties from the data class
        val keyField = first.javaClass.getDeclaredField("key")
        keyField.isAccessible = true
        val key = keyField.get(first) as String
        assertEquals("BOOKMARKS_ATTRIBUTES", key)
    }

    // neutralizeOrRevert: all AyuVariant entries

    @Test
    fun `neutralizeOrRevert with each AyuVariant calls applyNeutral correctly`() {
        for (variant in AyuVariant.entries) {
            val element = mockk<AccentElement>(relaxed = true)
            invokeNeutralizeOrRevert(element, variant)
            verify { element.applyNeutral(variant) }
        }
    }

    @Test
    fun `logCgpWarning handles exception with null message`() {
        invokePrivate("logCgpWarning", "test action", RuntimeException())
    }

    // syncCodeGlanceProViewport: various null method fields

    @Test
    fun `syncCodeGlanceProViewport exits early when cgpService is null after resolve`() {
        state.cgpIntegrationEnabled = true
        setPrivateField("cgpMethodsResolved", true)
        // cgpService left as null

        invokePrivate("syncCodeGlanceProViewport", "#FFCC66")
    }

    @Test
    fun `syncCodeGlanceProViewport exits early when cgpGetState is null`() {
        state.cgpIntegrationEnabled = true
        setPrivateField("cgpMethodsResolved", true)
        setPrivateField("cgpService", Any())

        invokePrivate("syncCodeGlanceProViewport", "#FFCC66")
    }

    @Test
    fun `syncCodeGlanceProViewport exits early when cgpSetViewportColor is null`() {
        state.cgpIntegrationEnabled = true
        setPrivateField("cgpMethodsResolved", true)
        setPrivateField("cgpService", Any())
        setPrivateField("cgpGetState", Any::class.java.getMethod("toString"))

        invokePrivate("syncCodeGlanceProViewport", "#FFCC66")
    }

    // resolveCgpMethods: additional scenarios

    @Test
    fun `resolveCgpMethods skips when already resolved`() {
        setPrivateField("cgpMethodsResolved", true)

        invokePrivate("resolveCgpMethods")

        val service = getPrivateField<Any?>("cgpService")
        assertEquals(null, service)
    }

    @Test
    fun `resolveCgpMethods returns when plugin classloader is null`() {
        val mockPlugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { PluginManagerCore.getPlugin(any()) } returns mockPlugin
        every { mockPlugin.pluginClassLoader } returns null

        invokePrivate("resolveCgpMethods")

        val service = getPrivateField<Any?>("cgpService")
        assertEquals(null, service)
        assertTrue(getPrivateField("cgpMethodsResolved"))
    }

    // Field constant verification

    @Test
    fun `DARK_FOREGROUND constant has correct value`() {
        val darkFg = getPrivateField<Color>("DARK_FOREGROUND")
        assertEquals(Color(0x1F2430), darkFg)
    }

    @Test
    fun `TAB_ACCENT_BG_ALPHA constant is 50`() {
        val alpha = getPrivateField<Int>("TAB_ACCENT_BG_ALPHA")
        assertEquals(50, alpha)
    }

    // Apply round-trip

    @Test
    fun `apply then revert clears all UI keys`() {
        applyWithoutExtensions("#FFCC66")
        revertWithoutExtensions()

        val alwaysOnUiKeys = getPrivateField<List<String>>("ALWAYS_ON_UI_KEYS")
        for (key in alwaysOnUiKeys) {
            verify { UIManager.put(key, null) }
        }
    }

    @Test
    fun `apply then revert clears editor keys`() {
        applyWithoutExtensions("#FFCC66")
        revertWithoutExtensions()

        verify(atLeast = 2) { mockScheme.setColor(any<ColorKey>(), null) }
        verify(atLeast = 9) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
    }

    // Tests for public apply() method

    @Test
    fun `apply calls applyAlwaysOnUiKeys and applyAlwaysOnEditorKeys on EDT`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any(), any()) } returns Unit
        state.cgpIntegrationEnabled = false

        AccentApplicator.apply("#FFCC66")

        // Verify always-on UI keys were set (proves applyAlwaysOnUiKeys ran)
        verify(atLeast = 13) { UIManager.put(any<String>(), any<Color>()) }
        // Verify always-on editor keys were set (proves applyAlwaysOnEditorKeys ran)
        verify(atLeast = 2) { mockScheme.setColor(any<ColorKey>(), any<Color>()) }
    }

    @Test
    fun `apply invokes IndentRainbowSync with the exact accent hex passed in`() {
        // Regression guard against passthrough drift: a future refactor that dropped the hex
        // parameter and called AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        // internally would make IR paint the GLOBAL accent during rotation + override scenarios,
        // but the old `any()` matcher would still pass. Assert the exact hex flows through.
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any(), any()) } returns Unit
        state.cgpIntegrationEnabled = false
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        AccentApplicator.apply("#FFCC66")

        verify { IndentRainbowSync.apply(AyuVariant.MIRAGE, "#FFCC66") }
    }

    @Test
    fun `apply skips IndentRainbowSync when variant is null`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        state.cgpIntegrationEnabled = false
        every { AyuVariant.detect() } returns null

        AccentApplicator.apply("#FFCC66")

        verify(exactly = 0) { IndentRainbowSync.apply(any(), any()) }
    }

    @Test
    fun `apply calls repaintAllWindows`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any(), any()) } returns Unit
        state.cgpIntegrationEnabled = false
        val mockWindow = mockk<Window>(relaxed = true)
        every { Window.getWindows() } returns arrayOf(mockWindow)

        AccentApplicator.apply("#FFCC66")

        verify { mockWindow.repaint() }
    }

    @Test
    fun `apply runs work directly when on EDT`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any(), any()) } returns Unit
        state.cgpIntegrationEnabled = false
        every { SwingUtilities.isEventDispatchThread() } returns true

        AccentApplicator.apply("#FFCC66")

        // If on EDT, work runs synchronously, so UIManager.put should be called.
        verify(atLeast = 1) { UIManager.put(any<String>(), any()) }
    }

    @Test
    fun `apply persists accent hex to lastAppliedAccentHex for next-startup anti-flicker`() {
        // Regression guard for Phase 40-anti-flicker: AyuIslandsAppListener.appFrameCreated
        // reads state.lastAppliedAccentHex on the next IDE restart to paint the first frame
        // without a global-accent flash. If a refactor drops the state write inside apply(),
        // multi-window restores would flicker Gold before each StartupActivity ran.
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any(), any()) } returns Unit
        state.cgpIntegrationEnabled = false

        AccentApplicator.apply("#5CCFE6")

        assertEquals("#5CCFE6", state.lastAppliedAccentHex)
    }

    @Test
    fun `apply updates lastAppliedAccentHex with last-write-wins semantics`() {
        // A later apply() must overwrite the persisted hex so settings changes, rotation
        // ticks, and per-project swaps leave the right color for the next restart.
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any(), any()) } returns Unit
        state.cgpIntegrationEnabled = false

        AccentApplicator.apply("#5CCFE6")
        assertEquals("#5CCFE6", state.lastAppliedAccentHex)

        AccentApplicator.apply("#FF3333")
        assertEquals("#FF3333", state.lastAppliedAccentHex)
    }

    // Hex validation — Phase 40 Round 2 Fix B-1

    @Test
    fun `apply rejects invalid hex strings without throwing or mutating UIManager`() {
        // Phase 40 Round 2 Fix B-1: corrupted / hand-edited persisted hex must not
        // abort the first frame paint. AccentApplicator.apply now rejects anything that
        // doesn't match HEX_COLOR_PATTERN before reaching Color.decode (which would
        // throw NumberFormatException). Covers "garbage", empty string, and malformed
        // shapes that used to crash the applier.
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any(), any()) } returns Unit
        state.cgpIntegrationEnabled = false

        // None of these should throw; none should set the cached hex.
        AccentApplicator.apply("garbage")
        AccentApplicator.apply("")
        AccentApplicator.apply("FFCC66") // missing leading #
        AccentApplicator.apply("#12345") // 5 chars — too short
        AccentApplicator.apply("#1234567") // 7 chars — too long
        AccentApplicator.apply("#ZZZZZZ") // non-hex digits

        // UIManager.put must not have been called for any of these (apply short-circuits
        // before applyAlwaysOnUiKeys). The cached hex stays whatever it was (default null).
        verify(exactly = 0) { UIManager.put(any<String>(), any<Color>()) }
        assertEquals(null, state.lastAppliedAccentHex)
    }

    @Test
    fun `apply accepts well-formed 6-digit hex with hash prefix`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any(), any()) } returns Unit
        state.cgpIntegrationEnabled = false

        // Boundary: exactly #RRGGBB with valid hex digits. Must go through the full
        // apply flow (UIManager writes, lastAppliedAccentHex persisted).
        AccentApplicator.apply("#123456")

        verify(atLeast = 1) { UIManager.put(any<String>(), any<Color>()) }
        assertEquals("#123456", state.lastAppliedAccentHex)
    }

    @Test
    fun `apply accepts mixed-case hex digits`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any(), any()) } returns Unit
        state.cgpIntegrationEnabled = false

        // Upper and lower case 0-9A-Fa-f are all valid per Color.decode.
        AccentApplicator.apply("#AbCdEf")

        assertEquals("#AbCdEf", state.lastAppliedAccentHex)
    }

    @Test
    fun `HEX_COLOR_PATTERN matches expected shapes`() {
        // Positive
        assertTrue(AccentApplicator.HEX_COLOR_PATTERN.matches("#000000"))
        assertTrue(AccentApplicator.HEX_COLOR_PATTERN.matches("#FFFFFF"))
        assertTrue(AccentApplicator.HEX_COLOR_PATTERN.matches("#ffcc66"))
        assertTrue(AccentApplicator.HEX_COLOR_PATTERN.matches("#5CCFE6"))

        // Negative
        assertEquals(false, AccentApplicator.HEX_COLOR_PATTERN.matches(""))
        assertEquals(false, AccentApplicator.HEX_COLOR_PATTERN.matches("garbage"))
        assertEquals(false, AccentApplicator.HEX_COLOR_PATTERN.matches("FFCC66"))
        assertEquals(false, AccentApplicator.HEX_COLOR_PATTERN.matches("#12345"))
        assertEquals(false, AccentApplicator.HEX_COLOR_PATTERN.matches("#1234567"))
        assertEquals(false, AccentApplicator.HEX_COLOR_PATTERN.matches("#ZZZZZZ"))
        assertEquals(false, AccentApplicator.HEX_COLOR_PATTERN.matches("#12 34 56"))
    }

    @Test
    fun `apply posts to invokeLater when not on EDT`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any(), any()) } returns Unit
        state.cgpIntegrationEnabled = false
        every { SwingUtilities.isEventDispatchThread() } returns false
        every { mockApplication.invokeLater(any(), any<ModalityState>()) } answers {
            firstArg<Runnable>().run()
        }

        AccentApplicator.apply("#FFCC66")

        verify { mockApplication.invokeLater(any(), any<ModalityState>()) }
        verify(exactly = 0) { SwingUtilities.invokeLater(any()) }
        verify(atLeast = 1) { UIManager.put(any<String>(), any()) }
    }

    // Tests for public revertAll() method

    @Test
    fun `revertAll clears all UI keys and iterates EP`() {
        val mockElement = mockk<AccentElement>(relaxed = true)
        mockEpExtensionList(listOf(mockElement))

        AccentApplicator.revertAll()

        // Verify always-on UI keys cleared
        verify(atLeast = 13) { UIManager.put(any<String>(), null) }
        // Verify element.revert() was called
        verify { mockElement.revert() }
    }

    @Test
    fun `revertAll calls revert on each registered element`() {
        val element1 = mockk<AccentElement>(relaxed = true)
        val element2 = mockk<AccentElement>(relaxed = true)
        mockEpExtensionList(listOf(element1, element2))

        AccentApplicator.revertAll()

        verify { element1.revert() }
        verify { element2.revert() }
    }

    @Test
    fun `revertAll catches RuntimeException from element revert`() {
        val failingElement = mockk<AccentElement>(relaxed = true)
        every { failingElement.revert() } throws RuntimeException("revert failed")
        every { failingElement.displayName } returns "FailingElement"
        mockEpExtensionList(listOf(failingElement))

        // Should not throw
        AccentApplicator.revertAll()
    }

    @Test
    fun `revertAll continues after one element throws`() {
        val failingElement = mockk<AccentElement>(relaxed = true)
        every { failingElement.revert() } throws RuntimeException("revert failed")
        every { failingElement.displayName } returns "FailingElement"
        val successElement = mockk<AccentElement>(relaxed = true)
        mockEpExtensionList(listOf(failingElement, successElement))

        AccentApplicator.revertAll()

        verify { successElement.revert() }
    }

    @Test
    fun `revertAll clears editor keys via revertAlwaysOnEditorKeys`() {
        mockEpExtensionList(emptyList())

        AccentApplicator.revertAll()

        verify(atLeast = 2) { mockScheme.setColor(any<ColorKey>(), null) }
        verify(atLeast = 9) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
    }

    @Test
    fun `revertAll posts to invokeLater when not on EDT`() {
        mockEpExtensionList(emptyList())
        every { SwingUtilities.isEventDispatchThread() } returns false
        every { mockApplication.invokeLater(any(), any<ModalityState>()) } answers {
            firstArg<Runnable>().run()
        }

        AccentApplicator.revertAll()

        verify { mockApplication.invokeLater(any(), any<ModalityState>()) }
        verify(exactly = 0) { SwingUtilities.invokeLater(any()) }
        verify(atLeast = 13) { UIManager.put(any<String>(), null) }
    }

    // Tests for applyElements via reflection

    @Test
    fun `applyElements with enabled element calls element apply`() {
        val mockElement = mockk<AccentElement>(relaxed = true)
        every { mockElement.id } returns AccentElementId.CARET_ROW
        every { mockElement.displayName } returns "Caret Row"
        mockEpExtensionList(listOf(mockElement))

        val accent = Color.decode("#FFCC66")
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)

        verify { mockElement.apply(accent) }
    }

    @Test
    fun `applyElements with disabled toggle neutralizes element`() {
        val mockElement = mockk<AccentElement>(relaxed = true)
        every { mockElement.id } returns AccentElementId.CARET_ROW
        every { mockElement.displayName } returns "Caret Row"
        state.caretRow = false
        mockEpExtensionList(listOf(mockElement))

        val accent = Color.decode("#FFCC66")
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)

        verify { mockElement.applyNeutral(AyuVariant.MIRAGE) }
        verify(exactly = 0) { mockElement.apply(any()) }
    }

    @Test
    fun `applyElements with disabled toggle and null variant reverts element`() {
        val mockElement = mockk<AccentElement>(relaxed = true)
        every { mockElement.id } returns AccentElementId.CARET_ROW
        every { mockElement.displayName } returns "Caret Row"
        state.caretRow = false
        mockEpExtensionList(listOf(mockElement))

        val accent = Color.decode("#FFCC66")
        invokeApplyElements(state, accent, null)

        verify { mockElement.revert() }
        verify(exactly = 0) { mockElement.apply(any()) }
    }

    @Test
    fun `applyElements with conflict and no force override neutralizes`() {
        val mockElement = mockk<AccentElement>(relaxed = true)
        every { mockElement.id } returns AccentElementId.MATCHING_TAG
        every { mockElement.displayName } returns "Matching Tag"
        val conflict =
            ConflictEntry(
                pluginDisplayName = "Atom Material Icons",
                pluginId = "com.mallowigi",
                affectedElements = setOf(AccentElementId.MATCHING_TAG),
                type = ConflictType.BLOCK,
            )
        every { ConflictRegistry.getConflictFor(AccentElementId.MATCHING_TAG) } returns conflict
        mockEpExtensionList(listOf(mockElement))

        val accent = Color.decode("#FFCC66")
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)

        verify { mockElement.applyNeutral(AyuVariant.MIRAGE) }
        verify(exactly = 0) { mockElement.apply(any()) }
    }

    @Test
    fun `applyElements with conflict and force override applies anyway`() {
        val mockElement = mockk<AccentElement>(relaxed = true)
        every { mockElement.id } returns AccentElementId.MATCHING_TAG
        every { mockElement.displayName } returns "Matching Tag"
        val conflict =
            ConflictEntry(
                pluginDisplayName = "Atom Material Icons",
                pluginId = "com.mallowigi",
                affectedElements = setOf(AccentElementId.MATCHING_TAG),
                type = ConflictType.BLOCK,
            )
        every { ConflictRegistry.getConflictFor(AccentElementId.MATCHING_TAG) } returns conflict
        state.forceOverrides = mutableSetOf(AccentElementId.MATCHING_TAG.name)
        mockEpExtensionList(listOf(mockElement))

        val accent = Color.decode("#FFCC66")
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)

        verify { mockElement.apply(accent) }
    }

    @Test
    fun `applyElements catches RuntimeException from element apply`() {
        val mockElement = mockk<AccentElement>(relaxed = true)
        every { mockElement.id } returns AccentElementId.CARET_ROW
        every { mockElement.displayName } returns "Caret Row"
        every { mockElement.apply(any()) } throws RuntimeException("apply failed")
        mockEpExtensionList(listOf(mockElement))

        val accent = Color.decode("#FFCC66")
        // Should not throw
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)
    }

    @Test
    fun `applyElements processes multiple elements independently`() {
        val element1 = mockk<AccentElement>(relaxed = true)
        every { element1.id } returns AccentElementId.CARET_ROW
        every { element1.displayName } returns "Caret Row"
        every { element1.apply(any()) } throws RuntimeException("element1 failed")

        val element2 = mockk<AccentElement>(relaxed = true)
        every { element2.id } returns AccentElementId.SCROLLBAR
        every { element2.displayName } returns "Scrollbar"
        mockEpExtensionList(listOf(element1, element2))

        val accent = Color.decode("#FFCC66")
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)

        // element2 should still be applied despite element1 throwing
        verify { element2.apply(accent) }
    }

    // Defensive dispatch regression tests — capture try-catch isolation.
    // If a refactor removes the per-element try-catch in applyElements,
    // revertAll, or neutralizeOrRevert, these tests must fail.

    /**
     * Regression guard for the per-element try-catch at line 220-227 of
     * [AccentApplicator.applyElements]. If removed, the middle element's
     * exception would propagate and the third element would never receive
     * `apply`, breaking isolation between unrelated accent elements.
     */
    @Test
    fun `applyElements continues dispatch when one element throws RuntimeException`() {
        val first = createFakeAccentElement(AccentElementId.CARET_ROW, "Caret Row")
        val second = createFakeAccentElement(AccentElementId.SCROLLBAR, "Scrollbar")
        every { second.apply(any()) } throws IllegalStateException("simulated")
        val third = createFakeAccentElement(AccentElementId.LINKS, "Links")
        mockEpExtensionList(listOf(first, second, third))

        val accent = Color.decode("#FFCC66")
        // Must not propagate the simulated exception
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)

        // Loop must have continued past the failing middle element
        verify { first.apply(accent) }
        verify { second.apply(accent) }
        verify { third.apply(accent) }
    }

    /**
     * Regression guard for the per-element try-catch at line 157-164 of
     * [AccentApplicator.revertAll]. Revert failures on one element must
     * not block revert for the rest.
     */
    @Test
    fun `revertAll continues loop when one element throws`() {
        val first = createFakeAccentElement(AccentElementId.CARET_ROW, "Caret Row")
        val second = createFakeAccentElement(AccentElementId.SCROLLBAR, "Scrollbar")
        every { second.revert() } throws IllegalStateException("revert failed")
        val third = createFakeAccentElement(AccentElementId.LINKS, "Links")
        mockEpExtensionList(listOf(first, second, third))

        AccentApplicator.revertAll()

        verify { first.revert() }
        verify { second.revert() }
        verify { third.revert() }
    }

    /**
     * Regression guard for the try-catch at line 187-195 of
     * [AccentApplicator.neutralizeOrRevert]. When an element is disabled,
     * the neutral path runs — and its failure must not propagate out of
     * [AccentApplicator.applyElements].
     */
    @Test
    fun `neutralizeOrRevert catches exceptions from disabled elements`() {
        val failing = createFakeAccentElement(AccentElementId.CARET_ROW, "Caret Row")
        every { failing.applyNeutral(any()) } throws IllegalStateException("neutralize failed")
        val trailing = createFakeAccentElement(AccentElementId.SCROLLBAR, "Scrollbar")
        state.caretRow = false
        mockEpExtensionList(listOf(failing, trailing))

        val accent = Color.decode("#FFCC66")
        // Must not throw despite failing.applyNeutral exploding
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)

        // Loop continued and the trailing element was processed normally
        verify { failing.applyNeutral(AyuVariant.MIRAGE) }
        verify { trailing.apply(accent) }
    }

    /**
     * Behaviour check for the disabled branch of
     * [AccentApplicator.applyElements]: `apply` must not be called and
     * the neutral path (applyNeutral on non-null variant) must run.
     */
    @Test
    fun `applyElements skips disabled element without calling apply`() {
        val element = createFakeAccentElement(AccentElementId.CARET_ROW, "Caret Row")
        state.setToggle(AccentElementId.CARET_ROW, false)
        mockEpExtensionList(listOf(element))

        val accent = Color.decode("#FFCC66")
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)

        verify(exactly = 0) { element.apply(any()) }
        verify { element.applyNeutral(AyuVariant.MIRAGE) }
    }

    /**
     * Behaviour check for the conflict branch of
     * [AccentApplicator.applyElements]: when [ConflictRegistry] reports a
     * conflict and the user has NOT opted into force-override, the element
     * must be neutralized instead of applied.
     */
    @Test
    fun `applyElements skips element when ConflictRegistry reports a conflict`() {
        val element = createFakeAccentElement(AccentElementId.MATCHING_TAG, "Matching Tag")
        val conflict =
            ConflictEntry(
                pluginDisplayName = "Atom Material Icons",
                pluginId = "com.mallowigi",
                affectedElements = setOf(AccentElementId.MATCHING_TAG),
                type = ConflictType.BLOCK,
            )
        every { ConflictRegistry.getConflictFor(AccentElementId.MATCHING_TAG) } returns conflict
        // forceOverrides does NOT contain the element id name
        state.forceOverrides = mutableSetOf()
        mockEpExtensionList(listOf(element))

        val accent = Color.decode("#FFCC66")
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)

        verify(exactly = 0) { element.apply(any()) }
        verify { element.applyNeutral(AyuVariant.MIRAGE) }
    }

    /**
     * Behaviour check for the force-override escape hatch in
     * [AccentApplicator.applyElements]: if the user opts in via
     * `forceOverrides`, the conflict is bypassed and the element applies
     * normally.
     */
    @Test
    fun `applyElements force-overrides conflict when user opted in`() {
        val element = createFakeAccentElement(AccentElementId.MATCHING_TAG, "Matching Tag")
        val conflict =
            ConflictEntry(
                pluginDisplayName = "Atom Material Icons",
                pluginId = "com.mallowigi",
                affectedElements = setOf(AccentElementId.MATCHING_TAG),
                type = ConflictType.BLOCK,
            )
        every { ConflictRegistry.getConflictFor(AccentElementId.MATCHING_TAG) } returns conflict
        state.forceOverrides = mutableSetOf(AccentElementId.MATCHING_TAG.name)
        mockEpExtensionList(listOf(element))

        val accent = Color.decode("#FFCC66")
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)

        // Force override wins — apply called, neutralize path not taken
        verify { element.apply(accent) }
        verify(exactly = 0) { element.applyNeutral(any()) }
    }

    // --- Round 3 hotfix regression tests (C7, C8) ---
    //
    // Locks the revert-on-apply-fail block introduced in Phase 40 Round 3 M-7:
    // when an element's `apply` throws, `applyElements` must roll that element
    // back with `revert()` so a partial mutation doesn't leave UIManager +
    // live peers in a mixed tinted+stock state (which would then poison
    // `ChromeBaseColors` on the next capture). The PRE-EXISTING
    // "catches RuntimeException from element apply" test passes even if the
    // revert block is deleted — these tests fail if the block is removed.

    /**
     * C7 — when an element's `apply` throws, `applyElements` must call
     * `revert()` on the same element AND continue to the next element in
     * the extension list. Without the revert block, the partial mutation
     * would stay visible until the next full apply/revert cycle.
     */
    @Test
    fun `applyElements calls revert on an element whose apply throws`() {
        val throwingElement = createFakeAccentElement(AccentElementId.CARET_ROW, "Caret Row")
        every { throwingElement.apply(any()) } throws RuntimeException("apply broke")
        val normalElement = createFakeAccentElement(AccentElementId.SCROLLBAR, "Scrollbar")
        mockEpExtensionList(listOf(throwingElement, normalElement))

        val accent = Color.decode("#FFCC66")
        // Must not propagate the simulated apply failure.
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)

        // Revert was invoked exactly once on the throwing element — this is
        // the Round 3 M-7 lock; if the new try/revert block is deleted,
        // this verification fails.
        verify(exactly = 1) { throwingElement.revert() }
        // Dispatch proceeded to the next element despite the failure above.
        verify(exactly = 1) { normalElement.apply(accent) }
    }

    /**
     * C8 — when `apply` throws AND the fallback `revert()` also throws,
     * `applyElements` must still move on to the next element. Locks the
     * nested try/catch around the cleanup path so a doubly-broken element
     * does not take the whole dispatch loop down with it.
     */
    @Test
    fun `applyElements continues to next element when revert-after-apply-fail also throws`() {
        val doubleThrower = createFakeAccentElement(AccentElementId.CARET_ROW, "Caret Row")
        every { doubleThrower.apply(any()) } throws RuntimeException("apply broke")
        every { doubleThrower.revert() } throws RuntimeException("revert broke too")
        val survivor = createFakeAccentElement(AccentElementId.SCROLLBAR, "Scrollbar")
        mockEpExtensionList(listOf(doubleThrower, survivor))

        val accent = Color.decode("#FFCC66")
        // Must not propagate either the apply or the revert exception.
        invokeApplyElements(state, accent, AyuVariant.MIRAGE)

        // Revert WAS attempted on the failing element, even though it
        // threw — locks that the cleanup path runs unconditionally after
        // an apply failure.
        verify(exactly = 1) { doubleThrower.revert() }
        // Dispatch continued to the next element after both failures.
        verify(exactly = 1) { survivor.apply(accent) }
    }

    /**
     * Builds a relaxed [AccentElement] mock with the given id and display
     * name. Used by the defensive dispatch regression tests to assemble
     * multi-element scenarios quickly and consistently.
     */
    private fun createFakeAccentElement(
        id: AccentElementId,
        displayName: String,
    ): AccentElement {
        val element = mockk<AccentElement>(relaxed = true)
        every { element.id } returns id
        every { element.displayName } returns displayName
        return element
    }

    // Tests for syncCodeGlanceProViewport full flow

    @Test
    fun `syncCodeGlanceProViewport full flow with all methods resolved`() {
        state.cgpIntegrationEnabled = true

        // Set up mock CGP service and methods
        val mockConfig = Any()
        val mockService = Any()
        val mockGetState = mockk<Method>(relaxed = true)
        val mockSetColor = mockk<Method>(relaxed = true)
        val mockSetBorderColor = mockk<Method>(relaxed = true)
        val mockSetBorderThickness = mockk<Method>(relaxed = true)

        every { mockGetState.invoke(any()) } returns mockConfig
        every { mockSetColor.invoke(any(), any()) } returns null
        every { mockSetBorderColor.invoke(any(), any()) } returns null
        every { mockSetBorderThickness.invoke(any(), any()) } returns null

        setPrivateField("cgpMethodsResolved", true)
        setPrivateField("cgpService", mockService)
        setPrivateField("cgpGetState", mockGetState)
        setPrivateField("cgpSetViewportColor", mockSetColor)
        setPrivateField("cgpSetViewportBorderColor", mockSetBorderColor)
        setPrivateField("cgpSetViewportBorderThickness", mockSetBorderThickness)

        invokePrivate("syncCodeGlanceProViewport", "#FFCC66")

        verify { mockGetState.invoke(mockService) }
        verify { mockSetColor.invoke(mockConfig, ACCENT_HEX_STRIPPED) }
        verify { mockSetBorderColor.invoke(mockConfig, ACCENT_HEX_STRIPPED) }
        verify { mockSetBorderThickness.invoke(mockConfig, 1) }
    }

    @Test
    fun `syncCodeGlanceProViewport strips hash prefix from accent hex`() {
        state.cgpIntegrationEnabled = true

        val mockConfig = Any()
        val mockService = Any()
        val mockGetState = mockk<Method>(relaxed = true)
        val mockSetColor = mockk<Method>(relaxed = true)
        val mockSetBorderColor = mockk<Method>(relaxed = true)
        val mockSetBorderThickness = mockk<Method>(relaxed = true)

        every { mockGetState.invoke(any()) } returns mockConfig
        every { mockSetColor.invoke(any(), any()) } returns null
        every { mockSetBorderColor.invoke(any(), any()) } returns null
        every { mockSetBorderThickness.invoke(any(), any()) } returns null

        setPrivateField("cgpMethodsResolved", true)
        setPrivateField("cgpService", mockService)
        setPrivateField("cgpGetState", mockGetState)
        setPrivateField("cgpSetViewportColor", mockSetColor)
        setPrivateField("cgpSetViewportBorderColor", mockSetBorderColor)
        setPrivateField("cgpSetViewportBorderThickness", mockSetBorderThickness)

        invokePrivate("syncCodeGlanceProViewport", "#E6B450")

        // Verify the hash was stripped
        verify { mockSetColor.invoke(mockConfig, "E6B450") }
    }

    @Test
    fun `syncCodeGlanceProViewport returns early when getState returns null config`() {
        state.cgpIntegrationEnabled = true

        val mockService = Any()
        val mockGetState = mockk<Method>(relaxed = true)
        val mockSetColor = mockk<Method>(relaxed = true)
        val mockSetBorderColor = mockk<Method>(relaxed = true)
        val mockSetBorderThickness = mockk<Method>(relaxed = true)

        every { mockGetState.invoke(any()) } returns null

        setPrivateField("cgpMethodsResolved", true)
        setPrivateField("cgpService", mockService)
        setPrivateField("cgpGetState", mockGetState)
        setPrivateField("cgpSetViewportColor", mockSetColor)
        setPrivateField("cgpSetViewportBorderColor", mockSetBorderColor)
        setPrivateField("cgpSetViewportBorderThickness", mockSetBorderThickness)

        invokePrivate("syncCodeGlanceProViewport", "#FFCC66")

        // setColor should not be called since config is null
        verify(exactly = 0) { mockSetColor.invoke(any(), any()) }
    }

    @Test
    fun `syncCodeGlanceProViewport catches InvocationTargetException`() {
        state.cgpIntegrationEnabled = true

        val mockConfig = Any()
        val mockService = Any()
        val mockGetState = mockk<Method>(relaxed = true)
        val mockSetColor = mockk<Method>(relaxed = true)
        val mockSetBorderColor = mockk<Method>(relaxed = true)
        val mockSetBorderThickness = mockk<Method>(relaxed = true)

        every { mockGetState.invoke(any()) } returns mockConfig
        every { mockSetColor.invoke(any(), any()) } throws
            java.lang.reflect.InvocationTargetException(RuntimeException("inner"))

        setPrivateField("cgpMethodsResolved", true)
        setPrivateField("cgpService", mockService)
        setPrivateField("cgpGetState", mockGetState)
        setPrivateField("cgpSetViewportColor", mockSetColor)
        setPrivateField("cgpSetViewportBorderColor", mockSetBorderColor)
        setPrivateField("cgpSetViewportBorderThickness", mockSetBorderThickness)

        // Should not throw
        invokePrivate("syncCodeGlanceProViewport", "#FFCC66")
    }

    @Test
    fun `syncCodeGlanceProViewport catches RuntimeException`() {
        state.cgpIntegrationEnabled = true

        val mockConfig = Any()
        val mockService = Any()
        val mockGetState = mockk<Method>(relaxed = true)
        val mockSetColor = mockk<Method>(relaxed = true)
        val mockSetBorderColor = mockk<Method>(relaxed = true)
        val mockSetBorderThickness = mockk<Method>(relaxed = true)

        every { mockGetState.invoke(any()) } returns mockConfig
        every { mockSetColor.invoke(any(), any()) } throws RuntimeException("CGP exploded")

        setPrivateField("cgpMethodsResolved", true)
        setPrivateField("cgpService", mockService)
        setPrivateField("cgpGetState", mockGetState)
        setPrivateField("cgpSetViewportColor", mockSetColor)
        setPrivateField("cgpSetViewportBorderColor", mockSetBorderColor)
        setPrivateField("cgpSetViewportBorderThickness", mockSetBorderThickness)

        // Should not throw
        invokePrivate("syncCodeGlanceProViewport", "#FFCC66")
    }

    // Helpers

    /**
     * Mocks `EP_NAME.extensionList` by swapping the static final field with a mock.
     * Since the field cannot be replaced via standard reflection on Java 21+,
     * we use `sun.misc.Unsafe` to write to it directly.
     */
    @Suppress("UNCHECKED_CAST")
    private fun mockEpExtensionList(elements: List<AccentElement>) {
        val epField = AccentApplicator::class.java.getDeclaredField("EP_NAME")
        epField.isAccessible = true
        val mockEp = mockk<ExtensionPointName<AccentElement>>(relaxed = true)
        every { mockEp.extensionList } returns elements
        unsafeWriteStaticField(epField, mockEp)
    }

    /**
     * Restores the original EP_NAME field value after mocking.
     * Called in the resetCgpState or can be called in tearDown.
     */
    private var originalEpName: ExtensionPointName<AccentElement>? = null

    private fun saveOriginalEpName() {
        if (originalEpName != null) return
        val epField = AccentApplicator::class.java.getDeclaredField("EP_NAME")
        epField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        originalEpName = epField.get(null) as ExtensionPointName<AccentElement>
    }

    private fun restoreOriginalEpName() {
        val original = originalEpName ?: return
        val epField = AccentApplicator::class.java.getDeclaredField("EP_NAME")
        epField.isAccessible = true
        unsafeWriteStaticField(epField, original)
        originalEpName = null
    }

    @Suppress("DEPRECATION")
    private fun unsafeWriteStaticField(
        field: java.lang.reflect.Field,
        value: Any?,
    ) {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val offset = unsafe.staticFieldOffset(field)
        unsafe.putObject(field.declaringClass, offset, value)
    }

    /**
     * Invokes the private `applyElements(AyuIslandsState, Color, AyuVariant?)` method
     * via reflection.
     */
    private fun invokeApplyElements(
        targetState: AyuIslandsState,
        accent: Color,
        variant: AyuVariant?,
    ) {
        val method =
            AccentApplicator::class.java.declaredMethods
                .first { it.name == "applyElements" }
        method.isAccessible = true
        method.invoke(AccentApplicator, targetState, accent, variant)
    }

    // Helper for invoking private methods that accept nullable parameters

    private fun invokeNeutralizeOrRevert(vararg args: Any?) {
        val method =
            AccentApplicator::class.java.declaredMethods
                .first { it.name == "neutralizeOrRevert" }
        method.isAccessible = true
        method.invoke(AccentApplicator, *args)
    }

    // resolveUnderlineHeight tests

    @Test
    fun `resolveUnderlineHeight returns tabUnderlineHeight when tab mode is OFF`() {
        state.glowTabMode = "OFF"
        state.tabUnderlineHeight = 6

        assertEquals(6, AccentApplicator.resolveUnderlineHeight(state))
    }

    @Test
    fun `resolveUnderlineHeight returns tabUnderlineHeight when glow sync disabled`() {
        state.glowTabMode = "MINIMAL"
        state.tabUnderlineGlowSync = false
        state.tabUnderlineHeight = 4

        assertEquals(4, AccentApplicator.resolveUnderlineHeight(state))
    }

    @Test
    fun `resolveUnderlineHeight returns glow width when sync enabled and glow active`() {
        state.glowTabMode = "MINIMAL"
        state.tabUnderlineGlowSync = true
        state.glowEnabled = true
        state.glowStyle = "SOFT"

        val expected = state.getWidthForStyle(dev.ayuislands.glow.GlowStyle.SOFT)
        assertEquals(expected, AccentApplicator.resolveUnderlineHeight(state))
    }

    @Test
    fun `resolveUnderlineHeight returns tabUnderlineHeight when sync enabled but glow disabled`() {
        state.glowTabMode = "MINIMAL"
        state.tabUnderlineGlowSync = true
        state.glowEnabled = false
        state.tabUnderlineHeight = 8

        assertEquals(8, AccentApplicator.resolveUnderlineHeight(state))
    }

    // applyTabUnderlineStyle tests

    @Test
    fun `applyTabUnderlineStyle sets underline height and arc via UIManager`() {
        state.glowTabMode = "MINIMAL"
        state.tabUnderlineHeight = 4
        state.tabUnderlineGlowSync = false

        val method =
            AccentApplicator::class.java.getDeclaredMethod(
                "applyTabUnderlineStyle",
                AyuIslandsState::class.java,
            )
        method.isAccessible = true
        method.invoke(AccentApplicator, state)

        verify { UIManager.put("EditorTabs.underlineHeight", Integer.valueOf(4)) }
        verify { UIManager.put("EditorTabs.underlineArc", any<Int>()) }
    }

    // overrideTabUnderlineForOffMode tests

    @Test
    fun `overrideTabUnderlineForOffMode sets neutral gray when OFF and variant present`() {
        state.glowTabMode = "OFF"

        val method =
            AccentApplicator::class.java.declaredMethods
                .first { it.name == "overrideTabUnderlineForOffMode" }
        method.isAccessible = true
        method.invoke(AccentApplicator, state, AyuVariant.MIRAGE)

        verify { mockScheme.setColor(any(), Color.decode(AyuVariant.MIRAGE.neutralGray)) }
    }

    @Test
    fun `overrideTabUnderlineForOffMode does nothing when not OFF`() {
        state.glowTabMode = "MINIMAL"

        val method =
            AccentApplicator::class.java.declaredMethods
                .first { it.name == "overrideTabUnderlineForOffMode" }
        method.isAccessible = true
        method.invoke(AccentApplicator, state, AyuVariant.MIRAGE)

        verify(exactly = 0) { mockScheme.setColor(ColorKey.find("TAB_UNDERLINE"), any()) }
    }

    @Test
    fun `overrideTabUnderlineForOffMode does nothing when variant is null`() {
        state.glowTabMode = "OFF"

        val method =
            AccentApplicator::class.java.declaredMethods
                .first { it.name == "overrideTabUnderlineForOffMode" }
        method.isAccessible = true
        method.invoke(AccentApplicator, state, null)

        verify(exactly = 0) { mockScheme.setColor(ColorKey.find("TAB_UNDERLINE"), any()) }
    }

    private fun resetCgpState() {
        val fields =
            listOf(
                "cgpService",
                "cgpGetState",
                "cgpSetViewportColor",
                "cgpSetViewportBorderColor",
                "cgpSetViewportBorderThickness",
            )
        for (fieldName in fields) {
            val field = AccentApplicator::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(AccentApplicator, null)
        }
        val resolvedField = AccentApplicator::class.java.getDeclaredField("cgpMethodsResolved")
        resolvedField.isAccessible = true
        resolvedField.set(AccentApplicator, false)
    }

    companion object {
        private const val ACCENT_HEX_STRIPPED = "FFCC66"
    }
}
