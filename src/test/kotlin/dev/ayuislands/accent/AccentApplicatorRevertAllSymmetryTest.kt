package dev.ayuislands.accent

import com.intellij.openapi.util.io.FileUtil
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pattern G apply/revert symmetry source-regex regression lock.
 *
 * Every key written by `apply()` MUST have a matching reverse op in
 * `revertAll()`. Two integrations previously broke that invariant:
 *
 *   apply  -> IndentRainbowSync.apply(variant, hex)
 *   revert -> (nothing)                                 <- BUG
 *
 *   apply  -> syncCodeGlanceProViewport(hex)
 *   revert -> (nothing)                                 <- BUG
 *
 * Both pairs are now closed by inserting `IndentRainbowSync.revert()` and
 * `revertCodeGlanceProViewport()` into the `revertAll` body. This test locks
 * the symmetry: a future agent who deletes either revert call (or moves it
 * outside `revertAll`) gets a named regression that points at the exact
 * apply/revert pair the symmetry breaks.
 *
 * Also locks the ordering: integrations BEFORE the per-project `notifyOnly`
 * loop, so subscribers (EditorScrollbarManager etc.) see consistent
 * app-scoped state when the topic fires.
 */
class AccentApplicatorRevertAllSymmetryTest {
    private val source: String by lazy {
        val file =
            File(
                System.getProperty("user.dir"),
                "src/main/kotlin/dev/ayuislands/accent/AccentApplicator.kt",
            )
        stripComments(FileUtil.loadFile(file))
    }

    private fun stripComments(input: String): String {
        val noBlock = input.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock
            .lineSequence()
            .joinToString("\n") { line -> line.replaceFirst(Regex("//.*$"), "") }
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
                "with `IndentRainbowSync.apply(...)` in the apply path. Without " +
                "this, the apply path writes the indent palette but the revert " +
                "path never clears it, leaving stale Ayu colors in IR's " +
                "app-scoped IrConfig after the user switches to a non-Ayu LAF.",
        )
    }

    @Test
    fun `revertAll body contains revertCodeGlanceProViewport call`() {
        val body = extractFunctionBody(source, "fun revertAll(")
        assertTrue(
            Regex("""revertCodeGlanceProViewport\(\)""").containsMatchIn(body),
            "revertAll MUST call revertCodeGlanceProViewport() — Pattern G symmetry " +
                "with `syncCodeGlanceProViewport(...)` in the apply path. Without " +
                "this, the apply path tints CGP's minimap viewport but the revert " +
                "path never restores it, leaving the orange viewport painted over " +
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
    fun `apply body wraps IndentRainbowSync apply in if variant non-null check`() {
        // The behavioral test `apply skips IndentRainbowSync when variant is
        // null` covers the runtime path; this source-regex pin guards against
        // a refactor that drops the structural `if (variant != null)` wrapper.
        // IR.apply takes a non-null AyuVariant; if the wrapper goes away the
        // call would not compile, but a future change to IR.apply's signature
        // (nullable variant) could silently re-introduce the unconditional
        // invocation. Pattern L — defensive structural lock.
        val body = extractFunctionBody(source, "fun apply(")
        assertTrue(
            Regex("""if\s*\(variant\s*!=\s*null\s*\)\s*\{\s*[\s\S]*?IndentRainbowSync\.apply""")
                .containsMatchIn(body),
            "apply body MUST wrap IndentRainbowSync.apply in `if (variant != null)` " +
                "so a null variant (non-Ayu LAF) skips IR's reflection chain. " +
                "Pattern L lock — keep the source structure explicit even when the " +
                "current type system would catch a bare invocation.",
        )
    }

    @Test
    fun `apply body fires IR apply before CGP sync before notifyOnly`() {
        // Mirror of the revert-side ordering lock. Apply path order matches
        // the revert path (IR -> CGP -> notifyOnly) so future debugging
        // reasons about a single ordering rather than two inverse-mirrored
        // sequences. Drift between apply and revert order is silently
        // observable as app-scoped state where IR pushed first on apply but
        // CGP unwinds first on revert. Pattern G + L — apply/revert symmetry
        // and structural ordering lock.
        val body = extractFunctionBody(source, "fun apply(")
        val irIdx = body.indexOf("IndentRainbowSync.apply")
        val cgpIdx = body.indexOf("CodeGlanceProIntegration.syncCodeGlanceProViewport")
        val notifyIdx = body.indexOf("ComponentTreeRefresher.notifyOnly")
        assertTrue(
            irIdx in 0 until cgpIdx,
            "IndentRainbowSync.apply must come BEFORE CodeGlanceProIntegration.syncCodeGlanceProViewport " +
                "in apply body (ordering lock — found irIdx=$irIdx cgpIdx=$cgpIdx). " +
                "Mirrors the revert-side ordering in revertAll body.",
        )
        assertTrue(
            cgpIdx in 0 until notifyIdx,
            "CodeGlanceProIntegration.syncCodeGlanceProViewport must come BEFORE notifyOnly in apply body " +
                "(ordering lock — found cgpIdx=$cgpIdx notifyIdx=$notifyIdx). Integrations " +
                "stamp the app-scoped caches before the refresh broadcast.",
        )
    }

    @Test
    fun `IR revert precedes CGP revert precedes notifyOnly in revertAll body`() {
        // Ordering lock: integrations BEFORE notifyOnly so subscribers see
        // consistent app-scoped state when the topic fires. Reordering would
        // silently re-introduce the stale-cache class of bugs this closes.
        val body = extractFunctionBody(source, "fun revertAll(")
        val irIdx = body.indexOf("IndentRainbowSync.revert()")
        val cgpIdx = body.indexOf("revertCodeGlanceProViewport()")
        val notifyIdx = body.indexOf("ComponentTreeRefresher.notifyOnly")
        assertTrue(
            irIdx in 0 until cgpIdx,
            "IR revert must come BEFORE CGP revert in revertAll body " +
                "(ordering lock — found irIdx=$irIdx cgpIdx=$cgpIdx)",
        )
        assertTrue(
            cgpIdx in 0 until notifyIdx,
            "CGP revert must come BEFORE notifyOnly in revertAll body " +
                "(ordering lock — found cgpIdx=$cgpIdx notifyIdx=$notifyIdx)",
        )
    }
}
