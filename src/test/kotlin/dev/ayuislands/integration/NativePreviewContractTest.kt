package dev.ayuislands.integration

import com.intellij.lexer.LexerBase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import dev.ayuislands.settings.ConditionalAbsence
import dev.ayuislands.settings.HighlightEvidence
import dev.ayuislands.settings.HighlightEvidenceCollector
import dev.ayuislands.settings.IdePreviewInspector
import dev.ayuislands.settings.NativePreviewResolution
import dev.ayuislands.settings.NativePreviewResolver
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
import dev.ayuislands.syntax.effectivePrimitive
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.test.assertTrue

class NativePreviewContractTest : LightPlatformCodeInsightFixture4TestCase() {
    private val runtime by lazy(SyntaxRuntimeSelection::select)

    @Test
    fun `production inspector confirms every candidate preview available in test runtime`() {
        val specifications = runtimeSpecifications()
        val results =
            ApplicationManager
                .getApplication()
                .executeOnPooledThread(
                    Callable {
                        specifications.associateWith { specification ->
                            ReadAction.compute<SyntaxProbeResult, RuntimeException> {
                                IdePreviewInspector(project).inspect(specification, generation = 12)
                            }
                        }
                    },
                ).get()
        val failures =
            results.mapNotNull { (specification, result) ->
                val expected =
                    specification.preview.files.flatMapTo(linkedSetOf(), PreviewFileSpec::demonstratedCategories)
                val confirmed = result as? SyntaxProbeResult.Confirmed
                val accounted =
                    confirmed?.evidence?.let { evidence ->
                        evidence.confirmedCells + evidence.conditionalAbsences.map(ConditionalAbsence::primitive)
                    }
                when {
                    confirmed == null -> "${specification.storageId}: $result"
                    accounted != expected ->
                        "${specification.storageId}: expected=$expected, accounted=$accounted"
                    else -> null
                }
            }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(prefix = "Production preview inspector failures:\n", separator = "\n"),
        )
    }

    @Test
    fun `lexical iterator contributes every attributes key without resolving colors`() {
        val keyword = TextAttributesKey.find("JAVA_KEYWORD")
        val operator = TextAttributesKey.find("JAVA_OPERATION_SIGN")
        val highlighter =
            object : SyntaxHighlighter {
                override fun getHighlightingLexer() = FixedTokenLexer(TokenType.WHITE_SPACE)

                override fun getTokenHighlights(tokenType: IElementType) = arrayOf(keyword, operator)
            }

        val evidence = HighlightEvidenceCollector.collect("Java", highlighter, "token", emptySet())

        assertEquals(setOf("JAVA_KEYWORD", "JAVA_OPERATION_SIGN"), evidence.lexicalKeys)
        assertEquals(setOf(PrimitiveCategory.KEYWORD, PrimitiveCategory.OPERATOR), evidence.categories)
    }

    @Test
    fun `semantic highlight keys supplement lexical categories`() {
        val keyword = TextAttributesKey.find("SWIFT.KEYWORD")
        val function = TextAttributesKey.find("SWIFT.FUNCTION_DECLARATION")
        val parameter = TextAttributesKey.find("SWIFT.PARAMETER")
        val evidence =
            HighlightEvidenceCollector.assemble(
                languageId = "Swift",
                lexicalKeys = setOf(keyword.externalName),
                supplementalKeys = setOf(function.externalName, parameter.externalName),
            )

        assertEquals(setOf("SWIFT.KEYWORD"), evidence.lexicalKeys)
        assertEquals(setOf("SWIFT.FUNCTION_DECLARATION", "SWIFT.PARAMETER"), evidence.supplementalKeys)
        assertEquals(
            setOf(PrimitiveCategory.KEYWORD, PrimitiveCategory.FUNCTION_DECL, PrimitiveCategory.PARAMETER),
            evidence.categories,
        )
    }

    @Test
    fun `installed native previews emit every claimed category`() {
        val results =
            runtimeSpecifications()
                .flatMap { specification ->
                    specification.preview.files.map { previewFile -> inspectPreview(specification, previewFile) }
                }
        val unavailable = results.filterIsInstance<NativePreviewResult.Unavailable>()
        val matrix = buildContractMatrix(results)
        publishContractReport(matrix)
        val missingClaims =
            results
                .filterIsInstance<NativePreviewResult.Verified>()
                .mapNotNull { result ->
                    val missing = result.previewFile.demonstratedCategories - result.evidence.categories
                    MissingClaims(result, missing).takeIf { it.categories.isNotEmpty() }
                }
        val problems =
            buildList {
                unavailable.forEach { result ->
                    add("Required ${result.language} preview resolved to ${result.fileTypeName}")
                }
                missingClaims.forEach { failure -> add(failure.describe()) }
                matrix.languages
                    .filter { language -> language.language in runtime.candidateLanguages }
                    .filter(LanguageContract::hasStructuralGap)
                    .forEach { language -> add("${language.language}: ${language.actions.joinToString(" ")}") }
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
            runtimeSpecifications()
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

    private fun buildContractMatrix(results: List<NativePreviewResult>): SyntaxContractMatrix {
        val verified =
            results
                .filterIsInstance<NativePreviewResult.Verified>()
                .groupBy(
                    keySelector = { result -> result.specification.storageId },
                    valueTransform = { result -> result.evidence.categories },
                ).mapValues { (_, categories) -> categories.flatten().toSet() }
        val runtimeEvidence =
            results
                .groupBy(
                    keySelector = NativePreviewResult::language,
                    valueTransform = { result -> result.toRuntimeEvidence(runtime.id) },
                )
        return SyntaxContractInventory.build(
            specifications = SyntaxLanguageRegistry.specifications(),
            existing = existingCategories(),
            verified = verified,
            runtimeEvidence = runtimeEvidence,
        )
    }

    private fun runtimeSpecifications(): List<LanguageSpecification> =
        runtime.candidateLanguages
            .map { language -> requireNotNull(SyntaxLanguageRegistry.findByStorageId(language)) }
            .sortedBy(LanguageSpecification::storageId)

    private fun existingCategories(): Map<String, Set<PrimitiveCategory>> {
        val loader = SyntaxOverlayLoader()
        return VARIANTS
            .flatMap { variant ->
                SyntaxIntensityApplicator
                    .tunableCategories(
                        baseline = loader.loadBaselineForVariant(variant),
                        overlay = loader.loadOverlayForVariant(variant),
                        fallbacks = loader.fallbacksFor(variant),
                    ).entries
            }.groupBy(
                keySelector = Map.Entry<String, Set<PrimitiveCategory>>::key,
                valueTransform = Map.Entry<String, Set<PrimitiveCategory>>::value,
            ).mapValues { (_, categories) -> categories.flatten().toSet() }
    }

    private fun publishContractReport(matrix: SyntaxContractMatrix) {
        val outputDirectory = Path.of(System.getProperty(REPORT_DIRECTORY_PROPERTY, DEFAULT_REPORT_DIRECTORY))
        SyntaxContractReport.write(matrix, runtime, outputDirectory)
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
                failures +=
                    "$languageId/$category has no language-owned native key; " +
                    "observed=${result.evidence.keysByPrimitive[category].orEmpty()}"
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
        val profile = requireNotNull(specification.nativeProfiles.firstOrNull { it.id == previewFile.profileId })
        val nativeFileType =
            when (val resolution = NativePreviewResolver().resolve(previewFile.fileName, profile)) {
                is NativePreviewResolution.Resolved -> resolution.fileType
                is NativePreviewResolution.LookupFailed -> throw resolution.failure
                is NativePreviewResolution.Unavailable -> {
                    myFixture.configureByText(previewFile.fileName, code)
                    return NativePreviewResult.Unavailable(
                        specification.storageId,
                        previewFile.profileId,
                        myFixture.file.fileType.nativeIdentity(),
                    )
                }
            }
        val projectFile = myFixture.addFileToProject(previewFile.fileName, code)
        if (isExpectedNativeType(profile, projectFile.fileType)) {
            myFixture.configureFromExistingVirtualFile(projectFile.virtualFile)
        } else {
            myFixture.configureByText(nativeFileType, code)
        }
        val projectFileType = myFixture.file.fileType
        val identity = projectFileType.nativeIdentity()
        if (!isExpectedNativeType(profile, projectFileType)) {
            return NativePreviewResult.Unavailable(specification.storageId, previewFile.profileId, identity)
        }

        val highlighter =
            checkNotNull(
                SyntaxHighlighterFactory.getSyntaxHighlighter(
                    projectFileType,
                    project,
                    myFixture.file.virtualFile,
                ),
            ) {
                "No syntax highlighter for ${previewFile.fileName}"
            }
        val fixture = myFixture as CodeInsightTestFixtureImpl
        val previewText = fixture.editor.document.text
        fixture.canChangeDocumentDuringHighlighting(runtime.allowsHighlightingRestart)
        val highlightInfos =
            try {
                fixture.doHighlighting()
            } finally {
                fixture.canChangeDocumentDuringHighlighting(false)
            }
        assertEquals(
            "${runtime.id} changed ${previewFile.fileName} during native highlighting",
            previewText,
            fixture.editor.document.text,
        )
        val semanticKeys =
            highlightInfos.mapTo(linkedSetOf()) { info ->
                (info.forcedTextAttributesKey ?: info.type.attributesKey).externalName
            }
        val descriptorKeys =
            HighlightEvidenceCollector
                .descriptorKeys(highlighter)
                .filterTo(linkedSetOf()) { keyName ->
                    SyntaxKeyRoleRegistry.classify(keyName).effectivePrimitive in specification.semanticOnlyCategories
                }
        val evidence =
            HighlightEvidenceCollector.collect(
                specification.storageId,
                highlighter,
                code,
                semanticKeys + descriptorKeys,
            )
        val origins =
            SyntaxEvidenceProvenance.classify(
                lexicalKeys = evidence.lexicalKeys,
                previewOccurrenceKeys = semanticKeys,
                descriptorKeys = descriptorKeys,
            )
        return NativePreviewResult.Verified(specification, previewFile, identity, evidence, origins)
    }

    private fun FileType.nativeIdentity(): NativeIdentity {
        val languageIds =
            (this as? LanguageFileType)
                ?.language
                ?.let { language -> setOf(language.id, language.displayName) }
                .orEmpty()
        return NativeIdentity(
            fileTypeName = name,
            fileTypeClass = javaClass.name,
            languageIds = languageIds,
        )
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

    private sealed interface NativePreviewResult {
        val language: String

        fun toRuntimeEvidence(runtimeId: String): RuntimeSyntaxEvidence

        data class Verified(
            val specification: LanguageSpecification,
            val previewFile: PreviewFileSpec,
            val identity: NativeIdentity,
            val evidence: HighlightEvidence,
            val originsByPrimitive: Map<PrimitiveCategory, Set<SyntaxEvidenceOrigin>>,
        ) : NativePreviewResult {
            override val language: String = specification.storageId
            val fileTypeName: String = identity.description

            override fun toRuntimeEvidence(runtimeId: String): RuntimeSyntaxEvidence =
                RuntimeSyntaxEvidence(
                    runtimeId = runtimeId,
                    profileId = previewFile.profileId,
                    status = RuntimeEvidenceStatus.VERIFIED,
                    fileTypeName = identity.fileTypeName,
                    languageIds = identity.languageIds,
                    originsByPrimitive = originsByPrimitive,
                )
        }

        data class Unavailable(
            override val language: String,
            val profileId: String,
            val identity: NativeIdentity,
        ) : NativePreviewResult {
            val fileTypeName: String = identity.description

            override fun toRuntimeEvidence(runtimeId: String): RuntimeSyntaxEvidence =
                RuntimeSyntaxEvidence(
                    runtimeId = runtimeId,
                    profileId = profileId,
                    status = RuntimeEvidenceStatus.UNAVAILABLE,
                    fileTypeName = identity.fileTypeName,
                    languageIds = identity.languageIds,
                )
        }
    }

    private data class NativeIdentity(
        val fileTypeName: String,
        val fileTypeClass: String,
        val languageIds: Set<String>,
    ) {
        val description: String = "$fileTypeName ($fileTypeClass)"
    }

    private data class MissingClaims(
        val result: NativePreviewResult.Verified,
        val categories: Set<PrimitiveCategory>,
    ) {
        fun describe(): String =
            "${result.specification.storageId}: missing=$categories, fileType=${result.fileTypeName}, " +
                "resource=${result.previewFile.resourceName}, lexical=${result.evidence.lexicalKeys}, " +
                "semantic=${result.evidence.supplementalKeys}"
    }

    private data class ActuationFixture(
        val variant: String,
        val baseline: Map<TextAttributesKey, TextAttributes>,
        val overlay: Map<TextAttributesKey, TextAttributes>,
        val fallbacks: Map<String, String>,
    )

    private companion object {
        private const val AUTO_DETECTED_FILE_TYPE = "AUTO_DETECTED"
        private const val REPORT_DIRECTORY_PROPERTY = "syntaxContractReportDir"
        private const val DEFAULT_REPORT_DIRECTORY = "build/reports/syntax-contract"

        private val VARIANTS = listOf("Mirage", "Dark", "Light")
        private val INTENSITIES = listOf(0, 25, 50, 75, 100)
    }
}

private class FixedTokenLexer(
    private val fixedType: IElementType,
) : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset = 0
    private var endOffset = 0
    private var hasToken = false

    override fun start(
        buffer: CharSequence,
        startOffset: Int,
        endOffset: Int,
        initialState: Int,
    ) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        hasToken = startOffset < endOffset
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = fixedType.takeIf { hasToken }

    override fun getTokenStart(): Int = startOffset

    override fun getTokenEnd(): Int = endOffset

    override fun advance() {
        hasToken = false
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = endOffset
}
