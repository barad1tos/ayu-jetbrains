package dev.ayuislands.glow

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.fail

/**
 * Pattern L structural lock for the D-02 lifecycle guard.
 *
 * Companion to [GlowFallbackBannedApiGuardTest], which forbids the OLD shape
 * (DEFAULT_ACCENT_HEX fallbacks). This test pins the NEW shape: the head of
 * `updateGlow()` must open with the literal sequence
 *
 *   if (!AyuVariant.isAyuActive()) {
 *       removeAllOverlays()
 *       return
 *   }
 *
 * A future agent who silently reorders the steps (e.g. moving `return` before
 * `removeAllOverlays()` so the dispose never runs, or replacing the predicate
 * with `AyuVariant.detect() == null` and bypassing Pattern J) gets a named
 * regression that names this test rather than a generic visual bug report.
 */
class GlowLifecycleGuardTest {
    private val source: String by lazy {
        val path: Path =
            Paths.get(
                System.getProperty("user.dir"),
                "src",
                "main",
                "kotlin",
                "dev",
                "ayuislands",
                "glow",
                "GlowOverlayManager.kt",
            )
        stripComments(Files.readString(path))
    }

    private fun stripComments(input: String): String {
        val noBlock = input.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock
            .lineSequence()
            .map { line -> line.replaceFirst(Regex("//.*$"), "") }
            .joinToString("\n")
    }

    private fun extractFunctionBody(
        source: String,
        signaturePrefix: String,
    ): String {
        val start = source.indexOf(signaturePrefix)
        require(start >= 0) { "Could not locate '$signaturePrefix' in stripped source" }
        val openBrace = source.indexOf('{', start)
        require(openBrace >= 0) { "Could not locate opening brace for '$signaturePrefix'" }
        var depth = 1
        var i = openBrace + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) return source.substring(openBrace + 1, i)
            i++
        }
        error("Unbalanced braces while extracting body for '$signaturePrefix'")
    }

    @Test
    fun `updateGlow body contains isAyuActive guard before removeAllOverlays and return`() {
        val body = extractFunctionBody(source, "fun updateGlow(")
        // The guardPattern matches `!AyuVariant.isAyuActive()` followed by
        // `removeAllOverlays()` followed by `return`, with arbitrary whitespace
        // and the surrounding `if (...) { ... }` braces between them. A regex
        // alternative that allowed `return` before `removeAllOverlays` would
        // accept a broken guard where the function exits without disposing
        // overlays — the whole point of D-02.
        val guardPattern =
            Regex("""!AyuVariant\.isAyuActive\(\)[\s\S]*?removeAllOverlays\(\)[\s\S]*?return""")
        if (!guardPattern.containsMatchIn(body)) {
            fail(
                "GlowOverlayManager.updateGlow must open with " +
                    "`if (!AyuVariant.isAyuActive()) { removeAllOverlays(); return }` " +
                    "as the lifecycle gate. The guard was either missing, used the " +
                    "wrong predicate (use isAyuActive(), not detect() == null — " +
                    "Pattern J), or the order of `removeAllOverlays()` and `return` " +
                    "was inverted. See D-02 in 40.1-CONTEXT.md and Pattern L in " +
                    "RECURRING_PITFALLS.md.",
            )
        }
    }
}
