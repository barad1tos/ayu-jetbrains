package dev.ayuislands.studio.session

import com.intellij.ide.util.PropertiesComponent
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.gitpanel.GitProportionCheckpoint
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioReceiptPolicy

/** One application-property batch follows all project geometry; acknowledged evidence is append-only. */
internal class StudioWorkspaceProperties private constructor(
    private val ownerPrefix: String,
    private val process: StudioProcess,
    private val properties: PropertiesComponent,
    initial: List<PortableReceipt>,
    private val isRecovery: Boolean,
) {
    constructor(references: WorkspaceReferences, properties: PropertiesComponent) : this(
        "workspace/${references.ownerToken}/git",
        references.process,
        properties,
        emptyList(),
        false,
    )

    private val cells = linkedMapOf<String, GitProportionCheckpoint>()
    private val evidence = initial.toMutableList()
    private val observedCounts = mutableMapOf<String, Int>()
    private var isSealed = isRecovery

    val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                evidence.map { receipt ->
                    val cell = cells[propertyKey(receipt)]
                    if (cell == null || StudioReceiptPolicy.isTerminal(receipt)) {
                        receipt
                    } else {
                        receipt.copy(status = status(cell.record.state))
                    }
                },
            )

    fun capture(key: String): GitProportionCheckpoint {
        check(!isSealed && !isRecovery) { "Git property capture has already completed" }
        return cells.getOrPut(key) { GitProportionCheckpoint(key, properties) }
    }

    /** Called once after every manager has registered its captured splitter plans. */
    fun sealCapture() {
        if (isSealed) return
        for ((key, cell) in cells) {
            val record = cell.record
            append(key, setOf(record.before) + record.plans.flatMap { it.forwardOutcomes })
        }
        isSealed = true
    }

    /** Actual observations refine later recovery without changing any earlier receipt payload. */
    fun observe() {
        check(isSealed) { "Git property plans were not captured" }
        for ((key, cell) in cells) {
            val observed = cell.record.observations
            observed
                .drop(observedCounts[key] ?: 0)
                .filter { it.state == AccentWriteState.OWNED }
                .forEach { append(key, setOf(it.raw)) }
            observedCounts[key] = observed.size
        }
    }

    /** The caller durably acknowledges these possible writes before reversing any virtual splitter setter. */
    fun prepareRestore(blockedBy: List<PortableReceipt>) {
        observe()
        visit { key, cell ->
            if (isPending(cell)) {
                checkUnblocked(key, blockedBy)
                val record = cell.prepareRestore()
                val possible =
                    buildSet {
                        addAll(latestOutcomes(key))
                        add(record.before)
                        record.plans.forEach { addAll(it.rollbackOutcomes) }
                    }
                append(key, possible)
            }
        }
    }

    /** Geometry must settle first: its public virtual setters may write these same application properties. */
    fun restore(blockedBy: List<PortableReceipt>) {
        try {
            visit { key, cell ->
                if (isPending(cell)) checkUnblocked(key, blockedBy)
                cell.restore()
            }
        } finally {
            observe()
        }
    }

    fun accept() = visit { _, cell -> cell.commit() }

    private fun append(
        key: String,
        outcomes: Set<String?>,
    ) {
        require(outcomes.isNotEmpty())
        val encoded = RawValue.OrderedList(StudioList(outcomes.map(::raw)))
        val previous = evidence.lastOrNull { propertyKey(it) == key }
        if (previous?.lastWritten?.let(::value) == encoded) return
        evidence +=
            StudioWorkspaceReceipts.create(
                NativeFieldIdentity(
                    "$RESOURCE_PREFIX$key",
                    WorkspaceNativeField.GIT_PROPERTY.name,
                    "$ownerPrefix/${evidence.size}",
                ),
                process,
                raw(checkNotNull(cells[key]).record.before),
                encoded,
            )
    }

    private fun latestOutcomes(key: String): Set<String?> =
        outcomes(evidence.last { propertyKey(it) == key }.lastWritten)

    private fun checkUnblocked(
        key: String,
        newer: List<PortableReceipt>,
    ) {
        check(
            !StudioWorkspaceReceipts.isBlocked("$RESOURCE_PREFIX$key", WorkspaceNativeField.GIT_PROPERTY.name, newer),
        ) {
            "Git property restoration is blocked by a newer write"
        }
    }

    private fun visit(action: (String, GitProportionCheckpoint) -> Unit) {
        var failure: Throwable? = null
        for ((key, cell) in cells.entries.toList().asReversed()) {
            val next = runCatchingPreservingCancellation { action(key, cell) }.exceptionOrNull()
            if (next != null) failure = combineUiFailures(failure, next)
        }
        failure?.let { throw it }
    }

    private fun isPending(cell: GitProportionCheckpoint): Boolean =
        cell.record.state != AccentWriteState.RESTORED && cell.record.state != AccentWriteState.RELINQUISHED

    companion object {
        private const val RESOURCE_PREFIX = "property/"

        /** Restores receipt-qualified raw cells only; native splitters are never rediscovered. */
        fun recover(
            receipts: List<PortableReceipt>,
            properties: PropertiesComponent,
        ): StudioWorkspaceProperties {
            require(receipts.isNotEmpty()) { "Git recovery has no property evidence" }
            require(receipts.none { it.status == RestoreStatus.KEPT_AFTER_RESTART }) {
                "Persistent Git properties cannot use the ToolWindow restart exception"
            }
            val process = StudioNativeEvidence.readProcess(receipts.first().before)
            require(
                receipts.all {
                    it.owner == EffectOwner.WORKSPACE &&
                        it.identity.field == WorkspaceNativeField.GIT_PROPERTY.name &&
                        StudioNativeEvidence.readProcess(it.before) == process &&
                        StudioNativeEvidence.readProcess(it.lastWritten) == process
                },
            ) { "Git property receipt identity differs" }
            val batch =
                StudioWorkspaceProperties(receipts.first().identity.ownerToken, process, properties, receipts, true)
            for ((key, group) in receipts.groupBy(::propertyKey)) {
                require(group.all { it.before == group.first().before }) { "Git property baseline differs" }
                if (group.all(StudioReceiptPolicy::isTerminal)) continue
                batch.cells[key] =
                    GitProportionCheckpoint.recover(
                        key,
                        text(value(group.first().before)),
                        outcomes(group.last().lastWritten),
                        properties,
                    )
            }
            return batch
        }

        private fun propertyKey(receipt: PortableReceipt): String {
            val resource = receipt.identity.resource
            require(resource.startsWith(RESOURCE_PREFIX) && resource.length > RESOURCE_PREFIX.length)
            return resource.removePrefix(RESOURCE_PREFIX)
        }

        private fun value(native: NativeValue): RawValue =
            StudioNativeEvidence.fields(requireNotNull(native as? NativeValue.Present).value).getValue("value")

        private fun outcomes(native: NativeValue): Set<String?> {
            val values = requireNotNull(value(native) as? RawValue.OrderedList).values
            require(values.isNotEmpty()) { "Git property outcomes are empty" }
            return values.map(::text).toSet()
        }

        private fun raw(value: String?): RawValue = value?.let(RawValue::Text) ?: RawValue.Null

        private fun text(value: RawValue): String? =
            if (value == RawValue.Null) null else requireNotNull(value as? RawValue.Text).value

        private fun status(state: AccentWriteState): RestoreStatus =
            when (state) {
                AccentWriteState.PREPARED -> RestoreStatus.PENDING
                AccentWriteState.OWNED -> RestoreStatus.OWNED
                AccentWriteState.RELINQUISHED -> RestoreStatus.FOREIGN
                AccentWriteState.RESTORED -> RestoreStatus.RESTORED
            }
    }
}
