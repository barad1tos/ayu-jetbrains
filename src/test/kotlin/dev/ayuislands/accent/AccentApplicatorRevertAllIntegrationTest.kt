package dev.ayuislands.accent

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.MessageBus
import com.nasller.codeglance.config.CodeGlanceConfigService
import dev.ayuislands.AyuPlugin
import dev.ayuislands.indent.IndentRainbowSync
import dev.ayuislands.integration.IntegrationOutcome
import dev.ayuislands.integration.IntegrationOwnership
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.ui.ComponentTreeRefresher
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral coverage for [AccentApplicator.revertAll] integration revert wiring.
 *
 * Pre-fix, `revertAll()` cleared UIManager keys but never reverted the two
 * integrations that read app-scoped state — IndentRainbow's `IrConfig` and
 * CodeGlance Pro's `CodeGlanceConfigService`. After a theme switch, the user
 * still saw the old Ayu accent in the indent palette and the CGP minimap
 * viewport because both integrations' app-scoped caches kept the last
 * apply()'d hex.
 *
 * The integration revert wiring covers:
 *   - `IndentRainbowSync.revert()` — already public, never wired before
 *   - `revertCodeGlanceProViewport()` — mirror of `syncCodeGlanceProViewport`,
 *     restores documented javap-verified defaults ("00FF00", "A0A0A0", 0)
 *   - `codeGlanceProRevertHook` ThreadLocal observer — lets tests assert the
 *     three default values are written without bringing CGP into the test
 *     classpath (matches the [ChromeDecorationsProbe.osSupplier] template)
 *
 * The tests rely on:
 *   - `AccentApplicator.codeGlanceProRevertHook` (ThreadLocal)
 *   - `AccentApplicator.resetCodeGlanceProRevertHookForTests`
 */
class AccentApplicatorRevertAllIntegrationTest {
    private val mockScheme = mockk<EditorColorsScheme>(relaxed = true)
    private val mockColorsManager = mockk<EditorColorsManager>(relaxed = true)
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockMessageBus = mockk<MessageBus>(relaxed = true)
    private val mockProjectManager = mockk<ProjectManager>(relaxed = true)

    private var originalEpName: ExtensionPointName<AccentElement>? = null

    @BeforeTest
    fun setUp() {
        saveOriginalEpName()

        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns true

        mockkStatic(UIManager::class)

        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns mockColorsManager
        every { mockColorsManager.globalScheme } returns mockScheme
        every { mockScheme.getAttributes(any<TextAttributesKey>()) } returns TextAttributes()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.messageBus } returns mockMessageBus
        every { mockMessageBus.syncPublisher(EditorColorsManager.TOPIC) } returns mockk(relaxed = true)

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state
        // Default: CGP integration enabled so revertCodeGlanceProViewport reaches
        // the hook. Per-test overrides flip this to exercise the gate.
        state.cgpIntegrationEnabled = true
        state.isCgpOwnershipMigrated = true

        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns emptyArray()

        mockkObject(ComponentTreeRefresher)
        every { ComponentTreeRefresher.notifyOnly(any()) } returns Unit

        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.revert() } returns IntegrationOutcome.Skipped

        // Default to an empty EP list so revertAll's loop runs without each test
        // re-stubbing the EP. Ensures the new IR/CGP revert calls are reachable.
        mockEpExtensionList(emptyList())
    }

    @AfterTest
    fun tearDown() {
        // Pattern I: ThreadLocal seam reset is mandatory because per-test
        // try/finally inside the test body cannot recover from an assertion
        // failure that exits the worker mid-test. The class-level teardown is
        // the safety net.
        AccentApplicator.resetCodeGlanceProRevertHookForTests()
        // Reflection-path tests stash mocks into [CodeGlanceProIntegration]'s
        // private reflection cache. Without this reset, a test failure could
        // leave pinned mocks visible to a subsequent test in the same worker JVM.
        CodeGlanceProIntegration.resetReflectionCache()
        restoreOriginalEpName()
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `revertAll calls IndentRainbowSync revert`() {
        AccentApplicator.revertAll()

        verify(exactly = 1) { IndentRainbowSync.revert() }
    }

    @Test
    fun `revertAll calls revertCodeGlanceProViewport`() {
        seedOwnedCgp()
        val observed = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.codeGlanceProRevertHook.set { c, bc, bt -> observed += Triple(c, bc, bt) }
        try {
            AccentApplicator.revertAll()
        } finally {
            AccentApplicator.resetCodeGlanceProRevertHookForTests()
        }
        assertEquals(
            1,
            observed.size,
            "revertCodeGlanceProViewport must fire exactly once from revertAll",
        )
    }

    @Test
    fun `revertCodeGlanceProViewport writes captured baseline via hook`() {
        seedOwnedCgp()
        val observed = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.codeGlanceProRevertHook.set { c, bc, bt -> observed += Triple(c, bc, bt) }
        try {
            AccentApplicator.revertAll()
        } finally {
            AccentApplicator.resetCodeGlanceProRevertHookForTests()
        }
        assertEquals(listOf(Triple("123456", "654321", 2)), observed)
    }

    @Test
    fun `each integration revert is isolated by RuntimeException catch`() {
        // Pattern B regression lock: IR's revert throwing must NOT block CGP's
        // revert. Both integrations are wrapped in narrow `RuntimeException`
        // catches.
        every { IndentRainbowSync.revert() } throws RuntimeException("IR exploded")
        seedOwnedCgp()

        val cgpObserved = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.codeGlanceProRevertHook.set { c, bc, bt -> cgpObserved += Triple(c, bc, bt) }
        try {
            AccentApplicator.revertAll() // MUST NOT throw — IR failure is caught
        } finally {
            AccentApplicator.resetCodeGlanceProRevertHookForTests()
        }

        assertEquals(
            1,
            cgpObserved.size,
            "CGP revert must still fire when IR revert throws (Pattern B isolation)",
        )
    }

    @Test
    fun `revertAll completes notifyOnly after integration revert throws`() {
        // The downstream notifyOnly loop runs even when IR revert throws —
        // subscribers (EditorScrollbarManager etc.) need the refresh topic so
        // their customizations re-apply against the freshly-cleared UIManager.
        val project = mockProject()
        every { mockProjectManager.openProjects } returns arrayOf(project)
        every { IndentRainbowSync.revert() } throws RuntimeException("IR exploded")

        AccentApplicator.revertAll()

        verify(exactly = 1) { ComponentTreeRefresher.notifyOnly(project) }
    }

    @Test
    fun `revertAll continues past a throwing UI-clear step and still unwinds integrations`() {
        // Widened isolation lock (ContinuePerStep): pre-plan code isolated only
        // the IR/CGP reverts — a throw while clearing UIManager keys aborted the
        // whole revert and stranded integrations tinted across a theme switch.
        // Every step is now isolated in the runner, so the tail must still run.
        val project = mockProject()
        every { mockProjectManager.openProjects } returns arrayOf(project)
        every { UIManager.put(any<String>(), isNull()) } throws RuntimeException("UI clear exploded")
        seedOwnedCgp()

        val events = mutableListOf<String>()
        every { IndentRainbowSync.revert() } answers {
            events += "ir_revert"
            IntegrationOutcome.Restored
        }
        AccentApplicator.codeGlanceProRevertHook.set { _, _, _ -> events += "cgp_revert" }
        try {
            AccentApplicator.revertAll() // MUST NOT throw — every step is isolated
        } finally {
            AccentApplicator.resetCodeGlanceProRevertHookForTests()
        }

        assertEquals(listOf("ir_revert", "cgp_revert"), events)
        verify(exactly = 1) { ComponentTreeRefresher.notifyOnly(project) }
    }

    @Test
    fun `revertAll orders IR revert before CGP revert before notifyOnly`() {
        // Ordering lock: integrations BEFORE notifyOnly so subscribers see
        // consistent app-scoped state when they decide to repaint. Pattern G + L:
        // capture every observable side-effect on a single `events` timeline and
        // assert the EXACT interleaving. A weaker
        // `verifyOrder { IR; notifyOnly }` + `cgpCalls.size == 1` pair would
        // still pass if CGP fired BEFORE IR (size stays 1, IR-before-notifyOnly
        // holds) — the explicit timeline closes that hole.
        val project = mockProject()
        every { mockProjectManager.openProjects } returns arrayOf(project)
        seedOwnedCgp()

        val events = mutableListOf<String>()
        every { IndentRainbowSync.revert() } answers {
            events += "ir_revert"
            IntegrationOutcome.Restored
        }
        every { ComponentTreeRefresher.notifyOnly(project) } answers {
            events += "notify_only"
        }

        val cgpCalls = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.codeGlanceProRevertHook.set { c, bc, bt ->
            cgpCalls += Triple(c, bc, bt)
            events += "cgp_revert"
        }
        try {
            AccentApplicator.revertAll()
        } finally {
            AccentApplicator.resetCodeGlanceProRevertHookForTests()
        }

        // Sanity: every hook fired exactly once. Keeps the existing size gate
        // honest so a future regression that drops a hook entirely still trips.
        assertEquals(
            1,
            cgpCalls.size,
            "CGP revert hook must have fired exactly once (single-fire gate)",
        )
        verify(exactly = 1) { IndentRainbowSync.revert() }
        verify(exactly = 1) { ComponentTreeRefresher.notifyOnly(project) }

        // Exclusive ordering: IR -> CGP -> notifyOnly with NO interleaving.
        // assertEquals on the literal list locks both relative position AND
        // the absence of additional hooks firing in between.
        assertEquals(
            listOf("ir_revert", "cgp_revert", "notify_only"),
            events,
            "revertAll MUST fire IR revert -> CGP revert -> notifyOnly in that " +
                "exact order with no interleaving (ordering lock — observed: $events)",
        )
    }

    @Test
    fun `revertCodeGlanceProViewport invokes hook even when cgpIntegrationEnabled false`() {
        // Regression lock. Pre-fix: a `cgpIntegrationEnabled = false`
        // gate at the top of `revertCodeGlanceProViewport` short-circuited
        // every revert call, so a user who toggled CGP off after an apply was
        // stuck with CGP's app-scoped cache holding the previous Ayu accent
        // forever — the apply path stamped CGP, the toggle prevented further
        // writes, and the revert path silently became a no-op. Post-fix: the
        // gate moves to `syncCodeGlanceProViewport`'s entry, which mirrors
        // `IndentRainbowSync`. The revert path runs unconditionally so theme
        // switch / license loss can clean up CGP regardless of toggle state.
        //
        // Pattern G + J — apply/revert symmetry. revertAll fires the hook
        // because the path is reachable from every revertAll call (theme
        // switch, license loss); the toggle does NOT gate cleanup.
        state.cgpIntegrationEnabled = false
        seedOwnedCgp()
        var hookInvoked = false
        AccentApplicator.codeGlanceProRevertHook.set { _, _, _ -> hookInvoked = true }
        try {
            AccentApplicator.revertAll()
        } finally {
            AccentApplicator.resetCodeGlanceProRevertHookForTests()
        }
        assertTrue(
            hookInvoked,
            "Hook MUST fire even when cgpIntegrationEnabled is false — revert path " +
                "is reachable on theme-switch / license-loss regardless of toggle state " +
                "(Pattern G/J).",
        )
    }

    @Test
    fun `syncCodeGlanceProViewport with cgpIntegrationEnabled false restores captured baseline`() {
        // Toggle-off after a previous apply. Without this fix, the
        // disabled-branch returns silently, leaving CGP's app-scoped cache
        // tinted with the previous Ayu accent forever. Mirrors
        // IndentRainbowSync.apply, which already reverts on
        // !irIntegrationEnabled.
        state.cgpIntegrationEnabled = false
        seedOwnedCgp()
        val observed = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.codeGlanceProRevertHook.set { c, bc, bt -> observed += Triple(c, bc, bt) }
        try {
            CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")
        } finally {
            AccentApplicator.resetCodeGlanceProRevertHookForTests()
        }
        assertEquals(
            listOf(Triple("123456", "654321", 2)),
            observed,
            "Disabling CGP integration must restore the exact pre-Ayu viewport.",
        )
    }

    @Test
    fun `syncCodeGlanceProViewport with external permission disabled fires revert path`() {
        state.cgpIntegrationEnabled = true
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeCodeGlanceProEnabled = false
        seedOwnedCgp()
        val observed = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.codeGlanceProRevertHook.set { color, borderColor, borderThickness ->
            observed += Triple(color, borderColor, borderThickness)
        }
        try {
            CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6", AccentContext.External)
        } finally {
            AccentApplicator.resetCodeGlanceProRevertHookForTests()
        }
        assertEquals(
            listOf(Triple("123456", "654321", 2)),
            observed,
            "External CodeGlance Pro permission OFF must restore the captured viewport.",
        )
    }

    @Test
    fun `syncCodeGlanceProViewportForSwap delegates to CodeGlanceProIntegration syncCodeGlanceProViewport`() {
        // Regression lock. Pre-fix: AccentApplicator.syncCodeGlanceProViewportForSwap
        // was a one-line wrapper "verified by association" — its only test was
        // that it did NOT throw. A future agent who deleted the wrapper or
        // renamed the underlying call would silently break the swap path's
        // CGP refresh. This test stages a non-null reflection chain via mocks,
        // calls the wrapper directly, and verifies the inner setter receives
        // the hex with the # prefix stripped (CGP rejects # silently).
        val mockConfig = mockk<Any>(relaxed = true)
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns mockConfig
        every { mockSetColor.invoke(mockConfig, any()) } returns null
        every { mockSetBorderColor.invoke(mockConfig, any()) } returns null
        every { mockSetBorderThickness.invoke(mockConfig, any()) } returns null

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        AccentApplicator.syncCodeGlanceProViewportForSwap("#5CCFE6")

        // Hex stripped of the # prefix per CGP's plain-string contract.
        verify(exactly = 1) { mockSetColor.invoke(mockConfig, "5CCFE6") }
        verify(exactly = 1) { mockSetBorderColor.invoke(mockConfig, "5CCFE6") }
        verify(exactly = 1) { mockSetBorderThickness.invoke(mockConfig, 1) }
    }

    @Test
    fun `syncCodeGlanceProViewport resolves CGP app service dynamically and writes viewport state`() {
        val cgpService = CodeGlanceConfigService()
        mockkObject(AyuPlugin)
        val mockPlugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { AyuPlugin.findLoadedPlugin(any()) } returns mockPlugin
        every { mockPlugin.pluginClassLoader } returns cgpService.javaClass.classLoader
        every { mockApplication.getService(any<Class<*>>()) } returns cgpService

        CodeGlanceProIntegration.resetReflectionCache()
        CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")

        val state = cgpService.getState()
        assertEquals(
            "5CCFE6",
            state.viewportColor,
            "CGP must receive the accent hex without the # prefix through its app service.",
        )
        assertEquals(
            "5CCFE6",
            state.viewportBorderColor,
            "CGP viewport border must match the accent written through the dynamic app service.",
        )
        assertEquals(1, state.viewportBorderThickness, "Ayu accent sync must enable the CGP viewport border.")
    }

    @Test
    fun `integration failure keeps the accent apply marked incomplete`() {
        mockkObject(AccentContext.Companion)
        every { AccentContext.detect() } returns AccentContext.Ayu(AyuVariant.MIRAGE)
        every {
            IndentRainbowSync.apply(any<AccentContext>(), any())
        } returns
            IntegrationOutcome.Failed(
                operation = "sync failed",
                error = IllegalStateException("Indent Rainbow rejected the palette"),
            )

        AccentApplicator.apply(AccentHex.require("#5CCFE6"))

        assertEquals(false, state.lastApplyOk)
    }

    @Test
    fun `first sync captures exact CodeGlance Pro viewport before writing Ayu values`() {
        val cgpService = installCgpService()
        val viewport = cgpService.getState()
        viewport.setViewportColor("112233")
        viewport.setViewportBorderColor("445566")
        viewport.setViewportBorderThickness(3)

        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")

        assertEquals(IntegrationOutcome.Applied, outcome)
        assertEquals(IntegrationOwnership.OWNED.name, state.cgpOwnership)
        assertEquals("112233", state.cgpBaseColor)
        assertEquals("445566", state.cgpBaseBorder)
        assertEquals(3, state.cgpBaseThickness)
        assertEquals("5CCFE6", state.cgpAppliedColor)
        assertEquals("5CCFE6", state.cgpAppliedBorder)
        assertEquals(1, state.cgpAppliedThickness)
    }

    @Test
    fun `restore writes CodeGlance Pro baseline only while current values match last applied`() {
        val cgpService = installCgpService()
        val viewport = cgpService.getState()
        viewport.setViewportColor("112233")
        viewport.setViewportBorderColor("445566")
        viewport.setViewportBorderThickness(3)
        CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")

        val outcome = CodeGlanceProIntegration.restoreOwnedState()

        assertEquals(IntegrationOutcome.Restored, outcome)
        assertEquals("112233", viewport.viewportColor)
        assertEquals("445566", viewport.viewportBorderColor)
        assertEquals(3, viewport.viewportBorderThickness)
        assertEquals(IntegrationOwnership.UNOWNED.name, state.cgpOwnership)
        assertEquals(null, state.cgpBaseColor)
        assertEquals(null, state.cgpAppliedColor)
    }

    @Test
    fun `manual CodeGlance Pro edit suspends ownership without another Ayu write`() {
        val cgpService = installCgpService()
        val viewport = cgpService.getState()
        viewport.setViewportColor("112233")
        viewport.setViewportBorderColor("445566")
        viewport.setViewportBorderThickness(3)
        CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")
        viewport.setViewportColor("ABCDEF")

        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#FFCC66")

        assertEquals(IntegrationOutcome.Skipped, outcome)
        assertEquals(IntegrationOwnership.SUSPENDED.name, state.cgpOwnership)
        assertEquals("ABCDEF", viewport.viewportColor)
        assertEquals("5CCFE6", viewport.viewportBorderColor)
        assertEquals(1, viewport.viewportBorderThickness)
    }

    @Test
    fun `legacy CodeGlance Pro state suspends instead of capturing an Ayu viewport as baseline`() {
        state.isCgpOwnershipMigrated = false
        val cgpService = installCgpService()
        val viewport = cgpService.getState()
        viewport.setViewportColor("5CCFE6")
        viewport.setViewportBorderColor("5CCFE6")
        viewport.setViewportBorderThickness(1)

        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#FFCC66")

        assertEquals(IntegrationOutcome.Skipped, outcome)
        assertEquals(IntegrationOwnership.SUSPENDED.name, state.cgpOwnership)
        assertTrue(state.isCgpOwnershipMigrated)
        assertEquals("5CCFE6", viewport.viewportColor)
        assertEquals("5CCFE6", viewport.viewportBorderColor)
        assertEquals(1, viewport.viewportBorderThickness)
        assertEquals(null, state.cgpBaseColor)
    }

    @Test
    fun `failed CodeGlance Pro restore rolls a partial write back to the applied viewport`() {
        val cgpService = installCgpService()
        val viewport = cgpService.getState()
        viewport.setViewportColor("112233")
        viewport.setViewportBorderColor("445566")
        viewport.setViewportBorderThickness(3)
        CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")
        viewport.shouldRejectNextBorder = true

        val outcome = CodeGlanceProIntegration.restoreOwnedState()

        assertTrue(outcome is IntegrationOutcome.Failed)
        assertEquals("5CCFE6", viewport.viewportColor)
        assertEquals("5CCFE6", viewport.viewportBorderColor)
        assertEquals(1, viewport.viewportBorderThickness)
        assertEquals(IntegrationOwnership.OWNED.name, state.cgpOwnership)
        assertEquals("112233", state.cgpBaseColor)
    }

    @Test
    fun `failed CodeGlance Pro restore and rollback remain recoverable`() {
        val cgpService = installCgpService()
        val viewport = cgpService.getState()
        viewport.setViewportColor("112233")
        viewport.setViewportBorderColor("445566")
        viewport.setViewportBorderThickness(3)
        CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")
        viewport.shouldRejectNextThicknessAndRollbackBorder = true

        val failed = CodeGlanceProIntegration.restoreOwnedState()

        assertTrue(failed is IntegrationOutcome.Failed)
        assertEquals(IntegrationOwnership.RECOVERY_PENDING.name, state.cgpOwnership)
        assertEquals(IntegrationOutcome.Restored, CodeGlanceProIntegration.restoreOwnedState())
        assertEquals("112233", viewport.viewportColor)
        assertEquals("445566", viewport.viewportBorderColor)
        assertEquals(3, viewport.viewportBorderThickness)
        assertEquals(IntegrationOwnership.UNOWNED.name, state.cgpOwnership)
    }

    @Test
    fun `failed first CodeGlance Pro write recovers the original baseline before retry`() {
        val cgpService = installCgpService()
        val viewport = cgpService.getState()
        viewport.setViewportColor("112233")
        viewport.setViewportBorderColor("445566")
        viewport.setViewportBorderThickness(3)
        viewport.shouldRejectNextThicknessAndRollbackBorder = true

        val failed = CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")

        assertTrue(failed is IntegrationOutcome.Failed)
        assertEquals(IntegrationOwnership.RECOVERY_PENDING.name, state.cgpOwnership)
        assertEquals("112233", state.cgpBaseColor)
        assertEquals("445566", state.cgpBaseBorder)
        assertEquals(3, state.cgpBaseThickness)

        assertEquals(
            IntegrationOutcome.Applied,
            CodeGlanceProIntegration.syncCodeGlanceProViewport("#FFCC66"),
        )
        assertEquals(IntegrationOutcome.Restored, CodeGlanceProIntegration.restoreOwnedState())
        assertEquals("112233", viewport.viewportColor)
        assertEquals("445566", viewport.viewportBorderColor)
        assertEquals(3, viewport.viewportBorderThickness)
    }

    @Test
    fun `failed pending CodeGlance Pro recovery captures the partial viewport for retry`() {
        val cgpService = installCgpService()
        val viewport = cgpService.getState()
        viewport.setViewportColor("112233")
        viewport.setViewportBorderColor("445566")
        viewport.setViewportBorderThickness(3)
        viewport.shouldRejectNextThicknessAndRollbackBorder = true
        assertTrue(
            CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6") is IntegrationOutcome.Failed,
        )
        viewport.shouldRejectNextBorder = true

        val failed = CodeGlanceProIntegration.restoreOwnedState()

        assertTrue(failed is IntegrationOutcome.Failed)
        assertEquals(IntegrationOwnership.RECOVERY_PENDING.name, state.cgpOwnership)
        assertEquals(IntegrationOutcome.Restored, CodeGlanceProIntegration.restoreOwnedState())
        assertEquals("112233", viewport.viewportColor)
        assertEquals("445566", viewport.viewportBorderColor)
        assertEquals(3, viewport.viewportBorderThickness)
    }

    @Test
    fun `transient CodeGlance Pro resolution failure preserves owned recovery`() {
        seedOwnedCgp()
        mockkObject(AyuPlugin)
        val brokenPlugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { AyuPlugin.findLoadedPlugin(any()) } returns brokenPlugin
        every { brokenPlugin.pluginClassLoader } returns MissingCgpClassLoader

        val failed = CodeGlanceProIntegration.restoreOwnedState()

        assertTrue(failed is IntegrationOutcome.Failed)
        assertEquals(IntegrationOwnership.OWNED.name, state.cgpOwnership)

        val cgpService = installCgpService(shouldResetCache = false)
        val viewport = cgpService.getState()
        viewport.setViewportColor("00FF00")
        viewport.setViewportBorderColor("A0A0A0")
        viewport.setViewportBorderThickness(0)
        assertEquals(IntegrationOutcome.Restored, CodeGlanceProIntegration.restoreOwnedState())
        assertEquals("123456", viewport.viewportColor)
        assertEquals("654321", viewport.viewportBorderColor)
        assertEquals(2, viewport.viewportBorderThickness)
    }

    @Test
    fun `manual CodeGlance Pro edit prevents pending recovery from overwriting the viewport`() {
        val cgpService = installCgpService()
        val viewport = cgpService.getState()
        viewport.setViewportColor("112233")
        viewport.setViewportBorderColor("445566")
        viewport.setViewportBorderThickness(3)
        viewport.shouldRejectNextThicknessAndRollbackBorder = true
        assertTrue(CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6") is IntegrationOutcome.Failed)
        viewport.setViewportColor("ABCDEF")
        viewport.setViewportBorderColor("FEDCBA")
        viewport.setViewportBorderThickness(7)

        val outcome = CodeGlanceProIntegration.restoreOwnedState()

        assertEquals(IntegrationOutcome.Skipped, outcome)
        assertEquals(IntegrationOwnership.SUSPENDED.name, state.cgpOwnership)
        assertEquals("ABCDEF", viewport.viewportColor)
        assertEquals("FEDCBA", viewport.viewportBorderColor)
        assertEquals(7, viewport.viewportBorderThickness)
    }

    @Test
    fun `missing CodeGlance Pro baseline never writes documented defaults`() {
        val cgpService = installCgpService()
        val viewport = cgpService.getState()
        viewport.setViewportColor("5CCFE6")
        viewport.setViewportBorderColor("5CCFE6")
        viewport.setViewportBorderThickness(1)
        state.cgpOwnership = IntegrationOwnership.OWNED.name
        state.cgpAppliedColor = "5CCFE6"
        state.cgpAppliedBorder = "5CCFE6"
        state.cgpAppliedThickness = 1

        val outcome = CodeGlanceProIntegration.restoreOwnedState()

        assertEquals(IntegrationOutcome.Skipped, outcome)
        assertEquals(IntegrationOwnership.SUSPENDED.name, state.cgpOwnership)
        assertEquals("5CCFE6", viewport.viewportColor)
        assertEquals("5CCFE6", viewport.viewportBorderColor)
        assertEquals(1, viewport.viewportBorderThickness)
    }

    @Test
    fun `revertCodeGlanceProViewport via reflection restores captured baseline in order`() {
        seedOwnedCgp()
        val mockConfig = mockk<Any>(relaxed = true)
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns mockConfig

        val callOrder = mutableListOf<String>()
        every { mockSetColor.invoke(mockConfig, "123456") } answers {
            callOrder += "color"
            null
        }
        every { mockSetBorderColor.invoke(mockConfig, "654321") } answers {
            callOrder += "border-color"
            null
        }
        every { mockSetBorderThickness.invoke(mockConfig, 2) } answers {
            callOrder += "border-thickness"
            null
        }

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        CodeGlanceProIntegration.revertCodeGlanceProViewport()

        assertEquals(
            listOf("color", "border-color", "border-thickness"),
            callOrder,
            "CGP restore must reproduce the captured pre-Ayu viewport in a stable order.",
        )
    }

    @Test
    fun `revertCodeGlanceProViewport handles InvocationTargetException gracefully`() {
        // Catch-path coverage. CGP setters can throw via reflection if upstream
        // renames or guards a setter. The InvocationTargetException catch must
        // swallow the failure with a WARN — a thrown exception here would
        // propagate up through revertAll and break theme switch.
        val mockConfig = mockk<Any>(relaxed = true)
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns mockConfig
        every { mockSetColor.invoke(mockConfig, any()) } throws
            java.lang.reflect.InvocationTargetException(IllegalStateException("CGP setter rejected"))

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        // Expectation: no throw. Test fails (assertion in finally) only if
        // the InvocationTargetException catch is dropped from the source.
        CodeGlanceProIntegration.revertCodeGlanceProViewport()
    }

    @Test
    fun `revertCodeGlanceProViewport handles ReflectiveOperationException gracefully`() {
        // Catch-path coverage for the IllegalAccessException /
        // NoSuchMethodException class. Same argument as the InvocationTargetException
        // case — must swallow without propagating to revertAll.
        val mockConfig = mockk<Any>(relaxed = true)
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns mockConfig
        every { mockSetColor.invoke(mockConfig, any()) } throws
            IllegalAccessException("CGP setter inaccessible")

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        CodeGlanceProIntegration.revertCodeGlanceProViewport()
    }

    @Test
    fun `revertCodeGlanceProViewport passes hex without hash prefix to CGP setters`() {
        // Contract lock. CGP rejects '#' silently — its setters store the value
        // as-is, so '#5CCFE6' would be persisted as a literal 7-char string and
        // the minimap would render with a broken hex. The defaults are
        // pre-stripped (00FF00, A0A0A0); this test pins that the values passed
        // have no '#' character regardless of how the constants were declared.
        val passedValues = mutableListOf<Any?>()
        val mockConfig = mockk<Any>(relaxed = true)
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns mockConfig
        every { mockSetColor.invoke(mockConfig, any()) } answers {
            passedValues += secondArg<Any?>()
            null
        }
        every { mockSetBorderColor.invoke(mockConfig, any()) } answers {
            passedValues += secondArg<Any?>()
            null
        }
        every { mockSetBorderThickness.invoke(mockConfig, any()) } returns null

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        CodeGlanceProIntegration.revertCodeGlanceProViewport()

        for (value in passedValues) {
            assertEquals(
                false,
                (value as? String)?.contains("#") ?: false,
                "CGP setter MUST receive a hex without '#' prefix — got '$value'. " +
                    "CGP would store '#XXXXXX' as a literal 7-char string and render broken hex.",
            )
        }
    }

    @Test
    fun `syncCodeGlanceProViewport via reflection writes hex without hash to setters in order`() {
        // Mirrors the reflection test for
        // [CodeGlanceProIntegration.revertCodeGlanceProViewport]. Pre-test, the
        // `syncCodeGlanceProViewport` reflection happy path (CodeGlanceProIntegration.kt
        // lines ~152-169) had no behavior-locked test — only the
        // `cgpService ?: return` short-circuit was hit. This stages the
        // reflection chain via `installCgpReflectionMocks`, calls sync
        // directly, and verifies all three setters fire with the `#`
        // stripped from the input AND in the documented order: color,
        // border-color, border-thickness=1 (active accent thickness, NOT
        // the 0 default the revert path writes). Pattern G — apply/revert
        // symmetry: sync writes thickness=1, revert writes thickness=0.
        val mockConfig = mockk<Any>(relaxed = true)
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns mockConfig

        val callOrder = mutableListOf<String>()
        every { mockSetColor.invoke(mockConfig, "5CCFE6") } answers {
            callOrder += "color"
            null
        }
        every { mockSetBorderColor.invoke(mockConfig, "5CCFE6") } answers {
            callOrder += "border-color"
            null
        }
        every { mockSetBorderThickness.invoke(mockConfig, 1) } answers {
            callOrder += "border-thickness"
            null
        }

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")

        assertEquals(
            listOf("color", "border-color", "border-thickness"),
            callOrder,
            "sync MUST call setViewportColor, setViewportBorderColor, " +
                "setViewportBorderThickness in that order with the # prefix " +
                "stripped — order regression would tint the minimap before the " +
                "border, and a leaked `#` would persist as a literal 7-char string.",
        )
    }

    @Test
    fun `syncCodeGlanceProViewport via reflection accepts bare hex with no hash prefix`() {
        // Pattern L source-regex lock for `accentHex.removePrefix("#")`
        // (CodeGlanceProIntegration.kt line 161). When the caller passes a bare
        // hex (no `#`), removePrefix is a no-op and the raw 6-char string flows
        // to the setter unchanged. Locks the contract that production accepts
        // BOTH `#XXXXXX` and `XXXXXX` forms — a regression to
        // `accentHex.substring(1)` would silently drop the first character of
        // the bare-hex callsite.
        val mockConfig = mockk<Any>(relaxed = true)
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns mockConfig
        every { mockSetColor.invoke(mockConfig, any()) } returns null
        every { mockSetBorderColor.invoke(mockConfig, any()) } returns null
        every { mockSetBorderThickness.invoke(mockConfig, any()) } returns null

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        CodeGlanceProIntegration.syncCodeGlanceProViewport("FFCC66")

        verify(exactly = 1) { mockSetColor.invoke(mockConfig, "FFCC66") }
        verify(exactly = 1) { mockSetBorderColor.invoke(mockConfig, "FFCC66") }
        verify(exactly = 1) { mockSetBorderThickness.invoke(mockConfig, 1) }
    }

    @Test
    fun `syncCodeGlanceProViewport via reflection short-circuits when getState returns null`() {
        // Pattern L: defensive guard at `getState.invoke(service) ?: return`
        // (CodeGlanceProIntegration.kt line 162). Unreachable in production
        // today (CGP's getState always returns the cached state object), but
        // the guard exists so a future CGP version that returns null mid-init
        // does not NPE through to the setters. This test pins the branch so a
        // refactor that drops `?: return` would visibly call setters on a null
        // config.
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns null

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")

        verify(exactly = 0) { mockSetColor.invoke(any(), any()) }
        verify(exactly = 0) { mockSetBorderColor.invoke(any(), any()) }
        verify(exactly = 0) { mockSetBorderThickness.invoke(any(), any()) }
    }

    @Test
    fun `syncCodeGlanceProViewport via reflection handles InvocationTargetException gracefully`() {
        // Pattern B isolation: CGP's setters can throw via reflection if
        // upstream guards a setter. The `InvocationTargetException` catch at
        // CodeGlanceProIntegration.kt lines 170-176 must swallow the failure
        // with a WARN — a thrown exception here would propagate up through
        // `AccentApplicator.apply` and break a theme apply.
        val mockConfig = mockk<Any>(relaxed = true)
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns mockConfig
        every { mockSetColor.invoke(mockConfig, any()) } throws
            java.lang.reflect.InvocationTargetException(IllegalStateException("CGP setter rejected"))

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        // Expectation: no throw. Test fails only if the
        // `InvocationTargetException` catch is dropped from the source.
        CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")
    }

    @Test
    fun `syncCodeGlanceProViewport via reflection handles ReflectiveOperationException gracefully`() {
        // Pattern B isolation: catch path at CodeGlanceProIntegration.kt
        // lines 177-182 must swallow `IllegalAccessException` /
        // `NoSuchMethodException` without re-throwing.
        val mockConfig = mockk<Any>(relaxed = true)
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns mockConfig
        every { mockSetColor.invoke(mockConfig, any()) } throws
            IllegalAccessException("CGP setter inaccessible")

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")
    }

    @Test
    fun `syncCodeGlanceProViewport via reflection handles RuntimeException gracefully`() {
        // Pattern B isolation: the third catch at CodeGlanceProIntegration.kt
        // lines 183-189 covers a plain `RuntimeException` bubbling out of the
        // reflective invoke (e.g. CGP's setter dereferences a stale field
        // internally). Must NOT propagate to the theme apply caller.
        val mockConfig = mockk<Any>(relaxed = true)
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns mockConfig
        every { mockSetColor.invoke(mockConfig, any()) } throws
            IllegalStateException("CGP internal NPE")

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")
    }

    @Test
    fun `revertCodeGlanceProViewport via reflection short-circuits when getState returns null`() {
        // Pattern L: defensive guard at `getState.invoke(service) ?: return`
        // (CodeGlanceProIntegration.kt line 240). Mirror of the sync-side
        // getState-null lock — Pattern G symmetry. `codeGlanceProRevertHook`
        // left null so the production reflection path runs.
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns null

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        CodeGlanceProIntegration.revertCodeGlanceProViewport()

        verify(exactly = 0) { mockSetColor.invoke(any(), any()) }
        verify(exactly = 0) { mockSetBorderColor.invoke(any(), any()) }
        verify(exactly = 0) { mockSetBorderThickness.invoke(any(), any()) }
    }

    @Test
    fun `revertCodeGlanceProViewport via reflection handles RuntimeException gracefully`() {
        // Pattern B + G symmetry: existing reflection tests cover the
        // `InvocationTargetException` and `ReflectiveOperationException`
        // catches on revert; this pins the third catch
        // (CodeGlanceProIntegration.kt lines 259-265) so a regression that
        // narrows the catch surface back to two clauses would surface here
        // instead of breaking the user's theme switch when CGP's setter NPEs
        // internally.
        val mockConfig = mockk<Any>(relaxed = true)
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns mockConfig
        every { mockSetColor.invoke(mockConfig, any()) } throws
            IllegalStateException("CGP internal NPE on revert")

        installCgpReflectionMocks(
            service = mockService,
            getState = mockGetState,
            setColor = mockSetColor,
            setBorderColor = mockSetBorderColor,
            setBorderThickness = mockSetBorderThickness,
        )

        // Expectation: no throw. Test fails only if the third catch is
        // dropped from the source.
        CodeGlanceProIntegration.revertCodeGlanceProViewport()
    }

    @Test
    fun `revertAll continues integration revert after EP element revert throws`() {
        // Regression lock. Pre-fix observation: an EP element whose
        // revert() throws RuntimeException must NOT block downstream
        // integration reverts. The narrow catch in applyElements / EP loop
        // already isolates per-element failures, but the contract that
        // IR.revert + CGP revert still fire afterwards needed an explicit
        // test. Pattern B isolation + Pattern G symmetry.
        val brokenElement =
            mockk<AccentElement>(relaxed = true) {
                every { displayName } returns "broken-element"
                every { revert() } throws RuntimeException("EP element exploded on revert")
            }
        mockEpExtensionList(listOf(brokenElement))
        seedOwnedCgp()

        val cgpObserved = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.codeGlanceProRevertHook.set { c, bc, bt -> cgpObserved += Triple(c, bc, bt) }
        try {
            AccentApplicator.revertAll() // MUST NOT throw
        } finally {
            AccentApplicator.resetCodeGlanceProRevertHookForTests()
        }

        verify(exactly = 1) { brokenElement.revert() }
        verify(exactly = 1) { IndentRainbowSync.revert() }
        assertEquals(
            1,
            cgpObserved.size,
            "CGP revert hook MUST still fire after EP element revert throws " +
                "(isolation lock — Pattern B + G).",
        )
    }

    @Test
    fun `syncCodeGlanceProViewport resolveCgpMethods catches ReflectiveOperationException when CGP class missing`() {
        // Pattern B isolation: drives the `ReflectiveOperationException` catch
        // inside `resolveCgpMethods` (CodeGlanceProIntegration.kt lines 116-121).
        // When the CGP plugin reports a classloader but `Class.forName` cannot
        // locate `com.nasller.codeglance.config.CodeGlanceConfigService` (the
        // test classpath has no CGP), `Class.forName` throws
        // `ClassNotFoundException`. The catch must swallow with a WARN —
        // a propagated exception would break theme apply for users without
        // CGP installed but with a stale plugin entry.
        //
        // Reflection cache is reset in `@AfterTest` via
        // `CodeGlanceProIntegration.resetReflectionCache()`, otherwise the
        // `cgpMethodsResolved = true` flag set on entry would leak into
        // the next test.
        CodeGlanceProIntegration.resetReflectionCache()
        mockkObject(AyuPlugin)
        val mockPlugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { AyuPlugin.findLoadedPlugin(any()) } returns mockPlugin
        // Use a loader that behaves like a stale CGP plugin descriptor: the
        // plugin is present, but the service class is absent.
        every { mockPlugin.pluginClassLoader } returns MissingCgpClassLoader

        // No throw expected. The `cgpService` field stays null because the
        // catch swallows before it can be assigned.
        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")

        val serviceField = CodeGlanceProIntegration::class.java.getDeclaredField("cgpService")
        serviceField.isAccessible = true
        assertTrue(outcome is IntegrationOutcome.Failed)
        assertEquals(IntegrationOwnership.UNOWNED.name, state.cgpOwnership)
        assertEquals(
            null,
            serviceField.get(CodeGlanceProIntegration),
            "cgpService MUST stay null after `Class.forName` throws — the catch " +
                "must short-circuit before any field assignment.",
        )
    }

    @Test
    fun `syncCodeGlanceProViewport resolveCgpMethods catches RuntimeException when classloader misbehaves`() {
        // Pattern B isolation: drives the `RuntimeException` catch inside
        // `resolveCgpMethods` (CodeGlanceProIntegration.kt lines 122-127).
        // A classloader subclass that throws `IllegalStateException` from
        // `loadClass` exercises the second catch — covers a CGP plugin in a
        // weird state (corrupt jar, security manager rejection) where the
        // first catch's `ReflectiveOperationException` does NOT match.
        //
        // Reflection cache is reset in `@AfterTest` via
        // `CodeGlanceProIntegration.resetReflectionCache()`.
        CodeGlanceProIntegration.resetReflectionCache()
        mockkObject(AyuPlugin)
        val mockPlugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { AyuPlugin.findLoadedPlugin(any()) } returns mockPlugin
        every { mockPlugin.pluginClassLoader } returns BrokenClassLoader

        // No throw expected. `IllegalStateException` is a `RuntimeException`,
        // not a `ReflectiveOperationException` — the second catch handles it.
        val outcome = CodeGlanceProIntegration.syncCodeGlanceProViewport("#5CCFE6")

        val serviceField = CodeGlanceProIntegration::class.java.getDeclaredField("cgpService")
        serviceField.isAccessible = true
        assertTrue(outcome is IntegrationOutcome.Failed)
        assertEquals(IntegrationOwnership.UNOWNED.name, state.cgpOwnership)
        assertEquals(
            null,
            serviceField.get(CodeGlanceProIntegration),
            "cgpService MUST stay null after the classloader throws " +
                "RuntimeException — the broader catch must short-circuit before " +
                "any field assignment.",
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun installCgpReflectionMocks(
        service: Any,
        getState: java.lang.reflect.Method,
        setColor: java.lang.reflect.Method,
        setBorderColor: java.lang.reflect.Method,
        setBorderThickness: java.lang.reflect.Method,
    ) {
        // Stage non-null reflection chain so [revertCodeGlanceProViewport] /
        // [syncCodeGlanceProViewport] reach the production reflection branch
        // instead of short-circuiting on `cgpService ?: return`. Uses raw
        // field writes routed through the typed
        // [CodeGlanceProIntegration.resetReflectionCache] helper for cleanup.
        // Marks `cgpMethodsResolved = true` so `resolveCgpMethods` is a no-op
        // (we already supplied the cached refs).
        val ownerClass = CodeGlanceProIntegration::class.java
        val getColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val getBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val getBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { getColor.invoke(any()) } returns "00FF00"
        every { getBorderColor.invoke(any()) } returns "A0A0A0"
        every { getBorderThickness.invoke(any()) } returns 0
        ownerClass.getDeclaredField("cgpService").apply {
            isAccessible = true
            set(CodeGlanceProIntegration, service)
        }
        ownerClass.getDeclaredField("cgpGetState").apply {
            isAccessible = true
            set(CodeGlanceProIntegration, getState)
        }
        ownerClass.getDeclaredField("cgpGetColor").apply {
            isAccessible = true
            set(CodeGlanceProIntegration, getColor)
        }
        ownerClass.getDeclaredField("cgpGetBorder").apply {
            isAccessible = true
            set(CodeGlanceProIntegration, getBorderColor)
        }
        ownerClass.getDeclaredField("cgpGetThickness").apply {
            isAccessible = true
            set(CodeGlanceProIntegration, getBorderThickness)
        }
        ownerClass.getDeclaredField("cgpSetColor").apply {
            isAccessible = true
            set(CodeGlanceProIntegration, setColor)
        }
        ownerClass.getDeclaredField("cgpSetBorder").apply {
            isAccessible = true
            set(CodeGlanceProIntegration, setBorderColor)
        }
        ownerClass.getDeclaredField("cgpSetThickness").apply {
            isAccessible = true
            set(CodeGlanceProIntegration, setBorderThickness)
        }
        ownerClass.getDeclaredField("cgpMethodsResolved").apply {
            isAccessible = true
            set(CodeGlanceProIntegration, true)
        }
    }

    private fun installCgpService(shouldResetCache: Boolean = true): CodeGlanceConfigService {
        val service = CodeGlanceConfigService()
        mockkObject(AyuPlugin)
        val plugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { AyuPlugin.findLoadedPlugin(any()) } returns plugin
        every { plugin.pluginClassLoader } returns service.javaClass.classLoader
        every { mockApplication.getService(any<Class<*>>()) } returns service
        if (shouldResetCache) {
            CodeGlanceProIntegration.resetReflectionCache()
        }
        return service
    }

    private fun seedOwnedCgp() {
        state.cgpOwnership = IntegrationOwnership.OWNED.name
        state.cgpBaseColor = "123456"
        state.cgpBaseBorder = "654321"
        state.cgpBaseThickness = 2
        state.cgpAppliedColor = "00FF00"
        state.cgpAppliedBorder = "A0A0A0"
        state.cgpAppliedThickness = 0
    }

    private fun mockProject(): Project {
        val project = mockk<Project>(relaxed = true)
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        return project
    }

    private fun saveOriginalEpName() {
        if (originalEpName != null) return
        val epField = AccentApplicator::class.java.getDeclaredField("EP_NAME")
        epField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        originalEpName = epField.get(null) as ExtensionPointName<AccentElement>
    }

    private fun restoreOriginalEpName() {
        val original = originalEpName ?: return
        val epField = AccentApplicator::class.java.getDeclaredField("EP_NAME")
        epField.isAccessible = true
        unsafeWriteStaticField(epField, original)
        originalEpName = null
    }

    private fun mockEpExtensionList(elements: List<AccentElement>) {
        val epField = AccentApplicator::class.java.getDeclaredField("EP_NAME")
        epField.isAccessible = true
        val mockEp = mockk<ExtensionPointName<AccentElement>>(relaxed = true)
        every { mockEp.extensionList } returns elements
        unsafeWriteStaticField(epField, mockEp)
    }

    @Suppress("DEPRECATION")
    private fun unsafeWriteStaticField(
        field: java.lang.reflect.Field,
        value: Any?,
    ) {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val offset = unsafe.staticFieldOffset(field)
        unsafe.putObject(field.declaringClass, offset, value)
    }

    /**
     * Test-only `ClassLoader` whose `loadClass` throws plain
     * [IllegalStateException] (a [RuntimeException]) instead of
     * [ClassNotFoundException]. Used to exercise the broader
     * `RuntimeException` catch in `CodeGlanceProIntegration.resolveCgpMethods` — covers
     * the case where a CGP plugin's classloader is in a corrupt state
     * (security manager rejection, broken jar, native-load failure) where
     * the first `ReflectiveOperationException` catch does NOT match.
     *
     * Object-scoped (no per-test instance state) so the same loader can be
     * reused across tests without interfering with [unmockkAll] in
     * `@AfterTest`.
     */
    private object BrokenClassLoader : ClassLoader() {
        override fun loadClass(name: String?): Class<*> =
            error(
                "BrokenClassLoader rejects loadClass for '$name' — " +
                    "exercises the resolveCgpMethods RuntimeException catch.",
            )
    }

    private object MissingCgpClassLoader :
        ClassLoader(AccentApplicatorRevertAllIntegrationTest::class.java.classLoader) {
        override fun loadClass(name: String?): Class<*> {
            if (name == "com.nasller.codeglance.config.CodeGlanceConfigService") {
                throw ClassNotFoundException(name)
            }
            return super.loadClass(name)
        }
    }
}
