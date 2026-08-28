package dev.ayuislands.integration

import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntaxRuntimeCatalogTest {
    @Test
    fun `every advertised language has a runtime candidate`() {
        val advertised = SyntaxLanguageRegistry.specifications().mapTo(linkedSetOf(), LanguageSpecification::storageId)
        val candidates = SyntaxRuntimeCatalog.entries.flatMapTo(linkedSetOf(), SyntaxRuntime::candidateLanguages)

        assertEquals(advertised, candidates)
    }

    @Test
    fun `every advertised language belongs to exactly one runtime candidate`() {
        val candidateCounts =
            SyntaxRuntimeCatalog.entries
                .flatMap(SyntaxRuntime::candidateLanguages)
                .groupingBy { language -> language }
                .eachCount()

        assertTrue(candidateCounts.all { (_, count) -> count == 1 }, candidateCounts.toString())
    }

    @Test
    fun `runtime identities are unique and reference only catalog languages`() {
        val runtimes = SyntaxRuntimeCatalog.entries
        val advertised = SyntaxLanguageRegistry.specifications().mapTo(linkedSetOf(), LanguageSpecification::storageId)

        assertEquals(runtimes.size, runtimes.map(SyntaxRuntime::id).distinct().size)
        runtimes.forEach { runtime ->
            assertTrue(runtime.id.isNotBlank())
            assertTrue(runtime.productId.isNotBlank())
            assertTrue(runtime.version.isNotBlank())
            assertTrue(runtime.candidateLanguages.isNotEmpty())
            assertTrue(advertised.containsAll(runtime.candidateLanguages), runtime.id)
        }
    }

    @Test
    fun `base runtime preserves the currently verified native contract`() {
        val base = SyntaxRuntimeCatalog.require("idea-community")

        assertEquals(
            setOf(
                "Bash",
                "Groovy",
                "Java",
                "JSON",
                "Kotlin",
                "Markdown",
                "Properties files",
                "XML",
                "YAML",
            ),
            base.candidateLanguages,
        )
        assertTrue(base.provisioning is RuntimeProvisioning.Ready)
    }

    @Test
    fun `production language specifications carry no verification topology`() {
        val productionFields = LanguageSpecification::class.java.declaredFields.mapTo(linkedSetOf()) { it.name }

        assertFalse("verificationRuntimeId" in productionFields)
    }

    @Test
    fun `PyCharm isolates community Python from the Django provider graph`() {
        val pyCharmLanguages = SyntaxRuntimeCatalog.require("pycharm").candidateLanguages

        assertTrue("Python" in pyCharmLanguages)
        assertFalse("Django" in pyCharmLanguages)
        assertEquals(setOf("Django"), SyntaxRuntimeCatalog.require("pycharm-django").candidateLanguages)
    }

    @Test
    fun `DQL uses its marketplace language provider instead of a host product`() {
        val runtime = SyntaxRuntimeCatalog.require("dynatrace-dql")

        assertEquals(setOf("DQL"), runtime.candidateLanguages)
        assertEquals(MarketplaceDependency("pl.thedeem.dql", "1.10.0"), runtime.marketplaceDependency)
    }

    @Test
    fun `Dart uses its official marketplace language provider`() {
        val runtime = SyntaxRuntimeCatalog.require("dart")

        assertEquals(setOf("Dart"), runtime.candidateLanguages)
        assertEquals(MarketplaceDependency("Dart", "506.1.0"), runtime.marketplaceDependency)
    }

    @Test
    fun `GraphQL uses its official marketplace language provider`() {
        val runtime = SyntaxRuntimeCatalog.require("graphql")

        assertEquals(setOf("GraphQL"), runtime.candidateLanguages)
        assertEquals(
            MarketplaceDependency("com.intellij.lang.jsgraphql", "251.23774.318"),
            runtime.marketplaceDependency,
        )
    }

    @Test
    fun `HCL and TIL share their official marketplace language provider`() {
        val runtime = SyntaxRuntimeCatalog.require("hcl")

        assertEquals(setOf("HCL", "TIL"), runtime.candidateLanguages)
        assertEquals(
            MarketplaceDependency("org.intellij.plugins.hcl", "251.23774.426"),
            runtime.marketplaceDependency,
        )
    }

    @Test
    fun `Scala uses its official marketplace language provider`() {
        val runtime = SyntaxRuntimeCatalog.require("scala")

        assertEquals(setOf("Scala"), runtime.candidateLanguages)
        assertEquals(MarketplaceDependency("org.intellij.scala", "2025.1.22"), runtime.marketplaceDependency)
    }

    @Test
    fun `PowerShell uses its canonical marketplace language provider`() {
        val runtime = SyntaxRuntimeCatalog.require("powershell")

        assertEquals(setOf("PowerShell"), runtime.candidateLanguages)
        assertEquals(
            MarketplaceDependency("com.intellij.plugin.adernov.powershell", "2.10.0"),
            runtime.marketplaceDependency,
        )
    }

    @Test
    fun `bundled provider languages stay with their host product runtimes`() {
        assertTrue("Gherkin" in SyntaxRuntimeCatalog.require("webstorm").candidateLanguages)
        assertTrue("CoffeeScript" in SyntaxRuntimeCatalog.require("rubymine").candidateLanguages)
        assertTrue(
            SyntaxRuntimeCatalog.require("pycharm").candidateLanguages.containsAll(setOf("dotenv", "Puppet")),
        )
    }

    @Test
    fun `Qute records the IntelliJ light fixture boundary`() {
        val runtime = SyntaxRuntimeCatalog.require("idea-ultimate-qute")
        val provisioning = runtime.provisioning as RuntimeProvisioning.Blocked

        assertEquals(setOf("Qute"), runtime.candidateLanguages)
        assertTrue(provisioning.reason.contains("does not register QuteFileType"))
    }

    @Test
    fun `Cron records the injected language boundary`() {
        val runtime = SyntaxRuntimeCatalog.require("idea-ultimate-cron")
        val provisioning = runtime.provisioning as RuntimeProvisioning.Blocked

        assertEquals(setOf("Cron expression"), runtime.candidateLanguages)
        assertTrue(provisioning.reason.contains("injected language without a LanguageFileType"))
    }

    @Test
    fun `Rider records the solution-backed runtime boundary`() {
        val runtime = SyntaxRuntimeCatalog.require("rider")
        val provisioning = runtime.provisioning as RuntimeProvisioning.Blocked

        assertEquals(setOf("C# (ReSharper)"), runtime.candidateLanguages)
        assertTrue(provisioning.reason.contains("solution-backed"))
    }

    @Test
    fun `Objective-C records the CLion Nova public API boundary`() {
        val runtime = SyntaxRuntimeCatalog.require("clion-objective-c")
        val provisioning = runtime.provisioning as RuntimeProvisioning.Blocked

        assertEquals(setOf("Objective-C"), runtime.candidateLanguages)
        assertTrue(provisioning.reason.contains("public LanguageFileType"))
    }

    @Test
    fun `every unavailable runtime carries an action point`() {
        SyntaxRuntimeCatalog.entries.forEach { runtime ->
            val action =
                when (val provisioning = runtime.provisioning) {
                    RuntimeProvisioning.Ready -> return@forEach
                    is RuntimeProvisioning.Blocked -> provisioning.reason
                    is RuntimeProvisioning.ProviderResearch -> provisioning.action
                }

            assertTrue(action.isNotBlank(), runtime.id)
        }
    }
}
