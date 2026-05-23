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
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 49 Plan 49-04 — full apply-cycle assertion for [SyntaxModeService]
 * (SYNTAX-03, SYNTAX-06, SYNTAX-09 lite).
 *
 * Complements [SyntaxModeServiceTest] (which focuses on per-scheme write
 * isolation and the publish-once invariant) by asserting end-to-end mood
 * behavior with a realistic tier map: STANDARD has 2 keys, RICH has 3, and
 * MAXIMUM has 5 (total 10) so we can verify cumulative whitelist application
 * (D-04 additive semantics).
 *
 * Post-H10 contract: every write carries a non-null `TextAttributes`. The
 * partition is now whitelisted-overlay-clone (foreground color matches the
 * overlay) vs cleared-payload (empty `TextAttributes()` — null foreground).
 *
 * Plain `kotlin.test` + MockK — no platform fixture. Mirrors [AccentElementsTest]
 * static-mock pattern.
 */
class SyntaxModeServiceLifecycleTest {
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

    // 10 overlay keys distributed: 2 STANDARD, 3 RICH, 5 MAXIMUM
    private val standardKeyNames = listOf("STD_1", "STD_2")
    private val richKeyNames = listOf("RICH_1", "RICH_2", "RICH_3")
    private val maximumKeyNames = listOf("MAX_1", "MAX_2", "MAX_3", "MAX_4", "MAX_5")
    private val allKeyNames = standardKeyNames + richKeyNames + maximumKeyNames

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

        // Build a realistic overlay: each key has a unique TextAttributes baseline.
        loader = mockk(relaxed = true)
        val overlay =
            allKeyNames.associate { name ->
                key(name) to TextAttributes().apply { foregroundColor = java.awt.Color(0xFF, 0xCC, 0x66) }
            }
        for (variant in listOf("Mirage", "Dark", "Light")) {
            every { loader.loadOverlayForVariant(variant) } returns overlay
            // Empty baseline → clear payload is an empty `TextAttributes()` whose
            // foregroundColor is null. That is the discriminator the tests use to
            // partition whitelisted (overlay clone, fg!=null) from cleared (fg==null).
            every { loader.loadBaselineForVariant(variant) } returns emptyMap()
        }
        every { loader.tierKeys(SyntaxMood.MINIMAL) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.STANDARD) } returns standardKeyNames.map { key(it) }.toSet()
        every { loader.tierKeys(SyntaxMood.RICH) } returns richKeyNames.map { key(it) }.toSet()
        every { loader.tierKeys(SyntaxMood.MAXIMUM) } returns maximumKeyNames.map { key(it) }.toSet()
        StyleAxis.entries.forEach { axis -> every { loader.axisKeys(axis) } returns emptySet() }

        mockkObject(SyntaxOverlayLoader.Companion)
        every { SyntaxOverlayLoader.getInstance() } returns loader

        // State mock for the reapplyForActiveLaf path.
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

    /**
     * Captures every `setAttributes` invocation made against [scheme] into a
     * list of `(key, attrs)` pairs. With the post-H10 contract `attrs` is
     * always non-null — callers partition on `attrs.foregroundColor` to tell
     * a whitelisted overlay clone (non-null fg) from a cleared payload (null
     * fg from the empty `TextAttributes()` fallback when no baseline exists).
     */
    private fun captureWrites(scheme: EditorColorsScheme): MutableList<Pair<TextAttributesKey, TextAttributes>> {
        val writes = mutableListOf<Pair<TextAttributesKey, TextAttributes>>()
        every { scheme.setAttributes(any<TextAttributesKey>(), any<TextAttributes>()) } answers {
            writes += firstArg<TextAttributesKey>() to secondArg<TextAttributes>()
        }
        return writes
    }

    @Test
    fun `apply with default mood MAXIMUM and empty axes writes overlay attrs for every key in cumulative tiers`() {
        // D-02 default mood — MAXIMUM cumulative whitelist = STANDARD ∪ RICH ∪ MAXIMUM = all 10 keys.
        val writes = captureWrites(mockMirage)

        SyntaxModeService().apply(SyntaxMood.MAXIMUM, emptySet())

        // Every overlay key should receive a whitelisted clone (foreground set).
        val whitelistedKeys = writes.filter { it.second.foregroundColor != null }.map { it.first.externalName }.toSet()
        assertEquals(
            allKeyNames.toSet(),
            whitelistedKeys,
            "MAXIMUM cumulative whitelist must cover all 10 overlay keys (D-04)",
        )
        // Zero cleared payloads — every key is in the active mood's whitelist.
        val clearedKeys = writes.filter { it.second.foregroundColor == null }
        assertTrue(
            clearedKeys.isEmpty(),
            "MAXIMUM mood must NOT emit any clear payloads — every overlay key is whitelisted",
        )
    }

    @Test
    fun `apply with MINIMAL clears every overlay key (non-null empty TextAttributes per H10)`() {
        val writes = captureWrites(mockMirage)

        SyntaxModeService().apply(SyntaxMood.MINIMAL, emptySet())

        // MINIMAL = empty whitelist → every overlay key cleared via empty TextAttributes
        // (post-H10: never null — would violate the platform @NotNull contract).
        assertEquals(
            allKeyNames.size,
            writes.size,
            "MINIMAL must emit one write per overlay key (the empty-TextAttributes clear payload)",
        )
        assertTrue(
            writes.all { it.second.foregroundColor == null },
            "MINIMAL clear payloads must all have null foreground (empty TextAttributes — no override)",
        )
    }

    @Test
    fun `apply with STANDARD writes 2 keys with overlay clone and clears the remaining 8`() {
        val writes = captureWrites(mockMirage)

        SyntaxModeService().apply(SyntaxMood.STANDARD, emptySet())

        val whitelistedKeys = writes.filter { it.second.foregroundColor != null }.map { it.first.externalName }.toSet()
        val clearedKeys = writes.filter { it.second.foregroundColor == null }.map { it.first.externalName }.toSet()

        // STANDARD whitelist = exactly the 2 STD_* keys.
        assertEquals(standardKeyNames.toSet(), whitelistedKeys, "STANDARD whitelist must be the 2 STD_* keys")
        // The remaining 8 keys (RICH + MAXIMUM tiers) must be cleared.
        assertEquals(
            (richKeyNames + maximumKeyNames).toSet(),
            clearedKeys,
            "Keys outside the STANDARD whitelist must be cleared via empty TextAttributes",
        )
    }

    @Test
    fun `apply with RICH writes 5 keys with overlay clone (STANDARD plus RICH) and clears 5 MAXIMUM keys`() {
        val writes = captureWrites(mockDark)

        SyntaxModeService().apply(SyntaxMood.RICH, emptySet())

        val whitelistedKeys = writes.filter { it.second.foregroundColor != null }.map { it.first.externalName }.toSet()
        val clearedKeys = writes.filter { it.second.foregroundColor == null }.map { it.first.externalName }.toSet()
        // RICH cumulative = STANDARD ∪ RICH = 5 keys.
        assertEquals((standardKeyNames + richKeyNames).toSet(), whitelistedKeys)
        // MAXIMUM tier keys cleared.
        assertEquals(maximumKeyNames.toSet(), clearedKeys)
    }

    @Test
    fun `apply triggers exactly one globalSchemeChange publish across all 3 scheme writes`() {
        SyntaxModeService().apply(SyntaxMood.MAXIMUM, emptySet())
        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    @Test
    fun `apply does not mutate SyntaxModeState (apply is read-only on state)`() {
        // Pre-record state snapshot.
        val moodBefore = stateBase.mood
        val axesBefore = stateBase.axes.toSet()

        SyntaxModeService().apply(SyntaxMood.RICH, setOf(StyleAxis.ITALIC_DECLARATIONS))

        // apply MUST NOT mutate state — that's the Panel's job (apply-before-persist
        // Anti-Pattern #4 lesson from Phase 40.4 — the service is a pure orchestrator).
        assertEquals(moodBefore, stateBase.mood, "service.apply must not write state.mood")
        assertEquals(
            axesBefore,
            stateBase.axes.toSet(),
            "service.apply must not write state.axes",
        )
    }

    @Test
    fun `apply with TextAttributes capture proves overlay clones land in scheme`() {
        // Sanity check on captured attrs object: the foreground color set in the
        // test loader baseline should round-trip through compute → setAttributes.
        val captured = slot<TextAttributes>()
        every { mockMirage.setAttributes(any<TextAttributesKey>(), capture(captured)) } answers {}

        SyntaxModeService().apply(SyntaxMood.MAXIMUM, emptySet())

        assertTrue(captured.isCaptured, "setAttributes must have been invoked at least once with non-null attrs")
        val fg = captured.captured.foregroundColor
        assertNotNull(fg, "loader-baseline foregroundColor must survive compute → setAttributes")
        assertEquals(0xFF, fg.red, "loader-baseline red must round-trip unchanged")
        assertEquals(0xCC, fg.green, "loader-baseline green must round-trip unchanged")
        assertEquals(0x66, fg.blue, "loader-baseline blue must round-trip unchanged")
    }
}
