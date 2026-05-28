package dev.ayuislands.syntax

import dev.ayuislands.preset.ColorPreset
import dev.ayuislands.preset.PresetFamily
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the [SyntaxPreset] franchise contract:
 *
 *  - 5 entries in canonical order (Whisper / Ambient / Neon / Cyberpunk / Custom).
 *  - Every entry implements the [ColorPreset] marker (revised D-11 — marker only).
 *  - Companion implements the optional [PresetFamily] adapter (opt-in per D-11).
 *  - `fromName` and `detect` fall back to [SyntaxPreset.AMBIENT] for null /
 *    unknown / tampered persisted values (D-23 safety net).
 *
 * No reflection — type-erased `is` checks keep the suite dependency-free of
 * `kotlin-reflect` without compiler-known-true assertions.
 */
class SyntaxPresetTest {
    @Test
    fun `SyntaxPreset has exactly 5 entries`() {
        assertEquals(5, SyntaxPreset.entries.size)
    }

    @Test
    fun `SyntaxPreset entries are in canonical order WHISPER AMBIENT NEON CYBERPUNK CUSTOM`() {
        assertEquals(
            listOf(
                SyntaxPreset.WHISPER,
                SyntaxPreset.AMBIENT,
                SyntaxPreset.NEON,
                SyntaxPreset.CYBERPUNK,
                SyntaxPreset.CUSTOM,
            ),
            SyntaxPreset.entries.toList(),
        )
    }

    @Test
    fun `every SyntaxPreset entry has a non-blank displayName`() {
        for (entry in SyntaxPreset.entries) {
            assertTrue(
                entry.displayName.isNotBlank(),
                "SyntaxPreset.$entry must declare a non-blank displayName",
            )
        }
    }

    @Test
    fun `fromName resolves a known enum name to its entry`() {
        assertEquals(SyntaxPreset.AMBIENT, SyntaxPreset.fromName("AMBIENT"))
    }

    @Test
    fun `fromName falls back to AMBIENT for null per D-23 safety net`() {
        assertEquals(SyntaxPreset.AMBIENT, SyntaxPreset.fromName(null))
    }

    @Test
    fun `fromName falls back to AMBIENT for an unknown name per D-23 safety net`() {
        assertEquals(SyntaxPreset.AMBIENT, SyntaxPreset.fromName("nonsense"))
    }

    @Test
    fun `SyntaxPreset entries implement the ColorPreset marker per revised D-11`() {
        val whisper: Any = SyntaxPreset.WHISPER
        assertTrue(whisper is ColorPreset)
    }

    @Test
    fun `detect resolves AMBIENT from a config with selectedPreset = AMBIENT`() {
        val config = SyntaxPresetConfig(selectedPreset = "AMBIENT", customOverrides = emptyMap())
        assertEquals(SyntaxPreset.AMBIENT, SyntaxPreset.detect(config))
    }

    @Test
    fun `detect resolves NEON from a config with selectedPreset = NEON`() {
        val config = SyntaxPresetConfig(selectedPreset = "NEON", customOverrides = emptyMap())
        assertEquals(SyntaxPreset.NEON, SyntaxPreset.detect(config))
    }

    @Test
    fun `detect falls back to AMBIENT for a tampered selectedPreset string per D-23`() {
        val config = SyntaxPresetConfig(selectedPreset = "tampered", customOverrides = emptyMap())
        assertEquals(SyntaxPreset.AMBIENT, SyntaxPreset.detect(config))
    }

    @Test
    fun `SyntaxPreset companion implements the optional PresetFamily adapter from Plan 50-01`() {
        val companion: Any = SyntaxPreset.Companion
        assertTrue(
            companion is PresetFamily<*, *>,
            "SyntaxPreset.Companion must adopt the optional PresetFamily adapter (revised D-11)",
        )
    }
}
