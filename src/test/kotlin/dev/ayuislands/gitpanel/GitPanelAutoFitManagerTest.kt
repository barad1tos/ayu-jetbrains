package dev.ayuislands.gitpanel

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.toolwindow.AutoFitCalculator
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.tree.TreePath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class GitPanelAutoFitManagerTest {
    private lateinit var project: Project
    private lateinit var toolWindowManager: ToolWindowManager
    private lateinit var settingsMock: AyuIslandsSettings
    private lateinit var realState: AyuIslandsState
    private lateinit var connection: MessageBusConnection

    @BeforeTest
    fun setUp() {
        mockkStatic(ToolWindowManager::class)
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
            }

        toolWindowManager = mockk(relaxed = true)
        every {
            ToolWindowManager.getInstance(project)
        } returns toolWindowManager
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `apply does nothing when not licensed`() {
        every {
            LicenseChecker.isLicensedOrGrace()
        } returns false

        val manager = GitPanelAutoFitManager(project)
        manager.apply()

        // No tool window interaction
        verify(exactly = 0) {
            toolWindowManager.getToolWindow(any())
        }
    }

    @Test
    fun `apply with DEFAULT removes listeners`() {
        every {
            LicenseChecker.isLicensedOrGrace()
        } returns true
        realState.gitPanelWidthMode =
            PanelWidthMode.DEFAULT.name

        every {
            toolWindowManager.getToolWindow("Version Control")
        } returns null

        val manager = GitPanelAutoFitManager(project)
        manager.apply()
        // No crash = listener removal path works
    }

    @Test
    fun `apply with AUTO_FIT fits splitters`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            realState.gitPanelWidthMode =
                PanelWidthMode.AUTO_FIT.name
            realState.gitPanelAutoFitMaxWidth = 500
            realState.gitPanelAutoFitMinWidth = 200

            mockkObject(AutoFitCalculator)
            every {
                AutoFitCalculator.measureTreeMaxRowWidth(any())
            } returns 250

            // Build a splitter hierarchy inside a "Log" tab
            val tree = JTree()
            val table = JTable()
            val innerFirst = JPanel(FlowLayout())
            innerFirst.add(table)
            val innerSecond = JPanel(FlowLayout())
            innerSecond.add(tree)

            val splitter = Splitter()
            splitter.setSize(1000, 400)
            splitter.firstComponent = innerFirst
            splitter.secondComponent = innerSecond

            val logContent =
                mockk<Content>(relaxed = true) {
                    every { tabName } returns "Log"
                    every { component } returns splitter
                }
            val contentManager =
                mockk<ContentManager>(relaxed = true) {
                    every {
                        contents
                    } returns arrayOf(logContent)
                }
            val toolWindow =
                mockk<ToolWindow>(relaxed = true) {
                    every {
                        this@mockk.contentManager
                    } returns contentManager
                }
            every {
                toolWindowManager
                    .getToolWindow("Version Control")
            } returns toolWindow

            val manager = GitPanelAutoFitManager(project)
            manager.apply()

            // Inner splitter (firstHasTable=true):
            // proportion = (1.0 - desired/total)
            //   .coerceIn(0.5, 0.95)
            // desired = (250+20).coerceAtMost(500)
            //                   .coerceAtLeast(200) = 270
            // expected ≈ 1.0 - 270/1000 = 0.73
            val proportion = splitter.proportion
            assertTrue(
                proportion in 0.7f..0.76f,
                "Expected inner proportion ~0.73 " +
                    "but got $proportion",
            )
        }
    }

    @Test
    fun `fitSplitter handles null components gracefully`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            realState.gitPanelWidthMode =
                PanelWidthMode.AUTO_FIT.name

            // Splitter with null components (lazy-loaded)
            val splitter = Splitter()
            splitter.setSize(1000, 400)
            // firstComponent and secondComponent are null

            val logContent =
                mockk<Content>(relaxed = true) {
                    every { tabName } returns "Log"
                    every { component } returns splitter
                }
            val contentManager =
                mockk<ContentManager>(relaxed = true) {
                    every {
                        contents
                    } returns arrayOf(logContent)
                }
            val toolWindow =
                mockk<ToolWindow>(relaxed = true) {
                    every {
                        this@mockk.contentManager
                    } returns contentManager
                }
            every {
                toolWindowManager
                    .getToolWindow("Version Control")
            } returns toolWindow

            val manager = GitPanelAutoFitManager(project)
            // Should not throw
            manager.apply()
        }
    }

    @Test
    fun `apply with FIXED mode sets proportions`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            realState.gitPanelWidthMode =
                PanelWidthMode.FIXED.name
            realState.gitPanelFixedWidth = 300

            val tree = JTree()
            val panel = JPanel(FlowLayout())
            panel.add(tree)

            // Outer splitter: first has tree (no table)
            val splitter = Splitter()
            splitter.setSize(1000, 400)
            splitter.firstComponent = panel
            splitter.secondComponent = JPanel()

            val logContent =
                mockk<Content>(relaxed = true) {
                    every { tabName } returns "Log"
                    every { component } returns splitter
                }
            val contentManager =
                mockk<ContentManager>(relaxed = true) {
                    every {
                        contents
                    } returns arrayOf(logContent)
                }
            val toolWindow =
                mockk<ToolWindow>(relaxed = true) {
                    every {
                        this@mockk.contentManager
                    } returns contentManager
                }
            every {
                toolWindowManager
                    .getToolWindow("Version Control")
            } returns toolWindow

            val manager = GitPanelAutoFitManager(project)
            manager.apply()

            // Outer proportion = fixedWidth / splitterWidth
            // = 300/1000 = 0.3, coerced to [0.05, 0.5]
            val proportion = splitter.proportion
            assertTrue(
                proportion in 0.05f..0.5f,
                "Expected outer proportion in " +
                    "[0.05, 0.5] but got $proportion",
            )
        }
    }

    @Test
    fun `dispose removes expansion listeners safely`() {
        every {
            LicenseChecker.isLicensedOrGrace()
        } returns true
        every {
            toolWindowManager.getToolWindow("Version Control")
        } returns null

        val manager = GitPanelAutoFitManager(project)
        // Should not throw
        manager.dispose()
    }

    @Test
    fun `init subscribes to ToolWindowManagerListener`() {
        GitPanelAutoFitManager(project)

        verify {
            connection.subscribe(
                ToolWindowManagerListener.TOPIC,
                any(),
            )
        }
    }

    @Test
    fun `stateChanged ignores events from another visible tool window`() {
        every {
            LicenseChecker.isLicensedOrGrace()
        } returns true
        realState.gitPanelWidthMode =
            PanelWidthMode.AUTO_FIT.name
        every { toolWindowManager.activeToolWindowId } returns "AWS"

        val listenerSlot = slot<ToolWindowManagerListener>()
        every {
            connection.subscribe(
                ToolWindowManagerListener.TOPIC,
                capture(listenerSlot),
            )
        } returns Unit

        GitPanelAutoFitManager(project)

        listenerSlot.captured.stateChanged(
            toolWindowManager,
            ToolWindowManagerEventType.ActivateToolWindow,
        )

        verify(exactly = 0) {
            toolWindowManager.getToolWindow("Version Control")
        }
    }

    @Test
    fun `stateChanged handles global layout changes for visible Version Control`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            realState.gitPanelWidthMode =
                PanelWidthMode.AUTO_FIT.name
            realState.gitPanelAutoFitMaxWidth = 500
            realState.gitPanelAutoFitMinWidth = 200

            mockkObject(AutoFitCalculator)
            every {
                AutoFitCalculator.measureTreeMaxRowWidth(any())
            } returns 250

            val tree = JTree()
            val table = JTable()
            val innerFirst = JPanel(FlowLayout())
            innerFirst.add(table)
            val innerSecond = JPanel(FlowLayout())
            innerSecond.add(tree)

            val splitter = Splitter()
            splitter.setSize(1000, 400)
            splitter.proportion = 0.9f
            splitter.firstComponent = innerFirst
            splitter.secondComponent = innerSecond

            val logContent =
                mockk<Content>(relaxed = true) {
                    every { tabName } returns "Log"
                    every { component } returns splitter
                }
            val contentManager =
                mockk<ContentManager>(relaxed = true) {
                    every {
                        contents
                    } returns arrayOf(logContent)
                }
            val toolWindow =
                mockk<ToolWindow>(relaxed = true) {
                    every { id } returns "Version Control"
                    every { isVisible } returns true
                    every {
                        this@mockk.contentManager
                    } returns contentManager
                }
            every { toolWindowManager.activeToolWindowId } returns "Version Control"
            every {
                toolWindowManager.getToolWindow("Version Control")
            } returns toolWindow

            val listenerSlot = slot<ToolWindowManagerListener>()
            every {
                connection.subscribe(
                    ToolWindowManagerListener.TOPIC,
                    capture(listenerSlot),
                )
            } returns Unit

            val manager = GitPanelAutoFitManager(project)
            try {
                listenerSlot.captured.stateChanged(
                    toolWindowManager,
                    ToolWindowManagerEventType.SetLayout,
                )
                manager.flushDebounceForTesting()

                val proportion = splitter.proportion
                assertTrue(
                    proportion in 0.7f..0.76f,
                    "Expected global layout refresh to fit splitter, got $proportion",
                )
            } finally {
                manager.dispose()
            }
        }
    }

    @Test
    fun `listener is removed when mode switches from AUTO_FIT to DEFAULT`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            realState.gitPanelWidthMode =
                PanelWidthMode.AUTO_FIT.name
            realState.gitPanelAutoFitMaxWidth = 500
            realState.gitPanelAutoFitMinWidth = 200

            mockkObject(AutoFitCalculator)
            every {
                AutoFitCalculator.measureTreeMaxRowWidth(any())
            } returns 250

            val tree = JTree()
            val table = JTable()
            val innerFirst = JPanel(FlowLayout())
            innerFirst.add(table)
            val innerSecond = JPanel(FlowLayout())
            innerSecond.add(tree)

            val splitter = Splitter()
            splitter.setSize(1000, 400)
            splitter.firstComponent = innerFirst
            splitter.secondComponent = innerSecond

            val logContent =
                mockk<Content>(relaxed = true) {
                    every { tabName } returns "Log"
                    every { component } returns splitter
                }
            val contentManager =
                mockk<ContentManager>(relaxed = true) {
                    every {
                        contents
                    } returns arrayOf(logContent)
                }
            val toolWindow =
                mockk<ToolWindow>(relaxed = true) {
                    every {
                        this@mockk.contentManager
                    } returns contentManager
                }
            every {
                toolWindowManager
                    .getToolWindow("Version Control")
            } returns toolWindow

            val listenersBefore = tree.treeExpansionListeners.size
            val manager = GitPanelAutoFitManager(project)

            // AUTO_FIT: expansion listener attached to inner tree
            manager.apply()
            assert(tree.treeExpansionListeners.size > listenersBefore) {
                "Expected expansion listener installed after AUTO_FIT"
            }

            // Switch to DEFAULT: listener must be removed
            realState.gitPanelWidthMode =
                PanelWidthMode.DEFAULT.name
            manager.apply()
            assert(tree.treeExpansionListeners.size == listenersBefore) {
                "Expected expansion listener removed after DEFAULT"
            }
        }
    }

    @Test
    fun `entitlement loss removes AUTO_FIT automation without changing preferences`() {
        SwingUtilities.invokeAndWait {
            var licensed = true
            every { LicenseChecker.isLicensedOrGrace() } answers { licensed }
            val fixture = createAutoFitFixture()
            val initialListenerCount = fixture.tree.treeExpansionListeners.size
            val savedMaxWidth = realState.gitPanelAutoFitMaxWidth
            val savedMinWidth = realState.gitPanelAutoFitMinWidth

            fixture.manager.apply()
            assertTrue(fixture.tree.treeExpansionListeners.size > initialListenerCount)

            licensed = false
            fixture.manager.apply()

            assertTrue(fixture.tree.treeExpansionListeners.size == initialListenerCount)
            assertTrue(realState.gitPanelWidthMode == PanelWidthMode.AUTO_FIT.name)
            assertTrue(realState.gitPanelAutoFitMaxWidth == savedMaxWidth)
            assertTrue(realState.gitPanelAutoFitMinWidth == savedMinWidth)
        }
    }

    @Test
    fun `delayed AUTO_FIT does not mutate geometry after entitlement loss`() {
        SwingUtilities.invokeAndWait {
            var licensed = true
            every { LicenseChecker.isLicensedOrGrace() } answers { licensed }
            val fixture = createAutoFitFixture()
            fixture.manager.apply()
            fixture.splitter.proportion = 0.9f

            fixture.tree.treeExpansionListeners.forEach {
                it.treeExpanded(TreeExpansionEvent(fixture.tree, TreePath(fixture.tree.model.root)))
            }
            licensed = false
            fixture.manager.flushDebounceForTesting()

            assertTrue(fixture.splitter.proportion == 0.9f)
        }
    }

    @Test
    fun `fixed geometry mutation is gated when entitlement is lost`() {
        SwingUtilities.invokeAndWait {
            every { LicenseChecker.isLicensedOrGrace() } returnsMany listOf(true, false)
            realState.gitPanelWidthMode = PanelWidthMode.FIXED.name
            realState.gitPanelFixedWidth = 300
            val fixture = createAutoFitFixture()
            fixture.splitter.proportion = 0.4f

            fixture.manager.apply()

            assertTrue(fixture.splitter.proportion == 0.4f)
        }
    }

    @Test
    fun `relicensing restores automation for preserved AUTO_FIT mode`() {
        SwingUtilities.invokeAndWait {
            var licensed = true
            every { LicenseChecker.isLicensedOrGrace() } answers { licensed }
            val fixture = createAutoFitFixture()
            val initialListenerCount = fixture.tree.treeExpansionListeners.size

            fixture.manager.apply()
            licensed = false
            fixture.manager.apply()
            licensed = true
            fixture.manager.apply()

            assertTrue(fixture.tree.treeExpansionListeners.size > initialListenerCount)
            assertTrue(realState.gitPanelWidthMode == PanelWidthMode.AUTO_FIT.name)
        }
    }

    private fun createAutoFitFixture(): AutoFitFixture {
        realState.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        realState.gitPanelAutoFitMaxWidth = 500
        realState.gitPanelAutoFitMinWidth = 200
        mockkObject(AutoFitCalculator)
        every { AutoFitCalculator.measureTreeMaxRowWidth(any()) } returns 250

        val tree = JTree()
        val tablePanel = JPanel(FlowLayout()).apply { add(JTable()) }
        val treePanel = JPanel(FlowLayout()).apply { add(tree) }
        val splitter =
            Splitter().apply {
                setSize(1000, 400)
                firstComponent = tablePanel
                secondComponent = treePanel
            }
        val logContent =
            mockk<Content>(relaxed = true) {
                every { tabName } returns "Log"
                every { component } returns splitter
            }
        val contentManager =
            mockk<ContentManager>(relaxed = true) {
                every { contents } returns arrayOf(logContent)
            }
        val toolWindow =
            mockk<ToolWindow>(relaxed = true) {
                every { this@mockk.contentManager } returns contentManager
            }
        every { toolWindowManager.getToolWindow("Version Control") } returns toolWindow

        return AutoFitFixture(GitPanelAutoFitManager(project), tree, splitter)
    }

    private data class AutoFitFixture(
        val manager: GitPanelAutoFitManager,
        val tree: JTree,
        val splitter: Splitter,
    )
}
