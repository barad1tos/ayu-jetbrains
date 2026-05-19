package dev.ayuislands.settings

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Placement + extraction discipline regression locks for
 * [AyuIslandsAccentPanel]'s Quick Switcher composition.
 *
 *  - Composition order: the Quick Switcher group sits between Chrome Tinting
 *    injection (`afterOverridesInjection?.invoke`) and Accent Rotation
 *    (`panel.buildAccentRotationGroup`).
 *  - Extraction: the panel references `AyuIslandsQuickSwitcherGroupBuilder`
 *    (i.e. the builder lives in its own file, not inlined into the panel).
 *  - LargeClass discipline: the panel's file LOC stays under a soft ceiling so a
 *    future change that wants to grow it substantially must explicitly raise
 *    this constant (NEVER raise detekt's threshold).
 *
 * All three are source-grep / line-count assertions — no platform mocking, no
 * Kotlin UI DSL bootstrap.
 */
class AyuIslandsAccentPanelGroupOrderTest {
    @Test
    fun `buildPanel composes Quick Switcher between Chrome Tinting and Accent Rotation`() {
        val source = Files.readString(Paths.get(ACCENT_PANEL_PATH))
        val lines = source.lines()
        val markers =
            listOf(
                "panel.buildAccentColorGroup",
                "overrides.buildGroup",
                "afterOverridesInjection?.invoke",
                "quickSwitcher.buildGroup",
                "panel.buildAccentRotationGroup",
            )
        val lineIndices =
            markers.map { marker ->
                val idx = lines.indexOfFirst { it.contains(marker) }
                require(idx >= 0) {
                    "Marker '$marker' not found in AyuIslandsAccentPanel.kt"
                }
                idx
            }
        for (i in 1 until lineIndices.size) {
            assertTrue(
                lineIndices[i] > lineIndices[i - 1],
                "Group order violation: '${markers[i]}' (line ${lineIndices[i] + 1}) must appear " +
                    "AFTER '${markers[i - 1]}' (line ${lineIndices[i - 1] + 1})",
            )
        }
    }

    @Test
    fun `AyuIslandsAccentPanel references AyuIslandsQuickSwitcherGroupBuilder (extracted builder)`() {
        val source = Files.readString(Paths.get(ACCENT_PANEL_PATH))
        val matches = "AyuIslandsQuickSwitcherGroupBuilder".toRegex().findAll(source).count()
        assertTrue(
            matches >= 1,
            "AyuIslandsAccentPanel must instantiate AyuIslandsQuickSwitcherGroupBuilder",
        )
    }

    @Test
    fun `AyuIslandsAccentPanel LOC stays under the soft ceiling post-extraction`() {
        // The extracted builder keeps the panel slim — current file sits around 712 LOC.
        // Cap at 720 — a future change that needs to grow the file substantially must raise
        // this constant explicitly with reviewer approval (NEVER raise detekt's LargeClass
        // threshold, per CLAUDE.md).
        val lineCount = Files.lines(Paths.get(ACCENT_PANEL_PATH)).count()
        assertTrue(
            lineCount < SOFT_CEILING,
            "AyuIslandsAccentPanel grew to $lineCount LOC (soft ceiling = $SOFT_CEILING) — " +
                "extraction discipline violated",
        )
    }

    private companion object {
        const val ACCENT_PANEL_PATH = "src/main/kotlin/dev/ayuislands/settings/AyuIslandsAccentPanel.kt"
        const val SOFT_CEILING = 720L
    }
}
