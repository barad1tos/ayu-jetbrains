package dev.ayuislands

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.wm.IdeFrame
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioThemeChange

class AppearanceSyncListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
        val studio = StudioService.getInstance()
        if (studio.acceptedView?.settings?.followSystemAppearance != true) return
        studio.nativeChanged(StudioNativeChange.Theme(StudioThemeChange.FollowSystem))
    }
}
