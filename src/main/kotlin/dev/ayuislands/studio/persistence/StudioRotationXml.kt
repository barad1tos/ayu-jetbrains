package dev.ayuislands.studio.persistence

import dev.ayuislands.accent.AyuVariant
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
import java.util.UUID

/** The aggregate's rotation checkpoint; source evidence remains in its existing legacy envelope. */
internal object StudioRotationXml {
    private const val STEP_SERIAL = "step-serial"
    private const val LAST_STEP = "last-step"
    private const val SCHEDULED_AT = "scheduled-at"
    private const val NEXT_CURSOR = "next-cursor"
    private const val INTERVAL_HOURS = "interval-hours"

    fun write(checkpoint: RotationRuntimeCheckpoint): StudioXmlNode =
        StudioXmlNode(
            "rotation",
            mapOf(
                "schema" to checkpoint.schemaVersion.toString(),
                "cursor" to checkpoint.sequenceCursorRaw.toString(),
                STEP_SERIAL to checkpoint.successfulStepSerial.toString(),
            ) + (checkpoint.lastSuccessfulStepEpochMillis?.let { mapOf(LAST_STEP to it.toString()) } ?: emptyMap()),
            listOf(writeOwner(checkpoint.owner), writeResume(checkpoint.resume)) +
                listOfNotNull(
                    checkpoint.output?.let(::writeOutput),
                    checkpoint.schedule?.let(::writeSchedule),
                    checkpoint.retryCandidate?.let { writeCandidate(it, "retry") },
                    checkpoint.recoveryFault?.let {
                        StudioXmlNode(
                            "fault",
                            mapOf("code" to StudioXml.encodeText(it.code)),
                        )
                    },
                ),
        )

    fun read(node: StudioXmlNode): RotationRuntimeCheckpoint {
        node.requireShape(
            setOf("schema", "cursor", STEP_SERIAL) + node.attributes.keys.filter { it == LAST_STEP },
            setOf("owner", "resume", "output", "schedule", "retry", "fault"),
        )
        require(node.attribute("schema").toInt() == 1) { "Unsupported rotation checkpoint schema" }
        return RotationRuntimeCheckpoint(
            owner = readOwner(node.child("owner")),
            sequenceCursorRaw = node.attribute("cursor").toInt(),
            successfulStepSerial = node.attribute(STEP_SERIAL).toLong(),
            lastSuccessfulStepEpochMillis = node.attributes[LAST_STEP]?.toLong(),
            output = node.optionalChild("output")?.let(::readOutput),
            schedule = node.optionalChild("schedule")?.let(::readSchedule),
            resume = readResume(node.child("resume")),
            retryCandidate = node.optionalChild("retry")?.let(::readCandidate),
            recoveryFault =
                node.optionalChild("fault")?.let {
                    RuntimeFault.DurableRecovery(StudioXml.decodeText(it.requireShape(setOf("code")).attribute("code")))
                },
        )
    }

    fun writeOwner(owner: RuntimeOwner): StudioXmlNode =
        StudioXmlNode(
            "owner",
            mapOf("revision" to owner.appearanceRevision.toString()),
            listOf(StudioAppearanceXml.writeTarget(owner.target)),
        )

    fun readOwner(node: StudioXmlNode): RuntimeOwner {
        node.requireShape(setOf("revision"), setOf("target"))
        return RuntimeOwner(StudioAppearanceXml.readTarget(node.child("target")), node.attribute("revision").toLong())
    }

    private fun writeOutput(output: RotationOutput): StudioXmlNode =
        StudioXmlNode(
            "output",
            mapOf("variant" to output.variant.name, "hex" to StudioXml.encodeText(output.hex)),
            listOf(writeOwner(output.owner)),
        )

    private fun readOutput(node: StudioXmlNode): RotationOutput {
        node.requireShape(setOf("variant", "hex"), setOf("owner"))
        return RotationOutput(
            readOwner(node.child("owner")),
            AyuVariant.valueOf(node.attribute("variant")),
            StudioXml.decodeText(node.attribute("hex")),
        )
    }

    private fun writeSchedule(schedule: RotationSchedule): StudioXmlNode =
        StudioXmlNode(
            "schedule",
            mapOf(
                SCHEDULED_AT to schedule.scheduledAtEpochMillis.toString(),
                "deadline" to schedule.deadlineEpochMillis.toString(),
                "interval" to schedule.intervalMillisAtScheduling.toString(),
                "serial" to schedule.scheduleSerial.toString(),
            ),
        )

    private fun readSchedule(node: StudioXmlNode): RotationSchedule {
        node.requireShape(setOf(SCHEDULED_AT, "deadline", "interval", "serial"))
        return RotationSchedule(
            node.attribute(SCHEDULED_AT).toLong(),
            node.attribute("deadline").toLong(),
            node.attribute("interval").toLong(),
            node.attribute("serial").toLong(),
        )
    }

    private fun writeResume(resume: RotationResume): StudioXmlNode =
        StudioXmlNode(
            "resume",
            when (resume) {
                RotationResume.KeepDeadline -> mapOf("kind" to "keep-deadline")
                RotationResume.NoHistory -> mapOf("kind" to "no-history")
                RotationResume.ImmediateEnable -> mapOf("kind" to "immediate-enable")
                RotationResume.ImmediateConfiguration -> mapOf("kind" to "immediate-configuration")
                is RotationResume.FullInterval -> mapOf("kind" to "full-interval", "reason" to resume.reason.name)
            },
        )

    private fun readResume(node: StudioXmlNode): RotationResume =
        when (node.attribute("kind")) {
            "keep-deadline" -> {
                node.requireShape(setOf("kind"))
                RotationResume.KeepDeadline
            }

            "no-history" -> {
                node.requireShape(setOf("kind"))
                RotationResume.NoHistory
            }

            "immediate-enable" -> {
                node.requireShape(setOf("kind"))
                RotationResume.ImmediateEnable
            }

            "immediate-configuration" -> {
                node.requireShape(setOf("kind"))
                RotationResume.ImmediateConfiguration
            }

            "full-interval" -> {
                node.requireShape(setOf("kind", "reason"))
                RotationResume.FullInterval(FullIntervalReason.valueOf(node.attribute("reason")))
            }

            else -> {
                throw IllegalArgumentException("Unknown rotation resume obligation")
            }
        }

    fun writeCandidate(
        candidate: RotationCandidate,
        nodeName: String,
    ): StudioXmlNode =
        StudioXmlNode(
            nodeName,
            mapOf(
                "operation" to candidate.operationId.toString(),
                "mode" to candidate.mode.name,
                "variant" to candidate.variant.name,
                "hex" to StudioXml.encodeText(candidate.hex),
                NEXT_CURSOR to candidate.nextCursor.toString(),
            ),
            listOf(writeOwner(candidate.owner), writeIntent(candidate.intent)),
        )

    fun readCandidate(node: StudioXmlNode): RotationCandidate {
        node.requireShape(setOf("operation", "mode", "variant", "hex", NEXT_CURSOR), setOf("owner", "intent"))
        return RotationCandidate(
            UUID.fromString(node.attribute("operation")),
            readOwner(node.child("owner")),
            readIntent(node.child("intent")),
            AccentRotationMode.valueOf(node.attribute("mode")),
            AyuVariant.valueOf(node.attribute("variant")),
            StudioXml.decodeText(node.attribute("hex")),
            node.attribute(NEXT_CURSOR).toInt(),
        )
    }

    private fun writeIntent(intent: RotationIntent): StudioXmlNode =
        StudioXmlNode(
            "intent",
            mapOf("enabled" to intent.enabled.toString(), INTERVAL_HOURS to intent.intervalHoursRaw.toString()) +
                (intent.modeRaw?.let { mapOf("mode" to StudioXml.encodeText(it)) } ?: emptyMap()),
        )

    private fun readIntent(node: StudioXmlNode): RotationIntent {
        node.requireShape(setOf("enabled", INTERVAL_HOURS) + node.attributes.keys.filter { it == "mode" })
        return RotationIntent(
            enabled = node.attribute("enabled").toBooleanStrict(),
            modeRaw = node.attributes["mode"]?.let(StudioXml::decodeText),
            intervalHoursRaw = node.attribute(INTERVAL_HOURS).toInt(),
        )
    }
}
