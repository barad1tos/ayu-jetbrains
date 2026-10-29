package dev.ayuislands.licensing

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.testFramework.LoggedErrorProcessor
import dev.ayuislands.AyuIslandsStartupActivity
import dev.ayuislands.accent.AccentApplyScope
import dev.ayuislands.accent.AccentLifecycle
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.util.EnumSet
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EntitlementLifecycleTest {
    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `confirmed loss waits for the current apply before reconciling`() {
        val calls = mutableListOf<LicenseEntitlement>()
        val listener = listener({ LicenseEntitlement.UNLICENSED }, calls)

        withApply(
            during = {
                listener.licenseStateChanged(null)
                assertTrue(calls.isEmpty())
            },
            after = { assertEquals(expected = listOf(LicenseEntitlement.UNLICENSED), actual = calls) },
        )
    }

    @Test
    fun `queued license loss cannot repaint an obsolete entitlement`() {
        var entitlement = LicenseEntitlement.UNLICENSED
        val calls = mutableListOf<LicenseEntitlement>()
        val listener = listener({ entitlement }, calls)

        withApply(
            during = {
                listener.licenseStateChanged(null)
                entitlement = LicenseEntitlement.LICENSED
            },
            after = { assertTrue(calls.isEmpty()) },
        )
    }

    @Test
    fun `unknown entitlement does not interrupt a valid apply`() {
        val calls = mutableListOf<LicenseEntitlement>()
        val listener = listener({ LicenseEntitlement.UNKNOWN }, calls)

        withApply(
            during = { scope ->
                listener.licenseStateChanged(null)
                scope.runStep { }
            },
            after = { assertTrue(calls.isEmpty()) },
        )
    }

    @Test
    fun `scheduled startup reconciliation waits for the current apply`() {
        val scheduled = ArrayDeque<() -> Unit>()
        val calls = mutableListOf<LicenseEntitlement>()
        val activity =
            AyuIslandsStartupActivity(
                entitlementProvider = { LicenseEntitlement.UNLICENSED },
                projectsProvider = { emptyList() },
                reconcile = { observed, _ ->
                    calls += observed
                    ReconciliationResult.Success
                },
                scheduleRecheck = { _, action -> scheduled.addLast(action) },
            )
        activity.reconcileForTest(LicenseEntitlement.UNKNOWN, emptyList())

        withApply(
            during = {
                scheduled.removeFirst().invoke()
                assertTrue(calls.isEmpty())
            },
            after = { assertEquals(expected = listOf(LicenseEntitlement.UNLICENSED), actual = calls) },
        )
    }

    @Test
    fun `failed deferred entitlement lookup schedules a retry without consuming the transition`() {
        var isUnavailable = false
        val calls = mutableListOf<LicenseEntitlement>()
        val retries = ArrayDeque<() -> Unit>()
        val failure = IllegalStateException("Marketplace facade unavailable")
        val reported = mutableListOf<Throwable?>()
        val transitionListener =
            LicenseTransitionListener(
                entitlementProvider = {
                    if (isUnavailable) throw failure
                    LicenseEntitlement.UNLICENSED
                },
                projectsProvider = { emptyList() },
                reconcile = { observed, _ ->
                    calls += observed
                    ReconciliationResult.Success
                },
                dispatch = { it() },
                recheckDelayProvider = { null },
                confirmedProvider = { LicenseEntitlement.LICENSED },
                scheduleRecheck = { _, action -> retries.addLast(action) },
            )
        val processor =
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    throwable: Throwable?,
                ): Set<Action> {
                    reported += throwable
                    return EnumSet.noneOf(Action::class.java)
                }
            }

        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            withApply(
                during = {
                    transitionListener.licenseStateChanged(null)
                    isUnavailable = true
                },
                after = {
                    assertTrue(
                        calls.isEmpty(),
                        "provider failure must not create a license transition",
                    )
                    assertEquals(expected = 1, actual = retries.size)
                    isUnavailable = false
                    retries.removeFirst().invoke()
                    assertEquals(expected = listOf(LicenseEntitlement.UNLICENSED), actual = calls)
                    assertTrue(retries.isEmpty())
                },
            )
        }
        assertSame(expected = failure, actual = reported.single())
    }

    private fun listener(
        provider: () -> LicenseEntitlement,
        calls: MutableList<LicenseEntitlement>,
    ): LicenseTransitionListener {
        val state = AyuIslandsState()
        state.lastConfirmedEntitlement = LicenseEntitlement.LICENSED.name
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        return LicenseTransitionListener(
            entitlementProvider = provider,
            projectsProvider = { emptyList() },
            reconcile = { observed, _ ->
                calls += observed
                ReconciliationResult.Success
            },
            dispatch = { it() },
            recheckDelayProvider = { null },
            scheduleRecheck = { _, _ -> },
        )
    }

    private fun withApply(
        during: (AccentApplyScope) -> Unit,
        after: () -> Unit,
    ) {
        val application = mockk<Application>(relaxed = true)
        val posted = ArrayDeque<Runnable>()
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.invokeLater(any<Runnable>(), any<ModalityState>()) } answers {
            posted.addLast(firstArg())
        }
        SwingUtilities.invokeAndWait {
            val scope = AccentApplyScope()
            scope.open()
            try {
                during(scope)
            } finally {
                try {
                    scope.restore()
                } finally {
                    scope.close()
                    AccentLifecycle.afterApply()
                    while (posted.isNotEmpty()) posted.removeFirst().run()
                }
            }
            after()
        }
    }
}
