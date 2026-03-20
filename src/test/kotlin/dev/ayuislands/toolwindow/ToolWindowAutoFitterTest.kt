package dev.ayuislands.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import dev.ayuislands.settings.PanelWidthMode
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// EDT note: tests use SwingUtilities.invokeAndWait because
// ToolWindowAutoFitter asserts EDT in applyAutoFitWidth/applyFixedWidth.
// Pure calculation logic is already extracted into AutoFitCalculator
// (tested without EDT in AutoFitCalculatorTest). If these tests flake
// on CI, the EDT assertion can be relaxed — but so far it hasn't.
class ToolWindowAutoFitterTest {
    private lateinit var project: Project
    private lateinit var toolWindowManager: ToolWindowManager
    private lateinit var toolWindowEx: ToolWindowEx

    @BeforeTest
    fun setUp() {
        mockkStatic(ToolWindowManager::class)
        project =
            mockk(relaxed = true) {
                every { isDisposed } returns false
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

    // region applyWidthMode

    @Test
    fun `applyWidthMode DEFAULT removes listener`() {
        SwingUtilities.invokeAndWait {
            every {
                toolWindowManager.getToolWindow("Project")
            } returns null

            val fitter =
                ToolWindowAutoFitter(
                    project,
                    "Project",
                    253,
                )
            fitter.applyWidthMode(
                PanelWidthMode.DEFAULT,
                400,
                300,
            )
            // DEFAULT mode should not look up a tree
            // (no crash = listener removal path hit)
        }
    }

    @Test
    fun `applyWidthMode AUTO_FIT installs listener`() {
        SwingUtilities.invokeAndWait {
            val tree = JTree()
            val panel = JPanel(FlowLayout())
            panel.add(tree)
            val content =
                mockk<Content>(relaxed = true) {
                    every { component } returns panel
                }
            val contentManager =
                mockk<ContentManager>(relaxed = true) {
                    every { contents } returns arrayOf(content)
                }
            every {
                toolWindowManager.getToolWindow("Project")
            } returns toolWindowEx
            every {
                toolWindowEx.contentManager
            } returns contentManager
            every { toolWindowEx.component } returns panel
            every {
                toolWindowEx.type
            } returns ToolWindowType.DOCKED

            val fitter =
                ToolWindowAutoFitter(
                    project,
                    "Project",
                    253,
                )
            fitter.applyWidthMode(
                PanelWidthMode.AUTO_FIT,
                400,
                300,
            )
            // Should reach applyAutoFitWidth without a crash
        }
    }

    @Test
    fun `applyWidthMode FIXED removes listener`() {
        SwingUtilities.invokeAndWait {
            val panel = JPanel(FlowLayout())
            panel.setSize(200, 400)
            every {
                toolWindowManager.getToolWindow("Commit")
            } returns toolWindowEx
            every { toolWindowEx.component } returns panel
            every {
                toolWindowEx.type
            } returns ToolWindowType.DOCKED

            val fitter =
                ToolWindowAutoFitter(
                    project,
                    "Commit",
                    269,
                )
            fitter.applyWidthMode(
                PanelWidthMode.FIXED,
                400,
                350,
            )
            // Should call applyFixedWidth ->
            // stretchWidth with delta
            verify { toolWindowEx.stretchWidth(any()) }
        }
    }

    // endregion

    // region findTree

    @Test
    fun `findTree returns null when no tool window`() {
        every {
            toolWindowManager.getToolWindow("Project")
        } returns null

        val fitter =
            ToolWindowAutoFitter(
                project,
                "Project",
                253,
            )
        assertNull(fitter.findTree())
    }

    @Test
    fun `findTree returns null when no contents`() {
        val contentManager =
            mockk<ContentManager>(relaxed = true) {
                every { contents } returns emptyArray()
            }
        val toolWindow =
            mockk<ToolWindow>(relaxed = true) {
                every { this@mockk.contentManager } returns
                    contentManager
            }
        every {
            toolWindowManager.getToolWindow("Project")
        } returns toolWindow

        val fitter =
            ToolWindowAutoFitter(
                project,
                "Project",
                253,
            )
        assertNull(fitter.findTree())
    }

    @Test
    fun `findTree returns JTree from hierarchy`() {
        val tree = JTree()
        val inner = JPanel(FlowLayout())
        inner.add(tree)
        val outer = JPanel(FlowLayout())
        outer.add(inner)

        val content =
            mockk<Content>(relaxed = true) {
                every { component } returns outer
            }
        val contentManager =
            mockk<ContentManager>(relaxed = true) {
                every { contents } returns arrayOf(content)
            }
        val toolWindow =
            mockk<ToolWindow>(relaxed = true) {
                every { this@mockk.contentManager } returns
                    contentManager
            }
        every {
            toolWindowManager.getToolWindow("Project")
        } returns toolWindow

        val fitter =
            ToolWindowAutoFitter(
                project,
                "Project",
                253,
            )
        assertNotNull(fitter.findTree())
    }

    // endregion

    // region applyAutoFitWidth

    @Test
    fun `applyAutoFitWidth skips stretch for jitter`() {
        SwingUtilities.invokeAndWait {
            // Tree width 180 + padding 20 = 200
            // Current = 200 -> delta = 0 -> jitter
            mockCalculatorForWidth(180)
            val tree = JTree()
            val panel = JPanel(FlowLayout())
            panel.add(tree)
            panel.setSize(200, 400)

            val content =
                mockk<Content>(relaxed = true) {
                    every { component } returns panel
                }
            val contentManager =
                mockk<ContentManager>(relaxed = true) {
                    every { contents } returns arrayOf(content)
                }
            every {
                toolWindowManager.getToolWindow("Project")
            } returns toolWindowEx
            every {
                toolWindowEx.contentManager
            } returns contentManager
            every {
                toolWindowEx.component
            } returns panel
            every {
                toolWindowEx.type
            } returns ToolWindowType.DOCKED

            val fitter =
                ToolWindowAutoFitter(
                    project,
                    "Project",
                    100,
                )
            fitter.applyAutoFitWidth(500)
            verify(exactly = 0) {
                toolWindowEx.stretchWidth(any())
            }
        }
    }

    @Test
    fun `applyAutoFitWidth calls stretch for delta`() {
        SwingUtilities.invokeAndWait {
            // Tree width 280 + padding 20 = 300
            // Current = 200 -> delta = 100
            mockCalculatorForWidth(280)
            val tree = JTree()
            val panel = JPanel(FlowLayout())
            panel.add(tree)
            panel.setSize(200, 400)

            val content =
                mockk<Content>(relaxed = true) {
                    every { component } returns panel
                }
            val contentManager =
                mockk<ContentManager>(relaxed = true) {
                    every { contents } returns arrayOf(content)
                }
            every {
                toolWindowManager.getToolWindow("Project")
            } returns toolWindowEx
            every {
                toolWindowEx.contentManager
            } returns contentManager
            every {
                toolWindowEx.component
            } returns panel
            every {
                toolWindowEx.type
            } returns ToolWindowType.DOCKED

            val fitter =
                ToolWindowAutoFitter(
                    project,
                    "Project",
                    100,
                )
            fitter.applyAutoFitWidth(500)
            verify { toolWindowEx.stretchWidth(100) }
        }
    }

    // endregion

    // region removeExpansionListener

    @Test
    fun `removeExpansionListener is safe when nothing installed`() {
        val fitter =
            ToolWindowAutoFitter(
                project,
                "Project",
                253,
            )
        // Should not throw
        fitter.removeExpansionListener()
    }

    // endregion

    // region installExpansionListener

    @Test
    fun `installExpansionListener adds listener to tree`() {
        SwingUtilities.invokeAndWait {
            val tree = JTree()
            val panel = JPanel(FlowLayout())
            panel.add(tree)
            val content =
                mockk<Content>(relaxed = true) {
                    every { component } returns panel
                }
            val contentManager =
                mockk<ContentManager>(relaxed = true) {
                    every { contents } returns arrayOf(content)
                }
            every {
                toolWindowManager.getToolWindow("Project")
            } returns toolWindowEx
            every {
                toolWindowEx.contentManager
            } returns contentManager

            val listenersBefore =
                tree.treeExpansionListeners.size
            val fitter =
                ToolWindowAutoFitter(
                    project,
                    "Project",
                    253,
                )
            fitter.installExpansionListener()
            val listenersAfter =
                tree.treeExpansionListeners.size
            assert(listenersAfter > listenersBefore) {
                "Expected listener to be added"
            }
        }
    }

    // endregion

    // region project disposed

    @Test
    fun `applyAutoFitWidth no-ops when project disposed`() {
        SwingUtilities.invokeAndWait {
            every { project.isDisposed } returns true
            every {
                toolWindowManager.getToolWindow("Project")
            } returns null

            val fitter =
                ToolWindowAutoFitter(
                    project,
                    "Project",
                    253,
                )
            // Should return early without a crash
            fitter.applyAutoFitWidth(400)
        }
    }

    // endregion

    /**
     * Mocks AutoFitCalculator measurement methods
     * so headless JTree (no row bounds) can be used.
     * findFirstOfType is left unmocked (works on
     * real Swing components without a display).
     */
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
