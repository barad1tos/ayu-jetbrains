package dev.ayuislands.studio.ui

import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.session.SessionEdits
import dev.ayuislands.studio.session.StudioCatalogStaging
import dev.ayuislands.studio.session.StudioSessionBinding
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/** Catalog operations stage existing intents; accepted rows stay authoritative until Apply. */
internal class StudioProfileMenu(
    private val binding: StudioSessionBinding,
    private val dialogs: StudioHostDialogs,
) {
    fun show(parent: JComponent) {
        val state = binding.controller.state
        val work = state.record?.work ?: return
        val draft = work.drafts.single { it.id == work.selected }
        val definitions =
            binding.document.catalog + work.catalog.filterIsInstance<CatalogIntent.Create>().map { it.definition }
        val profile = definitions.singleOrNull { it.id == draft.source?.id }
        val isMutable = profile is ProfileDefinition.User
        val popup = JPopupMenu()
        val create = CatalogCommand.Create(draft.id, ProfileId(binding.nextId()), "")
        val canEditCatalog = SessionEdits.permits(state.phase, SessionIntent.Catalog(create))
        popup.add(
            item("Create profile…", canEditCatalog) {
                dialogs.profileName(null)?.let { stage(create.copy(name = it)) }
            },
        )
        popup.add(
            item("Update profile", isMutable && canEditCatalog) {
                profile?.let { stage(CatalogCommand.Update(draft.id, it.id)) }
            },
        )
        popup.add(
            item("Rename…", isMutable && canEditCatalog) {
                profile?.let { selected ->
                    dialogs.profileName(selected.name)?.let { stage(CatalogCommand.Rename(selected.id, it)) }
                }
            },
        )
        popup.add(
            item(
                "Delete profile…",
                isMutable && canEditCatalog,
            ) { profile?.let { stage(CatalogCommand.Delete(it.id)) } },
        )
        val pending = work.catalog.withIndex().filter { StudioCatalogStaging.profileId(it.value) == profile?.id }
        popup.add(
            item("Undo profile changes", pending.isNotEmpty() && canEditCatalog) {
                pending.asReversed().forEach { stage(CatalogCommand.Unstage(it.index)) }
            },
        )
        popup.show(parent, parent.width / 2, 0)
    }

    private fun stage(command: CatalogCommand) {
        binding.controller.dispatch(SessionEvent.Intent(SessionIntent.Catalog(command)))
    }

    private fun item(
        title: String,
        enabled: Boolean,
        action: () -> Unit,
    ): JMenuItem =
        JMenuItem(title).apply {
            isEnabled = enabled
            addActionListener { action() }
        }
}
