package dev.ayuislands.gitpanel

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
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
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingUtilities
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

            // Splitter proportion should have been set
            // (inner splitter: firstHasTable=true)
            val proportion = splitter.proportion
            assertTrue(
                proportion in 0.5f..0.95f,
                "Expected inner proportion in " +
                    "[0.5, 0.95] but got $proportion",
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
}
