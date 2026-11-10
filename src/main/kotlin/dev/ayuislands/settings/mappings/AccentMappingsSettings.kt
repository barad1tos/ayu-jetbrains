package dev.ayuislands.settings.mappings

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.persistence.AccentMappingsView

/** Ordered accepted mappings; migration owns legacy macro expansion and the original source archive. */
@Service
internal class AccentMappingsSettings {
    val state: AccentMappingsView? get() = StudioService.getInstance().acceptedView?.mappings

    companion object {
        fun getInstance(): AccentMappingsSettings = service()
    }
}
