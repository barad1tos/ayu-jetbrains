package dev.ayuislands.settings

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import dev.ayuislands.syntax.SyntaxKeyRoleRegistry
import dev.ayuislands.syntax.effectivePrimitive

internal object HighlightEvidenceCollector {
    fun collect(
        languageId: String,
        highlighter: EditorHighlighter,
        highlightInfos: List<HighlightInfo>,
    ): HighlightEvidence {
        val lexicalKeys = lexicalKeys(highlighter)
        val semanticKeys = semanticKeys(highlightInfos)
        val keysByPrimitive =
            (lexicalKeys + semanticKeys)
                .mapNotNull { keyName ->
                    val primitive = SyntaxKeyRoleRegistry.classify(keyName).effectivePrimitive
                    primitive?.let { it to keyName }
                }.groupBy({ it.first }, { it.second })
                .mapValues { (_, keyNames) -> keyNames.toSet() }

        return HighlightEvidence(
            languageId = languageId,
            lexicalKeys = lexicalKeys,
            semanticKeys = semanticKeys,
            keysByPrimitive = keysByPrimitive,
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
