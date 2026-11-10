package dev.ayuislands.studio

import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.session.StudioGlowSelectionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioGlowSelectionTest {
    @Test
    fun `ordinary Glow edits retain exact addresses including dormant style values`() {
        val before = sessionDocument()
        val changes = listOf("glowEnabled" to RawValue.BooleanValue(true), "sharpNeonWidth" to RawValue.Integer(19))
        for ((name, value) in changes) {
            val after = appearance(before, name, value)
            val selected = select(before, after)
            assertEquals(listOf(address(name)), selected.fields.toList())
            assertTrue(selected.dependencies.isEmpty())
        }
    }

    @Test
    fun `unchanged explicit reapply selects only its exact Glow field`() {
        val before = sessionDocument()
        val draft = sessionRecord(before).work.drafts.single()
        val requested = listOf(address("glowEnabled"), address("mirageAccent"), address("glowEnabled"))
        val reapplied = draft.copy(tracking = draft.tracking.copy(reapply = StudioList(requested)))

        val selected = select(before, before, reapplied)

        assertEquals(listOf(address("glowEnabled")), selected.fields.toList())
        assertEquals(listOf(GlowDependency.ACCENT), selected.dependencies.toList())
        assertEquals(StudioGlowSelection(), select(before, before))
    }

    @Test
    fun `full profile selects every canonical Glow field even when values match`() {
        val before = sessionDocument()
        val draft = sessionRecord(before).work.drafts.single()
        val profile = draft.copy(tracking = draft.tracking.copy(intent = DraftIntent.FULL_PROFILE))

        val selected = select(before, before, profile)

        val expected =
            before.currents.global.appearance.glow.records
                .map { FieldAddress(it.key) }
        assertEquals(expected, selected.fields.toList())
        assertEquals(listOf(GlowDependency.ACCENT), selected.dependencies.toList())
    }

    @Test
    fun `accent color requests no Glow shape intensity or width fields`() {
        val before = sessionDocument()
        val after = appearance(before, "mirageAccent", RawValue.Text("#123456"))
        val selected = select(before, after)
        assertTrue(selected.fields.isEmpty())
        assertEquals(listOf(GlowDependency.ACCENT), selected.dependencies.toList())
        assertEquals(
            StudioGlowSelection(),
            select(before, appearance(before, "chromeTintIntensity", RawValue.Integer(43))),
        )
    }

    @Test
    fun `forced system accent gates profile preference without rewriting it`() {
        val base = sessionDocument()
        val before = base.copy(shared = base.shared.copy(systemAccentPolicy = SystemAccentPolicy.FORCE_OFF))
        val after = appearance(before, "followSystemAccent", RawValue.BooleanValue(true))
        assertEquals(StudioGlowSelection(), select(before, after))
        val following = after.copy(shared = after.shared.copy(systemAccentPolicy = SystemAccentPolicy.BY_PROFILE))
        assertEquals(listOf(GlowDependency.ACCENT), select(after, following).dependencies.toList())
        val preference =
            following.currents.global.appearance.accent
                .value("followSystemAccent")
        assertEquals(RawValue.BooleanValue(true), preference)
    }

    @Test
    fun `external Glow availability compares the compound gate`() {
        val before = sessionDocument()
        val enabled = plugins(before, "externalThemeEnhancementsEnabled", RawValue.BooleanValue(true))
        assertEquals(StudioGlowSelection(), select(before, enabled))
        val available = plugins(enabled, "externalThemeGlowEnabled", RawValue.BooleanValue(true))
        assertEquals(listOf(GlowDependency.AVAILABILITY), select(enabled, available).dependencies.toList())
        val disabled = plugins(available, "externalThemeEnhancementsEnabled", RawValue.BooleanValue(false))
        assertEquals(listOf(GlowDependency.AVAILABILITY), select(available, disabled).dependencies.toList())
    }

    @Test
    fun `external color inputs request color but unrelated integrations do not`() {
        val before = sessionDocument()
        val changed = plugins(before, "externalThemeAccent", RawValue.Text("#123456"))
        assertEquals(listOf(GlowDependency.ACCENT), select(before, changed).dependencies.toList())
        val integration = plugins(before, "cgpIntegrationEnabled", RawValue.BooleanValue(true))
        assertEquals(StudioGlowSelection(), select(before, integration))
    }

    @Test
    fun `system appearance enable requests color and membership but other system edits do not`() {
        val before = sessionDocument()
        val enabled = system(before, "followSystemAppearance", RawValue.BooleanValue(true))
        assertEquals(GlowDependency.entries.toList(), select(before, enabled).dependencies.toList())
        assertEquals(StudioGlowSelection(), select(enabled, before))
        for ((name, value) in listOf(
            "quickSwitcherWidgetEnabled" to RawValue.BooleanValue(false),
            "syncEditorScheme" to RawValue.BooleanValue(false),
            "lastDarkAppearanceTheme" to RawValue.Text("Ayu Dark"),
        )) {
            assertEquals(StudioGlowSelection(), select(before, system(before, name, value)), name)
        }
    }

    @Test
    fun `mapping display names do not request native Glow but resolution inputs do`() {
        val before = sessionDocument()
        val entries = RawValue.OrderedMap(StudioList(listOf(RawValue.Entry("/project", RawValue.Text("#123456")))))
        val names = mappings(before, "projectDisplayNames", entries)
        val colors = mappings(before, "projectAccents", entries)
        assertEquals(StudioGlowSelection(), select(before, names))
        assertEquals(listOf(GlowDependency.ACCENT), select(before, colors).dependencies.toList())
    }

    @Test
    fun `local rotation enable and enabled parameter changes request the dependent color`() {
        val before = sessionDocument()
        val dormant = appearance(before, "accentRotationIntervalHours", RawValue.Integer(9))
        assertEquals(StudioGlowSelection(), select(before, dormant))
        val enabled = appearance(dormant, "accentRotationEnabled", RawValue.BooleanValue(true))
        assertEquals(listOf(GlowDependency.ACCENT), select(dormant, enabled).dependencies.toList())
        val changed = appearance(enabled, "accentRotationIntervalHours", RawValue.Integer(11))
        assertEquals(listOf(GlowDependency.ACCENT), select(enabled, changed).dependencies.toList())
        assertEquals(StudioGlowSelection(), select(enabled, dormant))
        val draft = sessionRecord(enabled).work.drafts.single()
        val requested = StudioList(listOf(address("accentRotationEnabled")))
        val reapplied = draft.copy(tracking = draft.tracking.copy(reapply = requested))
        assertEquals(StudioGlowSelection(), select(enabled, enabled, reapplied))
    }

    @Test
    fun `language classification follows key sets and effective policy rather than color values or order`() {
        val initial = sessionDocument()
        val entries =
            listOf(RawValue.Entry("Kotlin", RawValue.Text("#123456")), RawValue.Entry("unknown", RawValue.Text("")))
        val before = mappings(initial, "languageAccents", RawValue.OrderedMap(StudioList(entries)))
        val recolored = entries.map { it.copy(value = RawValue.Text("#ABCDEF")) }
        for (records in listOf(entries.reversed(), recolored)) {
            val after = mappings(before, "languageAccents", RawValue.OrderedMap(StudioList(records)))
            val selected = select(before, after)
            assertEquals(listOf(GlowDependency.ACCENT), selected.dependencies.toList())
            assertFalse(selected.shouldReclassifyLanguage)
        }
        assertTrue(select(initial, before).shouldReclassifyLanguage)
        assertTrue(select(before, initial).shouldReclassifyLanguage)
        val strict = mappings(before, "dominanceThreshold", RawValue.FloatBits(0.95f.toRawBits()))
        assertTrue(select(before, strict).shouldReclassifyLanguage)
    }

    @Test
    fun `raw invalid policy changes with the same native interpretation do not reclassify`() {
        val initial = sessionDocument()
        val before = mappings(initial, "dominanceThreshold", RawValue.FloatBits(Float.NaN.toRawBits()))
        val after = mappings(before, "dominanceThreshold", RawValue.FloatBits(Float.POSITIVE_INFINITY.toRawBits()))
        assertEquals(listOf(GlowDependency.ACCENT), select(before, after).dependencies.toList())
        assertFalse(select(before, after).shouldReclassifyLanguage)
    }

    @Test
    fun `project forced and fallback edits do not request a fresh language classification`() {
        val before = sessionDocument()
        val entry = RawValue.OrderedMap(StudioList(listOf(RawValue.Entry("/project", RawValue.Text("  ")))))
        for (name in listOf("projectAccents", "forcedProjectLanguages", "projectFallbackAccents")) {
            assertFalse(select(before, mappings(before, name, entry)).shouldReclassifyLanguage)
        }
        val fallback = mappings(before, "languageFallbackAccent", RawValue.Text("#123456"))
        assertFalse(select(before, fallback).shouldReclassifyLanguage)
        val draft = sessionRecord(before).work.drafts.single()
        val profile = draft.copy(tracking = draft.tracking.copy(intent = DraftIntent.FULL_PROFILE))
        assertFalse(select(before, before, profile).shouldReclassifyLanguage)
    }

    private fun select(
        before: StudioDocument,
        after: StudioDocument,
        draft: DraftRecord = sessionRecord(before).work.drafts.single(),
    ): StudioGlowSelection = StudioGlowSelectionPolicy.select(before, after, draft, StudioTarget.Global)

    private fun address(name: String): FieldAddress = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, name))

    private fun appearance(
        document: StudioDocument,
        name: String,
        value: RawValue,
    ): StudioDocument {
        val current = document.currents.global
        val field = StudioAppearanceFields.field(current.appearance, address(name).field)
        val changed = StudioAppearanceFields.replace(current.appearance, field.copy(value = value))
        return document.copy(currents = document.currents.copy(global = current.copy(appearance = changed)))
    }

    private fun plugins(
        document: StudioDocument,
        name: String,
        value: RawValue,
    ): StudioDocument =
        document.copy(shared = document.shared.copy(plugins = replace(document.shared.plugins, name, value)))

    private fun system(
        document: StudioDocument,
        name: String,
        value: RawValue,
    ): StudioDocument =
        document.copy(shared = document.shared.copy(system = replace(document.shared.system, name, value)))

    private fun mappings(
        document: StudioDocument,
        name: String,
        value: RawValue,
    ): StudioDocument =
        document.copy(shared = document.shared.copy(mappings = replace(document.shared.mappings, name, value)))

    private fun replace(
        fields: StudioFields,
        name: String,
        value: RawValue,
    ): StudioFields {
        require(fields.records.any { it.key.name == name })
        val records = fields.records.map { if (it.key.name == name) it.copy(value = value) else it }
        return fields.copy(records = StudioList(records))
    }
}
