package dev.ayuislands.studio.session

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.RegistryValue
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.projectview.RootPathLease
import dev.ayuislands.projectview.RootPathPolicy
import dev.ayuislands.projectview.RootPathValue
import dev.ayuislands.projectview.RootPathWrite
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioProcessRelation
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioReceiptPolicy

/** One application-wide Registry batch retains its existing lease and detached runtime metadata. */
internal class StudioWorkspaceRoot private constructor(
    initial: List<PortableReceipt>,
    private val native: RootPathLease.RootPathHold?,
    private val projection: RootProjection?,
) {
    private val evidence = initial.toMutableList()
    private val original = initial.first()
    private var phase = if (projection == null) Phase.RECOVERING else Phase.PREPARED
    private var isRestorePrepared = false

    val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                evidence.map { receipt ->
                    if (StudioReceiptPolicy.isTerminal(receipt)) {
                        receipt
                    } else {
                        receipt.copy(status = status())
                    }
                },
            )

    val runtimeChanges: StudioList<RuntimeFieldChange>
        get() =
            if (phase == Phase.APPLIED || phase == Phase.ACCEPTING || phase == Phase.ACCEPTED) {
                projection?.changes() ?: StudioList(emptyList())
            } else {
                StudioList(emptyList())
            }

    fun apply() {
        check(phase == Phase.PREPARED) { "Root Registry application cannot be replayed" }
        val checkpoint = checkNotNull(native as? RootPathLease.RootPathCheckpoint)
        phase = Phase.APPLYING
        try {
            check(checkpoint.apply()) { "Prepared root Registry value changed before application" }
            phase = Phase.APPLIED
        } finally {
            observe()
        }
    }

    /** Adds the possible rollback value before the caller acknowledges the next native setter. */
    fun prepareRestore(blockedBy: List<PortableReceipt>) {
        check(phase != Phase.ACCEPTING && phase != Phase.ACCEPTED) { "Accepted root Registry value cannot be restored" }
        if (native?.hasPending != true) return
        checkUnblocked(blockedBy)
        observe()
        append(decode(original.before))
        isRestorePrepared = true
    }

    fun restore(blockedBy: List<PortableReceipt>) {
        check(phase != Phase.ACCEPTING && phase != Phase.ACCEPTED) { "Accepted root Registry value cannot be restored" }
        val checkpoint = native ?: return
        if (phase == Phase.RESTORED) return
        if (checkpoint.hasPending) {
            checkUnblocked(blockedBy)
            check(isRestorePrepared) { "Root Registry rollback must be acknowledged before restoration" }
        }
        phase = Phase.RESTORING
        isRestorePrepared = false
        try {
            checkpoint.restore()
            check(!checkpoint.hasPending) { "Root Registry recovery remains incomplete" }
            checkpoint.releaseResources()
            phase = Phase.RESTORED
        } finally {
            observe()
        }
    }

    fun accept() {
        if (phase == Phase.ACCEPTED) return
        check(phase == Phase.APPLIED || phase == Phase.ACCEPTING || phase == Phase.RECOVERING) {
            "Root Registry application has not completed"
        }
        phase = Phase.ACCEPTING
        native?.commit()
        native?.releaseResources()
        phase = Phase.ACCEPTED
    }

    private fun checkUnblocked(newer: List<PortableReceipt>) {
        check(!StudioWorkspaceReceipts.isBlocked(original.identity.resource, original.identity.field, newer)) {
            "Root Registry restoration is blocked by a newer write"
        }
    }

    private fun observe() {
        val current =
            when (val checkpoint = native) {
                is RootPathLease.RootPathCheckpoint -> checkpoint.lastWritten
                is RootPathLease.RootPathRecovery -> checkpoint.lastWritten
                null -> return
                else -> error("Unknown root Registry checkpoint")
            }
        append(current)
    }

    private fun append(value: RootPathValue) {
        val last = evidence.last().lastWritten
        if (decodeWrite(last) == null && decode(last) == value) return
        evidence +=
            StudioWorkspaceReceipts.create(
                original.identity.copy(ownerToken = "${original.identity.ownerToken}/step/${evidence.size}"),
                StudioNativeEvidence.readProcess(original.before),
                encode(decode(original.before)),
                encode(value),
            )
    }

    private fun status(): RestoreStatus =
        when (native?.state) {
            null -> RestoreStatus.UNAVAILABLE
            AccentWriteState.PREPARED -> RestoreStatus.PENDING
            AccentWriteState.OWNED -> if (phase == Phase.APPLYING) RestoreStatus.PENDING else RestoreStatus.OWNED
            AccentWriteState.RELINQUISHED -> RestoreStatus.FOREIGN
            AccentWriteState.RESTORED -> RestoreStatus.RESTORED
        }

    companion object {
        private const val RESOURCE_PREFIX = "registry/"

        fun prepare(
            input: StudioEffectInput,
            references: WorkspaceReferences,
            host: StudioWorkspaceHost,
            projects: Set<Project>,
            hidePath: Boolean,
        ): StudioWorkspaceRoot? {
            val lease = host.rootLease() ?: return null
            val registry = host.rootRegistry()
            val projection = RootProjection(input)
            val checkpoint = lease.prepare(projects, registry, hidePath, projection.state)
            val receipt =
                StudioWorkspaceReceipts.create(
                    NativeFieldIdentity(
                        "$RESOURCE_PREFIX${registry.key}",
                        WorkspaceNativeField.ROOT_PATH.name,
                        "workspace/${references.ownerToken}/root/0",
                    ),
                    references.process,
                    encode(checkpoint.before),
                    checkpoint.planned?.let(::encode) ?: encode(checkpoint.before),
                )
            return StudioWorkspaceRoot(listOf(receipt), checkpoint, projection)
        }

        /** A new process rebinds only the exact recorded Registry cell, never project membership. */
        fun recover(
            receipts: List<PortableReceipt>,
            facts: StudioNativeFacts,
            lease: RootPathLease?,
            registry: RegistryValue?,
        ): StudioWorkspaceRoot {
            val process = validateReceipts(receipts)
            val first = receipts.first()
            if (lease == null || registry == null) return StudioWorkspaceRoot(receipts, null, null)
            val matchesRegistry = first.identity.resource == "$RESOURCE_PREFIX${registry.key}"
            val hasPending = receipts.any { !StudioReceiptPolicy.isTerminal(it) }
            val isRestarted =
                StudioProcessPolicy.compare(process, facts.currentProcess) == StudioProcessRelation.DIFFERENT
            val checkpoint =
                if (matchesRegistry && hasPending && isRestarted) {
                    val before = decode(first.before)
                    val current = RootPathValue(registry.asString(), registry.isChangedFromDefault())
                    val observed = receipts.filter { decodeWrite(it.lastWritten) == null }
                    val outcomes = observed.ifEmpty { receipts }
                    val isOwned = outcomes.any { matches(it.lastWritten, current) }
                    lease.recoverReceipt(registry, before, if (isOwned) current else before)
                } else {
                    null
                }
            return StudioWorkspaceRoot(receipts, checkpoint, null)
        }

        private fun validateReceipts(receipts: List<PortableReceipt>): StudioProcess {
            require(receipts.isNotEmpty()) { "Root Registry recovery has no evidence" }
            val first = receipts.first()
            val process = StudioNativeEvidence.readProcess(first.before)
            require(
                receipts.all {
                    it.owner == EffectOwner.WORKSPACE &&
                        it.identity.field == WorkspaceNativeField.ROOT_PATH.name &&
                        it.identity.resource == first.identity.resource &&
                        it.before == first.before &&
                        it.status != RestoreStatus.KEPT_AFTER_RESTART &&
                        StudioNativeEvidence.readProcess(it.before) == process &&
                        StudioNativeEvidence.readProcess(it.lastWritten) == process
                },
            ) { "Root Registry receipt identity differs" }
            return process
        }

        private fun encode(value: RootPathValue): RawValue =
            StudioNativeEvidence.record(
                "raw" to RawValue.Text(value.raw),
                "changed" to RawValue.BooleanValue(value.wasChanged),
            )

        private fun encode(write: RootPathWrite): RawValue =
            when (write) {
                is RootPathWrite.Set -> StudioNativeEvidence.record("set" to RawValue.Text(write.raw))
                RootPathWrite.Reset -> StudioNativeEvidence.record("reset" to RawValue.BooleanValue(true))
            }

        private fun matches(
            value: NativeValue,
            current: RootPathValue,
        ): Boolean = decodeWrite(value)?.let { RootPathPolicy.matches(it, current) } ?: (decode(value) == current)

        private fun decodeWrite(value: NativeValue): RootPathWrite? {
            val fields = readFields(value)
            return when (fields.keys) {
                setOf("raw", "changed") -> null
                setOf("set") -> RootPathWrite.Set(requireNotNull(fields.getValue("set") as? RawValue.Text).value)
                setOf("reset") -> {
                    require(fields.getValue("reset") == RawValue.BooleanValue(true))
                    RootPathWrite.Reset
                }
                else -> error("Root Registry receipt has an invalid write intent")
            }
        }

        private fun readFields(value: NativeValue): Map<String, RawValue> {
            val envelope = StudioNativeEvidence.fields(requireNotNull(value as? NativeValue.Present).value)
            return StudioNativeEvidence.fields(envelope.getValue("value"))
        }

        private fun decode(value: NativeValue): RootPathValue {
            val fields = readFields(value)
            require(fields.keys == setOf("raw", "changed"))
            return RootPathValue(
                requireNotNull(fields.getValue("raw") as? RawValue.Text).value,
                requireNotNull(fields.getValue("changed") as? RawValue.BooleanValue).value,
            )
        }
    }

    private enum class Phase { PREPARED, APPLYING, APPLIED, RECOVERING, RESTORING, RESTORED, ACCEPTING, ACCEPTED }
}

/** Only these existing runtime fields are emitted; the parent publishes them after whole-operation acceptance. */
private class RootProjection(
    input: StudioEffectInput,
) {
    private val fields = input.candidate.runtime.legacy.records
    val state =
        AyuIslandsState().apply {
            hasRootPathLease = boolean("hasRootPathLease")
            wasRootPathShown = boolean("wasRootPathShown")
            wasRootPathChanged = boolean("wasRootPathChanged")
        }

    fun changes(): StudioList<RuntimeFieldChange> {
        val output =
            mapOf(
                "hasRootPathLease" to RawValue.BooleanValue(state.hasRootPathLease),
                "wasRootPathShown" to RawValue.BooleanValue(state.wasRootPathShown),
                "wasRootPathChanged" to RawValue.BooleanValue(state.wasRootPathChanged),
            )
        return StudioList(
            fields.mapNotNull { field ->
                val after = output[field.key.name] ?: return@mapNotNull null
                if (after == field.value) {
                    null
                } else {
                    RuntimeFieldChange(
                        FieldAddress(field.key),
                        FieldValue.Present(field.value),
                        FieldValue.Present(after),
                    )
                }
            },
        )
    }

    private fun boolean(name: String): Boolean =
        requireNotNull(fields.single { it.key.name == name }.value as? RawValue.BooleanValue).value
}
