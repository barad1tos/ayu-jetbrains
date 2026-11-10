package dev.ayuislands.studio.session

import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.font.FontApplyOrigin
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontOwnershipPolicy
import dev.ayuislands.font.FontOwnershipRecord
import dev.ayuislands.font.FontOwnershipTransition
import dev.ayuislands.font.FontPreferencePolicy
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontSurface
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.StudioFontOwnership

/** Capture-only native adapter; policies select transitions and the legacy codec validates managed output. */
internal class StudioFontCapture(
    private val input: StudioEffectInput,
    private val ownership: StudioFontOwnership,
) {
    val writes = mutableListOf<StudioFontWrite>()
    private val outcomes = linkedMapOf<String, FontOwnershipRecord?>()

    fun suspendDuplicates(ids: Set<String>) {
        for (key in ownership.keys) {
            if (FontIdentityRules.recordId(key) !in ids) continue
            val record = ownership.read(key) ?: continue
            outcomes[key] = FontOwnershipPolicy.suspendDuplicate(record)
        }
    }

    fun apply(
        scheme: EditorColorsScheme,
        id: String,
        settings: FontSettings,
        family: String,
    ) {
        require(settings.fontSize.isFinite() && settings.fontSize > 0f)
        require(settings.lineSpacing.isFinite() && settings.lineSpacing > 0f)
        for (surface in FontSurface.entries.filter { it.isAvailable(scheme) }) {
            if (surface == FontSurface.CONSOLE && !settings.applyToConsole) {
                restoreSurface(scheme, id, surface)
                continue
            }
            val key = FontIdentityRules.key(id, surface)
            val record = ownership.read(key)
            check(key !in ownership.keys || record != null) { "Unrecognized Font ownership must be preserved" }
            val current = surface.captureExact(scheme)
            val target =
                FontOwnershipPolicy.target(
                    surface,
                    current == FontSnapshot.Inherited,
                    FontData.captureExact(surface.preferences(scheme)),
                    settings,
                    family,
                )
            val transition =
                if (input.fontOrigin == FontApplyOrigin.AUTOMATIC) {
                    FontOwnershipPolicy.apply(record, current, target, FontApplyOrigin.AUTOMATIC)
                } else {
                    StudioFontPolicy.apply(key, record, current, target, input.choices)
                }
            retain(scheme, id, surface, current, transition)
        }
    }

    fun restore(
        scheme: EditorColorsScheme,
        id: String,
    ) {
        for (surface in FontSurface.entries.reversed().filter { it.isAvailable(scheme) }) {
            restoreSurface(scheme, id, surface)
        }
    }

    fun changes(): StudioList<RuntimeFieldChange> = ownership.changes(outcomes, hasWrites = writes.isNotEmpty())

    private fun restoreSurface(
        scheme: EditorColorsScheme,
        id: String,
        surface: FontSurface,
    ) {
        val record = ownership.read(FontIdentityRules.key(id, surface)) ?: return
        val current = surface.captureExact(scheme)
        retain(scheme, id, surface, current, FontOwnershipPolicy.restore(record, current, null))
    }

    private fun retain(
        scheme: EditorColorsScheme,
        id: String,
        surface: FontSurface,
        current: FontSnapshot,
        transition: FontOwnershipTransition,
    ) {
        val key = FontIdentityRules.key(id, surface)
        when (transition) {
            FontOwnershipTransition.Unchanged -> Unit
            is FontOwnershipTransition.Retain -> outcomes[key] = transition.record
            is FontOwnershipTransition.Write -> {
                val identity = FontReceiptIdentity(id, surface, input.candidate.header.transactionId)
                val write = StudioFontWrite.capture(identity, scheme, transition.target)
                check(
                    FontPreferencePolicy.matches(current, write.record.before),
                ) { "Font capture changed during preparation" }
                outcomes[key] =
                    if (transition.releaseAfterWrite) null else transition.record.copy(applied = write.record.target)
                writes.add(write)
            }
        }
    }
}
