package dev.ayuislands.syntax

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil
import java.util.concurrent.ConcurrentHashMap

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
 * - `baseAttributes="REF"` entries: resolve via `TextAttributesKey.find(REF)
 *   .defaultAttributes`. If null (plugin absent), fall back to empty
 *   `TextAttributes()` and continue (no throw).
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

    private fun buildOverlayMap(attributesEl: org.jdom.Element): Map<TextAttributesKey, TextAttributes> {
        val map = mutableMapOf<TextAttributesKey, TextAttributes>()
        for (optionEl in JDOMUtil.getChildren(attributesEl, "option")) {
            val keyName = optionEl.getAttributeValue("name") ?: continue
            val key = TextAttributesKey.find(keyName)
            val baseRef = optionEl.getAttributeValue("baseAttributes")
            val attrs =
                when {
                    baseRef != null -> resolveBaseAttributes(baseRef)
                    else -> {
                        val valueEl = optionEl.getChild("value") ?: continue
                        try {
                            TextAttributes(valueEl)
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

    private fun resolveBaseAttributes(baseRef: String): TextAttributes {
        val baseKey = TextAttributesKey.find(baseRef)
        return baseKey.defaultAttributes?.clone() ?: TextAttributes()
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

        fun getInstance(): SyntaxOverlayLoader {
            val app = ApplicationManager.getApplication()
            return app.getService(SyntaxOverlayLoader::class.java)
        }
    }
}
