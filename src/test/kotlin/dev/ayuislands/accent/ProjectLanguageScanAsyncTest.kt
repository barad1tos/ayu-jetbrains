package dev.ayuislands.accent

import com.intellij.util.concurrency.AppExecutorUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the dedup gate on [ProjectLanguageScanAsync]. A broken dedup would
 * silently schedule redundant scans — not crash, but would leak CPU on large
 * monorepos when multiple UI components simultaneously ask for the dominant
 * language. Red/green here catches that regression at the unit level.
 */
class ProjectLanguageScanAsyncTest {
    private val capturedRunnables = mutableListOf<Runnable>()

    @BeforeTest
    fun setUp() {
        // Replace the real executor with an inline capture so tests control
        // exactly when scheduled tasks run. Without this the test runs the
        // task immediately (app-pool executes synchronously-ish) and the in-
        // flight gate is released before dedup can be observed.
        mockkStatic(AppExecutorUtil::class)
        val executor = mockk<java.util.concurrent.ExecutorService>()
        every { AppExecutorUtil.getAppExecutorService() } returns executor
        every { executor.execute(any()) } answers { capturedRunnables.add(firstArg()) }
        ProjectLanguageScanAsync.clearForTest()
        capturedRunnables.clear()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProjectLanguageScanAsync.clearForTest()
    }

    @Test
    fun `schedule returns true and dispatches task on first call`() {
        val scheduled = ProjectLanguageScanAsync.schedule("alpha") { /* no-op */ }
        assertTrue(scheduled)
        assertEquals(1, capturedRunnables.size, "Scheduled runnable must be dispatched to the pool")
    }

    @Test
    fun `second schedule for the same key while in-flight returns false and does NOT re-dispatch`() {
        // First schedule: task captured but NOT yet run — in-flight gate holds.
        val first = ProjectLanguageScanAsync.schedule("alpha") { /* no-op */ }
        assertTrue(first)
        assertTrue(ProjectLanguageScanAsync.isInFlight("alpha"))

        // Second schedule for same key while first is still in-flight: dedup kicks in.
        val second = ProjectLanguageScanAsync.schedule("alpha") { /* no-op */ }
        assertFalse(second)
        assertEquals(1, capturedRunnables.size, "Duplicate schedule must not re-dispatch")
    }

    @Test
    fun `schedule for different keys runs both independently`() {
        ProjectLanguageScanAsync.schedule("alpha") { }
        ProjectLanguageScanAsync.schedule("beta") { }
        assertTrue(ProjectLanguageScanAsync.isInFlight("alpha"))
        assertTrue(ProjectLanguageScanAsync.isInFlight("beta"))
        assertEquals(2, capturedRunnables.size)
    }

    @Test
    fun `after task runs the key is no longer in-flight and can be rescheduled`() {
        ProjectLanguageScanAsync.schedule("alpha") { }
        assertTrue(ProjectLanguageScanAsync.isInFlight("alpha"))

        // Simulate the pool running the captured task — the schedule's finally
        // block removes the key from the in-flight set.
        capturedRunnables[0].run()
        assertFalse(ProjectLanguageScanAsync.isInFlight("alpha"))

        val rescheduled = ProjectLanguageScanAsync.schedule("alpha") { }
        assertTrue(rescheduled)
        assertEquals(2, capturedRunnables.size)
    }

    @Test
    fun `task exception does not leak the in-flight gate`() {
        // Regression guard: if the finally removed the key BEFORE the try/catch
        // swallowed the exception, a thrown task would leave the gate stuck forever,
        // blocking every subsequent schedule for that key until IDE restart.
        ProjectLanguageScanAsync.schedule("alpha") { error("simulated task failure") }
        capturedRunnables[0].run() // exception is caught inside the scheduled task

        assertFalse(ProjectLanguageScanAsync.isInFlight("alpha"), "Gate must clear after thrown task")
        val rescheduled = ProjectLanguageScanAsync.schedule("alpha") { }
        assertTrue(rescheduled, "After exception, the key must be reschedulable")
    }

    @Test
    fun `cancellation exception propagates out of the scheduled task`() {
        ProjectLanguageScanAsync.schedule("alpha") {
            throw kotlin.coroutines.cancellation.CancellationException("cancel")
        }
        val runnable = capturedRunnables[0]
        kotlin.test.assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
            runnable.run()
        }
        // Even with the exception, the finally must have cleared the gate.
        assertFalse(ProjectLanguageScanAsync.isInFlight("alpha"))
    }

    @Test
    fun `clearForTest resets every in-flight key`() {
        ProjectLanguageScanAsync.schedule("alpha") { }
        ProjectLanguageScanAsync.schedule("beta") { }
        ProjectLanguageScanAsync.clearForTest()
        assertFalse(ProjectLanguageScanAsync.isInFlight("alpha"))
        assertFalse(ProjectLanguageScanAsync.isInFlight("beta"))
    }
}
