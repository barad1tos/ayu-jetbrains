package dev.ayuislands.accent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-level regression locks for [AccentResolutionChainBuilder] branches
 * that are structurally unreachable through the public surface and therefore
 * have no behavioral test (same technique as `LiveChromeRefresherTest`'s
 * broken-container cap lock).
 */
class AccentResolutionChainBuilderGuardTest {
    @Test
    fun `overrideWinner keeps the defensive null-hex winner guard`() {
        // Every WON step the engine builds today carries a non-null hex, so the
        // guard cannot be exercised behaviorally. It must survive refactors
        // anyway: without it, a future step-construction change that produces a
        // hex-less winner would push a null hex toward the applicator instead
        // of degrading to the caller's global fallback.
        val source = readChainBuilderSource()
        assertTrue(
            source.contains("val hex = winner.hex"),
            "overrideWinner must read the winner hex into a local for the null guard",
        )
        val nullHexGuard =
            Regex(
                """if\s*\(hex\s*==\s*null\)\s*\{.*?LOG\.warn\(.*?return null""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            nullHexGuard.containsMatchIn(source),
            "overrideWinner must warn and degrade to 'no override' when a WON step carries no hex",
        )
    }

    private fun readChainBuilderSource(): String {
        val file = File("src/main/kotlin/dev/ayuislands/accent/AccentResolutionChainBuilder.kt")
        return file.takeIf { it.exists() }?.readText()
            ?: error("Could not locate AccentResolutionChainBuilder.kt for source-level guard")
    }
}
