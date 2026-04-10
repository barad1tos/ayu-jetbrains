package dev.ayuislands.font

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import java.io.IOException

/**
 * Pure-ish resolver that turns a [FontCatalog.Entry] into a concrete download URL.
 *
 * The GitHub Releases API is queried via an injectable [HttpClient] so the logic
 * can be unit-tested without network access. All failures (IOException, non-200,
 * parse failure, regex miss) are logged and degrade to [FontCatalog.Entry.fallbackUrl].
 *
 * Entries with [FontCatalog.Entry.useDirectUrl] bypass the API entirely and
 * return the fallback URL as-is (used for Victor Mono, which has no release
 * assets on GitHub).
 */
class FontAssetResolver(
    private val httpClient: HttpClient = defaultHttpClient,
) {
    private val log = logger<FontAssetResolver>()

    /** Functional interface for GET requests returning response body as text. */
    fun interface HttpClient {
        @Throws(IOException::class)
        fun get(url: String): String
    }

    fun resolve(entry: FontCatalog.Entry): String {
        if (entry.useDirectUrl) return entry.fallbackUrl
        if (entry.githubOwner.isBlank() || entry.githubRepo.isBlank()) return entry.fallbackUrl

        val apiUrl = "https://api.github.com/repos/${entry.githubOwner}/${entry.githubRepo}/releases/latest"
        val body =
            try {
                httpClient.get(apiUrl)
            } catch (e: IOException) {
                log.warn("GitHub API failed for ${entry.githubOwner}/${entry.githubRepo}, using fallback", e)
                return entry.fallbackUrl
            } catch (e: RuntimeException) {
                log.warn("GitHub API failed for ${entry.githubOwner}/${entry.githubRepo}, using fallback", e)
                return entry.fallbackUrl
            }

        return parseFirstMatchingAsset(body, entry.assetPattern) ?: entry.fallbackUrl
    }

    /**
     * Minimal JSON-regex scan for asset objects: every `browser_download_url`
     * is paired with the nearest preceding `name` field. Order matches the
     * order assets appear in the GitHub API response.
     */
    private fun parseFirstMatchingAsset(
        body: String,
        pattern: Regex,
    ): String? {
        val nameRegex = """"name"\s*:\s*"([^"]+)"""".toRegex()
        val urlRegex = """"browser_download_url"\s*:\s*"([^"]+)"""".toRegex()

        val names = nameRegex.findAll(body).map { it.range.first to it.groupValues[1] }.toList()
        val urls = urlRegex.findAll(body).map { it.range.first to it.groupValues[1] }.toList()
        if (urls.isEmpty() || names.isEmpty()) return null

        // For each URL, find the closest preceding name — that's its asset name.
        for ((urlPos, url) in urls) {
            val assetName = names.lastOrNull { it.first < urlPos }?.second ?: continue
            if (pattern.containsMatchIn(assetName)) return url
        }
        return null
    }

    companion object {
        private val defaultHttpClient =
            HttpClient { url ->
                HttpRequests
                    .request(url)
                    .productNameAsUserAgent()
                    .readString()
            }
    }
}
