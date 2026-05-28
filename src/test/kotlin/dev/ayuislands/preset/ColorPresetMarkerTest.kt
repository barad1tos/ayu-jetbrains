package dev.ayuislands.preset

import dev.ayuislands.font.FontPreset
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.vcs.VcsColorPreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Type-level marker adoption gate for the [ColorPreset] franchise unification.
 *
 * Locks the revised D-11 decision (2026-05-24, 5-of-5 reviewer consensus):
 *  - [ColorPreset] is a PURE marker — no `<TConfig>` generic, no companion contract.
 *  - [PresetFamily] is the OPTIONAL adapter — franchises opt in per need.
 *  - [GlowPreset.detect], [VcsColorPreset.byName], [FontPreset.fromName] keep
 *    their existing native signatures verbatim — no wrapper class, no synthetic
 *    `detect()` aliases, no caller-fix cascade.
 *
 * Type-erased marker checks (`is ColorPreset`) — no reflection, no `kotlin-reflect`
 * dependency required, and no compiler-known-true assertions.
 */
class ColorPresetMarkerTest {
    @Test
    fun `ColorPreset is a pure marker with no type parameters`() {
        assertEquals(
            0,
            ColorPreset::class.typeParameters.size,
            "ColorPreset MUST stay marker-only (revised D-11 — 2026-05-24). " +
                "Do not regress to ColorPreset<TConfig>; that approach was rejected by 5-of-5 reviewer consensus.",
        )
    }

    @Test
    fun `PresetFamily is the optional generic adapter with two type parameters`() {
        assertEquals(
            2,
            PresetFamily::class.typeParameters.size,
            "PresetFamily<P, TConfig> is the opt-in adapter for franchises that fit the single-TConfig detect model.",
        )
    }

    @Test
    fun `every GlowPreset entry implements ColorPreset marker`() {
        assertEntriesImplementColorPreset("GlowPreset", GlowPreset.entries)
    }

    @Test
    fun `every VcsColorPreset entry implements ColorPreset marker`() {
        assertEntriesImplementColorPreset("VcsColorPreset", VcsColorPreset.entries)
    }

    @Test
    fun `every FontPreset entry implements ColorPreset marker`() {
        assertEntriesImplementColorPreset("FontPreset", FontPreset.entries)
    }

    @Test
    fun `every SyntaxPreset entry implements ColorPreset marker (INTENSITY-18 4-of-4)`() {
        assertEntriesImplementColorPreset("SyntaxPreset", SyntaxPreset.entries)
    }

    @Test
    fun `displayName surfaces through the marker for every franchise`() {
        val whisperGlow: ColorPreset = GlowPreset.WHISPER
        assertEquals("Whisper", whisperGlow.displayName)
        val ambientVcs: ColorPreset = VcsColorPreset.AMBIENT
        assertEquals("Ambient", ambientVcs.displayName)
        val neonFont: ColorPreset = FontPreset.NEON
        assertEquals("Neon", neonFont.displayName)
    }

    @Test
    fun `GlowPreset detect keeps the 4-parameter signature (no GlowDetectionContext wrapper)`() {
        // Compile-time lock: if the signature regressed to a wrapper type, this call would not compile.
        // Verified output is the historic algorithm result; no behavioral change.
        val detected =
            GlowPreset.detect(
                GlowStyle.SOFT,
                GlowPreset.WHISPER.intensity!!,
                GlowPreset.WHISPER.width!!,
                GlowAnimation.NONE,
            )
        assertEquals(GlowPreset.WHISPER, detected)
    }

    @Test
    fun `VcsColorPreset uses byName as canonical lookup (no fromName alias)`() {
        assertEquals(VcsColorPreset.WHISPER, VcsColorPreset.byName("WHISPER"))
        assertEquals(VcsColorPreset.AMBIENT, VcsColorPreset.byName(null))
        assertEquals(VcsColorPreset.AMBIENT, VcsColorPreset.byName("tampered"))
    }

    @Test
    fun `FontPreset uses fromName as canonical lookup (no detect synthesis)`() {
        assertEquals(FontPreset.AMBIENT, FontPreset.fromName("AMBIENT"))
        // Tampered/null falls back to AMBIENT via existing fromName semantics.
        assertEquals(FontPreset.AMBIENT, FontPreset.fromName(null))
    }

    private fun assertEntriesImplementColorPreset(
        franchiseName: String,
        entries: Iterable<Any>,
    ) {
        for (entry in entries) {
            assertTrue(entry is ColorPreset, "$franchiseName.$entry must implement ColorPreset")
        }
    }
}
