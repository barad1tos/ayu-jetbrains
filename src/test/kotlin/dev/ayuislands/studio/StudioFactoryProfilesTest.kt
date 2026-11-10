package dev.ayuislands.studio

import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioFactoryProfilesTest {
    @Test
    fun `starter recipes cover complete accent glow syntax font and vcs defaults`() {
        val profiles = StudioFactoryProfiles.create(StudioFixtures.fields())
        profiles.forEachIndexed { index, profile ->
            val appearance = profile.appearance
            val preset = profile.name.uppercase()
            val accent = listOf("#D9BE98", "#FFCD66", "#5CCFE6", "#DFBFFF")[index]
            assertStrings(
                appearance.accent,
                mapOf(
                    "mirageAccent" to accent,
                    "darkAccent" to accent,
                    "lightAccent" to accent,
                    "accentRotationMode" to "PRESET",
                    "glowTabMode" to "MINIMAL",
                ),
            )
            assertFlags(
                appearance.accent,
                listOf(
                    "inlayHints",
                    "caretRow",
                    "progressBar",
                    "scrollbar",
                    "links",
                    "bracketMatch",
                    "searchResults",
                    "matchingTag",
                    "bracketScopeEnabled",
                    "chromeTintingEnabled",
                ),
                enabled = true,
            )
            assertFlags(
                appearance.accent,
                listOf(
                    "followSystemAccent",
                    "accentRotationEnabled",
                    "tabUnderlineGlowSync",
                    "chromeStatusBar",
                    "chromeMainToolbar",
                    "chromeToolWindowStripe",
                    "chromeNavBar",
                    "chromePanelBorder",
                ),
                enabled = false,
            )
            assertIntegers(
                appearance.accent,
                mapOf(
                    "accentRotationIntervalHours" to 6,
                    "tabUnderlineHeight" to 4,
                    "chromeTintIntensity" to 40,
                ),
            )
            assertEquals(emptyList(), assertIs<RawValue.OrderedSet>(appearance.accent.value("forceOverrides")).values)
            assertStrings(appearance.font, mapOf("fontPresetName" to preset))
            assertFlags(appearance.font, listOf("fontPresetEnabled"), enabled = true)
            assertFlags(appearance.font, listOf("fontApplyToConsole"), enabled = false)
            assertEquals(
                emptyList(),
                assertIs<RawValue.OrderedMap>(appearance.font.value("fontPresetCustomizations")).entries,
            )
            assertGlow(appearance.glow, index)
            assertStrings(appearance.syntax, mapOf("selectedPreset" to preset, "subordinatePreset" to preset))
            assertFlags(
                appearance.syntax,
                listOf(
                    "dimComments",
                    "softenDocumentation",
                    "quietOperators",
                    "emphasizeDeclarations",
                ),
                enabled = false,
            )
            for (name in listOf("customOverrides", "customStyles", "customEmphasis")) {
                assertEquals(emptyList(), assertIs<RawValue.OrderedMap>(appearance.syntax.value(name)).entries)
            }
            assertIntegers(appearance.syntax, mapOf("schemaVersion" to 4))
            assertFlags(appearance.vcs, listOf("vcsColorEnabled"), enabled = true)
            assertStrings(
                appearance.vcs,
                mapOf(
                    "vcsDiffPreset" to preset,
                    "vcsMergePreset" to preset,
                    "vcsBlamePreset" to preset,
                ),
            )
            for ((key, value) in appearance.vcs.records.filter { it.key.name.endsWith("Intensity") }) {
                assertEquals(RawValue.Integer(listOf(0, 33, 67, 100)[index]), value, key.name)
            }
        }
    }

    private fun assertGlow(
        fields: StudioFields,
        index: Int,
    ) {
        assertStrings(
            fields,
            mapOf(
                "glowShape" to "SOLID",
                "glowEditorPlacement" to "ISLAND",
                "glowToolWindowPlacement" to "ISLAND",
                "waveformDirection" to "CLOCKWISE",
                "waveformBaseline" to "CENTERED",
                "glowAnimation" to listOf("NONE", "BREATHE", "NONE", "PULSE")[index],
            ),
        )
        assertFlags(
            fields,
            listOf(
                "glowEnabled",
                "glowEditor",
                "glowProject",
                "glowTerminal",
                "glowRun",
                "glowDebug",
                "glowGit",
                "glowServices",
                "glowFocusRing",
            ),
            enabled = true,
        )
        assertIntegers(
            fields,
            mapOf(
                "softIntensity" to listOf(35, 20, 20, 20)[index],
                "softWidth" to listOf(8, 4, 4, 4)[index],
                "gradientIntensity" to listOf(30, 45, 30, 30)[index],
                "gradientWidth" to listOf(6, 10, 6, 6)[index],
                "sharpNeonIntensity" to listOf(50, 50, 65, 85)[index],
                "sharpNeonWidth" to listOf(4, 4, 8, 10)[index],
                "waveformTraceDensity" to 1,
                "waveformTraceLength" to 199,
                "waveformAmplitude" to 24,
                "waveformIntensity" to 100,
            ),
        )
        assertEquals(RawValue.FloatBits(20f.toRawBits()), fields.value("waveformLoopSeconds"))
    }

    private fun assertStrings(
        fields: StudioFields,
        expected: Map<String, String>,
    ) {
        for ((name, value) in expected) assertEquals(RawValue.Text(value), fields.value(name), name)
    }

    private fun assertFlags(
        fields: StudioFields,
        names: List<String>,
        enabled: Boolean,
    ) {
        for (name in names) assertEquals(RawValue.BooleanValue(enabled), fields.value(name), name)
    }

    private fun assertIntegers(
        fields: StudioFields,
        expected: Map<String, Int>,
    ) {
        for ((name, value) in expected) assertEquals(RawValue.Integer(value), fields.value(name), name)
    }

    @Test
    fun `four named recipes retain inactive slots and enable their complete appearance`() {
        val profiles = StudioFactoryProfiles.create(StudioFixtures.fields())
        assertEquals(listOf("Whisper", "Ambient", "Neon", "Cyberpunk"), profiles.map { it.name })
        val accents = listOf("#D9BE98", "#FFCD66", "#5CCFE6", "#DFBFFF")
        profiles.forEachIndexed { index, profile ->
            val preset = GlowPreset.valueOf(profile.name.uppercase())
            val appearance = profile.appearance
            assertEquals("factory:${profile.name.lowercase()}", profile.id.value)
            assertEquals(RawValue.Text(accents[index]), appearance.accent.value("mirageAccent"))
            assertEquals(RawValue.BooleanValue(false), appearance.accent.value("followSystemAccent"))
            assertEquals(RawValue.BooleanValue(false), appearance.accent.value("accentRotationEnabled"))
            assertEquals(RawValue.Integer(6), appearance.accent.value("accentRotationIntervalHours"))
            assertEquals(RawValue.BooleanValue(true), appearance.font.value("fontPresetEnabled"))
            assertEquals(RawValue.BooleanValue(true), appearance.glow.value("glowEnabled"))
            assertEquals(RawValue.BooleanValue(true), appearance.vcs.value("vcsColorEnabled"))
            assertEquals(RawValue.Text(preset.name), appearance.syntax.value("selectedPreset"))
            assertEquals(RawValue.Text(preset.style?.name.orEmpty()), appearance.glow.value("glowStyle"))
            assertEquals(RawValue.Integer(if (index == 0) 35 else 20), appearance.glow.value("softIntensity"))
            assertEquals(RawValue.Integer(if (index == 1) 45 else 30), appearance.glow.value("gradientIntensity"))
            assertEquals(RawValue.Text("CENTERED"), appearance.glow.value("waveformBaseline"))
        }
    }
}
