package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProcessCanceledException
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.theme.OverrideWriteResult
import java.util.IdentityHashMap
import kotlin.coroutines.cancellation.CancellationException

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

    sealed interface Failure : SyntaxTransactionResult {
        val cause: RuntimeException
    }

    data class RolledBack(
        override val cause: RuntimeException,
    ) : Failure

    data class RecoveryRequired(
        override val cause: RuntimeException,
        val rollbackFailures: List<RuntimeException>,
    ) : Failure
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
        val saved = checkpoints[checkpoint] ?: return emptyList()
        val failures = EditorSchemeOverrides.checkpoints.rollback(saved)
        if (failures.isEmpty()) checkpoints.remove(checkpoint)
        return failures
    }

    override fun release(checkpoint: SyntaxSchemeCheckpoint) {
        checkpoints.remove(checkpoint)
    }
}

/**
 * Retains reversible syntax work across runtime boundaries.
 *
 * Successful preview checkpoints remain until Apply advances or Cancel restores them. Failed rollback
 * checkpoints and a publication that still needs retry remain explicit recovery debt until restoration
 * completes; neither may be released by advancing the session.
 */
internal class SyntaxRecoveryLedger {
    private var rollbackState: RollbackRecovery = RollbackRecovery.Clean
    private var publication: PublicationRecovery = PublicationRecovery.Synchronized

    val hasRecoveryWork: Boolean
        get() = rollbackState.entries.isNotEmpty() || publication == PublicationRecovery.Pending

    val hasPendingFailure: Boolean
        get() =
            rollbackState.entries.any { it is RecoveryCheckpoint.Incomplete } ||
                publication == PublicationRecovery.Pending

    fun restore(
        writer: SyntaxSchemeWriter,
        afterRollback: () -> Unit = {},
        publish: () -> Unit,
    ): SyntaxTransactionResult {
        if (rollbackState.entries.isNotEmpty()) {
            val attempt = rollback(writer, rollbackState.entries.map(RecoveryCheckpoint::checkpoint))
            rollbackState =
                RollbackRecovery.retaining(
                    attempt.incompleteCheckpoints.map(RecoveryCheckpoint::Incomplete),
                )
            publication = PublicationRecovery.Pending
            attempt.rethrowCancellation()
            if (attempt.failures.isNotEmpty()) {
                return SyntaxTransactionResult.RecoveryRequired(attempt.failures.first(), attempt.failures)
            }
            rollbackState = RollbackRecovery.Clean
            afterRollback()
        }
        if (publication == PublicationRecovery.Synchronized) {
            return SyntaxTransactionResult.Applied(emptySet(), emptySet())
        }
        return try {
            publish()
            publication = PublicationRecovery.Synchronized
            SyntaxTransactionResult.Applied(emptySet(), emptySet())
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: RuntimeException) {
            SyntaxTransactionResult.RolledBack(failure)
        }
    }

    fun advance(writer: SyntaxSchemeWriter) {
        check(!hasPendingFailure) { "Cannot advance syntax state while recovery is pending" }
        rollbackState.entries.map(RecoveryCheckpoint::checkpoint).forEach(writer::release)
        rollbackState = RollbackRecovery.Clean
        publication = PublicationRecovery.Synchronized
    }

    internal fun retainUndo(retained: List<SyntaxSchemeCheckpoint>) {
        rollbackState =
            RollbackRecovery.retaining(
                rollbackState.entries + retained.map(RecoveryCheckpoint::Undo),
            )
    }

    internal fun retainIncompleteRollback(retained: List<SyntaxSchemeCheckpoint>) {
        rollbackState =
            RollbackRecovery.retaining(
                rollbackState.entries + retained.map(RecoveryCheckpoint::Incomplete),
            )
    }

    internal fun requirePublication() {
        publication = PublicationRecovery.Pending
    }
}

private sealed interface RecoveryCheckpoint {
    val checkpoint: SyntaxSchemeCheckpoint

    data class Undo(
        override val checkpoint: SyntaxSchemeCheckpoint,
    ) : RecoveryCheckpoint

    data class Incomplete(
        override val checkpoint: SyntaxSchemeCheckpoint,
    ) : RecoveryCheckpoint
}

private sealed interface RollbackRecovery {
    val entries: List<RecoveryCheckpoint>

    data object Clean : RollbackRecovery {
        override val entries: List<RecoveryCheckpoint> = emptyList()
    }

    data class Retained(
        override val entries: List<RecoveryCheckpoint>,
    ) : RollbackRecovery

    companion object {
        fun retaining(entries: List<RecoveryCheckpoint>): RollbackRecovery =
            if (entries.isEmpty()) Clean else Retained(entries.toList())
    }
}

private sealed interface PublicationRecovery {
    data object Synchronized : PublicationRecovery

    data object Pending : PublicationRecovery
}

/** Applies a fully computed syntax plan as one publish-or-rollback transaction. */
internal class SyntaxSchemeTransaction(
    private val writer: SyntaxSchemeWriter,
    private val publish: () -> Unit,
) {
    fun apply(
        changes: List<SyntaxSchemeChange>,
        ledger: SyntaxRecoveryLedger? = null,
    ): SyntaxTransactionResult {
        val checkpoints = mutableListOf<SyntaxSchemeCheckpoint>()
        var publicationAttempted = false
        return try {
            changes.forEach { change -> checkpoints += writer.checkpoint(change) }
            val relinquishedKeys =
                changes.flatMapTo(linkedSetOf()) { change -> writer.write(change) }
            publicationAttempted = true
            publish()
            if (ledger == null) checkpoints.forEach(writer::release) else ledger.retainUndo(checkpoints)
            SyntaxTransactionResult.Applied(
                changedKeys =
                    changes.flatMapTo(linkedSetOf()) { change ->
                        change.attributes.keys.map(TextAttributesKey::getExternalName)
                    },
                relinquishedKeys = relinquishedKeys,
            )
        } catch (failure: RuntimeException) {
            val rollback = rollback(writer, checkpoints)
            ledger?.retainIncompleteRollback(rollback.incompleteCheckpoints)
            if (publicationAttempted) ledger?.requirePublication()
            rollback.rethrowCancellation(failure)
            if (rollback.failures.isEmpty()) {
                SyntaxTransactionResult.RolledBack(failure)
            } else {
                SyntaxTransactionResult.RecoveryRequired(failure, rollback.failures)
            }
        }
    }
}

private data class RollbackAttempt(
    val incompleteCheckpoints: List<SyntaxSchemeCheckpoint>,
    val failures: List<RuntimeException>,
    val cancellation: RuntimeException?,
) {
    fun rethrowCancellation(originalFailure: RuntimeException? = null) {
        val originalCancellation = originalFailure?.takeIf(::isCancellation)
        val cancellation = originalCancellation ?: cancellation ?: return
        if (originalFailure != null && originalFailure !== cancellation) {
            cancellation.addSuppressed(originalFailure)
        }
        this.cancellation?.takeIf { it !== cancellation }?.let(cancellation::addSuppressed)
        failures.forEach(cancellation::addSuppressed)
        throw cancellation
    }
}

private fun rollback(
    writer: SyntaxSchemeWriter,
    checkpoints: List<SyntaxSchemeCheckpoint>,
): RollbackAttempt {
    val incomplete = mutableListOf<SyntaxSchemeCheckpoint>()
    val failures = mutableListOf<RuntimeException>()
    var cancellation: RuntimeException? = null
    checkpoints.asReversed().forEach { checkpoint ->
        try {
            val checkpointFailures = writer.rollback(checkpoint)
            if (checkpointFailures.isNotEmpty()) {
                incomplete += checkpoint
                failures += checkpointFailures
            }
        } catch (failure: RuntimeException) {
            incomplete += checkpoint
            if (isCancellation(failure)) {
                cancellation = cancellation.record(failure)
            } else {
                failures += failure
            }
        }
    }
    return RollbackAttempt(incomplete, failures, cancellation)
}

private fun RuntimeException?.record(next: RuntimeException): RuntimeException {
    val first = this ?: return next
    if (first !== next) first.addSuppressed(next)
    return first
}

private fun isCancellation(failure: RuntimeException): Boolean =
    failure is ProcessCanceledException || failure is CancellationException
