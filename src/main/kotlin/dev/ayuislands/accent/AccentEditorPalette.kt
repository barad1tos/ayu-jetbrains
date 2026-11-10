package dev.ayuislands.accent

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import java.awt.Color

/** One catalog for legacy writes and detached Studio preparation; neither path changes the source attributes. */
internal object AccentEditorPalette {
    val colorKeys: List<ColorKey> =
        listOf(
            ColorKey.find("BUTTON_BACKGROUND"),
            ColorKey.find("WARNING_FOREGROUND"),
            ColorKey.find("TAB_UNDERLINE"),
        )

    private enum class AttributeTarget { FOREGROUND, EFFECT_COLOR, ERROR_STRIPE }

    private data class AttributeOverride(
        val key: String,
        val targets: Set<AttributeTarget>,
    )

    private val overrides =
        listOf(
            AttributeOverride("BOOKMARKS_ATTRIBUTES", setOf(AttributeTarget.ERROR_STRIPE)),
            AttributeOverride("DEBUGGER_INLINED_VALUES_MODIFIED", setOf(AttributeTarget.FOREGROUND)),
            AttributeOverride("LIVE_TEMPLATE_ATTRIBUTES", setOf(AttributeTarget.EFFECT_COLOR)),
            AttributeOverride("LOG_INFO_OUTPUT", setOf(AttributeTarget.FOREGROUND)),
            AttributeOverride("RUNTIME_ERROR", setOf(AttributeTarget.EFFECT_COLOR)),
            AttributeOverride("SMART_COMPLETION_STATISTICAL_MATCHED_ITEM", setOf(AttributeTarget.FOREGROUND)),
            AttributeOverride("TEXT_STYLE_WARNING", setOf(AttributeTarget.EFFECT_COLOR)),
            AttributeOverride("TODO_DEFAULT_ATTRIBUTES", setOf(AttributeTarget.FOREGROUND)),
            AttributeOverride("WARNING_ATTRIBUTES", setOf(AttributeTarget.EFFECT_COLOR, AttributeTarget.ERROR_STRIPE)),
        )

    /** Lazy reads preserve legacy read/write ordering; Studio materializes the sequence before its first write. */
    fun attributes(
        scheme: EditorColorsScheme,
        accent: Color,
    ): Sequence<Pair<TextAttributesKey, TextAttributes>> =
        overrides.asSequence().map { override ->
            val key = TextAttributesKey.find(override.key)
            val updated = scheme.getAttributes(key)?.clone() ?: TextAttributes()
            if (AttributeTarget.FOREGROUND in override.targets) updated.foregroundColor = accent
            if (AttributeTarget.EFFECT_COLOR in override.targets) updated.effectColor = accent
            if (AttributeTarget.ERROR_STRIPE in override.targets) updated.errorStripeColor = accent
            key to updated
        }

    fun entries(
        scheme: EditorColorsScheme,
        accent: Color,
    ): Sequence<Pair<SchemeEntry, SchemeValue>> =
        sequence {
            for (key in colorKeys) yield(SchemeEntry.ColorEntry(key) to SchemeValue.ColorValue(accent))
            for ((key, value) in attributes(scheme, accent)) {
                yield(SchemeEntry.AttributesEntry(key) to SchemeValue.AttributesValue(value))
            }
        }
}
