package dev.ayuislands.theme

/** Selection origin; the lookup name is independent of the selected object's actual name. */
internal sealed interface SchemeSource {
    data object Active : SchemeSource

    data class Named(
        val lookupName: String,
    ) : SchemeSource
}
