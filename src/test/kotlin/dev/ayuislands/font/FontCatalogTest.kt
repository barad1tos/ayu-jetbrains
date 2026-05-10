package dev.ayuislands.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontCatalogTest {
    // ---- forPreset / requirePreset lookups ----

    @Test
    fun `requirePreset WHISPER returns Victor Mono`() {
        val entry = FontCatalog.requirePreset(FontPreset.WHISPER)
        assertEquals("Victor Mono", entry.familyName)
        assertEquals("Victor Mono", entry.displayName)
    }

    @Test
    fun `requirePreset AMBIENT returns Maple Mono`() {
        val entry = FontCatalog.requirePreset(FontPreset.AMBIENT)
        assertEquals("Maple Mono", entry.familyName)
        assertEquals("Maple Mono", entry.displayName)
    }

    @Test
    fun `requirePreset NEON returns Monaspace Neon`() {
        val entry = FontCatalog.requirePreset(FontPreset.NEON)
        assertEquals("Monaspace Neon", entry.familyName)
        assertEquals("Monaspace Neon", entry.displayName)
    }

    @Test
    fun `requirePreset CYBERPUNK returns Monaspace Xenon`() {
        val entry = FontCatalog.requirePreset(FontPreset.CYBERPUNK)
        assertEquals("Monaspace Xenon", entry.familyName)
        assertEquals("Monaspace Xenon", entry.displayName)
    }

    @Test
    fun `forPreset CUSTOM returns null (issue #164 - non-curated has no catalog entry)`() {
        // RED-test for issue #164: pre-2.6.2 forPreset threw IllegalStateException here,
        // freezing the Settings page on "Loading…" forever for users with persisted
        // fontPresetName="CUSTOM". forPreset must return null so defensive callers can
        // gracefully skip the install pipeline for non-curated presets.
        assertNull(FontCatalog.forPreset(FontPreset.CUSTOM))
    }

    @Test
    fun `requirePreset CUSTOM throws (curated-only API contract)`() {
        // requirePreset is the non-null variant for call sites that statically
        // operate on curated presets only (onboarding cards iterating FONT_PRESETS,
        // tests pinning known presets). Passing CUSTOM is a programmer error and
        // must fail fast.
        assertFailsWith<IllegalStateException> {
            FontCatalog.requirePreset(FontPreset.CUSTOM)
        }
    }

    // ---- entries list invariants ----

    @Test
    fun `entries count matches curated presets`() {
        val curatedPresets = FontPreset.entries.filter { it != FontPreset.CUSTOM }
        assertEquals(curatedPresets.size, FontCatalog.entries.size)
    }

    @Test
    fun `all entries have non-blank fallbackUrl`() {
        for (entry in FontCatalog.entries) {
            assertTrue(entry.fallbackUrl.isNotBlank(), "${entry.preset.name} fallbackUrl should not be blank")
        }
    }

    @Test
    fun `all entries have non-empty filesToKeep`() {
        for (entry in FontCatalog.entries) {
            assertTrue(entry.filesToKeep.isNotEmpty(), "${entry.preset.name} filesToKeep should not be empty")
        }
    }

    @Test
    fun `all entries have positive approxSizeMb`() {
        for (entry in FontCatalog.entries) {
            assertTrue(entry.approxSizeMb > 0, "${entry.preset.name} approxSizeMb should be positive")
        }
    }

    @Test
    fun `all entries have non-blank brewCaskSlug`() {
        for (entry in FontCatalog.entries) {
            assertTrue(entry.brewCaskSlug.isNotBlank(), "${entry.preset.name} brewCaskSlug should not be blank")
        }
    }

    @Test
    fun `all entries have non-blank displayName`() {
        for (entry in FontCatalog.entries) {
            assertTrue(entry.displayName.isNotBlank(), "${entry.preset.name} displayName should not be blank")
        }
    }

    // ---- assetPattern validity ----

    @Test
    fun `all assetPatterns are valid regexes that execute without error`() {
        for (entry in FontCatalog.entries) {
            // assetPattern is already a Regex — verify it doesn't throw on use
            entry.assetPattern.containsMatchIn("test-string")
        }
    }

    @Test
    fun `AMBIENT assetPattern matches expected zip filename`() {
        val pattern = FontCatalog.requirePreset(FontPreset.AMBIENT).assetPattern
        assertTrue(pattern.matches("MapleMono-TTF.zip"))
        assertFalse(pattern.matches("MapleMono-OTF.zip"))
    }

    @Test
    fun `NEON assetPattern matches variable font zip`() {
        val pattern = FontCatalog.requirePreset(FontPreset.NEON).assetPattern
        assertTrue(pattern.containsMatchIn("monaspace-variable-v1.400.zip"))
    }

    @Test
    fun `CYBERPUNK assetPattern matches variable font zip`() {
        val pattern = FontCatalog.requirePreset(FontPreset.CYBERPUNK).assetPattern
        assertTrue(pattern.containsMatchIn("monaspace-variable-v1.400.zip"))
    }

    // ---- filesToKeep regex matching ----

    @Test
    fun `WHISPER filesToKeep matches VictorMono-Light ttf`() {
        val regexes = FontCatalog.requirePreset(FontPreset.WHISPER).filesToKeep
        assertTrue(regexes.any { it.matches("fonts/VictorMono-Light.ttf") })
        assertTrue(regexes.any { it.matches("VictorMono-Light.otf") })
    }

    @Test
    fun `WHISPER filesToKeep rejects VictorMono-Bold`() {
        val regexes = FontCatalog.requirePreset(FontPreset.WHISPER).filesToKeep
        assertFalse(regexes.any { it.matches("VictorMono-Bold.ttf") })
    }

    @Test
    fun `AMBIENT filesToKeep matches MapleMono-Regular ttf`() {
        val regexes = FontCatalog.requirePreset(FontPreset.AMBIENT).filesToKeep
        assertTrue(regexes.any { it.matches("MapleMono-Regular.ttf") })
    }

    @Test
    fun `AMBIENT filesToKeep rejects MapleMono-Bold`() {
        val regexes = FontCatalog.requirePreset(FontPreset.AMBIENT).filesToKeep
        assertFalse(regexes.any { it.matches("MapleMono-Bold.ttf") })
    }

    @Test
    fun `NEON filesToKeep matches MonaspaceNeonVarVF ttf`() {
        val regexes = FontCatalog.requirePreset(FontPreset.NEON).filesToKeep
        assertTrue(regexes.any { it.matches("MonaspaceNeonVarVF.ttf") })
        assertTrue(regexes.any { it.matches("path/to/MonaspaceNeonVarVF.otf") })
    }

    @Test
    fun `NEON filesToKeep rejects MonaspaceXenonVarVF`() {
        val regexes = FontCatalog.requirePreset(FontPreset.NEON).filesToKeep
        assertFalse(regexes.any { it.matches("MonaspaceXenonVarVF.ttf") })
    }

    @Test
    fun `CYBERPUNK filesToKeep matches MonaspaceXenonVarVF ttf`() {
        val regexes = FontCatalog.requirePreset(FontPreset.CYBERPUNK).filesToKeep
        assertTrue(regexes.any { it.matches("MonaspaceXenonVarVF.ttf") })
        assertTrue(regexes.any { it.matches("fonts/MonaspaceXenonVarVF.otf") })
    }

    @Test
    fun `CYBERPUNK filesToKeep rejects MonaspaceNeonVarVF`() {
        val regexes = FontCatalog.requirePreset(FontPreset.CYBERPUNK).filesToKeep
        assertFalse(regexes.any { it.matches("MonaspaceNeonVarVF.ttf") })
    }

    // ---- useDirectUrl ----

    @Test
    fun `WHISPER uses direct URL`() {
        assertTrue(FontCatalog.requirePreset(FontPreset.WHISPER).useDirectUrl)
    }

    @Test
    fun `non-direct-URL presets use GitHub API`() {
        val apiEntries = FontCatalog.entries.filter { !it.useDirectUrl }
        assertTrue(apiEntries.isNotEmpty(), "At least one preset should use GitHub API")
        for (entry in apiEntries) {
            assertTrue(entry.githubOwner.isNotBlank(), "${entry.preset.name} should have githubOwner")
        }
    }

    // ---- GitHub repo consistency ----

    @Test
    fun `NEON and CYBERPUNK share the same GitHub repo`() {
        val neon = FontCatalog.requirePreset(FontPreset.NEON)
        val cyberpunk = FontCatalog.requirePreset(FontPreset.CYBERPUNK)
        assertEquals(neon.githubOwner, cyberpunk.githubOwner)
        assertEquals(neon.githubRepo, cyberpunk.githubRepo)
    }

    @Test
    fun `NEON and CYBERPUNK share the same fallbackUrl`() {
        val neon = FontCatalog.requirePreset(FontPreset.NEON)
        val cyberpunk = FontCatalog.requirePreset(FontPreset.CYBERPUNK)
        assertEquals(neon.fallbackUrl, cyberpunk.fallbackUrl)
    }

    @Test
    fun `API-based entries have non-blank githubOwner and githubRepo`() {
        val apiEntries = FontCatalog.entries.filter { !it.useDirectUrl }
        for (entry in apiEntries) {
            assertTrue(entry.githubOwner.isNotBlank(), "${entry.preset.name} githubOwner should not be blank")
            assertTrue(entry.githubRepo.isNotBlank(), "${entry.preset.name} githubRepo should not be blank")
        }
    }

    // ---- preset-entry bijection ----

    @Test
    fun `every curated FontPreset has a catalog entry`() {
        val curatedPresets = FontPreset.entries.filter { it.isCurated }
        for (preset in curatedPresets) {
            val entry = FontCatalog.requirePreset(preset)
            assertEquals(preset, entry.preset)
        }
    }

    @Test
    fun `all entries map back to distinct presets`() {
        val presets = FontCatalog.entries.map { it.preset }
        assertEquals(presets.size, presets.toSet().size, "Each entry should map to a unique preset")
    }
}
