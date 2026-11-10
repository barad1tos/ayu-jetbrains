package dev.ayuislands.studio

import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioFontRequest
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioFontFacts
import dev.ayuislands.studio.session.StudioFonts
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioFontsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual curated alias satisfies availability without changing the canonical request`() {
        val resources = resources()
        val preset = FontPreset.AMBIENT
        val alias = preset.fontAliases.last().lowercase()
        val requested = StudioFontRequest.Enabled(FontSettings.fromPreset(preset))
        val observed = StudioFontFacts(resources, listOf(alias), emptyMap())

        assertEquals(FontAvailability.AVAILABLE, StudioFonts.availability(requested, resources, observed))
        assertEquals(preset.fontFamily, requested.settings.fontFamily)
        assertEquals(
            FontAvailability.MISSING_CURATED,
            StudioFonts.availability(requested, resources, observed.copy(families = emptyList())),
        )
    }

    @Test
    fun `recorded healthy files need a restart until the JVM exposes their family`() {
        val preset = FontPreset.AMBIENT
        val path = directory.resolve("installed.ttf").toString()
        val resources = resources(listOf(preset.fontFamily), mapOf(preset.fontFamily to path))
        val requested = StudioFontRequest.Enabled(FontSettings.fromPreset(preset))
        val observed = StudioFontFacts(resources, emptyList(), mapOf(path to true))

        assertEquals(FontAvailability.RESTART_REQUIRED, StudioFonts.availability(requested, resources, observed))
        assertEquals(
            FontAvailability.AVAILABLE,
            StudioFonts.availability(requested, resources, observed.copy(families = listOf(preset.fontFamily))),
        )
        val legacy = resources(listOf(preset.fontFamily))
        assertEquals(
            FontAvailability.RESTART_REQUIRED,
            StudioFonts.availability(requested, legacy, StudioFontFacts(legacy, emptyList(), emptyMap())),
        )
    }

    @Test
    fun `missing recorded file is corrupted only after the JVM loses the recorded family`() {
        val preset = FontPreset.AMBIENT
        val path = directory.resolve("removed.ttf").toString()
        val resources = resources(listOf(preset.fontFamily), mapOf(preset.fontFamily to path))
        val requested = StudioFontRequest.Enabled(FontSettings.fromPreset(preset))
        val missing = StudioFontFacts(resources, emptyList(), mapOf(path to false))

        assertEquals(FontAvailability.CORRUPTED, StudioFonts.availability(requested, resources, missing))
        assertEquals(
            FontAvailability.AVAILABLE,
            StudioFonts.availability(requested, resources, missing.copy(families = listOf(preset.fontFamily))),
        )
        assertEquals(
            FontAvailability.UNCONFIRMED,
            StudioFonts.availability(requested, resources, missing.copy(files = emptyMap())),
        )
    }

    @Test
    fun `custom selection requires its exact family and does not borrow a curated alias`() {
        val resources = resources()
        val requested =
            StudioFontRequest.Enabled(FontSettings.fromPreset(FontPreset.CUSTOM).copy(fontFamily = "User Font"))
        val unrelated = StudioFontFacts(resources, listOf(FontPreset.AMBIENT.fontFamily), emptyMap())

        assertEquals(FontAvailability.MISSING_CUSTOM, StudioFonts.availability(requested, resources, unrelated))
        assertEquals(
            FontAvailability.AVAILABLE,
            StudioFonts.availability(requested, resources, unrelated.copy(families = listOf("user font"))),
        )
        assertEquals("User Font", requested.settings.fontFamily)
    }

    @Test
    fun `unmatched resource inventory and unknown requests remain unconfirmed`() {
        val accepted = resources()
        val requested = StudioFontRequest.Enabled(FontSettings.fromPreset(FontPreset.AMBIENT))
        val observed = StudioFontFacts(accepted, listOf(FontPreset.AMBIENT.fontFamily), emptyMap())
        val changed = resources(listOf(FontPreset.AMBIENT.fontFamily))

        assertEquals(FontAvailability.UNCONFIRMED, StudioFonts.availability(requested, accepted, null))
        assertEquals(FontAvailability.UNCONFIRMED, StudioFonts.availability(requested, changed, observed))
        assertEquals(
            FontAvailability.UNCONFIRMED,
            StudioFonts.availability(StudioFontRequest.Unavailable("future-preset"), accepted, observed),
        )
    }

    @Test
    fun `capture checks real recorded paths using the existing path codec without rewriting resources`() {
        val present = Files.write(directory.resolve("a font.ttf"), byteArrayOf(1))
        val missing = directory.resolve("missing font.ttf")
        val encoded = AyuIslandsState.encodeFontPaths(listOf(present.toFile(), missing.toFile()))
        val resources =
            resources(
                listOf(FontPreset.AMBIENT.fontFamily),
                mapOf(
                    FontPreset.AMBIENT.fontFamily to encoded,
                ),
            )

        val observed = runBlocking { StudioFonts.capture(resources) }

        assertEquals(resources, observed.resources)
        assertTrue(observed.files.getValue(present.toString()))
        assertFalse(observed.files.getValue(missing.toString()))
        assertTrue(Files.exists(present))
        assertFalse(Files.exists(missing))
    }

    private fun resources(
        installed: List<String> = emptyList(),
        paths: Map<String, String> = emptyMap(),
    ): StudioFields {
        val fields = sessionDocument().auxiliary.resources
        return fields.copy(
            records =
                StudioList(
                    fields.records.map { field ->
                        val next =
                            when (field.key.name) {
                                "installedFonts" -> RawValue.OrderedSet(StudioList(installed))
                                "installedFontFiles" ->
                                    RawValue.OrderedMap(
                                        StudioList(
                                            paths.map { (family, encoded) ->
                                                RawValue.Entry(family, RawValue.Text(encoded))
                                            },
                                        ),
                                    )
                                else -> field.value
                            }
                        StudioFieldEdits.edit(field, FieldPart.Value, FieldValue.Present(next))
                    },
                ),
        )
    }
}
