package dev.ayuislands.studio

import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.rotation.FullIntervalReason
import dev.ayuislands.rotation.RotationCandidate
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.rotation.RotationOutput
import dev.ayuislands.rotation.RotationResume
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationSchedule
import dev.ayuislands.rotation.RuntimeFault
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioAccentContinuation
import dev.ayuislands.studio.model.StudioAcceptedInput
import dev.ayuislands.studio.model.StudioEffectiveContinuation
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.StudioContinuationXml
import dev.ayuislands.studio.persistence.StudioLegacyFields
import dev.ayuislands.studio.persistence.StudioRotationXml
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.persistence.StudioXmlNode
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudioRuntimeXmlTest {
    @Test
    fun `checkpoint preserves deadlines retry identity and every resume obligation`() {
        val saved = checkpoint()
        val obligations =
            listOf(
                RotationResume.KeepDeadline,
                RotationResume.NoHistory,
                RotationResume.ImmediateEnable,
                RotationResume.ImmediateConfiguration,
                RotationResume.FullInterval(FullIntervalReason.INTERRUPTED_HANDOFF),
            )
        for (resume in obligations) {
            val expected = saved.copy(resume = resume)
            val bytes = StudioXml.write(StudioRotationXml.write(expected))
            assertEquals(expected, StudioRotationXml.read(StudioXml.parse(bytes)))
        }
        val empty = RotationRuntimeCheckpoint(saved.owner, sequenceCursorRaw = -1, successfulStepSerial = 0)
        assertEquals(empty, StudioRotationXml.read(StudioXml.parse(StudioXml.write(StudioRotationXml.write(empty)))))
    }

    @Test
    fun `continuation separates requested values from retained accent inputs`() {
        val fields =
            StudioLegacyFields.capture(
                freshSettingsState(),
                SyntaxIntensityBaseState(),
                AccentMappingsState(),
                origins = emptyMap(),
            )
        val requested = StudioFactoryProfiles.create(fields).first().appearance
        val key = LegacyFieldKey(LegacyStore.MAPPINGS, "projectAccents")
        val inputs =
            StudioList(
                listOf(
                    StudioAcceptedInput(FieldAddress(key, FieldPart.MapEntry("z")), FieldValue.Absent),
                    StudioAcceptedInput(
                        FieldAddress(key, FieldPart.MapEntry("a")),
                        FieldValue.Present(RawValue.Text("#5CCFE6")),
                    ),
                ),
            )
        val resolved = ResolvedAccent(AccentResolver.Source.PROJECT_OVERRIDE, "#5CCFE6")
        val accent = StudioAccentContinuation(null, resolved, inputs)
        for (retained in listOf(null, accent, accent.copy(variant = AyuVariant.MIRAGE))) {
            val expected = StudioEffectiveContinuation(checkpoint().owner, requested, retained)
            val bytes = StudioXml.write(StudioContinuationXml.write(expected))
            assertEquals(expected, StudioContinuationXml.read(StudioXml.parse(bytes)))
        }
    }

    @Test
    fun `runtime codec rejects foreign schema tags and duplicate schedules`() {
        val node = StudioRotationXml.write(checkpoint())
        assertFailsWith<IllegalArgumentException> { StudioRotationXml.read(node.copy(name = "wrong")) }
        val future = node.copy(attributes = node.attributes + ("schema" to "2"))
        assertFailsWith<IllegalArgumentException> { StudioRotationXml.read(future) }
        val duplicate = StudioXmlNode(node.name, node.attributes, node.children + node.child("schedule"))
        assertFailsWith<IllegalArgumentException> { StudioRotationXml.read(duplicate) }
    }

    private fun checkpoint(): RotationRuntimeCheckpoint {
        val owner = RuntimeOwner(StudioTarget.Project(StudioProjectKey($$"/work/$PROJECT$/ā")), 7)
        val retry =
            RotationCandidate(
                UUID.fromString("502662c9-1bfb-4e92-b265-7df23bcb0314"),
                owner,
                RotationIntent(true, "future-mode", 3),
                AccentRotationMode.RANDOM,
                AyuVariant.MIRAGE,
                "#5CCFE6",
                9,
            )
        return RotationRuntimeCheckpoint(
            owner,
            sequenceCursorRaw = 8,
            successfulStepSerial = 4,
            lastSuccessfulStepEpochMillis = 1000,
            output = RotationOutput(owner, AyuVariant.DARK, "#FFCC66"),
            schedule = RotationSchedule(1000, 10_801_000, 10_800_000, 11),
            retryCandidate = retry,
            recoveryFault = RuntimeFault.DurableRecovery("interrupted"),
        )
    }
}
