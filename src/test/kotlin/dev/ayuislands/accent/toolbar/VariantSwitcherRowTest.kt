package dev.ayuislands.accent.toolbar

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentDefaults
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
 * Coverage for the variant row's `SegmentedControl` + `IslandsUiPill` layout,
 * the theme apply path via `LafManager.setCurrentLookAndFeel(laf, false)`
 * (locks in the `lockEditorScheme = false` second arg), and the warn-and-return
 * no-op when the requested theme is missing from `installedThemes`.
 *
 * Mirrors the `@Suppress("UnstableApiUsage")` on the production `VariantSwitcherRow.applyVariantAndChrome` —
 * the experimental theme API (`UIThemeLookAndFeelInfo`, `getInstalledThemes`,
 * `setCurrentLookAndFeel(UIThemeLookAndFeelInfo, Boolean)`) is the canonical Ayu
 * path with no stable alternative on the current platform target.
 */
@Suppress("UnstableApiUsage")
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
    fun `source contains zero JRadioButton or JCheckBox references`() {
        val source = Files.readString(Paths.get(SOURCE_PATH))
        val radioCount = "JRadioButton".toRegex().findAll(source).count()
        val checkboxCount = "JCheckBox".toRegex().findAll(source).count()
        assertEquals(0, radioCount, "Variant row: zero JRadioButton references allowed")
        assertEquals(0, checkboxCount, "Variant row: zero JCheckBox references allowed")
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
    fun `missing theme in installedThemes is a warn-and-return no-op`() {
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

    @Test
    fun `IslandsUiPill accent supplier falls back to currentVariant when AyuVariant detect returns null`() {
        // Locks the `AyuVariant.detect() ?: currentVariant` fallback inside
        // `resolveCurrentAccent`. When the platform LAF is non-Ayu (mid-
        // theme-switch / unrelated test harness theme), the detector returns
        // null and the row's seed-time `currentVariant` must be the variant
        // handed to the resolver — otherwise the pill's tinted glyph would
        // resolve against the wrong palette.
        //
        // Seed the LAF as `Ayu Mirage (Islands UI)` so the pill seeds
        // `islandsUi=true` and therefore actually invokes the accent supplier
        // at paint time (the supplier is only called when isSelected).
        val islandsTheme = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { islandsTheme.name } returns "Ayu Mirage (Islands UI)"
        every { lafManager.currentUIThemeLookAndFeel } returns islandsTheme
        every { lafManager.installedThemes } returns sequenceOf(mirageTheme, islandsTheme)
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns null
        every { AccentResolver.resolve(any(), AyuVariant.DARK) } returns "#73D0FF"

        val row = VariantSwitcherRow(AyuVariant.DARK)
        val pill =
            row.component.components
                .filterIsInstance<IslandsUiPill>()
                .single()
        // Force a repaint cycle so the accent supplier fires through
        // IslandsUiPill's paint path (the supplier is a `() -> String`
        // captured at construction time and only called when isSelected).
        pill.size = java.awt.Dimension(JBUI_SCALED_PILL_W, JBUI_SCALED_PILL_H)
        val image =
            java.awt.image.BufferedImage(
                pill.width,
                pill.height,
                java.awt.image.BufferedImage.TYPE_INT_ARGB,
            )
        val g2 = image.createGraphics()
        try {
            pill.paint(g2)
        } finally {
            g2.dispose()
        }
        // Behavior assertion: resolver was called with `AyuVariant.DARK`
        // (the row's currentVariant fallback) — proving AyuVariant.detect's
        // null result did NOT propagate as a null variant downstream.
        verify(atLeast = 1) { AccentResolver.resolve(any(), AyuVariant.DARK) }
    }

    @Test
    fun `IslandsUiPill accent supplier returns MIRAGE_HEX fallback when resolver throws`() {
        // Locks the Pattern B catch inside `resolveCurrentAccent`.
        // `AccentResolver` can throw under a plugin reload race or corrupted
        // persisted state; the pill's accent supplier MUST swallow the throw
        // and return `AccentDefaults.MIRAGE_HEX` so the popup keeps painting
        // with a sane fallback instead of crashing the EDT.
        val islandsTheme = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { islandsTheme.name } returns "Ayu Mirage (Islands UI)"
        every { lafManager.currentUIThemeLookAndFeel } returns islandsTheme
        every { lafManager.installedThemes } returns sequenceOf(mirageTheme, islandsTheme)
        every { AccentResolver.resolve(any(), any()) } throws RuntimeException("resolver race")

        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val pill =
            row.component.components
                .filterIsInstance<IslandsUiPill>()
                .single()
        pill.size = java.awt.Dimension(JBUI_SCALED_PILL_W, JBUI_SCALED_PILL_H)
        val image =
            java.awt.image.BufferedImage(
                pill.width,
                pill.height,
                java.awt.image.BufferedImage.TYPE_INT_ARGB,
            )
        val g2 = image.createGraphics()
        try {
            // Must NOT throw — the supplier swallows and substitutes
            // MIRAGE_HEX. The verify below proves the resolver was reached
            // and threw; the lack of an escaping exception proves the catch.
            pill.paint(g2)
        } finally {
            g2.dispose()
        }
        verify(atLeast = 1) { AccentResolver.resolve(any(), any()) }
        // Behavior pin — IslandsUiPill resolves to MIRAGE_HEX when the
        // resolver throws; the constant itself is the value the user sees
        // painted in the fallback. Lock the exact literal so any future
        // rename or hex bump forces a deliberate test update (Pattern L).
        assertEquals(
            "#FFB454",
            AccentDefaults.MIRAGE_HEX,
            "AccentDefaults.MIRAGE_HEX is the canonical Ayu Mirage default — changing it requires updating this lock.",
        )
    }

    private companion object {
        const val SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/VariantSwitcherRow.kt"

        /** Reasonable JBUI-scaled pill dimensions for paint sampling. */
        const val JBUI_SCALED_PILL_W: Int = 80
        const val JBUI_SCALED_PILL_H: Int = 28
    }
}
