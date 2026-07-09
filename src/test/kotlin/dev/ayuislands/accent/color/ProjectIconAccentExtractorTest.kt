package dev.ayuislands.accent.color

import com.intellij.ui.ColorUtil
import dev.ayuislands.rotation.HslColor
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Behavior locks for [ProjectIconAccentExtractor]. Fixture icons are built
 * programmatically ([BufferedImage] + [ImageIO.write]) rather than bundled
 * as binaries — the repo's established fixture approach — while the file
 * variants still exercise the real PNG decode path.
 */
class ProjectIconAccentExtractorTest {
    @field:TempDir
    lateinit var tempDir: Path

    @Test
    fun `solid in-band icon yields exactly that color`() {
        val image = solidImage(Color(0x5C, 0xCF, 0xE6), width = 32, height = 32)

        assertEquals("#5CCFE6", ProjectIconAccentExtractor.extract(image)?.value)
    }

    @Test
    fun `majority color wins a two-color icon`() {
        val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 10) {
            for (y in 0 until 10) {
                image.setRGB(x, y, if (x < 6) Color(0x5C, 0xCF, 0xE6).rgb else Color(0xF2, 0x87, 0x79).rgb)
            }
        }

        assertEquals("#5CCFE6", ProjectIconAccentExtractor.extract(image)?.value)
    }

    @Test
    fun `opaque minority wins a transparency-heavy icon`() {
        val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val transparent = Color(0xF2, 0x87, 0x79, 0)
        for (x in 0 until 10) {
            for (y in 0 until 10) {
                image.setRGB(x, y, if (x < 2) Color(0x5C, 0xCF, 0xE6).rgb else transparent.rgb)
            }
        }

        assertEquals("#5CCFE6", ProjectIconAccentExtractor.extract(image)?.value)
    }

    @Test
    fun `grayscale icon yields no accent`() {
        assertNull(ProjectIconAccentExtractor.extract(solidImage(Color(0x80, 0x80, 0x80), 16, 16)))
    }

    @Test
    fun `fully transparent icon yields no accent`() {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)

        assertNull(ProjectIconAccentExtractor.extract(image))
    }

    @Test
    fun `out-of-band dominant color is lifted to the palette lightness floor keeping hue`() {
        val darkBlue = Color(0x11, 0x33, 0x55)
        val result = ProjectIconAccentExtractor.extract(solidImage(darkBlue, 16, 16))

        assertNotNull(result, "a saturated dark color qualifies and must produce an accent")
        val resultHsl = HslColor.fromColor(ColorUtil.fromHex(result.value))
        val inputHsl = HslColor.fromColor(darkBlue)
        assertEquals(AccentHsl.MIN_PALETTE_LIGHTNESS, resultHsl.lightness, absoluteTolerance = 0.01f)
        assertEquals(inputHsl.hue, resultHsl.hue, absoluteTolerance = 1.0f)
    }

    @Test
    fun `extraction is deterministic across runs`() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 64) {
            for (y in 0 until 64) {
                image.setRGB(x, y, Color(0x40 + x % 64, 0xCF, 0xE6 - y % 32).rgb)
            }
        }

        assertEquals(
            ProjectIconAccentExtractor.extract(image)?.value,
            ProjectIconAccentExtractor.extract(image)?.value,
        )
    }

    @Test
    fun `one-pixel icon extracts that pixel`() {
        assertEquals("#5CCFE6", ProjectIconAccentExtractor.extract(solidImage(Color(0x5C, 0xCF, 0xE6), 1, 1))?.value)
    }

    @Test
    fun `corrupt png file yields no accent without throwing`() {
        val corrupt = iconDir().resolve("icon.png")
        Files.writeString(corrupt, "definitely not a png")

        assertNull(ProjectIconAccentExtractor.extract(corrupt.toFile()))
    }

    @Test
    fun `real png file round-trips through the decode path`() {
        val iconFile = iconDir().resolve("icon.png").toFile()
        ImageIO.write(solidImage(Color(0x5C, 0xCF, 0xE6), 24, 24), "png", iconFile)

        assertEquals("#5CCFE6", ProjectIconAccentExtractor.extract(iconFile)?.value)
        assertEquals(
            iconFile.canonicalPath,
            ProjectIconAccentExtractor.projectIconFile(tempDir.toString())?.canonicalPath,
        )
    }

    @Test
    fun `projectIconFile rejects blank base path missing icon and oversized icon`() {
        assertNull(ProjectIconAccentExtractor.projectIconFile(null))
        assertNull(ProjectIconAccentExtractor.projectIconFile("  "))
        assertNull(ProjectIconAccentExtractor.projectIconFile(tempDir.resolve("no-such-project").toString()))

        val oversized = iconDir().resolve("icon.png")
        Files.write(oversized, ByteArray((ProjectIconAccentExtractor.MAX_ICON_FILE_BYTES + 1).toInt()))
        assertNull(ProjectIconAccentExtractor.projectIconFile(tempDir.toString()))
    }

    private fun iconDir(): Path = Files.createDirectories(tempDir.resolve(".idea"))

    private fun solidImage(
        color: Color,
        width: Int,
        height: Int,
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until width) {
            for (y in 0 until height) {
                image.setRGB(x, y, color.rgb)
            }
        }
        return image
    }
}
