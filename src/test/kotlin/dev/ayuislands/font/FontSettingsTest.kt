package dev.ayuislands.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontSettingsTest {
    // ---- fromPreset ----

    @Test
    fun `fromPreset creates settings matching preset defaults`() {
        for (preset in FontPreset.entries) {
            val settings = FontSettings.fromPreset(preset)
            assertEquals(preset, settings.preset)
            assertEquals(preset.fontFamily, settings.fontFamily)
            assertEquals(preset.fontSize, settings.fontSize)
            assertEquals(preset.lineSpacing, settings.lineSpacing)
            assertEquals(preset.enableLigatures, settings.enableLigatures)
            assertEquals(preset.defaultWeight, settings.weight)
            assertFalse(settings.applyToConsole)
        }
    }

    // ---- encode / decode round-trip ----

    @Test
    fun `encode and decode round-trip for curated preset`() {
        val original =
            FontSettings(
                preset = FontPreset.WHISPER,
                fontFamily = FontPreset.WHISPER.fontFamily,
                fontSize = 16f,
                lineSpacing = 1.5f,
                enableLigatures = false,
                weight = FontWeight.LIGHT,
                applyToConsole = true,
            )

        val encoded = original.encode()
        val decoded = FontSettings.decode(encoded, FontPreset.WHISPER)

        assertEquals(original.fontSize, decoded.fontSize)
        assertEquals(original.lineSpacing, decoded.lineSpacing)
        assertEquals(original.enableLigatures, decoded.enableLigatures)
        assertEquals(original.weight, decoded.weight)
    }

    @Test
    fun `encode for curated preset does not include fontFamily`() {
        val settings = FontSettings.fromPreset(FontPreset.NEON)
        val encoded = settings.encode()
        // Curated: "size|spacing|ligatures|weight" — 4 parts
        assertEquals(4, encoded.split("|").size)
    }

    @Test
    fun `encode for CUSTOM preset includes fontFamily`() {
        val settings =
            FontSettings(
                preset = FontPreset.CUSTOM,
                fontFamily = "Fira Code",
                fontSize = 14f,
                lineSpacing = 1.3f,
                enableLigatures = true,
                weight = FontWeight.REGULAR,
                applyToConsole = false,
            )
        val encoded = settings.encode()
        // Custom: "size|spacing|ligatures|weight|fontFamily" — 5 parts
        val parts = encoded.split("|")
        assertEquals(5, parts.size)
        assertEquals("Fira Code", parts[4])
    }

    @Test
    fun `decode CUSTOM preset restores fontFamily`() {
        val original =
            FontSettings(
                preset = FontPreset.CUSTOM,
                fontFamily = "Fira Code",
                fontSize = 14f,
                lineSpacing = 1.3f,
                enableLigatures = true,
                weight = FontWeight.REGULAR,
                applyToConsole = false,
            )

        val encoded = original.encode()
        val decoded = FontSettings.decode(encoded, FontPreset.CUSTOM)

        assertEquals("Fira Code", decoded.fontFamily)
        assertEquals(14f, decoded.fontSize)
        assertEquals(1.3f, decoded.lineSpacing)
        assertTrue(decoded.enableLigatures)
        assertEquals(FontWeight.REGULAR, decoded.weight)
    }

    @Test
    fun `decode with null returns preset defaults`() {
        val decoded = FontSettings.decode(null, FontPreset.AMBIENT)
        val expected = FontSettings.fromPreset(FontPreset.AMBIENT)

        assertEquals(expected, decoded)
    }

    @Test
    fun `decode with partial data uses preset defaults for missing fields`() {
        // Only size provided
        val decoded = FontSettings.decode("16", FontPreset.WHISPER)

        assertEquals(16f, decoded.fontSize)
        // Missing fields fall back to preset defaults
        assertEquals(FontPreset.WHISPER.lineSpacing, decoded.lineSpacing)
        assertEquals(FontPreset.WHISPER.enableLigatures, decoded.enableLigatures)
        assertEquals(FontPreset.WHISPER.defaultWeight, decoded.weight)
    }

    @Test
    fun `decode with invalid numeric data uses preset defaults`() {
        val decoded = FontSettings.decode("abc|xyz|maybe|UNKNOWN", FontPreset.NEON)

        // Invalid float → falls back to preset default
        assertEquals(FontPreset.NEON.fontSize, decoded.fontSize)
        assertEquals(FontPreset.NEON.lineSpacing, decoded.lineSpacing)
        // Invalid boolean → falls back to preset default
        assertEquals(FontPreset.NEON.enableLigatures, decoded.enableLigatures)
        // Invalid weight name → falls back to REGULAR (FontWeight.fromName default)
        assertEquals(FontWeight.REGULAR, decoded.weight)
    }

    @Test
    fun `decode with empty string uses preset defaults for all fields`() {
        val decoded = FontSettings.decode("", FontPreset.CYBERPUNK)

        // Empty string split gives [""] — getOrNull(0) = "" which toFloatOrNull returns null
        assertEquals(FontPreset.CYBERPUNK.fontSize, decoded.fontSize)
        assertEquals(FontPreset.CYBERPUNK.lineSpacing, decoded.lineSpacing)
        assertEquals(FontPreset.CYBERPUNK.enableLigatures, decoded.enableLigatures)
        assertEquals(FontPreset.CYBERPUNK.defaultWeight, decoded.weight)
    }

    // ---- encode format ----

    @Test
    fun `encode produces pipe-separated format`() {
        val settings =
            FontSettings(
                preset = FontPreset.AMBIENT,
                fontFamily = FontPreset.AMBIENT.fontFamily,
                fontSize = 13f,
                lineSpacing = 1.3f,
                enableLigatures = true,
                weight = FontWeight.REGULAR,
                applyToConsole = false,
            )

        assertEquals("13.0|1.3|true|REGULAR", settings.encode())
    }

    @Test
    fun `encode preserves fontSize decimal`() {
        val settings = FontSettings.fromPreset(FontPreset.WHISPER).copy(fontSize = 14.7f)
        val encoded = settings.encode()
        assertTrue(encoded.startsWith("14.7|"), "fontSize should preserve decimal: $encoded")
    }

    // ---- applyToConsole is not encoded ----

    @Test
    fun `applyToConsole is not encoded`() {
        val withConsole = FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = true)
        val withoutConsole = FontSettings.fromPreset(FontPreset.AMBIENT).copy(applyToConsole = false)

        assertEquals(withConsole.encode(), withoutConsole.encode())
    }

    // ---- decode restores applyToConsole as false ----

    @Test
    fun `decode always sets applyToConsole to false`() {
        val encoded = FontSettings.fromPreset(FontPreset.AMBIENT).encode()
        val decoded = FontSettings.decode(encoded, FontPreset.AMBIENT)
        assertFalse(decoded.applyToConsole)
    }

    // ---- All presets round-trip ----

    @Test
    fun `all presets survive encode-decode round-trip`() {
        for (preset in FontPreset.entries) {
            val original = FontSettings.fromPreset(preset)
            val encoded = original.encode()
            val decoded = FontSettings.decode(encoded, preset)

            assertEquals(original.fontSize, decoded.fontSize, "fontSize mismatch for ${preset.name}")
            assertEquals(original.lineSpacing, decoded.lineSpacing, "lineSpacing mismatch for ${preset.name}")
            assertEquals(original.enableLigatures, decoded.enableLigatures, "ligatures mismatch for ${preset.name}")
            assertEquals(original.weight, decoded.weight, "weight mismatch for ${preset.name}")
        }
    }

    // ---- Custom preset with various weights ----

    @Test
    fun `encode-decode preserves all FontWeight variants`() {
        for (weight in FontWeight.entries) {
            val settings = FontSettings.fromPreset(FontPreset.AMBIENT).copy(weight = weight)
            val decoded = FontSettings.decode(settings.encode(), FontPreset.AMBIENT)
            assertEquals(weight, decoded.weight, "Weight ${weight.name} should survive round-trip")
        }
    }
}
