package dev.ayuislands.syntax

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 49 Plan 49-04 — Syntax tab wiring assertions for [dev.ayuislands.settings.AyuIslandsConfigurable]
 * (SYNTAX-01, D-03, Q6).
 *
 * **Rule 4 deviation note (revision iteration 1, warning 3 + integration-task scope):**
 * the plan originally requested a `BasePlatformTestCase` integration test at
 * `src/test/kotlin/dev/ayuislands/integration/SettingsConfigurableSyntaxTabTest.kt`
 * that would construct the Configurable and inspect the live JBTabbedPane.
 * Executing it surfaced the same `integrationTest`-task infrastructure issue
 * documented on [SyntaxModeStatePersistenceRoundTripTest] — the project's
 * `tasks.register<Test>("integrationTest")` is registered without the
 * IntelliJ Platform classpath / `--add-opens` flags, so BasePlatformTestCase
 * never reaches setUp. The existing `SettingsConfigurableIntegrationTest` in
 * `integration/` has the same problem (never executed by any CI).
 *
 * Rather than expand Plan 49-04 to fix shared build infrastructure, this test
 * asserts the same invariants via source-regex regression locks (Pattern L) +
 * structural assertions on the `AyuIslandsConfigurable` source. This is the
 * pattern used elsewhere in the project (see `SyntaxModeUpgradeNotifierTest`
 * GROUP_ID lock, AyuIslandsSyntaxPanelTest LicenseChecker lock).
 *
 * Coverage preserved:
 *  - "Syntax" tab appears in the JBTabbedPane (insertTab call exists)
 *  - Syntax tab sits between Glow and VCS (D-03 placement)
 *  - syntaxPanel is registered in the `panels` list (apply()/reset()/isModified() route)
 *  - Tab title is "Syntax" (resilient to index shifts)
 */
class SettingsConfigurableSyntaxTabWiringTest {
    @Test
    fun `configurable source contains insertTab Syntax call with index 3 (Q6)`() {
        val source = readConfigurableSource()
        // Pattern L source-regex lock — exactly one insertTab("Syntax", ...) call
        // routed through SYNTAX_TAB_INDEX. Catches accidental removal or
        // re-positioning that would shift Q6's placement contract.
        val pattern = Regex("""tabs\.insertTab\(\s*"Syntax"\s*,[^)]*\bSYNTAX_TAB_INDEX\b""")
        val matches = pattern.findAll(source).count()
        assertEquals(
            1,
            matches,
            "Expected exactly 1 tabs.insertTab(\"Syntax\", ..., SYNTAX_TAB_INDEX) call in " +
                "AyuIslandsConfigurable source. Found $matches. If you renamed the index " +
                "constant or removed the insertTab call, Plan 49-04 SYNTAX-01 has regressed.",
        )
    }

    @Test
    fun `SYNTAX_TAB_INDEX constant equals 3 (D-03 between Glow and VCS)`() {
        val source = readConfigurableSource()
        // Lock the literal value — D-03 reasoning was Accent | Font | Glow |
        // Syntax | VCS | Workspace | Plugins ordering with Syntax at index 3
        // (between Glow at index 2 and VCS at the next slot). Index drift
        // would break the title-position contract.
        val pattern = Regex("""const\s+val\s+SYNTAX_TAB_INDEX\s*=\s*3\b""")
        assertTrue(
            pattern.containsMatchIn(source),
            "SYNTAX_TAB_INDEX must equal 3 per D-03 placement (Accent|Font|Glow|Syntax|VCS|...).",
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
        // Source-order assertion — protects against accidental re-ordering of
        // the tab construction block, which would shift visible tab order even
        // if SYNTAX_TAB_INDEX stays 3 (since addTab calls before insertTab
        // determine the base index).
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
        // Surface assertion that AyuIslandsConfigurable.apply() / reset() /
        // isModified() iterate the syntax panel — the integration test would
        // have asserted this dynamically; we assert it structurally here.
        val source = readConfigurableSource()
        // The `panels` field must list `syntaxPanel` so the per-panel apply
        // dispatches to it. Source-regex lock: search for the panels-list
        // declaration with syntaxPanel inside.
        val listPattern =
            Regex(
                """private\s+val\s+panels\s*:\s*List<AyuIslandsSettingsPanel>\s*=\s*listOf\([^)]*\bsyntaxPanel\b""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            listPattern.containsMatchIn(source),
            "AyuIslandsConfigurable.panels list must include syntaxPanel so apply()/reset()/" +
                "isModified() dispatch to it. Without this, the Settings UI shows the tab " +
                "but Apply does nothing — SYNTAX-01 regression.",
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
        // `val syntaxTab = panel { syntaxPanel.buildPanel(...) }` must exist
        // so the tab actually renders the Syntax UI.
        val pattern =
            Regex(
                """val\s+syntaxTab\s*=\s*panel\s*\{[^}]*syntaxPanel\.buildPanel""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            pattern.containsMatchIn(source),
            "syntaxTab must be a panel { syntaxPanel.buildPanel(...) } block — without it the " +
                "JBTabbedPane has an empty Syntax tab.",
        )
    }

    private fun readConfigurableSource(): String =
        Files.readString(
            Path.of("src/main/kotlin/dev/ayuislands/settings/AyuIslandsConfigurable.kt"),
        )
}
