package dev.ayuislands.integration

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import dev.ayuislands.settings.SyntaxPreviewCatalog
import dev.ayuislands.settings.SyntaxPreviewSpec
import dev.ayuislands.syntax.PrimitiveCategory
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativePreviewContractTest : LightPlatformCodeInsightFixture4TestCase() {
    @Test
    fun `lexical iterator contributes every attributes key without resolving colors`() {
        val keyword = TextAttributesKey.find("JAVA_KEYWORD")
        val operator = TextAttributesKey.find("JAVA_OPERATION_SIGN")
        val highlighter = highlighterWith(listOf(arrayOf(keyword), arrayOf(operator)))

        val evidence = PreviewHighlightProbe.collect("Java", highlighter, emptyList())

        assertEquals(setOf("JAVA_KEYWORD", "JAVA_OPERATION_SIGN"), evidence.lexicalKeys)
        assertEquals(setOf(PrimitiveCategory.KEYWORD, PrimitiveCategory.OPERATOR), evidence.categories)
    }

    @Test
    fun `semantic highlight keys supplement lexical categories`() {
        val keyword = TextAttributesKey.find("SWIFT.KEYWORD")
        val function = TextAttributesKey.find("SWIFT.FUNCTION_DECLARATION")
        val parameter = TextAttributesKey.find("SWIFT.PARAMETER")
        val highlighter = highlighterWith(listOf(arrayOf(keyword)))
        val forcedInfo =
            HighlightInfo
                .newHighlightInfo(HighlightInfoType.INFORMATION)
                .range(0, 1)
                .textAttributes(function)
                .createUnconditionally()
        val typedInfo =
            HighlightInfo
                .newHighlightInfo(
                    HighlightInfoType.HighlightInfoTypeImpl(
                        HighlightSeverity.INFORMATION,
                        parameter,
                    ),
                ).range(1, 2)
                .createUnconditionally()

        val evidence = PreviewHighlightProbe.collect("Swift", highlighter, listOf(forcedInfo, typedInfo))

        assertEquals(setOf("SWIFT.KEYWORD"), evidence.lexicalKeys)
        assertEquals(setOf("SWIFT.FUNCTION_DECLARATION", "SWIFT.PARAMETER"), evidence.semanticKeys)
        assertEquals(
            setOf(PrimitiveCategory.KEYWORD, PrimitiveCategory.FUNCTION_DECL, PrimitiveCategory.PARAMETER),
            evidence.categories,
        )
    }

    @Test
    fun `installed native previews emit every claimed category`() {
        val results =
            SyntaxPreviewCatalog
                .languages()
                .sorted()
                .map { language -> inspectPreview(requireNotNull(SyntaxPreviewCatalog.find(language))) }
        val unavailable = results.filterIsInstance<NativePreviewResult.Unavailable>()
        val requiredUnavailable = unavailable.filter { result -> result.language in REQUIRED_NATIVE_LANGUAGES }
        val missingClaims =
            results
                .filterIsInstance<NativePreviewResult.Verified>()
                .mapNotNull { result ->
                    val missing = result.spec.demonstratedCategories - result.evidence.categories
                    MissingClaims(result, missing).takeIf { it.categories.isNotEmpty() }
                }
        val problems =
            buildList {
                requiredUnavailable.forEach { result ->
                    add("Required ${result.language} preview resolved to ${result.fileTypeName}")
                }
                missingClaims.forEach { failure -> add(failure.describe()) }
            }
        val unavailableSummary =
            unavailable.joinToString { result -> "${result.language}=${result.fileTypeName}" }

        assertTrue(
            problems.isEmpty(),
            problems.joinToString(
                prefix = "Native preview contract failures:\n",
                separator = "\n",
                postfix = "\nUnavailable optional previews: $unavailableSummary",
            ),
        )
    }

    private fun inspectPreview(spec: SyntaxPreviewSpec): NativePreviewResult {
        val code = loadPreview(spec.resourceName)
        myFixture.configureByText(spec.fileName, code)
        val fileType = myFixture.file.fileType
        val fileTypeIdentity = "${fileType.name} (${fileType.javaClass.name})"
        if (!isExpectedNativeType(spec, fileType)) {
            return NativePreviewResult.Unavailable(spec.language, fileTypeIdentity)
        }

        val scheme = EditorColorsManager.getInstance().globalScheme
        val highlighter =
            EditorHighlighterFactory
                .getInstance()
                .createEditorHighlighter(myFixture.file.virtualFile, scheme, project)
        highlighter.setText(code)
        val highlightInfos = myFixture.doHighlighting()
        val evidence = PreviewHighlightProbe.collect(spec.language, highlighter, highlightInfos)
        return NativePreviewResult.Verified(spec, fileTypeIdentity, evidence)
    }

    private fun isExpectedNativeType(
        spec: SyntaxPreviewSpec,
        fileType: FileType,
    ): Boolean {
        if (fileType === PlainTextFileType.INSTANCE) return false
        if (fileType === UnknownFileType.INSTANCE) return false
        if (fileType.name == AUTO_DETECTED_FILE_TYPE) return false
        if (fileType !is LanguageFileType) return false
        if (!fileType.name.equals(spec.standardFileTypeName, ignoreCase = true)) return false
        return matchesLanguage(spec.language, fileType)
    }

    private fun matchesLanguage(
        expectedLanguage: String,
        fileType: LanguageFileType,
    ): Boolean {
        val acceptedNames = LANGUAGE_ALIASES[expectedLanguage].orEmpty() + expectedLanguage
        return acceptedNames.any { name -> fileType.language.displayName.equals(name, ignoreCase = true) }
    }

    private fun loadPreview(resourceName: String): String {
        val path = "/dev/ayuislands/settings/syntax-preview/$resourceName"
        val stream = checkNotNull(javaClass.getResourceAsStream(path)) { "Missing preview resource $path" }
        return stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText().trimIndent() }
    }

    private fun highlighterWith(ranges: List<Array<TextAttributesKey>>): EditorHighlighter {
        val iterator = mockk<HighlighterIterator>()
        every { iterator.atEnd() } returnsMany List(ranges.size) { false } + true
        every { iterator.textAttributesKeys } returnsMany ranges
        every { iterator.advance() } just Runs

        return mockk {
            every { createIterator(0) } returns iterator
        }
    }

    private sealed interface NativePreviewResult {
        data class Verified(
            val spec: SyntaxPreviewSpec,
            val fileTypeName: String,
            val evidence: PreviewEvidence,
        ) : NativePreviewResult

        data class Unavailable(
            val language: String,
            val fileTypeName: String,
        ) : NativePreviewResult
    }

    private data class MissingClaims(
        val result: NativePreviewResult.Verified,
        val categories: Set<PrimitiveCategory>,
    ) {
        fun describe(): String =
            "${result.spec.language}: missing=$categories, fileType=${result.fileTypeName}, " +
                "resource=${result.spec.resourceName}, lexical=${result.evidence.lexicalKeys}, " +
                "semantic=${result.evidence.semanticKeys}"
    }

    private companion object {
        private const val AUTO_DETECTED_FILE_TYPE = "AUTO_DETECTED"

        private val LANGUAGE_ALIASES =
            mapOf(
                "Bash" to setOf("Shell Script"),
                "Properties files" to setOf("Properties"),
            )

        private val REQUIRED_NATIVE_LANGUAGES =
            setOf(
                "Java",
                "Kotlin",
                "Groovy",
                "Bash",
                "JSON",
                "Markdown",
                "XML",
                "Properties files",
                "YAML",
            )
    }
}
