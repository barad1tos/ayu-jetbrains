package dev.ayuislands.settings

import dev.ayuislands.syntax.PrimitiveCategory

internal typealias SyntaxPreviewSpec = dev.ayuislands.syntax.SyntaxPreviewSpec

/** Compatibility view while settings consumers migrate to the language specification catalog. */
internal object SyntaxPreviewCatalog {
    fun find(language: String): SyntaxPreviewSpec? =
        dev.ayuislands.syntax
            .SyntaxPreviewCatalog
            .find(language)

    fun languages(): Set<String> =
        dev.ayuislands.syntax
            .SyntaxPreviewCatalog
            .languages()

    fun categories(language: String): Set<PrimitiveCategory> =
        dev.ayuislands.syntax
            .SyntaxPreviewCatalog
            .categories(language)

    fun resourceNames(): Set<String> =
        dev.ayuislands.syntax
            .SyntaxPreviewCatalog
            .resourceNames()
}
