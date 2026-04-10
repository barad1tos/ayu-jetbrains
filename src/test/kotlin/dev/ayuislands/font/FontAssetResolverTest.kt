package dev.ayuislands.font

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FontAssetResolverTest {
    private val mapleEntry = FontCatalog.forPreset(FontPreset.AMBIENT)
    private val victorEntry = FontCatalog.forPreset(FontPreset.WHISPER)
    private val neonEntry = FontCatalog.forPreset(FontPreset.NEON)

    @Test
    fun `direct-url entry bypasses http client`() {
        var called = false
        val resolver =
            FontAssetResolver { _ ->
                called = true
                "unused"
            }
        val url = resolver.resolve(victorEntry)
        assertEquals(victorEntry.fallbackUrl, url)
        assertFalse(called, "HttpClient must not be invoked for useDirectUrl entries")
    }

    @Test
    fun `api success returns matching asset url`() {
        val expectedUrl = "https://github.com/subframe7536/maple-font/releases/download/v9.9/MapleMono-TTF.zip"
        val json =
            """
            {
              "assets": [
                {
                  "name": "MapleMono-NF.zip",
                  "browser_download_url": "https://example.com/MapleMono-NF.zip"
                },
                {
                  "name": "MapleMono-TTF.zip",
                  "browser_download_url": "$expectedUrl"
                }
              ]
            }
            """.trimIndent()
        val resolver = FontAssetResolver { _ -> json }
        assertEquals(expectedUrl, resolver.resolve(mapleEntry))
    }

    @Test
    fun `api http failure falls back to hardcoded url`() {
        val resolver = FontAssetResolver { _ -> throw IOException("404") }
        assertEquals(mapleEntry.fallbackUrl, resolver.resolve(mapleEntry))
    }

    @Test
    fun `api regex miss falls back to hardcoded url`() {
        val json =
            """
            {
              "assets": [
                {
                  "name": "unrelated.tar.gz",
                  "browser_download_url": "https://example.com/unrelated.tar.gz"
                }
              ]
            }
            """.trimIndent()
        val resolver = FontAssetResolver { _ -> json }
        assertEquals(mapleEntry.fallbackUrl, resolver.resolve(mapleEntry))
    }

    @Test
    fun `malformed json falls back to hardcoded url`() {
        val resolver = FontAssetResolver { _ -> "not json at all" }
        assertEquals(mapleEntry.fallbackUrl, resolver.resolve(mapleEntry))
    }

    @Test
    fun `monaspace variable pattern matches versioned asset name`() {
        val expectedUrl =
            "https://github.com/githubnext/monaspace/releases/download/" +
                "v1.500/monaspace-variable-v1.500.zip"
        val json =
            """
            {
              "assets": [
                {
                  "name": "monaspace-static-v1.500.zip",
                  "browser_download_url": "https://example.com/static.zip"
                },
                {
                  "name": "monaspace-variable-v1.500.zip",
                  "browser_download_url": "$expectedUrl"
                }
              ]
            }
            """.trimIndent()
        val resolver = FontAssetResolver { _ -> json }
        assertEquals(expectedUrl, resolver.resolve(neonEntry))
    }
}
