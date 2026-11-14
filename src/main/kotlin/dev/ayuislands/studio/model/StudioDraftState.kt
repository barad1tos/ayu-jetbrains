package dev.ayuislands.studio.model

import dev.ayuislands.rotation.RotationAcceptanceOrigin

internal enum class DraftIntent { EDITS, FULL_PROFILE }

internal data class DraftTracking(
    val initial: AppearanceSnapshot,
    val touched: StudioList<FieldAddress> = StudioList(emptyList()),
    val reapply: StudioList<FieldAddress> = StudioList(emptyList()),
    val intent: DraftIntent = DraftIntent.EDITS,
    val rotationOrigin: RotationAcceptanceOrigin? = null,
)
