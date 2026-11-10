package dev.ayuislands.studio

import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.LegacyValueType
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDomain
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StudioFieldCatalogTest {
    @Test
    fun `legacy serializer uses JavaBean names for is-prefixed ownership markers`() {
        val source =
            AyuIslandsState().apply {
                isCgpOwnershipMigrated = true
                isIrOwnershipMigrated = true
            }
        val names = XmlSerializer.serialize(source).getChildren("option").map { it.getAttributeValue("name") }
        assertContains(names, "cgpOwnershipMigrated")
        assertContains(names, "irOwnershipMigrated")
    }

    @Test
    fun `appearance constructors cannot omit a domain field or swap domain payloads`() {
        val appearance = StudioFactoryProfiles.create(StudioFixtures.fields()).first().appearance
        val incomplete = StudioFields(StudioList(appearance.accent.records.dropLast(1)))
        assertFailsWith<IllegalArgumentException> { appearance.copy(accent = incomplete) }
        assertFailsWith<IllegalArgumentException> { appearance.copy(font = appearance.glow) }
    }

    @Test
    fun `catalog types match actual legacy getter values`() {
        val records = StudioFixtures.fields().records.associateBy { it.key }
        val types = StudioFieldCatalog.fields.associate { LegacyFieldKey(it.source, it.name) to it.type }
        for ((key, type) in types) {
            val expected =
                when (records.getValue(key).value) {
                    RawValue.Null, is RawValue.Text -> LegacyValueType.TEXT
                    is RawValue.BooleanValue -> LegacyValueType.BOOLEAN
                    is RawValue.Integer -> LegacyValueType.INTEGER
                    is RawValue.LongValue -> LegacyValueType.LONG
                    is RawValue.FloatBits -> LegacyValueType.FLOAT
                    is RawValue.OrderedMap -> LegacyValueType.STRING_MAP
                    is RawValue.OrderedSet -> LegacyValueType.STRING_SET
                    is RawValue.OrderedList, is RawValue.Bytes -> error("Unexpected legacy value type")
                }
            assertEquals(expected, type, key.toString())
        }
    }

    @Test
    fun `every persisted legacy field has exactly one migration owner`() {
        val sourceTypes =
            mapOf(
                LegacyStore.SETTINGS to AyuIslandsState::class.java,
                LegacyStore.SYNTAX to SyntaxIntensityBaseState::class.java,
                LegacyStore.MAPPINGS to AccentMappingsState::class.java,
            )
        val expected =
            sourceTypes.flatMap { (store, type) ->
                type.declaredFields
                    .filter { it.name.endsWith($$"$delegate") }
                    .map { store to it.name.removeSuffix($$"$delegate") }
            }
        val actual = StudioFieldCatalog.fields.map { it.source to it.name }

        assertEquals(197, expected.size)
        assertEquals(expected.toSet(), actual.toSet())
        assertEquals(expected.size, actual.size, "A duplicated owner could migrate the same preference twice")
    }

    @Test
    fun `appearance payload excludes runtime resources and shared preferences`() {
        val owners = StudioFieldCatalog.fields.associate { it.name to it.owner }

        assertEquals(StudioDomain.ACCENT, owners.getValue("followSystemAccent").domain)
        assertEquals(StudioDomain.ACCENT, owners.getValue("glowTabMode").domain)
        assertEquals(StudioDomain.GLOW, owners.getValue("glowFocusRing").domain)
        assertEquals(StudioFieldOwner.RUNTIME, owners.getValue("accentRotationPresetIndex"))
        assertEquals(StudioFieldOwner.RESOURCES, owners.getValue("installedFonts"))
        assertEquals(StudioFieldOwner.UI, owners.getValue("settingsSelectedTab"))
        for (name in listOf("projectAccents", "externalThemeAccent", "hideEditorVScrollbar", "syncEditorScheme")) {
            assertNull(owners.getValue(name).domain, "$name must not change when a profile is selected")
        }
    }
}
