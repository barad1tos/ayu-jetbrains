package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogError
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.CatalogStage
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList

internal object StudioCatalogStaging {
    fun stage(
        document: StudioDocument,
        work: SessionWork,
        command: CatalogCommand,
    ): CatalogStage =
        when (command) {
            is CatalogCommand.Create -> create(document, work, command)
            is CatalogCommand.Update -> update(document, work, command)
            is CatalogCommand.Rename -> rename(document, work, command)
            is CatalogCommand.Delete -> delete(document, work, command)
            is CatalogCommand.Unstage ->
                if (command.index in work.catalog.indices) {
                    CatalogStage.Staged(
                        work.copy(
                            catalog =
                                StudioList(
                                    work.catalog.filterIndexed { index, _ ->
                                        index !=
                                            command.index
                                    },
                                ),
                        ),
                    )
                } else {
                    CatalogStage.Rejected(CatalogError.MISSING_INTENT)
                }
        }

    private fun create(
        document: StudioDocument,
        work: SessionWork,
        command: CatalogCommand.Create,
    ): CatalogStage {
        val name = validName(command.name) ?: return CatalogStage.Rejected(CatalogError.INVALID_NAME)
        val draft =
            work.drafts.singleOrNull { it.id == command.draft }
                ?: return CatalogStage.Rejected(CatalogError.MISSING_DRAFT)
        val intent = CatalogIntent.Create(ProfileDefinition.User(command.id, name, draft.appearance, 1), draft.id)
        if (work.catalog.any { it == intent }) return CatalogStage.Staged(work)
        val invalidId = command.id.value.isBlank() || command.id.value.startsWith("factory:")
        val collision = document.catalog.any { it.id == command.id } || work.catalog.any { profileId(it) == command.id }
        if (invalidId || collision) {
            return CatalogStage.Rejected(CatalogError.DUPLICATE_ID)
        }
        return CatalogStage.Staged(work.copy(catalog = StudioList(work.catalog + intent)))
    }

    private fun update(
        document: StudioDocument,
        work: SessionWork,
        command: CatalogCommand.Update,
    ): CatalogStage {
        val draft =
            work.drafts.singleOrNull { it.id == command.draft }
                ?: return CatalogStage.Rejected(CatalogError.MISSING_DRAFT)
        val created =
            work.catalog.filterIsInstance<CatalogIntent.Create>().singleOrNull {
                it.definition.id ==
                    command.id
            }
        if (created !=
            null
        ) {
            return replace(
                work,
                created,
                created.copy(definition = created.definition.copy(appearance = draft.appearance), draft = draft.id),
            )
        }
        val profile =
            document.catalog.singleOrNull { it.id == command.id }
                ?: return CatalogStage.Rejected(CatalogError.MISSING_PROFILE)
        if (profile !is ProfileDefinition.User) return CatalogStage.Rejected(CatalogError.FACTORY_LOCKED)
        val intent =
            CatalogIntent.Update(
                profile,
                profile.copy(
                    appearance = draft.appearance,
                    revision =
                        profile.revision + 1,
                ),
                draft.id,
            )
        val previous = work.catalog.filterIsInstance<CatalogIntent.Update>().singleOrNull { it.before.id == command.id }
        return replace(work, previous, intent)
    }

    private fun rename(
        document: StudioDocument,
        work: SessionWork,
        command: CatalogCommand.Rename,
    ): CatalogStage {
        val name = validName(command.name) ?: return CatalogStage.Rejected(CatalogError.INVALID_NAME)
        val created =
            work.catalog.filterIsInstance<CatalogIntent.Create>().singleOrNull {
                it.definition.id ==
                    command.id
            }
        if (created !=
            null
        ) {
            return replace(work, created, created.copy(definition = created.definition.copy(name = name)))
        }
        val profile =
            document.catalog.singleOrNull { it.id == command.id }
                ?: return CatalogStage.Rejected(CatalogError.MISSING_PROFILE)
        if (profile !is ProfileDefinition.User) return CatalogStage.Rejected(CatalogError.FACTORY_LOCKED)
        val previous = work.catalog.filterIsInstance<CatalogIntent.Rename>().singleOrNull { it.before.id == command.id }
        return replace(work, previous, CatalogIntent.Rename(profile, name))
    }

    private fun delete(
        document: StudioDocument,
        work: SessionWork,
        command: CatalogCommand.Delete,
    ): CatalogStage {
        val created =
            work.catalog.filterIsInstance<CatalogIntent.Create>().singleOrNull {
                it.definition.id ==
                    command.id
            }
        if (created !=
            null
        ) {
            return CatalogStage.Staged(work.copy(catalog = StudioList(work.catalog.filter { it != created })))
        }
        val profile =
            document.catalog.singleOrNull { it.id == command.id }
                ?: return CatalogStage.Rejected(CatalogError.MISSING_PROFILE)
        if (profile !is ProfileDefinition.User) return CatalogStage.Rejected(CatalogError.FACTORY_LOCKED)
        val previous = work.catalog.filterIsInstance<CatalogIntent.Delete>().singleOrNull { it.before.id == command.id }
        return replace(work, previous, CatalogIntent.Delete(profile))
    }

    private fun replace(
        work: SessionWork,
        previous: CatalogIntent?,
        next: CatalogIntent,
    ): CatalogStage {
        val intents =
            if (previous ==
                null
            ) {
                work.catalog + next
            } else {
                work.catalog.map { if (it == previous) next else it }
            }
        return CatalogStage.Staged(work.copy(catalog = StudioList(intents)))
    }

    private fun validName(entered: String): String? {
        if (entered.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
        return entered.trim().takeIf { it.isNotEmpty() }
    }

    fun profileId(intent: CatalogIntent): ProfileId =
        when (intent) {
            is CatalogIntent.Create -> intent.definition.id
            is CatalogIntent.Update -> intent.before.id
            is CatalogIntent.Rename -> intent.before.id
            is CatalogIntent.Delete -> intent.before.id
        }
}
