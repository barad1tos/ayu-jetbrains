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
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Phase 49 Plan 49-04 — service-method LAF behavior for [SyntaxModeService.reapplyForActiveLaf]
 * (SYNTAX-10, unit-level).
 *
 * Validates the read-state-then-delegate contract used by AyuIslandsLafListener
 * on LAF cycle Ayu → non-Ayu → Ayu. The listener integration test (which
 * exercises the actual LafManagerListener callback wiring) lives in Plan 49-05
 * alongside the listener extension — keeping both halves of that contract in
 * the same commit eliminates the @Disabled / Nyquist risk flagged in revision
 * iteration 1, warning #3.
 *
 * Plain `kotlin.test` + MockK — no platform fixture.
 */
class SyntaxModeServiceLafSwitchTest {
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

        loader = mockk(relaxed = true)
        val overlay = mapOf(key("K1") to TextAttributes())
        for (variant in listOf("Mirage", "Dark", "Light")) {
            every { loader.loadOverlayForVariant(variant) } returns overlay
        }
        every { loader.tierKeys(SyntaxMood.MINIMAL) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.STANDARD) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.RICH) } returns setOf(key("K1"))
        every { loader.tierKeys(SyntaxMood.MAXIMUM) } returns emptySet()
        StyleAxis.entries.forEach { axis -> every { loader.axisKeys(axis) } returns emptySet() }

        mockkObject(SyntaxOverlayLoader.Companion)
        every { SyntaxOverlayLoader.getInstance() } returns loader

        stateBase = SyntaxModeBaseState()
        state = mockk(relaxed = true)
        every { state.state } returns stateBase
        mockkObject(SyntaxModeState.Companion)
        every { SyntaxModeState.getInstance() } returns state

        every { mockApp.getService(SyntaxModeService::class.java) } returns SyntaxModeService()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun key(name: String): TextAttributesKey = TextAttributesKey.find(name)

    @Test
    fun `reapplyForActiveLaf reads SyntaxModeState and delegates to apply with stored mood and axes`() {
        // Given a stored mood + axes, reapplyForActiveLaf must materialize them
        // into a service.apply call observable via the publish + scheme writes.
        stateBase.mood = "RICH"
        stateBase.axes.clear()
        stateBase.axes.add("ITALIC_DECLARATIONS")

        SyntaxModeService().reapplyForActiveLaf()

        // Single publish per reapply → proves apply was invoked exactly once.
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
        // RICH whitelist has K1 → all three schemes got the non-null write.
        verify(exactly = 1) { mockMirage.setAttributes(any<TextAttributesKey>(), any<TextAttributes>()) }
        verify(exactly = 1) { mockDark.setAttributes(any<TextAttributesKey>(), any<TextAttributes>()) }
        verify(exactly = 1) { mockLight.setAttributes(any<TextAttributesKey>(), any<TextAttributes>()) }
    }

    @Test
    fun `reapplyForActiveLaf with invalid stored mood name falls back to MAXIMUM (D-02 default)`() {
        // Bogus mood — `SyntaxMood.fromName` returns MAXIMUM per the D-02 first-launch
        // semantics; LAF re-apply must succeed without throwing. MAXIMUM cumulative
        // whitelist includes K1 (which sits in RICH); a publish proves apply happened
        // and a non-null write proves the MAXIMUM whitelist materialized correctly.
        stateBase.mood = "BOGUS_TIER"
        stateBase.axes.clear()

        SyntaxModeService().reapplyForActiveLaf()

        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
        // Non-null write — K1 sits in RICH which is included in MAXIMUM cumulative
        // whitelist (MINIMAL ⊂ STANDARD ⊂ RICH ⊂ MAXIMUM per D-04).
        verify(exactly = 1) { mockMirage.setAttributes(any<TextAttributesKey>(), any<TextAttributes>()) }
    }

    @Test
    fun `reapplyForActiveLaf with invalid stored axis name silently filters it (no throw)`() {
        // StyleAxis.valueOf throws IllegalArgumentException for unknown names; the
        // service wraps that in runCatching → mapNotNullTo, so bogus entries are
        // silently dropped and the remaining valid axes apply.
        stateBase.mood = "RICH"
        stateBase.axes.clear()
        stateBase.axes.addAll(setOf("ITALIC_DECLARATIONS", "BOGUS_AXIS"))

        SyntaxModeService().reapplyForActiveLaf()

        // Apply succeeded (publish fired) — invalid axis name dropped without throwing.
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    @Test
    fun `reapplyForActiveLaf with empty axes set produces a clean baseline apply`() {
        stateBase.mood = "RICH"
        stateBase.axes.clear()

        SyntaxModeService().reapplyForActiveLaf()

        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    @Test
    fun `reapplyForActiveLaf round-trip Ayu to non-Ayu to Ayu replays the stored mood (R-7)`() {
        // Simulate the LAF cycle: first reapply (Ayu active), second reapply
        // (Ayu restored after switching away and back). Each call produces an
        // independent publish — single publish per call, no batching.
        stateBase.mood = "RICH"
        stateBase.axes.clear()

        SyntaxModeService().reapplyForActiveLaf()
        SyntaxModeService().reapplyForActiveLaf()

        verify(exactly = 2) { mockPublisher.globalSchemeChange(null) }
        verify(exactly = 2) { mockMirage.setAttributes(any<TextAttributesKey>(), any<TextAttributes>()) }
    }
}
