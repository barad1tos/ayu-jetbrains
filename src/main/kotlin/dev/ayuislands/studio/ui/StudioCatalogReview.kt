package dev.ayuislands.studio.ui

import com.intellij.openapi.ui.ComboBox
import dev.ayuislands.studio.model.CaptureDifference
import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.CatalogReview
import dev.ayuislands.studio.model.DeleteImpact
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioProfileLabel
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.session.StudioCatalogStaging
import dev.ayuislands.studio.session.StudioProfileLabels
import java.awt.BorderLayout
import java.util.UUID
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField

/** Catalog controls stage only their returned intents, after the enclosing review is confirmed. */
internal class StudioCatalogReview(
    private val session: StudioSessionRecord,
    private val document: StudioDocument,
    private val onChange: () -> Unit,
    private val edit: (DraftId) -> Unit,
) {
    private val labels = StudioProfileLabels.project(document, session.work)
    private val checks = mutableListOf<() -> Boolean>()
    private val commands = mutableListOf<() -> List<SessionIntent>>()
    private val deleteChoices = mutableListOf<() -> DeleteImpact?>()
    private val captureChoices = mutableListOf<() -> CaptureDifference?>()

    fun hidden(id: DraftId): JComponent {
        val draft = session.work.drafts.single { it.id == id }
        val definition = document.catalog.singleOrNull { it.id == draft.source?.id }
        val options =
            buildList {
                add(CHOOSE)
                if (definition is ProfileDefinition.User) add(UPDATE)
                add(CREATE)
                add(DISCARD)
            }
        val row = reviewRow("Unsaved draft: ${draftLabel(draft)}")
        val selected = ComboBox(options.toTypedArray())
        val name = profileName(draftName(draft))
        val createdId = ProfileId(UUID.randomUUID().toString())
        row.add(name, BorderLayout.CENTER)
        row.add(
            reviewActions().apply {
                add(editButton(id, edit))
                add(selected)
            },
            BorderLayout.SOUTH,
        )
        selected.addActionListener {
            name.isVisible = selected.selectedItem == CREATE
            row.revalidate()
            onChange()
        }
        checks.add { selected.selectedIndex > 0 }
        commands.add {
            when (selected.selectedItem) {
                UPDATE -> listOf(SessionIntent.Catalog(CatalogCommand.Update(id, requireNotNull(definition).id)))
                CREATE -> listOf(SessionIntent.Catalog(CatalogCommand.Create(id, createdId, name.text)))
                DISCARD -> listOf(SessionIntent.Discard(id))
                else -> emptyList()
            }
        }
        return row
    }

    fun item(item: CatalogReview): JComponent =
        when (item) {
            is CatalogReview.ConfirmDelete -> confirmDelete(item.impact)
            is CatalogReview.StaleCapture -> capture(item.difference)
            is CatalogReview.DefinitionChanged -> changed(item.id, conflicting = false)
            is CatalogReview.ConflictingIntents -> changed(item.id, conflicting = true)
            is CatalogReview.MissingDraft -> missing(item.id)
        }

    fun liveSession(): JComponent {
        checks.add { false }
        return reviewRow("Other pending changes").apply {
            add(JLabel("Review the other Settings window before applying."), BorderLayout.CENTER)
        }
    }

    fun isResolved(): Boolean = checks.all { it() }

    fun intents(): List<SessionIntent> {
        val selected = commands.flatMap { it() }
        val removals =
            selected
                .asSequence()
                .filterIsInstance<SessionIntent.Catalog>()
                .map { it.command }
                .filterIsInstance<CatalogCommand.Unstage>()
                .distinct()
                .sortedByDescending { it.index }
                .toList()
        return removals.map { SessionIntent.Catalog(it) } +
            selected.filterNot {
                it is SessionIntent.Catalog && it.command is CatalogCommand.Unstage
            }
    }

    fun deletions(): List<DeleteImpact> = deleteChoices.mapNotNull { it() }

    fun retained(): List<CaptureDifference> = captureChoices.mapNotNull { it() }

    private fun confirmDelete(impact: DeleteImpact): JComponent {
        val selected = JCheckBox("Delete profile")
        selected.addActionListener { onChange() }
        checks.add { selected.isSelected }
        deleteChoices.add { impact.takeIf { selected.isSelected } }
        return reviewRow("Delete profile: ${profileLabel(impact.definition.id) ?: impact.definition.name}").apply {
            add(
                JLabel(
                    "Used by ${impact.targets.size} locations and ${impact.drafts.size} drafts. " +
                        "Their appearance is kept.",
                ),
                BorderLayout.CENTER,
            )
            add(selected, BorderLayout.SOUTH)
        }
    }

    private fun capture(difference: CaptureDifference): JComponent {
        val selected = ComboBox(arrayOf(CHOOSE, "Refresh saved snapshot", "Keep staged snapshot"))
        selected.addActionListener { onChange() }
        val draft = session.work.drafts.single { it.id == difference.draft }
        checks.add { selected.selectedIndex > 0 }
        captureChoices.add { difference.takeIf { selected.selectedIndex == 2 } }
        commands.add {
            if (selected.selectedIndex == 1) {
                listOf(
                    SessionIntent.Catalog(
                        CatalogCommand.Update(difference.draft, StudioCatalogStaging.profileId(difference.intent)),
                    ),
                )
            } else {
                emptyList()
            }
        }
        return reviewRow("Draft changed after saving: ${draftLabel(draft)}").apply {
            add(
                reviewActions().apply {
                    add(editButton(difference.draft, edit))
                    add(selected)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    private fun changed(
        id: ProfileId,
        conflicting: Boolean,
    ): JComponent {
        val staged = session.work.catalog.filter { StudioCatalogStaging.profileId(it) == id }
        val definition = document.catalog.singleOrNull { it.id == id }
        val draftId = staged.firstNotNullOfOrNull(::capturedDraft)
        val draft = session.work.drafts.singleOrNull { it.id == draftId }
        val options = profileActions(conflicting, definition, draft)
        val row = reviewRow("Profile changed: ${profileLabel(id) ?: draft?.let(::draftLabel) ?: "Unavailable profile"}")
        val selected = ComboBox(options.toTypedArray())
        val name = profileName(definition?.name ?: draft?.let(::draftName).orEmpty())
        val createdId = ProfileId(UUID.randomUUID().toString())
        row.add(name, BorderLayout.CENTER)
        row.add(
            reviewActions().apply {
                if (draft != null) add(editButton(draft.id, edit))
                add(selected)
            },
            BorderLayout.SOUTH,
        )
        selected.addActionListener {
            name.isVisible = selected.selectedItem == CREATE
            row.revalidate()
            onChange()
        }
        checks.add { selected.selectedIndex > 0 }
        commands.add {
            when (selected.selectedItem) {
                "Keep deletion" -> {
                    remove(staged.filterNot { it is CatalogIntent.Delete })
                }

                "Keep profile changes" -> {
                    remove(staged.filterIsInstance<CatalogIntent.Delete>())
                }

                "Use my change" -> {
                    staged.map { SessionIntent.Catalog(refresh(it)) }
                }

                CREATE -> {
                    remove(staged) +
                        SessionIntent.Catalog(CatalogCommand.Create(requireNotNull(draft).id, createdId, name.text))
                }

                "Cancel staged changes" -> {
                    remove(staged)
                }

                else -> {
                    emptyList()
                }
            }
        }
        return row
    }

    private fun profileActions(
        conflicting: Boolean,
        definition: ProfileDefinition?,
        draft: DraftRecord?,
    ): List<String> =
        buildList {
            add(CHOOSE)
            if (conflicting) {
                add("Keep deletion")
                add("Keep profile changes")
            } else if (definition is ProfileDefinition.User) {
                add("Use my change")
            }
            if (draft != null) add(CREATE)
            add("Cancel staged changes")
        }

    private fun missing(id: DraftId): JComponent {
        val selected = JCheckBox("Remove unavailable staged change")
        selected.addActionListener { onChange() }
        checks.add { selected.isSelected }
        commands.add {
            if (selected.isSelected) remove(session.work.catalog.filter { capturedDraft(it) == id }) else emptyList()
        }
        return reviewRow("The source draft is no longer available").apply {
            add(selected, BorderLayout.SOUTH)
        }
    }

    private fun remove(intents: List<CatalogIntent>): List<SessionIntent> =
        session.work.catalog.mapIndexedNotNull { index, intent ->
            if (intent in intents) SessionIntent.Catalog(CatalogCommand.Unstage(index)) else null
        }

    private fun draftName(draft: DraftRecord): String =
        draft.sourceName ?: document.catalog.singleOrNull { it.id == draft.source?.id }?.name
            ?: StudioProfileLabels.CURRENT_APPEARANCE

    private fun profileLabel(id: ProfileId): String? = labels[StudioProfileChoice.Profile(id)]?.let(::labelText)

    private fun draftLabel(draft: DraftRecord): String =
        draft.source?.id?.let(::profileLabel)
            ?: labels[StudioProfileChoice.Draft(draft.id)]?.let(::labelText)
            ?: draftName(draft)

    private fun profileName(initial: String): JTextField =
        JTextField(initial, PROFILE_NAME_COLUMNS).apply {
            isVisible = false
            accessibleContext.accessibleName = "Profile name"
        }

    private companion object {
        const val PROFILE_NAME_COLUMNS = 24
        const val CHOOSE = "Choose action"
        const val UPDATE = "Update profile"
        const val CREATE = "Create profile"
        const val DISCARD = "Discard changes"
    }
}

private fun labelText(label: StudioProfileLabel): String =
    (label.pendingName ?: label.name) + label.qualifier?.let { " · $it" }.orEmpty()

private fun capturedDraft(intent: CatalogIntent): DraftId? =
    when (intent) {
        is CatalogIntent.Create -> intent.draft
        is CatalogIntent.Update -> intent.draft
        is CatalogIntent.Rename, is CatalogIntent.Delete -> null
    }

private fun refresh(intent: CatalogIntent): CatalogCommand =
    when (intent) {
        is CatalogIntent.Create -> CatalogCommand.Update(intent.draft, intent.definition.id)
        is CatalogIntent.Update -> CatalogCommand.Update(intent.draft, intent.before.id)
        is CatalogIntent.Rename -> CatalogCommand.Rename(intent.before.id, intent.name)
        is CatalogIntent.Delete -> CatalogCommand.Delete(intent.before.id)
    }
