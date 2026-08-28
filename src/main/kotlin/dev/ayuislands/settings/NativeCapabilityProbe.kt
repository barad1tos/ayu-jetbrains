package dev.ayuislands.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import dev.ayuislands.syntax.LanguageSpecification
import kotlinx.coroutines.CancellationException
import java.util.concurrent.Callable

internal class NativeCapabilityProbe(
    private val project: Project,
    private val inspector: PreviewInspector = IdePreviewInspector(project),
) : SyntaxCapabilityProbe {
    override fun start(
        specification: LanguageSpecification,
        generation: Long,
        parent: Disposable,
        completed: (SyntaxProbeResult) -> Unit,
    ) {
        val completion = NativeProbeCompletion(specification.storageId, generation, parent, completed)
        ReadAction
            .nonBlocking(Callable { inspector.inspect(specification, generation) })
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(parent)
            .finishOnUiThread(ModalityState.any(), completed)
            .submit(AppExecutorUtil.getAppExecutorService())
            .onError(completion::failed)
    }
}

internal class NativeProbeCompletion(
    private val languageId: String,
    private val generation: Long,
    parent: Disposable,
    private val completed: (SyntaxProbeResult) -> Unit,
    private val dispatch: (() -> Unit) -> Unit = ::dispatchOnUiThread,
) : Disposable {
    @Volatile
    private var isDisposed = false

    init {
        Disposer.register(parent, this)
    }

    fun failed(failure: Throwable) {
        if (isDisposed) return
        val runtimeFailure = failure as? RuntimeException ?: throw failure
        if (runtimeFailure.isCancellation()) throw runtimeFailure
        LOG.warn("Native syntax capability probe failed for $languageId", runtimeFailure)
        dispatch {
            if (isDisposed) return@dispatch
            completed(
                SyntaxProbeResult.Deferred(
                    languageId = languageId,
                    generation = generation,
                    reason = probeFailureMessage(languageId),
                ),
            )
        }
    }

    override fun dispose() {
        isDisposed = true
    }

    private companion object {
        private val LOG = Logger.getInstance(NativeProbeCompletion::class.java)
    }
}

internal fun probeFailureMessage(languageId: String): String =
    "Could not check $languageId support. Retry, or update or enable its language plugin if the problem continues."

private fun dispatchOnUiThread(action: () -> Unit) {
    ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
}

private fun RuntimeException.isCancellation(): Boolean =
    this is ProcessCanceledException || this is CancellationException
