package dev.ayuislands.font

import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme

/** Retains a newly adopted scheme identity until all dependent font writes have settled. */
internal class FontIdentityCheckpoint private constructor(
    private var boundScheme: AbstractColorsScheme?,
    initial: FontIdentityRecord,
    isRecovery: Boolean,
) {
    constructor(scheme: AbstractColorsScheme, plan: FontIdentityPlan.Adopt) :
        this(scheme, FontIdentityRecord(plan.id, FontWritePhase.PREPARED, FontRestoreState.PENDING), false)

    var record: FontIdentityRecord =
        if (isRecovery && initial.applyPhase == FontWritePhase.PREPARED) {
            initial.copy(applyPhase = FontWritePhase.WRITING)
        } else {
            initial
        }
        private set

    init {
        requireNotNull(FontIdentityRules.canonicalId(initial.id))
        if (!isRecovery) check(requireNotNull(boundScheme).metaProperties[FontIdentityRules.METADATA_KEY] == null)
    }

    fun apply(
        census: FontSchemeCensus,
        reservedKeys: Set<String>,
    ) {
        check(record.applyPhase == FontWritePhase.PREPARED)
        val scheme = requireNotNull(boundScheme)
        val expected = FontIdentityPlan.Adopt(record.id)
        check(census.planIdentity(scheme, FontApplyOrigin.EXPLICIT, record.id, reservedKeys) == expected)
        record = record.copy(applyPhase = FontWritePhase.WRITING)
        scheme.setSaveNeeded(true)
        scheme.metaProperties.setProperty(FontIdentityRules.METADATA_KEY, record.id)
        check(scheme.metaProperties[FontIdentityRules.METADATA_KEY] == record.id)
        record = record.copy(applyPhase = FontWritePhase.SETTLED)
    }

    fun restore(
        census: FontSchemeCensus,
        dependentsSettled: Boolean,
    ): Boolean {
        if (isSettled(record.restoreState)) return true
        if (!dependentsSettled) return false
        if (record.applyPhase == FontWritePhase.PREPARED) {
            record = record.copy(restoreState = FontRestoreState.RESTORED)
            return true
        }
        return try {
            val scheme = resolveScheme(census) ?: return false
            val raw = scheme.metaProperties[FontIdentityRules.METADATA_KEY]
            when {
                raw == null -> record = record.copy(restoreState = FontRestoreState.RESTORED)
                raw != record.id -> record = record.copy(restoreState = FontRestoreState.FOREIGN)
                (census.lookup(record.id) as? FontSchemeLookup.Unique)?.scheme === scheme -> {
                    scheme.setSaveNeeded(true)
                    scheme.metaProperties.remove(FontIdentityRules.METADATA_KEY)
                    if (scheme.metaProperties[FontIdentityRules.METADATA_KEY] == null) {
                        record = record.copy(restoreState = FontRestoreState.RESTORED)
                    }
                }
            }
            isSettled(record.restoreState)
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun resolveScheme(census: FontSchemeCensus): AbstractColorsScheme? {
        if (boundScheme == null) {
            boundScheme = (census.lookup(record.id) as? FontSchemeLookup.Unique)?.scheme as? AbstractColorsScheme
        }
        return boundScheme?.takeIf(census::contains)
    }

    companion object {
        fun recover(
            census: FontSchemeCensus,
            record: FontIdentityRecord,
        ): FontIdentityCheckpoint {
            val scheme =
                if (isSettled(record.restoreState)) {
                    null
                } else {
                    (census.lookup(record.id) as? FontSchemeLookup.Unique)?.scheme as? AbstractColorsScheme
                }
            return FontIdentityCheckpoint(scheme, record, true)
        }

        private fun isSettled(state: FontRestoreState): Boolean =
            state == FontRestoreState.RESTORED || state == FontRestoreState.FOREIGN
    }
}
