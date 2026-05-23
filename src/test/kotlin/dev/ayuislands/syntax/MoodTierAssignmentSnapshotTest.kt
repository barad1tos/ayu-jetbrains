package dev.ayuislands.syntax

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail

/**
 * Phase 49 Plan 49-04 — CI snapshot gate for the D-05 tier partition invariant
 * AND the warning-7b tolerance lock binding `SyntaxMood.approximateKeyCount`
 * to the actual curated tier sizes.
 *
 * Invariants enforced (D-05 — curation rule):
 *  - Every key in `extended/AyuIslands{Variant}.extended.xml` appears in
 *    EXACTLY ONE tier section of `mood-tiers.txt` (STANDARD ∪ RICH ∪ MAXIMUM).
 *  - Zero orphans (overlay key with no tier assignment).
 *  - Zero duplicates (key in two or more tiers — would split behavior on
 *    mid-tier mood switch).
 *  - `# TIER: MINIMAL` does NOT appear (MINIMAL is the implicit empty subset
 *    per D-04).
 *  - Tier headers appear in declaration order STANDARD → RICH → MAXIMUM.
 *
 * Warning 7b — tolerance lock (revision iteration 1):
 *  - For each mood, `|baseline(1488) + cumulative_tier_size − approximateKeyCount| ≤ 150`.
 *  - Fails if curation drifts beyond the UI-rounding tolerance OR if the enum is
 *    bumped without re-curating tiers — both arms force a deliberate update to
 *    the other side, keeping `SyntaxMood.kt` and `mood-tiers.txt` in sync.
 *
 * Plain `kotlin.test` + JDOMUtil + classpath resources. No platform fixture.
 */
class MoodTierAssignmentSnapshotTest {
    private val variants = listOf("Mirage", "Dark", "Light")

    // Per-variant baseline `<option>` count at commit 5673357 (Plan 49-01 revert
    // target). Each Ayu scheme XML carries 1488 baseline attribute entries; the
    // overlay then layers tier-classified additions on top.
    private val baseline = 1488

    // UI-rounding tolerance: 150 ≈ 1.5× the rounding step the radio labels use.
    // Large enough to absorb routine curation tweaks without false alarms;
    // tight enough to catch real drift before users see mismatched key counts.
    private val tolerance = 150

    // Mood iteration order — additive whitelist application sequence per D-04:
    // MINIMAL ⊂ STANDARD ⊂ RICH ⊂ MAXIMUM.
    private val cumulativeOrder =
        listOf(
            SyntaxMood.MINIMAL,
            SyntaxMood.STANDARD,
            SyntaxMood.RICH,
            SyntaxMood.MAXIMUM,
        )

    @Test
    fun `every delta key appears in exactly one tier (D-05)`() {
        val overlay = loadOverlayKeys()
        val tiers = loadTierMap()
        val unioned = tiers.values.flatten().toSet()

        val orphans = overlay - unioned
        if (orphans.isNotEmpty()) {
            fail(
                "D-05 violation: ${orphans.size} overlay keys with NO tier assignment in " +
                    "mood-tiers.txt. First 10: ${orphans.sorted().take(10)}",
            )
        }

        val standard = tiers["STANDARD"] ?: emptySet()
        val rich = tiers["RICH"] ?: emptySet()
        val maximum = tiers["MAXIMUM"] ?: emptySet()
        val srOverlap = standard intersect rich
        val smOverlap = standard intersect maximum
        val rmOverlap = rich intersect maximum
        if (srOverlap.isNotEmpty() || smOverlap.isNotEmpty() || rmOverlap.isNotEmpty()) {
            fail(
                "D-05 violation: tier duplicates. STANDARD∩RICH=${srOverlap.take(5)} " +
                    "STANDARD∩MAXIMUM=${smOverlap.take(5)} RICH∩MAXIMUM=${rmOverlap.take(5)}",
            )
        }
    }

    @Test
    fun `no orphan keys (every overlay key has a tier assignment)`() {
        val overlay = loadOverlayKeys()
        val tiers = loadTierMap()
        val unioned = tiers.values.flatten().toSet()
        val orphans = overlay - unioned
        if (orphans.isNotEmpty()) {
            fail(
                "D-05 orphan check failed: ${orphans.size} overlay keys not classified " +
                    "in any tier. First 10: ${orphans.sorted().take(10)}",
            )
        }
    }

    @Test
    fun `no duplicate keys (no key appears in two or more tiers)`() {
        val tiers = loadTierMap()
        val standard = tiers["STANDARD"] ?: emptySet()
        val rich = tiers["RICH"] ?: emptySet()
        val maximum = tiers["MAXIMUM"] ?: emptySet()

        val srOverlap = standard intersect rich
        val smOverlap = standard intersect maximum
        val rmOverlap = rich intersect maximum

        if (srOverlap.isNotEmpty()) fail("STANDARD∩RICH must be empty: ${srOverlap.take(10)}")
        if (smOverlap.isNotEmpty()) fail("STANDARD∩MAXIMUM must be empty: ${smOverlap.take(10)}")
        if (rmOverlap.isNotEmpty()) fail("RICH∩MAXIMUM must be empty: ${rmOverlap.take(10)}")
    }

    @Test
    fun `tier sections appear in declaration order STANDARD then RICH then MAXIMUM`() {
        val text = readTierFileText()
        val standardIdx = text.indexOf("# TIER: STANDARD")
        val richIdx = text.indexOf("# TIER: RICH")
        val maxIdx = text.indexOf("# TIER: MAXIMUM")
        if (standardIdx < 0) fail("# TIER: STANDARD header missing")
        if (richIdx < 0) fail("# TIER: RICH header missing")
        if (maxIdx < 0) fail("# TIER: MAXIMUM header missing")
        if (standardIdx >= richIdx) {
            fail("STANDARD header (offset $standardIdx) must precede RICH (offset $richIdx)")
        }
        if (richIdx >= maxIdx) {
            fail("RICH header (offset $richIdx) must precede MAXIMUM (offset $maxIdx)")
        }
    }

    @Test
    fun `MINIMAL tier is implicit empty set (no section in txt)`() {
        val text = readTierFileText()
        if (text.contains("# TIER: MINIMAL")) {
            fail(
                "MINIMAL must NOT have its own tier section per D-04 (MINIMAL is the empty " +
                    "subset of the overlay; whitelist materializes via tier accumulation).",
            )
        }
    }

    // Revision iteration 1, warning 7b — tolerance lock binding tier curation
    // to enum values. Fails if either side drifts; fix message names both
    // remediation arms.
    @Test
    fun `SyntaxMood approximateKeyCount stays within 150 of cumulative tier sizes (warning 7b)`() {
        val tiers = loadTierMap()
        val tierSize: (SyntaxMood) -> Int = { mood ->
            when (mood) {
                SyntaxMood.MINIMAL -> 0
                SyntaxMood.STANDARD -> tiers["STANDARD"]?.size ?: 0
                SyntaxMood.RICH -> tiers["RICH"]?.size ?: 0
                SyntaxMood.MAXIMUM -> tiers["MAXIMUM"]?.size ?: 0
            }
        }
        val drifts = mutableListOf<String>()
        for (mood in cumulativeOrder) {
            val cumulative =
                cumulativeOrder
                    .takeWhile { it.ordinal <= mood.ordinal }
                    .sumOf(tierSize)
            val expectedActual = baseline + cumulative
            val drift = abs(expectedActual - mood.approximateKeyCount)
            if (drift > tolerance) {
                drifts +=
                    "${mood.name}: approximateKeyCount=${mood.approximateKeyCount} " +
                    "vs actual cumulative (baseline $baseline + tier sum $cumulative) = $expectedActual " +
                    "(drift=$drift > $tolerance)"
            }
        }
        if (drifts.isNotEmpty()) {
            fail(
                "SyntaxMood.approximateKeyCount drifted beyond UI-rounding tolerance:\n" +
                    drifts.joinToString("\n") +
                    "\n\nFix path: either update SyntaxMood.kt approximateKeyCount values to fresh " +
                    "UI-rounded numbers OR re-run Plan 49-01 curation to bring tier sizes back within " +
                    "tolerance. See SyntaxMood KDoc for the canonical-count formula.",
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

    private fun loadTierMap(): Map<String, Set<String>> {
        val text = readTierFileText()
        val map = mutableMapOf<String, MutableSet<String>>()
        var current: String? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("# TIER:")) {
                current = line.removePrefix("# TIER:").trim()
                map[current] = mutableSetOf()
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            current?.let { map[it]!!.add(line) }
        }
        return map
    }

    private fun readTierFileText(): String {
        val resourcePath = "/themes/extended/mood-tiers.txt"
        val stream =
            javaClass.getResourceAsStream(resourcePath)
                ?: fail("Missing classpath resource: $resourcePath")
        return stream.bufferedReader().use { it.readText() }
    }
}
