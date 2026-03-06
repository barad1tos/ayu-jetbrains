package dev.ayuislands.indent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IndentPresetTest {
    @Test
    fun `all named presets have non-null alpha`() {
        for (preset in IndentPreset.entries.filter { it != IndentPreset.CUSTOM }) {
            assertNotNull(preset.alpha, "${preset.name} should have non-null alpha")
        }
    }

    @Test
    fun `CUSTOM has null alpha`() {
        assertNull(IndentPreset.CUSTOM.alpha)
    }

    @Test
    fun `fromName returns correct preset`() {
        assertEquals(IndentPreset.WHISPER, IndentPreset.fromName("WHISPER"))
        assertEquals(IndentPreset.AMBIENT, IndentPreset.fromName("AMBIENT"))
        assertEquals(IndentPreset.NEON, IndentPreset.fromName("NEON"))
        assertEquals(IndentPreset.CYBERPUNK, IndentPreset.fromName("CYBERPUNK"))
        assertEquals(IndentPreset.CUSTOM, IndentPreset.fromName("CUSTOM"))
    }

    @Test
    fun `fromName returns CUSTOM for unknown name`() {
        assertEquals(IndentPreset.CUSTOM, IndentPreset.fromName("UNKNOWN"))
        assertEquals(IndentPreset.CUSTOM, IndentPreset.fromName(""))
    }

    @Test
    fun `detect returns matching preset by alpha`() {
        assertEquals(IndentPreset.WHISPER, IndentPreset.detect(0x1A))
        assertEquals(IndentPreset.AMBIENT, IndentPreset.detect(0x2E))
        assertEquals(IndentPreset.NEON, IndentPreset.detect(0x4D))
        assertEquals(IndentPreset.CYBERPUNK, IndentPreset.detect(0x73))
    }

    @Test
    fun `detect returns CUSTOM for unknown alpha`() {
        assertEquals(IndentPreset.CUSTOM, IndentPreset.detect(42))
        assertEquals(IndentPreset.CUSTOM, IndentPreset.detect(0))
        assertEquals(IndentPreset.CUSTOM, IndentPreset.detect(255))
    }

    @Test
    fun `preset count is exactly 5`() {
        assertEquals(5, IndentPreset.entries.size)
    }
}
