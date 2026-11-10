package dev.ayuislands.studio.session

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontApplyOrigin
import dev.ayuislands.font.FontIdentityCheckpoint
import dev.ayuislands.font.FontIdentityPlan
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.font.FontSurface
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.StudioFontOwnership
import dev.ayuislands.theme.AyuEditorSchemeBinder
import dev.ayuislands.theme.AyuSchemeResource
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeCheckpoints.EnabledCheckpoint
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.theme.SchemeTarget
import dev.ayuislands.theme.SchemeWriteBatch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Retains native scheme checkpoints under the outer transaction's receipt and commit authority. */
internal class StudioSchemeOperation private constructor(
    private val host: StudioSchemeHost,
    private val nativeContext: CoroutineContext,
    private val owner: EffectOwner,
    private val token: String,
    private val process: StudioProcess?,
    private val isRecovery: Boolean,
) : StudioEffectOperation {
    private val writes = mutableListOf<StudioSchemeWrite>()
    private val recoveries = mutableListOf<StudioSchemeRecovery>()
    private val adoptions = mutableListOf<FontIdentityCheckpoint>()
    private val targets = mutableListOf<CapturedScheme>()
    private val tokens = StudioNativeTokens("$token/$owner")
    private var reservedKeys: Set<String> = emptySet()
    private var hasStarted = false

    /** Reacquires the persisted operation without replaying native Apply. */
    constructor(
        schemeHost: StudioSchemeHost,
        context: CoroutineContext,
        effectOwner: EffectOwner,
        input: StudioEffectInput,
        savedReceipts: List<PortableReceipt>,
        facts: StudioNativeFacts,
    ) : this(schemeHost, context, effectOwner, input.candidate.header.transactionId, facts.currentProcess, true) {
        require(savedReceipts.all { it.owner == owner && it.identity.ownerToken == token })
        require(savedReceipts.all { FontIdentityRules.canonicalId(it.identity.resource) != null })
        val metadata = savedReceipts.filter { it.identity.field == StudioSchemeIdentityReceipt.FIELD }
        require(metadata.map { it.identity.resource }.distinct().size == metadata.size)
        metadata.forEach {
            adoptions += FontIdentityCheckpoint.recover(census(), StudioSchemeIdentityReceipt.decode(owner, token, it))
        }
        val fields = savedReceipts.filter { it.identity.field != StudioSchemeIdentityReceipt.FIELD }
        fields.groupBy { it.identity.resource }.forEach { (id, records) ->
            val origin = StudioNativeEvidence.readProcess(records.first(StudioSchemeReceipts::isOriginal).before)
            recoveries +=
                StudioSchemeRecovery.recover(SchemeReceiptIdentity(owner, id, token, origin), records, process)
        }
    }

    override val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                adoptions.map { StudioSchemeIdentityReceipt.encode(owner, token, it.record) } + nativeReceipts(),
            )

    override val runtimeChanges: StudioList<RuntimeFieldChange> = StudioList(emptyList())

    /** Captures one producer's explicit values without adopting an identity or applying native writes. */
    fun capture(
        scheme: EditorColorsScheme,
        requested: Map<SchemeEntry, SchemeValue>,
        managed: Set<SchemeEntry>,
        source: SchemeSource = SchemeSource.Named(scheme.name),
        schemeOwner: EditorSchemeOwner = nativeOwner(),
    ) {
        check(!isRecovery && !hasStarted)
        val target = captureTarget(scheme, source)
        val batch =
            EditorSchemeOverrides.checkpoints.prepareOverrides(target, schemeOwner, requested, managed).batch
        retain(target, batch)
    }

    /** Element rearming and final values share one native before-image and one persistent write per entry. */
    fun captureElement(
        scheme: EditorColorsScheme,
        enabled: EnabledCheckpoint,
        requested: Map<SchemeEntry, SchemeValue>?,
        source: SchemeSource = SchemeSource.Named(scheme.name),
    ) {
        check(!isRecovery && !hasStarted)
        val target = captureTarget(scheme, source)
        retain(target, EditorSchemeOverrides.checkpoints.prepareElementOverrides(target, enabled, requested))
    }

    private fun captureTarget(
        scheme: EditorColorsScheme,
        source: SchemeSource,
    ): SchemeTarget =
        checkNotNull(
            SchemeTarget.capture(source) {
                val observed = host.observe()
                when (it) {
                    SchemeSource.Active -> observed.active
                    is SchemeSource.Named -> observed.schemes.singleOrNull { candidate -> candidate === scheme }
                }
            },
        ) { "Native scheme is unavailable" }

    private fun retain(
        target: SchemeTarget,
        batch: SchemeWriteBatch,
    ) {
        if (batch.records.isEmpty()) return
        val entries = batch.records.mapTo(mutableSetOf()) { it.entry }
        val previous = targets.filter { it.target.scheme === target.scheme }
        check(previous.none { it.entries.any(entries::contains) }) { "Native scheme entries overlap" }
        val id = previous.firstOrNull()?.id ?: captureIdentity(target.scheme)
        val write =
            StudioSchemeWrite.capture(
                SchemeReceiptIdentity(owner, id, token, checkNotNull(process)),
                target,
                batch,
                tokens,
                resource(target.scheme),
            )
        writes += write
        targets += CapturedScheme(target, id, entries)
    }

    override suspend fun apply() =
        withContext(nativeContext) {
            check(!isRecovery && !hasStarted) { "Native scheme recovery cannot replay Apply" }
            hasStarted = true
            requireTargets()
            adoptions.forEach { it.apply(census(), reservedKeys) }
            for (write in writes) {
                requireTargets()
                write.apply()
            }
            host.refresh()
            requireTargets()
        }

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) =
        withContext(nativeContext) {
            writes.asReversed().forEach { it.prepareRestore(blockedBy) }
            recoveries.asReversed().forEach { it.prepareRestore(census(), blockedBy) }
        }

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation =
        withContext(nativeContext) {
            var continuation = StudioRestoreContinuation.NONE
            for (write in writes.asReversed()) {
                if (write.restore(blockedBy) == StudioRestoreContinuation.REQUIRED) {
                    continuation = StudioRestoreContinuation.REQUIRED
                }
            }
            for (recovery in recoveries.asReversed()) {
                if (recovery.restore(census(), blockedBy) == StudioRestoreContinuation.REQUIRED) {
                    continuation = StudioRestoreContinuation.REQUIRED
                }
            }
            for (adoption in adoptions.asReversed()) {
                val id = adoption.record.id
                val dependents = nativeReceipts() + blockedBy
                val settled = dependents.filter { it.identity.resource == id }.all { isTerminal(it) }
                adoption.restore(census(), settled)
            }
            host.refresh()
            continuation
        }

    override suspend fun accept() =
        withContext(nativeContext) {
            writes.forEach(StudioSchemeWrite::accept)
            recoveries.forEach(StudioSchemeRecovery::accept)
            host.refresh()
        }

    private fun captureIdentity(scheme: AbstractColorsScheme): String {
        val observed = census()
        val existing = observed.planIdentity(scheme, null, "", reservedKeys)
        if (existing is FontIdentityPlan.Existing) return existing.id
        check(existing == FontIdentityPlan.Missing) { "Native scheme identity is unconfirmed" }
        val pendingKeys =
            reservedKeys +
                adoptions.flatMap { adoption ->
                    FontSurface.entries.map { FontIdentityRules.key(adoption.record.id, it) }
                }
        var planned: FontIdentityPlan
        do {
            planned = observed.planIdentity(scheme, FontApplyOrigin.EXPLICIT, host.newIdentity(), pendingKeys)
        } while (planned == FontIdentityPlan.Collision)
        val adopted = checkNotNull(planned as? FontIdentityPlan.Adopt) { "Native scheme identity is unconfirmed" }
        adoptions += FontIdentityCheckpoint(scheme, adopted)
        return adopted.id
    }

    private fun nativeOwner(): EditorSchemeOwner =
        when (owner) {
            EffectOwner.SYNTAX -> EditorSchemeOwner.Syntax
            EffectOwner.VCS -> EditorSchemeOwner.Vcs
            else -> error("Unsupported native scheme participant")
        }

    private fun resource(scheme: AbstractColorsScheme): AyuSchemeResource? {
        val original = scheme.original ?: return null
        val variant =
            AyuVariant.entries.singleOrNull { AyuEditorSchemeBinder.targetSchemeName(it) == original.name }
                ?: return null
        return checkNotNull(AyuSchemeResource.capture(variant, original)) { "Canonical native resource changed" }
    }

    private fun nativeReceipts(): List<PortableReceipt> =
        writes.flatMap { it.receipts } + recoveries.flatMap { it.receipts }

    private fun census(): FontSchemeCensus = FontSchemeCensus(host.observe().schemes.toTypedArray())

    private fun requireTargets() {
        check(targets.all { it.target.isSelected() }) { "Native scheme selection changed" }
    }

    private class CapturedScheme(
        val target: SchemeTarget,
        val id: String,
        val entries: Set<SchemeEntry>,
    )

    private fun isTerminal(receipt: PortableReceipt): Boolean = StudioNativeEvidence.isTerminal(receipt.status)

    companion object {
        fun prepare(
            host: StudioSchemeHost,
            nativeContext: CoroutineContext,
            owner: EffectOwner,
            input: StudioEffectInput,
            facts: StudioNativeFacts,
        ): StudioSchemeOperation =
            StudioSchemeOperation(
                host,
                nativeContext,
                owner,
                input.candidate.header.transactionId,
                checkNotNull(facts.currentProcess) { "Native process observation is unavailable" },
                false,
            ).apply {
                reservedKeys = StudioFontOwnership(input.candidate.runtime).keys
            }
    }
}
