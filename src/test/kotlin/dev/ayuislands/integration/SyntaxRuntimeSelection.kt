package dev.ayuislands.integration

internal object SyntaxRuntimeSelection {
    private const val PROPERTY_NAME = "syntaxRuntimeId"

    fun select(readProperty: (String) -> String? = System::getProperty): SyntaxRuntime {
        val runtimeId =
            requireNotNull(readProperty(PROPERTY_NAME)?.takeIf(String::isNotBlank)) {
                "Missing required -D$PROPERTY_NAME; expected ${SyntaxRuntimeCatalog.entries.joinToString { it.id }}"
            }
        return SyntaxRuntimeCatalog.require(runtimeId)
    }
}
