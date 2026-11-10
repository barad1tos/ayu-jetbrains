package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.LegacyStore

/** Original v2 storage names retained by capture and the immutable migration manifest. */
internal val legacyFileNames: Map<LegacyStore, String> =
    mapOf(
        LegacyStore.SETTINGS to "ayuIslands.xml",
        LegacyStore.SYNTAX to "ayu-islands-syntax-intensity.xml",
        LegacyStore.MAPPINGS to "ayuIslandsAccentMappings.xml",
    )
