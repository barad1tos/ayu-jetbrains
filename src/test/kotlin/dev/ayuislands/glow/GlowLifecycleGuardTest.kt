package dev.ayuislands.glow

import com.intellij.openapi.util.io.FileUtil
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Pattern L structural lock for the glow lifecycle guard.
 *
 * Companion to [GlowFallbackBannedApiGuardTest], which forbids the old shape
 * (`DEFAULT_ACCENT_HEX` fallbacks). This test pins the current shape: the head
 * of `updateGlow()` must open with the literal sequence
 *
 *   val context = AccentContext.detect()
 *   if (context == null) {
 *       removeAllOverlays()
 *       return
 *   }
 *
 * A future agent who silently reorders the steps (e.g. moving `return` before
 * `removeAllOverlays()` so the dispose never runs, or replacing the predicate
 * with an Ayu-only gate and bypassing external mode) gets a named regression
 * that names this test rather than a generic visual bug report.
 */
class GlowLifecycleGuardTest {
    private val source: String by lazy {
        val file =
            File(
                System.getProperty("user.dir"),
                "src/main/kotlin/dev/ayuislands/glow/GlowOverlayManager.kt",
            )
        stripComments(FileUtil.loadFile(file))
    }

    private fun stripComments(input: String): String {
        val noBlock = input.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock
            .lineSequence()
            .joinToString("\n") { line -> line.replaceFirst(Regex("//.*$"), "") }
    }

    private fun extractUpdateGlowBody(): String = extractFunctionBody("fun updateGlow(")

    private fun extractFunctionBody(signaturePrefix: String): String {
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
    fun `updateGlow body contains AccentContext guard before removeAllOverlays and return`() {
        val body = extractUpdateGlowBody()
        // The guardPattern matches `AccentContext.detect()` followed by
        // `if (context == null)` followed by
        // `removeAllOverlays()` followed by `return`, with arbitrary whitespace
        // and the surrounding `if (...) { ... }` braces between them. A regex
        // alternative that allowed `return` before `removeAllOverlays` would
        // accept a broken guard where the function exits without disposing
        // overlays — the whole point of the lifecycle gate.
        val guardPattern =
            Regex(
                """val\s+context\s*=\s*AccentContext\.detect\(\)[\s\S]*?""" +
                    """if\s*\(\s*context\s*==\s*null\s*\)[\s\S]*?""" +
                    """removeAllOverlays\(\)[\s\S]*?return""",
            )
        if (!guardPattern.containsMatchIn(body)) {
            fail(
                "GlowOverlayManager.updateGlow must open with " +
                    "`val context = AccentContext.detect(); " +
                    "if (context == null) { removeAllOverlays(); return }` " +
                    "as the lifecycle gate. The guard was either missing, used the " +
                    "wrong predicate, or the order of `removeAllOverlays()` and " +
                    "`return` was inverted. See Pattern L in RECURRING_PITFALLS.md.",
            )
        }
    }

    @Test
    fun `focus ring and late overlay attach honor external glow allow-list`() {
        val focusBody = extractFunctionBody("fun initializeFocusRingGlow(")
        val attachBody = extractFunctionBody("fun attachOverlay(")

        if (!focusBody.contains("isExternalGlowBlocked(context, state, \"initializeFocusRingGlow\")")) {
            fail("initializeFocusRingGlow must guard AccentContext.External through isExternalGlowBlocked")
        }
        if (!attachBody.contains("isExternalGlowBlocked(context, state, \"attachOverlay($")) {
            fail("attachOverlay must guard AccentContext.External through isExternalGlowBlocked")
        }
    }
}
