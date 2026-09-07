package dev.ayuislands.font

import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.settings.AyuIslandsState

/**
 * Per-surface transitions: absent -> owned on managed apply; absent -> one-shot on installer apply;
 * mismatched owned -> suspended on apply; matching owned -> absent on restore; mismatched
 * owned/suspended -> released on managed restore; mismatched one-shot -> released only on
 * family-qualified restore. One-shot records authorize only an exact-family uninstall restore.
 * Released backups never authorize writes; explicit managed apply captures a fresh baseline from
 * one-shot or released state. Unknown records are untouched. A mismatch may be a manual edit or a
 * lossy native scheme reload.
 */
internal class FontOwnership(
    private val scheme: EditorColorsScheme,
    private val state: AyuIslandsState,
    private val identities: FontSchemeIdentity,
) {
    fun apply(
        settings: FontSettings,
        origin: FontApplyOrigin,
    ): Boolean {
        val family =
            if (settings.preset.isCurated) {
                FontDetector.resolveFamily(
                    settings.preset,
                ) ?: settings.preset.fontFamily
            } else {
                settings.fontFamily
            }
        // Read both surfaces before an editor write can affect inherited console preferences.
        val targets =
            FontSurface.entries
                .filter { it.isAvailable(scheme) }
                .associateWith { surface ->
                    resolveTarget(surface, settings, family)
                }
        val id = identities.resolve(scheme, origin) ?: return false
        var changed = false
        for (surface in targets.keys) {
            val surfaceChanged =
                if (surface == FontSurface.CONSOLE && !settings.applyToConsole) {
                    restoreSurface(surface, FontSchemeIdentity.key(id, surface))
                } else {
                    applySurface(
                        surface,
                        FontSchemeIdentity.key(id, surface),
                        targets.getValue(surface),
                        origin,
                    )
                }
            changed = surfaceChanged || changed
        }
        return changed
    }

    private fun resolveTarget(
        surface: FontSurface,
        settings: FontSettings,
        family: String,
    ): FontSnapshot {
        // Preserve live inheritance; applying the editor already updates the console.
        if (surface == FontSurface.CONSOLE && scheme.isUseEditorFontPreferencesInConsole) {
            return FontSnapshot.Inherited
        }
        val current = FontData.capture(surface.preferences(scheme))
        return FontSnapshot.Explicit(
            current.copy(
                effectiveFamilies = listOf(family),
                families = listOf(FontFamily(family, settings.fontSize)),
                templateSize = settings.fontSize,
                lineSpacing = settings.lineSpacing,
                ligatures =
                    if (surface == FontSurface.EDITOR) settings.enableLigatures else current.ligatures,
                regularSubFamily = settings.weight.subFamily,
            ),
        )
    }

    fun restore(family: String? = null): Boolean {
        val id = identities.resolve(scheme) ?: return false
        var changed = false
        // Restore explicit console ownership first; later editor restoration can affect inherited
        // values.
        for (surface in FontSurface.entries.reversed().filter { it.isAvailable(scheme) }) {
            changed =
                restoreSurface(
                    surface,
                    FontSchemeIdentity.key(id, surface),
                    family,
                ) ||
                changed
        }
        return changed
    }

    private fun applySurface(
        surface: FontSurface,
        key: String,
        target: FontSnapshot,
        origin: FontApplyOrigin,
    ): Boolean {
        val raw = state.fontOwnershipSnapshots[key]
        val existing = raw?.let(FontOwnershipCodec::decode)
        if (existing == null && (raw != null || origin == FontApplyOrigin.AUTOMATIC)) {
            identities.hasUnconfirmedIdentity = true
            return false
        }
        val current = surface.capture(scheme)
        val record =
            when (existing?.status) {
                FontOwnershipStatus.SUSPENDED -> {
                    return false
                }

                FontOwnershipStatus.OWNED -> {
                    if (current != existing.applied) {
                        retainBackup(key, existing, FontOwnershipStatus.SUSPENDED)
                        return false
                    }
                    if (origin == FontApplyOrigin.ONE_SHOT) {
                        existing.copy(status = FontOwnershipStatus.ONE_SHOT)
                    } else {
                        existing
                    }
                }

                FontOwnershipStatus.ONE_SHOT -> {
                    resolveOneShot(existing, current, origin) ?: return false
                }

                null,
                FontOwnershipStatus.RELEASED,
                -> {
                    if (existing != null && origin == FontApplyOrigin.AUTOMATIC) return false
                    val status =
                        if (origin == FontApplyOrigin.ONE_SHOT) {
                            FontOwnershipStatus.ONE_SHOT
                        } else {
                            FontOwnershipStatus.OWNED
                        }
                    FontOwnershipRecord(status, current, current)
                }
            }
        return writeOwned(surface, key, target, record)
    }

    private fun resolveOneShot(
        existing: FontOwnershipRecord,
        current: FontSnapshot,
        origin: FontApplyOrigin,
    ): FontOwnershipRecord? =
        when (origin) {
            FontApplyOrigin.AUTOMATIC -> {
                null
            }

            FontApplyOrigin.EXPLICIT -> {
                FontOwnershipRecord(FontOwnershipStatus.OWNED, current, current)
            }

            FontApplyOrigin.ONE_SHOT -> {
                if (current == existing.applied) {
                    existing
                } else {
                    FontOwnershipRecord(FontOwnershipStatus.ONE_SHOT, current, current)
                }
            }
        }

    private fun restoreSurface(
        surface: FontSurface,
        key: String,
        family: String? = null,
    ): Boolean {
        val raw = state.fontOwnershipSnapshots[key] ?: return false
        val record =
            FontOwnershipCodec.decode(raw)
                ?: run {
                    identities.hasUnconfirmedIdentity = true
                    return false
                }
        if (record.status == FontOwnershipStatus.RELEASED) return false
        if (record.status == FontOwnershipStatus.ONE_SHOT && family == null) return false
        val appliedFamily =
            (record.applied as? FontSnapshot.Explicit)
                ?.preferences
                ?.families
                ?.firstOrNull()
                ?.name
        if (family != null && !family.equals(appliedFamily, ignoreCase = true)) return false
        val canRestore =
            record.status == FontOwnershipStatus.OWNED || record.status == FontOwnershipStatus.ONE_SHOT
        if (!canRestore || surface.capture(scheme) != record.applied) {
            retainBackup(key, record, FontOwnershipStatus.RELEASED)
            return false
        }
        val changed = writeOwned(surface, key, record.baseline, record)
        state.fontOwnershipSnapshots.remove(key)
        return changed
    }

    private fun retainBackup(
        key: String,
        record: FontOwnershipRecord,
        status: FontOwnershipStatus,
    ) {
        state.fontOwnershipSnapshots[key] = FontOwnershipCodec.encode(record.copy(status = status))
        if (record.status == FontOwnershipStatus.OWNED) identities.hasChangedPreferences = true
    }

    private fun writeOwned(
        surface: FontSurface,
        key: String,
        target: FontSnapshot,
        record: FontOwnershipRecord,
    ): Boolean {
        state.fontOwnershipVersion = VERSION
        // Preserve the baseline before writing. Record actual partial writes for safe retry after a
        // failure.
        state.fontOwnershipSnapshots[key] = FontOwnershipCodec.encode(record)
        if (surface.capture(scheme) == target) return false
        try {
            surface.write(scheme, target)
        } finally {
            state.fontOwnershipSnapshots[key] =
                FontOwnershipCodec.encode(record.copy(applied = surface.capture(scheme)))
        }
        return true
    }

    companion object {
        const val VERSION = 2
    }
}

internal enum class FontOwnershipStatus {
    OWNED,
    ONE_SHOT,
    SUSPENDED,
    RELEASED,
}

internal enum class FontApplyOrigin {
    EXPLICIT,
    ONE_SHOT,
    AUTOMATIC,
}

internal data class FontOwnershipRecord(
    val status: FontOwnershipStatus,
    val baseline: FontSnapshot,
    val applied: FontSnapshot,
)
