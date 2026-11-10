package dev.ayuislands.integration.indent

import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.indent.CurrentIrPalette
import dev.ayuislands.indent.IrAccessProvider
import dev.ayuislands.indent.IrPalette
import dev.ayuislands.indent.IrPart
import dev.ayuislands.indent.ResolvedIrState
import indent.rainbow.settings.IrCachedData
import indent.rainbow.settings.IrColorsPaletteType
import indent.rainbow.settings.IrConfig

/** Loaded by the optional dependency descriptor; core ownership never links native plugin classes. */
internal class IndentRainbowAdapter : IrAccessProvider {
    override fun resolve(): ResolvedIrState? {
        val config = ApplicationManager.getApplication().getService(IrConfig::class.java) ?: return null
        return ResolvedIrState(
            paletteTypes = IrColorsPaletteType.entries.associateBy { it.name },
            read = {
                CurrentIrPalette(
                    IrPalette(config.paletteType.name, config.customPalette, config.customPaletteNumberColors),
                    config.paletteType,
                )
            },
            write = { part, target ->
                when (part) {
                    IrPart.TYPE -> config.paletteType = requireNotNull(target.typeValue as? IrColorsPaletteType)
                    IrPart.PALETTE -> config.customPalette = requireNotNull(target.palette.palette)
                    IrPart.COUNT -> config.customPaletteNumberColors = target.palette.colorCount
                }
            },
            update = { IrCachedData.update(config) },
            current = { ApplicationManager.getApplication().getService(IrConfig::class.java) === config },
        )
    }
}
