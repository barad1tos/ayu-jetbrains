package dev.ayuislands.studio.session

import com.intellij.ui.JBColor
import dev.ayuislands.accent.AccentUiInput
import dev.ayuislands.accent.AccentUiKeys
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.UiDefaultsCheckpoint
import dev.ayuislands.accent.UiDefaultsOwnership
import dev.ayuislands.accent.UiDefaultsOwnership.PreparedOverride
import dev.ayuislands.accent.UiDefaultsTarget
import dev.ayuislands.accent.UiOverrideValue
import dev.ayuislands.accent.accentUiPalette
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.awt.Color

/** Retains the existing native UI-defaults owner until the outer Accent transaction settles. */
internal class StudioAccentUiOperation private constructor(
    private val defaultsWrites: List<PreparedOverride>,
    private val nativeDispatcher: CoroutineDispatcher,
    prepared: List<PortableReceipt>,
) : StudioOwnerOperation {
    override var receipts: StudioList<PortableReceipt> = StudioList(prepared)
        private set

    override suspend fun apply(writes: StudioNativeWrite): Boolean =
        withContext(nativeDispatcher) {
            try {
                for (prepared in defaultsWrites) {
                    val qualified =
                        writes.execute(nativeDispatcher) {
                            check(prepared.apply()) { "Prepared Accent default changed: ${prepared.observation.key}" }
                        }
                    if (!qualified) return@withContext false
                }
                true
            } finally {
                observeReceipts()
            }
        }

    override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation =
        withContext(nativeDispatcher) {
            val blockedKeys =
                blocked
                    .filter { it.identity.resource == UI_DEFAULTS && !StudioNativeEvidence.isTerminal(it.status) }
                    .mapTo(mutableSetOf()) { it.identity.field }
            try {
                var failure: Throwable? = null
                for (index in defaultsWrites.indices.reversed()) {
                    if (StudioNativeEvidence.isTerminal(receipts[index].status)) continue
                    val prepared = defaultsWrites[index]
                    val problem =
                        runCatchingPreservingCancellation {
                            prepared.restore(prepared.observation.key in blockedKeys)
                        }.exceptionOrNull()
                    if (problem != null) failure = combineUiFailures(failure, problem)
                }
                failure?.let { throw it }
                RestoreContinuation.COMPLETE
            } finally {
                observeReceipts()
            }
        }

    override suspend fun accept() =
        withContext(nativeDispatcher) {
            defaultsWrites.forEach(PreparedOverride::accept)
        }

    private fun observeReceipts() {
        receipts =
            StudioList(
                receipts.mapIndexed { index, receipt ->
                    if (StudioNativeEvidence.isTerminal(receipt.status)) return@mapIndexed receipt
                    val prepared = defaultsWrites[index]
                    val status =
                        when (prepared.observe()) {
                            AccentWriteState.PREPARED -> RestoreStatus.PENDING
                            AccentWriteState.OWNED -> RestoreStatus.OWNED
                            AccentWriteState.RELINQUISHED -> {
                                prepared.relinquish()
                                RestoreStatus.FOREIGN
                            }
                            AccentWriteState.RESTORED -> RestoreStatus.RESTORED
                        }
                    receipt.copy(status = status)
                },
            )
    }

    companion object {
        private const val UI_DEFAULTS = "accent-ui-defaults"

        suspend fun capture(
            input: AccentUiInput,
            process: StudioProcess,
            transactionId: String,
            nativeDispatcher: CoroutineDispatcher,
            owner: UiDefaultsOwnership,
        ): StudioAccentUiOperation? =
            withContext(nativeDispatcher) {
                val target = UiDefaultsTarget()
                val palette = accentUiPalette(input)
                val writes =
                    AccentUiKeys.colors.sortedBy(palette::containsKey).mapNotNull { key ->
                        val color = palette[key]?.let { JBColor(Color(it, true), Color(it, true)) }
                        val requested = if (key in palette) UiOverrideValue(color) else null
                        owner.prepareOverride(key, requested, UiDefaultsCheckpoint(target))
                    }
                val ownerToken = "accent/$transactionId"
                val references = StudioNativeTokens(ownerToken)

                fun evidence(value: Any?) =
                    StudioNativeEvidence.envelope(
                        process,
                        StudioNativeEvidence.reference(value?.let(references::token)),
                    )
                val receipts =
                    writes.map { prepared ->
                        val field = prepared.observation
                        PortableReceipt(
                            "$ownerToken/$UI_DEFAULTS/${field.key}",
                            EffectOwner.ACCENT,
                            NativeFieldIdentity(UI_DEFAULTS, field.key, ownerToken),
                            evidence(field.before),
                            evidence(prepared.planned),
                            RestoreStatus.PENDING,
                        )
                    }
                if (writes.isEmpty()) null else StudioAccentUiOperation(writes, nativeDispatcher, receipts)
            }
    }
}
