package dev.ayuislands.glow

import java.awt.Color

/** Dynamic colors retain identity; distinct plain colors with the same ARGB render identically. */
internal fun sameGlowColor(
    actual: Color,
    expected: Color,
): Boolean {
    val bothPlain = actual.javaClass == Color::class.java && expected.javaClass == Color::class.java
    return actual === expected || (bothPlain && actual.rgb == expected.rgb)
}
