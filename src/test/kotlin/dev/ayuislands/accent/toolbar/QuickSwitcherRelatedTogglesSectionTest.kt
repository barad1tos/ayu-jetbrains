package dev.ayuislands.accent.toolbar

import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.ToggleTile
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.awt.Container
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave-7 redesign coverage per 48-REDESIGN-SPEC §3.5:
 *   - layout is a 2 × 2 [GridLayout] of [ToggleTile] composites,
 *   - tile labels in fixed top-to-bottom / left-to-right order,
 *   - Chrome tinting still binds to `chromeStatusBar` ONLY (Locked Answer #3 —
 *     `chromeMainToolbar` / `chromeToolWindowStripe` / `chromeNavBar` /
 *     `chromePanelBorder` are forbidden in this file),
 *   - D-13 single-source-of-truth bindSelected plumbing preserved.
 */
class QuickSwitcherRelatedTogglesSectionTest {
    @BeforeTest
    fun setUp() {
        val state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>(relaxed = true)
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns null
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any()) } returns "#FFB454"
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.DARK
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `layout is 2x2 GridLayout`() {
        val section = QuickSwitcherRelatedTogglesSection()
        val layout = (section.component as JPanel).layout as GridLayout
        assertEquals(2, layout.rows)
        assertEquals(2, layout.columns)
    }

    @Test
    fun `grid contains exactly 4 ToggleTile children`() {
        val section = QuickSwitcherRelatedTogglesSection()
        val tiles = (section.component as JPanel).components.filterIsInstance<ToggleTile>()
        assertEquals(4, tiles.size, "Expected exactly 4 ToggleTile children")
    }

    @Test
    fun `tile labels in fixed order Chrome tinting Glow Accent rotation Follow system`() {
        val section = QuickSwitcherRelatedTogglesSection()
        val labels = collectLabelTexts(section.component as Container)
        val expected = listOf("Chrome tinting", "Glow", "Accent rotation", "Follow system accent")
        assertTrue(labels.containsAll(expected), "Tile labels drifted; got=$labels expected to include $expected")
        // Preserve the visual D-09 / D-13 order.
        assertEquals(
            expected,
            labels.filter { it in expected },
            "Order drifted from D-09/D-13",
        )
    }

    @Test
    fun `Chrome tinting binds to chromeStatusBar only (Locked Answer 3)`() {
        val source = Files.readString(Paths.get(SOURCE_PATH))
        val statusBarRefs = "chromeStatusBar".toRegex().findAll(source).count()
        assertTrue(statusBarRefs >= 2, "Expected ≥2 chromeStatusBar refs (getter + setter), got $statusBarRefs")
        for (forbidden in FORBIDDEN_SURFACES) {
            val count = forbidden.toRegex().findAll(source).count()
            assertEquals(0, count, "Locked Answer #3: must NOT reference $forbidden (count=$count)")
        }
    }

    @Test
    fun `bindSelected reads back the matching state field at construction time`() {
        val state = AyuIslandsSettings.getInstance().state
        state.chromeStatusBar = false
        state.glowEnabled = true
        state.accentRotationEnabled = false
        state.followSystemAccent = true

        val section = QuickSwitcherRelatedTogglesSection()
        val tiles = (section.component as JPanel).components.filterIsInstance<ToggleTile>()
        val byLabel = tiles.associateBy { label -> findLabelText(label) }
        assertEquals(false, byLabel["Chrome tinting"]?.toggleSwitch?.isSelected)
        assertEquals(true, byLabel["Glow"]?.toggleSwitch?.isSelected)
        assertEquals(false, byLabel["Accent rotation"]?.toggleSwitch?.isSelected)
        assertEquals(true, byLabel["Follow system accent"]?.toggleSwitch?.isSelected)
    }

    @Test
    fun `source carries at least four bindSelected calls (Pattern Q regression lock)`() {
        val source = Files.readString(Paths.get(SOURCE_PATH))
        val count = "bindSelected".toRegex().findAll(source).count()
        assertTrue(count >= 4, "Expected ≥4 bindSelected calls, got $count")
    }

    @Test
    fun `source has zero AccentApplicator calls (Pattern G adjacency lock)`() {
        val source = Files.readString(Paths.get(SOURCE_PATH))
        val applyHex = "applyFromHexString".toRegex().findAll(source).count()
        assertEquals(0, applyHex, "Must NOT call applyFromHexString from the toggles section (Pattern G)")
    }

    @Test
    fun `density grid gap matches Density TILE_GAP`() {
        val section = QuickSwitcherRelatedTogglesSection()
        val layout = (section.component as JPanel).layout as GridLayout
        val expected =
            com.intellij.util.ui.JBUI
                .scale(Density.TILE_GAP)
        assertEquals(expected, layout.hgap)
        assertEquals(expected, layout.vgap)
    }

    private fun findLabelText(tile: ToggleTile): String {
        val labels = collectLabelTexts(tile)
        // Labels include any leading icon JLabel (text empty) — keep the non-empty.
        return labels.firstOrNull { it.isNotEmpty() } ?: ""
    }

    private fun collectLabelTexts(
        root: Container,
        out: MutableList<String> = mutableListOf(),
    ): List<String> {
        for (child in root.components) {
            if (child is JLabel) {
                val text = child.text
                if (!text.isNullOrEmpty()) out.add(text)
            }
            if (child is Container) collectLabelTexts(child, out)
        }
        return out
    }

    private companion object {
        const val SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherRelatedTogglesSection.kt"
        val FORBIDDEN_SURFACES =
            listOf(
                "chromeMainToolbar",
                "chromeToolWindowStripe",
                "chromeNavBar",
                "chromePanelBorder",
            )
    }
}
