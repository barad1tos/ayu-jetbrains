package dev.ayuislands.studio.session

import com.intellij.openapi.editor.colors.EditorColorsManager
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontSettings
import dev.ayuislands.theme.EditorSchemeChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** Native I/O only; candidate intent, ownership decisions and rollback remain outside the host. */
internal interface StudioFontHost : StudioSchemeHost {
    suspend fun resolveFamily(settings: FontSettings): String?
}

/** Uses the caller's modality; resource discovery never substitutes a fallback font family. */
internal class IdeFontHost(
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : StudioFontHost {
    override fun observe(): FontSchemeView {
        val manager = EditorColorsManager.getInstance()
        return FontSchemeView(manager.allSchemes.toList(), manager.globalScheme, AyuVariant.detect())
    }

    override suspend fun resolveFamily(settings: FontSettings): String? =
        withContext(ioContext) {
            if (settings.preset.isCurated) {
                FontDetector.resolveFamily(settings.preset)
            } else {
                settings.fontFamily.takeIf(FontDetector::isFamilyInstalled)
            }
        }

    override fun newIdentity(): String = UUID.randomUUID().toString()

    override fun refresh() = EditorSchemeChange.publish()
}
