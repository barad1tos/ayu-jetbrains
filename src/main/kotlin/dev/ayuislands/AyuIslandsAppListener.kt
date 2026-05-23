package dev.ayuislands

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.syntax.SyntaxModeUpgradeNotifier

internal class AyuIslandsAppListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        // Phase 49 (SYNTAX-07 / D-10) — one-shot Syntax Moods upgrade notification.
        // Notification must fire on first launch regardless of active theme
        // (SYNTAX-07 + iteration-1 BLOCKER fix). It MUST run BEFORE the
        // AyuVariant.fromThemeName() early-return below because the notification
        // is feature discovery (theme-agnostic): a user on a non-Ayu theme still
        // learns that the Syntax tab exists and can opt into an Ayu theme to try
        // it. Re-placing this call below the `?: return` would silently break
        // first-launch discovery for the majority of new users — do not move.
        SyntaxModeUpgradeNotifier.maybeFire()

        val themeName = AyuVariant.currentThemeName()
        val variant = AyuVariant.fromThemeName(themeName) ?: return

        // Anti-flicker: if a previous session persisted the last-applied hex, use it
        // directly rather than re-resolving against a null project context (which always
        // returns the global accent and can flash Gold before per-project StartupActivity
        // runs). The live resolver chain still fires once projects settle (see
        // AyuIslandsStartupActivity.execute), so this cached hex is only authoritative
        // for the first painted frame.
        //
        // Trust-but-verify the persisted hex: [AyuIslandsState.lastAppliedAccentHex] is
        // a plain String property on a [BaseState] serialized to XML. Corrupted writes
        // (partial persist on IDE crash, hand-edited ayu-islands.xml, stale format from
        // a legacy build) would feed straight into [AccentApplicator.apply], which in
        // turn routes through [Color.decode] and throws [NumberFormatException]. The
        // applier now self-defends, but we still clear the bad persisted value so the
        // next boot does not re-poison startup. When the cache is unusable we fall
        // through to the resolver path just like a fresh install.
        val settings = AyuIslandsSettings.getInstance()
        val cached = settings.state.lastAppliedAccentHex
        val cleanCache = settings.state.lastApplyOk
        val validCached = settings.state.effectiveLastAppliedAccentHex()
        if (cached != null && validCached == null) {
            LOG.warn("AyuIslandsAppListener: invalid cached hex '$cached'; clearing and re-resolving")
            settings.state.lastAppliedAccentHex = null
        }
        // Trust the cached hex only when the previous session's apply finished
        // cleanly (lastApplyOk=true). A mid-EP throw leaves the hex persisted
        // without the clean flag, so we must fall back to the resolver rather
        // than re-paint against a torn half-apply.
        val trustedCached = validCached?.takeIf { cleanCache }
        val accentHex = trustedCached?.value ?: AccentResolver.resolve(null, variant)
        val applied = AccentApplicator.applyFromHexString(accentHex)
        val source =
            when {
                trustedCached != null -> "cached"
                validCached != null -> "cached-untrusted"
                else -> "resolved"
            }
        if (applied) {
            LOG.info(
                "Ayu Islands accent applied in appFrameCreated " +
                    "(source=$source, hex='$accentHex') for ${variant.name}",
            )
        } else {
            LOG.warn(
                "Ayu Islands accent rejected in appFrameCreated " +
                    "(source=$source, hex='$accentHex') for ${variant.name}",
            )
        }
    }

    companion object {
        private val LOG = logger<AyuIslandsAppListener>()
    }
}
