package dev.ayuislands.settings

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.ColorSettingsPages
import dev.ayuislands.syntax.SyntaxKeyRoleRegistry
import dev.ayuislands.syntax.effectivePrimitive

internal object HighlightEvidenceCollector {
    fun descriptorKeys(highlighter: SyntaxHighlighter): Set<String> =
        ColorSettingsPages
            .getInstance()
            .registeredPages
            .asSequence()
            .filter { page -> page.highlighter.javaClass == highlighter.javaClass }
            .flatMap { page ->
                (
                    page.attributeDescriptors.map { descriptor -> descriptor.key } +
                        page.additionalHighlightingTagToDescriptorMap.orEmpty().values
                ).asSequence()
            }.mapTo(linkedSetOf(), TextAttributesKey::getExternalName)

    fun collect(
        languageId: String,
        highlighter: SyntaxHighlighter,
        text: CharSequence,
        supplementalKeys: Set<String>,
    ): HighlightEvidence = assemble(languageId, lexicalKeys(highlighter, text), supplementalKeys)

    fun assemble(
        languageId: String,
        lexicalKeys: Set<String>,
        supplementalKeys: Set<String>,
    ): HighlightEvidence {
        val keysByPrimitive =
            (lexicalKeys + supplementalKeys)
                .mapNotNull { keyName ->
                    val primitive = SyntaxKeyRoleRegistry.classify(keyName).effectivePrimitive
                    primitive?.let { it to keyName }
                }.groupBy({ it.first }, { it.second })
                .mapValues { (_, keyNames) -> keyNames.toSet() }

        return HighlightEvidence(
            languageId = languageId,
            lexicalKeys = lexicalKeys,
            supplementalKeys = supplementalKeys,
            keysByPrimitive = keysByPrimitive,
        )
    }

    private fun lexicalKeys(
        highlighter: SyntaxHighlighter,
        text: CharSequence,
    ): Set<String> {
        val keys = linkedSetOf<String>()
        val lexer = highlighter.highlightingLexer
        lexer.start(text)
        while (lexer.tokenType != null) {
            highlighter.getTokenHighlights(lexer.tokenType).mapTo(keys, TextAttributesKey::getExternalName)
            lexer.advance()
        }
        return keys
    }
}
