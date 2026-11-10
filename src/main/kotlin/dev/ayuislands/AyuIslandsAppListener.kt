package dev.ayuislands

import com.intellij.ide.AppLifecycleListener
import dev.ayuislands.studio.StudioService

/** Opens the accepted application authority before the first appearance refresh. */
internal class AyuIslandsAppListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        StudioService.getInstance().start()
    }
}
