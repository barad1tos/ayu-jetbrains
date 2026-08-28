package dev.ayuislands.integration

internal data class MarketplaceDependency(
    val pluginId: String,
    val version: String,
)

internal sealed interface RuntimeProvisioning {
    data object Ready : RuntimeProvisioning

    data class ProviderResearch(
        val action: String,
    ) : RuntimeProvisioning

    data class Blocked(
        val reason: String,
    ) : RuntimeProvisioning
}

internal data class SyntaxRuntime(
    val id: String,
    val productId: String,
    val version: String,
    val candidateLanguages: Set<String>,
    val marketplaceDependency: MarketplaceDependency? = null,
    val provisioning: RuntimeProvisioning = RuntimeProvisioning.Ready,
)

internal object SyntaxRuntimeCatalog {
    val entries: List<SyntaxRuntime> =
        listOf(
            runtime(
                id = "idea-community",
                productId = "INTELLIJ_IDEA_COMMUNITY",
                version = "2025.1",
                languages =
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
            ),
            runtime(
                id = "idea-ultimate",
                productId = "INTELLIJ_IDEA",
                version = "2025.3.3",
                languages =
                    setOf(
                        "Docker",
                        "EditorConfig",
                        "FreeMarker",
                        "Protobuf",
                        "Protobuf text",
                        "Velocity",
                    ),
            ),
            runtime(
                id = "webstorm",
                productId = "WEBSTORM",
                version = "2025.3.3",
                languages =
                    setOf(
                        "Angular",
                        "CSS",
                        "Gherkin",
                        "HTML",
                        "HTTP client",
                        "JavaScript",
                        "RegExp",
                        "Sass",
                        "TypeScript",
                    ),
            ),
            runtime(
                id = "webstorm-gitlab-ci",
                productId = "WEBSTORM",
                version = "2025.3.3",
                languages = setOf("GitLab CI"),
            ),
            runtime("phpstorm", "PHPSTORM", "2025.3.3", setOf("PHP")),
            runtime("clion", "CLION", "2025.3.3", setOf("Makefile")),
            runtime("goland", "GOLAND", "2025.1.3", setOf("Go")),
            runtime("pycharm", "PYCHARM", "2025.1.3", setOf("Python", "Puppet", "dotenv")),
            runtime("pycharm-django", "PYCHARM", "2025.1.3", setOf("Django")),
            SyntaxRuntime(
                id = "dynatrace-dql",
                productId = "INTELLIJ_IDEA_COMMUNITY",
                version = "2025.1",
                candidateLanguages = setOf("DQL"),
                marketplaceDependency = MarketplaceDependency("pl.thedeem.dql", "1.10.0"),
            ),
            SyntaxRuntime(
                id = "dart",
                productId = "INTELLIJ_IDEA_COMMUNITY",
                version = "2025.1",
                candidateLanguages = setOf("Dart"),
                marketplaceDependency = MarketplaceDependency("Dart", "506.1.0"),
            ),
            SyntaxRuntime(
                id = "graphql",
                productId = "INTELLIJ_IDEA_COMMUNITY",
                version = "2025.1",
                candidateLanguages = setOf("GraphQL"),
                marketplaceDependency =
                    MarketplaceDependency("com.intellij.lang.jsgraphql", "251.23774.318"),
            ),
            SyntaxRuntime(
                id = "hcl",
                productId = "INTELLIJ_IDEA_COMMUNITY",
                version = "2025.1",
                candidateLanguages = setOf("HCL", "TIL"),
                marketplaceDependency = MarketplaceDependency("org.intellij.plugins.hcl", "251.23774.426"),
            ),
            SyntaxRuntime(
                id = "scala",
                productId = "INTELLIJ_IDEA_COMMUNITY",
                version = "2025.1",
                candidateLanguages = setOf("Scala"),
                marketplaceDependency = MarketplaceDependency("org.intellij.scala", "2025.1.22"),
            ),
            SyntaxRuntime(
                id = "rider",
                productId = "RIDER",
                version = "2025.1.3",
                candidateLanguages = setOf("C# (ReSharper)"),
                provisioning =
                    RuntimeProvisioning.Blocked(
                        "C# highlighting requires a solution-backed Rider/ReSharper runtime; " +
                            "the light fixture cannot initialize Rider without an open solution.",
                    ),
            ),
            runtime("rubymine", "RUBYMINE", "2025.1.3", setOf("CoffeeScript", "HAML", "Ruby", "Slim")),
            SyntaxRuntime(
                id = "apple-plist",
                productId = "INTELLIJ_IDEA",
                version = "2025.3.3",
                candidateLanguages = setOf("Apple plist"),
                provisioning =
                    RuntimeProvisioning.Blocked(
                        "IntelliJ IDEA Ultimate has no bundled Apple plist language provider.",
                    ),
            ),
            SyntaxRuntime(
                id = "idea-ultimate-qute",
                productId = "INTELLIJ_IDEA",
                version = "2025.3.3",
                candidateLanguages = setOf("Qute"),
                provisioning =
                    RuntimeProvisioning.Blocked(
                        "The bundled Quarkus descriptor exposes Qute, but the public IntelliJ light fixture " +
                            "does not register QuteFileType even with the root dependency closure.",
                    ),
            ),
            SyntaxRuntime(
                id = "idea-ultimate-cron",
                productId = "INTELLIJ_IDEA",
                version = "2025.3.3",
                candidateLanguages = setOf("Cron expression"),
                provisioning =
                    RuntimeProvisioning.Blocked(
                        "The bundled Cron plugin exposes CronExp as an injected language without a LanguageFileType.",
                    ),
            ),
            SyntaxRuntime(
                id = "webstorm-jsonpath",
                productId = "WEBSTORM",
                version = "2025.3.3",
                candidateLanguages = setOf("JSONPath"),
                provisioning =
                    RuntimeProvisioning.Blocked(
                        "The bundled JSONPath provider exposes an embedded language without a LanguageFileType.",
                    ),
            ),
            SyntaxRuntime(
                id = "webstorm-vue",
                productId = "WEBSTORM",
                version = "2025.3.3",
                candidateLanguages = setOf("Vue"),
                provisioning =
                    RuntimeProvisioning.Blocked(
                        "The bundled Vue preview emits only host-language keys for the advertised operator primitive.",
                    ),
            ),
            SyntaxRuntime(
                id = "clion-objective-c",
                productId = "CLION",
                version = "2025.3.3",
                candidateLanguages = setOf("Objective-C"),
                provisioning =
                    RuntimeProvisioning.Blocked(
                        "CLion Nova exposes ObjectiveC without a public LanguageFileType for native previews.",
                    ),
            ),
            SyntaxRuntime(
                id = "rustrover",
                productId = "RUSTROVER",
                version = "2025.1.3",
                candidateLanguages = setOf("Rust"),
                provisioning =
                    RuntimeProvisioning.Blocked(
                        "JetBrains CDN artifact is missing the Core plugin; select a reproducible supported build.",
                    ),
            ),
            SyntaxRuntime(
                id = "noctule-swift",
                productId = "INTELLIJ_IDEA_COMMUNITY",
                version = "2025.1",
                candidateLanguages = setOf("Swift"),
                marketplaceDependency = MarketplaceDependency("dev.j-a.swift", "1.11.1.435-251"),
            ),
            SyntaxRuntime(
                id = "provider-research",
                productId = "INTELLIJ_IDEA_COMMUNITY",
                version = "2025.1",
                candidateLanguages =
                    setOf(
                        "CodeQL",
                        "Drools",
                        "Erlang",
                        "Ignore files",
                        "Lua",
                        "Nginx",
                        "PowerShell",
                        "Windows Batch",
                    ),
                provisioning =
                    RuntimeProvisioning.ProviderResearch(
                        "Resolve an official compatible provider and pin its Marketplace coordinates.",
                    ),
            ),
        )

    fun require(runtimeId: String): SyntaxRuntime =
        requireNotNull(entries.firstOrNull { runtime -> runtime.id == runtimeId }) {
            "Unknown syntax runtime '$runtimeId'; expected ${entries.joinToString { it.id }}"
        }

    private fun runtime(
        id: String,
        productId: String,
        version: String,
        languages: Set<String>,
    ): SyntaxRuntime =
        SyntaxRuntime(
            id = id,
            productId = productId,
            version = version,
            candidateLanguages = languages,
        )
}
