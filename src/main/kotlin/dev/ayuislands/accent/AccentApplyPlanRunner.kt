package dev.ayuislands.accent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import javax.swing.SwingUtilities

/** Posts deferred native-owner lifecycle callbacks outside modal settings dialogs. */
internal object AccentApplyPlanRunner {
    private val log = logger<AccentApplyPlanRunner>()

    fun post(work: Runnable) {
        val app = ApplicationManager.getApplication()
        if (app != null) {
            app.invokeLater(work, ModalityState.nonModal())
        } else {
            log.warn(
                "Application not available, " +
                    "falling back to SwingUtilities",
            )
            SwingUtilities.invokeLater(work)
        }
    }
}
