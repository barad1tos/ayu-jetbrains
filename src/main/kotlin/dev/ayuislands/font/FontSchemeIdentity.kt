package dev.ayuislands.font

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import dev.ayuislands.settings.AyuIslandsState
import java.util.UUID

/** One operation's available-scheme census; names never authorize ownership or recovery. */
internal class FontSchemeIdentity(
    schemes: Array<EditorColorsScheme>,
    private val state: AyuIslandsState,
) {
    private val owners =
        schemes
            .mapNotNull { scheme ->
                canonicalId(scheme.metaProperties[METADATA_KEY])?.let { it to scheme }
            }.groupBy({ it.first }, { it.second })
            .toMutableMap()
    private val duplicates = owners.filterValues { it.size > 1 }.keys

    var hasUnconfirmedIdentity = false
    var hasChangedPreferences = false

    init {
        hasUnconfirmedIdentity = duplicates.isNotEmpty()
        for ((key, raw) in state.fontOwnershipSnapshots.toMap()) {
            val id = recordId(key)
            if (id == null || id !in owners) hasUnconfirmedIdentity = true
            if (state.fontOwnershipVersion != FontOwnership.VERSION || id !in duplicates) continue
            val record = FontOwnershipCodec.decode(raw) ?: continue
            if (
                record.status == FontOwnershipStatus.OWNED ||
                record.status == FontOwnershipStatus.ONE_SHOT
            ) {
                state.fontOwnershipSnapshots[key] =
                    FontOwnershipCodec.encode(record.copy(status = FontOwnershipStatus.SUSPENDED))
            }
        }
    }

    fun resolve(
        scheme: EditorColorsScheme,
        origin: FontApplyOrigin? = null,
    ): String? {
        val raw = scheme.metaProperties[METADATA_KEY]
        val id = canonicalId(raw)
        val invalidMetadata = raw != null && id == null
        val ambiguousOwner = id != null && owners[id]?.singleOrNull() !== scheme
        if (invalidMetadata || ambiguousOwner) {
            hasUnconfirmedIdentity = true
            return null
        }
        if (id != null) return id
        if (origin == null) return null
        if (origin == FontApplyOrigin.AUTOMATIC || scheme !is AbstractColorsScheme) {
            hasUnconfirmedIdentity = true
            return null
        }
        var freshId: String
        do {
            freshId = UUID.randomUUID().toString()
        } while (
            freshId in owners ||
            FontSurface.entries.any { key(freshId, it) in state.fontOwnershipSnapshots }
        )
        // Metadata alone must survive saving even when the requested fonts already match.
        scheme.setSaveNeeded(true)
        scheme.metaProperties.setProperty(METADATA_KEY, freshId)
        owners[freshId] = listOf(scheme)
        return freshId
    }

    fun notifyPreserved() {
        val detail =
            when {
                duplicates.isNotEmpty() -> {
                    "Several schemes share the same font ownership identity. " +
                        "Their fonts and backups were preserved. " +
                        "Apply remains blocked for those schemes. Resolve the duplicate identities before " +
                        "turning presets off and explicitly applying again."
                }

                hasUnconfirmedIdentity -> {
                    "Font ownership could not be confirmed. " +
                        "Affected fonts and any earlier backups were preserved. " +
                        "For unchanged presets on new or renamed schemes, open Customize in Font Settings, " +
                        "select Reapply preset, then Apply. This starts a new backup from their current fonts " +
                        "and cannot recover the pre-rename settings. " +
                        "Unrecognized identities still block Apply until resolved."
                }

                hasChangedPreferences -> {
                    "Your current fonts differ from the settings last applied by Ayu Islands " +
                        "and were left unchanged. " +
                        "Your earlier settings are kept as a backup. To change fonts, turn font presets off, " +
                        "then choose and apply a preset in Settings."
                }

                else -> {
                    return
                }
            }
        LOG.warn(detail)
        Notifications.Bus.notify(
            Notification("Ayu Islands", "Font settings preserved", detail, NotificationType.WARNING),
            null,
        )
    }

    companion object {
        private val LOG = logger<FontSchemeIdentity>()
        private const val KEY_PARTS = 3
        private const val KEY_PREFIX = "v${FontOwnership.VERSION}"
        private const val METADATA_KEY = "dev.ayuislands.fontOwnershipId"

        fun key(
            id: String,
            surface: FontSurface,
        ): String = "$KEY_PREFIX:${surface.name}:$id"

        private fun recordId(key: String): String? {
            val parts = key.split(':')
            if (parts.size != KEY_PARTS || parts[0] != KEY_PREFIX || FontSurface.entries.none { it.name == parts[1] }) {
                return null
            }
            return canonicalId(parts[2])
        }

        private fun canonicalId(raw: Any?): String? =
            (raw as? String)?.let { value ->
                try {
                    value.takeIf { UUID.fromString(it).toString() == it }
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
    }
}
