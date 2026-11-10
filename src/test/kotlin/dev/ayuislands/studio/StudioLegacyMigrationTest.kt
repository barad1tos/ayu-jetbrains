package dev.ayuislands.studio

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.studio.migration.MigrationIssue
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.session.StudioProfileProjection
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class StudioLegacyMigrationTest {
    @Test
    fun `incomplete capture archive or factory catalog blocks migration without guessing missing state`() {
        val input = StudioFixtures.capture()
        val missing = input.fields.records.first()
        val incomplete = input.copy(fields = input.fields.copy(records = StudioList(input.fields.records.drop(1))))
        val result = assertIs<MigrationResult.Conflict>(StudioLegacyMigration.plan(incomplete))
        assertEquals(MigrationIssue.INCOMPLETE_CAPTURE, result.issue)
        assertEquals(listOf(missing.key), result.fields)
        val missingArchive =
            input.copy(
                source = input.source.copy(archives = StudioList(input.source.archives.drop(1))),
            )
        assertEquals(
            MigrationIssue.INCOMPLETE_ARCHIVE,
            assertIs<MigrationResult.Conflict>(StudioLegacyMigration.plan(missingArchive)).issue,
        )
        assertEquals(
            MigrationIssue.INVALID_FACTORIES,
            assertIs<MigrationResult.Conflict>(
                StudioLegacyMigration.plan(input.copy(factories = StudioList(input.factories.reversed()))),
            ).issue,
        )
    }

    @Test
    fun `unknown ordered records remain opaque and ordered alongside their original archives`() {
        val original = StudioFixtures.load("unknown-and-ordered")
        val extensions =
            StudioList(
                listOf(
                    OpaqueRecord("z-future", StudioBytes(byteArrayOf(0, 1, -1))),
                    OpaqueRecord("a-future", StudioBytes("  $".encodeToByteArray())),
                ),
            )
        val input = original.copy(fields = original.fields.copy(extensions = extensions))
        val output = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(input)).document
        assertEquals(extensions, output.extensions)
        assertEquals(original.source, output.header.migration)
        assertEquals(
            input.fields.value("fontPresetCustomizations"),
            output.currents.global.appearance.font
                .value("fontPresetCustomizations"),
        )
        assertEquals(input.fields.value("projectAccents"), output.shared.mappings.value("projectAccents"))
        assertEquals(
            input.fields.value("forceOverrides"),
            output.currents.global.appearance.accent
                .value("forceOverrides"),
        )
    }

    @Test
    fun `source shaped XML fixtures preserve every field and the complete original byte archives`() {
        val fixtures =
            listOf(
                "fresh-absent",
                "legacy-omitted",
                "mixed-user-values",
                "unknown-and-ordered",
                "existing-ownership",
            )
        for (name in fixtures) {
            val input = StudioFixtures.load(name)
            val output = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(input)).document
            val records =
                listOf(
                    output.currents.global.appearance.accent,
                    output.currents.global.appearance.font,
                    output.currents.global.appearance.glow,
                    output.currents.global.appearance.syntax,
                    output.currents.global.appearance.vcs,
                    output.shared.plugins,
                    output.shared.workspace,
                    output.shared.system,
                    output.shared.mappings,
                    output.auxiliary.ui,
                    output.auxiliary.resources,
                    output.auxiliary.legacyMarkers,
                    output.runtime.legacy,
                ).flatMap { it.records }
            assertEquals(input.fields.records.associateBy { it.key }, records.associateBy { it.key }, name)
            assertEquals(input.source, output.header.migration, name)
            assertEquals(197, records.size, name)
            val captured = input.fields.records.associateBy { it.key.name }
            for (line in StudioFixtures.resourceLines(name, "expected.tsv")) {
                val parts = line.split('\t')
                val actual = captured.getValue(parts[1]).value
                try {
                    assertExpectedValue(parts[2], parts[3], actual)
                } catch (failure: AssertionError) {
                    throw AssertionError("$name: ${parts[0]}.${parts[1]}", failure)
                }
            }
        }
    }

    private fun assertExpectedValue(
        type: String,
        encoded: String,
        actual: RawValue,
    ) {
        fun decode(value: String): String = Base64.getDecoder().decode(value).decodeToString()
        when (type) {
            "TEXT" -> {
                val expected =
                    if (encoded == "-" ||
                        encoded.isEmpty()
                    ) {
                        RawValue.Null
                    } else {
                        RawValue.Text(decode(encoded))
                    }
                assertEquals(expected, actual)
            }
            "BOOLEAN" -> assertEquals(RawValue.BooleanValue(decode(encoded).toBooleanStrict()), actual)
            "INTEGER" -> assertEquals(RawValue.Integer(decode(encoded).toInt()), actual)
            "LONG" -> assertEquals(RawValue.LongValue(decode(encoded).toLong()), actual)
            "FLOAT" -> assertEquals(RawValue.FloatBits(decode(encoded).toFloat().toRawBits()), actual)
            "STRING_SET" ->
                assertEquals(
                    encoded.split(',').map(::decode).toSet(),
                    assertIs<RawValue.OrderedSet>(actual).values.toSet(),
                )
            "STRING_MAP" -> {
                val expected =
                    encoded.split(',').associate { pair ->
                        val parts = pair.split(':')
                        decode(parts[0]) to RawValue.Text(decode(parts[1]))
                    }
                assertEquals(expected, assertIs<RawValue.OrderedMap>(actual).entries.associate { it.key to it.value })
            }
            else -> error("Unknown fixture type: $type")
        }
    }

    @Test
    fun `upgrade keeps mixed appearance hidden slots resources and global follow policy`() {
        val legacy =
            AyuIslandsState().apply {
                accentRotationEnabled = true
                accentRotationIntervalHours = 9
                glowShape = "WAVEFORM"
                glowStyle = "FUTURE_STYLE"
                softIntensity = 17
                sharpNeonWidth = 23
                waveformDirection = "CHAOTIC"
                glowEditor = false
                glowTerminal = false
                followSystemAccent = true
                fontPresetName = "CYBERPUNK"
                vcsDiffPreset = "WHISPER"
                vcsMergePreset = "NEON"
                fontPresetCustomizations["FUTURE_FONT"] = "14|1.33|false|future|"
                installedFonts.add("Unknown installed font")
                explicitlyUninstalledFonts.add("Maple Mono")
                settingsSelectedTab = 5
            }
        val capture = StudioFixtures.capture(legacy)
        val result = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture)).document
        val current = result.currents.global
        val custom = assertIs<ProfileDefinition.User>(result.catalog.last())

        assertEquals(5, result.catalog.size)
        assertEquals("Custom", custom.name)
        assertEquals("migrated-custom:migration-1", custom.id.value)
        assertEquals(current.appearance, custom.appearance)
        assertNotSame(current.appearance, custom.appearance)
        assertEquals(ProfileReference(custom.id, custom.revision), current.source)
        val profileView = StudioProfileProjection.project(result, sessionState(result))
        val customChoice = StudioProfileChoice.Profile(custom.id)
        assertEquals(customChoice, profileView.selected)
        assertEquals("Custom", profileView.currentName)
        assertEquals(listOf(customChoice), profileView.rows.filter { it.isCurrent }.map { it.choice })
        assertEquals(SystemAccentPolicy.FORCE_ON, result.shared.systemAccentPolicy)
        assertEquals(RawValue.BooleanValue(true), current.appearance.accent.value("accentRotationEnabled"))
        assertEquals(RawValue.Text("WAVEFORM"), current.appearance.glow.value("glowShape"))
        assertEquals(RawValue.Text("FUTURE_STYLE"), current.appearance.glow.value("glowStyle"))
        val migrated =
            listOf(
                current.appearance.accent,
                current.appearance.font,
                current.appearance.glow,
                current.appearance.syntax,
                current.appearance.vcs,
                result.shared.plugins,
                result.shared.workspace,
                result.shared.system,
                result.shared.mappings,
                result.auxiliary.ui,
                result.auxiliary.resources,
                result.auxiliary.legacyMarkers,
                result.runtime.legacy,
            ).flatMap { it.records }.associateBy { it.key }
        assertEquals(capture.fields.records.associateBy { it.key }, migrated)
        assertEquals(capture.source, result.header.migration)
        assertEquals(result, assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture)).document)
    }

    @Test
    fun `absent files and existing omitted fields retain their distinct waveform defaults`() {
        val fresh =
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(
                    StudioFixtures.capture(freshSettingsState(), fresh = true),
                ),
            ).document
        val legacy = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture())).document

        assertEquals(4, fresh.catalog.size)
        assertEquals(SystemAccentPolicy.BY_PROFILE, fresh.shared.systemAccentPolicy)
        assertEquals(SystemAccentPolicy.FORCE_OFF, legacy.shared.systemAccentPolicy)
        assertEquals(
            RawValue.Text("CENTERED"),
            fresh.currents.global.appearance.glow
                .value("waveformBaseline"),
        )
        assertEquals(
            RawValue.Text("OUTSIDE"),
            legacy.currents.global.appearance.glow
                .value("waveformBaseline"),
        )
        assertNull(fresh.currents.global.source)
    }
}
