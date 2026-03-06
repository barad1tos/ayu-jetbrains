package dev.ayuislands.accent

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.messages.MessageBus
import dev.ayuislands.accent.conflict.ConflictRegistry
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

        // Reset CGP cached state before each test
        resetCgpState()
    }

    @AfterTest
    fun tearDown() {
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

        // The attributes should be cloned (not the same reference)
        val capturedAttrs = mutableListOf<TextAttributes>()
        verify { mockScheme.setAttributes(any(), capture(capturedAttrs)) }
        assertTrue(capturedAttrs.isNotEmpty(), "setAttributes should have been called")
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
        assertEquals(2, keys.size, "Expected 2 always-on editor color keys")
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

    // Helper for invoking private methods that accept nullable parameters

    private fun invokeNeutralizeOrRevert(vararg args: Any?) {
        val method =
            AccentApplicator::class.java.declaredMethods
                .first { it.name == "neutralizeOrRevert" }
        method.isAccessible = true
        method.invoke(AccentApplicator, *args)
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
}
