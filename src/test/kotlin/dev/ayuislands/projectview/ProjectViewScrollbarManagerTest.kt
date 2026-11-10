package dev.ayuislands.projectview

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.AbstractProjectViewPane
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.AccentApplyPlanRunner
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.ui.ComponentTreeRefreshedListener
import dev.ayuislands.ui.ComponentTreeRefreshedTopic
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.TreeCellRenderer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectViewScrollbarManagerTest {
    private lateinit var project: Project
    private lateinit var toolWindowManager: ToolWindowManager
    private lateinit var settingsMock: AyuIslandsSettings
    private lateinit var realState: AyuIslandsState
    private lateinit var connection: MessageBusConnection
    private lateinit var registryValue: RegistryValue
    private lateinit var rootPathLease: RootPathLease

    @BeforeTest
    fun setUp() {
        mockkStatic(ToolWindowManager::class)
        mockkStatic(Registry::class)
        mockkObject(Registry.Companion)
        val registry = mockk<Registry>()
        every { Registry.getInstance() } returns registry
        every { registry.getBundleValueOrNull(any()) } returns "true"
        mockkStatic(ProjectView::class)
        mockkObject(AyuIslandsSettings.Companion)
        mockkObject(LicenseChecker)

        realState = AyuIslandsState()
        settingsMock =
            mockk<AyuIslandsSettings> {
                every { state } answers {
                    dev.ayuislands.studio.StudioFixtures
                        .legacyView(realState)
                        .settings
                }
            }
        every {
            AyuIslandsSettings.getInstance()
        } returns settingsMock

        registryValue = mockk(relaxed = true)
        rootPathLease = RootPathLease()
        every {
            Registry.get(any<String>())
        } returns registryValue

        val projectView =
            mockk<ProjectView>(relaxed = true) {
                every {
                    currentProjectViewPane
                } returns
                    mockk<AbstractProjectViewPane>(
                        relaxed = true,
                    )
            }
        every {
            ProjectView.getInstance(any())
        } returns projectView

        connection = mockk(relaxed = true)
        val messageBus =
            mockk<MessageBus> {
                every {
                    connect(any<Disposable>())
                } returns connection
            }

        project =
            mockk(relaxed = true) {
                every { isDisposed } returns false
                every { this@mockk.messageBus } returns messageBus
                every { name } returns "TestProject"
                every { basePath } returns "/tmp/test"
            }

        toolWindowManager = mockk(relaxed = true)
        every {
            ToolWindowManager.getInstance(project)
        } returns toolWindowManager

        // Default: no tool window available
        every {
            toolWindowManager.getToolWindow("Project")
        } returns null

        // Default: licensed
        every {
            LicenseChecker.isLicensedOrGrace()
        } returns true
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Creates manager and drains init{}'s invokeLater.
     * Must NOT be called from EDT (invokeAndWait blocks).
     */
    private fun createAndDrain(): ProjectViewScrollbarManager {
        val manager =
            ProjectViewScrollbarManager(project, rootPathLease)
        // Drain the invokeLater posted by init{}
        SwingUtilities.invokeAndWait {}
        return manager
    }

    @Test
    fun `unlicensed apply restores runtime scrollbar and gain reapplies settings`() {
        realState.hideProjectViewHScrollbar = true
        val tree = JTree()
        val scrollPane = JScrollPane(tree)
        val originalPolicy = scrollPane.horizontalScrollBarPolicy
        val wrapper = JPanel(FlowLayout()).apply { add(scrollPane) }
        setupToolWindowContent(wrapper)

        val manager = createAndDrain()
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scrollPane.horizontalScrollBarPolicy)

        every {
            LicenseChecker.isLicensedOrGrace()
        } returns false
        SwingUtilities.invokeAndWait {
            manager.apply()
        }
        assertEquals(originalPolicy, scrollPane.horizontalScrollBarPolicy)

        every { LicenseChecker.isLicensedOrGrace() } returns true
        SwingUtilities.invokeAndWait { manager.apply() }
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scrollPane.horizontalScrollBarPolicy)
    }

    @Test
    fun `unlicensed manager leaves shared Registry ownership with the Studio transaction`() {
        realState.hideProjectRootPath = true
        realState.hasRootPathLease = true
        realState.wasRootPathShown = true
        realState.wasRootPathChanged = true
        rootPathLease = mockk(relaxed = true)
        every { registryValue.asBoolean() } returns false
        every { LicenseChecker.isLicensedOrGrace() } returns false

        val manager =
            ProjectViewScrollbarManager(
                project = project,
                rootPathLease = rootPathLease,
            )
        SwingUtilities.invokeAndWait {}
        SwingUtilities.invokeAndWait { manager.apply() }

        verify(exactly = 0) { registryValue.resetToDefault() }
        assertTrue(realState.hasRootPathLease)
    }

    @Test
    fun `initial unlicensed apply cannot change shared Registry ownership during construction`() {
        realState.hideProjectRootPath = true
        realState.hasRootPathLease = true
        realState.wasRootPathShown = true
        realState.wasRootPathChanged = true
        rootPathLease = mockk(relaxed = true)
        every { registryValue.asBoolean() } returns false
        every { LicenseChecker.isLicensedOrGrace() } returns false
        var connections = 0
        every { project.messageBus.connect(any<Disposable>()) } answers {
            // The second subscription follows the queued initial apply: let the EDT run it mid-construction.
            if (++connections == 2) SwingUtilities.invokeAndWait {}
            connection
        }

        ProjectViewScrollbarManager(
            project = project,
            rootPathLease = rootPathLease,
        )
        SwingUtilities.invokeAndWait {}

        verify(exactly = 0) { registryValue.resetToDefault() }
        assertTrue(realState.hasRootPathLease)
    }

    @Test
    fun `stale root renderer preserves native output when unlicensed`() {
        SwingUtilities.invokeAndWait {
            val native =
                SimpleColoredComponent().apply {
                    append("TestProject", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  /tmp/test", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            val delegate = TreeCellRenderer { _, _, _, _, _, _, _ -> native }
            val renderer = RootFilteringRenderer(delegate, project) { false }

            val rendered =
                renderer.getTreeCellRendererComponent(
                    JTree(),
                    "root",
                    selected = false,
                    expanded = true,
                    leaf = false,
                    row = 0,
                    hasFocus = false,
                ) as SimpleColoredComponent

            assertEquals(native, rendered)
            assertEquals(2, rendered.fragmentCount)
        }
    }

    @Test
    fun `installed root renderer does not recheck license while painting rows`() {
        realState.hideProjectRootPath = true
        lateinit var tree: JTree
        lateinit var wrapper: JPanel
        SwingUtilities.invokeAndWait {
            tree = JTree()
            wrapper = JPanel(FlowLayout()).apply { add(JScrollPane(tree)) }
        }
        setupToolWindowContent(wrapper)
        val manager = createAndDrain()
        try {
            lateinit var renderer: RootFilteringRenderer
            SwingUtilities.invokeAndWait {
                renderer = assertIs(tree.cellRenderer)
            }
            clearMocks(LicenseChecker, answers = false, recordedCalls = true)

            SwingUtilities.invokeAndWait {
                paintRootRow(tree, renderer)
            }

            verify(exactly = 0) { LicenseChecker.isLicensedOrGrace() }
        } finally {
            SwingUtilities.invokeAndWait { manager.dispose() }
        }
    }

    @Test
    fun `renderer guard keeps replacement paint free of license checks`() {
        realState.hideProjectRootPath = true
        lateinit var tree: JTree
        lateinit var wrapper: JPanel
        lateinit var replacement: DefaultTreeCellRenderer
        SwingUtilities.invokeAndWait {
            tree = JTree()
            wrapper = JPanel(FlowLayout()).apply { add(JScrollPane(tree)) }
            replacement = DefaultTreeCellRenderer()
        }
        setupToolWindowContent(wrapper)
        val manager = createAndDrain()
        try {
            lateinit var renderer: RootFilteringRenderer
            SwingUtilities.invokeAndWait {
                tree.cellRenderer = replacement
                renderer = assertIs(tree.cellRenderer)
                assertSame(replacement, renderer.delegate)
            }
            clearMocks(LicenseChecker, answers = false, recordedCalls = true)

            SwingUtilities.invokeAndWait {
                paintRootRow(tree, renderer)
            }

            verify(exactly = 0) { LicenseChecker.isLicensedOrGrace() }
        } finally {
            SwingUtilities.invokeAndWait { manager.dispose() }
        }
    }

    private fun paintRootRow(
        tree: JTree,
        renderer: TreeCellRenderer,
    ) {
        val isSelected = false
        val isExpanded = false
        val isLeaf = false
        val hasFocus = false
        renderer.getTreeCellRendererComponent(
            tree,
            tree.model.root,
            isSelected,
            isExpanded,
            isLeaf,
            0,
            hasFocus,
        )
    }

    @Test
    fun `applyScrollbar hides horizontal scrollbar`() {
        realState.hideProjectViewHScrollbar = true
        realState.hideProjectRootPath = false

        val tree = JTree()
        val scrollPane = JScrollPane(tree)
        val wrapper = JPanel(FlowLayout())
        wrapper.add(scrollPane)

        setupToolWindowContent(wrapper)

        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            manager.apply()
        }

        assertEquals(
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            scrollPane.horizontalScrollBarPolicy,
        )
    }

    @Test
    fun `applyScrollbar restores original policy`() {
        val tree = JTree()
        val scrollPane = JScrollPane(tree)
        val originalPolicy =
            scrollPane.horizontalScrollBarPolicy
        val wrapper = JPanel(FlowLayout())
        wrapper.add(scrollPane)

        setupToolWindowContent(wrapper)

        // First hide the scrollbar
        realState.hideProjectViewHScrollbar = true
        realState.hideProjectRootPath = false
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            manager.apply()
        }

        // Then restore it
        realState.hideProjectViewHScrollbar = false
        SwingUtilities.invokeAndWait {
            manager.apply()
        }

        assertEquals(
            originalPolicy,
            scrollPane.horizontalScrollBarPolicy,
        )
    }

    @Test
    fun `dispose restores scrollbar policy`() {
        // Scrollbar-only test: disable root path
        // hiding to avoid Registry interaction
        // (Registry mocking is unreliable in this
        // test environment — see comment below)
        realState.hideProjectViewHScrollbar = false
        realState.hideProjectRootPath = false

        val tree = JTree()
        val scrollPane = JScrollPane(tree)
        val originalPolicy =
            scrollPane.horizontalScrollBarPolicy
        val wrapper = JPanel(FlowLayout())
        wrapper.add(scrollPane)

        setupToolWindowContent(wrapper)

        val manager = createAndDrain()

        // Enable scrollbar hiding and apply
        realState.hideProjectViewHScrollbar = true
        SwingUtilities.invokeAndWait {
            manager.apply()
        }

        assertEquals(
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            scrollPane.horizontalScrollBarPolicy,
        )

        // Dispose should restore original policy
        SwingUtilities.invokeAndWait {
            manager.dispose()
        }

        assertEquals(
            originalPolicy,
            scrollPane.horizontalScrollBarPolicy,
        )
    }

    // Registry.resetToDefault verification skipped:
    // mockkStatic(Registry::class) interacts poorly
    // with JetBrains test framework — Registry.get()
    // returns the real RegistryValue instead of the
    // mock in some test orderings. The Registry
    // interaction is verified implicitly through
    // applyRootDisplay handles missing Registry key
    // test (which confirms the try/catch path works).

    @Test
    fun `init subscribes to ToolWindowManagerListener`() {
        createAndDrain()

        verify {
            connection.subscribe(
                ToolWindowManagerListener.TOPIC,
                any(),
            )
        }
    }

    @Test
    fun `stateChanged ignores events from another visible tool window`() {
        realState.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
        realState.hideProjectViewHScrollbar = true
        every { toolWindowManager.activeToolWindowId } returns "AWS"

        val listenerSlot = slot<ToolWindowManagerListener>()
        every {
            connection.subscribe(
                ToolWindowManagerListener.TOPIC,
                capture(listenerSlot),
            )
        } returns Unit

        val manager = ProjectViewScrollbarManager(project, rootPathLease)
        try {
            SwingUtilities.invokeAndWait {}
            clearMocks(toolWindowManager, answers = false)
            every { toolWindowManager.activeToolWindowId } returns "AWS"

            listenerSlot.captured.stateChanged(
                toolWindowManager,
                ToolWindowManagerListener.ToolWindowManagerEventType.ActivateToolWindow,
            )

            verify(exactly = 0) {
                toolWindowManager.getToolWindow("Project")
            }
        } finally {
            SwingUtilities.invokeAndWait {
                manager.dispose()
            }
        }
    }

    @Test
    fun `stateChanged reapplies project tweaks for global layout changes`() {
        realState.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
        realState.hideProjectViewHScrollbar = true

        val tree = JTree()
        val scrollPane = JScrollPane(tree)
        val wrapper = JPanel(FlowLayout())
        wrapper.add(scrollPane)
        setupToolWindowContent(wrapper)
        every { toolWindowManager.activeToolWindowId } returns "Project"

        val listenerSlot = slot<ToolWindowManagerListener>()
        every {
            connection.subscribe(
                ToolWindowManagerListener.TOPIC,
                capture(listenerSlot),
            )
        } returns Unit

        val manager = ProjectViewScrollbarManager(project, rootPathLease)
        try {
            SwingUtilities.invokeAndWait {}
            scrollPane.horizontalScrollBarPolicy =
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED

            listenerSlot.captured.stateChanged(
                toolWindowManager,
                ToolWindowManagerListener.ToolWindowManagerEventType.SetLayout,
            )

            assertEquals(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                scrollPane.horizontalScrollBarPolicy,
            )
        } finally {
            SwingUtilities.invokeAndWait {
                manager.dispose()
            }
        }
    }

    @Test
    fun `apply is idempotent when called multiple times with same state`() {
        realState.hideProjectViewHScrollbar = true
        realState.hideProjectRootPath = false

        val tree = JTree()
        val scrollPane = JScrollPane(tree)
        val originalPolicy = scrollPane.horizontalScrollBarPolicy
        val wrapper = JPanel(FlowLayout())
        wrapper.add(scrollPane)

        setupToolWindowContent(wrapper)

        val manager = createAndDrain()

        // Three consecutive applies with unchanged state
        SwingUtilities.invokeAndWait {
            manager.apply()
            manager.apply()
            manager.apply()
        }

        // Final state: horizontal scrollbar hidden
        assertEquals(
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            scrollPane.horizontalScrollBarPolicy,
        )

        // Flipping the setting off must restore the ORIGINAL policy,
        // not a stale value captured from a previous hidden-state apply.
        // This proves originalScrollbarPolicy was captured exactly once.
        realState.hideProjectViewHScrollbar = false
        SwingUtilities.invokeAndWait {
            manager.apply()
        }
        assertEquals(originalPolicy, scrollPane.horizontalScrollBarPolicy)
    }

    @Test
    fun `candidate capture preserves default and explicit scrollbar baselines through release and abort`() {
        for (policy in listOf(
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS,
        )) {
            withScrollbar(policy) { manager, pane, _, _ ->
                val writes = pane.writes
                val hidden = assertNotNull(manager.prepareScrollbar(hideH = true))
                assertEquals(policy, hidden.before.policy)
                assertNull(hidden.before.originalPolicy)
                assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, hidden.planned.policy)
                assertEquals(policy, hidden.planned.originalPolicy)
                assertEquals(writes, pane.writes)
                assertFalse(realState.hideProjectViewHScrollbar)
                try {
                    assertTrue(hidden.apply())
                    assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, pane.horizontalScrollBarPolicy)
                    val acceptanceWrites = pane.writes
                    hidden.commit()
                    hidden.releaseResources()
                    hidden.commit()
                    hidden.releaseResources()
                    assertEquals(acceptanceWrites, pane.writes)
                } finally {
                    if (hidden.hasPending) hidden.restore()
                }
                val release = assertNotNull(manager.prepareScrollbar(hideH = false))
                assertEquals(policy, release.before.originalPolicy)
                assertEquals(policy, release.planned.policy)
                assertNull(release.planned.originalPolicy)
                try {
                    assertTrue(release.apply())
                    assertEquals(policy, pane.horizontalScrollBarPolicy)
                } finally {
                    release.restore()
                }
                assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, pane.horizontalScrollBarPolicy)
                assertFalse(realState.hideProjectViewHScrollbar)
                val retry = assertNotNull(manager.prepareScrollbar(hideH = false))
                assertEquals(policy, retry.before.originalPolicy)
                try {
                    assertTrue(retry.apply())
                    retry.commit()
                    retry.releaseResources()
                } finally {
                    if (retry.hasPending) retry.restore()
                }
                assertEquals(policy, pane.horizontalScrollBarPolicy)
            }
        }
    }

    @Test
    fun `native refresh callbacks wait for retained policy rollback and acceptance`() {
        withScrollbar(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED) { manager, pane, treeRefresh, toolRefresh ->
            realState.hideProjectViewHScrollbar = true
            manager.apply()
            val release = assertNotNull(manager.prepareScrollbar(hideH = false))
            try {
                assertTrue(release.apply())
                val writes = pane.writes
                treeRefresh.afterRefresh(project)
                toolRefresh.stateChanged(
                    toolWindowManager,
                    ToolWindowManagerListener.ToolWindowManagerEventType.SetLayout,
                )
                assertEquals(writes, pane.writes)
                assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, pane.horizontalScrollBarPolicy)
                assertTrue(realState.hideProjectViewHScrollbar)
            } finally {
                release.restore()
            }
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, pane.horizontalScrollBarPolicy)
            realState.hideProjectViewHScrollbar = false
            treeRefresh.afterRefresh(project)
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, pane.horizontalScrollBarPolicy)

            val hidden = assertNotNull(manager.prepareScrollbar(hideH = true))
            try {
                assertTrue(hidden.apply())
                realState.hideProjectViewHScrollbar = true
                hidden.commit()
                hidden.releaseResources()
            } finally {
                if (hidden.hasPending) hidden.restore()
            }
            realState.hideProjectViewHScrollbar = false
            treeRefresh.afterRefresh(project)
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, pane.horizontalScrollBarPolicy)
        }
    }

    @Test
    fun `mutating policy setter failures retain the same baseline and hold callbacks through restore retry`() {
        withScrollbar(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS) { manager, pane, treeRefresh, _ ->
            val checkpoint = assertNotNull(manager.prepareScrollbar(hideH = true))
            try {
                pane.failOn = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                assertFailsWith<IllegalStateException> { checkpoint.apply() }
                assertEquals(AccentWriteState.OWNED, checkpoint.state)
                assertTrue(checkpoint.hasPending)
                val appliedWrites = pane.writes
                treeRefresh.afterRefresh(project)
                assertEquals(appliedWrites, pane.writes)
                assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, pane.horizontalScrollBarPolicy)

                pane.failOn = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
                assertFailsWith<IllegalStateException> { checkpoint.restore() }
                assertTrue(checkpoint.hasPending)
                val failedRestoreWrites = pane.writes
                treeRefresh.afterRefresh(project)
                assertEquals(failedRestoreWrites, pane.writes)
                checkpoint.restore()
                assertFalse(checkpoint.hasPending)
                assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, pane.horizontalScrollBarPolicy)
                assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, checkpoint.before.policy)
                assertNull(checkpoint.before.originalPolicy)
            } finally {
                pane.failOn = null
                checkpoint.restore()
            }
        }
    }

    @Test
    fun `foreign policy target LAF and disposal fences preserve changed peers before and after application`() {
        for (isApplied in listOf(false, true)) {
            for (drift in PolicyDrift.entries) {
                withScrollbar(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED) { manager, pane, _, _ ->
                    val checkpoint = assertNotNull(manager.prepareScrollbar(hideH = true))
                    var replacement: PolicyPane? = null
                    try {
                        if (isApplied) assertTrue(checkpoint.apply())
                        when (drift) {
                            PolicyDrift.FOREIGN -> {
                                pane.horizontalScrollBarPolicy =
                                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
                            }

                            PolicyDrift.TARGET -> {
                                replacement =
                                    PolicyPane().apply {
                                        horizontalScrollBarPolicy =
                                            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
                                    }
                                setupToolWindowContent(JPanel().apply { add(replacement) })
                            }

                            PolicyDrift.LAF -> {
                                UIManager.setLookAndFeel(MetalLookAndFeel())
                            }

                            PolicyDrift.PROJECT_DISPOSED -> {
                                every { project.isDisposed } returns true
                            }

                            PolicyDrift.MANAGER_DISPOSED -> {
                                manager.dispose()
                            }
                        }
                        val policy = pane.horizontalScrollBarPolicy
                        val writes = pane.writes
                        if (!isApplied) assertFalse(checkpoint.apply())
                        checkpoint.restore()
                        assertEquals(AccentWriteState.RELINQUISHED, checkpoint.state)
                        checkpoint.commit()
                        checkpoint.releaseResources()
                        assertEquals(writes, pane.writes)
                        assertEquals(policy, pane.horizontalScrollBarPolicy)
                        manager.dispose()
                        assertEquals(writes, pane.writes)
                        assertEquals(policy, pane.horizontalScrollBarPolicy)
                        replacement?.let {
                            assertEquals(
                                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS,
                                it.horizontalScrollBarPolicy,
                            )
                        }
                    } finally {
                        checkpoint.restore()
                    }
                }
            }
        }
    }

    @Test
    fun `root display callback waits for shared Registry owner and never writes that surface`() {
        withRootRenderer(false) { manager, tree, posted ->
            val original = tree.cellRenderer
            val detached = AyuIslandsState()
            val checkpoint = rootPathLease.prepare(setOf(project), registryValue, true, detached)
            assertTrue(checkpoint.apply())
            realState.hideProjectRootPath = true
            clearMocks(registryValue, answers = false, recordedCalls = true)
            manager.apply()
            assertSame(original, tree.cellRenderer)
            assertTrue(posted.isEmpty())
            checkpoint.commit()
            assertEquals(1, posted.size)
            posted.removeAt(0).run()
            assertTrue(tree.cellRenderer is RootFilteringRenderer)
            verify(exactly = 0) { registryValue.setValue(any<String>()) }
            verify(exactly = 0) { registryValue.resetToDefault() }
        }
    }

    private fun withScrollbar(
        policy: Int,
        action: (
            ProjectViewScrollbarManager,
            PolicyPane,
            ComponentTreeRefreshedListener,
            ToolWindowManagerListener,
        ) -> Unit,
    ) {
        realState.hideProjectViewHScrollbar = false
        realState.hideProjectRootPath = false
        realState.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
        val treeRefresh = slot<ComponentTreeRefreshedListener>()
        val toolRefresh = slot<ToolWindowManagerListener>()
        every { connection.subscribe(ComponentTreeRefreshedTopic.TOPIC, capture(treeRefresh)) } returns Unit
        every { connection.subscribe(ToolWindowManagerListener.TOPIC, capture(toolRefresh)) } returns Unit
        lateinit var pane: PolicyPane
        SwingUtilities.invokeAndWait {
            pane = PolicyPane().apply { horizontalScrollBarPolicy = policy }
            setupToolWindowContent(JPanel().apply { add(pane) })
        }
        val manager = createAndDrain()
        val previousLaf = UIManager.getLookAndFeel()
        try {
            SwingUtilities.invokeAndWait { action(manager, pane, treeRefresh.captured, toolRefresh.captured) }
        } finally {
            every { project.isDisposed } returns false
            SwingUtilities.invokeAndWait {
                UIManager.setLookAndFeel(previousLaf)
                manager.dispose()
            }
        }
    }

    @Test
    fun `root capture is readonly and candidate filtering does not write settings or registry`() {
        withRootRenderer(false) { manager, tree, _ ->
            val native = tree.cellRenderer
            val listeners = tree.getPropertyChangeListeners("cellRenderer").toList()
            val checkpoint = manager.prepareRootRenderer(true)
            val captured = checkpoint.records.single()
            assertSame(tree, captured.tree)
            assertSame(native, captured.before)
            assertSame(native, captured.lastWritten)
            assertSame(native, tree.cellRenderer)
            assertEquals(listeners, tree.getPropertyChangeListeners("cellRenderer").toList())
            clearMocks(registryValue, answers = false, recordedCalls = true)

            assertTrue(checkpoint.apply())

            assertSame(captured.planned, tree.cellRenderer)
            assertEquals(1, renderRoot(tree).fragmentCount, "Candidate hide must paint before settings acceptance")
            assertFalse(realState.hideProjectRootPath)
            verify(exactly = 0) { registryValue.setValue(any<Boolean>()) }
            verify(exactly = 0) { registryValue.resetToDefault() }
            checkpoint.restore(emptyList())
            assertSame(native, tree.cellRenderer)
            assertEquals(listeners, tree.getPropertyChangeListeners("cellRenderer").toList())
            assertFalse(checkpoint.hasPending)
        }
    }

    @Test
    fun `native capture and ownership guards never create tool window content`() {
        withRootRenderer(false) { manager, _, _ ->
            val window = checkNotNull(toolWindowManager.getToolWindow("Project"))
            val content = checkNotNull(window.contentManagerIfCreated)
            every { window.contentManager } throws IllegalStateException("Creating content getter must not run")
            try {
                val checkpoint = manager.prepareRootRenderer(true)
                assertEquals(1, checkpoint.records.size)
                assertTrue(checkpoint.apply())
                checkpoint.restore(emptyList())
                assertFalse(checkpoint.hasPending)
                val scrollbar = checkNotNull(manager.prepareScrollbar(true))
                assertTrue(scrollbar.apply())
                scrollbar.restore(emptyList())
                assertFalse(scrollbar.hasPending)
            } finally {
                every { window.contentManager } returns content
            }
        }
    }

    @Test
    fun `tentative root disable retains guard identity and defers accepted apply until rollback`() {
        withRootRenderer(true) { manager, tree, posted ->
            val filtered = tree.cellRenderer
            val listeners = tree.getPropertyChangeListeners("cellRenderer").toList()
            val checkpoint = manager.prepareRootRenderer(false)
            assertTrue(checkpoint.apply())
            assertSame(assertIs<RootFilteringRenderer>(filtered).delegate, tree.cellRenderer)
            assertEquals(listeners, tree.getPropertyChangeListeners("cellRenderer").toList())
            assertEquals(2, renderRoot(tree).fragmentCount)

            manager.apply()

            assertTrue(posted.isEmpty())
            assertSame(checkpoint.records.single().planned, tree.cellRenderer)
            checkpoint.restore(emptyList())
            assertSame(filtered, tree.cellRenderer)
            assertEquals(listeners, tree.getPropertyChangeListeners("cellRenderer").toList())
            assertEquals(1, posted.size)
            posted.removeAt(0).run()
            assertSame(filtered, tree.cellRenderer)
        }
    }

    @Test
    fun `mutating renderer setter failures retain exact progress and retry the original guard`() {
        withRootRenderer(true) { manager, tree, posted ->
            val filtered = tree.cellRenderer
            val listeners = tree.getPropertyChangeListeners("cellRenderer").toList()
            val checkpoint = manager.prepareRootRenderer(false)
            tree.failNextWrite = true
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertTrue(checkpoint.hasPending)
            assertSame(checkpoint.records.single().planned, checkpoint.records.single().lastWritten)
            manager.apply()
            tree.failNextWrite = true
            assertFailsWith<IllegalStateException> { checkpoint.restore(emptyList()) }
            assertTrue(checkpoint.hasPending)
            assertTrue(posted.isEmpty())
            assertSame(filtered, tree.cellRenderer)
            assertSame(filtered, checkpoint.records.single().lastWritten)

            checkpoint.restore(emptyList())

            assertFalse(checkpoint.hasPending)
            assertSame(filtered, tree.cellRenderer)
            assertEquals(listeners, tree.getPropertyChangeListeners("cellRenderer").toList())
            assertEquals(1, posted.size)
        }
    }

    @Test
    fun `foreign renderer survives root rollback without guard repair during the hold`() {
        withRootRenderer(true) { manager, tree, _ ->
            val checkpoint = manager.prepareRootRenderer(false)
            assertTrue(checkpoint.apply())
            val foreign = DefaultTreeCellRenderer()
            tree.cellRenderer = foreign
            assertSame(foreign, tree.cellRenderer)

            checkpoint.restore(emptyList())

            assertSame(foreign, tree.cellRenderer)
            assertEquals(AccentWriteState.RELINQUISHED, checkpoint.state)
            assertFalse(checkpoint.hasPending)
        }
    }

    @Test
    fun `root acceptance settles caches only after accepted settings and registry batch`() {
        withRootRenderer(false) { manager, tree, _ ->
            val checkpoint = manager.prepareRootRenderer(true)
            assertTrue(checkpoint.apply())
            val filtered = tree.cellRenderer
            realState.hideProjectRootPath = true
            val detached = AyuIslandsState()
            val registry = rootPathLease.prepare(setOf(project), registryValue, true, detached)
            assertTrue(registry.apply())
            registry.commit()
            clearMocks(registryValue, answers = false, recordedCalls = true)

            checkpoint.commit()
            manager.apply()

            assertSame(filtered, tree.cellRenderer)
            assertFalse(checkpoint.hasPending)
            verify(exactly = 0) { registryValue.setValue(any<Boolean>()) }
            realState.hideProjectRootPath = false
            manager.apply()
            verify(exactly = 0) { registryValue.resetToDefault() }
            val release = rootPathLease.prepare(setOf(project), registryValue, false, detached)
            assertTrue(release.apply())
            release.commit()
            verify(exactly = 1) { registryValue.resetToDefault() }
        }
    }

    @Test
    fun `empty root capture holds lifecycle without advancing absent target eligibility`() {
        realState.hideProjectRootPath = false
        realState.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
        val manager = createAndDrain()
        SwingUtilities.invokeAndWait {
            try {
                val checkpoint = manager.prepareRootRenderer(true)
                assertTrue(checkpoint.records.isEmpty())
                assertTrue(checkpoint.apply())
                checkpoint.commit()
                val tree = RendererTree()
                setupToolWindowContent(JPanel().apply { add(JScrollPane(tree)) })
                realState.hideProjectRootPath = true

                manager.apply()

                assertIs<RootFilteringRenderer>(tree.cellRenderer)
            } finally {
                manager.dispose()
            }
        }
    }

    private fun withRootRenderer(
        hidePath: Boolean,
        action: (ProjectViewScrollbarManager, RendererTree, MutableList<Runnable>) -> Unit,
    ) {
        realState.hideProjectRootPath = hidePath
        realState.hideProjectViewHScrollbar = false
        realState.projectPanelWidthMode = PanelWidthMode.DEFAULT.name
        var raw = "true"
        every { registryValue.asBoolean() } answers { raw.toBoolean() }
        every { registryValue.asString() } answers { raw }
        every { registryValue.isChangedFromDefault() } answers { raw != "true" }
        every { registryValue.setValue(any<Boolean>()) } answers { raw = firstArg<Boolean>().toString() }
        every { registryValue.setValue(any<String>()) } answers { raw = firstArg() }
        every { registryValue.resetToDefault() } answers { raw = "true" }
        val posted = mutableListOf<Runnable>()
        mockkObject(AccentApplyPlanRunner)
        every { AccentApplyPlanRunner.post(any()) } answers { posted += firstArg<Runnable>() }
        lateinit var tree: RendererTree
        SwingUtilities.invokeAndWait {
            tree = RendererTree()
            tree.cellRenderer =
                TreeCellRenderer { _, _, _, _, _, _, _ ->
                    SimpleColoredComponent().apply {
                        append("TestProject", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append("  /tmp/test", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            setupToolWindowContent(JPanel().apply { add(JScrollPane(tree)) })
        }
        val manager = createAndDrain()
        try {
            SwingUtilities.invokeAndWait { action(manager, tree, posted) }
        } finally {
            SwingUtilities.invokeAndWait { manager.dispose() }
        }
    }

    private fun renderRoot(tree: JTree): SimpleColoredComponent =
        tree.cellRenderer.getTreeCellRendererComponent(tree, tree.model.root, false, false, false, 0, false)
            as SimpleColoredComponent

    private class RendererTree : JTree() {
        var failNextWrite = false

        override fun setCellRenderer(renderer: TreeCellRenderer?) {
            super.setCellRenderer(renderer)
            if (failNextWrite) {
                failNextWrite = false
                error("Renderer setter failed after mutation")
            }
        }
    }

    private enum class PolicyDrift { FOREIGN, TARGET, LAF, PROJECT_DISPOSED, MANAGER_DISPOSED }

    private class PolicyPane : JScrollPane(JTree()) {
        var writes: Int = 0
        var failOn: Int? = null

        override fun setHorizontalScrollBarPolicy(policy: Int) {
            super.setHorizontalScrollBarPolicy(policy)
            writes++
            if (failOn == policy) {
                failOn = null
                error("Scrollbar policy setter failed after mutation")
            }
        }
    }

    private fun setupToolWindowContent(component: JPanel) {
        val content =
            mockk<Content>(relaxed = true) {
                every {
                    this@mockk.component
                } returns component
            }
        val contentManager =
            mockk<ContentManager>(relaxed = true) {
                every {
                    contents
                } returns arrayOf(content)
            }
        val toolWindow =
            mockk<ToolWindow>(relaxed = true) {
                every { isVisible } returns true
                every {
                    this@mockk.contentManager
                } returns contentManager
                every {
                    this@mockk.contentManagerIfCreated
                } returns contentManager
            }
        every {
            toolWindowManager.getToolWindow("Project")
        } returns toolWindow
    }
}
