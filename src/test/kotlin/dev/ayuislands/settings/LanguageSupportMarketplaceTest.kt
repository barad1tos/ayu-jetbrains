package dev.ayuislands.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageSupportMarketplaceTest {
    @Test
    fun `recovery searches by language without provider coordinates`() {
        val opened = mutableListOf<String>()
        val marketplace = LanguageSupportMarketplace(opened::add)

        marketplace.open(languageId = "Swift")

        assertEquals(listOf("https://plugins.jetbrains.com/search?search=Swift"), opened)
    }

    @Test
    fun `recovery encodes language names in Marketplace search`() {
        val opened = mutableListOf<String>()
        val marketplace = LanguageSupportMarketplace(opened::add)

        marketplace.open("C# (ReSharper)")

        assertEquals(
            listOf("https://plugins.jetbrains.com/search?search=C%23+%28ReSharper%29"),
            opened,
        )
    }
}
