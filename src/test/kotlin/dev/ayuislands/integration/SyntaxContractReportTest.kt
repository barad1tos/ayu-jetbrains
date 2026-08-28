package dev.ayuislands.integration

import dev.ayuislands.syntax.PrimitiveCategory.KEYWORD
import dev.ayuislands.syntax.PrimitiveCategory.OPERATOR
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SyntaxContractReportTest {
    @TempDir
    lateinit var reportDirectory: Path
    private val runtime = SyntaxRuntimeCatalog.require("idea-community")

    @Test
    fun `report exposes structured evidence actions and a filterable table`() {
        val matrix =
            SyntaxContractMatrix.build(
                listOf(
                    LanguageContractEvidence(
                        language = "Swift",
                        advertised = true,
                        existing = setOf(KEYWORD, OPERATOR),
                        declared = setOf(KEYWORD),
                        previewed = setOf(KEYWORD),
                        verified = setOf(KEYWORD),
                        runtimeEvidence =
                            listOf(
                                RuntimeSyntaxEvidence(
                                    runtimeId = "idea-community",
                                    profileId = "Swift:default",
                                    status = RuntimeEvidenceStatus.VERIFIED,
                                    fileTypeName = "NoctuleSwift",
                                    languageIds = setOf("NoctuleSwift", "Swift"),
                                    originsByPrimitive =
                                        mapOf(
                                            KEYWORD to
                                                setOf(
                                                    SyntaxEvidenceOrigin.LEXICAL_TOKEN,
                                                    SyntaxEvidenceOrigin.PREVIEW_OCCURRENCE,
                                                ),
                                        ),
                                ),
                            ),
                    ),
                ),
            )

        val report = SyntaxContractReport.render(matrix, runtime)

        assertContains(report.json, "\"schemaVersion\": 1")
        assertContains(report.json, "\"id\": \"idea-community\"")
        assertContains(report.json, "\"product\": \"INTELLIJ_IDEA_COMMUNITY\"")
        assertContains(report.json, "\"version\": \"2025.1\"")
        assertContains(report.json, "\"language\": \"Swift\"")
        assertContains(report.json, "\"profileId\": \"Swift:default\"")
        assertContains(report.json, "\"status\": \"VERIFIED\"")
        assertContains(report.json, "\"fileTypeName\": \"NoctuleSwift\"")
        assertContains(report.json, "\"languageIds\": [\"NoctuleSwift\", \"Swift\"]")
        assertContains(report.json, "\"KEYWORD\": [\"LEXICAL_TOKEN\", \"PREVIEW_OCCURRENCE\"]")
        assertContains(report.json, "\"existingButUndeclared\": [\"OPERATOR\"]")
        assertContains(
            report.json,
            "\"actions\": [\"Add language-owned native evidence for Operator before declaring Swift tuning.\"]",
        )
        assertContains(report.html, "<input id=\"filter\"")
        assertContains(report.html, "data-language=\"swift\"")
        assertContains(report.html, "Add language-owned native evidence for Operator before declaring Swift tuning.")
        assertContains(report.markdown, "1 languages | 0 complete | 1 need action")
    }

    @Test
    fun `report escapes language and action content in every output`() {
        val matrix =
            SyntaxContractMatrix.build(
                listOf(LanguageContractEvidence(language = "C# <script>", advertised = true)),
            )

        val report = SyntaxContractReport.render(matrix, runtime)

        assertContains(report.json, "C# <script>")
        assertContains(report.html, "C# &lt;script&gt;")
        assertContains(report.markdown, "C# &lt;script&gt;")
    }

    @Test
    fun `writer publishes deterministic report filenames`() {
        val matrix =
            SyntaxContractMatrix.build(
                listOf(LanguageContractEvidence(language = "Kotlin", advertised = true)),
            )

        val written = SyntaxContractReport.write(matrix, runtime, reportDirectory)

        assertEquals(
            setOf("syntax-contract.html", "syntax-contract-idea-community.json", "syntax-contract.md"),
            written.map { it.fileName.toString() }.toSet(),
        )
        assertContains(
            reportDirectory.resolve("syntax-contract-idea-community.json").toFile().readText(),
            "\"language\": \"Kotlin\"",
        )
    }
}
