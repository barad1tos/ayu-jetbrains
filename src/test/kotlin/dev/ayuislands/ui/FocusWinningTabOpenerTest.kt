package dev.ayuislands.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import dev.ayuislands.onboarding.OnboardingOrchestrator
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behavioral locks for the focus-race protocol via [FocusWinningTabOpener.openOnEdt]
 * (the EDT hop itself cannot dispatch headlessly — a source-level guard pins it
 * instead, mirroring the `AyuIslandsStartupActivityTest` convention).
 */
class FocusWinningTabOpenerTest {
    private val log = mockk<Logger>(relaxed = true)

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // ------------------------------------------------------------------
    // Focus check
    // ------------------------------------------------------------------

    @Test
    fun `auto trigger defers when a different project frame has focus`() {
        val gate = SessionOneShot()
        val target = project("alpha")
        focusOn(project("beta"))
        var opened = false
        var succeeded = false

        opener(gate).openOnEdt(
            target,
            bypassFocus = false,
            bypassGate = false,
            onSuccess = { succeeded = true },
        ) { opened = true }

        assertFalse(opened, "non-focused project must not open the tab")
        assertFalse(succeeded, "deferring must not mark the feature as shown")
        assertTrue(gate.tryAcquire(), "deferring must not consume the session claim")
    }

    @Test
    fun `focused project opens the tab and runs the success callback`() {
        val gate = SessionOneShot()
        val target = project("alpha")
        focusOn(target)
        var opened = false
        var succeeded = false

        opener(gate).openOnEdt(
            target,
            bypassFocus = false,
            bypassGate = false,
            onSuccess = { succeeded = true },
        ) { opened = true }

        assertTrue(opened, "focused project must open the tab")
        assertTrue(succeeded, "clean open must run the success callback")
        assertFalse(gate.tryAcquire(), "successful open must keep the session claim")
    }

    // ------------------------------------------------------------------
    // Cold-start CAS fallback
    // ------------------------------------------------------------------

    @Test
    fun `cold start with no focused frame lets exactly one project win the claim`() {
        // Cold start / IDE minimized: `lastFocusedFrame` is null, so every
        // window passes the focus check and the CAS decides — first caller
        // wins, the rest bail without opening.
        val gate = SessionOneShot()
        focusOn(null)
        val openedIn = mutableListOf<String>()
        val sharedOpener = opener(gate)

        sharedOpener.openOnEdt(
            project("alpha"),
            bypassFocus = false,
            bypassGate = false,
            onSuccess = {},
        ) { openedIn += it.name }
        sharedOpener.openOnEdt(
            project("beta"),
            bypassFocus = false,
            bypassGate = false,
            onSuccess = {},
        ) { openedIn += it.name }

        assertEquals(listOf("alpha"), openedIn, "first caller wins the CAS; second must bail")
    }

    @Test
    fun `auto trigger bails when the session claim is already taken`() {
        val gate = SessionOneShot()
        assertTrue(gate.tryAcquire(), "pre-claim the gate to simulate a winning sibling window")
        val target = project("alpha")
        focusOn(target)
        var opened = false
        var succeeded = false

        opener(gate).openOnEdt(
            target,
            bypassFocus = false,
            bypassGate = false,
            onSuccess = { succeeded = true },
        ) { opened = true }

        assertFalse(opened, "claim-lost window must not open a duplicate tab")
        assertFalse(succeeded)
    }

    // ------------------------------------------------------------------
    // Release-on-failure
    // ------------------------------------------------------------------

    @Test
    fun `failed open releases the claim so a later trigger can retry`() {
        val gate = SessionOneShot()
        val target = project("alpha")
        focusOn(target)
        var succeeded = false

        opener(gate).openOnEdt(
            target,
            bypassFocus = false,
            bypassGate = false,
            onSuccess = { succeeded = true },
        ) { throw IllegalStateException("editor subsystem not ready") }

        assertFalse(succeeded, "failed open must not mark the feature as shown")
        assertTrue(
            gate.tryAcquire(),
            "failed open must release the claim — otherwise one failure deadlocks the JVM session",
        )
    }

    @Test
    fun `onboarding wizard claim is released when the wizard open fails`() {
        // Latent-bug regression lock: before the shared opener, the license
        // path's copy of this protocol claimed the onboarding slot via
        // `tryPick()` and never released it when `openFile` threw — one failed
        // open permanently blocked the wizard for the whole JVM session.
        // This exercises the exact opener configuration wired in
        // `StartupLicenseHandler` against the real `OnboardingOrchestrator`
        // gate (global state — reset in finally).
        try {
            val target = project("alpha")
            focusOn(target)
            val wizardOpener =
                FocusWinningTabOpener(
                    gate = OnboardingOrchestrator.gate,
                    log = log,
                    logPrefix = "Ayu onboarding",
                    subject = "wizard",
                )

            wizardOpener.openOnEdt(
                target,
                bypassFocus = false,
                bypassGate = false,
                onSuccess = {},
            ) { throw IllegalStateException("openFile failed") }

            assertTrue(
                OnboardingOrchestrator.gate.tryAcquire(),
                "a failed wizard open must release the onboarding claim so a later window can retry",
            )
        } finally {
            OnboardingOrchestrator.gate.resetForTesting()
        }
    }

    // ------------------------------------------------------------------
    // Manual bypass
    // ------------------------------------------------------------------

    @Test
    fun `manual trigger bypasses both the focus check and the gate`() {
        val gate = SessionOneShot()
        assertTrue(gate.tryAcquire(), "pre-claim the gate to prove manual bypasses it")
        val target = project("alpha")
        focusOn(project("beta"))
        var opened = false
        var succeeded = false

        opener(gate).openOnEdt(
            target,
            bypassFocus = true,
            bypassGate = true,
            onSuccess = { succeeded = true },
        ) { opened = true }

        assertTrue(opened, "manual open must proceed even when unfocused and already claimed")
        assertTrue(succeeded)
    }

    @Test
    fun `manual failed open does not release another window's auto-trigger claim`() {
        // A bypassed gate is never claimed by the manual path, so its failure
        // handler must not release a claim that belongs to a sibling window.
        val gate = SessionOneShot()
        assertTrue(gate.tryAcquire(), "pre-claim the gate on behalf of another window")
        val target = project("alpha")
        focusOn(target)

        opener(gate).openOnEdt(
            target,
            bypassFocus = true,
            bypassGate = true,
            onSuccess = {},
        ) { throw IllegalStateException("boom") }

        assertFalse(gate.tryAcquire(), "the sibling window's claim must stay held")
    }

    // ------------------------------------------------------------------
    // Lifecycle guard
    // ------------------------------------------------------------------

    @Test
    fun `disposed project is a no-op and leaves the gate free`() {
        val gate = SessionOneShot()
        val target = mockk<Project> { every { isDisposed } returns true }
        var opened = false

        opener(gate).openOnEdt(
            target,
            bypassFocus = false,
            bypassGate = false,
            onSuccess = {},
        ) { opened = true }

        assertFalse(opened, "disposed project must not open anything")
        assertTrue(gate.tryAcquire(), "disposed bail must not consume the claim")
    }

    // ------------------------------------------------------------------
    // Source-level guards (EDT dispatch + launcher wiring)
    // ------------------------------------------------------------------

    @Test
    fun `open hops to EDT with nonModal modality around the protocol body`() {
        // The invariant is source-level: dispatching through `Dispatchers.EDT`
        // needs a live platform application unavailable in a plain unit test.
        // Enforce the wrap statically so a future refactor cannot silently
        // unwrap it (or drop the modality element) without this test failing.
        val source = readSource("src/main/kotlin/dev/ayuislands/ui/FocusWinningTabOpener.kt")
        val edtHop =
            Regex(
                """withContext\(Dispatchers\.EDT \+ ModalityState\.nonModal\(\)\.asContextElement\(\)\) \{\s*""" +
                    """openOnEdt\(""",
            )
        assertTrue(
            edtHop.containsMatchIn(source),
            "open() must run openOnEdt inside " +
                "withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement())",
        )
    }

    @Test
    fun `whats new launcher routes tab opens through the shared opener`() {
        val source = readSource("src/main/kotlin/dev/ayuislands/whatsnew/WhatsNewLauncher.kt")
        assertTrue(
            source.contains("FocusWinningTabOpener("),
            "WhatsNewLauncher must delegate the focus-race protocol to FocusWinningTabOpener",
        )
        assertTrue(
            source.contains("gate = WhatsNewOrchestrator.gate"),
            "WhatsNewLauncher's opener must claim through the What's New gate",
        )
    }

    @Test
    fun `license handler routes wizard opens through the shared opener with the onboarding gate`() {
        // Wiring half of the latent-bug lock: the behavioral half above proves
        // the opener releases on failure; this half proves the license path
        // actually goes through the opener instead of a local copy without
        // release-on-failure.
        val source = readSource("src/main/kotlin/dev/ayuislands/StartupLicenseHandler.kt")
        assertTrue(
            source.contains("FocusWinningTabOpener("),
            "StartupLicenseHandler must delegate the focus-race protocol to FocusWinningTabOpener",
        )
        assertTrue(
            source.contains("gate = OnboardingOrchestrator.gate"),
            "StartupLicenseHandler's opener must claim through the onboarding gate",
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun opener(gate: SessionOneShot) =
        FocusWinningTabOpener(
            gate = gate,
            log = log,
            logPrefix = "Ayu test",
            subject = "tab",
        )

    private fun project(named: String): Project =
        mockk {
            every { isDisposed } returns false
            every { name } returns named
        }

    /** Stubs the global focus lookup; `null` simulates a cold start with no focused frame. */
    private fun focusOn(focused: Project?) {
        val frame = focused?.let { active -> mockk<IdeFrame> { every { project } returns active } }
        val focusManager = mockk<IdeFocusManager> { every { lastFocusedFrame } returns frame }
        mockkStatic(IdeFocusManager::class)
        every { IdeFocusManager.getGlobalInstance() } returns focusManager
    }

    private fun readSource(path: String): String {
        val source = File(path).takeIf { it.exists() }?.readText()
        assertNotNull(source, "Could not locate $path for source-level guard")
        return source
    }
}
