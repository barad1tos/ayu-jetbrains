package dev.ayuislands.accent.elements

import dev.ayuislands.accent.ChromeTintBlender
import dev.ayuislands.accent.WcagForeground
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Aggregate WCAG-contrast matrix for Phase 40 chrome foreground picks.
 *
 * Closes VERIFICATION Gap 2 at the algorithmic level: across every
 * supported `(element × accent × intensity)` tuple the blender produces a
 * tinted background which, when fed to [WcagForeground.pickForeground],
 * returns a foreground meeting the target WCAG 2.1 AA threshold.
 *
 * Scope:
 *  - Elements: `StatusBar`, `NavBar`, `ToolWindowStripe`, `MainToolbar`
 *    (`PanelBorder` is excluded — no text on borders).
 *  - Accents: representative 5-colour set covering the perceptual span of
 *    the Ayu accent palette (warm/cold/saturated/pastel extremes).
 *  - Intensities: 20 (subtle), 40 (default — matches
 *    `DEFAULT_CHROME_TINT_INTENSITY`), 80 (near-max saturated).
 *  - Targets: `PRIMARY_TEXT` for StatusBar/NavBar/MainToolbar (text
 *    surfaces, 4.5:1 floor); `ICON` for ToolWindowStripe (icon buttons,
 *    3.0:1 floor).
 *
 * A single assertion per tuple keeps failures granular — the failure
 * message names the exact `element / accent / intensity / ratio /
 * threshold` so a regression pinpoints the offending combination.
 *
 * Also locks the spot-check from plan 40-10 W-3: the canonical Ayu dark
 * foreground `Color(0x1F2430)` on pure white exceeds 14.0 (actual ≈ 14.7).
 * This protects the WcagForeground palette from accidental drift — if
 * someone edits `DARK_FOREGROUND_HEX`, the absolute-contrast lock fails
 * before the matrix even runs.
 */
class ChromeForegroundWcagMatrixTest {
    private val darkBase = Color(0x1F, 0x24, 0x30)

    @BeforeTest
    fun setUp() {
        // Feed ChromeTintBlender a deterministic stock background per base
        // key so the matrix exercises blend output, not UIManager wiring.
        mockkStatic(UIManager::class)
        every { UIManager.getColor(any<String>()) } returns darkBase
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `W-3 spot check — Ayu dark foreground on white exceeds 14 to 1`() {
        val ratio = WcagForeground.contrastRatio(Color.WHITE, darkBase)
        assertTrue(
            ratio >= W3_MIN_RATIO,
            "W-3 lock: contrast(WHITE, 0x1F2430) = $ratio, expected >= $W3_MIN_RATIO",
        )
    }

    @Test
    fun `every element-accent-intensity tuple meets WCAG AA`() {
        val failures = mutableListOf<String>()

        for (spec in ELEMENT_SPECS) {
            for (accent in PALETTE) {
                for (intensity in INTENSITIES) {
                    val tinted = ChromeTintBlender.blend(accent.color, spec.sampleKey, intensity)
                    val foreground = WcagForeground.pickForeground(tinted, spec.target)
                    val ratio = WcagForeground.contrastRatio(foreground, tinted)
                    if (ratio < spec.target.minRatio) {
                        failures.add(
                            "element=${spec.name} accent=${accent.name} " +
                                "intensity=$intensity ratio=%.3f threshold=%.1f".format(
                                    ratio,
                                    spec.target.minRatio,
                                ),
                        )
                    }
                }
            }
        }

        assertTrue(
            failures.isEmpty(),
            "WCAG AA violations across ${failures.size} tuple(s):\n" + failures.joinToString("\n"),
        )
    }

    private data class ElementSpec(
        val name: String,
        val sampleKey: String,
        val target: WcagForeground.TextTarget,
    )

    private data class AccentSample(
        val name: String,
        val color: Color,
    )

    private companion object {
        /** WCAG ratio that the Ayu dark foreground on pure white must exceed (plan 40-10 W-3). */
        private const val W3_MIN_RATIO = 14.0

        private val ELEMENT_SPECS =
            listOf(
                ElementSpec("StatusBar", "StatusBar.background", WcagForeground.TextTarget.PRIMARY_TEXT),
                ElementSpec("NavBar", "NavBar.background", WcagForeground.TextTarget.PRIMARY_TEXT),
                ElementSpec(
                    "ToolWindowStripe",
                    "ToolWindow.Button.selectedBackground",
                    WcagForeground.TextTarget.ICON,
                ),
                ElementSpec("MainToolbar", "MainToolbar.background", WcagForeground.TextTarget.PRIMARY_TEXT),
            )

        // Representative 5-colour set covering the perceptual span of the
        // Ayu accent palette: warm gold, saturated rose, primary cyan,
        // pastel lavender, mid-luminance forest green.
        private val PALETTE =
            listOf(
                AccentSample("Cyan(0x5CCFE6)", Color(0x5C, 0xCF, 0xE6)),
                AccentSample("Rose(0xFF3333)", Color(0xFF, 0x33, 0x33)),
                AccentSample("Gold(0xFFCD66)", Color(0xFF, 0xCD, 0x66)),
                AccentSample("Lavender(0xDFBFFF)", Color(0xDF, 0xBF, 0xFF)),
                AccentSample("ForestGreen(0x66CC66)", Color(0x66, 0xCC, 0x66)),
            )

        private val INTENSITIES = listOf(20, 40, 80)
    }
}
