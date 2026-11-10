package dev.ayuislands.glow

import java.awt.Color

/** Presentation equality never substitutes for the checkpoint's stricter native ownership identity. */
internal fun sameColor(
    actual: Color,
    expected: Color,
): Boolean =
    actual === expected ||
        (actual.javaClass == Color::class.java && expected.javaClass == Color::class.java && actual.rgb == expected.rgb)
