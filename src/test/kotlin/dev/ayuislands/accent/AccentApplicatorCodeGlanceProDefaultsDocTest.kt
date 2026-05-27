package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the three compiled CodeGlance Pro viewport default constants to their
 * javap-verified values.
 *
 * The defaults were extracted via `javap` from
 * `com.nasller.codeglance.config.CodeGlanceConfig.<init>` in
 * `CodeGlancePro-2.0.2.jar`:
 *   - `CGP_DEFAULT_VIEWPORT_COLOR            = "00FF00"`
 *   - `CGP_DEFAULT_VIEWPORT_BORDER_COLOR     = "A0A0A0"`
 *   - `CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS = 0`
 *
 * Each test asserts the exact compiled value directly off
 * [CodeGlanceProIntegration]; the equality also rejects any `#` prefix, because
 * CGP stores hex as plain 6-char uppercase strings and an invalid hex string
 * would silently break the viewport reset. The javap re-verification recipe
 * lives as a KDoc comment on the constants in `CodeGlanceProIntegration.kt` —
 * run it when bumping the CGP plugin version.
 */
class AccentApplicatorCodeGlanceProDefaultsDocTest {
    @Test
    fun `CGP viewport color default stays the javap-verified 00FF00`() {
        assertEquals("00FF00", CodeGlanceProIntegration.CGP_DEFAULT_VIEWPORT_COLOR)
    }

    @Test
    fun `CGP viewport border color default stays the javap-verified A0A0A0`() {
        assertEquals("A0A0A0", CodeGlanceProIntegration.CGP_DEFAULT_VIEWPORT_BORDER_COLOR)
    }

    @Test
    fun `CGP viewport border thickness default stays 0`() {
        assertEquals(0, CodeGlanceProIntegration.CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS)
    }
}
