package dev.ayuislands.font

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import dev.ayuislands.settings.AyuIslandsState
import java.util.UUID

/** One operation's available-scheme census; names never authorize ownership or recovery. */
internal class FontSchemeIdentity(
    private val schemes: Array<EditorColorsScheme>,
    private val state: AyuIslandsState,
) {
    private var census = FontSchemeCensus(schemes)
    private val duplicates = census.duplicateIds

    var hasUnconfirmedIdentity = false
    var hasChangedPreferences = false

    init {
        hasUnconfirmedIdentity = duplicates.isNotEmpty()
        for ((key, raw) in state.fontOwnershipSnapshots.toMap()) {
            val id = FontIdentityRules.recordId(key)
            if (id == null || census.lookup(id) == FontSchemeLookup.Missing) hasUnconfirmedIdentity = true
            if (state.fontOwnershipVersion != FontOwnership.VERSION || id !in duplicates) continue
            val record = FontOwnershipCodec.decode(raw) ?: continue
            val suspended = FontOwnershipPolicy.suspendDuplicate(record)
            if (suspended != record) state.fontOwnershipSnapshots[key] = FontOwnershipCodec.encode(suspended)
        }
    }

    fun resolve(
        scheme: EditorColorsScheme,
        origin: FontApplyOrigin? = null,
    ): String? {
        var plan: FontIdentityPlan
        do {
            plan = census.planIdentity(scheme, origin, UUID.randomUUID().toString(), state.fontOwnershipSnapshots.keys)
        } while (plan == FontIdentityPlan.Collision)
        return when (val selected = plan) {
            is FontIdentityPlan.Existing -> selected.id
            is FontIdentityPlan.Adopt -> {
                // Mark metadata dirty even when the requested fonts already match.
                (scheme as AbstractColorsScheme).setSaveNeeded(true)
                scheme.metaProperties.setProperty(FontIdentityRules.METADATA_KEY, selected.id)
                census = FontSchemeCensus(schemes)
                selected.id
            }
            FontIdentityPlan.Missing -> null
            FontIdentityPlan.Unconfirmed, FontIdentityPlan.Collision -> {
                hasUnconfirmedIdentity = true
                null
            }
        }
    }

    companion object {
        fun key(
            id: String,
            surface: FontSurface,
        ): String = FontIdentityRules.key(id, surface)
    }
}
