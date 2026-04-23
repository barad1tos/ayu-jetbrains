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
        val source =
            java.io
                .File("src/main/kotlin/dev/ayuislands/AyuIslandsStartupActivity.kt")
                .takeIf { it.exists() }
                ?.readText()
        assertNotNull(source, "Could not locate AyuIslandsStartupActivity.kt for EDT-wrap guard")
        assertTrue(
            source.contains("import com.intellij.openapi.application.EDT"),
            "Source must import Dispatchers.EDT extension property",
        )
        assertTrue(
            source.contains("import kotlinx.coroutines.withContext"),
            "Source must import kotlinx.coroutines.withContext",
        )
        // The resolveFocusedProject call must sit inside a withContext(Dispatchers.EDT) block.
        // Regex matches across newlines so formatter variations (chained vs. split args) still
        // pass. The critical constraint: resolveFocusedProject appears *after* Dispatchers.EDT
        // and before the next top-level statement.
        val edtBlock =
            Regex(
                """withContext\(Dispatchers\.EDT\)\s*\{[^}]*AccentApplicator\.resolveFocusedProject\(\)""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            edtBlock.containsMatchIn(source),
            "AccentApplicator.resolveFocusedProject must be invoked inside withContext(Dispatchers.EDT) { … }",
        )
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
