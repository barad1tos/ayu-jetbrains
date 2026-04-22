package dev.ayuislands.accent.elements

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * D-14 symmetry gate — locks the invariant that every chrome element file wires
 * BOTH a [dev.ayuislands.accent.LiveChromeRefresher] refresh call (in `apply`) AND
 * a matching clear call (in `revert`).
 *
 * Reads element source files as text and asserts the ratio refresh-to-clear. A
 * file with a refresh but no clear is a D-14 violation (stale explicit background
 * lingers on the peer after revert; user cannot turn off chrome tinting without
 * an IDE restart).
 *
 * Comments are stripped so KDoc that documents the pattern doesn't false-pass a
 * regression where the actual call site was deleted.
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
    fun `every chrome element has at least one LiveChromeRefresher refresh invocation`() {
        for (name in elementFiles) {
            val source = readStripped(name)
            val refreshCount = refreshCallCount(source)
            assertTrue(
                refreshCount >= 1,
                "$name must wire at least one LiveChromeRefresher.refresh* call " +
                    "in apply() (Gap 4). Found: $refreshCount",
            )
        }
    }

    @Test
    fun `every chrome element has at least one LiveChromeRefresher clear invocation`() {
        for (name in elementFiles) {
            val source = readStripped(name)
            val clearCount = clearCallCount(source)
            assertTrue(
                clearCount >= 1,
                "$name must wire at least one LiveChromeRefresher.clear* call " +
                    "in revert() (D-14 symmetry). Found: $clearCount",
            )
        }
    }

    @Test
    fun `every refresh invocation is paired with a matching clear in the same file`() {
        for (name in elementFiles) {
            val source = readStripped(name)
            val refreshCount = refreshCallCount(source)
            val clearCount = clearCallCount(source)
            if (refreshCount != clearCount) {
                fail(
                    "$name has asymmetric LiveChromeRefresher wiring — " +
                        "refresh=$refreshCount clear=$clearCount. " +
                        "D-14 requires every refresh to have a matching clear so revert " +
                        "hands the peer back to the LAF default.",
                )
            }
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
