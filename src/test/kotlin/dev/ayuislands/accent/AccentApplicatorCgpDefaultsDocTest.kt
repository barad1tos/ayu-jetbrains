package dev.ayuislands.accent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pattern L source-regex regression lock for D-05.
 *
 * The three CGP viewport defaults landed via Wave 1 plan 02:
 *   - `CGP_DEFAULT_VIEWPORT_COLOR        = "00FF00"`
 *   - `CGP_DEFAULT_VIEWPORT_BORDER_COLOR = "A0A0A0"`
 *   - `CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS = 0`
 *
 * Each value was extracted via `javap` from
 * `com.nasller.codeglance.config.CodeGlanceConfig.<init>` in
 * `CodeGlancePro-2.0.2.jar` (CONTEXT.md §specifics). This test pins both:
 *   1. The exact constant values (no `#` prefix, no off-by-one digits).
 *   2. The KDoc provenance reference — `javap` / `CodeGlanceConfig` / `2.0.2`
 *      must remain in the comment block above the constants so a future agent
 *      who bumps the CGP plugin version can re-run the verification command
 *      and confirm whether the defaults shifted.
 *
 * The KDoc-reference checks read the RAW source (NOT stripped) because
 * `stripComments` discards the very block that contains the provenance.
 */
class AccentApplicatorCgpDefaultsDocTest {
    private val rawSource: String by lazy {
        val path: Path =
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
        Files.readString(path)
    }

    private val strippedSource: String by lazy { stripComments(rawSource) }

    private fun stripComments(input: String): String {
        val noBlock = input.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock
            .lineSequence()
            .map { line -> line.replaceFirst(Regex("//.*$"), "") }
            .joinToString("\n")
    }

    @Test
    fun `CGP_DEFAULT_VIEWPORT_COLOR constant exists with exact value 00FF00`() {
        assertTrue(
            Regex("""CGP_DEFAULT_VIEWPORT_COLOR\s*=\s*"00FF00"""").containsMatchIn(strippedSource),
            "CGP_DEFAULT_VIEWPORT_COLOR must equal \"00FF00\" — the literal value " +
                "extracted via javap from CodeGlanceConfig-2.0.2 (no `#` prefix; CGP " +
                "stores hex as plain 6-char strings).",
        )
    }

    @Test
    fun `CGP_DEFAULT_VIEWPORT_BORDER_COLOR constant exists with exact value A0A0A0`() {
        assertTrue(
            Regex("""CGP_DEFAULT_VIEWPORT_BORDER_COLOR\s*=\s*"A0A0A0"""")
                .containsMatchIn(strippedSource),
            "CGP_DEFAULT_VIEWPORT_BORDER_COLOR must equal \"A0A0A0\" — the literal " +
                "value extracted via javap from CodeGlanceConfig-2.0.2.",
        )
    }

    @Test
    fun `CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS constant exists with exact value 0`() {
        assertTrue(
            Regex("""CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS\s*=\s*0\b""")
                .containsMatchIn(strippedSource),
            "CGP_DEFAULT_VIEWPORT_BORDER_THICKNESS must equal 0 — javap-verified " +
                "iconst_0 from CodeGlanceConfig-2.0.2.",
        )
    }

    @Test
    fun `no CGP hex constant carries a hash prefix`() {
        assertFalse(
            Regex("""CGP_DEFAULT_VIEWPORT_(COLOR|BORDER_COLOR)\s*=\s*"#""")
                .containsMatchIn(strippedSource),
            "CGP setters do NOT want the # prefix — CodeGlanceConfig stores hex as " +
                "plain 6-char uppercase strings. Adding `#` would silently break the " +
                "viewport reset by writing an invalid hex string into CGP's config.",
        )
    }

    @Test
    fun `CGP defaults KDoc block references javap provenance`() {
        // Read the RAW source: stripComments discards the KDoc block where the
        // provenance lives. The three tokens (`javap`, `CodeGlanceConfig`,
        // `2.0.2`) are the re-verification recipe — a future agent who bumps
        // the CGP version can run `javap` against the new jar and confirm the
        // defaults are still the same. Removing the recipe would silently turn
        // the constants into magic numbers.
        assertTrue(
            Regex("""javap""").containsMatchIn(rawSource),
            "CGP defaults KDoc must reference `javap` so the re-verification command " +
                "stays discoverable. See CONTEXT.md §specifics for the original " +
                "extraction recipe.",
        )
        assertTrue(
            Regex("""CodeGlanceConfig""").containsMatchIn(rawSource),
            "CGP defaults KDoc must reference `CodeGlanceConfig` (the class the " +
                "javap command targets).",
        )
        assertTrue(
            Regex("""2\.0\.2""").containsMatchIn(rawSource),
            "CGP defaults KDoc must reference `2.0.2` (the version the defaults " +
                "were extracted from). Bumping CGP would rewrite this version " +
                "string — until then, the literal pin proves provenance.",
        )
    }
}
