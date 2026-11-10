package dev.ayuislands.studio.session

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.font.FontSchemeLookup
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioProcessRelation
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.theme.AyuEditorSchemeBinder
import dev.ayuislands.theme.AyuSchemeResource
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.SchemeAdmissionFacts
import dev.ayuislands.theme.SchemeCellFacts
import dev.ayuislands.theme.SchemeComponent
import dev.ayuislands.theme.SchemeEntryRecord
import dev.ayuislands.theme.SchemePersistenceCapture
import dev.ayuislands.theme.SchemePersistenceLoad
import dev.ayuislands.theme.SchemePersistenceResult
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.theme.SchemeTarget
import dev.ayuislands.theme.capturePlannedCell
import dev.ayuislands.theme.captureSchemeCell
import javax.swing.SwingUtilities

/** Resumes acknowledged native rollback after process replacement; accepted cleanup never reapplies effects. */
internal class StudioSchemeRecovery private constructor(
    private val identity: SchemeReceiptIdentity,
    receipts: List<PortableReceipt>,
    private val currentProcess: StudioProcess?,
) {
    private val records = StudioSchemeRecoveryRecord.decode(identity, receipts)
    private val retained = receipts.toMutableList()
    private var write: StudioSchemeWrite? = null
    private var activeRecords: List<StudioSchemeRecoveryRecord> = emptyList()
    private var isWritePrepared = false
    private var boundScheme: AbstractColorsScheme? = null
    private var latestCensus: FontSchemeCensus? = null
    private var isPrepared = false
    private var isAccepted = false

    val receipts: StudioList<PortableReceipt> get() = StudioList(retained)

    fun prepareRestore(
        census: FontSchemeCensus,
        blockedBy: List<PortableReceipt>,
    ) {
        checkMutable()
        isWritePrepared = false
        isPrepared = true
        if (retained.all { StudioNativeEvidence.isTerminal(it.status) }) return
        val scheme = bind(census) ?: return unavailable()
        val current = write ?: prepareWrite(scheme, blockedBy)?.also { write = it } ?: return
        if (isWriteBlocked(blockedBy)) return
        try {
            current.prepareRestore(blockedBy)
            isWritePrepared = true
        } finally {
            retain(current.receipts)
        }
    }

    fun restore(
        census: FontSchemeCensus,
        blockedBy: List<PortableReceipt>,
    ): StudioRestoreContinuation {
        checkMutable()
        if (retained.all { StudioNativeEvidence.isTerminal(it.status) }) return StudioRestoreContinuation.NONE
        check(isPrepared) { "Native recovery needs acknowledged preparation" }
        isPrepared = false
        if (bind(census) == null) {
            unavailable()
            return StudioRestoreContinuation.NONE
        }
        if (!isWritePrepared || isWriteBlocked(blockedBy)) return StudioRestoreContinuation.NONE
        isWritePrepared = false
        val current = checkNotNull(write)
        return try {
            current.restore(blockedBy).also { continuation ->
                if (continuation == StudioRestoreContinuation.NONE) {
                    write = null
                    activeRecords = emptyList()
                }
            }
        } finally {
            retain(current.receipts)
        }
    }

    fun accept() {
        check(SwingUtilities.isEventDispatchThread()) { "Native recovery cleanup must run on EDT" }
        isAccepted = true
        isWritePrepared = false
        write = null
        activeRecords = emptyList()
    }

    private fun prepareWrite(
        scheme: AbstractColorsScheme,
        blockedBy: List<PortableReceipt>,
    ): StudioSchemeWrite? {
        val resource = resource(scheme)
        val target = checkNotNull(SchemeTarget.capture(SchemeSource.Named(scheme.name)) { selected() })
        val captured =
            records
                .filterNot(::isTerminal)
                .mapNotNull { record ->
                    if (isBlocked(record, blockedBy)) {
                        mark(record, RestoreStatus.UNAVAILABLE)
                        null
                    } else {
                        captureRecord(target, record, resource)?.let { record to it }
                    }
                }.toMap()
        if (captured.isEmpty()) return null
        val batch = EditorSchemeOverrides.checkpoints.prepareRecovery(target, captured.values.map { it.first })
        if (batch == null) {
            captured.keys.forEach { mark(it, RestoreStatus.UNAVAILABLE) }
            return null
        }
        val byPath = retained.groupBy { it.identity.field.substringBeforeLast('/') }
        val fields = captured.map { (source, evidence) -> evidence.first.entry to byPath.getValue(source.path) }.toMap()
        activeRecords = captured.keys.toList()
        return StudioSchemeWrite.recover(
            identity.copy(process = checkNotNull(currentProcess)),
            batch,
            fields,
            captured.values.map { it.second },
            StudioNativeTokens("${identity.ownerToken}/recovery/${retained.size}"),
        )
    }

    private fun captureRecord(
        target: SchemeTarget,
        record: StudioSchemeRecoveryRecord,
        resource: AyuSchemeResource?,
    ): Pair<SchemeEntryRecord, SchemePersistenceResult.Ready>? {
        val entry = entry(record.path)
        val recovered = recoveryRecord(target.scheme, record, entry, resource) ?: return null
        val observed = recovered.planned
        val observation = SchemeEntryRecord(entry, observed, observed, observed, null)
        val capture = SchemePersistenceCapture.capture(target, observation, resource)
        if (capture !is SchemePersistenceResult.Ready) {
            mark(record, RestoreStatus.UNAVAILABLE)
            return null
        }
        return recovered to capture
    }

    private fun isWriteBlocked(blockedBy: List<PortableReceipt>): Boolean {
        if (activeRecords.none { isBlocked(it, blockedBy) }) return false
        activeRecords.forEach { mark(it, RestoreStatus.UNAVAILABLE) }
        return true
    }

    private fun recoveryRecord(
        scheme: AbstractColorsScheme,
        record: StudioSchemeRecoveryRecord,
        entry: SchemeEntry,
        resource: AyuSchemeResource?,
    ): SchemeEntryRecord? {
        val cell = captureSchemeCell(scheme, entry)
        val metadata = scheme.metaProperties[entry.metadataKey]
        if (resource?.isCurrent() == false || cell == null) {
            mark(record, RestoreStatus.UNAVAILABLE)
            return null
        }
        if (metadata != null && metadata !is String) {
            mark(record, RestoreStatus.UNAVAILABLE)
            return null
        }
        val acceptedMetadata =
            setOf(record.before.metadata, record.planned.metadata) + listOfNotNull(record.releasedMetadata)
        if (metadata !in acceptedMetadata) {
            mark(record, RestoreStatus.FOREIGN)
            return null
        }
        val observed =
            SchemeAdmissionFacts(cell, null, scheme.metaProperties, metadata != null, metadata, scheme.parentScheme)
        return restoreRecord(record, entry, resource, observed)
    }

    private fun restoreRecord(
        record: StudioSchemeRecoveryRecord,
        entry: SchemeEntry,
        resource: AyuSchemeResource?,
        observed: SchemeAdmissionFacts,
    ): SchemeEntryRecord? {
        val before = SchemePersistenceLoad.value(entry, record.before.cell, resource)
        val cell = observed.cell
        val wasRestored = SchemePersistenceLoad.matches(before, cell)
        val isOwned =
            (listOf(record.planned.cell) + record.intermediate).any {
                SchemePersistenceLoad.matches(SchemePersistenceLoad.value(entry, it, resource), cell)
            }
        if (!wasRestored && !isOwned && record.hasUnqualifiedProgress) {
            mark(record, RestoreStatus.UNAVAILABLE)
            return null
        }
        val isForeign = !wasRestored && !isOwned
        if (isForeign) mark(record, RestoreStatus.FOREIGN, SchemeComponent.VALUE)
        val destination =
            when {
                wasRestored || isForeign -> cell
                before != null -> capturePlannedCell(before)
                entry is SchemeEntry.ColorEntry -> SchemeCellFacts.Color(null)
                else -> SchemeCellFacts.Attributes(null)
            }
        val restoredMetadata =
            if (isForeign) record.releasedMetadata ?: record.before.metadata else record.before.metadata
        val restored = captureRestoration(observed, destination, restoredMetadata)
        val released = record.releasedMetadata?.let { captureRestoration(observed, destination, it) }
        return SchemeEntryRecord(entry, restored, observed, restored, released)
    }

    private fun captureRestoration(
        observed: SchemeAdmissionFacts,
        destination: SchemeCellFacts,
        metadata: String?,
    ): SchemeAdmissionFacts =
        SchemeAdmissionFacts(
            destination,
            null,
            observed.metadataTable,
            metadata != null,
            if (observed.metadataValue == metadata) observed.metadataValue else metadata,
            observed.parent,
        )

    private fun retain(updated: List<PortableReceipt>) {
        val byId = updated.associateBy { it.id }
        val existing = retained.mapTo(mutableSetOf()) { it.id }
        retained.replaceAll { byId[it.id] ?: it }
        retained += updated.filter { it.id !in existing }
    }

    private fun bind(census: FontSchemeCensus): AbstractColorsScheme? {
        latestCensus = census
        val found =
            (census.lookup(identity.resource) as? FontSchemeLookup.Unique)?.scheme as? AbstractColorsScheme
                ?: return null
        val bound = boundScheme
        if (bound != null) return found.takeIf { it === bound }
        if (retained.filterNot { StudioNativeEvidence.isTerminal(it.status) }.any {
                StudioProcessPolicy.compare(StudioNativeEvidence.readProcess(it.before), currentProcess) !=
                    StudioProcessRelation.DIFFERENT
            }
        ) {
            return null
        }
        boundScheme = found
        return found
    }

    private fun selected(): AbstractColorsScheme? =
        (latestCensus?.lookup(identity.resource) as? FontSchemeLookup.Unique)
            ?.scheme
            ?.takeIf { it === boundScheme } as? AbstractColorsScheme

    private fun resource(scheme: AbstractColorsScheme): AyuSchemeResource? {
        val original = scheme.original ?: return null
        val variant =
            AyuVariant.entries.singleOrNull { AyuEditorSchemeBinder.targetSchemeName(it) == original.name }
                ?: return null
        return checkNotNull(AyuSchemeResource.capture(variant, original)) { "Canonical native resource changed" }
    }

    private fun entry(path: String): SchemeEntry =
        when {
            path.startsWith(
                "editor/color/",
            ) -> SchemeEntry.ColorEntry(ColorKey.find(path.removePrefix("editor/color/")))

            else -> SchemeEntry.AttributesEntry(TextAttributesKey.find(path.removePrefix("editor/attributes/")))
        }

    private fun isBlocked(
        record: StudioSchemeRecoveryRecord,
        blockedBy: List<PortableReceipt>,
    ): Boolean =
        blockedBy.any {
            it.identity.resource == identity.resource &&
                it.identity.field.startsWith("${record.path}/") &&
                !StudioNativeEvidence.isTerminal(it.status)
        }

    private fun isTerminal(record: StudioSchemeRecoveryRecord): Boolean =
        retained
            .filter {
                it.identity.field.startsWith(
                    "${record.path}/",
                )
            }.all { StudioNativeEvidence.isTerminal(it.status) }

    private fun mark(
        record: StudioSchemeRecoveryRecord,
        status: RestoreStatus,
        component: SchemeComponent? = null,
    ) {
        val affectedField = component?.let { "${record.path}/${it.name}" }
        retained.replaceAll {
            val matches =
                if (affectedField == null) {
                    it.identity.field.startsWith("${record.path}/")
                } else {
                    it.identity.field == affectedField
                }
            if (matches && !StudioNativeEvidence.isTerminal(it.status)) {
                it.copy(status = status)
            } else {
                it
            }
        }
    }

    private fun unavailable() = records.filterNot(::isTerminal).forEach { mark(it, RestoreStatus.UNAVAILABLE) }

    private fun checkMutable() {
        check(SwingUtilities.isEventDispatchThread()) { "Native recovery must run on EDT" }
        check(!isAccepted) { "Accepted native fields cannot be restored" }
    }

    companion object {
        fun recover(
            identity: SchemeReceiptIdentity,
            receipts: List<PortableReceipt>,
            currentProcess: StudioProcess?,
        ): StudioSchemeRecovery = StudioSchemeRecovery(identity, receipts, currentProcess)
    }
}
