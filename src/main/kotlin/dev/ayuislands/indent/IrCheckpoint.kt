package dev.ayuislands.indent

import dev.ayuislands.accent.IntegrationCheckpoint
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.integration.IntegrationOwnership
import dev.ayuislands.integration.SettingCheckpoint
import dev.ayuislands.integration.SettingsCheckpoint
import dev.ayuislands.integration.captureFailure
import dev.ayuislands.settings.AyuIslandsState

/** Owns one attempt's native fields separately from its persisted pre-Ayu baseline. */
internal class IrCheckpoint(
    private val state: AyuIslandsState,
    private val isCurrent: () -> Boolean,
    private val isTarget: (ResolvedIrState) -> Boolean,
    private val retainReceipt: (IrCheckpoint) -> Unit,
) : IntegrationCheckpoint() {
    private val originalBase =
        state.irBasePalette().takeUnless {
            IntegrationOwnership.fromName(state.irOwnership) == IntegrationOwnership.UNOWNED
        }
    override val metadata =
        SettingsCheckpoint(
            state::irOwnership,
            SettingCheckpoint(state::isIrOwnershipMigrated),
            SettingCheckpoint(state::irBaseType),
            SettingCheckpoint(state::irBasePalette),
            SettingCheckpoint(state::irBaseColorCount),
            SettingCheckpoint(state::irAppliedType),
            SettingCheckpoint(state::irAppliedPalette),
            SettingCheckpoint(state::irAppliedColorCount),
        )
    private var access: ResolvedIrState? = null
    private lateinit var original: CurrentIrPalette
    private lateinit var expected: CurrentIrPalette
    private val beforeWrite = mutableMapOf<IrPart, CurrentIrPalette>()
    private val foreign = mutableSetOf<IrPart>()
    private var refreshPending = false
    override val currentState: Boolean
        get() = isCurrent()

    override val currentTarget: Boolean
        get() = access?.let(isTarget) ?: true

    override val foreignNative: Boolean
        get() = foreign.isNotEmpty()

    fun write(
        resolved: ResolvedIrState,
        part: IrPart,
        target: CurrentIrPalette,
    ) {
        if (access == null) {
            access = resolved
            original = resolved.readPalette()
            expected = original
        }
        check(isCurrent() && isTarget(resolved)) { "Indent Rainbow target changed during accent application" }
        observe(part)
        if (part in foreign) return
        beforeWrite[part] = expected
        expected = expected.replacing(part, target)
        refreshPending = true
        mutate({ resolved.writePart(part, target) }) { observe(part) }
    }

    fun refreshed() {
        refreshPending = false
    }

    private fun observe(part: IrPart) {
        val observed = checkNotNull(access).readPalette()
        val previous = beforeWrite[part]
        when {
            observed.matches(part, expected) -> Unit
            previous != null && observed.matches(part, previous) -> expected = expected.replacing(part, previous)
            else -> foreign += part
        }
        beforeWrite.remove(part)
    }

    override fun restoreNative() {
        val resolved = access
        val failure = resolved?.let(::restorePalette)
        if (failure != null) throw captureRecovery(resolved, failure)
    }

    private fun restorePalette(resolved: ResolvedIrState): Throwable? {
        var failure: Throwable? = null
        for (part in IrPart.entries) {
            val error = captureFailure { restorePart(resolved, part) }
            if (error != null) failure = combineUiFailures(failure, error)
        }
        val refreshError = if (refreshPending) captureFailure(resolved::refresh) else null
        return refreshError?.let { combineUiFailures(failure, it) } ?: failure
    }

    private fun restorePart(
        resolved: ResolvedIrState,
        part: IrPart,
    ) {
        observe(part)
        if (part !in foreign && !expected.matches(part, original)) write(resolved, part, original)
    }

    private fun captureRecovery(
        resolved: ResolvedIrState?,
        failure: Throwable,
    ): Throwable {
        val captureError =
            captureFailure {
                if (resolved != null) {
                    val base = originalBase ?: original.palette
                    state.storeIrBase(base)
                    state.storeIrApplied(resolved.readPalette().palette)
                    state.irOwnership = IntegrationOwnership.RECOVERY_PENDING.name
                }
            }
        if (captureError != null) metadata.suspendIfOwned()
        captureMetadata()
        return captureError?.let { combineUiFailures(failure, it) } ?: failure
    }

    override fun retain() {
        if (hasPending) retainReceipt(this)
    }
}

private fun CurrentIrPalette.matches(
    part: IrPart,
    other: CurrentIrPalette,
): Boolean =
    when (part) {
        IrPart.TYPE -> typeValue === other.typeValue
        IrPart.PALETTE -> palette.palette == other.palette.palette
        IrPart.COUNT -> palette.colorCount == other.palette.colorCount
    }

private fun CurrentIrPalette.replacing(
    part: IrPart,
    other: CurrentIrPalette,
): CurrentIrPalette =
    when (part) {
        IrPart.TYPE -> copy(palette = palette.copy(type = other.palette.type), typeValue = other.typeValue)
        IrPart.PALETTE -> copy(palette = palette.copy(palette = other.palette.palette))
        IrPart.COUNT -> copy(palette = palette.copy(colorCount = other.palette.colorCount))
    }
