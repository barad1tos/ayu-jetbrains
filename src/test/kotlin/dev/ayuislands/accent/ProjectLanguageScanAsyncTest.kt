package dev.ayuislands.accent

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the dedup gate on [ProjectLanguageScanAsync]. A broken dedup would
 * silently schedule redundant scans — not crash, but would leak CPU on large
 * monorepos when multiple UI components simultaneously ask for the dominant
 * language. Red/green here catches that regression at the unit level.
 */
class ProjectLanguageScanAsyncTest {
    private val capturedRunnables = mutableListOf<Runnable>()
    private lateinit var project: Project

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
        mockkStatic(ProgressManager::class)
        val progressManager = mockk<ProgressManager>()
        every { ProgressManager.getInstance() } returns progressManager
        every { progressManager.runProcess(any<Runnable>(), any<ProgressIndicator>()) } answers {
            firstArg<Runnable>().run()
        }
        project = mockk(relaxed = true)
        every { project.isDisposed } returns false
        ProjectLanguageScanAsync.clearForTest()
        capturedRunnables.clear()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProjectLanguageScanAsync.clearForTest()
    }

    @Test
    fun `scanner sampling skips non-cancellation Error from file type lookup`() {
        val file = mockk<VirtualFile>()
        every { file.path } returns "/tmp/Broken.kt"
        every { file.fileType } throws NoClassDefFoundError("language plugin unloaded")

        assertNull(ProjectLanguageScanner.sampleLanguageWeightForTest(file))
    }

    @Test
    fun `scanner sampling propagates platform cancellation from file type lookup`() {
        val file = mockk<VirtualFile>()
        every { file.path } returns "/tmp/Cancel.kt"
        every { file.fileType } throws ProcessCanceledException()

        kotlin.test.assertFailsWith<ProcessCanceledException> {
            ProjectLanguageScanner.sampleLanguageWeightForTest(file)
        }
    }

    @Test
    fun `scanner sampling propagates kotlin cancellation from file type lookup`() {
        val file = mockk<VirtualFile>()
        every { file.path } returns "/tmp/Cancel.kt"
        every { file.fileType } throws kotlin.coroutines.cancellation.CancellationException("cancel")

        kotlin.test.assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
            ProjectLanguageScanner.sampleLanguageWeightForTest(file)
        }
    }

    @Test
    fun `schedule returns true and dispatches task on first call`() {
        val scheduled =
            ProjectLanguageScanAsync.schedule(project, "alpha") {
                ProjectLanguageScanAsync.ScanResult.Completed
            }
        assertTrue(scheduled)
        assertEquals(1, capturedRunnables.size, "Scheduled runnable must be dispatched to the pool")
    }

    @Test
    fun `second schedule for the same key while in-flight returns false and does NOT re-dispatch`() {
        // First schedule: task captured but NOT yet run — in-flight gate holds.
        val first =
            ProjectLanguageScanAsync.schedule(project, "alpha") {
                ProjectLanguageScanAsync.ScanResult.Completed
            }
        assertTrue(first)
        assertTrue(ProjectLanguageScanAsync.isInFlight("alpha"))

        // Second schedule for same key while first is still in-flight: dedup kicks in.
        val second =
            ProjectLanguageScanAsync.schedule(project, "alpha") {
                ProjectLanguageScanAsync.ScanResult.Completed
            }
        assertFalse(second)
        assertEquals(1, capturedRunnables.size, "Duplicate schedule must not re-dispatch")
    }

    @Test
    fun `schedule for different keys runs both independently`() {
        ProjectLanguageScanAsync.schedule(project, "alpha") {
            ProjectLanguageScanAsync.ScanResult.Completed
        }
        ProjectLanguageScanAsync.schedule(project, "beta") {
            ProjectLanguageScanAsync.ScanResult.Completed
        }
        assertTrue(ProjectLanguageScanAsync.isInFlight("alpha"))
        assertTrue(ProjectLanguageScanAsync.isInFlight("beta"))
        assertEquals(2, capturedRunnables.size)
    }

    @Test
    fun `after task runs the key is no longer in-flight and can be rescheduled`() {
        ProjectLanguageScanAsync.schedule(project, "alpha") {
            ProjectLanguageScanAsync.ScanResult.Completed
        }
        assertTrue(ProjectLanguageScanAsync.isInFlight("alpha"))

        // Simulate the pool running the captured task — the schedule's finally
        // block removes the key from the in-flight set.
        capturedRunnables[0].run()
        assertFalse(ProjectLanguageScanAsync.isInFlight("alpha"))

        val rescheduled =
            ProjectLanguageScanAsync.schedule(project, "alpha") {
                ProjectLanguageScanAsync.ScanResult.Completed
            }
        assertTrue(rescheduled)
        assertEquals(2, capturedRunnables.size)
    }

    @Test
    fun `task exception does not leak the in-flight gate`() {
        // Regression guard: if the finally removed the key BEFORE the try/catch
        // swallowed the exception, a thrown task would leave the gate stuck forever,
        // blocking every subsequent schedule for that key until IDE restart.
        ProjectLanguageScanAsync.schedule(project, "alpha") { error("simulated task failure") }
        capturedRunnables[0].run() // exception is caught inside the scheduled task

        assertFalse(ProjectLanguageScanAsync.isInFlight("alpha"), "Gate must clear after thrown task")
        val rescheduled =
            ProjectLanguageScanAsync.schedule(project, "alpha") {
                ProjectLanguageScanAsync.ScanResult.Completed
            }
        assertTrue(rescheduled, "After exception, the key must be reschedulable")
    }

    @Test
    fun `disposed project before task body does not run task and clears the in-flight gate`() {
        every { project.isDisposed } returns true
        var ranTask = false
        ProjectLanguageScanAsync.schedule(project, "alpha") {
            ranTask = true
            ProjectLanguageScanAsync.ScanResult.Completed
        }

        capturedRunnables[0].run()

        assertFalse(ranTask, "Disposed project must skip the task body")
        assertFalse(ProjectLanguageScanAsync.isInFlight("alpha"), "Gate must clear after disposal skip")
    }

    @Test
    fun `unavailable task result does not leak the in-flight gate`() {
        ProjectLanguageScanAsync.schedule(project, "alpha") {
            ProjectLanguageScanAsync.ScanResult.Unavailable
        }

        capturedRunnables[0].run()

        assertFalse(ProjectLanguageScanAsync.isInFlight("alpha"), "Gate must clear after unavailable scan")
    }

    @Test
    fun `process cancellation does not leak the in-flight gate`() {
        ProjectLanguageScanAsync.schedule(project, "alpha") {
            throw ProcessCanceledException()
        }

        capturedRunnables[0].run()

        assertFalse(ProjectLanguageScanAsync.isInFlight("alpha"), "Gate must clear after platform cancellation")
    }

    @Test
    fun `cancellation exception propagates out of the scheduled task`() {
        ProjectLanguageScanAsync.schedule(project, "alpha") {
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
        ProjectLanguageScanAsync.schedule(project, "alpha") {
            ProjectLanguageScanAsync.ScanResult.Completed
        }
        ProjectLanguageScanAsync.schedule(project, "beta") {
            ProjectLanguageScanAsync.ScanResult.Completed
        }
        ProjectLanguageScanAsync.clearForTest()
        assertFalse(ProjectLanguageScanAsync.isInFlight("alpha"))
        assertFalse(ProjectLanguageScanAsync.isInFlight("beta"))
    }
}
