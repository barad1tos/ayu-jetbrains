package dev.ayuislands.studio.model

internal sealed interface CatalogCommand {
    data class Create(
        val draft: DraftId,
        val id: ProfileId,
        val name: String,
    ) : CatalogCommand

    data class Update(
        val draft: DraftId,
        val id: ProfileId,
    ) : CatalogCommand

    data class Rename(
        val id: ProfileId,
        val name: String,
    ) : CatalogCommand

    data class Delete(
        val id: ProfileId,
    ) : CatalogCommand

    data class Unstage(
        val index: Int,
    ) : CatalogCommand
}

internal enum class CatalogError {
    INVALID_NAME,
    FACTORY_LOCKED,
    MISSING_PROFILE,
    MISSING_DRAFT,
    DUPLICATE_ID,
    MISSING_INTENT,
}

internal sealed interface CatalogStage {
    data class Staged(
        val work: SessionWork,
    ) : CatalogStage

    data class Rejected(
        val error: CatalogError,
    ) : CatalogStage
}
