package dev.ayuislands.ui

import com.intellij.openapi.util.io.FileUtil
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

/** Prevents recursive LAF refresh calls from re-entering IDE model code. */
class UiRefreshGuardTest {
    private val productionSourcesRoot =
        File(
            System.getProperty("user.dir"),
            "src/main/kotlin/dev/ayuislands",
        )

    private val productionSources: List<Pair<File, String>> by lazy {
        productionSourcesRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to stripComments(FileUtil.loadFile(it)) }
            .toList()
    }

    private val accentSources: List<Pair<File, String>> by lazy {
        productionSources.filter { (file, _) ->
            file.invariantSeparatorsPath.contains("/dev/ayuislands/accent/")
        }
    }

    /** Removes comments so documented API names do not trigger the executable-code guard. */
    private fun stripComments(input: String): String {
        val noBlock = input.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock
            .lineSequence()
            .joinToString("\n") { line -> line.replaceFirst(Regex("//.*$"), "") }
    }

    @Test
    fun `production must not recursively refresh component trees`() {
        val bannedCalls =
            listOf(
                "SwingUtilities.updateComponentTreeUI",
                "IJSwingUtilities.updateComponentTreeUI",
            )
        val offenders =
            productionSources.filter { (_, source) ->
                bannedCalls.any(source::contains)
            }

        assertFalse(
            offenders.isNotEmpty(),
            "Recursive component-tree refresh can enter project model code from a " +
                "write-unsafe event: ${offenders.map { it.first.relativeTo(productionSourcesRoot) }}",
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
            "Files calling LafManager.getInstance().updateUI re-enter the LAF cycle: " +
                offenders.map { it.first.name },
        )
    }

    @Test
    fun `accent module must not publish LafManagerListener broadcasts`() {
        val offenders =
            accentSources.filter { (_, source) ->
                source.contains("syncPublisher(LafManagerListener.TOPIC") ||
                    source.contains("LafManager.getInstance().lookAndFeelChanged")
            }
        assertFalse(
            offenders.isNotEmpty(),
            "Files publishing LafManagerListener broadcasts re-enter the apply cycle: " +
                offenders.map { it.first.name },
        )
    }
}
