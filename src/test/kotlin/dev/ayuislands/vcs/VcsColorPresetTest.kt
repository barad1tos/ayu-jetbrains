package dev.ayuislands.vcs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural coverage for [VcsColorPreset] — the named-profile enum that the
 * Settings panel persists by `.name` and resolves via [VcsColorPreset.byName].
 *
 * `byName` is the defensive read at the XML boundary; if a future code path
 * accidentally falls through it with an unknown / corrupted string, the
 * user's VCS surfaces would silently tint to a random preset. The fallback
 * lands on [AMBIENT] so a corrupted persisted value is a visual no-op rather
 * than a surprise tint.
 *
 * [CUSTOM.intensityFor] is documented as a sentinel — callers branch on the
 * preset and read the per-category map directly. The fallback exists to
 * protect against fall-through, and this test pins the AMBIENT-slider
 * value so a regression that swapped the sentinel for `0` (which would map
 * to a Whisper-level tint) fails loudly.
 */
class VcsColorPresetTest {
    @Test
    fun `byName resolves every declared enum constant exactly`() {
        for (preset in VcsColorPreset.entries) {
            assertEquals(preset, VcsColorPreset.byName(preset.name))
        }
    }

    @Test
    fun `byName returns AMBIENT for null`() {
        assertEquals(VcsColorPreset.AMBIENT, VcsColorPreset.byName(null))
    }

    @Test
    fun `byName returns AMBIENT for empty string`() {
        assertEquals(VcsColorPreset.AMBIENT, VcsColorPreset.byName(""))
    }

    @Test
    fun `byName returns AMBIENT for unknown enum names`() {
        assertEquals(VcsColorPreset.AMBIENT, VcsColorPreset.byName("AMBIENT_OBSOLETE"))
        assertEquals(VcsColorPreset.AMBIENT, VcsColorPreset.byName("MUTED"))
        assertEquals(VcsColorPreset.AMBIENT, VcsColorPreset.byName("not-a-preset"))
    }

    @Test
    fun `CUSTOM intensityFor returns AMBIENT_SLIDER sentinel for every category`() {
        // The CUSTOM branch is a fall-through sentinel — callers must branch
        // on the preset and read the per-category snapshot map directly. If
        // a future refactor changes the sentinel from AMBIENT_SLIDER to 0,
        // any accidental CUSTOM.intensityFor caller would silently tint
        // every category to Whisper instead of stock.
        for (category in VcsColorCategory.entries) {
            assertEquals(VcsColorPreset.AMBIENT_SLIDER, VcsColorPreset.CUSTOM.intensityFor(category))
        }
    }

    @Test
    fun `named presets pin their slider position across every category`() {
        for (category in VcsColorCategory.entries) {
            assertEquals(VcsColorPreset.WHISPER_SLIDER, VcsColorPreset.WHISPER.intensityFor(category))
            assertEquals(VcsColorPreset.AMBIENT_SLIDER, VcsColorPreset.AMBIENT.intensityFor(category))
            assertEquals(VcsColorPreset.NEON_SLIDER, VcsColorPreset.NEON.intensityFor(category))
            assertEquals(VcsColorPreset.CYBERPUNK_SLIDER, VcsColorPreset.CYBERPUNK.intensityFor(category))
        }
    }
}
