package dev.ayuislands.studio.session

import java.util.IdentityHashMap

/** Equal native values can represent different objects within the same transaction. */
internal class StudioNativeTokens(
    private val namespace: String,
) {
    private val tokens = IdentityHashMap<Any, String>()

    fun token(value: Any): String = tokens.getOrPut(value) { "$namespace/${tokens.size}" }
}
