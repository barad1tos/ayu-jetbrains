package dev.ayuislands.syntax

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import kotlin.test.Test
import kotlin.test.fail

/**
 * Phase 49 Plan 49-04 — CI snapshot gate for `axis-keys.txt` sanity.
 *
 * Invariants enforced (D-06):
 *  - The file has EXACTLY 4 `# AXIS:` sections, one per [StyleAxis] entry.
 *  - Every key listed under any axis section exists in the union of BASELINE
 *    keys (extracted from `themes/AyuIslands{Variant}.xml`) ∪ overlay keys
 *    (extracted from `themes/extended/AyuIslands{Variant}.extended.xml`).
 *    No dangling references that the runtime would silently drop.
 *  - The `DIMMED_COMMENTS` section contains no doc-tag keys (no `*DOC*`,
 *    `*KDoc*`, `*Groovydoc*`, `*JavaDoc*`, `*Scaladoc*` substrings) —
 *    keeps the Comments and DocTags axes semantically disjoint per D-06.
 *  - Each axis section is non-empty.
 *
 * Contract change (Option C — axes-orthogonal-to-mood fix for the
 * `syntax-mood-noop-on-editor` bug): the dangling-reference invariant now
 * validates against `baseline ∪ overlay`, not overlay alone. Axes are
 * allowed to target baseline-only keys (e.g. `DEFAULT_LINE_COMMENT`,
 * `JAVA_LINE_COMMENT`) because [SyntaxModeApplicator] applies axis
 * transforms to a baseline clone whenever the target is absent from the
 * overlay.
 *
 * Plain `kotlin.test` + JDOMUtil + classpath resources. No platform fixture.
 */
class AxisKeyAssignmentSnapshotTest {
    private val variants = listOf("Mirage", "Dark", "Light")

    // Regex chunks that flag doc-tag keys we must NOT find in DIMMED_COMMENTS.
    // Both UPPERCASE and the JetBrains mixed-case scheme labels are covered.
    private val docTokens =
        listOf(
            "_DOC_",
            "DOC_TAG",
            "DOCTAG",
            "KDOC",
            "KDoc",
            "JAVADOC",
            "JavaDoc",
            "Javadoc",
            "SCALADOC",
            "Scaladoc",
            "ScalaDoc",
            "GROOVYDOC",
            "Groovydoc",
            "DartDoc",
            "RDoc",
            "Dartdoc",
        )

    @Test
    fun `axis-keys file has exactly 4 axis sections matching StyleAxis entries`() {
        val axes = loadAxisMap()
        val expected = StyleAxis.entries.map { it.name }.toSet()
        val actual = axes.keys
        if (expected != actual) {
            fail(
                "axis-keys.txt must have exactly the 4 StyleAxis sections. " +
                    "Expected=$expected actual=$actual " +
                    "missing=${expected - actual} extra=${actual - expected}",
            )
        }
    }

    @Test
    fun `every axis-keys key exists in baseline or extended overlay (no dangling references)`() {
        val baseline = loadBaselineKeys()
        val overlay = loadOverlayKeys()
        val combined = baseline + overlay
        val axes = loadAxisMap()
        val dangling = mutableMapOf<String, List<String>>()
        for ((axisName, keys) in axes) {
            val missing = keys.filterNot { it in combined }
            if (missing.isNotEmpty()) dangling[axisName] = missing
        }
        if (dangling.isNotEmpty()) {
            val summary =
                dangling.entries.joinToString("\n") { (axis, keys) ->
                    "$axis dangling=${keys.size} firstFew=${keys.take(5)}"
                }
            fail(
                "axis-keys.txt references keys NOT present in baseline or any extended overlay XML:\n" +
                    summary +
                    "\n\nFix: remove the dangling key from axis-keys.txt OR add it to the baseline " +
                    "AyuIslands{Variant}.xml or the extended overlay XML for at least one variant.",
            )
        }
    }

    @Test
    fun `DIMMED_COMMENTS axis does not contain any doc-tag key`() {
        val axes = loadAxisMap()
        val dimmed = axes["DIMMED_COMMENTS"].orEmpty()
        val offenders = dimmed.filter { keyName -> docTokens.any { token -> token in keyName } }
        if (offenders.isNotEmpty()) {
            fail(
                "DIMMED_COMMENTS must NOT include doc-tag keys (Comments and DocTags axes " +
                    "must stay semantically disjoint per D-06). Offending entries: " +
                    "${offenders.take(10)}",
            )
        }
    }

    @Test
    fun `each axis section is non-empty`() {
        val axes = loadAxisMap()
        val empties = axes.filterValues { it.isEmpty() }.keys
        if (empties.isNotEmpty()) {
            fail(
                "Each axis section must list at least one key; empty sections produce a " +
                    "checkbox the user can toggle with zero visual effect. Empty axes: $empties",
            )
        }
    }

    @Test
    fun `ITALIC_DECLARATIONS contains only keys with declaration semantics`() {
        // Less strict — most JetBrains declaration keys end with `_DECLARATION`
        // or are explicit method/function declarations. We allow either explicit
        // suffixes OR an allowlist of manual overrides (currently empty;
        // future curation may add explicit additions here).
        val axes = loadAxisMap()
        val italic = axes["ITALIC_DECLARATIONS"].orEmpty()
        val allowlist = emptySet<String>() // manual additions go here when needed
        val rejects =
            italic.filterNot { keyName ->
                keyName.endsWith("_DECLARATION") ||
                    keyName.contains("DECLARATION") ||
                    keyName in allowlist
            }
        if (rejects.isNotEmpty()) {
            fail(
                "ITALIC_DECLARATIONS keys must have declaration semantics (suffix or substring " +
                    "DECLARATION) OR be in the explicit allowlist. Offending entries: " +
                    "${rejects.take(10)}",
            )
        }
    }

    // ----- helpers -----

    private fun loadOverlayKeys(): Set<String> =
        variants
            .flatMap { variant ->
                val resourcePath = "/themes/extended/AyuIslands$variant.extended.xml"
                val stream =
                    javaClass.getResourceAsStream(resourcePath)
                        ?: fail("Missing classpath resource: $resourcePath")
                val root: Element = stream.use { JDOMUtil.load(it) }
                val attrs = root.getChild("attributes") ?: return@flatMap emptyList()
                attrs.getChildren("option").mapNotNull { it.getAttributeValue("name") }
            }.toSet()

    /**
     * Option C contract — axes may reference baseline-only keys. Reads the
     * `<attributes>` section from each variant's baseline scheme XML at
     * `themes/AyuIslands{Variant}.xml` so the dangling-reference check
     * validates axis keys against the full reachable surface.
     */
    private fun loadBaselineKeys(): Set<String> =
        variants
            .flatMap { variant ->
                val resourcePath = "/themes/AyuIslands$variant.xml"
                val stream =
                    javaClass.getResourceAsStream(resourcePath)
                        ?: fail("Missing classpath resource: $resourcePath")
                val root: Element = stream.use { JDOMUtil.load(it) }
                val attrs = root.getChild("attributes") ?: return@flatMap emptyList()
                attrs.getChildren("option").mapNotNull { it.getAttributeValue("name") }
            }.toSet()

    private fun loadAxisMap(): Map<String, Set<String>> {
        val resourcePath = "/themes/extended/axis-keys.txt"
        val stream =
            javaClass.getResourceAsStream(resourcePath)
                ?: fail("Missing classpath resource: $resourcePath")
        val text = stream.bufferedReader().use { it.readText() }
        val map = mutableMapOf<String, MutableSet<String>>()
        var current: String? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("# AXIS:")) {
                current = line.removePrefix("# AXIS:").trim()
                map[current] = mutableSetOf()
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            current?.let { map[it]!!.add(line) }
        }
        return map
    }
}
