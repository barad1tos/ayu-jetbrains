package dev.ayuislands.vcs

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font

/**
 * Behavioral coverage for [VcsColorApplier]. The applier reads the persisted
 * [AyuIslandsState], detects the active [AyuVariant], hops to EDT via
 * `ApplicationManager.getApplication().invokeLater`, and routes each
 * [VcsPaletteEntry] through the correct write mode ([VcsWriteMode.COLOR_KEY] →
 * `scheme.setColor`, [VcsWriteMode.TEXT_ATTR_BG] → clone-preserve
 * `scheme.setAttributes`).
 *
 * Tests run the [Runnable] passed to `invokeLater` synchronously by stubbing
 * the [Application] mock — matches the harness in
 * `AccentApplicatorRevertAllIntegrationTest`.
 */
class VcsColorApplierTest {
    private val mockScheme = mockk<EditorColorsScheme>(relaxed = true)
    private val mockColorsManager = mockk<EditorColorsManager>(relaxed = true)
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()

    @BeforeEach
    fun setUp() {
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockColorsManager
        every { mockColorsManager.globalScheme } returns mockScheme
        // Default: empty TextAttributes — overridden per test for the
        // clone-preserve check.
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns TextAttributes()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        // Run `invokeLater { ... }` body synchronously so the applier's EDT
        // hop becomes inline — keeps the test single-threaded.
        every { mockApplication.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `applyAll - variant null is a no-op (no scheme writes)`() {
        every { AyuVariant.detect() } returns null

        VcsColorApplier.applyAll()

        // Pattern G regression lock: when no Ayu variant is active the applier
        // must NOT touch the scheme — neither colors nor attributes. A
        // regression that fell through into writeAll would silently tint a
        // foreign LAF's gutter.
        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), any()) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
        // Variant gate fires BEFORE the EDT hop — no invokeLater either.
        verify(exactly = 0) { mockApplication.invokeLater(any()) }
    }

    @Test
    fun `applyAll - master disabled writes null to every palette entry (revert fan-out)`() {
        state.vcsColorEnabled = false

        VcsColorApplier.applyAll()

        // Iterate the same source the applier iterates so counts adapt as the
        // palette evolves — explicit literal counts would rot the moment a new
        // entry lands.
        val (colorKeyEntries, textAttrEntries) = partitionPaletteByMode()

        verify(exactly = colorKeyEntries.size) {
            mockScheme.setColor(any<ColorKey>(), null)
        }
        verify(exactly = textAttrEntries.size) {
            mockScheme.setAttributes(any<TextAttributesKey>(), null)
        }
    }

    @Test
    fun `applyAll - master enabled writes blended colors via correct write mode per entry`() {
        state.vcsColorEnabled = true
        // Per-category intensities default to [VcsColorPreset.AMBIENT_SLIDER]
        // (33) on a fresh state — the blender consumes that directly, so no
        // explicit per-category mutation is needed.

        VcsColorApplier.applyAll()

        val (colorKeyEntries, textAttrEntries) = partitionPaletteByMode()

        // Total invocations match the per-mode partition count. `any()` here
        // matches BOTH null and non-null args in MockK — the explicit-null
        // exactly-zero check below is what locks Pattern G symmetry.
        verify(exactly = colorKeyEntries.size) {
            mockScheme.setColor(any<ColorKey>(), any())
        }
        verify(exactly = textAttrEntries.size) {
            mockScheme.setAttributes(any<TextAttributesKey>(), any())
        }
        // Pattern G symmetry: enabled-mode MUST NOT issue any null-writes —
        // null is exclusively the revert signal.
        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), null) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), null) }
    }

    @Test
    fun `writeTextAttrBackground - preserves foreground errorStripe effectColor effectType from existing`() {
        // The clone-preserve dance is the entire point of TEXT_ATTR_BG mode —
        // a regression that constructed `TextAttributes(null, blended, ...)`
        // would clobber the user's existing foreground accent and error stripe.
        state.vcsColorEnabled = true
        val preExisting =
            TextAttributes(
                Color.RED, // foreground
                Color.BLUE, // background (will be replaced)
                Color.GREEN, // effect color
                EffectType.LINE_UNDERSCORE,
                Font.BOLD,
            ).apply { errorStripeColor = Color.YELLOW }
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns preExisting

        val capturedSlot = slot<TextAttributes>()
        every {
            mockScheme.setAttributes(any<TextAttributesKey>(), capture(capturedSlot))
        } returns Unit

        VcsColorApplier.applyAll()

        // At least one TEXT_ATTR_BG entry must exist for the slot to be filled —
        // sanity-check the palette shape before asserting the clone-preserve
        // contract. If the palette ever drops to zero TEXT_ATTR_BG entries, this
        // assertion turns the silent fall-through into a loud failure.
        val textAttrEntries = partitionPaletteByMode().second
        assertEquals(
            true,
            textAttrEntries.isNotEmpty(),
            "Palette must contain at least one TEXT_ATTR_BG entry for the clone-preserve contract to be exercised.",
        )
        assertNotNull(capturedSlot.captured, "setAttributes must have been invoked at least once with non-null attrs.")
        val captured = capturedSlot.captured
        assertEquals(Color.RED, captured.foregroundColor, "foreground must be preserved")
        assertEquals(Color.YELLOW, captured.errorStripeColor, "errorStripeColor must be preserved")
        assertEquals(Color.GREEN, captured.effectColor, "effectColor must be preserved")
        assertEquals(EffectType.LINE_UNDERSCORE, captured.effectType, "effectType must be preserved")
        assertEquals(Font.BOLD, captured.fontType, "fontType must be preserved")
    }

    @Test
    fun `safeWriteEntry - one failing key does not poison the rest of the loop`() {
        // Pattern B isolation: `safeWriteEntry` wraps every per-key write in a
        // narrow RuntimeException catch. A regression that dropped the catch
        // would surface here as a propagating exception OR as missed writes
        // on every entry following the failing one.
        state.vcsColorEnabled = true
        // Pick the FIRST COLOR_KEY entry as the poison pill — its setColor
        // call throws, every subsequent entry's write must still land.
        val colorKeyEntries = partitionPaletteByMode().first
        val poisonKeyName = colorKeyEntries.first().keyName
        val poisonKey = ColorKey.find(poisonKeyName)
        every { mockScheme.setColor(poisonKey, any()) } throws RuntimeException("boom on $poisonKeyName")

        // No throw expected — `safeWriteEntry` swallows.
        VcsColorApplier.applyAll()

        val textAttrEntries = partitionPaletteByMode().second
        // The poison entry's call attempt counts (MockK records the throw),
        // so total setColor invocations stay at colorKeyEntries.size. The
        // remaining `colorKeyEntries.size - 1` calls landed successfully; the
        // poison call threw but the loop continued.
        verify(exactly = colorKeyEntries.size) {
            mockScheme.setColor(any<ColorKey>(), any())
        }
        // Every TEXT_ATTR_BG entry still got its write — the poison only
        // affected one COLOR_KEY call, the rest of the loop is unaffected.
        verify(exactly = textAttrEntries.size) {
            mockScheme.setAttributes(any<TextAttributesKey>(), any())
        }
    }

    @Test
    fun `safeRevertEntry - one failing key does not poison the revert loop`() {
        // Symmetric to safeWriteEntry: one failing scheme.setColor must not
        // abandon the rest of the revert. Fire through applyAll with
        // vcsColorEnabled=false (routes to revertEveryEntry).
        state.vcsColorEnabled = false
        val colorKeyEntries = partitionPaletteByMode().first
        val poisonKeyName = colorKeyEntries.first().keyName
        val poisonKey = ColorKey.find(poisonKeyName)
        every { mockScheme.setColor(poisonKey, null) } throws RuntimeException("revert-boom on $poisonKeyName")

        // No throw expected — safeRevertEntry swallows.
        VcsColorApplier.applyAll()

        val textAttrEntries = partitionPaletteByMode().second
        // Same total-invocation invariant as the safeWriteEntry test: MockK
        // records the throwing call, so total setColor invocations stay at
        // colorKeyEntries.size. The remaining n-1 reverts landed successfully.
        verify(exactly = colorKeyEntries.size) {
            mockScheme.setColor(any<ColorKey>(), null)
        }
        verify(exactly = textAttrEntries.size) {
            mockScheme.setAttributes(any<TextAttributesKey>(), null)
        }
    }

    @Test
    fun `revertAll - iterates every palette entry with null`() {
        VcsColorApplier.revertAll()

        val (colorKeyEntries, textAttrEntries) = partitionPaletteByMode()

        // Pattern G: every entry receives a null write — the explicit `null`
        // literal in the verify block matches ONLY null args, so the equality
        // of the count and `partitionPaletteByMode()` size proves every entry
        // took the revert path. A regression that wrote a non-null value would
        // drop this count below the partition size.
        verify(exactly = colorKeyEntries.size) {
            mockScheme.setColor(any<ColorKey>(), null)
        }
        verify(exactly = textAttrEntries.size) {
            mockScheme.setAttributes(any<TextAttributesKey>(), null)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Splits the palette into (COLOR_KEY entries, TEXT_ATTR_BG entries) so
     * tests can derive expected verify counts from the same source the applier
     * iterates. Centralised so a palette schema change ripples to every
     * assertion without hard-coded numbers.
     */
    private fun partitionPaletteByMode(): Pair<List<VcsPaletteEntry>, List<VcsPaletteEntry>> {
        val allEntries = VcsColorPalette.allCategoriesAndEntries().values.flatten()
        val colorKey = allEntries.filter { it.mode == VcsWriteMode.COLOR_KEY }
        val textAttr = allEntries.filter { it.mode == VcsWriteMode.TEXT_ATTR_BG }
        return colorKey to textAttr
    }
}
