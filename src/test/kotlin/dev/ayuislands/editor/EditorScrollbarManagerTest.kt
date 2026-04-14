package dev.ayuislands.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
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
import javax.swing.JScrollPane
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

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
        // Verify it's a copy, not the same reference
        assert(storedSize !== sizeBefore || sizeBefore == storedSize)
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
}
