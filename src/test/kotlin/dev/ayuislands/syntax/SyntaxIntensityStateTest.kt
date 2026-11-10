package dev.ayuislands.syntax

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.State
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.persistence.SyntaxStateView
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Font
import kotlin.test.assertNull

/** Legacy XML imports and accepted Studio projections preserve syntax choices. */
class SyntaxIntensityStateTest {
    @Test
    fun `default base state is AMBIENT preset with empty customOverrides readability off and schemaVersion 4`() {
        val state = SyntaxIntensityBaseState()
        assertEquals("AMBIENT", state.selectedPreset, "default selectedPreset must be AMBIENT per D-23")
        assertTrue(state.customOverrides.isEmpty(), "default customOverrides must be empty (free tier never writes)")
        assertTrue(state.customStyles.isEmpty(), "default customStyles must be empty (free tier never writes)")
        assertTrue(state.customEmphasis.isEmpty(), "default customEmphasis must be empty")
        assertFalse(state.dimComments, "Dim comments must be opt-in")
        assertFalse(state.softenDocumentation, "Soften documentation must be opt-in")
        assertFalse(state.quietOperators, "Quiet operators must be opt-in")
        assertFalse(state.emphasizeDeclarations, "Emphasize declarations must be opt-in")
        assertEquals(4, state.schemaVersion, "default schemaVersion must be 4 since emphasis was added")
    }

    @Test
    fun `selectedPreset string survives XML import and Studio round-trip`() {
        val reloaded = roundTrip { state -> state.selectedPreset = "NEON" }
        assertEquals("NEON", reloaded.selectedPreset)
    }

    @Test
    fun `tampered selectedPreset string falls back to AMBIENT via SyntaxPreset fromName (D-23)`() {
        val reloaded = roundTrip { state -> state.selectedPreset = "BOGUS_PRESET_FROM_TAMPERED_XML" }
        assertEquals("BOGUS_PRESET_FROM_TAMPERED_XML", reloaded.selectedPreset)
        assertSame(SyntaxPreset.AMBIENT, SyntaxPreset.fromName(reloaded.selectedPreset))
    }

    @Test
    fun `read facade has no independent persistent storage`() {
        assertNull(SyntaxIntensityState::class.java.getAnnotation(State::class.java))
    }

    @Test
    fun `read facade exposes accepted syntax and remains unavailable before bootstrap`() {
        val studio = mockk<StudioService>()
        mockkObject(StudioService.Companion)
        try {
            every { StudioService.getInstance() } returns studio
            every { studio.acceptedView } returns null
            val facade = SyntaxIntensityState()
            assertNull(facade.state)
            assertNull(facade.toPresetConfig())

            val accepted =
                StudioFixtures.legacyView(
                    AyuIslandsState(),
                    syntax = SyntaxIntensityBaseState().apply { selectedPreset = "NEON" },
                )
            every { studio.acceptedView } returns accepted
            assertSame(accepted.syntax, facade.state)
            assertEquals("NEON", facade.toPresetConfig()?.selectedPreset)
        } finally {
            unmockkObject(StudioService.Companion)
        }
    }

    @Test
    fun `flat composite-key customOverrides survives XML import and Studio round-trip`() {
        val reloaded =
            roundTrip { state ->
                state.customOverrides["Java|KEYWORD"] = "75"
                state.customOverrides["Java|COMMENT"] = "25"
                state.customOverrides["Kotlin|KEYWORD"] = "60"
            }
        assertEquals(3, reloaded.customOverrides.size)
        assertEquals("75", reloaded.customOverrides["Java|KEYWORD"])
        assertEquals("25", reloaded.customOverrides["Java|COMMENT"])
        assertEquals("60", reloaded.customOverrides["Kotlin|KEYWORD"])
    }

    @Test
    fun `schemaVersion survives XML import and Studio round-trip for default and bumped values`() {
        // No mutation - verify the default schemaVersion survives the round-trip.
        val reloadedDefault = roundTrip { _ -> }
        assertEquals(4, reloadedDefault.schemaVersion)

        val reloadedBumped = roundTrip { state -> state.schemaVersion = 3 }
        assertEquals(3, reloadedBumped.schemaVersion, "schemaVersion 3 must round-trip for future migration")
    }

    @Test
    fun `toPresetConfig adapts flat composite-key map to nested DTO consumed by SyntaxPreset detect`() {
        val component = SyntaxIntensityBaseState()
        component.selectedPreset = "NEON"
        component.customOverrides["Java|KEYWORD"] = "75"
        component.customOverrides["Java|COMMENT"] = "25"
        component.customOverrides["Kotlin|KEYWORD"] = "60"

        val config = view(component).toPresetConfig()

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

    @Test
    fun `toPresetConfig silently skips malformed flat keys and non-Int values`() {
        val component = SyntaxIntensityBaseState()
        component.selectedPreset = "WHISPER"
        // Malformed: no separator at all.
        component.customOverrides["BadKey"] = "50"
        // Malformed: empty language (separator at position 0).
        component.customOverrides["|MISSING_LANG"] = "30"
        // Malformed: empty category (separator at end).
        component.customOverrides["Java|"] = "40"
        // Malformed: non-Int value.
        component.customOverrides["Python|KEYWORD"] = "not-a-number"
        // Well-formed: the only entry that should survive.
        component.customOverrides["Java|KEYWORD"] = "75"

        val config = view(component).toPresetConfig()

        assertEquals("WHISPER", config.selectedPreset)
        val expected = mapOf("Java" to mapOf("KEYWORD" to 75))
        assertEquals(expected, config.customOverrides, "only the well-formed entry survives the bridge")
    }

    @Test
    fun `only the moved cells are persisted - untouched cells never materialise (D-01)`() {
        val reloaded =
            roundTrip { state ->
                state.customOverrides["Java|KEYWORD"] = "75"
                state.customOverrides["Kotlin|COMMENT"] = "30"
            }
        assertEquals(
            2,
            reloaded.customOverrides.size,
            "sparse store - exactly the 2 moved cells persist, untouched cells inherit the preset",
        )
        assertEquals("75", reloaded.customOverrides["Java|KEYWORD"])
        assertEquals("30", reloaded.customOverrides["Kotlin|COMMENT"])
    }

    @Test
    fun `subordinatePreset string survives XML import and Studio round-trip (D-07)`() {
        val reloaded = roundTrip { state -> state.subordinatePreset = "NEON" }
        assertEquals("NEON", reloaded.subordinatePreset)
    }

    @Test
    fun `fresh base state defaults subordinatePreset to AMBIENT (backward-compatible, D-07)`() {
        // Legacy / pre-50.1 XML has no subordinatePreset field; the string()
        // delegate default must deserialise it to AMBIENT so old state stays
        // valid without a forced migration.
        assertEquals("AMBIENT", SyntaxIntensityBaseState().subordinatePreset)
    }

    @Test
    fun `flat composite-key customStyles survives XML import and Studio round-trip (sparse)`() {
        val reloaded =
            roundTrip { state ->
                state.customStyles["Java|KEYWORD"] = "BOLD"
                state.customStyles["Kotlin|COMMENT"] = "ITALIC"
            }
        // Only the two styled cells materialise - untouched cells inherit.
        assertEquals(2, reloaded.customStyles.size, "sparse store - only styled cells persist")
        assertEquals("BOLD", reloaded.customStyles["Java|KEYWORD"])
        assertEquals("ITALIC", reloaded.customStyles["Kotlin|COMMENT"])
    }

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
        assertEquals(Font.PLAIN, FontStyleOverride.PLAIN.fontType)
        assertEquals(Font.BOLD, FontStyleOverride.BOLD.fontType)
        assertEquals(Font.ITALIC, FontStyleOverride.ITALIC.fontType)
        assertEquals(
            Font.BOLD or Font.ITALIC,
            FontStyleOverride.BOLD_ITALIC.fontType,
            "BOLD_ITALIC must be the combined bitmask (3) so bold+italic are combinable",
        )
    }

    @Test
    fun `FontStyleOverride flags include explicit regular replacement`() {
        assertSame(FontStyleOverride.PLAIN, FontStyleOverride.fromFlags(isBold = false, isItalic = false))
        assertSame(FontStyleOverride.BOLD, FontStyleOverride.fromFlags(isBold = true, isItalic = false))
        assertSame(FontStyleOverride.ITALIC, FontStyleOverride.fromFlags(isBold = false, isItalic = true))
        assertSame(FontStyleOverride.BOLD_ITALIC, FontStyleOverride.fromFlags(isBold = true, isItalic = true))
    }

    @Test
    fun `toPresetConfig decodes customStyles flat map to nested fontType bitmasks`() {
        val component = SyntaxIntensityBaseState()
        component.selectedPreset = "CUSTOM"
        component.customStyles["Java|KEYWORD"] = "BOLD_ITALIC"
        component.customStyles["Kotlin|COMMENT"] = "ITALIC"

        val config = view(component).toPresetConfig()

        val expected =
            mapOf(
                "Java" to mapOf("KEYWORD" to (Font.BOLD or Font.ITALIC)),
                "Kotlin" to mapOf("COMMENT" to Font.ITALIC),
            )
        assertEquals(expected, config.customStyles, "style bridge must decode to nested java.awt.Font bitmasks")
    }

    @Test
    fun `toPresetConfig silently skips malformed style keys and tampered style values`() {
        val component = SyntaxIntensityBaseState()
        component.selectedPreset = "CUSTOM"
        // Malformed key: no separator.
        component.customStyles["BadKey"] = "BOLD"
        // Malformed key: empty language.
        component.customStyles["|KEYWORD"] = "BOLD"
        // Malformed key: empty category.
        component.customStyles["Java|"] = "BOLD"
        // Tampered value: not a FontStyleOverride name.
        component.customStyles["Python|KEYWORD"] = "WAVY_UNDERSCORE"
        // Well-formed: the only entry that should survive.
        component.customStyles["Java|KEYWORD"] = "BOLD"

        val config = view(component).toPresetConfig()

        val expected = mapOf("Java" to mapOf("KEYWORD" to Font.BOLD))
        assertEquals(expected, config.customStyles, "only the well-formed, decodable style cell survives the bridge")
    }

    @Test
    fun `a v1 config without customStyles reads as an empty style map (backward compat)`() {
        // A pre-font-style (schemaVersion 1) config never wrote customStyles.
        // Loading it must leave customStyles empty so every cell inherits the
        // source font - no read-time migration required.
        val legacy = SyntaxIntensityBaseState()
        legacy.schemaVersion = 1
        legacy.customOverrides["Java|KEYWORD"] = "75"
        // No customStyles writes - simulates the old on-disk shape.

        val config = view(legacy).toPresetConfig()

        assertTrue(config.customStyles.isEmpty(), "absent customStyles must surface as an empty nested map")
        // Intensity overrides still decode - the bump is additive only.
        assertEquals(mapOf("Java" to mapOf("KEYWORD" to 75)), config.customOverrides)
    }

    @Test
    fun `readability toggles survive XML import and Studio round-trip`() {
        val reloaded =
            roundTrip { state ->
                state.dimComments = true
                state.softenDocumentation = true
                state.quietOperators = true
                state.emphasizeDeclarations = true
            }

        assertTrue(reloaded.dimComments)
        assertTrue(reloaded.softenDocumentation)
        assertTrue(reloaded.quietOperators)
        assertTrue(reloaded.emphasizeDeclarations)
    }

    @Test
    fun `toPresetConfig carries readability toggles without touching custom maps`() {
        val component = SyntaxIntensityBaseState()
        component.dimComments = true
        component.quietOperators = true

        val config = view(component).toPresetConfig()

        assertEquals(SyntaxReadabilityOptions(dimComments = true, quietOperators = true), config.readabilityOptions)
        assertTrue(config.customOverrides.isEmpty())
        assertTrue(config.customStyles.isEmpty())
        assertTrue(config.customEmphasis.isEmpty())
    }

    @Test
    fun `schema 4 customEmphasis tokens survive XML serialization`() {
        val original =
            SyntaxIntensityBaseState().apply {
                selectedPreset = "CUSTOM"
                customEmphasis["Java|FUNCTION_DECLARATION"] = "BOLD"
                customEmphasis["Kotlin|COMMENT"] = "ITALIC"
                customEmphasis["Python|CLASS_DECLARATION"] = "BOLD_ITALIC"
            }

        val serialized = XmlSerializer.serialize(original)
        val reloaded = view(XmlSerializer.deserialize(serialized, SyntaxIntensityBaseState::class.java))

        assertEquals(
            mapOf(
                "Java|FUNCTION_DECLARATION" to "BOLD",
                "Kotlin|COMMENT" to "ITALIC",
                "Python|CLASS_DECLARATION" to "BOLD_ITALIC",
            ),
            reloaded.customEmphasis,
        )
        assertEquals(
            mapOf(
                "Java" to mapOf("FUNCTION_DECLARATION" to Font.BOLD),
                "Kotlin" to mapOf("COMMENT" to Font.ITALIC),
                "Python" to mapOf("CLASS_DECLARATION" to (Font.BOLD or Font.ITALIC)),
            ),
            reloaded.toPresetConfig().customEmphasis,
        )
    }

    @Test
    fun `legacy and future syntax cells survive XML round trip without normalization`() {
        val original =
            SyntaxIntensityBaseState().apply {
                selectedPreset = "CUSTOM"
                customOverrides["Swift|OPERATOR"] = "73"
                customOverrides["Future Language|FUTURE_PRIMITIVE"] = "19"
                customStyles["Swift|OPERATOR"] = "PLAIN"
                customEmphasis["Kotlin|KEYWORD"] = "ITALIC"
            }
        val serialized = XmlSerializer.serialize(original)
        val reloaded = view(XmlSerializer.deserialize(serialized, SyntaxIntensityBaseState::class.java))

        assertEquals("CUSTOM", reloaded.selectedPreset)
        assertEquals("73", reloaded.customOverrides["Swift|OPERATOR"])
        assertEquals("19", reloaded.customOverrides["Future Language|FUTURE_PRIMITIVE"])
        assertEquals("PLAIN", reloaded.customStyles["Swift|OPERATOR"])
        assertEquals("ITALIC", reloaded.customEmphasis["Kotlin|KEYWORD"])
    }

    @Test
    fun `schema 3 XML preserves legacy state and leaves emphasis empty`() {
        val saved =
            XmlSerializer.deserialize(
                JDOMUtil.load(
                    """
                    <SyntaxIntensityBaseState>
                      <option name="selectedPreset" value="CUSTOM" />
                      <option name="subordinatePreset" value="NEON" />
                      <option name="customOverrides">
                        <map>
                          <entry key="Java|KEYWORD" value="72" />
                          <entry key="Kotlin|COMMENT" value="28" />
                        </map>
                      </option>
                      <option name="customStyles">
                        <map>
                          <entry key="Java|KEYWORD" value="PLAIN" />
                          <entry key="Java|CLASS_DECLARATION" value="BOLD" />
                          <entry key="Kotlin|COMMENT" value="ITALIC" />
                          <entry key="Kotlin|FUNCTION_DECLARATION" value="BOLD_ITALIC" />
                        </map>
                      </option>
                      <option name="dimComments" value="true" />
                      <option name="softenDocumentation" value="true" />
                      <option name="quietOperators" value="true" />
                      <option name="emphasizeDeclarations" value="true" />
                      <option name="schemaVersion" value="3" />
                    </SyntaxIntensityBaseState>
                    """.trimIndent(),
                ),
                SyntaxIntensityBaseState::class.java,
            )
        val reloaded = view(saved)

        assertEquals("CUSTOM", reloaded.selectedPreset)
        assertEquals("NEON", reloaded.subordinatePreset)
        assertEquals(3, reloaded.schemaVersion)
        assertEquals(
            mapOf("Java|KEYWORD" to "72", "Kotlin|COMMENT" to "28"),
            reloaded.customOverrides,
        )
        assertEquals(
            mapOf(
                "Java|KEYWORD" to "PLAIN",
                "Java|CLASS_DECLARATION" to "BOLD",
                "Kotlin|COMMENT" to "ITALIC",
                "Kotlin|FUNCTION_DECLARATION" to "BOLD_ITALIC",
            ),
            reloaded.customStyles,
        )
        assertTrue(reloaded.customEmphasis.isEmpty())
        assertTrue(reloaded.dimComments)
        assertTrue(reloaded.softenDocumentation)
        assertTrue(reloaded.quietOperators)
        assertTrue(reloaded.emphasizeDeclarations)
        assertEquals(
            mapOf(
                "Java" to
                    mapOf(
                        "KEYWORD" to Font.PLAIN,
                        "CLASS_DECLARATION" to Font.BOLD,
                    ),
                "Kotlin" to
                    mapOf(
                        "COMMENT" to Font.ITALIC,
                        "FUNCTION_DECLARATION" to (Font.BOLD or Font.ITALIC),
                    ),
            ),
            reloaded.toPresetConfig().customStyles,
        )
        assertTrue(reloaded.toPresetConfig().customEmphasis.isEmpty())
    }

    @Test
    fun `unknown emphasis token is ignored without touching legacy styles`() {
        val state =
            SyntaxIntensityBaseState().apply {
                customStyles["Java|KEYWORD"] = "ITALIC"
                customEmphasis["Java|KEYWORD"] = "UNDERLINE"
            }

        val config = view(state).toPresetConfig()

        assertEquals(mapOf("Java" to mapOf("KEYWORD" to Font.ITALIC)), config.customStyles)
        assertTrue(config.customEmphasis.isEmpty())
    }

    @Test
    fun `getInstance returns the SyntaxIntensityState application service`() {
        val application = mockk<Application>()
        val expectedService = SyntaxIntensityState()
        mockkStatic(ApplicationManager::class)
        try {
            every { ApplicationManager.getApplication() } returns application
            every { application.getService(SyntaxIntensityState::class.java) } returns expectedService

            val actual = SyntaxIntensityState.getInstance()
            assertSame(expectedService, actual)
        } finally {
            unmockkStatic(ApplicationManager::class)
        }
    }

    private fun roundTrip(mutate: (SyntaxIntensityBaseState) -> Unit): SyntaxStateView =
        view(SyntaxIntensityBaseState().also(mutate))

    private fun view(state: SyntaxIntensityBaseState): SyntaxStateView =
        StudioFixtures.persistedView(AyuIslandsState(), syntax = state).syntax
}
