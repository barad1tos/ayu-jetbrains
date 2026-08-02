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
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.clearAllMocks
import io.mockk.clearMocks
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.util.Properties

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
    private val metadata = Properties()
    private val colors = mutableMapOf<ColorKey, Color?>()
    private val attributes = mutableMapOf<TextAttributesKey, TextAttributes?>()

    @BeforeEach
    fun setUp() {
        VcsColorApplier.resetClaims()
        metadata.clear()
        colors.clear()
        attributes.clear()
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockColorsManager
        every { mockColorsManager.globalScheme } returns mockScheme
        every { mockScheme.name } returns "Ayu Islands Mirage"
        every { mockScheme.metaProperties } returns metadata
        every { mockScheme.getColor(any<ColorKey>()) } answers { colors[firstArg()] }
        every { mockScheme.setColor(any<ColorKey>(), any()) } answers { colors[firstArg()] = secondArg() }
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } answers { attributes[firstArg()] }
        every { mockScheme.setAttributes(any<TextAttributesKey>(), any()) } answers {
            attributes[firstArg()] = secondArg()
        }

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
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
    }

    @AfterEach
    fun tearDown() {
        VcsColorApplier.resetClaims()
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
    fun `revertAll - inactive Ayu with foreign scheme is a no-op`() {
        every { AyuVariant.detect() } returns null
        every { mockScheme.name } returns "Solarized Dark"

        VcsColorApplier.revertAll()

        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), any()) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
    }

    @Test
    fun `revertAll - inactive Ayu restores exact values on the owned scheme`() {
        val originalColor = Color.RED
        val originalAttributes =
            TextAttributes(Color.BLUE, Color.BLACK, Color.GREEN, EffectType.LINE_UNDERSCORE, Font.BOLD).apply {
                errorStripeColor = Color.YELLOW
            }
        val (colorEntries, attributeEntries) = partitionPaletteByMode()
        colorEntries.forEach { colors[ColorKey.find(it.keyName)] = originalColor }
        attributeEntries.forEach { attributes[TextAttributesKey.find(it.keyName)] = originalAttributes.clone() }
        state.vcsColorEnabled = true
        VcsColorApplier.applyAll()
        val colorKey = ColorKey.find(partitionPaletteByMode().first.first().keyName)
        colors[colorKey] = Color.GREEN
        VcsColorApplier.applyAll()
        clearMocks(mockScheme, answers = false, recordedCalls = true)
        every { AyuVariant.detect() } returns null

        VcsColorApplier.revertAll()

        val (colorKeyEntries, textAttrEntries) = partitionPaletteByMode()
        verify(exactly = colorKeyEntries.size - 1) { mockScheme.setColor(any<ColorKey>(), originalColor) }
        verify(exactly = textAttrEntries.size) {
            mockScheme.setAttributes(any<TextAttributesKey>(), originalAttributes)
        }
        assertEquals(Color.GREEN, colors[colorKey])
    }

    @Test
    fun `applyAll - foreign scheme is a no-op`() {
        every { mockScheme.name } returns "Ayu Islands Dark"

        VcsColorApplier.applyAll()

        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), any()) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
    }

    @Test
    fun `applyCurrentScheme - explicit apply rejects a foreign scheme`() {
        state.vcsColorEnabled = true
        every { mockScheme.name } returns "Solarized Dark"

        VcsColorApplier.applyCurrentScheme()

        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), any()) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
    }

    @Test
    fun `foreign scheme never becomes eligible for later automatic reapply`() {
        state.vcsColorEnabled = true
        every { mockScheme.name } returns "Solarized Dark"

        VcsColorApplier.applyCurrentScheme()
        clearMocks(mockScheme, answers = false, recordedCalls = true)

        every { AyuVariant.detect() } returns null
        VcsColorApplier.revertAll()
        clearMocks(mockScheme, answers = false, recordedCalls = true)

        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        VcsColorApplier.applyAll()

        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), any()) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
    }

    @Test
    fun `applyAll - queued callback rechecks current scheme ownership`() {
        val callback = slot<Runnable>()
        every { mockApplication.invokeLater(capture(callback)) } returns Unit

        VcsColorApplier.applyAll()
        every { mockScheme.name } returns "Solarized Dark"
        callback.captured.run()

        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), any()) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
    }

    @Test
    fun `revertAll restores exact claimed scheme after current scheme changes`() {
        state.vcsColorEnabled = true
        VcsColorApplier.applyAll()
        clearMocks(mockScheme, answers = false, recordedCalls = true)

        val replacement = mockk<EditorColorsScheme>(relaxed = true)
        every { replacement.name } returns "Ayu Islands Dark"
        every { mockColorsManager.globalScheme } returns replacement
        every { AyuVariant.detect() } returns null

        VcsColorApplier.revertAll()

        val (colorKeyEntries, textAttrEntries) = partitionPaletteByMode()
        verify(exactly = colorKeyEntries.size) { mockScheme.setColor(any<ColorKey>(), null) }
        verify(exactly = textAttrEntries.size) { mockScheme.setAttributes(any<TextAttributesKey>(), null) }
        verify(exactly = 0) { replacement.setColor(any<ColorKey>(), any()) }
        verify(exactly = 0) { replacement.setAttributes(any<TextAttributesKey>(), any()) }
    }

    @Test
    fun `revertAll does not clean an unclaimed Ayu scheme`() {
        state.vcsColorEnabled = true

        VcsColorApplier.revertAll()

        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), any()) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
    }

    @Test
    fun `disabling VCS does not clean the newly selected unclaimed scheme`() {
        state.vcsColorEnabled = true
        VcsColorApplier.applyAll()

        val replacement = mockk<EditorColorsScheme>(relaxed = true)
        every { replacement.name } returns "Solarized Dark"
        every { mockColorsManager.globalScheme } returns replacement
        clearMocks(mockScheme, answers = false, recordedCalls = true)
        state.vcsColorEnabled = false

        VcsColorApplier.applyCurrentScheme()

        verify(exactly = 0) { replacement.setColor(any<ColorKey>(), any()) }
        verify(exactly = 0) { replacement.setAttributes(any<TextAttributesKey>(), any()) }
        verify(atLeast = 1) { mockScheme.setColor(any<ColorKey>(), null) }
    }

    @Test
    fun `applyAll - master disabled writes null to every palette entry (revert fan-out)`() {
        state.vcsColorEnabled = true
        VcsColorApplier.applyCurrentScheme()
        clearMocks(mockScheme, answers = false, recordedCalls = true)
        state.vcsColorEnabled = false

        VcsColorApplier.applyCurrentScheme()

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

        VcsColorApplier.applyCurrentScheme()

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
        state.vcsColorEnabled = true
        VcsColorApplier.applyCurrentScheme()
        clearMocks(mockScheme, answers = false, recordedCalls = true)
        state.vcsColorEnabled = false
        val colorKeyEntries = partitionPaletteByMode().first
        val poisonKeyName = colorKeyEntries.first().keyName
        val poisonKey = ColorKey.find(poisonKeyName)
        every { mockScheme.setColor(poisonKey, null) } throws RuntimeException("revert-boom on $poisonKeyName")

        // No throw expected — safeRevertEntry swallows.
        VcsColorApplier.applyCurrentScheme()

        val textAttrEntries = partitionPaletteByMode().second
        // The first pass still reaches every entry, then the bounded retry
        // targets only the one failed key.
        verify(exactly = colorKeyEntries.size + 1) {
            mockScheme.setColor(any<ColorKey>(), null)
        }
        verify(exactly = textAttrEntries.size) {
            mockScheme.setAttributes(any<TextAttributesKey>(), null)
        }
    }

    @Test
    fun `revertAll - iterates every palette entry with null`() {
        state.vcsColorEnabled = true
        VcsColorApplier.applyAll()
        clearMocks(mockScheme, answers = false, recordedCalls = true)

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

    @Test
    fun `revertAll retains a claim when cleanup fails so the next call retries`() {
        state.vcsColorEnabled = true
        VcsColorApplier.applyAll()
        clearMocks(mockScheme, answers = false, recordedCalls = true)

        val poisonKey = ColorKey.find(partitionPaletteByMode().first.first().keyName)
        every { mockScheme.setColor(poisonKey, null) } throws RuntimeException("transient revert failure")
        VcsColorApplier.revertAll()
        clearMocks(mockScheme, answers = false, recordedCalls = true)

        every { mockScheme.setColor(poisonKey, null) } returns Unit
        VcsColorApplier.revertAll()

        verify(exactly = 1) { mockScheme.setColor(poisonKey, null) }
        verify(exactly = 1) { mockScheme.setColor(any<ColorKey>(), null) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), null) }
    }

    @Test
    fun `applyAll without a license reverts claimed premium colors`() {
        state.vcsColorEnabled = true
        VcsColorApplier.applyAll()
        clearMocks(mockScheme, answers = false, recordedCalls = true)
        state.vcsColorEnabled = true
        every { LicenseChecker.isLicensedOrGrace() } returns false

        VcsColorApplier.applyAll()

        val (colorKeyEntries, textAttrEntries) = partitionPaletteByMode()
        verify(exactly = colorKeyEntries.size) {
            mockScheme.setColor(any<ColorKey>(), null)
        }
        verify(exactly = textAttrEntries.size) {
            mockScheme.setAttributes(any<TextAttributesKey>(), null)
        }
        assertTrue(state.vcsColorEnabled)
    }

    @Test
    fun `applyAll without a license does not clean an unclaimed scheme`() {
        state.vcsColorEnabled = true
        every { LicenseChecker.isLicensedOrGrace() } returns false

        VcsColorApplier.applyAll()

        verify(exactly = 0) { mockScheme.setColor(any<ColorKey>(), any()) }
        verify(exactly = 0) { mockScheme.setAttributes(any<TextAttributesKey>(), any()) }
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
