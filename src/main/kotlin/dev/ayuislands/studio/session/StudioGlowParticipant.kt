package dev.ayuislands.studio.session

import com.intellij.openapi.application.EDT
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.glow.GlowRuntimeInput
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Joins requested Glow settings to confirmed native Accent without resolving or writing preferences. */
internal class StudioGlowParticipant(
    private val nativeContext: CoroutineContext = Dispatchers.EDT,
) : StudioEffectParticipant {
    override val owner: EffectOwner = EffectOwner.GLOW

    override suspend fun prepare(
        input: StudioEffectInput,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            val accent = checkNotNull(runtime.accent) { "Glow Accent handoff is unconfirmed" }
            val held = checkNotNull(runtime.glow) { "Glow manager holds are unavailable" }
            check(held.isActive) { "Glow manager holds are not active" }
            val current = checkNotNull(StudioTargets.current(input.candidate, input.target))
            val settings = StudioGlowProjection.settings(current.appearance)
            val external = StudioPluginsEditing.settings(input.candidate.shared.plugins).external
            val sources =
                held.entries.map { entry ->
                    val color =
                        if (accent.context == null) {
                            null
                        } else {
                            val projectAccent =
                                checkNotNull(accent.projects.singleOrNull { it.project === entry.manager.project }) {
                                    "Held Glow project has no unique confirmed Accent"
                                }
                            AccentHex.require(projectAccent.accent.hex)
                        }
                    StudioGlowInput(
                        entry,
                        GlowRuntimeInput(
                            settings,
                            external.enabled && external.features.glow,
                            color,
                        ),
                        accent.context,
                    )
                }
            val facts = runtime.native
            val references =
                StudioGlowReferences(
                    input.candidate.header.transactionId,
                    checkNotNull(facts.currentProcess) { "Glow process identity is unconfirmed" },
                )
            val entitlement = if (facts.isLicensed) LicenseEntitlement.LICENSED else LicenseEntitlement.UNLICENSED
            StudioGlowCapture(references, nativeContext).capture(
                input.glowSelection,
                sources,
                focus(sources, accent),
                entitlement,
            )
        }

    override suspend fun recover(
        input: StudioEffectInput,
        receipts: StudioList<PortableReceipt>,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation =
        LostGlowOperation(StudioGlowReceipts.recoverLost(receipts, runtime.native.currentProcess))

    private fun focus(
        sources: List<StudioGlowInput>,
        accent: StudioAccentResult,
    ): StudioGlowInput? {
        if (sources.isEmpty() || accent.context == null) return null
        val source =
            checkNotNull(sources.singleOrNull { it.owner.manager.project === accent.focusedProject }) {
                "Confirmed Glow focus owner is outside the held census"
            }
        return source.copy(
            candidate = source.candidate.copy(accent = AccentHex.require(checkNotNull(accent.focused).hex)),
        )
    }
}

/** Lost native handles permit only process-proven cleanup; no surface can be rediscovered or replayed. */
private class LostGlowOperation(
    override val receipts: StudioList<PortableReceipt>,
) : StudioEffectOperation {
    override val runtimeChanges: StudioList<RuntimeFieldChange> = StudioList(emptyList())

    override suspend fun apply(): Unit = error("Lost Glow handles cannot replay application")

    override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) = Unit

    override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation =
        StudioRestoreContinuation.NONE

    override suspend fun accept() {
        check(receipts.all { StudioNativeEvidence.isTerminal(it.status) }) { "Glow cleanup is still unresolved" }
    }
}
