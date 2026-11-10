package dev.ayuislands.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LoggedErrorProcessor
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.ui.ComponentTreeRefreshedListener
import dev.ayuislands.ui.ComponentTreeRefreshedTopic
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Dimension
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private val refreshListenerSlot = slot<ComponentTreeRefreshedListener>()

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
                every { state } answers {
                    dev.ayuislands.studio.StudioFixtures
                        .legacyView(realState)
                        .settings
                }
            }
        every { AyuIslandsSettings.getInstance() } returns settingsMock
        every { LicenseChecker.isLicensedOrGrace() } returns true

        project =
            mockk(relaxed = true) {
                every { isDisposed } returns false
            }

        every {
            project.messageBus.connect(any<Disposable>()).subscribe(
                ComponentTreeRefreshedTopic.TOPIC,
                capture(refreshListenerSlot),
            )
        } returns Unit

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
    fun `resetOriginalSizeCache tolerates putClientProperty failures`() {
        // Listener body is `resetOriginalSizeCacheForTest() then apply()`. If a downstream
        // Swing layer throws from putClientProperty (platform regression, disposed
        // component) the listener must survive — otherwise the MessageBus dispatcher
        // catches it as SEVERE and the follow-up apply() never runs (scrollbars stay
        // unhidden after LAF change). Mocked scrollbar that throws on the null-value
        // clear proves the per-pane try/catch keeps iteration alive.
        val throwingVBar = mockk<JScrollBar>(relaxed = true)
        every {
            throwingVBar.putClientProperty(eq("ayuIslands.originalPreferredSize"), null)
        } throws IllegalStateException("simulated putClientProperty failure")

        val mockScrollPane = mockk<JScrollPane>(relaxed = true)
        every { mockScrollPane.verticalScrollBar } returns throwingVBar
        every { mockScrollPane.horizontalScrollBar } returns mockk(relaxed = true)
        every { editorEx.scrollPane } returns mockScrollPane

        realState.hideEditorVScrollbar = true
        val manager = createManager()
        manager.apply() // populates patchedScrollPanes; hideScrollBar's
        // putClientProperty (with non-null Dimension) is the relaxed-mock no-op.

        // Reset path: the null-value put on the vertical bar throws; the iteration must
        // continue (horizontal bar still gets cleared) and no exception escapes.
        val suppressLoggedErrors =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean = false
            }
        LoggedErrorProcessor.executeWith<Throwable>(suppressLoggedErrors) {
            manager.resetOriginalSizeCacheForTest()
        }

        verify(atLeast = 1) {
            throwingVBar.putClientProperty(eq("ayuIslands.originalPreferredSize"), null)
        }
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
        manager.dispose()
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

    @Test
    fun `capture is read only and stale preparation cannot replace a later native change`() =
        SwingUtilities.invokeAndWait {
            val vertical = RecordingScrollBar(JScrollBar.VERTICAL)
            val horizontal = RecordingScrollBar(JScrollBar.HORIZONTAL)
            scrollPane.verticalScrollBar = vertical
            scrollPane.horizontalScrollBar = horizontal
            val beforeVertical = ScrollbarObservation(vertical)
            val beforeHorizontal = ScrollbarObservation(horizontal)
            val manager = createManager()
            try {
                val checkpoint = manager.capture(hideVertical = true, hideHorizontal = true)
                beforeVertical.assertCurrent(vertical)
                beforeHorizontal.assertCurrent(horizontal)
                assertEquals(0, vertical.sizeWrites)
                assertEquals(0, horizontal.sizeWrites)

                // Capture does not hold callbacks before the outer journal acknowledgement.
                realState.hideEditorVScrollbar = true
                manager.apply()
                assertEquals(Dimension(0, 0), vertical.preferredSize)
                val changedVertical = ScrollbarObservation(vertical)
                val writesBeforeAttempt = vertical.sizeWrites
                assertFalse(checkpoint.apply())
                checkpoint.restore()
                checkpoint.releaseResources()

                changedVertical.assertCurrent(vertical)
                beforeHorizontal.assertCurrent(horizontal)
                assertEquals(writesBeforeAttempt, vertical.sizeWrites)
                assertEquals(0, horizontal.sizeWrites)
            } finally {
                manager.dispose()
            }

            val previousLaf = UIManager.getLookAndFeel()
            for (isApplied in listOf(false, true)) {
                val lafManager = createManager()
                val prepared = lafManager.capture(hideVertical = true, hideHorizontal = true)
                try {
                    if (isApplied) assertTrue(prepared.apply())
                    UIManager.setLookAndFeel(MetalLookAndFeel())
                    val retainedVertical = ScrollbarObservation(vertical)
                    val retainedHorizontal = ScrollbarObservation(horizontal)
                    val verticalWrites = vertical.sizeWrites
                    val horizontalWrites = horizontal.sizeWrites
                    if (!isApplied) assertFalse(prepared.apply())
                    prepared.restore()
                    prepared.releaseResources()
                    assertEquals(AccentWriteState.RELINQUISHED, prepared.state)
                    retainedVertical.assertCurrent(vertical)
                    retainedHorizontal.assertCurrent(horizontal)
                    assertEquals(verticalWrites, vertical.sizeWrites)
                    assertEquals(horizontalWrites, horizontal.sizeWrites)
                } finally {
                    UIManager.setLookAndFeel(previousLaf)
                    lafManager.dispose()
                }
            }
        }

    @Test
    fun `candidate axes restore inherited and explicit preferred sizes without rewriting preferences`() =
        SwingUtilities.invokeAndWait {
            val vertical = scrollPane.verticalScrollBar
            val horizontal = scrollPane.horizontalScrollBar
            vertical.preferredSize = null
            horizontal.preferredSize = Dimension(61, 9)
            val beforeVertical = ScrollbarObservation(vertical)
            val beforeHorizontal = ScrollbarObservation(horizontal)
            every { LicenseChecker.isLicensedOrGrace() } returns false
            val manager = createManager()
            try {
                val checkpoint = manager.capture(hideVertical = true, hideHorizontal = true)
                assertTrue(checkpoint.apply())
                assertEquals(Dimension(0, 0), vertical.preferredSize)
                assertEquals(Dimension(0, 0), horizontal.preferredSize)
                assertFalse(realState.hideEditorVScrollbar)
                assertFalse(realState.hideEditorHScrollbar)

                checkpoint.restore()
                checkpoint.restore()
                checkpoint.releaseResources()

                beforeVertical.assertCurrent(vertical)
                beforeHorizontal.assertCurrent(horizontal)
                assertFalse(vertical.isPreferredSizeSet)
                assertTrue(horizontal.isPreferredSizeSet)
                assertFalse(checkpoint.hasPending)
                assertFalse(realState.hideEditorVScrollbar)
                assertFalse(realState.hideEditorHScrollbar)
            } finally {
                manager.dispose()
            }

            for (isVertical in listOf(true, false)) {
                for (isAccepted in listOf(false, true)) {
                    val selected = RecordingScrollBar(if (isVertical) JScrollBar.VERTICAL else JScrollBar.HORIZONTAL)
                    val foreign = RecordingScrollBar(if (isVertical) JScrollBar.HORIZONTAL else JScrollBar.VERTICAL)
                    scrollPane.verticalScrollBar = if (isVertical) selected else foreign
                    scrollPane.horizontalScrollBar = if (isVertical) foreign else selected
                    foreign.preferredSize = Dimension(39, 113)
                    foreign.putClientProperty("ayuIslands.originalPreferredSize", Any())
                    val selectedBefore = ScrollbarObservation(selected)
                    val selectedManager = createManager()
                    try {
                        val checkpoint =
                            selectedManager.capture(
                                hideVertical = if (isVertical) true else null,
                                hideHorizontal = if (isVertical) null else true,
                            )
                        assertSame(selected, checkpoint.records.single().bar)
                        // Unselected fields may change even after capture without invalidating this operation.
                        foreign.preferredSize = Dimension(47, 131)
                        foreign.putClientProperty("ayuIslands.originalPreferredSize", Any())
                        val foreignBefore = ScrollbarObservation(foreign)
                        val foreignWrites = foreign.sizeWrites
                        var baselineWrites = 0
                        foreign.addPropertyChangeListener("ayuIslands.originalPreferredSize") { baselineWrites++ }

                        assertTrue(checkpoint.apply())
                        assertEquals(Dimension(0, 0), selected.preferredSize)
                        foreignBefore.assertCurrent(foreign)
                        if (isAccepted) checkpoint.commit() else checkpoint.restore()
                        checkpoint.releaseResources()
                        checkpoint.releaseResources()
                        foreignBefore.assertCurrent(foreign)
                        assertEquals(foreignWrites, foreign.sizeWrites)
                        assertEquals(0, baselineWrites)
                        if (!isAccepted) selectedBefore.assertCurrent(selected)

                        selectedManager.dispose()
                        selectedBefore.assertCurrent(selected)
                        foreignBefore.assertCurrent(foreign)
                        assertEquals(foreignWrites, foreign.sizeWrites)
                        assertEquals(0, baselineWrites)
                    } finally {
                        selectedManager.dispose()
                    }
                }
            }
        }

    @Test
    fun `mutation then throw keeps rollback retryable and restores the other axis independently`() =
        SwingUtilities.invokeAndWait {
            val vertical = RecordingScrollBar(JScrollBar.VERTICAL)
            val horizontal = RecordingScrollBar(JScrollBar.HORIZONTAL)
            scrollPane.verticalScrollBar = vertical
            scrollPane.horizontalScrollBar = horizontal
            val beforeVertical = ScrollbarObservation(vertical)
            val beforeHorizontal = ScrollbarObservation(horizontal)
            val manager = createManager()
            try {
                val checkpoint = manager.capture(hideVertical = true, hideHorizontal = true)
                horizontal.shouldFailWrite = true
                assertFailsWith<IllegalStateException> { checkpoint.apply() }
                assertEquals(Dimension(0, 0), vertical.preferredSize)
                assertEquals(Dimension(0, 0), horizontal.preferredSize)
                assertTrue(checkpoint.hasPending)

                horizontal.shouldFailWrite = true
                assertFailsWith<IllegalStateException> { checkpoint.restore() }
                beforeVertical.assertCurrent(vertical)
                assertTrue(checkpoint.hasPending)
                val restoreWrites = horizontal.sizeWrites
                checkpoint.restore()
                checkpoint.releaseResources()

                beforeVertical.assertCurrent(vertical)
                beforeHorizontal.assertCurrent(horizontal)
                assertEquals(restoreWrites, horizontal.sizeWrites)
                assertFalse(checkpoint.hasPending)
            } finally {
                manager.dispose()
            }
        }

    @Test
    fun `rollback preserves a foreign size and replacement scrollbar through cleanup`() =
        SwingUtilities.invokeAndWait {
            for (changedBeforeCapture in listOf(false, true)) {
                val vertical = RecordingScrollBar(JScrollBar.VERTICAL)
                val horizontal = RecordingScrollBar(JScrollBar.HORIZONTAL)
                scrollPane.verticalScrollBar = vertical
                scrollPane.horizontalScrollBar = horizontal
                realState.hideEditorVScrollbar = true
                val manager = createManager()
                try {
                    manager.apply()
                    val foreignSize = Dimension(37, 121)
                    if (changedBeforeCapture) vertical.preferredSize = foreignSize
                    val checkpoint = manager.capture(hideVertical = false, hideHorizontal = true)
                    assertTrue(checkpoint.apply())
                    if (changedBeforeCapture) assertEquals(foreignSize, vertical.preferredSize)
                    vertical.preferredSize = foreignSize
                    val foreignVertical = ScrollbarObservation(vertical)
                    val replacement = RecordingScrollBar(JScrollBar.HORIZONTAL)
                    replacement.preferredSize = Dimension(233, 19)
                    scrollPane.horizontalScrollBar = replacement
                    val foreignHorizontal = ScrollbarObservation(replacement)
                    val detachedWrites = horizontal.sizeWrites
                    val foreignWrites = vertical.sizeWrites

                    checkpoint.restore()
                    checkpoint.releaseResources()
                    checkpoint.releaseResources()
                    manager.dispose()

                    foreignVertical.assertCurrent(vertical)
                    foreignHorizontal.assertCurrent(replacement)
                    assertSame(replacement, scrollPane.horizontalScrollBar)
                    assertEquals(foreignWrites, vertical.sizeWrites)
                    assertEquals(detachedWrites, horizontal.sizeWrites)
                    assertFalse(checkpoint.hasPending)
                } finally {
                    manager.dispose()
                }
            }
        }

    @Test
    fun `commit holds callbacks until release without replaying setters on captured editors`() =
        SwingUtilities.invokeAndWait {
            val vertical = RecordingScrollBar(JScrollBar.VERTICAL)
            val horizontal = RecordingScrollBar(JScrollBar.HORIZONTAL)
            scrollPane.verticalScrollBar = vertical
            scrollPane.horizontalScrollBar = horizontal
            val beforeVertical = ScrollbarObservation(vertical)
            val beforeHorizontal = ScrollbarObservation(horizontal)
            val manager = createManager()
            try {
                val checkpoint = manager.capture(hideVertical = true, hideHorizontal = true)
                assertTrue(checkpoint.apply())
                val appliedVertical = ScrollbarObservation(vertical)
                val appliedHorizontal = ScrollbarObservation(horizontal)
                val openedPane = JScrollPane()
                val openedBar = RecordingScrollBar(JScrollBar.VERTICAL)
                openedPane.verticalScrollBar = openedBar
                val openedEditor =
                    mockk<EditorEx>(relaxed = true) {
                        every { project } returns this@EditorScrollbarManagerTest.project
                        every { scrollPane } returns openedPane
                    }
                val event =
                    mockk<com.intellij.openapi.editor.event.EditorFactoryEvent> {
                        every { editor } returns openedEditor
                    }
                val openedVertical = ScrollbarObservation(openedPane.verticalScrollBar)
                every { editorFactory.allEditors } returns arrayOf(editorEx, openedEditor)

                manager.apply()
                listenerSlot.captured.editorCreated(event)
                refreshListenerSlot.captured.afterRefresh(project)
                appliedVertical.assertCurrent(vertical)
                appliedHorizontal.assertCurrent(horizontal)
                openedVertical.assertCurrent(openedPane.verticalScrollBar)
                assertFalse(realState.hideEditorVScrollbar)
                assertFalse(realState.hideEditorHScrollbar)

                // The outer transaction persists preferences before accepting native resources.
                realState.hideEditorVScrollbar = true
                realState.hideEditorHScrollbar = true
                val verticalWrites = vertical.sizeWrites
                val horizontalWrites = horizontal.sizeWrites
                checkpoint.commit()
                listenerSlot.captured.editorCreated(event)
                openedVertical.assertCurrent(openedPane.verticalScrollBar)
                openedBar.shouldFailWrite = true
                checkpoint.releaseResources()
                checkpoint.releaseResources()
                assertEquals(0, openedBar.sizeWrites)
                assertEquals(verticalWrites, vertical.sizeWrites)
                assertEquals(horizontalWrites, horizontal.sizeWrites)
                assertFalse(checkpoint.hasPending)

                openedBar.shouldFailWrite = false
                listenerSlot.captured.editorCreated(event)
                assertEquals(Dimension(0, 0), openedPane.verticalScrollBar.preferredSize)
                realState.hideEditorVScrollbar = false
                realState.hideEditorHScrollbar = false
                manager.apply()
                beforeVertical.assertCurrent(vertical)
                beforeHorizontal.assertCurrent(horizontal)
                openedVertical.assertCurrent(openedPane.verticalScrollBar)
            } finally {
                manager.dispose()
            }
        }
}

private class ScrollbarObservation(
    scrollBar: JScrollBar,
) {
    private val isExplicit = scrollBar.isPreferredSizeSet
    private val size = Dimension(scrollBar.preferredSize)
    private val baselineProperty = scrollBar.getClientProperty("ayuIslands.originalPreferredSize")

    fun assertCurrent(current: JScrollBar) {
        assertEquals(isExplicit, current.isPreferredSizeSet)
        assertEquals(size, current.preferredSize)
        assertSame(baselineProperty, current.getClientProperty("ayuIslands.originalPreferredSize"))
    }
}

private class RecordingScrollBar(
    orientation: Int,
) : JScrollBar(orientation) {
    var sizeWrites = 0
        private set
    var shouldFailWrite = false

    override fun setPreferredSize(preferredSize: Dimension?) {
        super.setPreferredSize(preferredSize)
        sizeWrites++
        if (shouldFailWrite) {
            shouldFailWrite = false
            error("Preferred size changed before setter failure")
        }
    }
}
