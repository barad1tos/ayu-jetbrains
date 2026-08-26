package dev.ayuislands.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.ayuislands.syntax.LanguageSpecification
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
        ReadAction
            .nonBlocking(Callable { inspector.inspect(specification, generation) })
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(parent)
            .finishOnUiThread(ModalityState.any(), completed)
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
