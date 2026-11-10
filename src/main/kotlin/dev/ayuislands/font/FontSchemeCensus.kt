package dev.ayuislands.font

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme

internal sealed interface FontSchemeLookup {
    data class Unique(
        val scheme: EditorColorsScheme,
    ) : FontSchemeLookup

    data object Missing : FontSchemeLookup

    data object Duplicate : FontSchemeLookup
}

internal sealed interface FontIdentityPlan {
    data class Existing(
        val id: String,
    ) : FontIdentityPlan

    data class Adopt(
        val id: String,
    ) : FontIdentityPlan

    data object Missing : FontIdentityPlan

    data object Unconfirmed : FontIdentityPlan

    data object Collision : FontIdentityPlan
}

/** One read-only native membership census; it never allocates or writes scheme metadata. */
internal class FontSchemeCensus(
    schemes: Array<EditorColorsScheme>,
) {
    private val available = schemes.toList()
    private val owners =
        available
            .mapNotNull { scheme ->
                FontIdentityRules
                    .canonicalId(
                        scheme.metaProperties[FontIdentityRules.METADATA_KEY],
                    )?.let { it to scheme }
            }.groupBy({ it.first }, { it.second })
    val duplicateIds: Set<String> = owners.filterValues { it.size > 1 }.keys

    fun contains(scheme: EditorColorsScheme): Boolean = available.any { it === scheme }

    fun lookup(id: String): FontSchemeLookup =
        when (val matches = owners[id]) {
            null -> FontSchemeLookup.Missing
            else -> matches.singleOrNull()?.let(FontSchemeLookup::Unique) ?: FontSchemeLookup.Duplicate
        }

    fun planIdentity(
        scheme: EditorColorsScheme,
        origin: FontApplyOrigin?,
        proposedId: String,
        reservedKeys: Set<String>,
    ): FontIdentityPlan {
        if (!contains(scheme)) return FontIdentityPlan.Unconfirmed
        val raw = scheme.metaProperties[FontIdentityRules.METADATA_KEY]
        val id = FontIdentityRules.canonicalId(raw)
        if (raw != null && id == null) return FontIdentityPlan.Unconfirmed
        if (id != null) {
            val owner = lookup(id) as? FontSchemeLookup.Unique
            return if (owner?.scheme === scheme) FontIdentityPlan.Existing(id) else FontIdentityPlan.Unconfirmed
        }
        if (origin == null) return FontIdentityPlan.Missing
        if (origin == FontApplyOrigin.AUTOMATIC ||
            scheme !is AbstractColorsScheme ||
            FontIdentityRules.canonicalId(proposedId) == null
        ) {
            return FontIdentityPlan.Unconfirmed
        }
        if (proposedId in owners || FontSurface.entries.any { FontIdentityRules.key(proposedId, it) in reservedKeys }) {
            return FontIdentityPlan.Collision
        }
        return FontIdentityPlan.Adopt(proposedId)
    }
}
