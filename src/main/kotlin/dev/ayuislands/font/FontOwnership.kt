package dev.ayuislands.font

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.settings.AyuIslandsState

/**
 * Per-surface transitions: absent -> owned on apply; mismatched owned -> suspended on apply;
 * matching owned -> absent on restore; mismatched owned/suspended -> released on disable.
 * Released backups never authorize automatic writes; explicit apply captures a fresh baseline.
 * Unknown records are untouched. A mismatch may be a manual edit or a lossy native scheme reload.
 */
internal object FontOwnership {
    private val LOG = logger<FontOwnership>()
    const val VERSION = 1

    fun apply(
        scheme: EditorColorsScheme,
        settings: FontSettings,
        state: AyuIslandsState,
        origin: FontApplyOrigin,
    ): Boolean {
        require(settings.fontSize.isFinite() && settings.fontSize > 0f)
        require(settings.lineSpacing.isFinite() && settings.lineSpacing > 0f)
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
            FontSurface.entries.filter { it.isAvailable(scheme) }.associateWith { surface ->
                resolveTarget(scheme, surface, settings, family)
            }
        var changed = false
        for (surface in targets.keys) {
            val surfaceChanged =
                if (surface == FontSurface.CONSOLE && !settings.applyToConsole) {
                    restoreSurface(scheme, surface, state)
                } else {
                    applySurface(scheme, surface, targets.getValue(surface), state, origin)
                }
            changed = surfaceChanged || changed
        }
        return changed
    }

    private fun resolveTarget(
        scheme: EditorColorsScheme,
        surface: FontSurface,
        settings: FontSettings,
        family: String,
    ): FontSnapshot {
        // Preserve live inheritance; applying the editor already updates the console.
        if (surface == FontSurface.CONSOLE && scheme.isUseEditorFontPreferencesInConsole) return FontSnapshot.Inherited
        val current = FontData.capture(surface.preferences(scheme))
        return FontSnapshot.Explicit(
            current.copy(
                effectiveFamilies = listOf(family),
                families = listOf(FontFamily(family, settings.fontSize)),
                templateSize = settings.fontSize,
                lineSpacing = settings.lineSpacing,
                ligatures = if (surface == FontSurface.EDITOR) settings.enableLigatures else current.ligatures,
                regularSubFamily = settings.weight.subFamily,
            ),
        )
    }

    fun restore(
        scheme: EditorColorsScheme,
        state: AyuIslandsState,
        family: String? = null,
    ): Boolean {
        var changed = false
        // Restore explicit console ownership first; later editor restoration can affect inherited values.
        for (surface in FontSurface.entries.reversed().filter { it.isAvailable(scheme) }) {
            changed = restoreSurface(scheme, surface, state, family) || changed
        }
        return changed
    }

    private fun applySurface(
        scheme: EditorColorsScheme,
        surface: FontSurface,
        target: FontSnapshot,
        state: AyuIslandsState,
        origin: FontApplyOrigin,
    ): Boolean {
        val key = "${surface.name}:${scheme.name}"
        val raw = state.fontOwnershipSnapshots[key]
        val existing = raw?.let(FontOwnershipCodec::decode)
        if (raw != null && existing == null) return false
        val current = surface.capture(scheme)
        val record =
            when (existing?.status) {
                FontOwnershipStatus.SUSPENDED -> return false
                FontOwnershipStatus.OWNED -> {
                    if (current != existing.applied) {
                        retainBackup(key, existing, FontOwnershipStatus.SUSPENDED, state)
                        return false
                    }
                    existing
                }
                null, FontOwnershipStatus.RELEASED -> {
                    if (existing != null && origin == FontApplyOrigin.AUTOMATIC) return false
                    FontOwnershipRecord(FontOwnershipStatus.OWNED, current, current)
                }
            }
        return writeOwned(scheme, surface, target, record, state)
    }

    private fun restoreSurface(
        scheme: EditorColorsScheme,
        surface: FontSurface,
        state: AyuIslandsState,
        family: String? = null,
    ): Boolean {
        val key = "${surface.name}:${scheme.name}"
        val raw = state.fontOwnershipSnapshots[key] ?: return false
        val record = FontOwnershipCodec.decode(raw) ?: return false
        if (record.status == FontOwnershipStatus.RELEASED) return false
        val appliedFamily =
            (record.applied as? FontSnapshot.Explicit)
                ?.preferences
                ?.families
                ?.firstOrNull()
                ?.name
        if (family != null && !family.equals(appliedFamily, ignoreCase = true)) return false
        if (record.status != FontOwnershipStatus.OWNED || surface.capture(scheme) != record.applied) {
            retainBackup(key, record, FontOwnershipStatus.RELEASED, state)
            return false
        }
        val changed = writeOwned(scheme, surface, record.baseline, record, state)
        state.fontOwnershipSnapshots.remove(key)
        return changed
    }

    private fun retainBackup(
        key: String,
        record: FontOwnershipRecord,
        status: FontOwnershipStatus,
        state: AyuIslandsState,
    ) {
        state.fontOwnershipSnapshots[key] = FontOwnershipCodec.encode(record.copy(status = status))
        if (record.status != FontOwnershipStatus.OWNED) return
        LOG.warn("Font ownership cannot be confirmed for $key; preserving current fonts and the recovery snapshot")
        Notifications.Bus.notify(
            Notification(
                "Ayu Islands",
                "Font settings preserved",
                "Your current fonts differ from the settings last applied by Ayu Islands and were left unchanged. " +
                    "Your earlier settings are kept as a backup. To change fonts, turn font presets off, " +
                    "then choose and apply a preset in Settings.",
                NotificationType.WARNING,
            ),
            null,
        )
    }

    private fun writeOwned(
        scheme: EditorColorsScheme,
        surface: FontSurface,
        target: FontSnapshot,
        record: FontOwnershipRecord,
        state: AyuIslandsState,
    ): Boolean {
        val key = "${surface.name}:${scheme.name}"
        // Preserve the baseline before writing. Record actual partial writes for safe retry after a failure.
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
}

internal enum class FontOwnershipStatus { OWNED, SUSPENDED, RELEASED }

internal enum class FontApplyOrigin { EXPLICIT, AUTOMATIC }

internal data class FontOwnershipRecord(
    val status: FontOwnershipStatus,
    val baseline: FontSnapshot,
    val applied: FontSnapshot,
)
