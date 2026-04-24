package dev.ayuislands

import com.intellij.testFramework.LoggedErrorProcessor
import java.util.EnumSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Locks in the [AyuIslandsStartupActivity.runStep] catch contract:
 *
 *  - [RuntimeException] → logged, swallowed, next call would proceed
 *  - [VirtualMachineError] → logged then rethrown so the JVM crash reporter still gets it
 *  - other [Error] → logged, swallowed (LinkageError / NoClassDefFoundError from optional
 *    plugin deps shouldn't abort plugin startup)
 *
 * The split is non-trivial — a future widening of the Error catch to omit the rethrow
 * (or narrowing it past LinkageError) would silently change startup behavior. These
 * tests freeze the contract.
 */
class AyuIslandsStartupActivityTest {
    private val activity = AyuIslandsStartupActivity()

    // No @AfterTest needed — LoggedErrorProcessor.executeWith restores the default
    // processor at block end, and we don't mockkStatic/mockkObject anything global.

    @Test
    fun `runStep swallows RuntimeException so the next step can run`() {
        val captured = mutableListOf<Pair<String, Throwable?>>()
        val processor = capturingProcessor(captured)

        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            activity.runStepForTest("step-X") {
                throw IllegalArgumentException("boom from step-X")
            }
        }

        assertEquals(1, captured.size)
        assertEquals("License startup step 'step-X' failed", captured.single().first)
    }

    @Test
    fun `runStep rethrows VirtualMachineError so the JVM crash reporter receives it`() {
        // Locks the rethrow: OOM, StackOverflowError, InternalError indicate unrecoverable
        // JVM state and continuing would risk cascading corruption. The test triggers an
        // InternalError (the cheapest VirtualMachineError to construct in a unit test).
        val captured = mutableListOf<Pair<String, Throwable?>>()
        val processor = capturingProcessor(captured)

        assertFailsWith<InternalError> {
            LoggedErrorProcessor.executeWith<Throwable>(processor) {
                activity.runStepForTest("step-vm") {
                    throw InternalError("simulated unrecoverable")
                }
            }
        }

        // Step name was logged before the rethrow.
        assertEquals(1, captured.size)
        assertEquals("License startup step 'step-vm' failed with VM error", captured.single().first)
    }

    @Test
    fun `runStep swallows non-VM Error so LinkageError from one step doesn't abort the rest`() {
        // LinkageError / NoClassDefFoundError typically surface a class-loading issue in
        // an individual step's transitive closure (e.g. a Kotlin stdlib mismatch from a
        // lazily-loaded service). The plugin's remaining steps should continue
        // independently — otherwise one class-load glitch silently kills all subsequent
        // steps.
        val captured = mutableListOf<Pair<String, Throwable?>>()
        val processor = capturingProcessor(captured)

        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            activity.runStepForTest("step-link") {
                throw NoClassDefFoundError("optional plugin missing")
            }
        }

        assertEquals(1, captured.size)
        assertEquals("License startup step 'step-link' failed with Error", captured.single().first)
    }

    @Test
    fun `execute dispatches AccentApplicator resolveFocusedProject via withContext Dispatchers EDT`() {
        // Regression guard for the PR #151 Round 1 fix: AccentApplicator.resolveFocusedProject
        // is @RequiresEdt (IdeFocusManager touches Swing focus state), but ProjectActivity.execute
        // runs on a background coroutine by default. Without a withContext(Dispatchers.EDT) wrap
        // the call throws a threading assertion inside the IDE under fleetMode/assertions.
        //
        // The invariant is source-level — dynamically invoking execute() requires a project
        // fixture, DumbService, LafManager, and a fully wired message bus, none of which are
        // available in a plain unit test. We enforce the wrap statically so a future refactor
        // cannot silently unwrap it without this test failing.
        val source = readStartupActivitySource()
        assertTrue(
            source.contains("import com.intellij.openapi.application.EDT"),
            "Source must import Dispatchers.EDT extension property",
        )
        assertTrue(
            source.contains("import kotlinx.coroutines.withContext"),
            "Source must import kotlinx.coroutines.withContext",
        )
        // The resolveFocusedProject call must sit inside a withContext(Dispatchers.EDT) block.
        // Non-greedy `.*?` with DOT_MATCHES_ALL tolerates nested blocks (disposal bail,
        // runCatchingPreservingCancellation) between the outer withContext brace and the
        // resolveFocusedProject call site, while still anchoring that the call appears
        // after withContext(Dispatchers.EDT) in the text.
        val edtBlock =
            Regex(
                """withContext\(Dispatchers\.EDT\).*?AccentApplicator\.resolveFocusedProject\(\)""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            edtBlock.containsMatchIn(source),
            "AccentApplicator.resolveFocusedProject must be invoked inside withContext(Dispatchers.EDT) { … }",
        )
    }

    @Test
    fun `execute publishes swap-service cache inside the same EDT withContext block`() {
        // Regression guard for the PR #151 Round 2 Fix B-2: ProjectAccentSwapService has an
        // EDT precondition for notifyExternalApply (per AccentApplicator KDoc lines 203-205,
        // the cache write is a bare volatile with no dispatch and must publish in the same
        // ordering as the apply that preceded it).
        //
        // Phase 40 review-loop Round 2 HIGH R2-1 then split install and notifyExternalApply
        // into two runCatchingPreservingCancellation blocks for better error attribution,
        // so the regex tolerates intermediate braces — the only invariant that matters is
        // that all four operations appear in order inside the EDT turn, not that they
        // share a single inner block.
        val source = readStartupActivitySource()
        val edtTriplet =
            Regex(
                """withContext\(Dispatchers\.EDT\).*?AccentApplicator\.resolveFocusedProject\(\)""" +
                    """.*?AccentApplicator\.(apply|applyFromHexString)\(""" +
                    """.*?swapService\.install\(\)""" +
                    """.*?swapService\.notifyExternalApply\(""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            edtTriplet.containsMatchIn(source),
            "resolveFocusedProject → apply → install → notifyExternalApply must all run " +
                "inside the same withContext(Dispatchers.EDT) { … } turn",
        )
    }

    @Test
    fun `startup accent helper emits distinct error messages per failure branch`() {
        // Round 2 HIGH R2-1 regression lock. The Round 1 loop fix split one
        // runCatching into three (apply / install / notifyExternalApply) so
        // triage can tell which half of the startup triplet failed from the
        // log line alone. A maintainer who collapses the three branches back
        // into one message, or who accidentally swaps the strings, would
        // silently break that attribution contract.
        val source = readStartupActivitySource()
        assertTrue(
            source.contains("Startup accent apply failed for project"),
            "Apply branch must have its own distinct ERROR message",
        )
        assertTrue(
            source.contains("Startup accent-swap install failed for project"),
            "Install branch must have its own distinct ERROR message",
        )
        assertTrue(
            source.contains("Startup accent-swap cache publish (notifyExternalApply) failed for"),
            "notifyExternalApply branch must have its own distinct ERROR message",
        )
        // And the old bundled wording must NOT reappear — that's the swap-back regression.
        assertEquals(
            false,
            source.contains("Startup accent-swap install/notify failed"),
            "Old bundled install+notify message must not return",
        )
    }

    @Test
    fun `startup EDT block bails early when project or application is disposed`() {
        // Round 2 HIGH R2-2 regression lock. The disposal bail must be the
        // FIRST statement inside the withContext(Dispatchers.EDT) body so a
        // mid-hop disposal can't reach platform APIs that would rewrap
        // ProcessCanceledException as AlreadyDisposedException (which the
        // coroutine cancellation helper cannot unwrap). Locks both presence
        // AND position of the guard.
        val source = readStartupActivitySource()
        val disposedBail =
            Regex(
                """withContext\(Dispatchers\.EDT\)\s*\{\s*if\s*\(\s*project\.isDisposed\s*\|\|\s*""" +
                    """ApplicationManager\.getApplication\(\)\.isDisposed\s*\)\s*\{\s*return@withContext""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            disposedBail.containsMatchIn(source),
            "Disposal bail must be the first statement inside withContext(Dispatchers.EDT) { … }",
        )
    }

    @Test
    fun `startup projectName is captured before the EDT hop`() {
        // Round 2 HIGH R2-3 regression lock. `projectName` MUST be captured
        // OUTSIDE `withContext(Dispatchers.EDT)` so a mid-hop disposal cannot
        // NPE inside the error logger and swallow the original exception
        // (the MEDIUM-3 failure mode). Tempting to move it inside since
        // projectName is only read there — this guard catches that.
        val source = readStartupActivitySource()
        val beforeEdt =
            Regex(
                """val\s+projectName\s*=\s*(?:try\s*\{|runCatching\s*\{).*?withContext\(Dispatchers\.EDT\)""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            beforeEdt.containsMatchIn(source),
            "projectName must be captured before the withContext(Dispatchers.EDT) block",
        )
    }

    @Test
    fun `startup projectName capture uses disposed fallback when project name access throws`() {
        // Round 2 S-3 lock for MEDIUM-3 fix. The projectName capture uses a
        // try/catch with RuntimeException (narrowed from runCatching-Throwable
        // in Round 2 MEDIUM R2-1) and falls back to the "<disposed>" literal
        // when project.name access blows up. Lock both the narrowed catch and
        // the literal fallback.
        val source = readStartupActivitySource()
        assertTrue(
            source.contains("\"<disposed>\""),
            "projectName fallback must use the <disposed> sentinel string literal",
        )
        val narrowedCatch =
            Regex(
                """val\s+projectName\s*=\s*try\s*\{\s*project\.name\s*\}""" +
                    """\s*catch\s*\(\s*exception:\s*RuntimeException\s*\)""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            narrowedCatch.containsMatchIn(source),
            "projectName capture must use try/catch(RuntimeException), not runCatching (Throwable)",
        )
    }

    @Test
    fun `install and notifyExternalApply are structurally separated by a short-circuit bail`() {
        // Round 3 G1 regression lock. The R2-1 fix depends on install() and
        // notifyExternalApply() sitting in SEPARATE runCatchingPreservingCancellation
        // blocks with an `if (!installed) return@withContext` short-circuit
        // between them. A "simplify" refactor that collapses them back into
        // one block would keep both error literals (as dead code inside one
        // runCatching body) while silently restoring the exact bug R2-1 fixed.
        // Lock the structural pattern.
        val source = readStartupActivitySource()
        val structural =
            Regex(
                """runCatchingPreservingCancellation\s*\{\s*swapService\.install\(\)""" +
                    """.*?\}\.onFailure\s*\{.*?\}\.isSuccess""" +
                    """.*?if\s*\(\s*!\s*installed\s*\)\s*return@withContext""" +
                    """.*?runCatchingPreservingCancellation\s*\{\s*swapService\.notifyExternalApply\(""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            structural.containsMatchIn(source),
            "install() and notifyExternalApply() must remain in SEPARATE runCatching " +
                "blocks with an `if (!installed) return@withContext` short-circuit between them",
        )
    }

    @Test
    fun `startup helper WARNs on Success-null hex as a defensive branch for future refactors`() {
        // Round 3 G2 regression lock. MEDIUM R2-2 added a defensive branch:
        // if applyOutcome.isSuccess but hex is null (not reachable today but
        // reachable after any refactor that makes resolved nullable), WARN.
        // The branch has NO behavioural test because the condition is not
        // reachable today, so its only guard is a source-level lock on both
        // the WARN literal AND the structural `applyOutcome.isSuccess` check
        // inside the `hex == null` branch.
        val source = readStartupActivitySource()
        assertTrue(
            source.contains("Startup accent apply returned a null hex unexpectedly"),
            "WARN-on-null-hex defensive branch must remain against future nullable-refactor regressions",
        )
        val isSuccessInNullBranch =
            Regex(
                """if\s*\(\s*hex\s*==\s*null\s*\)\s*\{\s*[^}]*if\s*\(\s*applyOutcome\.isSuccess\s*\)""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            isSuccessInNullBranch.containsMatchIn(source),
            "isSuccess check must sit INSIDE the `if (hex == null)` block — " +
                "that is the Success(null) discriminator; removing it collapses the MEDIUM R2-2 fix",
        )
    }

    @Test
    fun `execute does not claim AccentApplicator apply self-dispatches`() {
        // Regression guard for the PR #151 Round 2 Fix B-2: the old comment above the
        // withContext block asserted "AccentApplicator.apply self-dispatches internally",
        // which contradicts [AccentApplicator.apply]'s @RequiresEdt annotation and its
        // KDoc explicitly stating the helper does NOT self-dispatch pre-apply steps.
        // A future maintainer reading the false comment would be tempted to unwrap the
        // withContext — exactly the regression this guard prevents.
        val source = readStartupActivitySource()
        assertEquals(
            false,
            source.contains("apply self-dispatches internally"),
            "Stale/false 'apply self-dispatches internally' comment must not reappear — " +
                "AccentApplicator.apply is @RequiresEdt and requires callers to already be on EDT",
        )
    }

    // Phase 40.2 T-2: lock the full 5-step order inside runStartupAccentOnEdt —
    // resolveFocusedProject → AccentResolver.resolve → AccentApplicator.apply →
    // swapService.install → swapService.notifyExternalApply. The existing
    // "same EDT withContext block" test covers the last four; this one adds
    // AccentResolver.resolve between them so a future refactor that drops the
    // resolver step (and hands a stale accent hex directly to apply) surfaces
    // here.
    @Test
    fun `runStartupAccentOnEdt invokes resolveFocusedProject, resolve, apply, install, notifyExternalApply in order`() {
        val source = readStartupActivitySource()
        val fullOrder =
            Regex(
                """AccentApplicator\.resolveFocusedProject\(\)""" +
                    """.*?AccentResolver\.resolve\(""" +
                    """.*?AccentApplicator\.(apply|applyFromHexString)\(""" +
                    """.*?swapService\.install\(\)""" +
                    """.*?swapService\.notifyExternalApply\(""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            fullOrder.containsMatchIn(source),
            "runStartupAccentOnEdt must invoke resolveFocusedProject → AccentResolver.resolve → " +
                "AccentApplicator.apply → swapService.install → swapService.notifyExternalApply in order",
        )
    }

    private fun readStartupActivitySource(): String {
        val source =
            java.io
                .File("src/main/kotlin/dev/ayuislands/AyuIslandsStartupActivity.kt")
                .takeIf { it.exists() }
                ?.readText()
        assertNotNull(source, "Could not locate AyuIslandsStartupActivity.kt for source-level guard")
        return source
    }

    private fun capturingProcessor(captured: MutableList<Pair<String, Throwable?>>) =
        object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<out String>,
                throwable: Throwable?,
            ): Set<Action> {
                captured += message to throwable
                return EnumSet.noneOf(Action::class.java)
            }
        }
}
