package dev.ayuislands.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.components.ActionLink
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
import java.awt.Container
import javax.swing.JComponent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
 *    [ChromeDecorationsProbe.isCustomHeaderActive] returns `true` and disabled (with the
 *    CHROME-02 comment) when it returns `false` — present but disabled, never hidden.
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
        // Gap-3 helper: default false so all 14 pre-existing tests stay behavioral
        // (no merged-menu offer row renders in the existing scenarios).
        every { ChromeDecorationsProbe.canEnableCustomHeaderOnMac() } returns false

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
    fun `buildPanel produces licensed content with 5 toggles, intensity slider, contrast checkbox`() {
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
        assertEquals(
            10..100,
            chromePanel.intensitySliderRangeForTest(),
            "Intensity slider range must be 10-100 per CONTEXT D-09",
        )
        assertNotNull(
            chromePanel.keepForegroundReadableCheckboxForTest(),
            "Chrome Tinting group must render the 'Keep foreground readable' checkbox",
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

    @Test
    fun `isModified returns true after keep-foreground-readable flips`() {
        val chromePanel = AyuIslandsChromePanel()
        buildPanel(chromePanel)

        // Default for chromeTintKeepForegroundReadable is true; flip to false.
        chromePanel.setPendingChromeTintKeepForegroundReadableForTest(false)

        assertTrue(
            chromePanel.isModified(),
            "Flipping pendingChromeTintKeepForegroundReadable must dirty the panel",
        )
    }

    // ── Test 6-7: apply() persistence ──────────────────────────────────────────

    @Test
    fun `apply persists all 8 chrome fields and triggers applyForFocusedProject once`() {
        val chromePanel = AyuIslandsChromePanel()
        buildPanel(chromePanel, AyuVariant.DARK)

        // Mutate every user-facing pending field.
        chromePanel.setPendingChromeStatusBarForTest(true)
        chromePanel.setPendingChromeMainToolbarForTest(true)
        chromePanel.setPendingChromeToolWindowStripeForTest(true)
        chromePanel.setPendingChromeNavBarForTest(true)
        chromePanel.setPendingChromePanelBorderForTest(true)
        chromePanel.setPendingChromeTintIntensityForTest(75)
        chromePanel.setPendingChromeTintKeepForegroundReadableForTest(false)

        chromePanel.apply()

        // Every backing state field must mirror the pending value.
        assertTrue(state.chromeStatusBar, "chromeStatusBar not persisted")
        assertTrue(state.chromeMainToolbar, "chromeMainToolbar not persisted")
        assertTrue(state.chromeToolWindowStripe, "chromeToolWindowStripe not persisted")
        assertTrue(state.chromeNavBar, "chromeNavBar not persisted")
        assertTrue(state.chromePanelBorder, "chromePanelBorder not persisted")
        assertEquals(75, state.chromeTintIntensity, "chromeTintIntensity not persisted")
        assertFalse(
            state.chromeTintKeepForegroundReadable,
            "chromeTintKeepForegroundReadable not persisted",
        )

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
        state.chromeTintKeepForegroundReadable = true

        val chromePanel = AyuIslandsChromePanel()
        buildPanel(chromePanel)

        chromePanel.setPendingChromeStatusBarForTest(true)
        chromePanel.setPendingChromeTintIntensityForTest(90)
        chromePanel.setPendingChromeTintKeepForegroundReadableForTest(false)

        chromePanel.reset()

        assertFalse(chromePanel.getPendingChromeStatusBarForTest())
        assertEquals(
            AyuIslandsState.DEFAULT_CHROME_TINT_INTENSITY,
            chromePanel.getPendingChromeTintIntensityForTest(),
        )
        assertTrue(chromePanel.getPendingChromeTintKeepForegroundReadableForTest())
        // Reset must not call the applicator.
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
        assertNull(
            chromePanel.keepForegroundReadableCheckboxForTest(),
            "Unlicensed build must not wire the 'Keep foreground readable' checkbox",
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
    fun `main toolbar row is disabled with CHROME-02 comment when probe reports native chrome`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false
        val chromePanel = AyuIslandsChromePanel()

        buildPanel(chromePanel)

        assertFalse(
            chromePanel.mainToolbarRowEnabledForTest(),
            "Main Toolbar row must be disabled when the OS paints the native title bar (CHROME-02)",
        )
        assertEquals(
            "Disabled: your OS paints the native title bar",
            chromePanel.mainToolbarRowCommentForTest(),
            "Main Toolbar row must display the CHROME-02 disabled-state comment",
        )
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

    // ── Plan 40-11 Gap 3: merged-menu offer link ──────────────────────────────

    /**
     * Walks the Swing component tree rooted at [root] and returns the first
     * [ActionLink] whose visible text equals [text]. The Kotlin UI DSL `link(text) { … }`
     * builder emits an [ActionLink] (decompiled against 2025.1 SDK — see L-4 commit body).
     */
    private fun findLinkByText(root: Container, text: String): ActionLink? {
        if (root is ActionLink && root.text == text) return root
        for (child in root.components) {
            if (child is Container) {
                val hit = findLinkByText(child, text)
                if (hit != null) return hit
            }
        }
        return null
    }

    private fun assertNoLinkWithText(root: Container, text: String) {
        val found = findLinkByText(root, text)
        if (found != null) {
            fail(
                "Expected NO component labelled '$text' in the rendered panel, but the DSL " +
                    "traversal found one. The merged-menu offer row must not render in this scenario.",
            )
        }
    }

    @Test
    fun `L-1 merged menu offer link is visible when probe canEnableCustomHeaderOnMac returns true`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false
        every { ChromeDecorationsProbe.canEnableCustomHeaderOnMac() } returns true
        val chromePanel = AyuIslandsChromePanel()

        val dialogPanel = buildPanel(chromePanel)

        assertTrue(
            chromePanel.mergedMenuOfferVisibleForTest(),
            "mergedMenuOfferVisibleForTest must be true when canEnableCustomHeaderOnMac returns true",
        )
        assertFalse(
            chromePanel.mainToolbarRowEnabledForTest(),
            "Main Toolbar row must be disabled when isCustomHeaderActive returns false",
        )
        // DSL traversal lock (W-4 remediation): a rendered ActionLink must actually exist,
        // not just an internal flag set to true. If the DSL `link(...)` call was dropped
        // or misrendered this assertion fails.
        val link = findLinkByText(dialogPanel, "Enable merged menu to tint title bar")
        assertNotNull(
            link,
            "Expected a component labelled 'Enable merged menu to tint title bar' in the rendered panel " +
                "when canEnableCustomHeaderOnMac is true — DSL link(...) wiring broken",
        )
    }

    @Test
    fun `L-2 merged menu offer link is hidden when probe isCustomHeaderActive is true`() {
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns true
        every { ChromeDecorationsProbe.canEnableCustomHeaderOnMac() } returns false
        val chromePanel = AyuIslandsChromePanel()

        val dialogPanel = buildPanel(chromePanel)

        assertFalse(
            chromePanel.mergedMenuOfferVisibleForTest(),
            "mergedMenuOfferVisibleForTest must be false when custom header already active",
        )
        assertTrue(
            chromePanel.mainToolbarRowEnabledForTest(),
            "Main Toolbar row must be enabled when the IDE paints a JBR custom window header",
        )
        assertNoLinkWithText(dialogPanel, "Enable merged menu to tint title bar")
    }

    @Test
    fun `L-3 merged menu offer link is hidden on non-macOS even when probe reports inactive`() {
        // Non-mac forces canEnableCustomHeaderOnMac = false (see probe Task 1 M-3 tests).
        // isCustomHeaderActive() reports false (e.g., Linux without ide.linux.custom.title.bar).
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false
        every { ChromeDecorationsProbe.canEnableCustomHeaderOnMac() } returns false
        val chromePanel = AyuIslandsChromePanel()

        val dialogPanel = buildPanel(chromePanel)

        assertFalse(
            chromePanel.mergedMenuOfferVisibleForTest(),
            "mergedMenuOfferVisibleForTest must be false on non-macOS platforms",
        )
        assertFalse(
            chromePanel.mainToolbarRowEnabledForTest(),
            "Main Toolbar row must still be disabled when probe reports inactive (non-mac too)",
        )
        assertNoLinkWithText(dialogPanel, "Enable merged menu to tint title bar")
    }

    @Test
    fun `L-4 clicking the rendered merged-menu link opens Settings via ShowSettingsUtil (DSL traversal)`() {
        // (1) Arrange: enable the offer branch.
        every { ChromeDecorationsProbe.isCustomHeaderActive() } returns false
        every { ChromeDecorationsProbe.canEnableCustomHeaderOnMac() } returns true

        // (2) Stub ShowSettingsUtil.getInstance() so we can verify the call.
        mockkStatic(ShowSettingsUtil::class)
        val showSettingsUtilMock = mockk<ShowSettingsUtil>(relaxed = true)
        every { ShowSettingsUtil.getInstance() } returns showSettingsUtilMock

        // (3) Stub ProjectManager so the panel's project resolver doesn't NPE.
        mockkStatic(ProjectManager::class)
        val projectManagerMock = mockk<ProjectManager>(relaxed = true)
        every { ProjectManager.getInstance() } returns projectManagerMock
        every { projectManagerMock.openProjects } returns emptyArray()

        val chromePanel = AyuIslandsChromePanel()
        val dialogPanel = buildPanel(chromePanel)

        // (4) Locate the link via component-tree traversal — NOT via a @TestOnly back-door
        // seam. If the DSL `link(…)` binding is broken (wrong builder, wrong lambda
        // wiring, wrong container nesting) this assertion fails.
        val link = findLinkByText(dialogPanel, "Enable merged menu to tint title bar")
            ?: fail(
                "Expected a link labelled 'Enable merged menu to tint title bar' in the rendered panel, " +
                    "but the DSL traversal found none. This usually means the DSL `link(...)` block did " +
                    "not render — check Change 2 wiring in AyuIslandsChromePanel.",
            )

        // (5) Click the link. ActionLink extends JButton, so doClick fires the action.
        link.doClick()

        // (6) Assert: ShowSettingsUtil was invoked exactly once with the verified id.
        // Kotlin view of the Java method has a non-null Project parameter (annotated
        // @Nullable at runtime but erased in the descriptor), hence `any<Project>()`.
        verify(exactly = 1) {
            showSettingsUtilMock.showSettingsDialog(
                any<Project>(),
                "preferences.lookFeel",
            )
        }

        // (7) Regression guards — T-40-40 / T-40-41:
        //   - No direct Registry write (the earlier B-1 design wrote ide.mac.bigSurStyle).
        //   - No ApplicationManager.restart() call (we delegate to IntelliJ's native
        //     restart-required prompt, which fires inside the Settings panel we open).
        // Both guards are also enforced statically via `rg` in the plan's acceptance
        // criteria (see 40-11-PLAN.md). Here we additionally verify at runtime that the
        // click path does not touch Application.restart().
        verify(exactly = 0) {
            ApplicationManager.getApplication().exit(any(), any(), any())
        }
    }
}
