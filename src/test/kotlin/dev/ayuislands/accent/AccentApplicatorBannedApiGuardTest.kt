package dev.ayuislands.accent

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Regression guards that freeze the banned-API profile of [AccentApplicator].
 *
 * The apply-path refresh hook uses [dev.ayuislands.ui.ComponentTreeRefresher.notifyOnly]
 * only — never a [com.intellij.ide.ui.LafManagerListener.TOPIC] publish or a
 * [javax.swing.SwingUtilities.updateComponentTreeUI] /
 * [com.intellij.ide.ui.LafManager.updateUI] refresh. Those alternatives either
 * recurse through the LAF cycle (LafManagerListener) or crash mid-LAF
 * (updateComponentTreeUI — see the revertAll comment naming this constraint).
 * Project policy pins this as a permanent constraint:
 *
 *   "NEVER use `SwingUtilities.updateComponentTreeUI()` in JetBrains plugins —
 *    triggers ActionToolbar.updateUI → SlowOperations SEVERE crash."
 *
 * Each @Test owns one pattern so a future regression names the exact API that
 * crept back in, not a generic "banned substrings found".
 */
class AccentApplicatorBannedApiGuardTest {
    private val source: String by lazy {
        val path =
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

    /**
     * Strips block comments `/* ... */` and line comments `// ...` from Kotlin
     * source. Keeps string literals intact because the banned APIs are always
     * code references, never in user-visible strings in this file. The guard
     * tests would otherwise false-positive on KDoc that documents the very
     * banned APIs they forbid (e.g. the explanatory comment in `revertAll`
     * naming `updateComponentTreeUI`).
     */
    private fun stripComments(input: String): String {
        val noBlock = input.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock
            .lineSequence()
            .map { line -> line.replaceFirst(Regex("//.*$"), "") }
            .joinToString("\n")
    }

    @Test
    fun `AccentApplicator does not call SwingUtilities updateComponentTreeUI`() {
        assertFalse(
            source.contains("SwingUtilities.updateComponentTreeUI"),
            "AccentApplicator.kt must not use SwingUtilities.updateComponentTreeUI " +
                "(triggers ActionToolbar.updateUI → SlowOperations SEVERE crash). " +
                "Use ComponentTreeRefresher.notifyOnly or window.repaint() instead.",
        )
    }

    @Test
    fun `AccentApplicator does not call LafManager updateUI`() {
        assertFalse(
            source.contains("LafManager.getInstance().updateUI"),
            "AccentApplicator.kt must not call LafManager.updateUI — it re-enters the " +
                "LAF cycle and recurses during apply/revert. Use notifyOnly.",
        )
    }

    @Test
    fun `AccentApplicator does not reference LafManagerListener`() {
        assertFalse(
            source.contains("LafManagerListener"),
            "AccentApplicator.kt must not publish or subscribe to LafManagerListener.TOPIC. " +
                "Publishing lookAndFeelChanged from the apply path would recurse. " +
                "notifyOnly only.",
        )
    }
}
