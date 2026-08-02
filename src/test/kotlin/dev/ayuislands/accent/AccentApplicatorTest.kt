package dev.ayuislands.accent

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.messages.MessageBus
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.conflict.ConflictEntry
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.accent.conflict.ConflictType
import dev.ayuislands.accent.elements.AbstractChromeElement
import dev.ayuislands.accent.elements.CaretRowElement
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.indent.IndentRainbowSync
import dev.ayuislands.integration.IntegrationOutcome
import dev.ayuislands.integration.IntegrationOwnership
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.theme.AyuEditorSchemeScope
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        AyuEditorSchemeScope.resetClaims()
        saveOriginalEpName()

        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns true

        mockkStatic(UIManager::class)

        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockColorsManager
        every { mockColorsManager.globalScheme } returns mockScheme
        every { mockScheme.name } returns "Ayu Islands Mirage"
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns TextAttributes()

        // ApplicationManager must be mocked BEFORE AyuIslandsSettings.Companion,
        // because getInstance() calls ApplicationManager.getApplication().getService()
        // during MockK recording.
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.messageBus } returns mockMessageBus
        every { mockMessageBus.syncPublisher(EditorColorsManager.TOPIC) } returns mockk(relaxed = true)
        // AccentChangedTopic publish — Apply path casts the syncPublisher
        // return value to AccentChangeListener. Stub a relaxed mock so the
        // cast succeeds in this legacy headless test.
        every { mockMessageBus.syncPublisher(AccentChangedTopic.TOPIC) } returns mockk(relaxed = true)

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state
        state.isCgpOwnershipMigrated = true
        state.isIrOwnershipMigrated = true
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        mockkObject(ConflictRegistry)
        every { ConflictRegistry.getConflictFor(any()) } returns null

        mockkStatic(Window::class)
        every { Window.getWindows() } returns emptyArray()

        mockkObject(AyuPlugin)
        every { AyuPlugin.findLoadedPlugin(any()) } returns null

        // Per-project notify plumbing: revertAll iterates ProjectManager.openProjects
        // and calls ComponentTreeRefresher.notifyOnly per usable project. Unit tests
        // don't boot the platform, so both must be stubbed or the notifyOnly loop
        // blows up with "Can't get extension point" / a null ProjectManager.
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
        resetCodeGlanceProState()
    }

    @AfterTest
    fun tearDown() {
        AyuEditorSchemeScope.resetClaims()
        ExternalChromeOwnership.resetForTests()
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
        val state = AyuIslandsSettings.getInstance().state
        invokePrivate(
            "applyAlwaysOnUiKeys",
            state,
            accent,
            LicenseChecker.isLicensedOrGrace(),
        )
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

        AyuEditorSchemeScope.claimActiveScheme()
        invokePrivate("revertAlwaysOnEditorKeys")

        val windows = Window.getWindows()
        invokePrivate("repaintAllWindows", windows)
    }

    /** Routes the one private CodeGlance method to its owning object. */
    private fun ownerForName(name: String): Any =
        if (name == CODE_GLANCE_METHOD_RESOLUTION) {
            CodeGlanceProIntegration
        } else {
            AccentApplicator
        }

    private fun invokePrivate(
        methodName: String,
        vararg args: Any,
    ) {
        val owner = ownerForName(methodName)
        val method =
            owner.javaClass.declaredMethods
                .first { it.name == methodName && it.parameterCount == args.size }
        method.isAccessible = true
        method.invoke(owner, *args)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getPrivateField(fieldName: String): T {
        val owner = ownerForName(fieldName)
        val field = owner.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(owner) as T
    }

    @Test
    fun `apply sets always-on UI keys`() {
        applyWithoutExtensions("#FFCC66")

        verify(atLeast = 12) { UIManager.put(any<String>(), any<Color>()) }
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

        verify(atLeast = 12) { UIManager.put(any<String>(), null) }
    }

    @Test
    fun `accent editor writes preserve a foreign scheme`() {
        every { mockScheme.name } returns "Solarized Dark"

        applyWithoutExtensions("#FFCC66")
        revertWithoutExtensions()

        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), any<Color>()) }
        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), null) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any<TextAttributes>()) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), null) }
    }

    @Test
    fun `always-on editor values restore their first explicit baseline`() {
        val store = EditorSchemeStore()
        every { mockColorsManager.globalScheme } returns store.scheme

        invokePrivate("applyAlwaysOnEditorKeys", Color.RED)
        invokePrivate("applyAlwaysOnEditorKeys", Color.BLUE)
        invokePrivate("revertAlwaysOnEditorKeys")

        store.assertOriginalValues()
    }

    @Test
    fun `always-on editor values restore a null baseline`() {
        val store = EditorSchemeStore(null, null)
        every { mockColorsManager.globalScheme } returns store.scheme

        invokePrivate("applyAlwaysOnEditorKeys", Color.RED)
        invokePrivate("revertAlwaysOnEditorKeys")

        store.assertOriginalValues()
    }

    @Test
    fun `external changes permanently relinquish always-on editor keys`() {
        val store = EditorSchemeStore()
        every { mockColorsManager.globalScheme } returns store.scheme

        invokePrivate("applyAlwaysOnEditorKeys", Color.RED)
        store.replaceTouchedValues()
        invokePrivate("applyAlwaysOnEditorKeys", Color.BLUE)
        invokePrivate("revertAlwaysOnEditorKeys")

        store.assertExternalValues()
    }

    @Test
    fun `tab underline shares the always-on baseline`() {
        val store = EditorSchemeStore()
        every { mockColorsManager.globalScheme } returns store.scheme
        state.glowTabMode = "OFF"

        invokePrivate("applyAlwaysOnEditorKeys", Color.RED)
        invokeApplyTabUnderline(state, AccentContext.Ayu(AyuVariant.MIRAGE))
        invokePrivate("revertAlwaysOnEditorKeys")

        store.assertOriginalValue(ColorKey.find("TAB_UNDERLINE"))
    }

    @Test
    fun `element disable and re-enable explicitly re-arms editor ownership`() {
        val store = EditorSchemeStore()
        every { mockColorsManager.globalScheme } returns store.scheme
        val element = CaretRowElement()
        val accent = Color.RED

        state.setToggle(element.id, true)
        invokeApplyElement(state, element, accent)
        store.replaceTouchedValues()
        invokeApplyElement(state, element, Color.BLUE)

        state.setToggle(element.id, false)
        invokeApplyElement(state, element, accent)
        store.assertExternalValues()

        state.setToggle(element.id, true)
        invokeApplyElement(state, element, accent)
        store.assertWasChangedTo(accent)

        state.setToggle(element.id, false)
        invokeApplyElement(state, element, accent)
        store.assertExternalValues()
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
    fun `unlicensed apply renders minimal tab mode without erasing FULL preference`() {
        state.glowTabMode = "FULL"
        every { LicenseChecker.isLicensedOrGrace() } returns false

        applyWithoutExtensions("#FFCC66")

        verify {
            UIManager.put(
                "EditorTabs.underlinedTabBackground",
                match<Color> { color -> color.alpha == 0 },
            )
        }
        assertEquals("FULL", state.glowTabMode)
    }

    @Test
    fun `apply snapshots chrome entitlement once`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false

        AccentApplicator.applyFromHexString("#FFCC66")

        verify(exactly = 1) { LicenseChecker.isLicensedOrGrace() }
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
        every { mockWindow.isDisplayable } returns true
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
        // repaintAllWindows skips non-displayable windows.
        every { window1.isDisplayable } returns true
        every { window2.isDisplayable } returns true
        every { window3.isDisplayable } returns true

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

    // CodeGlance Pro viewport sync: early return when disabled.

    @Test
    fun `CodeGlance Pro viewport sync returns early when integration is disabled`() {
        state.cgpIntegrationEnabled = false

        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#FFCC66")

        assertEquals(IntegrationOutcome.Skipped, outcome)
        verify(exactly = 0) { AyuPlugin.findLoadedPlugin(any()) }
    }

    @Test
    fun `CodeGlance Pro viewport sync with enabled flag but no plugin does not throw`() {
        state.cgpIntegrationEnabled = true

        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#FFCC66")

        assertEquals(IntegrationOutcome.Skipped, outcome)
        verify(exactly = 1) { AyuPlugin.findLoadedPlugin(any()) }
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
        assertEquals(12, keys.size, "Expected 12 always-on UI keys")
    }

    @Test
    fun `ALWAYS_ON_UI_KEYS does not tint shared OnePixelDivider`() {
        val keys = getPrivateField<List<String>>("ALWAYS_ON_UI_KEYS")

        assertFalse(
            "OnePixelDivider.background" in keys,
            "OnePixelDivider is shared across editor splitters, tool windows, " +
                "Settings, and diff views; " +
                "always-on accent writes would over-tint IDE-wide dividers",
        )
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

    // CodeGlance Pro method resolution.

    @Test
    fun `CodeGlance Pro method resolution checks plugin when cache is empty`() {
        resetCodeGlanceProState()

        invokePrivate(CODE_GLANCE_METHOD_RESOLUTION)

        verify(exactly = 1) { AyuPlugin.findLoadedPlugin(any()) }
    }

    @Test
    fun `CodeGlance Pro method resolution is idempotent after first call`() {
        resetCodeGlanceProState()

        invokePrivate(CODE_GLANCE_METHOD_RESOLUTION)
        invokePrivate(CODE_GLANCE_METHOD_RESOLUTION)

        verify(exactly = 1) { AyuPlugin.findLoadedPlugin(any()) }
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

    // CodeGlance Pro method resolution: additional scenarios.

    @Test
    fun `CodeGlance Pro method resolution skips when already resolved`() {
        seedCodeGlanceMethods(Any(), mockk(relaxed = true), Any())

        invokePrivate(CODE_GLANCE_METHOD_RESOLUTION)

        verify(exactly = 0) { AyuPlugin.findLoadedPlugin(any()) }
    }

    @Test
    fun `CodeGlance Pro method resolution returns when plugin classloader is null`() {
        val mockPlugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { AyuPlugin.findLoadedPlugin(any()) } returns mockPlugin
        every { mockPlugin.pluginClassLoader } returns null

        invokePrivate(CODE_GLANCE_METHOD_RESOLUTION)

        verify(exactly = 1) { AyuPlugin.findLoadedPlugin(any()) }
        verify(exactly = 1) { mockPlugin.pluginClassLoader }
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

    // Tests for public apply() method

    @Test
    fun `applyAlwaysOnUiKeys accepts state as an explicit parameter (state-snapshot lock)`() {
        // Regression lock: the outer apply() already captures
        // AyuIslandsSettings.state at entry, so applyAlwaysOnUiKeys must thread
        // that same state through rather than re-fetching via
        // AyuIslandsSettings.getInstance(). A future refactor that drops the
        // parameter would re-open the split-brain window where the EP chain and
        // tab-mode resolution could observe different state snapshots if settings
        // mutated mid-apply.
        val method =
            AccentApplicator::class.java.declaredMethods
                .first { it.name == "applyAlwaysOnUiKeys" }
        assertEquals(
            3,
            method.parameterCount,
            "applyAlwaysOnUiKeys must accept (state, accent, entitlement) so tab-mode resolution shares the " +
                "outer apply() snapshots and cannot drift mid-apply",
        )
        assertEquals(
            AyuIslandsState::class.java,
            method.parameterTypes[0],
            "First parameter must be AyuIslandsState — threaded from apply()'s captured snapshot",
        )
        assertEquals(
            Color::class.java,
            method.parameterTypes[1],
            "Second parameter must be the resolved accent Color",
        )
        assertEquals(
            Boolean::class.javaPrimitiveType,
            method.parameterTypes[2],
            "Third parameter must be the chrome entitlement captured once by apply()",
        )
    }

    @Test
    fun `apply calls applyAlwaysOnUiKeys and applyAlwaysOnEditorKeys on EDT`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AyuVariant>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false

        AccentApplicator.applyFromHexString("#FFCC66")

        // Verify always-on UI keys were set (proves applyAlwaysOnUiKeys ran)
        verify(atLeast = 12) { UIManager.put(any<String>(), any<Color>()) }
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
        every { IndentRainbowSync.apply(any<AyuVariant>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        AccentApplicator.applyFromHexString("#FFCC66")

        verify { IndentRainbowSync.apply(AyuVariant.MIRAGE, "#FFCC66") }
    }

    @Test
    fun `external chrome default off leaves every element and underline untouched`() {
        val visualElement = createFakeAccentElement(AccentElementId.CARET_ROW, "Caret Row")
        val chromeElement = createExternalChromeElement()
        stubExternalChromeBase(Color(0x24, 0x29, 0x36))
        mockEpExtensionList(listOf(visualElement, chromeElement))
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        state.externalThemeEnhancementsEnabled = true
        state.chromePanelBorder = true
        every { AyuVariant.detect() } returns null

        AccentApplicator.applyFromHexString("#AABBCC")

        verify(exactly = 1) { IndentRainbowSync.apply(AccentContext.External, "#AABBCC") }
        verify(exactly = 0) { visualElement.apply(any()) }
        verify(exactly = 0) { visualElement.revert() }
        verify(exactly = 0) { visualElement.applyNeutral(any()) }
        verify(exactly = 0) { UIManager.put(EXTERNAL_CHROME_TEST_KEY, any()) }
        verify(exactly = 0) { UIManager.put("EditorTabs.underlineHeight", any()) }
        verify(exactly = 0) { UIManager.put("EditorTabs.underlineArc", any()) }
    }

    @Test
    fun `external chrome applies only chrome group surfaces and underline`() {
        val visualElement = createFakeAccentElement(AccentElementId.CARET_ROW, "Caret Row")
        val chromeElement = createExternalChromeElement()
        stubExternalChromeBase(Color(0x24, 0x29, 0x36))
        mockEpExtensionList(listOf(visualElement, chromeElement))
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true
        state.chromePanelBorder = true
        every { AyuVariant.detect() } returns null

        AccentApplicator.applyFromHexString("#AABBCC")

        verify(exactly = 0) { visualElement.apply(any()) }
        verify(exactly = 0) { visualElement.revert() }
        verify(exactly = 0) { visualElement.applyNeutral(any()) }
        verify(exactly = 1) { UIManager.put(EXTERNAL_CHROME_TEST_KEY, any<Color>()) }
        verify(exactly = 1) { UIManager.put("EditorTabs.underlineHeight", any()) }
        verify(exactly = 1) { UIManager.put("EditorTabs.underlineArc", any()) }
        verify(exactly = 0) { UIManager.put("EditorTabs.underlineHeight", null) }
        verify(exactly = 0) { UIManager.put("EditorTabs.underlineArc", null) }
    }

    @Test
    fun `disabling external chrome reverts only surfaces this process tinted`() {
        val chromeElement = createExternalChromeElement()
        stubExternalChromeBase(Color(0x24, 0x29, 0x36))
        mockEpExtensionList(listOf(chromeElement))
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true
        state.chromePanelBorder = true
        every { AyuVariant.detect() } returns null

        AccentApplicator.applyFromHexString("#AABBCC")
        state.externalThemeChromeTintEnabled = false
        AccentApplicator.applyFromHexString("#AABBCC")

        verify(exactly = 2) { UIManager.put(EXTERNAL_CHROME_TEST_KEY, any()) }
        verify(exactly = 1) { UIManager.put(EXTERNAL_CHROME_TEST_KEY, null) }
        verify(exactly = 2) { UIManager.put("EditorTabs.underlineHeight", any()) }
        verify(exactly = 1) { UIManager.put("EditorTabs.underlineHeight", null) }
    }

    @Test
    fun `failed global revert keeps external surface owned for retry`() {
        val chromeElement = createExternalChromeElement()
        stubExternalChromeBase(Color(0x24, 0x29, 0x36))
        mockEpExtensionList(listOf(chromeElement))
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true
        state.chromePanelBorder = true
        every { AyuVariant.detect() } returns null

        AccentApplicator.applyFromHexString("#AABBCC")
        every { UIManager.put(EXTERNAL_CHROME_TEST_KEY, null) } throws RuntimeException("revert failed")
        invokePrivate("clearReverseUiAndExtensions")

        every { UIManager.put(EXTERNAL_CHROME_TEST_KEY, null) } returns Unit
        state.externalThemeChromeTintEnabled = false
        AccentApplicator.applyFromHexString("#AABBCC")

        verify(exactly = 2) { UIManager.put(EXTERNAL_CHROME_TEST_KEY, null) }
    }

    @Test
    fun `external write exception rolls back the preclaimed surface`() {
        val chromeElement = createExternalChromeElement()
        stubExternalChromeBase(Color(0x24, 0x29, 0x36))
        mockEpExtensionList(listOf(chromeElement))
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true
        state.chromePanelBorder = true
        every { AyuVariant.detect() } returns null
        every {
            UIManager.put(EXTERNAL_CHROME_TEST_KEY, any<Color>())
        } throws RuntimeException("listener failed after write")
        every { UIManager.put(EXTERNAL_CHROME_TEST_KEY, null) } returns Unit

        AccentApplicator.applyFromHexString("#AABBCC")

        verify(exactly = 2) { UIManager.put(EXTERNAL_CHROME_TEST_KEY, any()) }
        verify(exactly = 1) { UIManager.put(EXTERNAL_CHROME_TEST_KEY, null) }
    }

    @Test
    fun `partial external underline write remains owned for retry`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true
        every { AyuVariant.detect() } returns null
        every { UIManager.put("EditorTabs.underlineArc", any<Int>()) } throws RuntimeException("arc failed")

        AccentApplicator.applyFromHexString("#AABBCC")
        every { UIManager.put("EditorTabs.underlineArc", any()) } returns Unit
        state.externalThemeChromeTintEnabled = false
        AccentApplicator.applyFromHexString("#AABBCC")

        verify(exactly = 1) { UIManager.put("EditorTabs.underlineHeight", null) }
        verify(exactly = 1) { UIManager.put("EditorTabs.underlineArc", null) }
    }

    @Test
    fun `failed external underline cleanup remains owned for retry`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true
        every { AyuVariant.detect() } returns null

        AccentApplicator.applyFromHexString("#AABBCC")
        state.externalThemeChromeTintEnabled = false
        every { UIManager.put("EditorTabs.underlineArc", null) } throws RuntimeException("cleanup failed")
        AccentApplicator.applyFromHexString("#AABBCC")

        every { UIManager.put("EditorTabs.underlineArc", null) } returns Unit
        AccentApplicator.applyFromHexString("#AABBCC")

        verify(exactly = 2) { UIManager.put("EditorTabs.underlineHeight", null) }
        verify(exactly = 2) { UIManager.put("EditorTabs.underlineArc", null) }
    }

    @Test
    fun `external chrome with a missing base never claims or reverts the surface`() {
        val chromeElement = createExternalChromeElement()
        stubExternalChromeBase(null)
        mockEpExtensionList(listOf(chromeElement))
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true
        state.chromePanelBorder = true
        every { AyuVariant.detect() } returns null

        AccentApplicator.applyFromHexString("#AABBCC")
        state.externalThemeChromeTintEnabled = false
        AccentApplicator.applyFromHexString("#AABBCC")

        verify(exactly = 0) { UIManager.put(EXTERNAL_CHROME_TEST_KEY, any()) }
    }

    @Test
    fun `external chrome disabled surface never claims or reverts ownership`() {
        val chromeElement = createExternalChromeElement(isEnabled = false)
        stubExternalChromeBase(Color(0x24, 0x29, 0x36))
        mockEpExtensionList(listOf(chromeElement))
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true
        state.chromePanelBorder = true
        every { AyuVariant.detect() } returns null

        AccentApplicator.applyFromHexString("#AABBCC")
        state.externalThemeChromeTintEnabled = false
        AccentApplicator.applyFromHexString("#AABBCC")

        verify(exactly = 0) { UIManager.put(EXTERNAL_CHROME_TEST_KEY, any()) }
    }

    @Test
    fun `unlicensed external startup leaves chrome and underline untouched`() {
        val chromeElement = createExternalChromeElement()
        stubExternalChromeBase(Color(0x24, 0x29, 0x36))
        mockEpExtensionList(listOf(chromeElement))
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AccentContext>(), any()) } returns IntegrationOutcome.Skipped
        every { LicenseChecker.isLicensedOrGrace() } returns false
        state.cgpIntegrationEnabled = false
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true
        state.chromePanelBorder = true
        every { AyuVariant.detect() } returns null

        AccentApplicator.applyFromHexString("#AABBCC")

        verify(exactly = 0) { UIManager.put(EXTERNAL_CHROME_TEST_KEY, any()) }
        verify(exactly = 0) { UIManager.put("EditorTabs.underlineHeight", any()) }
        verify(exactly = 0) { UIManager.put("EditorTabs.underlineArc", any()) }
    }

    @Test
    fun `apply skips IndentRainbowSync when variant is null`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        state.cgpIntegrationEnabled = false
        every { AyuVariant.detect() } returns null

        AccentApplicator.applyFromHexString("#FFCC66")

        verify(exactly = 0) { IndentRainbowSync.apply(any<AyuVariant>(), any()) }
    }

    @Test
    fun `apply calls repaintAllWindows`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AyuVariant>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        val mockWindow = mockk<Window>(relaxed = true)
        every { mockWindow.isDisplayable } returns true
        every { Window.getWindows() } returns arrayOf(mockWindow)

        AccentApplicator.applyFromHexString("#FFCC66")

        verify { mockWindow.repaint() }
    }

    @Test
    fun `apply runs work directly when on EDT`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AyuVariant>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        every { SwingUtilities.isEventDispatchThread() } returns true

        AccentApplicator.applyFromHexString("#FFCC66")

        // If on EDT, work runs synchronously, so UIManager.put should be called.
        verify(atLeast = 1) { UIManager.put(any<String>(), any()) }
    }

    @Test
    fun `apply persists accent hex to lastAppliedAccentHex for next-startup anti-flicker`() {
        // Regression guard for anti-flicker on next startup: AyuIslandsAppListener.appFrameCreated
        // reads state.lastAppliedAccentHex on the next IDE restart to paint the first frame
        // without a global-accent flash. If a refactor drops the state write inside apply(),
        // multi-window restores would flicker Gold before each StartupActivity ran.
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AyuVariant>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false

        AccentApplicator.applyFromHexString("#5CCFE6")

        assertEquals("#5CCFE6", state.lastAppliedAccentHex)
    }

    @Test
    fun `apply updates lastAppliedAccentHex with last-write-wins semantics`() {
        // A later apply() must overwrite the persisted hex so settings changes, rotation
        // ticks, and per-project swaps leave the right color for the next restart.
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AyuVariant>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false

        AccentApplicator.applyFromHexString("#5CCFE6")
        assertEquals("#5CCFE6", state.lastAppliedAccentHex)

        AccentApplicator.applyFromHexString("#FF3333")
        assertEquals("#FF3333", state.lastAppliedAccentHex)
    }

    // Hex validation

    @Test
    fun `apply rejects invalid hex strings without throwing or mutating UIManager`() {
        // Corrupted / hand-edited persisted hex must not abort the first
        // frame paint. AccentApplicator.apply rejects anything that doesn't
        // match HEX_COLOR_PATTERN before reaching Color.decode (which would
        // throw NumberFormatException). Covers "garbage", empty string, and
        // malformed shapes that used to crash the applier.
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AyuVariant>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false

        // None of these should throw; none should set the cached hex.
        AccentApplicator.applyFromHexString("garbage")
        AccentApplicator.applyFromHexString("")
        AccentApplicator.applyFromHexString("FFCC66") // missing leading #
        AccentApplicator.applyFromHexString("#12345") // 5 chars — too short
        AccentApplicator.applyFromHexString("#1234567") // 7 chars — too long
        AccentApplicator.applyFromHexString("#ZZZZZZ") // non-hex digits

        // UIManager.put must not have been called for any of these (apply short-circuits
        // before applyAlwaysOnUiKeys). The cached hex stays whatever it was (default null).
        verify(exactly = 0) { UIManager.put(any<String>(), any<Color>()) }
        assertEquals(null, state.lastAppliedAccentHex)
    }

    @Test
    fun `apply accepts well-formed 6-digit hex with hash prefix`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AyuVariant>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false

        // Boundary: exactly #RRGGBB with valid hex digits. Must go through the full
        // apply flow (UIManager writes, lastAppliedAccentHex persisted).
        AccentApplicator.applyFromHexString("#123456")

        verify(atLeast = 1) { UIManager.put(any<String>(), any<Color>()) }
        assertEquals("#123456", state.lastAppliedAccentHex)
    }

    @Test
    fun `apply accepts mixed-case hex digits`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AyuVariant>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false

        // Upper and lower case 0-9A-Fa-f are all valid per Color.decode.
        AccentApplicator.applyFromHexString("#AbCdEf")

        assertEquals("#AbCdEf", state.lastAppliedAccentHex)
    }

    @Test
    fun `AccentHex of matches expected shapes`() {
        // The shape check lives on AccentHex.of, which is the single boundary
        // that gates Color.decode. This test is retained under the applicator
        // suite so the same contract the applicator used to own is still
        // asserted in the applicator's coverage footprint.
        // Positive
        assertNotNull(AccentHex.of("#000000"))
        assertNotNull(AccentHex.of("#FFFFFF"))
        assertNotNull(AccentHex.of("#ffcc66"))
        assertNotNull(AccentHex.of("#5CCFE6"))

        // Negative
        assertNull(AccentHex.of(""))
        assertNull(AccentHex.of("garbage"))
        assertNull(AccentHex.of("FFCC66"))
        assertNull(AccentHex.of("#12345"))
        assertNull(AccentHex.of("#1234567"))
        assertNull(AccentHex.of("#ZZZZZZ"))
        assertNull(AccentHex.of("#12 34 56"))
    }

    @Test
    fun `apply posts to invokeLater when not on EDT`() {
        mockEpExtensionList(emptyList())
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.apply(any<AyuVariant>(), any()) } returns IntegrationOutcome.Skipped
        state.cgpIntegrationEnabled = false
        every { SwingUtilities.isEventDispatchThread() } returns false
        every { mockApplication.invokeLater(any(), any<ModalityState>()) } answers {
            firstArg<Runnable>().run()
        }

        AccentApplicator.applyFromHexString("#FFCC66")

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
        verify(atLeast = 12) { UIManager.put(any<String>(), null) }
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
    fun `revertAll retains editor claims when an element cleanup fails`() {
        val failingElement = mockk<AccentElement>(relaxed = true)
        every { failingElement.id } returns AccentElementId.CARET_ROW
        every { failingElement.displayName } returns "FailingElement"
        every { failingElement.revert() } throws RuntimeException("revert failed")
        mockEpExtensionList(listOf(failingElement))
        AyuEditorSchemeScope.claimActiveScheme()

        AccentApplicator.revertAll()

        assertEquals(1, AyuEditorSchemeScope.claimedAccentSchemes().size)
    }

    @Test
    fun `revertAll releases editor claims after a chrome-only cleanup failure`() {
        val failingElement = mockk<AccentElement>(relaxed = true)
        every { failingElement.id } returns AccentElementId.STATUS_BAR
        every { failingElement.displayName } returns "FailingChrome"
        every { failingElement.revert() } throws RuntimeException("revert failed")
        mockEpExtensionList(listOf(failingElement))
        AyuEditorSchemeScope.claimActiveScheme()

        AccentApplicator.revertAll()

        assertTrue(AyuEditorSchemeScope.claimedAccentSchemes().isEmpty())
    }

    @Test
    fun `revertAll releases cleaned scheme and retains only failed scheme identity`() {
        val failedStore = EditorSchemeStore()
        val cleanedStore = EditorSchemeStore()
        every { mockColorsManager.globalScheme } returns failedStore.scheme
        invokePrivate("applyAlwaysOnEditorKeys", Color.RED)
        every { mockColorsManager.globalScheme } returns cleanedStore.scheme
        invokePrivate("applyAlwaysOnEditorKeys", Color.RED)
        failedStore.failRestores()
        mockEpExtensionList(emptyList())

        AccentApplicator.revertAll()

        assertEquals(1, AyuEditorSchemeScope.claimedAccentSchemes().size)
        assertTrue(AyuEditorSchemeScope.claimedAccentSchemes().single() === failedStore.scheme)
        cleanedStore.assertOriginalValues()
    }

    @Test
    fun `later cancellation does not retain successfully cleaned editor claims`() {
        mockEpExtensionList(emptyList())
        AyuEditorSchemeScope.claimActiveScheme()
        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.revert() } throws ProcessCanceledException()

        assertFailsWith<ProcessCanceledException> {
            AccentApplicator.revertAll()
        }

        assertTrue(AyuEditorSchemeScope.claimedAccentSchemes().isEmpty())
    }

    @Test
    fun `revertAll releases editor claims only after queued cleanup completes`() {
        mockEpExtensionList(emptyList())
        AyuEditorSchemeScope.claimActiveScheme()
        every { SwingUtilities.isEventDispatchThread() } returns false
        val callback = io.mockk.slot<Runnable>()
        every { mockApplication.invokeLater(capture(callback), any<ModalityState>()) } returns Unit

        AccentApplicator.revertAll()

        assertEquals(1, AyuEditorSchemeScope.claimedAccentSchemes().size)
        callback.captured.run()
        assertTrue(AyuEditorSchemeScope.claimedAccentSchemes().isEmpty())
    }

    @Test
    fun `revertAll restores editor keys via revertAlwaysOnEditorKeys`() {
        val store = EditorSchemeStore()
        every { mockColorsManager.globalScheme } returns store.scheme
        mockEpExtensionList(emptyList())
        invokePrivate("applyAlwaysOnEditorKeys", Color.RED)

        AccentApplicator.revertAll()

        store.assertOriginalValues()
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
        verify(atLeast = 12) { UIManager.put(any<String>(), null) }
    }

    // Tests for applyElements via reflection

    @Test
    fun `applyElements with enabled element calls element apply`() {
        val mockElement = mockk<AccentElement>(relaxed = true)
        every { mockElement.id } returns AccentElementId.CARET_ROW
        every { mockElement.displayName } returns "Caret Row"
        mockEpExtensionList(listOf(mockElement))

        val accent = Color.decode("#FFCC66")
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))

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
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))

        verify { mockElement.applyNeutral(AyuVariant.MIRAGE) }
        verify(exactly = 0) { mockElement.apply(any()) }
    }

    @Test
    fun `applyElements under external context leaves non chrome element untouched`() {
        val mockElement = mockk<AccentElement>(relaxed = true)
        every { mockElement.id } returns AccentElementId.CARET_ROW
        every { mockElement.displayName } returns "Caret Row"
        state.caretRow = false
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true
        mockEpExtensionList(listOf(mockElement))

        val accent = Color.decode("#FFCC66")
        invokeApplyElements(state, accent, AccentContext.External)

        verify(exactly = 0) { mockElement.revert() }
        verify(exactly = 0) { mockElement.apply(any()) }
    }

    @Test
    fun `applyElements under Ayu context gates chrome without erasing its preference`() {
        val chromeElement = mockk<AccentElement>(relaxed = true)
        every { chromeElement.id } returns AccentElementId.PANEL_BORDER
        every { chromeElement.displayName } returns "Panel border"
        every { LicenseChecker.isLicensedOrGrace() } returns false
        state.chromePanelBorder = true
        mockEpExtensionList(listOf(chromeElement))

        invokeApplyElements(state, Color.decode("#FFCC66"), AccentContext.Ayu(AyuVariant.MIRAGE))

        verify(exactly = 1) { chromeElement.applyNeutral(AyuVariant.MIRAGE) }
        verify(exactly = 0) { chromeElement.apply(any()) }
        assertTrue(state.chromePanelBorder)
    }

    @Test
    fun `applyElements unlicensed uses the default enabled state without erasing a visual preference`() {
        val visualElement = mockk<AccentElement>(relaxed = true)
        every { visualElement.id } returns AccentElementId.INLAY_HINTS
        every { visualElement.displayName } returns "Inlay hints"
        every { LicenseChecker.isLicensedOrGrace() } returns false
        state.setToggle(AccentElementId.INLAY_HINTS, false)
        mockEpExtensionList(listOf(visualElement))

        invokeApplyElements(state, Color.decode("#FFCC66"), AccentContext.Ayu(AyuVariant.MIRAGE))

        verify(exactly = 1) { visualElement.apply(any()) }
        verify(exactly = 0) { visualElement.applyNeutral(any()) }
        assertFalse(state.isToggleEnabled(AccentElementId.INLAY_HINTS))
    }

    @Test
    fun `applyElements unlicensed ignores a persisted force override`() {
        val visualElement = mockk<AccentElement>(relaxed = true)
        every { visualElement.id } returns AccentElementId.INLAY_HINTS
        every { visualElement.displayName } returns "Inlay hints"
        every { LicenseChecker.isLicensedOrGrace() } returns false
        every { ConflictRegistry.getConflictFor(AccentElementId.INLAY_HINTS) } returns
            ConflictEntry(
                pluginDisplayName = "Conflicting plugin",
                pluginId = "conflicting.plugin",
                affectedElements = setOf(AccentElementId.INLAY_HINTS),
                type = ConflictType.BLOCK,
            )
        state.forceOverrides.add(AccentElementId.INLAY_HINTS.name)
        mockEpExtensionList(listOf(visualElement))

        invokeApplyElements(state, Color.decode("#FFCC66"), AccentContext.Ayu(AyuVariant.MIRAGE))

        verify(exactly = 1) { visualElement.applyNeutral(AyuVariant.MIRAGE) }
        verify(exactly = 0) { visualElement.apply(any()) }
        assertTrue(AccentElementId.INLAY_HINTS.name in state.forceOverrides)
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
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))

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
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))

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
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))
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
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))

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
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))

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
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))

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
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))

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
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))

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
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))

        // Force override wins — apply called, neutralize path not taken
        verify { element.apply(accent) }
        verify(exactly = 0) { element.applyNeutral(any()) }
    }

    // --- Revert-on-apply-fail regression tests ---
    //
    // Locks the revert-on-apply-fail block: when an element's `apply` throws,
    // `applyElements` must roll that element back with `revert()` so a partial
    // mutation doesn't leave UIManager + live peers in a mixed tinted+stock
    // state (which would then poison `ChromeBaseColors` on the next capture).
    // The PRE-EXISTING "catches RuntimeException from element apply" test
    // passes even if the revert block is deleted — these tests fail if the
    // block is removed.

    /**
     * When an element's `apply` throws, `applyElements` must call `revert()`
     * on the same element AND continue to the next element in the extension
     * list. Without the revert block, the partial mutation would stay visible
     * until the next full apply/revert cycle.
     */
    @Test
    fun `applyElements calls revert on an element whose apply throws`() {
        val throwingElement = createFakeAccentElement(AccentElementId.CARET_ROW, "Caret Row")
        every { throwingElement.apply(any()) } throws RuntimeException("apply broke")
        val normalElement = createFakeAccentElement(AccentElementId.SCROLLBAR, "Scrollbar")
        mockEpExtensionList(listOf(throwingElement, normalElement))

        val accent = Color.decode("#FFCC66")
        // Must not propagate the simulated apply failure.
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))

        // Revert was invoked exactly once on the throwing element — this is
        // the revert-on-apply-fail lock; if the try/revert block is deleted,
        // this verification fails.
        verify(exactly = 1) { throwingElement.revert() }
        // Dispatch proceeded to the next element despite the failure above.
        verify(exactly = 1) { normalElement.apply(accent) }
    }

    /**
     * When `apply` throws AND the fallback `revert()` also throws,
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
        invokeApplyElements(state, accent, AccentContext.Ayu(AyuVariant.MIRAGE))

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

    private fun seedCodeGlanceMethods(
        service: Any,
        getState: Method,
        config: Any,
    ): CodeGlanceProIntegration.CgpViewportMethods {
        val viewport = viewportMethods(config)
        CodeGlanceProIntegration.seedReflectionMethods(
            CodeGlanceProIntegration.CgpMethods(
                service = service,
                getState = getState,
                viewport = viewport,
            ),
        )
        return viewport
    }

    private fun viewportMethods(config: Any): CodeGlanceProIntegration.CgpViewportMethods {
        val viewport =
            CodeGlanceProIntegration.CgpViewportMethods(
                getColor = mockk(relaxed = true),
                getBorder = mockk(relaxed = true),
                getThickness = mockk(relaxed = true),
                setColor = mockk(relaxed = true),
                setBorder = mockk(relaxed = true),
                setThickness = mockk(relaxed = true),
            )
        every { viewport.getColor.invoke(config) } returns "123456"
        every { viewport.getBorder.invoke(config) } returns "654321"
        every { viewport.getThickness.invoke(config) } returns 2
        return viewport
    }

    // Tests for syncCodeGlanceProViewport full flow

    @Test
    fun `syncCodeGlanceProViewport full flow with all methods resolved`() {
        state.cgpIntegrationEnabled = true

        val mockConfig = Any()
        val mockService = Any()
        val mockGetState = mockk<Method>(relaxed = true)
        val viewport = seedCodeGlanceMethods(mockService, mockGetState, mockConfig)

        every { mockGetState.invoke(any()) } returns mockConfig
        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#FFCC66")

        assertEquals(IntegrationOutcome.Applied, outcome)
        verify { mockGetState.invoke(mockService) }
        verify { viewport.setColor.invoke(mockConfig, ACCENT_HEX_STRIPPED) }
        verify { viewport.setBorder.invoke(mockConfig, ACCENT_HEX_STRIPPED) }
        verify { viewport.setThickness.invoke(mockConfig, 1) }
    }

    @Test
    fun `syncCodeGlanceProViewport unlicensed reverts a persisted enabled integration`() {
        state.cgpIntegrationEnabled = true
        state.cgpOwnership = IntegrationOwnership.OWNED.name
        state.cgpBaseColor = "123456"
        state.cgpBaseBorder = "654321"
        state.cgpBaseThickness = 2
        state.cgpAppliedColor = ACCENT_HEX_STRIPPED
        state.cgpAppliedBorder = ACCENT_HEX_STRIPPED
        state.cgpAppliedThickness = 1
        every { LicenseChecker.isLicensedOrGrace() } returns false

        val mockConfig = Any()
        val mockService = Any()
        val mockGetState = mockk<Method>(relaxed = true)
        val viewport = seedCodeGlanceMethods(mockService, mockGetState, mockConfig)
        every { mockGetState.invoke(any()) } returns mockConfig
        every { viewport.getColor.invoke(mockConfig) } returns ACCENT_HEX_STRIPPED
        every { viewport.getBorder.invoke(mockConfig) } returns ACCENT_HEX_STRIPPED
        every { viewport.getThickness.invoke(mockConfig) } returns 1

        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#FFCC66")

        assertEquals(IntegrationOutcome.Restored, outcome)
        verify { viewport.setColor.invoke(mockConfig, "123456") }
        verify { viewport.setBorder.invoke(mockConfig, "654321") }
        verify { viewport.setThickness.invoke(mockConfig, 2) }
        assertTrue(state.cgpIntegrationEnabled)
    }

    @Test
    fun `syncCodeGlanceProViewport strips hash prefix from accent hex`() {
        state.cgpIntegrationEnabled = true

        val mockConfig = Any()
        val mockService = Any()
        val mockGetState = mockk<Method>(relaxed = true)
        val viewport = seedCodeGlanceMethods(mockService, mockGetState, mockConfig)

        every { mockGetState.invoke(any()) } returns mockConfig
        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#E6B450")

        assertEquals(IntegrationOutcome.Applied, outcome)
        verify { viewport.setColor.invoke(mockConfig, "E6B450") }
    }

    @Test
    fun `syncCodeGlanceProViewport returns early when getState returns null config`() {
        state.cgpIntegrationEnabled = true

        val mockService = Any()
        val mockGetState = mockk<Method>(relaxed = true)
        val viewport = seedCodeGlanceMethods(mockService, mockGetState, Any())

        every { mockGetState.invoke(any()) } returns null

        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#FFCC66")

        assertEquals(IntegrationOutcome.Skipped, outcome)
        verify(exactly = 0) { viewport.setColor.invoke(any(), any()) }
    }

    @Test
    fun `syncCodeGlanceProViewport catches InvocationTargetException`() {
        state.cgpIntegrationEnabled = true

        val mockConfig = Any()
        val mockService = Any()
        val mockGetState = mockk<Method>(relaxed = true)
        val viewport = seedCodeGlanceMethods(mockService, mockGetState, mockConfig)

        every { mockGetState.invoke(any()) } returns mockConfig
        every { viewport.setColor.invoke(any(), any()) } throws
            java.lang.reflect.InvocationTargetException(RuntimeException("inner"))

        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#FFCC66")

        assertTrue(outcome is IntegrationOutcome.Failed)
    }

    @Test
    fun `syncCodeGlanceProViewport catches RuntimeException`() {
        state.cgpIntegrationEnabled = true

        val mockConfig = Any()
        val mockService = Any()
        val mockGetState = mockk<Method>(relaxed = true)
        val viewport = seedCodeGlanceMethods(mockService, mockGetState, mockConfig)

        every { mockGetState.invoke(any()) } returns mockConfig
        every { viewport.setColor.invoke(any(), any()) } throws RuntimeException("CGP exploded")

        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#FFCC66")

        assertTrue(outcome is IntegrationOutcome.Failed)
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
     * Called during CodeGlance Pro state reset or in tearDown.
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
     * Invokes the private `applyElements(AyuIslandsState, Color, AccentContext, Boolean)`
     * method via reflection.
     */
    private fun invokeApplyElements(
        targetState: AyuIslandsState,
        accent: Color,
        context: AccentContext,
    ) {
        val method =
            AccentApplicator::class.java.declaredMethods
                .first { it.name == "applyElements" }
        method.isAccessible = true
        method.invoke(
            AccentApplicator,
            targetState,
            accent,
            context,
            LicenseChecker.isLicensedOrGrace(),
        )
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

        assertEquals(6, resolveUnderlineHeight(state, GlowTabMode.OFF))
    }

    @Test
    fun `resolveUnderlineHeight returns tabUnderlineHeight when glow sync disabled`() {
        state.glowTabMode = "MINIMAL"
        state.tabUnderlineGlowSync = false
        state.tabUnderlineHeight = 4

        assertEquals(4, resolveUnderlineHeight(state, GlowTabMode.MINIMAL))
    }

    @Test
    fun `resolveUnderlineHeight returns glow width when sync enabled and glow active`() {
        state.glowTabMode = "MINIMAL"
        state.tabUnderlineGlowSync = true
        state.glowEnabled = true
        state.glowStyle = "SOFT"

        val expected = state.getWidthForStyle(dev.ayuislands.glow.GlowStyle.SOFT)
        assertEquals(expected, resolveUnderlineHeight(state, GlowTabMode.MINIMAL))
    }

    @Test
    fun `resolveUnderlineHeight returns tabUnderlineHeight when sync enabled but glow disabled`() {
        state.glowTabMode = "MINIMAL"
        state.tabUnderlineGlowSync = true
        state.glowEnabled = false
        state.tabUnderlineHeight = 8

        assertEquals(8, resolveUnderlineHeight(state, GlowTabMode.MINIMAL))
    }

    // applyTabUnderline tests (merged from applyTabUnderlineStyle +
    // overrideTabUnderlineForOffMode to keep AccentApplicator under detekt's
    // TooManyFunctions cap)

    @Test
    fun `applyTabUnderline sets underline height and arc via UIManager`() {
        state.glowTabMode = "MINIMAL"
        state.tabUnderlineHeight = 4
        state.tabUnderlineGlowSync = false

        invokeApplyTabUnderline(state, AccentContext.Ayu(AyuVariant.MIRAGE))

        verify { UIManager.put("EditorTabs.underlineHeight", Integer.valueOf(4)) }
        verify { UIManager.put("EditorTabs.underlineArc", any<Int>()) }
    }

    @Test
    fun `applyTabUnderline uses free runtime height then restores licensed saved height without mutation`() {
        state.glowTabMode = "FULL"
        state.tabUnderlineHeight = 7
        state.tabUnderlineGlowSync = true
        state.glowEnabled = false

        every { LicenseChecker.isLicensedOrGrace() } returns false
        invokeApplyTabUnderline(state, AccentContext.Ayu(AyuVariant.MIRAGE))

        every { LicenseChecker.isLicensedOrGrace() } returns true
        invokeApplyTabUnderline(state, AccentContext.Ayu(AyuVariant.MIRAGE))

        verify(exactly = 1) {
            UIManager.put(
                "EditorTabs.underlineHeight",
                Integer.valueOf(AyuIslandsState.DEFAULT_TAB_UNDERLINE_HEIGHT),
            )
        }
        verify(exactly = 1) { UIManager.put("EditorTabs.underlineHeight", Integer.valueOf(7)) }
        assertEquals(7, state.tabUnderlineHeight)
        assertTrue(state.tabUnderlineGlowSync)
        assertEquals("FULL", state.glowTabMode)
        assertFalse(state.glowEnabled)
    }

    @Test
    fun `applyTabUnderline sets neutral gray when OFF and variant present`() {
        state.glowTabMode = "OFF"

        invokeApplyTabUnderline(state, AccentContext.Ayu(AyuVariant.MIRAGE))

        verify { mockScheme.setColor(any(), Color.decode(AyuVariant.MIRAGE.neutralGray)) }
    }

    @Test
    fun `applyTabUnderline skips neutral gray when not OFF`() {
        state.glowTabMode = "MINIMAL"

        invokeApplyTabUnderline(state, AccentContext.Ayu(AyuVariant.MIRAGE))

        verify(exactly = 0) { mockScheme.setColor(ColorKey.find("TAB_UNDERLINE"), any()) }
    }

    @Test
    fun `applyTabUnderline leaves external geometry untouched when tab mode is OFF`() {
        state.glowTabMode = "OFF"
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeChromeTintEnabled = true

        invokeApplyTabUnderline(state, AccentContext.External)

        verify(exactly = 0) { mockScheme.setColor(ColorKey.find("TAB_UNDERLINE"), any()) }
        verify(exactly = 0) { UIManager.put("EditorTabs.underlineHeight", any()) }
        verify(exactly = 0) { UIManager.put("EditorTabs.underlineArc", any()) }
    }

    private fun invokeApplyTabUnderline(
        state: AyuIslandsState,
        context: AccentContext,
    ) {
        val method =
            AccentApplicator::class.java.declaredMethods
                .first { it.name == "applyTabUnderline" }
        method.isAccessible = true
        method.invoke(
            AccentApplicator,
            state,
            context,
            LicenseChecker.isLicensedOrGrace(),
        )
    }

    private fun invokeApplyElement(
        state: AyuIslandsState,
        element: AccentElement,
        accent: Color,
    ) {
        invokePrivate(
            "applyElement",
            state,
            element,
            accent,
            AyuVariant.MIRAGE,
            true,
        )
    }

    private class EditorSchemeStore(
        private val originalColor: Color? = Color(0x12, 0x34, 0x56),
        originalAttributes: TextAttributes? = originalAttributes(),
    ) {
        private val originalAttributes = originalAttributes?.clone()
        private val externalColor = Color(0x65, 0x43, 0x21)
        private val externalAttributes = originalAttributes(Color(0x56, 0x34, 0x12))
        private val colors = mutableMapOf<ColorKey, Color?>()
        private val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>()
        private val touchedColors = linkedSetOf<ColorKey>()
        private val touchedAttributes = linkedSetOf<TextAttributesKey>()
        private var shouldFailRestore = false

        val scheme: EditorColorsScheme =
            mockk(relaxed = true) {
                every { name } returns "_@user_Ayu Islands Mirage"
                every { getColor(any()) } answers {
                    val key = firstArg<ColorKey>()
                    if (!colors.containsKey(key)) colors[key] = originalColor
                    colors[key]
                }
                every { setColor(any(), any()) } answers {
                    val key = firstArg<ColorKey>()
                    val value = secondArg<Color?>()
                    check(!shouldFailRestore || value != originalColor) { "cleanup failed" }
                    touchedColors.add(key)
                    colors[key] = value
                }
                every { getAttributes(any<TextAttributesKey>()) } answers {
                    val key = firstArg<TextAttributesKey>()
                    if (!attributes.containsKey(key)) {
                        attributes[key] = this@EditorSchemeStore.originalAttributes?.clone()
                    }
                    attributes[key]
                }
                every { setAttributes(any(), any()) } answers {
                    val key = firstArg<TextAttributesKey>()
                    val value = secondArg<TextAttributes?>()?.clone()
                    check(
                        !shouldFailRestore || value != this@EditorSchemeStore.originalAttributes,
                    ) { "cleanup failed" }
                    touchedAttributes.add(key)
                    attributes[key] = value
                }
            }

        fun replaceTouchedValues() {
            touchedColors.forEach { key -> colors[key] = externalColor }
            touchedAttributes.forEach { key -> attributes[key] = externalAttributes.clone() }
        }

        fun failRestores() {
            shouldFailRestore = true
        }

        fun assertOriginalValues() {
            touchedColors.forEach { key -> assertEquals(originalColor, colors[key]) }
            touchedAttributes.forEach { key -> assertEquals(originalAttributes, attributes[key]) }
        }

        fun assertOriginalValue(key: ColorKey) {
            assertEquals(originalColor, colors[key])
        }

        fun assertExternalValues() {
            touchedColors.forEach { key -> assertEquals(externalColor, colors[key]) }
            touchedAttributes.forEach { key -> assertEquals(externalAttributes, attributes[key]) }
        }

        fun assertWasChangedTo(accent: Color) {
            assertTrue(touchedColors.isNotEmpty())
            assertTrue(colors.values.none { value -> value == externalColor })
            assertTrue(colors.values.any { value -> value == accent })
        }

        companion object {
            private fun originalAttributes(foreground: Color = Color(0x21, 0x43, 0x65)): TextAttributes =
                TextAttributes().apply {
                    foregroundColor = foreground
                    backgroundColor = Color.BLACK
                    effectColor = Color.CYAN
                    errorStripeColor = Color.MAGENTA
                    fontType = 3
                }
        }
    }

    private fun resetCodeGlanceProState() {
        // Use the typed `CodeGlanceProIntegration.resetReflectionCache`
        // helper instead of hand-rolled raw-reflection writes. A field loop
        // that iterated five field names as raw strings would silently leave
        // stale state in the next test on rename. The helper lives next to
        // the fields it resets — a Kotlin rename refactors both at once.
        CodeGlanceProIntegration.resetReflectionCache()
    }

    private fun createExternalChromeElement(isEnabled: Boolean = true): AbstractChromeElement =
        object : AbstractChromeElement() {
            override val id = AccentElementId.PANEL_BORDER
            override val displayName = "Test external chrome"
            override val backgroundKeys = listOf(EXTERNAL_CHROME_TEST_KEY)
            override val foregroundKeys = emptyList<String>()
            override val foregroundTextTarget = WcagForeground.TextTarget.PRIMARY_TEXT
            override val peerTarget: ChromeTarget? = null
            override val isEnabled = isEnabled
        }

    private fun stubExternalChromeBase(color: Color?) {
        mockkObject(ChromeBaseColors)
        every { ChromeBaseColors[EXTERNAL_CHROME_TEST_KEY] } returns color
        every { ChromeBaseColors.rememberPluginTint(any(), any()) } returns Unit
        every { ChromeBaseColors.forgetPluginTint(any()) } returns Unit
    }

    // apply() with an invalid hex returns false AND posts a user-visible
    // notification on the "Ayu Islands" group. A prior apply() returned Unit
    // and silently swallowed corruption — a regression that let a single bad
    // hex in per-project XML go undiagnosed.
    @Test
    fun `apply with invalid hex returns false and notifies the user`() {
        mockkStatic(com.intellij.notification.Notifications.Bus::class)
        every {
            com.intellij.notification.Notifications.Bus
                .notify(any())
        } returns Unit

        val result = AccentApplicator.applyFromHexString("garbage-hex")

        assertEquals(false, result, "Invalid hex must return false from applyFromHexString()")
        verify(exactly = 1) {
            com.intellij.notification.Notifications.Bus
                .notify(any())
        }
    }

    // applyForFocusedProject MUST carry @RequiresEdt so an off-EDT caller
    // fails fast instead of throwing deep inside a platform API. Source-regex
    // test because the annotation is a contract promise to callers, not a
    // runtime check we can exercise cheaply.
    @Test
    fun `applyForFocusedProject carries RequiresEdt annotation in source`() {
        val sourceFile = java.io.File("src/main/kotlin/dev/ayuislands/accent/AccentApplicator.kt")
        assertTrue(sourceFile.exists(), "Could not locate AccentApplicator.kt for source-level guard")
        val source = sourceFile.readText()
        val edtGuard =
            Regex(
                """@RequiresEdt\s*\n\s*fun\s+applyForFocusedProject""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            edtGuard.containsMatchIn(source),
            "applyForFocusedProject must be annotated @RequiresEdt so off-EDT callers fail fast",
        )
    }

    private companion object {
        private const val ACCENT_HEX_STRIPPED = "FFCC66"
        private const val EXTERNAL_CHROME_TEST_KEY = "Ayu.Test.externalChrome"
        private const val CODE_GLANCE_METHOD_RESOLUTION =
            "resolve" + "C" + "g" + "p" + "Methods"
    }
}
