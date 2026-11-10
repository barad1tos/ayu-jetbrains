package dev.ayuislands.font

/** Native writes and long-lived backup updates selected without reading or mutating the IDE. */
internal sealed interface FontOwnershipTransition {
    data object Unchanged : FontOwnershipTransition

    data class Retain(
        val record: FontOwnershipRecord,
        val changedPreferences: Boolean,
    ) : FontOwnershipTransition

    data class Write(
        val target: FontSnapshot,
        val record: FontOwnershipRecord,
        val releaseAfterWrite: Boolean = false,
    ) : FontOwnershipTransition
}

/** Shared v2 ownership rules; a transaction's immediate before-state is not its managed baseline. */
internal object FontOwnershipPolicy {
    fun suspendDuplicate(record: FontOwnershipRecord): FontOwnershipRecord =
        when (record.status) {
            FontOwnershipStatus.OWNED, FontOwnershipStatus.ONE_SHOT ->
                record.copy(
                    status = FontOwnershipStatus.SUSPENDED,
                )
            FontOwnershipStatus.SUSPENDED, FontOwnershipStatus.RELEASED -> record
        }

    fun apply(
        existing: FontOwnershipRecord?,
        current: FontSnapshot,
        target: FontSnapshot,
        origin: FontApplyOrigin,
    ): FontOwnershipTransition {
        val record =
            when (existing?.status) {
                FontOwnershipStatus.SUSPENDED -> return FontOwnershipTransition.Unchanged
                FontOwnershipStatus.OWNED -> {
                    if (current != existing.applied) return retain(existing, FontOwnershipStatus.SUSPENDED)
                    if (origin == FontApplyOrigin.ONE_SHOT) {
                        existing.copy(status = FontOwnershipStatus.ONE_SHOT)
                    } else {
                        existing
                    }
                }
                FontOwnershipStatus.ONE_SHOT ->
                    resolveOneShot(existing, current, origin) ?: return FontOwnershipTransition.Unchanged
                null, FontOwnershipStatus.RELEASED -> {
                    if (origin == FontApplyOrigin.AUTOMATIC) return FontOwnershipTransition.Unchanged
                    val status =
                        if (origin ==
                            FontApplyOrigin.ONE_SHOT
                        ) {
                            FontOwnershipStatus.ONE_SHOT
                        } else {
                            FontOwnershipStatus.OWNED
                        }
                    FontOwnershipRecord(status, current, current)
                }
            }
        return FontOwnershipTransition.Write(target, record)
    }

    fun restore(
        record: FontOwnershipRecord,
        current: FontSnapshot,
        family: String?,
    ): FontOwnershipTransition {
        if (record.status == FontOwnershipStatus.RELEASED) return FontOwnershipTransition.Unchanged
        if (record.status == FontOwnershipStatus.ONE_SHOT && family == null) return FontOwnershipTransition.Unchanged
        val appliedFamily =
            (record.applied as? FontSnapshot.Explicit)
                ?.preferences
                ?.families
                ?.firstOrNull()
                ?.name
        if (family != null && !family.equals(appliedFamily, ignoreCase = true)) {
            return FontOwnershipTransition.Unchanged
        }
        val canRestore = record.status == FontOwnershipStatus.OWNED || record.status == FontOwnershipStatus.ONE_SHOT
        if (!canRestore || current != record.applied) return retain(record, FontOwnershipStatus.RELEASED)
        return FontOwnershipTransition.Write(record.baseline, record, releaseAfterWrite = true)
    }

    fun target(
        surface: FontSurface,
        inherited: Boolean,
        current: FontData,
        settings: FontSettings,
        family: String,
    ): FontSnapshot {
        if (surface == FontSurface.CONSOLE && inherited) return FontSnapshot.Inherited
        return FontSnapshot.Explicit(
            current.copy(
                effectiveFamilies = listOf(family),
                families = listOf(FontFamily(family, settings.fontSize)),
                templateSize = settings.fontSize,
                lineSpacing = settings.lineSpacing,
                ligatures = if (surface == FontSurface.EDITOR) settings.enableLigatures else current.ligatures,
                regularSubFamily = settings.weight.subFamily.takeIf(String::isNotEmpty),
            ),
        )
    }

    private fun retain(
        record: FontOwnershipRecord,
        status: FontOwnershipStatus,
    ): FontOwnershipTransition.Retain =
        FontOwnershipTransition.Retain(record.copy(status = status), record.status == FontOwnershipStatus.OWNED)

    private fun resolveOneShot(
        existing: FontOwnershipRecord,
        current: FontSnapshot,
        origin: FontApplyOrigin,
    ): FontOwnershipRecord? =
        when (origin) {
            FontApplyOrigin.AUTOMATIC -> null
            FontApplyOrigin.EXPLICIT -> FontOwnershipRecord(FontOwnershipStatus.OWNED, current, current)
            FontApplyOrigin.ONE_SHOT ->
                if (current ==
                    existing.applied
                ) {
                    existing
                } else {
                    FontOwnershipRecord(FontOwnershipStatus.ONE_SHOT, current, current)
                }
        }
}
