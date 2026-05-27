package dev.ayuislands.syntax

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.walk

/**
 * Pattern L regression lock: guarantees the legacy syntax mood/axis symbols
 * cannot be reintroduced. Greps `src/main/kotlin/`, `src/main/resources/`,
 * `src/test/kotlin/`, and `src/test/resources/` for legacy class names and
 * data file references (expanded scope so post-sunset orphan references in
 * test files or resource overlays are caught).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class Phase49SunsetRegressionTest {
    @Test
    fun `no legacy source symbols anywhere in src tree`() {
        val forbidden =
            listOf(
                "SyntaxMood",
                "StyleAxis",
                "SyntaxModeApplicator",
                "SyntaxModeService",
                "SyntaxModeState",
                "SyntaxModeUpgradeNotifier",
            )
        val scanRoots =
            listOf(
                Path.of("src/main/kotlin"),
                Path.of("src/test/kotlin"),
            )
        // Other Pattern L lock tests legitimately hold the forbidden literals
        // as string constants in their own narrow-scope forbidden-symbol lists.
        // Excluding them by filename keeps this scan focused on accidental
        // re-introductions (imports, references, calls) rather than sibling
        // regression locks that intentionally name the legacy symbols.
        val selfExcludedTestFiles =
            setOf(
                "Phase49SunsetRegressionTest.kt",
                "AyuIslandsSyntaxPanelTest.kt",
                "SettingsConfigurableSyntaxTabWiringTest.kt",
            )
        val offenders = mutableListOf<String>()
        for (root in scanRoots) {
            if (!Files.exists(root)) continue
            root
                .walk()
                .filter { it.toString().endsWith(".kt") }
                .filter { file -> selfExcludedTestFiles.none { file.toString().endsWith(it) } }
                .forEach { file ->
                    val content = Files.readString(file)
                    for (symbol in forbidden) {
                        if (content.contains(symbol)) {
                            offenders.add("$file: contains '$symbol'")
                        }
                    }
                }
        }
        assertEquals(
            emptyList<String>(),
            offenders,
            "Legacy syntax symbols leaked back: $offenders",
        )
    }

    @Test
    fun `no legacy data files in resources`() {
        val forbiddenPaths =
            listOf(
                Path.of("src/main/resources/themes/extended/mood-tiers.txt"),
                Path.of("src/main/resources/themes/extended/axis-keys.txt"),
                Path.of("src/test/resources/themes/extended-test/axis-keys.txt"),
            )
        for (path in forbiddenPaths) {
            if (Files.exists(path)) {
                error("Legacy data file leaked back: $path")
            }
        }
    }

    @Test
    fun `plugin xml does not register legacy services`() {
        val pluginXml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))
        val forbidden = listOf("SyntaxModeService", "SyntaxModeState", "SyntaxModeUpgradeNotifier")
        for (symbol in forbidden) {
            if (pluginXml.contains(symbol)) {
                error("plugin.xml still references '$symbol' — sunset unregistration incomplete")
            }
        }
    }
}
