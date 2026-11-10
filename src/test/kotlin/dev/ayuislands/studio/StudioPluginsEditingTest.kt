package dev.ayuislands.studio

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.indent.IndentPreset
import dev.ayuislands.settings.ExternalFeatures
import dev.ayuislands.settings.ExternalThemeSettings
import dev.ayuislands.settings.IgnorePreviewInput
import dev.ayuislands.settings.IntegrationSettings
import dev.ayuislands.settings.PluginTarget
import dev.ayuislands.settings.PluginsPanelEvent
import dev.ayuislands.settings.PluginsPreview
import dev.ayuislands.settings.PluginsPreviewInput
import dev.ayuislands.settings.PluginsSettings
import dev.ayuislands.settings.PluginsToggle
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioPluginsEditing
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxReadabilityOptions
import java.awt.Color
import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class StudioPluginsEditingTest {
    @Test
    fun `projection retains all fifteen preferences including unknown nullable and dormant values`() {
        val original = fields()
        val requested = StudioPluginsEditing.settings(original)

        assertEquals(
            PluginsSettings(
                external =
                    ExternalThemeSettings(
                        enabled = false,
                        overrideAccent = true,
                        features =
                            ExternalFeatures(
                                quickSwitcher = true,
                                glow = false,
                                codeGlance = true,
                                indentRainbow = false,
                                chromeTint = true,
                            ),
                        accentSource = null,
                        accent = "future-color",
                    ),
                integrations =
                    IntegrationSettings(
                        codeGlanceEnabled = false,
                        indentEnabled = false,
                        indentPreset = "FUTURE_INDENT",
                        indentAlpha = 217,
                        highlightErrors = true,
                        ignoreColorsEnabled = true,
                    ),
            ),
            requested,
        )
        assertEquals(fields(), original)
        val nullable =
            original.copy(
                records =
                    StudioList(
                        original.records.map { record ->
                            when (record.key.name) {
                                "externalThemeAccentSource" -> record.copy(value = text("FUTURE_SOURCE"))
                                "externalThemeAccent", "indentPresetName" -> record.copy(value = RawValue.Null)
                                else -> record
                            }
                        },
                    ),
            )
        val nullableSettings = StudioPluginsEditing.settings(nullable)
        assertEquals("FUTURE_SOURCE", nullableSettings.external.accentSource)
        assertEquals(null, nullableSettings.external.accent)
        assertEquals(null, nullableSettings.integrations.indentPreset)
        assertChanges(
            nullable,
            PluginsPanelEvent.Toggle(PluginsToggle.EXTERNAL_ENABLED, true),
            mapOf("externalThemeEnhancementsEnabled" to bool(true)),
        )
    }

    @Test
    fun `every boolean route changes only its shared field and preserves opaque records and ordering`() {
        val original = fields()
        val routes =
            listOf(
                PluginsToggle.EXTERNAL_ENABLED to "externalThemeEnhancementsEnabled",
                PluginsToggle.EXTERNAL_QUICK_SWITCHER to "externalThemeQuickSwitcherEnabled",
                PluginsToggle.EXTERNAL_GLOW to "externalThemeGlowEnabled",
                PluginsToggle.EXTERNAL_CODE_GLANCE to "externalThemeCodeGlanceProEnabled",
                PluginsToggle.EXTERNAL_INDENT_RAINBOW to "externalThemeIndentRainbowEnabled",
                PluginsToggle.EXTERNAL_CHROME_TINT to "externalThemeChromeTintEnabled",
                PluginsToggle.OVERRIDE_ACCENT to "externalThemeAccentOverrideEnabled",
                PluginsToggle.CODE_GLANCE to "cgpIntegrationEnabled",
                PluginsToggle.INDENT_RAINBOW to "irIntegrationEnabled",
                PluginsToggle.INDENT_ERRORS to "irErrorHighlightEnabled",
                PluginsToggle.IGNORE_COLORS to "ignorePluginSyntaxColorsEnabled",
            )
        for ((target, name) in routes) {
            val before = assertIs<RawValue.BooleanValue>(original.records.single { it.key.name == name }.value)
            assertChanges(original, PluginsPanelEvent.Toggle(target, !before.value), mapOf(name to bool(!before.value)))
        }
    }

    @Test
    fun `turning external support override and integrations off and on retains their granular preferences`() {
        val original = fields()
        val routes =
            listOf(
                PluginsToggle.EXTERNAL_ENABLED to "externalThemeEnhancementsEnabled",
                PluginsToggle.OVERRIDE_ACCENT to "externalThemeAccentOverrideEnabled",
                PluginsToggle.CODE_GLANCE to "cgpIntegrationEnabled",
                PluginsToggle.INDENT_RAINBOW to "irIntegrationEnabled",
            )
        for ((target, name) in routes) {
            val saved = assertIs<RawValue.BooleanValue>(original.records.single { it.key.name == name }.value).value
            val changed = assertChanges(original, PluginsPanelEvent.Toggle(target, !saved), mapOf(name to bool(!saved)))
            val restored = assertChanges(changed, PluginsPanelEvent.Toggle(target, saved), mapOf(name to bool(saved)))
            assertEquals(original.records.map { it.key to it.value }, restored.records.map { it.key to it.value })
            assertEquals(original.extensions, restored.extensions)
        }
    }

    @Test
    fun `source preset and alpha selections never normalize sibling raw preferences`() {
        val original = fields()
        assertChanges(
            original,
            PluginsPanelEvent.AccentSource(ExternalAccentSource.AUTOMATIC),
            mapOf("externalThemeAccentSource" to text("AUTOMATIC")),
        )
        for (preset in IndentPreset.entries) {
            assertChanges(original, PluginsPanelEvent.Preset(preset), mapOf("indentPresetName" to text(preset.name)))
        }
        for (alpha in listOf(13, 77, 153)) {
            assertChanges(original, PluginsPanelEvent.Alpha(alpha), mapOf("indentCustomAlpha" to number(alpha)))
        }
    }

    @Test
    fun `choosing an external accent selects Manual without enabling any gate or integration`() {
        val original = fields()
        val selected =
            assertChanges(
                original,
                PluginsPanelEvent.Accent("#12Ab34"),
                mapOf("externalThemeAccent" to text("#12Ab34"), "externalThemeAccentSource" to text("MANUAL")),
            )
        assertTrue(StudioPluginsEditing.edits(selected, PluginsPanelEvent.Accent("#12Ab34")).isEmpty())
        val automatic =
            assertChanges(
                selected,
                PluginsPanelEvent.AccentSource(ExternalAccentSource.AUTOMATIC),
                mapOf("externalThemeAccentSource" to text("AUTOMATIC")),
            )
        assertChanges(
            automatic,
            PluginsPanelEvent.Accent("#12Ab34"),
            mapOf("externalThemeAccentSource" to text("MANUAL")),
        )
    }

    @Test
    fun `same values and host actions preserve source lexemes without staging a field edit`() {
        val original = fields()
        val actions =
            listOf(
                PluginsPanelEvent.Unlock,
                PluginsPanelEvent.CustomAccent,
                PluginsPanelEvent.PluginPage(PluginTarget.CODE_GLANCE),
                PluginsPanelEvent.PluginPage(PluginTarget.INDENT_RAINBOW),
                PluginsPanelEvent.Toggle(PluginsToggle.IGNORE_COLORS, true),
                PluginsPanelEvent.Alpha(217),
            )
        for (event in actions) {
            assertTrue(StudioPluginsEditing.edits(original, event).isEmpty(), "$event must not stage an edit")
        }
        assertEquals(fields(), original)
    }

    @Test
    fun `Indent preview resolves null future and bounded Custom alpha without rewriting raw choices`() {
        val initial = StudioPluginsEditing.settings(fields())
        val cases =
            listOf(
                Triple(null, 217, 46),
                Triple("FUTURE_PRESET", 77, 77),
                Triple("CUSTOM", -8, 1),
                Triple("CUSTOM", 400, 255),
                Triple("NEON", 13, 77),
            )
        for ((preset, customAlpha, expectedAlpha) in cases) {
            val requested =
                initial.copy(
                    integrations = initial.integrations.copy(indentPreset = preset, indentAlpha = customAlpha),
                )
            val prepared = PluginsPreview.prepare(PluginsPreviewInput(requested, "#FFCC66", AyuVariant.MIRAGE, null))
            assertEquals(
                expectedAlpha,
                prepared.indentColors.firstOrNull()?.alpha,
                "Effective alpha for $preset/$customAlpha",
            )
            assertEquals(requested, prepared.settings, "Effective preview must preserve every raw preference")
        }
    }

    @Test
    fun `disabled Ignore samples retain canonical Light and Dark stock styles without aliases`() {
        val initial = StudioPluginsEditing.settings(fields())
        val requested = initial.copy(integrations = initial.integrations.copy(ignoreColorsEnabled = false))
        for (variant in listOf(AyuVariant.LIGHT, AyuVariant.DARK)) {
            val variantTag = variant.name.lowercase().replaceFirstChar(Char::uppercaseChar)
            val originals =
                SyntaxIntensityService.IGNORE_PLUGIN_KEY_NAMES.associate { name ->
                    TextAttributesKey.createTextAttributesKey(name) to
                        SyntaxIntensityService.ignorePluginStockAttributes(variantTag, name)
                }
            val saved = originals.mapValues { it.value.clone() }
            val request =
                SyntaxIntensityApplicator.Request(
                    SyntaxPreset.AMBIENT,
                    variantTag,
                    Color.WHITE,
                    baseline = emptyMap(),
                    overlay = emptyMap(),
                )
            val prepared =
                PluginsPreview.prepare(
                    PluginsPreviewInput(
                        requested,
                        variant.defaultAccent,
                        variant,
                        IgnorePreviewInput("# Stock ignore colors\n!keep", request, originals),
                    ),
                )
            val styles = assertNotNull(prepared.ignore).styles
            assertEquals(saved, styles)
            assertEquals(saved, originals)
            for ((key, original) in originals) {
                val detached = styles.getValue(key)
                assertNotSame(original, detached)
                assertEquals(original.foregroundColor, detached.foregroundColor)
                assertEquals(original.backgroundColor, detached.backgroundColor)
                assertEquals(original.effectColor, detached.effectColor)
                assertEquals(original.effectType, detached.effectType)
                assertEquals(original.fontType, detached.fontType)
                detached.foregroundColor = Color.MAGENTA
            }
            assertEquals(saved, originals, "Detached sample changes must not mutate canonical observations")
            assertEquals(
                saved,
                originals.mapValues { (key, _) ->
                    SyntaxIntensityService.ignorePluginStockAttributes(variantTag, key.externalName)
                },
            )
        }
    }

    @Test
    fun `Ignore preview computes supported colors and preserves exact disabled styles without input aliases`() {
        val key = TextAttributesKey.createTextAttributesKey("IGNORE.COMMENT")
        val baseline = TextAttributes(Color(0x80, 0x80, 0x80), null, null, null, Font.PLAIN)
        val stock =
            TextAttributes(
                Color(0x19, 0x92, 0x47),
                Color(0x20, 0x21, 0x22),
                Color.RED,
                EffectType.LINE_UNDERSCORE,
                Font.ITALIC,
            )
        val savedBaseline = baseline.clone()
        val savedStock = stock.clone()
        val requested = StudioPluginsEditing.settings(fields())
        val request =
            SyntaxIntensityApplicator.Request(
                preset = SyntaxPreset.AMBIENT,
                variantName = "Mirage",
                editorBg = Color(0x1F, 0x24, 0x30),
                baseline = mapOf(key to baseline),
                overlay = emptyMap(),
                readabilityOptions = SyntaxReadabilityOptions(dimComments = true),
            )
        val input =
            PluginsPreviewInput(
                requested,
                "#FFCC66",
                AyuVariant.MIRAGE,
                IgnorePreviewInput("# Native ignore patterns", request, mapOf(key to stock)),
            )

        val enabled = assertNotNull(PluginsPreview.prepare(input).ignore)
        val computed = assertNotNull(enabled.styles[key])
        assertNotEquals(
            baseline.foregroundColor,
            computed.foregroundColor,
            "Supported Ignore comments must be computed",
        )
        val computedColor = assertNotNull(computed.foregroundColor)
        val baselineColor = assertNotNull(baseline.foregroundColor)
        assertTrue(distance(computedColor, request.editorBg) < distance(baselineColor, request.editorBg))
        val disabled =
            assertNotNull(
                PluginsPreview
                    .prepare(
                        input.copy(
                            settings =
                                requested.copy(
                                    integrations = requested.integrations.copy(ignoreColorsEnabled = false),
                                ),
                        ),
                    ).ignore,
            )
        assertEquals(mapOf(key to savedStock), disabled.styles)
        assertNotSame(stock, disabled.styles.getValue(key))
        assertNotSame(baseline, computed)
        assertEquals(savedBaseline, baseline)
        assertEquals(savedStock, stock)
        stock.foregroundColor = Color.MAGENTA
        baseline.fontType = Font.BOLD
        assertEquals(savedStock, disabled.styles.getValue(key))
        assertEquals(Font.PLAIN, computed.fontType)
    }

    private fun assertChanges(
        original: StudioFields,
        event: PluginsPanelEvent,
        expected: Map<String, RawValue>,
    ): StudioFields {
        val changes =
            StudioPluginsEditing.edits(original, event).map { intent ->
                assertIs<SharedIntent.Field>(assertIs<SessionIntent.Shared>(intent).intent)
            }
        assertEquals(expected, changes.associate { it.after.key.name to it.after.value })
        assertEquals(expected.size, changes.size)
        for ((before, after) in changes) {
            assertEquals(original.records.single { it.key == before.key }, before)
            assertEquals(before.key, after.key)
            assertEquals(FieldOrigin(SourcePresence.VALUE), after.origin)
        }
        val actual =
            original.copy(
                records =
                    StudioList(
                        original.records.map { record ->
                            changes.singleOrNull { it.before.key == record.key }?.after
                                ?: record
                        },
                    ),
            )
        val wanted =
            original.copy(
                records =
                    StudioList(
                        original.records.map { record ->
                            expected[record.key.name]?.let {
                                record.copy(
                                    value = it,
                                    origin = FieldOrigin(SourcePresence.VALUE),
                                )
                            }
                                ?: record
                        },
                    ),
            )
        assertEquals(wanted, actual)
        return actual
    }

    private fun fields(): StudioFields {
        val values =
            linkedMapOf(
                "externalThemeEnhancementsEnabled" to bool(false),
                "externalThemeQuickSwitcherEnabled" to bool(true),
                "externalThemeGlowEnabled" to bool(false),
                "externalThemeCodeGlanceProEnabled" to bool(true),
                "externalThemeIndentRainbowEnabled" to bool(false),
                "externalThemeChromeTintEnabled" to bool(true),
                "externalThemeAccentOverrideEnabled" to bool(true),
                "cgpIntegrationEnabled" to bool(false),
                "irIntegrationEnabled" to bool(false),
                "irErrorHighlightEnabled" to bool(true),
                "ignorePluginSyntaxColorsEnabled" to bool(true),
                "externalThemeAccentSource" to RawValue.Null,
                "externalThemeAccent" to text("future-color"),
                "indentPresetName" to text("FUTURE_INDENT"),
                "indentCustomAlpha" to number(217),
                "futurePluginOrder" to
                    RawValue.OrderedMap(
                        StudioList(
                            listOf(RawValue.Entry("zeta", text("keep")), RawValue.Entry("alpha", RawValue.Null)),
                        ),
                    ),
            )
        return StudioFields(
            StudioList(
                values.map { (name, value) ->
                    StudioField(
                        LegacyFieldKey(LegacyStore.SETTINGS, name),
                        value,
                        FieldOrigin(
                            when {
                                name == "externalThemeGlowEnabled" -> SourcePresence.ABSENT
                                value == RawValue.Null -> SourcePresence.NULL
                                else -> SourcePresence.VALUE
                            },
                            StudioBytes("exact source for $name".encodeToByteArray()),
                        ),
                    )
                },
            ),
            StudioList(listOf(OpaqueRecord("future-plugins", StudioBytes(byteArrayOf(0, 7, -1, 4))))),
        )
    }

    private fun distance(
        first: Color,
        second: Color,
    ): Int =
        kotlin.math.abs(first.red - second.red) + kotlin.math.abs(first.green - second.green) +
            kotlin.math.abs(first.blue - second.blue)

    private fun text(value: String): RawValue = RawValue.Text(value)

    private fun bool(value: Boolean): RawValue = RawValue.BooleanValue(value)

    private fun number(value: Int): RawValue = RawValue.Integer(value)
}
