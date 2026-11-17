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
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.FlowLayout
import java.util.MissingResourceException
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
        mockkStatic(ProjectView::class)
        mockkObject(AyuIslandsSettings.Companion)
        mockkObject(LicenseChecker)

        realState = AyuIslandsState()
        settingsMock =
            mockk<AyuIslandsSettings> {
                every { state } returns realState
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
    fun `fresh unlicensed manager releases the persisted root path lease`() {
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
                registryValueProvider = { registryValue },
            )
        SwingUtilities.invokeAndWait {}
        SwingUtilities.invokeAndWait { manager.apply() }

        verify(atLeast = 1) { rootPathLease.release(project, registryValue) }
    }

    @Test
    fun `initial apply queued during construction uses the injected root path lease`() {
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
            registryValueProvider = { registryValue },
        )
        SwingUtilities.invokeAndWait {}

        verify(exactly = 1) { rootPathLease.release(project, registryValue) }
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
    fun `applyRootDisplay handles missing Registry key`() {
        realState.hideProjectRootPath = true
        realState.hideProjectViewHScrollbar = false

        val manager =
            ProjectViewScrollbarManager(
                project = project,
                rootPathLease = rootPathLease,
                registryValueProvider = {
                    throw MissingResourceException(
                        "Not found",
                        "Registry",
                        "project.tree.structure.show.url",
                    )
                },
            )
        SwingUtilities.invokeAndWait {}

        SwingUtilities.invokeAndWait {
            // Should not throw
            manager.apply()
        }
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
    fun `Studio capture leaves Project View unchanged until Apply and restores the exact policy`() {
        val pane = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        setupToolWindowContent(JPanel().apply { add(pane) })
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            assertNull(manager.prepareScrollbar(hideHorizontal = false))
            val native = checkNotNull(manager.prepareScrollbar(hideHorizontal = true))
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, pane.horizontalScrollBarPolicy)
            assertTrue(native.apply())
            manager.apply() // A legacy callback must not change the preview under the transaction.
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, pane.horizontalScrollBarPolicy)
            assertFalse(realState.hideProjectViewHScrollbar)

            native.restore()
            assertFalse(native.hasPending)
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, pane.horizontalScrollBarPolicy)
            assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, pane.verticalScrollBarPolicy)
            manager.dispose()
        }
    }

    @Test
    fun `Studio capture does not initialize absent Project View content`() {
        val window = setupToolWindowContent(JPanel())
        val manager = createAndDrain()
        every { window.contentManagerIfCreated } returns null
        clearMocks(window, answers = false)

        SwingUtilities.invokeAndWait {
            assertNull(manager.prepareScrollbar(hideHorizontal = true))
        }

        verify(exactly = 0) { window.contentManager }
        SwingUtilities.invokeAndWait { manager.dispose() }
    }

    @Test
    fun `rollback restores the same Project View pane after a theme change`() {
        val pane = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        setupToolWindowContent(JPanel().apply { add(pane) })
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            val originalLookAndFeel = UIManager.getLookAndFeel()
            try {
                val native = checkNotNull(manager.prepareScrollbar(hideHorizontal = true))
                assertTrue(native.apply())

                UIManager.setLookAndFeel(MetalLookAndFeel())
                manager.apply()
                assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, pane.horizontalScrollBarPolicy)

                native.restore()
                assertFalse(native.hasPending)
                assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, pane.horizontalScrollBarPolicy)
                assertEquals(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, pane.verticalScrollBarPolicy)
            } finally {
                UIManager.setLookAndFeel(originalLookAndFeel)
                manager.dispose()
            }
        }
    }

    @Test
    fun `a theme change before Apply makes the prepared Project View capture stale`() {
        val pane = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        setupToolWindowContent(JPanel().apply { add(pane) })
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            val originalLookAndFeel = UIManager.getLookAndFeel()
            try {
                val native = checkNotNull(manager.prepareScrollbar(hideHorizontal = true))
                UIManager.setLookAndFeel(MetalLookAndFeel())

                assertFalse(native.apply())
                assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, pane.horizontalScrollBarPolicy)
                assertFalse(realState.hideProjectViewHScrollbar)
            } finally {
                UIManager.setLookAndFeel(originalLookAndFeel)
                manager.dispose()
            }
        }
    }

    @Test
    fun `closing Project View restores the policy even if Apply has not released it`() {
        for (accepted in listOf(false, true)) {
            val pane = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
            setupToolWindowContent(JPanel().apply { add(pane) })
            val manager = createAndDrain()

            SwingUtilities.invokeAndWait {
                val native = checkNotNull(manager.prepareScrollbar(hideHorizontal = true))
                assertTrue(native.apply())
                if (accepted) {
                    realState.hideProjectViewHScrollbar = true
                    native.commit()
                }

                manager.dispose()

                assertFalse(native.hasPending)
                assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, pane.horizontalScrollBarPolicy)
                assertEquals(accepted, realState.hideProjectViewHScrollbar)
            }
        }
    }

    @Test
    fun `cancelling after capture leaves Project View unchanged and allows a fresh Apply`() {
        val pane = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        setupToolWindowContent(JPanel().apply { add(pane) })
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            val abandoned = checkNotNull(manager.prepareScrollbar(hideHorizontal = true))
            abandoned.restore()
            abandoned.releaseResources()
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, pane.horizontalScrollBarPolicy)

            val next = checkNotNull(manager.prepareScrollbar(hideHorizontal = true))
            assertTrue(next.apply())
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, pane.horizontalScrollBarPolicy)
            manager.dispose()
        }
    }

    @Test
    fun `an IDE policy written by a listener during Apply is left intact`() {
        val pane = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        setupToolWindowContent(JPanel().apply { add(pane) })
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            val native = checkNotNull(manager.prepareScrollbar(hideHorizontal = true))
            pane.addPropertyChangeListener("horizontalScrollBarPolicy") { event ->
                if (event.newValue == ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
                    pane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                }
            }

            assertFalse(native.apply())
            native.restore()
            manager.dispose()
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, pane.horizontalScrollBarPolicy)
        }
    }

    @Test
    fun `accepting Apply leaves a newer Project View policy intact`() {
        val pane = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        setupToolWindowContent(JPanel().apply { add(pane) })
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            val native = checkNotNull(manager.prepareScrollbar(hideHorizontal = true))
            assertTrue(native.apply())

            pane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            realState.hideProjectViewHScrollbar = true
            native.commit()
            native.releaseResources()

            assertFalse(native.hasPending)
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, pane.horizontalScrollBarPolicy)
            manager.dispose()
            assertTrue(realState.hideProjectViewHScrollbar)
        }
    }

    @Test
    fun `disabling the legacy toggle preserves policies that the IDE owns`() {
        val pane = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
        setupToolWindowContent(JPanel().apply { add(pane) })
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            realState.hideProjectViewHScrollbar = true
            manager.apply()
            realState.hideProjectViewHScrollbar = false
            manager.apply()
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, pane.horizontalScrollBarPolicy)

            pane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
            realState.hideProjectViewHScrollbar = true
            manager.apply()
            pane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            realState.hideProjectViewHScrollbar = false
            manager.apply()

            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, pane.horizontalScrollBarPolicy)
            manager.dispose()
        }
    }

    @Test
    fun `disabling the scrollbar override never borrows the old pane policy for its replacement`() {
        val first = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        val replacement = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        val wrapper = JPanel().apply { add(first) }
        setupToolWindowContent(wrapper)
        realState.hideProjectViewHScrollbar = true
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, first.horizontalScrollBarPolicy)
            wrapper.remove(first)
            wrapper.add(replacement)
            realState.hideProjectViewHScrollbar = false
            manager.apply()
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, replacement.horizontalScrollBarPolicy)
            manager.dispose()
        }
    }

    @Test
    fun `disabling the override restores both Project View panes after revisiting the first`() {
        val first = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        val second = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        val wrapper = JPanel().apply { add(first) }
        setupToolWindowContent(wrapper)
        realState.hideProjectViewHScrollbar = true
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, first.horizontalScrollBarPolicy)
            wrapper.remove(first)
            wrapper.add(second)
            manager.apply()
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, second.horizontalScrollBarPolicy)

            wrapper.remove(second)
            wrapper.add(first)
            realState.hideProjectViewHScrollbar = false
            manager.apply()

            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, first.horizontalScrollBarPolicy)
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, second.horizontalScrollBarPolicy)
            manager.dispose()
        }
    }

    @Test
    fun `prepared Project View policy cannot be written after manager disposal`() {
        val pane = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        setupToolWindowContent(JPanel().apply { add(pane) })
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            val change = checkNotNull(manager.prepareScrollbar(hideHorizontal = true))
            manager.dispose()

            assertFalse(change.apply())
            assertFalse(change.hasPending)
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, pane.horizontalScrollBarPolicy)
        }
    }

    @Test
    fun `disposal defers legacy cleanup until a failed Project View rollback can retry`() {
        val pane = RollbackFailingPane()
        setupToolWindowContent(JPanel().apply { add(pane) })
        realState.hideProjectViewHScrollbar = true
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            val change = checkNotNull(manager.prepareScrollbar(hideHorizontal = false))
            assertTrue(change.apply())
            pane.rejectedPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

            assertFailsWith<IllegalStateException> { manager.dispose() }
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, pane.horizontalScrollBarPolicy)
            change.retain()
            change.restore()
            assertFalse(change.hasPending)
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, pane.horizontalScrollBarPolicy)
        }
        SwingUtilities.invokeAndWait {
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, pane.horizontalScrollBarPolicy)
            assertTrue(realState.hideProjectViewHScrollbar)
        }
    }

    @Test
    fun `a throwing Project View listener leaves the changed policy recoverable`() {
        val pane = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        setupToolWindowContent(JPanel().apply { add(pane) })
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            val native = checkNotNull(manager.prepareScrollbar(hideHorizontal = true))
            pane.addPropertyChangeListener("horizontalScrollBarPolicy") { event ->
                check(event.newValue != ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
                    "Listener failed after write"
                }
            }

            assertFailsWith<IllegalStateException> { native.apply() }
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, pane.horizontalScrollBarPolicy)
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, native.lastWritten.policy)
            assertTrue(native.hasPending)

            native.restore()
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, pane.horizontalScrollBarPolicy)
            assertFalse(native.hasPending)
            manager.dispose()
        }
    }

    @Test
    fun `rollback and its deferred refresh do not overwrite a foreign Project View policy`() {
        val pane = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        setupToolWindowContent(JPanel().apply { add(pane) })
        realState.hideProjectViewHScrollbar = true
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            val native = checkNotNull(manager.prepareScrollbar(hideHorizontal = false))
            assertTrue(native.apply())
            manager.apply()
            pane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            native.restore()
            assertFalse(native.hasPending)
        }

        SwingUtilities.invokeAndWait {
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, pane.horizontalScrollBarPolicy)
            manager.dispose()
        }
    }

    @Test
    fun `prepared Project View policy cannot be written to a replaced pane`() {
        val first = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        val replacement = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        val wrapper = JPanel().apply { add(first) }
        setupToolWindowContent(wrapper)
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            val native = checkNotNull(manager.prepareScrollbar(hideHorizontal = true))
            wrapper.remove(first)
            wrapper.add(replacement)

            assertFalse(native.apply())
            native.restore()
            assertFalse(native.hasPending)
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, first.horizontalScrollBarPolicy)
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED, replacement.horizontalScrollBarPolicy)
            manager.dispose()
        }
    }

    @Test
    fun `an accepted Project View change replays the latest scrollbar preference after release`() {
        val pane = projectPane(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
        setupToolWindowContent(JPanel().apply { add(pane) })
        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            val native = checkNotNull(manager.prepareScrollbar(hideHorizontal = true))
            assertTrue(native.apply())
            manager.apply()
            manager.apply()
            realState.hideProjectViewHScrollbar = true
            native.commit()
            native.releaseResources()

            // A component refresh reset the policy while the deferred update was queued.
            pane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
        }

        SwingUtilities.invokeAndWait {
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, pane.horizontalScrollBarPolicy)
            manager.dispose()
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS, pane.horizontalScrollBarPolicy)
        }
    }

    private class RollbackFailingPane :
        JScrollPane(JTree(), VERTICAL_SCROLLBAR_ALWAYS, HORIZONTAL_SCROLLBAR_ALWAYS) {
        var rejectedPolicy: Int? = null

        override fun setHorizontalScrollBarPolicy(policy: Int) {
            if (policy == rejectedPolicy) {
                rejectedPolicy = null
                error("IDE refused the rollback write")
            }
            super.setHorizontalScrollBarPolicy(policy)
        }
    }

    private fun projectPane(policy: Int): JScrollPane =
        JScrollPane(JTree(), ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, policy)

    private fun setupToolWindowContent(component: JPanel): ToolWindow {
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
                every { contentManagerIfCreated } returns contentManager
            }
        every {
            toolWindowManager.getToolWindow("Project")
        } returns toolWindow
        return toolWindow
    }
}
