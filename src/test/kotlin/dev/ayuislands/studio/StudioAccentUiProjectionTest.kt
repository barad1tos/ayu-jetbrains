package dev.ayuislands.studio

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioAccentProjection
import dev.ayuislands.studio.session.StudioTabGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioAccentUiProjectionTest {
    private val document = sessionDocument()
    private val context = AccentContext.Ayu(AyuVariant.MIRAGE)
    private val licensed = LicenseEntitlement.LICENSED
    private val appearance =
        document.currents.global.appearance.let {
            it.copy(
                accent =
                    replace(
                        it.accent,
                        "glowTabMode" to RawValue.Text("FULL"),
                        "tabUnderlineHeight" to RawValue.Integer(27),
                        "tabUnderlineGlowSync" to RawValue.BooleanValue(true),
                    ),
                glow =
                    replace(
                        it.glow,
                        "glowEnabled" to RawValue.BooleanValue(true),
                        "glowStyle" to RawValue.Text("SOFT"),
                        "softWidth" to RawValue.Integer(11),
                    ),
            )
        }

    @Test
    fun `tab height follows the selected raw style without normalizing stored choices`() {
        val before = appearance.copy()
        assertEquals(StudioTabGeometry.Set(11), project(appearance).geometry)
        val off = appearance.copy(accent = replace(appearance.accent, "glowTabMode" to RawValue.Text("OFF")))
        assertEquals(StudioTabGeometry.Set(27), project(off).geometry)
        val unsynced =
            appearance.copy(
                accent =
                    replace(
                        appearance.accent,
                        "tabUnderlineGlowSync" to RawValue.BooleanValue(false),
                    ),
            )
        assertEquals(StudioTabGeometry.Set(27), project(unsynced).geometry)
        val disabled = appearance.copy(glow = replace(appearance.glow, "glowEnabled" to RawValue.BooleanValue(false)))
        assertEquals(StudioTabGeometry.Set(27), project(disabled).geometry)
        assertEquals(before, appearance)
    }

    @Test
    fun `Free uses the legacy effective mode and height while preserving the premium recipe`() {
        val projected =
            StudioAccentProjection.ui(
                appearance,
                document.shared.plugins,
                context,
                LicenseEntitlement.UNLICENSED,
            )
        assertEquals(GlowTabMode.MINIMAL, projected.tabMode)
        assertEquals(StudioTabGeometry.Set(4), projected.geometry)
        assertTrue(projected.policy.overrideNative)
        assertEquals(
            RawValue.Text("FULL"),
            appearance.accent.records
                .single { it.key.name == "glowTabMode" }
                .value,
        )
    }

    @Test
    fun `external native opt out can retain tab styling but null context never changes geometry`() {
        val plugins =
            replace(
                document.shared.plugins,
                "externalThemeEnhancementsEnabled" to RawValue.BooleanValue(true),
                "externalThemeChromeTintEnabled" to RawValue.BooleanValue(true),
                "externalThemeAccentOverrideEnabled" to RawValue.BooleanValue(false),
            )
        val tabsOnly = StudioAccentProjection.ui(appearance, plugins, AccentContext.External, licensed)
        assertFalse(tabsOnly.policy.overrideNative)
        assertTrue(tabsOnly.policy.tintTabColors)
        assertEquals(StudioTabGeometry.Set(11), tabsOnly.geometry)
        val disabled = replace(plugins, "externalThemeChromeTintEnabled" to RawValue.BooleanValue(false))
        assertEquals(
            StudioTabGeometry.Release,
            StudioAccentProjection.ui(appearance, disabled, AccentContext.External, licensed).geometry,
        )
        assertEquals(
            StudioTabGeometry.Unchanged,
            StudioAccentProjection.ui(appearance, plugins, null, licensed).geometry,
        )
    }

    @Test
    fun `chrome master does not change always on palette or tab geometry`() {
        val enabled = project(appearance)
        val disabled =
            appearance.copy(
                accent =
                    replace(
                        appearance.accent,
                        "chromeTintingEnabled" to RawValue.BooleanValue(false),
                    ),
            )
        assertEquals(enabled, project(disabled))
        assertFailsWith<IllegalArgumentException> {
            StudioAccentProjection.ui(appearance, document.shared.plugins, context, LicenseEntitlement.UNKNOWN)
        }
    }

    private fun project(candidate: AppearanceSnapshot) =
        StudioAccentProjection.ui(candidate, document.shared.plugins, context, licensed)

    private fun replace(
        fields: StudioFields,
        vararg changes: Pair<String, RawValue>,
    ): StudioFields {
        val values = changes.toMap()
        return fields.copy(
            records = StudioList(fields.records.map { it.copy(value = values[it.key.name] ?: it.value) }),
        )
    }
}
