package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.accent.AyuVariant
import org.jetbrains.annotations.TestOnly
import java.util.Collections
import java.util.IdentityHashMap

internal object AyuEditorSchemeScope {
    private val accentClaims: MutableSet<EditorColorsScheme> =
        Collections.newSetFromMap(IdentityHashMap())

    fun activeScheme(): EditorColorsScheme? {
        val variant = AyuVariant.detect() ?: return null
        val scheme = EditorColorsManager.getInstance().globalScheme

        return scheme.takeIf {
            AyuEditorSchemeBinder.matchesVariant(it.name, variant)
        }
    }

    fun claimActiveScheme(): EditorColorsScheme? =
        activeScheme()?.also { scheme ->
            synchronized(accentClaims) {
                accentClaims.add(scheme)
            }
        }

    fun currentAyuScheme(): EditorColorsScheme? =
        EditorColorsManager
            .getInstance()
            .globalScheme
            .takeIf { AyuEditorSchemeBinder.isAyuScheme(it.name) }

    fun claimedAccentSchemes(): List<EditorColorsScheme> =
        synchronized(accentClaims) {
            accentClaims.toList()
        }

    fun releaseAccentClaims() {
        synchronized(accentClaims) {
            accentClaims.clear()
        }
    }

    @TestOnly
    fun resetClaims() {
        releaseAccentClaims()
    }
}
