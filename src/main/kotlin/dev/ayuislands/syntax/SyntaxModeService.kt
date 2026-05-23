package dev.ayuislands.syntax

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 49 orchestrator. Applies a (mood, axes) selection across all three
 * Ayu scheme variants by name (R-5) and fires a single scheme-change publish
 * wrapped in [ReadAction] (R-7).
 *
 * Active-scheme write (H5 fix, debug `syntax-mood-noop-on-editor`): the IDE
 * may persist a `_@user_Ayu Islands {Variant}` derived scheme whose
 * `parent_scheme` is `Darcula` rather than our registered Ayu variant. That
 * derived scheme sits in the rendering chain instead of the named one we
 * write to by-name, so by-name writes alone produce zero visual change.
 * After the by-name loop, apply() also writes the same computed payload to
 * `EditorColorsManager.getInstance().globalScheme` whenever the active
 * scheme is NOT one of the three named instances we already touched
 * (identity dedup — no double-write on a clean install).
 *
 * Lifecycle gating (Pattern J) lives in `dev.ayuislands.AyuIslandsLafListener`,
 * not here — the service is callable from the Settings Apply path even
 * mid-LAF-switch (D-09).
 *
 * Per-scheme write failures are isolated (catches RuntimeException only,
 * per Pattern B — broader catches are forbidden): the failing variant logs
 * WARN and the other two still receive their writes; the single publish
 * still fires once per apply(). `CancellationException` propagates
 * unconditionally — the service never swallows it.
 */
@Service(Service.Level.APP)
class SyntaxModeService {
    private val log = logger<SyntaxModeService>()
    private val missingSchemeLogged = ConcurrentHashMap.newKeySet<String>()

    fun apply(
        mood: SyntaxMood,
        axes: Set<StyleAxis>,
    ) {
        val loader = SyntaxOverlayLoader.getInstance()
        val manager = EditorColorsManager.getInstance()
        val touched = mutableSetOf<EditorColorsScheme>()
        for ((schemeName, overlayVariant) in AYU_SCHEMES) {
            val scheme = manager.getScheme(schemeName)
            if (scheme == null) {
                if (missingSchemeLogged.add(schemeName)) {
                    log.warn("Ayu scheme '$schemeName' not registered — skipping syntax overlay")
                }
                continue
            }
            val computed = SyntaxModeApplicator.compute(mood, axes, overlayVariant, loader)
            writeSchemeAttributes(scheme, computed, schemeName)
            touched.add(scheme)
        }
        writeActiveSchemeIfNotTouched(manager, mood, axes, loader, touched)
        publishSchemeChange()
    }

    fun reapplyForActiveLaf() {
        val baseState = SyntaxModeState.getInstance().state
        val mood = SyntaxMood.fromName(baseState.mood)
        val axes =
            baseState.axes.mapNotNullTo(mutableSetOf()) { name ->
                runCatching { StyleAxis.valueOf(name) }.getOrNull()
            }
        apply(mood, axes)
    }

    fun clearAll() {
        apply(SyntaxMood.MINIMAL, emptySet())
    }

    /**
     * H5 fix path. When the active editor scheme is a user-derived
     * `_@user_Ayu Islands {Variant}` (parent_scheme=Darcula, originalScheme=ours)
     * none of the three named schemes sits in the rendering chain. The fix
     * is to ALSO write the computed payload to whatever scheme the IDE is
     * actually rendering from. Identity dedup against [touched] avoids a
     * double-write on the clean-install path where the active scheme IS one
     * of the three named instances we already wrote to.
     *
     * Variant selection: matches the user-derived suffix against the known
     * Ayu variant tokens. Falls back to Mirage (the historical default) when
     * the active scheme name is unrecognized — the worst case is the
     * Mirage overlay's tier whitelist applied to a different scheme, which
     * the user can correct by re-picking the editor scheme.
     */
    private fun writeActiveSchemeIfNotTouched(
        manager: EditorColorsManager,
        mood: SyntaxMood,
        axes: Set<StyleAxis>,
        loader: SyntaxOverlayLoader,
        touched: Set<EditorColorsScheme>,
    ) {
        val active = manager.globalScheme
        if (active in touched) return
        val variant = resolveOverlayVariant(active.name)
        val computed = SyntaxModeApplicator.compute(mood, axes, variant, loader)
        writeSchemeAttributes(active, computed, active.name)
    }

    private fun resolveOverlayVariant(activeSchemeName: String): String {
        for ((_, overlayVariant) in AYU_SCHEMES) {
            if (activeSchemeName.contains(overlayVariant, ignoreCase = true)) {
                return overlayVariant
            }
        }
        return "Mirage"
    }

    private fun writeSchemeAttributes(
        scheme: EditorColorsScheme,
        computed: Map<TextAttributesKey, TextAttributes?>,
        variantName: String,
    ) {
        for ((key, attrs) in computed) {
            try {
                scheme.setAttributes(key, attrs)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                // Pattern B: never swallow CancellationException — propagate so
                // coroutine cancellation semantics remain intact.
                throw cancellation
            } catch (runtime: RuntimeException) {
                // Pattern B: catch RuntimeException only (broader catches are
                // forbidden). Per-scheme write isolation — log + continue with
                // the next key/scheme.
                log.warn("setAttributes failed on $variantName for key ${key.externalName}", runtime)
            }
        }
    }

    private fun publishSchemeChange() {
        ReadAction.run<RuntimeException> {
            ApplicationManager
                .getApplication()
                .messageBus
                .syncPublisher(EditorColorsManager.TOPIC)
                .globalSchemeChange(null)
        }
    }

    companion object {
        // Pairs of (registered scheme name → overlay variant short name).
        // Scheme names match EditorColorsManager.getScheme(...) (R-5).
        // Overlay variant names match SyntaxOverlayLoader.loadOverlayForVariant
        // which composes `/themes/extended/AyuIslands{Variant}.extended.xml`.
        private val AYU_SCHEMES =
            listOf(
                "Ayu Islands Mirage" to "Mirage",
                "Ayu Islands Dark" to "Dark",
                "Ayu Islands Light" to "Light",
            )

        fun getInstance(): SyntaxModeService {
            val app = ApplicationManager.getApplication()
            return app.getService(SyntaxModeService::class.java)
        }
    }
}
