package dev.ayuislands.licensing

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.ui.LicensingFacade
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.util.EnumSet
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseTransitionListenerTest {
    private lateinit var state: AyuIslandsState
    private val reconciliations = mutableListOf<Pair<LicenseEntitlement, List<Project>>>()
    private val facade: LicensingFacade = mockk(relaxed = true)
    private val project: Project = mockk(relaxed = true)

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        reconciliations.clear()

        mockkStatic(ProjectManager::class)
        val projectManager = mockk<ProjectManager>()
        every { ProjectManager.getInstance() } returns projectManager
        every { projectManager.openProjects } returns arrayOf(project)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `initial licensed notification records baseline without reconciliation`() {
        state.premiumOnboardingShown = true

        listenerFor(LicenseEntitlement.LICENSED).licenseStateChanged(facade)

        assertTrue(state.premiumOnboardingShown)
        assertTrue(reconciliations.isEmpty())
    }

    @Test
    fun `initial unlicensed notification reconciles optimistic runtime once`() {
        val listener = listenerFor(LicenseEntitlement.UNLICENSED, LicenseEntitlement.UNLICENSED)

        listener.licenseStateChanged(facade)
        listener.licenseStateChanged(facade)

        assertEquals(
            listOf(LicenseEntitlement.UNLICENSED to listOf(project)),
            reconciliations,
        )
    }

    @Test
    fun `license loss and recovery reconcile once per transition`() {
        state.premiumOnboardingShown = true
        val listener =
            listenerFor(
                LicenseEntitlement.LICENSED,
                LicenseEntitlement.UNLICENSED,
                LicenseEntitlement.UNLICENSED,
                LicenseEntitlement.LICENSED,
            )
        listener.licenseStateChanged(facade)
        listener.licenseStateChanged(facade)
        listener.licenseStateChanged(facade)
        listener.licenseStateChanged(facade)

        assertEquals(
            listOf(LicenseEntitlement.UNLICENSED, LicenseEntitlement.LICENSED),
            reconciliations.map { it.first },
        )
        assertFalse(state.premiumOnboardingShown)
    }

    @Test
    fun `unknown result neither reconciles nor becomes transition baseline`() {
        val listener = listenerFor(LicenseEntitlement.UNKNOWN, LicenseEntitlement.LICENSED)
        listener.licenseStateChanged(facade)
        listener.licenseStateChanged(facade)

        assertTrue(reconciliations.isEmpty())
    }

    @Test
    fun `unknown between known states cannot hide license loss`() {
        val listener =
            listenerFor(
                LicenseEntitlement.LICENSED,
                LicenseEntitlement.UNKNOWN,
                LicenseEntitlement.UNLICENSED,
            )
        listener.licenseStateChanged(facade)
        listener.licenseStateChanged(facade)
        listener.licenseStateChanged(facade)

        assertEquals(listOf(LicenseEntitlement.UNLICENSED), reconciliations.map { it.first })
    }

    @Test
    fun `unknown result cannot rearm premium onboarding`() {
        state.premiumOnboardingShown = true
        val listener = listenerFor(LicenseEntitlement.UNLICENSED, LicenseEntitlement.UNKNOWN)
        listener.licenseStateChanged(facade)
        listener.licenseStateChanged(facade)

        assertTrue(state.premiumOnboardingShown)
    }

    @Test
    fun `checker failure does not corrupt remembered entitlement`() {
        state.premiumOnboardingShown = true
        var call = 0
        val listener =
            LicenseTransitionListener(
                entitlementProvider = {
                    when (call++) {
                        0 -> error("platform glitch")
                        1 -> LicenseEntitlement.UNLICENSED
                        else -> LicenseEntitlement.LICENSED
                    }
                },
                reconcile = {
                    entitlement,
                    projects,
                    ->
                    recordReconciliation(entitlement, projects)
                    ReconciliationResult.Success
                },
                dispatch = { it() },
                recheckDelayProvider = { null },
                scheduleRecheck = { _, _ -> },
            )
        val loggedErrors = mutableListOf<Throwable?>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    throwable: Throwable?,
                ): Set<Action> {
                    loggedErrors += throwable
                    return EnumSet.noneOf(Action::class.java)
                }
            }

        LoggedErrorProcessor.executeWith<IllegalStateException>(processor) {
            listener.licenseStateChanged(facade)
            listener.licenseStateChanged(facade)
            listener.licenseStateChanged(facade)
        }

        assertEquals(1, loggedErrors.size)
        assertEquals("platform glitch", loggedErrors.single()?.message)
        assertFalse(state.premiumOnboardingShown)
        assertEquals(
            listOf(LicenseEntitlement.UNLICENSED, LicenseEntitlement.LICENSED),
            reconciliations.map { it.first },
        )
    }

    @Test
    fun `same confirmed entitlement retries after reconciliation failure`() {
        var attempts = 0
        val listener =
            LicenseTransitionListener(
                entitlementProvider = { LicenseEntitlement.UNLICENSED },
                reconcile = { _, _ ->
                    attempts += 1
                    if (attempts == 1) {
                        ReconciliationResult(
                            listOf(
                                ReconciliationFailure(
                                    operation = "refresh Project view",
                                    error = RuntimeException("first attempt failed"),
                                ),
                            ),
                        )
                    } else {
                        ReconciliationResult.Success
                    }
                },
                dispatch = { it() },
                recheckDelayProvider = { null },
                scheduleRecheck = { _, _ -> },
            )

        listener.licenseStateChanged(facade)
        listener.licenseStateChanged(facade)

        assertEquals(2, attempts)
    }

    @Test
    fun `grace recheck reconciles expiry without another facade callback`() {
        var entitlement = LicenseEntitlement.LICENSED
        var scheduled: (() -> Unit)? = null
        val listener =
            LicenseTransitionListener(
                entitlementProvider = { entitlement },
                reconcile = {
                    current,
                    projects,
                    ->
                    recordReconciliation(current, projects)
                    ReconciliationResult.Success
                },
                dispatch = { it() },
                recheckDelayProvider = { 1_000L },
                scheduleRecheck = { _, action -> scheduled = action },
            )

        listener.licenseStateChanged(facade)
        entitlement = LicenseEntitlement.UNLICENSED
        checkNotNull(scheduled).invoke()

        assertEquals(listOf(LicenseEntitlement.UNLICENSED), reconciliations.map { it.first })
    }

    @Test
    fun `entitlement provider runs inside the serialized dispatch`() {
        val queued = mutableListOf<() -> Unit>()
        var providerCalls = 0
        val listener =
            LicenseTransitionListener(
                entitlementProvider = {
                    providerCalls += 1
                    LicenseEntitlement.LICENSED
                },
                reconcile = { _, _ -> ReconciliationResult.Success },
                dispatch = { queued += it },
                recheckDelayProvider = { null },
                scheduleRecheck = { _, _ -> },
            )

        listener.licenseStateChanged(facade)

        assertEquals(0, providerCalls)
        queued.single().invoke()
        assertEquals(1, providerCalls)
    }

    private fun listenerFor(vararg entitlements: LicenseEntitlement): LicenseTransitionListener {
        val remaining = ArrayDeque(entitlements.toList())
        return LicenseTransitionListener(
            entitlementProvider = { remaining.removeFirst() },
            reconcile = {
                entitlement,
                projects,
                ->
                recordReconciliation(entitlement, projects)
                ReconciliationResult.Success
            },
            dispatch = { it() },
            recheckDelayProvider = { null },
            scheduleRecheck = { _, _ -> },
        )
    }

    private fun recordReconciliation(
        entitlement: LicenseEntitlement,
        projects: Iterable<Project>,
    ) {
        reconciliations += entitlement to projects.toList()
    }
}
