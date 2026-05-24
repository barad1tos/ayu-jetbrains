package dev.ayuislands.syntax

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull

/**
 * RED-gate test set for [SyntaxIntensityState].
 *
 * Uses the same in-memory loadState round-trip pattern as
 * [SyntaxModeStatePersistenceRoundTripTest] — the
 * `XmlSerializerUtil.copyBean` path inside [SimplePersistentStateComponent]
 * matches the on-disk persistence shape and avoids the platform-fixture
 * boot pain documented in Plan 49-04 SUMMARY (warning 5 fallback). Tests
 * that would need a live `@Service` container (Test 10 `getInstance()`)
 * fall back to Pattern L source-grep regression locks per the same SUMMARY.
 *
 * 10 invariants per the plan spec — see PLAN 50-04 Task 2 `<behavior>`:
 *  1.  Default state: `selectedPreset == "AMBIENT"`, `customOverrides`
 *      empty, `schemaVersion == 1`.
 *  2.  `selectedPreset` round-trip via in-memory loadState.
 *  3.  `SyntaxPreset.fromName` integration — tampered preset name falls
 *      back to `AMBIENT` (D-23).
 *  4.  Pattern L source-regex lock — `@Storage(value = "ayu-islands-syntax-intensity.xml")`
 *      literal present in source (D-13).
 *  5.  Pattern L source-regex lock — `@State(name = "AyuIslandsSyntaxIntensityState")`
 *      literal present in source.
 *  6.  Flat composite-key `customOverrides` round-trip (Gemini + OpenCode
 *      consensus — replaces nested-map spike).
 *  7.  `schemaVersion` round-trip.
 *  8.  `toPresetConfig()` bridge — adapts flat composite-key map back to
 *      nested `Map<String, Map<String, Int>>` consumed by
 *      `SyntaxIntensityApplicator` + `SyntaxPreset.detect` (Codex HIGH #1
 *      continuation).
 *  9.  `toPresetConfig()` skips malformed flat keys / non-Int values
 *      silently (forward-compat / tamper resistance).
 * 10.  Pattern L source-regex lock for `getInstance()` companion — the
 *      live application-service lookup is exercised in integration tests
 *      under `src/test/kotlin/dev/ayuislands/integration/` (per the
 *      Plan 49-04 warning 5 fallback documented in
 *      [SyntaxModeStatePersistenceRoundTripTest]).
 */
class SyntaxIntensityStateTest {
    // --- Test 1 — defaults --------------------------------------------------

    @Test
    fun `default base state is AMBIENT preset with empty customOverrides and schemaVersion 1`() {
        val state = SyntaxIntensityBaseState()
        assertEquals("AMBIENT", state.selectedPreset, "default selectedPreset must be AMBIENT per D-23")
        assertTrue(state.customOverrides.isEmpty(), "default customOverrides must be empty (Phase 50A never writes)")
        assertEquals(1, state.schemaVersion, "default schemaVersion must be 1 (OpenCode forward-compat)")
    }

    // --- Test 2 — selectedPreset round-trip --------------------------------

    @Test
    fun `selectedPreset string survives loadState round-trip`() {
        val reloaded = roundTrip { state -> state.selectedPreset = "NEON" }
        assertEquals("NEON", reloaded.state.selectedPreset)
    }

    // --- Test 3 — SyntaxPreset.fromName fallback ---------------------------

    @Test
    fun `tampered selectedPreset string falls back to AMBIENT via SyntaxPreset fromName (D-23)`() {
        val reloaded = roundTrip { state -> state.selectedPreset = "BOGUS_PRESET_FROM_TAMPERED_XML" }
        assertEquals("BOGUS_PRESET_FROM_TAMPERED_XML", reloaded.state.selectedPreset)
        assertSame(SyntaxPreset.AMBIENT, SyntaxPreset.fromName(reloaded.state.selectedPreset))
    }

    // --- Test 4 — Pattern L source-regex storage filename lock -------------

    @Test
    fun `Storage filename literal is ayu-islands-syntax-intensity_xml (D-13 distinct from Phase 49)`() {
        val source = readStateSource()
        assertTrue(
            source.contains("\"ayu-islands-syntax-intensity.xml\""),
            "Pattern L lock — Storage filename literal must be \"ayu-islands-syntax-intensity.xml\" per D-13",
        )
        assertFalse(
            source.contains("\"ayu-islands-syntax-mode.xml\""),
            "must not reuse Phase 49 filename — Phase 50 state file is distinct per D-13",
        )
    }

    // --- Test 5 — @State name lock -----------------------------------------

    @Test
    fun `State name literal is AyuIslandsSyntaxIntensityState`() {
        val source = readStateSource()
        assertTrue(
            source.contains("AyuIslandsSyntaxIntensityState"),
            "Pattern L lock — State name literal must be AyuIslandsSyntaxIntensityState",
        )
    }

    // --- Test 6 — flat composite-key customOverrides round-trip ------------

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

    // --- Test 7 — schemaVersion round-trip ---------------------------------

    @Test
    fun `schemaVersion survives loadState round-trip for default and bumped values`() {
        // No mutation — verify the default schemaVersion survives the round-trip.
        val reloadedDefault = roundTrip { _ -> }
        assertEquals(1, reloadedDefault.state.schemaVersion)

        val reloadedBumped = roundTrip { state -> state.schemaVersion = 2 }
        assertEquals(2, reloadedBumped.state.schemaVersion, "schemaVersion 2 must round-trip for future migration")
    }

    // --- Test 8 — toPresetConfig bridge (Codex HIGH #1) --------------------

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

    // --- Test 9 — toPresetConfig skips malformed entries -------------------

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

    // --- Test 10 — Pattern L source-regex lock for getInstance() ----------

    @Test
    fun `getInstance companion is wired via ApplicationManager getService (source-regex lock)`() {
        val source = readStateSource()
        assertTrue(source.contains("fun getInstance"), "Pattern L lock — companion getInstance() must be present")
        // The source may split the chain across local-val lines to satisfy
        // detekt MaxLineLength; lock both fragments instead of the full chain.
        assertTrue(
            source.contains("ApplicationManager.getApplication()"),
            "Pattern L lock — getInstance() must call ApplicationManager.getApplication()",
        )
        assertTrue(
            source.contains("getService(SyntaxIntensityState::class.java)"),
            "Pattern L lock — getInstance() must resolve via getService(SyntaxIntensityState::class.java) " +
                "(live integration tested under src/test/kotlin/dev/ayuislands/integration/)",
        )
        // Sanity — assertNotNull on the source to keep the import referenced.
        assertNotNull(source, "state source must load from disk")
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

    private fun readStateSource(): String = Files.readString(Path.of(STATE_SOURCE_PATH))

    companion object {
        private const val STATE_SOURCE_PATH =
            "src/main/kotlin/dev/ayuislands/syntax/SyntaxIntensityState.kt"
    }
}
