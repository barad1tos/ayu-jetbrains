package dev.ayuislands.font

import java.util.UUID

/** Canonical persisted identity syntax shared by legacy application and Studio recovery. */
internal object FontIdentityRules {
    const val METADATA_KEY = "dev.ayuislands.fontOwnershipId"
    private const val KEY_PARTS = 3
    private const val KEY_PREFIX = "v${FontOwnership.VERSION}"

    fun key(
        id: String,
        surface: FontSurface,
    ): String = "$KEY_PREFIX:${surface.name}:$id"

    fun recordId(key: String): String? {
        val parts = key.split(':')
        if (parts.size != KEY_PARTS || parts[0] != KEY_PREFIX || FontSurface.entries.none { it.name == parts[1] }) {
            return null
        }
        return canonicalId(parts[2])
    }

    fun canonicalId(raw: Any?): String? =
        (raw as? String)?.let { value ->
            try {
                value.takeIf { UUID.fromString(it).toString() == it }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
}
