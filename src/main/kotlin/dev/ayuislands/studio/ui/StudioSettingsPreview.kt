package dev.ayuislands.studio.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontDetector
import dev.ayuislands.settings.IgnorePreviewInput
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.session.StudioFontFacts
import dev.ayuislands.studio.session.StudioFonts
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxOverlayLoader
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxPreviewCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/** Detached editor/font inputs. File reads and font enumeration are prepared outside Swing rendering. */
internal data class StudioSettingsPreview(
    val variant: AyuVariant,
    val editor: StudioEditorContext,
    val fonts: StudioFontFacts,
    val monospaceFamilies: List<String>,
) {
    companion object {
        suspend fun capture(
            resources: StudioFields,
            ioContext: CoroutineContext = Dispatchers.IO,
        ): StudioSettingsPreview {
            val native =
                withContext(Dispatchers.EDT) {
                    val scheme = EditorColorsManager.getInstance().globalScheme.clone() as EditorColorsScheme
                    (AyuVariant.detect() ?: AyuVariant.MIRAGE) to scheme
                }
            return withContext(ioContext) {
                val fonts = StudioFonts.capture(resources, ioContext)
                StudioSettingsPreview(
                    native.first,
                    editor(native.first, native.second),
                    fonts,
                    FontDetector.listMonospaceFonts(),
                )
            }
        }

        private fun editor(
            variant: AyuVariant,
            scheme: EditorColorsScheme,
        ): StudioEditorContext {
            val name = variant.name.lowercase(Locale.ROOT).replaceFirstChar(Char::titlecase)
            val loader = SyntaxOverlayLoader.getInstance()
            val request =
                SyntaxIntensityApplicator.Request(
                    SyntaxPreset.AMBIENT,
                    name,
                    scheme.defaultBackground,
                    loader.loadBaselineForVariant(name),
                    loader.loadOverlayForVariant(name),
                    fallbacks = loader.fallbacksFor(name),
                )
            return StudioEditorContext(scheme, request, ignore(request))
        }

        private fun ignore(request: SyntaxIntensityApplicator.Request): IgnorePreviewInput? {
            val fixture = SyntaxPreviewCatalog.find("Ignore files")?.fixtures?.firstOrNull() ?: return null
            val stream =
                StudioSettingsPreview::class.java.getResourceAsStream(
                    "/dev/ayuislands/settings/syntax-preview/${fixture.resourceName}",
                ) ?: return null
            val code = stream.bufferedReader().use { it.readText() }
            val keys =
                (request.baseline.keys + request.overlay.keys).filter {
                    it.externalName in SyntaxIntensityService.IGNORE_PLUGIN_KEY_NAMES
                }
            return IgnorePreviewInput(
                code,
                request,
                keys.associateWith {
                    SyntaxIntensityService.ignorePluginStockAttributes(
                        request.variantName,
                        it.externalName,
                    )
                },
            )
        }
    }
}
