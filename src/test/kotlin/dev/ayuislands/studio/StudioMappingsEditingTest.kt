package dev.ayuislands.studio

import dev.ayuislands.settings.mappings.LanguageMapping
import dev.ayuislands.settings.mappings.MappingRow
import dev.ayuislands.settings.mappings.MappingsEdit
import dev.ayuislands.settings.mappings.MappingsTable
import dev.ayuislands.settings.mappings.ProjectMapping
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioMappingsEditing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioMappingsEditingTest {
    @Test
    fun `opening mappings retains raw rows missing names and every hidden policy field`() {
        val original = fields()
        val before = original.copy()

        val settings = StudioMappingsEditing.settings(original)

        assertEquals(
            listOf(
                MappingRow("/work/Робота", "<html>Особистий проєкт</html>", "#aAbBcC"),
                MappingRow("", null, "future-color"),
                MappingRow("/work/missing-name", null, " #112233 "),
            ),
            settings.projects,
        )
        assertEquals(
            listOf(
                MappingRow("TypeScript", null, "not-a-color"),
                MappingRow("kotlin", null, "#5ccfe6"),
                MappingRow("future language", null, "#ABCDEF"),
            ),
            settings.languages,
        )
        assertEquals(before, original)
        assertEquals(10, original.records.size)
    }

    @Test
    fun `adding overrides appends only the requested domain and retains independent name order`() {
        val original = fields()
        val project = ProjectMapping("/work/new-project", "Новий проєкт", "#bBcCdD")
        val addedProject =
            assertChanges(
                original,
                MappingsEdit.AddProject(project),
                mapOf(
                    "projectAccents" to
                        ordered(
                            "/work/Робота" to "#aAbBcC",
                            "" to "future-color",
                            "/work/missing-name" to " #112233 ",
                            "/work/new-project" to "#bBcCdD",
                        ),
                    "projectDisplayNames" to
                        ordered(
                            "/work/unmapped" to "Keep detached name",
                            "/work/Робота" to "<html>Особистий проєкт</html>",
                            "/work/new-project" to "Новий проєкт",
                        ),
                ),
            )
        assertChanges(
            addedProject,
            MappingsEdit.AddLanguage(LanguageMapping("rust", "Rust", "#fFaA11")),
            mapOf(
                "languageAccents" to
                    ordered(
                        "TypeScript" to "not-a-color",
                        "kotlin" to "#5ccfe6",
                        "future language" to "#ABCDEF",
                        "rust" to "#fFaA11",
                    ),
            ),
        )
    }

    @Test
    fun `editing color uses the exact stored key without repairing siblings or display names`() {
        val original = fields()
        assertChanges(
            original,
            MappingsEdit.Color(MappingsTable.PROJECTS, "/work/missing-name", "#FFCC66"),
            mapOf(
                "projectAccents" to
                    ordered(
                        "/work/Робота" to "#aAbBcC",
                        "" to "future-color",
                        "/work/missing-name" to "#FFCC66",
                    ),
            ),
        )
        assertChanges(
            original,
            MappingsEdit.Color(MappingsTable.LANGUAGES, "TypeScript", "#FFCC66"),
            mapOf(
                "languageAccents" to
                    ordered("TypeScript" to "#FFCC66", "kotlin" to "#5ccfe6", "future language" to "#ABCDEF"),
            ),
        )
    }

    @Test
    fun `removing a project preserves its fallback and forced language while removing its paired name`() {
        val original = fields()
        assertChanges(
            original,
            MappingsEdit.Remove(MappingsTable.PROJECTS, "/work/Робота"),
            mapOf(
                "projectAccents" to ordered("" to "future-color", "/work/missing-name" to " #112233 "),
                "projectDisplayNames" to ordered("/work/unmapped" to "Keep detached name"),
            ),
        )
        assertChanges(
            original,
            MappingsEdit.Remove(MappingsTable.LANGUAGES, "kotlin"),
            mapOf("languageAccents" to ordered("TypeScript" to "not-a-color", "future language" to "#ABCDEF")),
        )
    }

    @Test
    fun `explicit diagnostics edits touch only their focused project entry`() {
        val original = fields()
        val changed =
            assertChanges(
                original,
                MappingsEdit.Fallback("/work/Робота", "#73D0FF"),
                mapOf(
                    "projectFallbackAccents" to
                        ordered("/work/Робота" to "#73D0FF", "/work/other" to "future-fallback"),
                ),
            )
        assertChanges(
            changed,
            MappingsEdit.Fallback("/work/Робота", null),
            mapOf("projectFallbackAccents" to ordered("/work/other" to "future-fallback")),
        )
        val forced =
            assertChanges(
                original,
                MappingsEdit.ForcedLanguage("/work/Робота", "kotlin"),
                mapOf(
                    "forcedProjectLanguages" to
                        ordered("/work/other" to " Unknown Language ", "/work/Робота" to "kotlin"),
                ),
            )
        assertChanges(
            forced,
            MappingsEdit.ForcedLanguage("/work/Робота", null),
            mapOf("forcedProjectLanguages" to ordered("/work/other" to " Unknown Language ")),
        )
    }

    @Test
    fun `accepting an unchanged color or removing an already absent row creates no pending edit`() {
        val original = fields()
        assertTrue(
            StudioMappingsEditing
                .edits(original, MappingsEdit.Color(MappingsTable.PROJECTS, "/work/Робота", "#aAbBcC"))
                .isEmpty(),
        )
        assertTrue(
            StudioMappingsEditing.edits(original, MappingsEdit.Remove(MappingsTable.LANGUAGES, "absent")).isEmpty(),
        )
    }

    private fun assertChanges(
        original: StudioFields,
        edit: MappingsEdit,
        expected: Map<String, RawValue>,
    ): StudioFields {
        val changes =
            StudioMappingsEditing.edits(original, edit).map { intent ->
                assertIs<SharedIntent.Field>(assertIs<SessionIntent.Shared>(intent).intent)
            }
        assertEquals(expected, changes.associate { it.after.key.name to it.after.value })
        assertEquals(expected.size, changes.size)
        changes.forEach { change ->
            assertEquals(original.records.single { it.key == change.before.key }, change.before)
            assertEquals(change.before.key, change.after.key)
            assertEquals(FieldOrigin(SourcePresence.VALUE), change.after.origin)
        }
        val next =
            original.copy(
                records =
                    StudioList(
                        original.records.map { field ->
                            changes.singleOrNull { it.before.key == field.key }?.after ?: field
                        },
                    ),
            )
        assertEquals(original.records.map { it.key }, next.records.map { it.key })
        assertEquals(original.extensions, next.extensions)
        assertEquals(
            original.records.filterNot { it.key.name in expected },
            next.records.filterNot { it.key.name in expected },
        )
        return next
    }

    private fun fields(): StudioFields {
        val values =
            mapOf(
                "projectAccents" to
                    ordered(
                        "/work/Робота" to "#aAbBcC",
                        "" to "future-color",
                        "/work/missing-name" to " #112233 ",
                    ),
                "projectDisplayNames" to
                    ordered(
                        "/work/unmapped" to "Keep detached name",
                        "/work/Робота" to "<html>Особистий проєкт</html>",
                    ),
                "languageAccents" to
                    ordered("TypeScript" to "not-a-color", "kotlin" to "#5ccfe6", "future language" to "#ABCDEF"),
                "projectFallbackAccents" to
                    ordered("/work/Робота" to " #112233 ", "/work/other" to "future-fallback"),
                "forcedProjectLanguages" to
                    ordered("/work/other" to " Unknown Language ", "/work/Робота" to " TypeScript "),
                "languageFallbackAccent" to RawValue.Text(" future-language-fallback "),
                "dominanceThreshold" to RawValue.FloatBits(Float.NaN.toRawBits()),
                "dominanceMarginRatio" to RawValue.FloatBits((-2.25f).toRawBits()),
                "dominanceFloor" to RawValue.FloatBits(1.5f.toRawBits()),
                "tiebreakMinShare" to RawValue.FloatBits((-0.0f).toRawBits()),
            )
        return sessionDocument().shared.mappings.copy(
            records =
                StudioList(
                    sessionDocument().shared.mappings.records.reversed().map { field ->
                        field.copy(
                            value = values.getValue(field.key.name),
                            origin =
                                FieldOrigin(
                                    SourcePresence.VALUE,
                                    StudioBytes("raw:${field.key.name}".toByteArray()),
                                ),
                        )
                    },
                ),
            extensions = StudioList(listOf(OpaqueRecord("future-mapping-policy", StudioBytes(byteArrayOf(7, 3, 0))))),
        )
    }

    private fun ordered(vararg entries: Pair<String, String>): RawValue.OrderedMap =
        RawValue.OrderedMap(StudioList(entries.map { (key, value) -> RawValue.Entry(key, RawValue.Text(value)) }))
}
