package dev.ayuislands.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ayuislands.indent.IrPalette
import dev.ayuislands.indent.ResolvedIrState
import javax.swing.SwingUtilities

class IndentRainbowRefreshTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testSwingRefreshPublishesSynchronously() {
        val application = ApplicationManager.getApplication()
        val config = PaletteConfig()
        val cache = PaletteCache()
        val original = IrPalette(config.type.name, config.palette, config.colorCount)
        val resolved =
            ResolvedIrState(
                config = config,
                paletteTypeField = PaletteConfig::class.java.getField("type"),
                customPaletteField = PaletteConfig::class.java.getField("palette"),
                colorCountField = PaletteConfig::class.java.getField("colorCount"),
                updateMethod = PaletteCache::class.java.getMethod("update", PaletteConfig::class.java),
                companion = cache,
            )
        val target = IrPalette("CUSTOM", "updated palette", 5)
        val observed = mutableListOf<IrPalette>()
        val lifetime = Disposer.newDisposable("IndentRainbowRefreshTest.listener")
        var hasWriteAccess = true

        try {
            application.messageBus.connect(lifetime).subscribe(
                EditorColorsManager.TOPIC,
                EditorColorsListener {
                    assertTrue("Editor listeners must run inside the write action", application.isWriteAccessAllowed)
                    assertEquals(
                        "The cache must update before editor listeners run",
                        IrPalette(config.type.name, config.palette, config.colorCount),
                        cache.palette,
                    )
                    observed += cache.palette ?: error("Palette cache is absent")
                },
            )

            SwingUtilities.invokeAndWait {
                hasWriteAccess = application.isWriteAccessAllowed
                resolved.writePalette(target, PaletteType.CUSTOM)
                assertEquals("Listeners must complete before the palette write returns", listOf(target), observed)
                resolved.writePalette(original, PaletteType.DEFAULT)
                assertEquals("Restoration must also publish before returning", listOf(target, original), observed)
            }

            assertFalse("A plain Swing callback must start outside a write action", hasWriteAccess)
            assertEquals(original, cache.palette)
            assertEquals(PaletteType.DEFAULT, config.type)
            assertEquals(original.palette, config.palette)
            assertEquals(original.colorCount, config.colorCount)
        } finally {
            Disposer.dispose(lifetime)
        }
    }

    enum class PaletteType { DEFAULT, CUSTOM }

    class PaletteConfig {
        @JvmField var type = PaletteType.DEFAULT

        @JvmField var palette: String? = "native palette"

        @JvmField var colorCount = 3
    }

    class PaletteCache {
        internal var palette: IrPalette? = null
            private set

        fun update(config: PaletteConfig) {
            palette = IrPalette(config.type.name, config.palette, config.colorCount)
        }
    }
}
