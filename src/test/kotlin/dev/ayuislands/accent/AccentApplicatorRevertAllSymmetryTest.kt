package dev.ayuislands.accent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pattern G apply/revert symmetry source-regex regression lock for D-04.
 *
 * Every key written by `apply()` MUST have a matching reverse op in
 * `revertAll()`. Pre-40.1, two integrations broke that invariant:
 *
 *   apply  -> IndentRainbowSync.apply(variant, hex)
 *   revert -> (nothing)                                 <- BUG
 *
 *   apply  -> syncCodeGlanceProViewport(hex)
 *   revert -> (nothing)                                 <- BUG
 *
 * Wave 1 plan 02 closes both pairs by inserting `IndentRainbowSync.revert()`
 * and `revertCodeGlanceProViewport()` into the `revertAll` body. This test
 * locks the symmetry: a future agent who deletes either revert call (or moves
 * it outside `revertAll`) gets a named regression that points at the exact
 * apply/revert pair the symmetry breaks.
 *
 * Also locks the RESEARCH §D-04 ordering: integrations BEFORE the per-project
 * `notifyOnly` loop, so subscribers (EditorScrollbarManager etc.) see
 * consistent app-scoped state when the topic fires.
 */
class AccentApplicatorRevertAllSymmetryTest {
    private val source: String by lazy {
        val path: Path =
            Paths.get(
                System.getProperty("user.dir"),
                "src",
                "main",
                "kotlin",
                "dev",
                "ayuislands",
                "accent",
                "AccentApplicator.kt",
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
    fun `revertAll body contains IndentRainbowSync revert call`() {
        val body = extractFunctionBody(source, "fun revertAll(")
        assertTrue(
            Regex("""IndentRainbowSync\.revert\(\)""").containsMatchIn(body),
            "revertAll MUST call IndentRainbowSync.revert() — Pattern G symmetry " +
                "with `IndentRainbowSync.apply(...)` in the apply path. Pre-40.1, " +
                "the apply path wrote the indent palette but the revert path never " +
                "cleared it, leaving stale Ayu colors in IR's app-scoped IrConfig " +
                "after the user switched to a non-Ayu LAF.",
        )
    }

    @Test
    fun `revertAll body contains revertCodeGlanceProViewport call`() {
        val body = extractFunctionBody(source, "fun revertAll(")
        assertTrue(
            Regex("""revertCodeGlanceProViewport\(\)""").containsMatchIn(body),
            "revertAll MUST call revertCodeGlanceProViewport() — Pattern G symmetry " +
                "with `syncCodeGlanceProViewport(...)` in the apply path. Pre-40.1, " +
                "the apply path tinted CGP's minimap viewport but the revert path " +
                "never restored it, leaving the orange viewport painted over " +
                "Darcula chrome after a theme switch.",
        )
    }

    @Test
    fun `apply body contains IndentRainbowSync apply and syncCodeGlanceProViewport calls`() {
        // Anchor for the symmetry: assert apply() still calls both helpers so the
        // symmetry test is comparing apples to apples. If a future refactor moves
        // either helper out of apply(), the symmetry tests above would still pass
        // (because nothing is left to revert) but the integrations would no
        // longer be wired anywhere — a worse regression than the one we're
        // protecting against. Pinning both ends keeps the pair honest.
        val body = extractFunctionBody(source, "fun apply(")
        assertTrue(
            Regex("""IndentRainbowSync\.apply\(""").containsMatchIn(body),
            "apply MUST still call IndentRainbowSync.apply — symmetry anchor for the revert-side test",
        )
        assertTrue(
            Regex("""syncCodeGlanceProViewport\(""").containsMatchIn(body),
            "apply MUST still call syncCodeGlanceProViewport — symmetry anchor for the revert-side test",
        )
    }

    @Test
    fun `IR revert precedes CGP revert precedes notifyOnly in revertAll body`() {
        // RESEARCH §D-04 ordering lock: integrations BEFORE notifyOnly so
        // subscribers see consistent app-scoped state when the topic fires.
        // Reordering would silently re-introduce the stale-cache class of bugs
        // we're closing.
        val body = extractFunctionBody(source, "fun revertAll(")
        val irIdx = body.indexOf("IndentRainbowSync.revert()")
        val cgpIdx = body.indexOf("revertCodeGlanceProViewport()")
        val notifyIdx = body.indexOf("ComponentTreeRefresher.notifyOnly")
        assertTrue(
            irIdx in 0 until cgpIdx,
            "IR revert must come BEFORE CGP revert in revertAll body " +
                "(RESEARCH §D-04 ordering lock — found irIdx=$irIdx cgpIdx=$cgpIdx)",
        )
        assertTrue(
            cgpIdx in 0 until notifyIdx,
            "CGP revert must come BEFORE notifyOnly in revertAll body " +
                "(RESEARCH §D-04 ordering lock — found cgpIdx=$cgpIdx notifyIdx=$notifyIdx)",
        )
    }
}
