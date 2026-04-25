package dev.ayuislands.theme

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.accent.AyuVariant

/**
 * Domain object that resolves and applies the editor color scheme matching the
 * active Ayu theme variant.
 *
 * **Why this exists.** JetBrains decouples UI theme from editor color scheme by
 * design: switching theme does NOT change the user's active scheme. That respects
 * users who have curated a non-bundled scheme (Solarized, Material, custom),
 * but it surfaces as a bug for first-time Ayu users — Ayu Light theme renders
 * with Mirage editor colors because the active scheme is still "Ayu Islands
 * Mirage" from the previous session. This binder closes that gap with two
 * guarantees: (a) when the user is on a NEUTRAL or another Ayu scheme, swap to
 * the variant's matching scheme; (b) NEVER override a non-Ayu scheme the user
 * explicitly chose.
 *
 * **Single responsibility.** Pure mapping + side-effect on
 * `EditorColorsManager.globalScheme`. No LAF event handling, no settings flag
 * checks (caller — typically [AyuThemeSchemeBinderListener] — owns the gate).
 * Pattern J — gate on [AyuVariant.isAyuActive] is the caller's job; this
 * object only does what it's told.
 *
 * **Pattern G** — `bindForVariant` is the apply path. A revert path
 * (Ayu→non-Ayu transition restoring user's prior scheme) is intentionally NOT
 * implemented here; that requires per-user "previous scheme" persistence and
 * is tracked as a separate feature.
 */
internal object AyuEditorSchemeBinder {
    private val log = logger<AyuEditorSchemeBinder>()

    /**
     * Schemes the binder is allowed to overwrite. Includes our three Ayu
     * scheme names (so Ayu→Ayu transitions sync) and platform defaults
     * (so first-time activation from Default/Darcula sticks). Anything else
     * is treated as "user explicitly chose this" — binder must NOT touch it.
     *
     * Pattern J — explicit allowlist over implicit prefix-match: prevents a
     * future scheme named e.g. "Ayu Custom" from being silently overwritten.
     */
    internal val NEUTRAL_SCHEMES: Set<String> =
        setOf(
            // Our three bundled schemes
            "Ayu Islands Mirage",
            "Ayu Islands Dark",
            "Ayu Islands Light",
            // Platform defaults
            "Default",
            "Darcula",
            "IntelliJ Light",
            "Light",
            "Dark",
        )

    /**
     * Apply the editor color scheme matching [variant] when the current scheme
     * is in [NEUTRAL_SCHEMES] and differs from the target. No-op otherwise.
     *
     * Returns true iff a scheme switch was performed. Tests assert this signal.
     */
    fun bindForVariant(variant: AyuVariant): Boolean {
        val target = targetSchemeName(variant)
        val ecm = EditorColorsManager.getInstance()
        val current = ecm.globalScheme.name
        if (current == target) {
            log.debug("Editor scheme already matches variant $variant ($current); no-op")
            return false
        }
        if (current !in NEUTRAL_SCHEMES) {
            log.info("Skipping editor-scheme bind for $variant — current scheme '$current' is user-custom")
            return false
        }
        val targetScheme: EditorColorsScheme =
            ecm.allSchemes.firstOrNull { it.name == target }
                ?: run {
                    log.warn(
                        "Cannot bind editor scheme for $variant — '$target' not found in EditorColorsManager. " +
                            "Plugin install may be incomplete; check IDE plugin verifier output.",
                    )
                    return false
                }
        ecm.setGlobalScheme(targetScheme)
        log.info("Editor scheme bound for $variant: '$current' -> '$target'")
        return true
    }

    /**
     * Maps an [AyuVariant] to the canonical scheme name registered by our
     * `.theme.json` files via the `editorScheme` field. Single source of
     * truth — duplication elsewhere is forbidden.
     */
    fun targetSchemeName(variant: AyuVariant): String =
        when (variant) {
            AyuVariant.MIRAGE -> "Ayu Islands Mirage"
            AyuVariant.DARK -> "Ayu Islands Dark"
            AyuVariant.LIGHT -> "Ayu Islands Light"
        }
}
