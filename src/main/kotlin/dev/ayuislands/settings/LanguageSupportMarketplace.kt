package dev.ayuislands.settings

import com.intellij.ide.BrowserUtil
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class LanguageSupportMarketplace(
    private val browse: (String) -> Unit = BrowserUtil::browse,
) {
    fun open(languageId: String) {
        browse(searchUrl(languageId))
    }

    private companion object {
        private const val MARKETPLACE_SEARCH_URL = "https://plugins.jetbrains.com/search?search="

        private fun searchUrl(languageId: String): String =
            MARKETPLACE_SEARCH_URL + URLEncoder.encode(languageId, StandardCharsets.UTF_8)
    }
}
