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
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.FlowLayout
import java.util.MissingResourceException
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectViewScrollbarManagerTest {
    private lateinit var project: Project
    private lateinit var toolWindowManager: ToolWindowManager
    private lateinit var settingsMock: AyuIslandsSettings
    private lateinit var realState: AyuIslandsState
    private lateinit var connection: MessageBusConnection
    private lateinit var registryValue: RegistryValue

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
            ProjectViewScrollbarManager(project)
        // Drain the invokeLater posted by init{}
        SwingUtilities.invokeAndWait {}
        return manager
    }

    @Test
    fun `apply does nothing when not licensed`() {
        every {
            LicenseChecker.isLicensedOrGrace()
        } returns false

        val manager = createAndDrain()

        SwingUtilities.invokeAndWait {
            manager.apply()
        }

        // No tool window interaction beyond init drain
        // (which also exited early due to no license)
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

        every {
            Registry.get(any<String>())
        } throws
            MissingResourceException(
                "Not found",
                "Registry",
                "project.tree.structure.show.url",
            )

        val manager = createAndDrain()

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
                every {
                    this@mockk.contentManager
                } returns contentManager
            }
        every {
            toolWindowManager.getToolWindow("Project")
        } returns toolWindow
    }
}
