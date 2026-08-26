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
                    ),
                ),
            )

        val report = SyntaxContractReport.render(matrix)

        assertContains(report.json, "\"language\": \"Swift\"")
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

        val report = SyntaxContractReport.render(matrix)

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

        val written = SyntaxContractReport.write(matrix, reportDirectory)

        assertEquals(
            setOf("syntax-contract.html", "syntax-contract.json", "syntax-contract.md"),
            written.map { it.fileName.toString() }.toSet(),
        )
        assertContains(
            reportDirectory.resolve("syntax-contract.json").toFile().readText(),
            "\"language\": \"Kotlin\"",
        )
    }
}
