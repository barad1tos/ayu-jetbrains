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
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Phase 49 Plan 49-04 — first-launch defaults (SYNTAX-06, D-02).
 *
 * Asserts the zero-regression contract: users upgrading from a build that
 * shipped the unconditional +1564-key delta on `fix/semantic-keys-yaml-hcl`
 * see ZERO visual change after the Syntax Moods feature lands. That means:
 *  - fresh [SyntaxModeBaseState] has `mood = null` (deserializes to MAXIMUM
 *    via [SyntaxMood.fromName])
 *  - fresh state has `axes = empty set`
 *  - [SyntaxModeService.reapplyForActiveLaf] on that fresh state apply
 *    (MAXIMUM, emptySet) — every overlay key receives a non-null write,
 *    no null clears
 *
 * Plain `kotlin.test` + MockK.
 */
class SyntaxModeServiceUpgradeDefaultTest {
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

        // Overlay with 3 keys distributed: 1 STANDARD, 1 RICH, 1 MAXIMUM
        // → cumulative MAXIMUM whitelist = all 3 keys.
        loader = mockk(relaxed = true)
        val overlay =
            mapOf(
                key("STD_K") to TextAttributes(),
                key("RICH_K") to TextAttributes(),
                key("MAX_K") to TextAttributes(),
            )
        for (variant in listOf("Mirage", "Dark", "Light")) {
            every { loader.loadOverlayForVariant(variant) } returns overlay
        }
        every { loader.tierKeys(SyntaxMood.MINIMAL) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.STANDARD) } returns setOf(key("STD_K"))
        every { loader.tierKeys(SyntaxMood.RICH) } returns setOf(key("RICH_K"))
        every { loader.tierKeys(SyntaxMood.MAXIMUM) } returns setOf(key("MAX_K"))
        StyleAxis.entries.forEach { axis -> every { loader.axisKeys(axis) } returns emptySet() }

        mockkObject(SyntaxOverlayLoader.Companion)
        every { SyntaxOverlayLoader.getInstance() } returns loader

        // FRESH state — no prior persisted values.
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
    fun `SyntaxModeState fresh instance has null mood (deserializes to MAXIMUM via fromName)`() {
        // BaseState `string()` delegate defaults to null. fromName collapses null
        // to MAXIMUM (D-02 first-launch contract).
        assertSame(SyntaxMood.MAXIMUM, SyntaxMood.fromName(stateBase.mood))
    }

    @Test
    fun `SyntaxModeState fresh instance has empty axes set`() {
        assertTrue(stateBase.axes.isEmpty(), "fresh axes must be empty per D-02")
    }

    @Test
    fun `reapplyForActiveLaf on a fresh state applies MAXIMUM with empty axes`() {
        // Fresh state → mood=null → fromName=MAXIMUM, axes empty.
        SyntaxModeService().reapplyForActiveLaf()

        // Single publish proves apply happened exactly once.
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
        // MAXIMUM cumulative whitelist = all 3 keys → 3 non-null writes per scheme.
        verify(exactly = 3) { mockMirage.setAttributes(any<TextAttributesKey>(), any<TextAttributes>()) }
        verify(exactly = 3) { mockDark.setAttributes(any<TextAttributesKey>(), any<TextAttributes>()) }
        verify(exactly = 3) { mockLight.setAttributes(any<TextAttributesKey>(), any<TextAttributes>()) }
    }

    @Test
    fun `first install zero visual change — every overlay key receives a non-null write`() {
        // Capture all writes to detect any null sentinel (which would mean "cleared
        // a key the pre-Phase-49 build had populated" → visual regression).
        val writes = mutableListOf<Pair<TextAttributesKey, TextAttributes?>>()
        every {
            mockMirage.setAttributes(any<TextAttributesKey>(), any())
        } answers {
            writes += firstArg<TextAttributesKey>() to secondArg<TextAttributes?>()
        }

        // Use MAXIMUM directly to confirm the contract; this mirrors what
        // reapplyForActiveLaf would do on a fresh state.
        SyntaxModeService().apply(SyntaxMood.MAXIMUM, emptySet())

        val nullCount = writes.count { it.second == null }
        assertEquals(
            0,
            nullCount,
            "First-install MAXIMUM apply must NOT emit any null (clear) writes — every overlay " +
                "key is in the whitelist, so users see the same delta they had pre-Phase-49 (D-02)",
        )
        assertEquals(3, writes.size, "All 3 overlay keys must receive a non-null write on first install")
    }
}
