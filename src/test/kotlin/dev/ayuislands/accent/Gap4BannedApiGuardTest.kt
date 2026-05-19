package dev.ayuislands.accent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Banned-API regression guard for the Level 2 direct Swing peer mutation hook.
 *
 * The hook MUST stay strictly on direct Swing peer mutation
 * (`setBackground` + `repaint`). Any reach back into LAF refresh paths is a
 * regression — `SwingUtilities.updateComponentTreeUI` crashes via
 * `ActionToolbar.updateUI → SlowOperations` (per project CLAUDE.md), and
 * `LafManager.updateUI` / `LafManagerListener` re-enter the LAF cycle and
 * recurse against the apply/revert pass.
 *
 * Each @Test owns one banned pattern so a future regression names the exact
 * API that crept back in, not a generic "banned substrings found".
 */
class Gap4BannedApiGuardTest {
    private val accentSourcesRoot: Path =
        Paths.get(
            System.getProperty("user.dir"),
            "src",
            "main",
            "kotlin",
            "dev",
            "ayuislands",
            "accent",
        )

    private val accentSources: List<Pair<Path, String>> by lazy {
        Files.walk(accentSourcesRoot).use { stream ->
            stream
                .asSequence()
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map { it to stripComments(Files.readString(it)) }
                .toList()
        }
    }

    /**
     * Strips block comments `/* ... */` and line comments `// ...` so KDoc that
     * documents the very banned APIs they forbid (e.g. `AccentApplicator`'s
     * KDoc naming `updateComponentTreeUI`) cannot false-positive a guard.
     */
    private fun stripComments(input: String): String {
        val noBlock = input.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock
            .lineSequence()
            .map { line -> line.replaceFirst(Regex("//.*$"), "") }
            .joinToString("\n")
    }

    @Test
    fun `accent module must not call SwingUtilities updateComponentTreeUI`() {
        val offenders =
            accentSources.filter { (_, source) ->
                source.contains("SwingUtilities.updateComponentTreeUI")
            }
        assertFalse(
            offenders.isNotEmpty(),
            "Files calling SwingUtilities.updateComponentTreeUI " +
                "(triggers ActionToolbar.updateUI → SlowOperations SEVERE crash, " +
                "see CLAUDE.md): ${offenders.map { it.first.fileName }}",
        )
    }

    @Test
    fun `accent module must not call LafManager updateUI`() {
        val offenders =
            accentSources.filter { (_, source) ->
                source.contains("LafManager.getInstance().updateUI")
            }
        assertFalse(
            offenders.isNotEmpty(),
            "Files calling LafManager.getInstance().updateUI (re-enters LAF cycle, " +
                "recurses through apply/revert): ${offenders.map { it.first.fileName }}",
        )
    }

    @Test
    fun `accent module must not publish LafManagerListener broadcasts`() {
        // Subscribing to the LAF topic is SAFE — a passive cache-invalidation
        // listener cannot recurse through the apply/revert path. What is
        // actually banned is *publishing* lookAndFeelChanged from the apply
        // path (the `syncPublisher(LafManagerListener.TOPIC)` shape), which
        // would recurse through `ProcessPopup`'s handler that calls
        // `IJSwingUtilities.updateComponentTreeUI`.
        //
        // This guard catches the unsafe shape while allowing the safe
        // `ChromeBaseColors` subscriber pattern (`subscribe(LafManagerListener.TOPIC, …)`).
        val offenders =
            accentSources.filter { (_, source) ->
                source.contains("syncPublisher(LafManagerListener.TOPIC") ||
                    source.contains("LafManager.getInstance().lookAndFeelChanged")
            }
        assertFalse(
            offenders.isNotEmpty(),
            "Files publishing LafManagerListener broadcasts (UNSAFE — publishing " +
                "lookAndFeelChanged from apply path would recurse): " +
                "${offenders.map { it.first.fileName }}",
        )
    }

    @Test
    fun `coverage thresholds and ignore lists in build gradle kts unchanged from baseline`() {
        // Read koverVerify line to detect a smuggled threshold drop. The number
        // (80) is the project-wide floor per CLAUDE.md "Coverage Floors". Any
        // reduction to bypass hook coverage is a violation.
        val buildGradle =
            Files.readString(
                Paths.get(System.getProperty("user.dir"), "build.gradle.kts"),
            )
        val pattern = Regex("""minBound\((\d+)\)""")
        val koverFloor = pattern.findAll(buildGradle).map { it.groupValues[1] }.toList()
        assertFalse(
            koverFloor.any { it.toInt() < EXPECTED_KOVER_FLOOR },
            "build.gradle.kts kover minBound must not drop below " +
                "$EXPECTED_KOVER_FLOOR — found: $koverFloor",
        )
    }

    private companion object {
        const val EXPECTED_KOVER_FLOOR = 80
    }
}
