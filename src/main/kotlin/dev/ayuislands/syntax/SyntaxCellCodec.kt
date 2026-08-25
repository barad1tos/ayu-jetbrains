package dev.ayuislands.syntax

/** Decodes sparse `language|category` cells into the applicator's nested shape. */
internal fun <T : Any> decodeSyntaxCells(
    flat: Map<String, String>,
    decodeValue: (String) -> T?,
): Map<String, Map<String, T>> {
    val nested = mutableMapOf<String, MutableMap<String, T>>()
    for ((compositeKey, encodedValue) in flat) {
        val separatorIndex = compositeKey.indexOf('|')
        if (separatorIndex <= 0 || separatorIndex == compositeKey.lastIndex) continue
        val language = compositeKey.substring(0, separatorIndex)
        val category = compositeKey.substring(separatorIndex + 1)
        val decoded = decodeValue(encodedValue) ?: continue
        nested.getOrPut(language) { mutableMapOf() }[category] = decoded
    }
    return nested
}
