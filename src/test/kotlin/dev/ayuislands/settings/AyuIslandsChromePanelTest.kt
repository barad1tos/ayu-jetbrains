package dev.ayuislands.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ChromeDecorationsProbe
import dev.ayuislands.licensing.LicenseChecker
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [AyuIslandsChromePanel] (Phase 40 / Plan 07):
 *
 *  - Build contract: panel renders the "Chrome Tinting" collapsible group with 5 per-surface
 *    checkboxes, an intensity slider (10-100), and a "Keep foreground readable" checkbox when
 *    the user is licensed.
 *  - Modified tracking: `isModified()` toggles on every pending-vs-stored divergence.
 *  - Apply persistence: each of the 8 chrome state fields is round-tripped into
 *    [AyuIslandsState]; [AccentApplicator.applyForFocusedProject] fires exactly once per
 *    modified apply and does NOT fire when there is no diff.
 *  - Reset: pending is restored to stored.
 *  - Premium gate (CONTEXT D-10): unlicensed users see no chrome tinting controls — the
 *    collapsible content collapses to a single "requires Pro" comment row so the gate is
 *    visible as a hint but none of the underlying bindings surface.
 *  - Probe gate (CONTEXT D-09 / CHROME-02): the Main Toolbar row is enabled when
 *    [ChromeDecorationsProbe.isCustomHeaderActive] returns `true` and disabled (with a
 *    per-OS honest comment) when it returns `false` — present but disabled, never hidden.
 *  - Persisted expanded state: toggling the collapsible writes
 *    [AyuIslandsState.chromeTintingGroupExpanded].
 *
 * Tests interact with the panel through `@TestOnly` seams on [AyuIslandsChromePanel] rather
 * than traversing a built `DialogPanel`'s component tree — mirrors the project convention
 * (see `OverridesGroupBuilderProportionsTest`) of exercising panels via deterministic test
 * hooks instead of spinning up a BasePlatformTestCase Swing harness.
 */
class AyuIslandsChromePanelTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        settings = mockk(relaxed = true)
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(ChromeDecorationsProbe)
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns true

        mockkObject(AccentApplicator)
        every { AccentApplicator.applyForFocusedProject(any()) } returns "#E6B450"

        // The Kotlin UI DSL `collapsibleGroup { … }` builder resolves the CollapsiblePanel
        // toggle action through `ActionManager.getInstance()` which goes via
        // `ApplicationManager.getApplication().getService(ActionManager::class.java)` and
        // down-casts the result. Without a typed ActionManager stub the cast throws
        // `ClassCastException` inside `CollapsibleRowImpl.<init>` and the whole panel
        // cannot build. We hand the relaxed Application mock a relaxed ActionManager so
        // the cast succeeds and `getAction("CollapsiblePanel-toggle")` returns null,
        // which the DSL treats as "no shortcut".
        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        val actionManagerMock = mockk<ActionManager>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { appMock.getService(ActionManager::class.java) } returns actionManagerMock
        every { actionManagerMock.getAction(any()) } returns null

        // `Cell.comment(...)` and the unlicensed "requires Pro" row both call into
        // `ExperimentalUI.getInstance()` for New-UI-aware styling. Same cast trap as
        // ActionManager above — hand over a relaxed ExperimentalUI so the downcast
        // inside `ExperimentalUI.getInstance()` succeeds.
        val experimentalUiMock = mockk<ExperimentalUI>(relaxed = true)
        every { appMock.getService(ExperimentalUI::class.java) } returns experimentalUiMock
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Exercises [AyuIslandsChromePanel.buildPanel] the way the real `BoundConfigurable`
     * does — wraps it in a top-level UI DSL `panel { … }` builder. The returned
     * [com.intellij.openapi.ui.DialogPanel] is discarded; the panel instance itself holds
     * the wired state we assert against through `@TestOnly` seams.
     */
    private fun buildPanel(
        chromePanel: AyuIslandsChromePanel,
        variant: AyuVariant = AyuVariant.DARK,
    ): DialogPanel =
        panel {
            chromePanel.buildPanel(this, variant)
        }

    // ── Test 1: user-space structure ───────────────────────────────────────────

    @Test
    fun `buildPanel produces licensed content with 5 toggles and intensity slider`() {
        every { LicenseChecker.isLicensedOrGrace() } returns true
        val chromePanel = AyuIslandsChromePanel()

        buildPanel(chromePanel)

        assertTrue(
            chromePanel.collapsibleRenderedLicensedForTest(),
            "Licensed build must render the full Chrome Tinting content, not the 'requires Pro' placeholder",
        )
        assertEquals(
            5,
            chromePanel.surfaceCheckboxCountForTest(),
            "Chrome Tinting group must render exactly 5 per-surface toggle checkboxes",
        )
        assertNotNull(
            chromePanel.intensitySliderForTest(),
            "Chrome Tinting group must render the intensity slider",
        )
        val slider = chromePanel.intensitySliderForTest()
        assertEquals(
            10..50,
            slider?.let { it.minimum..it.maximum },
            "Intensity slider user-facing range must be 10-50 — saturations above 50 " +
                "produce near-accent chrome surfaces that fail the readable-contrast bar " +
                "even with always-on WCAG foreground picks. ChromeTintBlender still accepts " +
                "0-100 internally as a math-safety clamp; the 50 cap is the user-visible ceiling.",
        )
    }

    @Test
    fun `loadStored clamps legacy out-of-range intensity down to MAX_INTENSITY and dirties the panel`() {
        // Simulate a user returning from a session predating the cap with intensity > 50.
        state.chromeTintIntensity = 80
        val chromePanel = AyuIslandsChromePanel()

        buildPanel(chromePanel)

        assertEquals(
            50,
            chromePanel.getPendingChromeTintIntensityForTest(),
            "Legacy intensity > MAX_INTENSITY must clamp down to the user-facing cap on load",
        )
        assertEquals(
            50,
            chromePanel.intensitySliderForTest()?.value,
            "Slider must display the clamped pending value, not the raw out-of-range stored value",
        )
        assertTrue(
            chromePanel.isModified(),
            "Clamping a legacy value must dirty the panel so the user gets a visible Apply " +
                "button to persist the capped intensity back into AyuIslandsState",
        )
    }

    @Test
    fun `loadStored clamps negative legacy intensity up to MIN_INTENSITY so slider construction stays total`() {
        // Corrupted XML or a third-party migration may persist a negative intensity.
        // The prior coerceAtMost clamp only trimmed the upper bound, so a negative
        // value would slip through to the JSlider(min, max, value) constructor which
        // throws IllegalArgumentException when value < min. coerceIn fixes both sides.
        state.chromeTintIntensity = -5
        val chromePanel = AyuIslandsChromePanel()

        buildPanel(chromePanel)

        assertEquals(
            10,
            chromePanel.getPendingChromeTintIntensityForTest(),
            "Negative legacy intensity must clamp up to MIN_INTENSITY so slider construction cannot throw",
        )
        assertEquals(
            10,
            chromePanel.intensitySliderForTest()?.value,
            "Slider must display the floor-clamped pending value, not the raw negative stored value",
        )
    }

    @Test
    fun `applying a clamped legacy intensity persists MAX_INTENSITY back into state`() {
        state.chromeTintIntensity = 90
        val chromePanel = AyuIslandsChromePanel()
        buildPanel(chromePanel, AyuVariant.DARK)

        // No user interaction — the clamp alone dirtied the panel. Applying should
        // write the capped pending value back to state, not the raw 90.
        chromePanel.apply()

        assertEquals(
            50,
            state.chromeTintIntensity,
            "apply() on a legacy-clamped panel must persist MAX_INTENSITY back to state",
        )
    }

    @Test
    fun `buildPanel source no longer references the retired keep-foreground-readable checkbox`() {
        // Regression guard: the "Keep foreground readable" row was retired — WCAG
        // foreground contrast is now always-on so the toggle and its backing
        // state field should not appear anywhere in the panel source.
        val source =
            java.io
                .File("src/main/kotlin/dev/ayuislands/settings/AyuIslandsChromePanel.kt")
                .takeIf { it.exists() }
                ?.readText()
        assertNotNull(source, "Could not locate AyuIslandsChromePanel.kt for static regression guard")
        assertFalse(
            source.contains("Keep foreground readable"),
            "Panel must no longer render the retired 'Keep foreground readable' checkbox",
        )
        assertFalse(
            source.contains("chromeTintKeepForegroundReadable"),
            "Panel must no longer reference chromeTintKeepForegroundReadable state",
        )
    }

    // ── Test 2-5: isModified tracking ──────────────────────────────────────────

    @Test
    fun `isModified returns false immediately after buildPanel`() {
        val chromePanel = AyuIslandsChromePanel()
        buildPanel(chromePanel)

        assertFalse(chromePanel.isModified(), "Fresh build must have pending == stored")
    }

    @Test
    fun `isModified returns true after a per-surface toggle flips`() {
        val chromePanel = AyuIslandsChromePanel()
        buildPanel(chromePanel)

        chromePanel.setPendingChromeStatusBarForTest(true)

        assertTrue(chromePanel.isModified(), "Flipping pendingChromeStatusBar must dirty the panel")
    }

    @Test
    fun `isModified returns true after intensity changes`() {
        val chromePanel = AyuIslandsChromePanel()
        buildPanel(chromePanel)

        chromePanel.setPendingChromeTintIntensityForTest(55)

        assertTrue(chromePanel.isModified(), "Changing pendingChromeTintIntensity must dirty the panel")
    }

    // ── Test 6-7: apply() persistence ──────────────────────────────────────────

    @Test
    fun `apply persists all 7 chrome fields and triggers applyForFocusedProject once`() {
        val chromePanel = AyuIslandsChromePanel()
        buildPanel(chromePanel, AyuVariant.DARK)

        // Mutate every user-facing pending field.
        chromePanel.setPendingChromeStatusBarForTest(true)
        chromePanel.setPendingChromeMainToolbarForTest(true)
        chromePanel.setPendingChromeToolWindowStripeForTest(true)
        chromePanel.setPendingChromeNavBarForTest(true)
        chromePanel.setPendingChromePanelBorderForTest(true)
        chromePanel.setPendingChromeTintIntensityForTest(45)

        chromePanel.apply()

        // Every backing state field must mirror the pending value.
        assertTrue(state.chromeStatusBar, "chromeStatusBar not persisted")
        assertTrue(state.chromeMainToolbar, "chromeMainToolbar not persisted")
        assertTrue(state.chromeToolWindowStripe, "chromeToolWindowStripe not persisted")
        assertTrue(state.chromeNavBar, "chromeNavBar not persisted")
        assertTrue(state.chromePanelBorder, "chromePanelBorder not persisted")
        assertEquals(45, state.chromeTintIntensity, "chromeTintIntensity not persisted")

        // apply() must re-run the EP chain exactly once for the panel's variant so the
        // 5 chrome AccentElement impls repaint immediately (CONTEXT D-07 / must_have 4).
        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(AyuVariant.DARK) }
    }

    @Test
    fun `apply is a no-op when nothing changed`() {
        val chromePanel = AyuIslandsChromePanel()
        buildPanel(chromePanel)

        chromePanel.apply()

        // No mutation → no re-apply. Prevents T-40-26 (DoS on repeated clean apply).
        verify(exactly = 0) { AccentApplicator.applyForFocusedProject(any()) }
    }

    // ── Test 8: reset() ────────────────────────────────────────────────────────

    @Test
    fun `reset reverts pending fields to stored without touching AyuIslandsState`() {
        state.chromeStatusBar = false
        state.chromeTintIntensity = AyuIslandsState.DEFAULT_CHROME_TINT_INTENSITY

        val chromePanel = AyuIslandsChromePanel()
        buildPanel(chromePanel)

        chromePanel.setPendingChromeStatusBarForTest(true)
        chromePanel.setPendingChromeTintIntensityForTest(90)

        chromePanel.reset()

        assertFalse(chromePanel.getPendingChromeStatusBarForTest())
        assertEquals(
            AyuIslandsState.DEFAULT_CHROME_TINT_INTENSITY,
            chromePanel.getPendingChromeTintIntensityForTest(),
        )
        // Reset must not call the applicator.
        verify(exactly = 0) { AccentApplicator.applyForFocusedProject(any()) }
    }

    // ── Phase 40.2 T-6: mid-session license flip must not persist ──────────────
    //
    // If the panel is built while licensed and the license flips to false
    // before the user presses Apply, the panel MUST not persist chrome state
    // fields AND must not invoke applyForFocusedProject. Prevents grace-expiry
    // / sign-out scenarios where the UI is already built but the gate has
    // since closed behind the user's back.

    @Test
    fun `apply refuses to persist chrome state when license flips to false mid-session`() {
        // Panel built while licensed — all controls wired.
        every { LicenseChecker.isLicensedOrGrace() } returns true
        val chromePanel = AyuIslandsChromePanel()
        buildPanel(chromePanel, AyuVariant.DARK)

        // User makes a change.
        chromePanel.setPendingChromeStatusBarForTest(true)
        chromePanel.setPendingChromeTintIntensityForTest(35)

        // License is revoked before apply() runs.
        every { LicenseChecker.isLicensedOrGrace() } returns false

        chromePanel.apply()

        // No state mutation.
        assertFalse(
            state.chromeStatusBar,
            "Post-license-revocation apply must not persist chromeStatusBar",
        )
        assertEquals(
            AyuIslandsState.DEFAULT_CHROME_TINT_INTENSITY,
            state.chromeTintIntensity,
            "Post-license-revocation apply must not persist chromeTintIntensity",
        )
        // No EP re-apply.
        verify(exactly = 0) { AccentApplicator.applyForFocusedProject(any()) }
    }

    // ── Test 9: premium gate (CONTEXT D-10) ────────────────────────────────────

    @Test
    fun `unlicensed build hides chrome tinting controls behind a 'requires Pro' comment`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val chromePanel = AyuIslandsChromePanel()

        buildPanel(chromePanel)

        assertFalse(
            chromePanel.collapsibleRenderedLicensedForTest(),
            "Unlicensed build must not render the chrome tinting controls (D-10 premium gate)",
        )
        assertEquals(
            0,
            chromePanel.surfaceCheckboxCountForTest(),
            "Unlicensed build must not wire any of the 5 per-surface checkboxes",
        )
        assertNull(
            chromePanel.intensitySliderForTest(),
            "Unlicensed build must not wire the intensity slider",
        )
    }

    // ── Test 10-11: probe-driven enabledIf (CONTEXT D-09 / CHROME-02) ──────────

    @Test
    fun `main toolbar row is enabled when ChromeDecorationsProbe reports a custom header`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns true
        val chromePanel = AyuIslandsChromePanel()

        buildPanel(chromePanel)

        assertTrue(
            chromePanel.mainToolbarRowEnabledForTest(),
            "Main Toolbar row must be enabled when the IDE paints a JBR custom window header",
        )
    }

    @Test
    fun `main toolbar row is disabled with per-OS honest comment when probe reports native chrome`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false
        val chromePanel = AyuIslandsChromePanel()

        buildPanel(chromePanel)

        assertFalse(
            chromePanel.mainToolbarRowEnabledForTest(),
            "Main Toolbar row must be disabled when the OS paints the native title bar (CHROME-02)",
        )
        val comment = chromePanel.mainToolbarRowCommentForTest()
        assertNotNull(
            comment,
            "Main Toolbar row must display a per-OS disabled-state comment",
        )
        // Host-OS-dependent assertion: the rendered comment must match the branch that
        // disabledMainToolbarComment() picks for the running OS. Other branches are
        // exercised by the dedicated per-branch tests below via the helper seam.
        val expected =
            when {
                SystemInfo.isMac ->
                    "Disabled: macOS paints the native title bar (IDE 2026.1+ no longer exposes a toggle)."
                SystemInfo.isWindows ->
                    "Disabled: your IDE is not using custom window decorations."
                SystemInfo.isLinux ->
                    "Disabled: your window manager paints the native title bar."
                else ->
                    "Disabled: your OS paints the native title bar."
            }
        assertEquals(
            expected,
            comment,
            "Main Toolbar row comment must match the per-OS branch of disabledMainToolbarComment()",
        )
    }

    // ── Per-OS disabledMainToolbarComment branch coverage (Pattern L + I) ─────
    //
    // The host-OS-dependent test above locks exactly one branch: whichever the CI
    // runner happens to be. The three sibling `ChromeSupport.Unsupported` variants
    // (`GnomeSsd`, `WindowsNoCustomHeader`, `UnknownOs`) plus the off-host major
    // branch (e.g., `NativeMacTitleBar` when CI is Linux) must each be exercised
    // explicitly so a refactor that mutates one per-OS copy string without
    // touching the others cannot slip through.
    //
    // Each test pins `ChromeDecorationsProbe.osSupplier` to the desired branch
    // and wraps the call in `try { … } finally { resetOsSupplierForTests() }`
    // per Pattern I (JUnit's `@AfterTest` alone is insufficient — an assertion
    // failure bypasses it and poisons the next test on the same worker).
    //
    // The probe's per-OS inputs (UIManager boolean key + Registry boolean key)
    // are left at their default-false values so `probe()` returns the
    // "unsupported" branch matching the pinned OS.
    //
    // `isCustomHeaderActive()` stays stubbed to `false` so the panel builds the
    // disabled row (which is where `disabledMainToolbarComment()` is invoked).
    // `probe()` itself is NOT stubbed — the real implementation runs so it reads
    // our pinned `osSupplier` and the production UIManager/Registry defaults.
    //
    // The OS comments are read via `mainToolbarRowCommentForTest()`; the exact
    // literal strings are duplicated from `AyuIslandsChromePanel` on purpose so
    // a copy edit in the production source forces an intentional test update.
    //
    // Note: the probe's `diagnosticLogged` AtomicBoolean is per-process; we
    // reset it before each test so the INFO-diagnostic gate doesn't vary with
    // test ordering.
    private fun pinProbeOsForTest(branch: ChromeDecorationsProbe.Os) {
        ChromeDecorationsProbe.osSupplier = { branch }
        ChromeDecorationsProbe.resetDiagnosticLoggedForTests()
    }

    @Test
    fun `main toolbar comment on Linux with GNOME SSD reports window-manager native title bar`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false
        try {
            pinProbeOsForTest(ChromeDecorationsProbe.Os.LINUX)
            val chromePanel = AyuIslandsChromePanel()

            buildPanel(chromePanel)

            assertEquals(
                "Disabled: your window manager paints the native title bar.",
                chromePanel.mainToolbarRowCommentForTest(),
                "Linux+GnomeSsd branch must render the window-manager copy",
            )
        } finally {
            ChromeDecorationsProbe.resetOsSupplierForTests()
        }
    }

    @Test
    fun `main toolbar comment on Windows without custom header reports IDE-side decorations disabled`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false
        try {
            pinProbeOsForTest(ChromeDecorationsProbe.Os.WINDOWS)
            val chromePanel = AyuIslandsChromePanel()

            buildPanel(chromePanel)

            assertEquals(
                "Disabled: your IDE is not using custom window decorations.",
                chromePanel.mainToolbarRowCommentForTest(),
                "Windows+WindowsNoCustomHeader branch must render the IDE-decorations copy",
            )
        } finally {
            ChromeDecorationsProbe.resetOsSupplierForTests()
        }
    }

    @Test
    fun `main toolbar comment on unknown OS falls back to the generic native title bar copy`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false
        try {
            pinProbeOsForTest(ChromeDecorationsProbe.Os.UNKNOWN)
            val chromePanel = AyuIslandsChromePanel()

            buildPanel(chromePanel)

            assertEquals(
                "Disabled: your OS paints the native title bar.",
                chromePanel.mainToolbarRowCommentForTest(),
                "UnknownOs branch must render the generic OS copy",
            )
        } finally {
            ChromeDecorationsProbe.resetOsSupplierForTests()
        }
    }

    @Test
    fun `main toolbar comment on macOS native title bar reports the macOS-specific copy`() {
        // Pins macOS regardless of the CI runner host so Linux / Windows runners still
        // exercise the `NativeMacTitleBar` branch copy — the host-OS-dependent test
        // above only hits this branch when CI happens to be macOS.
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false
        try {
            pinProbeOsForTest(ChromeDecorationsProbe.Os.MAC)
            val chromePanel = AyuIslandsChromePanel()

            buildPanel(chromePanel)

            assertEquals(
                "Disabled: macOS paints the native title bar (IDE 2026.1+ no longer exposes a toggle).",
                chromePanel.mainToolbarRowCommentForTest(),
                "Mac+NativeMacTitleBar branch must render the macOS-specific copy",
            )
        } finally {
            ChromeDecorationsProbe.resetOsSupplierForTests()
        }
    }

    // ── Test 12: persisted expanded state ──────────────────────────────────────

    @Test
    fun `collapsible expanded-state round-trips via AyuIslandsState#chromeTintingGroupExpanded`() {
        state.chromeTintingGroupExpanded = false
        val chromePanel = AyuIslandsChromePanel()

        buildPanel(chromePanel)

        // Initial write wires to stored state — false.
        assertFalse(
            chromePanel.collapsibleExpandedForTest(),
            "Collapsible must open with the persisted state.chromeTintingGroupExpanded value",
        )

        // Simulate a user expand click.
        chromePanel.triggerCollapsibleExpandedForTest(true)

        assertTrue(
            state.chromeTintingGroupExpanded,
            "Expanding the group must persist state.chromeTintingGroupExpanded = true",
        )

        // And collapse.
        chromePanel.triggerCollapsibleExpandedForTest(false)
        assertFalse(
            state.chromeTintingGroupExpanded,
            "Collapsing the group must persist state.chromeTintingGroupExpanded = false",
        )
    }

    // ── Static regression guard for plan 40-11 supersede ──────────────────────
    //
    // After the 2026-04-22 supersede (link retracted, per-OS comment added), the panel
    // source must NOT reference any of the following — they are the removed machinery
    // for the merged-menu offer link that IDE 2026.1+ made dead-letter. A static
    // substring check provides defence-in-depth beyond the `rg` command in SUMMARY.

    @Test
    fun `panel source contains no merged-menu offer residue after supersede`() {
        val source =
            this::class
                .java
                .classLoader
                .getResource("dev/ayuislands/settings/AyuIslandsChromePanel.kt")
                ?.readText()
        // Source is not on the test classpath in this project layout; fall back to
        // reading from the on-disk src tree relative to the project root. This mirrors
        // the pattern used elsewhere (e.g., integration tests that resolve .planning/).
        val onDisk =
            source
                ?: java.io
                    .File("src/main/kotlin/dev/ayuislands/settings/AyuIslandsChromePanel.kt")
                    .takeIf {
                        it.exists()
                    }?.readText()
        assertNotNull(
            onDisk,
            "Could not locate AyuIslandsChromePanel.kt source for static regression guard",
        )
        for (
        forbidden in
        listOf(
            "MERGED_MENU_OFFER_LABEL",
            "MERGED_MENU_CONFIGURABLE_ID",
            "ide.mac.bigSurStyle",
            "Registry.get",
            "Registry.`is`",
            "ApplicationManager.getApplication().restart",
            "ShowSettingsUtil",
            "Settings.KEY",
            "DataManager",
        )
        ) {
            assertFalse(
                onDisk.contains(forbidden),
                "Panel source must not contain '$forbidden' after plan 40-11 supersede",
            )
        }
    }
}
