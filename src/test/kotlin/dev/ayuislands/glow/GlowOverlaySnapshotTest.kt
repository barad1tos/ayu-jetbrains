package dev.ayuislands.glow

import com.intellij.testFramework.junit5.RunInEdt
import dev.ayuislands.settings.AyuIslandsState
import java.awt.Color
import java.awt.event.ComponentAdapter
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.HierarchyBoundsAdapter
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRootPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunInEdt
class GlowOverlaySnapshotTest {
    @Test
    fun `pane and health ownership remain local when an independent overlay is added`() {
        OverlayFixture().use { fixture ->
            val original = fixture.add("Editor")
            val snapshot = GlowOverlaySnapshot(fixture.entries, fixture.hold)
            val before = original.glassPane.captureAppearance()
            assertNull(snapshot.capturePane("Editor", before.copy()))
            val change = requireNotNull(snapshot.capturePane("Editor", before.copy(color = Color.CYAN)))
            val health = snapshot.captureFailure("Editor")
            val added = fixture.add("Terminal")
            val addedBefore = added.glassPane.captureAppearance()

            assertFailsWith<IllegalStateException> { snapshot.checkCurrent() }
            change.apply()
            assertSame(Color.CYAN, original.glassPane.glowColor)
            change.restore()
            health.prepareRestore()
            health.restore()

            assertEquals(before, original.glassPane.captureAppearance())
            assertEquals(addedBefore, added.glassPane.captureAppearance())
            assertEquals(GlowCheckpointPhase.RESTORED, health.record.phase)
        }
    }

    @Test
    fun `removal capture and precommit hide leave membership intact until cleanup`() {
        OverlayFixture().use { fixture ->
            val entry = fixture.add("Project")
            val snapshot = GlowOverlaySnapshot(fixture.entries, fixture.hold)
            val children = entry.layeredPane.components.toList()
            val listeners = entry.host.componentListeners.toList()
            val bounds = entry.host.hierarchyBoundsListeners.toList()
            val removal = snapshot.prepareRemoval("Project")
            assertFalse(removal.isComplete)
            assertEquals(children, entry.layeredPane.components.toList())
            assertEquals(listeners, entry.host.componentListeners.toList())
            val hiddenAppearance = entry.glassPane.captureAppearance().copy(isVisible = false)
            val hidden = requireNotNull(snapshot.capturePane("Project", hiddenAppearance))
            hidden.apply()
            assertSame(entry, fixture.entries["Project"])
            assertSame(entry.layeredPane, entry.glassPane.parent)
            hidden.accept()

            removal.cleanup()

            assertTrue(removal.isComplete)
            assertNull(entry.glassPane.parent)
            assertTrue(fixture.entries.isEmpty())
            assertEquals(listeners.filterNot { it === entry.componentListener }, entry.host.componentListeners.toList())
            assertEquals(
                bounds.filterNot { it === entry.hierarchyBoundsListener },
                entry.host.hierarchyBoundsListeners.toList(),
            )
            fixture.hold.release()
            removal.cleanup()
        }
    }

    @Test
    fun `removal retry observes native mutation and never repeats a completed removal callback`() {
        OverlayFixture().use { fixture ->
            val entry = fixture.add("Project")
            val removal = GlowOverlaySnapshot(fixture.entries, fixture.hold).prepareRemoval("Project")
            var removals = 0
            val failure =
                object : ContainerAdapter() {
                    override fun componentRemoved(event: ContainerEvent) {
                        if (event.child === entry.glassPane) {
                            removals++
                            error("After native removal")
                        }
                    }
                }
            entry.layeredPane.addContainerListener(failure)

            assertFailsWith<IllegalStateException> { removal.cleanup() }
            assertFalse(removal.isComplete)
            assertNull(entry.glassPane.parent)
            assertSame(entry, fixture.entries["Project"])
            removal.cleanup()
            removal.cleanup()

            assertTrue(removal.isComplete)
            assertEquals(1, removals)
            assertTrue(fixture.entries.isEmpty())
        }
    }

    @Test
    fun `old snapshot cleans orphan resources without following or erasing the replacement`() {
        OverlayFixture().use { fixture ->
            val original = fixture.add("Project")
            fixture.add("Terminal")
            val snapshot = GlowOverlaySnapshot(fixture.entries, fixture.hold)
            val removal = snapshot.prepareRemoval("Project")
            val replacement = fixture.add("Project", JRootPane(), original.host)
            val children = replacement.layeredPane.components.toList()
            val listeners = replacement.host.componentListeners.filterNot { it === original.componentListener }
            val bounds = replacement.host.hierarchyBoundsListeners.filterNot { it === original.hierarchyBoundsListener }
            val appearance = replacement.glassPane.captureAppearance()
            assertFailsWith<IllegalStateException> {
                snapshot.capturePane("Project", original.glassPane.captureAppearance().copy(color = Color.CYAN))
            }
            assertFailsWith<IllegalStateException> { snapshot.captureFailure("Project") }

            removal.cleanup()

            assertTrue(removal.isComplete)
            assertNull(original.glassPane.parent)
            assertSame(replacement, fixture.entries["Project"])
            assertEquals(listOf("Project", "Terminal"), fixture.entries.keys.toList())
            assertEquals(children, replacement.layeredPane.components.toList())
            assertEquals(listeners, replacement.host.componentListeners.toList())
            assertEquals(bounds, replacement.host.hierarchyBoundsListeners.toList())
            assertEquals(appearance, replacement.glassPane.captureAppearance())
        }
    }

    @Test
    fun `cleanup never follows an old pane reparented by a removal callback`() {
        OverlayFixture().use { fixture ->
            val entry = fixture.add("Project")
            val removal = GlowOverlaySnapshot(fixture.entries, fixture.hold).prepareRemoval("Project")
            val foreign = JRootPane().layeredPane
            var removals = 0
            entry.layeredPane.addContainerListener(
                object : ContainerAdapter() {
                    override fun componentRemoved(event: ContainerEvent) {
                        if (event.child === entry.glassPane) {
                            removals++
                            foreign.add(event.child)
                        }
                    }
                },
            )

            assertFailsWith<IllegalStateException> { removal.cleanup() }
            assertSame(foreign, entry.glassPane.parent)
            assertFailsWith<IllegalStateException> { removal.cleanup() }
            assertSame(foreign, entry.glassPane.parent)
            assertFalse(removal.isComplete)
            assertEquals(1, removals)
            foreign.remove(entry.glassPane)
            removal.cleanup()
            assertTrue(removal.isComplete)
        }
    }
}

private class OverlayFixture : AutoCloseable {
    val entries = linkedMapOf<String, GlowOverlayManager.OverlayEntry>()
    private val panes = mutableListOf<GlowGlassPane>()
    private val focus = FocusRingManager()
    val hold = GlowUpdateGate({ false }, { loadGlowRuntimeInput(AyuIslandsState()) }, focus::holdCallbacks).hold()

    fun add(
        id: String,
        root: JRootPane = JRootPane(),
        host: JComponent = JPanel(),
    ): GlowOverlayManager.OverlayEntry {
        root.contentPane.add(host)
        val pane = GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6)
        panes.add(pane)
        root.layeredPane.add(pane)
        val componentListener = object : ComponentAdapter() {}
        val boundsListener = object : HierarchyBoundsAdapter() {}
        host.addComponentListener(componentListener)
        host.addHierarchyBoundsListener(boundsListener)
        return GlowOverlayManager.OverlayEntry(pane, host, root.layeredPane, componentListener, boundsListener).also {
            entries[id] = it
        }
    }

    override fun close() {
        hold.release()
        panes.forEach(GlowGlassPane::stopAnimation)
    }
}
