package dev.ayuislands.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LoggedErrorProcessor
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import java.awt.Dimension
import java.beans.PropertyChangeListener
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorScrollbarManagerTest {
    private lateinit var project: Project
    private lateinit var editorFactory: EditorFactory
    private lateinit var realState: AyuIslandsState
    private val listenerSlot = slot<EditorFactoryListener>()

    private lateinit var scrollPane: JScrollPane
    private lateinit var editorEx: EditorEx

    @BeforeTest
    fun setUp() {
        mockkStatic(EditorFactory::class)
        mockkObject(AyuIslandsSettings.Companion)
        mockkObject(LicenseChecker)

        realState = AyuIslandsState()
        val settingsMock =
            mockk<AyuIslandsSettings> {
                every { state } returns realState
            }
        every { AyuIslandsSettings.getInstance() } returns settingsMock
        every { LicenseChecker.isLicensedOrGrace() } returns true

        project =
            mockk(relaxed = true) {
                every { isDisposed } returns false
            }

        scrollPane = JScrollPane()
        editorEx =
            mockk<EditorEx>(relaxed = true) {
                every { this@mockk.project } returns this@EditorScrollbarManagerTest.project
                every { this@mockk.scrollPane } returns this@EditorScrollbarManagerTest.scrollPane
            }

        editorFactory =
            mockk(relaxed = true) {
                every { allEditors } returns arrayOf(editorEx)
                every {
                    addEditorFactoryListener(capture(listenerSlot), any())
                } returns Unit
            }
        every { EditorFactory.getInstance() } returns editorFactory
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun createManager(): EditorScrollbarManager = EditorScrollbarManager(project)

    @Test
    fun `apply hides vertical scrollbar with zero preferred size`() {
        realState.hideEditorVScrollbar = true
        val originalSize = scrollPane.verticalScrollBar.preferredSize

        val manager = createManager()
        manager.apply()

        assertEquals(Dimension(0, 0), scrollPane.verticalScrollBar.preferredSize)
        assertNotEquals(originalSize, Dimension(0, 0))
        manager.dispose()
    }

    @Test
    fun `apply hides horizontal scrollbar with zero preferred size`() {
        realState.hideEditorHScrollbar = true
        val originalSize = scrollPane.horizontalScrollBar.preferredSize

        val manager = createManager()
        manager.apply()

        assertEquals(Dimension(0, 0), scrollPane.horizontalScrollBar.preferredSize)
        assertNotEquals(originalSize, Dimension(0, 0))
        manager.dispose()
    }

    @Test
    fun `apply restores scrollbar when setting disabled`() {
        realState.hideEditorVScrollbar = true
        val originalSize = Dimension(scrollPane.verticalScrollBar.preferredSize)

        val manager = createManager()
        manager.apply()
        assertEquals(Dimension(0, 0), scrollPane.verticalScrollBar.preferredSize)

        realState.hideEditorVScrollbar = false
        manager.apply()
        assertEquals(originalSize, scrollPane.verticalScrollBar.preferredSize)
        manager.dispose()
    }

    @Test
    fun `apply restores scrollbars when license revoked`() {
        realState.hideEditorVScrollbar = true
        realState.hideEditorHScrollbar = true
        val originalVSize = Dimension(scrollPane.verticalScrollBar.preferredSize)
        val originalHSize = Dimension(scrollPane.horizontalScrollBar.preferredSize)

        val manager = createManager()
        manager.apply()
        assertEquals(Dimension(0, 0), scrollPane.verticalScrollBar.preferredSize)
        assertEquals(Dimension(0, 0), scrollPane.horizontalScrollBar.preferredSize)

        every { LicenseChecker.isLicensedOrGrace() } returns false
        manager.apply()

        assertEquals(originalVSize, scrollPane.verticalScrollBar.preferredSize)
        assertEquals(originalHSize, scrollPane.horizontalScrollBar.preferredSize)
        manager.dispose()
    }

    @Test
    fun `apply does not hide when not licensed`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        realState.hideEditorVScrollbar = true
        val originalSize = Dimension(scrollPane.verticalScrollBar.preferredSize)

        val manager = createManager()
        manager.apply()

        assertEquals(originalSize, scrollPane.verticalScrollBar.preferredSize)
        manager.dispose()
    }

    @Test
    fun `apply skips editors from other projects`() {
        val otherProject = mockk<Project>(relaxed = true)
        val otherEditor =
            mockk<EditorEx>(relaxed = true) {
                every { project } returns otherProject
            }
        every { editorFactory.allEditors } returns arrayOf(otherEditor)

        realState.hideEditorVScrollbar = true

        val manager = createManager()
        manager.apply()
        // No crash, no patching of other project's editors
        manager.dispose()
    }

    @Test
    fun `dispose restores all patched scrollbars`() {
        realState.hideEditorVScrollbar = true
        realState.hideEditorHScrollbar = true
        val originalVSize = Dimension(scrollPane.verticalScrollBar.preferredSize)
        val originalHSize = Dimension(scrollPane.horizontalScrollBar.preferredSize)

        val manager = createManager()
        manager.apply()

        manager.dispose()

        assertEquals(originalVSize, scrollPane.verticalScrollBar.preferredSize)
        assertEquals(originalHSize, scrollPane.horizontalScrollBar.preferredSize)
    }

    @Test
    fun `hideScrollBar stores defensive copy of original size`() {
        realState.hideEditorVScrollbar = true
        val vBar = scrollPane.verticalScrollBar
        val sizeBefore = vBar.preferredSize

        val manager = createManager()
        manager.apply()

        val storedSize =
            vBar.getClientProperty("ayuIslands.originalPreferredSize") as? Dimension
        assertEquals(sizeBefore, storedSize)
        // Verify it's a defensive copy, not the same reference — the previous assertion
        // (`storedSize !== sizeBefore || sizeBefore == storedSize`) was a tautology that
        // passed regardless of whether a copy was actually made.
        assert(storedSize !== sizeBefore) {
            "Expected defensive copy of preferredSize, but stored the same reference"
        }
        manager.dispose()
    }

    @Test
    fun `restoreScrollBar clears client property`() {
        realState.hideEditorVScrollbar = true
        val vBar = scrollPane.verticalScrollBar

        val manager = createManager()
        manager.apply()
        assert(vBar.getClientProperty("ayuIslands.originalPreferredSize") != null)

        realState.hideEditorVScrollbar = false
        manager.apply()
        assertNull(vBar.getClientProperty("ayuIslands.originalPreferredSize"))
        manager.dispose()
    }

    @Test
    fun `apply re-hides scrollbar after platform reset simulation`() {
        // Regression guard for the stale-flag bug (commit 64833a0): a previous version of
        // applyToEditor gated hideScrollBar on `!patched.verticalHidden`. After startup the
        // flag would read "true", the platform's LAF walk would reset preferredSize back to
        // the default, and the subsequent apply() — triggered by LafListener via
        // ComponentTreeRefreshedTopic — would see the true flag and skip re-hiding. Result:
        // visible scrollbar until the user manually toggled off → on.
        //
        // Simulate the exact sequence and assert preferredSize stays at (0,0) after the second
        // apply, regardless of what the internal state flag looked like before.
        realState.hideEditorVScrollbar = true
        val vBar = scrollPane.verticalScrollBar

        val manager = createManager()
        manager.apply()
        assertEquals(Dimension(0, 0), vBar.preferredSize)

        // Simulate what IJSwingUtilities.updateComponentTreeUI does to a patched scrollbar
        // during a LAF change: the UI delegate is reinstalled, and preferredSize reverts to
        // the default (some non-zero dimension computed by the new UI delegate).
        vBar.preferredSize = Dimension(12, 100)

        // The LafListener publishes ComponentTreeRefreshedTopic, subscribers call apply().
        // The real contract: apply() must re-zero the scrollbar regardless of cached state.
        manager.apply()

        assertEquals(Dimension(0, 0), vBar.preferredSize)
        realState.hideEditorVScrollbar = false
        manager.apply()
        assertEquals(Dimension(12, 100), vBar.preferredSize)
        assertTrue(vBar.isPreferredSizeSet)
        manager.dispose()
    }

    @Test
    fun `a Swing callback failure preserves the recorded size and does not interrupt the other axis`() =
        SwingUtilities.invokeAndWait {
            for (property in listOf("preferredSize", "ayuIslands.originalPreferredSize")) {
                verifyCallbackFailure(property)
            }
        }

    private fun verifyCallbackFailure(property: String) {
        val vertical = scrollPane.verticalScrollBar
        val horizontal = scrollPane.horizontalScrollBar
        val verticalSize = Dimension(18, 104)
        val horizontalSize = Dimension(142, 19)
        vertical.preferredSize = verticalSize
        horizontal.preferredSize = horizontalSize
        realState.hideEditorVScrollbar = true
        realState.hideEditorHScrollbar = true
        val listener =
            PropertyChangeListener { event ->
                if (event.newValue != null) {
                    error("scrollbar listener failed after the write")
                }
            }
        val manager = createManager()
        vertical.addPropertyChangeListener(property, listener)
        try {
            withExpectedScrollbarWarning { manager.apply() }
            assertEquals(Dimension(0, 0), vertical.preferredSize)
            assertEquals(Dimension(0, 0), horizontal.preferredSize)
            vertical.removePropertyChangeListener(property, listener)
            realState.hideEditorVScrollbar = false
            realState.hideEditorHScrollbar = false
            manager.apply()
            assertEquals(verticalSize, vertical.preferredSize)
            assertEquals(horizontalSize, horizontal.preferredSize)
        } finally {
            vertical.removePropertyChangeListener(property, listener)
            manager.dispose()
        }
    }

    @Test
    fun `a replacement is hidden even when the detached scrollbar reports a restoration failure`() =
        SwingUtilities.invokeAndWait {
            val detached = scrollPane.verticalScrollBar
            val original = Dimension(19, 108)
            detached.preferredSize = original
            realState.hideEditorVScrollbar = true
            val manager = createManager()
            val listener =
                PropertyChangeListener { event ->
                    if (event.newValue == original) error("scrollbar listener failed after restoration")
                }
            detached.addPropertyChangeListener("preferredSize", listener)
            try {
                manager.apply()
                val replacement = JScrollBar(JScrollBar.VERTICAL)
                val replacementSize = Dimension(14, 112)
                replacement.preferredSize = replacementSize
                scrollPane.verticalScrollBar = replacement
                withExpectedScrollbarWarning { manager.apply() }
                assertEquals(original, detached.preferredSize)
                assertNull(detached.getClientProperty("ayuIslands.originalPreferredSize"))
                assertEquals(Dimension(0, 0), replacement.preferredSize)

                realState.hideEditorVScrollbar = false
                manager.apply()
                assertEquals(replacementSize, replacement.preferredSize)
            } finally {
                detached.removePropertyChangeListener("preferredSize", listener)
                manager.dispose()
            }
        }

    private fun withExpectedScrollbarWarning(action: () -> Unit) {
        val log =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean = false
            }
        LoggedErrorProcessor.executeWith<Throwable>(log) { action() }
    }

    @Test
    fun `showing scrollbars preserves newer ownership and an inherited sibling size`() =
        SwingUtilities.invokeAndWait {
            val vertical = scrollPane.verticalScrollBar
            val horizontal = scrollPane.horizontalScrollBar
            val originalSize = Dimension(18, 104)
            val newerSize = Dimension(21, 108)
            val newerBaseline = Dimension(newerSize)
            val key = "ayuIslands.originalPreferredSize"
            vertical.preferredSize = originalSize
            horizontal.preferredSize = Dimension(142, 19)
            realState.hideEditorVScrollbar = true
            realState.hideEditorHScrollbar = true
            val manager = createManager()
            val listener =
                PropertyChangeListener { event ->
                    if (event.newValue == originalSize) {
                        vertical.putClientProperty(key, newerBaseline)
                        vertical.preferredSize = newerSize
                    }
                }
            try {
                manager.apply()
                assertEquals(Dimension(0, 0), vertical.preferredSize)
                assertEquals(Dimension(0, 0), horizontal.preferredSize)
                vertical.addPropertyChangeListener("preferredSize", listener)
                horizontal.preferredSize = null

                realState.hideEditorVScrollbar = false
                realState.hideEditorHScrollbar = false
                manager.apply()

                assertEquals(newerSize, vertical.preferredSize)
                assertSame(newerBaseline, vertical.getClientProperty(key))
                assertFalse(horizontal.isPreferredSizeSet)
                assertNull(horizontal.getClientProperty(key))
            } finally {
                vertical.removePropertyChangeListener("preferredSize", listener)
                manager.dispose()
            }
        }

    @Test
    fun `showing scrollbars restores inherited and explicit sizes independently`() =
        SwingUtilities.invokeAndWait {
            val vertical = scrollPane.verticalScrollBar
            val horizontal = scrollPane.horizontalScrollBar
            vertical.preferredSize = null
            horizontal.preferredSize = Dimension(140, 18)
            realState.hideEditorVScrollbar = true
            realState.hideEditorHScrollbar = true
            val manager = createManager()
            try {
                manager.apply()
                assertEquals(Dimension(0, 0), vertical.preferredSize)
                assertEquals(Dimension(0, 0), horizontal.preferredSize)

                realState.hideEditorVScrollbar = false
                realState.hideEditorHScrollbar = false
                manager.apply()

                assertFalse(vertical.isPreferredSizeSet)
                assertTrue(horizontal.isPreferredSizeSet)
                assertEquals(Dimension(140, 18), horizontal.preferredSize)
            } finally {
                manager.dispose()
            }
        }

    @Test
    fun `toggling scrollbars preserves newer size and ownership from outside Ayu`() =
        SwingUtilities.invokeAndWait {
            val vertical = scrollPane.verticalScrollBar
            val horizontal = scrollPane.horizontalScrollBar
            realState.hideEditorVScrollbar = true
            realState.hideEditorHScrollbar = true
            val manager = createManager()
            try {
                manager.apply()
                val foreignSize = Dimension(23, 133)
                vertical.preferredSize = foreignSize
                val previousBaseline = horizontal.getClientProperty("ayuIslands.originalPreferredSize") as Dimension
                val replacementBaseline = Dimension(previousBaseline)
                horizontal.putClientProperty("ayuIslands.originalPreferredSize", replacementBaseline)

                realState.hideEditorVScrollbar = false
                realState.hideEditorHScrollbar = false
                manager.apply()

                assertEquals(foreignSize, vertical.preferredSize)
                assertNull(vertical.getClientProperty("ayuIslands.originalPreferredSize"))
                assertEquals(Dimension(0, 0), horizontal.preferredSize)
                assertSame(replacementBaseline, horizontal.getClientProperty("ayuIslands.originalPreferredSize"))

                val replacementSize = Dimension(151, 19)
                horizontal.preferredSize = replacementSize
                realState.hideEditorHScrollbar = true
                manager.apply()

                assertEquals(replacementSize, horizontal.preferredSize)
                assertSame(replacementBaseline, horizontal.getClientProperty("ayuIslands.originalPreferredSize"))
            } finally {
                manager.dispose()
            }
        }

    @Test
    fun `refresh recaptures the inherited size and keeps an unowned sibling property`() =
        SwingUtilities.invokeAndWait {
            val vertical = scrollPane.verticalScrollBar
            val horizontal = scrollPane.horizontalScrollBar
            vertical.preferredSize = Dimension(13, 110)
            realState.hideEditorVScrollbar = true
            val manager = createManager()
            try {
                manager.apply()
                vertical.preferredSize = null
                val foreignBaseline = Dimension(111, 19)
                horizontal.putClientProperty("ayuIslands.originalPreferredSize", foreignBaseline)
                manager.apply()
                assertEquals(Dimension(0, 0), vertical.preferredSize)
                realState.hideEditorVScrollbar = false
                manager.apply()
                assertFalse(vertical.isPreferredSizeSet)
                assertSame(foreignBaseline, horizontal.getClientProperty("ayuIslands.originalPreferredSize"))
            } finally {
                manager.dispose()
            }
        }

    @Test
    fun `replacing a hidden scrollbar releases the detached bar without touching its replacement`() =
        SwingUtilities.invokeAndWait {
            val detached = scrollPane.verticalScrollBar
            detached.preferredSize = null
            val replacement = JScrollBar(JScrollBar.VERTICAL)
            replacement.preferredSize = Dimension(19, 111)
            realState.hideEditorVScrollbar = true
            val manager = createManager()
            try {
                manager.apply()
                assertEquals(Dimension(0, 0), detached.preferredSize)
                scrollPane.verticalScrollBar = replacement
                realState.hideEditorVScrollbar = false
                manager.apply()

                assertFalse(detached.isPreferredSizeSet)
                assertNull(detached.getClientProperty("ayuIslands.originalPreferredSize"))
                assertEquals(Dimension(19, 111), replacement.preferredSize)
            } finally {
                manager.dispose()
            }
        }

    @Test
    fun `editorCreated listener applies to new editors when licensed`() {
        realState.hideEditorVScrollbar = true
        createManager()

        val event =
            mockk<com.intellij.openapi.editor.event.EditorFactoryEvent>(relaxed = true) {
                every { editor } returns editorEx
            }
        listenerSlot.captured.editorCreated(event)

        assertEquals(Dimension(0, 0), scrollPane.verticalScrollBar.preferredSize)
    }

    @Test
    fun `editorCreated listener skips when not licensed`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        realState.hideEditorVScrollbar = true
        val originalSize = Dimension(scrollPane.verticalScrollBar.preferredSize)

        createManager()

        val event =
            mockk<com.intellij.openapi.editor.event.EditorFactoryEvent>(relaxed = true) {
                every { editor } returns editorEx
            }
        listenerSlot.captured.editorCreated(event)

        assertEquals(originalSize, scrollPane.verticalScrollBar.preferredSize)
    }

    @Test
    fun `editorCreated listener skips editors from other projects`() {
        realState.hideEditorVScrollbar = true
        val otherProject = mockk<Project>(relaxed = true)
        val otherScrollPane = JScrollPane()
        val otherEditor =
            mockk<EditorEx>(relaxed = true) {
                every { project } returns otherProject
                every { this@mockk.scrollPane } returns otherScrollPane
            }
        val originalSize = Dimension(otherScrollPane.verticalScrollBar.preferredSize)

        createManager()

        val event =
            mockk<com.intellij.openapi.editor.event.EditorFactoryEvent>(relaxed = true) {
                every { editor } returns otherEditor
            }
        listenerSlot.captured.editorCreated(event)

        assertEquals(originalSize, otherScrollPane.verticalScrollBar.preferredSize)
    }

    @Test
    fun `apply hides both scrollbars simultaneously`() {
        realState.hideEditorVScrollbar = true
        realState.hideEditorHScrollbar = true

        val manager = createManager()
        manager.apply()

        assertEquals(Dimension(0, 0), scrollPane.verticalScrollBar.preferredSize)
        assertEquals(Dimension(0, 0), scrollPane.horizontalScrollBar.preferredSize)
        manager.dispose()
    }

    @Test
    fun `apply skips non-EditorEx editors`() {
        val plainEditor =
            mockk<Editor>(relaxed = true) {
                every { project } returns this@EditorScrollbarManagerTest.project
            }
        every { editorFactory.allEditors } returns arrayOf(plainEditor)

        realState.hideEditorVScrollbar = true

        val manager = createManager()
        manager.apply()
        // No crash
        manager.dispose()
    }

    @Test
    fun `multiple editors opened sequentially all have consistent scrollbar state`() {
        realState.hideEditorHScrollbar = true

        val scrollPane1 = JScrollPane()
        val scrollPane2 = JScrollPane()
        val scrollPane3 = JScrollPane()

        val originalH1 = Dimension(scrollPane1.horizontalScrollBar.preferredSize)
        val originalH2 = Dimension(scrollPane2.horizontalScrollBar.preferredSize)
        val originalH3 = Dimension(scrollPane3.horizontalScrollBar.preferredSize)

        val editor1 =
            mockk<EditorEx>(relaxed = true) {
                every { project } returns this@EditorScrollbarManagerTest.project
                every { scrollPane } returns scrollPane1
            }
        val editor2 =
            mockk<EditorEx>(relaxed = true) {
                every { project } returns this@EditorScrollbarManagerTest.project
                every { scrollPane } returns scrollPane2
            }
        val editor3 =
            mockk<EditorEx>(relaxed = true) {
                every { project } returns this@EditorScrollbarManagerTest.project
                every { scrollPane } returns scrollPane3
            }

        every { editorFactory.allEditors } returns arrayOf(editor1, editor2, editor3)

        val manager = createManager()
        manager.apply()

        // All three scrollbars must be hidden with zero preferred size
        assertEquals(Dimension(0, 0), scrollPane1.horizontalScrollBar.preferredSize)
        assertEquals(Dimension(0, 0), scrollPane2.horizontalScrollBar.preferredSize)
        assertEquals(Dimension(0, 0), scrollPane3.horizontalScrollBar.preferredSize)

        // Disposing the manager must restore every scrollbar to its original size
        manager.dispose()

        assertEquals(originalH1, scrollPane1.horizontalScrollBar.preferredSize)
        assertEquals(originalH2, scrollPane2.horizontalScrollBar.preferredSize)
        assertEquals(originalH3, scrollPane3.horizontalScrollBar.preferredSize)
    }
}
