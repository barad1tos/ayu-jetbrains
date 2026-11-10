package dev.ayuislands.studio

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentChangedTopic
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.rethrowIfCancelled
import dev.ayuislands.studio.session.StudioAccentResult

/** Delivers accepted colors without letting a subscriber replay an already committed operation. */
internal object StudioAccentPublication {
    private val log = logger<StudioAccentPublication>()

    fun publish(result: StudioAccentResult?) {
        if (result?.context == null) return
        for ((project, accent) in result.projects) {
            if (project.isDisposed || project.isDefault) continue
            try {
                ApplicationManager
                    .getApplication()
                    .messageBus
                    .syncPublisher(AccentChangedTopic.TOPIC)
                    .accentChanged(project, AccentHex.require(accent.hex), accent.source)
            } catch (failure: RuntimeException) {
                failure.rethrowIfCancelled()
                log.warn("Ayu Islands accent subscriber failed after acceptance", failure)
            }
        }
    }
}
