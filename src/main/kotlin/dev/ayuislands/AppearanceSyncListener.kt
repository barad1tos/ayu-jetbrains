package dev.ayuislands

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.wm.IdeFrame
import dev.ayuislands.settings.AyuIslandsSettings

class AppearanceSyncListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
        val settings = AyuIslandsSettings.getInstance()
        if (!settings.state.followSystemAppearance) return
        AppearanceSyncService.getInstance().syncIfNeeded()
    }
}
