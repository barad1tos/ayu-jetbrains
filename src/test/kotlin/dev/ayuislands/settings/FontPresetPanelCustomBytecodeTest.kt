package dev.ayuislands.settings

import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontPreset
import dev.ayuislands.onboarding.PremiumOnboardingPanel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bytecode-level regression locks for the issue #164 fix.
 *
 * Issue #164 froze the Settings page on "Loading…" forever for users with
 * persisted `fontPresetName="CUSTOM"` because `FontCatalog.forPreset(CUSTOM)`
 * threw `IllegalStateException` from the eager Brew-cask hint row body in
 * [FontPresetPanel] at panel-build time. The structural fix splits the
 * catalog API into [FontCatalog.forPreset] (nullable) and
 * [FontCatalog.requirePreset] (non-null, throws). Defensive call sites in
 * `FontPresetPanel` MUST use `forPreset`; curated-only call sites in
 * [PremiumOnboardingPanel] MUST use `requirePreset`.
 *
 * These tests pin those choices at the bytecode level so a future refactor
 * that reverts `FontPresetPanel` to `requirePreset` (e.g. a "simplification"
 * that misses the CUSTOM persisted-state code path) fails fast at CI rather
 * than reproducing the freeze for users.
 *
 * Pattern mirrors `AyuIslandsAccentPanelTest`'s bytecode-inspection tests:
 * read the compiled `.class` resource, search for symbol references in the
 * raw bytes. No Swing / DSL runtime is required.
 */
class FontPresetPanelCustomBytecodeTest {
    @Test
    fun `FontPresetPanel bytecode references nullable forPreset (issue #164 defensive lock)`() {
        // The defensive call sites at L269 (triggerLifecycleAction), L327
        // (buildInstallHintRow), L336 / L342 (Copy / Run-in-Terminal links),
        // L520 (updateFontMissing) all consume `pendingPreset`, which the user
        // can drive to CUSTOM through the segmented button. They MUST use
        // `forPreset` (nullable). If the bytecode references `requirePreset`
        // anywhere in this class, issue #164 has been reintroduced.
        val classText = readClassBytes("FontPresetPanel")
        assertTrue(
            classText.contains("forPreset"),
            "FontPresetPanel bytecode must reference FontCatalog.forPreset (nullable) — " +
                "the defensive code path that prevents issue #164 freeze on CUSTOM presets",
        )
        assertFalse(
            classText.contains("requirePreset"),
            "FontPresetPanel bytecode MUST NOT reference FontCatalog.requirePreset — " +
                "user-driven preset paths route through pendingPreset which can be CUSTOM, " +
                "and requirePreset throws IllegalStateException for non-curated presets " +
                "(this is the regression that froze Settings on \"Loading…\" in 2.6.1).",
        )
    }

    @Test
    fun `FontPresetPanel bytecode logs a warning before dropping non-curated lifecycle clicks`() {
        // triggerLifecycleAction's `?: return` was originally silent — a future
        // visibility-gate bypass would silently no-op the user's click with no
        // diagnostic in idea.log. Round-1 review fix added a LOG.warn before
        // the early return. Lock it: bytecode must reference both `LOG.warn`
        // and the `forPreset` lookup that precedes it.
        val classText = readClassBytes("FontPresetPanel")
        assertTrue(
            classText.contains("visibility gate bypassed"),
            "triggerLifecycleAction must log a 'visibility gate bypassed' warning when " +
                "forPreset returns null (so future regressions surface in idea.log)",
        )
    }

    @Test
    fun `PremiumOnboardingPanel bytecode references requirePreset for curated-only paths`() {
        // FONT_PRESETS = [WHISPER, AMBIENT, NEON, CYBERPUNK] — CUSTOM is never
        // passed to createFontCard / handleFontCardClick. requirePreset encodes
        // that invariant: a future regression that adds CUSTOM to FONT_PRESETS
        // throws IllegalStateException at first paint, fail-fast.
        val classText = readClassBytes("../onboarding/PremiumOnboardingPanel")
        assertTrue(
            classText.contains("requirePreset"),
            "PremiumOnboardingPanel must use FontCatalog.requirePreset for FONT_PRESETS " +
                "iteration so a regression that admits CUSTOM fails fast",
        )
    }

    @Test
    fun `PremiumOnboardingPanel FONT_PRESETS list contains only curated presets`() {
        // Reflective invariant on the private FONT_PRESETS field. The bytecode
        // test above asserts `requirePreset` is the lookup; this asserts the
        // upstream filter (the list itself) excludes CUSTOM. Both together
        // make the curated-only contract robust against either side drifting.
        // Kotlin private companion-object vals get compiled as private static
        // fields on the enclosing class, not on $Companion. Reflect there.
        val fontPresetsField =
            PremiumOnboardingPanel::class.java.getDeclaredField("FONT_PRESETS")
        fontPresetsField.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val presets = fontPresetsField.get(null) as List<FontPreset>

        assertTrue(presets.isNotEmpty(), "FONT_PRESETS must not be empty")
        assertTrue(
            presets.all { it.isCurated },
            "FONT_PRESETS must contain only curated presets, got: " +
                presets.filter { !it.isCurated },
        )
        assertFalse(
            presets.contains(FontPreset.CUSTOM),
            "FONT_PRESETS must not include CUSTOM (would break PremiumOnboardingPanel's requirePreset contract)",
        )
    }

    private fun readClassBytes(simpleName: String): String {
        // Anchor on FontPresetPanel for FontPresetPanel; cross-package paths
        // (e.g. ../onboarding/PremiumOnboardingPanel) still resolve via the
        // class loader's path-relative .class lookup.
        val resource = "$simpleName.class"
        val stream =
            FontPresetPanel::class.java.getResourceAsStream(resource)
                ?: error("$simpleName.class must be loadable for bytecode inspection")
        val bytes = stream.use { it.readAllBytes() }
        assertTrue(bytes.isNotEmpty(), "$simpleName.class must not be empty")
        return String(bytes, Charsets.ISO_8859_1)
    }
}
