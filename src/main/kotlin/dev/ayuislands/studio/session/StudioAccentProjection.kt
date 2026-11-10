package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentFallback
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ChromeTintSnapshot
import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.accent.TintIntensity
import dev.ayuislands.accent.accentUiPolicy
import dev.ayuislands.accent.resolveUnderlineHeight
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.persistence.AccentMappingsView
import dev.ayuislands.studio.persistence.LegacyValues
import java.util.Locale

/** Detached resolver input only; never serialize it back over the ordered raw Studio fields. */
internal object StudioAccentProjection {
    fun ui(
        appearance: AppearanceSnapshot,
        plugins: StudioFields,
        context: AccentContext?,
        entitlement: LicenseEntitlement,
    ): StudioAccentUiSettings {
        require(entitlement != LicenseEntitlement.UNKNOWN) { "Accent entitlement is unconfirmed" }
        val fields = values(appearance.accent)
        val integrations = values(plugins)
        val licensed = entitlement == LicenseEntitlement.LICENSED
        val mode =
            if (licensed) {
                GlowTabMode.fromName(nullableText(fields.getValue("glowTabMode")) ?: GlowTabMode.MINIMAL.name)
            } else {
                GlowTabMode.MINIMAL
            }
        val policy =
            accentUiPolicy(
                external = context == AccentContext.External,
                overrideEnabled = boolean(integrations.getValue("externalThemeAccentOverrideEnabled")),
                chromeAllowed =
                    licensed &&
                        boolean(integrations.getValue("externalThemeEnhancementsEnabled")) &&
                        boolean(integrations.getValue("externalThemeChromeTintEnabled")),
                tabMode = mode,
            )
        val geometry =
            when {
                context == null -> StudioTabGeometry.Unchanged
                !policy.overrideTabGeometry -> StudioTabGeometry.Release
                !licensed -> StudioTabGeometry.Set(AyuIslandsState.DEFAULT_TAB_UNDERLINE_HEIGHT)
                else -> StudioTabGeometry.Set(underlineHeight(appearance, fields, mode))
            }
        return StudioAccentUiSettings(context, mode, policy, geometry)
    }

    fun chrome(
        appearance: AppearanceSnapshot,
        plugins: StudioFields,
        context: AccentContext?,
        entitlement: LicenseEntitlement,
    ): StudioChromeInput {
        val fields = values(appearance.accent)
        val integrations = values(plugins)
        val intensity = requireNotNull(fields.getValue("chromeTintIntensity") as? RawValue.Integer).value
        val settings =
            ChromeTintSnapshot(
                chromeStatusBar = boolean(fields.getValue("chromeStatusBar")),
                chromeMainToolbar = boolean(fields.getValue("chromeMainToolbar")),
                chromeToolWindowStripe = boolean(fields.getValue("chromeToolWindowStripe")),
                chromeNavBar = boolean(fields.getValue("chromeNavBar")),
                chromePanelBorder = boolean(fields.getValue("chromePanelBorder")),
                intensity = TintIntensity.of(intensity),
                chromeTintingEnabled = boolean(fields.getValue("chromeTintingEnabled")),
            )
        return StudioChromeInput(
            context,
            settings,
            boolean(integrations.getValue("externalThemeEnhancementsEnabled")) &&
                boolean(integrations.getValue("externalThemeChromeTintEnabled")),
            requireNotNull(fields.getValue("forceOverrides") as? RawValue.OrderedSet).values,
            entitlement,
        )
    }

    fun context(
        plugins: StudioFields,
        variant: AyuVariant?,
    ): AccentContext? =
        when {
            variant != null -> {
                AccentContext.Ayu(variant)
            }

            values(plugins).getValue("externalThemeEnhancementsEnabled") == RawValue.BooleanValue(true) -> {
                AccentContext.External
            }

            else -> {
                null
            }
        }

    fun fallback(
        appearance: AppearanceSnapshot,
        shared: SharedPreferences,
        context: AccentContext,
        systemAccent: String?,
    ): AccentFallback =
        when (context) {
            is AccentContext.Ayu -> {
                val variant = context.ayuVariant
                val key = "${variant.name.lowercase(Locale.ROOT)}Accent"
                val stored = nullableText(values(appearance.accent).getValue(key)) ?: variant.defaultAccent
                val followsSystem = StudioAccentPolicy.followsSystem(appearance, shared.systemAccentPolicy)
                AccentFallback.Global(if (followsSystem && systemAccent != null) systemAccent else stored)
            }

            AccentContext.External -> {
                val plugins = values(shared.plugins)
                AccentFallback.External(
                    ExternalAccentSource.fromName(nullableText(plugins.getValue("externalThemeAccentSource"))),
                    nullableText(plugins.getValue("externalThemeAccent")),
                )
            }
        }

    fun mappings(fields: StudioFields): AccentMappingsView =
        AccentMappingsView(LegacyValues(LegacyStore.MAPPINGS, fields.records))

    /** Color values and ordering do not change classification of the existing cached language weights. */
    fun reclassifiesLanguage(
        before: StudioFields,
        after: StudioFields,
    ): Boolean {
        if (before.records == after.records) return false
        val previous = values(before)
        val proposed = values(after)
        return languageKeys(previous) != languageKeys(proposed) ||
            policyState(previous).languageResolutionPolicy() != policyState(proposed).languageResolutionPolicy()
    }

    private fun policyState(raw: Map<String, RawValue>): AccentMappingsState =
        AccentMappingsState().apply {
            dominanceThreshold = number(raw.getValue("dominanceThreshold"))
            dominanceMarginRatio = number(raw.getValue("dominanceMarginRatio"))
            dominanceFloor = number(raw.getValue("dominanceFloor"))
            tiebreakMinShare = number(raw.getValue("tiebreakMinShare"))
        }

    private fun underlineHeight(
        appearance: AppearanceSnapshot,
        fields: Map<String, RawValue>,
        mode: GlowTabMode,
    ): Int {
        val glow = StudioGlowProjection.settings(appearance)
        return resolveUnderlineHeight(
            mode,
            requireNotNull(fields.getValue("tabUnderlineHeight") as? RawValue.Integer).value,
            boolean(fields.getValue("tabUnderlineGlowSync")),
            glow.enabled,
            glow.width.getValue(glow.style),
        )
    }

    private fun languageKeys(raw: Map<String, RawValue>): Set<String> =
        requireNotNull(raw.getValue("languageAccents") as? RawValue.OrderedMap).entries.map { it.key }.toSet()

    private fun values(fields: StudioFields): Map<String, RawValue> =
        fields.records.associate { it.key.name to it.value }

    private fun text(raw: RawValue): String = requireNotNull(raw as? RawValue.Text).value

    private fun nullableText(raw: RawValue): String? = if (raw == RawValue.Null) null else text(raw)

    private fun number(raw: RawValue): Float = Float.fromBits(requireNotNull(raw as? RawValue.FloatBits).bits)

    private fun boolean(raw: RawValue): Boolean = requireNotNull(raw as? RawValue.BooleanValue).value
}
