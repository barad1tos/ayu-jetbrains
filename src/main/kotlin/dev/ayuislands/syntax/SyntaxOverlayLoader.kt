package dev.ayuislands.syntax

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 49 overlay loader. Parses the three `AyuIslands{Variant}.extended.xml`
 * scheme overlays plus `mood-tiers.txt` and `axis-keys.txt` into cached
 * in-memory maps. All public accessors return cached results after the first
 * call (per-instance cache — each [SyntaxOverlayLoader] instance owns its own
 * cache, so tests using the [resourceBase] seam get isolation).
 *
 * **Test seam (warning #2):** the [resourceBase] constructor parameter lets
 * tests inject `/themes/extended-test` or `/themes/extended-missing-tier` so
 * the loader exercises edge paths (missing tier, unknown key, base-attribute
 * reference) against controlled fixtures instead of production XMLs. The
 * default `/themes/extended` matches the production resource layout. The
 * [baselineResourceBase] seam mirrors the same pattern for the baseline
 * scheme XMLs (`AyuIslands{Variant}.xml`) consumed by
 * [loadBaselineForVariant].
 *
 * **Graceful degradation (T-49-05, T-49-06):**
 * - Malformed XML or missing resource files → log WARN once per resource via
 *   the [warnedResources] latch (Pattern A — no silent `?: continue`), return
 *   empty map for that variant/tier.
 * - Unknown keys in tier / axis txt sections (not present in any extended XML)
 *   → log INFO once per name via [warnedUnknownKeys], skip.
 * - `baseAttributes="REF"` entries: resolve via `TextAttributesKey.find(REF)
 *   .defaultAttributes`. If null (plugin absent), fall back to empty
 *   `TextAttributes()` and continue (no throw).
 *
 * **Axes-orthogonal-to-mood path (debug `syntax-mood-noop-on-editor` fix):**
 * [loadBaselineForVariant] returns the variant's baseline `<attributes>`
 * section so [SyntaxModeApplicator] can apply axis transforms (dim, italic,
 * bold) to keys that live ONLY in baseline (e.g. `DEFAULT_LINE_COMMENT`,
 * `JAVA_LINE_COMMENT`) and are not present in any mood overlay. This makes
 * style axes independent of the mood whitelist — toggling DIMMED_COMMENTS
 * dims comments regardless of whether MINIMAL/STANDARD/RICH/MAXIMUM is
 * active, because the transform operates on a baseline clone rather than on
 * an overlay clone.
 */
@Service(Service.Level.APP)
class SyntaxOverlayLoader internal constructor(
    internal val resourceBase: String = DEFAULT_RESOURCE_BASE,
    internal val baselineResourceBase: String = DEFAULT_BASELINE_BASE,
) {
    private val log = logger<SyntaxOverlayLoader>()
    private val warnedResources = ConcurrentHashMap.newKeySet<String>()
    private val warnedUnknownKeys = ConcurrentHashMap.newKeySet<String>()

    private val overlayCache = ConcurrentHashMap<String, Map<TextAttributesKey, TextAttributes>>()
    private val baselineCache = ConcurrentHashMap<String, Map<TextAttributesKey, TextAttributes>>()
    private val tierCache = ConcurrentHashMap<SyntaxMood, Set<TextAttributesKey>>()
    private val axisCache = ConcurrentHashMap<StyleAxis, Set<TextAttributesKey>>()

    private val tierIndex: Map<SyntaxMood, Set<TextAttributesKey>> by lazy { parseTierFile() }
    private val axisIndex: Map<StyleAxis, Set<TextAttributesKey>> by lazy { parseAxisFile() }

    fun loadOverlayForVariant(variantName: String): Map<TextAttributesKey, TextAttributes> =
        overlayCache.computeIfAbsent(variantName) { parseOverlayXml(it) }

    /**
     * Loads the baseline scheme `<attributes>` section for [variantName] from
     * `$baselineResourceBase/AyuIslands{Variant}.xml`. Cached per variant.
     *
     * Returns empty map on missing/malformed resource (same graceful-degradation
     * contract as [loadOverlayForVariant]).
     *
     * Consumed by [SyntaxModeApplicator] to make style axes orthogonal to the
     * mood whitelist — axis transforms (dim, italic, bold) apply equally to
     * baseline-only keys and overlay keys.
     */
    fun loadBaselineForVariant(variantName: String): Map<TextAttributesKey, TextAttributes> =
        baselineCache.computeIfAbsent(variantName) { parseBaselineXml(it) }

    fun tierKeys(tier: SyntaxMood): Set<TextAttributesKey> {
        if (tier == SyntaxMood.MINIMAL) return emptySet()
        return tierCache.computeIfAbsent(tier) { tierIndex[it].orEmpty() }
    }

    fun axisKeys(axis: StyleAxis): Set<TextAttributesKey> = axisCache.computeIfAbsent(axis) { axisIndex[it].orEmpty() }

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
        return runCatching {
            val root = stream.use { JDOMUtil.load(it) }
            val attributesEl = root.getChild("attributes") ?: return@runCatching emptyMap()
            buildOverlayMap(attributesEl)
        }.getOrElse { error ->
            logResourceOnce(path, "failed to parse scheme XML: ${error.message}")
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
                        runCatching { TextAttributes(valueEl) }.getOrNull() ?: TextAttributes()
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

    private fun parseTierFile(): Map<SyntaxMood, Set<TextAttributesKey>> {
        val knownKeys = collectKnownKeys()
        val sections = parseSectionFile(TIER_FILE, TIER_HEADER_PREFIX)
        val result = mutableMapOf<SyntaxMood, Set<TextAttributesKey>>()
        for ((sectionName, names) in sections) {
            val mood = runCatching { SyntaxMood.valueOf(sectionName) }.getOrNull() ?: continue
            if (mood == SyntaxMood.MINIMAL) continue
            result[mood] = resolveKeyNames(names, knownKeys)
        }
        return result
    }

    private fun parseAxisFile(): Map<StyleAxis, Set<TextAttributesKey>> {
        val knownKeys = collectKnownAxisKeys()
        val sections = parseSectionFile(AXIS_FILE, AXIS_HEADER_PREFIX)
        val result = mutableMapOf<StyleAxis, Set<TextAttributesKey>>()
        for ((sectionName, names) in sections) {
            val axis = runCatching { StyleAxis.valueOf(sectionName) }.getOrNull() ?: continue
            result[axis] = resolveKeyNames(names, knownKeys)
        }
        return result
    }

    private fun collectKnownKeys(): Map<String, TextAttributesKey> {
        val combined = mutableMapOf<String, TextAttributesKey>()
        for (variant in VARIANT_NAMES) {
            for ((key, _) in loadOverlayForVariant(variant)) {
                combined.putIfAbsent(key.externalName, key)
            }
        }
        return combined
    }

    /**
     * Axis-keys can reference EITHER overlay keys (existing path) OR baseline-only
     * keys (Option C — axes-orthogonal-to-mood fix for `syntax-mood-noop-on-editor`).
     * Returns the union so DIMMED_COMMENTS / ITALIC_DECLARATIONS / etc. can list
     * baseline comment / declaration keys without tripping the unknown-key skip.
     */
    private fun collectKnownAxisKeys(): Map<String, TextAttributesKey> {
        val combined = mutableMapOf<String, TextAttributesKey>()
        for (variant in VARIANT_NAMES) {
            for ((key, _) in loadOverlayForVariant(variant)) {
                combined.putIfAbsent(key.externalName, key)
            }
            for ((key, _) in loadBaselineForVariant(variant)) {
                combined.putIfAbsent(key.externalName, key)
            }
        }
        return combined
    }

    private fun resolveKeyNames(
        names: List<String>,
        knownKeys: Map<String, TextAttributesKey>,
    ): Set<TextAttributesKey> {
        val resolved = LinkedHashSet<TextAttributesKey>()
        for (name in names) {
            val key = knownKeys[name]
            if (key == null) {
                if (warnedUnknownKeys.add(name)) {
                    log.info("Skipping unknown overlay key '$name' (not present in any extended XML)")
                }
                continue
            }
            resolved.add(key)
        }
        return resolved
    }

    private fun parseSectionFile(
        fileName: String,
        headerPrefix: String,
    ): Map<String, List<String>> {
        val path = "$resourceBase/$fileName"
        val stream =
            openClasspathResource(path) ?: run {
                logResourceOnce(path, "section file not found")
                return emptyMap()
            }
        return runCatching {
            stream.bufferedReader().use { reader ->
                parseSections(reader.readLines(), headerPrefix)
            }
        }.getOrElse { error ->
            logResourceOnce(path, "failed to parse section file: ${error.message}")
            emptyMap()
        }
    }

    private fun parseSections(
        lines: List<String>,
        headerPrefix: String,
    ): Map<String, List<String>> {
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith(headerPrefix)) {
                val sectionName = line.removePrefix(headerPrefix).trim()
                current = sections.getOrPut(sectionName) { mutableListOf() }
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            current?.add(line)
        }
        return sections
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
        private const val TIER_FILE = "mood-tiers.txt"
        private const val AXIS_FILE = "axis-keys.txt"
        private const val TIER_HEADER_PREFIX = "# TIER:"
        private const val AXIS_HEADER_PREFIX = "# AXIS:"
        private val VARIANT_NAMES = listOf("Mirage", "Dark", "Light")

        fun getInstance(): SyntaxOverlayLoader {
            val app = ApplicationManager.getApplication()
            return app.getService(SyntaxOverlayLoader::class.java)
        }
    }
}
