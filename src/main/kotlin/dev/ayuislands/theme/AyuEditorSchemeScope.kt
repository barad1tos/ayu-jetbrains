package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.runCatchingPreservingCancellation
import org.jetbrains.annotations.TestOnly
import java.awt.Color
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
            claim(scheme)
        }

    fun writeColor(
        owner: EditorSchemeOwner,
        key: ColorKey,
        value: Color?,
    ) {
        val scheme = activeScheme() ?: return
        writeColor(scheme, owner, key, value)
    }

    fun writeColor(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        key: ColorKey,
        value: Color?,
    ) {
        if (activeScheme() !== scheme) return
        claim(scheme)
        EditorSchemeOverrides.writeColor(scheme, owner, key, value)
    }

    fun writeAttributes(
        owner: EditorSchemeOwner,
        key: TextAttributesKey,
        value: TextAttributes?,
    ) {
        val scheme = activeScheme() ?: return
        writeAttributes(scheme, owner, key, value)
    }

    fun writeAttributes(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        key: TextAttributesKey,
        value: TextAttributes?,
    ) {
        if (activeScheme() !== scheme) return
        claim(scheme)
        EditorSchemeOverrides.writeAttributes(scheme, owner, key, value)
    }

    fun restore(owner: EditorSchemeOwner) {
        val schemes = allSchemes()
        val current = currentAyuScheme()
        val variant =
            current?.let { scheme ->
                AyuVariant.entries.firstOrNull { AyuEditorSchemeBinder.matchesVariant(scheme.name, it) }
            }
        val canonicalName = variant?.let(AyuEditorSchemeBinder::targetSchemeName)
        if (current != null && canonicalName != null && current.name != canonicalName) {
            schemes.firstOrNull { it.name == canonicalName }?.let { canonical ->
                EditorSchemeOverrides.inherit(current, canonical)
            }
        }
        cleanClaimedAccentSchemes { scheme ->
            EditorSchemeOverrides.restore(scheme, owner)
        }
        val claimed = claimedAccentSchemes()
        schemes
            .filter { scheme -> claimed.none { it === scheme } && EditorSchemeOverrides.hasState(scheme, owner) }
            .forEach { scheme -> EditorSchemeOverrides.restore(scheme, owner) }
    }

    fun observeElementEnabled(
        id: AccentElementId,
        isEnabled: Boolean,
    ) {
        EditorSchemeOverrides.observeElementEnabled(id, isEnabled, ::allSchemes)
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

    private fun allSchemes(): List<EditorColorsScheme> = EditorColorsManager.getInstance().allSchemes.toList()

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

    private fun claim(scheme: EditorColorsScheme) {
        val isNew = synchronized(accentClaims) { accentClaims.add(scheme) }
        if (!isNew) return
        val variant = AyuVariant.detect() ?: return
        val canonicalName = AyuEditorSchemeBinder.targetSchemeName(variant)
        if (scheme.name == canonicalName) return
        allSchemes().firstOrNull { it.name == canonicalName }?.let { canonical ->
            EditorSchemeOverrides.inherit(scheme, canonical)
        }
    }

    @TestOnly
    fun resetClaims() {
        releaseAccentClaims()
        EditorSchemeOverrides.reset()
    }
}
