package dev.ayuislands.studio.ui

import dev.ayuislands.settings.mappings.MappingsControls
import dev.ayuislands.settings.mappings.MappingsEdit
import dev.ayuislands.settings.mappings.MappingsPanelAction
import dev.ayuislands.settings.mappings.MappingsPanelView
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.session.StudioMappingsEditing

/** Accent subview; completed host-validated edits stage intents without changing the rendered authority. */
internal class StudioMappingsSection(
    initialExpanded: Boolean,
    private val stage: (List<SessionIntent>) -> Unit,
    action: (MappingsPanelAction) -> Unit,
) {
    private var fields: StudioFields? = null
    private var rendered: MappingsPanelView? = null
    val component = MappingsControls(initialExpanded, action, ::accept)

    fun render(
        fields: StudioFields,
        view: MappingsPanelView,
    ) {
        require(view.settings == StudioMappingsEditing.settings(fields)) { "Mapping view belongs to another draft" }
        this.fields = fields
        rendered = view
        component.render(view)
    }

    fun accept(edit: MappingsEdit) {
        val view = rendered ?: return
        val current = fields ?: return
        if (!view.isEditable || !view.isLicensed) return
        val changes = StudioMappingsEditing.edits(current, edit)
        if (changes.isNotEmpty()) stage(changes)
    }
}
