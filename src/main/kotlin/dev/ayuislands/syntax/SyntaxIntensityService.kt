package dev.ayuislands.syntax

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.licensing.LicenseChecker
import java.awt.Color
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
 *  - R-7 single publish: exactly one
 *    `MessageBus.syncPublisher(EditorColorsManager.TOPIC)` invocation
 *    publishing a single global-scheme-change event per apply call,
 *    wrapped in a read action.
 *  - Pattern A latches: a missing named scheme logs WARN once per (scheme,
 *    session); an unknown overlay variant tag arriving via
 *    [resolveActiveAyuOverlayVariant] (a future Ayu variant outside the whitelist)
 *    logs WARN once per (variantTag, session) and skips the R-1 fallback.
 *  - Pattern B per-key write isolation: [writeSchemeAttributes] catches
 *    `RuntimeException` only (broader catches are forbidden); the failing
 *    key logs WARN and the next key still gets written. The single
 *    publish still fires once per apply. `CancellationException` propagates
 *    unconditionally.
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
    private val unlicensedCustomLogged = AtomicBoolean(false)

    fun apply(
        preset: SyntaxPreset,
        customOverrides: Map<String, Map<String, Int>>,
        subordinatePreset: SyntaxPreset = SyntaxPreset.AMBIENT,
        customStyles: Map<String, Map<String, Int>> = emptyMap(),
        readabilityOptions: SyntaxReadabilityOptions = SyntaxReadabilityOptions.DEFAULT,
    ) {
        val effectivePreset = enforceCustomGate(preset)
        val effectiveReadabilityOptions = enforceReadabilityGate(readabilityOptions)
        val context =
            ApplyContext(
                preset = effectivePreset,
                customOverrides = customOverrides,
                subordinatePreset = subordinatePreset,
                customStyles = customStyles,
                readabilityOptions = effectiveReadabilityOptions,
            )
        val manager = EditorColorsManager.getInstance()
        val touched = mutableSetOf<EditorColorsScheme>()
        for ((schemeName, overlayVariant) in AYU_SCHEMES) {
            val scheme = manager.getScheme(schemeName)
            if (scheme == null) {
                if (missingSchemeLogged.add(schemeName)) {
                    log.warn("Ayu scheme '$schemeName' not registered — skipping syntax intensity overlay")
                }
                continue
            }
            val computed =
                computeSchemeAttributes(
                    scheme = scheme,
                    variantTag = overlayVariant,
                    context = context,
                )
            writeSchemeAttributes(scheme, computed, schemeName)
            touched.add(scheme)
        }
        writeActiveSchemeIfNotTouched(context, touched)
        publishSchemeChange()
    }

    fun reapplyForActiveLaf() {
        val state = SyntaxIntensityState.getInstance()
        val config = state.toPresetConfig()
        val preset = SyntaxPreset.fromName(config.selectedPreset)
        val subordinate = SyntaxPreset.fromName(config.subordinatePreset)
        apply(preset, config.customOverrides, subordinate, config.customStyles, config.readabilityOptions)
    }

    private data class ApplyContext(
        val preset: SyntaxPreset,
        val customOverrides: Map<String, Map<String, Int>>,
        val subordinatePreset: SyntaxPreset,
        val customStyles: Map<String, Map<String, Int>>,
        val readabilityOptions: SyntaxReadabilityOptions,
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
    private fun writeActiveSchemeIfNotTouched(
        context: ApplyContext,
        touched: Set<EditorColorsScheme>,
    ) {
        val active = EditorColorsManager.getInstance().globalScheme
        if (active in touched) return
        val variant =
            resolveActiveAyuOverlayVariant(active.name)
                ?: run {
                    if (skippedForeignActiveSchemeLogged.add(active.name)) {
                        log.warn(
                            "Active editor scheme '${active.name}' is not an Ayu Islands scheme — " +
                                "skipping syntax intensity write",
                        )
                    }
                    return
                }
        val computed =
            computeSchemeAttributes(
                scheme = active,
                variantTag = variant,
                context = context,
            )
        writeSchemeAttributes(active, computed, active.name)
    }

    private fun computeSchemeAttributes(
        scheme: EditorColorsScheme,
        variantTag: String,
        context: ApplyContext,
    ): Map<TextAttributesKey, TextAttributes> {
        val loader = SyntaxOverlayLoader.getInstance()
        return SyntaxIntensityApplicator.compute(
            SyntaxIntensityApplicator.Request(
                preset = context.preset,
                variantName = variantTag,
                editorBg = resolveEditorBg(scheme, variantTag),
                baseline = loader.loadBaselineForVariant(variantTag),
                overlay = loader.loadOverlayForVariant(variantTag),
                customOverrides = context.customOverrides,
                subordinatePreset = context.subordinatePreset,
                customStyles = context.customStyles,
                readabilityOptions = context.readabilityOptions,
            ),
        )
    }

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

    private fun writeSchemeAttributes(
        scheme: EditorColorsScheme,
        computed: Map<TextAttributesKey, TextAttributes>,
        schemeLabel: String,
    ) {
        for ((key, attrs) in computed) {
            try {
                scheme.setAttributes(key, attrs)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (runtime: RuntimeException) {
                log.warn("setAttributes failed on $schemeLabel for key ${key.externalName}", runtime)
            }
        }
    }

    private fun publishSchemeChange() {
        val application = ApplicationManager.getApplication()
        application.runReadAction {
            application
                .messageBus
                .syncPublisher(EditorColorsManager.TOPIC)
                .globalSchemeChange(null)
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

        fun getInstance(): SyntaxIntensityService {
            val app = ApplicationManager.getApplication()
            return app.getService(SyntaxIntensityService::class.java)
        }
    }
}
