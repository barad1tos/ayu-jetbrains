package dev.ayuislands.integration.codeglance

import com.intellij.openapi.application.ApplicationManager
import com.nasller.codeglance.config.CodeGlanceConfigService
import dev.ayuislands.accent.CgpAccess
import dev.ayuislands.accent.CgpAccessProvider
import dev.ayuislands.accent.CgpPart
import dev.ayuislands.accent.CgpViewport

/** Loaded only by the optional CodeGlance Pro descriptor. */
internal class CodeGlanceAdapter : CgpAccessProvider {
    override fun resolve(): CgpAccess? {
        val application = ApplicationManager.getApplication()
        val service = application.getService(CodeGlanceConfigService::class.java) ?: return null
        val config = service.state
        return CgpAccess(
            read = { CgpViewport(config.viewportColor, config.viewportBorderColor, config.viewportBorderThickness) },
            write = { part, target ->
                when (part) {
                    CgpPart.COLOR -> config.viewportColor = target.color
                    CgpPart.BORDER -> config.viewportBorderColor = target.border
                    CgpPart.THICKNESS -> config.viewportBorderThickness = target.thickness
                }
            },
            targetMatches = {
                application.getService(CodeGlanceConfigService::class.java) === service && service.state === config
            },
        )
    }
}
