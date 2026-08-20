package dev.ayuislands.syntax

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import org.jdom.Element
import java.util.concurrent.ConcurrentHashMap

private data class ParsedColorField(
    val name: String,
    val color: JBColor,
)

private data class NormalizedAttributes(
    val element: Element,
    val colors: List<ParsedColorField>,
)

/**
 * Overlay loader for the `AyuIslands{Variant}.extended.xml` scheme overlays
 * plus the matching baseline `AyuIslands{Variant}.xml` scheme attributes.
 * All public accessors return cached results after the first call (per-instance
 * cache — each [SyntaxOverlayLoader] instance owns its own cache, so tests
 * using the [resourceBase] seam get isolation).
 *
 * **Test seam (warning #2):** the [resourceBase] constructor parameter lets
 * tests inject `/themes/extended-test` so the loader exercises edge paths
 * (missing key, base-attribute reference) against controlled fixtures instead
 * of production XMLs. The default `/themes/extended` matches the production
 * resource layout. The [baselineResourceBase] seam mirrors the same pattern
 * for the baseline scheme XMLs (`AyuIslands{Variant}.xml`) consumed by
 * [loadBaselineForVariant].
 *
 * **Graceful degradation:**
 * - Malformed XML or missing resource files → log WARN once per resource via
 *   the [warnedResources] latch (Pattern A — no silent `?: continue`), return
 *   empty map for that variant.
 * - `baseAttributes="REF"` entries: return empty `TextAttributes()` so the
 *   active editor scheme resolves the reference through its own inheritance
 *   chain instead of baking platform-default colors into the overlay.
 *
 * **Baseline path:** [loadBaselineForVariant] returns the variant's baseline
 * `<attributes>` section so downstream consumers can read the curated baseline
 * semantic-key universe without re-parsing the scheme XML themselves.
 */
@Service(Service.Level.APP)
class SyntaxOverlayLoader internal constructor(
    internal val resourceBase: String = DEFAULT_RESOURCE_BASE,
    internal val baselineResourceBase: String = DEFAULT_BASELINE_BASE,
) {
    private val log = logger<SyntaxOverlayLoader>()
    private val warnedResources = ConcurrentHashMap.newKeySet<String>()

    private val overlayCache = ConcurrentHashMap<String, Map<TextAttributesKey, TextAttributes>>()
    private val baselineCache = ConcurrentHashMap<String, Map<TextAttributesKey, TextAttributes>>()

    fun loadOverlayForVariant(variantName: String): Map<TextAttributesKey, TextAttributes> =
        overlayCache.computeIfAbsent(variantName) { parseOverlayXml(it) }

    /**
     * Loads the baseline scheme `<attributes>` section for [variantName] from
     * `$baselineResourceBase/AyuIslands{Variant}.xml`. Cached per variant.
     *
     * Returns empty map on missing/malformed resource (same graceful-degradation
     * contract as [loadOverlayForVariant]).
     */
    fun loadBaselineForVariant(variantName: String): Map<TextAttributesKey, TextAttributes> =
        baselineCache.computeIfAbsent(variantName) { parseBaselineXml(it) }

    private fun parseOverlayXml(variantName: String): Map<TextAttributesKey, TextAttributes> {
        val path = "$resourceBase/AyuIslands$variantName.extended.xml"
        return parseAttributesXml(path)
    }

    private fun parseBaselineXml(variantName: String): Map<TextAttributesKey, TextAttributes> {
        val path = "$baselineResourceBase/AyuIslands$variantName.xml"
        return parseAttributesXml(path)
    }

    private fun parseAttributesXml(path: String): Map<TextAttributesKey, TextAttributes> {
        val stream =
            openClasspathResource(path) ?: run {
                logResourceOnce(path, "scheme XML resource not found")
                return emptyMap()
            }
        return try {
            val root = stream.use { JDOMUtil.load(it) }
            val attributesEl = root.getChild("attributes") ?: return emptyMap()
            buildOverlayMap(attributesEl)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (runtime: RuntimeException) {
            logResourceOnce(path, "failed to parse scheme XML: ${runtime.message}")
            emptyMap()
        }
    }

    /**
     * Resolves [path] against the broadest available classloader. `javaClass`-rooted
     * lookups (`SyntaxOverlayLoader::class.java.getResourceAsStream`) only see the
     * main classpath; test fixtures live on the test classpath which is reachable
     * via the test thread's context classloader. Falls back to the loader's own
     * classloader when no context loader is set (production / non-test code paths).
     */
    private fun openClasspathResource(path: String): java.io.InputStream? {
        val normalized = path.trimStart('/')
        Thread
            .currentThread()
            .contextClassLoader
            ?.getResourceAsStream(normalized)
            ?.let { return it }
        return javaClass.classLoader.getResourceAsStream(normalized)
    }

    private fun buildOverlayMap(attributesEl: Element): Map<TextAttributesKey, TextAttributes> {
        val map = mutableMapOf<TextAttributesKey, TextAttributes>()
        for (optionEl in JDOMUtil.getChildren(attributesEl, "option")) {
            val keyName = optionEl.getAttributeValue("name") ?: continue
            val key = TextAttributesKey.find(keyName)
            val baseRef = optionEl.getAttributeValue("baseAttributes")
            val attrs =
                when {
                    // baseAttributes keys (e.g. GO_STRING -> DEFAULT_STRING) carry no own
                    // foreground — they inherit from baseRef at render time via the scheme's
                    // own resolution. Returning empty TextAttributes (no foreground) makes the
                    // applicator skip them (sourceFg == null -> continue), so the scheme's
                    // natural inheritance renders them. Resolving baseRef.defaultAttributes here
                    // would bake the PLATFORM-default color (Darcula) instead of the active Ayu
                    // value, overwriting inherited string/comment keys with a muted fallback.
                    baseRef != null -> TextAttributes()
                    else -> {
                        val valueEl = optionEl.getChild("value") ?: continue
                        try {
                            parseTextAttributes(valueEl)
                        } catch (cancellation: kotlinx.coroutines.CancellationException) {
                            throw cancellation
                        } catch (runtime: RuntimeException) {
                            log.warn("[SyntaxOverlayLoader] failed to parse <value> for '$keyName': ${runtime.message}")
                            TextAttributes()
                        }
                    }
                }
            map[key] = attrs
        }
        return map
    }

    private fun parseTextAttributes(valueElement: Element): TextAttributes {
        val input = normalizeRgbaFields(valueElement)
        return TextAttributes(input.element).also { attributes ->
            input.colors.forEach { field -> applyColor(attributes, field) }
        }
    }

    private fun normalizeRgbaFields(valueElement: Element): NormalizedAttributes {
        val normalizedElement = valueElement.clone()
        val colors = mutableListOf<ParsedColorField>()
        for (fieldElement in JDOMUtil.getChildren(normalizedElement, "option")) {
            val fieldName = fieldElement.getAttributeValue("name") ?: continue
            val value = fieldElement.getAttributeValue("value") ?: continue
            if (fieldName !in COLOR_FIELDS || value.length != RGBA_LENGTH) continue

            colors += ParsedColorField(fieldName, parseRgba(value))
            fieldElement.setAttribute("value", value.take(RGB_LENGTH))
        }
        return NormalizedAttributes(normalizedElement, colors)
    }

    private fun applyColor(
        attributes: TextAttributes,
        field: ParsedColorField,
    ) {
        when (field.name) {
            "FOREGROUND" -> attributes.foregroundColor = field.color
            "BACKGROUND" -> attributes.backgroundColor = field.color
            "EFFECT_COLOR" -> attributes.effectColor = field.color
            "ERROR_STRIPE_COLOR" -> attributes.errorStripeColor = field.color
            else -> error("Unsupported text-attribute color field: ${field.name}")
        }
    }

    private fun parseRgba(value: String): JBColor {
        val rgb = ColorUtil.fromHex(value.take(RGB_LENGTH))
        val rgba = ColorUtil.toAlpha(rgb, value.drop(RGB_LENGTH).toInt(HEX_RADIX))
        return JBColor(rgba, rgba)
    }

    private fun logResourceOnce(
        path: String,
        message: String,
    ) {
        if (warnedResources.add(path)) {
            log.warn("[SyntaxOverlayLoader] $path — $message")
        }
    }

    companion object {
        internal const val DEFAULT_RESOURCE_BASE = "/themes/extended"
        internal const val DEFAULT_BASELINE_BASE = "/themes"
        private const val HEX_RADIX = 16
        private const val RGB_LENGTH = 6
        private const val RGBA_LENGTH = 8
        private val COLOR_FIELDS = setOf("FOREGROUND", "BACKGROUND", "EFFECT_COLOR", "ERROR_STRIPE_COLOR")

        fun getInstance(): SyntaxOverlayLoader {
            val app = ApplicationManager.getApplication()
            return app.getService(SyntaxOverlayLoader::class.java)
        }
    }
}
