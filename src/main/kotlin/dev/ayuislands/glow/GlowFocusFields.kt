package dev.ayuislands.glow

/** Native identity and scalar comparison never invoke an opaque Color subclass. */
internal object GlowFocusFields {
    fun matches(
        actual: GlowFocusAppearance,
        expected: GlowFocusAppearance,
    ): Boolean =
        actual.color === expected.color &&
            actual.style == expected.style &&
            actual.intensity == expected.intensity &&
            actual.enabled == expected.enabled
}
