package dev.ayuislands.commitpanel

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.CommitPathDisplayMode
import dev.ayuislands.settings.PanelWidthMode
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val SAMPLE_SOURCE_PATH = "sample/source/root/package/renderer"
private const val DIRECTORY_ROW_PATH = "fixtures/domain/subsystem/renderer"

class CommitPathShorteningRendererTest {
    @Test
    fun `renderer shortens only the path fragment when commit width is managed`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathMinHiddenLevels = 2
                    commitPanelPathMaxHiddenLevels = 2
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        filePathRenderer(
                            fileName = "AddProjectMappingDialog.kt",
                            path = "alpha/beta/gamma/delta",
                        ),
                    stateProvider = { state },
                )

            val component = render(renderer, treeWithVisibleWidth(260))
            val fragments = component.fragmentsForTest()

            assertEquals("AddProjectMappingDialog.kt", fragments.first())
            assertEquals("  .../gamma/delta", fragments.last())
        }
    }

    @Test
    fun `renderer preserves native fragments in default mode`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.DEFAULT.name
                    commitPanelPathMinHiddenLevels = 2
                    commitPanelPathMaxHiddenLevels = 2
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        filePathRenderer(
                            fileName = "AddProjectMappingDialog.kt",
                            path = "alpha/beta/gamma/delta",
                        ),
                    stateProvider = { state },
                )

            val component = render(renderer, treeWithVisibleWidth(120))

            assertEquals(
                listOf(
                    "AddProjectMappingDialog.kt",
                    "  alpha/beta/gamma/delta",
                ),
                component.fragmentsForTest(),
            )
        }
    }

    @Test
    fun `renderer leaves component unchanged when delegate is not simple colored component`() {
        SwingUtilities.invokeAndWait {
            val label = JLabel("alpha/beta/gamma")
            val renderer =
                CommitPathShorteningRenderer(
                    delegate = { _, _, _, _, _, _, _ -> label },
                    stateProvider = {
                        AyuIslandsState().apply {
                            commitPanelWidthMode = PanelWidthMode.FIXED.name
                            commitPanelPathMinHiddenLevels = 2
                            commitPanelPathMaxHiddenLevels = 2
                        }
                    },
                )

            val rendered = renderComponent(renderer, treeWithVisibleWidth(120))

            assertSame(label, rendered)
            assertEquals("alpha/beta/gamma", label.text)
        }
    }

    @Test
    fun `renderer keeps full path when it is no wider than the path anchor`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathMinHiddenLevels = 0
                    commitPanelPathMaxHiddenLevels = 5
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        filePathRenderer(
                            fileName = "LongEnoughFileName.kt",
                            path = "alpha/beta",
                        ),
                    stateProvider = { state },
                )

            val component = render(renderer, treeWithVisibleWidth(1000))
            val fragments = component.fragmentsForTest()

            assertEquals("LongEnoughFileName.kt", fragments.first())
            assertEquals("  alpha/beta", fragments.last())
            assertFalse(fragments.last().contains("..."))
        }
    }

    @Test
    fun `renderer uses width pressure when min is zero`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathMinHiddenLevels = 0
                    commitPanelPathMaxHiddenLevels = 5
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        filePathRenderer(
                            fileName = "AddProjectMappingDialog.kt",
                            path = "alpha/beta/gamma/delta/epsilon/zeta",
                        ),
                    stateProvider = { state },
                )

            val component = render(renderer, treeWithVisibleWidth(160))
            val fragments = component.fragmentsForTest()

            assertTrue(
                fragments.last().contains("..."),
                "path fragment must shorten when the row is wider than the tree",
            )
        }
    }

    @Test
    fun `renderer uses max hidden levels when min is zero and path is wider than file name`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathMinHiddenLevels = 0
                    commitPanelPathMaxHiddenLevels = 2
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        filePathRenderer(
                            fileName = "CommitPanelAutoFitManager.kt",
                            path = SAMPLE_SOURCE_PATH,
                        ),
                    stateProvider = { state },
                )

            val component = render(renderer, treeWithVisibleWidth(1000))
            val fragments = component.fragmentsForTest()

            assertEquals(
                "  .../root/package/renderer",
                fragments.last(),
            )
        }
    }

    @Test
    fun `renderer can shorten to the configured max after a narrower candidate already fits`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathMinHiddenLevels = 0
                    commitPanelPathMaxHiddenLevels = 10
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        filePathRenderer(
                            fileName = "CommitPanelAutoFitManager.kt",
                            path = SAMPLE_SOURCE_PATH,
                        ),
                    stateProvider = { state },
                )

            val component = render(renderer, treeWithVisibleWidth(1000))

            assertEquals(
                "  ...",
                component.fragmentsForTest().last(),
            )
        }
    }

    @Test
    fun `renderer hides file path inline and exposes it as tooltip`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathDisplayMode = CommitPathDisplayMode.TOOLTIP.name
                    commitPanelPathMinHiddenLevels = 0
                    commitPanelPathMaxHiddenLevels = 10
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        filePathRenderer(
                            fileName = "CommitPanelAutoFitManager.kt",
                            path = SAMPLE_SOURCE_PATH,
                        ),
                    stateProvider = { state },
                )

            val component = render(renderer, treeWithVisibleWidth(1000))

            assertEquals(listOf("CommitPanelAutoFitManager.kt"), component.fragmentsForTest())
            assertEquals(SAMPLE_SOURCE_PATH, component.toolTipText)
        }
    }

    @Test
    fun `renderer hides root location label inline and exposes it as tooltip`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathDisplayMode = CommitPathDisplayMode.TOOLTIP.name
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        filePathRenderer(
                            fileName = "proguard-rules.pro",
                            path = "ayu-jetbrains",
                        ),
                    stateProvider = { state },
                )

            val component = render(renderer, treeWithVisibleWidth(1000))

            assertEquals(listOf("proguard-rules.pro"), component.fragmentsForTest())
            assertEquals("ayu-jetbrains", component.toolTipText)
        }
    }

    @Test
    fun `renderer keeps directory leaf inline and exposes full directory path as tooltip`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathDisplayMode = CommitPathDisplayMode.TOOLTIP.name
                    commitPanelPathMinHiddenLevels = 0
                    commitPanelPathMaxHiddenLevels = 10
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate = pathOnlyRenderer(),
                    stateProvider = { state },
                )

            val component = render(renderer, treeWithVisibleWidth(1000))

            assertEquals(listOf("renderer"), component.fragmentsForTest())
            assertEquals(DIRECTORY_ROW_PATH, component.toolTipText)
        }
    }

    @Test
    fun `renderer clears stale tooltip when delegate reuses component for non path row`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathDisplayMode = CommitPathDisplayMode.TOOLTIP.name
                }
            val reusedComponent = SimpleColoredComponent()
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        TreeCellRenderer { _, value, _, _, _, _, _ ->
                            reusedComponent.clear()
                            if ((value as DefaultMutableTreeNode).userObject == "path") {
                                reusedComponent.append(
                                    "CommitPanelAutoFitManager.kt",
                                    SimpleTextAttributes.REGULAR_ATTRIBUTES,
                                )
                                reusedComponent.append(
                                    "  $SAMPLE_SOURCE_PATH",
                                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                                )
                            } else {
                                reusedComponent.append("Changes", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                            }
                            reusedComponent
                        },
                    stateProvider = { state },
                )
            val tree = treeWithVisibleWidth(1000)

            val pathComponent =
                render(
                    renderer,
                    tree,
                    DefaultMutableTreeNode("path"),
                )
            assertEquals(SAMPLE_SOURCE_PATH, pathComponent.toolTipText)

            val nonPathComponent =
                render(
                    renderer,
                    tree,
                    DefaultMutableTreeNode("branch"),
                )

            assertEquals(listOf("Changes"), nonPathComponent.fragmentsForTest())
            assertNull(nonPathComponent.toolTipText)
        }
    }

    @Test
    fun `renderer shortens directory-only rows against their last segment`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathMinHiddenLevels = 0
                    commitPanelPathMaxHiddenLevels = 2
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate = pathOnlyRenderer(),
                    stateProvider = { state },
                )

            val component = render(renderer, treeWithVisibleWidth(1000))

            assertEquals(
                listOf(".../subsystem/renderer"),
                component.fragmentsForTest(),
            )
        }
    }

    @Test
    fun `renderer propagates tooltip to nested commit cell renderer root`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathDisplayMode = CommitPathDisplayMode.TOOLTIP.name
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        nestedFilePathRenderer(
                            path = "alpha/beta/gamma/delta",
                        ),
                    stateProvider = { state },
                )

            val component = renderComponent(renderer, treeWithVisibleWidth(1000)) as JPanel
            val textRenderer = component.simpleColoredChildForTest()

            assertEquals("alpha/beta/gamma/delta", component.toolTipText)
            assertEquals("alpha/beta/gamma/delta", textRenderer?.toolTipText)
        }
    }

    @Test
    fun `renderer shortens path inside nested commit cell renderer panel`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathMinHiddenLevels = 0
                    commitPanelPathMaxHiddenLevels = 5
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        nestedFilePathRenderer(
                            path = "alpha/beta/gamma/delta/epsilon/zeta",
                        ),
                    stateProvider = { state },
                )

            val component = renderComponent(renderer, treeWithVisibleWidth(160))
            val textRenderer = component.simpleColoredChildForTest()
            val fragments = textRenderer?.fragmentsForTest().orEmpty()

            assertTrue(
                fragments.last().contains("..."),
                "nested commit text renderer path must shorten under width pressure",
            )
        }
    }

    @Test
    fun `renderer does not query row bounds while measuring a row`() {
        SwingUtilities.invokeAndWait {
            val state =
                AyuIslandsState().apply {
                    commitPanelWidthMode = PanelWidthMode.FIXED.name
                    commitPanelPathMinHiddenLevels = 0
                    commitPanelPathMaxHiddenLevels = 5
                }
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        filePathRenderer(
                            fileName = "AddProjectMappingDialog.kt",
                            path = "alpha/beta/gamma/delta/epsilon/zeta",
                        ),
                    stateProvider = { state },
                )

            val component = render(renderer, treeRejectingRowBounds())
            val fragments = component.fragmentsForTest()

            assertTrue(fragments.last().contains("..."))
        }
    }

    @Test
    fun `renderer serves fallback text when the delegate hits a lock-prohibited context`() {
        SwingUtilities.invokeAndWait {
            val renderer =
                CommitPathShorteningRenderer(
                    delegate = TreeCellRenderer { _, _, _, _, _, _, _ -> throw FakeLockAccessDisallowed() },
                    stateProvider = { AyuIslandsState() },
                )

            val component = render(renderer, treeWithVisibleWidth(260), value = "ChangeNode.txt")

            assertEquals(listOf("ChangeNode.txt"), component.fragmentsForTest())
        }
    }

    @Test
    fun `renderer serves fallback text when the lock error is wrapped as a cause`() {
        SwingUtilities.invokeAndWait {
            val renderer =
                CommitPathShorteningRenderer(
                    delegate =
                        TreeCellRenderer { _, _, _, _, _, _, _ ->
                            throw IllegalStateException("render failed", FakeLockAccessDisallowed())
                        },
                    stateProvider = { AyuIslandsState() },
                )

            val component = render(renderer, treeWithVisibleWidth(260), value = "WrappedNode.txt")

            assertEquals(listOf("WrappedNode.txt"), component.fragmentsForTest())
        }
    }

    @Test
    fun `renderer rethrows unrelated delegate failures`() {
        SwingUtilities.invokeAndWait {
            val renderer =
                CommitPathShorteningRenderer(
                    delegate = TreeCellRenderer { _, _, _, _, _, _, _ -> throw IllegalStateException("boom") },
                    stateProvider = { AyuIslandsState() },
                )

            assertFailsWith<IllegalStateException> {
                renderComponent(renderer, treeWithVisibleWidth(260))
            }
        }
    }

    /**
     * Stand-in for the platform's `ThreadingSupport$LockAccessDisallowed`,
     * which is not on the 2025.1 compile classpath — the production guard
     * matches by class-name suffix, which this fake's name satisfies too.
     */
    private class FakeLockAccessDisallowed : IllegalStateException("lock guard test double")

    private fun render(
        renderer: TreeCellRenderer,
        tree: JTree,
        value: Any? = DefaultMutableTreeNode("file"),
    ): SimpleColoredComponent = renderComponent(renderer, tree, value) as SimpleColoredComponent

    private fun renderComponent(
        renderer: TreeCellRenderer,
        tree: JTree,
        value: Any? = DefaultMutableTreeNode("file"),
    ): Component {
        val isSelected = false
        val isExpanded = false
        val isLeaf = true
        val row = 0
        val hasFocus = false
        return renderer.getTreeCellRendererComponent(
            tree,
            value,
            isSelected,
            isExpanded,
            isLeaf,
            row,
            hasFocus,
        )
    }

    private fun filePathRenderer(
        fileName: String,
        path: String,
    ): TreeCellRenderer =
        TreeCellRenderer { _, _, _, _, _, _, _ ->
            SimpleColoredComponent().apply {
                append(fileName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  $path", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

    private fun nestedFilePathRenderer(path: String): TreeCellRenderer =
        TreeCellRenderer { _, _, _, _, _, _, _ ->
            JPanel(BorderLayout()).apply {
                add(
                    SimpleColoredComponent().apply {
                        append("AddProjectMappingDialog.kt", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append("  $path", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    },
                    BorderLayout.CENTER,
                )
            }
        }

    private fun pathOnlyRenderer(): TreeCellRenderer =
        TreeCellRenderer { _, _, _, _, _, _, _ ->
            SimpleColoredComponent().apply {
                append(DIRECTORY_ROW_PATH, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }

    private fun treeWithVisibleWidth(width: Int): JTree =
        JTree(DefaultMutableTreeNode("root")).apply {
            setSize(width, 120)
            visibleRowCount = 1
        }

    private fun treeRejectingRowBounds(): JTree =
        object : JTree(DefaultMutableTreeNode("root")) {
            override fun getRowBounds(row: Int): Rectangle = throw AssertionError("unexpected getRowBounds")
        }.apply {
            setSize(160, 120)
            visibleRowCount = 1
        }

    private fun Component.simpleColoredChildForTest(): SimpleColoredComponent? {
        if (this is SimpleColoredComponent) return this
        if (this !is Container) return null

        for (child in components) {
            val found = child.simpleColoredChildForTest()
            if (found != null) return found
        }
        return null
    }

    private fun SimpleColoredComponent.fragmentsForTest(): List<String> {
        val fragments = mutableListOf<String>()
        val iterator = iterator()
        while (iterator.hasNext()) {
            iterator.next()
            fragments.add(iterator.fragment)
        }
        return fragments
    }
}
