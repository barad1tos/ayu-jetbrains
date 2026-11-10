package dev.ayuislands.theme

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.JDOMUtil
import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.AccentApplyScope
import dev.ayuislands.accent.AccentCheckpoint
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.accent.restoreAccentCheckpoints
import org.jdom.Element
import java.awt.Color
import java.util.Base64
import java.util.Collections
import java.util.EnumMap
import java.util.IdentityHashMap
import java.util.Properties
import javax.swing.SwingUtilities
import kotlin.coroutines.cancellation.CancellationException

internal sealed interface EditorSchemeOwner {
    data class Element(
        val id: AccentElementId,
    ) : EditorSchemeOwner

    data object AlwaysOn : EditorSchemeOwner

    data object Syntax : EditorSchemeOwner

    data object Vcs : EditorSchemeOwner
}

internal enum class OverrideWriteResult {
    APPLIED,
    RELINQUISHED,
    SKIPPED,
}

internal object EditorSchemeOverrides {
    private val log = logger<EditorSchemeOverrides>()
    private val lock = Any()
    private val states = IdentityHashMap<EditorColorsScheme, MutableMap<SchemeEntry, OverrideState>>()
    private val elementEnabled = EnumMap<AccentElementId, Boolean>(AccentElementId::class.java)
    val checkpoints = SchemeCheckpoints()

    internal data class SchemeCheckpoint(
        val scheme: EditorColorsScheme,
        val entries: Map<SchemeEntry, CheckpointEntry>,
    )

    internal data class CheckpointEntry(
        val directValue: SchemeValue,
        val overrideState: OverrideState?,
        val metadataValue: String?,
    )

    internal class PreviewCheckpoint(
        val original: SchemeCheckpoint,
        val expected: MutableMap<SchemeEntry, CheckpointEntry>,
        val pending: MutableSet<SchemeEntry>,
    )

    internal data class PreviewRestoreAttempt(
        val completed: Set<PreviewCheckpoint>,
        val failures: List<RuntimeException>,
        val cancellation: RuntimeException?,
        val changed: Boolean,
    )

    fun writeColor(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        key: ColorKey,
        value: Color?,
    ) {
        write(scheme, owner, SchemeEntry.ColorEntry(key), SchemeValue.ColorValue(value))
    }

    fun writeAttributes(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        key: TextAttributesKey,
        value: TextAttributes?,
    ): OverrideWriteResult =
        write(
            scheme,
            owner,
            SchemeEntry.AttributesEntry(key),
            SchemeValue.AttributesValue(value?.clone()),
        )

    fun restore(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
    ) {
        synchronized(lock) {
            checkpoints.prepare()
            hydrate(scheme)
            val schemeStates: Map<SchemeEntry, OverrideState> = states[scheme] ?: return

            var firstFailure: Throwable? = null
            for ((entry, state) in schemeStates.toList()) {
                val failure = restoreEntry(scheme, owner, entry, state) ?: continue
                firstFailure = combineUiFailures(firstFailure, failure)
            }
            if (schemeStates.isEmpty()) states.remove(scheme)
            firstFailure?.let { throw it }
        }
    }

    fun observeElementEnabled(
        id: AccentElementId,
        isEnabled: Boolean,
        schemes: () -> Iterable<EditorColorsScheme>,
    ) {
        synchronized(lock) {
            checkpoints.observeEnabled(id, isEnabled)
            val wasEnabled = elementEnabled.put(id, isEnabled)
            if (wasEnabled == false && isEnabled) {
                rearm(EditorSchemeOwner.Element(id), schemes())
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            states.clear()
            elementEnabled.clear()
            checkpoints.reset()
        }
    }

    internal class SchemeCheckpoints {
        private val pending = mutableListOf<AccentCheckpoint>()
        private var refresh: RefreshCheckpoint? = null
        private val receipts = mutableListOf<EntryCheckpoint>()
        private var boundScope: AccentApplyScope? = null
        private var boundSegment: AccentApplyScope.Segment? = null
        private var target: EditorColorsScheme? = null

        /** Detached capture never retries recovery, enrolls a scope, hydrates claims, or publishes a refresh. */
        fun prepareWrite(
            scheme: EditorColorsScheme,
            entry: SchemeEntry,
            desired: CheckpointEntry,
            capturedTarget: EditorColorsScheme?,
        ): AccentWriteCheckpoint = prepareEntry(scheme, entry, EntryRequest(desired), capturedTarget)

        fun prepareBatch(
            selection: SchemeTarget,
            desired: Map<SchemeEntry, CheckpointEntry>,
        ): SchemeWriteBatch =
            synchronized(lock) {
                check(SwingUtilities.isEventDispatchThread()) { "Editor scheme preparation must run on EDT" }
                check(selection.isSelected()) { "Editor scheme selection changed" }
                NativeBatch(
                    selection,
                    desired.mapValues { (entry, value) ->
                        prepareEntry(selection.scheme, entry, EntryRequest(value), null, selection)
                    },
                )
            }

        /** Reuses native rollback only after the restart adapter has proved the persisted owner and payload. */
        fun prepareRecovery(
            selection: SchemeTarget,
            records: List<SchemeEntryRecord>,
        ): SchemeWriteBatch? =
            synchronized(lock) {
                check(SwingUtilities.isEventDispatchThread()) { "Editor scheme recovery must run on EDT" }
                check(selection.isSelected()) { "Editor scheme selection changed" }
                val entries = linkedMapOf<SchemeEntry, EntryCheckpoint>()
                for (record in records) {
                    val admission = captureAdmission(selection.scheme, record.entry)
                    if (admission.owner != null || !SchemeAdmission.matches(record.planned, admission)) {
                        return@synchronized null
                    }
                    check(record.restored === record.before)
                    for (destination in listOfNotNull(record.before, record.released)) {
                        check(
                            destination.owner == null &&
                                destination.parent === admission.parent &&
                                destination.metadataTable === admission.metadataTable,
                        )
                    }
                    val original = snapshotEntry(selection.scheme, record.entry)
                    val native =
                        NativeEntry(selection.scheme, record.entry, original, original, admission, true, record)
                    native.resumeRecovery()
                    entries[record.entry] =
                        EntryCheckpoint(
                            selection.scheme,
                            record.entry,
                            original,
                            null,
                            null,
                            native = native,
                            selection = selection,
                        )
                }
                NativeBatch(selection, entries, recovering = true)
            }

        /** Plans this owner's complete next write without hydrating persisted claims or changing native state. */
        fun prepareOverrides(
            selection: SchemeTarget,
            owner: EditorSchemeOwner,
            requested: Map<SchemeEntry, SchemeValue>,
            managed: Set<SchemeEntry>,
            rearm: Boolean = false,
        ): SchemeOverridePreparation =
            synchronized(lock) {
                check(SwingUtilities.isEventDispatchThread()) { "Editor scheme preparation must run on EDT" }
                check(pending.none { it.hasPending }) { "Editor scheme recovery must settle before preparation" }
                check(selection.isSelected()) { "Editor scheme selection changed" }
                require(requested.keys.containsAll(managed)) { "Managed editor keys require requested values" }
                val live = states[selection.scheme].orEmpty().toMap()
                val known = (live.keys + (requested.keys - managed)).mapTo(hashSetOf()) { it.metadataKey }
                check(
                    selection.scheme.metaProperties.entries.none { (key, value) ->
                        key is String &&
                            key.startsWith(METADATA_PREFIX) &&
                            key !in known &&
                            value is String &&
                            OverrideCodec.namesOwner(value, owner)
                    },
                ) { "Persisted editor ownership has no live authority" }
                val entries = requested.keys + live.filterValues { it.owner == owner }.keys
                val prepared =
                    entries.associateWith { entry ->
                        val requestedValue = requested[entry]
                        val isManaged = entry in managed
                        val scheme = selection.scheme
                        val admission = captureAdmission(scheme, entry)
                        check(admission.cell !== SchemeCellFacts.Unavailable) { "Editor local state is unavailable" }
                        check(
                            !admission.hasMetadata || admission.metadataValue is String,
                        ) { "Editor metadata is not a raw string" }
                        val current = admission.owner as OverrideState?
                        check(!isManaged || current != null || !admission.hasMetadata) {
                            "Editor metadata must be qualified before acquiring ownership"
                        }
                        val baseline = read(scheme, entry, isBaseline = true)
                        val comparison =
                            if (current is OverrideState.Owned && current.owner == owner) {
                                compareOwned(scheme, entry, current.lastWritten)
                            } else {
                                null
                            }
                        val facts =
                            SchemeOverrideFacts(
                                owner,
                                current.takeUnless { rearm && it is OverrideState.Relinquished && it.owner == owner },
                                requestedValue,
                                isManaged,
                                baseline,
                                comparison,
                            )
                        prepareOverride(selection, entry, admission, facts)
                    }
                SchemeOverridePreparation(
                    NativeBatch(selection, prepared.mapValues { it.value.first }),
                    Collections.unmodifiableSet(
                        prepared.filterValues { it.second == OverrideWriteResult.RELINQUISHED }.keys.toSet(),
                    ),
                )
            }

        /** Plans the existing explicit enable transition without publishing its Boolean or rearming live claims. */
        fun prepareEnabled(
            id: AccentElementId,
            enabled: Boolean,
        ): EnabledCheckpoint =
            synchronized(lock) {
                check(SwingUtilities.isEventDispatchThread()) { "Element preparation must run on EDT" }
                check(pending.none { it.hasPending }) { "Editor scheme recovery must settle before preparation" }
                EnabledCheckpoint(id, elementEnabled[id], enabled, AccentWriteState.PREPARED)
            }

        /** Null requests observe/rearm only, as on external themes; empty requests retire this owner's overrides. */
        fun prepareElementOverrides(
            selection: SchemeTarget,
            enabled: EnabledCheckpoint,
            requested: Map<SchemeEntry, SchemeValue>?,
        ): SchemeWriteBatch =
            synchronized(lock) {
                check(SwingUtilities.isEventDispatchThread()) { "Element preparation must run on EDT" }
                check(selection.isSelected() && enabled.isCurrent()) { "Element preparation changed" }
                val owner = EditorSchemeOwner.Element(enabled.id)
                if (requested != null) {
                    prepareOverrides(selection, owner, requested, requested.keys, enabled.rearms).batch
                } else {
                    val entries =
                        states[selection.scheme].orEmpty().filterValues {
                            enabled.rearms && it is OverrideState.Relinquished && it.owner == owner
                        }
                    prepareBatch(
                        selection,
                        entries.mapValues { (entry, _) ->
                            snapshotEntry(selection.scheme, entry).copy(overrideState = null, metadataValue = null)
                        },
                    )
                }
            }

        private fun prepareOverride(
            selection: SchemeTarget,
            entry: SchemeEntry,
            admission: SchemeAdmissionFacts,
            facts: SchemeOverrideFacts,
        ): Pair<EntryCheckpoint, OverrideWriteResult> {
            val scheme = selection.scheme
            val current = admission.owner as OverrideState?
            val plan = SchemeOverridePolicy.plan(facts)
            plan.value?.let(OverrideCodec::checkValue)
            val metadata =
                when {
                    plan.state === current -> {
                        admission.metadataValue as String?
                    }

                    plan.state == null -> {
                        null
                    }

                    else -> {
                        if (plan.state is OverrideState.Owned) {
                            OverrideCodec.checkValue(plan.state.original)
                            OverrideCodec.checkValue(plan.state.lastWritten)
                        }
                        OverrideCodec.encode(plan.state)
                    }
                }
            val prepared =
                prepareEntry(
                    scheme,
                    entry,
                    EntryRequest(
                        CheckpointEntry(plan.value ?: checkNotNull(facts.baseline), plan.state, metadata),
                        plan.value == null,
                    ),
                    null,
                    selection,
                )
            check(SchemeAdmission.matches(admission, requireNotNull(prepared.native).record.before)) {
                "Editor scheme changed during override planning"
            }
            return prepared to plan.result
        }

        private fun compareOwned(
            scheme: EditorColorsScheme,
            entry: SchemeEntry,
            expected: SchemeValue,
        ): SchemeEquality =
            when (entry) {
                is SchemeEntry.ColorEntry -> {
                    if (expected is SchemeValue.ColorValue) {
                        SchemeComparison.colors(
                            captureColorFacts(scheme.getColor(entry.key)),
                            captureColorFacts(expected.value),
                        )
                    } else {
                        SchemeEquality.DIFFERENT
                    }
                }

                is SchemeEntry.AttributesEntry -> {
                    if (expected is SchemeValue.AttributesValue) {
                        SchemeComparison.attributes(
                            captureAttributeFacts(scheme.getAttributes(entry.key)),
                            captureAttributeFacts(expected.value),
                        )
                    } else {
                        SchemeEquality.DIFFERENT
                    }
                }
            }

        private data class EntryRequest(
            val desired: CheckpointEntry,
            val preserveValue: Boolean = false,
        )

        private fun prepareEntry(
            scheme: EditorColorsScheme,
            entry: SchemeEntry,
            request: EntryRequest,
            capturedTarget: EditorColorsScheme?,
            selection: SchemeTarget? = null,
        ): EntryCheckpoint =
            synchronized(lock) {
                check(SwingUtilities.isEventDispatchThread()) { "Editor scheme preparation must run on EDT" }
                check(pending.none { it.hasPending }) { "Editor scheme recovery must settle before preparation" }
                check(selection?.isSelected() ?: (AyuEditorSchemeScope.activeScheme() === capturedTarget)) {
                    "Editor scheme target changed"
                }
                val desired = request.desired
                val admission = captureAdmission(scheme, entry)
                check(admission.cell !== SchemeCellFacts.Unavailable) { "Editor local state is unavailable" }
                val owner = admission.owner as OverrideState?
                check(
                    owner == null ||
                        (owner.encodedMetadata != null && admission.metadataValue === owner.encodedMetadata),
                ) {
                    "Editor ownership metadata changed outside Ayu"
                }
                check(owner != null || desired.overrideState !is OverrideState.Owned || !admission.hasMetadata) {
                    "Editor metadata must be qualified before acquiring ownership"
                }
                val original = snapshotEntry(scheme, entry)
                val requested =
                    desired.copy(
                        directValue = desired.directValue.snapshot(),
                        overrideState =
                            if (desired.overrideState === owner && desired.metadataValue === admission.metadataValue) {
                                owner
                            } else {
                                desired.overrideState?.snapshot(desired.metadataValue)
                            },
                    )
                check(
                    SchemeAdmission.matches(admission, captureAdmission(scheme, entry)) &&
                        selection?.isSelected() != false,
                ) {
                    "Editor scheme changed during preparation"
                }
                val native = NativeEntry(scheme, entry, original, requested, admission, request.preserveValue)
                EntryCheckpoint(scheme, entry, original, null, capturedTarget, requested, native, selection)
            }

        private inner class NativeBatch(
            private val selection: SchemeTarget,
            private val entries: Map<SchemeEntry, EntryCheckpoint>,
            recovering: Boolean = false,
        ) : SchemeWriteBatch {
            private val scheme = selection.scheme
            private val native = entries.mapValues { (_, checkpoint) -> requireNotNull(checkpoint.native) }
            private val updates: List<EntryCheckpoint>
            private val additions: List<EntryCheckpoint>
            private var phase = BatchRestorePhase.UNPREPARED
            private var seed: SchemeSeedCheckpoint? = null
            private var deletion: SchemeDeletionCheckpoint? = null
            override val records: List<SchemeEntryRecord> =
                Collections.unmodifiableList(
                    native.values.map { it.record },
                )
            override val seeds: List<SchemeSeedRecord>
                get() = seed?.records.orEmpty()
            override val seedProgress: List<SchemeSeedObservation>
                get() = seed?.observations.orEmpty()
            override val deletions: Map<SchemeEntry, SchemeCellFacts>
                get() = deletion?.cells.orEmpty()
            override val deletionProgress: Map<SchemeEntry, SchemeDeletionState>
                get() = deletion?.states.orEmpty()
            override val states: Map<SchemeEntry, AccentWriteState>
                get() = Collections.unmodifiableMap(entries.mapValues { it.value.state })
            override val progress: Map<SchemeEntry, Map<SchemeComponent, SchemeWriteProgress>>
                get() = Collections.unmodifiableMap(native.mapValues { it.value.progress })

            init {
                val grouped =
                    entries.values.partition {
                        !SchemeAdmission.isAbsent(
                            requireNotNull(it.native).record.before.cell,
                        )
                    }
                updates = grouped.first
                additions = grouped.second
                check(isPrepared(recovering)) { "Editor batch changed during preparation" }
                checkRestoration(entries.values)
                check(isPrepared(recovering)) { "Editor batch changed during recovery qualification" }
            }

            private fun isPrepared(recovering: Boolean): Boolean =
                if (recovering) {
                    selection.isSelected() && native.values.all { it.matchesPlanned() } && selection.isSelected()
                } else {
                    isUnchanged(entries.values)
                }

            override fun apply(): Boolean =
                synchronized(lock) {
                    checkEdt()
                    check(phase == BatchRestorePhase.UNPREPARED)
                    if (!isUnchanged(entries.values)) {
                        false
                    } else {
                        checkRestoration(entries.values)
                        isUnchanged(entries.values) && updates.all { it.apply() } && applyAdditions()
                    }
                }

            private fun applyAdditions(): Boolean {
                if (!isUnchanged(additions)) return false
                // Ordinary setters can no-op; qualify their actual results before creating deletion debt.
                checkRestoration(additions)
                return isUnchanged(additions) && additions.all { it.apply() }
            }

            private fun checkRestoration(checkpoints: Collection<EntryCheckpoint>) {
                val materialized =
                    checkpoints.map { requireNotNull(it.native).record }.filterNot {
                        SchemeAdmission.matchesCell(it.before.cell, it.planned.cell)
                    }
                checkBatchRestoration(scheme, materialized)
            }

            private fun isUnchanged(checkpoints: Collection<EntryCheckpoint>): Boolean =
                selection.isSelected() &&
                    checkpoints.all { requireNotNull(it.native).matchesBefore() } &&
                    selection.isSelected()

            override fun prepareRestore(blocked: Set<SchemeEntry>) {
                synchronized(lock) {
                    checkEdt()
                    if (phase != BatchRestorePhase.ENTRIES && additions.any { it.hasPending }) checkBlockers(blocked)
                    when (phase) {
                        BatchRestorePhase.UNPREPARED -> prepareSeeds()

                        BatchRestorePhase.SEEDED -> prepareDeletion()

                        BatchRestorePhase.SEEDS, BatchRestorePhase.DELETION,
                        BatchRestorePhase.ENTRIES, BatchRestorePhase.SETTLED,
                        -> Unit
                    }
                }
            }

            private fun prepareSeeds() {
                val targets = absentTargets()
                if (targets.isEmpty()) {
                    phase = BatchRestorePhase.ENTRIES
                    return
                }
                val prepared = SchemeSeedCheckpoint.prepare(scheme, targets.keys, ::ownsEntry)
                check(
                    prepared.expectedCells.all { (entry, cell) ->
                        SchemeAdmission.matchesCell(targets.getValue(entry), cell)
                    },
                ) {
                    "Editor seed preparation lost its retained value"
                }
                seed = prepared
                phase = BatchRestorePhase.SEEDS
            }

            private fun prepareDeletion() {
                val targets = absentTargets()
                if (targets.isEmpty()) {
                    phase = BatchRestorePhase.ENTRIES
                    return
                }
                val prepared = SchemeDeletionCheckpoint.prepare(scheme, targets.keys, ::ownsEntry)
                check(
                    prepared.cells.all { (entry, cell) ->
                        SchemeAdmission.matchesCell(targets.getValue(entry), cell)
                    },
                ) {
                    "Editor deletion preparation lost its retained seed"
                }
                deletion = prepared
                phase = BatchRestorePhase.DELETION
            }

            private fun absentTargets(): Map<SchemeEntry, SchemeCellFacts> =
                if (!selection.canRestore()) {
                    emptyMap()
                } else {
                    native
                        .mapNotNull { (entry, checkpoint) ->
                            checkpoint.prepareAbsence()?.let { entry to it }
                        }.toMap()
                }

            private fun ownsEntry(entry: SchemeEntry): Boolean =
                selection.canRestore() && native.getValue(entry).ownsEntry()

            override fun restore(blocked: Set<SchemeEntry>): Boolean =
                synchronized(lock) {
                    checkEdt()
                    check(phase != BatchRestorePhase.UNPREPARED && phase != BatchRestorePhase.SEEDED) {
                        "Editor restoration needs acknowledged preparation"
                    }
                    if (phase == BatchRestorePhase.SETTLED) return true
                    if (phase != BatchRestorePhase.ENTRIES) checkBlockers(blocked)
                    if (phase == BatchRestorePhase.SEEDS) {
                        restoreSeeds()
                        return false
                    }
                    if (phase == BatchRestorePhase.DELETION) restoreDeletion()
                    restoreEntries(blocked)
                    phase = BatchRestorePhase.SETTLED
                    true
                }

            private fun restoreEntries(blocked: Set<SchemeEntry>) {
                var failure: Throwable? = null
                for (checkpoint in (updates + additions).asReversed()) {
                    val error =
                        runCatching {
                            check(
                                !checkpoint.hasPending || requireNotNull(checkpoint.native).record.entry !in blocked,
                            ) {
                                "Editor restoration has a newer pending write"
                            }
                            checkpoint.restore()
                        }.exceptionOrNull() ?: continue
                    if (error is VirtualMachineError) throw error
                    failure = combineUiFailures(failure, error)
                }
                failure?.let { throw it }
            }

            private fun restoreSeeds() {
                val prepared = requireNotNull(seed)
                try {
                    prepared.apply()
                } finally {
                    for (entry in prepared.records.map { it.entry }.toSet()) {
                        native.getValue(entry).followSeed(prepared.expectedCells[entry])
                    }
                }
                check(!prepared.hasPending) { "Editor seeds remain unresolved" }
                phase = BatchRestorePhase.SEEDED
            }

            private fun restoreDeletion() {
                val prepared = requireNotNull(deletion)
                try {
                    prepared.apply()
                } finally {
                    prepared.states.forEach { (entry, state) -> native.getValue(entry).followDeletion(state) }
                }
                check(!prepared.hasPending) { "Editor deletion remains unresolved" }
                phase = BatchRestorePhase.ENTRIES
            }

            override fun accept() {
                synchronized(lock) {
                    checkEdt()
                    entries.values.forEach { it.commit() }
                    phase = BatchRestorePhase.SETTLED
                }
            }

            private fun checkBlockers(blocked: Set<SchemeEntry>) {
                check(
                    blocked.none { entries[it]?.hasPending == true },
                ) { "Editor restoration has a newer pending write" }
            }

            private fun checkEdt() {
                check(SwingUtilities.isEventDispatchThread()) { "Editor batch must run on EDT" }
            }
        }

        private enum class BatchRestorePhase { UNPREPARED, SEEDS, SEEDED, DELETION, ENTRIES, SETTLED }

        private fun captureAdmission(
            scheme: EditorColorsScheme,
            entry: SchemeEntry,
        ): SchemeAdmissionFacts {
            val parent = (scheme as? AbstractColorsScheme)?.parentScheme
            val cell = captureSchemeCell(scheme, entry) ?: SchemeCellFacts.Unavailable
            val metadata = scheme.metaProperties
            return SchemeAdmissionFacts(
                cell,
                states[scheme]?.get(entry),
                metadata,
                metadata.containsKey(entry.metadataKey),
                metadata[entry.metadataKey],
                parent,
            )
        }

        fun prepare() {
            val scope = activeScope()
            scope?.checkWritable()
            if (pending.isNotEmpty()) {
                if (scope ==
                    null
                ) {
                    restoreAccentCheckpoints(pending)
                } else {
                    scope.recover { restoreAccentCheckpoints(pending) }
                }
            }
            if (scope == null) return
            if (boundScope !== scope) {
                boundScope = scope
                receipts.clear()
                target = AyuEditorSchemeScope.activeScheme()
            }
            check(AyuEditorSchemeScope.activeScheme() === target) { "Editor scheme changed during accent application" }
            if (boundSegment !== scope.segment || refresh?.hasPending != true) {
                boundSegment = scope.segment
                refresh = RefreshCheckpoint(target).also(scope::record)
            }
        }

        fun retain(checkpoint: AccentCheckpoint) {
            if (checkpoint.hasPending && checkpoint !in pending) pending += checkpoint
        }

        fun reset() {
            pending.clear()
            receipts.clear()
            boundScope = null
            boundSegment = null
            target = null
            refresh = null
        }

        fun blocksClaim(
            scheme: EditorColorsScheme,
            blockedBy: List<AccentCheckpoint>,
        ): Boolean = blockedBy.any { it is EntryCheckpoint && it.scheme === scheme && it.hasPending }

        fun observeEnabled(
            id: AccentElementId,
            enabled: Boolean,
        ) {
            prepare()
            val scope = activeScope() ?: return
            scope.record(EnabledCheckpoint(id, elementEnabled[id], enabled))
        }

        private fun activeScope(): AccentApplyScope? =
            if (SwingUtilities.isEventDispatchThread()) AccentApplyScope.active?.takeUnless { it.isCommitted } else null

        /** Retained writes persist the cleanup baseline before a setter can replace the scheme. */
        private fun writeOrder(
            desired: CheckpointEntry,
            retained: Boolean,
        ): List<SchemeComponent> =
            if (retained && desired.overrideState is OverrideState.Owned) {
                listOf(SchemeComponent.OWNERSHIP, SchemeComponent.METADATA, SchemeComponent.VALUE)
            } else {
                SchemeComponent.entries
            }

        fun mutate(
            scheme: EditorColorsScheme,
            entry: SchemeEntry,
            desired: CheckpointEntry,
        ): Boolean {
            val prepared = desired.copy(overrideState = desired.overrideState?.snapshot(desired.metadataValue))
            val scope = activeScope()
            scope?.checkWritable()
            val previous = receipts.lastOrNull { it.scheme === scheme && it.entry == entry && it.isUnsettled }
            if (scope != null && previous?.permitsWrite() == false) return false
            val checkpoint =
                scope?.let {
                    EntryCheckpoint(
                        scheme,
                        entry,
                        snapshotEntry(scheme, entry),
                        refresh,
                        target,
                    )
                }
            if (checkpoint != null) {
                scope.record(checkpoint)
                receipts += checkpoint
            }
            for (component in writeOrder(prepared, retained = scope != null)) {
                if (scope != null) prepare()
                if (checkpoint == null) {
                    writeComponent(scheme, entry, prepared, component)
                } else if (!checkpoint.write(prepared, component)) {
                    return false
                }
            }
            if (scope != null) prepare()
            return true
        }

        private inner class RefreshCheckpoint(
            private val capturedTarget: EditorColorsScheme?,
        ) : AccentCheckpoint {
            private var needsRefresh = false
            override var hasPending = true
                private set

            fun markChanged() {
                needsRefresh = true
            }

            override fun restore(blockedBy: List<AccentCheckpoint>) {
                if (AyuEditorSchemeScope.activeScheme() !== capturedTarget) {
                    commit()
                    return
                }
                if (needsRefresh) {
                    EditorSchemeChange.publish()
                    needsRefresh = false
                }
                if (blockedBy.none { it is EntryCheckpoint && it.hasPending }) commit()
            }

            override fun commit() {
                hasPending = false
                needsRefresh = false
            }

            override fun retain() = this@SchemeCheckpoints.retain(this)
        }

        class EnabledCheckpoint internal constructor(
            val id: AccentElementId,
            val before: Boolean?,
            val planned: Boolean,
            initialState: AccentWriteState = AccentWriteState.OWNED,
        ) : AccentWriteCheckpoint {
            override var state: AccentWriteState = initialState
                private set

            override val hasPending: Boolean
                get() = state == AccentWriteState.PREPARED || state == AccentWriteState.OWNED

            val rearms: Boolean
                get() = before == false && planned

            fun isCurrent(): Boolean = elementEnabled[id] == before

            override fun apply(): Boolean =
                synchronized(lock) {
                    check(state == AccentWriteState.PREPARED) { "Element observation was already applied" }
                    if (!isCurrent()) {
                        state = AccentWriteState.RELINQUISHED
                        false
                    } else {
                        elementEnabled[id] = planned
                        state = AccentWriteState.OWNED
                        true
                    }
                }

            override fun restore(blockedBy: List<AccentCheckpoint>) {
                if (state == AccentWriteState.PREPARED) {
                    commit()
                    return
                }
                if (state != AccentWriteState.OWNED) return
                if (elementEnabled[id] == planned) {
                    if (before == null) elementEnabled.remove(id) else elementEnabled[id] = before
                    commit()
                } else {
                    state = AccentWriteState.RELINQUISHED
                }
            }

            override fun commit() {
                state = AccentWriteState.RESTORED
            }

            override fun retain() = Unit
        }

        private inner class EntryCheckpoint(
            val scheme: EditorColorsScheme,
            val entry: SchemeEntry,
            private val original: CheckpointEntry,
            private val notifier: RefreshCheckpoint?,
            private val capturedTarget: EditorColorsScheme?,
            private val requested: CheckpointEntry? = null,
            val native: NativeEntry? = null,
            private val selection: SchemeTarget? = null,
        ) : AccentWriteCheckpoint {
            private var expected = original
            private var beforeWrite: CheckpointEntry? = null
            private var entryState = if (requested == null) EntryState.OWNED else EntryState.PREPARED
            override val state: AccentWriteState
                get() =
                    when (entryState) {
                        EntryState.PREPARED -> AccentWriteState.PREPARED
                        EntryState.OWNED, EntryState.RELINQUISHING -> AccentWriteState.OWNED
                        EntryState.RELINQUISHED -> AccentWriteState.RELINQUISHED
                        EntryState.RESTORED -> AccentWriteState.RESTORED
                    }
            override val hasPending: Boolean
                get() = entryState == EntryState.OWNED || entryState == EntryState.RELINQUISHING
            val isUnsettled: Boolean
                get() = entryState != EntryState.RESTORED

            fun permitsWrite(): Boolean = entryState == EntryState.OWNED && observe()

            override fun apply(): Boolean =
                synchronized(lock) {
                    check(SwingUtilities.isEventDispatchThread()) { "Editor scheme application must run on EDT" }
                    check(entryState == EntryState.PREPARED) { "Editor scheme application was already started" }
                    val desired = requireNotNull(requested)
                    if (pending.any { it.hasPending } ||
                        !isSelected() ||
                        !requireNotNull(native).matchesBefore()
                    ) {
                        commit()
                        return false
                    }
                    entryState = EntryState.OWNED
                    val prepared = native
                    for (component in writeOrder(desired, retained = true)) {
                        check(isSelected() && prepared.isCurrent()) {
                            "Editor scheme target changed"
                        }
                        if (!prepared.apply(component)) return false
                    }
                    check(isSelected() && prepared.isCurrent()) {
                        "Editor scheme target changed"
                    }
                    true
                }

            private fun isSelected(): Boolean =
                selection?.isSelected() ?: (AyuEditorSchemeScope.activeScheme() === capturedTarget)

            private fun canRestore(): Boolean =
                selection?.canRestore() ?: (AyuEditorSchemeScope.activeScheme() === capturedTarget)

            fun write(
                desired: CheckpointEntry,
                component: SchemeComponent,
            ): Boolean {
                if (!observe()) return false
                val updated = expected.restoring(component, desired)
                if (expected == updated) {
                    beforeWrite = null
                    return true
                }
                beforeWrite = expected
                expected = updated
                writeComponent(scheme, entry, expected, component)
                if (!observe()) return false
                check(expected == updated) { "Editor setter did not update ${entry.metadataKey}" }
                beforeWrite = null
                return true
            }

            private fun observe(): Boolean {
                when (val current = snapshotEntry(scheme, entry)) {
                    expected -> Unit
                    beforeWrite -> expected = current
                    else -> relinquish()
                }
                return entryState == EntryState.OWNED
            }

            private fun relinquish() {
                if (AyuEditorSchemeScope.activeScheme() !== capturedTarget) {
                    commit()
                    return
                }
                entryState = EntryState.RELINQUISHING
                val observed = snapshotEntry(scheme, entry)
                if (observed.overrideState != expected.overrideState &&
                    observed.overrideState != beforeWrite?.overrideState
                ) {
                    entryState = EntryState.RELINQUISHED
                    return
                }
                val owner =
                    observed.overrideState?.owner ?: run {
                        entryState = EntryState.RELINQUISHED
                        return
                    }
                val released = OverrideState.Relinquished(owner)
                val encoded = OverrideCodec.encode(released)
                expected = expected.copy(overrideState = released.copy(encodedMetadata = encoded))
                writeComponent(scheme, entry, expected, SchemeComponent.OWNERSHIP)
                val metadata = observed.metadataValue
                if (metadata == expected.metadataValue || metadata == beforeWrite?.metadataValue) {
                    beforeWrite = expected.copy(metadataValue = metadata)
                    expected = expected.copy(metadataValue = encoded)
                    if (metadata != encoded) writeComponent(scheme, entry, expected, SchemeComponent.METADATA)
                }
                entryState = EntryState.RELINQUISHED
            }

            override fun restore(blockedBy: List<AccentCheckpoint>) {
                synchronized(lock) {
                    if (entryState == EntryState.PREPARED) commit()
                    if (!hasPending) return
                    if (!canRestore()) {
                        if (native == null) {
                            commit()
                        } else {
                            native.preserve()
                            entryState = EntryState.RELINQUISHED
                        }
                        return
                    }
                    check(
                        blockedBy.none {
                            it is EntryCheckpoint &&
                                it.scheme === scheme &&
                                it.entry == entry &&
                                it.hasPending
                        },
                    ) {
                        "Editor restoration blocked by a newer write: ${entry.metadataKey}"
                    }
                    restoreOwned()
                }
            }

            private fun restoreOwned() {
                if (native != null) {
                    if (native.restore(::canRestore)) {
                        commit()
                    } else {
                        native.preserve()
                        entryState = EntryState.RELINQUISHED
                    }
                    return
                }
                if (entryState == EntryState.RELINQUISHING) {
                    relinquish()
                    return
                }
                for (component in SchemeComponent.entries) {
                    if (AyuEditorSchemeScope.activeScheme() !== capturedTarget) {
                        commit()
                        return
                    }
                    if (!restoreComponent(component)) return
                }
                commit()
            }

            private fun restoreComponent(component: SchemeComponent): Boolean {
                val previousValue = snapshotEntry(scheme, entry).directValue
                return try {
                    write(original, component)
                } finally {
                    if (component == SchemeComponent.VALUE &&
                        snapshotEntry(scheme, entry).directValue != previousValue
                    ) {
                        notifier?.markChanged()
                    }
                }
            }

            override fun commit() {
                native?.finishUnused()
                if (entryState != EntryState.RELINQUISHED) entryState = EntryState.RESTORED
            }

            override fun retain() {
                this@SchemeCheckpoints.retain(this)
            }
        }

        private enum class EntryState { PREPARED, OWNED, RELINQUISHING, RELINQUISHED, RESTORED }

        /** Uses the existing owner map; only immutable per-attempt evidence is retained here. */
        private inner class NativeEntry(
            private val scheme: EditorColorsScheme,
            private val entry: SchemeEntry,
            original: CheckpointEntry,
            requested: CheckpointEntry,
            admission: SchemeAdmissionFacts,
            preserveValue: Boolean,
            recovered: SchemeEntryRecord? = null,
        ) {
            private val planned =
                SchemeAdmissionFacts(
                    if (preserveValue) admission.cell else capturePlannedCell(requested.directValue),
                    requested.overrideState,
                    admission.metadataTable,
                    requested.metadataValue != null,
                    requested.metadataValue,
                    admission.parent,
                )
            private val restored =
                SchemeAdmissionFacts(
                    when (val cell = admission.cell) {
                        is SchemeCellFacts.Attributes -> {
                            if (cell.value == null) cell else capturePlannedCell(original.directValue)
                        }

                        else -> {
                            cell
                        }
                    },
                    admission.owner,
                    admission.metadataTable,
                    admission.hasMetadata,
                    admission.metadataValue,
                    admission.parent,
                )
            private val released =
                ((admission.owner ?: requested.overrideState) as? OverrideState.Owned)?.let { claim ->
                    val state = OverrideState.Relinquished(claim.owner)
                    val encoded = OverrideCodec.encode(state)
                    SchemeAdmissionFacts(
                        restored.cell,
                        state.copy(encodedMetadata = encoded),
                        admission.metadataTable,
                        true,
                        encoded,
                        admission.parent,
                    )
                }
            val record = recovered ?: SchemeEntryRecord(entry, admission, planned, restored, released)
            private val evidence =
                SchemeComponent.entries.associateWith {
                    SchemeWriteEvidence(it, record.before, record.planned, record.restored, record.released)
                }

            val progress: Map<SchemeComponent, SchemeWriteProgress>
                get() = Collections.unmodifiableMap(evidence.mapValues { it.value.progress })

            fun finishUnused() = evidence.values.forEach { it.finishUnused() }

            fun preserve() {
                finishUnused()
                evidence.values.forEach { it.preserve() }
            }

            fun isCurrent(): Boolean = (scheme as AbstractColorsScheme).parentScheme === planned.parent

            fun matchesBefore(): Boolean = SchemeAdmission.matches(record.before, captureAdmission(scheme, entry))

            fun matchesPlanned(): Boolean = SchemeAdmission.matches(record.planned, captureAdmission(scheme, entry))

            fun resumeRecovery() {
                val observed = captureAdmission(scheme, entry)
                check(SchemeAdmission.matches(record.planned, observed)) { "Editor recovery observation changed" }
                evidence.values.forEach { receipt ->
                    receipt.beginApply()
                    check(receipt.observeApply(observed)) { "Editor recovery evidence changed" }
                }
            }

            fun ownsEntry(): Boolean =
                isCurrent() && evidence.getValue(SchemeComponent.OWNERSHIP).accepts(captureAdmission(scheme, entry))

            fun prepareAbsence(): SchemeCellFacts? {
                if (!SchemeAdmission.isAbsent(record.before.cell) || !isCurrent()) return null
                val receipt = evidence.getValue(SchemeComponent.VALUE)
                val observed = captureAdmission(scheme, entry)
                if (!evidence.getValue(SchemeComponent.OWNERSHIP).accepts(observed)) receipt.preserve()
                return if (isCurrent() && receipt.prepareRestore(observed)) observed.cell else null
            }

            fun followSeed(cell: SchemeCellFacts?) = evidence.getValue(SchemeComponent.VALUE).followSeed(cell)

            fun followDeletion(state: SchemeDeletionState) {
                val receipt = evidence.getValue(SchemeComponent.VALUE)
                when (state) {
                    SchemeDeletionState.PENDING -> Unit
                    SchemeDeletionState.RESTORED -> receipt.prepareRestore(record.before)
                    SchemeDeletionState.FOREIGN -> receipt.preserve()
                }
            }

            fun apply(component: SchemeComponent): Boolean {
                val observed = captureAdmission(scheme, entry)
                if (observed.parent !== planned.parent || evidence.values.any { !it.accepts(observed) }) return false
                val receipt = evidence.getValue(component)
                receipt.beginApply()
                if (SchemeAdmission.matchesComponent(receipt.planned, observed, component)) {
                    return receipt.observeApply(observed)
                }
                var accepted = false
                writeObserved(component, receipt.planned) {
                    accepted = receipt.observeApply(captureAdmission(scheme, entry))
                }
                return accepted
            }

            fun restore(isTargetCurrent: () -> Boolean): Boolean {
                for ((component, receipt) in evidence) {
                    if (!isTargetCurrent() || !isCurrent()) return false
                    val observed = captureAdmission(scheme, entry)
                    if (!prepareRestore(component, receipt, observed)) continue
                    receipt.beginRestore()
                    writeObserved(component, receipt.restored) {
                        receipt.observeRestore(captureAdmission(scheme, entry))
                    }
                    check(!receipt.hasPending) { "Editor component restoration did not settle: ${entry.metadataKey}" }
                }
                return isTargetCurrent() && isCurrent()
            }

            private fun prepareRestore(
                component: SchemeComponent,
                receipt: SchemeWriteEvidence,
                observed: SchemeAdmissionFacts,
            ): Boolean {
                if (component == SchemeComponent.VALUE &&
                    !evidence.getValue(SchemeComponent.OWNERSHIP).accepts(observed)
                ) {
                    receipt.preserve()
                }
                if (component != SchemeComponent.VALUE && evidence.getValue(SchemeComponent.VALUE).isForeign) {
                    if (evidence.getValue(SchemeComponent.OWNERSHIP).isForeign) {
                        receipt.preserve()
                    } else {
                        receipt.release()
                    }
                }
                return receipt.prepareRestore(observed)
            }

            private fun writeObserved(
                component: SchemeComponent,
                desired: SchemeAdmissionFacts,
                observe: () -> Unit,
            ) {
                var failure: Throwable? = null
                try {
                    writeNative(component, desired)
                } catch (writeFailure: RuntimeException) {
                    failure = writeFailure
                }
                try {
                    observe()
                } catch (readFailure: RuntimeException) {
                    failure = combineUiFailures(failure, readFailure)
                }
                failure?.let { throw it }
            }

            private fun writeNative(
                component: SchemeComponent,
                desired: SchemeAdmissionFacts,
            ) {
                when (component) {
                    SchemeComponent.VALUE -> {
                        writeSchemeCell(scheme as AbstractColorsScheme, entry, desired.cell)
                    }

                    SchemeComponent.OWNERSHIP -> {
                        val owner = desired.owner as OverrideState?
                        if (owner == null) {
                            states[scheme]?.remove(entry)
                            if (states[scheme]?.isEmpty() == true) states.remove(scheme)
                        } else {
                            states.getOrPut(scheme) { mutableMapOf() }[entry] = owner
                        }
                    }

                    SchemeComponent.METADATA -> {
                        val metadata = desired.metadataTable as Properties
                        check(scheme.metaProperties === metadata) { "Editor metadata container changed" }
                        (scheme as AbstractColorsScheme).setSaveNeeded(true)
                        if (desired.hasMetadata) {
                            metadata[entry.metadataKey] = requireNotNull(desired.metadataValue)
                        } else {
                            metadata.remove(entry.metadataKey)
                        }
                    }
                }
            }
        }

        fun sealPreview(checkpoint: SchemeCheckpoint): PreviewCheckpoint =
            synchronized(lock) {
                PreviewCheckpoint(
                    checkpoint,
                    checkpoint.entries.keys
                        .associateWith { snapshotEntry(checkpoint.scheme, it) }
                        .toMutableMap(),
                    checkpoint.entries.keys.toMutableSet(),
                )
            }

        fun restorePreviews(checkpoints: List<PreviewCheckpoint>): PreviewRestoreAttempt =
            synchronized(lock) { PreviewRestoration().restore(checkpoints) }

        fun capture(
            scheme: EditorColorsScheme,
            owner: EditorSchemeOwner,
            entries: Set<SchemeEntry>,
        ): SchemeCheckpoint =
            synchronized(lock) {
                hydrate(scheme)
                val owned = states[scheme].orEmpty().filterValues { it.owner == owner }.keys
                SchemeCheckpoint(scheme, (entries + owned).associateWith { snapshotEntry(scheme, it) })
            }

        fun rollback(checkpoint: SchemeCheckpoint): List<RuntimeException> =
            synchronized(lock) {
                val failures = mutableListOf<RuntimeException>()
                val schemeStates = states.getOrPut(checkpoint.scheme) { mutableMapOf() }
                var cancellation: RuntimeException? = null
                checkpoint.entries.forEach { (entry, saved) ->
                    try {
                        failures += restoreCheckpointEntry(checkpoint.scheme, schemeStates, entry, saved)
                    } catch (failure: RuntimeException) {
                        if (!failure.isCancellation()) throw failure
                        cancellation = cancellation.record(failure)
                    }
                }
                if (schemeStates.isEmpty()) states.remove(checkpoint.scheme)
                cancellation?.let { cancelled ->
                    failures.forEach(cancelled::addSuppressed)
                    throw cancelled
                }
                failures
            }

        private fun restoreCheckpointEntry(
            scheme: EditorColorsScheme,
            schemeStates: MutableMap<SchemeEntry, OverrideState>,
            entry: SchemeEntry,
            saved: CheckpointEntry,
        ): List<RuntimeException> =
            buildList {
                var cancellation =
                    attemptRestore(this) {
                        writeValue(scheme, entry, saved.directValue)
                    }
                cancellation =
                    attemptRestore(this, cancellation) {
                        val state = saved.overrideState
                        if (state == null) {
                            schemeStates.remove(entry)
                        } else {
                            schemeStates[entry] = state.snapshot()
                        }
                        val metadata = saved.metadataValue
                        if (metadata == null) {
                            scheme.metaProperties.remove(entry.metadataKey)
                        } else {
                            scheme.metaProperties.setProperty(entry.metadataKey, metadata)
                        }
                    }
                cancellation?.let { cancelled ->
                    forEach(cancelled::addSuppressed)
                    throw cancelled
                }
            }

        private fun attemptRestore(
            failures: MutableList<RuntimeException>,
            cancellation: RuntimeException? = null,
            action: () -> Unit,
        ): RuntimeException? =
            try {
                action()
                cancellation
            } catch (failure: RuntimeException) {
                if (failure.isCancellation()) {
                    cancellation.record(failure)
                } else {
                    failures += failure
                    cancellation
                }
            }
    }

    private fun snapshotEntry(
        scheme: EditorColorsScheme,
        entry: SchemeEntry,
    ): CheckpointEntry =
        CheckpointEntry(
            read(scheme, entry, isBaseline = true).snapshot(),
            states[scheme]?.get(entry)?.snapshot(),
            scheme.metaProperties.getProperty(entry.metadataKey),
        )

    /** Restores only a continuous chain of owned preview writes, retaining proven progress for retry. */
    private class PreviewRestoration {
        private val completed = mutableSetOf<PreviewCheckpoint>()
        private val failures = mutableListOf<RuntimeException>()
        private var cancellation: RuntimeException? = null
        private var changed = false

        fun restore(checkpoints: List<PreviewCheckpoint>): PreviewRestoreAttempt {
            if (attempt { validateChain(checkpoints) }) {
                val blocked = IdentityHashMap<EditorColorsScheme, MutableSet<SchemeEntry>>()
                checkpoints.asReversed().forEach { checkpoint ->
                    val original = checkpoint.original
                    val blockedEntries = blocked.getOrPut(original.scheme) { mutableSetOf() }
                    if (original.entries.keys.any { it in blockedEntries }) {
                        failures +=
                            IllegalStateException("Syntax preview restore is blocked by an incomplete newer preview")
                        blockedEntries += original.entries.keys
                    } else if (restoreCheckpoint(checkpoint)) {
                        completed += checkpoint
                    } else {
                        blockedEntries += original.entries.keys
                    }
                }
            }
            return PreviewRestoreAttempt(completed.toSet(), failures.toList(), cancellation, changed)
        }

        private fun validateChain(checkpoints: List<PreviewCheckpoint>) {
            val projected = IdentityHashMap<EditorColorsScheme, MutableMap<SchemeEntry, CheckpointEntry>>()
            checkpoints.asReversed().forEach { checkpoint ->
                val original = checkpoint.original
                val entries = projected.getOrPut(original.scheme) { mutableMapOf() }
                original.entries.forEach { (entry, saved) ->
                    val observed = entries.getOrPut(entry) { snapshotEntry(original.scheme, entry) }
                    checkOwned(entry, observed, checkpoint.expected.getValue(entry))
                    entries[entry] = saved
                }
            }
        }

        private fun restoreCheckpoint(checkpoint: PreviewCheckpoint): Boolean {
            var succeeded = true
            checkpoint.pending.toList().forEach { entry ->
                val restored = restoreEntry(checkpoint, entry)
                if (restored) checkpoint.pending.remove(entry) else succeeded = false
            }
            return succeeded
        }

        private fun restoreEntry(
            checkpoint: PreviewCheckpoint,
            entry: SchemeEntry,
        ): Boolean {
            var succeeded = true
            for (component in SchemeComponent.entries) {
                if (!restoreComponent(checkpoint, entry, component)) succeeded = false
            }
            return succeeded
        }

        private fun restoreComponent(
            checkpoint: PreviewCheckpoint,
            entry: SchemeEntry,
            component: SchemeComponent,
        ): Boolean {
            val scheme = checkpoint.original.scheme
            val expected = checkpoint.expected.getValue(entry)
            val desired = expected.restoring(component, checkpoint.original.entries.getValue(entry))
            if (!attempt { checkOwned(entry, snapshotEntry(scheme, entry), expected) }) return false
            if (expected == desired) return true
            val written = attempt { writeComponent(scheme, entry, desired, component) }
            val verified =
                attempt {
                    val observed = snapshotEntry(scheme, entry)
                    if (observed == desired) {
                        checkpoint.expected[entry] = desired
                        changed = true
                    } else {
                        checkOwned(entry, observed, expected)
                        check(!written) { "Syntax preview restore did not update ${entry.metadataKey}" }
                    }
                }
            return written && verified
        }

        private fun attempt(action: () -> Unit): Boolean =
            try {
                action()
                true
            } catch (failure: RuntimeException) {
                if (failure.isCancellation()) cancellation = cancellation.record(failure) else failures += failure
                false
            }

        private fun checkOwned(
            entry: SchemeEntry,
            observed: CheckpointEntry,
            expected: CheckpointEntry,
        ) {
            check(
                observed == expected,
            ) { "Syntax preview restore conflicts with current state for ${entry.metadataKey}" }
        }
    }

    private fun CheckpointEntry.restoring(
        component: SchemeComponent,
        saved: CheckpointEntry,
    ): CheckpointEntry =
        when (component) {
            SchemeComponent.VALUE -> copy(directValue = saved.directValue.snapshot())
            SchemeComponent.OWNERSHIP -> copy(overrideState = saved.overrideState?.snapshot())
            SchemeComponent.METADATA -> copy(metadataValue = saved.metadataValue)
        }

    private fun writeComponent(
        scheme: EditorColorsScheme,
        entry: SchemeEntry,
        desired: CheckpointEntry,
        component: SchemeComponent,
    ) {
        when (component) {
            SchemeComponent.VALUE -> {
                writeValue(scheme, entry, desired.directValue)
            }

            SchemeComponent.OWNERSHIP -> {
                val state = desired.overrideState
                if (state == null) {
                    states[scheme]?.remove(entry)
                    if (states[scheme]?.isEmpty() == true) states.remove(scheme)
                } else {
                    states.getOrPut(scheme) { mutableMapOf() }[entry] = state.snapshot()
                }
            }

            SchemeComponent.METADATA -> {
                val metadata = desired.metadataValue
                if (metadata == null) {
                    scheme.metaProperties.remove(entry.metadataKey)
                } else {
                    scheme.metaProperties.setProperty(entry.metadataKey, metadata)
                }
            }
        }
    }

    private fun write(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        entry: SchemeEntry,
        value: SchemeValue,
    ): OverrideWriteResult {
        synchronized(lock) {
            checkpoints.prepare()
            hydrate(scheme, entry)
            return when (val state = states[scheme]?.get(entry)) {
                null -> {
                    val original = read(scheme, entry, isBaseline = true)
                    val owned = OverrideState.Owned(owner, original, value.snapshot())
                    val desired = CheckpointEntry(value.snapshot(), owned, OverrideCodec.encode(owned))
                    if (checkpoints.mutate(
                            scheme,
                            entry,
                            desired,
                        )
                    ) {
                        OverrideWriteResult.APPLIED
                    } else {
                        OverrideWriteResult.RELINQUISHED
                    }
                }

                is OverrideState.Relinquished -> {
                    OverrideWriteResult.RELINQUISHED
                }

                is OverrideState.Owned -> {
                    updateOwned(scheme, owner, entry, value, state)
                }
            }
        }
    }

    private fun updateOwned(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        entry: SchemeEntry,
        value: SchemeValue,
        state: OverrideState.Owned,
    ): OverrideWriteResult {
        if (state.owner != owner) {
            log.warn("Skipping editor scheme override because $entry is already owned by ${state.owner}")
            return OverrideWriteResult.SKIPPED
        }
        val current = snapshotEntry(scheme, entry)
        val updated =
            if (read(scheme, entry) != state.lastWritten) {
                OverrideState.Relinquished(owner)
            } else {
                state.copy(lastWritten = value.snapshot())
            }
        val desired =
            CheckpointEntry(
                if (updated is OverrideState.Owned) value.snapshot() else current.directValue,
                updated,
                OverrideCodec.encode(updated),
            )
        return if (checkpoints.mutate(scheme, entry, desired) && updated is OverrideState.Owned) {
            OverrideWriteResult.APPLIED
        } else {
            OverrideWriteResult.RELINQUISHED
        }
    }

    private fun read(
        scheme: EditorColorsScheme,
        entry: SchemeEntry,
        isBaseline: Boolean = false,
    ): SchemeValue {
        if (isBaseline && scheme is AbstractColorsScheme) {
            return when (entry) {
                is SchemeEntry.ColorEntry -> {
                    when (val direct = readSchemeColor(scheme, entry.key)) {
                        SchemeColorCell.Absent, SchemeColorCell.Inherited -> SchemeValue.InheritedColor
                        SchemeColorCell.Null -> SchemeValue.ColorValue(null)
                        is SchemeColorCell.Value -> SchemeValue.ColorValue(direct.color)
                    }
                }

                is SchemeEntry.AttributesEntry -> {
                    when (val direct = readSchemeAttributes(scheme, entry.key)) {
                        SchemeAttributeCell.Absent, SchemeAttributeCell.Inherited -> SchemeValue.InheritedAttributes
                        is SchemeAttributeCell.Value -> SchemeValue.AttributesValue(direct.attributes.clone())
                    }
                }
            }
        }
        return when (entry) {
            is SchemeEntry.ColorEntry -> {
                SchemeValue.ColorValue(scheme.getColor(entry.key))
            }

            is SchemeEntry.AttributesEntry -> {
                SchemeValue.AttributesValue(
                    scheme.getAttributes(entry.key)?.clone(),
                )
            }
        }
    }

    private fun writeValue(
        scheme: EditorColorsScheme,
        entry: SchemeEntry,
        value: SchemeValue,
    ) {
        when (entry) {
            is SchemeEntry.ColorEntry -> {
                when (value) {
                    is SchemeValue.ColorValue -> {
                        scheme.setColor(entry.key, value.value)
                    }

                    is SchemeValue.InheritedColor -> {
                        scheme.setColor(entry.key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
                    }

                    else -> {
                        error("Editor scheme entry and value types must match")
                    }
                }
            }

            is SchemeEntry.AttributesEntry -> {
                when (value) {
                    is SchemeValue.AttributesValue -> {
                        scheme.setAttributes(entry.key, value.value?.clone())
                    }

                    is SchemeValue.InheritedAttributes -> {
                        scheme.setAttributes(entry.key, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
                    }

                    else -> {
                        error("Editor scheme entry and value types must match")
                    }
                }
            }
        }
    }

    fun rearm(
        owner: EditorSchemeOwner,
        schemes: Iterable<EditorColorsScheme>,
        activeAttributes: Map<EditorColorsScheme, Set<String>> = emptyMap(),
    ) {
        synchronized(lock) {
            checkpoints.prepare()
            schemes.forEach(::hydrate)
            val isTargeted = activeAttributes.isNotEmpty()
            for (scheme in states.keys.toList()) {
                if (isTargeted && scheme !in activeAttributes) continue
                val schemeStates: Map<SchemeEntry, OverrideState> = states[scheme].orEmpty()
                for ((entry, state) in schemeStates.toList()) {
                    val activeKeyNames = activeAttributes[scheme]
                    val shouldRemove =
                        state is OverrideState.Relinquished &&
                            state.owner == owner &&
                            (
                                activeKeyNames == null ||
                                    entry !is SchemeEntry.AttributesEntry ||
                                    entry.key.externalName !in activeKeyNames
                            )
                    if (shouldRemove) {
                        checkpoints.mutate(
                            scheme,
                            entry,
                            snapshotEntry(scheme, entry).copy(
                                overrideState = null,
                                metadataValue = null,
                            ),
                        )
                    }
                }
            }
            states.entries.removeIf { it.value.isEmpty() }
        }
    }

    fun hasState(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
    ): Boolean =
        synchronized(lock) {
            hydrate(scheme)
            states[scheme]?.values?.any { it.owner == owner } == true
        }

    fun inherit(
        target: EditorColorsScheme,
        canonical: EditorColorsScheme,
    ) = synchronized(lock) {
        checkpoints.prepare()
        hydrate(target)
        hydrate(canonical)
        for ((entry, state) in states[canonical].orEmpty()) {
            if (states[target]?.containsKey(entry) == true) continue
            if (state is OverrideState.Owned && read(target, entry) != state.lastWritten) continue
            val inherited =
                when (state) {
                    is OverrideState.Owned -> {
                        state.copy(
                            original = state.original.snapshot(),
                            lastWritten = state.lastWritten.snapshot(),
                        )
                    }

                    is OverrideState.Relinquished -> {
                        state
                    }
                }
            checkpoints.mutate(
                target,
                entry,
                snapshotEntry(target, entry).copy(
                    overrideState = inherited,
                    metadataValue = OverrideCodec.encode(inherited),
                ),
            )
        }
    }

    private fun restoreEntry(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        entry: SchemeEntry,
        state: OverrideState,
    ): RuntimeException? {
        if (state.owner != owner || state !is OverrideState.Owned) return null
        return try {
            val current = snapshotEntry(scheme, entry)
            val desired =
                if (read(scheme, entry) != state.lastWritten) {
                    val relinquished = OverrideState.Relinquished(owner)
                    current.copy(overrideState = relinquished, metadataValue = OverrideCodec.encode(relinquished))
                } else {
                    CheckpointEntry(state.original.snapshot(), null, null)
                }
            checkpoints.mutate(scheme, entry, desired)
            null
        } catch (exception: RuntimeException) {
            exception
        }
    }

    private fun hydrate(scheme: EditorColorsScheme) {
        scheme.metaProperties
            .stringPropertyNames()
            .filter { it.startsWith(METADATA_PREFIX) }
            .forEach { key ->
                val entry = SchemeEntry.fromMetadataKey(key) ?: return@forEach
                hydrate(scheme, entry)
            }
    }

    private fun hydrate(
        scheme: EditorColorsScheme,
        entry: SchemeEntry,
    ) {
        val schemeStates = states.getOrPut(scheme) { mutableMapOf() }
        if (entry in schemeStates) return
        scheme.metaProperties
            .getProperty(entry.metadataKey)
            .takeIf { it?.contains(';') == true }
            ?.let { encoded -> schemeStates[entry] = OverrideCodec.decode(encoded).snapshot(encoded) }
    }

    internal sealed interface SchemeEntry {
        val metadataKey: String

        data class ColorEntry(
            val key: ColorKey,
        ) : SchemeEntry {
            override val metadataKey = "$METADATA_PREFIX$COLOR_KIND.${key.externalName}"
        }

        data class AttributesEntry(
            val key: TextAttributesKey,
        ) : SchemeEntry {
            override val metadataKey = "$METADATA_PREFIX$ATTRIBUTES_KIND.${key.externalName}"
        }

        companion object {
            fun fromMetadataKey(key: String): SchemeEntry? {
                val encoded = key.removePrefix(METADATA_PREFIX)
                val kind = encoded.substringBefore('.')
                val name = encoded.substringAfter('.', missingDelimiterValue = "")
                if (name.isEmpty()) return null
                return when (kind) {
                    COLOR_KIND -> ColorEntry(ColorKey.find(name))
                    ATTRIBUTES_KIND -> AttributesEntry(TextAttributesKey.find(name))
                    else -> null
                }
            }
        }
    }

    internal sealed interface SchemeValue {
        fun snapshot(): SchemeValue

        data class ColorValue(
            val value: Color?,
        ) : SchemeValue {
            override fun snapshot(): SchemeValue = this
        }

        data class AttributesValue(
            val value: TextAttributes?,
        ) : SchemeValue {
            override fun snapshot(): SchemeValue = AttributesValue(value?.clone())
        }

        data object InheritedColor : SchemeValue {
            override fun snapshot(): SchemeValue = this
        }

        data object InheritedAttributes : SchemeValue {
            override fun snapshot(): SchemeValue = this
        }
    }

    internal sealed interface OverrideState {
        val owner: EditorSchemeOwner
        val encodedMetadata: String?

        data class Owned(
            override val owner: EditorSchemeOwner,
            val original: SchemeValue,
            val lastWritten: SchemeValue,
            override val encodedMetadata: String? = null,
        ) : OverrideState

        data class Relinquished(
            override val owner: EditorSchemeOwner,
            override val encodedMetadata: String? = null,
        ) : OverrideState
    }

    private fun OverrideState.snapshot(metadata: String? = encodedMetadata): OverrideState =
        when (this) {
            is OverrideState.Owned -> {
                copy(
                    original = original.snapshot(),
                    lastWritten = lastWritten.snapshot(),
                    encodedMetadata = metadata,
                )
            }

            is OverrideState.Relinquished -> {
                copy(encodedMetadata = metadata)
            }
        }

    private object OverrideCodec {
        fun encode(state: OverrideState): String {
            val owner = ownerToken(state.owner)
            return when (state) {
                is OverrideState.Owned -> {
                    listOf("O", owner, encodeValue(state.original), encodeValue(state.lastWritten))
                        .joinToString(";")
                }

                is OverrideState.Relinquished -> {
                    "R;$owner"
                }
            }
        }

        /** Reads only the owner header; unknown payload versions remain uninterpreted. */
        fun namesOwner(
            encoded: String,
            owner: EditorSchemeOwner,
        ): Boolean = encoded.substringAfter(';', "").substringBefore(';') == ownerToken(owner)

        private fun ownerToken(owner: EditorSchemeOwner): String =
            when (owner) {
                is EditorSchemeOwner.Element -> "E:${owner.id.name}"
                EditorSchemeOwner.AlwaysOn -> "A"
                EditorSchemeOwner.Syntax -> "S"
                EditorSchemeOwner.Vcs -> "V"
            }

        /** New claims must not invoke callbacks in a foreign payload while encoding their retained baseline. */
        fun checkValue(value: SchemeValue) {
            val qualified =
                when (val cell = capturePlannedCell(value)) {
                    is SchemeCellFacts.Color -> {
                        cell.value !is SchemeColorFacts.Opaque
                    }

                    is SchemeCellFacts.Attributes -> {
                        val fields = checkNotNull(cell.value).fields
                        listOf(fields.background, fields.foreground, fields.effectColor, fields.errorStripe)
                            .all { it == null || it is SchemeColorFacts.Plain }
                    }

                    SchemeCellFacts.InheritedAttributes -> {
                        true
                    }

                    SchemeCellFacts.Unavailable -> {
                        false
                    }
                }
            check(qualified) { "Editor metadata requires a qualified native payload" }
        }

        fun decode(encoded: String): OverrideState {
            val parts = encoded.split(';')
            val owner =
                when (val encodedOwner = parts[1]) {
                    "A" -> EditorSchemeOwner.AlwaysOn
                    "S" -> EditorSchemeOwner.Syntax
                    "V" -> EditorSchemeOwner.Vcs
                    else -> EditorSchemeOwner.Element(AccentElementId.valueOf(encodedOwner.removePrefix("E:")))
                }
            return if (parts[0] == "R") {
                OverrideState.Relinquished(owner)
            } else {
                OverrideState.Owned(
                    owner,
                    decodeValue(parts[STATE_PAYLOAD_INDEX]),
                    decodeValue(parts.last()),
                )
            }
        }

        private fun encodeValue(value: SchemeValue): String =
            when (value) {
                is SchemeValue.ColorValue -> {
                    "C,${value.value?.rgb?.toString().orEmpty()}"
                }

                is SchemeValue.AttributesValue -> {
                    value.value?.let { attributes ->
                        val element = Element("attributes")
                        attributes.writeExternal(element)
                        "A,${Base64.getEncoder().encodeToString(JDOMUtil.writeElement(element).encodeToByteArray())}"
                    } ?: "N"
                }

                SchemeValue.InheritedColor -> {
                    "IC"
                }

                SchemeValue.InheritedAttributes -> {
                    "IA"
                }
            }

        private fun decodeValue(encoded: String): SchemeValue =
            when (encoded) {
                "IC" -> {
                    SchemeValue.InheritedColor
                }

                "IA" -> {
                    SchemeValue.InheritedAttributes
                }

                else -> {
                    when (encoded.first()) {
                        'C' -> {
                            SchemeValue.ColorValue(
                                encoded
                                    .substringAfter(',')
                                    .takeIf(String::isNotEmpty)
                                    ?.toInt()
                                    ?.let(::decodeArgb),
                            )
                        }

                        'N' -> {
                            SchemeValue.AttributesValue(null)
                        }

                        else -> {
                            val xml = Base64.getDecoder().decode(encoded.substringAfter(',')).decodeToString()
                            SchemeValue.AttributesValue(TextAttributes(JDOMUtil.load(xml)))
                        }
                    }
                }
            }

        private fun decodeArgb(value: Int): Color {
            val rgbHex = (value and RGB_MASK).toString(HEX_RADIX).padStart(RGB_HEX_LENGTH, '0')
            val alpha = value ushr ALPHA_SHIFT and CHANNEL_MASK
            return ColorUtil.toAlpha(ColorUtil.fromHex(rgbHex), alpha)
        }

        private const val RGB_MASK = 0xFFFFFF
        private const val HEX_RADIX = 16
        private const val RGB_HEX_LENGTH = 6
        private const val ALPHA_SHIFT = 24
        private const val CHANNEL_MASK = 0xFF

        private const val STATE_PAYLOAD_INDEX = 2
    }

    private const val METADATA_PREFIX = "dev.ayuislands.override.v1."
    private const val COLOR_KIND = "color"
    private const val ATTRIBUTES_KIND = "attributes"
}

private fun RuntimeException?.record(next: RuntimeException): RuntimeException {
    val first = this ?: return next
    if (first !== next) first.addSuppressed(next)
    return first
}

private fun RuntimeException.isCancellation(): Boolean =
    this is ProcessCanceledException || this is CancellationException
