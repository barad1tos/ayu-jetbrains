package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.session.StudioNativeEvidence.isTerminal
import dev.ayuislands.theme.AyuSchemeResource
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.SchemeAdmission
import dev.ayuislands.theme.SchemeCellFacts
import dev.ayuislands.theme.SchemeComponent
import dev.ayuislands.theme.SchemePersistenceCapture
import dev.ayuislands.theme.SchemePersistenceResult
import dev.ayuislands.theme.SchemeStoredCell
import dev.ayuislands.theme.SchemeTarget
import dev.ayuislands.theme.SchemeWriteBatch
import dev.ayuislands.theme.SchemeWritePhase
import dev.ayuislands.theme.SchemeWriteProgress
import javax.swing.SwingUtilities

/**
 * Keeps one exact native batch alive until outer acceptance or guarded restoration.
 * Callers acknowledge receipts before apply and after every restoration preparation.
 * The restart adapter separately proves native binding before constructing a recovered rollback batch.
 */
internal class StudioSchemeWrite private constructor(
    private val identity: SchemeReceiptIdentity,
    private val batch: SchemeWriteBatch,
    private val captures: List<SchemePersistenceResult.Ready>,
    private val tokens: StudioNativeTokens,
    recovered: Map<SchemeEntry, List<PortableReceipt>>? = null,
) {
    private val originals =
        recovered?.mapValues { (_, receipts) -> StudioList(receipts.filter(StudioSchemeReceipts::isOriginal)) }
            ?: captures.associate { capture ->
                capture.record.entry to StudioSchemeReceipts.encode(identity, capture.record, capture, tokens)
            }
    private val retained = recovered?.mapValuesTo(mutableMapOf()) { StudioList(it.value) } ?: originals.toMutableMap()
    private val restorations = StudioSchemeReceipts.Restoration(identity.process, tokens)
    private val restorationProgress = mutableMapOf<Pair<SchemeEntry, SchemeComponent>, SchemeWriteProgress>()
    private val capturedDeletions = mutableSetOf<SchemeEntry>()
    private var seedCount = 0
    private var phase = if (recovered == null) Phase.PREPARED else Phase.APPLIED
    private var isRestorePrepared = false

    val receipts: StudioList<PortableReceipt>
        get() {
            refreshReceipts()
            return StudioList(retained.values.flatten())
        }

    fun apply() {
        checkEdt()
        check(phase == Phase.PREPARED) { "Editor batch application was already started" }
        check(
            captures.all(SchemePersistenceCapture::isCurrent),
        ) { "Editor persistence capture changed before application" }
        phase = Phase.APPLYING
        try {
            check(batch.apply()) { "Prepared editor batch changed during application" }
            phase = Phase.APPLIED
        } finally {
            refreshReceipts()
        }
    }

    fun prepareRestore(blockedBy: List<PortableReceipt>) {
        checkEdt()
        check(phase != Phase.ACCEPTED) { "Accepted editor fields cannot be restored" }
        if (phase == Phase.RESTORED) return
        phase = Phase.RESTORING
        isRestorePrepared = false
        try {
            batch.prepareRestore(blockedEntries(blockedBy))
            appendRestorations()
            appendSeeds()
            appendDeletions()
            isRestorePrepared = true
        } finally {
            refreshReceipts()
        }
    }

    fun restore(blockedBy: List<PortableReceipt>): StudioRestoreContinuation {
        checkEdt()
        check(phase != Phase.ACCEPTED) { "Accepted editor fields cannot be restored" }
        if (phase == Phase.RESTORED) return StudioRestoreContinuation.NONE
        check(isRestorePrepared) { "Editor restoration needs acknowledged preparation" }
        try {
            return if (batch.restore(blockedEntries(blockedBy))) {
                phase = Phase.RESTORED
                StudioRestoreContinuation.NONE
            } else {
                StudioRestoreContinuation.REQUIRED
            }
        } finally {
            isRestorePrepared = false
            refreshReceipts()
        }
    }

    /** The enclosing transaction calls this only after its durable commit decision. */
    fun accept() {
        checkEdt()
        if (phase == Phase.ACCEPTED) return
        check(phase == Phase.APPLIED) { "Editor batch application has not completed" }
        batch.accept()
        phase = Phase.ACCEPTED
    }

    private fun appendRestorations() {
        for ((entry, components) in batch.progress) {
            for ((component, progress) in components) {
                appendRestoration(entry, component, progress)
            }
        }
    }

    private fun appendRestoration(
        entry: SchemeEntry,
        component: SchemeComponent,
        progress: SchemeWriteProgress,
    ) {
        if (progress.phase == SchemeWritePhase.PREPARED ||
            progress.phase == SchemeWritePhase.RESTORED ||
            progress.phase == SchemeWritePhase.FOREIGN
        ) {
            return
        }
        val key = entry to component
        val previous = restorationProgress[key]
        if (previous?.phase == progress.phase &&
            previous.expected === progress.expected &&
            previous.destination === progress.destination
        ) {
            return
        }
        val original = originals.getValue(entry).single { it.identity.field.endsWith("/${component.name}") }
        if (isTerminal(original.status)) return
        val sequence = retained.getValue(entry).count { it.id.startsWith("${original.id}/restore/") }
        val persisted =
            if (component == SchemeComponent.VALUE) {
                projectCells(entry, progress.expected.cell, progress.destination.cell)
            } else {
                null
            }
        append(
            entry,
            restorations.encodeRestore(
                original,
                sequence,
                progress,
                persisted,
            ),
        )
        restorationProgress[key] = progress
    }

    private fun appendSeeds() {
        val seeds = batch.seeds
        val observations = batch.seedProgress
        check(seeds.size == observations.size && seedCount <= seeds.size) { "Editor seed evidence changed" }
        for (index in seedCount until seeds.size) {
            val seed = seeds[index]
            val original = originals.getValue(seed.entry).single { it.identity.field.endsWith("/VALUE") }
            val sequence = retained.getValue(seed.entry).count { it.id.startsWith("${original.id}/seed/") }
            val persisted = projectCells(seed.entry, seed.before, seed.planned)
            append(
                seed.entry,
                restorations.encodeSeed(
                    original,
                    sequence,
                    seed,
                    observations[index],
                    persisted,
                ),
            )
            seedCount++
        }
    }

    private fun appendDeletions() {
        val progress = batch.deletionProgress
        for ((entry, before) in batch.deletions) {
            if (entry in capturedDeletions) continue
            val original = originals.getValue(entry).single { it.identity.field.endsWith("/VALUE") }
            val destination =
                batch.records
                    .single { it.entry == entry }
                    .before.cell
            val persisted = projectCells(entry, before, destination)
            val sequence = retained.getValue(entry).count { it.id.startsWith("${original.id}/deletion") }
            append(
                entry,
                restorations.encodeDeletion(
                    original,
                    before to destination,
                    progress.getValue(entry),
                    persisted,
                    sequence,
                ),
            )
            capturedDeletions += entry
        }
    }

    private fun projectCells(
        entry: SchemeEntry,
        before: SchemeCellFacts,
        after: SchemeCellFacts,
    ): Pair<SchemeStoredCell, SchemeStoredCell> {
        val capture = captures.single { it.record.entry == entry }
        return checkNotNull(SchemePersistenceCapture.projectCell(capture, before)) {
            "Editor rollback source projection is unavailable"
        } to
            checkNotNull(SchemePersistenceCapture.projectCell(capture, after)) {
                "Editor rollback destination projection is unavailable"
            }
    }

    private fun append(
        entry: SchemeEntry,
        receipt: PortableReceipt,
    ) {
        retained[entry] = StudioList(retained.getValue(entry) + receipt)
    }

    private fun blockedEntries(newer: List<PortableReceipt>): Set<SchemeEntry> {
        val fields: Set<String> =
            newer
                .filter { it.identity.resource == identity.resource && !isTerminal(it.status) }
                .mapTo(mutableSetOf()) { it.identity.field.substringBeforeLast('/') }
        return originals
            .filterValues { receipts ->
                receipts
                    .first()
                    .identity.field
                    .substringBeforeLast('/') in fields
            }.keys
    }

    private fun refreshReceipts() {
        if (phase == Phase.ACCEPTED) return
        val progress = batch.progress
        for ((entry, receipts) in retained) {
            retained[entry] = StudioSchemeReceipts.refresh(receipts, progress.getValue(entry))
        }
    }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Studio editor writes must run on EDT" }
    }

    private enum class Phase { PREPARED, APPLYING, APPLIED, RESTORING, RESTORED, ACCEPTED }

    companion object {
        fun recover(
            identity: SchemeReceiptIdentity,
            batch: SchemeWriteBatch,
            receipts: Map<SchemeEntry, List<PortableReceipt>>,
            captures: List<SchemePersistenceResult.Ready>,
            tokens: StudioNativeTokens,
        ): StudioSchemeWrite {
            check(SwingUtilities.isEventDispatchThread()) { "Studio editor recovery must run on EDT" }
            val byEntry = captures.associateBy { it.record.entry }
            check(
                byEntry.size == batch.records.size &&
                    batch.records.all { entryRecord ->
                        val capture = byEntry[entryRecord.entry]
                        capture != null && SchemeAdmission.matches(capture.record.before, entryRecord.planned)
                    },
            ) {
                "Recovery capture belongs to another native observation"
            }
            check(receipts.keys == byEntry.keys) { "Recovery receipts belong to another native batch" }
            return StudioSchemeWrite(identity, batch, captures, tokens, receipts)
        }

        fun capture(
            identity: SchemeReceiptIdentity,
            target: SchemeTarget,
            batch: SchemeWriteBatch,
            tokens: StudioNativeTokens,
            resource: AyuSchemeResource? = null,
        ): StudioSchemeWrite {
            check(SwingUtilities.isEventDispatchThread()) { "Studio editor capture must run on EDT" }
            check(
                batch.progress.values.all { components ->
                    components.values.all {
                        it.phase ==
                            SchemeWritePhase.PREPARED
                    }
                },
            ) {
                "Editor receipts require an unused prepared batch"
            }
            val captures =
                batch.records.map { record ->
                    when (val capture = SchemePersistenceCapture.capture(target, record, resource)) {
                        is SchemePersistenceResult.Ready -> capture
                        is SchemePersistenceResult.Unavailable -> error(capture.reason)
                    }
                }
            return StudioSchemeWrite(identity, batch, captures, tokens)
        }
    }
}
