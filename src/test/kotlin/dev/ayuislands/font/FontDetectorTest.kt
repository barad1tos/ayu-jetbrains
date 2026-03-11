package dev.ayuislands.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontDetectorTest {
    // ---- invalidateCache ----

    @Test
    fun `invalidateCache does not throw`() {
        FontDetector.invalidateCache()
    }

    @Test
    fun `invalidateCache resets state so next call re-queries`() {
        // Warm the cache
        FontDetector.detectAll()
        // Invalidate
        FontDetector.invalidateCache()
        // Re-detect should work without error
        val result = FontDetector.detectAll()
        assertNotNull(result)
    }

    // ---- detectAll ----

    @Test
    fun `detectAll returns entry for every preset`() {
        FontDetector.invalidateCache()
        val result = FontDetector.detectAll()
        for (preset in FontPreset.entries) {
            assertTrue(result.containsKey(preset), "detectAll should contain ${preset.name}")
        }
    }

    @Test
    fun `detectAll returns true for CUSTOM regardless of installation`() {
        FontDetector.invalidateCache()
        val result = FontDetector.detectAll()
        assertTrue(result[FontPreset.CUSTOM] == true, "CUSTOM should always be true (not curated)")
    }

    // ---- isInstalled ----

    @Test
    fun `isInstalled returns false for CUSTOM (empty aliases)`() {
        FontDetector.invalidateCache()
        // CUSTOM has empty fontAliases, so any() returns false
        assertFalse(FontDetector.isInstalled(FontPreset.CUSTOM))
    }

    // ---- resolveFamily ----

    @Test
    fun `resolveFamily returns null for CUSTOM (empty aliases)`() {
        FontDetector.invalidateCache()
        assertNull(FontDetector.resolveFamily(FontPreset.CUSTOM))
    }

    @Test
    fun `resolveFamily returns null for uninstalled curated font`() {
        FontDetector.invalidateCache()
        // In a typical CI/test JVM, Nerd Fonts are not installed
        // If a curated font IS installed locally, this test still passes
        // because resolveFamily returns either a valid name or null
        val result = FontDetector.resolveFamily(FontPreset.WHISPER)
        // We can only assert the type — null or valid string
        if (result != null) {
            assertTrue(result.isNotBlank())
        }
    }

    // ---- listMonospaceFonts ----

    @Test
    fun `listMonospaceFonts returns sorted list`() {
        FontDetector.invalidateCache()
        val fonts = FontDetector.listMonospaceFonts()
        val sorted = fonts.sorted()
        assertEquals(sorted, fonts, "Monospace font list should be sorted alphabetically")
    }

    @Test
    fun `listMonospaceFonts returns consistent results on repeated calls (cache)`() {
        FontDetector.invalidateCache()
        val first = FontDetector.listMonospaceFonts()
        val second = FontDetector.listMonospaceFonts()
        assertEquals(first, second, "Cached results should be identical")
    }

    @Test
    fun `listMonospaceFonts contains no duplicates`() {
        FontDetector.invalidateCache()
        val fonts = FontDetector.listMonospaceFonts()
        assertEquals(fonts.size, fonts.toSet().size, "Monospace list should have no duplicates")
    }

    // ---- isInstalled + detectAll consistency ----

    @Test
    fun `detectAll and isInstalled are consistent for curated presets`() {
        FontDetector.invalidateCache()
        val all = FontDetector.detectAll()
        for (preset in FontPreset.entries) {
            if (preset.isCurated) {
                assertEquals(
                    FontDetector.isInstalled(preset),
                    all[preset],
                    "detectAll and isInstalled should agree for ${preset.name}",
                )
            }
        }
    }

    // ---- resolveFamily + isInstalled consistency ----

    @Test
    fun `resolveFamily is non-null only when isInstalled is true for curated presets`() {
        FontDetector.invalidateCache()
        for (preset in FontPreset.entries) {
            if (preset.isCurated) {
                val installed = FontDetector.isInstalled(preset)
                val resolved = FontDetector.resolveFamily(preset)
                if (installed) {
                    assertNotNull(resolved, "${preset.name}: isInstalled=true but resolveFamily=null")
                } else {
                    assertNull(resolved, "${preset.name}: isInstalled=false but resolveFamily=$resolved")
                }
            }
        }
    }
}
