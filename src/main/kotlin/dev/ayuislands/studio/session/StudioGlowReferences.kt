package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowFailureOrigin
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowWaveformFailure
import dev.ayuislands.studio.model.StudioProcess

/** One operation shares exact native identities across all managers and recovery attempts. */
internal class StudioGlowReferences(
    private val ownerToken: String,
    private val process: StudioProcess,
) {
    private val tokens = StudioNativeTokens("glow/$ownerToken")

    init {
        require(ownerToken.isNotBlank())
    }

    fun token(value: Any): String = tokens.token(value)

    fun identity(value: Any): GlowReceiptIdentity = GlowReceiptIdentity(token(value), ownerToken, process)

    fun overlayTokens(entry: GlowOverlayManager.OverlayEntry): GlowMemberTokens.Overlay =
        GlowMemberTokens.Overlay(
            token(entry),
            token(entry.glassPane),
            token(entry.host),
            token(entry.layeredPane),
            entry.componentListener?.let(::token),
            entry.hierarchyBoundsListener?.let(::token),
            entry.glassPane.isEditorOverlay,
        )

    fun failureTokens(failure: GlowWaveformFailure?): GlowFailureTokens? {
        if (failure == null) return null
        val producer =
            when (val origin = failure.origin) {
                is GlowFailureOrigin.Local -> origin.engine
                is GlowFailureOrigin.Route -> origin.playback
            }
        return GlowFailureTokens(token(failure), producer?.let(::token))
    }
}
