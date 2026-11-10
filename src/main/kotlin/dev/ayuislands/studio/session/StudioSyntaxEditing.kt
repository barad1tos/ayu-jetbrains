package dev.ayuislands.studio.session

import dev.ayuislands.settings.SyntaxPanelEvent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxCellId
import dev.ayuislands.syntax.SyntaxPreset

/** Converts an explicit control edit into exact addresses without rebuilding sparse user maps. */
internal object StudioSyntaxEditing {
    fun edits(
        draft: DraftRecord,
        event: SyntaxPanelEvent,
    ): List<SessionIntent> =
        when (event) {
            is SyntaxPanelEvent.Preset -> {
                val selected = scalar(draft, "selectedPreset", RawValue.Text(event.value.name))
                if (event.value == SyntaxPreset.CUSTOM) {
                    listOf(selected)
                } else {
                    listOf(selected, scalar(draft, "subordinatePreset", RawValue.Text(event.value.name)))
                }
            }

            is SyntaxPanelEvent.DimComments -> {
                listOf(scalar(draft, "dimComments", RawValue.BooleanValue(event.value)))
            }

            is SyntaxPanelEvent.SoftenDocumentation -> {
                listOf(scalar(draft, "softenDocumentation", RawValue.BooleanValue(event.value)))
            }

            is SyntaxPanelEvent.QuietOperators -> {
                listOf(scalar(draft, "quietOperators", RawValue.BooleanValue(event.value)))
            }

            is SyntaxPanelEvent.EmphasizeDeclarations -> {
                listOf(scalar(draft, "emphasizeDeclarations", RawValue.BooleanValue(event.value)))
            }

            else -> {
                cellEdits(draft, event)
            }
        }

    private fun cellEdits(
        draft: DraftRecord,
        event: SyntaxPanelEvent,
    ): List<SessionIntent> =
        when (event) {
            is SyntaxPanelEvent.Intensity -> {
                listOf(
                    cell(
                        draft,
                        "customOverrides",
                        event.language,
                        event.category,
                        event.value
                            .takeUnless {
                                it ==
                                    IDENTITY
                            }?.toString(),
                    ),
                )
            }

            is SyntaxPanelEvent.Style -> {
                listOf(cell(draft, "customStyles", event.language, event.category, event.value?.name))
            }

            is SyntaxPanelEvent.Emphasis -> {
                listOf(cell(draft, "customEmphasis", event.language, event.category, event.value?.name))
            }

            is SyntaxPanelEvent.ResetCell -> {
                reset(draft, event.language, setOf(event.category))
            }

            is SyntaxPanelEvent.ResetLanguage -> {
                reset(draft, event.language, event.categories)
            }

            else -> {
                emptyList()
            }
        }

    private fun reset(
        draft: DraftRecord,
        language: String,
        categories: Set<PrimitiveCategory>,
    ): List<SessionIntent> =
        categories.flatMap { category ->
            listOf("customOverrides", "customStyles", "customEmphasis").map { name ->
                cell(draft, name, language, category, null)
            }
        }

    private fun scalar(
        draft: DraftRecord,
        name: String,
        value: RawValue,
    ): SessionIntent.EditField =
        SessionIntent.EditField(
            draft.id,
            FieldAddress(LegacyFieldKey(LegacyStore.SYNTAX, name)),
            FieldValue.Present(value),
        )

    private fun cell(
        draft: DraftRecord,
        name: String,
        language: String,
        category: PrimitiveCategory,
        value: String?,
    ): SessionIntent.EditField =
        SessionIntent.EditField(
            draft.id,
            FieldAddress(
                LegacyFieldKey(LegacyStore.SYNTAX, name),
                FieldPart.MapEntry(SyntaxCellId(language, category.specification.storageId).encode()),
            ),
            value?.let { FieldValue.Present(RawValue.Text(it)) } ?: FieldValue.Absent,
        )

    private const val IDENTITY = 50
}
