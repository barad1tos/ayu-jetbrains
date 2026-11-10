package dev.ayuislands.studio.session

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.options.Scheme
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.font.FontSchemeLookup
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioProcessRelation
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.theme.AyuEditorSchemeBinder
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Retains the exact public selection and its pre-existing editable result before the first write. */
internal class StudioSchemeSelection private constructor(
    private val host: StudioThemeHost,
    private val before: EditorColorsScheme,
    private val target: EditorColorsScheme,
    private val planned: EditorColorsScheme,
    private var receipt: PortableReceipt,
    private val nativeContext: CoroutineContext,
) : StudioEffectOperation {
    private var isApplied = false
    private var isRestoring = false
    private var isAccepted = false
    override val receipts: StudioList<PortableReceipt>
        get() = StudioList(listOf(receipt))
    override val runtimeChanges = StudioList<RuntimeFieldChange>(emptyList())

    override suspend fun apply() =
        withContext(nativeContext) {
            check(!isAccepted && !isApplied && receipt.status == RestoreStatus.PENDING)
            check(host.observe().scheme === before && isTargetCurrent()) {
                "Native editor scheme changed after selection preparation"
            }
            try {
                host.selectScheme(target)
            } finally {
                if (host.observe().scheme === planned) {
                    isApplied = true
                    receipt = receipt.copy(status = RestoreStatus.OWNED)
                }
            }
            check(isApplied) { "Native editor scheme selection did not reach its captured target" }
        }

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) = Unit

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation =
        withContext(nativeContext) {
            check(!isAccepted) { "Accepted editor scheme selection cannot be rolled back" }
            if (StudioNativeEvidence.isTerminal(receipt.status)) return@withContext StudioRestoreContinuation.NONE
            val current = host.observe().scheme
            val status =
                when {
                    StudioSelectionReceipts.blockedBy(blockedBy) -> RestoreStatus.UNAVAILABLE
                    !isApplied -> if (current === before) RestoreStatus.RESTORED else RestoreStatus.FOREIGN
                    current !== (if (isRestoring) before else planned) -> RestoreStatus.FOREIGN
                    else -> restoreSelection()
                }
            receipt = receipt.copy(status = status)
            StudioRestoreContinuation.NONE
        }

    override suspend fun accept() {
        isAccepted = true
    }

    private fun restoreSelection(): RestoreStatus {
        try {
            host.selectScheme(before)
        } finally {
            if (host.observe().scheme === before) isRestoring = true
        }
        check(isRestoring) { "Native editor scheme restoration did not settle" }
        return RestoreStatus.RESTORED
    }

    private fun isTargetCurrent(): Boolean =
        host.scheme(target.name) === target && visibleSelection(host, target) === planned

    companion object {
        fun prepare(
            host: StudioThemeHost,
            input: StudioEffectInput,
            process: StudioProcess,
            variant: AyuVariant,
            nativeContext: CoroutineContext,
        ): StudioSchemeSelection? {
            val enabled =
                input.candidate.shared.system.records
                    .single { it.key.name == "syncEditorScheme" }
                    .value
            if (enabled != RawValue.BooleanValue(true)) return null
            val before = host.observe().scheme
            if (AyuEditorSchemeBinder.matchesVariant(before.name, variant) ||
                !AyuEditorSchemeBinder.canBind(before.name)
            ) {
                return null
            }
            val schemes = host.schemes()
            val target = host.scheme(AyuEditorSchemeBinder.targetSchemeName(variant)) ?: return null
            val planned = visibleSelection(host, target)
            val receipt =
                StudioSelectionReceipts.capture(
                    input,
                    process,
                    FontSchemeCensus(schemes.toTypedArray()),
                    before,
                    planned,
                )
            return StudioSchemeSelection(host, before, target, planned, receipt, nativeContext)
        }

        private fun visibleSelection(
            host: StudioThemeHost,
            target: EditorColorsScheme,
        ): EditorColorsScheme {
            val editable = host.scheme(Scheme.EDITABLE_COPY_PREFIX + target.name) as? AbstractColorsScheme
            return editable?.takeIf { it.original === target } ?: target
        }
    }
}

internal class RecoveredSchemeSelection(
    private val host: StudioThemeHost,
    captured: StudioList<PortableReceipt>,
    private val process: StudioProcess?,
    private val nativeContext: CoroutineContext,
) : StudioEffectOperation {
    override var receipts = captured
        private set
    override val runtimeChanges = StudioList<RuntimeFieldChange>(emptyList())

    init {
        require(captured.size <= 1) { "Repeated editor scheme selection receipt" }
        captured.forEach(StudioSelectionReceipts::decode)
    }

    override suspend fun apply(): Unit = error("Recovered editor scheme selection cannot replay application")

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) = Unit

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation =
        withContext(nativeContext) {
            val receipt = receipts.singleOrNull() ?: return@withContext StudioRestoreContinuation.NONE
            if (StudioNativeEvidence.isTerminal(receipt.status)) return@withContext StudioRestoreContinuation.NONE
            val evidence = StudioSelectionReceipts.decode(receipt)
            val status =
                if (StudioSelectionReceipts.blockedBy(blockedBy) ||
                    StudioProcessPolicy.compare(evidence.process, process) != StudioProcessRelation.DIFFERENT
                ) {
                    RestoreStatus.UNAVAILABLE
                } else {
                    restoreSelection(evidence)
                }
            receipts = StudioList(listOf(receipt.copy(status = status)))
            StudioRestoreContinuation.NONE
        }

    override suspend fun accept() = Unit

    private fun restoreSelection(evidence: StudioSelectionEvidence): RestoreStatus {
        val beforeId = evidence.beforeId ?: return RestoreStatus.UNAVAILABLE
        val plannedId = evidence.plannedId ?: return RestoreStatus.UNAVAILABLE
        val census = FontSchemeCensus(host.schemes().toTypedArray())
        val before = (census.lookup(beforeId) as? FontSchemeLookup.Unique)?.scheme ?: return RestoreStatus.UNAVAILABLE
        val planned = (census.lookup(plannedId) as? FontSchemeLookup.Unique)?.scheme ?: return RestoreStatus.UNAVAILABLE
        val current = host.observe().scheme
        if (current !== before && current !== planned) return RestoreStatus.FOREIGN
        host.selectScheme(before)
        check(host.observe().scheme === before) { "Recovered editor scheme restoration did not settle" }
        return RestoreStatus.RESTORED
    }
}
