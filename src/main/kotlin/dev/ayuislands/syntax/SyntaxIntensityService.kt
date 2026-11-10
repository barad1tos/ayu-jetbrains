package dev.ayuislands.syntax

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.syntax.SyntaxIntensityService.Companion.AYU_SCHEMES
import dev.ayuislands.syntax.SyntaxIntensityService.Companion.DARK_OVERLAY_VARIANTS
import dev.ayuislands.theme.AyuEditorSchemeBinder
import java.awt.Color
import java.awt.Font
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Computes candidate syntax values; the shared Studio owner controls native writes and publication. */
@Service(Service.Level.APP)
class SyntaxIntensityService {
    private val log = logger<SyntaxIntensityService>()
    private val missingSchemeLogged = ConcurrentHashMap.newKeySet<String>()
    private val unknownVariantLogged = ConcurrentHashMap.newKeySet<String>()
    private val skippedForeignActiveSchemeLogged = ConcurrentHashMap.newKeySet<String>()
    private val unlicensedCustomLogged = AtomicBoolean(false)

    internal val fontState = SyntaxFontState()

    /** Computes explicit candidate choices without native writes, publication, or advancing runtime state. */
    internal fun prepare(
        config: SyntaxPresetConfig,
        isLicensed: Boolean,
        ignorePluginSyntaxColorsEnabled: Boolean,
    ): SyntaxPreparedChanges {
        val context = applyContext(config, isLicensed, ignorePluginSyntaxColorsEnabled)
        return SyntaxPreparedChanges(
            schemeChanges(context),
            SyntaxIntensityApplicator.replacementFonts(context.preset, context.customStyles, context.customEmphasis),
        )
    }

    private fun schemeChanges(context: ApplyContext): List<SyntaxSchemeChange> {
        val manager = EditorColorsManager.getInstance()
        val touched = mutableSetOf<EditorColorsScheme>()
        val changes = mutableListOf<SyntaxSchemeChange>()
        for ((variant, overlayVariant) in AYU_SCHEMES) {
            val schemeName = AyuEditorSchemeBinder.targetSchemeName(variant)
            val scheme = manager.getScheme(variant.editorSchemeResource)
            if (scheme == null) {
                if (missingSchemeLogged.add(schemeName)) {
                    log.warn("Ayu editable scheme '$schemeName' not registered — skipping syntax intensity overlay")
                }
                continue
            }
            changes += schemeChange(scheme, schemeName, overlayVariant, context)
            touched.add(scheme)
        }
        activeSchemeChange(context, touched)?.let(changes::add)
        return changes
    }

    private fun applyContext(
        config: SyntaxPresetConfig,
        isLicensed: Boolean,
        ignorePluginSyntaxColorsEnabled: Boolean,
    ): ApplyContext {
        val preset = SyntaxPreset.fromName(config.selectedPreset)
        val subordinatePreset = SyntaxPreset.fromName(config.subordinatePreset)
        val effectivePreset = enforceCustomGate(preset, isLicensed)
        val effectiveReadabilityOptions =
            SyntaxIntensityApplicator.effectiveReadability(config.readabilityOptions, isLicensed)
        return ApplyContext(
            preset = effectivePreset,
            customOverrides = config.customOverrides,
            subordinatePreset = subordinatePreset,
            customStyles = config.customStyles,
            readabilityOptions = effectiveReadabilityOptions,
            customEmphasis = config.customEmphasis,
            ignorePluginSyntaxColorsEnabled = ignorePluginSyntaxColorsEnabled,
        )
    }

    private fun schemeChange(
        scheme: EditorColorsScheme,
        label: String,
        variantTag: String,
        context: ApplyContext,
    ): SyntaxSchemeChange {
        val computed = computeSchemeAttributes(scheme, variantTag, context)
        return SyntaxSchemeChange(
            scheme = scheme,
            label = label,
            attributes = applyIgnorePluginPreference(computed.attributes, context, variantTag),
            materializedKeys = computed.materializedKeys,
        )
    }

    internal fun replacementFontType(
        language: String,
        category: PrimitiveCategory,
    ): Int? = fontState.current.styles[language]?.get(category.name)

    private data class ApplyContext(
        val preset: SyntaxPreset,
        val customOverrides: Map<String, Map<String, Int>>,
        val subordinatePreset: SyntaxPreset,
        val customStyles: Map<String, Map<String, Int>>,
        val readabilityOptions: SyntaxReadabilityOptions,
        val customEmphasis: Map<String, Map<String, Int>>,
        val ignorePluginSyntaxColorsEnabled: Boolean,
    )

    private data class SchemeComputation(
        val attributes: Map<TextAttributesKey, TextAttributes>,
        val materializedKeys: Set<String>,
    )

    /**
     * Service-layer `CUSTOM` premium gate.
     *
     * The Settings panel hides the `CUSTOM` pill from free users, but a call
     * path that bypasses the panel (future actions, programmatic apply,
     * settings imports, tests) would otherwise reach the applicator with
     * `CUSTOM` and a populated overrides map. Normalising to `AMBIENT` here
     * keeps the premium-only surface unreachable from any unlicensed call
     * site. The WARN log is latched (Pattern A) so a leaky call site is
     * discoverable in `idea.log` without spamming.
     */
    private fun enforceCustomGate(
        preset: SyntaxPreset,
        isLicensed: Boolean,
    ): SyntaxPreset {
        val effective = SyntaxIntensityApplicator.effectivePreset(preset, isLicensed)
        if (effective == preset) return effective
        if (unlicensedCustomLogged.compareAndSet(false, true)) {
            log.warn(
                "Syntax intensity CUSTOM preset requested without license — normalizing to AMBIENT. " +
                    "If this is reproducible from a normal UI flow, the panel-level gate has a leak.",
            )
        }
        return effective
    }

    /**
     * Resolve the editor background passed to [SyntaxIntensityApplicator.compute].
     *
     * R-1 fallback engagement is gated against the explicit
     * [DARK_OVERLAY_VARIANTS] whitelist rather than substring matching the
     * variant name — a hypothetical future scheme like "Ayu Islands
     * Darkroom" would otherwise be silently captured by a `contains("Dark")`
     * branch. An overlay variant tag not present in [AYU_SCHEMES] logs WARN
     * once per session and the fallback is NOT applied; the rebuild needs
     * to extend the whitelist before R-1 mitigation engages for the new
     * variant.
     *
     * For the Light variant, `Color.WHITE` IS the correct background and
     * must flow through unchanged — the fallback only triggers when the
     * variant tag is in [DARK_OVERLAY_VARIANTS] AND the scheme returned
     * `Color.WHITE` (the platform sentinel for an early-init read where
     * the scheme's background has not yet resolved).
     */
    private fun resolveEditorBg(
        scheme: EditorColorsScheme,
        variantTag: String,
    ): Color {
        val raw = scheme.defaultBackground
        val isKnownVariant = AYU_SCHEMES.any { it.second == variantTag }
        if (!isKnownVariant) {
            if (unknownVariantLogged.add(variantTag)) {
                log.warn(
                    "Unknown overlay variant tag '$variantTag' encountered — R-1 fallback skipped. " +
                        "If a new Ayu variant ships, extend AYU_SCHEMES + DARK_OVERLAY_VARIANTS in " +
                        "SyntaxIntensityService before the variant can rely on R-1 mitigation.",
                )
            }
            return raw
        }
        return if (raw.rgb == Color.WHITE.rgb && variantTag in DARK_OVERLAY_VARIANTS) {
            RgbBlend.fallbackEditorBgFor(variantTag)
        } else {
            raw
        }
    }

    /**
     * H5 active-derived-scheme write. The IDE may persist a
     * `_@user_Ayu Islands {Variant}` derived scheme whose `parent_scheme` is
     * `Darcula` rather than our registered Ayu variant. That derived scheme
     * sits in the rendering chain instead of the bundled original. After
     * resolving the native editable targets, also write the computed payload to
     * the active `globalScheme` when it is still an Ayu Islands named or
     * user-derived scheme and is NOT one of the three editable instances we
     * already touched (identity dedup — no double-write on a clean install).
     * Foreign active schemes are skipped so syntax-intensity reapply never
     * mutates non-Ayu color schemes.
     *
     * The active scheme is read from the [EditorColorsManager] singleton; the
     * `touched` set was built from the same singleton in the caller, so the
     * identity dedup comparison stays sound without threading the manager
     * instance through.
     */
    private fun activeSchemeChange(
        context: ApplyContext,
        touched: Set<EditorColorsScheme>,
    ): SyntaxSchemeChange? {
        val active = EditorColorsManager.getInstance().globalScheme
        if (active in touched) return null
        val variant =
            resolveActiveAyuOverlayVariant(active.name)
                ?: run {
                    if (skippedForeignActiveSchemeLogged.add(active.name)) {
                        log.warn(
                            "Active editor scheme '${active.name}' is not an Ayu Islands scheme — " +
                                "skipping syntax intensity write",
                        )
                    }
                    return null
                }
        val computed =
            computeSchemeAttributes(
                scheme = active,
                variantTag = variant,
                context = context,
            )
        return SyntaxSchemeChange(
            scheme = active,
            label = active.name,
            attributes = applyIgnorePluginPreference(computed.attributes, context, variant),
            materializedKeys = computed.materializedKeys,
        )
    }

    private fun computeSchemeAttributes(
        scheme: EditorColorsScheme,
        variantTag: String,
        context: ApplyContext,
    ): SchemeComputation {
        val loader = SyntaxOverlayLoader.getInstance()
        val baseline = loader.loadBaselineForVariant(variantTag)
        val overlay = loader.loadOverlayForVariant(variantTag)
        val fallbacks = loader.fallbacksFor(variantTag)
        val request =
            SyntaxIntensityApplicator.Request(
                preset = context.preset,
                variantName = variantTag,
                editorBg = resolveEditorBg(scheme, variantTag),
                baseline = baseline,
                overlay = overlay,
                customOverrides = context.customOverrides,
                subordinatePreset = context.subordinatePreset,
                customStyles = context.customStyles,
                readabilityOptions = context.readabilityOptions,
                customEmphasis = context.customEmphasis,
                fallbacks = fallbacks,
            )
        val computed = SyntaxIntensityApplicator.compute(request)
        val materializedKeys =
            computed.keys
                .filterTo(linkedSetOf()) { key ->
                    val source = overlay[key] ?: baseline[key]
                    source?.foregroundColor == null &&
                        (key.externalName in fallbacks || key.fallbackAttributeKey != null)
                }.mapTo(linkedSetOf()) { it.externalName }
        return SchemeComputation(computed, materializedKeys)
    }

    private fun applyIgnorePluginPreference(
        computed: Map<TextAttributesKey, TextAttributes>,
        context: ApplyContext,
        variantTag: String,
    ): Map<TextAttributesKey, TextAttributes> {
        if (context.ignorePluginSyntaxColorsEnabled) return computed

        val result = LinkedHashMap(computed)
        for (keyName in IGNORE_PLUGIN_KEY_NAMES) {
            val key = TextAttributesKey.find(keyName)
            result[key] = ignorePluginStockAttributes(variantTag, keyName)
        }
        return result
    }

    companion object {
        // Explicit (theme variant -> overlay variant tag) whitelist.
        // Substring matching against variant names is intentionally avoided so
        // a hypothetical future "Ayu Islands Darkroom" does not silently
        // capture the "Dark" branch.
        private val AYU_SCHEMES =
            listOf(
                AyuVariant.MIRAGE to "Mirage",
                AyuVariant.DARK to "Dark",
                AyuVariant.LIGHT to "Light",
            )

        // Dark-variant subset of AYU_SCHEMES. Only these trigger R-1 fallback
        // when defaultBackground == Color.WHITE. The Light variant's
        // Color.WHITE IS correct and must flow through unchanged.
        private val DARK_OVERLAY_VARIANTS = setOf("Mirage", "Dark")

        private const val IGNORE_COMMENT_KEY = "IGNORE.COMMENT"
        private const val IGNORE_SECTION_KEY = "IGNORE.SECTION"
        private const val IGNORE_HEADER_KEY = "IGNORE.HEADER"
        private const val IGNORE_NEGATION_KEY = "IGNORE.NEGATION"
        private const val IGNORE_BRACKET_KEY = "IGNORE.BRACKET"
        private const val IGNORE_SLASH_KEY = "IGNORE.SLASH"
        private const val IGNORE_SYNTAX_KEY = "IGNORE.SYNTAX"
        private const val IGNORE_VALUE_KEY = "IGNORE.VALUE"
        private const val IGNORE_UNUSED_ENTRY_KEY = "IGNORE.UNUSED_ENTRY"

        internal val IGNORE_PLUGIN_KEY_NAMES =
            listOf(
                IGNORE_COMMENT_KEY,
                IGNORE_SECTION_KEY,
                IGNORE_HEADER_KEY,
                IGNORE_NEGATION_KEY,
                IGNORE_BRACKET_KEY,
                IGNORE_SLASH_KEY,
                IGNORE_SYNTAX_KEY,
                IGNORE_VALUE_KEY,
                IGNORE_UNUSED_ENTRY_KEY,
            )

        private val IGNORE_PLUGIN_DARCULA_STOCK =
            mapOf(
                IGNORE_COMMENT_KEY to IgnorePluginStockStyle(foregroundRgb = 0x808080),
                IGNORE_SECTION_KEY to IgnorePluginStockStyle(foregroundRgb = 0x8C8C8C, backgroundRgb = 0x3A3A3A),
                IGNORE_HEADER_KEY to
                    IgnorePluginStockStyle(
                        foregroundRgb = 0x8C8C8C,
                        backgroundRgb = 0x3A3A3A,
                        fontType = Font.BOLD,
                    ),
                IGNORE_NEGATION_KEY to IgnorePluginStockStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
                IGNORE_BRACKET_KEY to IgnorePluginStockStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
                IGNORE_SLASH_KEY to IgnorePluginStockStyle(foregroundRgb = 0x808080),
                IGNORE_SYNTAX_KEY to
                    IgnorePluginStockStyle(
                        foregroundRgb = 0xACACAC,
                        backgroundRgb = 0x4A4A4A,
                        fontType = Font.BOLD,
                    ),
                IGNORE_VALUE_KEY to IgnorePluginStockStyle(foregroundRgb = 0x629755),
                IGNORE_UNUSED_ENTRY_KEY to IgnorePluginStockStyle(foregroundRgb = 0x808080, fontType = Font.ITALIC),
            )

        private val IGNORE_PLUGIN_DEFAULT_STOCK =
            mapOf(
                IGNORE_COMMENT_KEY to IgnorePluginStockStyle(foregroundRgb = 0x808080),
                IGNORE_SECTION_KEY to IgnorePluginStockStyle(foregroundRgb = 0x808080, backgroundRgb = 0xECFAEB),
                IGNORE_HEADER_KEY to
                    IgnorePluginStockStyle(
                        foregroundRgb = 0x808080,
                        backgroundRgb = 0xECFAEB,
                        fontType = Font.BOLD,
                    ),
                IGNORE_NEGATION_KEY to IgnorePluginStockStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
                IGNORE_BRACKET_KEY to IgnorePluginStockStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
                IGNORE_SLASH_KEY to IgnorePluginStockStyle(foregroundRgb = 0x808080),
                IGNORE_SYNTAX_KEY to
                    IgnorePluginStockStyle(
                        foregroundRgb = 0xACACAC,
                        backgroundRgb = 0x4A4A4A,
                        fontType = Font.BOLD,
                    ),
                IGNORE_VALUE_KEY to IgnorePluginStockStyle(foregroundRgb = 0x5C9F30),
                IGNORE_UNUSED_ENTRY_KEY to IgnorePluginStockStyle(foregroundRgb = 0x808080, fontType = Font.ITALIC),
            )

        /**
         * Map a named or `_@user_`-derived Ayu active scheme name to its overlay
         * variant tag. Returns null for foreign schemes, including names that only
         * happen to contain variant words such as "Light".
         */
        internal fun resolveActiveAyuOverlayVariant(activeSchemeName: String): String? {
            val normalized = activeSchemeName.removePrefix("_@user_")
            for ((variant, overlayVariant) in AYU_SCHEMES) {
                val schemeName = AyuEditorSchemeBinder.targetSchemeName(variant)
                if (normalized.equals(schemeName, ignoreCase = true) ||
                    normalized.startsWith("$schemeName ", ignoreCase = true) ||
                    normalized.startsWith("$schemeName (", ignoreCase = true)
                ) {
                    return overlayVariant
                }
            }
            return null
        }

        /** Materializes the same detached stock attributes used when Ignore colors are disabled. */
        internal fun ignorePluginStockAttributes(
            variantTag: String,
            keyName: String,
        ): TextAttributes =
            (if (variantTag == "Light") IGNORE_PLUGIN_DEFAULT_STOCK else IGNORE_PLUGIN_DARCULA_STOCK)
                .getValue(keyName)
                .toTextAttributes()

        private data class IgnorePluginStockStyle(
            val foregroundRgb: Int,
            val backgroundRgb: Int? = null,
            val fontType: Int = Font.PLAIN,
        ) {
            // Same value in both themes on purpose: these replicate the .ignore plugin's
            // own stock colours, which do not vary with the IDE theme.
            fun toTextAttributes(): TextAttributes =
                TextAttributes().also { attributes ->
                    attributes.foregroundColor = JBColor(foregroundRgb, foregroundRgb)
                    attributes.backgroundColor = backgroundRgb?.let { JBColor(it, it) }
                    attributes.fontType = fontType
                }
        }

        fun getInstance(): SyntaxIntensityService {
            val app = ApplicationManager.getApplication()
            return app.getService(SyntaxIntensityService::class.java)
        }
    }
}
