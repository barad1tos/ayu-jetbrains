package dev.ayuislands.font

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicReference

private val activeFontTask = AtomicReference<Task.Backgroundable?>()
internal const val FONT_OPERATION_BUSY = "Try again when the current font installation or removal finishes."

/** Retain exclusive access to the font directory until the task's inventory update completes. */
internal fun <Outcome> queueFontOperation(
    project: Project?,
    title: String,
    onComplete: (Outcome) -> Unit,
    onBusy: () -> Unit,
    operation: (ProgressIndicator, (Outcome) -> Unit) -> Unit,
) {
    val task =
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                if (!activeFontTask.compareAndSet(null, this)) {
                    onBusy()
                    return
                }

                var completionScheduled = false
                try {
                    operation(indicator) { result ->
                        if (activeFontTask.compareAndSet(this, null)) onComplete(result)
                    }
                    completionScheduled = true
                } finally {
                    if (!completionScheduled) activeFontTask.compareAndSet(this, null)
                }
            }
        }
    ProgressManager.getInstance().run(task)
}
