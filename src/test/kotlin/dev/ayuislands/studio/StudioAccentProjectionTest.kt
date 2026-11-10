package dev.ayuislands.studio

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.PersistedAccentMappingsView
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioAccentProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioAccentProjectionTest {
    @Test
    fun `chrome projection clamps runtime intensity without replacing raw choices or unknown overrides`() {
        val document = sessionDocument()
        val original = document.currents.global.appearance
        val overrides = RawValue.OrderedSet(StudioList(listOf("future", "NAV_BAR", "old-extension")))
        val appearance =
            original.copy(
                accent =
                    replace(
                        original.accent,
                        "chromeTintingEnabled" to RawValue.BooleanValue(false),
                        "chromeStatusBar" to RawValue.BooleanValue(true),
                        "chromeMainToolbar" to RawValue.BooleanValue(false),
                        "chromeToolWindowStripe" to RawValue.BooleanValue(true),
                        "chromeNavBar" to RawValue.BooleanValue(false),
                        "chromePanelBorder" to RawValue.BooleanValue(true),
                        "chromeTintIntensity" to RawValue.Integer(77),
                        "forceOverrides" to overrides,
                    ),
            )
        val before = appearance.copy()
        val plugins =
            replace(
                document.shared.plugins,
                "externalThemeEnhancementsEnabled" to RawValue.BooleanValue(true),
                "externalThemeChromeTintEnabled" to RawValue.BooleanValue(true),
                "externalThemeAccentOverrideEnabled" to RawValue.BooleanValue(false),
            )
        val projected =
            StudioAccentProjection.chrome(
                appearance,
                plugins,
                AccentContext.Ayu(AyuVariant.MIRAGE),
                LicenseEntitlement.LICENSED,
            )
        assertEquals(50, projected.settings.intensity.percent)
        assertTrue(projected.settings.chromeStatusBar)
        assertFalse(projected.settings.chromeMainToolbar)
        assertTrue(projected.settings.chromeToolWindowStripe)
        assertFalse(projected.settings.chromeNavBar)
        assertTrue(projected.settings.chromePanelBorder)
        assertFalse(projected.settings.isToggleEnabled(AccentElementId.STATUS_BAR))
        assertTrue(projected.externalAllowed)
        assertEquals(overrides.values, projected.forceOverrides)
        assertEquals(before, appearance)
    }

    @Test
    fun `resolver projection preserves raw maps and cannot write back into Studio`() {
        val entries = mapping("/z/../project" to " invalid HEX ", "/a" to "", " Unknown-ID " to "#ABCDEF")
        val maps =
            listOf(
                "projectAccents",
                "languageAccents",
                "projectDisplayNames",
                "projectFallbackAccents",
                "forcedProjectLanguages",
            )
        val base = sessionDocument().shared.mappings
        val fields =
            replace(base, *maps.map { it to entries }.toTypedArray()).copy(
                extensions = StudioList(listOf(OpaqueRecord("future", StudioBytes(byteArrayOf(0, 1, 2))))),
            )
        val original = fields.copy()
        val projected = StudioAccentProjection.mappings(fields)
        val expected = entries.entries.associate { it.key to (it.value as RawValue.Text).value }
        assertEquals(expected, projected.projectAccents)
        assertEquals(expected, projected.languageAccents)
        assertEquals(expected, projected.projectDisplayNames)
        assertEquals(expected, projected.projectFallbackAccents)
        assertEquals(expected, projected.forcedProjectLanguages)
        assertFailsWith<UnsupportedOperationException> {
            (projected.projectAccents as MutableMap<String, String>)
                .clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (projected.languageAccents as MutableMap<String, String>)["replacement"] = "#111111"
        }
        assertEquals(original, fields)
        for (name in maps) {
            assertEquals(entries, fields.records.single { it.key.name == name }.value)
        }
    }

    @Test
    fun `blank forced entries and unknown language identities retain legacy resolution semantics`() {
        val fields =
            replace(
                sessionDocument().shared.mappings,
                "forcedProjectLanguages" to mapping("blank" to "  ", "future" to " Unknown-ID "),
                "languageAccents" to mapping("Unknown-ID" to " invalid HEX "),
                "languageFallbackAccent" to RawValue.Text(""),
            )
        val projected = StudioAccentProjection.mappings(fields)
        val view = PersistedAccentMappingsView { projected }
        assertTrue(view.hasForcedLanguageEntry("blank"))
        assertNull(view.forcedLanguageId("blank"))
        assertFalse(view.hasForcedLanguageEntry("absent"))
        assertEquals("Unknown-ID", view.forcedLanguageId("future"))
        assertEquals(" invalid HEX ", view.languageAccent("Unknown-ID"))
        assertNull(view.languageAccent("unknown-id"))
        assertNull(view.languageFallbackAccent)
        assertEquals(RawValue.Text(""), fields.records.single { it.key.name == "languageFallbackAccent" }.value)
    }

    @Test
    fun `policy input retains exact stored float bits before legacy interpretation`() {
        val values = listOf(-0.0f, Float.fromBits(0x7fc00011), Float.POSITIVE_INFINITY, -1.5f)
        val names = listOf("dominanceThreshold", "dominanceMarginRatio", "dominanceFloor", "tiebreakMinShare")
        val changes = names.zip(values).map { (name, number) -> name to RawValue.FloatBits(number.toRawBits()) }
        val fields = replace(sessionDocument().shared.mappings, *changes.toTypedArray())
        val projected = StudioAccentProjection.mappings(fields)
        val actual =
            listOf(
                projected.dominanceThreshold,
                projected.dominanceMarginRatio,
                projected.dominanceFloor,
                projected.tiebreakMinShare,
            )
        assertEquals(values.map { it.toRawBits() }, actual.map { it.toRawBits() })
    }

    private fun mapping(vararg entries: Pair<String, String>): RawValue.OrderedMap =
        RawValue.OrderedMap(StudioList(entries.map { (key, value) -> RawValue.Entry(key, RawValue.Text(value)) }))

    private fun replace(
        fields: StudioFields,
        vararg changes: Pair<String, RawValue>,
    ): StudioFields {
        val replacements = changes.toMap()
        return fields.copy(
            records = StudioList(fields.records.map { it.copy(value = replacements[it.key.name] ?: it.value) }),
        )
    }
}
