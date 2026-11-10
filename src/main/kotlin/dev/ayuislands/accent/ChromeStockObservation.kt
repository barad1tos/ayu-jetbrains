package dev.ayuislands.accent

import java.awt.Color

/** Exact cache dependencies and the stock result; reading a color never publishes these values. */
internal data class ChromeStockObservation(
    val key: String,
    val target: UiDefaultsTarget,
    val generation: Long,
    val cached: Color?,
    val previousStock: Color?,
    val previousTint: Color?,
    val stock: Color?,
    val source: ChromeStockSource,
)

/** Raw provider identities are observed after native lazy resolution, never evaluated by validation. */
internal data class ChromeStockSource(
    val local: Any?,
    val fallback: Any?,
)

/** Legacy tint recognition uses color equality; cache admission separately uses reference identity. */
internal fun recoverChromeStock(
    current: Color,
    previousStock: Color?,
    previousTint: Color?,
): Color {
    if (previousTint == null || previousStock == null) return current
    return if (current == previousTint) previousStock else current
}
