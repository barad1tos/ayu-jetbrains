package dev.ayuislands.accent

import com.intellij.openapi.progress.ProcessCanceledException
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.awt.Color
import java.awt.Window
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChromePeerRollbackTest {
    private class ChromePanel : JPanel()

    private class CallbackPanel : JPanel() {
        var afterWrite: (() -> Unit)? = null

        override fun setBackground(bg: Color?) {
            super.setBackground(bg)
            afterWrite?.invoke()
        }
    }

    private class FailingPanel : JPanel() {
        var failOn: Color? = null

        override fun setBackground(bg: Color?) {
            check(bg !== failOn || failOn == null) { "peer restore failed before mutation" }
            super.setBackground(bg)
        }
    }

    @Test
    fun `abort restores the exact previous peer background`() =
        withScope { scope ->
            val peer = ChromePanel().apply { background = Color.BLUE }
            val root = JPanel().apply { add(peer) }

            scope.runStep { refresh(root, Color.ORANGE) }
            assertSame(expected = Color.ORANGE, actual = peer.background)
            scope.restore()

            assertSame(expected = Color.BLUE, actual = peer.background)
        }

    @Test
    fun `abort restores inheritance instead of pinning a resolved parent color`() =
        withScope { scope ->
            val peer = ChromePanel().apply { background = null }
            val root =
                JPanel().apply {
                    background = Color.BLUE
                    add(peer)
                }

            scope.runStep { refresh(root, Color.ORANGE) }
            root.background = Color.GREEN
            scope.restore()

            assertFalse(peer.isBackgroundSet)
            assertSame(expected = Color.GREEN, actual = peer.background)
        }

    @Test
    fun `abort of clear restores a previously explicit peer background`() =
        withScope { scope ->
            val peer = ChromePanel().apply { background = Color.BLUE }
            val root = JPanel().apply { add(peer) }

            scope.runStep { LiveChromeRefresher.clearOnTree(root, ClassFqn.require(ChromePanel::class.java.name)) }
            assertFalse(peer.isBackgroundSet)
            scope.restore()

            assertTrue(peer.isBackgroundSet)
            assertSame(expected = Color.BLUE, actual = peer.background)
        }

    @Test
    fun `foreign peer color survives rollback`() =
        withScope { scope ->
            val peer = ChromePanel().apply { background = Color.BLUE }
            val root = JPanel().apply { add(peer) }

            scope.runStep { refresh(root, Color.ORANGE) }
            peer.background = Color.GREEN
            scope.restore()

            assertSame(expected = Color.GREEN, actual = peer.background)
        }

    @Test
    fun `moved peer does not receive a stale rollback`() =
        withScope { scope ->
            val peer = ChromePanel().apply { background = Color.BLUE }
            val root = JPanel().apply { add(peer) }
            val replacement = JPanel()

            scope.runStep { refresh(root, Color.ORANGE) }
            replacement.add(peer)
            scope.restore()

            assertSame(expected = replacement, actual = peer.parent)
            assertSame(expected = Color.ORANGE, actual = peer.background)
        }

    @Test
    fun `child rollback keeps an earlier successful peer tint`() =
        withScope { scope ->
            val peer = ChromePanel().apply { background = Color.BLUE }
            val root = JPanel().apply { add(peer) }
            scope.runStep { refresh(root, Color.ORANGE) }

            scope.runStep {
                val rollback = scope.elementRollback()
                refresh(root, Color.PINK)
                rollback()
            }
            assertSame(expected = Color.ORANGE, actual = peer.background)
            scope.commit()

            assertSame(expected = Color.ORANGE, actual = peer.background)
        }

    @Test
    fun `failed newer rollback blocks the older receipt until both can recover`() =
        withScope { scope ->
            val peer = FailingPanel().apply { background = Color.BLUE }
            val root = JPanel().apply { add(peer) }
            val peerClass = ClassFqn.require(FailingPanel::class.java.name)
            scope.runStep { LiveChromeRefresher.refreshOnTree(root, peerClass, Color.ORANGE) }
            scope.runStep {
                val rollback = scope.elementRollback()
                LiveChromeRefresher.refreshOnTree(root, peerClass, Color.PINK)
                peer.failOn = Color.ORANGE
                assertFailsWith<IllegalStateException> { rollback() }
            }
            assertFailsWith<IllegalStateException> { scope.restore() }
            assertSame(expected = Color.PINK, actual = peer.background)
            peer.failOn = null

            scope.restore()

            assertSame(expected = Color.BLUE, actual = peer.background)
        }

    @Test
    fun `equal RGB foreign callback permanently relinquishes peer ownership`() =
        withScope { scope ->
            val peer = CallbackPanel().apply { background = Color.BLUE }
            val root = JPanel().apply { add(peer) }
            val peerClass = ClassFqn.require(CallbackPanel::class.java.name)
            val attempted = Color(0x112233)
            val foreign = Color(0x112233)
            peer.afterWrite = {
                peer.afterWrite = null
                peer.background = foreign
            }
            scope.runStep { LiveChromeRefresher.refreshOnTree(root, peerClass, attempted) }
            assertSame(expected = foreign, actual = peer.background)
            peer.background = attempted

            scope.runStep { LiveChromeRefresher.refreshOnTree(root, peerClass, Color.GREEN) }
            scope.restore()

            assertSame(expected = attempted, actual = peer.background)
        }

    @Test
    fun `peer cancellation escapes traversal and restores its completed mutation`() =
        withScope { scope ->
            val peer = CallbackPanel().apply { background = Color.BLUE }
            val root = JPanel().apply { add(peer) }
            val peerClass = ClassFqn.require(CallbackPanel::class.java.name)
            val cancellation = ProcessCanceledException()
            peer.afterWrite = {
                peer.afterWrite = null
                throw cancellation
            }

            val thrown =
                assertFailsWith<ProcessCanceledException> {
                    scope.runStep { LiveChromeRefresher.refreshOnTree(root, peerClass, Color.PINK) }
                }
            assertSame(expected = cancellation, actual = thrown)
            scope.restore()

            assertSame(expected = Color.BLUE, actual = peer.background)
        }

    @Test
    fun `pending child recovery survives commit and close until the next apply`() {
        val peer = FailingPanel().apply { background = Color.BLUE }
        val root = JPanel().apply { add(peer) }
        val peerClass = ClassFqn.require(FailingPanel::class.java.name)
        withScope { first ->
            first.runStep { LiveChromeRefresher.refreshOnTree(root, peerClass, Color.ORANGE) }
            first.runStep {
                val rollback = first.elementRollback()
                LiveChromeRefresher.refreshOnTree(root, peerClass, Color.PINK)
                peer.failOn = Color.ORANGE
                assertFailsWith<IllegalStateException> { rollback() }
            }
            first.commit()
            assertSame(expected = Color.PINK, actual = peer.background)
        }
        peer.failOn = null

        withScope { next ->
            next.runStep { LiveChromeRefresher.refreshOnTree(root, peerClass, Color.GREEN) }
            next.restore()
        }

        assertSame(expected = Color.ORANGE, actual = peer.background)
    }

    @Test
    fun `standalone peer write cannot bypass its unresolved recovery`() {
        val peer = interruptedPeer()
        try {
            SwingUtilities.invokeAndWait {
                assertFailsWith<IllegalStateException> { ChromePeerOverrides.put(peer, Color.GREEN) }
                assertSame(Color.ORANGE, peer.background)
            }
        } finally {
            settlePeer(peer)
        }
    }

    @Test
    fun `standalone tree cleanup propagates pending peer recovery failure`() {
        val root = JPanel()
        val peer = interruptedPeer(root)
        try {
            SwingUtilities.invokeAndWait {
                assertFailsWith<IllegalStateException> {
                    LiveChromeRefresher.clearOnTree(root, ClassFqn.require(FailingPanel::class.java.name))
                }
                assertSame(Color.ORANGE, peer.background)
            }
        } finally {
            settlePeer(peer)
        }
    }

    @Test
    fun `standalone window cleanup propagates pending peer recovery failure`() {
        val root = JPanel()
        val peer = interruptedPeer(root)
        val window =
            mockk<Window> {
                every { isShowing } returns true
                every { components } returns arrayOf(root)
            }
        mockkStatic(Window::class)
        every { Window.getWindows() } returns arrayOf(window)
        try {
            SwingUtilities.invokeAndWait {
                assertFailsWith<IllegalStateException> {
                    LiveChromeRefresher.clear(ChromeTarget.ByClassName(ClassFqn.require(FailingPanel::class.java.name)))
                }
                assertSame(Color.ORANGE, peer.background)
            }
        } finally {
            unmockkStatic(Window::class)
            settlePeer(peer)
        }
    }

    @Test
    fun `standalone peer write settles its previous receipt before changing the property`() {
        val peer = interruptedPeer()
        peer.failOn = null
        val writes = mutableListOf<Color?>()
        peer.addPropertyChangeListener("background") { writes += it.newValue as Color? }
        try {
            SwingUtilities.invokeAndWait { ChromePeerOverrides.put(peer, Color.GREEN) }

            assertEquals(listOf(Color.BLUE, Color.GREEN), writes)
            assertSame(Color.GREEN, peer.background)
        } finally {
            settlePeer(peer)
        }
    }

    @Test
    fun `a failed peer recovery cannot block a different component`() {
        val interrupted = interruptedPeer()
        val independent = ChromePanel().apply { background = Color.CYAN }
        try {
            withScope { scope ->
                ChromePeerOverrides.put(independent, Color.GREEN)
                assertSame(Color.GREEN, independent.background)
                scope.restore()
                assertSame(Color.CYAN, independent.background)
            }
        } finally {
            settlePeer(interrupted)
        }
    }

    private fun interruptedPeer(parent: JPanel? = null): FailingPanel {
        val peer = FailingPanel().apply { background = Color.BLUE }
        parent?.add(peer)
        withScope { scope ->
            ChromePeerOverrides.put(peer, Color.ORANGE)
            peer.failOn = Color.BLUE
            assertFailsWith<IllegalStateException> { scope.restore() }
        }
        return peer
    }

    private fun settlePeer(peer: FailingPanel) {
        peer.failOn = null
        withScope { scope ->
            ChromePeerOverrides.put(peer, Color.BLUE)
            scope.commit()
        }
    }

    @Test
    fun `look and feel replacement invalidates peer rollback`() =
        withScope { scope ->
            val peer = ChromePanel().apply { background = Color.BLUE }
            val root = JPanel().apply { add(peer) }
            val original = UIManager.getLookAndFeel()
            try {
                scope.runStep { refresh(root, Color.ORANGE) }
                UIManager.setLookAndFeel(MetalLookAndFeel())
                scope.restore()

                assertSame(expected = Color.ORANGE, actual = peer.background)
            } finally {
                UIManager.setLookAndFeel(original)
            }
        }

    private fun refresh(
        root: JPanel,
        color: Color,
    ) {
        LiveChromeRefresher.refreshOnTree(root, ClassFqn.require(ChromePanel::class.java.name), color)
    }

    private fun withScope(action: (AccentApplyScope) -> Unit) {
        SwingUtilities.invokeAndWait {
            val scope = AccentApplyScope()
            scope.open()
            try {
                action(scope)
            } finally {
                scope.close()
            }
        }
    }
}
