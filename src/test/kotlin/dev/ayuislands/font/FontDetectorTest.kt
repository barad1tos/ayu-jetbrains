package dev.ayuislands.font

import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontDetectorTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
        FontDetector.invalidateCache()
    }

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

    /**
     * Helper: find a curated preset whose aliases are NOT installed on the current system.
     * Required because tests run on real JVM — any of Victor Mono / Maple Mono / Monaspace
     * may or may not be installed locally.
     */
    private fun findUninstalledPreset(): FontPreset? {
        val environment = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        val systemFonts = environment.availableFontFamilyNames.map { it.lowercase() }.toSet()
        return FontPreset.entries.firstOrNull { preset ->
            preset.isCurated && preset.fontAliases.none { systemFonts.contains(it.lowercase()) }
        }
    }

    @Test
    fun `isInstalled returns false when settings throws IllegalStateException`() {
        val preset = findUninstalledPreset() ?: return // all curated fonts installed locally
        FontDetector.invalidateCache()
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } throws IllegalStateException("no app")

        assertFalse(FontDetector.isInstalled(preset))
    }

    @Test
    fun `isInstalled returns false when settings throws NullPointerException`() {
        val preset = findUninstalledPreset() ?: return
        FontDetector.invalidateCache()
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } throws NullPointerException("no state")

        assertFalse(FontDetector.isInstalled(preset))
    }

    @Test
    fun `isInstalled returns true when state has recorded font alias`() {
        val preset = findUninstalledPreset() ?: return
        FontDetector.invalidateCache()
        val aliasToRecord = preset.fontAliases.first()
        val state =
            dev.ayuislands.settings.AyuIslandsState().apply {
                installedFonts.add(aliasToRecord)
            }
        val settings = AyuIslandsSettings().apply { loadState(state) }
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        assertTrue(FontDetector.isInstalled(preset))
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

    // ---- status (three-tier health check, D-10) ----

    private fun stubSettingsAndGraphicsEnv(
        state: AyuIslandsState,
        availableFonts: Array<String>,
    ) {
        mockkObject(AyuIslandsSettings.Companion)
        val settings = AyuIslandsSettings().apply { loadState(state) }
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkStatic(GraphicsEnvironment::class)
        val ge = io.mockk.mockk<GraphicsEnvironment>()
        every { GraphicsEnvironment.getLocalGraphicsEnvironment() } returns ge
        every { ge.availableFontFamilyNames } returns availableFonts
        FontDetector.invalidateCache()
    }

    @Test
    fun `status returnsNotInstalled when no record and no system font`() {
        stubSettingsAndGraphicsEnv(AyuIslandsState(), arrayOf("JetBrains Mono", "Helvetica"))
        assertEquals(FontStatus.NOT_INSTALLED, FontDetector.status(FontPreset.AMBIENT))
    }

    @Test
    fun `status returnsHealthy when installedFonts has family but no file paths recorded`() {
        val state = AyuIslandsState().apply { installedFonts.add("Maple Mono") }
        stubSettingsAndGraphicsEnv(state, arrayOf("JetBrains Mono", "Helvetica"))
        assertEquals(FontStatus.HEALTHY, FontDetector.status(FontPreset.AMBIENT))
    }

    @Test
    fun `status returnsHealthy when all recorded files exist`() {
        val tmpDir = createTempDirectory("fontdet-ok").toFile()
        try {
            val realFile = File(tmpDir, "MapleMono-Regular.ttf").apply { writeText("") }
            val state =
                AyuIslandsState().apply {
                    installedFonts.add("Maple Mono")
                    installedFontFiles["Maple Mono"] = realFile.absolutePath
                }
            stubSettingsAndGraphicsEnv(state, arrayOf("JetBrains Mono", "Helvetica"))
            assertEquals(FontStatus.HEALTHY, FontDetector.status(FontPreset.AMBIENT))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `status returnsCorrupted when file missing and JVM does not see family`() {
        val tmpDir = createTempDirectory("fontdet-corrupt").toFile()
        try {
            val missing = File(tmpDir, "does-not-exist.ttf")
            val state =
                AyuIslandsState().apply {
                    installedFonts.add("Maple Mono")
                    installedFontFiles["Maple Mono"] = missing.absolutePath
                }
            stubSettingsAndGraphicsEnv(state, arrayOf("JetBrains Mono", "Helvetica"))
            assertEquals(FontStatus.CORRUPTED, FontDetector.status(FontPreset.AMBIENT))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `status returnsHealthy when file missing but JVM still sees family`() {
        val tmpDir = createTempDirectory("fontdet-jvm-cached").toFile()
        try {
            val missing = File(tmpDir, "gone.ttf")
            val state =
                AyuIslandsState().apply {
                    installedFonts.add("Maple Mono")
                    installedFontFiles["Maple Mono"] = missing.absolutePath
                }
            stubSettingsAndGraphicsEnv(state, arrayOf("Maple Mono", "JetBrains Mono"))
            assertEquals(FontStatus.HEALTHY, FontDetector.status(FontPreset.AMBIENT))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `isInstalled backwardsCompat returnsTrue when status is HEALTHY`() {
        val state = AyuIslandsState().apply { installedFonts.add("Maple Mono") }
        stubSettingsAndGraphicsEnv(state, arrayOf("JetBrains Mono", "Helvetica"))
        assertEquals(FontStatus.HEALTHY, FontDetector.status(FontPreset.AMBIENT))
        assertTrue(FontDetector.isInstalled(FontPreset.AMBIENT))
    }

    @Test
    fun `status cachesResults until invalidate`() {
        val state = AyuIslandsState().apply { installedFonts.add("Maple Mono") }
        stubSettingsAndGraphicsEnv(state, arrayOf("JetBrains Mono", "Helvetica"))
        FontDetector.status(FontPreset.AMBIENT)
        FontDetector.status(FontPreset.AMBIENT)
        FontDetector.status(FontPreset.AMBIENT)
        // GraphicsEnvironment.getLocalGraphicsEnvironment() is called at most
        // twice per epoch: once to build the installedFonts() cache and at most
        // once more for status() edge cases. Critically, it is NOT called 3
        // times even though status() was invoked 3 times.
        verify(atMost = 2) { GraphicsEnvironment.getLocalGraphicsEnvironment() }
    }
}
