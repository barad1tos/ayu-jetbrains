package dev.ayuislands.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.AccentApplyPlanRunner
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.projectview.RootPathLease
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.WorkspacePanelEvent
import dev.ayuislands.settings.WorkspaceToggle
import dev.ayuislands.studio.catalogTestDocument
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.OwnerAcceptance
import dev.ayuislands.studio.session.OwnerApply
import dev.ayuislands.studio.session.OwnerRollback
import dev.ayuislands.studio.session.StudioOwnerJournal
import dev.ayuislands.studio.session.StudioOwnerWork
import dev.ayuislands.studio.session.StudioWorkspaceEditing
import dev.ayuislands.studio.session.StudioWorkspaceProjection
import dev.ayuislands.studio.session.StudioWorkspaceScrollbarOperation
import dev.ayuislands.ui.ComponentTreeRefreshedListener
import dev.ayuislands.ui.ComponentTreeRefreshedTopic
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import java.awt.Dimension
import java.beans.PropertyChangeListener
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorScrollbarManagerTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var project: Project
    private lateinit var editorFactory: EditorFactory
    private lateinit var realState: AyuIslandsState
    private val listenerSlot = slot<EditorFactoryListener>()
    private val refreshSlot = slot<ComponentTreeRefreshedListener>()

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

        val connection = mockk<MessageBusConnection>()
        every { connection.subscribe(ComponentTreeRefreshedTopic.TOPIC, capture(refreshSlot)) } returns Unit
        every { connection.subscribe(ToolWindowManagerListener.TOPIC, any()) } returns Unit
        project =
            mockk(relaxed = true) {
                every { isDisposed } returns false
                every { messageBus.connect(any<Disposable>()) } returns connection
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
                val canceled = manager.capture(hideVertical = true, hideHorizontal = true)
                canceled.restore()
                canceled.releaseResources()

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

                val closing = manager.capture(hideVertical = true, hideHorizontal = true)
                every { editorEx.isDisposed } returns true
                assertFalse(closing.apply())
                closing.restore()

                changedVertical.assertCurrent(vertical)
                beforeHorizontal.assertCurrent(horizontal)
                assertEquals(writesBeforeAttempt, vertical.sizeWrites)
                assertEquals(0, horizontal.sizeWrites)
            } finally {
                manager.dispose()
            }
        }

    @Test
    fun `a native callback cannot lose newer scrollbar values during Apply`() =
        SwingUtilities.invokeAndWait {
            for (changeSize in listOf(true, false)) {
                val bar = JScrollBar(JScrollBar.VERTICAL)
                scrollPane.verticalScrollBar = bar
                val initialSize = bar.preferredSize
                val newerSize = Dimension(38, 114)
                val newerOwner = Any()
                val key = "ayuIslands.originalPreferredSize"
                val listener =
                    PropertyChangeListener { event ->
                        if (event.newValue is Dimension) {
                            if (changeSize) bar.preferredSize = newerSize else bar.putClientProperty(key, newerOwner)
                        }
                    }
                val manager = createManager()
                try {
                    val checkpoint = manager.capture(hideVertical = true, hideHorizontal = null)
                    bar.addPropertyChangeListener(key, listener)
                    assertFailsWith<IllegalStateException> { checkpoint.apply() }
                    checkpoint.restore()
                    checkpoint.releaseResources()

                    assertEquals(if (changeSize) newerSize else initialSize, bar.preferredSize)
                    assertSame(if (changeSize) null else newerOwner, bar.getClientProperty(key))
                    assertFalse(realState.hideEditorVScrollbar)
                } finally {
                    bar.removePropertyChangeListener(key, listener)
                    manager.dispose()
                }
            }
        }

    @Test
    fun `accepted hide uses a newer scrollbar size when it is shown again`() =
        SwingUtilities.invokeAndWait {
            val vertical = scrollPane.verticalScrollBar
            vertical.preferredSize = Dimension(18, 105)
            val newerSize = Dimension(24, 110)
            realState.hideEditorVScrollbar = true
            val manager = createManager()
            try {
                manager.apply()
                assertEquals(Dimension(0, 0), vertical.preferredSize)
                vertical.preferredSize = newerSize

                val hide = manager.capture(hideVertical = true, hideHorizontal = null)
                assertTrue(hide.apply())
                assertEquals(Dimension(0, 0), vertical.preferredSize)
                hide.commit()
                hide.releaseResources()

                val show = manager.capture(hideVertical = false, hideHorizontal = null)
                assertTrue(show.apply())
                show.commit()
                show.releaseResources()
                assertEquals(newerSize, vertical.preferredSize)
            } finally {
                manager.dispose()
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

                val closing = manager.capture(hideVertical = true, hideHorizontal = true)
                assertTrue(closing.apply())
                manager.dispose()
                beforeVertical.assertCurrent(vertical)
                beforeHorizontal.assertCurrent(horizontal)
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

                        val foreignOnly =
                            selectedManager.capture(
                                hideVertical = if (isVertical) null else true,
                                hideHorizontal = if (isVertical) true else null,
                            )
                        assertTrue(foreignOnly.records.isEmpty())
                        assertTrue(foreignOnly.apply())
                        foreignOnly.commit()
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
                checkpoint.retain()
                manager.apply()
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
                checkpoint.retain()
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
                    if (!changedBeforeCapture) vertical.putClientProperty("ayuIslands.originalPreferredSize", Any())
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
    fun `rollback restores its scrollbar after the look and feel changes`() =
        SwingUtilities.invokeAndWait {
            val scrollbar = scrollPane.verticalScrollBar
            val originalLookAndFeel = UIManager.getLookAndFeel()
            val manager = createManager()
            try {
                val checkpoint = manager.capture(hideVertical = true, hideHorizontal = null)
                assertTrue(checkpoint.apply())
                assertEquals(Dimension(0, 0), scrollbar.preferredSize)

                UIManager.setLookAndFeel(MetalLookAndFeel())
                assertSame(scrollbar, scrollPane.verticalScrollBar)
                checkpoint.restore()
                checkpoint.releaseResources()

                assertFalse(scrollbar.isPreferredSizeSet)
                assertNull(scrollbar.getClientProperty("ayuIslands.originalPreferredSize"))
                assertFalse(realState.hideEditorVScrollbar)
            } finally {
                UIManager.setLookAndFeel(originalLookAndFeel)
                manager.dispose()
            }
        }

    @Test
    fun `aborted rehide does not leave Ayu ownership on a newer size`() =
        SwingUtilities.invokeAndWait {
            val scrollbar = scrollPane.verticalScrollBar
            val marker = "ayuIslands.originalPreferredSize"
            val newerSize = Dimension(35, 120)
            val manager = createManager()
            try {
                realState.hideEditorVScrollbar = true
                manager.apply()
                scrollbar.preferredSize = Dimension(23, 107)
                val checkpoint = manager.capture(hideVertical = true, hideHorizontal = null)
                assertTrue(checkpoint.apply())
                scrollbar.preferredSize = newerSize
                checkpoint.restore()
                checkpoint.releaseResources()

                assertEquals(newerSize, scrollbar.preferredSize)
                assertNull(scrollbar.getClientProperty(marker))
                manager.apply()
                assertEquals(Dimension(0, 0), scrollbar.preferredSize)
                realState.hideEditorVScrollbar = false
                manager.apply()
                assertEquals(newerSize, scrollbar.preferredSize)
            } finally {
                manager.dispose()
            }
        }

    @Test
    fun `opening an editor before admission makes the prepared scrollbar capture stale`() =
        SwingUtilities.invokeAndWait {
            val manager = createManager()
            try {
                val checkpoint = manager.capture(hideVertical = true, hideHorizontal = null)
                val openedPane = JScrollPane()
                val openedEditor =
                    mockk<EditorEx>(relaxed = true) {
                        every { project } returns this@EditorScrollbarManagerTest.project
                        every { scrollPane } returns openedPane
                    }
                every { editorFactory.allEditors } returns arrayOf(editorEx, openedEditor)
                val event =
                    mockk<com.intellij.openapi.editor.event.EditorFactoryEvent> {
                        every { editor } returns openedEditor
                    }
                listenerSlot.captured.editorCreated(event)

                assertFalse(checkpoint.apply())
                assertFalse(scrollPane.verticalScrollBar.isPreferredSizeSet)
                assertFalse(openedPane.verticalScrollBar.isPreferredSizeSet)
            } finally {
                manager.dispose()
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
            val queued = mutableListOf<Runnable>()
            mockkObject(AccentApplyPlanRunner)
            every { AccentApplyPlanRunner.post(capture(queued)) } returns Unit
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
                checkpoint.releaseResources()
                checkpoint.releaseResources()
                assertEquals(1, queued.size)
                assertEquals(0, openedBar.sizeWrites)
                assertEquals(verticalWrites, vertical.sizeWrites)
                assertEquals(horizontalWrites, horizontal.sizeWrites)
                assertFalse(checkpoint.hasPending)

                queued.forEach { it.run() }
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

    @Test
    fun `refresh during Apply runs once after acceptance releases the editors`() =
        SwingUtilities.invokeAndWait {
            val vertical = scrollPane.verticalScrollBar
            val queued = mutableListOf<Runnable>()
            mockkObject(AccentApplyPlanRunner)
            every { AccentApplyPlanRunner.post(capture(queued)) } returns Unit
            val manager = createManager()
            try {
                val checkpoint = manager.capture(hideVertical = true, hideHorizontal = null)
                assertTrue(checkpoint.apply())
                vertical.preferredSize = null
                refreshSlot.captured.afterRefresh(project)
                refreshSlot.captured.afterRefresh(project)
                assertTrue(queued.isEmpty())
                assertFalse(vertical.isPreferredSizeSet)

                realState.hideEditorVScrollbar = true
                checkpoint.commit()
                checkpoint.releaseResources()
                assertEquals(1, queued.size)
                queued.single().run()
                assertEquals(Dimension(0, 0), vertical.preferredSize)

                realState.hideEditorVScrollbar = false
                manager.apply()
                assertFalse(vertical.isPreferredSizeSet)
            } finally {
                manager.dispose()
            }
        }

    @Test
    fun `Studio journals the selected editor axis before changing it and restores it on abort`() =
        exerciseJournaledScrollbars(JournalOutcome.ABORTED)

    @Test
    fun `an accepted editor change keeps its selected axis and releases the checkpoint`() =
        exerciseJournaledScrollbars(JournalOutcome.ACCEPTED)

    @Test
    fun `losing the Apply lease after journaling leaves the editor untouched`() =
        exerciseJournaledScrollbars(JournalOutcome.EDITOR_LEASE_LOST)

    @Test
    fun `losing the Apply lease between editor axes leaves the second scrollbar untouched`() =
        exerciseJournaledScrollbars(
            JournalOutcome.EDITOR_AXIS_LEASE_LOST,
            toggles = listOf(WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR, WorkspaceToggle.EDITOR_HORIZONTAL_SCROLLBAR),
        )

    @Test
    fun `losing the Apply lease after the editor write leaves Project View untouched and rolls back the editor`() =
        exerciseJournaledScrollbars(
            JournalOutcome.PROJECT_LEASE_LOST,
            toggles = listOf(WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR, WorkspaceToggle.PROJECT_HORIZONTAL_SCROLLBAR),
        )

    @Test
    fun `a failing Project View setter restores both scrollbars through the same journal`() =
        exerciseJournaledScrollbars(
            JournalOutcome.PROJECT_WRITE_FAILED,
            toggles = listOf(WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR, WorkspaceToggle.PROJECT_HORIZONTAL_SCROLLBAR),
        )

    @Test
    fun `a Project View only Apply leaves editor scrollbars and the legacy settings alone`() =
        exerciseJournaledScrollbars(
            JournalOutcome.ACCEPTED,
            toggles = listOf(WorkspaceToggle.PROJECT_HORIZONTAL_SCROLLBAR),
        )

    @Test
    fun `a deferred legacy refresh preserves the accepted Project View scrollbar policy`() =
        exerciseJournaledScrollbars(
            JournalOutcome.PROJECT_REFRESH,
            toggles = listOf(WorkspaceToggle.PROJECT_HORIZONTAL_SCROLLBAR),
        )

    private fun exerciseJournaledScrollbars(
        outcome: JournalOutcome,
        toggles: List<WorkspaceToggle> = listOf(WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR),
    ) = runBlocking {
        val nativeDispatcher = Executor { SwingUtilities.invokeLater(it) }.asCoroutineDispatcher()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ioDispatcher ->
            withContext(ioDispatcher) {
                StudioAppearanceStore(openStudioFiles(directory)).use { store ->
                    val scenario = JournalScenario(outcome, toggles)
                    verifyJournaledScrollbars(store, ioDispatcher, nativeDispatcher, scenario)
                }
            }
        }
    }

    private suspend fun verifyJournaledScrollbars(
        store: StudioAppearanceStore,
        ioDispatcher: CoroutineDispatcher,
        nativeDispatcher: CoroutineDispatcher,
        scenario: JournalScenario,
    ) {
        val before = catalogTestDocument()
        val fields = before.shared.workspace
        val changes =
            scenario.toggles
                .flatMap { StudioWorkspaceEditing.edits(fields, WorkspacePanelEvent.Toggle(it, true)) }
                .associate { it.after.key to it.after }
        val edited = fields.copy(records = StudioList(fields.records.map { changes[it.key] ?: it }))
        val selection = StudioWorkspaceProjection.resolve(edited, changes.keys.map(::FieldAddress), true)
        val plan = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
        assertEquals(StoreLoad.Empty, store.load())
        assertEquals(JournalResult.Acknowledged, store.preserveSource(before.header.migration))
        val first = assertIs<PrepareResult.Prepared>(store.prepare(-1, before, plan)).token
        assertIs<CommitResult.Committed>(store.commit(first, before.runtime))
        assertEquals(JournalResult.Acknowledged, store.finishAccepted(first))
        val candidate =
            before.copy(
                header = before.header.copy(revision = 1, transactionId = "workspace-scrollbars"),
                shared = before.shared.copy(workspace = edited),
            )
        val route = plan.copy(owners = StudioList(listOf(EffectOwner.WORKSPACE)))
        val journal = assertIs<PrepareResult.Prepared>(store.prepare(0, candidate, route)).journal
        val work = StudioOwnerWork(journal)
        val owners = StudioOwnerJournal(store, ioDispatcher)
        val horizontalSize = Dimension(53, 9)
        val projectPane = JScrollPane()
        val (manager, projectManager) =
            withContext(nativeDispatcher) {
                scrollPane.verticalScrollBar.preferredSize = null
                scrollPane.horizontalScrollBar.preferredSize = horizontalSize
                projectPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
                createManager() to if (scenario.includesProject) createProjectViewManager(projectPane) else null
            }
        try {
            if (scenario.failProjectWrite) withContext(nativeDispatcher) { failProjectAfterWrite(projectPane) }
            var nativeAttempts = 0
            val applied =
                owners.apply(
                    work,
                    qualifies = {
                        val pending =
                            withContext(ioDispatcher) {
                                assertIs<StoreLoad.Recovering>(store.load()).journal.receipts
                            }
                        if (pending.isNotEmpty()) {
                            assertTrue(SwingUtilities.isEventDispatchThread())
                            assertEquals(scenario.expectedFields(), pending.map { it.identity.field })
                            assertTrue(pending.all { it.status == RestoreStatus.PENDING })
                            assertScrollbarTargets(
                                scenario,
                                projectPane,
                                horizontalSize,
                                applied = nativeAttempts > 0,
                                projectApplied = false,
                            )
                            nativeAttempts++
                        }
                        nativeAttempts != scenario.outcome.refuseWrite
                    },
                ) {
                    StudioWorkspaceScrollbarOperation.capture(
                        StudioWorkspaceScrollbarOperation.Targets(listOf(manager), listOfNotNull(projectManager)),
                        selection,
                        StudioProcess(501, Instant.EPOCH),
                        journal.token.transactionId,
                        nativeDispatcher,
                    )
                }
            refreshProjectWhileOwned(scenario.outcome, projectManager, nativeDispatcher)
            when {
                scenario.failProjectWrite ->
                    assertEquals(EffectOwner.WORKSPACE, assertIs<OwnerApply.OwnerFailed>(applied).owner)
                scenario.outcome.refuseWrite != null -> {
                    assertEquals(OwnerApply.LeaseLost, applied)
                    assertTrue(work.journal.receipts.all { it.status == RestoreStatus.PENDING })
                }
                else -> {
                    assertEquals(OwnerApply.Completed, applied)
                    assertTrue(work.journal.receipts.all { it.status == RestoreStatus.OWNED })
                }
            }
            assertTrue(nativeAttempts > 0)
            withContext(nativeDispatcher) {
                assertScrollbarTargets(
                    scenario,
                    projectPane,
                    horizontalSize,
                    applied = scenario.outcome != JournalOutcome.EDITOR_LEASE_LOST,
                    projectApplied = scenario.outcome.refuseWrite == null,
                )
            }
            if (scenario.accept) {
                assertEquals(OwnerAcceptance.Decision(CommitResolution.NotCommitted), owners.accept(work))
                val committed = assertIs<CommitResult.Committed>(store.commit(journal.token, candidate.runtime))
                assertEquals(OwnerAcceptance.Completed, owners.accept(work))
                assertEquals(JournalResult.Acknowledged, store.finishAccepted(journal.token))
                assertEquals(edited, committed.document.shared.workspace)
                assertEquals(StoreLoad.Ready(committed.document), store.load())
            } else {
                assertEquals(OwnerRollback.Settled, owners.rollback(work))
                assertEquals(JournalResult.Acknowledged, store.finishRollback(journal.token))
                assertEquals(StoreLoad.Ready(before), store.load())
            }
            withContext(nativeDispatcher) {
                assertScrollbarTargets(scenario, projectPane, horizontalSize, applied = scenario.accept)
                if (scenario.accept && scenario.includesEditor) {
                    assertTrue(manager.capture(hideVertical = false, hideHorizontal = null).records.isNotEmpty())
                }
                if (scenario.accept && scenario.includesProject) {
                    assertNotNull(projectManager?.prepareScrollbar(hideHorizontal = false))
                }
                assertFalse(realState.hideEditorVScrollbar)
                assertFalse(realState.hideProjectViewHScrollbar)
            }
        } finally {
            withContext(nativeDispatcher) {
                projectManager?.dispose()
                manager.dispose()
            }
        }
    }

    private suspend fun refreshProjectWhileOwned(
        outcome: JournalOutcome,
        manager: ProjectViewScrollbarManager?,
        nativeDispatcher: CoroutineDispatcher,
    ) {
        if (outcome == JournalOutcome.PROJECT_REFRESH) {
            withContext(nativeDispatcher) { checkNotNull(manager).apply() }
        }
    }

    private fun failProjectAfterWrite(pane: JScrollPane) {
        pane.addPropertyChangeListener("horizontalScrollBarPolicy") { event ->
            if (event.newValue == ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
                assertEquals(Dimension(0, 0), scrollPane.verticalScrollBar.preferredSize)
                error("Project View write failed after changing the policy")
            }
        }
    }

    private fun assertScrollbarTargets(
        scenario: JournalScenario,
        pane: JScrollPane,
        horizontalSize: Dimension,
        applied: Boolean,
        projectApplied: Boolean = applied,
    ) {
        if (applied && scenario.includesEditor) {
            assertEquals(Dimension(0, 0), scrollPane.verticalScrollBar.preferredSize)
        } else {
            assertFalse(scrollPane.verticalScrollBar.isPreferredSizeSet)
        }
        val expectedPolicy =
            if (projectApplied && scenario.includesProject) {
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            } else {
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
            }
        assertEquals(expectedPolicy, pane.horizontalScrollBarPolicy)
        assertEquals(horizontalSize, scrollPane.horizontalScrollBar.preferredSize)
    }

    private fun createProjectViewManager(pane: JScrollPane): ProjectViewScrollbarManager {
        mockkStatic(ToolWindowManager::class)
        val wrapper = JPanel().apply { add(pane) }
        val content = mockk<Content> { every { component } returns wrapper }
        val contents = mockk<ContentManager> { every { this@mockk.contents } returns arrayOf(content) }
        val window =
            mockk<ToolWindow> {
                every { contentManager } returns contents
                every { contentManagerIfCreated } returns contents
            }
        val toolWindows = mockk<ToolWindowManager> { every { getToolWindow("Project") } returns window }
        every { ToolWindowManager.getInstance(project) } returns toolWindows
        return ProjectViewScrollbarManager(project, RootPathLease()) { mockk<RegistryValue>(relaxed = true) }
    }

    private enum class JournalOutcome(
        val refuseWrite: Int? = null,
    ) {
        ABORTED,
        ACCEPTED,
        EDITOR_LEASE_LOST(1),
        EDITOR_AXIS_LEASE_LOST(2),
        PROJECT_LEASE_LOST(2),
        PROJECT_WRITE_FAILED,
        PROJECT_REFRESH,
    }

    private data class JournalScenario(
        val outcome: JournalOutcome,
        val toggles: List<WorkspaceToggle>,
    ) {
        val accept get() = outcome == JournalOutcome.ACCEPTED || outcome == JournalOutcome.PROJECT_REFRESH
        val failProjectWrite get() = outcome == JournalOutcome.PROJECT_WRITE_FAILED
        val includesEditor get() = WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR in toggles
        val includesProject get() = WorkspaceToggle.PROJECT_HORIZONTAL_SCROLLBAR in toggles

        fun expectedFields(): List<String> =
            buildList {
                if (includesEditor) addAll(listOf("EDITOR_SIZE", "EDITOR_BASELINE"))
                if (WorkspaceToggle.EDITOR_HORIZONTAL_SCROLLBAR in toggles) {
                    addAll(listOf("EDITOR_SIZE", "EDITOR_BASELINE"))
                }
                if (includesProject) addAll(listOf("PROJECT_SCROLLBAR_POLICY", "PROJECT_SCROLLBAR_BASELINE"))
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
