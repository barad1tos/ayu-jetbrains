package dev.ayuislands.settings

import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontPreset
import dev.ayuislands.onboarding.PremiumOnboardingPanel
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
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
        // Defensive call sites in triggerLifecycleAction, buildInstallHintRow,
        // the Copy / Run-in-Terminal link callbacks, and updateFontMissing all
        // consume `pendingPreset`, which the user can drive to CUSTOM through
        // the segmented button. They MUST use `forPreset` (nullable). If the
        // bytecode references `requirePreset` anywhere in this class, issue
        // #164 has been reintroduced. (No line numbers here on purpose —
        // they would rot on every unrelated edit; bytecode lookup is symbolic.)
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
        // visibility-gate bypass would no-op the user's click with no
        // diagnostic in idea.log. Round-1 fix added a LOG.warn message
        // containing "visibility gate bypassed". Lock the diagnostic: bytecode
        // must contain that string-constant from the LOG.warn call site so a
        // future "simplification" that drops the warn fails this test fast.
        val classText = readClassBytes("FontPresetPanel")
        assertTrue(
            classText.contains("visibility gate bypassed"),
            "triggerLifecycleAction (and Copy / Run-in-Terminal links) must log a " +
                "'visibility gate bypassed' warning when forPreset returns null, so " +
                "future regressions surface in idea.log instead of dropping clicks silently",
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

    @Test
    fun `updateFontMissing runs without throwing when pendingPreset is CUSTOM (issue #164 runtime lock)`() {
        // Round-2 review C1: bytecode tests above prove the symbols are right,
        // but they pass even if a future regression writes
        // `forPreset(preset)!!.brewCaskSlug` (still references "forPreset"). The
        // actual freeze in 2.6.1 happened at panel-build time when the
        // installHintLabel?.let { FontCatalog.forPreset(...).brewCaskSlug }
        // lambda evaluated for CUSTOM. Run that lambda directly: set
        // pendingPreset="CUSTOM", attach a real JLabel to installHintLabel,
        // invoke updateFontMissing() — must not throw, must leave label.text
        // empty (no Brew cask slug for non-curated).
        val panel = FontPresetPanel()
        setPrivateField(panel, "pendingPreset", FontPreset.CUSTOM.name)
        setPrivateField(panel, "pendingEnabled", false) // collapses to NOT_INSTALLED, skips FontDetector
        val label = JLabel("placeholder")
        setPrivateField(panel, "installHintLabel", label)

        // Initialize the lateinit `availability` field so the previewComponent
        // line at the bottom of updateFontMissing doesn't NPE — empty map is
        // fine since updatePreset takes a preset parameter we don't care about.
        setPrivateField(panel, "availability", emptyMap<FontPreset, Boolean>())

        invokePrivateMethod(panel, "updateFontMissing")

        assertEquals(
            "",
            label.text,
            "installHintLabel.text must be empty for CUSTOM (no Brew cask slug for non-curated)",
        )
    }

    @Test
    fun `triggerLifecycleAction does not throw when pendingPreset is CUSTOM`() {
        // Round-2 review C1: the install-row link callback routes through
        // triggerLifecycleAction. With pendingPreset=CUSTOM, the new LOG.warn
        // path must run cleanly: forPreset returns null, the warn fires, the
        // method returns without queuing any install/uninstall task. The catch
        // block surrounding the body would also swallow any unexpected throw,
        // but the goal here is to assert the happy path of the defensive guard.
        val panel = FontPresetPanel()
        setPrivateField(panel, "pendingPreset", FontPreset.CUSTOM.name)
        setPrivateField(panel, "pendingEnabled", false)
        setPrivateField(panel, "availability", emptyMap<FontPreset, Boolean>())

        val method =
            FontPresetPanel::class.java.getDeclaredMethod("triggerLifecycleAction", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        // Should return cleanly — visibility-gate-bypass guard fires LOG.warn
        // and returns without dispatching to FontInstaller / FontUninstaller.
        method.invoke(panel, false)
        method.invoke(panel, true)
    }

    private fun setPrivateField(
        target: Any,
        name: String,
        value: Any?,
    ) {
        val field = FontPresetPanel::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun invokePrivateMethod(
        target: Any,
        name: String,
    ) {
        val method = FontPresetPanel::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(target)
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
