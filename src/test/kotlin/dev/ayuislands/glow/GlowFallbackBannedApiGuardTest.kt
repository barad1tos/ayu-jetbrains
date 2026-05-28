package dev.ayuislands.glow

import com.intellij.openapi.util.io.FileUtil
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pattern L source-regex regression lock for the glow lifecycle gate.
 *
 * Historically, three sites in [GlowOverlayManager] used a defensive
 * `if (variant != null) AccentResolver.resolve(...) else DEFAULT_ACCENT_HEX`
 * fallback in `initializeFocusRingGlow`, `attachOverlay`, and `updateGlow`.
 * Those branches were dead code reachable only after a theme-switch race that
 * the `isAyuActive` lifecycle gate now resolves, so they were deleted.
 *
 * This test pins the deletion: a future agent who "helpfully" reintroduces a
 * `DEFAULT_ACCENT_HEX` fallback in any of these three function bodies gets a
 * named regression instead of silently re-opening the orange-glow-on-Darcula
 * bug. The companion-object declaration of the constant stays whitelisted
 * because `safeDecodeColor` still uses it as a `Color.decode` fallback for
 * malformed hex strings — that path is unrelated to the lifecycle gate.
 */
class GlowFallbackBannedApiGuardTest {
    private val source: String by lazy {
        val file =
            File(
                System.getProperty("user.dir"),
                "src/main/kotlin/dev/ayuislands/glow/GlowOverlayManager.kt",
            )
        stripComments(FileUtil.loadFile(file))
    }

    /**
     * Strips block comments `/* ... */` and line comments `// ...` so KDoc that
     * documents the very banned API it forbids cannot false-positive a guard.
     * Mirrors the canonical helper in `dev.ayuislands.accent.Gap4BannedApiGuardTest`.
     */
    private fun stripComments(input: String): String {
        val noBlock = input.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock
            .lineSequence()
            .joinToString("\n") { line -> line.replaceFirst(Regex("//.*$"), "") }
    }

    /**
     * Extracts the body of a Kotlin function declaration from the stripped
     * source. Matches `fun <name>(` (or `private fun <name>(`) through to the
     * matching closing brace by counting balanced braces. Mirrors the
     * `extractFunctionBody` shape used by [dev.ayuislands.accent.elements.ChromeLiveRefreshSymmetryTest].
     */
    private fun extractFunctionBody(
        source: String,
        signaturePrefix: String,
    ): String {
        val start = source.indexOf(signaturePrefix)
        require(start >= 0) { "Could not locate function signature '$signaturePrefix' in stripped source" }
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
    fun `initializeFocusRingGlow body does not reference DEFAULT_ACCENT_HEX`() {
        val body = extractFunctionBody(source, "fun initializeFocusRingGlow(")
        assertFalse(
            body.contains("DEFAULT_ACCENT_HEX"),
            "initializeFocusRingGlow MUST NOT use DEFAULT_ACCENT_HEX as a fallback — " +
                "the `if (!AyuVariant.isAyuActive())` guard makes this branch unreachable. " +
                "Reintroducing it re-opens the orange-glow-on-Darcula bug.",
        )
    }

    @Test
    fun `attachOverlay body does not reference DEFAULT_ACCENT_HEX`() {
        val body = extractFunctionBody(source, "fun attachOverlay(")
        assertFalse(
            body.contains("DEFAULT_ACCENT_HEX"),
            "attachOverlay MUST NOT use DEFAULT_ACCENT_HEX as a fallback (lifecycle-gate regression).",
        )
    }

    @Test
    fun `updateGlow body does not reference DEFAULT_ACCENT_HEX`() {
        val body = extractFunctionBody(source, "fun updateGlow(")
        assertFalse(
            body.contains("DEFAULT_ACCENT_HEX"),
            "updateGlow MUST NOT use DEFAULT_ACCENT_HEX as a fallback (lifecycle-gate regression).",
        )
    }

    @Test
    fun `companion object block still declares DEFAULT_ACCENT_HEX`() {
        // Whitelist: the literal MUST still exist somewhere in the file because
        // `safeDecodeColor` uses it as a Color.decode fallback for malformed
        // hex strings — that path is unrelated to the lifecycle gate. If a
        // future agent deletes the constant entirely (instead of just removing
        // the three banned fallback branches), `safeDecodeColor` breaks for
        // anyone who calls it with a malformed hex.
        assertTrue(
            Regex("""private\s+const\s+val\s+DEFAULT_ACCENT_HEX""").containsMatchIn(source),
            "DEFAULT_ACCENT_HEX MUST remain declared in the companion object — " +
                "safeDecodeColor uses it as a Color.decode fallback for malformed hex strings. " +
                "Only the three function-body fallback branches are forbidden.",
        )
    }
}
