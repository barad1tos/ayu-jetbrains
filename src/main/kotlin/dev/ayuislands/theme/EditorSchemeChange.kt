package dev.ayuislands.theme

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.concurrency.annotations.RequiresEdt

internal object EditorSchemeChange {
    @RequiresEdt
    fun publish() {
        val application = ApplicationManager.getApplication()
        WriteAction.run<RuntimeException> {
            application
                .messageBus
                .syncPublisher(EditorColorsManager.TOPIC)
                .globalSchemeChange(null)
        }
    }
}
