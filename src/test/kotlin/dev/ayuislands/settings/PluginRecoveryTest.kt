package dev.ayuislands.settings

import dev.ayuislands.syntax.PluginRequirement
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PluginRecoveryTest {
    @Test
    fun `installed provider is not reported as a missing plugin`() {
        val swift =
            requireNotNull(
                SyntaxLanguageRegistry.findByStorageId("Swift"),
            )
        val recovery = PluginRecoveryResolver { true }

        val result = recovery.unavailable(swift, generation = 7)

        val deferred = assertIs<SyntaxProbeResult.Deferred>(result)
        assertEquals("Swift", deferred.languageId)
        assertEquals(7, deferred.generation)
    }

    @Test
    fun `recovery opens the declared provider Marketplace page`() {
        val opened = mutableListOf<String>()
        val marketplace = PluginMarketplace { url -> opened.add(url) }

        marketplace.open(
            languageId = "Swift",
            requirement =
                PluginRequirement(
                    pluginId = "dev.j-a.swift",
                    displayName = "Noctule, the Swift IDE",
                    marketplaceUrl = "https://plugins.jetbrains.com/plugin/22150-noctule-the-swift-ide",
                ),
        )

        assertEquals(
            listOf("https://plugins.jetbrains.com/plugin/22150-noctule-the-swift-ide"),
            opened,
        )
    }

    @Test
    fun `generic recovery opens an encoded Marketplace language search`() {
        val opened = mutableListOf<String>()
        val marketplace = PluginMarketplace(opened::add)

        marketplace.open("C# (ReSharper)", requirement = null)

        assertEquals(
            listOf("https://plugins.jetbrains.com/search?search=C%23+%28ReSharper%29"),
            opened,
        )
    }
}
