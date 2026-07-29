package dev.ayuislands.indent

import dev.ayuislands.settings.AyuIslandsState
import java.lang.reflect.Field
import java.lang.reflect.Method

internal data class IrPalette(
    val type: String,
    val palette: String?,
    val colorCount: Int,
)

internal data class CurrentIrPalette(
    val palette: IrPalette,
    val typeValue: Any,
)

internal class ResolvedIrState(
    private val config: Any,
    private val paletteTypeField: Field,
    private val customPaletteField: Field,
    private val colorCountField: Field,
    private val updateMethod: Method,
    private val companion: Any,
    private val refreshMethod: Method,
    private val colorsInstance: Any,
) {
    fun readPalette(): CurrentIrPalette {
        val typeValue = paletteTypeField[config] ?: error("paletteType field returned null")
        val palette =
            when (val paletteValue = customPaletteField[config]) {
                null -> null
                is String -> paletteValue
                else -> error("customPalette field returned non-string")
            }
        return CurrentIrPalette(
            palette =
                IrPalette(
                    type = enumName(typeValue),
                    palette = palette,
                    colorCount = colorCountField.getInt(config),
                ),
            typeValue = typeValue,
        )
    }

    fun writePalette(
        palette: IrPalette,
        typeValue: Any,
    ) {
        paletteTypeField[config] = typeValue
        customPaletteField[config] = palette.palette
        colorCountField.setInt(config, palette.colorCount)
        updateMethod.invoke(companion, config)
        refreshMethod.invoke(colorsInstance)
    }
}

internal fun enumName(value: Any): String = (value as? Enum<*>)?.name ?: value.toString()

internal fun AyuIslandsState.storeIrBase(palette: IrPalette) {
    irBaseType = palette.type
    irBasePalette = palette.palette
    irBaseColorCount = palette.colorCount
}

internal fun AyuIslandsState.storeIrApplied(palette: IrPalette) {
    irAppliedType = palette.type
    irAppliedPalette = palette.palette
    irAppliedColorCount = palette.colorCount
}

internal fun AyuIslandsState.irBasePalette(): IrPalette? =
    IrPalette(
        type = irBaseType ?: return null,
        palette = irBasePalette,
        colorCount = irBaseColorCount,
    )

internal fun AyuIslandsState.irAppliedPalette(): IrPalette? =
    IrPalette(
        type = irAppliedType ?: return null,
        palette = irAppliedPalette ?: return null,
        colorCount = irAppliedColorCount,
    )
