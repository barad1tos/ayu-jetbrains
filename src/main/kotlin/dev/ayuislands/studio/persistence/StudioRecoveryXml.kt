package dev.ayuislands.studio.persistence

import dev.ayuislands.rotation.RotationHandoffKind
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.LimitationReason
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.StudioThemeChange
import java.util.UUID

internal object StudioRecoveryXml {
    private const val BLOCKS_EFFECT = "blocks-effect"
    private const val ROTATION_STEP = "rotation-step"
    private const val RUNTIME_PURPOSE = "runtime-purpose"
    private const val METADATA_PURPOSE = "metadata-purpose"
    private const val EXPLICIT_THEME = "explicit-theme"

    fun write(plan: RecoveryPlan): StudioXmlNode =
        StudioXmlNode(
            "recovery",
            children =
                buildList {
                    add(StudioAppearanceXml.writeTarget(plan.target))
                    plan.owners.mapTo(this) { StudioXmlNode("owner", mapOf("kind" to it.name)) }
                    plan.limitations.mapTo(this, ::writeLimitation)
                    writePurpose(plan.purpose)?.let(::add)
                    plan.rotationStep?.let { add(StudioRotationXml.writeCandidate(it, ROTATION_STEP)) }
                },
        )

    fun read(node: StudioXmlNode): RecoveryPlan {
        require(node.name == "recovery")
        node.requireShape(
            childNames = setOf("target", "owner", "limitation", ROTATION_STEP, RUNTIME_PURPOSE, METADATA_PURPOSE),
        )
        val owners =
            node.children.filter { it.name == "owner" }.map {
                EffectOwner.valueOf(it.requireShape(setOf("kind")).attribute("kind"))
            }
        val limitations = node.children.filter { it.name == "limitation" }.map(::readLimitation)
        require(owners == owners.distinct().sortedBy(EffectOwner::ordinal)) { "Invalid recovery owner order" }
        require(limitations.distinct().size == limitations.size) { "Duplicate effect limitation" }
        require(limitations.none { it.blocksEffect && it.owner in owners }) { "Recovery owner is blocked" }
        val target = StudioAppearanceXml.readTarget(node.child("target"))
        require(target !is StudioTarget.Project || target.key.value.isNotBlank()) {
            "Missing recovery project identity"
        }
        val step = node.optionalChild(ROTATION_STEP)?.let(StudioRotationXml::readCandidate)
        require(step == null || step.owner.target == target) { "Rotation candidate targets another context" }
        val plan =
            RecoveryPlan(
                target,
                StudioList(owners),
                StudioList(limitations),
                step,
                readPurpose(node),
            )
        when (plan.purpose) {
            RecoveryPurpose.Metadata ->
                require(owners.isEmpty() && limitations.isEmpty() && plan.rotationStep == null) {
                    "Metadata cannot own native effects"
                }
            is RecoveryPurpose.RotationStep -> {
                requireNotNull(plan.rotationStep) { "Missing automatic rotation candidate" }
            }
            else -> Unit
        }
        return plan
    }

    private fun writeLimitation(limitation: EffectLimitation): StudioXmlNode {
        val attributes = linkedMapOf("owner" to limitation.owner.name, "reason" to limitation.reason.name)
        if (!limitation.blocksEffect) attributes[BLOCKS_EFFECT] = "false"
        return StudioXmlNode("limitation", attributes)
    }

    private fun readLimitation(node: StudioXmlNode): EffectLimitation {
        val attributes = setOf("owner", "reason") + listOf(BLOCKS_EFFECT).filter(node.attributes::containsKey)
        node.requireShape(attributes)
        return EffectLimitation(
            EffectOwner.valueOf(node.attribute("owner")),
            LimitationReason.valueOf(node.attribute("reason")),
            node.attributes[BLOCKS_EFFECT]?.toBooleanStrict() ?: true,
        )
    }

    private fun writePurpose(purpose: RecoveryPurpose): StudioXmlNode? =
        when (purpose) {
            RecoveryPurpose.UserAcceptance -> null
            RecoveryPurpose.Metadata -> StudioXmlNode(METADATA_PURPOSE)
            is RecoveryPurpose.RotationStep -> writeRuntime("step", purpose.operationId)
            is RecoveryPurpose.RotationSchedule -> writeRuntime("schedule", purpose.operationId)
            is RecoveryPurpose.ContextHandoff ->
                writeRuntime(
                    when (purpose.kind) {
                        RotationHandoffKind.FOCUS -> "focus"
                        RotationHandoffKind.CONTINUITY -> "continuity"
                    },
                    purpose.operationId,
                )
            is RecoveryPurpose.NativeChange ->
                writeRuntime("native", purpose.operationId, writeNativeChange(purpose.change))
        }

    private fun writeRuntime(
        kind: String,
        operation: UUID,
        attributes: Map<String, String> = emptyMap(),
    ): StudioXmlNode =
        StudioXmlNode(RUNTIME_PURPOSE, mapOf("kind" to kind, "operation" to operation.toString()) + attributes)

    private fun readPurpose(node: StudioXmlNode): RecoveryPurpose {
        val metadata = node.optionalChild(METADATA_PURPOSE)
        val runtime = node.optionalChild(RUNTIME_PURPOSE)
        if (metadata != null) {
            metadata.requireShape()
            require(runtime == null) { "Metadata cannot own an automatic runtime operation" }
            return RecoveryPurpose.Metadata
        }
        if (runtime == null) return RecoveryPurpose.UserAcceptance
        val operation = UUID.fromString(runtime.attribute("operation"))
        val kind = runtime.attribute("kind")
        if (kind == "native") return RecoveryPurpose.NativeChange(operation, readNativeChange(runtime))
        runtime.requireShape(setOf("kind", "operation"))
        return when (kind) {
            "step" -> RecoveryPurpose.RotationStep(operation)
            "schedule" -> RecoveryPurpose.RotationSchedule(operation)
            "focus" -> RecoveryPurpose.ContextHandoff(operation, RotationHandoffKind.FOCUS)
            "continuity" -> RecoveryPurpose.ContextHandoff(operation, RotationHandoffKind.CONTINUITY)
            else -> throw IllegalArgumentException("Invalid runtime purpose")
        }
    }

    private fun writeNativeChange(change: StudioNativeChange): Map<String, String> =
        when (change) {
            StudioNativeChange.Appearance -> mapOf("change" to "appearance")
            StudioNativeChange.AccentContext -> mapOf("change" to "accent")
            is StudioNativeChange.Theme ->
                when (val selection = change.selection) {
                    StudioThemeChange.FollowSystem -> mapOf("change" to "follow-system")
                    is StudioThemeChange.Explicit ->
                        mapOf("change" to EXPLICIT_THEME, "theme" to StudioXml.encodeText(selection.name))
                }
        }

    private fun readNativeChange(node: StudioXmlNode): StudioNativeChange {
        val kind = node.attribute("change")
        val common = setOf("kind", "operation", "change")
        node.requireShape(if (kind == EXPLICIT_THEME) common + "theme" else common)
        return when (kind) {
            "appearance" -> StudioNativeChange.Appearance
            "accent" -> StudioNativeChange.AccentContext
            "follow-system" -> StudioNativeChange.Theme(StudioThemeChange.FollowSystem)
            EXPLICIT_THEME ->
                StudioNativeChange.Theme(StudioThemeChange.Explicit(StudioXml.decodeText(node.attribute("theme"))))
            else -> throw IllegalArgumentException("Invalid native change")
        }
    }
}
