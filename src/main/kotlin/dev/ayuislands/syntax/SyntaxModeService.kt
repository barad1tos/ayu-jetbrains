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
        for ((schemeName, overlayVariant) in AYU_SCHEMES) {
            val scheme = EditorColorsManager.getInstance().getScheme(schemeName)
            if (scheme == null) {
                if (missingSchemeLogged.add(schemeName)) {
                    log.warn("Ayu scheme '$schemeName' not registered — skipping syntax overlay")
                }
                continue
            }
            val computed = SyntaxModeApplicator.compute(mood, axes, overlayVariant, loader)
            writeSchemeAttributes(scheme, computed, schemeName)
        }
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
