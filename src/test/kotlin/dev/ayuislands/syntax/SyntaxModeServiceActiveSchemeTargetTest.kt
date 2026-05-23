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
 * Regression coverage for the H5 root cause confirmed in
 * `.planning/debug/syntax-mood-noop-on-editor.md`:
 *
 * The editor renders from the IDE's active scheme. When a user-derived
 * `_@user_Ayu Islands Mirage` scheme exists with `parent_scheme="Darcula"`
 * (created by an earlier plugin write), the rendering chain
 * `_@user_Ayu Islands Mirage → Darcula` never visits the registered
 * `Ayu Islands Mirage` scheme. Writes targeted by name therefore land on
 * a scheme that is not in the active chain and are visually dead.
 *
 * The fix (Option A — defensive) is that `SyntaxModeService.apply()` must
 * additionally write to `EditorColorsManager.getInstance().globalScheme`
 * so the writes always reach the active rendering chain regardless of how
 * the user-derived scheme inherits.
 *
 * Post-H10 contract: the active-scheme writeback also carries a non-null
 * `TextAttributes` clear payload for non-whitelisted keys (empty
 * `TextAttributes()` here, since the baseline mock is empty). The legacy
 * `setAttributes(any(), null)` assertion has been replaced with the
 * `setAttributes(any(), any<TextAttributes>())` shape.
 *
 * Pre-fix: globalScheme is never touched → these tests FAIL (RED).
 * Post-fix: globalScheme also receives the same per-key writes → GREEN.
 */
class SyntaxModeServiceActiveSchemeTargetTest {
    private lateinit var mockMirage: EditorColorsScheme
    private lateinit var mockDark: EditorColorsScheme
    private lateinit var mockLight: EditorColorsScheme
    private lateinit var mockActive: EditorColorsScheme
    private lateinit var mockManager: EditorColorsManager
    private lateinit var mockMessageBus: MessageBus
    private lateinit var mockPublisher: EditorColorsListener
    private lateinit var mockApp: Application
    private lateinit var loader: SyntaxOverlayLoader
    private lateinit var keyCache: MutableMap<String, TextAttributesKey>

    @BeforeTest
    fun setUp() {
        keyCache = mutableMapOf()
        mockkStatic(TextAttributesKey::class)
        every { TextAttributesKey.find(any<String>()) } answers {
            val name = firstArg<String>()
            keyCache.getOrPut(name) { mockk(relaxed = true) { every { externalName } returns name } }
        }

        mockMirage = mockk(relaxed = true) { every { name } returns "Ayu Islands Mirage" }
        mockDark = mockk(relaxed = true) { every { name } returns "Ayu Islands Dark" }
        mockLight = mockk(relaxed = true) { every { name } returns "Ayu Islands Light" }
        // Active = the user-derived scheme; its NAME is "_@user_Ayu Islands Mirage"
        // — distinct from any of the three registered Ayu schemes by name.
        mockActive = mockk(relaxed = true) { every { name } returns "_@user_Ayu Islands Mirage" }

        mockManager = mockk(relaxed = true)
        mockMessageBus = mockk(relaxed = true)
        mockPublisher = mockk(relaxed = true)
        mockApp = mockk(relaxed = true)

        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockManager
        every { mockManager.getScheme("Ayu Islands Mirage") } returns mockMirage
        every { mockManager.getScheme("Ayu Islands Dark") } returns mockDark
        every { mockManager.getScheme("Ayu Islands Light") } returns mockLight
        // The active scheme is NOT any of the three named schemes — it is the
        // user-derived `_@user_Ayu Islands Mirage` that JetBrains routes
        // editor renders through.
        every { mockManager.globalScheme } returns mockActive

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
        val overlay = mapOf(key("GO_FUNCTION_DECLARATION") to attrs(0xFF, 0xCC, 0x66))
        for (variant in listOf("Mirage", "Dark", "Light")) {
            every { loader.loadOverlayForVariant(variant) } returns overlay
            // Empty baseline → clear payload is an empty TextAttributes (still non-null).
            every { loader.loadBaselineForVariant(variant) } returns emptyMap()
        }
        every { loader.tierKeys(SyntaxMood.MINIMAL) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.STANDARD) } returns setOf(key("GO_FUNCTION_DECLARATION"))
        every { loader.tierKeys(SyntaxMood.RICH) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.MAXIMUM) } returns emptySet()
        StyleAxis.entries.forEach { axis -> every { loader.axisKeys(axis) } returns emptySet() }

        mockkObject(SyntaxOverlayLoader.Companion)
        every { SyntaxOverlayLoader.getInstance() } returns loader

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
    fun `apply writes to globalScheme so user-derived active scheme receives changes (H5 regression)`() {
        // RED before fix: SyntaxModeService.apply only iterates the three named
        // schemes; it never queries or writes to globalScheme. The active
        // user-derived scheme therefore never sees the mood writes and the
        // editor renders unchanged.
        SyntaxModeService().apply(SyntaxMood.STANDARD, emptySet())

        // The service must read the active scheme.
        verify(atLeast = 1) { mockManager.globalScheme }
        // And write the same per-key payload onto it.
        verify(atLeast = 1) { mockActive.setAttributes(any(), any<TextAttributes>()) }
    }

    @Test
    fun `apply with MINIMAL writes non-null clear payload on globalScheme (H10 @NotNull contract)`() {
        // The MINIMAL → clear-everything path is what the user clicks when
        // they want a clean baseline. Post-H10 it must reach the active
        // scheme with a non-null `TextAttributes` clear payload — the legacy
        // `setAttributes(any(), null)` shape would violate the platform
        // `@NotNull` contract and the throw would be silently swallowed
        // (leaving the editor stuck at MAXIMUM forever).
        SyntaxModeService().apply(SyntaxMood.MINIMAL, emptySet())

        verify(atLeast = 1) { mockManager.globalScheme }
        verify(atLeast = 1) { mockActive.setAttributes(any(), any<TextAttributes>()) }
        // Explicit regression guard: the legacy null-clear shape must never appear.
        verify(exactly = 0) { mockActive.setAttributes(any(), null) }
    }

    @Test
    fun `apply does not double-publish globalSchemeChange when adding active-scheme write`() {
        // The single-publish invariant (R-7) must hold even after we add
        // a fourth write target. Exactly one publish per apply().
        SyntaxModeService().apply(SyntaxMood.STANDARD, emptySet())

        verify(exactly = 1) { mockPublisher.globalSchemeChange(null) }
    }

    @Test
    fun `apply skips active-scheme write when globalScheme equals one of the named schemes (no double-write)`() {
        // Clean-install case: the user has not yet caused a `_@user_` derivative,
        // so globalScheme IS one of our three named schemes. To avoid writing
        // the same payload twice to the same instance, the service must skip
        // the active-scheme write when the active scheme is already covered
        // by the by-name loop.
        every { mockManager.globalScheme } returns mockMirage

        SyntaxModeService().apply(SyntaxMood.STANDARD, emptySet())

        // Mirage is hit exactly once via the by-name loop; the active-scheme
        // branch must detect "already covered" and skip.
        verify(exactly = 1) { mockMirage.setAttributes(any(), any<TextAttributes>()) }
    }
}
