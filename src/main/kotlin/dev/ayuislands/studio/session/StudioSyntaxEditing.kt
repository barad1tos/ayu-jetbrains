package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioSyntaxEdit
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SYNTAX_SLIDER_MIDPOINT
import dev.ayuislands.syntax.SyntaxCellId
import dev.ayuislands.syntax.SyntaxPreset

/** Apply each Syntax action to the supplied draft using its exact field or cell address. */
internal object StudioSyntaxEditing {
    private val cellFields =
        listOf(StudioSyntaxFields.customOverrides, StudioSyntaxFields.customStyles, StudioSyntaxFields.customEmphasis)

    fun edit(
        draft: DraftRecord,
        edit: StudioSyntaxEdit,
    ): DraftRecord =
        when (edit) {
            is StudioSyntaxEdit.Preset -> selectPreset(draft, edit.value)
            is StudioSyntaxEdit.Readability -> editScalar(draft, edit.option.field, RawValue.BooleanValue(edit.enabled))
            is StudioSyntaxEdit.Intensity ->
                editCell(
                    draft,
                    StudioSyntaxFields.customOverrides,
                    cell(edit.language, edit.category),
                    edit.value.takeUnless { it == SYNTAX_SLIDER_MIDPOINT }?.toString(),
                )
            is StudioSyntaxEdit.Style ->
                editCell(draft, StudioSyntaxFields.customStyles, cell(edit.language, edit.category), edit.value?.name)
            is StudioSyntaxEdit.Emphasis ->
                editCell(draft, StudioSyntaxFields.customEmphasis, cell(edit.language, edit.category), edit.value?.name)
            is StudioSyntaxEdit.Reset -> reset(draft, edit)
        }

    private fun selectPreset(
        draft: DraftRecord,
        preset: SyntaxPreset,
    ): DraftRecord {
        val name = RawValue.Text(preset.name)
        val selected = editScalar(draft, StudioSyntaxFields.selectedPreset, name)
        if (preset == SyntaxPreset.CUSTOM) return selected
        return editScalar(selected, StudioSyntaxFields.subordinatePreset, name)
    }

    private fun reset(
        draft: DraftRecord,
        edit: StudioSyntaxEdit.Reset,
    ): DraftRecord =
        edit.categories.fold(draft) { pending, category ->
            val cell = cell(edit.language, category)
            cellFields.fold(pending) { current, field -> editCell(current, field, cell, null) }
        }

    private fun cell(
        language: String,
        category: PrimitiveCategory,
    ): FieldPart.MapEntry = FieldPart.MapEntry(SyntaxCellId(language, category.specification.storageId).encode())

    private fun editScalar(
        draft: DraftRecord,
        field: LegacyFieldKey,
        value: RawValue,
    ): DraftRecord = StudioDraftPolicy.edit(draft, FieldAddress(field), FieldValue.Present(value))

    private fun editCell(
        draft: DraftRecord,
        field: LegacyFieldKey,
        cell: FieldPart.MapEntry,
        value: String?,
    ): DraftRecord =
        StudioDraftPolicy.edit(
            draft,
            FieldAddress(field, cell),
            value?.let { FieldValue.Present(RawValue.Text(it)) } ?: FieldValue.Absent,
        )
}
