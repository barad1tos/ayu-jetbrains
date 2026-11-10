package dev.ayuislands.commitpanel

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.CommitPathDisplayMode
import dev.ayuislands.settings.PanelWidthMode
import java.awt.Component
import java.awt.Container
import javax.accessibility.AccessibleContext
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommitPresentationTest {
    @Test
    fun `cold rendering requests preparation once without invoking model presentation`() =
        onEdt {
            Fixture().use { fixture ->
                fixture.tree.cellRenderer = fixture.renderer
                val calls = fixture.delegateCalls

                repeat(2) {
                    val text = fixture.render(fixture.first)
                    assertEquals(listOf("Loading change…"), text.fragments())
                    assertNull(text.toolTipText)
                }

                assertEquals(calls, fixture.delegateCalls)
                assertEquals(1, fixture.requests)
                assertTrue(fixture.renderer.isStale)
            }
        }

    @Test
    fun `prepared reused native component keeps each original node presentation and tags`() =
        onEdt {
            Fixture().use { fixture ->
                fixture.prepare()
                val calls = fixture.delegateCalls
                val order = fixture.root.children().toList()

                for (node in listOf(fixture.first, fixture.second, fixture.first)) {
                    val text = fixture.render(node)
                    assertEquals(listOf("File.kt", "  .../${node.userObject}"), text.fragments())
                    assertSame(fixture.tag, text.getFragmentTag(0))
                    assertEquals(
                        SimpleTextAttributes.LINK_ATTRIBUTES,
                        text.iterator().let {
                            it.next()
                            it.textAttributes
                        },
                    )
                }

                assertEquals(calls, fixture.delegateCalls)
                assertEquals(order, fixture.root.children().toList())
                assertFalse(fixture.renderer.isStale)
            }
        }

    @Test
    fun `width font and settings invalidation retain matching text until explicit refresh`() =
        onEdt {
            Fixture().use { fixture ->
                fixture.prepare()
                val before = fixture.render(fixture.first).fragments()
                val calls = fixture.delegateCalls
                fixture.tree.setSize(120, 100)
                fixture.tree.font = fixture.tree.font.deriveFont(20f)
                fixture.state.commitPanelPathDisplayMode = CommitPathDisplayMode.TOOLTIP.name
                fixture.renderer.invalidate()
                fixture.renderer.invalidate()

                assertEquals(before, fixture.render(fixture.first).fragments())
                assertEquals(calls, fixture.delegateCalls)
                assertEquals(1, fixture.requests)
                assertTrue(fixture.renderer.isStale)
                var announcements = 0
                fixture.tree.accessibleContext.addPropertyChangeListener {
                    if (it.propertyName == AccessibleContext.ACCESSIBLE_VISIBLE_DATA_PROPERTY) announcements++
                }

                fixture.prepare()

                val refreshed = fixture.render(fixture.first)
                assertEquals(listOf("File.kt"), refreshed.fragments())
                assertEquals("alpha/beta/first", refreshed.toolTipText)
                assertFalse(fixture.renderer.isStale)
                assertTrue(announcements > 0, "New prepared presentation must notify accessibility")
            }
        }

    @Test
    fun `replacement model never receives presentation from the old node at the same row`() =
        onEdt {
            Fixture().use { fixture ->
                fixture.prepare()
                val replacement = DefaultMutableTreeNode("replacement")
                val nextRoot = DefaultMutableTreeNode("root").apply { add(replacement) }
                fixture.tree.model = DefaultTreeModel(nextRoot)
                fixture.tree.expandPath(TreePath(nextRoot.path))
                val calls = fixture.delegateCalls

                assertEquals(listOf("Loading change…"), fixture.render(replacement).fragments())
                assertEquals(calls, fixture.delegateCalls)
                assertTrue(fixture.renderer.isStale)
                fixture.prepare()
                assertEquals(listOf("File.kt", "  .../replacement"), fixture.render(replacement).fragments())
            }
        }

    @Test
    fun `foreign renderer and closed owner reject late preparation without native calls`() =
        onEdt {
            Fixture().use { fixture ->
                fixture.prepare()
                val foreign = TreeCellRenderer { _, _, _, _, _, _, _ -> SimpleColoredComponent() }
                fixture.tree.cellRenderer = foreign
                fixture.renderer.invalidate()
                val calls = fixture.delegateCalls

                assertFalse(fixture.renderer.prepare(fixture.tree))
                assertSame(foreign, fixture.tree.cellRenderer)
                fixture.renderer.close()
                assertFalse(fixture.renderer.prepare(fixture.tree))
                assertSame(foreign, fixture.tree.cellRenderer)
                assertEquals(calls, fixture.delegateCalls)
            }
        }

    @Test
    fun `tagged path retains its native hit geometry instead of shortening its link`() =
        onEdt {
            Fixture(tagPath = true).use { fixture ->
                fixture.prepare()
                val text = fixture.render(fixture.first)

                assertEquals(listOf("File.kt", "  alpha/beta/first"), text.fragments())
                assertSame(fixture.tag, text.getFragmentTag(1))
            }
        }

    @Test
    fun `preparation drops a pending row removed from the current model`() =
        onEdt {
            Fixture().use { fixture ->
                fixture.tree.cellRenderer = fixture.renderer
                assertEquals(listOf("Loading change…"), fixture.render(fixture.first).fragments())
                (fixture.tree.model as DefaultTreeModel).removeNodeFromParent(fixture.first)
                fixture.delegateNodes.clear()

                fixture.prepare()

                assertFalse(fixture.delegateNodes.any { it === fixture.first })
                assertEquals(listOf("File.kt", "  .../second"), fixture.render(fixture.second).fragments())
            }
        }

    @Test
    fun `prewarming accepts the exact original wrapper without installing the candidate`() =
        onEdt {
            Fixture().use { fixture ->
                fixture.prepare()
                val original = fixture.renderer
                CommitPathShorteningRenderer(
                    original.delegate,
                    { CommitPathStyle.capture(fixture.state) },
                    canApply = { true },
                ).use { candidate ->
                    assertFalse(candidate.prepare(fixture.tree))

                    assertTrue(candidate.prepare(fixture.tree, expectedRenderer = original))

                    assertSame(original, fixture.tree.cellRenderer)
                    assertFalse(candidate.isStale)
                    val foreign = TreeCellRenderer { _, _, _, _, _, _, _ -> SimpleColoredComponent() }
                    fixture.tree.cellRenderer = foreign
                    val before = fixture.delegateCalls
                    assertFalse(candidate.prepare(fixture.tree, expectedRenderer = original))
                    assertEquals(before, fixture.delegateCalls)
                    assertSame(foreign, fixture.tree.cellRenderer)
                }
            }
        }

    @Test
    fun `prewarming rejects a renderer replaced during native capture`() =
        onEdt {
            Fixture().use { fixture ->
                fixture.prepare()
                val original = fixture.renderer
                val foreign = TreeCellRenderer { _, _, _, _, _, _, _ -> SimpleColoredComponent() }
                val delegate =
                    TreeCellRenderer { tree, value, selected, expanded, leaf, row, focused ->
                        tree.cellRenderer = foreign
                        original.delegate.getTreeCellRendererComponent(
                            tree,
                            value,
                            selected,
                            expanded,
                            leaf,
                            row,
                            focused,
                        )
                    }
                CommitPathShorteningRenderer(
                    delegate,
                    { CommitPathStyle.capture(fixture.state) },
                    canApply = { true },
                ).use { candidate ->
                    assertFalse(candidate.prepare(fixture.tree, expectedRenderer = original))
                    assertSame(foreign, fixture.tree.cellRenderer)
                    assertTrue(candidate.isStale)
                }
            }
        }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private class Fixture(
        tagPath: Boolean = false,
    ) : AutoCloseable {
        val first = DefaultMutableTreeNode("first")
        val second = DefaultMutableTreeNode("second")
        val root =
            DefaultMutableTreeNode("root").apply {
                add(first)
                add(second)
            }
        val tree =
            JTree(DefaultTreeModel(root)).apply {
                isRootVisible = false
                setSize(260, 100)
                expandPath(TreePath(root.path))
            }
        val state =
            AyuIslandsState().apply {
                commitPanelWidthMode = PanelWidthMode.FIXED.name
                commitPanelPathDisplayMode = CommitPathDisplayMode.INLINE.name
                commitPanelPathMinHiddenLevels = 2
                commitPanelPathMaxHiddenLevels = 2
            }
        val tag = Runnable { error("Rendering must never invoke a tag") }
        var requests = 0
        var delegateCalls = 0
        val delegateNodes = mutableListOf<Any?>()
        private val native = SimpleColoredComponent()
        private val delegate =
            TreeCellRenderer { _, value, _, _, _, _, _ ->
                delegateCalls++
                delegateNodes += value
                native.clear()
                native.append("File.kt", SimpleTextAttributes.LINK_ATTRIBUTES, if (tagPath) null else tag)
                native.append(
                    "  alpha/beta/${(value as DefaultMutableTreeNode).userObject}",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    if (tagPath) tag else null,
                )
                native
            }
        val renderer =
            CommitPathShorteningRenderer(
                delegate,
                { CommitPathStyle.capture(state) },
                canApply = { true },
                onInvalidated = { requests++ },
            )

        init {
            tree.cellRenderer = delegate
        }

        fun prepare() {
            assertTrue(renderer.prepare(tree))
            tree.cellRenderer = renderer
        }

        fun render(node: DefaultMutableTreeNode): SimpleColoredComponent {
            val rendered =
                renderer.getTreeCellRendererComponent(
                    tree,
                    node,
                    selected = false,
                    expanded = false,
                    leaf = true,
                    row = tree.getRowForPath(TreePath(node.path)),
                    hasFocus = false,
                )
            return descendants(rendered).filterIsInstance<SimpleColoredComponent>().single()
        }

        override fun close() = renderer.close()

        private fun descendants(component: Component): List<Component> =
            listOf(component) + if (component is Container) component.components.flatMap(::descendants) else emptyList()
    }

    private fun SimpleColoredComponent.fragments(): List<String> =
        buildList {
            val iterator = this@fragments.iterator()
            while (iterator.hasNext()) {
                iterator.next()
                add(iterator.fragment)
            }
        }
}
