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
 * Quick-switcher redesign coverage:
 *   - grid is 2 × 6 [PopupSwatch] cells (was 3 × 4 of `JButton` / `ColorIcon`),
 *   - each preset hex maps to exactly one swatch,
 *   - the Custom… link uses the bundled `pipette.svg`,
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
        assertEquals(12, north.components.size, "Expected 12 presets")
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
    fun `source loads pipette dot svg via IconLoader`() {
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
    fun `More link click swallows RuntimeException from ShowSettingsUtil (Pattern B)`() {
        // Pattern B — the platform's ShowSettingsUtil throws
        // IllegalArgumentException if a configurable id is unknown, and
        // ProcessCanceledException (a RuntimeException subclass on 2025.1+) if
        // the dialog is dismissed mid-build. The More… click handler must
        // absorb both so the chip stays responsive.
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
        val links = collectActionLinks(south)
        // The More… link is the second one (Custom… first per declaration order).
        // Must NOT throw out of the click.
        links[1].doClick()
        verify(exactly = 1) { showUtil.showSettingsDialog(any(), eq("Ayu Islands")) }
    }

    @Test
    fun `Custom link click swallows RuntimeException from ColorPicker (Pattern B)`() {
        // Pattern B — the platform's ColorPicker dialog can throw
        // ProcessCanceledException (a RuntimeException subclass) when the
        // popup chain is dismissed mid-build. The Custom… click handler
        // must absorb the throw so the chip stays responsive for the next
        // click.
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns null
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), AyuVariant.MIRAGE) } returns "#FFB454"
        mockkStatic(com.intellij.ui.ColorPicker::class)
        every {
            com.intellij.ui.ColorPicker.showDialog(
                any(),
                any<String>(),
                any(),
                any<Boolean>(),
                any(),
                any<Boolean>(),
            )
        } throws RuntimeException("user dismissed mid-build")

        val grid = QuickSwitcherAccentGrid()
        val south = (grid.component as JPanel).components.filterIsInstance<JPanel>().last()
        val links = collectActionLinks(south)
        // The Custom… link is the first one.
        // Must NOT throw out of the click.
        links[0].doClick()
        verify(exactly = 1) {
            com.intellij.ui.ColorPicker.showDialog(
                any(),
                eq("Choose Accent Color"),
                any(),
                any<Boolean>(),
                any(),
                any<Boolean>(),
            )
        }
    }

    @Test
    fun `More link opens the Ayu Islands settings dialog (user-space)`() {
        // User-space coverage. The More… link must reach
        // ShowSettingsUtil.showSettingsDialog with the canonical "Ayu Islands"
        // group id. Without this test, a rename of the link wiring would land
        // silently and the link would become inert.
        //
        // The Custom… link no longer routes here — it opens ColorPicker
        // directly (covered by the next test). This test now only verifies
        // the More… link still reaches settings.
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
        // The More… link is the second one (Custom… first per declaration order).
        links[1].doClick()
        verify(exactly = 1) { showUtil.showSettingsDialog(any(), eq("Ayu Islands")) }
    }

    @Test
    fun `Custom link opens ColorPicker dialog seeded with current accent (user-space)`() {
        // User-space coverage. The Custom… link must open the native
        // ColorPicker dialog directly — not the Settings dialog — so users
        // can pick a colour without navigating into Settings → Ayu Islands.
        // Locks the wiring so a future refactor that silently routes Custom…
        // back to openAyuSettings fails this test.
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns null
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), AyuVariant.MIRAGE) } returns "#FFB454"
        mockkStatic(com.intellij.ui.ColorPicker::class)
        every {
            com.intellij.ui.ColorPicker.showDialog(
                any(),
                any<String>(),
                any(),
                any<Boolean>(),
                any(),
                any<Boolean>(),
            )
        } returns null

        val grid = QuickSwitcherAccentGrid()
        val south = (grid.component as JPanel).components.filterIsInstance<JPanel>().last()
        val links = collectActionLinks(south)
        // The Custom… link is the first one (per declaration order).
        links[0].doClick()
        verify(exactly = 1) {
            com.intellij.ui.ColorPicker.showDialog(
                any(),
                eq("Choose Accent Color"),
                any(),
                any<Boolean>(),
                any(),
                any<Boolean>(),
            )
        }
    }

    @Test
    fun `clicking a swatch restamps selected flag so exactly one swatch is selected`() {
        // User-space restamp coverage. After applyPreset(hex), exactly one
        // swatch is selected (the clicked one); previously-selected swatches clear.
        // Locks the for-each restamp in `QuickSwitcherAccentGrid`.
        every { AccentApplicator.applyFromHexString(any()) } returns true
        mockkObject(ProjectAccentSwapService.Companion)
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService

        val grid = QuickSwitcherAccentGrid()
        val north = (grid.component as JPanel).components.filterIsInstance<JPanel>().first()
        val swatches = north.components.filterIsInstance<PopupSwatch>()
        // `swatch.hex` is the [AccentHex] value class; unwrap via `.value` for
        // case-insensitive String comparison at the test boundary.
        val secondHex = swatches[1].hex.value
        // Click the second swatch.
        swatches[1].setSize(36, 24)
        swatches[1].dispatchEvent(makePress(swatches[1]))
        swatches[1].dispatchEvent(makeRelease(swatches[1]))

        val selectedCount = swatches.count { it.selected }
        assertEquals(1, selectedCount, "Exactly one swatch must be selected after apply; got $selectedCount")
        assertTrue(swatches[1].selected, "Clicked swatch must be selected after apply")
        // Belt-and-braces: prior selection (index 0 by default) must have cleared.
        assertTrue(!swatches[0].selected || swatches[0].hex.value.equals(secondHex, ignoreCase = true))
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
