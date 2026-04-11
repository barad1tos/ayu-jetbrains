package dev.ayuislands.commitpanel

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
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
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.tree.TreePath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class CommitPanelAutoFitManagerTest {
    private lateinit var project: Project
    private lateinit var toolWindowManager: ToolWindowManager
    private lateinit var toolWindowEx: ToolWindowEx
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

        toolWindowEx = mockk(relaxed = true)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `apply does nothing when not licensed`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns false

            val manager = CommitPanelAutoFitManager(project)
            manager.apply()

            // No ToolWindowManager interaction expected
            verify(exactly = 0) {
                toolWindowManager.getToolWindow(any())
            }
        }
    }

    @Test
    fun `apply with DEFAULT mode does not stretch`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            realState.commitPanelWidthMode =
                PanelWidthMode.DEFAULT.name

            // No tool window needed; DEFAULT just removes
            // expansion listener (no-op if none installed)
            every {
                toolWindowManager.getToolWindow("Commit")
            } returns null

            val manager = CommitPanelAutoFitManager(project)
            manager.apply()

            // Should not call stretchWidth
            verify(exactly = 0) {
                toolWindowEx.stretchWidth(any())
            }
        }
    }

    @Test
    fun `apply with FIXED mode calls stretchWidth`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            realState.commitPanelWidthMode =
                PanelWidthMode.FIXED.name
            realState.commitPanelFixedWidth = 350

            val panel = JPanel(FlowLayout())
            panel.setSize(200, 400)
            every {
                toolWindowManager.getToolWindow("Commit")
            } returns toolWindowEx
            every {
                toolWindowEx.component
            } returns panel
            every {
                toolWindowEx.type
            } returns ToolWindowType.DOCKED

            val manager = CommitPanelAutoFitManager(project)
            manager.apply()

            verify { toolWindowEx.stretchWidth(any()) }
        }
    }

    @Test
    fun `dispose removes expansion listener safely`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            every {
                toolWindowManager.getToolWindow("Commit")
            } returns null

            val manager = CommitPanelAutoFitManager(project)
            // Should not throw
            manager.dispose()
        }
    }

    @Test
    fun `init subscribes to ToolWindowManagerListener`() {
        CommitPanelAutoFitManager(project)

        verify {
            connection.subscribe(
                ToolWindowManagerListener.TOPIC,
                any(),
            )
        }
    }

    @Test
    fun `apply with AUTO_FIT installs expansion listener and stretches on expand`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            realState.commitPanelWidthMode =
                PanelWidthMode.AUTO_FIT.name
            realState.autoFitCommitMaxWidth = 500
            realState.commitPanelAutoFitMinWidth = 269

            mockCalculatorForWidth(280)
            val tree = JTree()
            val panel = JPanel(FlowLayout())
            panel.add(tree)
            panel.setSize(200, 400)
            setupCommitToolWindow(panel)

            val listenersBefore = tree.treeExpansionListeners.size
            val manager = CommitPanelAutoFitManager(project)
            manager.apply()

            // Listener installed
            assert(tree.treeExpansionListeners.size > listenersBefore) {
                "Expected expansion listener to be installed"
            }
            // Immediate apply on mode change also stretches once
            verify { toolWindowEx.stretchWidth(any()) }

            // Fire a tree expansion event — goes through debounce
            val path = TreePath(tree.model.root)
            val event = TreeExpansionEvent(tree, path)
            for (listener in tree.treeExpansionListeners) {
                listener.treeExpanded(event)
            }
        }

        // Allow debounce (150ms) to fire on EDT
        Thread.sleep(400)
        SwingUtilities.invokeAndWait { /* flush */ }

        // Expect at least one stretch invocation after debounce fires
        verify(atLeast = 1) { toolWindowEx.stretchWidth(any()) }
    }

    @Test
    fun `listener is removed when mode switches from AUTO_FIT to DEFAULT`() {
        SwingUtilities.invokeAndWait {
            every {
                LicenseChecker.isLicensedOrGrace()
            } returns true
            realState.commitPanelWidthMode =
                PanelWidthMode.AUTO_FIT.name
            realState.autoFitCommitMaxWidth = 500
            realState.commitPanelAutoFitMinWidth = 269

            mockCalculatorForWidth(280)
            val tree = JTree()
            val panel = JPanel(FlowLayout())
            panel.add(tree)
            panel.setSize(200, 400)
            setupCommitToolWindow(panel)

            val listenersBefore = tree.treeExpansionListeners.size
            val manager = CommitPanelAutoFitManager(project)

            // AUTO_FIT -> listener installed
            manager.apply()
            assert(tree.treeExpansionListeners.size > listenersBefore) {
                "Expected listener installed after AUTO_FIT"
            }

            // Switch to DEFAULT -> listener must be removed
            realState.commitPanelWidthMode =
                PanelWidthMode.DEFAULT.name
            manager.apply()
            assert(tree.treeExpansionListeners.size == listenersBefore) {
                "Expected listener removed after DEFAULT"
            }

            // Firing an expansion event now should NOT trigger stretch
            // (use a fresh mock call counter via a second manager call).
            // We verify by checking that no listener exists on the tree.
        }
    }

    private fun setupCommitToolWindow(component: JPanel) {
        val content =
            mockk<Content>(relaxed = true) {
                every { this@mockk.component } returns component
            }
        val contentManager =
            mockk<ContentManager>(relaxed = true) {
                every { contents } returns arrayOf(content)
            }
        every {
            toolWindowManager.getToolWindow("Commit")
        } returns toolWindowEx
        every {
            toolWindowEx.contentManager
        } returns contentManager
        every {
            toolWindowEx.component
        } returns component
        every {
            toolWindowEx.type
        } returns ToolWindowType.DOCKED
    }

    private fun mockCalculatorForWidth(targetWidth: Int) {
        mockkObject(AutoFitCalculator)
        every {
            AutoFitCalculator.measureTreeMaxRowWidth(any())
        } returns targetWidth
        every {
            AutoFitCalculator.isJitterOnly(any(), any())
        } answers {
            val current = firstArg<Int>()
            val desired = secondArg<Int>()
            kotlin.math.abs(desired - current) <=
                AutoFitCalculator.JITTER_THRESHOLD
        }
        every {
            AutoFitCalculator.calculateDesiredWidth(
                any(),
                any(),
                any(),
            )
        } answers {
            val maxRow = firstArg<Int>()
            val maxW = secondArg<Int>()
            val minW = thirdArg<Int>()
            (maxRow + AutoFitCalculator.AUTOFIT_PADDING)
                .coerceAtMost(maxW)
                .coerceAtLeast(minW)
        }
    }
}
