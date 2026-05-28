package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Behavioral contract for the `codeGlanceProRevertHook` test seam.
 *
 * The hook is a per-thread override (ThreadLocal) so Gradle's parallel JUnit 5
 * workers can't leak a pinned observer from one test into a concurrent
 * sibling's revertAll() — a shared override would produce intermittent
 * "the wrong test's observer fired" false positives. The reset helper must
 * clear the override on the calling thread so the next sibling test on the
 * same worker starts clean. This test exercises that set-then-reset contract
 * directly rather than asserting the declaration's source shape.
 *
 * Mirrors the Pattern I parallel-worker rationale established by the
 * [ChromeDecorationsProbe.osSupplier] OS-detection seam.
 */
class AccentApplicatorTestSeamShapeTest {
    @Test
    fun `resetCodeGlanceProRevertHookForTests clears the per-thread CGP revert override`() {
        // The CGP revert hook is a ThreadLocal so Gradle's parallel JUnit workers
        // can't leak a pinned override into a sibling test's revertAll(). The reset
        // helper must clear it on the calling thread. The ThreadLocal shape itself is
        // compiler-enforced: resetCodeGlanceProRevertHookForTests() calls .remove(),
        // which only exists on ThreadLocal — a @Volatile var downgrade would not compile.
        AccentApplicator.codeGlanceProRevertHook.set { _, _, _ -> }
        assertNotNull(AccentApplicator.codeGlanceProRevertHook.get(), "precondition: override is set")

        AccentApplicator.resetCodeGlanceProRevertHookForTests()

        assertNull(
            AccentApplicator.codeGlanceProRevertHook.get(),
            "reset must clear the per-thread override so the next sibling test on this worker starts clean",
        )
    }
}
