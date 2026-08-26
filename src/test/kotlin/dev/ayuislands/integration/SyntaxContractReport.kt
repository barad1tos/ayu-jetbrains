package dev.ayuislands.integration

import dev.ayuislands.syntax.PrimitiveCategory
import java.nio.file.Files
import java.nio.file.Path

internal data class RenderedSyntaxContract(
    val json: String,
    val html: String,
    val markdown: String,
)

internal object SyntaxContractReport {
    fun render(matrix: SyntaxContractMatrix): RenderedSyntaxContract =
        RenderedSyntaxContract(
            json = renderJson(matrix),
            html = renderHtml(matrix),
            markdown = renderMarkdown(matrix),
        )

    fun write(
        matrix: SyntaxContractMatrix,
        outputDirectory: Path,
    ): List<Path> {
        val report = render(matrix)
        Files.createDirectories(outputDirectory)
        return listOf(
            outputDirectory.resolve("syntax-contract.json").write(report.json),
            outputDirectory.resolve("syntax-contract.html").write(report.html),
            outputDirectory.resolve("syntax-contract.md").write(report.markdown),
        )
    }

    private fun renderJson(matrix: SyntaxContractMatrix): String =
        matrix.languages.joinToString(
            prefix = "{\n  \"languages\": [\n",
            separator = ",\n",
            postfix = "\n  ]\n}\n",
        ) { language -> language.toJson().prependIndent("    ") }

    private fun LanguageContract.toJson(): String =
        buildString {
            appendLine("{")
            appendLine("  \"language\": ${language.jsonString()},")
            appendLine("  \"advertised\": $advertised,")
            appendLine("  \"complete\": $isComplete,")
            appendLine("  \"existing\": ${existing.toJson()},")
            appendLine("  \"declared\": ${declared.toJson()},")
            appendLine("  \"previewed\": ${previewed.toJson()},")
            appendLine("  \"verified\": ${verified.toJson()},")
            appendLine("  \"existingButUndeclared\": ${undeclaredImplementations.toJson()},")
            appendLine("  \"declaredButMissingImplementation\": ${unimplementedDeclarations.toJson()},")
            appendLine("  \"declaredButMissingPreview\": ${unpreviewedDeclarations.toJson()},")
            appendLine("  \"previewedButUnverified\": ${unverifiedPreviews.toJson()},")
            appendLine("  \"actions\": ${actions.toJson()}")
            append("}")
        }

    private fun renderHtml(matrix: SyntaxContractMatrix): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Ayu Islands syntax contract</title>
          <style>
            body { font: 14px system-ui, sans-serif; margin: 24px; color: #24292f; }
            input { box-sizing: border-box; margin-bottom: 12px; padding: 8px; width: 100%; }
            table { border-collapse: collapse; width: 100%; }
            th, td { border: 1px solid #d0d7de; padding: 8px; text-align: left; vertical-align: top; }
            th { background: #f6f8fa; position: sticky; top: 0; }
            .complete { color: #1a7f37; }
            .action { color: #9a6700; }
          </style>
        </head>
        <body>
          <h1>Ayu Islands syntax contract</h1>
          <input id="filter" type="search" placeholder="Filter languages or primitives" aria-label="Filter matrix">
          <table>
            <thead>
              <tr><th>Language</th><th>Status</th><th>Exists</th><th>Declared</th><th>Previewed</th><th>Verified</th><th>Action</th></tr>
            </thead>
            <tbody>
        ${matrix.languages.joinToString("\n") { it.toHtmlRow() }.prependIndent("      ")}
            </tbody>
          </table>
          <script>
            const filter = document.getElementById('filter');
            filter.addEventListener('input', () => {
              const query = filter.value.toLowerCase();
              document.querySelectorAll('tbody tr').forEach(row => {
                row.hidden = !row.textContent.toLowerCase().includes(query);
              });
            });
          </script>
        </body>
        </html>
        """.trimIndent() + "\n"

    private fun LanguageContract.toHtmlRow(): String {
        val status = if (isComplete) "Complete" else "Needs action"
        val statusClass = if (isComplete) "complete" else "action"
        return "<tr data-language=\"${language.lowercase().htmlAttribute()}\">" +
            "<td>${language.html()}</td>" +
            "<td class=\"$statusClass\">$status</td>" +
            "<td>${existing.toDisplayText()}</td>" +
            "<td>${declared.toDisplayText()}</td>" +
            "<td>${previewed.toDisplayText()}</td>" +
            "<td>${verified.toDisplayText()}</td>" +
            "<td>${actions.joinToString(" ").html()}</td></tr>"
    }

    private fun renderMarkdown(matrix: SyntaxContractMatrix): String {
        val complete = matrix.languages.count(LanguageContract::isComplete)
        val needsAction = matrix.languages.size - complete
        val details =
            matrix.languages
                .filterNot(LanguageContract::isComplete)
                .joinToString("\n") { language ->
                    "- **${language.language.html()}**: ${language.actions.joinToString(" ").html()}"
                }
        return buildString {
            append("Syntax contract: ${matrix.languages.size} languages")
            append(" | $complete complete | $needsAction need action")
            if (details.isNotEmpty()) append("\n$details")
            append('\n')
        }
    }
}

private fun Path.write(content: String): Path = also { Files.writeString(this, content) }

private fun Set<PrimitiveCategory>.toJson(): String = map(PrimitiveCategory::name).sorted().toJson()

private fun List<String>.toJson(): String = joinToString(prefix = "[", postfix = "]") { it.jsonString() }

private fun String.jsonString(): String =
    buildString {
        append('"')
        for (character in this@jsonString) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

private fun Set<PrimitiveCategory>.toDisplayText(): String =
    sortedBy(PrimitiveCategory::name).joinToString(", ", transform = PrimitiveCategory::displayName).html()

private fun String.html(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

private fun String.htmlAttribute(): String = html().replace("\"", "&quot;")
