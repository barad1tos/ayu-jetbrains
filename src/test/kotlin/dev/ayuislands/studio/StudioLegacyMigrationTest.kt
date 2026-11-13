package dev.ayuislands.studio

import dev.ayuislands.rotation.FullIntervalReason
import dev.ayuislands.rotation.RotationResume
import dev.ayuislands.rotation.RotationSchedule
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.studio.migration.MigrationIssue
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.FactoryRecipe
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.persistence.LegacyCapture
import dev.ayuislands.studio.persistence.LegacyCaptureIssue
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.persistence.StudioStateCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class StudioLegacyMigrationTest {
    @Test
    fun `fresh and omitted waveform settings use their own v2 defaults`() {
        val fresh = migrate(source())
        val existing = migrate(source(settings = component("AyuIslandsSettings", "")))

        assertEquals(FactoryRecipe.entries.map { it.id }, fresh.catalog.map { it.id })
        assertNull(fresh.currents.global.source)
        assertEquals(SystemAccentPolicy.BY_PROFILE, fresh.shared.systemAccentPolicy)
        assertEquals(SystemAccentPolicy.FORCE_OFF, existing.shared.systemAccentPolicy)
        assertEquals(
            RawValue.FloatBits(freshSettingsState().waveformLoopSeconds.toRawBits()),
            fresh.currents.global.appearance.glow
                .value("waveformLoopSeconds"),
        )
        assertEquals(
            RawValue.FloatBits(AyuIslandsState().waveformLoopSeconds.toRawBits()),
            existing.currents.global.appearance.glow
                .value("waveformLoopSeconds"),
        )
        assertEquals(
            "Custom",
            existing.catalog
                .filterIsInstance<ProfileDefinition.User>()
                .single()
                .name,
        )
    }

    @Test
    fun `upgrade preserves mixed appearance and resumes the existing rotation appointment`() {
        val waveform = "<option  name='glowShape' value='WAVEFORM' />"
        val unknown = "<option name='futureGlow' value='&#65;β' />"
        val input =
            source(
                settings =
                    component(
                        "AyuIslandsSettings",
                        """
                            $waveform
                            $unknown
                            <option name="glowGit" value="false" />
                            <option name="glowEditor" value="true" />
                            <option name="accentRotationEnabled" value="true" />
                            <option name="accentRotationIntervalHours" value="6" />
                            <option name="accentRotationLastSwitchMs" value="1234" />
                            <option name="accentRotationPresetIndex" value="-3" />
                            <option name="followSystemAccent" value="true" />
                            <option name="fontPresetName" value="UNKNOWN_FONT" />
                            <option name="vcsDiffPreset" value="NEON" />
                            <option name="vcsMergePreset" value="AMBIENT" />
                            <option name="autoFitProjectPanelWidth" value="true" />
                        <option name="commitPanelWidthMode" value="FUTURE_MODE" />
                        <option name="autoFitCommitPanelWidth" value="true" />
                            <option name="trialWelcomeShown" value="true" />
                        <option name="forceOverrides"><set><option value="z" /><option value="a" /></set></option>
                        """.trimIndent(),
                    ),
                syntax =
                    component(
                        "AyuIslandsSyntaxIntensityState",
                        "<option name='selectedPreset' value='CUSTOM' />" +
                            "<option name='customStyles'><map>" +
                            "<entry key='z' value='FUTURE_STYLE' /><entry key='a' value='' />" +
                            "</map></option>",
                    ),
            )
        val document = migrate(input)
        val current = document.currents.global.appearance
        val custom = document.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val rotation = requireNotNull(document.runtime.rotation)

        assertEquals(input, document.header.migration)
        assertEquals(current, custom.appearance)
        assertNotSame(current, custom.appearance)
        assertEquals(RawValue.Text("WAVEFORM"), current.glow.value("glowShape"))
        assertEquals(RawValue.BooleanValue(false), current.glow.value("glowGit"))
        assertEquals(RawValue.BooleanValue(true), current.glow.value("glowEditor"))
        assertEquals(RawValue.Text("UNKNOWN_FONT"), current.font.value("fontPresetName"))
        assertEquals(RawValue.Text("NEON"), current.vcs.value("vcsDiffPreset"))
        assertEquals(RawValue.Text("AMBIENT"), current.vcs.value("vcsMergePreset"))
        val styles = assertIs<RawValue.OrderedMap>(current.syntax.value("customStyles"))
        assertEquals(listOf("z", "a"), styles.entries.map { it.key })
        assertEquals(listOf(RawValue.Text("FUTURE_STYLE"), RawValue.Text("")), styles.entries.map { it.value })
        assertEquals(RawValue.BooleanValue(true), current.accent.value("followSystemAccent"))
        assertEquals(SystemAccentPolicy.FORCE_ON, document.shared.systemAccentPolicy)
        assertEquals(RawValue.Text("AUTO_FIT"), document.shared.workspace.value("projectPanelWidthMode"))
        assertEquals(RawValue.Text("FUTURE_MODE"), document.shared.workspace.value("commitPanelWidthMode"))
        assertEquals(RawValue.OrderedSet(StudioList(listOf("z", "a"))), current.accent.value("forceOverrides"))
        assertEquals(RawValue.BooleanValue(true), document.auxiliary.ui.value("premiumOnboardingShown"))
        assertEquals(-3, rotation.sequenceCursorRaw)
        assertEquals(1234L, rotation.lastSuccessfulStepEpochMillis)
        assertEquals(RotationSchedule(1234, 21_601_234, 21_600_000, 1), rotation.schedule)
        assertNull(rotation.output)
        val origin =
            current.glow.records
                .single { it.key.name == "glowShape" }
                .origin
        assertEquals(SourcePresence.VALUE, origin.presence)
        assertContentEquals(waveform.encodeToByteArray(), requireNotNull(origin.lexeme).toByteArray())
        assertContentEquals(
            unknown.encodeToByteArray(),
            document.extensions
                .single()
                .bytes
                .toByteArray(),
        )
        assertContentEquals(StudioStateCodec.encode(document), StudioStateCodec.encode(migrate(input)))
    }

    @Test
    fun `startup distinguishes no history from invalid retained timestamps`() {
        for ((stamp, resume) in listOf(
            0L to RotationResume.NoHistory,
            -1L to RotationResume.FullInterval(FullIntervalReason.REPAIRED_SCHEDULE),
            Long.MAX_VALUE to RotationResume.FullInterval(FullIntervalReason.REPAIRED_SCHEDULE),
        )) {
            val input =
                source(
                    settings =
                        component(
                            "AyuIslandsSettings",
                            "<option name='accentRotationEnabled' value='true' />" +
                                "<option name='accentRotationLastSwitchMs' value='$stamp' />",
                        ),
                )
            val document = migrate(input)
            assertEquals(RawValue.LongValue(stamp), document.runtime.legacy.value("accentRotationLastSwitchMs"))
            val rotation = requireNotNull(document.runtime.rotation)
            assertEquals(resume, rotation.resume)
            assertNull(rotation.schedule)
            assertNull(rotation.output)
        }
    }

    @Test
    fun `macro expansion preserves map order and rejects collisions without dropping the archive`() {
        val prefix = $$"$USER_HOME$"

        fun entries(second: String): MigrationSource =
            source(
                mappings =
                    component(
                        "AyuIslandsAccentMappings",
                        "<option name='projectAccents'><map>" +
                            "<entry key='$prefix/z' value='#FFCD66' /><entry key='$second' value='#5CCFE6' />" +
                            "</map></option>",
                    ),
            )

        val original = entries("/work/a")
        val document = migrate(original)
        val values = assertIs<RawValue.OrderedMap>(document.shared.mappings.value("projectAccents"))
        assertEquals(listOf("/home/ayu/z", "/work/a"), values.entries.map { it.key })
        assertEquals(original, document.header.migration)

        val collision = entries("/home/ayu/z")
        val rejected = assertIs<LegacyCapture.Blocked>(StudioLegacyCapture.read(collision, "transaction", "/home/ayu"))
        assertEquals(LegacyCaptureIssue.PATH_CONFLICT, rejected.issue)
        assertEquals(collision, rejected.source)
    }

    @Test
    fun `malformed source is distinct from a missing original file`() {
        val broken = source(settings = "<application>")
        val rejected = assertIs<LegacyCapture.Blocked>(StudioLegacyCapture.read(broken, "transaction", "/home/ayu"))
        assertEquals(LegacyCaptureIssue.INVALID_DATA, rejected.issue)
        assertEquals(broken, rejected.source)
    }

    @Test
    fun `planner rejects incomplete captures instead of filling values from factory defaults`() {
        val original = source()
        val result = StudioLegacyCapture.read(original, "transaction", "/home/ayu")
        val capture = assertIs<LegacyCapture.Ready>(result).snapshot
        val records = capture.fields.records
        val missing = capture.fields.copy(records = StudioList(records.drop(1)))
        val invalid = assertIs<MigrationResult.Conflict>(StudioLegacyMigration.plan(capture.copy(fields = missing)))
        assertEquals(MigrationIssue.INCOMPLETE_CAPTURE, invalid.issue)
        assertEquals(listOf(records[0].key), invalid.fields)

        val partial = original.copy(archives = StudioList(original.archives.drop(1)))
        val cases =
            listOf(
                capture.copy(source = partial) to MigrationIssue.INCOMPLETE_ARCHIVE,
                capture.copy(factories = StudioList(capture.factories.reversed())) to MigrationIssue.INVALID_FACTORIES,
                capture.copy(transactionId = "") to MigrationIssue.INVALID_IDENTITY,
            )
        for ((snapshot, issue) in cases) {
            assertEquals(issue, assertIs<MigrationResult.Conflict>(StudioLegacyMigration.plan(snapshot)).issue)
        }
    }

    private fun migrate(source: MigrationSource): StudioDocument {
        val capture = assertIs<LegacyCapture.Ready>(StudioLegacyCapture.read(source, "transaction", "/home/ayu"))
        return assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture.snapshot)).document
    }

    private fun source(
        settings: String? = null,
        mappings: String? = null,
        syntax: String? = null,
    ): MigrationSource {
        val xml =
            mapOf(LegacyStore.SETTINGS to settings, LegacyStore.MAPPINGS to mappings, LegacyStore.SYNTAX to syntax)
        val archives =
            LegacyStore.entries.map { store ->
                LegacyArchive(
                    store,
                    xml[store]?.let { LegacyFile.Present(StudioBytes(it.encodeToByteArray())) } ?: LegacyFile.Absent,
                )
            }
        return MigrationSource("fixture", StudioList(archives))
    }

    private fun component(
        name: String,
        body: String,
    ): String = "<application><component name='$name'>$body</component></application>"

    private fun StudioFields.value(name: String): RawValue = records.single { it.key.name == name }.value
}
