package dev.ayuislands.accent.toolbar

import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.ToggleTile
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Container
import java.awt.GridLayout
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Related-toggles section coverage:
 *   - layout is a 2 × 2 [GridLayout] of [ToggleTile] composites,
 *   - tile labels in fixed top-to-bottom / left-to-right order,
 *   - Chrome tinting gates the user's configured chrome surfaces without rewriting them,
 *   - tile clicks drive the runtime side-effects users expect immediately.
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
        every { AccentResolver.resolve(any(), any<AccentContext>()) } returns "#FFB454"
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#FFB454"
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.DARK
        mockkObject(AccentContext.Companion)
        every { AccentContext.detectQuickSwitcher() } returns AccentContext.Ayu(AyuVariant.DARK)
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
        // Preserve the visual tile order.
        assertEquals(
            expected,
            labels.filter { it in expected },
            "Tile order drifted",
        )
    }

    @Test
    fun `clicking Chrome tinting off preserves chrome surface choices and reapplies focused accent`() {
        val state = AyuIslandsSettings.getInstance().state
        state.chromeStatusBar = true
        state.chromeMainToolbar = false
        state.chromeToolWindowStripe = false
        state.chromeNavBar = false
        state.chromePanelBorder = false
        state.chromeTintingEnabled = true
        every { AccentApplicator.applyForFocusedProject(any<AccentContext>()) } returns "#FFB454"

        val section = QuickSwitcherRelatedTogglesSection()
        val chromeTile =
            (section.component as JPanel)
                .components
                .filterIsInstance<ToggleTile>()
                .first { tile -> labelOf(tile) == "Chrome tinting" }

        chromeTile.toggleSwitch.flip()

        assertEquals(false, state.chromeTintingEnabled, "Chrome tile must disable only the runtime chrome gate")
        assertEquals(true, state.chromeStatusBar, "Chrome tile must preserve status-bar chrome preference")
        assertEquals(false, state.chromeMainToolbar, "Chrome tile must preserve main-toolbar chrome preference")
        assertEquals(
            false,
            state.chromeToolWindowStripe,
            "Chrome tile must preserve tool-window stripe chrome preference",
        )
        assertEquals(false, state.chromeNavBar, "Chrome tile must preserve nav-bar chrome preference")
        assertEquals(false, state.chromePanelBorder, "Chrome tile must preserve panel-border chrome preference")
        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(AccentContext.Ayu(AyuVariant.DARK)) }
    }

    @Test
    fun `clicking Chrome tinting on restores the existing chrome surface choices`() {
        val state = AyuIslandsSettings.getInstance().state
        state.chromeStatusBar = true
        state.chromeMainToolbar = false
        state.chromeToolWindowStripe = false
        state.chromeNavBar = false
        state.chromePanelBorder = false
        state.chromeTintingEnabled = false
        every { AccentApplicator.applyForFocusedProject(any<AccentContext>()) } returns "#FFB454"

        val section = QuickSwitcherRelatedTogglesSection()
        val chromeTile =
            (section.component as JPanel)
                .components
                .filterIsInstance<ToggleTile>()
                .first { tile -> labelOf(tile) == "Chrome tinting" }

        chromeTile.toggleSwitch.flip()

        assertEquals(true, state.chromeTintingEnabled, "Chrome tile must re-enable only the runtime chrome gate")
        assertEquals(true, state.chromeStatusBar, "Chrome tile must restore the user's status-bar-only setup")
        assertEquals(false, state.chromeMainToolbar, "Chrome tile must not promote main-toolbar chrome")
        assertEquals(false, state.chromeToolWindowStripe, "Chrome tile must not promote tool-window stripe chrome")
        assertEquals(false, state.chromeNavBar, "Chrome tile must not promote nav-bar chrome")
        assertEquals(false, state.chromePanelBorder, "Chrome tile must not promote panel-border chrome")
        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(AccentContext.Ayu(AyuVariant.DARK)) }
    }

    @Test
    fun `clicking Glow off persists state and syncs overlays immediately`() {
        val state = AyuIslandsSettings.getInstance().state
        state.glowEnabled = true
        mockkObject(GlowOverlayManager.Companion)
        every { GlowOverlayManager.syncGlowForAllProjects() } returns Unit

        val section = QuickSwitcherRelatedTogglesSection()
        val glowTile =
            (section.component as JPanel)
                .components
                .filterIsInstance<ToggleTile>()
                .first { tile -> labelOf(tile) == "Glow" }

        glowTile.toggleSwitch.flip()

        assertEquals(false, state.glowEnabled, "Glow tile must persist disabled state")
        verify(exactly = 1) { GlowOverlayManager.syncGlowForAllProjects() }
    }

    @Test
    fun `clicking Accent rotation off stops rotation service immediately`() {
        val state = AyuIslandsSettings.getInstance().state
        state.accentRotationEnabled = true
        val rotationService = mockk<AccentRotationService>(relaxed = true)
        mockkObject(AccentRotationService.Companion)
        every { AccentRotationService.getInstance() } returns rotationService

        val section = QuickSwitcherRelatedTogglesSection()
        val rotationTile =
            (section.component as JPanel)
                .components
                .filterIsInstance<ToggleTile>()
                .first { tile -> labelOf(tile) == "Accent rotation" }

        rotationTile.toggleSwitch.flip()

        assertEquals(false, state.accentRotationEnabled, "Rotation tile must persist disabled state")
        verify(exactly = 1) { rotationService.stopRotation() }
    }

    @Test
    fun `clicking Follow system accent on disables rotation and reapplies focused accent`() {
        val state = AyuIslandsSettings.getInstance().state
        state.followSystemAccent = false
        state.accentRotationEnabled = true
        val rotationService = mockk<AccentRotationService>(relaxed = true)
        mockkObject(AccentRotationService.Companion)
        every { AccentRotationService.getInstance() } returns rotationService
        every { AccentApplicator.applyForFocusedProject(any<AccentContext>()) } returns "#7DCFFF"

        val section = QuickSwitcherRelatedTogglesSection()
        val followTile =
            (section.component as JPanel)
                .components
                .filterIsInstance<ToggleTile>()
                .first { tile -> labelOf(tile) == "Follow system accent" }

        followTile.toggleSwitch.flip()

        assertEquals(true, state.followSystemAccent, "Follow tile must persist enabled state")
        assertEquals(false, state.accentRotationEnabled, "Follow system accent must disable accent rotation")
        verify(exactly = 1) { rotationService.stopRotation() }
        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(AccentContext.Ayu(AyuVariant.DARK)) }
    }

    @Test
    fun `bindSelected reads back the matching state field at construction time`() {
        val state = AyuIslandsSettings.getInstance().state
        state.chromeTintingEnabled = false
        state.chromeStatusBar = true
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
    fun `density grid gap matches Density TILE_GAP`() {
        val section = QuickSwitcherRelatedTogglesSection()
        val layout = (section.component as JPanel).layout as GridLayout
        val expected =
            com.intellij.util.ui.JBUI
                .scale(Density.TILE_GAP)
        assertEquals(expected, layout.hgap)
        assertEquals(expected, layout.vgap)
    }

    @Test
    fun `selected toggle paint resolves accent through external context`() {
        val state = AyuIslandsSettings.getInstance().state
        state.chromeStatusBar = true
        every { AccentContext.detectQuickSwitcher() } returns AccentContext.External
        every { AccentResolver.resolve(any(), AccentContext.External) } returns "#5CCFE6"

        val section = QuickSwitcherRelatedTogglesSection()
        val chromeTile =
            (section.component as JPanel)
                .components
                .filterIsInstance<ToggleTile>()
                .first { tile -> labelOf(tile) == "Chrome tinting" }

        chromeTile.toggleSwitch.setSize(32, 16)
        val image = BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            chromeTile.toggleSwitch.paintForTest(graphics)
        } finally {
            graphics.dispose()
        }

        verify(exactly = 1) { AccentResolver.resolve(any(), AccentContext.External) }
    }

    private fun labelOf(tile: ToggleTile): String = findLabelText(tile)

    private fun findLabelText(tile: ToggleTile): String {
        val labels = collectLabelTexts(tile)
        // Labels include any leading icon JLabel (text empty) — keep the non-empty.
        return labels.firstOrNull { it.isNotEmpty() }.orEmpty()
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
}
