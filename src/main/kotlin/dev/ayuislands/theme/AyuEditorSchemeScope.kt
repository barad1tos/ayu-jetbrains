package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.runCatchingPreservingCancellation
import org.jetbrains.annotations.TestOnly
import java.util.Collections
import java.util.IdentityHashMap

internal object AyuEditorSchemeScope {
    private val accentClaims: MutableSet<EditorColorsScheme> =
        Collections.newSetFromMap(IdentityHashMap())
    private val accentCleanupFailures: MutableSet<EditorColorsScheme> =
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

    fun beginAccentCleanup() {
        synchronized(accentCleanupFailures) {
            accentCleanupFailures.clear()
        }
    }

    fun cleanClaimedAccentSchemes(cleanup: (EditorColorsScheme) -> Unit) {
        var firstFailure: Throwable? = null
        for (scheme in claimedAccentSchemes()) {
            runCatchingPreservingCancellation { cleanup(scheme) }
                .exceptionOrNull()
                ?.let { failure ->
                    synchronized(accentCleanupFailures) {
                        accentCleanupFailures.add(scheme)
                    }
                    if (firstFailure == null) firstFailure = failure
                }
        }
        firstFailure?.let { throw it }
    }

    fun retainAllAccentClaims() {
        synchronized(accentCleanupFailures) {
            accentCleanupFailures.addAll(claimedAccentSchemes())
        }
    }

    fun hasAccentCleanupFailures(): Boolean =
        synchronized(accentCleanupFailures) {
            accentCleanupFailures.isNotEmpty()
        }

    fun releaseCleanAccentClaims() {
        val failed = synchronized(accentCleanupFailures) { accentCleanupFailures.toList() }
        synchronized(accentClaims) {
            accentClaims.removeIf { claim -> failed.none { failedScheme -> failedScheme === claim } }
        }
        synchronized(accentCleanupFailures) {
            accentCleanupFailures.clear()
        }
    }

    fun releaseAccentClaims() {
        synchronized(accentClaims) {
            accentClaims.clear()
        }
        synchronized(accentCleanupFailures) {
            accentCleanupFailures.clear()
        }
    }

    @TestOnly
    fun resetClaims() {
        releaseAccentClaims()
    }
}
