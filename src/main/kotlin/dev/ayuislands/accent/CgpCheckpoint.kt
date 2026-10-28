package dev.ayuislands.accent

import dev.ayuislands.integration.IntegrationOwnership
import dev.ayuislands.integration.SettingCheckpoint
import dev.ayuislands.integration.SettingsCheckpoint
import dev.ayuislands.integration.captureFailure
import dev.ayuislands.settings.AyuIslandsState

/** Restores the previous viewport without treating it as the persisted native baseline. */
internal class CgpCheckpoint(
    private val state: AyuIslandsState,
    private val isCurrent: () -> Boolean,
    private val isTarget: (CgpAccess) -> Boolean,
    private val retainReceipt: (CgpCheckpoint) -> Unit,
) : IntegrationCheckpoint() {
    private val originalBase =
        state.cgpBaseViewport().takeUnless {
            IntegrationOwnership.fromName(state.cgpOwnership) == IntegrationOwnership.UNOWNED
        }
    override val metadata =
        SettingsCheckpoint(
            state::cgpOwnership,
            SettingCheckpoint(state::isCgpOwnershipMigrated),
            SettingCheckpoint(state::cgpBaseColor),
            SettingCheckpoint(state::cgpBaseBorder),
            SettingCheckpoint(state::cgpBaseThickness),
            SettingCheckpoint(state::cgpAppliedColor),
            SettingCheckpoint(state::cgpAppliedBorder),
            SettingCheckpoint(state::cgpAppliedThickness),
        )
    private var access: CgpAccess? = null
    private lateinit var original: CgpViewport
    private lateinit var expected: CgpViewport
    private val beforeWrite = mutableMapOf<CgpPart, CgpViewport>()
    private val foreign = mutableSetOf<CgpPart>()
    override val currentState: Boolean
        get() = isCurrent()

    override val currentTarget: Boolean
        get() = access?.let(isTarget) ?: true

    override val foreignNative: Boolean
        get() = foreign.isNotEmpty()

    fun write(
        resolved: CgpAccess,
        part: CgpPart,
        target: CgpViewport,
    ) {
        if (access == null) {
            access = resolved
            original = resolved.readViewport()
            expected = original
        }
        check(isCurrent() && isTarget(resolved)) { "CodeGlance Pro target changed during accent application" }
        observe(part)
        if (part in foreign) return
        beforeWrite[part] = expected
        expected = expected.replacing(part, target)
        mutate({ resolved.writePart(part, target) }) { observe(part) }
    }

    private fun observe(part: CgpPart) {
        val observed = checkNotNull(access).readViewport()
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
        val failure = resolved?.let(::restoreViewport)
        if (failure != null) throw captureRecovery(resolved, failure)
    }

    private fun restoreViewport(resolved: CgpAccess): Throwable? {
        var failure: Throwable? = null
        for (part in CgpPart.entries) {
            val error =
                captureFailure {
                    observe(part)
                    if (part !in foreign && !expected.matches(part, original)) write(resolved, part, original)
                }
            if (error != null) failure = combineUiFailures(failure, error)
        }
        return failure
    }

    private fun captureRecovery(
        resolved: CgpAccess?,
        failure: Throwable,
    ): Throwable {
        val captureError =
            captureFailure {
                if (resolved != null) {
                    val base = originalBase ?: original
                    state.cgpBaseColor = base.color
                    state.cgpBaseBorder = base.border
                    state.cgpBaseThickness = base.thickness
                    val current = resolved.readViewport()
                    state.cgpAppliedColor = current.color
                    state.cgpAppliedBorder = current.border
                    state.cgpAppliedThickness = current.thickness
                    state.cgpOwnership = IntegrationOwnership.RECOVERY_PENDING.name
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

private fun CgpViewport.matches(
    part: CgpPart,
    other: CgpViewport,
): Boolean =
    when (part) {
        CgpPart.COLOR -> color == other.color
        CgpPart.BORDER -> border == other.border
        CgpPart.THICKNESS -> thickness == other.thickness
    }

private fun CgpViewport.replacing(
    part: CgpPart,
    other: CgpViewport,
): CgpViewport =
    when (part) {
        CgpPart.COLOR -> copy(color = other.color)
        CgpPart.BORDER -> copy(border = other.border)
        CgpPart.THICKNESS -> copy(thickness = other.thickness)
    }
