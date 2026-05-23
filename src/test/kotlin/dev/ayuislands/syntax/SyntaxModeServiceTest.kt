package dev.ayuislands.syntax

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ThrowableRunnable
import com.intellij.util.messages.MessageBus
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Orchestration tests for [SyntaxModeService]. Mocks all three Ayu schemes
 * by name (R-5) and verifies a single ReadAction-wrapped globalSchemeChange
 * publish (R-7) per apply() invocation.
 *
 * Plain kotlin.test + MockK — no LightPlatformTestCase. Static mocks for
 * EditorColorsManager / ApplicationManager / ReadAction follow the
 * AccentElementsTest pattern.
 */
class SyntaxModeServiceTest {
    private lateinit var mockMirage: EditorColorsScheme
    private lateinit var mockDark: EditorColorsScheme
    private lateinit var mockLight: EditorColorsScheme
    private lateinit var mockManager: EditorColorsManager
    private lateinit var mockMessageBus: MessageBus
    private lateinit var mockPublisher: EditorColorsListener
    private lateinit var mockApp: Application
    private lateinit var loader: SyntaxOverlayLoader
    private lateinit var state: SyntaxModeState
    private lateinit var stateBase: SyntaxModeBaseState
    private lateinit var keyCache: MutableMap<String, TextAttributesKey>

    @BeforeTest
    fun setUp() {
        keyCache = mutableMapOf()
        mockkStatic(TextAttributesKey::class)
        every { TextAttributesKey.find(any<String>()) } answers {
            val name = firstArg<String>()
            keyCache.getOrPut(name) { mockk(relaxed = true) { every { externalName } returns name } }
        }

        mockMirage = mockk(relaxed = true)
        mockDark = mockk(relaxed = true)
        mockLight = mockk(relaxed = true)
        mockManager = mockk(relaxed = true)
        mockMessageBus = mockk(relaxed = true)
        mockPublisher = mockk(relaxed = true)
        mockApp = mockk(relaxed = true)

        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockManager
        every { mockManager.getScheme("Ayu Islands Mirage") } returns mockMirage
        every { mockManager.getScheme("Ayu Islands Dark") } returns mockDark
        every { mockManager.getScheme("Ayu Islands Light") } returns mockLight

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.messageBus } returns mockMessageBus
        every { mockMessageBus.syncPublisher(EditorColorsManager.TOPIC) } returns mockPublisher

        mockkStatic(ReadAction::class)
        every { ReadAction.run<RuntimeException>(any()) } answers {
            firstArg<ThrowableRunnable<RuntimeException>>().run()
        }

        // Loader returns a tiny but realistic overlay across all three variants.
        loader = mockk(relaxed = true)
        val overlay = mapOf(key("K1") to attrs(0xFF, 0xCC, 0x66))
        for (variant in listOf("Mirage", "Dark", "Light")) {
            every { loader.loadOverlayForVariant(variant) } returns overlay
        }
        every { loader.tierKeys(SyntaxMood.MINIMAL) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.STANDARD) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.RICH) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.MAXIMUM) } returns setOf(key("K1"))
        StyleAxis.entries.forEach { axis -> every { loader.axisKeys(axis) } returns emptySet() }

        // Service must obtain loader via SyntaxOverlayLoader.getInstance().
        mockkObject(SyntaxOverlayLoader.Companion)
        every { SyntaxOverlayLoader.getInstance() } returns loader

        // SyntaxModeState mock used by reapplyForActiveLaf.
        stateBase = SyntaxModeBaseState()
        state = mockk(relaxed = true)
        every { state.state } returns stateBase
        mockkObject(SyntaxModeState.Companion)
        every { SyntaxModeState.getInstance() } returns state

        // Service.getInstance() routes through ApplicationManager; relax that too.
        every { mockApp.getService(SyntaxModeService::class.java) } returns SyntaxModeService()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun key(name: String): TextAttributesKey = TextAttributesKey.find(name)

    private fun attrs(
        r: Int,
        g: Int,
        b: Int,
    ): TextAttributes =
        TextAttributes().apply {
            foregroundColor = java.awt.Color(r, g, b)
        }

    @Test
    fun `apply iterates all 3 Ayu scheme variants by name AND also reads globalScheme (H5 active-scheme write)`() {
        SyntaxModeService().apply(SyntaxMood.MAXIMUM, emptySet())
        verify(exactly = 1) { mockManager.getScheme("Ayu Islands Mirage") }
        verify(exactly = 1) { mockManager.getScheme("Ayu Islands Dark") }
        verify(exactly = 1) { mockManager.getScheme("Ayu Islands Light") }
        // H5 fix: the service additionally writes to globalScheme so user-derived
        // active schemes (`_@user_Ayu Islands {Variant}` with parent_scheme=Darcula)
        // also receive the payload. See SyntaxModeServiceActiveSchemeTargetTest
        // for the full regression coverage.
        verify(atLeast = 1) { mockManager.globalScheme }
    }

    @Test
    fun `apply fires globalSchemeChange exactly once across all 3 scheme writes`() {
        SyntaxModeService().apply(SyntaxMood.MAXIMUM, emptySet())
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    @Test
    fun `apply writes setAttributes for each key in computed map across schemes`() {
        SyntaxModeService().apply(SyntaxMood.MAXIMUM, emptySet())
        // 1 key in MAXIMUM whitelist × 3 schemes
        verify(exactly = 1) { mockMirage.setAttributes(any(), any<TextAttributes>()) }
        verify(exactly = 1) { mockDark.setAttributes(any(), any<TextAttributes>()) }
        verify(exactly = 1) { mockLight.setAttributes(any(), any<TextAttributes>()) }
    }

    @Test
    fun `apply with mood MINIMAL clears overlay keys (writes null)`() {
        SyntaxModeService().apply(SyntaxMood.MINIMAL, emptySet())
        verify(atLeast = 1) { mockMirage.setAttributes(any(), null) }
        verify(atLeast = 1) { mockDark.setAttributes(any(), null) }
        verify(atLeast = 1) { mockLight.setAttributes(any(), null) }
    }

    @Test
    fun `apply continues if one scheme write throws RuntimeException (logs WARN)`() {
        every { mockMirage.setAttributes(any(), any<TextAttributes>()) } throws RuntimeException("simulated")
        SyntaxModeService().apply(SyntaxMood.MAXIMUM, emptySet())
        // Dark + Light still got written, publish still fired exactly once
        verify(atLeast = 1) { mockDark.setAttributes(any(), any<TextAttributes>()) }
        verify(atLeast = 1) { mockLight.setAttributes(any(), any<TextAttributes>()) }
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    @Test
    fun `apply skips missing scheme (getScheme returns null) and continues`() {
        every { mockManager.getScheme("Ayu Islands Mirage") } returns null
        SyntaxModeService().apply(SyntaxMood.MAXIMUM, emptySet())
        verify(exactly = 1) { mockDark.setAttributes(any(), any<TextAttributes>()) }
        verify(exactly = 1) { mockLight.setAttributes(any(), any<TextAttributes>()) }
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    @Test
    fun `apply does not swallow CancellationException`() {
        every { mockMirage.setAttributes(any(), any<TextAttributes>()) } throws
            kotlinx.coroutines.CancellationException("cancelled")
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            SyntaxModeService().apply(SyntaxMood.MAXIMUM, emptySet())
        }
    }

    @Test
    fun `reapplyForActiveLaf reads state and applies stored mood and axes`() {
        stateBase.mood = "RICH"
        stateBase.axes.clear()
        stateBase.axes.addAll(setOf("ITALIC_DECLARATIONS"))
        SyntaxModeService().reapplyForActiveLaf()
        // Verify apply happened (publisher fired once)
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    @Test
    fun `reapplyForActiveLaf with null mood falls back to MAXIMUM default`() {
        stateBase.mood = null
        stateBase.axes.clear()
        SyntaxModeService().reapplyForActiveLaf()
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
        // 1 key in MAXIMUM × 3 schemes
        verify(exactly = 1) { mockMirage.setAttributes(any(), any<TextAttributes>()) }
    }

    @Test
    fun `reapplyForActiveLaf with bogus mood falls back to MAXIMUM default`() {
        stateBase.mood = "BOGUS_VALUE"
        stateBase.axes.clear()
        SyntaxModeService().reapplyForActiveLaf()
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    @Test
    fun `reapplyForActiveLaf with bogus axis name silently filters it`() {
        stateBase.mood = "MAXIMUM"
        stateBase.axes.clear()
        stateBase.axes.addAll(setOf("ITALIC_DECLARATIONS", "BOGUS_AXIS"))
        SyntaxModeService().reapplyForActiveLaf()
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    @Test
    fun `clearAll routes through apply with MINIMAL mood and empty axes`() {
        SyntaxModeService().clearAll()
        verify(atLeast = 1) { mockMirage.setAttributes(any(), null) }
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    @Test
    fun `globalSchemeChange publish is wrapped in ReadAction`() {
        // Confirm the mocked ReadAction.run was invoked at least once (the
        // service's publish path is the only call site in this test).
        every { ReadAction.run<RuntimeException>(any()) } just Runs
        SyntaxModeService().apply(SyntaxMood.MAXIMUM, emptySet())
        verify(exactly = 1) { ReadAction.run<RuntimeException>(any()) }
    }
}
