package dev.ayuislands.studio

import dev.ayuislands.settings.SyntaxPanelEvent
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioSyntaxEditing
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.syntax.FontEmphasis
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import dev.ayuislands.syntax.SyntaxPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioSyntaxEditingTest {
    @Test
    fun `named preset changes its subordinate while Custom retains every remembered field`() {
        val initial = draft()
        val shown = StudioSyntaxProjection.settings(initial.appearance)
        assertEquals("FUTURE_PRESET", shown.selectedPreset)
        assertEquals("CYBERPUNK", shown.subordinatePreset)
        assertEquals("FUTURE_STYLE", shown.customStyles["Java|COMMENT"])
        val originalStyles =
            assertIs<RawValue.OrderedMap>(
                initial.appearance.syntax.records
                    .single { it.key.name == "customStyles" }
                    .value,
            )
        assertEquals(originalStyles.entries.map { it.key }, shown.customStyles.keys.toList())
        val named = edit(initial, SyntaxPanelEvent.Preset(SyntaxPreset.NEON))
        assertAppearance(
            initial,
            named,
            mapOf("selectedPreset" to RawValue.Text("NEON"), "subordinatePreset" to RawValue.Text("NEON")),
        )

        val custom = edit(named, SyntaxPanelEvent.Preset(SyntaxPreset.CUSTOM))
        assertAppearance(named, custom, mapOf("selectedPreset" to RawValue.Text("CUSTOM")))
    }

    @Test
    fun `readability choices remain independent and inverse edits restore original metadata`() {
        val initial = draft()
        val choices =
            listOf(
                Triple("dimComments", SyntaxPanelEvent.DimComments(true), SyntaxPanelEvent.DimComments(false)),
                Triple(
                    "softenDocumentation",
                    SyntaxPanelEvent.SoftenDocumentation(true),
                    SyntaxPanelEvent.SoftenDocumentation(false),
                ),
                Triple("quietOperators", SyntaxPanelEvent.QuietOperators(true), SyntaxPanelEvent.QuietOperators(false)),
                Triple(
                    "emphasizeDeclarations",
                    SyntaxPanelEvent.EmphasizeDeclarations(true),
                    SyntaxPanelEvent.EmphasizeDeclarations(false),
                ),
            )
        for ((name, enable, disable) in choices) {
            val changed = edit(initial, enable)
            assertAppearance(initial, changed, mapOf(name to RawValue.BooleanValue(true)))
            val restored = edit(changed, disable)
            assertEquals(initial.appearance, restored.appearance)
            assertTrue(restored.tracking.touched.isEmpty())
        }
    }

    @Test
    fun `intensity updates one ordered entry and identity removes only that cell`() {
        val initial = draft()
        val changed = edit(initial, SyntaxPanelEvent.Intensity("Java", PrimitiveCategory.KEYWORD, 71))
        assertAppearance(
            initial,
            changed,
            mapOf("customOverrides" to mapChange(initial, "customOverrides", mapOf("Java|KEYWORD" to "71"))),
        )

        val inherited = edit(changed, SyntaxPanelEvent.Intensity("Java", PrimitiveCategory.KEYWORD, 50))
        assertAppearance(
            changed,
            inherited,
            mapOf("customOverrides" to mapChange(changed, "customOverrides", mapOf("Java|KEYWORD" to null))),
        )
    }

    @Test
    fun `replacement and additive emphasis keep separate sparse stores`() {
        val initial = draft()
        val replaced = edit(initial, SyntaxPanelEvent.Style("Java", PrimitiveCategory.KEYWORD, FontStyleOverride.PLAIN))
        assertAppearance(
            initial,
            replaced,
            mapOf("customStyles" to mapChange(initial, "customStyles", mapOf("Java|KEYWORD" to "PLAIN"))),
        )
        val emphasized =
            edit(replaced, SyntaxPanelEvent.Emphasis("Java", PrimitiveCategory.KEYWORD, FontEmphasis.ITALIC))
        assertAppearance(
            replaced,
            emphasized,
            mapOf("customEmphasis" to mapChange(replaced, "customEmphasis", mapOf("Java|KEYWORD" to "ITALIC"))),
        )
        val cleared = edit(emphasized, SyntaxPanelEvent.Emphasis("Java", PrimitiveCategory.KEYWORD, null))
        assertAppearance(
            emphasized,
            cleared,
            mapOf("customEmphasis" to mapChange(emphasized, "customEmphasis", mapOf("Java|KEYWORD" to null))),
        )
    }

    @Test
    fun `resets clear only requested available cells and preserve opaque values and other languages`() {
        val initial = draft()
        val category = edit(initial, SyntaxPanelEvent.ResetCell("Java", PrimitiveCategory.KEYWORD))
        val expected =
            listOf("customOverrides", "customStyles", "customEmphasis").associateWith { name ->
                mapChange(initial, name, mapOf("Java|KEYWORD" to null))
            }
        assertAppearance(initial, category, expected)

        val language =
            edit(initial, SyntaxPanelEvent.ResetLanguage("Java", setOf(PrimitiveCategory.KEYWORD)))
        assertAppearance(initial, language, expected)
    }

    @Test
    fun `navigation support and release actions never become appearance edits`() {
        val initial = draft()
        val events =
            listOf(
                SyntaxPanelEvent.Language("Kotlin"),
                SyntaxPanelEvent.ShowPrimitive(PrimitiveCategory.KEYWORD),
                SyntaxPanelEvent.SliderReleased,
                SyntaxPanelEvent.RecoverSupport,
                SyntaxPanelEvent.OpenHelp,
                SyntaxPanelEvent.Unlock,
            )
        for (event in events) assertTrue(StudioSyntaxEditing.edits(initial, event).isEmpty())
    }

    private fun draft(): DraftRecord {
        val syntax =
            SyntaxIntensityBaseState().apply {
                selectedPreset = "FUTURE_PRESET"
                subordinatePreset = "CYBERPUNK"
                customOverrides.putAll(
                    mapOf(
                        "Future|FUTURE" to " untouched ",
                        "Java|KEYWORD" to " 63 ",
                        "Java|COMMENT" to "not numeric",
                        "Kotlin|KEYWORD" to "-7",
                        "malformed" to "opaque",
                    ),
                )
                customStyles.putAll(
                    mapOf(
                        "Java|KEYWORD" to "BOLD",
                        "Java|COMMENT" to "FUTURE_STYLE",
                        "Kotlin|KEYWORD" to "ITALIC",
                        "malformed" to "unchanged",
                    ),
                )
                customEmphasis.putAll(
                    mapOf(
                        "Java|KEYWORD" to "BOLD",
                        "Java|COMMENT" to "FUTURE_EMPHASIS",
                        "Kotlin|KEYWORD" to "BOLD_ITALIC",
                    ),
                )
                schemaVersion = 99
            }
        val capture = StudioFixtures.capture().copy(fields = StudioFixtures.fields(syntax = syntax))
        val document = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture)).document
        return sessionRecord(document).work.drafts.single()
    }

    private fun edit(
        initial: DraftRecord,
        event: SyntaxPanelEvent,
    ): DraftRecord =
        StudioSyntaxEditing.edits(initial, event).fold(initial) { current, intent ->
            val field = assertIs<SessionIntent.EditField>(intent)
            assertEquals(initial.id, field.draft)
            StudioDraftPolicy.edit(current, field.address, field.value)
        }

    private fun mapChange(
        before: DraftRecord,
        name: String,
        changes: Map<String, String?>,
    ): RawValue.OrderedMap {
        val original =
            assertIs<RawValue.OrderedMap>(
                before.appearance.syntax.records
                    .single { it.key.name == name }
                    .value,
            )
        return RawValue.OrderedMap(
            StudioList(
                original.entries.mapNotNull { entry ->
                    if (entry.key !in changes) {
                        entry
                    } else {
                        changes[entry.key]?.let { RawValue.Entry(entry.key, RawValue.Text(it)) }
                    }
                },
            ),
        )
    }

    private fun assertAppearance(
        before: DraftRecord,
        after: DraftRecord,
        changes: Map<String, RawValue>,
    ) {
        val expected =
            before.appearance.syntax.copy(
                records =
                    StudioList(
                        before.appearance.syntax.records.map { field ->
                            val requested = changes[field.key.name]
                            if (requested == null || requested == field.value) {
                                field
                            } else {
                                field.copy(value = requested, origin = FieldOrigin(SourcePresence.VALUE))
                            }
                        },
                    ),
            )
        assertEquals(before.appearance.copy(syntax = expected), after.appearance)
    }
}
