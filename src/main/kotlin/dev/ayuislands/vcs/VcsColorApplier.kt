package dev.ayuislands.vcs

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import java.awt.Color

/** Computes the native VCS palette; the shared Studio participant owns all writes and restoration. */
internal object VcsColorApplier {
    /**
     * Computes explicit candidate values without claiming ownership, writing native fields, or scheduling a repaint.
     * Disabled or unavailable customization has no palette writes; the participant separately restores owned entries.
     */
    fun prepare(
        scheme: EditorColorsScheme,
        snapshot: VcsColorSnapshot,
        variant: AyuVariant,
        isLicensed: Boolean,
    ): Map<SchemeEntry, SchemeValue> {
        if (!snapshot.enabled || !isLicensed) return emptyMap()
        return buildMap {
            for ((category, entries) in VcsColorPalette.allCategoriesAndEntries()) {
                val intensity = snapshot.intensityFor(category)
                for (entry in entries) {
                    val tinted = blendFor(entry, variant, intensity)
                    when (entry.mode) {
                        VcsWriteMode.COLOR_KEY -> {
                            put(SchemeEntry.ColorEntry(ColorKey.find(entry.keyName)), SchemeValue.ColorValue(tinted))
                        }

                        VcsWriteMode.TEXT_ATTR_BG -> {
                            val key = TextAttributesKey.find(entry.keyName)
                            val attributes = backgroundAttributes(scheme.getAttributes(key), tinted)
                            put(SchemeEntry.AttributesEntry(key), SchemeValue.AttributesValue(attributes))
                        }
                    }
                }
            }
        }
    }

    private fun blendFor(
        entry: VcsPaletteEntry,
        variant: AyuVariant,
        intensity: VcsIntensity,
    ): Color {
        val (base, target) = VcsColorPalette.endpoints(entry, variant)
        return VcsColorBlender.blend(base, target, intensity)
    }

    private fun backgroundAttributes(
        existing: TextAttributes?,
        background: Color,
    ): TextAttributes = (existing?.clone() ?: TextAttributes()).apply { backgroundColor = background }
}
