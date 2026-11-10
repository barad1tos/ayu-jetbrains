package dev.ayuislands.studio

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioAcceptedInput
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.session.StudioAccentResolver
import dev.ayuislands.studio.session.StudioEffectInput
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StudioAccentResolverTest {
    private val projectPath = File(System.getProperty("java.io.tmpdir"), "studio-accent").canonicalPath
    private val project = createProject(projectPath)
    private val base = accent(sessionDocument(), "mirageAccent" to RawValue.Text("#112233"))

    @BeforeTest
    fun setUp() {
        mockkObject(AyuIslandsSettings.Companion, AccentMappingsSettings.Companion, ProjectLanguageDetector)
        every { AyuIslandsSettings.getInstance() } throws IllegalStateException("Unexpected legacy settings access")
        every { AccentMappingsSettings.getInstance() } throws IllegalStateException("Unexpected legacy mappings access")
        every { ProjectLanguageDetector.verdict(any()) } returns ProjectLanguageVerdict.Cold
        every { ProjectLanguageDetector.verdict(any(), true) } throws IllegalStateException("Unexpected cache warmup")
        every { ProjectLanguageDetector.dominant(any()) } throws IllegalStateException("Unexpected language scan")
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `actual variant selects candidate values preserving null and blank legacy semantics`() {
        val candidate =
            accent(
                base,
                "darkAccent" to RawValue.Text(""),
                "lightAccent" to RawValue.Null,
                "followSystemAccent" to RawValue.BooleanValue(false),
            )
        val expected = mapOf(AyuVariant.MIRAGE to "#112233", AyuVariant.DARK to "", AyuVariant.LIGHT to "#F29718")
        for ((variant, hex) in expected) {
            val resolver = resolver(candidate, variant)
            assertEquals(AccentContext.Ayu(variant), resolver.context)
            assertEquals(hex, assertNotNull(resolver.resolve(null)).hex)
        }
        val rawAccent = candidate.currents.global.appearance.accent
        assertEquals(RawValue.Text(""), rawAccent.value("darkAccent"))
        assertEquals(RawValue.Null, rawAccent.value("lightAccent"))
    }

    @Test
    fun `global system policy gates profile choice while project pin remains first`() {
        val following = accent(base, "followSystemAccent" to RawValue.BooleanValue(true))
        val cases =
            listOf(
                Triple(SystemAccentPolicy.BY_PROFILE, true, "#445566"),
                Triple(SystemAccentPolicy.BY_PROFILE, false, "#112233"),
                Triple(SystemAccentPolicy.FORCE_ON, false, "#445566"),
                Triple(SystemAccentPolicy.FORCE_OFF, true, "#112233"),
            )
        for ((policy, follows, expected) in cases) {
            val choices = accent(following, "followSystemAccent" to RawValue.BooleanValue(follows))
            val candidate = choices.copy(shared = choices.shared.copy(systemAccentPolicy = policy))
            assertEquals(expected, assertNotNull(resolver(candidate, systemAccent = "#445566").resolve(project)).hex)
            assertEquals("#112233", assertNotNull(resolver(candidate).resolve(project)).hex)
            val pinned = mappings(candidate, "projectAccents" to mapping(projectPath to "#778899"))
            val resolved = assertNotNull(resolver(pinned, systemAccent = "#445566").resolve(project))
            assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, resolved.source)
            assertEquals("#778899", resolved.hex)
        }
    }

    @Test
    fun `external candidate opt in and manual choice never consult old global preferences`() {
        val disabled = plugins(base, "externalThemeEnhancementsEnabled" to RawValue.BooleanValue(false))
        assertNull(resolver(disabled, variant = null).context)
        assertNull(resolver(disabled, variant = null).resolve(project))
        val manual =
            plugins(
                disabled,
                "externalThemeEnhancementsEnabled" to RawValue.BooleanValue(true),
                "externalThemeAccentSource" to RawValue.Text("MANUAL"),
                "externalThemeAccent" to RawValue.Text("#778899"),
            )
        val pinned = mappings(manual, "projectAccents" to mapping(projectPath to "#112233"))
        AccentResolver.withUiColorProviderForTest({ error("Manual color must not read native colors") }) {
            val resolver = resolver(pinned, variant = null, systemAccent = "#AABBCC")
            assertEquals(AccentContext.External, resolver.context)
            val result = assertNotNull(resolver.resolve(project))
            assertEquals(AccentResolver.Source.EXTERNAL_ACCENT, result.source)
            assertEquals("#778899", result.hex)
        }
        val automatic = plugins(manual, "externalThemeAccentSource" to RawValue.Text("future-source"))
        AccentResolver.withUiColorProviderForTest({ Color(0xAABBCC) }) {
            val result = assertNotNull(resolver(automatic, variant = null).resolve(project))
            assertEquals(AccentResolver.Source.MATERIAL_THEME, result.source)
            assertEquals("#AABBCC", result.hex)
        }
        assertEquals(RawValue.Text("future-source"), automatic.shared.plugins.value("externalThemeAccentSource"))
    }

    @Test
    fun `cached and candidate classification remain isolated per project without warming`() {
        val second = createProject("$projectPath/second")
        val candidate =
            mappings(
                base,
                "languageAccents" to mapping("kotlin" to "#445566", "java" to "#778899"),
                "projectFallbackAccents" to mapping(projectPath to "#AABBCC"),
            )
        every { ProjectLanguageDetector.verdict(project) } returns ProjectLanguageVerdict.Detected("java", null)
        every { ProjectLanguageDetector.verdict(project, any(), any()) } returns
            ProjectLanguageVerdict.Detected("kotlin", null)
        every { ProjectLanguageDetector.verdict(second, any(), any()) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("other" to 10L))
        assertEquals("#778899", assertNotNull(resolver(candidate).resolve(project)).hex)
        val reclassified = resolver(candidate, reclassify = true)
        assertEquals("#445566", assertNotNull(reclassified.resolve(project)).hex)
        assertEquals("#112233", assertNotNull(reclassified.resolve(second)).hex)
        verify(exactly = 1) { ProjectLanguageDetector.verdict(project, any(), any()) }
        verify(exactly = 1) { ProjectLanguageDetector.verdict(second, any(), any()) }
        verify(exactly = 0) { ProjectLanguageDetector.verdict(any(), true) }
        verify(exactly = 0) { ProjectLanguageDetector.dominant(any()) }
    }

    @Test
    fun `observed entitlement gates overrides and restores the surrounding native license scope`() {
        val candidate = mappings(base, "projectAccents" to mapping(projectPath to "#445566"))
        LicenseChecker.withConfirmedEntitlement(LicenseEntitlement.UNLICENSED) {
            assertEquals("#445566", assertNotNull(resolver(candidate).resolve(project)).hex)
            assertFalse(LicenseChecker.isLicensedOrGrace())
        }
        val free = resolver(candidate, entitlement = LicenseEntitlement.UNLICENSED)
        assertEquals(AccentResolver.Source.GLOBAL, assertNotNull(free.resolve(project)).source)
        assertEquals("#112233", assertNotNull(free.resolve(project)).hex)
        verify(exactly = 0) { ProjectLanguageDetector.verdict(any()) }
        assertFailsWith<IllegalArgumentException> { resolver(candidate, entitlement = LicenseEntitlement.UNKNOWN) }
    }

    @Test
    fun `invalid native colors stay raw while external resolution uses existing strict fallback`() {
        val candidate = mappings(base, "projectAccents" to mapping(projectPath to " invalid HEX "))
        val before = candidate.copy()
        assertEquals(" invalid HEX ", assertNotNull(resolver(candidate).resolve(project)).hex)
        val external =
            plugins(
                candidate,
                "externalThemeEnhancementsEnabled" to RawValue.BooleanValue(true),
                "externalThemeAccentSource" to RawValue.Text("MANUAL"),
                "externalThemeAccent" to RawValue.Text(" invalid HEX "),
            )
        assertEquals("#FFCC66", assertNotNull(resolver(external, variant = null).resolve(project)).hex)
        assertEquals(before, candidate)
        assertEquals(RawValue.Text(" invalid HEX "), external.shared.plugins.value("externalThemeAccent"))
    }

    @Test
    fun `native census preserves project order and resolves focused language once`() {
        val second = createProject("$projectPath/second")
        val candidate =
            mappings(
                base,
                "languageAccents" to mapping("kotlin" to "#445566", "java" to "#778899"),
            )
        every { ProjectLanguageDetector.verdict(project) } returns ProjectLanguageVerdict.Detected("kotlin", null)
        every { ProjectLanguageDetector.verdict(second) } returns ProjectLanguageVerdict.Detected("java", null)

        val result = resolver(candidate).resolve(project, listOf(second, project))

        assertEquals(listOf(second, project), result.projects.map { it.project })
        assertEquals(listOf("#778899", "#445566"), result.projects.map { it.accent.hex })
        assertEquals(result.projects[1].accent, result.focused)
        assertEquals(result.projects[1].dependencies, result.dependencies)
        verify(exactly = 1) { ProjectLanguageDetector.verdict(project) }
        verify(exactly = 1) { ProjectLanguageDetector.verdict(second) }
    }

    @Test
    fun `project pin records only its exact accepted entry without reading lower priority sources`() {
        val candidate =
            mappings(
                base,
                "projectAccents" to mapping("another-project" to "#778899", projectPath to "#445566"),
                "languageAccents" to mapping("kotlin" to "#AABBCC"),
            )
        val result = resolver(candidate).resolve(project, listOf(project))
        val expected =
            StudioAcceptedInput(
                mappingAddress(candidate, "projectAccents", projectPath),
                FieldValue.Present(RawValue.Text("#445566")),
            )

        assertEquals(listOf(expected), result.dependencies.toList())
        assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, result.focused?.source)
        verify(exactly = 0) { ProjectLanguageDetector.verdict(any()) }
        assertEquals(
            mapping("another-project" to "#778899", projectPath to "#445566"),
            candidate.shared.mappings.value("projectAccents"),
        )
    }

    @Test
    fun `language result retains absent higher priority entries and exact matching language without whole maps`() {
        val candidate =
            mappings(
                base,
                "projectAccents" to mapping("another-project" to "#112233"),
                "languageAccents" to mapping("java" to "#778899", "kotlin" to "#445566"),
            )
        every { ProjectLanguageDetector.verdict(project) } returns ProjectLanguageVerdict.Detected("kotlin", null)
        val result = resolver(candidate).resolve(project, listOf(project))
        val dependencies = result.dependencies.associate { it.address to it.value }

        assertEquals(FieldValue.Absent, dependencies[mappingAddress(candidate, "projectAccents", projectPath)])
        assertEquals(FieldValue.Absent, dependencies[mappingAddress(candidate, "forcedProjectLanguages", projectPath)])
        assertEquals(
            FieldValue.Present(RawValue.Text("#445566")),
            dependencies[mappingAddress(candidate, "languageAccents", "kotlin")],
        )
        assertFalse(dependencies.containsKey(mappingAddress(candidate, "projectAccents", "another-project")))
        assertFalse(dependencies.containsKey(mappingAddress(candidate, "languageAccents", "java")))
        assertFalse(dependencies.keys.any { it.field.name.endsWith("Accents") && it.part == FieldPart.Value })
    }

    @Test
    fun `forced language dependency retains exact raw entry and never reconstructs source from equal colors`() {
        val candidate =
            mappings(
                base,
                "forcedProjectLanguages" to mapping(projectPath to " kotlin "),
                "languageAccents" to mapping("java" to "#445566", "kotlin" to "#445566"),
            )
        val result = resolver(candidate).resolve(project, listOf(project))
        val dependencies = result.dependencies.associate { it.address to it.value }

        assertEquals(AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE, result.focused?.source)
        assertEquals(
            FieldValue.Present(RawValue.Text(" kotlin ")),
            dependencies[mappingAddress(candidate, "forcedProjectLanguages", projectPath)],
        )
        assertEquals(
            FieldValue.Present(RawValue.Text("#445566")),
            dependencies[mappingAddress(candidate, "languageAccents", "kotlin")],
        )
        assertFalse(dependencies.containsKey(mappingAddress(candidate, "languageAccents", "java")))
        verify(exactly = 0) { ProjectLanguageDetector.verdict(any()) }
    }

    @Test
    fun `inactive native census does not fabricate project colors or read mappings`() {
        val disabled = plugins(base, "externalThemeEnhancementsEnabled" to RawValue.BooleanValue(false))
        val result = resolver(disabled, variant = null).resolve(project, listOf(project))

        assertNull(result.context)
        assertNull(result.focused)
        assertEquals(project, result.focusedProject)
        assertEquals(emptyList(), result.projects.toList())
        assertEquals(emptyList(), result.dependencies.toList())
        verify(exactly = 0) { ProjectLanguageDetector.verdict(any()) }
    }

    private fun mappingAddress(
        document: StudioDocument,
        name: String,
        entry: String,
    ): FieldAddress =
        FieldAddress(
            document.shared.mappings.records
                .single { it.key.name == name }
                .key,
            FieldPart.MapEntry(entry),
        )

    private fun resolver(
        document: StudioDocument,
        variant: AyuVariant? = AyuVariant.MIRAGE,
        systemAccent: String? = null,
        entitlement: LicenseEntitlement = LicenseEntitlement.LICENSED,
        reclassify: Boolean = false,
    ): StudioAccentResolver =
        StudioAccentResolver(
            StudioEffectInput(
                document,
                StudioTarget.Global,
                StudioList(emptyList()),
                StudioGlowSelection(shouldReclassifyLanguage = reclassify),
            ),
            variant,
            systemAccent,
            entitlement,
        )

    private fun createProject(path: String): Project =
        mockk {
            every { basePath } returns path
            every { name } returns "candidate-project"
            every { isDefault } returns false
            every { isDisposed } returns false
        }

    private fun accent(
        document: StudioDocument,
        vararg changes: Pair<String, RawValue>,
    ): StudioDocument {
        val current = document.currents.global
        val appearance = current.appearance.copy(accent = replace(current.appearance.accent, *changes))
        return document.copy(currents = document.currents.copy(global = current.copy(appearance = appearance)))
    }

    private fun plugins(
        document: StudioDocument,
        vararg changes: Pair<String, RawValue>,
    ): StudioDocument =
        document.copy(shared = document.shared.copy(plugins = replace(document.shared.plugins, *changes)))

    private fun mappings(
        document: StudioDocument,
        vararg changes: Pair<String, RawValue>,
    ): StudioDocument =
        document.copy(shared = document.shared.copy(mappings = replace(document.shared.mappings, *changes)))

    private fun replace(
        fields: StudioFields,
        vararg changes: Pair<String, RawValue>,
    ): StudioFields {
        val replacements = changes.toMap()
        val records = fields.records.map { it.copy(value = replacements[it.key.name] ?: it.value) }
        return fields.copy(records = StudioList(records))
    }

    private fun mapping(vararg entries: Pair<String, String>): RawValue.OrderedMap =
        RawValue.OrderedMap(StudioList(entries.map { (key, value) -> RawValue.Entry(key, RawValue.Text(value)) }))
}
