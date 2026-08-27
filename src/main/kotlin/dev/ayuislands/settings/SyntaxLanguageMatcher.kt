package dev.ayuislands.settings

import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.NativeProfile
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import java.util.Locale

internal class SyntaxLanguageMatcher(
    specifications: List<LanguageSpecification> = SyntaxLanguageRegistry.specifications(),
) {
    private val byFileName = specifications.indexBy { it.exactFileNames }
    private val byLanguageId = specifications.indexBy { it.languageIds }
    private val byFileTypeName = specifications.indexBy { it.fileTypeNames }
    private val byExtension = specifications.indexBy { it.extensions }

    fun match(activeFile: ActiveFileContext): LanguageSpecification? {
        byFileName.unique(activeFile.fileName)?.let { return it }

        val languageMatches =
            activeFile.languageIds
                .flatMapTo(linkedSetOf()) { byLanguageId[normalize(it)].orEmpty() }
        val fileTypeMatches = byFileTypeName[normalize(activeFile.fileTypeName)].orEmpty()
        languageMatches.intersect(fileTypeMatches).singleOrNull()?.let { return it }
        languageMatches.singleOrNull()?.let { return it }
        fileTypeMatches.singleOrNull()?.let { return it }

        return byExtension.unique(activeFile.fileName.substringAfterLast('.', missingDelimiterValue = ""))
    }

    private fun List<LanguageSpecification>.indexBy(
        selectors: (NativeProfile) -> Set<String>,
    ): Map<String, Set<LanguageSpecification>> =
        buildMap<String, MutableSet<LanguageSpecification>> {
            for (specification in this@indexBy) {
                for (profile in specification.nativeProfiles) {
                    for (selector in selectors(profile)) {
                        getOrPut(normalize(selector), ::linkedSetOf).add(specification)
                    }
                }
            }
        }

    private fun Map<String, Set<LanguageSpecification>>.unique(value: String): LanguageSpecification? =
        get(normalize(value))?.singleOrNull()

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
}
