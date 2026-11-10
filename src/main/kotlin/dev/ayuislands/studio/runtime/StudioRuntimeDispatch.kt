package dev.ayuislands.studio.runtime

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.RotationTicket
import dev.ayuislands.rotation.RuntimeAvailability
import dev.ayuislands.studio.model.StudioAccentContinuation
import dev.ayuislands.studio.model.StudioNativeChange
import java.util.UUID

/** Unmaterialized runtime requests; clocks and random choices are read only after shared admission. */
internal sealed interface RuntimeDispatch {
    data class Start(
        val generation: Long,
        val availability: RuntimeAvailability,
    ) : RuntimeDispatch

    data class NativeChange(
        val focus: FocusSnapshot,
        val change: StudioNativeChange,
    ) : RuntimeDispatch

    sealed interface ContextChange : RuntimeDispatch {
        val focus: FocusSnapshot
        val availability: RuntimeAvailability
    }

    data class FocusChanged(
        override val focus: FocusSnapshot,
        override val availability: RuntimeAvailability,
    ) : ContextChange

    data class ContinuityTransfer(
        override val focus: FocusSnapshot,
        override val availability: RuntimeAvailability,
        val accent: StudioAccentContinuation?,
    ) : ContextChange

    data class Due(
        val ticket: RotationTicket,
    ) : RuntimeDispatch

    data class Availability(
        val generation: Long,
        val availability: RuntimeAvailability,
    ) : RuntimeDispatch

    data object Recover : RuntimeDispatch
}

internal interface StudioRuntimeSource {
    fun clock(): ClockSample

    fun operationId(): UUID

    fun transactionId(): String

    fun randomColor(variant: AyuVariant): String
}
