package dev.ayuislands.indent

import com.intellij.openapi.application.Application
import dev.ayuislands.accent.resetIntegrationReceipts
import io.mockk.every
import io.mockk.mockk
import java.lang.reflect.Field
import java.lang.reflect.Method

internal fun installIrHarness(
    application: Application,
    type: FakePaletteType,
    palette: String,
    colorCount: Int,
): IrHarness {
    val harness = IrHarness(type, palette, colorCount)
    val config = Any()
    every { application.getService(config.javaClass) } returns config
    val paletteType = mockIrField()
    val customPalette = mockIrField()
    val count = mockIrCount()

    every { paletteType.get(config) } answers { harness.type }
    every { paletteType.set(config, any()) } answers
        {
            harness.type = secondArg()
        }
    every { customPalette.get(config) } answers { harness.palette }
    every { customPalette.set(config, any()) } answers
        {
            harness.palette = secondArg()
            if (harness.shouldFailNextPalette) {
                harness.shouldFailNextPalette = false
                error("palette write rejected after mutation")
            }
        }
    every { count.getInt(config) } answers { harness.colorCount }
    every { count.setInt(config, any()) } answers
        {
            harness.colorCount = secondArg()
            if (harness.shouldFailNextCountAndRollbackPalette) {
                harness.shouldFailNextCountAndRollbackPalette = false
                harness.shouldFailNextPalette = true
                error("color count write rejected after mutation")
            }
        }

    setIrField("methodsResolved", true)
    setIrField("irConfig", config)
    setIrField("paletteTypeField", paletteType)
    setIrField("customPaletteField", customPalette)
    setIrField("customPaletteNumberColorsField", count)
    setIrField("customEnumValue", FakePaletteType.CUSTOM)
    setIrField("defaultEnumValue", FakePaletteType.DEFAULT)
    setIrField("paletteEnumValues", FakePaletteType.entries.associateBy { it.name })
    setIrField("cachedDataUpdateMethod", mockIrMethod())
    setIrField("cachedDataCompanion", Any())
    return harness
}

internal fun setIrField(
    fieldName: String,
    value: Any?,
) {
    val field = IndentRainbowSync::class.java.getDeclaredField(fieldName)
    field.isAccessible = true
    field.set(IndentRainbowSync, value)
}

internal fun resetIrBindings() {
    val fields =
        listOf(
            "irConfig",
            "paletteTypeField",
            "customPaletteField",
            "customPaletteNumberColorsField",
            "customEnumValue",
            "defaultEnumValue",
            "paletteEnumValues",
            "cachedDataUpdateMethod",
            "cachedDataCompanion",
            "resolutionFailure",
        )
    for (fieldName in fields) {
        setIrField(fieldName, null)
    }
    setIrField("methodsResolved", false)
    resetIntegrationReceipts(IndentRainbowSync)
}

private fun mockIrField(): Field =
    mockk<Field>(relaxed = true).also { field ->
        every { field.get(any()) } returns "DEFAULT_ENUM"
    }

private fun mockIrCount(): Field {
    val field = mockk<Field>(relaxed = true)
    every { field.setInt(any(), any()) } returns Unit
    return field
}

private fun mockIrMethod(): Method = mockk<Method>(relaxed = true)

internal data class IrHarness(
    var type: FakePaletteType,
    var palette: String,
    var colorCount: Int,
    var shouldFailNextCountAndRollbackPalette: Boolean = false,
    var shouldFailNextPalette: Boolean = false,
)

internal enum class FakePaletteType {
    DEFAULT,
    CUSTOM,
    RAINBOW,
}
