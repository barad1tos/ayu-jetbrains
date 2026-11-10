package dev.ayuislands.studio.session

import java.util.IdentityHashMap

/** Retained by one outer transaction; equal native values never share an identity by accident. */
internal class StudioNativeTokens(
    private val namespace: String,
) {
    private val tokens = IdentityHashMap<Any, String>()

    init {
        require(namespace.isNotBlank())
    }

    fun token(value: Any): String = tokens.getOrPut(value) { "$namespace/${tokens.size}" }
}
