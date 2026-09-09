package dev.ayuislands.accent

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AccentApplyFailureTest {
    @Test
    fun `success does not run rollback`() {
        var rollbackCalls = 0
        assertNull(
            captureAccentFailure(
                AccentApplyStep.ApplyElements,
                "apply caret",
                { rollbackCalls++ },
                {},
            ),
        )
        assertEquals(0, rollbackCalls)
    }

    @Test
    fun `failed rollback retains both causes`() {
        val original = IllegalStateException("apply failed")
        val recovery = IllegalStateException("restore failed")
        val captured =
            requireNotNull(
                captureAccentFailure(
                    AccentApplyStep.ApplyElements,
                    "apply caret",
                    { throw recovery },
                    { throw original },
                ),
            )
        assertSame(original, captured.error.cause)
        assertSame(recovery, original.suppressed.single())
    }

    @Test
    fun `cancellation runs rollback and escapes unchanged`() {
        for (cancelled in listOf(ProcessCanceledException(), CancellationException("cancelled"))) {
            var rollbackCalls = 0
            val thrown =
                assertFailsWith<RuntimeException> {
                    captureAccentFailure(
                        AccentApplyStep.ApplyElements,
                        "apply caret",
                        { rollbackCalls++ },
                        { throw cancelled },
                    )
                }
            assertSame(cancelled, thrown)
            assertEquals(1, rollbackCalls)
        }
    }

    @Test
    fun `cancellation during rollback is not demoted to an ordinary failure`() {
        val original = IllegalStateException("apply failed")
        val cancelled = ProcessCanceledException()
        val thrown =
            assertFailsWith<ProcessCanceledException> {
                captureAccentFailure(
                    AccentApplyStep.ApplyElements,
                    "apply caret",
                    { throw cancelled },
                    { throw original },
                )
            }
        assertSame(cancelled, thrown)
        assertSame(original, thrown.suppressed.single())
    }

    @Test
    fun `same exception from apply and rollback does not suppress itself`() {
        val cancelled = ProcessCanceledException()
        assertSame(
            cancelled,
            assertFailsWith<ProcessCanceledException> {
                captureAccentFailure(
                    AccentApplyStep.ApplyElements,
                    "apply caret",
                    { throw cancelled },
                    { throw cancelled },
                )
            },
        )
    }

    @Test
    fun `fatal error escapes without ordinary failure handling`() {
        val fatal = object : VirtualMachineError("fatal") {}
        assertSame(
            fatal,
            assertFailsWith<VirtualMachineError> {
                captureAccentFailure(AccentApplyStep.ApplyElements, "apply caret") { throw fatal }
            },
        )
    }
}
