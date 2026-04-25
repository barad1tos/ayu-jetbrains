package dev.ayuislands.accent

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
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
import dev.ayuislands.indent.IndentRainbowSync
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.ui.ComponentTreeRefresher
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
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
 * D-04 + D-05 + D-09 behavioral coverage for [AccentApplicator.revertAll].
 *
 * Pre-40.1, `revertAll()` cleared UIManager keys but never reverted the two
 * integrations that read app-scoped state — IndentRainbow's `IrConfig` and
 * CodeGlance Pro's `CodeGlanceConfigService`. After a theme switch, the user
 * still saw the old Ayu accent in the indent palette and the CGP minimap
 * viewport because both integrations' app-scoped caches kept the last
 * apply()'d hex.
 *
 * Wave 1 plan 02 wires:
 *   - `IndentRainbowSync.revert()` — already public, never wired before
 *   - `revertCodeGlanceProViewport()` — new private helper, mirror of
 *     `syncCodeGlanceProViewport`, restores documented javap-verified defaults
 *     ("00FF00", "A0A0A0", 0)
 *   - `cgpRevertHook` ThreadLocal observer (D-09) — lets tests assert the
 *     three default values are written without bringing CGP into the test
 *     classpath (matches the [ChromeDecorationsProbe.osSupplier] template)
 *
 * The tests reference symbols introduced in Wave 1 plan 02:
 *   - `AccentApplicator.cgpRevertHook` (ThreadLocal)
 *   - `AccentApplicator.resetCgpRevertHookForTests`
 * The references compile only after that plan lands; until then the file is
 * red, which is the entire point of Wave 0.
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

        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns emptyArray()

        mockkObject(ComponentTreeRefresher)
        every { ComponentTreeRefresher.notifyOnly(any()) } returns Unit

        mockkObject(IndentRainbowSync)
        every { IndentRainbowSync.revert() } just Runs

        // Default to an empty EP list so revertAll's loop runs without each test
        // re-stubbing the EP. Ensures the new IR/CGP revert calls are reachable.
        mockEpExtensionList(emptyList())
    }

    @AfterTest
    fun tearDown() {
        // Pattern I + Shared Pattern 3: ThreadLocal seam reset is mandatory
        // because per-test try/finally inside the test body cannot recover from
        // an assertion failure that exits the worker mid-test. The class-level
        // teardown is the safety net.
        AccentApplicator.resetCgpRevertHookForTests()
        // C-3 reflection-path tests stash mocks into [CgpIntegration]'s
        // private reflection cache. Without this reset, a test failure could
        // leave pinned mocks visible to a subsequent test in the same worker JVM.
        CgpIntegration.resetReflectionCacheForTests()
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
        val observed = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.cgpRevertHook.set { c, bc, bt -> observed += Triple(c, bc, bt) }
        try {
            AccentApplicator.revertAll()
        } finally {
            AccentApplicator.resetCgpRevertHookForTests()
        }
        assertEquals(
            1,
            observed.size,
            "revertCodeGlanceProViewport must fire exactly once from revertAll (D-04 wiring)",
        )
    }

    @Test
    fun `revertCodeGlanceProViewport writes documented defaults via hook`() {
        // V-D05a + V-D09b: the three defaults are javap-verified from
        // CodeGlanceConfig-2.0.2 (CONTEXT.md §specifics). The test pins the
        // exact tuple — drift on any value would silently re-paint the user's
        // CGP viewport with whatever default the agent guessed.
        val observed = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.cgpRevertHook.set { c, bc, bt -> observed += Triple(c, bc, bt) }
        try {
            AccentApplicator.revertAll()
        } finally {
            AccentApplicator.resetCgpRevertHookForTests()
        }
        assertEquals(listOf(Triple("00FF00", "A0A0A0", 0)), observed)
    }

    @Test
    fun `each integration revert is isolated by RuntimeException catch`() {
        // Pattern B regression lock: IR's revert throwing must NOT block CGP's
        // revert. Both integrations are wrapped in narrow `RuntimeException`
        // catches per RESEARCH §D-04.
        every { IndentRainbowSync.revert() } throws RuntimeException("IR exploded")

        val cgpObserved = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.cgpRevertHook.set { c, bc, bt -> cgpObserved += Triple(c, bc, bt) }
        try {
            AccentApplicator.revertAll() // MUST NOT throw — IR failure is caught
        } finally {
            AccentApplicator.resetCgpRevertHookForTests()
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
    fun `revertAll orders IR revert before CGP revert before notifyOnly`() {
        // RESEARCH §D-04 ordering lock: integrations BEFORE notifyOnly so
        // subscribers see consistent app-scoped state when they decide to
        // repaint. Pattern G + L: capture every observable side-effect on a
        // single `events` timeline and assert the EXACT interleaving. A weaker
        // `verifyOrder { IR; notifyOnly }` + `cgpCalls.size == 1` pair would
        // still pass if CGP fired BEFORE IR (size stays 1, IR-before-notifyOnly
        // holds) — the explicit timeline closes that hole.
        val project = mockProject()
        every { mockProjectManager.openProjects } returns arrayOf(project)

        val events = mutableListOf<String>()
        every { IndentRainbowSync.revert() } answers {
            events += "ir_revert"
        }
        every { ComponentTreeRefresher.notifyOnly(project) } answers {
            events += "notify_only"
        }

        val cgpCalls = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.cgpRevertHook.set { c, bc, bt ->
            cgpCalls += Triple(c, bc, bt)
            events += "cgp_revert"
        }
        try {
            AccentApplicator.revertAll()
        } finally {
            AccentApplicator.resetCgpRevertHookForTests()
        }

        // Sanity: every hook fired exactly once. Keeps the existing size gate
        // honest so a future regression that drops a hook entirely still trips.
        assertEquals(
            1,
            cgpCalls.size,
            "CGP revert hook must have fired exactly once (D-04 single-fire gate)",
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
                "exact order with no interleaving (D-04 ordering lock — observed: $events)",
        )
    }

    @Test
    fun `revertCodeGlanceProViewport invokes hook even when cgpIntegrationEnabled false`() {
        // CR-I1 regression lock. Pre-fix: a `cgpIntegrationEnabled = false`
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
        var hookInvoked = false
        AccentApplicator.cgpRevertHook.set { _, _, _ -> hookInvoked = true }
        try {
            AccentApplicator.revertAll()
        } finally {
            AccentApplicator.resetCgpRevertHookForTests()
        }
        assertTrue(
            hookInvoked,
            "Hook MUST fire even when cgpIntegrationEnabled is false — revert path " +
                "is reachable on theme-switch / license-loss regardless of toggle state " +
                "(CR-I1 fix; Pattern G/J).",
        )
    }

    @Test
    fun `syncCodeGlanceProViewport with cgpIntegrationEnabled false fires revert path with documented defaults`() {
        // CR-I1 — toggle-off after a previous apply. Without this fix, the
        // disabled-branch returns silently, leaving CGP's app-scoped cache
        // tinted with the previous Ayu accent forever. Mirrors
        // IndentRainbowSync.apply, which already reverts on
        // !irIntegrationEnabled.
        state.cgpIntegrationEnabled = false
        val observed = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.cgpRevertHook.set { c, bc, bt -> observed += Triple(c, bc, bt) }
        try {
            CgpIntegration.syncCodeGlanceProViewport("#5CCFE6")
        } finally {
            AccentApplicator.resetCgpRevertHookForTests()
        }
        assertEquals(
            listOf(Triple("00FF00", "A0A0A0", 0)),
            observed,
            "syncCodeGlanceProViewport with cgpIntegrationEnabled=false MUST drive " +
                "the revert path with the documented javap-verified defaults — " +
                "matches IndentRainbowSync.apply's same-shape symmetry (CR-I1, Pattern G).",
        )
    }

    @Test
    fun `syncCodeGlanceProViewportForSwap delegates to CgpIntegration syncCodeGlanceProViewport`() {
        // C-2 regression lock. Pre-fix: AccentApplicator.syncCodeGlanceProViewportForSwap
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
    fun `revertCodeGlanceProViewport via reflection writes documented defaults in order`() {
        // C-3 — when CGP IS installed (reflection chain primed), revertAll
        // exercises the reflection setters. cgpRevertHook stays null so the
        // production path runs. Verifies the three setters fire with the
        // exact javap-verified defaults AND in the documented order: color,
        // border color, border thickness.
        val mockConfig = mockk<Any>(relaxed = true)
        val mockService = mockk<Any>(relaxed = true)
        val mockGetState = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderColor = mockk<java.lang.reflect.Method>(relaxed = true)
        val mockSetBorderThickness = mockk<java.lang.reflect.Method>(relaxed = true)
        every { mockGetState.invoke(mockService) } returns mockConfig

        val callOrder = mutableListOf<String>()
        every { mockSetColor.invoke(mockConfig, "00FF00") } answers {
            callOrder += "color"
            null
        }
        every { mockSetBorderColor.invoke(mockConfig, "A0A0A0") } answers {
            callOrder += "border-color"
            null
        }
        every { mockSetBorderThickness.invoke(mockConfig, 0) } answers {
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

        CgpIntegration.revertCodeGlanceProViewport()

        assertEquals(
            listOf("color", "border-color", "border-thickness"),
            callOrder,
            "CGP revert MUST call setViewportColor, setViewportBorderColor, " +
                "setViewportBorderThickness in that order with documented defaults — " +
                "regression in order or values would silently re-paint the user's CGP " +
                "viewport with whatever default the agent guessed.",
        )
    }

    @Test
    fun `revertCodeGlanceProViewport handles InvocationTargetException gracefully`() {
        // C-3 catch-path coverage. CGP setters can throw via reflection if
        // upstream renames or guards a setter. The InvocationTargetException
        // catch must swallow the failure with a WARN — a thrown exception
        // here would propagate up through revertAll and break theme switch.
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
        CgpIntegration.revertCodeGlanceProViewport()
    }

    @Test
    fun `revertCodeGlanceProViewport handles ReflectiveOperationException gracefully`() {
        // C-3 catch-path coverage for the IllegalAccessException /
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

        CgpIntegration.revertCodeGlanceProViewport()
    }

    @Test
    fun `revertCodeGlanceProViewport passes hex without hash prefix to CGP setters`() {
        // C-3 contract lock. CGP rejects '#' silently — its setters store the
        // value as-is, so '#5CCFE6' would be persisted as a literal 7-char
        // string and the minimap would render with a broken hex. The defaults
        // are pre-stripped (00FF00, A0A0A0); this test pins that the values
        // passed have no '#' character regardless of how the constants were
        // declared.
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

        CgpIntegration.revertCodeGlanceProViewport()

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
        // Patch coverage: mirrors the existing C-3 reflection test for
        // [CgpIntegration.revertCodeGlanceProViewport]. Pre-test, the
        // `syncCodeGlanceProViewport` reflection happy path (CgpIntegration.kt
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

        CgpIntegration.syncCodeGlanceProViewport("#5CCFE6")

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
        // Patch coverage + Pattern L source-regex lock for
        // `accentHex.removePrefix("#")` (CgpIntegration.kt line 161). When the
        // caller passes a bare hex (no `#`), removePrefix is a no-op and the
        // raw 6-char string flows to the setter unchanged. Locks the contract
        // that production accepts BOTH `#XXXXXX` and `XXXXXX` forms — a
        // regression to `accentHex.substring(1)` would silently drop the
        // first character of the bare-hex callsite.
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

        CgpIntegration.syncCodeGlanceProViewport("FFCC66")

        verify(exactly = 1) { mockSetColor.invoke(mockConfig, "FFCC66") }
        verify(exactly = 1) { mockSetBorderColor.invoke(mockConfig, "FFCC66") }
        verify(exactly = 1) { mockSetBorderThickness.invoke(mockConfig, 1) }
    }

    @Test
    fun `syncCodeGlanceProViewport via reflection short-circuits when getState returns null`() {
        // Patch coverage + Pattern L: defensive guard at
        // `getState.invoke(service) ?: return` (CgpIntegration.kt line 162).
        // Unreachable in production today (CGP's getState always returns the
        // cached state object), but the guard exists so a future CGP version
        // that returns null mid-init does not NPE through to the setters.
        // This test pins the branch so a refactor that drops `?: return`
        // would visibly call setters on a null config.
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

        CgpIntegration.syncCodeGlanceProViewport("#5CCFE6")

        verify(exactly = 0) { mockSetColor.invoke(any(), any()) }
        verify(exactly = 0) { mockSetBorderColor.invoke(any(), any()) }
        verify(exactly = 0) { mockSetBorderThickness.invoke(any(), any()) }
    }

    @Test
    fun `syncCodeGlanceProViewport via reflection handles InvocationTargetException gracefully`() {
        // Patch coverage + Pattern B isolation: CGP's setters can throw via
        // reflection if upstream guards a setter. The `InvocationTargetException`
        // catch at CgpIntegration.kt lines 170-176 must swallow the failure
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
        CgpIntegration.syncCodeGlanceProViewport("#5CCFE6")
    }

    @Test
    fun `syncCodeGlanceProViewport via reflection handles ReflectiveOperationException gracefully`() {
        // Patch coverage + Pattern B isolation: catch path at
        // CgpIntegration.kt lines 177-182 must swallow `IllegalAccessException`
        // / `NoSuchMethodException` without re-throwing.
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

        CgpIntegration.syncCodeGlanceProViewport("#5CCFE6")
    }

    @Test
    fun `syncCodeGlanceProViewport via reflection handles RuntimeException gracefully`() {
        // Patch coverage + Pattern B isolation: the third catch at
        // CgpIntegration.kt lines 183-189 covers a plain `RuntimeException`
        // bubbling out of the reflective invoke (e.g. CGP's setter
        // dereferences a stale field internally). Must NOT propagate to the
        // theme apply caller.
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

        CgpIntegration.syncCodeGlanceProViewport("#5CCFE6")
    }

    @Test
    fun `revertCodeGlanceProViewport via reflection short-circuits when getState returns null`() {
        // Patch coverage + Pattern L: defensive guard at
        // `getState.invoke(service) ?: return` (CgpIntegration.kt line 240).
        // Mirror of the sync-side getState-null lock — Pattern G symmetry.
        // `cgpRevertHook` left null so the production reflection path runs.
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

        CgpIntegration.revertCodeGlanceProViewport()

        verify(exactly = 0) { mockSetColor.invoke(any(), any()) }
        verify(exactly = 0) { mockSetBorderColor.invoke(any(), any()) }
        verify(exactly = 0) { mockSetBorderThickness.invoke(any(), any()) }
    }

    @Test
    fun `revertCodeGlanceProViewport via reflection handles RuntimeException gracefully`() {
        // Patch coverage + Pattern B + G symmetry: existing C-3 tests cover
        // the `InvocationTargetException` and `ReflectiveOperationException`
        // catches on revert; this pins the third catch
        // (CgpIntegration.kt lines 259-265) so a regression that narrows the
        // catch surface back to two clauses would surface here instead of
        // breaking the user's theme switch when CGP's setter NPEs internally.
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
        CgpIntegration.revertCodeGlanceProViewport()
    }

    @Test
    fun `revertAll continues integration revert after EP element revert throws`() {
        // TA-I5 regression lock. Pre-fix observation: an EP element whose
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

        val cgpObserved = mutableListOf<Triple<String, String, Int>>()
        AccentApplicator.cgpRevertHook.set { c, bc, bt -> cgpObserved += Triple(c, bc, bt) }
        try {
            AccentApplicator.revertAll() // MUST NOT throw
        } finally {
            AccentApplicator.resetCgpRevertHookForTests()
        }

        verify(exactly = 1) { brokenElement.revert() }
        verify(exactly = 1) { IndentRainbowSync.revert() }
        assertEquals(
            1,
            cgpObserved.size,
            "CGP revert hook MUST still fire after EP element revert throws " +
                "(TA-I5 isolation lock — Pattern B + G).",
        )
    }

    @Test
    fun `syncCodeGlanceProViewport resolveCgpMethods catches ReflectiveOperationException when CGP class missing`() {
        // Patch coverage + Pattern B isolation: drives the
        // `ReflectiveOperationException` catch inside `resolveCgpMethods`
        // (CgpIntegration.kt lines 116-121). When the CGP plugin reports a
        // classloader but `Class.forName` cannot locate
        // `com.nasller.codeglance.config.CodeGlanceConfigService` (the test
        // classpath has no CGP), `Class.forName` throws
        // `ClassNotFoundException`. The catch must swallow with a WARN —
        // a propagated exception would break theme apply for users without
        // CGP installed but with a stale plugin entry.
        //
        // Reflection cache is reset in `@AfterTest` via
        // `CgpIntegration.resetReflectionCacheForTests()`, otherwise the
        // `cgpMethodsResolved = true` flag set on entry would leak into
        // the next test.
        CgpIntegration.resetReflectionCacheForTests()
        mockkStatic(PluginManagerCore::class)
        val mockPlugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { PluginManagerCore.getPlugin(any()) } returns mockPlugin
        // Use the test's own classloader — it does not contain the CGP class,
        // so `Class.forName` throws `ClassNotFoundException` (a subtype of
        // `ReflectiveOperationException`).
        every { mockPlugin.pluginClassLoader } returns this::class.java.classLoader

        // No throw expected. The `cgpService` field stays null because the
        // catch swallows before it can be assigned.
        CgpIntegration.syncCodeGlanceProViewport("#5CCFE6")

        val serviceField = CgpIntegration::class.java.getDeclaredField("cgpService")
        serviceField.isAccessible = true
        assertEquals(
            null,
            serviceField.get(CgpIntegration),
            "cgpService MUST stay null after `Class.forName` throws — the catch " +
                "must short-circuit before any field assignment.",
        )
    }

    @Test
    fun `syncCodeGlanceProViewport resolveCgpMethods catches RuntimeException when classloader misbehaves`() {
        // Patch coverage + Pattern B isolation: drives the `RuntimeException`
        // catch inside `resolveCgpMethods` (CgpIntegration.kt lines 122-127).
        // A classloader subclass that throws `IllegalStateException` from
        // `loadClass` exercises the second catch — covers a CGP plugin in a
        // weird state (corrupt jar, security manager rejection) where the
        // first catch's `ReflectiveOperationException` does NOT match.
        //
        // Reflection cache is reset in `@AfterTest` via
        // `CgpIntegration.resetReflectionCacheForTests()`.
        CgpIntegration.resetReflectionCacheForTests()
        mockkStatic(PluginManagerCore::class)
        val mockPlugin = mockk<IdeaPluginDescriptor>(relaxed = true)
        every { PluginManagerCore.getPlugin(any()) } returns mockPlugin
        every { mockPlugin.pluginClassLoader } returns BrokenClassLoader

        // No throw expected. `IllegalStateException` is a `RuntimeException`,
        // not a `ReflectiveOperationException` — the second catch handles it.
        CgpIntegration.syncCodeGlanceProViewport("#5CCFE6")

        val serviceField = CgpIntegration::class.java.getDeclaredField("cgpService")
        serviceField.isAccessible = true
        assertEquals(
            null,
            serviceField.get(CgpIntegration),
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
        // instead of short-circuiting on `cgpService ?: return`. Mirrors the
        // pattern `AccentApplicatorTest` used pre-TD-I5 (raw field writes),
        // but routed through the typed
        // [CgpIntegration.resetReflectionCacheForTests] helper for cleanup.
        // Marks `cgpMethodsResolved = true` so `resolveCgpMethods` is a no-op
        // (we already supplied the cached refs).
        val ownerClass = CgpIntegration::class.java
        ownerClass.getDeclaredField("cgpService").apply {
            isAccessible = true
            set(CgpIntegration, service)
        }
        ownerClass.getDeclaredField("cgpGetState").apply {
            isAccessible = true
            set(CgpIntegration, getState)
        }
        ownerClass.getDeclaredField("cgpSetViewportColor").apply {
            isAccessible = true
            set(CgpIntegration, setColor)
        }
        ownerClass.getDeclaredField("cgpSetViewportBorderColor").apply {
            isAccessible = true
            set(CgpIntegration, setBorderColor)
        }
        ownerClass.getDeclaredField("cgpSetViewportBorderThickness").apply {
            isAccessible = true
            set(CgpIntegration, setBorderThickness)
        }
        ownerClass.getDeclaredField("cgpMethodsResolved").apply {
            isAccessible = true
            set(CgpIntegration, true)
        }
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
     * `RuntimeException` catch in `CgpIntegration.resolveCgpMethods` — covers
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
            throw IllegalStateException(
                "BrokenClassLoader rejects loadClass for '$name' — " +
                    "exercises the resolveCgpMethods RuntimeException catch.",
            )
    }
}
