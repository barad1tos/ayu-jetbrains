package dev.ayuislands.integration

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.UI
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.commitpanel.CommitPathShorteningRenderer
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.CommitPathDisplayMode
import dev.ayuislands.settings.PanelWidthMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.tree.TreeCellRenderer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommitPathRendererNativeTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `public preparation keeps actual Change accessibility without native callback work`() =
        runBlocking {
            val fixture = withContext(Dispatchers.EDT) { CommitPathRendererFixture(lockTaking = true) }
            try {
                val native =
                    withContext(Dispatchers.EDT) {
                        fixture.accessibility(fixture.renderPlatform()) to fixture.accessibility()
                    }
                var calls = 0
                val renderer =
                    withContext(Dispatchers.EDT) {
                        val original = fixture.platformRenderer
                        val counting =
                            TreeCellRenderer { tree, value, selected, expanded, leaf, row, focused ->
                                calls++
                                original.getTreeCellRendererComponent(
                                    tree,
                                    value,
                                    selected,
                                    expanded,
                                    leaf,
                                    row,
                                    focused,
                                )
                            }
                        fixture.useRenderer(counting)
                        prepared(fixture, counting)
                    }
                val before = withContext(Dispatchers.EDT) { fixture.treeState() }
                val preparedCalls = calls
                withContext(Dispatchers.UI) {
                    val component = fixture.renderWrapped()
                    val accessible = fixture.accessibility(component)
                    assertEquals(native.first.role, accessible.role)
                    assertEquals(native.first.states, accessible.states)
                    val row = fixture.accessibility()
                    assertEquals(native.second.role, row.role)
                    assertEquals(native.second.states, row.states)
                    assertTrue(row.name.contains("File.kt"))
                    assertTrue(fixture.checkbox(component).isSelected)
                    fixture.paint(component)
                    assertEquals(preparedCalls, calls)
                    assertEquals(before, fixture.treeState())
                    assertFalse(renderer.isStale)
                }
                withContext(Dispatchers.EDT) { renderer.close() }
            } finally {
                withContext(Dispatchers.EDT) { fixture.close() }
            }
        }

    @Test
    fun `stale presentation never paints the old checkbox over current native inclusion`() =
        runBlocking {
            val fixture = withContext(Dispatchers.EDT) { CommitPathRendererFixture(lockTaking = true) }
            try {
                val renderer = withContext(Dispatchers.EDT) { prepared(fixture) }
                val checked = withContext(Dispatchers.UI) { checkboxPixels(fixture, fixture.renderWrapped()) }
                val nativeUnchecked =
                    withContext(Dispatchers.EDT) {
                        fixture.clickCheckbox()
                        assertTrue(fixture.tree.includedSet.isEmpty())
                        renderer.invalidate()
                        checkboxPixels(fixture, fixture.renderPlatform())
                    }
                withContext(Dispatchers.UI) {
                    val component = fixture.renderWrapped()
                    assertFalse(fixture.checkbox(component).isSelected)
                    assertNotEquals(checked, nativeUnchecked)
                    assertEquals(nativeUnchecked, checkboxPixels(fixture, component))
                    assertTrue(renderer.isStale)
                }
                withContext(Dispatchers.EDT) { renderer.close() }
            } finally {
                withContext(Dispatchers.EDT) { fixture.close() }
            }
        }

    @Test
    fun `invalidated unselected hover disappears until preparation and keeps original native action`() =
        runBlocking {
            val fixture = withContext(Dispatchers.EDT) { CommitPathRendererFixture() }
            try {
                val renderer =
                    withContext(Dispatchers.EDT) {
                        fixture.tree.clearSelection()
                        prepared(fixture)
                    }
                for (hovered in listOf(true, false, true)) {
                    withContext(Dispatchers.EDT) {
                        if (hovered) fixture.moveHover() else fixture.exitHover()
                        renderer.invalidate()
                    }
                    withContext(Dispatchers.UI) { assertEquals(0, greenPixels(unselected(fixture, renderer))) }
                    withContext(Dispatchers.EDT) { assertTrue(renderer.prepare(fixture.tree)) }
                    withContext(Dispatchers.UI) {
                        assertEquals(hovered, greenPixels(unselected(fixture, renderer)) > 0)
                    }
                }
                withContext(Dispatchers.EDT) {
                    fixture.clickHoverAction()
                    assertEquals(1, fixture.hoverInvocations)
                    assertSame(fixture.rowNode, fixture.lastInvokedNode)
                    renderer.close()
                }
            } finally {
                withContext(Dispatchers.EDT) { fixture.close() }
            }
        }

    @Test
    fun `preparation captures target scale and replay keeps logical hover geometry`() =
        runBlocking {
            val fixture = withContext(Dispatchers.EDT) { CommitPathRendererFixture() }
            try {
                val renderer =
                    withContext(Dispatchers.EDT) {
                        fixture.setScale(1.0)
                        prepared(fixture)
                    }
                val first = withContext(Dispatchers.UI) { greenPixels(fixture.renderWrapped()) }
                withContext(Dispatchers.EDT) {
                    fixture.setScale(2.0)
                    renderer.invalidate()
                    fixture.hoverIcon.paintScales.clear()
                    assertTrue(renderer.prepare(fixture.tree))
                    assertTrue(fixture.hoverIcon.paintScales.any { it == 2.0 })
                }
                val paints = fixture.hoverIcon.paints
                withContext(Dispatchers.UI) {
                    assertEquals(first * 4, greenPixels(fixture.renderWrapped(), scale = 2))
                    assertEquals(paints, fixture.hoverIcon.paints)
                    assertFalse(renderer.isStale)
                }
                withContext(Dispatchers.EDT) { renderer.close() }
            } finally {
                withContext(Dispatchers.EDT) { fixture.close() }
            }
        }

    private fun prepared(
        fixture: CommitPathRendererFixture,
        original: TreeCellRenderer = fixture.platformRenderer,
    ): CommitPathShorteningRenderer {
        val state =
            AyuIslandsState().apply {
                commitPanelWidthMode = PanelWidthMode.FIXED.name
                commitPanelPathDisplayMode = CommitPathDisplayMode.INLINE.name
                commitPanelPathMinHiddenLevels = 2
                commitPanelPathMaxHiddenLevels = 2
            }
        return CommitPathShorteningRenderer(original, {
            dev.ayuislands.commitpanel.CommitPathStyle
                .capture(state)
        }, canApply = { true }, onInvalidated = {}).also {
            assertTrue(it.prepare(fixture.tree))
            fixture.useRenderer(it)
        }
    }

    private fun unselected(
        fixture: CommitPathRendererFixture,
        renderer: CommitPathShorteningRenderer,
    ): JComponent =
        renderer.getTreeCellRendererComponent(
            fixture.tree,
            fixture.rowNode,
            selected = false,
            expanded = false,
            leaf = true,
            row = 0,
            hasFocus = false,
        ) as JComponent

    private fun checkboxPixels(
        fixture: CommitPathRendererFixture,
        component: JComponent,
    ): List<Int> {
        val image = pixels(component)
        val bounds = fixture.checkbox(component).bounds
        return (bounds.x until bounds.x + bounds.width).flatMap { x ->
            (bounds.y until bounds.y + bounds.height).map { y -> image.getRGB(x, y) }
        }
    }

    private fun greenPixels(
        component: JComponent,
        scale: Int = 1,
    ): Int {
        val image = pixels(component, scale)
        return (0 until image.width).sumOf { x ->
            (0 until image.height).count { y -> image.getRGB(x, y) == Color.GREEN.rgb }
        }
    }

    private fun pixels(
        component: JComponent,
        scale: Int = 1,
    ): BufferedImage {
        component.setSize(260, 32)
        component.doLayout()
        val image = BufferedImage(260 * scale, 32 * scale, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.scale(scale.toDouble(), scale.toDouble())
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    @Test
    fun `public accessibility walk retains native checkbox state without acquiring a model lock`() =
        runBlocking {
            val fixture = withContext(Dispatchers.EDT) { CommitPathRendererFixture() }
            try {
                val before = withContext(Dispatchers.EDT) { fixture.treeState() }
                val (nativeRenderer, nativePresentation) =
                    withContext(Dispatchers.EDT) {
                        val component = fixture.renderPlatform()
                        component to observeRenderer(fixture, component)
                    }
                val native =
                    withContext(Dispatchers.UI) {
                        val component = fixture.renderPlatform()
                        assertSame(nativeRenderer, component)
                        assertEquals(nativePresentation, observeRenderer(fixture, component))
                        fixture.accessibility()
                    }
                val wrappedPresentation =
                    withContext(Dispatchers.EDT) {
                        fixture.useWrappedRenderer(CommitPathDisplayMode.TOOLTIP)
                        val component = fixture.renderWrapped()
                        fixture.accessibility(component)
                    }
                withContext(Dispatchers.UI) {
                    val rendered = fixture.renderWrapped()
                    val checkbox = fixture.checkbox(rendered)
                    assertTrue(checkbox.isVisible && checkbox.isSelected)
                    assertEquals(nativePresentation.role, wrappedPresentation.role)
                    assertEquals(nativePresentation.states, wrappedPresentation.states)
                    assertEquals(wrappedPresentation, fixture.accessibility(rendered))
                    val wrapped = fixture.accessibility()

                    assertEquals(native.role, wrapped.role)
                    assertEquals(native.states, wrapped.states)
                    assertTrue(wrapped.name.contains(CommitPathRendererFixture.FILE_NAME))
                    assertFalse(wrapped.name.contains(CommitPathRendererFixture.PATH))
                    assertEquals(before, fixture.treeState())
                }
            } finally {
                withContext(Dispatchers.EDT) { fixture.close() }
            }
        }

    @Test
    fun `prepared native presentation preserves checkbox selection hover painting and both path modes`() =
        runBlocking {
            withContext(Dispatchers.EDT) {
                CommitPathRendererFixture().use { fixture ->
                    val before = fixture.treeState()
                    val native = fixture.renderPlatform()
                    val accessible = native.accessibleContext
                    val checkbox = fixture.checkbox(native)
                    assertTrue(checkbox.isVisible && checkbox.isSelected)
                    val nativeRole = accessible.accessibleRole
                    val nativeStates = accessible.accessibleStateSet.toArray().toSet()

                    for (mode in CommitPathDisplayMode.entries) {
                        fixture.useWrappedRenderer(mode)
                        fixture.delegateCalls = 0

                        val rendered = fixture.renderWrapped()

                        assertEquals(0, fixture.delegateCalls)
                        assertEquals(checkbox.isSelected, fixture.checkbox(rendered).isSelected)
                        assertEquals(nativeRole, rendered.accessibleContext.accessibleRole)
                        assertEquals(
                            nativeStates,
                            rendered.accessibleContext.accessibleStateSet
                                .toArray()
                                .toSet(),
                        )
                        val text = fixture.text(rendered)
                        assertSame(fixture.fileIcon, text.icon)
                        when (mode) {
                            CommitPathDisplayMode.INLINE -> {
                                assertEquals(listOf("File.kt", "  .../gamma/delta"), fixture.fragments(text))
                                assertEquals(null, text.toolTipText)
                            }

                            CommitPathDisplayMode.TOOLTIP -> {
                                assertEquals(listOf("File.kt"), fixture.fragments(text))
                                assertEquals(CommitPathRendererFixture.PATH, text.toolTipText)
                                assertEquals(CommitPathRendererFixture.PATH, rendered.toolTipText)
                            }
                        }
                        val iconPaints = fixture.hoverIcon.paints
                        assertTrue(greenPixels(rendered) > 0)
                        assertEquals(iconPaints, fixture.hoverIcon.paints)
                        assertEquals(0, fixture.delegateCalls, "Painting must not delegate to the renderer again")
                        assertEquals(before, fixture.treeState())
                    }
                }
            }
        }

    private fun observeRenderer(
        fixture: CommitPathRendererFixture,
        component: JComponent,
    ): RendererAccessibility {
        val presentation = fixture.accessibility(component)
        val checkbox = fixture.checkbox(component)
        assertTrue(checkbox.isVisible && checkbox.isSelected)
        return presentation
    }
}
