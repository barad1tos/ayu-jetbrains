package dev.ayuislands.accent

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
        // gate at the top of revertCodeGlanceProViewport short-circuited every
        // revert call, so a user who toggled CGP off after an apply was stuck
        // with CGP's app-scoped cache holding the previous Ayu accent forever
        // — the apply path stamped CGP, the toggle prevented further writes,
        // and the revert path silently no-op'd. Post-fix: the gate moves to
        // syncCodeGlanceProViewport's entry, which mirrors IndentRainbowSync.
        // The revert path runs unconditionally so theme switch / license loss
        // can clean up CGP regardless of toggle state.
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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
}
