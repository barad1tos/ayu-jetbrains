package dev.ayuislands.glow

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
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
import java.awt.Color
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.HierarchyBoundsAdapter
import java.util.ArrayDeque
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        every { settings.state } answers { currentState }

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
    }

    fun hostFixture(): ToolWindowHostFixture = fixture

    fun secondaryRoot(): JRootPane = spareRoot

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
