package dev.ayuislands.settings.mappings

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.State
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.AccentMappingsView
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StoreDataException
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.legacyFileNames
import dev.ayuislands.studio.persistence.openStudioFiles
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Legacy macro expansion happens at cold import; the facade only reads accepted Studio mappings. */
class AccentMappingsSettingsTest {
    @TempDir
    lateinit var directory: Path
    private val userHome = "/Users/alice"

    @Test
    fun `legacy import expands project accent macro keys without rewriting their source`() {
        val view = importMappings(mapping("projectAccents", $$"$USER_HOME$/dev/foo", "#FFCD66"))

        assertNull(view.projectAccents[$$"$USER_HOME$/dev/foo"])
        assertEquals("#FFCD66", view.projectAccents["/Users/alice/dev/foo"])
    }

    @Test
    fun `legacy import expands project display name macro keys`() {
        val view = importMappings(mapping("projectDisplayNames", $$"$USER_HOME$/dev/foo", "Foo"))

        assertEquals("Foo", view.projectDisplayNames["/Users/alice/dev/foo"])
        assertNull(view.projectDisplayNames[$$"$USER_HOME$/dev/foo"])
    }

    @Test
    fun `legacy import expands project fallback accent macro keys`() {
        val view = importMappings(mapping("projectFallbackAccents", $$"$USER_HOME$/dev/foo", "#5CCFE6"))

        assertNull(view.projectFallbackAccents[$$"$USER_HOME$/dev/foo"])
        assertEquals("#5CCFE6", view.projectFallbackAccents["/Users/alice/dev/foo"])
    }

    @Test
    fun `legacy import expands forced project language keys while keeping language ids verbatim`() {
        val view = importMappings(mapping("forcedProjectLanguages", $$"$USER_HOME$/dev/foo", "  TypeScript  "))

        assertNull(view.forcedProjectLanguages[$$"$USER_HOME$/dev/foo"])
        assertEquals("  TypeScript  ", view.forcedProjectLanguages["/Users/alice/dev/foo"])
    }

    @Test
    fun `absolute paths and language accent keys remain unchanged`() {
        val view =
            importMappings(
                mapping("projectAccents", "/tmp/already-absolute", "#FFCD66") +
                    mapping("projectDisplayNames", "/tmp/already-absolute", "AbsoluteProj") +
                    mapping("languageAccents", $$"$USER_HOME$/fake-language", "#D5FF80"),
            )

        assertEquals(mapOf("/tmp/already-absolute" to "#FFCD66"), view.projectAccents)
        assertEquals(mapOf("/tmp/already-absolute" to "AbsoluteProj"), view.projectDisplayNames)
        assertEquals(mapOf($$"$USER_HOME$/fake-language" to "#D5FF80"), view.languageAccents)
    }

    @Test
    fun `missing home blocks each project mapping migration without modifying the original file`() {
        for (field in projectMapNames) {
            val failure =
                assertFailsWith<StoreDataException>(field) {
                    importMappings(mapping(field, $$"$USER_HOME$/dev/foo", "untouched"), home = "")
                }
            assertEquals(StoreIssue.CONFLICT, failure.issue, field)
        }
    }

    @Test
    fun `missing home does not block absolute paths or macro-like language ids`() {
        val view =
            importMappings(
                mapping("projectAccents", "/tmp/project", "unknown-color") +
                    mapping("languageAccents", $$"$USER_HOME$/language", "future-color"),
                home = "",
            )

        assertEquals("unknown-color", view.projectAccents["/tmp/project"])
        assertEquals("future-color", view.languageAccents[$$"$USER_HOME$/language"])
    }

    @Test
    fun `macro collisions in any project map reject the whole import without partial rewrite`() {
        for (field in projectMapNames) {
            val conflicting =
                "<option name='$field'><map>" +
                    "<entry key='$userHome/project' value='first'/>" +
                    $$"<entry key='$USER_HOME$/project' value='second'/>" +
                    "</map></option>"
            val failure = assertFailsWith<StoreDataException>(field) { importMappings(conflicting) }
            assertEquals(StoreIssue.CONFLICT, failure.issue, field)
        }
    }

    @Test
    fun `repeated import and Studio reload preserve expanded mappings and ordered unknown values`() {
        val xml =
            "<option name='projectAccents'><map>" +
                $$"<entry key='$USER_HOME$/z-project' value='unknown-color'/>" +
                "<entry key='/tmp/a-project' value='  #5CCFE6  '/>" +
                "</map></option><option name='languageFallbackAccent'/>"
        val first = importMappings(xml)
        val second = importMappings(xml)

        assertEquals(first.projectAccents, second.projectAccents)
        assertEquals(listOf("$userHome/z-project", "/tmp/a-project"), second.projectAccents.keys.toList())
        assertEquals(listOf("unknown-color", "  #5CCFE6  "), second.projectAccents.values.toList())
        assertNull(second.languageFallbackAccent)
    }

    @Test
    fun `mapping policy and dormant choices survive legacy XML and Studio persistence`() {
        val state =
            AccentMappingsState().apply {
                projectAccents["/closed-project"] = "future-color"
                projectDisplayNames["/closed-project"] = "Renamed project"
                forcedProjectLanguages["/closed-project"] = "  FutureLanguage  "
                projectFallbackAccents["/closed-project"] = "invalid-fallback"
                languageAccents["FutureLanguage"] = "unknown-language-color"
                languageFallbackAccent = " untouched "
                dominanceThreshold = -0.5f
                dominanceMarginRatio = 17f
                dominanceFloor = 0.001f
                tiebreakMinShare = 0.9f
            }
        val view = StudioFixtures.persistedView(AyuIslandsState(), mappings = state).mappings

        assertEquals(state.projectAccents, view.projectAccents)
        assertEquals(state.projectDisplayNames, view.projectDisplayNames)
        assertEquals(state.forcedProjectLanguages, view.forcedProjectLanguages)
        assertEquals(state.projectFallbackAccents, view.projectFallbackAccents)
        assertEquals(state.languageAccents, view.languageAccents)
        assertEquals(state.languageFallbackAccent, view.languageFallbackAccent)
        assertEquals(state.dominanceThreshold, view.dominanceThreshold)
        assertEquals(state.dominanceMarginRatio, view.dominanceMarginRatio)
        assertEquals(state.dominanceFloor, view.dominanceFloor)
        assertEquals(state.tiebreakMinShare, view.tiebreakMinShare)
        assertEquals(state.languageResolutionPolicy(), view.languageResolutionPolicy())
    }

    @Test
    fun `facade exposes only accepted mappings and has no separate persistence authority`() {
        val studio = mockk<StudioService>()
        mockkObject(StudioService.Companion)
        try {
            every { StudioService.getInstance() } returns studio
            every { studio.acceptedView } returns null
            val facade = AccentMappingsSettings()
            assertNull(facade.state)
            assertNull(AccentMappingsSettings::class.java.getAnnotation(State::class.java))

            val accepted =
                StudioFixtures.legacyView(
                    AyuIslandsState(),
                    mappings = AccentMappingsState().apply { projectAccents["/project"] = "#112233" },
                )
            every { studio.acceptedView } returns accepted
            assertSame(accepted.mappings, facade.state)
        } finally {
            unmockkObject(StudioService.Companion)
        }
    }

    @Test
    fun `getInstance resolves the registered readonly facade`() {
        val application = mockk<Application>()
        val expectedService = AccentMappingsSettings()
        mockkStatic(ApplicationManager::class)
        try {
            every { ApplicationManager.getApplication() } returns application
            every { application.getService(AccentMappingsSettings::class.java) } returns expectedService
            val actual = AccentMappingsSettings.getInstance()
            assertSame(expectedService, actual)
        } finally {
            unmockkStatic(ApplicationManager::class)
        }
    }

    private fun importMappings(
        options: String,
        home: String = userHome,
    ): AccentMappingsView {
        val optionsDirectory = Files.createDirectories(directory.resolve("options"))
        val source = optionsDirectory.resolve(legacyFileNames.getValue(LegacyStore.MAPPINGS))
        val bytes =
            "<application><component name='AyuIslandsAccentMappings'>$options</component></application>"
                .encodeToByteArray()
        Files.write(source, bytes)
        val storeDirectory = directory.resolve("studio")
        try {
            StudioAppearanceStore(openStudioFiles(storeDirectory)).use { store ->
                assertEquals(StoreLoad.Empty, store.load())
                var nextId = 0
                val snapshot = StudioLegacyCapture(optionsDirectory, store, { "mappings-${++nextId}" }, home).capture()
                val archived =
                    snapshot.source.archives
                        .single { it.store == LegacyStore.MAPPINGS }
                        .file
                assertContentEquals(bytes, assertIs<LegacyFile.Present>(archived).bytes.toByteArray())
                val document = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(snapshot)).document
                val reloaded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(document)))
                return assertNotNull(StudioLegacyProjection.project(reloaded.document, StudioTarget.Global)).mappings
            }
        } finally {
            assertContentEquals(bytes, Files.readAllBytes(source))
            assertFalse(Files.exists(storeDirectory.resolve("state.xml")))
        }
    }

    private fun mapping(
        field: String,
        key: String,
        value: String,
    ): String = "<option name='$field'><map><entry key='$key' value='$value'/></map></option>"

    companion object {
        private val projectMapNames =
            listOf("projectAccents", "projectDisplayNames", "projectFallbackAccents", "forcedProjectLanguages")
    }
}
