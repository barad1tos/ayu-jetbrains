package dev.ayuislands.integration

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import dev.ayuislands.settings.HighlightEvidence
import dev.ayuislands.settings.HighlightEvidenceCollector
import dev.ayuislands.settings.IdePreviewInspector
import dev.ayuislands.settings.SyntaxProbeResult
import dev.ayuislands.syntax.FontEmphasis
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.NativeProfile
import dev.ayuislands.syntax.PreviewFileSpec
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxKeyRole
import dev.ayuislands.syntax.SyntaxKeyRoleRegistry
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import dev.ayuislands.syntax.SyntaxOverlayLoader
import dev.ayuislands.syntax.SyntaxPreset
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Test
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativePreviewContractTest : LightPlatformCodeInsightFixture4TestCase() {
    @Test
    fun `production inspector confirms bundled Java preview through native passes`() {
        val specification = requireNotNull(SyntaxLanguageRegistry.findByStorageId("Java"))

        val result =
            ApplicationManager
                .getApplication()
                .executeOnPooledThread(
                    Callable {
                        ReadAction.compute<SyntaxProbeResult, RuntimeException> {
                            IdePreviewInspector(project).inspect(specification, generation = 12)
                        }
                    },
                ).get()

        val confirmed = assertIs<SyntaxProbeResult.Confirmed>(result)
        assertEquals("Java", confirmed.languageId)
        assertEquals(12, confirmed.generation)
        assertEquals(
            specification.preview.files
                .single()
                .demonstratedCategories,
            confirmed.evidence.confirmedCells,
        )
    }

    @Test
    fun `lexical iterator contributes every attributes key without resolving colors`() {
        val keyword = TextAttributesKey.find("JAVA_KEYWORD")
        val operator = TextAttributesKey.find("JAVA_OPERATION_SIGN")
        val highlighter = highlighterWith(listOf(arrayOf(keyword), arrayOf(operator)))

        val evidence = HighlightEvidenceCollector.collect("Java", highlighter, emptyList())

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

        val evidence = HighlightEvidenceCollector.collect("Swift", highlighter, listOf(forcedInfo, typedInfo))

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
            SyntaxLanguageRegistry
                .specifications()
                .sortedBy(LanguageSpecification::storageId)
                .flatMap { specification ->
                    specification.preview.files.map { previewFile -> inspectPreview(specification, previewFile) }
                }
        val unavailable = results.filterIsInstance<NativePreviewResult.Unavailable>()
        val requiredUnavailable = unavailable.filter { result -> result.language in REQUIRED_NATIVE_LANGUAGES }
        val missingClaims =
            results
                .filterIsInstance<NativePreviewResult.Verified>()
                .mapNotNull { result ->
                    val missing = result.previewFile.demonstratedCategories - result.evidence.categories
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

    @Test
    fun `every installed language-owned preview key actuates all cell dimensions`() {
        val results =
            SyntaxLanguageRegistry
                .specifications()
                .sortedBy(LanguageSpecification::storageId)
                .flatMap { specification ->
                    specification.preview.files.map { previewFile -> inspectPreview(specification, previewFile) }
                }.filterIsInstance<NativePreviewResult.Verified>()
        val failures =
            buildList {
                results.forEach { result -> verifyActuation(result, this) }
            }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(prefix = "Native actuation failures:\n", separator = "\n"),
        )
    }

    private fun verifyActuation(
        result: NativePreviewResult.Verified,
        failures: MutableList<String>,
    ) {
        val languageId = result.specification.storageId
        result.previewFile.demonstratedCategories.forEach { category ->
            val keys =
                result.evidence.keysByPrimitive[category].orEmpty().filter { keyName ->
                    val role = SyntaxKeyRoleRegistry.classify(keyName)
                    role is SyntaxKeyRole.Tunable && role.languageId == languageId
                }
            if (keys.isEmpty()) {
                failures += "$languageId/$category has no language-owned native key"
                return@forEach
            }
            VARIANTS.forEach { variant ->
                keys.forEach { keyName ->
                    verifyKeyActuation(languageId, category, keyName, variant, failures)
                }
            }
        }
    }

    private fun verifyKeyActuation(
        languageId: String,
        category: PrimitiveCategory,
        keyName: String,
        variant: String,
        failures: MutableList<String>,
    ) {
        val loader = SyntaxOverlayLoader()
        val fixture =
            ActuationFixture(
                variant = variant,
                baseline = loader.loadBaselineForVariant(variant),
                overlay = loader.loadOverlayForVariant(variant),
                fallbacks = loader.fallbacksFor(variant),
            )
        val key = TextAttributesKey.find(keyName)
        val resolvedSource = resolveAttributes(keyName, fixture.baseline, fixture.overlay, fixture.fallbacks)
        if (resolvedSource?.foregroundColor == null) {
            failures += "$variant/$languageId/$category/$keyName has no resolvable Ayu foreground"
            return
        }
        val intensities =
            INTENSITIES.mapNotNull { intensity ->
                computeCell(
                    fixture,
                    customOverrides = mapOf(languageId to mapOf(category.name to intensity)),
                )[key]
            }
        if (intensities.size != INTENSITIES.size) {
            failures += "$variant/$languageId/$category/$keyName misses intensity outputs"
            return
        }
        if (intensities.first().foregroundColor == intensities.last().foregroundColor) {
            failures += "$variant/$languageId/$category/$keyName has inert intensity endpoints"
        }
        intensities.forEach { attributes ->
            if (!preservesNonFontAttributes(attributes, resolvedSource)) {
                failures += "$variant/$languageId/$category/$keyName changes unrelated attributes"
            }
        }
        FontStyleOverride.entries.forEach { style ->
            val output =
                computeCell(
                    fixture,
                    customStyles = mapOf(languageId to mapOf(category.name to style.fontType)),
                )[key]
            if (output?.fontType != style.fontType) {
                failures += "$variant/$languageId/$category/$keyName cannot apply ${style.name}"
            }
        }
        FontEmphasis.entries.forEach { emphasis ->
            val output =
                computeCell(
                    fixture,
                    customStyles = mapOf(languageId to mapOf(category.name to FontStyleOverride.PLAIN.fontType)),
                    customEmphasis = mapOf(languageId to mapOf(category.name to emphasis.fontType)),
                )[key]
            if (output?.fontType != emphasis.fontType) {
                failures += "$variant/$languageId/$category/$keyName cannot add ${emphasis.name}"
            }
        }
    }

    private fun computeCell(
        fixture: ActuationFixture,
        customOverrides: Map<String, Map<String, Int>> = emptyMap(),
        customStyles: Map<String, Map<String, Int>> = emptyMap(),
        customEmphasis: Map<String, Map<String, Int>> = emptyMap(),
    ): Map<TextAttributesKey, TextAttributes> =
        SyntaxIntensityApplicator.compute(
            SyntaxIntensityApplicator.Request(
                preset = SyntaxPreset.CUSTOM,
                variantName = fixture.variant,
                editorBg = EditorColorsManager.getInstance().globalScheme.defaultBackground,
                baseline = fixture.baseline,
                overlay = fixture.overlay,
                customOverrides = customOverrides,
                customStyles = customStyles,
                customEmphasis = customEmphasis,
                fallbacks = fixture.fallbacks,
            ),
        )

    private fun preservesNonFontAttributes(
        actual: TextAttributes,
        expected: TextAttributes,
    ): Boolean =
        actual.backgroundColor == expected.backgroundColor &&
            actual.effectColor == expected.effectColor &&
            actual.effectType == expected.effectType &&
            actual.errorStripeColor == expected.errorStripeColor

    private fun resolveAttributes(
        keyName: String,
        baseline: Map<TextAttributesKey, TextAttributes>,
        overlay: Map<TextAttributesKey, TextAttributes>,
        fallbacks: Map<String, String>,
    ): TextAttributes? {
        val byName = (baseline + overlay).entries.associate { it.key.externalName to it.value }
        val visited = mutableSetOf<String>()
        var current: String? = keyName
        while (current != null && visited.add(current)) {
            byName[current]?.takeIf { it.foregroundColor != null }?.let { return it }
            current = fallbacks[current] ?: TextAttributesKey.find(current).fallbackAttributeKey?.externalName
        }
        return null
    }

    private fun inspectPreview(
        specification: LanguageSpecification,
        previewFile: PreviewFileSpec,
    ): NativePreviewResult {
        val code = loadPreview(previewFile.resourceName)
        myFixture.configureByText(previewFile.fileName, code)
        val fileType = myFixture.file.fileType
        val fileTypeIdentity = "${fileType.name} (${fileType.javaClass.name})"
        val profile = requireNotNull(specification.nativeProfiles.firstOrNull { it.id == previewFile.profileId })
        if (!isExpectedNativeType(profile, fileType)) {
            return NativePreviewResult.Unavailable(specification.storageId, fileTypeIdentity)
        }

        val scheme = EditorColorsManager.getInstance().globalScheme
        val highlighter =
            EditorHighlighterFactory
                .getInstance()
                .createEditorHighlighter(myFixture.file.virtualFile, scheme, project)
        highlighter.setText(code)
        val highlightInfos = myFixture.doHighlighting()
        val evidence = HighlightEvidenceCollector.collect(specification.storageId, highlighter, highlightInfos)
        return NativePreviewResult.Verified(specification, previewFile, fileTypeIdentity, evidence)
    }

    private fun isExpectedNativeType(
        profile: NativeProfile,
        fileType: FileType,
    ): Boolean {
        if (fileType === PlainTextFileType.INSTANCE) return false
        if (fileType === UnknownFileType.INSTANCE) return false
        if (fileType.name == AUTO_DETECTED_FILE_TYPE) return false
        if (fileType !is LanguageFileType) return false
        if (profile.fileTypeNames.none { it.equals(fileType.name, ignoreCase = true) }) return false
        return matchesLanguage(profile, fileType)
    }

    private fun matchesLanguage(
        profile: NativeProfile,
        fileType: LanguageFileType,
    ): Boolean =
        profile.languageIds.any { name ->
            fileType.language.id.equals(name, ignoreCase = true) ||
                fileType.language.displayName.equals(name, ignoreCase = true)
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
            val specification: LanguageSpecification,
            val previewFile: PreviewFileSpec,
            val fileTypeName: String,
            val evidence: HighlightEvidence,
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
            "${result.specification.storageId}: missing=$categories, fileType=${result.fileTypeName}, " +
                "resource=${result.previewFile.resourceName}, lexical=${result.evidence.lexicalKeys}, " +
                "semantic=${result.evidence.semanticKeys}"
    }

    private data class ActuationFixture(
        val variant: String,
        val baseline: Map<TextAttributesKey, TextAttributes>,
        val overlay: Map<TextAttributesKey, TextAttributes>,
        val fallbacks: Map<String, String>,
    )

    private companion object {
        private const val AUTO_DETECTED_FILE_TYPE = "AUTO_DETECTED"

        private val VARIANTS = listOf("Mirage", "Dark", "Light")
        private val INTENSITIES = listOf(0, 25, 50, 75, 100)

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
