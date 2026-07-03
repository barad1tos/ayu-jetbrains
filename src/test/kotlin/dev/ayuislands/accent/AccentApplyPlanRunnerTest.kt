package dev.ayuislands.accent

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import dev.ayuislands.accent.AccentApplyStep.ApplyAlwaysOnEditorKeys
import dev.ayuislands.accent.AccentApplyStep.ApplyAlwaysOnUiKeys
import dev.ayuislands.accent.AccentApplyStep.NotifyComponentTrees
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import javax.swing.SwingUtilities
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccentApplyPlanRunnerTest {
    private val plan = listOf(ApplyAlwaysOnUiKeys, ApplyAlwaysOnEditorKeys, NotifyComponentTrees)

    @BeforeTest
    fun setUp() {
        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns true
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `runs steps in plan order and completes with no failures`() {
        val executed = mutableListOf<AccentApplyStep>()
        var reported: List<AccentApplyStepFailure>? = null

        AccentApplyPlanRunner.run(
            plan = plan,
            policy = AccentApplyFailurePolicy.AbortOnFirstFailure,
            executeStep = { executed += it },
        ) { reported = it }

        assertEquals(plan, executed)
        assertEquals(emptyList(), reported)
    }

    @Test
    fun `abort policy stops at first failure and skips remaining steps`() {
        val executed = mutableListOf<AccentApplyStep>()
        var reported: List<AccentApplyStepFailure>? = null
        val boom = IllegalStateException("boom")

        AccentApplyPlanRunner.run(
            plan = plan,
            policy = AccentApplyFailurePolicy.AbortOnFirstFailure,
            executeStep = { step ->
                executed += step
                if (step == ApplyAlwaysOnEditorKeys) throw boom
            },
        ) { reported = it }

        assertEquals(listOf(ApplyAlwaysOnUiKeys, ApplyAlwaysOnEditorKeys), executed)
        assertEquals(listOf(AccentApplyStepFailure(ApplyAlwaysOnEditorKeys, boom)), reported)
    }

    @Test
    fun `continue policy runs every step and collects all failures`() {
        val executed = mutableListOf<AccentApplyStep>()
        var reported: List<AccentApplyStepFailure>? = null

        AccentApplyPlanRunner.run(
            plan = plan,
            policy = AccentApplyFailurePolicy.ContinuePerStep,
            executeStep = { step ->
                executed += step
                if (step != NotifyComponentTrees) error("boom at $step")
            },
        ) { reported = it }

        assertEquals(plan, executed)
        assertEquals(
            listOf(ApplyAlwaysOnUiKeys, ApplyAlwaysOnEditorKeys),
            reported?.map { it.step },
        )
    }

    @Test
    fun `rethrows ProcessCanceledException instead of capturing it`() {
        assertFailsWith<ProcessCanceledException> {
            AccentApplyPlanRunner.run(
                plan = plan,
                policy = AccentApplyFailurePolicy.ContinuePerStep,
                executeStep = { throw ProcessCanceledException() },
            )
        }
    }

    @Test
    fun `rethrows coroutine CancellationException instead of capturing it`() {
        assertFailsWith<CancellationException> {
            AccentApplyPlanRunner.run(
                plan = plan,
                policy = AccentApplyFailurePolicy.ContinuePerStep,
                executeStep = { throw CancellationException("cancelled") },
            )
        }
    }

    @Test
    fun `posts through application invokeLater when off EDT`() {
        every { SwingUtilities.isEventDispatchThread() } returns false
        val mockApplication = mockk<Application>()
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.invokeLater(any(), any<ModalityState>()) } answers {
            firstArg<Runnable>().run()
        }
        val executed = mutableListOf<AccentApplyStep>()
        var completed = false

        AccentApplyPlanRunner.run(
            plan = plan,
            policy = AccentApplyFailurePolicy.AbortOnFirstFailure,
            executeStep = { executed += it },
        ) { completed = true }

        verify { mockApplication.invokeLater(any(), any<ModalityState>()) }
        verify(exactly = 0) { SwingUtilities.invokeLater(any()) }
        assertEquals(plan, executed)
        assertTrue(completed, "onComplete must run after the dispatched work")
    }

    @Test
    fun `falls back to SwingUtilities invokeLater when application is null`() {
        every { SwingUtilities.isEventDispatchThread() } returns false
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns null
        every { SwingUtilities.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
        val executed = mutableListOf<AccentApplyStep>()

        AccentApplyPlanRunner.run(
            plan = plan,
            policy = AccentApplyFailurePolicy.AbortOnFirstFailure,
            executeStep = { executed += it },
        )

        verify { SwingUtilities.invokeLater(any()) }
        assertEquals(plan, executed)
    }
}
