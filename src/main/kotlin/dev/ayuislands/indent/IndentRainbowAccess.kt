package dev.ayuislands.indent

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.theme.EditorSchemeChange

internal data class IrPalette(
    val type: String,
    val palette: String?,
    val colorCount: Int,
)

internal data class CurrentIrPalette(
    val palette: IrPalette,
    // Preserve the optional plugin's enum identity without linking its classes into the core.
    val typeValue: Any,
)

/** Supplied only when the optional Indent Rainbow dependency is enabled. */
internal fun interface IrAccessProvider {
    fun resolve(): ResolvedIrState?
}

/** One native configuration identity remains bound throughout its checkpoint's lifetime. */
internal class ResolvedIrState(
    val paletteTypes: Map<String, Any>,
    private val read: () -> CurrentIrPalette,
    private val write: (IrPart, CurrentIrPalette) -> Unit,
    private val update: () -> Unit,
    private val current: () -> Boolean,
) {
    var checkpoint: IrCheckpoint? = null

    fun readPalette(): CurrentIrPalette = read()

    fun writePalette(
        palette: IrPalette,
        typeValue: Any,
    ) {
        val target = CurrentIrPalette(palette, typeValue)
        val receipt = checkpoint
        for (part in IrPart.entries) {
            if (receipt == null) writePart(part, target) else receipt.write(this, part, target)
        }
        refresh()
    }

    fun isCurrent(): Boolean = current()

    fun writePart(
        part: IrPart,
        target: CurrentIrPalette,
    ) = write(part, target)

    fun refresh() {
        update()
        // Publish through the supported editor-scheme path after the native cache is current.
        EditorSchemeChange.publish()
        checkpoint?.refreshed()
    }
}

internal enum class IrPart { TYPE, PALETTE, COUNT }

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

internal fun AyuIslandsState.irPendingPalette(): IrPalette? =
    IrPalette(
        type = irAppliedType ?: return null,
        palette = irAppliedPalette,
        colorCount = irAppliedColorCount,
    )
