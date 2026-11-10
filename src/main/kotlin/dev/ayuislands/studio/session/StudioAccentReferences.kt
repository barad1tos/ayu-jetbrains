package dev.ayuislands.studio.session

import dev.ayuislands.accent.UiOverrideState
import dev.ayuislands.studio.model.StudioProcess

/** All Accent writes in an outer transaction share physical resource and raw object identities. */
internal class StudioAccentReferences(
    private val ownerToken: String,
    private val process: StudioProcess,
) {
    private val tokens = StudioNativeTokens("accent/$ownerToken")

    init {
        require(ownerToken.isNotBlank())
    }

    fun token(value: Any?): String? = value?.let(tokens::token)

    fun identity(resource: Any): AccentReceiptIdentity =
        AccentReceiptIdentity(tokens.token(resource), ownerToken, process)

    fun claim(state: UiOverrideState?): AccentClaimTokens =
        when (state) {
            null -> AccentClaimTokens.Missing
            UiOverrideState.Unowned -> AccentClaimTokens.Unowned
            UiOverrideState.Relinquished -> AccentClaimTokens.Relinquished
            is UiOverrideState.Owned -> AccentClaimTokens.Owned(token(state.original.raw), token(state.expected.raw))
        }
}
