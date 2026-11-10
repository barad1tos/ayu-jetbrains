package dev.ayuislands.studio

import dev.ayuislands.rotation.FullIntervalReason
import dev.ayuislands.rotation.RotationResume
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationSchedule
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.studio.migration.LegacySnapshot
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioTarget
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StudioLegacyRotationImportTest {
    @Test
    fun `legacy appointment and dormant cursor are imported without advancing a step or changing raw fields`() {
        for (enabled in listOf(false, true)) {
            val capture = capture(enabled, 2 * HOUR, Int.MIN_VALUE, 6)
            val document = migrate(capture)
            val checkpoint = assertNotNull(document.runtime.rotation)

            assertEquals(RuntimeOwner(StudioTarget.Global, document.currents.global.revision), checkpoint.owner)
            assertEquals(Int.MIN_VALUE, checkpoint.sequenceCursorRaw)
            assertEquals(0L, checkpoint.successfulStepSerial)
            assertEquals(2 * HOUR, checkpoint.lastSuccessfulStepEpochMillis)
            assertNull(checkpoint.output)
            assertEquals(
                if (enabled) RotationSchedule(2 * HOUR, 8 * HOUR, 6 * HOUR, 1) else null,
                checkpoint.schedule,
            )
            assertEquals(RotationResume.KeepDeadline, checkpoint.resume)
            assertEquals(RawValue.Integer(Int.MIN_VALUE), document.runtime.legacy.value("accentRotationPresetIndex"))
            assertEquals(RawValue.LongValue(2 * HOUR), document.runtime.legacy.value("accentRotationLastSwitchMs"))
            assertEquals(capture.source, document.header.migration)
            assertEquals(document, migrate(capture))
        }
    }

    @Test
    fun `only recognized zero legacy history requests a restored first step`() {
        val zero = assertNotNull(migrate(capture(true, 0)).runtime.rotation)
        assertEquals(RotationResume.NoHistory, zero.resume)
        assertNull(zero.schedule)
        assertNull(zero.lastSuccessfulStepEpochMillis)
        assertEquals(0L, zero.successfulStepSerial)

        for (lastSwitch in listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val document = migrate(capture(true, lastSwitch))
            val checkpoint = assertNotNull(document.runtime.rotation)
            assertEquals(RotationResume.FullInterval(FullIntervalReason.REPAIRED_SCHEDULE), checkpoint.resume)
            assertNull(checkpoint.schedule)
            assertEquals(0L, checkpoint.successfulStepSerial)
            assertEquals(RawValue.LongValue(lastSwitch), document.runtime.legacy.value("accentRotationLastSwitchMs"))
        }
    }

    @Test
    fun `future legacy timestamps remain exact and interval interpretation reuses the full raw range`() {
        for (hours in listOf(Int.MIN_VALUE, 1, Int.MAX_VALUE)) {
            val capture = capture(true, 20 * HOUR, 17, hours)
            val document = migrate(capture)
            val checkpoint = assertNotNull(document.runtime.rotation)
            val interval = TimeUnit.HOURS.toMillis(maxOf(1L, hours.toLong()))
            assertEquals(RotationSchedule(20 * HOUR, 20 * HOUR + interval, interval, 1), checkpoint.schedule)
            assertEquals(20 * HOUR, checkpoint.lastSuccessfulStepEpochMillis)
            assertEquals(
                RawValue.Integer(hours),
                document.currents.global.appearance.accent
                    .value("accentRotationIntervalHours"),
            )
            assertEquals(17, checkpoint.sequenceCursorRaw)
        }
    }

    @Test
    fun `fresh disabled Current has an explicit empty checkpoint without choosing a factory or scheduling`() {
        val document = migrate(StudioFixtures.capture(freshSettingsState(), fresh = true))
        assertEquals(RotationRuntimeCheckpoint(RuntimeOwner(StudioTarget.Global, 0), 0, 0), document.runtime.rotation)
        assertEquals(
            RawValue.BooleanValue(false),
            document.currents.global.appearance.accent
                .value("accentRotationEnabled"),
        )
        assertNull(document.currents.global.source)
        assertEquals(4, document.catalog.size)
    }

    private fun capture(
        enabled: Boolean,
        lastSwitch: Long,
        cursor: Int = 3,
        hours: Int = 6,
    ): LegacySnapshot =
        StudioFixtures.capture(
            AyuIslandsState().apply {
                accentRotationEnabled = enabled
                accentRotationLastSwitchMs = lastSwitch
                accentRotationPresetIndex = cursor
                accentRotationIntervalHours = hours
            },
        )

    private fun migrate(capture: LegacySnapshot): StudioDocument =
        assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture)).document

    private companion object {
        const val HOUR = 3_600_000L
    }
}
