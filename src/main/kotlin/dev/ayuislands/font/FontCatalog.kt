package dev.ayuislands.font

/**
 * Static catalog of fonts that the plugin can auto-install at runtime.
 *
 * Entries map a [FontPreset] to upstream download metadata:
 * - [Entry.githubOwner] / [Entry.githubRepo] / [Entry.assetPattern] drive the
 *   GitHub Releases API resolution pipeline in [FontAssetResolver].
 * - [Entry.fallbackUrl] is a hard-coded last-known-good URL used when the API
 *   call fails (offline, rate-limited, regex miss).
 * - [Entry.useDirectUrl] = true means "never touch the API" — currently only
 *   Victor Mono, which has no release assets and ships from rubjo.github.io.
 *
 * [Entry.approxSizeMb] and [Entry.brewCaskSlug] are declared here (instead of in
 * the consuming wizard panel) so downstream plans can read them without cross-
 * plan edits.
 */
object FontCatalog {
    data class Entry(
        val preset: FontPreset,
        val displayName: String,
        val familyName: String,
        val githubOwner: String,
        val githubRepo: String,
        val assetPattern: Regex,
        val fallbackUrl: String,
        val filesToKeep: List<Regex>,
        val approxSizeMb: Int,
        val brewCaskSlug: String,
        val useDirectUrl: Boolean = false,
    )

    // Last-known-good 2026-04-09 (verified via live HTTP, see RESEARCH.md §1).
    private const val VICTOR_MONO_DIRECT_URL = "https://rubjo.github.io/victor-mono/VictorMonoAll.zip"

    // Last-known-good 2026-04-09 — subframe7536/maple-font v7.9 release asset.
    private const val MAPLE_MONO_FALLBACK_URL =
        "https://github.com/subframe7536/maple-font/releases/download/v7.9/MapleMono-TTF.zip"

    // Last-known-good 2026-04-09 — githubnext/monaspace v1.400 variable-font zip.
    private const val MONASPACE_VAR_FALLBACK_URL =
        "https://github.com/githubnext/monaspace/releases/download/v1.400/monaspace-variable-v1.400.zip"

    private const val APPROX_VICTOR_MB = 9
    private const val APPROX_MAPLE_MB = 2
    private const val APPROX_MONASPACE_MB = 4

    val entries: List<Entry> =
        listOf(
            Entry(
                preset = FontPreset.WHISPER,
                displayName = "Victor Mono",
                familyName = "Victor Mono",
                githubOwner = "",
                githubRepo = "",
                assetPattern = ".*".toRegex(),
                fallbackUrl = VICTOR_MONO_DIRECT_URL,
                filesToKeep = listOf(".*VictorMono-Light\\.(ttf|otf)$".toRegex()),
                approxSizeMb = APPROX_VICTOR_MB,
                brewCaskSlug = "font-victor-mono",
                useDirectUrl = true,
            ),
            Entry(
                preset = FontPreset.AMBIENT,
                displayName = "Maple Mono",
                familyName = "Maple Mono",
                githubOwner = "subframe7536",
                githubRepo = "maple-font",
                assetPattern = "MapleMono-TTF\\.zip".toRegex(),
                fallbackUrl = MAPLE_MONO_FALLBACK_URL,
                filesToKeep = listOf(".*MapleMono-Regular\\.ttf$".toRegex()),
                approxSizeMb = APPROX_MAPLE_MB,
                brewCaskSlug = "font-maple-mono",
            ),
            Entry(
                preset = FontPreset.NEON,
                displayName = "Monaspace Neon",
                familyName = "Monaspace Neon",
                githubOwner = "githubnext",
                githubRepo = "monaspace",
                assetPattern = "monaspace-variable-.*\\.zip".toRegex(),
                fallbackUrl = MONASPACE_VAR_FALLBACK_URL,
                filesToKeep = listOf(".*MonaspaceNeonVarVF\\.(ttf|otf)$".toRegex()),
                approxSizeMb = APPROX_MONASPACE_MB,
                brewCaskSlug = "font-monaspace",
            ),
            Entry(
                preset = FontPreset.CYBERPUNK,
                displayName = "Monaspace Xenon",
                familyName = "Monaspace Xenon",
                githubOwner = "githubnext",
                githubRepo = "monaspace",
                assetPattern = "monaspace-variable-.*\\.zip".toRegex(),
                fallbackUrl = MONASPACE_VAR_FALLBACK_URL,
                filesToKeep = listOf(".*MonaspaceXenonVarVF\\.(ttf|otf)$".toRegex()),
                approxSizeMb = APPROX_MONASPACE_MB,
                brewCaskSlug = "font-monaspace",
            ),
        )

    fun forPreset(preset: FontPreset): Entry =
        entries.firstOrNull { it.preset == preset }
            ?: error("No FontCatalog entry for preset $preset")
}
