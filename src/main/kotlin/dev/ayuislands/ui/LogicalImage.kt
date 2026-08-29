package dev.ayuislands.ui

import com.intellij.ui.paint.PaintUtil
import com.intellij.ui.scale.ScaleContext
import com.intellij.util.ui.ImageUtil
import java.awt.image.BufferedImage

internal fun logicalImage(
    width: Int,
    height: Int,
): BufferedImage =
    ImageUtil.createImage(
        ScaleContext.createIdentity(),
        width.toDouble(),
        height.toDouble(),
        BufferedImage.TYPE_INT_ARGB,
        PaintUtil.RoundingMode.ROUND,
    )
