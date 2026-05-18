package dev.ayuislands.accent.toolbar

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.popup.IslandsUiPill
import dev.ayuislands.accent.toolbar.popup.SegmentedControl
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave-7 redesign coverage per 48-REDESIGN-SPEC §3.3:
 *   - layout is [SegmentedControl] + [IslandsUiPill] (no `JRadioButton` /
 *     `JCheckBox` left in the file),
 *   - selecting a segment calls `applyVariantAndChrome` via
 *     `LafManager.setCurrentLookAndFeel(laf, false)` (Pitfall 5 lock),
 *   - missing theme in `installedThemes` is a warn-and-return no-op (Pitfall 7 fail-safe).
 */
class VariantSwitcherRowTest {
    private val lafManager = mockk<LafManager>(relaxed = true)
    private val mirageTheme = mockk<UIThemeLookAndFeelInfo>(relaxed = true)

    @BeforeTest
    fun setUp() {
        mockkStatic(LafManager::class)
        every { LafManager.getInstance() } returns lafManager
        every { lafManager.currentUIThemeLookAndFeel } returns mirageTheme
        every { mirageTheme.name } returns "Ayu Mirage"
        every { lafManager.installedThemes } returns sequenceOf(mirageTheme)
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns null
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any()) } returns "#FFB454"
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `layout is SegmentedControl plus IslandsUiPill (no radios or checkboxes)`() {
        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val children = row.component.components
        assertEquals(2, children.size, "Expected exactly two children: SegmentedControl + IslandsUiPill")
        assertTrue(children.any { it is SegmentedControl }, "Missing SegmentedControl")
        assertTrue(children.any { it is IslandsUiPill }, "Missing IslandsUiPill")
    }

    @Test
    fun `source contains zero JRadioButton or JCheckBox references (Wave 7 redesign)`() {
        val source = Files.readString(Paths.get(SOURCE_PATH))
        val radioCount = "JRadioButton".toRegex().findAll(source).count()
        val checkboxCount = "JCheckBox".toRegex().findAll(source).count()
        assertEquals(0, radioCount, "Wave 7 redesign: zero JRadioButton references allowed")
        assertEquals(0, checkboxCount, "Wave 7 redesign: zero JCheckBox references allowed")
    }

    @Test
    fun `selecting a SegmentedControl cell resolves theme by name and calls setCurrentLookAndFeel`() {
        val darkTheme = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { darkTheme.name } returns "Ayu Dark"
        every { lafManager.installedThemes } returns sequenceOf(mirageTheme, darkTheme)

        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val segmented =
            row.component.components
                .filterIsInstance<SegmentedControl>()
                .single()
        // Programmatic selection — fires the callback synchronously.
        segmented.setSelectedVariant(AyuVariant.DARK)
        // setSelectedVariant only repaints; the onSelectionChanged callback fires on
        // mouse click. We simulate the click by walking children and dispatching to
        // the Dark cell directly.
        val cells = segmented.components.toList()
        val darkCell = cells[1] // DARK is the second entry in AyuVariant.entries
        val click =
            java.awt.event.MouseEvent(
                darkCell,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                0L,
                0,
                5,
                5,
                1,
                false,
                java.awt.event.MouseEvent.BUTTON1,
            )
        darkCell.dispatchEvent(click)

        verify { lafManager.setCurrentLookAndFeel(darkTheme, false) }
        verify { lafManager.updateUI() }
    }

    @Test
    fun `applyVariantAndChrome swallows RuntimeException from installedThemes lookup`() {
        // Pattern B regression lock — `installedThemes` access throws a
        // RuntimeException (e.g. plugin reload race). Must NOT propagate to
        // the segment-click handler; chip stays usable.
        every { lafManager.installedThemes } throws RuntimeException("install race")

        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val segmented =
            row.component.components
                .filterIsInstance<SegmentedControl>()
                .single()
        val darkCell = segmented.components[1]
        val click =
            java.awt.event.MouseEvent(
                darkCell,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                0L,
                0,
                5,
                5,
                1,
                false,
                java.awt.event.MouseEvent.BUTTON1,
            )
        // Must NOT throw.
        darkCell.dispatchEvent(click)
        verify(exactly = 0) { lafManager.setCurrentLookAndFeel(any<UIThemeLookAndFeelInfo>(), any()) }
    }

    @Test
    fun `applyVariantAndChrome swallows RuntimeException from setCurrentLookAndFeel`() {
        // Pattern B — the platform LAF setter can throw on a malformed
        // UIThemeLookAndFeelInfo. The chip must absorb the throw and log,
        // not crash the popup mouse chain.
        val darkTheme = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { darkTheme.name } returns "Ayu Dark"
        every { lafManager.installedThemes } returns sequenceOf(mirageTheme, darkTheme)
        every {
            lafManager.setCurrentLookAndFeel(any<UIThemeLookAndFeelInfo>(), any())
        } throws RuntimeException("malformed LAF")

        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val segmented =
            row.component.components
                .filterIsInstance<SegmentedControl>()
                .single()
        val darkCell = segmented.components[1]
        val click =
            java.awt.event.MouseEvent(
                darkCell,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                0L,
                0,
                5,
                5,
                1,
                false,
                java.awt.event.MouseEvent.BUTTON1,
            )
        // Must NOT throw.
        darkCell.dispatchEvent(click)
        // updateUI must NOT run when setCurrentLookAndFeel threw.
        verify(exactly = 0) { lafManager.updateUI() }
    }

    @Test
    fun `missing theme in installedThemes is a warn-and-return no-op (Pitfall 7)`() {
        every { lafManager.installedThemes } returns emptySequence()

        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val segmented =
            row.component.components
                .filterIsInstance<SegmentedControl>()
                .single()
        val darkCell = segmented.components[1]
        val click =
            java.awt.event.MouseEvent(
                darkCell,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                0L,
                0,
                5,
                5,
                1,
                false,
                java.awt.event.MouseEvent.BUTTON1,
            )
        darkCell.dispatchEvent(click)

        verify(exactly = 0) { lafManager.setCurrentLookAndFeel(any<UIThemeLookAndFeelInfo>(), any()) }
        verify(exactly = 0) { lafManager.updateUI() }
    }

    @Test
    fun `toggling IslandsUiPill re-applies the currently selected variant with the new flavour`() {
        val islandsTheme = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { islandsTheme.name } returns "Ayu Mirage (Islands UI)"
        every { lafManager.installedThemes } returns sequenceOf(mirageTheme, islandsTheme)

        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val pill =
            row.component.components
                .filterIsInstance<IslandsUiPill>()
                .single()
        val click =
            java.awt.event.MouseEvent(
                pill,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                0L,
                0,
                5,
                5,
                1,
                false,
                java.awt.event.MouseEvent.BUTTON1,
            )
        pill.dispatchEvent(click)

        verify { lafManager.setCurrentLookAndFeel(islandsTheme, false) }
    }

    private companion object {
        const val SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/VariantSwitcherRow.kt"
    }
}
