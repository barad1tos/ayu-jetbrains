package dev.ayuislands.accent.toolbar

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.PopupSwatch
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wave-7 redesign coverage per 48-REDESIGN-SPEC §3.4:
 *   - grid is 2 × 6 [PopupSwatch] cells (was 3 × 4 of `JButton` / `ColorIcon`),
 *   - each preset hex maps to exactly one swatch,
 *   - the Custom… link uses the bundled `pipette.svg` (Locked Answer #1),
 *   - click flow through `applyFromHexString` + Pattern D + Pattern B preserved.
 */
class QuickSwitcherAccentGridTest {
    @BeforeTest
    fun setUp() {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.DARK
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns null
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any()) } returns AYU_ACCENT_PRESETS.first().hex
        // Quick-actions row + other premium components reach for
        // ApplicationManager.getApplication() during DumbAwareAction init.
        mockkStatic(ApplicationManager::class)
        val mockApp = mockk<Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns mockApp
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `grid is GRID_ROWS=2 GRID_COLS=6 with PopupSwatch cells`() {
        val grid = QuickSwitcherAccentGrid()
        val north = (grid.component as JPanel).components.filterIsInstance<JPanel>().first()
        val layout = north.layout as GridLayout
        assertEquals(2, layout.rows)
        assertEquals(6, layout.columns)
        assertEquals(north.components.size, AYU_ACCENT_PRESETS.size)
        north.components.forEach { child ->
            assertTrue(child is PopupSwatch, "Expected PopupSwatch child, got ${child.javaClass.simpleName}")
        }
    }

    @Test
    fun `swatch gap matches Density SWATCH_GAP`() {
        val grid = QuickSwitcherAccentGrid()
        val north = (grid.component as JPanel).components.filterIsInstance<JPanel>().first()
        val layout = north.layout as GridLayout
        assertEquals(
            com.intellij.util.ui.JBUI
                .scale(Density.SWATCH_GAP),
            layout.hgap,
        )
        assertEquals(
            com.intellij.util.ui.JBUI
                .scale(Density.SWATCH_GAP),
            layout.vgap,
        )
    }

    @Test
    fun `component renders exactly one PopupSwatch per AYU_ACCENT_PRESETS entry`() {
        val grid = QuickSwitcherAccentGrid()
        val north = (grid.component as JPanel).components.filterIsInstance<JPanel>().first()
        assertEquals(12, north.components.size, "Expected 12 presets per D-12 / RESEARCH §4")
    }

    @Test
    fun `Custom and More icon-link row appears below the grid`() {
        val grid = QuickSwitcherAccentGrid()
        val south = (grid.component as JPanel).components.filterIsInstance<JPanel>().last()
        assertNotNull(south, "Expected a SOUTH link row")
        // Two link wrappers (icon + ActionLink) — assert non-zero child count.
        assertTrue(south.components.isNotEmpty())
        // Walk descendants for a JLabel showing the pipette icon.
        val labels = collectJLabels(south)
        assertTrue(labels.isNotEmpty(), "Custom… / More… row must carry leading JLabel icons")
    }

    @Test
    fun `source loads pipette dot svg via IconLoader (Locked Answer 1)`() {
        val source = Files.readString(Paths.get(GRID_SOURCE_PATH))
        assertTrue(source.contains("pipette.svg"), "Custom… link must reuse the bundled pipette.svg")
        assertTrue(source.contains("IconLoader.getIcon"), "Must load via IconLoader.getIcon")
    }

    @Test
    fun `click invokes applyFromHexString AND notifyExternalApply when apply returns true (Pattern D)`() {
        every { AccentApplicator.applyFromHexString(any()) } returns true
        mockkObject(ProjectAccentSwapService.Companion)
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService

        val grid = QuickSwitcherAccentGrid()
        val north = (grid.component as JPanel).components.filterIsInstance<JPanel>().first()
        val firstSwatch = north.components.first() as PopupSwatch
        firstSwatch.setSize(36, 24)
        firstSwatch.dispatchEvent(makePress(firstSwatch))
        firstSwatch.dispatchEvent(makeRelease(firstSwatch))

        verify(exactly = 1) { AccentApplicator.applyFromHexString(AYU_ACCENT_PRESETS.first().hex) }
        verify(exactly = 1) { swapService.notifyExternalApply(AYU_ACCENT_PRESETS.first().hex) }
    }

    @Test
    fun `click does NOT call notifyExternalApply when apply returns false (Pattern D inverse)`() {
        every { AccentApplicator.applyFromHexString(any()) } returns false
        mockkObject(ProjectAccentSwapService.Companion)
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService

        val grid = QuickSwitcherAccentGrid()
        val north = (grid.component as JPanel).components.filterIsInstance<JPanel>().first()
        val firstSwatch = north.components.first() as PopupSwatch
        firstSwatch.setSize(36, 24)
        firstSwatch.dispatchEvent(makePress(firstSwatch))
        firstSwatch.dispatchEvent(makeRelease(firstSwatch))

        verify(exactly = 0) { swapService.notifyExternalApply(any()) }
    }

    @Test
    fun `click swallows RuntimeException from applyFromHexString (Pattern B)`() {
        every { AccentApplicator.applyFromHexString(any()) } throws RuntimeException("transient")
        mockkObject(ProjectAccentSwapService.Companion)
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService

        val grid = QuickSwitcherAccentGrid()
        val north = (grid.component as JPanel).components.filterIsInstance<JPanel>().first()
        val firstSwatch = north.components.first() as PopupSwatch
        firstSwatch.setSize(36, 24)
        // Must NOT propagate.
        firstSwatch.dispatchEvent(makePress(firstSwatch))
        firstSwatch.dispatchEvent(makeRelease(firstSwatch))
        verify(exactly = 0) { swapService.notifyExternalApply(any()) }
        assertTrue(firstSwatch.isEnabled, "Swatch must stay enabled after a swallowed RuntimeException")
    }

    private fun makePress(source: javax.swing.JComponent) =
        java.awt.event.MouseEvent(
            source,
            java.awt.event.MouseEvent.MOUSE_PRESSED,
            0L,
            0,
            5,
            5,
            1,
            false,
            java.awt.event.MouseEvent.BUTTON1,
        )

    private fun makeRelease(source: javax.swing.JComponent) =
        java.awt.event.MouseEvent(
            source,
            java.awt.event.MouseEvent.MOUSE_RELEASED,
            0L,
            0,
            5,
            5,
            1,
            false,
            java.awt.event.MouseEvent.BUTTON1,
        )

    private fun collectJLabels(
        root: java.awt.Container,
        out: MutableList<JLabel> = mutableListOf(),
    ): List<JLabel> {
        for (child in root.components) {
            if (child is JLabel) out.add(child)
            if (child is java.awt.Container) collectJLabels(child, out)
        }
        return out
    }

    private companion object {
        const val GRID_SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherAccentGrid.kt"
    }
}
