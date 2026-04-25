package dev.ayuislands.settings.mappings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pattern G + L source-regex regression locks for D-07.
 *
 * Pre-40.1, [ProjectAccentSwapService.handleWindowActivated] used a blanket
 * short-circuit at line :98:
 *
 *   if (effectiveHex == lastAppliedHex) return
 *
 * Bug B trigger: alt-tab from project A (hex X) to project B which also
 * resolves to hex X. The blanket return skipped applyFromHexString AND
 * walkAndNotify AND the integration writes — leaving CGP/IR app-scoped caches
 * holding project A's hex while the user looked at project B.
 *
 * Wave 2 plan 03 splits the gate into a conditional: applyFromHexString is
 * still skipped when hex is unchanged, but walkAndNotify + the new direct
 * integration calls (`AccentApplicator.syncCodeGlanceProViewportForSwap` +
 * `IndentRainbowSync.apply`) ALWAYS fire so the per-project hex is pushed
 * into the app-scoped caches before the tree walk repaints.
 *
 * This test pins:
 *   1. The blanket `if (effectiveHex == lastAppliedHex) return` literal must
 *      NOT appear (D-07 relaxation).
 *   2. `walkAndNotify` MUST appear exactly once at the function's top level so
 *      it fires on every focus swap, not nested inside the changed-only branch.
 *   3. The same-hex branch MUST trigger the integration refresh path
 *      (`syncCodeGlanceProViewportForSwap` + `IndentRainbowSync.apply`).
 *   4. plugin.xml MUST NOT register a second `ProjectManagerListener` — D-06
 *      regression lock; Bug B is solved by gate relaxation, not by adding a
 *      new lifecycle listener.
 */
class ProjectAccentSwapServiceHexGateShapeTest {
    private val source: String by lazy {
        val path: Path =
            Paths.get(
                System.getProperty("user.dir"),
                "src",
                "main",
                "kotlin",
                "dev",
                "ayuislands",
                "settings",
                "mappings",
                "ProjectAccentSwapService.kt",
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
    fun `handleWindowActivated does NOT use a blanket hex-gate return`() {
        val body = extractFunctionBody(source, "fun handleWindowActivated(")
        // Match the exact pre-40.1 line shape: `if (effectiveHex == lastAppliedHex) return`
        // possibly with whitespace variations. Note: this regex is line-anchored so a
        // multi-line `if (...) {\n  ...\n}` block won't false-positive — we want to
        // forbid ONLY the single-line blanket form.
        val blanketReturnPattern =
            Regex(
                """if\s*\(\s*effectiveHex\s*==\s*lastAppliedHex\s*\)\s*return\s*$""",
                RegexOption.MULTILINE,
            )
        assertFalse(
            blanketReturnPattern.containsMatchIn(body),
            "handleWindowActivated MUST NOT carry a blanket " +
                "`if (effectiveHex == lastAppliedHex) return` — the hex-gate must " +
                "only skip applyFromHexString. walkAndNotify and integration refresh " +
                "MUST always fire to push the per-project hex into app-scoped " +
                "CGP/IR caches (D-07, Pattern G + L regression lock).",
        )
    }

    @Test
    fun `walkAndNotify fires from both hex-changed and hex-unchanged branches`() {
        val body = extractFunctionBody(source, "fun handleWindowActivated(")
        assertTrue(
            Regex("""walkAndNotify\(""").containsMatchIn(body),
            "handleWindowActivated MUST still call ComponentTreeRefresher.walkAndNotify",
        )
        // walkAndNotify must appear EXACTLY once, at the function's top level
        // after the gate. Two occurrences would suggest duplication into both
        // branches (still correct but a maintenance hazard); zero would mean
        // the call vanished. One outside the conditional is the canonical
        // shape per RESEARCH §D-07.
        val walkCount = Regex("""walkAndNotify\(""").findAll(body).count()
        assertEquals(
            1,
            walkCount,
            "walkAndNotify MUST appear EXACTLY once in handleWindowActivated — " +
                "outside the hex-changed branch so it fires on every focus swap " +
                "(found $walkCount occurrences).",
        )
    }

    @Test
    fun `handleWindowActivated triggers integration refresh on same-hex branch`() {
        val body = extractFunctionBody(source, "fun handleWindowActivated(")
        // Per RESEARCH §Open Questions §1 resolution: on same-hex swap,
        // handleWindowActivated directly invokes the CGP + IR apply paths so
        // the per-project accent is pushed into the app-scoped caches before
        // the tree walk repaints.
        assertTrue(
            Regex("""syncCodeGlanceProViewportForSwap\(""").containsMatchIn(body),
            "handleWindowActivated MUST call AccentApplicator.syncCodeGlanceProViewportForSwap " +
                "on the same-hex branch (D-07 resolution — push per-project hex into " +
                "app-scoped CGP cache so the focused minimap repaints).",
        )
        assertTrue(
            Regex("""IndentRainbowSync\.apply\(""").containsMatchIn(body),
            "handleWindowActivated MUST call IndentRainbowSync.apply on the same-hex " +
                "branch (D-07 resolution — push per-project hex into app-scoped IR " +
                "cache so the focused indent palette repaints).",
        )
    }

    @Test
    fun `plugin xml does NOT register ProjectManagerListener for accent swap`() {
        // D-06 regression lock: the bug fix for Bug B does NOT add a second
        // lifecycle listener. The existing ProjectManagerListener for
        // `ProjectLanguageCacheInvalidator` is the ONLY permitted registration
        // — handleWindowActivated already covers focus swaps via AWT
        // WINDOW_ACTIVATED, which is the right granularity for alt-tab between
        // already-loaded projects. ProjectManagerListener.projectActivated only
        // fires on project load, missing the Bug B trigger entirely.
        val pluginXmlPath: Path =
            Paths.get(
                System.getProperty("user.dir"),
                "src",
                "main",
                "resources",
                "META-INF",
                "plugin.xml",
            )
        val pluginXml = Files.readString(pluginXmlPath)
        val listenerCount =
            Regex(
                """<listener[^>]*topic="com\.intellij\.openapi\.project\.ProjectManagerListener"""",
            ).findAll(pluginXml).count()
        assertTrue(
            listenerCount <= 1,
            "plugin.xml must register AT MOST one ProjectManagerListener " +
                "(`ProjectLanguageCacheInvalidator`). D-06 forbids adding a second " +
                "listener for accent swap — handleWindowActivated handles focus " +
                "swaps via AWT WINDOW_ACTIVATED. Found $listenerCount registrations.",
        )
    }
}
