package dev.ayuislands.integration

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxKeyRoleRegistry
import dev.ayuislands.syntax.effectivePrimitive

internal data class PreviewEvidence(
    val language: String,
    val lexicalKeys: Set<String>,
    val semanticKeys: Set<String>,
    val categories: Set<PrimitiveCategory>,
)

internal object PreviewHighlightProbe {
    fun collect(
        language: String,
        highlighter: EditorHighlighter,
        highlightInfos: List<HighlightInfo>,
    ): PreviewEvidence {
        val lexicalKeys = lexicalKeys(highlighter)
        val semanticKeys = semanticKeys(highlightInfos)
        val categories =
            (lexicalKeys + semanticKeys)
                .mapNotNullTo(linkedSetOf()) { keyName ->
                    SyntaxKeyRoleRegistry.classify(keyName).effectivePrimitive
                }

        return PreviewEvidence(
            language = language,
            lexicalKeys = lexicalKeys,
            semanticKeys = semanticKeys,
            categories = categories,
        )
    }

    private fun lexicalKeys(highlighter: EditorHighlighter): Set<String> {
        val keys = linkedSetOf<String>()
        val iterator = highlighter.createIterator(0)
        while (!iterator.atEnd()) {
            iterator.textAttributesKeys.mapTo(keys, TextAttributesKey::getExternalName)
            iterator.advance()
        }
        return keys
    }

    private fun semanticKeys(highlightInfos: List<HighlightInfo>): Set<String> =
        highlightInfos.mapTo(linkedSetOf()) { info ->
            (info.forcedTextAttributesKey ?: info.type.attributesKey).externalName
        }
}
