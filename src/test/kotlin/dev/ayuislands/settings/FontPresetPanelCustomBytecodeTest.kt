package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontInstaller
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontStatus
import dev.ayuislands.font.FontUninstaller
import dev.ayuislands.onboarding.PremiumOnboardingPanel
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import javax.swing.JLabel
import kotlin.test.AfterTest
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
    @AfterTest
    fun cleanup() {
        unmockkAll()
    }

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
        // Round-2 C1: bytecode tests prove the symbols are right but pass on a
        // future `forPreset(preset)!!.brewCaskSlug` regression. The actual
        // 2.6.1 freeze happened at panel-build time when the
        // installHintLabel?.let { FontCatalog.forPreset(...).brewCaskSlug }
        // lambda evaluated for CUSTOM. Run that lambda directly with
        // pendingPreset="CUSTOM" and assert the visibility-gate booleans
        // collapse to false (Round-3 strengthening — the original test only
        // asserted label.text==""; that left the `preset.isCurated &&` guard
        // on the boolean assignments untested).
        val panel = FontPresetPanel()
        setPrivateField(panel, "pendingPreset", FontPreset.CUSTOM.name)
        setPrivateField(panel, "pendingEnabled", true) // would force-enable for curated; CUSTOM still collapses
        val label = JLabel("placeholder")
        setPrivateField(panel, "installHintLabel", label)
        setPrivateField(panel, "availability", emptyMap<FontPreset, Boolean>())

        invokeUpdateFontMissing(panel)

        assertEquals(
            "",
            label.text,
            "installHintLabel.text must be empty for CUSTOM (no Brew cask slug for non-curated)",
        )
        // Visibility-gate invariant: all three booleans must be false for
        // non-curated presets regardless of pendingEnabled. A future regression
        // that strips `preset.isCurated &&` from the boolean assignments would
        // re-expose the install row for CUSTOM and reproduce issue #164.
        assertFalse(getBooleanProperty(panel, "fontMissing"), "fontMissing must be false for CUSTOM")
        assertFalse(getBooleanProperty(panel, "fontInstalled"), "fontInstalled must be false for CUSTOM")
        assertFalse(getBooleanProperty(panel, "fontCorrupted"), "fontCorrupted must be false for CUSTOM")
    }

    @Test
    fun `updateFontMissing handles curated preset with pendingEnabled and stubbed FontDetector`() {
        // Round-3 I2: original updateFontMissing test ran with
        // pendingEnabled=false to dodge FontDetector.status(). That left the
        // FontDetector branch uncovered — exactly the kind of "build-time
        // evaluation crashes" path issue #164 was about. Cover it explicitly
        // here with a curated preset and stubbed status, asserting the boolean
        // assignments mirror the status snapshot.
        mockkObject(FontDetector)
        every { FontDetector.status(FontPreset.WHISPER) } returns FontStatus.HEALTHY

        val panel = FontPresetPanel()
        setPrivateField(panel, "pendingPreset", FontPreset.WHISPER.name)
        setPrivateField(panel, "pendingEnabled", true)
        setPrivateField(panel, "installHintLabel", JLabel())
        setPrivateField(panel, "availability", mapOf(FontPreset.WHISPER to true))

        invokeUpdateFontMissing(panel)

        assertFalse(getBooleanProperty(panel, "fontMissing"), "WHISPER healthy: fontMissing must be false")
        assertTrue(getBooleanProperty(panel, "fontInstalled"), "WHISPER healthy: fontInstalled must be true")
        assertFalse(getBooleanProperty(panel, "fontCorrupted"), "WHISPER healthy: fontCorrupted must be false")
    }

    @Test
    fun `updateFontMissing forwards user family to preview for non-curated preset`() {
        // 2.6.2 follow-up: pre-fix updateFontMissing only called updatePreset,
        // which sets fontInstalled=false for CUSTOM (no catalog availability)
        // and the preview pane fell back to "Install <preset.fontFamily> to
        // preview" — i.e. "Install JetBrains Mono" even when the user had
        // picked a different family. The fix has three structural pieces this
        // test pins via bytecode references on the panel + detector + preview
        // classes: FontPresetPanel pushes the user family via updateFontFamily,
        // gates on isCurated, and asks FontDetector.isFamilyInstalled for a
        // system-level install check (instead of leaving fontInstalled=false
        // for CUSTOM).
        val classText = readClassBytes("FontPresetPanel")
        assertTrue(
            classText.contains("updateFontFamily"),
            "FontPresetPanel must call FontPreviewComponent.updateFontFamily " +
                "so non-curated presets render with the user's actual family, not " +
                "the catalog's default fontFamily constant.",
        )
        assertTrue(
            classText.contains("isCurated"),
            "FontPresetPanel must gate the updateFontFamily push on " +
                "preset.isCurated so curated presets keep using the catalog default.",
        )
        assertTrue(
            classText.contains("isFamilyInstalled"),
            "FontPresetPanel must call FontDetector.isFamilyInstalled to check " +
                "whether the user-chosen Custom family is installed on the system " +
                "(without this, fontInstalled stays false for CUSTOM and the " +
                "preview always falls back to 'Install X to preview').",
        )
    }

    @Test
    fun `triggerLifecycleAction with CUSTOM hits visibility-gate guard and never dispatches install or uninstall`() {
        // Round-3 C1: the original "does not throw" test was masked by the
        // outer try/catch — AccentApplicator.resolveFocusedProject() throws on
        // a plain JVM (no platform Application bootstrapped) and the catch
        // swallows the throw, so the test passed without ever reaching the
        // forPreset null-guard. Stub the platform call AND the install /
        // uninstall objects, then assert via verify(exactly = 0) that no
        // install/uninstall was dispatched. That's the actual behavior we
        // want to lock for the CUSTOM defensive path.
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns null
        mockkObject(FontInstaller)
        mockkObject(FontUninstaller)

        val panel = FontPresetPanel()
        setPrivateField(panel, "pendingPreset", FontPreset.CUSTOM.name)
        setPrivateField(panel, "pendingEnabled", false)
        setPrivateField(panel, "availability", emptyMap<FontPreset, Boolean>())

        val method =
            FontPresetPanel::class.java.getDeclaredMethod(
                "triggerLifecycleAction",
                Boolean::class.javaPrimitiveType,
            )
        method.isAccessible = true
        method.invoke(panel, false) // install attempt
        method.invoke(panel, true) // uninstall attempt

        verify(exactly = 0) { FontInstaller.install(any(), any(), any()) }
        verify(exactly = 0) { FontUninstaller.uninstall(any(), any(), any()) }
    }

    private fun getBooleanProperty(
        target: Any,
        name: String,
    ): Boolean {
        val field = FontPresetPanel::class.java.getDeclaredField(name)
        field.isAccessible = true
        val property = field.get(target) as AtomicBooleanProperty
        return property.get()
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

    private fun invokeUpdateFontMissing(target: Any) {
        val method = FontPresetPanel::class.java.getDeclaredMethod("updateFontMissing")
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
