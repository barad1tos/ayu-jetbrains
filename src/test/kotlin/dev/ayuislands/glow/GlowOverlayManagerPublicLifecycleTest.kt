package dev.ayuislands.glow

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioAccentResult
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioEffectRuntime
import dev.ayuislands.studio.session.StudioGlowCapture
import dev.ayuislands.studio.session.StudioGlowHolds
import dev.ayuislands.studio.session.StudioGlowInput
import dev.ayuislands.studio.session.StudioGlowOverlayAdd
import dev.ayuislands.studio.session.StudioGlowParticipant
import dev.ayuislands.studio.session.StudioGlowReferences
import dev.ayuislands.studio.session.StudioNativeFacts
import dev.ayuislands.studio.session.StudioNativeObservation
import dev.ayuislands.studio.session.StudioProjectAccent
import dev.ayuislands.studio.session.StudioRestoreContinuation
import dev.ayuislands.studio.session.StudioThemeTransition
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Component
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.HierarchyBoundsAdapter
import java.time.Instant
import java.util.ArrayDeque
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JTextField
import javax.swing.RepaintManager
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.Border
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Public-boundary lifecycle coverage for real tool-window glow overlays. */
class GlowOverlayManagerPublicLifecycleTest {
    private lateinit var environment: PublicLifecycleEnvironment

    @BeforeTest
    fun setUp() {
        environment = PublicLifecycleEnvironment.create()
    }

    @AfterTest
    fun tearDown() {
        if (::environment.isInitialized) environment.close()
    }

    @Test
    fun `Studio accent changes preserve hidden panes and do not discover unrelated hosts`() {
        environment.onEdt {
            runBlocking {
                val fixture = environment.hostFixture()
                val manager = environment.createManager(fixture)
                manager.initialize()
                environment.drainUiCallbacks()
                val pane = fixture.singleOverlay()
                pane.isVisible = false
                pane.glowWidth = 17
                val before = pane.captureAppearance()
                val preferences = environment.stateXml()
                val held = StudioGlowHolds(listOf(manager))
                held.acquire()
                try {
                    environment.rejectToolWindowReads()
                    val owner = held.entries.single()
                    val candidate = requireNotNull(owner.hold.before).copy(accent = AccentHex.of("#FFCC66"))
                    val selection = StudioGlowSelection(dependencies = StudioList(listOf(GlowDependency.ACCENT)))
                    val source = StudioGlowInput(owner, candidate, AccentContext.Ayu(AyuVariant.MIRAGE))
                    val operation =
                        StudioGlowCapture(
                            StudioGlowReferences("accent", StudioProcess(42, Instant.EPOCH)),
                            EmptyCoroutineContext,
                        ).capture(selection, listOf(source), source, LicenseEntitlement.LICENSED)
                    assertEquals(before, pane.captureAppearance())
                    assertTrue(operation.receipts.all { it.status == RestoreStatus.PENDING })
                    operation.apply()
                    assertEquals(before.copy(color = Color(0xFFCC66)), pane.captureAppearance())
                    assertSame(candidate, owner.hold.selected)
                    operation.prepareRestore(StudioList(emptyList()))
                    assertEquals(StudioRestoreContinuation.REQUIRED, operation.restore(StudioList(emptyList())))
                    assertEquals(before, pane.captureAppearance())
                    assertSame(requireNotNull(owner.hold.before), owner.hold.selected)
                    operation.prepareRestore(StudioList(emptyList()))
                    assertEquals(StudioRestoreContinuation.NONE, operation.restore(StudioList(emptyList())))
                    assertTrue(operation.receipts.all { it.status == RestoreStatus.RESTORED })
                    assertTrue(held.isActive)
                    assertEquals(preferences, environment.stateXml())
                } finally {
                    held.release()
                }
            }
        }
    }

    @Test
    fun `Studio target exchange retains old membership until acceptance and reverses in native order`() {
        environment.onEdt {
            runBlocking {
                val projectFixture = environment.hostFixture()
                val terminalFixture = ToolWindowHostFixture(environment.secondaryRoot())
                val manager = environment.createManager(projectFixture)
                manager.initialize()
                environment.drainUiCallbacks()
                environment.addTerminal(terminalFixture)
                val pane = projectFixture.singleOverlay()
                val before = pane.captureAppearance()
                val children =
                    projectFixture.root.layeredPane.components
                        .toList()
                val listeners = projectFixture.host.componentListeners.toList()
                val preferences = environment.stateXml()
                val held = StudioGlowHolds(listOf(manager))
                held.acquire()
                try {
                    val owner = held.entries.single()
                    val candidate =
                        requireNotNull(owner.hold.before).copy(
                            settings =
                                requireNotNull(owner.hold.before).settings.copy(
                                    islandToggles =
                                        requireNotNull(owner.hold.before).settings.islandToggles +
                                            mapOf("Project" to false, "Terminal" to true),
                                ),
                            accent = AccentHex.of("#5CCFE6"),
                        )
                    val selection = selectedGlow("glowProject", "glowTerminal")
                    val source = StudioGlowInput(owner, candidate, AccentContext.Ayu(AyuVariant.MIRAGE))
                    val operation =
                        StudioGlowCapture(
                            StudioGlowReferences("targets", StudioProcess(42, Instant.EPOCH)),
                            EmptyCoroutineContext,
                        ).capture(selection, listOf(source), source, LicenseEntitlement.LICENSED)
                    assertTrue(terminalFixture.overlays().isEmpty())
                    assertTrue(pane.isVisible)
                    operation.apply()
                    assertFalse(pane.isVisible)
                    assertSame(pane, projectFixture.singleOverlay())
                    assertEquals(1, terminalFixture.overlays().size)
                    operation.prepareRestore(StudioList(emptyList()))
                    assertEquals(StudioRestoreContinuation.REQUIRED, operation.restore(StudioList(emptyList())))
                    assertTrue(terminalFixture.overlays().isEmpty())
                    assertEquals(before, pane.captureAppearance())
                    operation.prepareRestore(StudioList(emptyList()))
                    assertEquals(StudioRestoreContinuation.NONE, operation.restore(StudioList(emptyList())))
                    assertTrue(operation.receipts.all { it.status == RestoreStatus.RESTORED })
                    assertSameIdentityOrder(
                        children,
                        projectFixture.root.layeredPane.components
                            .toList(),
                    )
                    assertSameIdentityOrder(listeners, projectFixture.host.componentListeners.toList())
                    assertEquals(preferences, environment.stateXml())
                } finally {
                    held.release()
                }
            }
        }
    }

    @Test
    fun `Studio disable removes retained members only after acceptance without rewriting preferences`() {
        environment.onEdt {
            runBlocking {
                val fixture = environment.hostFixture()
                val manager = environment.createManager(fixture)
                manager.initialize()
                environment.drainUiCallbacks()
                val pane = fixture.singleOverlay()
                val preferences = environment.stateXml()
                val held = StudioGlowHolds(listOf(manager))
                held.acquire()
                try {
                    val owner = held.entries.single()
                    val candidate =
                        requireNotNull(owner.hold.before).copy(
                            settings =
                                requireNotNull(owner.hold.before)
                                    .settings
                                    .copy(enabled = false),
                            accent = AccentHex.of("#5CCFE6"),
                        )
                    val source = StudioGlowInput(owner, candidate, AccentContext.Ayu(AyuVariant.MIRAGE))
                    val operation =
                        StudioGlowCapture(
                            StudioGlowReferences("disable", StudioProcess(42, Instant.EPOCH)),
                            EmptyCoroutineContext,
                        ).capture(selectedGlow("glowEnabled"), listOf(source), source, LicenseEntitlement.LICENSED)
                    operation.apply()
                    assertSame(pane, fixture.singleOverlay())
                    assertFalse(pane.isVisible)
                    operation.accept()
                    operation.accept()
                    assertTrue(fixture.overlays().isEmpty())
                    assertTrue(held.isActive)
                    assertEquals(preferences, environment.stateXml())
                } finally {
                    held.release()
                }
            }
        }
    }

    @Test
    fun `Studio shared focus uses the effective owner independently of project order and availability`() {
        for (isReversed in listOf(false, true)) {
            for (isEnabled in listOf(false, true)) {
                environment.onEdt { runBlocking { verifyFocusSource(isReversed, isEnabled) } }
            }
        }
    }

    private suspend fun verifyFocusSource(
        isReversed: Boolean,
        isEnabled: Boolean,
    ) {
        val fixture = environment.hostFixture()
        val first = environment.createManager(fixture)
        val second = environment.createManager(fixture)
        val held = StudioGlowHolds(if (isReversed) listOf(second, first) else listOf(first, second))
        val field = JTextField()
        val original = field.border
        val window = mockk<Window>(relaxed = true)
        every { window.components } returns arrayOf(field)
        mockkStatic(Window::class)
        every { Window.getWindows() } returns arrayOf(window)
        try {
            held.acquire()
            val sources =
                held.entries.map { owner ->
                    StudioGlowInput(
                        owner,
                        requireNotNull(owner.hold.before).copy(
                            settings =
                                requireNotNull(owner.hold.before)
                                    .settings
                                    .copy(focusRing = true),
                            accent = AccentHex.of(if (owner.manager === first) "#FF0000" else "#0000FF"),
                        ),
                        AccentContext.Ayu(AyuVariant.MIRAGE),
                    )
                }
            val selected = sources.single { it.owner.manager === second }
            val globalFocus =
                selected.copy(
                    candidate =
                        selected.candidate.copy(
                            settings = selected.candidate.settings.copy(focusRing = isEnabled),
                            accent = AccentHex.of("#00FF00"),
                        ),
                )
            val accent =
                StudioAccentResult(
                    globalFocus.context,
                    second.project,
                    ResolvedAccent(AccentResolver.Source.GLOBAL, requireNotNull(globalFocus.candidate.accent).value),
                    StudioList(
                        sources.map { source ->
                            StudioProjectAccent(
                                source.owner.manager.project,
                                ResolvedAccent(
                                    AccentResolver.Source.PROJECT_OVERRIDE,
                                    requireNotNull(source.candidate.accent).value,
                                ),
                            )
                        },
                    ),
                )
            val input = participantInput(selectedGlow("glowFocusRing")) { glowFocusRing = isEnabled }
            val operation = StudioGlowParticipant(EmptyCoroutineContext).prepare(input, glowRuntime(held, accent))
            assertTrue(field.focusListeners.none { it is GlowFocusListener })
            operation.apply()
            val installed = field.focusListeners.filterIsInstance<GlowFocusListener>()
            assertEquals(if (isEnabled) 1 else 0, installed.size)
            if (isEnabled) assertEquals(Color.GREEN, installed.single().currentAppearance.color)
            operation.prepareRestore(StudioList(emptyList()))
            operation.restore(StudioList(emptyList()))
            operation.prepareRestore(StudioList(emptyList()))
            assertEquals(StudioRestoreContinuation.NONE, operation.restore(StudioList(emptyList())))
            assertTrue(field.focusListeners.none { it is GlowFocusListener })
            assertSame(original, field.border)
        } finally {
            held.release()
            unmockkStatic(Window::class)
        }
    }

    @Test
    fun `Studio repairs partial attachment and earlier appearance writes after native insertion throws`() {
        environment.onEdt {
            runBlocking {
                val projectFixture = environment.hostFixture()
                val terminalFixture = ToolWindowHostFixture(environment.secondaryRoot())
                val manager = environment.createManager(projectFixture)
                manager.initialize()
                environment.drainUiCallbacks()
                environment.addTerminal(terminalFixture)
                val pane = projectFixture.singleOverlay()
                val before = pane.captureAppearance()
                val preferences = environment.stateXml()
                val held = StudioGlowHolds(listOf(manager))
                val rejectInsertion =
                    object : ContainerAdapter() {
                        override fun componentAdded(event: ContainerEvent) {
                            check(event.child !is GlowGlassPane) { "Insertion notification failed after mutation" }
                        }
                    }
                terminalFixture.root.layeredPane.addContainerListener(rejectInsertion)
                held.acquire()
                try {
                    val owner = held.entries.single()
                    val source =
                        StudioGlowInput(
                            owner,
                            requireNotNull(owner.hold.before).copy(
                                settings =
                                    requireNotNull(owner.hold.before).settings.copy(
                                        islandToggles =
                                            requireNotNull(owner.hold.before).settings.islandToggles +
                                                ("Terminal" to true),
                                    ),
                                accent = AccentHex.of("#FFCC66"),
                            ),
                            AccentContext.Ayu(AyuVariant.MIRAGE),
                        )
                    val selection =
                        selectedGlow("glowTerminal").copy(
                            dependencies = StudioList(listOf(GlowDependency.ACCENT)),
                        )
                    val operation =
                        StudioGlowCapture(
                            StudioGlowReferences("failed-insertion", StudioProcess(42, Instant.EPOCH)),
                            EmptyCoroutineContext,
                        ).capture(selection, listOf(source), source, LicenseEntitlement.LICENSED)
                    val original = operation.receipts.associateBy { it.id }
                    assertFailsWith<IllegalStateException> { operation.apply() }
                    assertEquals(Color(0xFFCC66), pane.glowColor)
                    assertEquals(1, terminalFixture.overlays().size)
                    operation.prepareRestore(StudioList(emptyList()))
                    assertEquals(StudioRestoreContinuation.REQUIRED, operation.restore(StudioList(emptyList())))
                    assertTrue(terminalFixture.overlays().isEmpty())
                    assertEquals(before, pane.captureAppearance())
                    operation.prepareRestore(StudioList(emptyList()))
                    assertEquals(StudioRestoreContinuation.NONE, operation.restore(StudioList(emptyList())))
                    assertTrue(operation.receipts.all { it.status == RestoreStatus.RESTORED })
                    for (receipt in operation.receipts) {
                        original[receipt.id]?.let { previous ->
                            assertEquals(previous, receipt.copy(status = previous.status))
                        }
                    }
                    assertEquals(preferences, environment.stateXml())
                } finally {
                    terminalFixture.root.layeredPane.removeContainerListener(rejectInsertion)
                    held.release()
                }
            }
        }
    }

    @Test
    fun `Studio cold activation starts stored animation before accepting callback registration`() {
        environment.onEdt {
            runBlocking {
                val fixture = environment.hostFixture()
                val manager = environment.createManager(fixture)
                environment.setFocusOwner(fixture.host)
                val preferences = environment.stateXml()
                val held = StudioGlowHolds(listOf(manager))
                held.acquire()
                try {
                    val owner = held.entries.single()
                    val source =
                        StudioGlowInput(
                            owner,
                            requireNotNull(owner.hold.before).copy(
                                settings =
                                    requireNotNull(owner.hold.before)
                                        .settings
                                        .copy(animation = GlowAnimation.PULSE),
                                accent = AccentHex.of("#5CCFE6"),
                            ),
                            AccentContext.Ayu(AyuVariant.MIRAGE),
                        )
                    val operation =
                        StudioGlowCapture(
                            StudioGlowReferences("cold", StudioProcess(42, Instant.EPOCH)),
                            EmptyCoroutineContext,
                        ).capture(selectedGlow("glowEnabled"), listOf(source), source, LicenseEntitlement.LICENSED)
                    assertTrue(fixture.overlays().isEmpty())
                    environment.assertFocusRegistrations(0)
                    operation.apply()
                    assertEquals(1, fixture.overlays().size)
                    assertEquals(GlowAnimation.PULSE, manager.capturePlayback(owner.hold).animation)
                    environment.assertFocusRegistrations(0)
                    operation.accept()
                    environment.assertFocusRegistrations(1)
                    assertEquals(GlowAnimation.PULSE, manager.capturePlayback(owner.hold).animation)
                    assertTrue(held.isActive)
                    assertEquals(preferences, environment.stateXml())
                } finally {
                    held.release()
                }
            }
        }
    }

    @Test
    fun `Studio participant applies confirmed accent and requested parameters before acceptance`() {
        environment.onEdt {
            runBlocking {
                val fixture = environment.hostFixture()
                val manager = environment.createManager(fixture)
                manager.initialize()
                environment.drainUiCallbacks()
                val pane = fixture.singleOverlay()
                val before = pane.captureAppearance()
                val preferences = environment.stateXml()
                val selection =
                    selectedGlow("glowStyle", "sharpNeonWidth", "sharpNeonIntensity").copy(
                        dependencies = StudioList(listOf(GlowDependency.ACCENT)),
                    )
                val input =
                    participantInput(selection) {
                        glowStyle = GlowStyle.SHARP_NEON.name
                        sharpNeonWidth = 13
                        sharpNeonIntensity = 79
                    }
                val candidate = input.candidate
                val held = StudioGlowHolds(listOf(manager))
                held.acquire()
                try {
                    val operation =
                        StudioGlowParticipant(EmptyCoroutineContext).prepare(
                            input,
                            glowRuntime(held, glowAccent(manager)),
                        )
                    assertEquals(before, pane.captureAppearance())
                    assertSame(
                        requireNotNull(
                            held.entries
                                .single()
                                .hold.before,
                        ),
                        held.entries
                            .single()
                            .hold.selected,
                    )
                    assertTrue(operation.receipts.isNotEmpty())
                    assertTrue(operation.receipts.all { it.status == RestoreStatus.PENDING })
                    operation.apply()
                    val visible = pane.captureAppearance()
                    assertEquals(GlowStyle.SHARP_NEON, visible.style)
                    assertEquals(13, visible.width)
                    assertEquals(79, visible.intensity)
                    assertEquals(Color(0x112233), visible.color)
                    assertEquals(candidate, input.candidate)
                    assertEquals(preferences, environment.stateXml())
                    assertTrue(held.isActive)
                    operation.accept()
                    assertEquals(visible, pane.captureAppearance())
                    assertEquals(preferences, environment.stateXml())
                    assertTrue(held.isActive)
                } finally {
                    held.release()
                }
            }
        }
    }

    @Test
    fun `confirmed inactive Accent retires real Glow members without a cached HEX and restores exact appearance`() {
        environment.onEdt {
            runBlocking {
                environment.state.glowFocusRing = true
                environment.state.lastAppliedAccentHex = null
                environment.state.lastApplyOk = false
                val fixture = environment.hostFixture()
                val manager = environment.createManager(fixture)
                val field = JTextField()
                val originalBorder = field.border
                val window = mockk<Window>(relaxed = true)
                every { window.components } returns arrayOf(field)
                mockkStatic(Window::class)
                every { Window.getWindows() } returns arrayOf(window)
                try {
                    manager.initialize()
                    environment.drainUiCallbacks()
                    val pane = fixture.singleOverlay()
                    val appearance = pane.captureAppearance()
                    val listener = field.focusListeners.filterIsInstance<GlowFocusListener>().single()
                    val focusAppearance = listener.currentAppearance
                    val preferences = environment.stateXml()
                    val selection = StudioGlowSelection(dependencies = StudioList(listOf(GlowDependency.AVAILABILITY)))
                    val input = participantInput(selection)
                    val candidate = input.candidate
                    val inactive = StudioAccentResult(null, manager.project, null, StudioList(emptyList()))
                    val held = StudioGlowHolds(listOf(manager))
                    held.acquire()
                    try {
                        assertNull(
                            requireNotNull(
                                held.entries
                                    .single()
                                    .hold.before,
                            ).accent,
                        )
                        val operation =
                            StudioGlowParticipant(EmptyCoroutineContext).prepare(input, glowRuntime(held, inactive))
                        assertEquals(appearance, pane.captureAppearance())
                        assertEquals(focusAppearance, listener.currentAppearance)

                        operation.apply()

                        assertSame(pane, fixture.singleOverlay())
                        assertEquals(appearance.copy(isVisible = false), pane.captureAppearance())
                        assertEquals(focusAppearance.copy(enabled = false), listener.currentAppearance)
                        assertNull(
                            requireNotNull(
                                held.entries
                                    .single()
                                    .hold.selected,
                            ).accent,
                        )
                        assertEquals(candidate, input.candidate)
                        assertEquals(preferences, environment.stateXml())
                        operation.prepareRestore(StudioList(emptyList()))
                        assertEquals(StudioRestoreContinuation.REQUIRED, operation.restore(StudioList(emptyList())))
                        operation.prepareRestore(StudioList(emptyList()))
                        assertEquals(StudioRestoreContinuation.NONE, operation.restore(StudioList(emptyList())))
                        assertSame(pane, fixture.singleOverlay())
                        assertEquals(appearance, pane.captureAppearance())
                        assertSame(listener, field.focusListeners.filterIsInstance<GlowFocusListener>().single())
                        assertEquals(focusAppearance, listener.currentAppearance)
                        assertSame(originalBorder, field.border)
                        assertEquals(preferences, environment.stateXml())
                    } finally {
                        held.release()
                    }
                    val cleanupHolds = StudioGlowHolds(listOf(manager))
                    cleanupHolds.acquire()
                    try {
                        val cleanup =
                            StudioGlowParticipant(
                                EmptyCoroutineContext,
                            ).prepare(input, glowRuntime(cleanupHolds, inactive))
                        cleanup.apply()
                        cleanup.accept()
                        assertTrue(fixture.overlays().isEmpty())
                        assertTrue(field.focusListeners.none { it is GlowFocusListener })
                        assertEquals(candidate, input.candidate)
                        assertEquals(preferences, environment.stateXml())
                    } finally {
                        cleanupHolds.release()
                    }
                } finally {
                    unmockkStatic(Window::class)
                }
            }
        }
    }

    @Test
    fun `confirmed inactive Accent accepts a cold empty Glow census without inventing a color`() {
        environment.onEdt {
            runBlocking {
                environment.state.lastAppliedAccentHex = null
                environment.state.lastApplyOk = false
                val preferences = environment.stateXml()
                val input =
                    participantInput(
                        StudioGlowSelection(dependencies = StudioList(listOf(GlowDependency.AVAILABILITY))),
                    )
                val candidate = input.candidate
                val held = StudioGlowHolds(emptyList())
                held.acquire()
                try {
                    val inactive = StudioAccentResult(null, null, null, StudioList(emptyList()))
                    val operation =
                        StudioGlowParticipant(EmptyCoroutineContext).prepare(input, glowRuntime(held, inactive))

                    operation.apply()
                    operation.accept()

                    assertTrue(operation.receipts.isEmpty())
                    assertTrue(held.entries.isEmpty())
                    assertEquals(candidate, input.candidate)
                    assertEquals(preferences, environment.stateXml())
                } finally {
                    held.release()
                }
            }
        }
    }

    @Test
    fun `Studio participant rejects unconfirmed accent before any native or preference write`() {
        environment.onEdt {
            runBlocking {
                val fixture = environment.hostFixture()
                val manager = environment.createManager(fixture)
                manager.initialize()
                environment.drainUiCallbacks()
                val pane = fixture.singleOverlay()
                val before = pane.captureAppearance()
                val preferences = environment.stateXml()
                val held = StudioGlowHolds(listOf(manager))
                held.acquire()
                try {
                    assertFailsWith<IllegalStateException> {
                        StudioGlowParticipant(EmptyCoroutineContext).prepare(
                            participantInput(selectedGlow("glowEnabled")) { glowEnabled = false },
                            glowRuntime(held, null),
                        )
                    }
                    assertEquals(before, pane.captureAppearance())
                    assertSame(
                        requireNotNull(
                            held.entries
                                .single()
                                .hold.before,
                        ),
                        held.entries
                            .single()
                            .hold.selected,
                    )
                    assertEquals(preferences, environment.stateXml())
                } finally {
                    held.release()
                }
            }
        }
    }

    @Test
    fun `Studio participant lost handle recovery requires different process proof and never replays native apply`() {
        environment.onEdt {
            runBlocking {
                val fixture = environment.hostFixture()
                val manager = environment.createManager(fixture)
                manager.initialize()
                environment.drainUiCallbacks()
                val pane = fixture.singleOverlay()
                val preferences = environment.stateXml()
                val input = participantInput(selectedGlow("softWidth")) { softWidth = 14 }
                val held = StudioGlowHolds(listOf(manager))
                val participant = StudioGlowParticipant(EmptyCoroutineContext)
                held.acquire()
                try {
                    val operation = participant.prepare(input, glowRuntime(held, glowAccent(manager)))
                    operation.apply()
                    val written = pane.captureAppearance()
                    val receipts = operation.receipts
                    assertTrue(receipts.any { it.status == RestoreStatus.OWNED })
                    for (process in listOf(StudioProcess(43, Instant.EPOCH), StudioProcess(42, Instant.EPOCH), null)) {
                        val recovered = participant.recover(input, receipts, glowRuntime(null, null, process))
                        assertFailsWith<IllegalStateException> { recovered.apply() }
                        recovered.prepareRestore(StudioList(emptyList()))
                        assertEquals(StudioRestoreContinuation.NONE, recovered.restore(StudioList(emptyList())))
                        if (process?.pid == 43L) {
                            assertTrue(recovered.receipts.all { it.status == RestoreStatus.RESTORED })
                            recovered.accept()
                        } else {
                            assertTrue(recovered.receipts.any { it.status == RestoreStatus.UNAVAILABLE })
                            assertFailsWith<IllegalStateException> { recovered.accept() }
                        }
                        assertEquals(written, pane.captureAppearance())
                        assertEquals(preferences, environment.stateXml())
                    }
                    operation.prepareRestore(StudioList(emptyList()))
                    operation.restore(StudioList(emptyList()))
                    operation.prepareRestore(StudioList(emptyList()))
                    assertEquals(StudioRestoreContinuation.NONE, operation.restore(StudioList(emptyList())))
                } finally {
                    held.release()
                }
            }
        }
    }

    private fun participantInput(
        selection: StudioGlowSelection,
        edit: AyuIslandsState.() -> Unit = {},
    ): StudioEffectInput {
        val requested = XmlSerializer.deserialize(JDOMUtil.load(environment.stateXml()), AyuIslandsState::class.java)
        requested.edit()
        val migrated = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture(requested)))
        return StudioEffectInput(migrated.document, StudioTarget.Global, StudioList(emptyList()), selection)
    }

    private fun glowAccent(manager: GlowOverlayManager): StudioAccentResult {
        val actual = ResolvedAccent(AccentResolver.Source.PROJECT_OVERRIDE, "#112233")
        return StudioAccentResult(
            AccentContext.Ayu(AyuVariant.MIRAGE),
            manager.project,
            actual,
            StudioList(listOf(StudioProjectAccent(manager.project, actual))),
        )
    }

    private fun glowRuntime(
        held: StudioGlowHolds?,
        confirmedAccent: StudioAccentResult?,
        process: StudioProcess? = StudioProcess(42, Instant.EPOCH),
    ): StudioEffectRuntime =
        object : StudioEffectRuntime {
            override val glow = held
            override val native = StudioNativeFacts(true, process)
            override val accent = confirmedAccent
            override val observation: StudioNativeObservation? = null

            override suspend fun confirmAccent(result: StudioAccentResult): Unit =
                error("This fixture does not hold native Accent admission")

            override suspend fun confirmTheme(transition: StudioThemeTransition): Unit =
                error("This fixture does not hold native theme admission")
        }

    private fun selectedGlow(vararg names: String): StudioGlowSelection =
        StudioGlowSelection(
            StudioList(names.map { FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, it)) }),
        )

    @Test
    fun `accepted cold registration installs callbacks without touching surfaces or preferences`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            val bus = manager.project.messageBus
            environment.state.glowEnabled = false
            val before = environment.stateXml()
            val hold = manager.holdUpdates()
            try {
                manager.registerCallbacks(hold)
                manager.registerCallbacks(hold)
                environment.drainUiCallbacks()

                assertTrue(fixture.overlays().isEmpty())
                assertEquals(before, environment.stateXml())
                verify(exactly = 1) { bus.connect(manager) }
                environment.assertFocusRegistrations(1)
            } finally {
                hold.release()
            }
        }
    }

    @Test
    fun `callback handoff repairs focus registration after invalidated legacy initialization`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            val bus = manager.project.messageBus
            manager.initialize()
            val hold = manager.holdUpdates()
            try {
                environment.drainUiCallbacks()
                environment.assertFocusRegistrations(0)
                manager.registerCallbacks(hold)
                environment.assertFocusRegistrations(1)
                assertTrue(fixture.overlays().isEmpty())
                verify(exactly = 1) { bus.connect(manager) }
            } finally {
                hold.release()
            }
        }
    }

    @Test
    fun `callback registration rejects another manager hold before subscribing`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            val bus = manager.project.messageBus
            val other = environment.createManager(fixture)
            val hold = other.holdUpdates()
            try {
                assertFailsWith<IllegalStateException> { manager.registerCallbacks(hold) }
                verify(exactly = 0) { bus.connect(manager) }
                environment.assertFocusRegistrations(0)
            } finally {
                hold.release()
            }
        }
    }

    @Test
    fun `failed application registration retries only its incomplete connection`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            val projectBus = manager.project.messageBus
            val applicationBus = ApplicationManager.getApplication().messageBus
            val partial = mockk<MessageBusConnection>(relaxed = true)
            val replacement = mockk<MessageBusConnection>(relaxed = true)
            every { applicationBus.connect(manager) } returnsMany listOf(partial, replacement)
            every {
                partial.subscribe(ApplicationActivationListener.TOPIC, any())
            } throws IllegalStateException("subscription failed")
            val hold = manager.holdUpdates()
            try {
                assertFailsWith<IllegalStateException> { manager.registerCallbacks(hold) }
                manager.registerCallbacks(hold)
                manager.registerCallbacks(hold)

                verify(exactly = 1) { projectBus.connect(manager) }
                verify(exactly = 1) { partial.disconnect() }
                verify(exactly = 2) { applicationBus.connect(manager) }
                verify(exactly = 1) { replacement.subscribe(ApplicationActivationListener.TOPIC, any()) }
                environment.assertFocusRegistrations(1)
                assertTrue(fixture.overlays().isEmpty())
            } finally {
                hold.release()
            }
        }
    }

    @Test
    fun `explicit external attachment uses candidate context and color without changing persisted choices`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            every { AyuVariant.detect() } returns null
            environment.state.externalThemeEnhancementsEnabled = false
            val preferences = environment.stateXml()
            val hold = manager.holdUpdates()
            try {
                val candidate =
                    requireNotNull(
                        hold.before,
                    ).copy(externalAllowed = true, accent = AccentHex.of("#112233"))
                assertNull(manager.prepareOverlay(GlowHost("Project", fixture.host, isEditor = false), candidate))
                val attachment =
                    requireNotNull(
                        manager.prepareOverlay(
                            GlowHost("Project", fixture.host, isEditor = false),
                            candidate,
                            context = AccentContext.External,
                            hold = hold,
                        ),
                    )
                assertTrue(fixture.overlays().isEmpty())
                attachment.apply()
                assertEquals(Color(0x112233), fixture.singleOverlay().captureAppearance().color)
                attachment.restore()
                assertTrue(fixture.overlays().isEmpty())
                assertEquals(preferences, environment.stateXml())
                verify(exactly = 0) { AccentResolver.resolve(any(), any<AccentContext>()) }
            } finally {
                hold.release()
            }
        }
    }

    @Test
    fun `held attachment rejects a foreign manager hold and cannot apply after its own hold expires`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            val other = environment.createManager(fixture)
            val hold = manager.holdUpdates()
            val foreign = other.holdUpdates()
            try {
                assertFailsWith<IllegalStateException> {
                    manager.prepareOverlay(
                        GlowHost("Project", fixture.host, isEditor = false),
                        requireNotNull(hold.before),
                        hold = foreign,
                    )
                }
                val attachment =
                    requireNotNull(
                        manager.prepareOverlay(
                            GlowHost("Project", fixture.host, isEditor = false),
                            requireNotNull(hold.before),
                            hold = hold,
                        ),
                    )
                hold.release()
                val replacement = manager.holdUpdates()
                try {
                    assertFailsWith<IllegalStateException> { attachment.apply() }
                    assertFailsWith<IllegalStateException> {
                        manager.prepareOverlay(
                            GlowHost("Project", fixture.host, isEditor = false),
                            requireNotNull(hold.before),
                            hold = hold,
                        )
                    }
                    assertTrue(fixture.overlays().isEmpty())
                    assertEquals(GlowCheckpointPhase.PREPARED, attachment.phase)
                } finally {
                    replacement.release()
                }
            } finally {
                hold.release()
                foreign.release()
            }
        }
    }

    @Test
    fun `missing hosts preserve native order and candidate gates without attaching or rewriting settings`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            val terminal = ToolWindowHostFixture(environment.secondaryRoot())
            environment.addTerminal(terminal)
            val editor = EditorsSplittersFixture()
            fixture.root.contentPane.add(editor)
            editor.setBounds(340, 30, 200, 300)
            val fileEditor = mockk<FileEditor>()
            every { fileEditor.component } returns editor
            val editorManager = FileEditorManager.getInstance(manager.project)
            every { editorManager.selectedEditor } returns fileEditor
            val stored = loadGlowRuntimeInput(environment.state).settings
            val enabledIslands = stored.islandToggles + mapOf("Editor" to true, "Terminal" to true)
            val settings = stored.copy(islandToggles = enabledIslands)
            val preferences = environment.stateXml()

            val hosts = GlowHosts.findMissing(manager.project, settings, emptySet())

            assertEquals(listOf("Project", "Terminal", "Editor"), hosts.map { it.id })
            assertEquals(listOf(fixture.host, terminal.host, editor), hosts.map { it.host })
            assertEquals(listOf(false, false, true), hosts.map { it.isEditor })
            val toolWindows = ToolWindowManager.getInstance(manager.project)
            every { toolWindows.getToolWindow("Terminal")!!.isVisible } returns false
            val candidate = settings.copy(islandToggles = settings.islandToggles + ("Project" to false))
            assertEquals(listOf("Editor"), GlowHosts.findMissing(manager.project, candidate, emptySet()).map { it.id })
            assertTrue(GlowHosts.findMissing(manager.project, candidate, setOf("Editor")).isEmpty())
            environment.rejectToolWindowReads()
            assertTrue(GlowHosts.findMissing(manager.project, settings.copy(enabled = false), emptySet()).isEmpty())
            assertTrue(fixture.overlays().isEmpty())
            assertTrue(terminal.overlays().isEmpty())
            assertEquals(preferences, environment.stateXml())
        }
    }

    @Test
    fun `Studio holds capture every manager in order without changing native surfaces or preferences`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val first = environment.createManager(fixture)
            val second = environment.createManager(fixture)
            val supplied = mutableListOf(first, second)
            val held = StudioGlowHolds(supplied)
            val before = held.entries
            val preferences = environment.stateXml()
            supplied.clear()

            held.acquire()

            assertTrue(held.isActive)
            assertTrue(before.isEmpty())
            assertEquals(listOf(first, second), held.entries.map { it.manager })
            assertTrue(held.entries.all { it.hold.isActive })
            assertFailsWith<IllegalStateException> { first.holdUpdates() }
            assertFailsWith<IllegalStateException> { second.holdUpdates() }
            assertTrue(fixture.overlays().isEmpty())
            assertEquals(preferences, environment.stateXml())
            held.release()
            held.release()
            assertFalse(held.isActive)
            first.holdUpdates().release()
            second.holdUpdates().release()
        }
    }

    @Test
    fun `partial Studio acquisition retains earlier holds without releasing a foreign hold`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val first = environment.createManager(fixture)
            val second = environment.createManager(fixture)
            val foreign = second.holdUpdates()
            val held = StudioGlowHolds(listOf(first, second))
            try {
                assertFailsWith<IllegalStateException> { held.acquire() }

                assertFalse(held.isActive)
                val captured = held.entries.single()
                assertSame(first, captured.manager)
                assertTrue(captured.hold.isActive)
                assertFailsWith<IllegalStateException> { held.acquire() }
                held.release()
                assertTrue(foreign.isActive)
                first.holdUpdates().release()
            } finally {
                foreign.release()
            }
        }
    }

    @Test
    fun `Studio release keeps a failed focus cleanup and releases independent managers`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val first = environment.createManager(fixture)
            val second = environment.createManager(fixture)
            val held = StudioGlowHolds(listOf(first, second))
            var isFocused = false
            var failBorder = false
            val field =
                object : JTextField() {
                    override fun hasFocus(): Boolean = isFocused

                    override fun setBorder(border: Border?) {
                        check(!failBorder) { "Native focus border rejected the write" }
                        super.setBorder(border)
                    }
                }
            val window = mockk<Window>(relaxed = true)
            every { window.components } returns arrayOf(field)
            mockkStatic(Window::class)
            every { Window.getWindows() } returns arrayOf(window)
            try {
                held.acquire()
                val owned = held.entries.first().hold
                val independent = held.entries.last().hold
                val attachment =
                    owned.focus
                        .prepare(GlowFocusAppearance(Color.CYAN, GlowStyle.SOFT, 70, true), emptyList()) {
                            owned.isActive
                        }.single()
                attachment.apply()
                attachment.accept()
                isFocused = true
                failBorder = true

                assertFailsWith<IllegalStateException> { held.release() }

                assertFalse(held.isActive)
                assertTrue(owned.isActive)
                assertFalse(independent.isActive)
                failBorder = false
                held.release()
                assertTrue(held.entries.none { it.hold.isActive })
                assertTrue(field.border is GlowFocusBorder)
            } finally {
                failBorder = false
                held.release()
                unmockkStatic(Window::class)
            }
        }
    }

    @Test
    fun `Studio distinguishes an acquired empty census from an unacquired hold`() {
        environment.onEdt {
            val held = StudioGlowHolds(emptyList())
            assertFalse(held.isActive)
            held.acquire()
            assertTrue(held.isActive)
            assertTrue(held.entries.isEmpty())
            held.release()
            assertFalse(held.isActive)
            assertFailsWith<IllegalStateException> { held.acquire() }
        }
    }

    @Test
    fun `initializing twice keeps one configured overlay and preserves user state`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val childrenBefore = fixture.host.components.toList()
            val componentListenersBefore = fixture.host.componentListeners.toList()
            val hierarchyListenersBefore = fixture.host.hierarchyBoundsListeners.toList()
            val rootChildrenBefore =
                fixture.root.layeredPane.components
                    .toList()
            val stateXmlBefore = environment.stateXml()
            val manager = environment.createManager(fixture)

            manager.initialize()
            environment.drainUiCallbacks()

            val firstPane = fixture.singleOverlay()
            assertConfiguredPane(firstPane, Rectangle(20, 30, 300, 200))
            val componentListenersAfterAttach = fixture.host.componentListeners.toList()
            val hierarchyListenersAfterAttach = fixture.host.hierarchyBoundsListeners.toList()
            assertOneOwnedListenerAdded(componentListenersBefore, componentListenersAfterAttach)
            assertOneOwnedListenerAdded(hierarchyListenersBefore, hierarchyListenersAfterAttach)
            assertSameIdentityOrder(
                rootChildrenBefore,
                fixture.root.layeredPane.components
                    .filterNot { it === firstPane },
            )

            manager.initialize()
            environment.drainUiCallbacks()

            assertSame(firstPane, fixture.singleOverlay())
            assertSameIdentityOrder(componentListenersAfterAttach, fixture.host.componentListeners.toList())
            assertSameIdentityOrder(hierarchyListenersAfterAttach, fixture.host.hierarchyBoundsListeners.toList())
            assertSameIdentityOrder(childrenBefore, fixture.host.components.toList())
            assertEquals(stateXmlBefore, environment.stateXml())
        }
    }

    @Test
    fun `prepared overlay stays detached until apply and restores original hierarchy and listeners`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val layer = fixture.root.layeredPane
            val children = layer.components.toList()
            val componentListeners = fixture.host.componentListeners.toList()
            val boundsListeners = fixture.host.hierarchyBoundsListeners.toList()
            val preferences = environment.stateXml()
            val manager = environment.createManager(fixture)
            val hold = manager.holdUpdates()
            try {
                val attachment =
                    requireNotNull(
                        manager.prepareOverlay(
                            GlowHost("Project", fixture.host, isEditor = false),
                            requireNotNull(hold.before),
                        ),
                    )
                val references = StudioGlowReferences("attempt", StudioProcess(42, Instant.EPOCH))
                val addition = StudioGlowOverlayAdd(references, attachment)
                val original = addition.receipts.single()
                assertEquals(references.token(attachment.entry.glassPane), original.identity.resource)
                assertEquals(RestoreStatus.PENDING, original.status)
                assertFalse(attachment.hasPending)
                assertEquals(GlowCheckpointPhase.PREPARED, attachment.phase)
                assertSameIdentityOrder(children, layer.components.toList())
                assertSameIdentityOrder(componentListeners, fixture.host.componentListeners.toList())
                assertSameIdentityOrder(boundsListeners, fixture.host.hierarchyBoundsListeners.toList())

                addition.apply()
                assertTrue(attachment.hasPending)
                assertEquals(GlowCheckpointPhase.APPLIED, attachment.phase)
                assertConfiguredPane(fixture.singleOverlay(), Rectangle(20, 30, 300, 200))
                assertOneOwnedListenerAdded(componentListeners, fixture.host.componentListeners.toList())
                assertOneOwnedListenerAdded(boundsListeners, fixture.host.hierarchyBoundsListeners.toList())

                val applied = addition.receipts.single()
                assertEquals(RestoreStatus.OWNED, applied.status)
                assertEquals(original, applied.copy(status = original.status))
                val newer =
                    applied.copy(identity = applied.identity.copy(field = "glow/pane/COLOR", ownerToken = "newer"))
                assertFailsWith<IllegalStateException> { addition.restore(listOf(newer)) }
                assertSame(attachment.entry.glassPane, fixture.singleOverlay())
                addition.restore(emptyList())
                addition.restore(emptyList())
                assertEquals(RestoreStatus.RESTORED, addition.receipts.single().status)
                assertEquals(GlowCheckpointPhase.RESTORED, attachment.phase)
                assertFalse(attachment.hasPending)
                assertSameIdentityOrder(children, layer.components.toList())
                assertSameIdentityOrder(componentListeners, fixture.host.componentListeners.toList())
                assertSameIdentityOrder(boundsListeners, fixture.host.hierarchyBoundsListeners.toList())
                assertEquals(preferences, environment.stateXml())
            } finally {
                hold.release()
            }
        }
    }

    @Test
    fun `partial overlay insertion retains cleanup through a failing removal`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val layer = fixture.root.layeredPane
            val children = layer.components.toList()
            val componentListeners = fixture.host.componentListeners.toList()
            val boundsListeners = fixture.host.hierarchyBoundsListeners.toList()
            val manager = environment.createManager(fixture)
            val hold = manager.holdUpdates()
            val failure =
                object : ContainerAdapter() {
                    override fun componentAdded(event: ContainerEvent) {
                        if (event.child is GlowGlassPane) error("After native insertion")
                    }

                    override fun componentRemoved(event: ContainerEvent) {
                        if (event.child is GlowGlassPane) error("After native removal")
                    }
                }
            layer.addContainerListener(failure)
            try {
                val attachment =
                    requireNotNull(
                        manager.prepareOverlay(
                            GlowHost("Project", fixture.host, isEditor = false),
                            requireNotNull(hold.before),
                        ),
                    )
                val references = StudioGlowReferences("attempt", StudioProcess(42, Instant.EPOCH))
                val addition = StudioGlowOverlayAdd(references, attachment)
                val original = addition.receipts.single()
                assertFailsWith<IllegalStateException> { addition.apply() }
                assertEquals(original, addition.receipts.single())
                assertEquals(GlowCheckpointPhase.APPLYING, attachment.phase)
                assertEquals(1, fixture.overlays().size)
                assertTrue(attachment.hasPending)
                assertFailsWith<IllegalStateException> { addition.accept() }

                assertFailsWith<IllegalStateException> { addition.restore(emptyList()) }
                assertEquals(GlowCheckpointPhase.RESTORING, attachment.phase)
                assertTrue(fixture.overlays().isEmpty())
                assertEquals(original, addition.receipts.single())
                assertTrue(attachment.hasPending)

                addition.restore(emptyList())
                assertFalse(attachment.hasPending)
                assertEquals(RestoreStatus.RESTORED, addition.receipts.single().status)
                assertEquals(GlowCheckpointPhase.RESTORED, attachment.phase)
                assertSameIdentityOrder(children, layer.components.toList())
                assertSameIdentityOrder(componentListeners, fixture.host.componentListeners.toList())
                assertSameIdentityOrder(boundsListeners, fixture.host.hierarchyBoundsListeners.toList())
            } finally {
                layer.removeContainerListener(failure)
                hold.release()
            }
        }
    }

    @Test
    fun `held geometry refresh follows restored appearance and current host position`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            val hold = manager.holdUpdates()
            try {
                val attachment =
                    requireNotNull(
                        manager.prepareOverlay(
                            GlowHost("Project", fixture.host, isEditor = false),
                            requireNotNull(hold.before),
                            hold = hold,
                        ),
                    )
                attachment.apply()
                val pane = fixture.singleOverlay()
                val before = pane.captureAppearance()
                val snapshot = manager.capturePlayback(hold).overlays
                val planned = before.copy(shape = GlowShape.WAVEFORM, waveform = WaveformConfig(amplitude = 32))
                val change = requireNotNull(snapshot.capturePane("Project", planned))
                change.apply()
                fixture.host.setLocation(70, 90)
                snapshot.refreshBounds("Project")
                val margin = pane.waveformMargin
                assertEquals(Rectangle(70 - margin, 90 - margin, 300 + 2 * margin, 200 + 2 * margin), pane.bounds)

                change.restore()
                fixture.host.setLocation(45, 65)
                snapshot.refreshBounds("Project")
                assertEquals(Rectangle(45, 65, 300, 200), pane.bounds)
                attachment.restore()
            } finally {
                hold.release()
            }
        }
    }

    @Test
    fun `held geometry refresh rejects replacement root before changing the retained pane`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            val hold = manager.holdUpdates()
            try {
                val attachment =
                    requireNotNull(
                        manager.prepareOverlay(
                            GlowHost("Project", fixture.host, isEditor = false),
                            requireNotNull(hold.before),
                            hold = hold,
                        ),
                    )
                attachment.apply()
                val pane = fixture.singleOverlay()
                val snapshot = manager.capturePlayback(hold).overlays
                val before = pane.bounds
                environment.secondaryRoot().contentPane.add(fixture.host)
                fixture.host.setLocation(90, 80)

                assertFailsWith<IllegalStateException> { snapshot.refreshBounds("Project") }

                assertEquals(before, pane.bounds)
                assertTrue(environment.secondaryRoot().overlays().isEmpty())
                attachment.restore()
            } finally {
                hold.release()
            }
        }
    }

    @Test
    fun `held geometry stops after a repaint moves the host to a foreign root`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            val hold = manager.holdUpdates()
            val repaint = RepaintManager.currentManager(fixture.host)
            try {
                val attachment =
                    requireNotNull(
                        manager.prepareOverlay(
                            GlowHost("Project", fixture.host, isEditor = false),
                            requireNotNull(hold.before),
                            hold = hold,
                        ),
                    )
                attachment.apply()
                val pane = fixture.singleOverlay()
                pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig())
                pane.waveformTopSpans = listOf(5..10)
                val snapshot = manager.capturePlayback(hold).overlays
                val before = pane.bounds
                var hasMoved = false
                RepaintManager.setCurrentManager(
                    object : RepaintManager() {
                        override fun addDirtyRegion(
                            component: javax.swing.JComponent,
                            x: Int,
                            y: Int,
                            width: Int,
                            height: Int,
                        ) {
                            if (component === pane && !hasMoved) {
                                hasMoved = true
                                environment.secondaryRoot().contentPane.add(fixture.host)
                            }
                        }
                    },
                )

                assertFailsWith<IllegalStateException> { snapshot.refreshBounds("Project") }

                assertTrue(hasMoved)
                assertEquals(before, pane.bounds)
                assertTrue(environment.secondaryRoot().overlays().isEmpty())
                attachment.restore()
            } finally {
                RepaintManager.setCurrentManager(repaint)
                hold.release()
            }
        }
    }

    @Test
    fun `prepared overlay cannot follow its host into a replacement root`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val replacement = environment.secondaryRoot()
            val manager = environment.createManager(fixture)
            val hold = manager.holdUpdates()
            try {
                val attachment =
                    requireNotNull(
                        manager.prepareOverlay(
                            GlowHost("Project", fixture.host, isEditor = false),
                            requireNotNull(hold.before),
                        ),
                    )
                replacement.contentPane.add(fixture.host)
                layoutHostInRoot(replacement, fixture.host)

                assertFailsWith<IllegalStateException> { attachment.apply() }
                attachment.restore()

                assertFalse(attachment.hasPending)
                assertTrue(fixture.overlays().isEmpty())
                assertTrue(replacement.overlays().isEmpty())
            } finally {
                hold.release()
            }
        }
    }

    @Test
    fun `newer membership receipt blocks cleanup without changing native resources`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            val hold = manager.holdUpdates()
            try {
                val attachment =
                    requireNotNull(
                        manager.prepareOverlay(
                            GlowHost("Project", fixture.host, isEditor = false),
                            requireNotNull(hold.before),
                        ),
                    )
                attachment.apply()
                val pane = fixture.singleOverlay()
                val children =
                    fixture.root.layeredPane.components
                        .toList()
                val componentListeners = fixture.host.componentListeners.toList()
                val boundsListeners = fixture.host.hierarchyBoundsListeners.toList()

                assertFailsWith<IllegalStateException> { attachment.restore(isBlocked = true) }

                assertTrue(attachment.hasPending)
                assertSame(pane, fixture.singleOverlay())
                assertSameIdentityOrder(
                    children,
                    fixture.root.layeredPane.components
                        .toList(),
                )
                assertSameIdentityOrder(componentListeners, fixture.host.componentListeners.toList())
                assertSameIdentityOrder(boundsListeners, fixture.host.hierarchyBoundsListeners.toList())
                attachment.restore()
                assertFalse(attachment.hasPending)
                assertTrue(fixture.overlays().isEmpty())
            } finally {
                hold.release()
            }
        }
    }

    @Test
    fun `stale attachment cannot accept or remove its replacement after a layout change`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val replacement = environment.secondaryRoot()
            environment.state.glowProject = false
            val manager = environment.createManager(fixture)
            manager.initialize()
            environment.drainUiCallbacks()
            environment.state.glowProject = true
            val hold = manager.holdUpdates()
            val attachment =
                requireNotNull(
                    manager.prepareOverlay(
                        GlowHost("Project", fixture.host, isEditor = false),
                        requireNotNull(hold.before),
                    ),
                )
            try {
                attachment.apply()
            } finally {
                hold.release()
            }
            replacement.contentPane.add(fixture.host)
            layoutHostInRoot(replacement, fixture.host)
            environment.publishSetLayout()
            environment.drainUiCallbacks()
            val pane = replacement.singleOverlay()
            val children = replacement.layeredPane.components.toList()
            val componentListeners = fixture.host.componentListeners.toList()
            val boundsListeners = fixture.host.hierarchyBoundsListeners.toList()

            assertFailsWith<IllegalStateException> { attachment.accept() }
            attachment.restore()

            assertFalse(attachment.hasPending)
            assertSame(pane, replacement.singleOverlay())
            assertSameIdentityOrder(children, replacement.layeredPane.components.toList())
            assertSameIdentityOrder(componentListeners, fixture.host.componentListeners.toList())
            assertSameIdentityOrder(boundsListeners, fixture.host.hierarchyBoundsListeners.toList())
            assertNull(
                manager.prepareOverlay(
                    GlowHost("Project", fixture.host, isEditor = false),
                    loadGlowRuntimeInput(environment.state),
                ),
            )
        }
    }

    @Test
    fun `cleanup does not follow a pane moved by a native insertion callback`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val foreignLayer = environment.secondaryRoot().layeredPane
            val manager = environment.createManager(fixture)
            val hold = manager.holdUpdates()
            val mover =
                object : ContainerAdapter() {
                    override fun componentAdded(event: ContainerEvent) {
                        if (event.child is GlowGlassPane) foreignLayer.add(event.child)
                    }
                }
            fixture.root.layeredPane.addContainerListener(mover)
            try {
                val attachment =
                    requireNotNull(
                        manager.prepareOverlay(
                            GlowHost("Project", fixture.host, isEditor = false),
                            requireNotNull(hold.before),
                        ),
                    )
                assertFailsWith<IllegalStateException> { attachment.apply() }
                val pane = attachment.entry.glassPane
                assertSame(foreignLayer, pane.parent)

                assertFailsWith<IllegalStateException> { attachment.restore() }
                assertSame(foreignLayer, pane.parent)
                assertTrue(attachment.hasPending)

                foreignLayer.remove(pane)
                attachment.restore()
                assertFalse(attachment.hasPending)
            } finally {
                fixture.root.layeredPane.removeContainerListener(mover)
                hold.release()
            }
        }
    }

    @Test
    fun `attachment reserves the target before native callbacks and remains installed after acceptance`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            val hold = manager.holdUpdates()
            var insertionObserved = false
            val observer =
                object : ContainerAdapter() {
                    override fun componentAdded(event: ContainerEvent) {
                        if (event.child !is GlowGlassPane) return
                        insertionObserved = true
                        assertNull(
                            manager.prepareOverlay(
                                GlowHost("Project", fixture.host, isEditor = false),
                                requireNotNull(hold.before),
                            ),
                        )
                    }
                }
            fixture.root.layeredPane.addContainerListener(observer)
            try {
                val attachment =
                    requireNotNull(
                        manager.prepareOverlay(
                            GlowHost("Project", fixture.host, isEditor = false),
                            requireNotNull(hold.before),
                        ),
                    )
                attachment.apply()
                assertTrue(insertionObserved)
                val pane = fixture.singleOverlay()
                val componentListeners = fixture.host.componentListeners.toList()
                val boundsListeners = fixture.host.hierarchyBoundsListeners.toList()

                attachment.accept()
                attachment.accept()
                assertFalse(attachment.hasPending)
                assertEquals(GlowCheckpointPhase.ACCEPTED, attachment.phase)
                assertFailsWith<IllegalStateException> { attachment.restore() }
                assertFailsWith<IllegalStateException> { attachment.apply() }
                assertSame(pane, fixture.singleOverlay())
                assertSameIdentityOrder(componentListeners, fixture.host.componentListeners.toList())
                assertSameIdentityOrder(boundsListeners, fixture.host.hierarchyBoundsListeners.toList())
            } finally {
                fixture.root.layeredPane.removeContainerListener(observer)
                hold.release()
            }
        }
    }

    @Test
    fun `failed animation startup stays owned by its manager for cleanup`() {
        mockkConstructor(GlowAnimator::class)
        val failure = IllegalStateException("After animator startup")
        every { anyConstructed<GlowAnimator>().start(any(), any(), any()) } throws failure
        every { anyConstructed<GlowAnimator>().dispose() } just Runs
        try {
            environment.onEdt {
                val fixture = environment.hostFixture()
                environment.setFocusOwner(fixture.content)
                environment.state.glowAnimation = GlowAnimation.PULSE.name
                val manager = environment.createManager(fixture)
                try {
                    manager.initialize()
                    val thrown = assertFailsWith<IllegalStateException> { environment.drainUiCallbacks() }
                    assertSame(failure, thrown)
                    manager.dispose()

                    verify(exactly = 1) { anyConstructed<GlowAnimator>().dispose() }
                } finally {
                    manager.dispose()
                }
            }
        } finally {
            unmockkConstructor(GlowAnimator::class)
        }
    }

    @Test
    fun `disabling and reloading settings restores the same user configured overlay`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val childrenBefore = fixture.host.components.toList()
            val componentListenersBefore = fixture.host.componentListeners.toList()
            val hierarchyListenersBefore = fixture.host.hierarchyBoundsListeners.toList()
            var manager = environment.createManager(fixture)
            manager.initialize()
            environment.drainUiCallbacks()

            environment.state.glowEnabled = false
            val disabledXml = environment.stateXml()
            val disabledCustomizationOrder =
                environment.state.fontPresetCustomizations.keys
                    .toList()
            manager.updateGlow()
            environment.drainUiCallbacks()

            assertTrue(fixture.overlays().isEmpty())
            assertSameIdentityOrder(componentListenersBefore, fixture.host.componentListeners.toList())
            assertSameIdentityOrder(hierarchyListenersBefore, fixture.host.hierarchyBoundsListeners.toList())
            assertSameIdentityOrder(childrenBefore, fixture.host.components.toList())
            assertEquals(disabledXml, environment.stateXml())
            assertInMemoryCollectionState(environment.state, disabledCustomizationOrder)

            environment.state.glowEnabled = true
            val enabledXml = environment.stateXml()
            val enabledCustomizationOrder =
                environment.state.fontPresetCustomizations.keys
                    .toList()
            manager.updateGlow()
            environment.drainUiCallbacks()
            assertConfiguredPane(fixture.singleOverlay(), Rectangle(20, 30, 300, 200))
            assertEquals(enabledXml, environment.stateXml())
            assertInMemoryCollectionState(environment.state, enabledCustomizationOrder)

            manager.dispose()
            environment.drainUiCallbacks()
            environment.reloadState(enabledXml)
            manager = environment.createManager(fixture)
            manager.initialize()
            environment.drainUiCallbacks()

            assertConfiguredPane(fixture.singleOverlay(), Rectangle(20, 30, 300, 200))
            assertSameIdentityOrder(childrenBefore, fixture.host.components.toList())
            assertEquals(enabledXml, environment.stateXml())
            assertPersistedCollectionState(environment.state)
        }
    }

    @Test
    fun `target changes remove and restore its overlay without resetting saved choices`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val listenersBefore = fixture.host.componentListeners.toList()
            val manager = environment.createManager(fixture)
            manager.initialize()
            environment.drainUiCallbacks()
            val pane = fixture.singleOverlay()

            environment.state.glowProject = false
            val disabledXml = environment.stateXml()
            manager.updateGlow()
            environment.drainUiCallbacks()

            assertTrue(fixture.overlays().isEmpty())
            assertNull(pane.parent)
            assertSameIdentityOrder(listenersBefore, fixture.host.componentListeners.toList())
            assertEquals(disabledXml, environment.stateXml())

            environment.state.glowProject = true
            val enabledXml = environment.stateXml()
            manager.updateGlow()
            environment.drainUiCallbacks()

            assertConfiguredPane(fixture.singleOverlay(), Rectangle(20, 30, 300, 200))
            assertEquals(enabledXml, environment.stateXml())
        }
    }

    @Test
    fun `enabling another target keeps the existing overlay and attaches the newly selected panel`() {
        environment.onEdt {
            val projectFixture = environment.hostFixture()
            val terminalFixture = ToolWindowHostFixture(environment.secondaryRoot())
            val manager = environment.createManager(projectFixture)
            environment.addTerminal(terminalFixture)
            manager.initialize()
            environment.drainUiCallbacks()
            val projectPane = projectFixture.singleOverlay()
            assertTrue(terminalFixture.overlays().isEmpty())

            environment.state.glowTerminal = true
            val enabledXml = environment.stateXml()
            manager.updateGlow()
            environment.drainUiCallbacks()

            assertSame(projectPane, projectFixture.singleOverlay())
            assertEquals(1, terminalFixture.overlays().size)
            assertConfiguredPane(terminalFixture.singleOverlay(), Rectangle(20, 30, 300, 200))
            assertEquals(enabledXml, environment.stateXml())
        }
    }

    @Test
    fun `tool window layout event moves one overlay to the new root without changing user state`() {
        environment.onEdt {
            val firstFixture = environment.hostFixture()
            val secondRoot = environment.secondaryRoot()
            val hostChildrenBefore = firstFixture.host.components.toList()
            val componentListenersBefore = firstFixture.host.componentListeners.toList()
            val hierarchyListenersBefore = firstFixture.host.hierarchyBoundsListeners.toList()
            val stateXmlBefore = environment.stateXml()
            val manager = environment.createManager(firstFixture)
            manager.initialize()
            environment.drainUiCallbacks()
            val oldPane = firstFixture.singleOverlay()

            firstFixture.root.contentPane.remove(firstFixture.host)
            secondRoot.contentPane.add(firstFixture.host)
            layoutHostInRoot(secondRoot, firstFixture.host)
            environment.publishSetLayout()
            environment.drainUiCallbacks()

            assertTrue(firstFixture.overlays().isEmpty())
            assertNull(oldPane.parent)
            val movedPane = secondRoot.singleOverlay()
            assertConfiguredPane(movedPane, Rectangle(20, 30, 300, 200))
            val componentListenersAfterMove = firstFixture.host.componentListeners.toList()
            val hierarchyListenersAfterMove = firstFixture.host.hierarchyBoundsListeners.toList()
            assertOneOwnedListenerAdded(componentListenersBefore, componentListenersAfterMove)
            assertOneOwnedListenerAdded(hierarchyListenersBefore, hierarchyListenersAfterMove)
            assertSameIdentityOrder(hostChildrenBefore, firstFixture.host.components.toList())
            assertEquals(stateXmlBefore, environment.stateXml())

            environment.publishSetLayout()
            environment.drainUiCallbacks()

            assertSame(movedPane, secondRoot.singleOverlay())
            assertSameIdentityOrder(componentListenersAfterMove, firstFixture.host.componentListeners.toList())
            assertSameIdentityOrder(hierarchyListenersAfterMove, firstFixture.host.hierarchyBoundsListeners.toList())
        }
    }

    @Test
    fun `queued refresh cannot query disposed tool windows or recreate effects`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val manager = environment.createManager(fixture)
            manager.initialize()
            environment.drainUiCallbacks()
            manager.updateGlow()
            manager.dispose()
            environment.rejectToolWindowReads()

            environment.drainUiCallbacks()

            assertTrue(fixture.overlays().isEmpty())
        }
    }

    @Test
    fun `placement preview stays transient and disposal prevents late overlay resurrection`() {
        environment.onEdt {
            val fixture = environment.hostFixture()
            val listenersBefore = fixture.host.componentListeners.toList()
            val hierarchyListenersBefore = fixture.host.hierarchyBoundsListeners.toList()
            val manager = environment.createManager(fixture)
            manager.initialize()
            environment.drainUiCallbacks()
            val pane = fixture.singleOverlay()
            val stateXmlBeforePreview = environment.stateXml()

            manager.previewPlacements(null, GlowPlacement.SIDE_EDGES)

            assertEquals(GlowPlacement.SIDE_EDGES, pane.glowPlacement)
            assertEquals(stateXmlBeforePreview, environment.stateXml())

            manager.previewPlacements(null, null)

            assertEquals(GlowPlacement.ISLAND, pane.glowPlacement)
            assertEquals(stateXmlBeforePreview, environment.stateXml())

            environment.state.glowToolWindowPlacement = GlowPlacement.SIDE_EDGES.name
            val stateXmlAfterUserChange = environment.stateXml()
            manager.previewPlacements(null, GlowPlacement.ISLAND)

            assertEquals(GlowPlacement.ISLAND, pane.glowPlacement)
            assertEquals(stateXmlAfterUserChange, environment.stateXml())

            manager.previewPlacements(null, null)

            assertEquals(GlowPlacement.SIDE_EDGES, pane.glowPlacement)
            assertEquals(stateXmlAfterUserChange, environment.stateXml())

            manager.dispose()
            environment.drainUiCallbacks()
            assertTrue(fixture.overlays().isEmpty())
            assertSameIdentityOrder(listenersBefore, fixture.host.componentListeners.toList())
            assertSameIdentityOrder(hierarchyListenersBefore, fixture.host.hierarchyBoundsListeners.toList())

            environment.publishSetLayout()
            manager.initialize()
            environment.drainUiCallbacks()

            assertTrue(fixture.overlays().isEmpty())
            assertEquals(stateXmlAfterUserChange, environment.stateXml())
        }
    }

    private fun assertConfiguredPane(
        pane: GlowGlassPane,
        expectedBounds: Rectangle,
    ) {
        assertEquals(Color(0x5C, 0xCF, 0xE6), pane.glowColor)
        assertEquals(GlowStyle.SOFT, pane.glowStyle)
        assertEquals(67, pane.glowIntensity)
        assertEquals(9, pane.glowWidth)
        assertEquals(GlowPlacement.ISLAND, pane.glowPlacement)
        assertEquals(expectedBounds, pane.bounds)
    }

    private fun assertSameIdentityOrder(
        expected: List<*>,
        actual: List<*>,
    ) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedComponent, actualComponent) ->
            assertSame(expectedComponent, actualComponent)
        }
    }

    private fun assertOneOwnedListenerAdded(
        baseline: List<*>,
        listenersAfterAttach: List<*>,
    ) {
        val retainedBaseline = listenersAfterAttach.filter { listener -> baseline.any { it === listener } }
        val ownedListeners = listenersAfterAttach.filterNot { listener -> baseline.any { it === listener } }
        assertSameIdentityOrder(baseline, retainedBaseline)
        assertEquals(1, ownedListeners.size)
    }

    private fun assertInMemoryCollectionState(
        state: AyuIslandsState,
        expectedCustomizationOrder: List<String>,
    ) {
        assertEquals(expectedCustomizationOrder, state.fontPresetCustomizations.keys.toList())
        assertPersistedCollectionState(state)
    }

    private fun assertPersistedCollectionState(state: AyuIslandsState) {
        assertEquals("14|1.2|true|REGULAR", state.fontPresetCustomizations["AMBIENT"])
        assertEquals("future|unavailable|value", state.fontPresetCustomizations["FUTURE_PRESET"])
        assertEquals(
            "/Users/roman/Library/Fonts/MapleMono-Regular.ttf\n" +
                "/Users/roman/Library/Fonts/MapleMono-Italic.ttf",
            state.installedFontFiles["Maple Mono"],
        )
        assertEquals(
            listOf(
                "/Users/roman/Library/Fonts/MapleMono-Regular.ttf",
                "/Users/roman/Library/Fonts/MapleMono-Italic.ttf",
            ),
            AyuIslandsState.decodeFontPaths(state.installedFontFiles["Maple Mono"]),
        )
    }
}

private class PublicLifecycleEnvironment(
    private val fixture: ToolWindowHostFixture,
    private val spareRoot: JRootPane,
) : AutoCloseable {
    private val uiCallbacks = ArrayDeque<Runnable>()
    private val application = mockk<com.intellij.openapi.application.Application>(relaxed = true)
    private val applicationBus = mockk<MessageBus>(relaxed = true)
    private val applicationConnection = mockk<MessageBusConnection>(relaxed = true)
    private val settings = mockk<AyuIslandsSettings>(relaxed = true)
    private val projectManager = mockk<ProjectManager>(relaxed = true)
    private val focusManager = mockk<KeyboardFocusManager>(relaxed = true)
    private val managedManagers = mutableListOf<GlowOverlayManager>()
    private var currentState = configuredState()
    private var toolWindowListener: ToolWindowManagerListener? = null
    private var toolWindowManager: ToolWindowManager? = null

    val state: AyuIslandsState
        get() = currentState

    init {
        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.invokeLater(any()) } answers { uiCallbacks.addLast(firstArg()) }

        mockkConstructor(Timer::class)
        every { anyConstructed<Timer>().start() } just Runs

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.isActive } returns true
        every { application.messageBus } returns applicationBus
        every { applicationBus.connect(any<Disposable>()) } returns applicationConnection
        every { application.getService(KeystrokeHub::class.java) } returns mockk(relaxed = true)

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        every { settings.state } answers {
            StudioFixtures
                .legacyView(currentState)
                .settings
        }

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AccentContext>()) } returns "#5CCFE6"

        mockkStatic(PowerSaveMode::class)
        every { PowerSaveMode.isEnabled() } returns false
        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns projectManager
        every { projectManager.openProjects } returns emptyArray()
        mockkStatic(KeyboardFocusManager::class)
        every { KeyboardFocusManager.getCurrentKeyboardFocusManager() } returns focusManager
        every { focusManager.permanentFocusOwner } returns null
        every { focusManager.currentFocusCycleRoot } returns null
        every { focusManager.focusOwner } returns null
    }

    fun assertFocusRegistrations(expected: Int) {
        verify(exactly = expected) { focusManager.addPropertyChangeListener("permanentFocusOwner", any()) }
    }

    fun hostFixture(): ToolWindowHostFixture = fixture

    fun secondaryRoot(): JRootPane = spareRoot

    fun setFocusOwner(component: Component) {
        every { focusManager.permanentFocusOwner } returns component
    }

    fun createManager(hostFixture: ToolWindowHostFixture): GlowOverlayManager {
        val project = mockk<Project>(relaxed = true)
        val projectBus = mockk<MessageBus>(relaxed = true)
        val projectConnection = mockk<MessageBusConnection>(relaxed = true)
        val editorManager = mockk<FileEditorManager>(relaxed = true)
        val manager = mockk<ToolWindowManager>(relaxed = true)
        val toolWindow = mockk<ToolWindow>(relaxed = true)
        val listenerSlot = slot<ToolWindowManagerListener>()

        every { project.isDisposed } returns false
        every { project.isDefault } returns false
        every { project.name } returns "Ayu Islands lifecycle project"
        every { project.messageBus } returns projectBus
        every { projectBus.connect(any<Disposable>()) } returns projectConnection
        every {
            projectConnection.subscribe(ToolWindowManagerListener.TOPIC, capture(listenerSlot))
        } answers {
            toolWindowListener = listenerSlot.captured
        }
        every { project.getService(ToolWindowManager::class.java) } returns manager
        every { project.getService(FileEditorManager::class.java) } returns editorManager
        every { editorManager.selectedEditor } returns null
        every { manager.toolWindowIdSet } returns linkedSetOf(PROJECT_TOOL_WINDOW_ID)
        every { manager.activeToolWindowId } returns PROJECT_TOOL_WINDOW_ID
        every { manager.getToolWindow(PROJECT_TOOL_WINDOW_ID) } returns toolWindow
        every { toolWindow.id } returns PROJECT_TOOL_WINDOW_ID
        every { toolWindow.isVisible } returns true
        every { toolWindow.component } returns hostFixture.content

        toolWindowManager = manager
        return GlowOverlayManager(project).also(managedManagers::add)
    }

    fun publishSetLayout() {
        val listener = requireNotNull(toolWindowListener)
        val manager = requireNotNull(toolWindowManager)
        listener.stateChanged(manager, ToolWindowManagerListener.ToolWindowManagerEventType.SetLayout)
    }

    fun addTerminal(hostFixture: ToolWindowHostFixture) {
        val manager = requireNotNull(toolWindowManager)
        val terminal = mockk<ToolWindow>(relaxed = true)
        every { manager.toolWindowIdSet } returns linkedSetOf("Project", "Terminal")
        every { manager.getToolWindow("Terminal") } returns terminal
        every { terminal.id } returns "Terminal"
        every { terminal.isVisible } returns true
        every { terminal.component } returns hostFixture.content
    }

    fun rejectToolWindowReads() {
        val manager = requireNotNull(toolWindowManager)
        every { manager.toolWindowIdSet } throws IllegalStateException("Project tool windows are disposed")
    }

    fun stateXml(): String = JDOMUtil.writeElement(XmlSerializer.serialize(currentState))

    fun reloadState(xml: String) {
        currentState =
            XmlSerializer.deserialize(
                JDOMUtil.load(xml),
                AyuIslandsState::class.java,
            )
    }

    fun drainUiCallbacks() {
        check(EventQueue.isDispatchThread())
        repeat(MAX_UI_CALLBACKS) {
            val callback = uiCallbacks.pollFirst() ?: return
            callback.run()
        }
        check(uiCallbacks.isEmpty()) { "Glow lifecycle scheduled more than $MAX_UI_CALLBACKS UI callbacks" }
    }

    fun <T> onEdt(action: () -> T): T {
        if (EventQueue.isDispatchThread()) return action()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }

    override fun close() {
        var lifecycleFailure: Throwable? = null
        try {
            onEdt {
                managedManagers.forEach(GlowOverlayManager::dispose)
                drainUiCallbacks()
            }
        } catch (failure: Throwable) {
            lifecycleFailure = failure
        } finally {
            releaseMockBoundaries(lifecycleFailure)
        }
    }

    companion object {
        private const val MAX_UI_CALLBACKS = 100
        private const val PROJECT_TOOL_WINDOW_ID = "Project"

        fun create(): PublicLifecycleEnvironment =
            try {
                val preparedSwing = prepareSwingFixtures()
                PublicLifecycleEnvironment(preparedSwing.first, preparedSwing.second)
            } catch (failure: Throwable) {
                releaseMockBoundaries(failure)
                throw failure
            }

        private fun prepareSwingFixtures(): Pair<ToolWindowHostFixture, JRootPane> {
            if (EventQueue.isDispatchThread()) {
                return ToolWindowHostFixture(configuredRoot()) to configuredRoot()
            }
            var prepared: Pair<ToolWindowHostFixture, JRootPane>? = null
            EventQueue.invokeAndWait {
                prepared = ToolWindowHostFixture(configuredRoot()) to configuredRoot()
            }
            return requireNotNull(prepared)
        }

        private fun releaseMockBoundaries(initialFailure: Throwable? = null) {
            val cleanupActions =
                listOf(
                    { unmockkStatic(KeyboardFocusManager::class) },
                    { unmockkStatic(ProjectManager::class) },
                    { unmockkStatic(PowerSaveMode::class) },
                    { unmockkObject(AccentResolver) },
                    { unmockkObject(AyuVariant.Companion) },
                    { unmockkObject(LicenseChecker) },
                    { unmockkObject(AyuIslandsSettings.Companion) },
                    { unmockkStatic(ApplicationManager::class) },
                    { unmockkConstructor(Timer::class) },
                    { unmockkStatic(SwingUtilities::class) },
                )
            var firstFailure = initialFailure
            cleanupActions.forEach { cleanup ->
                try {
                    cleanup()
                } catch (failure: Throwable) {
                    val retainedFailure = firstFailure
                    if (retainedFailure == null) {
                        firstFailure = failure
                    } else if (retainedFailure !== failure) {
                        retainedFailure.addSuppressed(failure)
                    }
                }
            }
            firstFailure?.let { throw it }
        }

        private fun configuredState(): AyuIslandsState =
            AyuIslandsState().apply {
                glowEnabled = true
                glowEditor = false
                glowFocusRing = false
                glowProject = true
                glowTerminal = false
                glowRun = true
                glowGit = false
                glowServices = true
                glowStyle = GlowStyle.SOFT.name
                softIntensity = 67
                softWidth = 9
                sharpNeonIntensity = 81
                sharpNeonWidth = 15
                glowEditorPlacement = "RETAINED_UNKNOWN_PLACEMENT"
                glowToolWindowPlacement = GlowPlacement.ISLAND.name
                fontPresetCustomizations["AMBIENT"] = "14|1.2|true|REGULAR"
                fontPresetCustomizations["FUTURE_PRESET"] = "future|unavailable|value"
                installedFontFiles["Maple Mono"] =
                    "/Users/roman/Library/Fonts/MapleMono-Regular.ttf\n" +
                    "/Users/roman/Library/Fonts/MapleMono-Italic.ttf"
            }
    }
}

private class ToolWindowHostFixture(
    val root: JRootPane,
) {
    val host = IslandHolderFixture()
    val content = DisplayableContent()

    init {
        host.addComponentListener(object : ComponentAdapter() {})
        host.addHierarchyBoundsListener(object : HierarchyBoundsAdapter() {})
        host.add(JPanel())
        host.add(content)
        host.add(JPanel())
        root.contentPane.add(host)
        layoutHostInRoot(root, host)
    }

    fun overlays(): List<GlowGlassPane> = root.overlays()

    fun singleOverlay(): GlowGlassPane = root.singleOverlay()
}

private class IslandHolderFixture : JPanel(null) {
    override fun isDisplayable(): Boolean = true

    override fun isShowing(): Boolean = true
}

private class DisplayableContent : JPanel(null) {
    override fun isDisplayable(): Boolean = true
}

private class EditorsSplittersFixture : JPanel(null) {
    override fun isDisplayable(): Boolean = true
}

private fun configuredRoot(): JRootPane =
    JRootPane().apply {
        setBounds(0, 0, 640, 480)
        contentPane.layout = null
        layeredPane.setBounds(0, 0, 640, 480)
        contentPane.setBounds(0, 0, 640, 480)
    }

private fun layoutHostInRoot(
    root: JRootPane,
    host: IslandHolderFixture,
) {
    root.contentPane.setBounds(0, 0, 640, 480)
    host.setBounds(20, 30, 300, 200)
    host.components.forEachIndexed { index, component ->
        component.setBounds(10, 10 + index * 50, 280, 40)
    }
}

private fun JRootPane.overlays(): List<GlowGlassPane> = layeredPane.components.filterIsInstance<GlowGlassPane>()

private fun JRootPane.singleOverlay(): GlowGlassPane = overlays().single()
