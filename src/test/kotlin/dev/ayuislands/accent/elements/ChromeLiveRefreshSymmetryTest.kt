package dev.ayuislands.accent.elements

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * D-14 symmetry gate — locks the invariant that every chrome element has a
 * declared [dev.ayuislands.accent.ChromeTarget] `peerTarget`, and that the
 * base [AbstractChromeElement] dispatches both refresh (in `apply`) and clear
 * (in `revert`) for every ChromeTarget variant.
 *
 * Phase 40.3c Refactor 1 moved the per-element `LiveChromeRefresher.refresh*` /
 * `clear*` calls into [AbstractChromeElement] — the subclasses now declare
 * `peerTarget` and the base handles both sides of the symmetry. The invariant
 * therefore splits in two:
 *
 *   1. Each element subclass declares a non-null `peerTarget` (else its peer
 *      would never be mutated on apply, a Gap-4 regression).
 *   2. The base class wires refresh + clear for every ChromeTarget variant
 *      (StatusBar, ByClassName, ByClassNameInside) — a missing arm would leak
 *      an explicit background color on the peer.
 *
 * Comments are stripped so KDoc that documents the pattern doesn't false-pass.
 */
class ChromeLiveRefreshSymmetryTest {
    private val elementFiles =
        listOf(
            "StatusBarElement",
            "NavBarElement",
            "ToolWindowStripeElement",
            "MainToolbarElement",
            "PanelBorderElement",
        )

    @Test
    fun `every chrome element declares a ChromeTarget peerTarget`() {
        for (name in elementFiles) {
            val source = readStripped(name)
            assertTrue(
                Regex("""override\s+val\s+peerTarget""").containsMatchIn(source),
                "$name must override `peerTarget` so the base class (AbstractChromeElement) " +
                    "can dispatch the live peer refresh (Gap 4).",
            )
            assertTrue(
                source.contains("ChromeTarget."),
                "$name must reference a ChromeTarget variant (StatusBar / ByClassName / " +
                    "ByClassNameInside) in its peerTarget declaration.",
            )
        }
    }

    @Test
    fun `base AbstractChromeElement wires refresh and clear for every ChromeTarget variant`() {
        val source = readAbstractBaseStripped()
        val refreshRoutes =
            listOf(
                "LiveChromeRefresher.refreshStatusBar",
                "LiveChromeRefresher.refreshByClassName",
                "LiveChromeRefresher.refreshByClassNameInsideAncestorClass",
            )
        val clearRoutes =
            listOf(
                "LiveChromeRefresher.clearStatusBar",
                "LiveChromeRefresher.clearByClassName",
                "LiveChromeRefresher.clearByClassNameInsideAncestorClass",
            )
        for (entry in refreshRoutes) {
            assertTrue(
                source.contains(entry),
                "AbstractChromeElement must wire $entry for its matching ChromeTarget variant.",
            )
        }
        for (entry in clearRoutes) {
            assertTrue(
                source.contains(entry),
                "AbstractChromeElement must wire $entry for D-14 symmetry on its matching " +
                    "ChromeTarget variant.",
            )
        }
    }

    @Test
    fun `base AbstractChromeElement refresh and clear routes are balanced`() {
        val source = readAbstractBaseStripped()
        val refreshCount = refreshCallCount(source)
        val clearCount = clearCallCount(source)
        if (refreshCount != clearCount) {
            fail(
                "AbstractChromeElement has asymmetric LiveChromeRefresher wiring — " +
                    "refresh=$refreshCount clear=$clearCount. D-14 requires every refresh " +
                    "to have a matching clear so revert hands the peer back to the LAF default.",
            )
        }
    }

    private fun readStripped(elementName: String): String {
        val path: Path =
            Paths.get(
                System.getProperty("user.dir"),
                "src",
                "main",
                "kotlin",
                "dev",
                "ayuislands",
                "accent",
                "elements",
                "$elementName.kt",
            )
        return stripComments(Files.readString(path))
    }

    private fun readAbstractBaseStripped(): String = readStripped("AbstractChromeElement")

    /** Strips `/* ... */` and `// ...` so KDoc mentioning banned/expected patterns can't swing the count. */
    private fun stripComments(input: String): String {
        val noBlock = input.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock
            .lineSequence()
            .map { line -> line.replaceFirst(Regex("//.*$"), "") }
            .joinToString("\n")
    }

    private fun refreshCallCount(source: String): Int {
        val pattern = Regex("""LiveChromeRefresher\.refresh[A-Za-z]+""")
        return pattern.findAll(source).count()
    }

    private fun clearCallCount(source: String): Int {
        val pattern = Regex("""LiveChromeRefresher\.clear[A-Za-z]+""")
        return pattern.findAll(source).count()
    }
}
