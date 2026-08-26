package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.theme.OverrideWriteResult
import java.util.IdentityHashMap

internal data class SyntaxSchemeChange(
    val scheme: EditorColorsScheme,
    val label: String,
    val attributes: Map<TextAttributesKey, TextAttributes>,
    val materializedKeys: Set<String>,
)

internal class SyntaxSchemeCheckpoint(
    val label: String,
)

internal sealed interface SyntaxTransactionResult {
    data class Applied(
        val changedKeys: Set<String>,
        val relinquishedKeys: Set<String>,
    ) : SyntaxTransactionResult

    data class Failed(
        val cause: RuntimeException,
        val rollbackFailures: List<RuntimeException>,
    ) : SyntaxTransactionResult
}

internal interface SyntaxSchemeWriter {
    fun checkpoint(change: SyntaxSchemeChange): SyntaxSchemeCheckpoint

    fun write(change: SyntaxSchemeChange): Set<String>

    fun rollback(checkpoint: SyntaxSchemeCheckpoint): List<RuntimeException>

    fun release(checkpoint: SyntaxSchemeCheckpoint)
}

internal class IdeSyntaxSchemeWriter : SyntaxSchemeWriter {
    private val checkpoints =
        IdentityHashMap<SyntaxSchemeCheckpoint, EditorSchemeOverrides.AttributesCheckpoint>()

    override fun checkpoint(change: SyntaxSchemeChange): SyntaxSchemeCheckpoint {
        val token = SyntaxSchemeCheckpoint(change.label)
        checkpoints[token] =
            EditorSchemeOverrides.checkpoints.capture(
                scheme = change.scheme,
                owner = EditorSchemeOwner.Syntax,
                keys = change.attributes.keys,
            )
        return token
    }

    override fun write(change: SyntaxSchemeChange): Set<String> {
        EditorSchemeOverrides.restore(change.scheme, EditorSchemeOwner.Syntax)
        EditorSchemeOverrides.rearm(
            EditorSchemeOwner.Syntax,
            listOf(change.scheme),
            mapOf(change.scheme to change.materializedKeys),
        )
        val relinquished = linkedSetOf<String>()
        change.attributes.forEach { (key, attributes) ->
            if (key.externalName in change.materializedKeys) {
                val result =
                    EditorSchemeOverrides.writeAttributes(
                        change.scheme,
                        EditorSchemeOwner.Syntax,
                        key,
                        attributes,
                    )
                if (result == OverrideWriteResult.RELINQUISHED) relinquished += key.externalName
            } else {
                change.scheme.setAttributes(key, attributes)
            }
        }
        return relinquished
    }

    override fun rollback(checkpoint: SyntaxSchemeCheckpoint): List<RuntimeException> {
        val saved = checkpoints.remove(checkpoint) ?: return emptyList()
        return EditorSchemeOverrides.checkpoints.rollback(saved)
    }

    override fun release(checkpoint: SyntaxSchemeCheckpoint) {
        checkpoints.remove(checkpoint)
    }
}

/** Applies a fully computed syntax plan as one publish-or-rollback transaction. */
internal class SyntaxSchemeTransaction(
    private val writer: SyntaxSchemeWriter,
    private val publish: () -> Unit,
) {
    fun apply(changes: List<SyntaxSchemeChange>): SyntaxTransactionResult {
        val checkpoints = mutableListOf<SyntaxSchemeCheckpoint>()
        return try {
            changes.forEach { change -> checkpoints += writer.checkpoint(change) }
            val relinquishedKeys =
                changes.flatMapTo(linkedSetOf()) { change -> writer.write(change) }
            publish()
            checkpoints.forEach(writer::release)
            SyntaxTransactionResult.Applied(
                changedKeys =
                    changes.flatMapTo(linkedSetOf()) { change ->
                        change.attributes.keys.map(TextAttributesKey::getExternalName)
                    },
                relinquishedKeys = relinquishedKeys,
            )
        } catch (failure: RuntimeException) {
            val rollbackFailures =
                checkpoints.asReversed().flatMap { checkpoint -> writer.rollback(checkpoint) }
            SyntaxTransactionResult.Failed(failure, rollbackFailures)
        }
    }
}
