package dev.ayuislands.studio

import com.intellij.openapi.components.BaseState
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.LegacyValueType
import dev.ayuislands.studio.model.StudioDomain
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioFieldCatalogTest {
    @Test
    fun `catalog XML names match serialized ownership markers`() {
        val source =
            AyuIslandsState().apply {
                isCgpOwnershipMigrated = true
                isIrOwnershipMigrated = true
            }
        val names = XmlSerializer.serialize(source).getChildren("option").map { it.getAttributeValue("name") }
        val fields = StudioFieldCatalog.fields.filter { it.source == LegacyStore.SETTINGS }.associateBy { it.name }

        val ownershipMarkers =
            mapOf(
                "isCgpOwnershipMigrated" to "cgpOwnershipMigrated",
                "isIrOwnershipMigrated" to "irOwnershipMigrated",
            )
        for ((name, xmlName) in ownershipMarkers) {
            assertEquals(xmlName, fields.getValue(name).xmlName)
            assertContains(names, fields.getValue(name).xmlName)
        }
    }

    @Test
    fun `catalog types match actual legacy getter values`() {
        val beans = createLegacyBeans()
        for (field in StudioFieldCatalog.fields) {
            val bean = beans.getValue(field.source)
            val getterName =
                if (field.name.startsWith("is")) {
                    field.name
                } else {
                    "get${field.name.replaceFirstChar(Char::uppercaseChar)}"
                }
            val value = bean.javaClass.getMethod(getterName).invoke(bean)
            val expected =
                when (value) {
                    null, is String -> LegacyValueType.TEXT
                    is Boolean -> LegacyValueType.BOOLEAN
                    is Int -> LegacyValueType.INTEGER
                    is Long -> LegacyValueType.LONG
                    is Float -> LegacyValueType.FLOAT
                    is Map<*, *> -> LegacyValueType.STRING_MAP
                    is Set<*> -> LegacyValueType.STRING_SET
                    else -> error("Unexpected legacy value type for ${field.source}.${field.name}")
                }
            assertEquals(expected, field.type, "${field.source}.${field.name}")
        }
    }

    @Test
    fun `every persisted legacy field has exactly one migration owner`() {
        val expected =
            createLegacyBeans().flatMap { (store, bean) ->
                bean.javaClass.declaredFields
                    .filter { it.name.endsWith($$"$delegate") }
                    .map { store to it.name.removeSuffix($$"$delegate") }
            }
        val actual = StudioFieldCatalog.fields.map { it.source to it.name }

        assertEquals(
            mapOf(LegacyStore.SETTINGS to 177, LegacyStore.SYNTAX to 10, LegacyStore.MAPPINGS to 10),
            expected.groupingBy { it.first }.eachCount(),
        )
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
        for ((source, name, owner) in StudioFieldCatalog.fields.filter { it.owner in excludedOwners }) {
            assertNull(owner.domain, "$source.$name must stay outside appearance profiles")
        }
    }

    private fun createLegacyBeans(): Map<LegacyStore, BaseState> =
        mapOf(
            LegacyStore.SETTINGS to AyuIslandsState(),
            LegacyStore.SYNTAX to SyntaxIntensityBaseState(),
            LegacyStore.MAPPINGS to AccentMappingsState(),
        )

    private val excludedOwners =
        setOf(
            StudioFieldOwner.RUNTIME,
            StudioFieldOwner.RESOURCES,
            StudioFieldOwner.UI,
            StudioFieldOwner.PLUGINS,
            StudioFieldOwner.WORKSPACE,
            StudioFieldOwner.SYSTEM,
            StudioFieldOwner.MIGRATION,
            StudioFieldOwner.MAPPINGS,
        )
}
