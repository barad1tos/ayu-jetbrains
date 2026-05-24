package dev.ayuislands.settings

import dev.ayuislands.licensing.LicenseChecker
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

        verify(exactly = 1) { intensityService.apply(SyntaxPreset.NEON, emptyMap()) }
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
            intensityService.apply(SyntaxPreset.NEON, emptyMap())
            stateService.state
        }
    }

    @Test
    fun `apply ordering — service throw leaves state selectedPreset UNCHANGED`() {
        stateBase.selectedPreset = "AMBIENT"
        every { intensityService.apply(any(), any()) } throws RuntimeException("simulated apply failure")
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
        verify(exactly = 0) { intensityService.apply(any(), any()) }
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

        verify(exactly = 1) { intensityService.apply(SyntaxPreset.CUSTOM, emptyMap()) }
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

    // ---------- Reflection helpers ----------

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

    private fun readPanelSource(): String =
        Files.readString(
            Path.of("src/main/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanel.kt"),
        )
}
