package dev.ayuislands.accent.color

import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.rotation.HslColor
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream

/**
 * Derives an accent color from a project icon (`.idea/icon.png`).
 *
 * Producer for the per-project accent map: reads the icon, samples pixels on
 * a bounded grid, frequency-buckets the qualifying ones, and returns the
 * dominant color coerced into the accent lightness band via
 * [AccentHsl.clampToPaletteRange]. Returns null — never throws — when the
 * icon is missing, oversized, unreadable, or has no qualifying pixels
 * (fully transparent or near-gray), so callers treat "no derivable accent"
 * as a plain skip. SVG icons (`.idea/icon.svg`) are not supported: decoding
 * SVG would need a rasterizer dependency this producer stays free of.
 */
object ProjectIconAccentExtractor {
    private val log = logger<ProjectIconAccentExtractor>()

    internal const val MAX_ICON_FILE_BYTES = 2L * 1024 * 1024
    internal const val MAX_ICON_PIXELS = 1_048_576L
    private const val SAMPLE_GRID = 64
    private const val MIN_ALPHA = 128
    private const val MIN_SATURATION = 0.15f
    private const val MIN_LIGHTNESS = 0.12f
    private const val MAX_LIGHTNESS = 0.92f
    private const val BUCKET_BITS_PER_CHANNEL = 4
    private const val CHANNEL_BITS = 8
    private const val CHANNEL_MASK = 0xFF
    private const val ALPHA_SHIFT = 3 * CHANNEL_BITS
    private const val RED_SHIFT = 2 * CHANNEL_BITS

    /** The project icon file, or null when absent, empty, or over the size gate. */
    fun projectIconFile(projectBasePath: String?): File? {
        if (projectBasePath.isNullOrBlank()) return null
        val file = File(projectBasePath, ".idea/icon.png")
        if (!file.isFile) return null
        val length = file.length()
        if (length !in 1..MAX_ICON_FILE_BYTES) {
            log.warn("Project icon accent: '${file.path}' skipped (size=$length outside gate)")
            return null
        }
        return file
    }

    /** Dominant clamped accent from [iconFile], or null on any read/decode failure. */
    fun extract(iconFile: File): AccentHex? {
        val image = readBoundedImage(iconFile) ?: return null
        return extract(image)
    }

    private fun readBoundedImage(iconFile: File): BufferedImage? {
        try {
            val imageInput: ImageInputStream? = ImageIO.createImageInputStream(iconFile)
            if (imageInput == null) {
                log.warn("Project icon accent: failed to open '${iconFile.path}'")
                return null
            }
            return imageInput.use { input ->
                val readers: Iterator<ImageReader> = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) {
                    log.warn("Project icon accent: no registered image reader for '${iconFile.path}'")
                    return@use null
                }

                val reader: ImageReader = readers.next()
                try {
                    reader.setInput(input, true, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0) {
                        log.warn("Project icon accent: '${iconFile.path}' has invalid dimensions ${width}x$height")
                        return@use null
                    }
                    val pixelCount = width.toLong() * height.toLong()
                    if (pixelCount > MAX_ICON_PIXELS) {
                        log.warn(
                            "Project icon accent: '${iconFile.path}' skipped " +
                                "(${width}x$height exceeds pixel gate)",
                        )
                        return@use null
                    }
                    reader.read(0)
                } finally {
                    reader.dispose()
                }
            }
        } catch (exception: IOException) {
            log.warn("Project icon accent: failed to read '${iconFile.path}'", exception)
            return null
        } catch (exception: RuntimeException) {
            log.warn("Project icon accent: failed to decode '${iconFile.path}'", exception)
            return null
        }
    }

    /**
     * Pure core: the dominant qualifying color of [image], clamped into the
     * accent band; null when no pixel qualifies. Deterministic — buckets are
     * ranked by count with the lowest bucket key breaking ties, so map
     * iteration order never changes the winner.
     */
    internal fun extract(image: BufferedImage): AccentHex? {
        if (image.width <= 0 || image.height <= 0) return null
        val step = maxOf(1, maxOf(image.width, image.height) / SAMPLE_GRID)
        val buckets = HashMap<Int, BucketAccumulator>()
        var x = 0
        while (x < image.width) {
            var y = 0
            while (y < image.height) {
                accumulateQualifyingPixel(image.getRGB(x, y), buckets)
                y += step
            }
            x += step
        }

        val winner =
            buckets.entries.minWithOrNull(
                compareByDescending<Map.Entry<Int, BucketAccumulator>> { it.value.count }.thenBy { it.key },
            )
        if (winner == null) {
            log.debug("Project icon accent: no qualifying pixels in the icon")
            return null
        }

        val bucket = winner.value
        val hex =
            "#%02X%02X%02X".format(
                bucket.redSum / bucket.count,
                bucket.greenSum / bucket.count,
                bucket.blueSum / bucket.count,
            )
        val parsed = AccentHex.of(hex) ?: return null
        return AccentHsl.clampToPaletteRange(parsed)
    }

    private fun accumulateQualifyingPixel(
        argb: Int,
        buckets: MutableMap<Int, BucketAccumulator>,
    ) {
        val alpha = argb ushr ALPHA_SHIFT and CHANNEL_MASK
        if (alpha < MIN_ALPHA) return
        val red = argb ushr RED_SHIFT and CHANNEL_MASK
        val green = argb ushr CHANNEL_BITS and CHANNEL_MASK
        val blue = argb and CHANNEL_MASK

        val hsl = HslColor.fromColor(Color(red, green, blue))
        if (hsl.saturation < MIN_SATURATION) return
        if (hsl.lightness !in MIN_LIGHTNESS..MAX_LIGHTNESS) return

        val key =
            (quantize(red) shl (2 * BUCKET_BITS_PER_CHANNEL)) or
                (quantize(green) shl BUCKET_BITS_PER_CHANNEL) or
                quantize(blue)
        buckets.getOrPut(key) { BucketAccumulator() }.add(red, green, blue)
    }

    private fun quantize(channel: Int): Int = channel ushr (CHANNEL_BITS - BUCKET_BITS_PER_CHANNEL)

    private class BucketAccumulator {
        var count = 0
        var redSum = 0
        var greenSum = 0
        var blueSum = 0

        fun add(
            red: Int,
            green: Int,
            blue: Int,
        ) {
            count++
            redSum += red
            greenSum += green
            blueSum += blue
        }
    }
}
