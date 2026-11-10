package dev.ayuislands.accent

import dev.ayuislands.settings.AyuIslandsState

internal fun AyuIslandsState.cgpBaseViewport(): CgpViewport? =
    CgpViewport(
        color = cgpBaseColor ?: return null,
        border = cgpBaseBorder ?: return null,
        thickness = cgpBaseThickness,
    )

internal data class CgpViewport(
    val color: String,
    val border: String,
    val thickness: Int,
)

internal fun interface CgpAccessProvider {
    fun resolve(): CgpAccess?
}

internal class CgpAccess(
    private val read: () -> CgpViewport,
    private val write: (CgpPart, CgpViewport) -> Unit,
    private val targetMatches: () -> Boolean,
) {
    var checkpoint: CgpCheckpoint? = null

    fun readViewport(): CgpViewport = read()

    fun writeViewport(viewport: CgpViewport) {
        val receipt = checkpoint
        for (part in CgpPart.entries) {
            if (receipt == null) writePart(part, viewport) else receipt.write(this, part, viewport)
        }
    }

    fun isCurrent(): Boolean = targetMatches()

    fun writePart(
        part: CgpPart,
        target: CgpViewport,
    ) = write(part, target)
}

internal enum class CgpPart {
    COLOR,
    BORDER,
    THICKNESS,
}
