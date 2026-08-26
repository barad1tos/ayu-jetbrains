package dev.ayuislands.syntax

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.theme.EditorSchemeChange
import java.awt.Color
import java.awt.Font
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrator for the syntax-intensity apply pipeline.
 *
 * Responsibilities (composed across the apply path):
 *  - H5 dual-write: for each [AYU_SCHEMES] tuple, look up the named scheme,
 *    compute the per-key payload, and write it back; then ALSO write to the
 *    active `globalScheme` whenever it isn't one of the named instances we
 *    already touched (identity dedup avoids double-writes on a clean
 *    install).
 *  - R-7 single publish: exactly one [EditorSchemeChange] event per apply call.
 *    The shared publisher supplies the write-intent context required by
 *    downstream editor listeners.
 *  - Pattern A latches: a missing named scheme logs WARN once per (scheme,
 *    session); an unknown overlay variant tag arriving via
 *    [resolveActiveAyuOverlayVariant] (a future Ayu variant outside the whitelist)
 *    logs WARN once per (variantTag, session) and skips the R-1 fallback.
 *  - Atomic scheme writes: every target is computed before mutation, then
 *    [SyntaxSchemeTransaction] checkpoints only the touched attributes. Any
 *    write failure restores all checkpoints before the single publish; a
 *    publisher failure also restores the checkpointed attributes.
 *  - Inherited-key ownership: Custom may materialize a language key whose
 *    color normally comes from a registered fallback. [IdeSyntaxSchemeWriter]
 *    snapshots the exact direct attribute before the first write, restores it
 *    when the sparse cell disappears, and relinquishes ownership if the user
 *    changes that attribute while the overlay is active.
 *  - R-1 caller-side fallback: the applicator takes `editorBg` as a
 *    parameter and only emits a WARN when a dark variant arrives with
 *    `Color.WHITE`. This service is the canonical fallback site:
 *    [resolveEditorBg] substitutes [RgbBlend.fallbackEditorBgFor] when
 *    `scheme.defaultBackground == Color.WHITE` AND the variant tag is in
 *    the explicit [DARK_OVERLAY_VARIANTS] subset of [AYU_SCHEMES]. An
 *    unknown variant tag (a hypothetical future Ayu variant outside the
 *    whitelist) logs WARN once and the fallback is NOT applied — the
 *    rebuild needs to update [AYU_SCHEMES] + [DARK_OVERLAY_VARIANTS]
 *    before R-1 mitigation can engage. Substring matching against variant
 *    names is intentionally avoided here: a future scheme name like
 *    "Ayu Islands Darkroom" would otherwise silently capture the "Dark"
 *    branch.
 *  - Service-layer premium gates: [enforceCustomGate] normalises an unlicensed
 *    `CUSTOM` request down to `AMBIENT`, and [enforceReadabilityGate] drops
 *    premium readability modifiers for unlicensed callers. The Settings panel
 *    performs UI-level gating, but that gate is bypass-able from future
 *    actions, tests, or settings imports that call this service directly.
 *    Service-layer normalisation is the defense-in-depth so the applicator
 *    never sees premium syntax inputs from an unlicensed call path.
 *
 * The language tag for each baseline key is derived inside
 * [SyntaxIntensityApplicator.compute] via [SyntaxLanguageRegistry.classify].
 * The `overlayVariant` argument passed from here is the R-1 contract anchor
 * the applicator latches its dark-variant WARN against — the service does
 * NOT pre-compute a per-language map and does NOT pass the variant tag as
 * a "language" input to the curve lookup.
 *
 * Lifecycle gating (Pattern J `isAyuActive`) lives in
 * `dev.ayuislands.AyuIslandsLafListener`, not here — the service is callable
 * from the Settings Apply path even mid-LAF-switch.
 */
@Service(Service.Level.APP)
class SyntaxIntensityService {
    private val log = logger<SyntaxIntensityService>()
    private val missingSchemeLogged = ConcurrentHashMap.newKeySet<String>()
    private val unknownVariantLogged = ConcurrentHashMap.newKeySet<String>()
    private val skippedForeignActiveSchemeLogged = ConcurrentHashMap.newKeySet<String>()
    private val capabilitiesByVariant =
        ConcurrentHashMap<String, Map<String, Set<PrimitiveCategory>>>()
    private val loggedCapabilityMisses = ConcurrentHashMap.newKeySet<String>()
    private val unlicensedCustomLogged = AtomicBoolean(false)

    @Volatile
    private var replacementFonts: Map<String, Map<String, Int>> = emptyMap()

    fun apply(
        preset: SyntaxPreset,
        customOverrides: Map<String, Map<String, Int>>,
        subordinatePreset: SyntaxPreset = SyntaxPreset.AMBIENT,
        customStyles: Map<String, Map<String, Int>> = emptyMap(),
        readabilityOptions: SyntaxReadabilityOptions = SyntaxReadabilityOptions.DEFAULT,
    ) {
        apply(
            config =
                SyntaxPresetConfig(
                    selectedPreset = preset.name,
                    customOverrides = customOverrides,
                    subordinatePreset = subordinatePreset.name,
                    customStyles = customStyles,
                    readabilityOptions = readabilityOptions,
                ),
        )
    }

    fun apply(config: SyntaxPresetConfig) {
        val context = applyContext(config)
        val changes = schemeChanges(context)
        retireLegacyKeys(changes)
        val result = applyChanges(context, changes, IdeSyntaxSchemeWriter(), journal = null)
        if (result is SyntaxTransactionResult.Failed) {
            result.rollbackFailures.forEach { failure ->
                log.warn("Failed to roll back syntax scheme transaction", failure)
            }
            throw result.cause
        }
    }

    internal fun openRuntimeSession(): SyntaxRuntimeSession = SyntaxRuntimeSession()

    internal fun preview(
        config: SyntaxPresetConfig,
        writer: SyntaxSchemeWriter,
        journal: SyntaxSchemeJournal,
    ): SyntaxTransactionResult {
        val context = applyContext(config)
        val change =
            activeSchemeChange(context, emptySet())
                ?: return SyntaxTransactionResult.Applied(emptySet(), emptySet())
        return applyChanges(context, listOf(change), writer, journal)
    }

    internal fun materialize(
        config: SyntaxPresetConfig,
        writer: SyntaxSchemeWriter,
        journal: SyntaxSchemeJournal?,
    ): SyntaxTransactionResult {
        val context = applyContext(config)
        return applyChanges(context, schemeChanges(context), writer, journal)
    }

    private fun schemeChanges(context: ApplyContext): List<SyntaxSchemeChange> {
        val manager = EditorColorsManager.getInstance()
        val touched = mutableSetOf<EditorColorsScheme>()
        val changes = mutableListOf<SyntaxSchemeChange>()
        for ((schemeName, overlayVariant) in AYU_SCHEMES) {
            val scheme = manager.getScheme(schemeName)
            if (scheme == null) {
                if (missingSchemeLogged.add(schemeName)) {
                    log.warn("Ayu scheme '$schemeName' not registered — skipping syntax intensity overlay")
                }
                continue
            }
            changes += schemeChange(scheme, schemeName, overlayVariant, context)
            touched.add(scheme)
        }
        activeSchemeChange(context, touched)?.let(changes::add)
        return changes
    }

    private fun retireLegacyKeys(changes: List<SyntaxSchemeChange>) {
        val retirement = RetirementPass(retiredSchemeNames())
        changes.forEach { change -> retireKeys(change.scheme, change.label, retirement) }
        retirement.commitRepaired()
    }

    private fun applyContext(config: SyntaxPresetConfig): ApplyContext {
        val preset = SyntaxPreset.fromName(config.selectedPreset)
        val subordinatePreset = SyntaxPreset.fromName(config.subordinatePreset)
        val effectivePreset = enforceCustomGate(preset)
        val effectiveReadabilityOptions = enforceReadabilityGate(config.readabilityOptions)
        return ApplyContext(
            preset = effectivePreset,
            customOverrides = config.customOverrides,
            subordinatePreset = subordinatePreset,
            customStyles = config.customStyles,
            readabilityOptions = effectiveReadabilityOptions,
            customEmphasis = config.customEmphasis,
            ignorePluginSyntaxColorsEnabled =
                AyuIslandsSettings.getInstance().state.ignorePluginSyntaxColorsEnabled,
        )
    }

    private fun applyChanges(
        context: ApplyContext,
        changes: List<SyntaxSchemeChange>,
        writer: SyntaxSchemeWriter,
        journal: SyntaxSchemeJournal?,
    ): SyntaxTransactionResult {
        val previousFonts = replacementFonts
        replacementFonts = context.replacementFonts()
        val result = SyntaxSchemeTransaction(writer, ::publishSchemeChange).apply(changes, journal)
        when (result) {
            is SyntaxTransactionResult.Applied -> return result
            is SyntaxTransactionResult.Failed -> {
                replacementFonts = previousFonts
                return result
            }
        }
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

    fun reapplyForActiveLaf() {
        val state = SyntaxIntensityState.getInstance()
        val config = state.toPresetConfig()
        apply(config = config)
    }

    internal fun tunableCategories(variant: AyuVariant): Map<String, Set<PrimitiveCategory>>? {
        val variantName =
            when (variant) {
                AyuVariant.MIRAGE -> "Mirage"
                AyuVariant.DARK -> "Dark"
                AyuVariant.LIGHT -> "Light"
            }
        return capabilitiesByVariant[variantName]
            ?: run {
                if (loggedCapabilityMisses.add(variantName)) {
                    log.warn(
                        "Syntax capability snapshot for '$variantName' is unavailable — " +
                            "keeping all controls enabled",
                    )
                }
                null
            }
    }

    internal fun replacementFontType(
        language: String,
        category: PrimitiveCategory,
    ): Int? = replacementFonts[language]?.get(category.name)

    private data class ApplyContext(
        val preset: SyntaxPreset,
        val customOverrides: Map<String, Map<String, Int>>,
        val subordinatePreset: SyntaxPreset,
        val customStyles: Map<String, Map<String, Int>>,
        val readabilityOptions: SyntaxReadabilityOptions,
        val customEmphasis: Map<String, Map<String, Int>>,
        val ignorePluginSyntaxColorsEnabled: Boolean,
    ) {
        fun replacementFonts(): Map<String, Map<String, Int>> {
            if (preset != SyntaxPreset.CUSTOM) return emptyMap()
            return customStyles.mapValues { (language, styles) ->
                styles.mapValues { (category, style) ->
                    style or (customEmphasis[language]?.get(category) ?: Font.PLAIN)
                }
            }
        }
    }

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
    private fun enforceCustomGate(preset: SyntaxPreset): SyntaxPreset {
        if (preset != SyntaxPreset.CUSTOM) return preset
        if (LicenseChecker.isLicensedOrGrace()) return preset
        if (unlicensedCustomLogged.compareAndSet(false, true)) {
            log.warn(
                "Syntax intensity CUSTOM preset requested without license — normalizing to AMBIENT. " +
                    "If this is reproducible from a normal UI flow, the panel-level gate has a leak.",
            )
        }
        return SyntaxPreset.AMBIENT
    }

    private fun enforceReadabilityGate(readabilityOptions: SyntaxReadabilityOptions): SyntaxReadabilityOptions {
        if (readabilityOptions == SyntaxReadabilityOptions.DEFAULT) return readabilityOptions
        return if (LicenseChecker.isLicensedOrGrace()) readabilityOptions else SyntaxReadabilityOptions.DEFAULT
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
     * sits in the rendering chain instead of the named one we wrote to by
     * name. After the by-name loop, also write the same computed payload to
     * the active `globalScheme` when it is still an Ayu Islands named or
     * user-derived scheme and is NOT one of the three named instances we
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
        capabilitiesByVariant[variantTag] =
            SyntaxIntensityApplicator.tunableCategories(
                baseline = baseline,
                overlay = overlay,
                fallbacks = fallbacks,
            )
        loggedCapabilityMisses.remove(variantTag)
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

    private fun ignorePluginStockAttributes(
        variantTag: String,
        keyName: String,
    ): TextAttributes =
        (if (variantTag == "Light") IGNORE_PLUGIN_DEFAULT_STOCK else IGNORE_PLUGIN_DARCULA_STOCK)
            .getValue(keyName)
            .toTextAttributes()

    /**
     * Map a named or `_@user_`-derived Ayu active scheme name to its overlay
     * variant tag. Returns null for foreign schemes, including names that only
     * happen to contain variant words such as "Light".
     */
    private fun resolveActiveAyuOverlayVariant(activeSchemeName: String): String? {
        val normalized = activeSchemeName.removePrefix("_@user_")
        for ((schemeName, overlayVariant) in AYU_SCHEMES) {
            if (normalized.equals(schemeName, ignoreCase = true) ||
                normalized.startsWith("$schemeName ", ignoreCase = true) ||
                normalized.startsWith("$schemeName (", ignoreCase = true)
            ) {
                return overlayVariant
            }
        }
        return null
    }

    /**
     * Hands keys the plugin used to write back to the platform, once per install.
     *
     * Dropping a key from the overlay stops future writes but cannot undo a value an
     * earlier version already persisted into a derived `_@user_` scheme, and that file
     * outlives plugin upgrades. Writing the inherited marker is the only way to clear
     * one.
     *
     * One-shot per scheme name, not per install. `EditorColorsManager.getScheme(name)`
     * resolves the bundled instance by bare name; the editable `_@user_` copy that
     * actually persists carries the prefix and only arrives here as the active scheme.
     * The two therefore key separately and each gets its own pass, which also covers
     * users who alternate light and dark and so carry a flattened copy of each. An
     * install-wide flag would instead be spent on whichever copy happened to be active
     * and seal the rest away. Past its own pass a scheme's visibility keys belong to the
     * user again, so hand-set colours survive.
     */
    private fun retireKeys(
        scheme: EditorColorsScheme,
        schemeLabel: String,
        retirement: RetirementPass,
    ) {
        if (!retirement.isPending(schemeLabel)) return
        var clean = true
        for (keyName in RETIRED_KEY_NAMES) {
            try {
                scheme.setAttributes(
                    TextAttributesKey.find(keyName),
                    AbstractColorsScheme.INHERITED_ATTRS_MARKER,
                )
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (runtime: RuntimeException) {
                clean = false
                log.warn(
                    "Failed to retire $keyName on $schemeLabel — Java references may stay flattened " +
                        "there; re-apply from Settings -> Ayu Islands -> Syntax",
                    runtime,
                )
            }
        }
        if (clean) retirement.recordRepaired(schemeLabel)
    }

    /**
     * Tracks which schemes the one-shot retirement has already repaired.
     *
     * A scheme is only recorded once all four keys landed on it, so a partial failure
     * retries on the next apply instead of being sealed in.
     */
    private class RetirementPass(
        private val alreadyRetired: Set<String>,
    ) {
        private val repaired = mutableSetOf<String>()

        fun isPending(schemeLabel: String): Boolean = schemeLabel !in alreadyRetired

        fun recordRepaired(schemeLabel: String) {
            repaired.add(schemeLabel)
        }

        fun commitRepaired() {
            if (repaired.isEmpty()) return
            PropertiesComponent.getInstance().setList(RETIREMENT_FLAG_KEY, (alreadyRetired + repaired).toList())
        }
    }

    private fun retiredSchemeNames(): Set<String> =
        PropertiesComponent.getInstance().getList(RETIREMENT_FLAG_KEY)?.toSet() ?: emptySet()

    private fun publishSchemeChange() {
        EditorSchemeChange.publish()
    }

    internal inner class SyntaxRuntimeSession(
        private val writer: SyntaxSchemeWriter = IdeSyntaxSchemeWriter(),
        private val journal: SyntaxSchemeJournal = SyntaxSchemeJournal(),
    ) {
        private var fontCheckpoint = replacementFonts

        fun preview(config: SyntaxPresetConfig): SyntaxTransactionResult =
            this@SyntaxIntensityService.preview(config, writer, journal)

        fun materialize(config: SyntaxPresetConfig): SyntaxTransactionResult =
            this@SyntaxIntensityService.materialize(config, writer, journal)

        fun restore(): SyntaxTransactionResult {
            val result = journal.restore(writer, EditorSchemeChange::publish)
            if (result is SyntaxTransactionResult.Applied) replacementFonts = fontCheckpoint
            return result
        }

        fun advance() {
            journal.advance(writer)
            fontCheckpoint = replacementFonts
        }
    }

    companion object {
        // Explicit (registered scheme name -> overlay variant tag) whitelist.
        // Substring matching against variant names is intentionally avoided so
        // a hypothetical future "Ayu Islands Darkroom" does not silently
        // capture the "Dark" branch.
        private val AYU_SCHEMES =
            listOf(
                "Ayu Islands Mirage" to "Mirage",
                "Ayu Islands Dark" to "Dark",
                "Ayu Islands Light" to "Light",
            )

        // Dark-variant subset of AYU_SCHEMES. Only these trigger R-1 fallback
        // when defaultBackground == Color.WHITE. The Light variant's
        // Color.WHITE IS correct and must flow through unchanged.
        private val DARK_OVERLAY_VARIANTS = setOf("Mirage", "Dark")

        // Names of the schemes whose visibility keys have already been retired.
        // PropertiesComponent matches SyntaxIntensityMigrationNotifier — this does not
        // warrant its own @State file. Per scheme rather than one install-wide flag:
        // only the active scheme arrives here writable, so each poisoned copy needs its
        // own pass the first time it becomes active.
        private const val RETIREMENT_FLAG_KEY = "ayu.syntax.visibility.retired.schemes"

        // Keys the overlay used to define and must now leave to the platform.
        // Versions 2.7.0-2.8.1 gave these a foreground, which Java merges over the
        // role colour of a reference and flattens Java highlighting (issue #290).
        // Never add a key here without also removing it from the theme XMLs.
        private val RETIRED_KEY_NAMES =
            listOf(
                "PUBLIC_REFERENCE",
                "PROTECTED_REFERENCE",
                "PACKAGE_PRIVATE_REFERENCE",
                "PRIVATE_REFERENCE",
            )

        private const val IGNORE_COMMENT_KEY = "IGNORE.COMMENT"
        private const val IGNORE_SECTION_KEY = "IGNORE.SECTION"
        private const val IGNORE_HEADER_KEY = "IGNORE.HEADER"
        private const val IGNORE_NEGATION_KEY = "IGNORE.NEGATION"
        private const val IGNORE_BRACKET_KEY = "IGNORE.BRACKET"
        private const val IGNORE_SLASH_KEY = "IGNORE.SLASH"
        private const val IGNORE_SYNTAX_KEY = "IGNORE.SYNTAX"
        private const val IGNORE_VALUE_KEY = "IGNORE.VALUE"
        private const val IGNORE_UNUSED_ENTRY_KEY = "IGNORE.UNUSED_ENTRY"

        private val IGNORE_PLUGIN_KEY_NAMES =
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
