package dev.ayuislands

import com.intellij.testFramework.LoggedErrorProcessor
import java.util.EnumSet
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @AfterTest
    fun tearDown() {
        // Ensure the test logger doesn't leak between tests.
    }

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
    fun `runStep swallows non-VM Error so optional-plugin LinkageError doesn't abort startup`() {
        // LinkageError / NoClassDefFoundError typically mean an optional plugin dependency
        // didn't load. The plugin's own startup should continue, not abort — otherwise a
        // missing CodeGlance Pro etc. would silently kill remaining steps.
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
