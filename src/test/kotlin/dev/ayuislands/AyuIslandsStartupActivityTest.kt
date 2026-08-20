package dev.ayuislands

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.testFramework.LoggedErrorProcessor
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.licensing.ReconciliationFailure
import dev.ayuislands.licensing.ReconciliationResult
import dev.ayuislands.onboarding.WizardAction
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.ui.ComponentTreeRefresher
import dev.ayuislands.vcs.VcsColorApplier
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.util.EnumSet
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Locks in the [AyuIslandsStartupActivity.runStep] catch contract:
 *
 *  - [RuntimeException] -> logged, swallowed, next call would proceed
 *  - [VirtualMachineError] -> logged then rethrown so the JVM crash reporter still gets it
 *  - other [Error] -> logged, swallowed (LinkageError / NoClassDefFoundError from optional
 *    plugin deps shouldn't abort plugin startup)
 *
 * The split is non-trivial - a future widening of the Error catch to omit the rethrow
 * (or narrowing it past LinkageError) would silently change startup behavior. These
 * tests freeze the contract.
 */
class AyuIslandsStartupActivityTest {
    private val activity = AyuIslandsStartupActivity()

    // Tests that mock global objects restore them in a local finally block;
    // LoggedErrorProcessor.executeWith restores the default processor itself.

    @Test
    fun `license startup uses IntelliJ write-safe dispatcher`() {
        val application = mockk<Application>(relaxed = true)
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<AyuIslandsSettings>(relaxed = true)
        val scheduledActions = mutableListOf<Runnable>()
        mockkStatic(ApplicationManager::class)
        mockkObject(LicenseChecker)
        mockkObject(StartupLicenseHandler)
        every { ApplicationManager.getApplication() } returns application
        every {
            application.invokeLater(capture(scheduledActions), ModalityState.nonModal())
        } returns Unit
        every { LicenseChecker.getTrialDaysRemaining() } returns null
        every { StartupLicenseHandler.computeAdaptiveDelay() } returns 1
        val startupActivity =
            AyuIslandsStartupActivity(
                entitlementProvider = { LicenseEntitlement.UNKNOWN },
            )

        try {
            val method =
                AyuIslandsStartupActivity::class.java.getDeclaredMethod(
                    "checkLicenseState",
                    Project::class.java,
                    AyuIslandsSettings::class.java,
                    Boolean::class.javaPrimitiveType,
                )
            method.isAccessible = true
            method.invoke(startupActivity, project, settings, false)

            assertEquals(1, scheduledActions.size)
            verify(exactly = 1) {
                application.invokeLater(any<Runnable>(), ModalityState.nonModal())
            }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `startup frame refresh uses IntelliJ write-safe dispatcher`() {
        val application = mockk<Application>(relaxed = true)
        val project = mockk<Project>(relaxed = true)
        val windowManager = mockk<WindowManager>()
        val frame = mockk<JFrame>(relaxed = true)
        val scheduledActions = mutableListOf<Runnable>()
        mockkStatic(ApplicationManager::class)
        mockkStatic(WindowManager::class)
        mockkObject(ComponentTreeRefresher)
        every { ApplicationManager.getApplication() } returns application
        every {
            application.invokeLater(capture(scheduledActions), ModalityState.nonModal())
        } returns Unit
        every { WindowManager.getInstance() } returns windowManager
        every { windowManager.getFrame(project) } returns frame
        every { ComponentTreeRefresher.walkAndNotify(project, frame) } just Runs

        try {
            val method =
                AyuIslandsStartupActivity::class.java.getDeclaredMethod(
                    "scheduleFrameRefresh",
                    Project::class.java,
                )
            method.isAccessible = true
            method.invoke(activity, project)

            assertEquals(1, scheduledActions.size)
            scheduledActions.single().run()
            verify(exactly = 1) {
                application.invokeLater(any<Runnable>(), ModalityState.nonModal())
            }
            verify(exactly = 1) { ComponentTreeRefresher.walkAndNotify(project, frame) }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `execute routes frame refresh through write-safe helper`() {
        val invokedMethods = mutableListOf<Pair<String, String>>()
        val classBytes =
            requireNotNull(
                AyuIslandsStartupActivity::class.java.getResourceAsStream("AyuIslandsStartupActivity.class"),
            )
        classBytes.use { input ->
            ClassReader(input).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        if (name != "execute") return null
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(
                                opcode: Int,
                                owner: String,
                                name: String,
                                descriptor: String,
                                isInterface: Boolean,
                            ) {
                                invokedMethods += owner to name
                            }
                        }
                    }
                },
                ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        }

        assertTrue(
            invokedMethods.any {
                it.first == "dev/ayuislands/AyuIslandsStartupActivity" &&
                    it.second == "scheduleFrameRefresh"
            },
            "execute must keep the issue #330 component refresh on the write-safe helper path",
        )
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
    fun `startup reconciliation failure schedules a short retry`() {
        var attempts = 0
        var retryDelayMs: Long? = null
        var retryAction: (() -> Unit)? = null
        val project = mockk<Project>(relaxed = true)
        val retryingActivity =
            AyuIslandsStartupActivity(
                entitlementProvider = { LicenseEntitlement.LICENSED },
                reconcile = { _, _ ->
                    attempts += 1
                    if (attempts == 1) {
                        ReconciliationResult(
                            listOf(
                                ReconciliationFailure(
                                    operation = "refresh syntax",
                                    error = RuntimeException("first attempt failed"),
                                ),
                            ),
                        )
                    } else {
                        ReconciliationResult.Success
                    }
                },
                scheduleRecheck = { delayMs, action ->
                    retryDelayMs = delayMs
                    retryAction = action
                },
                recheckDelayProvider = { null },
                projectsProvider = { listOf(project) },
            )

        retryingActivity.reconcileForTest(LicenseEntitlement.LICENSED, listOf(project))

        assertEquals(1, attempts)
        assertTrue(checkNotNull(retryDelayMs) < 60_000L)
        checkNotNull(retryAction).invoke()
        assertEquals(2, attempts)
    }

    @Test
    fun `unknown startup entitlement schedules a definitive state retry`() {
        var reconciliationAttempts = 0
        val scheduledDelays = mutableListOf<Long>()
        val project = mockk<Project>(relaxed = true)
        val retryingActivity =
            AyuIslandsStartupActivity(
                reconcile = { _, _ ->
                    reconciliationAttempts += 1
                    ReconciliationResult.Success
                },
                scheduleRecheck = { delayMs, _ -> scheduledDelays += delayMs },
            )

        retryingActivity.reconcileForTest(LicenseEntitlement.UNKNOWN, listOf(project))

        assertEquals(0, reconciliationAttempts)
        assertEquals(listOf(5_000L), scheduledDelays)
    }

    @Test
    fun `definitive startup retry completes the deferred consumer workflow once`() {
        var entitlement = LicenseEntitlement.UNKNOWN
        var discoveryAttempts = 0
        var onboardingAttempts = 0
        val scheduledActions = mutableListOf<() -> Unit>()
        val disposedProject = mockk<Project>(relaxed = true)
        val project = mockk<Project>(relaxed = true)
        every { disposedProject.isDisposed } returns true
        every { project.isDisposed } returns false
        val settings =
            mockk<AyuIslandsSettings> {
                every { state } returns AyuIslandsState()
            }
        mockkObject(StartupLicenseHandler)
        mockkObject(LicenseChecker)
        every { LicenseChecker.getTrialDaysRemaining() } returns null
        every { LicenseChecker.checkTrialExpiryWarning(project) } returns Unit
        every { StartupLicenseHandler.computeAdaptiveDelay() } returns 1
        every { StartupLicenseHandler.runOnboardingMigration(settings) } returns Unit
        every { StartupLicenseHandler.initWorkspaceServices(project, settings) } returns Unit
        every {
            StartupLicenseHandler.resolveOnboarding(true, settings, false)
        } answers {
            onboardingAttempts += 1
            if (onboardingAttempts == 1) error("onboarding resolution failed")
            WizardAction.NoWizard
        }
        every {
            StartupLicenseHandler.applyEntitlement(LicenseEntitlement.LICENSED, project, settings)
        } returns Unit
        every {
            StartupLicenseHandler.handleWizardAction(WizardAction.NoWizard, project, 1)
        } returns Unit
        val retryingActivity =
            AyuIslandsStartupActivity(
                entitlementProvider = { entitlement },
                reconcile = { _, _ -> ReconciliationResult.Success },
                scheduleRecheck = { _, action -> scheduledActions += action },
                recheckDelayProvider = { null },
                projectsProvider = {
                    discoveryAttempts += 1
                    when (discoveryAttempts) {
                        1 -> error("project discovery failed")
                        2 -> emptyList()
                        else -> listOf(disposedProject, project)
                    }
                },
            )

        try {
            val method =
                AyuIslandsStartupActivity::class.java.getDeclaredMethod(
                    "checkLicenseState",
                    Project::class.java,
                    AyuIslandsSettings::class.java,
                    Boolean::class.javaPrimitiveType,
                )
            method.isAccessible = true
            method.invoke(retryingActivity, project, settings, false)
            SwingUtilities.invokeAndWait {}

            verify(exactly = 0) {
                StartupLicenseHandler.applyEntitlement(any(), any(), any())
            }
            entitlement = LicenseEntitlement.LICENSED
            scheduledActions.removeFirst().invoke()
            verify(exactly = 0) {
                StartupLicenseHandler.applyEntitlement(any(), any(), any())
            }
            scheduledActions.removeFirst().invoke()
            verify(exactly = 0) {
                StartupLicenseHandler.applyEntitlement(any(), any(), any())
            }
            val captured = mutableListOf<Pair<String, Throwable?>>()
            LoggedErrorProcessor.executeWith<RuntimeException>(capturingProcessor(captured)) {
                scheduledActions.removeFirst().invoke()
            }
            assertEquals("License startup step 'resolve-onboarding' failed", captured.single().first)
            assertEquals(1, scheduledActions.size)
            scheduledActions.removeFirst().invoke()

            verify(exactly = 2) {
                StartupLicenseHandler.resolveOnboarding(true, settings, false)
            }
            verify(exactly = 1) {
                StartupLicenseHandler.applyEntitlement(LicenseEntitlement.LICENSED, project, settings)
            }
            verify(exactly = 1) {
                StartupLicenseHandler.handleWizardAction(WizardAction.NoWizard, project, 1)
            }
            verify(exactly = 1) { LicenseChecker.checkTrialExpiryWarning(project) }
            assertEquals(4, discoveryAttempts)
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `definitive startup retry resumes wizard work without repeating entitlement apply`() {
        var entitlement = LicenseEntitlement.UNKNOWN
        var reconciliationAttempts = 0
        var entitlementAttempts = 0
        var wizardAttempts = 0
        val scheduledActions = mutableListOf<() -> Unit>()
        val project = mockk<Project>(relaxed = true)
        every { project.isDisposed } returns false
        val settings =
            mockk<AyuIslandsSettings> {
                every { state } returns AyuIslandsState()
            }
        mockkObject(StartupLicenseHandler)
        mockkObject(LicenseChecker)
        every { LicenseChecker.getTrialDaysRemaining() } returns null
        every { LicenseChecker.checkTrialExpiryWarning(project) } returns Unit
        every { StartupLicenseHandler.computeAdaptiveDelay() } returns 1
        every { StartupLicenseHandler.runOnboardingMigration(settings) } returns Unit
        every { StartupLicenseHandler.initWorkspaceServices(project, settings) } returns Unit
        every {
            StartupLicenseHandler.resolveOnboarding(true, settings, false)
        } returns WizardAction.NoWizard
        every {
            StartupLicenseHandler.applyEntitlement(LicenseEntitlement.LICENSED, project, settings)
        } answers {
            entitlementAttempts += 1
            if (entitlementAttempts == 1) error("entitlement application failed")
        }
        every {
            StartupLicenseHandler.handleWizardAction(WizardAction.NoWizard, project, 1)
        } answers {
            wizardAttempts += 1
            if (wizardAttempts == 1) error("wizard scheduling failed")
        }
        val retryingActivity =
            AyuIslandsStartupActivity(
                entitlementProvider = { entitlement },
                reconcile = { _, _ ->
                    reconciliationAttempts += 1
                    ReconciliationResult.Success
                },
                scheduleRecheck = { _, action -> scheduledActions += action },
                recheckDelayProvider = { null },
                projectsProvider = { listOf(project) },
            )

        try {
            val method =
                AyuIslandsStartupActivity::class.java.getDeclaredMethod(
                    "checkLicenseState",
                    Project::class.java,
                    AyuIslandsSettings::class.java,
                    Boolean::class.javaPrimitiveType,
                )
            method.isAccessible = true
            method.invoke(retryingActivity, project, settings, false)
            SwingUtilities.invokeAndWait {}

            entitlement = LicenseEntitlement.LICENSED
            val entitlementFailure = mutableListOf<Pair<String, Throwable?>>()
            LoggedErrorProcessor.executeWith<RuntimeException>(capturingProcessor(entitlementFailure)) {
                scheduledActions.removeFirst().invoke()
            }

            assertEquals(1, reconciliationAttempts)
            assertEquals(1, scheduledActions.size)
            assertEquals(
                "License startup step 'apply-entitlement' failed",
                entitlementFailure.single().first,
            )

            val wizardFailure = mutableListOf<Pair<String, Throwable?>>()
            LoggedErrorProcessor.executeWith<RuntimeException>(capturingProcessor(wizardFailure)) {
                scheduledActions.removeFirst().invoke()
            }

            assertEquals(2, reconciliationAttempts)
            assertEquals(1, scheduledActions.size)
            assertEquals(
                "License startup step 'handle-wizard-action' failed",
                wizardFailure.single().first,
            )

            scheduledActions.removeFirst().invoke()

            assertEquals(3, reconciliationAttempts)
            assertEquals(2, wizardAttempts)
            verify(exactly = 1) {
                StartupLicenseHandler.resolveOnboarding(true, settings, false)
            }
            verify(exactly = 2) {
                StartupLicenseHandler.applyEntitlement(LicenseEntitlement.LICENSED, project, settings)
            }
            verify(exactly = 1) { LicenseChecker.checkTrialExpiryWarning(project) }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `successful licensed startup schedules the adaptive license check`() {
        var reconciliationAttempts = 0
        val scheduledDelays = mutableListOf<Long>()
        val project = mockk<Project>(relaxed = true)
        val retryingActivity =
            AyuIslandsStartupActivity(
                reconcile = { _, _ ->
                    reconciliationAttempts += 1
                    ReconciliationResult.Success
                },
                scheduleRecheck = { delayMs, _ -> scheduledDelays += delayMs },
                recheckDelayProvider = { 100_000L },
            )

        retryingActivity.reconcileForTest(LicenseEntitlement.LICENSED, listOf(project))

        assertEquals(1, reconciliationAttempts)
        assertEquals(listOf(100_000L), scheduledDelays)
    }

    @Test
    fun `successful startup retry restores the adaptive license check`() {
        var attempts = 0
        val scheduledDelays = mutableListOf<Long>()
        val scheduledActions = mutableListOf<() -> Unit>()
        val project = mockk<Project>(relaxed = true)
        val retryingActivity =
            AyuIslandsStartupActivity(
                entitlementProvider = { LicenseEntitlement.LICENSED },
                reconcile = { _, _ ->
                    attempts += 1
                    if (attempts == 1) {
                        ReconciliationResult(
                            listOf(
                                ReconciliationFailure(
                                    operation = "refresh syntax",
                                    error = RuntimeException("first attempt failed"),
                                ),
                            ),
                        )
                    } else {
                        ReconciliationResult.Success
                    }
                },
                scheduleRecheck = { delayMs, action ->
                    scheduledDelays += delayMs
                    scheduledActions += action
                },
                recheckDelayProvider = { 100_000L },
                projectsProvider = { listOf(project) },
            )

        retryingActivity.reconcileForTest(LicenseEntitlement.LICENSED, listOf(project))
        scheduledActions.single().invoke()

        assertEquals(2, attempts)
        assertEquals(listOf(5_000L, 100_000L), scheduledDelays)
    }

    @Test
    fun `scheduled startup checks reconcile only currently open projects`() {
        val startupProject = mockk<Project>(relaxed = true)
        val currentProject = mockk<Project>(relaxed = true)
        every { startupProject.isDisposed } returns true
        every { currentProject.isDisposed } returns false
        val scheduledActions = mutableListOf<() -> Unit>()
        val reconciledProjects = mutableListOf<List<Project>>()
        var attempts = 0
        val retryingActivity =
            AyuIslandsStartupActivity(
                entitlementProvider = { LicenseEntitlement.LICENSED },
                reconcile = { _, projects ->
                    attempts += 1
                    reconciledProjects += projects.toList()
                    if (attempts == 1) {
                        ReconciliationResult(
                            listOf(
                                ReconciliationFailure(
                                    operation = "refresh syntax",
                                    error = RuntimeException("first attempt failed"),
                                ),
                            ),
                        )
                    } else {
                        ReconciliationResult.Success
                    }
                },
                scheduleRecheck = { _, action -> scheduledActions += action },
                recheckDelayProvider = { 100_000L },
                projectsProvider = { listOf(startupProject, currentProject) },
            )

        retryingActivity.reconcileForTest(LicenseEntitlement.LICENSED, listOf(startupProject))
        scheduledActions.removeFirst().invoke()
        scheduledActions.removeFirst().invoke()

        assertEquals(
            listOf(
                listOf(startupProject),
                listOf(currentProject),
                listOf(currentProject),
            ),
            reconciledProjects,
        )
    }

    @Test
    fun `scheduled startup check retries when current project discovery fails`() {
        val startupProject = mockk<Project>(relaxed = true)
        val currentProject = mockk<Project>(relaxed = true)
        every { currentProject.isDisposed } returns false
        val scheduledActions = mutableListOf<() -> Unit>()
        val reconciledProjects = mutableListOf<List<Project>>()
        var reconciliationAttempts = 0
        var discoveryAttempts = 0
        val retryingActivity =
            AyuIslandsStartupActivity(
                entitlementProvider = { LicenseEntitlement.LICENSED },
                reconcile = { _, projects ->
                    reconciliationAttempts += 1
                    reconciledProjects += projects.toList()
                    if (reconciliationAttempts == 1) {
                        ReconciliationResult(
                            listOf(
                                ReconciliationFailure(
                                    operation = "refresh syntax",
                                    error = RuntimeException("first attempt failed"),
                                ),
                            ),
                        )
                    } else {
                        ReconciliationResult.Success
                    }
                },
                scheduleRecheck = { _, action -> scheduledActions += action },
                recheckDelayProvider = { null },
                projectsProvider = {
                    discoveryAttempts += 1
                    if (discoveryAttempts == 1) {
                        error("project discovery failed")
                    }
                    listOf(currentProject)
                },
            )

        retryingActivity.reconcileForTest(LicenseEntitlement.LICENSED, listOf(startupProject))
        scheduledActions.removeFirst().invoke()
        scheduledActions.removeFirst().invoke()

        assertEquals(2, reconciliationAttempts)
        assertEquals(2, discoveryAttempts)
        assertEquals(listOf(listOf(startupProject), listOf(currentProject)), reconciledProjects)
    }

    @Test
    fun `scheduled startup check retries when reconciliation throws`() {
        val project = mockk<Project>(relaxed = true)
        var disposalReads = 0
        every { project.isDisposed } answers {
            disposalReads += 1
            if (disposalReads == 1) false else error("project disposed check failed")
        }
        val scheduledActions = mutableListOf<() -> Unit>()
        var reconciliationAttempts = 0
        val retryingActivity =
            AyuIslandsStartupActivity(
                entitlementProvider = { LicenseEntitlement.LICENSED },
                reconcile = { _, projects ->
                    reconciliationAttempts += 1
                    if (reconciliationAttempts == 1) {
                        ReconciliationResult(
                            listOf(
                                ReconciliationFailure(
                                    operation = "refresh syntax",
                                    error = RuntimeException("first attempt failed"),
                                ),
                            ),
                        )
                    } else {
                        projects.single().isDisposed
                        ReconciliationResult.Success
                    }
                },
                scheduleRecheck = { _, action -> scheduledActions += action },
                recheckDelayProvider = { null },
                projectsProvider = { listOf(project) },
            )

        retryingActivity.reconcileForTest(LicenseEntitlement.LICENSED, listOf(project))
        scheduledActions.removeFirst().invoke()

        assertEquals(2, reconciliationAttempts)
        assertEquals(1, scheduledActions.size)
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
        // independently - otherwise one class-load glitch silently kills all subsequent
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
    fun `startup language detector warmup includes exact language overrides`() {
        val state = AccentMappingsState()
        state.languageAccents["kotlin"] = "#73D0FF"

        assertTrue(
            activity.shouldWarmLanguageDetectorForTest(state),
            "Language pins should warm the detector so first Settings open shows proportions immediately",
        )
    }

    @Test
    fun `startup language detector warmup includes fallback-only language resolution`() {
        val state = AccentMappingsState()
        state.languageFallbackAccent = "#73D0FF"

        assertTrue(
            activity.shouldWarmLanguageDetectorForTest(state),
            "A fallback-only language policy still needs a detected language before it can apply",
        )
    }

    @Test
    fun `startup language detector warmup stays off without language resolution work`() {
        val state = AccentMappingsState()

        assertFalse(
            activity.shouldWarmLanguageDetectorForTest(state),
            "Projects without language pins or fallback should not pay the startup scan cost",
        )
    }

    @Test
    fun `execute dispatches AccentApplicator resolveFocusedProject via withContext Dispatchers EDT`() {
        // Regression guard: `AccentApplicator.resolveFocusedProject` is `@RequiresEdt`
        // (`IdeFocusManager` touches Swing focus state), but `ProjectActivity.execute`
        // runs on a background coroutine by default. Without a
        // `withContext(Dispatchers.EDT)` wrap the call throws a threading assertion
        // inside the IDE under fleetMode / assertions.
        //
        // The invariant is source-level - dynamically invoking execute() requires a project
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
            "AccentApplicator.resolveFocusedProject must be invoked inside withContext(Dispatchers.EDT) { ... }",
        )
    }

    @Test
    fun `execute publishes swap-service cache inside the same EDT withContext block`() {
        // Regression guard: `ProjectAccentSwapService` has an EDT precondition for
        // `notifyExternalApply` (per `AccentApplicator.applyForFocusedProject` KDoc -
        // the cache write is a bare volatile with no dispatch and must publish in the
        // same ordering as the apply that preceded it).
        //
        // Install and notifyExternalApply later split into two
        // `runCatchingPreservingCancellation` blocks for better error attribution,
        // so the regex tolerates intermediate braces - the only invariant that
        // matters is that all four operations appear in order inside the EDT turn,
        // not that they share a single inner block.
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
            "resolveFocusedProject -> apply -> install -> notifyExternalApply must all run " +
                "inside the same withContext(Dispatchers.EDT) { ... } turn",
        )
    }

    @Test
    fun `startup accent helper emits distinct error messages per failure branch`() {
        // Regression lock. The loop fix split one `runCatching` into three
        // (`apply` / `install` / `notifyExternalApply`) so triage can tell
        // which half of the startup triplet failed from the log line alone.
        // A maintainer who collapses the three branches back into one message,
        // or who accidentally swaps the strings, would silently break that
        // attribution contract.
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
        // And the old bundled wording must NOT reappear - that's the swap-back regression.
        assertEquals(
            false,
            source.contains("Startup accent-swap install/notify failed"),
            "Old bundled install+notify message must not return",
        )
    }

    @Test
    fun `startup EDT block bails early when project or application is disposed`() {
        // Regression lock. The disposal bail must be the FIRST statement inside
        // the `withContext(Dispatchers.EDT)` body so a mid-hop disposal can't
        // reach platform APIs that would rewrap `ProcessCanceledException` as
        // `AlreadyDisposedException` (which the coroutine cancellation helper
        // cannot unwrap). Locks both presence AND position of the guard.
        val source = readStartupActivitySource()
        val disposedBail =
            Regex(
                """withContext\(Dispatchers\.EDT\)\s*\{\s*if\s*\(\s*project\.isDisposed\s*\|\|\s*""" +
                    """ApplicationManager\.getApplication\(\)\.isDisposed\s*\)\s*\{\s*return@withContext""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            disposedBail.containsMatchIn(source),
            "Disposal bail must be the first statement inside withContext(Dispatchers.EDT) { ... }",
        )
    }

    @Test
    fun `execute applies external accents and initializes glow before non-Ayu early return`() {
        // Regression guard: external themes skip the Ayu-only startup pipeline,
        // but external accent integrations and Glow still need per-project
        // initialization when the user opted in. Keep both before the early
        // return so startup does not wait for a later Settings or LAF event.
        val source = readStartupActivitySource()
        val earlyReturn =
            Regex(
                """AyuVariant\.fromThemeName\(themeName\)\s*\?:\s*return\s+""" +
                    """initializeExternalThemeIfEnabled\(project,\s*settings\)""",
                RegexOption.DOT_MATCHES_ALL,
            )
        val externalStartup =
            Regex(
                """private\s+suspend\s+fun\s+initializeExternalThemeIfEnabled""" +
                    """.*?state\.externalThemeEnhancementsEnabled""" +
                    """.*?runStartupAccentOnEdt\(project,\s*AccentContext\.External\)""" +
                    """.*?initializeExternalGlowIfEnabled\(project,\s*settings\)""",
                RegexOption.DOT_MATCHES_ALL,
            )
        val externalGlowInitializer =
            Regex(
                """private\s+fun\s+initializeExternalGlowIfEnabled""" +
                    """.*?state\.isExternalGlowAllowed\(\).*?""" +
                    """ApplicationManager\.getApplication\(\)\.invokeLater\s*\(\s*""" +
                    """\{\s*GlowOverlayManager\.getInstance\(project\)\.initialize\(\)\s*},\s*""" +
                    """project\.disposed,\s*\).*?}""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            earlyReturn.containsMatchIn(source),
            "Non-Ayu startup must route through initializeExternalThemeIfEnabled before returning",
        )
        assertTrue(
            externalStartup.containsMatchIn(source),
            "External theme startup must apply AccentContext.External before returning",
        )
        assertTrue(
            externalGlowInitializer.containsMatchIn(source),
            "External theme startup must initialize GlowOverlayManager before the non-Ayu early return",
        )
    }

    @Test
    fun `startup projectName is captured before the EDT hop`() {
        // Regression lock. `projectName` MUST be captured OUTSIDE
        // `withContext(Dispatchers.EDT)` so a mid-hop disposal cannot NPE
        // inside the error logger and swallow the original exception.
        // Tempting to move it inside since `projectName` is only read there -
        // this guard catches that.
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
        // The `projectName` capture uses a `try/catch` with `RuntimeException`
        // (narrowed from `runCatching` over `Throwable`) and falls back to the
        // `"<disposed>"` literal when `project.name` access blows up. Lock
        // both the narrowed catch and the literal fallback.
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
        // Regression lock. The split-block fix depends on `install()` and
        // `notifyExternalApply()` sitting in SEPARATE
        // `runCatchingPreservingCancellation` blocks with an
        // `if (!installed) return@withContext` short-circuit between them. A
        // "simplify" refactor that collapses them back into one block would
        // keep both error literals (as dead code inside one `runCatching` body)
        // while silently restoring the bug - publish to swap cache despite a
        // failed install. Lock the structural pattern.
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
    fun `install failure short-circuits before notifyExternalApply`() {
        // Regression lock: when `swapService.install()` fails, `installed` is
        // false and the function MUST `return@withContext` before
        // `swapService.notifyExternalApply(...)` executes. Behavioural form is
        // impossible here (`runStartupAccentOnEdt` is a private suspend fun
        // that touches `Dispatchers.EDT` + IntelliJ platform singletons -
        // there is no test seam that exercises it in a unit harness). The
        // contract is locked source-structurally: the
        // `if (!installed) return@withContext` guard must sit between the
        // install block's `.isSuccess` suffix and the `notifyExternalApply`
        // call site. Removing or reordering that guard reintroduces the bug
        // where a failed install would still publish to the swap cache.
        val source = readStartupActivitySource()
        val guardBeforeNotify =
            Regex(
                """swapService\.install\(\)[^}]*\}\s*(\.onFailure\s*\{[^}]*\}\s*)?\.isSuccess""" +
                    """\s*if\s*\(\s*!\s*installed\s*\)\s*return@withContext""" +
                    """.*?swapService\.notifyExternalApply\(""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            guardBeforeNotify.containsMatchIn(source),
            "install() failure path must `return@withContext` BEFORE notifyExternalApply runs",
        )
        // Belt-and-braces: the failure log text is specific, so lock on it too.
        assertTrue(
            source.contains(
                "Startup accent-swap install failed for project",
            ),
            "install-failure ERROR message must remain to distinguish install-fail from apply-fail",
        )
    }

    @Test
    fun `startup helper WARNs when applyFromHexString rejects hex as a defensive branch`() {
        // Regression lock: `applyFromHexString` returns Boolean (false =
        // rejected); when rejected, `hex` becomes null via
        // `if (applied) resolved else null`. That branch must log a WARN so
        // operators can tell rejected-hex from throw-hex. Source-level lock on
        // the WARN literal AND the structural `applyOutcome.isSuccess` check
        // inside `if (hex == null)`.
        val source = readStartupActivitySource()
        assertTrue(
            source.contains("Startup accent apply was rejected by applyFromHexString"),
            "WARN-on-rejected-hex branch must remain against Boolean-gating regressions",
        )
        val isSuccessInNullBranch =
            Regex(
                """if\s*\(\s*hex\s*==\s*null\s*\)\s*\{\s*[^}]*if\s*\(\s*applyOutcome\.isSuccess\s*\)""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            isSuccessInNullBranch.containsMatchIn(source),
            "isSuccess check must sit INSIDE the `if (hex == null)` block - " +
                "that distinguishes Success(null=rejected) from Failure(throw)",
        )
    }

    @Test
    fun `execute does not claim AccentApplicator apply self-dispatches`() {
        // Regression guard: the old comment above the `withContext` block asserted
        // "AccentApplicator.apply self-dispatches internally", which contradicts
        // [AccentApplicator.apply]'s `@RequiresEdt` annotation and its KDoc
        // explicitly stating the helper does NOT self-dispatch pre-apply steps.
        // A future maintainer reading the false comment would be tempted to
        // unwrap the `withContext` - exactly the regression this guard prevents.
        val source = readStartupActivitySource()
        assertEquals(
            false,
            source.contains("apply self-dispatches internally"),
            "Stale/false 'apply self-dispatches internally' comment must not reappear - " +
                "AccentApplicator.apply is @RequiresEdt and requires callers to already be on EDT",
        )
    }

    // Lock the full 5-step order inside `runStartupAccentOnEdt`:
    // `resolveFocusedProject` -> `AccentResolver.resolve` -> `AccentApplicator.apply`
    // -> `swapService.install` -> `swapService.notifyExternalApply`. The existing
    // "same EDT withContext block" test covers the last four; this one adds
    // `AccentResolver.resolve` between them so a future refactor that drops
    // the resolver step (and hands a stale accent hex directly to apply)
    // surfaces here.
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
            "runStartupAccentOnEdt must invoke resolveFocusedProject -> AccentResolver.resolve -> " +
                "AccentApplicator.apply -> swapService.install -> swapService.notifyExternalApply in order",
        )
    }

    // -----------------------------------------------------------------------
    // applyPersistedVcsColors gate coverage.
    //
    // [AyuIslandsStartupActivity.applyPersistedVcsColors] is a `private fun`
    // that gates [VcsColorApplier.applyAll] on `state.vcsColorEnabled AND
    // LicenseChecker.isLicensedOrGrace()`. Since the function is private and
    // has no dedicated test seam, the tests below mirror the exact production
    // body inside the public [AyuIslandsStartupActivity.runStepForTest] seam.
    // This duplicates intent but is the only behavioural cover available
    // without altering production code, and the source-regex lock below
    // freezes the production gate so a future drift between mirror and source
    // is caught.
    // -----------------------------------------------------------------------

    private fun runVcsGateMirror(settings: AyuIslandsSettings) {
        activity.runStepForTest("apply-persisted-vcs-colors") {
            if (settings.state.vcsColorEnabled && LicenseChecker.isLicensedOrGrace()) {
                VcsColorApplier.applyAll()
            }
        }
    }

    @Test
    fun `applyPersistedVcsColors - gate-off when master is false (vcsColorEnabled=false) - applier NOT invoked`() {
        val state = AyuIslandsState().apply { vcsColorEnabled = false }
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        mockkObject(VcsColorApplier)
        every { VcsColorApplier.applyAll() } returns Unit
        try {
            runVcsGateMirror(settings)

            verify(exactly = 0) { VcsColorApplier.applyAll() }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `applyPersistedVcsColors - gate-off when unlicensed (license false) - applier NOT invoked`() {
        val state = AyuIslandsState().apply { vcsColorEnabled = true }
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns false
        mockkObject(VcsColorApplier)
        every { VcsColorApplier.applyAll() } returns Unit
        try {
            runVcsGateMirror(settings)

            verify(exactly = 0) { VcsColorApplier.applyAll() }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `applyPersistedVcsColors - both gates pass - applier invoked exactly once`() {
        val state = AyuIslandsState().apply { vcsColorEnabled = true }
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        mockkObject(VcsColorApplier)
        every { VcsColorApplier.applyAll() } returns Unit
        try {
            runVcsGateMirror(settings)

            verify(exactly = 1) { VcsColorApplier.applyAll() }
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `applyPersistedVcsColors - runStep wraps the call - RuntimeException is logged-and-swallowed`() {
        // Production wraps `applyPersistedVcsColors(settings)` in
        // `runStep("apply-persisted-vcs-colors") { ... }` (line 208). The
        // runStep contract (locked by the existing tests at the top of this
        // file) swallows RuntimeException after logging via LOG.error. This
        // test exercises the wrap end-to-end: if applyAll throws, no
        // exception escapes runStepForTest and the error message names the
        // step.
        val state = AyuIslandsState().apply { vcsColorEnabled = true }
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        mockkObject(VcsColorApplier)
        every { VcsColorApplier.applyAll() } throws RuntimeException("boom")

        val captured = mutableListOf<Pair<String, Throwable?>>()
        val processor = capturingProcessor(captured)
        try {
            LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
                // Should NOT throw - runStep is required to swallow RuntimeException.
                runVcsGateMirror(settings)
            }
            assertEquals(1, captured.size, "RuntimeException must be logged exactly once")
            assertEquals(
                "License startup step 'apply-persisted-vcs-colors' failed",
                captured.single().first,
                "Error message must name the apply-persisted-vcs-colors step",
            )
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `applyPersistedVcsColors source gate matches the test mirror exactly`() {
        // Drift lock: the four tests above mirror the production body of
        // `applyPersistedVcsColors`. If a future maintainer changes the gate
        // (e.g. adds a third predicate, flips operator precedence, swaps the
        // function call), the mirror in [runVcsGateMirror] silently goes
        // stale and the gate tests keep passing while the real gate
        // misbehaves. This regex locks the production gate to the exact shape
        // the mirror duplicates.
        val source = readStartupActivitySource()
        val productionGate =
            Regex(
                """private\s+fun\s+applyPersistedVcsColors\([^)]*\)\s*\{\s*""" +
                    """if\s*\(\s*settings\.state\.vcsColorEnabled\s*&&\s*""" +
                    """LicenseChecker\.isLicensedOrGrace\(\)\s*\)\s*\{\s*""" +
                    """VcsColorApplier\.applyAll\(\)\s*\}\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            productionGate.containsMatchIn(source),
            "applyPersistedVcsColors gate must remain `if (state.vcsColorEnabled " +
                "&& LicenseChecker.isLicensedOrGrace()) { VcsColorApplier.applyAll() }` " +
                "- gate-coverage tests in this file mirror that shape via runStepForTest",
        )
        // The runStep wrap at the call site must also remain so the
        // logged-and-swallowed test stays representative.
        assertTrue(
            source.contains("""runStep("apply-persisted-vcs-colors") { applyPersistedVcsColors(settings) }"""),
            "applyPersistedVcsColors must remain wrapped in runStep(\"apply-persisted-vcs-colors\") { ... }",
        )
    }

    @Test
    fun `startup re-applies persisted syntax intensity before migration notification`() {
        val source = readStartupActivitySource()
        val reapplyCall =
            Regex(
                """dispatchWriteSafe\s*\{\s*""" +
                    """SyntaxIntensityService\.getInstance\(\)\.reapplyForActiveLaf\(\)\s*}""",
            )
        val notificationCall =
            Regex(
                """dispatchWriteSafe\s*\{\s*""" +
                    """SyntaxIntensityMigrationNotifier\.maybeFire\(project\)\s*}""",
            )
        val reapplyIndex = reapplyCall.find(source)?.range?.first ?: -1
        val notificationIndex = notificationCall.find(source)?.range?.first ?: -1

        assertTrue(reapplyIndex >= 0, "startup must reapply persisted syntax intensity after scheme registration")
        assertTrue(
            notificationIndex >= 0,
            "startup must queue the syntax intensity migration notification in a write-safe context",
        )
        assertFalse(
            source.contains("SwingUtilities.invokeLater { SyntaxIntensityMigrationNotifier.maybeFire(project) }"),
            "the migration notification must not bypass the non-modal IntelliJ queue",
        )
        assertTrue(
            reapplyIndex < notificationIndex,
            "persisted syntax intensity must enter the non-modal queue before the migration notification",
        )
    }

    @Test
    fun `startup installs the scan-completion accent refresher before the first accent resolve`() {
        // Regression guard: `runStartupAccentOnEdt` can schedule the first
        // background language scan (EDT resolve on a cold detector cache).
        // `ScanCompletionAccentRefresher` owns the post-scan accent re-apply,
        // so it must subscribe BEFORE that first resolve — otherwise the
        // startup scan's completion fires into an empty subscriber list and
        // the accent stays on the global fallback until the next focus swap.
        // Like the EDT-wrap guards above, the invariant is source-level:
        // dynamically invoking execute() needs a full project fixture.
        val source = readStartupActivitySource()
        val installCall = "ScanCompletionAccentRefresher.getInstance(project)"
        val firstResolveCall = "runStartupAccentOnEdt(project, variant)"
        val installIndex = source.indexOf(installCall)
        val resolveIndex = source.indexOf(firstResolveCall)

        assertTrue(installIndex >= 0, "startup must install ScanCompletionAccentRefresher")
        assertTrue(resolveIndex >= 0, "startup must still run the initial accent resolve")
        assertTrue(
            installIndex < resolveIndex,
            "ScanCompletionAccentRefresher must be installed before runStartupAccentOnEdt " +
                "schedules the first background language scan",
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
