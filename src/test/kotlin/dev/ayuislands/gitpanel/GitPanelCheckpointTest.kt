package dev.ayuislands.gitpanel

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.AccentApplyPlanRunner
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.settings.WorkspaceWidth
import dev.ayuislands.toolwindow.AutoFitCalculator
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.plaf.metal.MetalLookAndFeel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitPanelCheckpointTest {
    @BeforeTest
    fun setUp() {
        mockkStatic(ToolWindowManager::class)
        mockkObject(AyuIslandsSettings.Companion)
        mockkObject(LicenseChecker)
        mockkObject(AutoFitCalculator)
    }

    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `manager binds shared persisted keys without prematurely restoring their raw value`() =
        withPanel { panel ->
            mockkStatic(PropertiesComponent::class)
            val storage = ProportionStorage()
            every { PropertiesComponent.getInstance() } returns storage.properties
            val key = "Git.Log.SharedProportion"
            storage.values[key] = "0.20000000"
            val inner =
                PersistedSplitter(key, 0.7f, 0.5f).apply {
                    firstComponent = JPanel().apply { add(JTable()) }
                    secondComponent = JPanel()
                    setSize(1000, 400)
                }
            val outer =
                PersistedSplitter(key, 0.2f, 0.5f).apply {
                    firstComponent = JPanel()
                    secondComponent = inner
                    setSize(1000, 400)
                }
            panel.logComponent = outer
            val shared = GitProportionCheckpoint(key, storage.properties)
            val checkpoint =
                panel.manager.capture(WorkspaceWidth(PanelWidthMode.FIXED.name, 200, 500, 300)) {
                    assertEquals(key, it)
                    shared
                }
            assertEquals(2, shared.record.plans.size)
            assertEquals(key, checkpoint.propertyRecords.single().key)
            assertEquals("0.20000000", checkpoint.propertyRecords.single().before)
            assertTrue(checkpoint.apply())
            assertEquals(
                storage.values[key],
                checkpoint.propertyRecords
                    .single()
                    .observations
                    .last()
                    .raw,
            )
            shared.prepareRestore()
            checkpoint.restore()
            assertEquals("0.2", storage.values[key], "Only the outer owner may finish shared raw restoration")
            shared.restore()
            assertEquals("0.20000000", storage.values[key])
            assertEquals(0.2f.toRawBits(), outer.proportion.toRawBits())
            assertEquals(0.7f.toRawBits(), inner.proportion.toRawBits())
        }

    @Test
    fun `capture never creates Log content and retains stale owned listeners independently of geometry`() =
        withPanel { panel ->
            panel.createdManager = null
            val before = panel.proportions()
            val writes = panel.writes()
            val originalListeners = panel.listeners()
            val empty = panel.manager.capture(WorkspaceWidth(PanelWidthMode.DEFAULT.name, 200, 500, 300))
            assertTrue(empty.records.isEmpty())
            assertEquals(before, panel.proportions())
            assertEquals(writes, panel.writes())
            assertEquals(originalListeners, panel.listeners())
            empty.restore()
            verify(exactly = 0) { panel.toolWindow.contentManager }

            panel.createdManager = panel.contentManager
            panel.settings.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
            panel.manager.apply()
            val ownedListeners = panel.listeners()
            assertTrue(
                ownedListeners.zip(originalListeners).all { (owned, original) ->
                    owned.size == original.size + 1
                },
            )
            panel.createdManager = null
            val stale = panel.manager.capture(WorkspaceWidth(PanelWidthMode.DEFAULT.name, 200, 500, 300))
            val retainedProportions = panel.proportions()
            val retainedWrites = panel.writes()
            assertTrue(stale.records.isEmpty())
            assertEquals(ownedListeners, panel.listeners())

            assertTrue(stale.apply())
            assertEquals(ownedListeners, panel.listeners())
            assertEquals(retainedProportions, panel.proportions())
            stale.restore()
            assertEquals(ownedListeners, panel.listeners())
            assertEquals(retainedWrites, panel.writes())
            verify(exactly = 0) { panel.toolWindow.contentManager }
        }

    @Test
    fun `explicit candidate widths retain v2 formulas caps and jitter without replacing saved values`() {
        val cases =
            listOf(
                WidthCase(WorkspaceWidth(PanelWidthMode.AUTO_FIT.name, 200, 500, -40), 0.24f, 0.62f),
                WidthCase(WorkspaceWidth(PanelWidthMode.AUTO_FIT.name, 900, 100, -40), 0.5f, 0.5f),
                WidthCase(WorkspaceWidth(PanelWidthMode.FIXED.name, 10, 20, 300), 0.3f, 0.7f),
                WidthCase(WorkspaceWidth(PanelWidthMode.FIXED.name, 10, 20, -40), 0.05f, 0.95f),
                WidthCase(WorkspaceWidth(PanelWidthMode.FIXED.name, 10, 20, 2000), 0.5f, 0.5f),
            )
        for ((width, outer, inner) in cases) {
            withPanel { panel ->
                val saved = panel.savedWidth()
                val before = panel.proportions()
                val beforeListeners = panel.listeners()
                every { LicenseChecker.isLicensedOrGrace() } returns false
                val checkpoint = panel.manager.capture(width)
                assertTrue(checkpoint.apply())
                assertEquals(outer.toRawBits(), panel.outer.proportion.toRawBits())
                assertEquals(inner.toRawBits(), panel.inner.proportion.toRawBits())
                assertEquals(saved, panel.savedWidth())
                checkpoint.restore()
                assertEquals(before, panel.proportions())
                assertEquals(beforeListeners, panel.listeners())
                assertEquals(saved, panel.savedWidth())
            }
        }
        withPanel { panel ->
            panel.outer.proportion = 0.245f
            panel.inner.proportion = 0.615f
            val before = panel.proportions()
            val writes = panel.writes()
            val checkpoint = panel.manager.capture(WorkspaceWidth(PanelWidthMode.AUTO_FIT.name, 200, 500, 300))
            assertTrue(checkpoint.apply())
            assertEquals(before, panel.proportions())
            assertEquals(writes, panel.writes())
            checkpoint.restore()
            assertEquals(writes, panel.writes())
        }
    }

    @Test
    fun `partially failed splitter writes restore independently and retry without replaying settled setters`() =
        withPanel { panel ->
            val before = panel.proportions()
            val checkpoint = panel.manager.capture(WorkspaceWidth(PanelWidthMode.FIXED.name, 200, 500, 300))
            panel.inner.shouldFailWrite = true
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertEquals(listOf(0.3f.toRawBits(), 0.7f.toRawBits()), panel.proportions())
            assertTrue(checkpoint.hasPending)
            val failedWrites = panel.writes()
            panel.settings.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
            panel.manager.apply()
            assertEquals(listOf(0.3f.toRawBits(), 0.7f.toRawBits()), panel.proportions())
            assertEquals(failedWrites, panel.writes())

            panel.inner.shouldFailWrite = true
            assertFailsWith<IllegalStateException> { checkpoint.restore() }
            assertEquals(before, panel.proportions())
            assertTrue(checkpoint.hasPending)
            val restoredWrites = panel.writes()
            checkpoint.restore()
            checkpoint.releaseResources()
            assertEquals(before, panel.proportions())
            assertEquals(restoredWrites, panel.writes())
            assertFalse(checkpoint.hasPending)
        }

    @Test
    fun `DEFAULT gates owned listeners while rollback preserves interleaved listener identities and order`() =
        withPanel { panel ->
            panel.settings.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
            panel.manager.apply()
            panel.branchTree.addTreeExpansionListener(ForeignExpansionListener())
            panel.changesTree.addTreeExpansionListener(ForeignExpansionListener())
            val ownedListeners = panel.listeners()
            val before = panel.proportions()
            val writes = panel.writes()
            val saved = panel.savedWidth()
            val checkpoint = panel.manager.capture(WorkspaceWidth(PanelWidthMode.DEFAULT.name, -20, -10, -30))
            assertEquals(2, checkpoint.listenerRecords.size)
            assertTrue(checkpoint.listenerRecords.all { it.before == 1 && it.planned == 0 && it.lastWritten == 1 })
            assertEquals(ownedListeners, panel.listeners())
            assertTrue(checkpoint.apply())
            assertEquals(ownedListeners, panel.listeners())
            panel.branchWidth = 490
            panel.changesWidth = 490
            panel.fireLayout()
            panel.manager.flushDebounceForTesting()
            assertEquals(before, panel.proportions())
            assertEquals(saved, panel.savedWidth())
            checkpoint.restore()
            checkpoint.releaseResources()
            assertEquals(ownedListeners, panel.listeners())
            assertEquals(writes, panel.writes())
            assertEquals(saved, panel.savedWidth())
        }

    @Test
    fun `foreign proportions and replaced native targets cannot be overwritten before or after application`() {
        for (isApplied in listOf(false, true)) {
            for (drift in SplitterDrift.entries) {
                withPanel { panel ->
                    val before = panel.proportions()
                    val checkpoint = panel.manager.capture(WorkspaceWidth(PanelWidthMode.FIXED.name, 200, 500, 300))
                    if (isApplied) assertTrue(checkpoint.apply())
                    when (drift) {
                        SplitterDrift.PROPORTION -> panel.outer.proportion = 0.41f
                        SplitterDrift.COMPONENT -> panel.inner.secondComponent = JPanel()
                        SplitterDrift.CONTENT -> panel.logComponent = JPanel()
                        SplitterDrift.LAF -> UIManager.setLookAndFeel(MetalLookAndFeel())
                        SplitterDrift.DISPOSED -> every { panel.project.isDisposed } returns true
                    }
                    val observed = panel.proportions()
                    val writes = panel.writes()
                    if (!isApplied) assertFalse(checkpoint.apply())
                    checkpoint.restore()
                    val restored = panel.proportions()
                    val restoredWrites = panel.writes()
                    if (isApplied && drift == SplitterDrift.PROPORTION) {
                        assertEquals(listOf(observed[0], before[1]), restored)
                        assertEquals(writes[0], restoredWrites[0])
                    } else if (isApplied && drift == SplitterDrift.COMPONENT) {
                        assertEquals(listOf(before[0], observed[1]), restored)
                        assertEquals(writes[1], restoredWrites[1])
                    } else {
                        assertEquals(observed, restored)
                        assertEquals(writes, restoredWrites)
                    }
                    checkpoint.releaseResources()
                    checkpoint.releaseResources()
                    assertEquals(restored, panel.proportions())
                    assertEquals(restoredWrites, panel.writes())
                    assertFalse(checkpoint.hasPending)
                }
            }
        }
    }

    @Test
    fun `abort retires only newly attached listeners after Log or LAF target loss`() {
        for (drift in listOf(SplitterDrift.CONTENT, SplitterDrift.LAF)) {
            withPanel { panel ->
                panel.settings.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
                panel.manager.apply()
                val originalListeners = panel.listeners()
                val addedTree = JTree()
                addedTree.addTreeExpansionListener(ForeignExpansionListener())
                checkNotNull(panel.inner.secondComponent).add(addedTree)
                val beforeAdded = addedTree.treeExpansionListeners.toList()
                val checkpoint = panel.manager.capture(WorkspaceWidth(PanelWidthMode.AUTO_FIT.name, 300, 700, 300))
                assertTrue(checkpoint.apply())
                assertEquals(beforeAdded.size + 1, addedTree.treeExpansionListeners.size)
                if (drift == SplitterDrift.CONTENT) {
                    panel.logComponent = JPanel()
                } else {
                    UIManager.setLookAndFeel(MetalLookAndFeel())
                }
                val retainedProportions = panel.proportions()
                val retainedWrites = panel.writes()

                checkpoint.restore()
                checkpoint.releaseResources()

                assertEquals(beforeAdded, addedTree.treeExpansionListeners.toList())
                assertEquals(originalListeners, panel.listeners())
                assertEquals(retainedProportions, panel.proportions())
                assertEquals(retainedWrites, panel.writes())
                assertFalse(checkpoint.hasPending)
            }
        }
    }

    @Test
    fun `capture leaves callbacks live while applied checkpoint defers due work until accepted mode can resume`() =
        withPanel { panel ->
            val posted = mutableListOf<Runnable>()
            mockkObject(AccentApplyPlanRunner)
            every { AccentApplyPlanRunner.post(any()) } answers { posted += firstArg<Runnable>() }
            val foreignListeners = panel.listeners()
            panel.settings.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
            panel.manager.apply()
            val candidate = WorkspaceWidth(PanelWidthMode.FIXED.name, 200, 500, 250)
            val stale = panel.manager.capture(candidate)
            panel.branchWidth = 400
            panel.changesWidth = 400
            panel.fireLayout()
            panel.manager.flushDebounceForTesting()
            assertEquals(listOf(0.42f.toRawBits(), 0.58000004f.toRawBits()), panel.proportions())
            assertFalse(stale.apply())
            stale.restore()

            panel.fireLayout()
            val checkpoint = panel.manager.capture(candidate)
            assertTrue(checkpoint.apply())
            val applied = panel.proportions()
            val writes = panel.writes()
            panel.manager.flushDebounceForTesting()
            panel.manager.apply()
            panel.fireLayout()
            panel.manager.flushDebounceForTesting()
            assertEquals(applied, panel.proportions())
            assertEquals(writes, panel.writes())

            panel.settings.gitPanelWidthMode = PanelWidthMode.FIXED.name
            panel.settings.gitPanelFixedWidth = 360
            checkpoint.commit()
            checkpoint.releaseResources()
            checkpoint.releaseResources()
            assertEquals(applied, panel.proportions())
            assertEquals(writes, panel.writes())
            assertTrue(posted.isNotEmpty())
            posted.forEach(Runnable::run)
            assertEquals(foreignListeners, panel.listeners())
            assertEquals(writes, panel.writes())
            panel.manager.flushDebounceForTesting()
            assertEquals(listOf(0.36f.toRawBits(), 0.64f.toRawBits()), panel.proportions())
            assertEquals(PanelWidthMode.FIXED.name, panel.settings.gitPanelWidthMode)
            assertEquals(360, panel.settings.gitPanelFixedWidth)
        }

    private fun withPanel(action: (GitPanelFixture) -> Unit) {
        SwingUtilities.invokeAndWait {
            val previousLaf = UIManager.getLookAndFeel()
            val panel = GitPanelFixture()
            try {
                action(panel)
            } finally {
                every { panel.project.isDisposed } returns false
                panel.manager.dispose()
                UIManager.setLookAndFeel(previousLaf)
            }
        }
    }
}

private data class WidthCase(
    val width: WorkspaceWidth,
    val outer: Float,
    val inner: Float,
)

private enum class SplitterDrift { PROPORTION, COMPONENT, CONTENT, LAF, DISPOSED }

private class GitPanelFixture {
    val settings =
        AyuIslandsState().apply {
            gitPanelWidthMode = PanelWidthMode.DEFAULT.name
            gitPanelAutoFitMinWidth = 200
            gitPanelAutoFitMaxWidth = 500
            gitPanelFixedWidth = 300
        }
    val project = mockk<Project>(relaxed = true)
    private val connection = mockk<MessageBusConnection>(relaxed = true)
    private val windowManager = mockk<ToolWindowManager>(relaxed = true)
    private val listener = slot<ToolWindowManagerListener>()
    val branchTree = JTree()
    val changesTree = JTree()
    var branchWidth = 220
    var changesWidth = 360
    val inner =
        RecordingSplitter().apply {
            firstComponent = JPanel().apply { add(JTable()) }
            secondComponent = JPanel().apply { add(changesTree) }
            setSize(1000, 400)
            proportion = 0.7777778f
        }
    val outer =
        RecordingSplitter().apply {
            firstComponent = JPanel().apply { add(branchTree) }
            secondComponent = inner
            setSize(1000, 400)
            proportion = 0.33333334f
        }
    var logComponent: JComponent = outer
    private val logContent =
        mockk<Content>(relaxed = true) {
            every { tabName } returns "Log"
            every { component } answers { logComponent }
        }
    val contentManager =
        mockk<ContentManager>(relaxed = true) {
            every { contents } returns arrayOf(logContent)
        }
    var createdManager: ContentManager? = contentManager
    val toolWindow =
        mockk<ToolWindow>(relaxed = true) {
            every { id } returns "Version Control"
            every { isVisible } returns true
            every { contentManagerIfCreated } answers { createdManager }
            every { this@mockk.contentManager } returns this@GitPanelFixture.contentManager
        }
    val manager: GitPanelAutoFitManager

    init {
        every { project.isDisposed } returns false
        every { project.messageBus } returns
            mockk<MessageBus> {
                every { connect(any<Disposable>()) } returns connection
            }
        every { connection.subscribe(ToolWindowManagerListener.TOPIC, capture(listener)) } returns Unit
        every { ToolWindowManager.getInstance(project) } returns windowManager
        every { windowManager.getToolWindow("Version Control") } returns toolWindow
        every { windowManager.activeToolWindowId } returns "Version Control"
        every { AyuIslandsSettings.getInstance() } returns
            mockk<AyuIslandsSettings> {
                every { state } answers {
                    dev.ayuislands.studio.StudioFixtures
                        .legacyView(settings)
                        .settings
                }
            }
        every { LicenseChecker.isLicensedOrGrace() } returns true
        every { AutoFitCalculator.measureTreeMaxRowWidth(branchTree) } answers { branchWidth }
        every { AutoFitCalculator.measureTreeMaxRowWidth(changesTree) } answers { changesWidth }
        branchTree.addTreeExpansionListener(ForeignExpansionListener())
        changesTree.addTreeExpansionListener(ForeignExpansionListener())
        manager = GitPanelAutoFitManager(project)
    }

    fun proportions(): List<Int> = listOf(outer.proportion.toRawBits(), inner.proportion.toRawBits())

    fun writes(): List<Int> = listOf(outer.writes, inner.writes)

    fun listeners(): List<List<TreeExpansionListener>> =
        listOf(branchTree.treeExpansionListeners.toList(), changesTree.treeExpansionListeners.toList())

    fun savedWidth(): WorkspaceWidth =
        WorkspaceWidth(
            settings.gitPanelWidthMode,
            settings.gitPanelAutoFitMinWidth,
            settings.gitPanelAutoFitMaxWidth,
            settings.gitPanelFixedWidth,
        )

    fun fireLayout() = listener.captured.stateChanged(windowManager, ToolWindowManagerEventType.SetLayout)
}

private class RecordingSplitter : Splitter() {
    var writes = 0
        private set
    var shouldFailWrite = false

    override fun setProportion(proportion: Float) {
        super.setProportion(proportion)
        writes++
        if (shouldFailWrite) {
            shouldFailWrite = false
            error("Splitter proportion changed before failure")
        }
    }
}

private class ForeignExpansionListener : TreeExpansionListener {
    override fun treeExpanded(event: TreeExpansionEvent) = Unit

    override fun treeCollapsed(event: TreeExpansionEvent) = Unit
}
