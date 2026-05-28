package dev.ayuislands.syntax

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.State
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * Test set for [SyntaxIntensityState].
 *
 * Uses an in-memory loadState round-trip pattern - the
 * `XmlSerializerUtil.copyBean` path inside [SimplePersistentStateComponent]
 * matches the on-disk persistence shape and avoids the platform-fixture
 * boot pain seen elsewhere. Annotation metadata is verified via reflection;
 * the application service lookup is mocked only at the IntelliJ boundary.
 *
 * 10 invariants per the plan spec:
 *  1.  Default state: `selectedPreset == "AMBIENT"`, `customOverrides`
 *      empty, `schemaVersion == 1`.
 *  2.  `selectedPreset` round-trip via in-memory loadState.
 *  3.  `SyntaxPreset.fromName` integration - tampered preset name falls
 *      back to `AMBIENT`.
 *  4.  `@Storage(value = "ayu-islands-syntax-intensity.xml")` metadata
 *      is present and distinct from the Phase 49 filename.
 *  5.  `@State(name = "AyuIslandsSyntaxIntensityState")` metadata is present.
 *  6.  Flat composite-key `customOverrides` round-trip.
 *  7.  `schemaVersion` round-trip.
 *  8.  `toPresetConfig()` bridge - adapts flat composite-key map back to
 *      nested `Map<String, Map<String, Int>>` consumed by the applicator
 *      and `SyntaxPreset.detect`.
 *  9.  `toPresetConfig()` skips malformed flat keys / non-Int values
 *      silently (forward-compat / tamper resistance).
 * 10.  `getInstance()` companion resolves the application service.
 * 11.  Sparse persistence - only the cells the user moves materialise; the
 *      untouched cells never appear in the persisted map (Phase 50.1 D-01).
 * 12.  `subordinatePreset` round-trip - a non-default name survives loadState.
 * 13.  Legacy default - a fresh state with no `subordinatePreset` field
 *      deserialises to "AMBIENT" (backward-compatible, Phase 50.1 D-07).
 */
class SyntaxIntensityStateTest {
    // --- Test 1 - defaults --------------------------------------------------

    @Test
    fun `default base state is AMBIENT preset with empty customOverrides and schemaVersion 2`() {
        val state = SyntaxIntensityBaseState()
        assertEquals("AMBIENT", state.selectedPreset, "default selectedPreset must be AMBIENT per D-23")
        assertTrue(state.customOverrides.isEmpty(), "default customOverrides must be empty (free tier never writes)")
        assertTrue(state.customStyles.isEmpty(), "default customStyles must be empty (free tier never writes)")
        assertEquals(2, state.schemaVersion, "default schemaVersion must be 2 since customStyles was added")
    }

    // --- Test 2 - selectedPreset round-trip --------------------------------

    @Test
    fun `selectedPreset string survives loadState round-trip`() {
        val reloaded = roundTrip { state -> state.selectedPreset = "NEON" }
        assertEquals("NEON", reloaded.state.selectedPreset)
    }

    // --- Test 3 - SyntaxPreset.fromName fallback ---------------------------

    @Test
    fun `tampered selectedPreset string falls back to AMBIENT via SyntaxPreset fromName (D-23)`() {
        val reloaded = roundTrip { state -> state.selectedPreset = "BOGUS_PRESET_FROM_TAMPERED_XML" }
        assertEquals("BOGUS_PRESET_FROM_TAMPERED_XML", reloaded.state.selectedPreset)
        assertSame(SyntaxPreset.AMBIENT, SyntaxPreset.fromName(reloaded.state.selectedPreset))
    }

    // --- Test 4 - storage filename metadata lock --------------------------

    @Test
    fun `Storage filename literal is ayu-islands-syntax-intensity_xml (D-13 distinct from Phase 49)`() {
        val storageValues = stateAnnotation().storages.map { storage -> storage.value }
        assertEquals(
            listOf("ayu-islands-syntax-intensity.xml"),
            storageValues,
            "Storage filename must remain distinct for Phase 50 syntax intensity state",
        )
        assertFalse(
            storageValues.contains("ayu-islands-syntax-mode.xml"),
            "must not reuse Phase 49 filename - Phase 50 state file is distinct per D-13",
        )
    }

    // --- Test 5 - @State name metadata lock --------------------------------

    @Test
    fun `State name literal is AyuIslandsSyntaxIntensityState`() {
        assertEquals(
            "AyuIslandsSyntaxIntensityState",
            stateAnnotation().name,
            "State name must remain stable for persisted settings migration",
        )
    }

    // --- Test 6 - flat composite-key customOverrides round-trip ------------

    @Test
    fun `flat composite-key customOverrides survives loadState round-trip`() {
        val reloaded =
            roundTrip { state ->
                state.customOverrides["Java|KEYWORD"] = "75"
                state.customOverrides["Java|COMMENT"] = "25"
                state.customOverrides["Kotlin|KEYWORD"] = "60"
            }
        assertEquals(3, reloaded.state.customOverrides.size)
        assertEquals("75", reloaded.state.customOverrides["Java|KEYWORD"])
        assertEquals("25", reloaded.state.customOverrides["Java|COMMENT"])
        assertEquals("60", reloaded.state.customOverrides["Kotlin|KEYWORD"])
    }

    // --- Test 7 - schemaVersion round-trip ---------------------------------

    @Test
    fun `schemaVersion survives loadState round-trip for default and bumped values`() {
        // No mutation - verify the default schemaVersion survives the round-trip.
        val reloadedDefault = roundTrip { _ -> }
        assertEquals(2, reloadedDefault.state.schemaVersion)

        val reloadedBumped = roundTrip { state -> state.schemaVersion = 3 }
        assertEquals(3, reloadedBumped.state.schemaVersion, "schemaVersion 3 must round-trip for future migration")
    }

    // --- Test 8 - toPresetConfig bridge (Codex HIGH #1) --------------------

    @Test
    fun `toPresetConfig adapts flat composite-key map to nested DTO consumed by SyntaxPreset detect`() {
        val component = SyntaxIntensityState()
        component.state.selectedPreset = "NEON"
        component.state.customOverrides["Java|KEYWORD"] = "75"
        component.state.customOverrides["Java|COMMENT"] = "25"
        component.state.customOverrides["Kotlin|KEYWORD"] = "60"

        val config = component.toPresetConfig()

        assertEquals("NEON", config.selectedPreset)
        val expected =
            mapOf(
                "Java" to mapOf("KEYWORD" to 75, "COMMENT" to 25),
                "Kotlin" to mapOf("KEYWORD" to 60),
            )
        assertEquals(expected, config.customOverrides)

        // SyntaxPreset.detect must accept the bridge output verbatim.
        assertSame(SyntaxPreset.NEON, SyntaxPreset.detect(config))
    }

    // --- Test 9 - toPresetConfig skips malformed entries -------------------

    @Test
    fun `toPresetConfig silently skips malformed flat keys and non-Int values`() {
        val component = SyntaxIntensityState()
        component.state.selectedPreset = "WHISPER"
        // Malformed: no separator at all.
        component.state.customOverrides["BadKey"] = "50"
        // Malformed: empty language (separator at position 0).
        component.state.customOverrides["|MISSING_LANG"] = "30"
        // Malformed: empty category (separator at end).
        component.state.customOverrides["Java|"] = "40"
        // Malformed: non-Int value.
        component.state.customOverrides["Python|KEYWORD"] = "not-a-number"
        // Well-formed: the only entry that should survive.
        component.state.customOverrides["Java|KEYWORD"] = "75"

        val config = component.toPresetConfig()

        assertEquals("WHISPER", config.selectedPreset)
        val expected = mapOf("Java" to mapOf("KEYWORD" to 75))
        assertEquals(expected, config.customOverrides, "only the well-formed entry survives the bridge")
    }

    // --- Test 11 - sparse persistence (only moved cells materialise) ------

    @Test
    fun `only the moved cells are persisted - untouched cells never materialise (D-01)`() {
        val reloaded =
            roundTrip { state ->
                state.customOverrides["Java|KEYWORD"] = "75"
                state.customOverrides["Kotlin|COMMENT"] = "30"
            }
        assertEquals(
            2,
            reloaded.state.customOverrides.size,
            "sparse store - exactly the 2 moved cells persist, untouched cells inherit the preset",
        )
        assertEquals("75", reloaded.state.customOverrides["Java|KEYWORD"])
        assertEquals("30", reloaded.state.customOverrides["Kotlin|COMMENT"])
    }

    // --- Test 12 - subordinatePreset round-trip ---------------------------

    @Test
    fun `subordinatePreset string survives loadState round-trip (D-07)`() {
        // `subordinatePreset` does NOT exist on SyntaxIntensityBaseState until
        // Plan 02 lands - referencing it here forces the RED state.
        val reloaded = roundTrip { state -> state.subordinatePreset = "NEON" }
        assertEquals("NEON", reloaded.state.subordinatePreset)
    }

    // --- Test 13 - legacy default for absent subordinatePreset ------------

    @Test
    fun `fresh base state defaults subordinatePreset to AMBIENT (backward-compatible, D-07)`() {
        // Legacy / pre-50.1 XML has no subordinatePreset field; the string()
        // delegate default must deserialise it to AMBIENT so old state stays
        // valid without a forced migration.
        assertEquals("AMBIENT", SyntaxIntensityBaseState().subordinatePreset)
    }

    // --- Test 14 - customStyles sparse round-trip --------------------------

    @Test
    fun `flat composite-key customStyles survives loadState round-trip (sparse)`() {
        val reloaded =
            roundTrip { state ->
                state.customStyles["Java|KEYWORD"] = "BOLD"
                state.customStyles["Kotlin|COMMENT"] = "ITALIC"
            }
        // Only the two styled cells materialise - untouched cells inherit.
        assertEquals(2, reloaded.state.customStyles.size, "sparse store - only styled cells persist")
        assertEquals("BOLD", reloaded.state.customStyles["Java|KEYWORD"])
        assertEquals("ITALIC", reloaded.state.customStyles["Kotlin|COMMENT"])
    }

    // --- Test 15 - FontStyleOverride tamper-safe decode -------------------

    @Test
    fun `FontStyleOverride fromName decodes known names and returns null for tampered input`() {
        assertSame(FontStyleOverride.PLAIN, FontStyleOverride.fromName("PLAIN"))
        assertSame(FontStyleOverride.BOLD, FontStyleOverride.fromName("BOLD"))
        assertSame(FontStyleOverride.ITALIC, FontStyleOverride.fromName("ITALIC"))
        assertSame(FontStyleOverride.BOLD_ITALIC, FontStyleOverride.fromName("BOLD_ITALIC"))
        // Tamper-safe: unknown token, lower-case, blank, and null all yield null.
        assertEquals(null, FontStyleOverride.fromName("UNDERLINE"))
        assertEquals(null, FontStyleOverride.fromName("bold"))
        assertEquals(null, FontStyleOverride.fromName(""))
        assertEquals(null, FontStyleOverride.fromName(null))
    }

    @Test
    fun `FontStyleOverride fontType matches the java_awt_Font bitmask (PLAIN ITALIC combinable)`() {
        assertEquals(java.awt.Font.PLAIN, FontStyleOverride.PLAIN.fontType)
        assertEquals(java.awt.Font.BOLD, FontStyleOverride.BOLD.fontType)
        assertEquals(java.awt.Font.ITALIC, FontStyleOverride.ITALIC.fontType)
        assertEquals(
            java.awt.Font.BOLD or java.awt.Font.ITALIC,
            FontStyleOverride.BOLD_ITALIC.fontType,
            "BOLD_ITALIC must be the combined bitmask (3) so bold+italic are combinable",
        )
    }

    // --- Test 16 - toPresetConfig style bridge ----------------------------

    @Test
    fun `toPresetConfig decodes customStyles flat map to nested fontType bitmasks`() {
        val component = SyntaxIntensityState()
        component.state.selectedPreset = "CUSTOM"
        component.state.customStyles["Java|KEYWORD"] = "BOLD_ITALIC"
        component.state.customStyles["Kotlin|COMMENT"] = "ITALIC"

        val config = component.toPresetConfig()

        val expected =
            mapOf(
                "Java" to mapOf("KEYWORD" to (java.awt.Font.BOLD or java.awt.Font.ITALIC)),
                "Kotlin" to mapOf("COMMENT" to java.awt.Font.ITALIC),
            )
        assertEquals(expected, config.customStyles, "style bridge must decode to nested java.awt.Font bitmasks")
    }

    @Test
    fun `toPresetConfig silently skips malformed style keys and tampered style values`() {
        val component = SyntaxIntensityState()
        component.state.selectedPreset = "CUSTOM"
        // Malformed key: no separator.
        component.state.customStyles["BadKey"] = "BOLD"
        // Malformed key: empty language.
        component.state.customStyles["|KEYWORD"] = "BOLD"
        // Malformed key: empty category.
        component.state.customStyles["Java|"] = "BOLD"
        // Tampered value: not a FontStyleOverride name.
        component.state.customStyles["Python|KEYWORD"] = "WAVY_UNDERSCORE"
        // Well-formed: the only entry that should survive.
        component.state.customStyles["Java|KEYWORD"] = "BOLD"

        val config = component.toPresetConfig()

        val expected = mapOf("Java" to mapOf("KEYWORD" to java.awt.Font.BOLD))
        assertEquals(expected, config.customStyles, "only the well-formed, decodable style cell survives the bridge")
    }

    // --- Test 17 - v1 backward compatibility (no customStyles element) ----

    @Test
    fun `a v1 config without customStyles reads as an empty style map (backward compat)`() {
        // A pre-font-style (schemaVersion 1) config never wrote customStyles.
        // Loading it must leave customStyles empty so every cell inherits the
        // source font - no read-time migration required.
        val legacy = SyntaxIntensityState()
        legacy.state.schemaVersion = 1
        legacy.state.customOverrides["Java|KEYWORD"] = "75"
        // No customStyles writes - simulates the old on-disk shape.

        val config = legacy.toPresetConfig()

        assertTrue(config.customStyles.isEmpty(), "absent customStyles must surface as an empty nested map")
        // Intensity overrides still decode - the bump is additive only.
        assertEquals(mapOf("Java" to mapOf("KEYWORD" to 75)), config.customOverrides)
    }

    // --- Test 10 - getInstance service lookup -----------------------------

    @Test
    fun `getInstance returns the SyntaxIntensityState application service`() {
        val application = mockk<Application>()
        val service = SyntaxIntensityState()
        mockkStatic(ApplicationManager::class)
        try {
            every { ApplicationManager.getApplication() } returns application
            every { application.getService(SyntaxIntensityState::class.java) } returns service

            assertSame(service, SyntaxIntensityState.getInstance())
        } finally {
            unmockkStatic(ApplicationManager::class)
        }
    }

    // --- Helpers ------------------------------------------------------------

    private fun roundTrip(mutate: (SyntaxIntensityBaseState) -> Unit): SyntaxIntensityState {
        val original = SyntaxIntensityState()
        mutate(original.state)
        val saved = original.state
        val reloaded = SyntaxIntensityState()
        reloaded.loadState(saved)
        return reloaded
    }

    private fun stateAnnotation(): State =
        assertNotNull(
            SyntaxIntensityState::class.java.getAnnotation(State::class.java),
            "SyntaxIntensityState must declare @State metadata",
        )
}
