package dev.ayuislands.settings.mappings

import com.intellij.openapi.util.io.FileUtil
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pattern G + L source-regex regression locks for the focus-swap hex gate.
 *
 * Historically, [ProjectAccentSwapService.handleWindowActivated] used a
 * blanket short-circuit:
 *
 *   if (effectiveHex == lastAppliedHex) return
 *
 * That triggered a bug on alt-tab from project A (hex X) to project B which
 * also resolves to hex X. The blanket return skipped `applyFromHexString` AND
 * `walkAndNotify` AND the integration writes — leaving CGP/IR app-scoped
 * caches holding project A's hex while the user looked at project B.
 *
 * The gate is now split into a conditional: `applyFromHexString` is still
 * skipped when hex is unchanged, but `walkAndNotify` + the direct integration
 * calls (`AccentApplicator.syncCodeGlanceProViewportForSwap` +
 * `IndentRainbowSync.apply`) ALWAYS fire so the per-project hex is pushed
 * into the app-scoped caches before the tree walk repaints.
 *
 * This test pins:
 *   1. The blanket `if (effectiveHex == lastAppliedHex) return` literal must
 *      NOT appear.
 *   2. `walkAndNotify` MUST appear exactly once at the function's top level so
 *      it fires on every focus swap, not nested inside the changed-only branch.
 *   3. The same-hex branch MUST trigger the integration refresh path
 *      (`syncCodeGlanceProViewportForSwap` + `IndentRainbowSync.apply`).
 *   4. `plugin.xml` MUST NOT register a second `ProjectManagerListener` —
 *      the bug is solved by gate relaxation, not by adding a new lifecycle
 *      listener.
 *
 * **Test-design note (documented compromise):** assertions 1–3 are
 * source-regex over `ProjectAccentSwapService.handleWindowActivated`. They
 * guard a real user-facing bug (stale CGP/IR app-scoped cache on alt-tab
 * between projects that resolve to the same hex). A behavioral substitute
 * would require reflection into the private `handleWindowActivated(AWTEvent)`
 * AND mocks for `findProjectForWindow`, AccentResolver, AyuVariant, the
 * settings state, AccentApplicator's two methods, and the message-bus
 * publish — heavy and brittle. Assertion 4 reads the actual plugin.xml
 * manifest (REAL-ARTIFACT). Pending a working `integrationTest` task
 * (currently misconfigured in CI), these checks are the cheapest assertions
 * that catch the regression. Do not delete in future "remove theater"
 * passes without replacing with an equivalent behavioral or integration test.
 */
class ProjectAccentSwapServiceHexGateShapeTest {
    private val source: String by lazy {
        val file =
            File(
                System.getProperty("user.dir"),
                "src/main/kotlin/dev/ayuislands/settings/mappings/ProjectAccentSwapService.kt",
            )
        stripComments(FileUtil.loadFile(file))
    }

    private fun stripComments(input: String): String {
        val noBlock = input.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock
            .lineSequence()
            .joinToString("\n") { line -> line.replaceFirst(Regex("//.*$"), "") }
    }

    private fun extractHandleWindowActivatedBody(): String = extractFunctionBody("fun handleWindowActivated(")

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
    fun `handleWindowActivated does NOT use a blanket hex-gate return`() {
        val body = extractHandleWindowActivatedBody()
        // Match the exact prior line shape: `if (effectiveHex == lastAppliedHex) return`
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
                "CGP/IR caches (Pattern G + L regression lock).",
        )
    }

    @Test
    fun `walkAndNotify fires from both hex-changed and hex-unchanged branches`() {
        val body = extractHandleWindowActivatedBody()
        assertTrue(
            Regex("""walkAndNotify\(""").containsMatchIn(body),
            "handleWindowActivated MUST still call ComponentTreeRefresher.walkAndNotify",
        )
        // walkAndNotify must appear EXACTLY once, at the function's top level
        // after the gate. Two occurrences would suggest duplication into both
        // branches (still correct but a maintenance hazard); zero would mean
        // the call vanished. One outside the conditional is the canonical
        // shape.
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
        val body = extractHandleWindowActivatedBody()
        val refreshBody = extractFunctionBody("fun refreshSameHexIntegrations(")
        val ayuRefreshBody = extractFunctionBody("fun refreshAyuIntegrations(")
        val externalRefreshBody = extractFunctionBody("fun refreshExternalIntegrations(")

        assertTrue(
            body.contains("refreshSameHexIntegrations(effectiveAccent)"),
            "handleWindowActivated MUST route the same-hex branch through refreshSameHexIntegrations.",
        )
        assertTrue(
            Regex("""syncCodeGlanceProViewportForSwap\(""").containsMatchIn(ayuRefreshBody),
            "refreshAyuIntegrations MUST push per-project hex into app-scoped CGP cache.",
        )
        assertTrue(
            Regex("""syncCodeGlanceProViewportForSwap\(""").containsMatchIn(externalRefreshBody),
            "refreshExternalIntegrations MUST push external per-project hex into app-scoped CGP cache.",
        )
        assertTrue(
            Regex("""refreshAyuIntegrations\(""").containsMatchIn(refreshBody) &&
                Regex("""refreshExternalIntegrations\(""").containsMatchIn(refreshBody),
            "refreshSameHexIntegrations MUST dispatch both Ayu and external contexts.",
        )
        assertTrue(
            Regex("""IndentRainbowSync\.apply\(""").containsMatchIn(ayuRefreshBody) &&
                Regex("""IndentRainbowSync\.apply\(""").containsMatchIn(externalRefreshBody),
            "Same-hex refresh helpers MUST push per-project hex into app-scoped IR cache.",
        )
    }

    @Test
    fun `plugin xml does NOT register ProjectManagerListener for accent swap`() {
        // Regression lock: the focus-swap fix does NOT add a second lifecycle
        // listener. The existing `ProjectManagerListener` for
        // `ProjectLanguageCacheInvalidator` is the ONLY permitted registration
        // — `handleWindowActivated` already covers focus swaps via AWT
        // WINDOW_ACTIVATED, which is the right granularity for alt-tab between
        // already-loaded projects. `ProjectManagerListener.projectActivated`
        // only fires on project load, missing the trigger entirely.
        val pluginXmlFile =
            File(
                System.getProperty("user.dir"),
                "src/main/resources/META-INF/plugin.xml",
            )
        val pluginXml = FileUtil.loadFile(pluginXmlFile)
        val listenerCount =
            Regex(
                """<listener[^>]*topic="com\.intellij\.openapi\.project\.ProjectManagerListener"""",
            ).findAll(pluginXml).count()
        assertTrue(
            listenerCount <= 1,
            "plugin.xml must register AT MOST one ProjectManagerListener " +
                "(`ProjectLanguageCacheInvalidator`). Adding a second listener for " +
                "accent swap is forbidden — handleWindowActivated handles focus " +
                "swaps via AWT WINDOW_ACTIVATED. Found $listenerCount registrations.",
        )
    }
}
