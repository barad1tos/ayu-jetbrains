package dev.ayuislands.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.InplaceButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxIntensityState
import dev.ayuislands.syntax.SyntaxPreset
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import java.awt.Color
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JSlider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [AyuIslandsSyntaxPanel] — pill row + Custom premium gate.
 *
 * Coverage:
 *  - Default preset on null / unknown persisted name = `AMBIENT` (D-23).
 *  - Pill selection invokes [SyntaxIntensityService.apply] and persists
 *    `state.selectedPreset` (apply-on-click, no Apply button).
 *  - Apply-FIRST persist-SECOND ordering (Anti-Pattern #4 / Phase 40.4):
 *    a service throw leaves `state.selectedPreset` untouched.
 *  - Custom rejection for unlicensed users: `LicenseChecker.requestLicense`
 *    is invoked, pending stays at the previous preset, no service call,
 *    no state mutation.
 *  - Custom accepted for licensed users: pill selection routes through the
 *    same apply path as the four named pills.
 *  - `reset()` reverts the pending buffer to the stored value.
 *  - Pattern L source-regex regression locks: license check call sites,
 *    apply ordering, absence of Phase 49 symbols, correct `LicenseChecker`
 *    import package, real `buildPanel(panel, variant)` signature, and the
 *    tooltip pre-placement helper presence.
 *
 * Plain kotlin.test + MockK. The Kotlin UI DSL requires EDT-managed
 * `DialogPanel` lifecycle, so the tests exercise apply / reset / isModified
 * on freshly constructed panels and drive the pill selection through the
 * private `onPresetChosen` seam via reflection.
 */
class AyuIslandsSyntaxPanelTest {
    private lateinit var stateBase: SyntaxIntensityBaseState
    private lateinit var stateService: SyntaxIntensityState
    private lateinit var intensityService: SyntaxIntensityService

    @BeforeTest
    fun setUp() {
        stateBase = SyntaxIntensityBaseState()
        stateService = mockk(relaxed = true)
        every { stateService.state } returns stateBase
        mockkObject(SyntaxIntensityState.Companion)
        every { SyntaxIntensityState.getInstance() } returns stateService

        intensityService = mockk(relaxed = true)
        mockkObject(SyntaxIntensityService.Companion)
        every { SyntaxIntensityService.getInstance() } returns intensityService

        mockkObject(LicenseChecker)
        // Default: licensed. Individual tests override to false where needed.
        every { LicenseChecker.isLicensedOrGrace() } returns true
        every { LicenseChecker.requestLicense(any()) } returns Unit
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // ---------- Test 1 — initial state defaults to AMBIENT (D-23) ----------

    @Test
    fun `loadStateIntoPending defaults to AMBIENT when state selectedPreset is null`() {
        stateBase.selectedPreset = null
        val panel = panelWithLoadedState()
        assertSame(SyntaxPreset.AMBIENT, readPendingPreset(panel))
        assertSame(SyntaxPreset.AMBIENT, readStoredPreset(panel))
    }

    @Test
    fun `loadStateIntoPending honors explicit AMBIENT default`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()
        assertSame(SyntaxPreset.AMBIENT, readPendingPreset(panel))
    }

    @Test
    fun `loadStateIntoPending honors a non-default persisted preset name`() {
        stateBase.selectedPreset = "NEON"
        val panel = panelWithLoadedState()
        assertSame(SyntaxPreset.NEON, readPendingPreset(panel))
    }

    // ---------- Test 2 — pill selection applies + persists ----------

    @Test
    fun `pill selection invokes SyntaxIntensityService apply with empty overrides`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.NEON)

        verify(exactly = 1) { intensityService.apply(SyntaxPreset.NEON, emptyMap(), any(), emptyMap()) }
    }

    @Test
    fun `pill selection persists selectedPreset to state`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.WHISPER)

        assertEquals("WHISPER", stateBase.selectedPreset)
    }

    @Test
    fun `pill selection updates stored buffer so subsequent isModified returns false`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.CYBERPUNK)

        assertFalse(panel.isModified(), "after pill click stored == pending so isModified is false")
    }

    // ---------- Test 3 — apply-FIRST persist-SECOND ordering ----------

    @Test
    fun `apply orders service call BEFORE state persistence (Anti-Pattern 4)`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.NEON)

        panel.apply()

        verifyOrder {
            intensityService.apply(SyntaxPreset.NEON, emptyMap(), any(), emptyMap())
            stateService.state
        }
    }

    @Test
    fun `apply ordering — service throw leaves state selectedPreset UNCHANGED`() {
        stateBase.selectedPreset = "AMBIENT"
        every {
            intensityService.apply(any(), any(), any(), any())
        } throws RuntimeException("simulated apply failure")
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.NEON)

        assertFailsWith<RuntimeException> { panel.apply() }

        assertEquals(
            "AMBIENT",
            stateBase.selectedPreset,
            "apply-FIRST persist-SECOND: a service throw must NOT mutate state.selectedPreset",
        )
    }

    // ---------- Test 4 — Custom rejection for unlicensed users ----------

    @Test
    fun `Custom pill rejected for unlicensed users — requestLicense fires, no service call, no persist`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.CUSTOM)

        verify(exactly = 1) {
            LicenseChecker.requestLicense("Unlock per-language syntax customization")
        }
        verify(exactly = 0) { intensityService.apply(any(), any(), any(), any()) }
        assertEquals("AMBIENT", stateBase.selectedPreset)
        assertSame(SyntaxPreset.AMBIENT, readPendingPreset(panel))
    }

    // ---------- Test 5 — Custom accepted for licensed users ----------

    @Test
    fun `Custom pill accepted for licensed users — apply with empty overrides + persist`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()

        invokeOnPresetChosen(panel, SyntaxPreset.CUSTOM)

        verify(exactly = 1) { intensityService.apply(SyntaxPreset.CUSTOM, emptyMap(), any(), emptyMap()) }
        assertEquals("CUSTOM", stateBase.selectedPreset)
        verify(exactly = 0) { LicenseChecker.requestLicense(any()) }
    }

    // ---------- Test 6 — reset reverts pending to stored ----------

    @Test
    fun `reset reverts pendingPreset to storedPreset`() {
        stateBase.selectedPreset = "AMBIENT"
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.NEON)
        assertTrue(panel.isModified())

        panel.reset()

        assertSame(SyntaxPreset.AMBIENT, readPendingPreset(panel))
        assertFalse(panel.isModified())
    }

    // ---------- Test 7 — Pattern L: LicenseChecker call site lock ----------

    @Test
    fun `panel source has exactly 2 LicenseChecker isLicensedOrGrace call sites (Pattern L)`() {
        val source = readPanelSource()
        val pattern = Regex("""LicenseChecker\.isLicensedOrGrace\(\)""")
        val matches = pattern.findAll(source).count()
        assertEquals(
            2,
            matches,
            "Pattern L: only the Custom-pill guard in onPresetChosen and the short-circuit in " +
                "applyCustomPillTooltipIfFree may call LicenseChecker.isLicensedOrGrace(). " +
                "Found $matches call sites — INTENSITY-10 regression risk.",
        )
    }

    @Test
    fun `Custom-pill guard sits next to SyntaxPreset CUSTOM literal in source (Pattern L)`() {
        val source = readPanelSource()
        // The first license call site is the onPresetChosen guard; it must
        // appear within a few lines of the literal SyntaxPreset.CUSTOM to
        // prove the gate is on the Custom branch and not a free-pill path.
        val guardRegex =
            Regex(
                """preset\s*==\s*SyntaxPreset\.CUSTOM\s*&&\s*!LicenseChecker\.isLicensedOrGrace\(\)""",
            )
        assertTrue(
            guardRegex.containsMatchIn(source),
            "Pattern L: the unlicensed Custom guard must read " +
                "'preset == SyntaxPreset.CUSTOM && !LicenseChecker.isLicensedOrGrace()' verbatim.",
        )
    }

    @Test
    fun `requestLicense appears exactly once in panel source`() {
        val source = readPanelSource()
        val matches = Regex("""LicenseChecker\.requestLicense\(""").findAll(source).count()
        assertEquals(
            1,
            matches,
            "Pattern L: LicenseChecker.requestLicense must be invoked exactly once " +
                "(the Custom-rejection branch).",
        )
    }

    // ---------- Test 8 — Pattern L: no Phase 49 symbol references ----------

    @Test
    fun `panel source contains no Phase 49 symbol references (Pattern L)`() {
        val source = readPanelSource()
        val forbidden =
            listOf(
                "SyntaxMood",
                "StyleAxis",
                "SyntaxModeService",
                "SyntaxModeState",
                "SyntaxModeUpgradeNotifier",
            )
        for (literal in forbidden) {
            assertFalse(
                source.contains(literal),
                "Pattern L: Phase 49 symbol '$literal' must not appear in the panel source.",
            )
        }
    }

    // ---------- Test 9 — Pattern L: apply ordering source lock ----------

    @Test
    fun `apply method body has service call BEFORE state mutation in source (Pattern L apply-FIRST)`() {
        val source = readPanelSource()
        val serviceCallIdx = source.indexOf("SyntaxIntensityService.getInstance().apply(")
        val statePersistIdx = source.indexOf("state.selectedPreset = pendingPreset.name")
        assertTrue(
            serviceCallIdx >= 0,
            "panel source must contain a SyntaxIntensityService.getInstance().apply(...) call",
        )
        assertTrue(
            statePersistIdx >= 0,
            "panel source must contain 'state.selectedPreset = pendingPreset.name' persistence",
        )
        assertTrue(
            serviceCallIdx < statePersistIdx,
            "Pattern L apply-FIRST: service.apply(...) must appear textually BEFORE " +
                "state.selectedPreset = pendingPreset.name (Anti-Pattern #4 ordering).",
        )
    }

    // ---------- Test 10 — Pattern L: browserLink present ----------

    @Test
    fun `panel source contains a browserLink call (Pattern L)`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("browserLink("),
            "Pattern L: panel must include the browserLink to the Color Scheme editor docs.",
        )
        assertTrue(
            source.contains("https://www.jetbrains.com/help/idea/configuring-colors-and-fonts.html"),
            "Pattern L: browserLink URL must point at the JetBrains Color Scheme help page.",
        )
    }

    // ---------- Test 11 — Pattern L: correct LicenseChecker package ----------

    @Test
    fun `panel source imports LicenseChecker from licensing package (Codex HIGH 2)`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("import dev.ayuislands.licensing.LicenseChecker"),
            "Codex HIGH #2: LicenseChecker must be imported from dev.ayuislands.licensing.",
        )
        assertFalse(
            source.contains("import dev.ayuislands.license.LicenseChecker"),
            "Codex HIGH #2: wrong package 'dev.ayuislands.license' must not appear in imports.",
        )
    }

    // ---------- Test 12 — Pattern L: real interface signature ----------

    @Test
    fun `panel source uses real buildPanel(panel, variant) signature (Codex HIGH 2)`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("override fun buildPanel("),
            "Codex HIGH #2: override fun buildPanel must be present.",
        )
        assertTrue(
            source.contains("panel: Panel,"),
            "Codex HIGH #2: buildPanel must take 'panel: Panel,' as a positional parameter.",
        )
        assertTrue(
            source.contains("variant: AyuVariant"),
            "Codex HIGH #2: buildPanel must take 'variant: AyuVariant' as a positional parameter.",
        )
        assertFalse(
            source.contains("override fun getComponent"),
            "Codex HIGH #2: getComponent is NOT part of the real AyuIslandsSettingsPanel interface.",
        )
    }

    // ---------- Test 13 — Pattern L: tooltip pre-placement helper presence ----------

    @Test
    fun `panel source contains applyCustomPillTooltipIfFree helper (Gemini MEDIUM 3)`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("applyCustomPillTooltipIfFree"),
            "Gemini MEDIUM #3: applyCustomPillTooltipIfFree helper must exist as the wire site " +
                "for the runIde-finalised Swing subtree lookup.",
        )
        assertTrue(
            source.contains("SwingUtilities.invokeLater { applyCustomPillTooltipIfFree() }"),
            "Gemini MEDIUM #3: tooltip pre-placement must be queued post-realise via " +
                "SwingUtilities.invokeLater { applyCustomPillTooltipIfFree() }.",
        )
    }

    // ---------- Test 14 — composite-key identity round-trip (Pitfall 1/2) ----------

    @Test
    fun `panel composite key resolves in the applicator and transforms the foreground`() {
        // Build the composite key exactly as the panel does: the language half
        // is the SyntaxLanguageRegistry displayName, the category half is
        // PrimitiveCategory.name (NOT displayName).
        val key = "Java|" + PrimitiveCategory.KEYWORD.name
        assertEquals("Java|KEYWORD", key, "panel key form must be displayName|CATEGORY_ENUM_NAME")

        // Reshape via the SAME `|` split the panel's buildNestedOverrides uses.
        val pipeIdx = key.indexOf('|')
        val language = key.substring(0, pipeIdx)
        val category = key.substring(pipeIdx + 1)
        val nested = mapOf(language to mapOf(category to 75))

        val baselineFg = Color(0xE6, 0xB6, 0x73)
        val javaKeywordKey = TextAttributesKey.createTextAttributesKey("JAVA_KEYWORD")
        val result =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides = nested,
                variantName = "Mirage",
                editorBg = Color(0x1F, 0x24, 0x30),
                baseline = mapOf(javaKeywordKey to attrsWithFg(baselineFg)),
                overlay = emptyMap(),
                subordinatePreset = SyntaxPreset.AMBIENT,
            )
        assertNotNull(result[javaKeywordKey], "the composite key must resolve to a transformed entry")
        assertNotEquals(
            baselineFg.rgb,
            result[javaKeywordKey]?.foregroundColor?.rgb,
            "panel key form, classify().displayName, and resolveCurve lookup must all agree on " +
                "'Java' + 'KEYWORD' so slider 75 transforms the foreground (no silent no-op)",
        )
    }

    // ---------- Test 15 — sparse write-through source lock (INTENSITY-17) ----------

    @Test
    fun `panel writes overrides sparsely keyed by composite key, never write-all (Pattern L)`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("pendingOverrides[\"\$language|\${category.name}\"]"),
            "INTENSITY-17: the sparse write must be keyed by the composite displayName|enum-name key.",
        )
        val writeAll =
            Regex(
                """for\s*\(\s*category\s+in\s+PrimitiveCategory\.entries\s*\)\s*\{[^}]*""" +
                    """pendingOverrides\[[^]]*]\s*=""",
            )
        assertFalse(
            writeAll.containsMatchIn(source),
            "INTENSITY-17: overrides must stay sparse — no loop writes every category unconditionally.",
        )
    }

    // ---------- Test 16 — license-invariant source lock (free/override path) ----------

    @Test
    fun `LicenseChecker is absent from apply, onSliderChanged, and rebindSlidersFor regions (Pattern L)`() {
        val source = readPanelSource()
        for (fn in listOf("override fun apply(", "private fun onSliderChanged(", "private fun rebindSlidersFor(")) {
            val body = functionBody(source, fn)
            assertFalse(
                body.contains("LicenseChecker"),
                "INTENSITY-16: the free/override write path ($fn) must not consult LicenseChecker — " +
                    "the service-layer enforceCustomGate is the defense-in-depth.",
            )
        }
    }

    // ---------- Test 17 — per-language master reset behavior (INTENSITY-15) ----------

    @Test
    fun `onResetCurrentLanguage clears only the active language's overrides, leaving others intact`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()

        // Seed two languages' overrides directly into the pending buffer, then
        // pin the active language and run the per-language reset.
        seedPendingOverride(panel, "Java|KEYWORD", "75")
        seedPendingOverride(panel, "Java|STRING_LITERAL", "20")
        seedPendingOverride(panel, "Kotlin|KEYWORD", "60")
        writeCurrentLanguage(panel, "Java")

        try {
            invokeOnResetCurrentLanguage(panel)

            val overrides = readPendingOverrides(panel)
            assertFalse(
                overrides.keys.any { it.startsWith("Java|") },
                "INTENSITY-15: per-language reset must drop every Java override cell.",
            )
            assertEquals(
                "60",
                overrides["Kotlin|KEYWORD"],
                "INTENSITY-15: other languages' overrides must survive a per-language reset.",
            )
        } finally {
            // onResetCurrentLanguage arms the debounce timer; stop it so the
            // platform's SwingTimerWatcherExtension does not flag a live timer.
            panel.dispose()
        }
    }

    @Test
    fun `onResetCurrentLanguage source lock — filters by current-language prefix, not clear-all (Pattern L)`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("onResetCurrentLanguage"),
            "INTENSITY-15: a per-language master reset helper must exist.",
        )
        val body = functionBody(source, "private fun onResetCurrentLanguage(")
        assertTrue(
            body.contains("startsWith(prefix)") || body.contains("\$currentLanguage|"),
            "INTENSITY-15: the per-language reset must scope removal to the active-language prefix.",
        )
        assertFalse(
            body.contains("pendingOverrides.clear()"),
            "INTENSITY-15: the per-language reset must NOT wipe every language's overrides.",
        )
    }

    // ---------- Test 20 — signed-delta readout (Direction B presentation) ----------

    @Test
    fun `signedReadout maps stored value to signed delta from identity`() {
        val panel = AyuIslandsSyntaxPanel()
        assertEquals("0", invokeSignedReadout(panel, 50), "identity (50) reads as 0")
        assertEquals("+25", invokeSignedReadout(panel, 75), "above identity reads +N")
        assertEquals("−20", invokeSignedReadout(panel, 30), "below identity reads −N with U+2212 minus")
        assertEquals("+50", invokeSignedReadout(panel, 100), "max reads +50")
        assertEquals("−50", invokeSignedReadout(panel, 0), "min reads −50")
    }

    // ---------- Test 21 — CATEGORY_GROUPS coverage invariant ----------

    @Test
    fun `CATEGORY_GROUPS covers every PrimitiveCategory exactly once (16 entries, four buckets)`() {
        val groups = readCategoryGroups()
        val flattened = groups.flatMap { it.second }
        assertEquals(
            PrimitiveCategory.entries.size,
            flattened.size,
            "CATEGORY_GROUPS must cover all 16 categories with no dupes — a future 17th enum " +
                "must be assigned to a bucket, not silently dropped from the UI.",
        )
        assertEquals(
            PrimitiveCategory.entries.toSet(),
            flattened.toSet(),
            "CATEGORY_GROUPS flat-map must equal PrimitiveCategory.entries as a set.",
        )
        assertEquals(
            flattened.size,
            flattened.toSet().size,
            "CATEGORY_GROUPS must contain no duplicate categories.",
        )
        assertEquals(
            listOf(4, 5, 3, 4),
            groups.map { it.second.size },
            "Direction B bucket sizes are 4 / 5 / 3 / 4 by visual weight.",
        )
        assertEquals(
            listOf("Declarations", "Identifiers & Members", "Literals", "Keywords & Docs"),
            groups.map { it.first },
            "Direction B group titles in visual-weight order.",
        )
    }

    // ---------- Test 21b — round-robin column split (Approach 1) ----------

    @Test
    fun `buildCategoryGroup splits categories round-robin — left gets even indices, right gets odd`() {
        // The shared-grid fix partitions each group's categories into a left
        // column (even indices) and a right column (odd indices). Replicate the
        // exact filterIndexed predicate the panel uses and assert the split.
        val groups = readCategoryGroups()
        for ((title, categories) in groups) {
            val left = categories.filterIndexed { index, _ -> index % 2 == 0 }
            val right = categories.filterIndexed { index, _ -> index % 2 == 1 }
            assertEquals(
                categories.size,
                left.size + right.size,
                "round-robin split of '$title' must lose no category",
            )
            // Odd-count groups give the left column exactly one extra row (no
            // placeholder juggling), so left.size is either equal to or one
            // more than right.size.
            assertTrue(
                left.size - right.size in 0..1,
                "'$title': left column may carry at most one more row than the right",
            )
            // Interleaving the two columns (left[0], right[0], left[1], ...)
            // must reconstruct the original row-major category order.
            val interleaved =
                categories.indices.map { index ->
                    if (index % 2 == 0) left[index / 2] else right[index / 2]
                }
            assertEquals(
                categories,
                interleaved,
                "interleaving left/right round-robin must reconstruct the original order for '$title'",
            )
        }
    }

    @Test
    fun `panel source uses round-robin filterIndexed split and per-column nested panels (Approach 1)`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("filterIndexed { index, _ -> index % 2 == 0 }"),
            "Approach 1: the left column must be the even-index categories.",
        )
        assertTrue(
            source.contains("filterIndexed { index, _ -> index % 2 == 1 }"),
            "Approach 1: the right column must be the odd-index categories.",
        )
        assertTrue(
            source.contains("for (category in leftCategories) categoryRow(category)"),
            "Approach 1: the left column must iterate its even-index categories into categoryRow.",
        )
        assertTrue(
            source.contains("for (category in rightCategories) categoryRow(category)"),
            "Approach 1: the right column must iterate its odd-index categories into categoryRow.",
        )
        assertTrue(
            source.contains("RightGap.COLUMNS"),
            "Approach 1: the left column must keep a COLUMNS-wide gap before the right column.",
        )
        assertFalse(
            source.contains("placeholder()"),
            "Approach 1: the old odd-slot placeholder juggling must be gone.",
        )
        assertFalse(
            source.contains("buildCategoryUnit"),
            "Approach 1: buildCategoryUnit (panel-per-category) must be replaced by categoryRow.",
        )
        assertTrue(
            source.contains("private fun Panel.categoryRow("),
            "Approach 1: categoryRow must be a Panel-context extension that mutates the enclosing grid.",
        )
    }

    // ---------- Test 21c — readout color signals default vs moved ----------

    @Test
    fun `applyReadout dims the identity readout and strengthens a moved readout`() {
        val panel = AyuIslandsSyntaxPanel()
        val identityLabel = JLabel()
        val movedLabel = JLabel()

        invokeApplyReadout(panel, identityLabel, 50)
        invokeApplyReadout(panel, movedLabel, 75)

        assertEquals("0", identityLabel.text, "identity readout text is 0")
        assertEquals("+25", movedLabel.text, "moved readout text is the signed delta")
        assertNotEquals(
            identityLabel.foreground.rgb,
            movedLabel.foreground.rgb,
            "identity (dimmed contextHelp) and moved (label) foregrounds must differ to signal state",
        )
    }

    @Test
    fun `applyReadout below identity is also rendered in the moved foreground`() {
        val panel = AyuIslandsSyntaxPanel()
        val identityLabel = JLabel()
        val belowLabel = JLabel()

        invokeApplyReadout(panel, identityLabel, 50)
        invokeApplyReadout(panel, belowLabel, 30)

        assertEquals("−20", belowLabel.text, "below identity reads −N with U+2212 minus")
        assertNotEquals(
            identityLabel.foreground.rgb,
            belowLabel.foreground.rgb,
            "a below-identity cell is 'moved' and must use the stronger foreground",
        )
    }

    // ---------- Test 22 — Direction B layout source locks ----------

    @Test
    fun `panel slider cell is tick-free (Direction B)`() {
        val source = readPanelSource()
        assertTrue(source.contains("paintTicks = false"), "Direction B: the slider must hide tick marks.")
        assertTrue(source.contains("paintLabels = false"), "Direction B: the slider must hide tick labels.")
    }

    @Test
    fun `panel uses signedReadout for the slider readout, never a percent string (Direction B)`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("signedReadout("),
            "Direction B: the readout label must render via signedReadout(value).",
        )
        assertFalse(
            source.contains("\"\$value%\"") || source.contains("\"\$SLIDER_MID%\""),
            "Direction B: the old percent readout must be gone — the readout is a signed delta.",
        )
    }

    @Test
    fun `panel builds sliders via the UI DSL slider cell, never a bare JSlider constructor`() {
        val source = readPanelSource()
        // Allow the documentation codespan `JSlider(...)` (backtick-quoted) but
        // forbid a real constructor call: JSlider( preceded by neither an
        // identifier char nor a backtick.
        assertFalse(
            Regex("""[^a-zA-Z_`]JSlider\(""").containsMatchIn(source),
            "Direction B: sliders must be built via the UI-DSL slider() cell, never new JSlider(...).",
        )
        assertTrue(
            source.contains("slider(SLIDER_MIN, SLIDER_MAX, 0, 0)"),
            "Direction B: the tick-free slider cell must be built with zero tick spacing.",
        )
    }

    // ---------- Test 22b — uniform leading-label width source lock (cross-group alignment) ----------

    @Test
    fun `categoryRow uses an explicit fixed-width leading JLabel, not the auto row(displayName) column`() {
        val source = readPanelSource()
        val body = functionBody(source, "private fun Panel.categoryRow(")
        assertFalse(
            body.contains("row(category.displayName)"),
            "cross-group alignment: the auto leading-label column row(category.displayName) must be " +
                "gone — it sizes column 1 per-grid and breaks the single vertical line across groups.",
        )
        assertTrue(
            body.contains("JLabel(category.displayName)"),
            "cross-group alignment: the leading cell must be an explicit JLabel(category.displayName).",
        )
        assertTrue(
            body.contains("preferredSize = Dimension(width, preferredSize.height)") &&
                body.contains("minimumSize = Dimension(width, preferredSize.height)") &&
                body.contains("val width = labelColumnWidth"),
            "cross-group alignment: the leading label must pin BOTH preferred and minimum width to " +
                "the shared labelColumnWidth so all eight grids resolve column 1 identically.",
        )
    }

    @Test
    fun `labelColumnWidth is at least the widest PrimitiveCategory displayName so no label clips`() {
        val panel = AyuIslandsSyntaxPanel()
        val width = readLabelColumnWidth(panel)
        val font = UIUtil.getLabelFont()
        val metrics = JLabel().getFontMetrics(font)
        val widest = PrimitiveCategory.entries.maxOf { metrics.stringWidth(it.displayName) }
        assertTrue(
            width >= widest,
            "the shared label column ($width) must be at least the widest displayName ($widest) so the " +
                "longest label (e.g. 'Interface declaration') never clips against the slider.",
        )
        assertTrue(width > 0, "labelColumnWidth must be positive — the defensive fallback guards a 0 measure.")
    }

    // ---------- Test 22c — RightGap.SMALL inter-cell gap source lock (spacing tightening) ----------

    @Test
    fun `categoryRow tightens label, slider, and readout cells with RightGap SMALL (spacing tightening)`() {
        val source = readPanelSource()
        // The row tightens its inter-cell gaps via the STABLE RightGap.SMALL
        // enum on the label, slider, and readout cells — no platform spacing
        // interface is implemented or delegated. The label→slider, slider→readout,
        // and readout→reset gaps drop from horizontalDefaultGap (scaled 16) to
        // the small gap uniformly across every categoryRow, keeping the shared
        // label column, slider-start axis, and readout column aligned.
        val body = functionBody(source, "private fun Panel.categoryRow(")
        assertTrue(
            body.contains(".gap(RightGap.SMALL)"),
            "spacing tightening: categoryRow must tighten its cells with .gap(RightGap.SMALL).",
        )
        val smallGapCount = Regex("""\.gap\(RightGap\.SMALL\)""").findAll(body).count()
        assertTrue(
            smallGapCount >= 3,
            "spacing tightening: the label, slider, and readout cells must EACH carry " +
                ".gap(RightGap.SMALL) (found $smallGapCount in categoryRow, expected at least 3).",
        )
    }

    @Test
    fun `panel never delegates or implements a platform spacing configuration (binary-compat regression guard)`() {
        val source = readPanelSource()
        // INTENSITY binary-compat regression lock: the old object-by-delegation
        // tightening (object : <spacing iface> by <platform impl>) crashed on
        // newer runtimes with AbstractMethodError because Kotlin's compile-time
        // `by` delegation leaves runtime-added interface members abstract. The
        // spacing interface and its delegation helper must never return.
        val forbiddenSpacingSymbols =
            listOf(
                "SpacingConfiguration",
                "customizeSpacingConfiguration",
                "IntelliJSpacingConfiguration",
                "tightenedSpacing",
                "HALF_HORIZONTAL_GAP",
            )
        for (forbidden in forbiddenSpacingSymbols) {
            assertFalse(
                source.contains(forbidden),
                "binary-compat guard: '$forbidden' must not appear in the panel source — delegating " +
                    "a platform UI-DSL spacing interface caused AbstractMethodError on 2026.1. " +
                    "Tighten gaps with the stable RightGap enum instead.",
            )
        }
    }

    @Test
    fun `readout 28, label padding 8, slider track 140, trailing zone 64 for width-parity (Part B)`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("private const val READOUT_WIDTH = 28"),
            "Part B width parity: the right-aligned readout cell must shrink 34 -> 28.",
        )
        assertTrue(
            source.contains("private const val LABEL_PADDING = 8"),
            "Part B width parity: the leading-label trailing padding must stay 8.",
        )
        assertTrue(
            source.contains("private const val SLIDER_TRACK_WIDTH = 140"),
            "Part B width parity: the slider track must shrink 160 -> 140 so the trailing zone " +
                "fits without growing the row.",
        )
        assertTrue(
            source.contains("private const val TRAILING_ZONE_WIDTH = 64"),
            "Part B width parity: the fixed reset + Bold + Italic trailing zone must be 64.",
        )
    }

    @Test
    fun `scaled readout cell still fits the widest live signed value without clipping`() {
        // The readout cell is right-aligned and fixed-width. Verify the chosen
        // READOUT_WIDTH (scaled) holds the widest signed string the live model
        // reaches: "−50" / "+50" (3 glyphs) at the label font, so the number
        // never clips when right-aligned. Trimming to 28 trades the prior
        // 4-glyph "−100" headroom (unreachable by the ±50 model) for the
        // trailing zone's width without clipping any live value.
        val width = readReadoutWidthScaled()
        val font = UIUtil.getLabelFont()
        val metrics = JLabel().getFontMetrics(font)
        val widestSigned = maxOf(metrics.stringWidth("−50"), metrics.stringWidth("+50"))
        assertTrue(
            width >= widestSigned,
            "the scaled readout cell ($width) must be at least the widest live signed value " +
                "($widestSigned for ±50) so the number never clips when right-aligned.",
        )
    }

    // ---------- Test 23 — slider-change behavior (readout + reset-link + sparse write) ----------

    @Test
    fun `onSliderChanged updates readout, reveals reset link, and records the sparse override`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        val widgets = seedWidgets(panel, PrimitiveCategory.KEYWORD)
        // Production wires the ChangeListener as `onSliderChanged(lang, cat,
        // jslider.value)`, so the slider's value is already the new value when
        // the listener fires (refreshResetVisibility reads the authoritative
        // slider value per the Part B spec). Mirror that contract here.
        widgets.slider.value = 80

        try {
            invokeOnSliderChanged(panel, "Java", PrimitiveCategory.KEYWORD, 80)

            assertEquals("+30", widgets.label.text, "readout must render the signed delta")
            assertTrue(widgets.resetButton.isVisible, "reset icon must appear once the cell diverges")
            assertEquals(
                "80",
                readPendingOverrides(panel)["Java|KEYWORD"],
                "the moved cell must be recorded as a sparse composite-key override",
            )
            val accessibleName = widgets.slider.accessibleContext.accessibleName
            assertTrue(
                accessibleName.contains("+30 from default"),
                "the slider must announce its signed distance from identity",
            )
        } finally {
            panel.dispose()
        }
    }

    @Test
    fun `onSliderChanged back to identity hides the reset link but keeps the cell recorded`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        val widgets = seedWidgets(panel, PrimitiveCategory.KEYWORD)

        try {
            invokeOnSliderChanged(panel, "Java", PrimitiveCategory.KEYWORD, 50)

            assertEquals("0", widgets.label.text, "identity reads as 0")
            assertFalse(widgets.resetButton.isVisible, "reset icon hides at identity")
            // onSliderChanged is a raw record of the moved value — explicit
            // removal is the per-row Reset link's job, not the change listener.
            assertEquals("50", readPendingOverrides(panel)["Java|KEYWORD"])
        } finally {
            panel.dispose()
        }
    }

    // ---------- Test 24 — programmatic setSliderValue snap is listener-safe ----------

    @Test
    fun `setSliderValue snaps slider, readout, and reset-link without recording an override`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        val widgets = seedWidgets(panel, PrimitiveCategory.STRING_LITERAL)

        invokeSetSliderValue(panel, PrimitiveCategory.STRING_LITERAL, 25)

        assertEquals(25, widgets.slider.value, "the slider must snap to the requested value")
        assertEquals("−25", widgets.label.text, "the readout must show the signed delta (U+2212)")
        assertTrue(widgets.resetButton.isVisible, "diverged value reveals the reset icon")
        assertTrue(
            readPendingOverrides(panel).isEmpty(),
            "setSliderValue is a programmatic snap — it must NOT write an override (suppressed listener)",
        )
    }

    // ---------- Test 25 — master reset button enablement tracks active language ----------

    @Test
    fun `refreshMasterResetButton labels and enables per the active language`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Kotlin")
        val widgets = seedWidgets(panel, PrimitiveCategory.KEYWORD)

        invokeRefreshMasterResetButton(panel)
        assertEquals("Reset Kotlin customizations", widgets.button.text)
        assertFalse(widgets.button.isEnabled, "no Kotlin override yet → disabled")

        seedPendingOverride(panel, "Kotlin|KEYWORD", "70")
        invokeRefreshMasterResetButton(panel)
        assertTrue(widgets.button.isEnabled, "a Kotlin override enables the master reset")

        seedPendingOverride(panel, "Java|KEYWORD", "70")
        writeCurrentLanguage(panel, "Java")
        invokeRefreshMasterResetButton(panel)
        assertEquals("Reset Java customizations", widgets.button.text, "label tracks the active language")
    }

    // ---------- Test 26 — buildNestedOverrides reshapes + guards the sparse map ----------

    @Test
    fun `apply reshapes seeded overrides into nested language-category-int and skips malformed keys`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.CUSTOM)
        seedPendingOverride(panel, "Java|KEYWORD", "75")
        seedPendingOverride(panel, "|KEYWORD", "60") // empty language half → skipped
        seedPendingOverride(panel, "Java|", "40") // empty category half → skipped
        seedPendingOverride(panel, "Java|STRING_LITERAL", "notAnInt") // non-int → skipped

        panel.apply()

        verify(exactly = 1) {
            intensityService.apply(
                SyntaxPreset.CUSTOM,
                mapOf("Java" to mapOf("KEYWORD" to 75)),
                any(),
                emptyMap(),
            )
        }
    }

    // ---------- Test 27 — rebindSlidersFor snaps seeded widgets to stored values ----------

    @Test
    fun `rebindSlidersFor snaps a seeded slider to the stored override and identity otherwise`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        val keyword = seedWidgets(panel, PrimitiveCategory.KEYWORD)
        // A second category with no override stays at identity.
        val stringLiteral = seedWidgets(panel, PrimitiveCategory.STRING_LITERAL)
        seedPendingOverride(panel, "Java|KEYWORD", "85")

        invokeRebindSlidersFor(panel, "Java")

        assertEquals(85, keyword.slider.value, "stored override snaps the slider")
        assertEquals("+35", keyword.label.text, "readout reflects the snapped signed delta")
        assertTrue(keyword.resetButton.isVisible, "diverged cell reveals its reset icon")
        assertEquals(50, stringLiteral.slider.value, "untouched cell snaps to identity")
        assertFalse(stringLiteral.resetButton.isVisible, "identity cell hides its reset icon")
    }

    // ---------- Part B Test 28 — toggle flips one bit and composes BOLD_ITALIC ----------

    @Test
    fun `onStyleToggle flips the bit B then I composes BOLD_ITALIC, toggling both off removes the key`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        seedWidgets(panel, PrimitiveCategory.KEYWORD)

        try {
            // First B → BOLD.
            invokeOnStyleToggle(panel, PrimitiveCategory.KEYWORD, Font.BOLD)
            assertEquals("BOLD", readPendingStyles(panel)["Java|KEYWORD"], "B toggle sets BOLD")

            // Then I → BOLD_ITALIC (bits compose, not replace).
            invokeOnStyleToggle(panel, PrimitiveCategory.KEYWORD, Font.ITALIC)
            assertEquals(
                "BOLD_ITALIC",
                readPendingStyles(panel)["Java|KEYWORD"],
                "I toggle composes onto BOLD to make BOLD_ITALIC",
            )

            // Toggle B off → ITALIC remains.
            invokeOnStyleToggle(panel, PrimitiveCategory.KEYWORD, Font.BOLD)
            assertEquals(
                "ITALIC",
                readPendingStyles(panel)["Java|KEYWORD"],
                "dropping the bold bit leaves ITALIC",
            )

            // Toggle I off → both bits off → key REMOVED (inherit, no PLAIN written).
            invokeOnStyleToggle(panel, PrimitiveCategory.KEYWORD, Font.ITALIC)
            assertFalse(
                readPendingStyles(panel).containsKey("Java|KEYWORD"),
                "both bits off must REMOVE the key (return to inherit) — v1 never persists PLAIN",
            )
            assertFalse(
                readPendingStyles(panel).values.contains("PLAIN"),
                "v1 must never write an explicit PLAIN style token",
            )
        } finally {
            panel.dispose()
        }
    }

    // ---------- Part B Test 29 — style toggle is a style-only modification ----------

    @Test
    fun `isModified is true after a style-only toggle (slider untouched)`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        seedWidgets(panel, PrimitiveCategory.KEYWORD)
        assertFalse(panel.isModified(), "fresh CUSTOM panel with no changes is not modified")

        try {
            invokeOnStyleToggle(panel, PrimitiveCategory.KEYWORD, Font.BOLD)
            assertTrue(
                panel.isModified(),
                "a style-only toggle (no slider move) must mark the panel modified so Apply enables",
            )
        } finally {
            panel.dispose()
        }
    }

    // ---------- Part B Test 30 — per-row reset clears BOTH dimensions ----------

    @Test
    fun `per-row reset clears BOTH the slider override and the style for the cell`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        val widgets = seedWidgets(panel, PrimitiveCategory.KEYWORD)
        // Seed both a slider override and a style override for the same cell.
        seedPendingOverride(panel, "Java|KEYWORD", "80")
        seedPendingStyle(panel, "Java|KEYWORD", "BOLD")
        widgets.slider.value = 80

        try {
            // The per-row reset icon's ActionListener delegates to resetCell;
            // seedWidgets cannot wire the throwaway InplaceButton back to this
            // panel instance, so drive the production resetCell directly (same
            // method the trailing reset InplaceButton invokes in categoryRow).
            invokeResetCell(panel, PrimitiveCategory.KEYWORD)

            assertFalse(
                readPendingOverrides(panel).containsKey("Java|KEYWORD"),
                "per-row reset must drop the slider override for the cell",
            )
            assertFalse(
                readPendingStyles(panel).containsKey("Java|KEYWORD"),
                "per-row reset must drop the style override for the cell",
            )
            assertEquals(50, widgets.slider.value, "per-row reset snaps the slider back to identity")
        } finally {
            panel.dispose()
        }
    }

    // ---------- Part B Test 31 — master reset clears both maps for the active language ----------

    @Test
    fun `onResetCurrentLanguage clears both overrides and styles for the active language only`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writeCurrentLanguage(panel, "Java")
        seedWidgets(panel, PrimitiveCategory.KEYWORD)
        seedPendingOverride(panel, "Java|KEYWORD", "75")
        seedPendingStyle(panel, "Java|STRING_LITERAL", "ITALIC")
        seedPendingOverride(panel, "Kotlin|KEYWORD", "60")
        seedPendingStyle(panel, "Kotlin|KEYWORD", "BOLD")

        try {
            invokeOnResetCurrentLanguage(panel)

            val overrides = readPendingOverrides(panel)
            val styles = readPendingStyles(panel)
            assertFalse(overrides.keys.any { it.startsWith("Java|") }, "master reset drops Java overrides")
            assertFalse(styles.keys.any { it.startsWith("Java|") }, "master reset drops Java styles")
            assertEquals("60", overrides["Kotlin|KEYWORD"], "other languages' overrides survive")
            assertEquals("BOLD", styles["Kotlin|KEYWORD"], "other languages' styles survive")
        } finally {
            panel.dispose()
        }
    }

    // ---------- Part B Test 32 — buildNested decodes styles and skips bad cells (via apply) ----------

    @Test
    fun `apply threads decoded font styles to the service and skips malformed style cells`() {
        stateBase.selectedPreset = "CUSTOM"
        val panel = panelWithLoadedState()
        writePendingPreset(panel, SyntaxPreset.CUSTOM)
        seedPendingStyle(panel, "Java|KEYWORD", "BOLD") // → Font.BOLD (1)
        seedPendingStyle(panel, "Java|STRING_LITERAL", "BOLD_ITALIC") // → Font.BOLD or ITALIC (3)
        seedPendingStyle(panel, "|KEYWORD", "BOLD") // empty language → skipped
        seedPendingStyle(panel, "Java|", "ITALIC") // empty category → skipped
        seedPendingStyle(panel, "Java|COMMENT", "NOT_A_STYLE") // undecodable → skipped

        panel.apply()

        verify(exactly = 1) {
            intensityService.apply(
                SyntaxPreset.CUSTOM,
                emptyMap(),
                any(),
                mapOf(
                    "Java" to
                        mapOf(
                            "KEYWORD" to (Font.BOLD),
                            "STRING_LITERAL" to (Font.BOLD or Font.ITALIC),
                        ),
                ),
            )
        }
    }

    // ---------- Part B Test 33 — InplaceButton-only trailing controls (source lock) ----------

    @Test
    fun `trailing controls use InplaceButton, never JToggleButton or ActionButton (Part B source lock)`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("InplaceButton("),
            "Part B: the trailing reset / Bold / Italic controls must be InplaceButton.",
        )
        assertFalse(
            source.contains("JToggleButton"),
            "Part B: no bare JToggleButton — its box shouts across 32 instances.",
        )
        assertFalse(
            Regex("""[^a-zA-Z_]ActionButton\b""").containsMatchIn(source.substringBefore("JBUI.CurrentTheme")) &&
                source.contains("import com.intellij.openapi.actionSystem.impl.ActionButton"),
            "Part B: no ActionButton — it trips the ActionToolbar.updateUI SlowOperations SEVERE.",
        )
        assertFalse(
            source.contains("updateComponentTreeUI"),
            "Part B: NEVER updateComponentTreeUI — SlowOperations SEVERE crash.",
        )
    }

    @Test
    fun `refreshResetVisibility condition references pendingStyles (Part B source lock)`() {
        val source = readPanelSource()
        assertTrue(
            source.contains("private fun refreshResetVisibility("),
            "Part B: a centralized refreshResetVisibility(category) helper must exist.",
        )
        val body = functionBody(source, "private fun refreshResetVisibility(")
        assertTrue(
            body.contains("pendingStyles["),
            "Part B: reset visibility must consider pendingStyles so a style-only cell stays resettable.",
        )
        assertTrue(
            body.contains("SLIDER_MID"),
            "Part B: reset visibility must also consider the slider divergence from identity.",
        )
    }

    @Test
    fun `apply threads built styles into the service and persists state customStyles (Part B source lock)`() {
        val source = readPanelSource()
        val body = functionBody(source, "override fun apply(")
        assertTrue(
            body.contains("buildNested(pendingStyles)"),
            "Part B: apply must build nested styles from pendingStyles.",
        )
        assertTrue(
            body.contains("state.customStyles.clear()") && body.contains("state.customStyles.putAll(pendingStyles)"),
            "Part B: apply must persist pendingStyles into state.customStyles (clear + putAll).",
        )
        val applyIdx = source.indexOf("SyntaxIntensityService.getInstance().apply(")
        val persistIdx = source.indexOf("state.customStyles.clear()")
        assertTrue(
            applyIdx in 0 until persistIdx,
            "Part B apply-FIRST: the service call must precede the customStyles persist.",
        )
    }

    // ---------- Test 18 — debounce source lock (INTENSITY-13 / D-19) ----------

    @Test
    fun `slider apply is debounced single-shot at 100ms and never synchronous (Pattern L)`() {
        val source = readPanelSource()
        assertTrue(source.contains("isRepeats = false"), "D-19: the debounce timer must be single-shot.")
        assertTrue(source.contains("applyTimer.restart()"), "D-19: the drag burst must restart the timer.")
        assertTrue(source.contains("DEBOUNCE_MS = 100"), "D-19: the debounce window must be exactly 100ms.")

        val body = functionBody(source, "private fun onSliderChanged(")
        assertTrue(
            body.contains("applyTimer.restart()"),
            "D-19: the slider change listener must defer the apply through the timer.",
        )
        assertFalse(
            body.contains("apply()"),
            "D-19: the slider change listener must NOT call apply() synchronously.",
        )
    }

    // ---------- Test 19 — isModified override-awareness source lock ----------

    @Test
    fun `isModified compares overrides so Apply enables after a slider move (Pattern L)`() {
        val source = readPanelSource()
        assertTrue(source.contains("storedOverrides"), "isModified must track a storedOverrides buffer.")
        val body = functionBody(source, "override fun isModified(")
        assertTrue(
            body.contains("pendingOverrides != storedOverrides"),
            "isModified() must compare pendingOverrides != storedOverrides so the Apply button " +
                "enables after a slider move (not just on preset change).",
        )
    }

    // ---------- Reflection helpers ----------

    private fun attrsWithFg(color: Color): TextAttributes {
        val attrs = TextAttributes()
        attrs.foregroundColor = color
        return attrs
    }

    /**
     * Return the source region from the start of the function whose
     * declaration contains [declaration] up to (but not including) the next
     * top-level function declaration. Good enough for a Pattern L lock — the
     * panel's helpers are short and do not nest function declarations.
     */
    private fun functionBody(
        source: String,
        declaration: String,
    ): String {
        val start = source.indexOf(declaration)
        assertTrue(start >= 0, "source must contain '$declaration'")
        val after = source.indexOf("    private fun ", start + declaration.length)
        val afterOverride = source.indexOf("    override fun ", start + declaration.length)
        val end =
            listOf(after, afterOverride)
                .filter { it >= 0 }
                .minOrNull() ?: source.length
        return source.substring(start, end)
    }

    private fun panelWithLoadedState(): AyuIslandsSyntaxPanel {
        val panel = AyuIslandsSyntaxPanel()
        val method = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("loadStateIntoPending")
        method.isAccessible = true
        method.invoke(panel)
        return panel
    }

    private fun invokeOnPresetChosen(
        panel: AyuIslandsSyntaxPanel,
        preset: SyntaxPreset,
    ) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "onPresetChosen",
                SyntaxPreset::class.java,
            )
        method.isAccessible = true
        method.invoke(panel, preset)
    }

    private fun readPendingPreset(panel: AyuIslandsSyntaxPanel): SyntaxPreset {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingPreset")
        field.isAccessible = true
        return field.get(panel) as SyntaxPreset
    }

    private fun readStoredPreset(panel: AyuIslandsSyntaxPanel): SyntaxPreset {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("storedPreset")
        field.isAccessible = true
        return field.get(panel) as SyntaxPreset
    }

    private fun writePendingPreset(
        panel: AyuIslandsSyntaxPanel,
        preset: SyntaxPreset,
    ) {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingPreset")
        field.isAccessible = true
        field.set(panel, preset)
    }

    private fun writeCurrentLanguage(
        panel: AyuIslandsSyntaxPanel,
        language: String,
    ) {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("currentLanguage")
        field.isAccessible = true
        field.set(panel, language)
    }

    private fun pendingOverridesField(panel: AyuIslandsSyntaxPanel): MutableMap<*, *> {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingOverrides")
        field.isAccessible = true
        return field.get(panel) as MutableMap<*, *>
    }

    /** Snapshot the pending override map as `String → String` without an unchecked cast. */
    private fun readPendingOverrides(panel: AyuIslandsSyntaxPanel): Map<String, String> =
        pendingOverridesField(panel).entries.associate { (key, value) ->
            (key as String) to (value as String)
        }

    private fun seedPendingOverride(
        panel: AyuIslandsSyntaxPanel,
        key: String,
        value: String,
    ) {
        // Reflective put avoids the parameterized-cast warning: the runtime
        // map element type is erased, so the put goes through java.util.Map.
        val map = pendingOverridesField(panel)
        val putMethod = map.javaClass.getMethod("put", Any::class.java, Any::class.java)
        putMethod.invoke(map, key, value)
    }

    private fun pendingStylesField(panel: AyuIslandsSyntaxPanel): MutableMap<*, *> {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("pendingStyles")
        field.isAccessible = true
        return field.get(panel) as MutableMap<*, *>
    }

    /** Snapshot the pending style map as `String → String` without an unchecked cast. */
    private fun readPendingStyles(panel: AyuIslandsSyntaxPanel): Map<String, String> =
        pendingStylesField(panel).entries.associate { (key, value) ->
            (key as String) to (value as String)
        }

    private fun seedPendingStyle(
        panel: AyuIslandsSyntaxPanel,
        key: String,
        value: String,
    ) {
        val map = pendingStylesField(panel)
        val putMethod = map.javaClass.getMethod("put", Any::class.java, Any::class.java)
        putMethod.invoke(map, key, value)
    }

    private fun invokeOnStyleToggle(
        panel: AyuIslandsSyntaxPanel,
        category: PrimitiveCategory,
        bit: Int,
    ) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "onStyleToggle",
                PrimitiveCategory::class.java,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        method.invoke(panel, category, bit)
    }

    private fun invokeOnResetCurrentLanguage(panel: AyuIslandsSyntaxPanel) {
        val method = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("onResetCurrentLanguage")
        method.isAccessible = true
        method.invoke(panel)
    }

    private fun invokeResetCell(
        panel: AyuIslandsSyntaxPanel,
        category: PrimitiveCategory,
    ) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "resetCell",
                PrimitiveCategory::class.java,
            )
        method.isAccessible = true
        method.invoke(panel, category)
    }

    private fun invokeOnSliderChanged(
        panel: AyuIslandsSyntaxPanel,
        language: String,
        category: PrimitiveCategory,
        value: Int,
    ) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "onSliderChanged",
                String::class.java,
                PrimitiveCategory::class.java,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        method.invoke(panel, language, category, value)
    }

    private fun invokeSetSliderValue(
        panel: AyuIslandsSyntaxPanel,
        category: PrimitiveCategory,
        value: Int,
    ) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "setSliderValue",
                PrimitiveCategory::class.java,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        method.invoke(panel, category, value)
    }

    private fun invokeRefreshMasterResetButton(panel: AyuIslandsSyntaxPanel) {
        val method = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("refreshMasterResetButton")
        method.isAccessible = true
        method.invoke(panel)
    }

    /**
     * Materialize a single category's slider / readout / reset-icon / Bold /
     * Italic widgets (and the master reset button) into the private component
     * maps so the logic methods can be driven without a built
     * [com.intellij.openapi.ui.DialogPanel]. The reset / toggle widgets are real
     * [InplaceButton]s, mirroring the production trailing zone.
     */
    private fun seedWidgets(
        panel: AyuIslandsSyntaxPanel,
        category: PrimitiveCategory,
    ): SeededWidgets {
        val slider = JSlider(0, 100, 50)
        val label = JLabel("0")
        val resetButton = InplaceButton("Reset", AllIcons.Actions.Rollback) {}
        val boldToggle = InplaceButton("Bold", AllIcons.Actions.Rollback) {}
        val italicToggle = InplaceButton("Italic", AllIcons.Actions.Rollback) {}
        val button = JButton()
        putIntoMapField(panel, "sliders", category, slider)
        putIntoMapField(panel, "sliderLabels", category, label)
        putIntoMapField(panel, "resetButtons", category, resetButton)
        putIntoMapField(panel, "boldToggles", category, boldToggle)
        putIntoMapField(panel, "italicToggles", category, italicToggle)
        val buttonField = AyuIslandsSyntaxPanel::class.java.getDeclaredField("masterResetButton")
        buttonField.isAccessible = true
        buttonField.set(panel, button)
        return SeededWidgets(slider, label, resetButton, boldToggle, italicToggle, button)
    }

    private fun putIntoMapField(
        panel: AyuIslandsSyntaxPanel,
        fieldName: String,
        category: PrimitiveCategory,
        value: Any,
    ) {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        val map = field.get(panel) as MutableMap<*, *>
        val putMethod = map.javaClass.getMethod("put", Any::class.java, Any::class.java)
        putMethod.invoke(map, category, value)
    }

    private data class SeededWidgets(
        val slider: JSlider,
        val label: JLabel,
        val resetButton: InplaceButton,
        val boldToggle: InplaceButton,
        val italicToggle: InplaceButton,
        val button: JButton,
    )

    private fun invokeRebindSlidersFor(
        panel: AyuIslandsSyntaxPanel,
        language: String,
    ) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("rebindSlidersFor", String::class.java)
        method.isAccessible = true
        method.invoke(panel, language)
    }

    private fun invokeApplyReadout(
        panel: AyuIslandsSyntaxPanel,
        label: JLabel,
        value: Int,
    ) {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "applyReadout",
                JLabel::class.java,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        method.invoke(panel, label, value)
    }

    private fun invokeSignedReadout(
        panel: AyuIslandsSyntaxPanel,
        value: Int,
    ): String {
        val method =
            AyuIslandsSyntaxPanel::class.java.getDeclaredMethod(
                "signedReadout",
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        return method.invoke(panel, value) as String
    }

    /**
     * Reflect the private `CATEGORY_GROUPS` companion list into a stable
     * `List<Pair<title, categories>>` shape the coverage-invariant test can
     * assert against without depending on the private `CategoryGroup` type.
     */
    private fun readCategoryGroups(): List<Pair<String, List<PrimitiveCategory>>> {
        // A private val on a private companion compiles to a private static
        // backing field on the OUTER class with no getter, so read it directly.
        val groupsField = AyuIslandsSyntaxPanel::class.java.getDeclaredField("CATEGORY_GROUPS")
        groupsField.isAccessible = true
        val groups = groupsField.get(null) as List<*>
        return groups.map { group ->
            requireNotNull(group)
            val titleMethod = group.javaClass.getDeclaredMethod("getTitle")
            titleMethod.isAccessible = true
            val categoriesMethod = group.javaClass.getDeclaredMethod("getCategories")
            categoriesMethod.isAccessible = true
            val title = titleMethod.invoke(group) as String
            val categories =
                (categoriesMethod.invoke(group) as List<*>).map { it as PrimitiveCategory }
            title to categories
        }
    }

    /**
     * Read the lazily-computed shared label-column width through its synthetic
     * getter (a `by lazy` property exposes `getLabelColumnWidth()`; the backing
     * field is the `Lazy` delegate, not the resolved Int).
     */
    private fun readLabelColumnWidth(panel: AyuIslandsSyntaxPanel): Int {
        val getter = AyuIslandsSyntaxPanel::class.java.getDeclaredMethod("getLabelColumnWidth")
        getter.isAccessible = true
        return getter.invoke(panel) as Int
    }

    /**
     * Read the private companion `READOUT_WIDTH` const (a private static field
     * on the outer class) and return it scaled by [JBUI.scale], matching the
     * runtime width the readout cell is pinned to.
     */
    private fun readReadoutWidthScaled(): Int {
        val field = AyuIslandsSyntaxPanel::class.java.getDeclaredField("READOUT_WIDTH")
        field.isAccessible = true
        return JBUI.scale(field.getInt(null))
    }

    private fun readPanelSource(): String =
        Files.readString(
            Path.of("src/main/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanel.kt"),
        )
}
