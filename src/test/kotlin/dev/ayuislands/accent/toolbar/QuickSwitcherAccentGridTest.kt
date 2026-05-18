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
    fun `openAyuSettings link click swallows RuntimeException from ShowSettingsUtil (Pattern B)`() {
        // Pattern B — the platform's ShowSettingsUtil throws
        // IllegalArgumentException if a configurable id is unknown, and
        // ProcessCanceledException (a RuntimeException subclass on 2025.1+) if
        // the dialog is dismissed mid-build. Click handler must absorb both so
        // the chip stays responsive.
        mockkStatic(com.intellij.openapi.options.ShowSettingsUtil::class)
        val showUtil = mockk<com.intellij.openapi.options.ShowSettingsUtil>(relaxed = true)
        every {
            com.intellij.openapi.options.ShowSettingsUtil
                .getInstance()
        } returns showUtil
        every {
            showUtil.showSettingsDialog(any(), any<String>())
        } throws RuntimeException("configurable not found")

        val grid = QuickSwitcherAccentGrid()
        val south = (grid.component as JPanel).components.filterIsInstance<JPanel>().last()
        val link = collectActionLinks(south).first()
        // Must NOT throw out of the click.
        link.doClick()
        verify(exactly = 1) { showUtil.showSettingsDialog(any(), eq("Ayu Islands")) }
    }

    @Test
    fun `Custom and More links open the Ayu Islands settings dialog (user-space)`() {
        // IMP-6 — user-space coverage. The Custom and More links must reach
        // ShowSettingsUtil.showSettingsDialog with the canonical "Ayu Islands"
        // group id. Without this test, a rename of the link wiring would land
        // silently and the link would become inert.
        mockkStatic(com.intellij.openapi.options.ShowSettingsUtil::class)
        val showUtil = mockk<com.intellij.openapi.options.ShowSettingsUtil>(relaxed = true)
        every {
            com.intellij.openapi.options.ShowSettingsUtil
                .getInstance()
        } returns showUtil

        val grid = QuickSwitcherAccentGrid()
        val south = (grid.component as JPanel).components.filterIsInstance<JPanel>().last()
        val links = collectActionLinks(south)
        assertEquals(2, links.size, "Expected exactly two ActionLink components (Custom and More)")
        for (link in links) link.doClick()
        verify(exactly = 2) { showUtil.showSettingsDialog(any(), eq("Ayu Islands")) }
    }

    @Test
    fun `clicking a swatch restamps selected flag so exactly one swatch is selected`() {
        // IMP-6 — user-space restamp coverage. After applyPreset(hex), exactly one
        // swatch is selected (the clicked one); previously-selected swatches clear.
        // Locks the for-each restamp at QuickSwitcherAccentGrid.kt:111.
        every { AccentApplicator.applyFromHexString(any()) } returns true
        mockkObject(ProjectAccentSwapService.Companion)
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService

        val grid = QuickSwitcherAccentGrid()
        val north = (grid.component as JPanel).components.filterIsInstance<JPanel>().first()
        val swatches = north.components.filterIsInstance<PopupSwatch>()
        val secondHex = swatches[1].hex
        // Click the second swatch.
        swatches[1].setSize(36, 24)
        swatches[1].dispatchEvent(makePress(swatches[1]))
        swatches[1].dispatchEvent(makeRelease(swatches[1]))

        val selectedCount = swatches.count { it.selected }
        assertEquals(1, selectedCount, "Exactly one swatch must be selected after apply; got $selectedCount")
        assertTrue(swatches[1].selected, "Clicked swatch must be selected after apply")
        // Belt-and-braces: prior selection (index 0 by default) must have cleared.
        assertTrue(!swatches[0].selected || swatches[0].hex.equals(secondHex, ignoreCase = true))
    }

    private fun collectActionLinks(
        root: java.awt.Container,
        out: MutableList<com.intellij.ui.components.ActionLink> = mutableListOf(),
    ): List<com.intellij.ui.components.ActionLink> {
        for (child in root.components) {
            if (child is com.intellij.ui.components.ActionLink) out.add(child)
            if (child is java.awt.Container) collectActionLinks(child, out)
        }
        return out
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
