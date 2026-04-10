package dev.ayuislands.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontPresetTest {
    // ---- isCurated ----

    @Test
    fun `curated presets return isCurated true`() {
        val curated = listOf(FontPreset.WHISPER, FontPreset.AMBIENT, FontPreset.NEON, FontPreset.CYBERPUNK)
        for (preset in curated) {
            assertTrue(preset.isCurated, "${preset.name} should be curated")
        }
    }

    @Test
    fun `CUSTOM returns isCurated false`() {
        assertFalse(FontPreset.CUSTOM.isCurated)
    }

    // ---- fromName ----

    @Test
    fun `fromName resolves all enum entries by name`() {
        for (preset in FontPreset.entries) {
            assertEquals(preset, FontPreset.fromName(preset.name))
        }
    }

    @Test
    fun `fromName with null falls back to AMBIENT`() {
        assertEquals(FontPreset.AMBIENT, FontPreset.fromName(null))
    }

    @Test
    fun `fromName with unknown string falls back to AMBIENT`() {
        assertEquals(FontPreset.AMBIENT, FontPreset.fromName("NONEXISTENT"))
    }

    @Test
    fun `fromName with empty string falls back to AMBIENT`() {
        assertEquals(FontPreset.AMBIENT, FontPreset.fromName(""))
    }

    // ---- Legacy name migration ----

    @Test
    fun `fromName migrates GLOW_WRITER to WHISPER`() {
        assertEquals(FontPreset.WHISPER, FontPreset.fromName("GLOW_WRITER"))
    }

    @Test
    fun `fromName migrates CLEAN to AMBIENT`() {
        assertEquals(FontPreset.AMBIENT, FontPreset.fromName("CLEAN"))
    }

    @Test
    fun `fromName migrates MODERN to AMBIENT`() {
        assertEquals(FontPreset.AMBIENT, FontPreset.fromName("MODERN"))
    }

    @Test
    fun `fromName migrates COMPACT to NEON`() {
        assertEquals(FontPreset.NEON, FontPreset.fromName("COMPACT"))
    }

    // ---- migrateCustomizations ----

    @Test
    fun `migrateCustomizations renames legacy keys`() {
        val map =
            mutableMapOf(
                "GLOW_WRITER" to "14|1.4|true|LIGHT",
                "CLEAN" to "13|1.3|true|REGULAR",
            )

        FontPreset.migrateCustomizations(map)

        assertFalse(map.containsKey("GLOW_WRITER"), "Old key GLOW_WRITER should be removed")
        assertFalse(map.containsKey("CLEAN"), "Old key CLEAN should be removed")
        assertEquals("14|1.4|true|LIGHT", map["WHISPER"])
        assertEquals("13|1.3|true|REGULAR", map["AMBIENT"])
    }

    @Test
    fun `migrateCustomizations does not overwrite existing new key`() {
        val map =
            mutableMapOf(
                "GLOW_WRITER" to "old-value",
                "WHISPER" to "existing-value",
            )

        FontPreset.migrateCustomizations(map)

        assertEquals("existing-value", map["WHISPER"], "Existing key should not be overwritten")
        assertFalse(map.containsKey("GLOW_WRITER"), "Old key should still be removed")
    }

    @Test
    fun `migrateCustomizations handles empty map`() {
        val map = mutableMapOf<String, String>()
        FontPreset.migrateCustomizations(map)
        assertTrue(map.isEmpty())
    }

    @Test
    fun `migrateCustomizations ignores non-legacy keys`() {
        val map =
            mutableMapOf(
                "AMBIENT" to "13|1.3|true|REGULAR",
                "CUSTOM" to "15|1.5|false|MEDIUM",
            )

        FontPreset.migrateCustomizations(map)

        assertEquals(2, map.size)
        assertEquals("13|1.3|true|REGULAR", map["AMBIENT"])
        assertEquals("15|1.5|false|MEDIUM", map["CUSTOM"])
    }

    // ---- Enum property invariants ----

    @Test
    fun `all curated presets have non-empty fontAliases`() {
        for (preset in FontPreset.entries) {
            if (preset.isCurated) {
                assertTrue(preset.fontAliases.isNotEmpty(), "${preset.name} should have aliases")
            }
        }
    }

    @Test
    fun `CUSTOM has empty fontAliases`() {
        assertTrue(FontPreset.CUSTOM.fontAliases.isEmpty())
    }

    @Test
    fun `all presets have positive fontSize`() {
        for (preset in FontPreset.entries) {
            assertTrue(preset.fontSize > 0f, "${preset.name} fontSize should be positive")
        }
    }

    @Test
    fun `all presets have positive lineSpacing`() {
        for (preset in FontPreset.entries) {
            assertTrue(preset.lineSpacing > 0f, "${preset.name} lineSpacing should be positive")
        }
    }

    @Test
    fun `entries count is 5`() {
        assertEquals(5, FontPreset.entries.size)
    }
}
