package dev.ayuislands.syntax

import dev.ayuislands.settings.AyuIslandsSettingsPanel
import dev.ayuislands.settings.AyuIslandsSyntaxPanel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Syntax tab wiring assertions for `AyuIslandsConfigurable`.
 *
 * Coverage:
 *  - `AyuIslandsSyntaxPanel` implements the real `AyuIslandsSettingsPanel`
 *    interface (compile-time assignability assertion).
 *  - The configurable constructs `AyuIslandsSyntaxPanel` and wires it into
 *    the JBTabbedPane via a `panel { syntaxPanel.buildPanel(...) }` block.
 *  - `syntaxPanel` is registered in the `panels` list so apply/reset/
 *    isModified dispatches to it.
 *  - The "Syntax" tab sits between "Glow" and "VCS" (placement contract).
 *  - The configurable does NOT instantiate Phase 49 service / state symbols
 *    directly — the tab wiring only constructs the panel; the panel itself
 *    owns the service `getInstance()` call sites.
 *
 * Source-regex regression locks (Pattern L) substitute for an integration
 * test that would otherwise need `BasePlatformTestCase` — the project's
 * `integrationTest` task is registered without the IntelliJ Platform
 * `--add-opens` flags, so the structural assertions here are the active
 * regression net.
 */
class SettingsConfigurableSyntaxTabWiringTest {
    @Test
    fun `AyuIslandsSyntaxPanel is assignable to AyuIslandsSettingsPanel`() {
        // Compile-time + runtime interface check. A future refactor that
        // accidentally drops the interface declaration (or renames the
        // base interface) would break this assertion.
        val panel: AyuIslandsSettingsPanel = AyuIslandsSyntaxPanel()
        assertTrue(
            panel is AyuIslandsSyntaxPanel,
            "AyuIslandsSyntaxPanel must remain assignable to the AyuIslandsSettingsPanel contract.",
        )
    }

    @Test
    fun `configurable source contains insertTab Syntax call with SYNTAX_TAB_INDEX`() {
        val source = readConfigurableSource()
        val pattern = Regex("""tabs\.insertTab\(\s*"Syntax"\s*,[^)]*\bSYNTAX_TAB_INDEX\b""")
        val matches = pattern.findAll(source).count()
        assertEquals(
            1,
            matches,
            "Expected exactly 1 tabs.insertTab(\"Syntax\", ..., SYNTAX_TAB_INDEX) call in " +
                "AyuIslandsConfigurable source. Found $matches.",
        )
    }

    @Test
    fun `SYNTAX_TAB_INDEX constant equals 3 (placement between Glow and VCS)`() {
        val source = readConfigurableSource()
        val pattern = Regex("""const\s+val\s+SYNTAX_TAB_INDEX\s*=\s*3\b""")
        assertTrue(
            pattern.containsMatchIn(source),
            "SYNTAX_TAB_INDEX must equal 3 — placement contract: " +
                "Accent | Font | Glow | Syntax | VCS | Workspace | Plugins.",
        )
    }

    @Test
    fun `tab insertion sits between addTab Glow and addTab VCS in source order`() {
        val source = readConfigurableSource()
        val glowIdx = source.indexOf("""tabs.addTab("Glow"""")
        val syntaxIdx = source.indexOf("""tabs.insertTab("Syntax"""")
        val vcsIdx = source.indexOf("""tabs.addTab("VCS"""")
        assertTrue(glowIdx >= 0, "tabs.addTab(\"Glow\", ...) must exist in the configurable source")
        assertTrue(syntaxIdx >= 0, "tabs.insertTab(\"Syntax\", ...) must exist in the configurable source")
        assertTrue(vcsIdx >= 0, "tabs.addTab(\"VCS\", ...) must exist in the configurable source")
        assertTrue(
            glowIdx < syntaxIdx,
            "Glow addTab (offset $glowIdx) must precede Syntax insertTab (offset $syntaxIdx) in source",
        )
        assertTrue(
            syntaxIdx < vcsIdx,
            "Syntax insertTab (offset $syntaxIdx) must precede VCS addTab (offset $vcsIdx) in source",
        )
    }

    @Test
    fun `AyuIslandsSyntaxPanel is registered in the panels list (apply reset route)`() {
        val source = readConfigurableSource()
        val listPattern =
            Regex(
                """private\s+val\s+panels\s*:\s*List<AyuIslandsSettingsPanel>\s*=\s*listOf\([^)]*\bsyntaxPanel\b""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            listPattern.containsMatchIn(source),
            "AyuIslandsConfigurable.panels list must include syntaxPanel so apply()/reset()/" +
                "isModified() dispatch to it.",
        )
    }

    @Test
    fun `syntaxPanel field is constructed as AyuIslandsSyntaxPanel`() {
        val source = readConfigurableSource()
        val pattern = Regex("""private\s+val\s+syntaxPanel\s*=\s*AyuIslandsSyntaxPanel\(\)""")
        assertTrue(
            pattern.containsMatchIn(source),
            "AyuIslandsConfigurable must construct syntaxPanel as AyuIslandsSyntaxPanel() at field-init time.",
        )
    }

    @Test
    fun `syntax tab builds via panel block invoking syntaxPanel buildPanel`() {
        val source = readConfigurableSource()
        val pattern =
            Regex(
                """val\s+syntaxTab\s*=\s*panel\s*\{[^}]*syntaxPanel\.buildPanel""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            pattern.containsMatchIn(source),
            "syntaxTab must be a panel { syntaxPanel.buildPanel(...) } block.",
        )
    }

    @Test
    fun `configurable source does not instantiate Phase 49 service or state symbols directly`() {
        val source = readConfigurableSource()
        // The tab wiring must only construct AyuIslandsSyntaxPanel; the
        // panel itself owns SyntaxIntensityService.getInstance() and
        // SyntaxIntensityState.getInstance() call sites. A Phase 49 leak
        // here would mean the sunset cascade missed a referrer.
        val forbidden =
            listOf(
                "SyntaxModeService(",
                "SyntaxModeState(",
                "SyntaxModeService.getInstance",
                "SyntaxModeState.getInstance",
            )
        for (literal in forbidden) {
            assertFalse(
                source.contains(literal),
                "configurable source must not reference Phase 49 symbol '$literal' " +
                    "directly — the tab wiring only constructs AyuIslandsSyntaxPanel.",
            )
        }
    }

    private fun readConfigurableSource(): String =
        Files.readString(
            Path.of("src/main/kotlin/dev/ayuislands/settings/AyuIslandsConfigurable.kt"),
        )
}
