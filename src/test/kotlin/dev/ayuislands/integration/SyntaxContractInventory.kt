package dev.ayuislands.integration

import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.PreviewFileSpec
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxKeyRole
import dev.ayuislands.syntax.SyntaxKeyRoleRegistry

internal object SyntaxContractInventory {
    fun build(
        specifications: List<LanguageSpecification>,
        existing: Map<String, Set<PrimitiveCategory>>,
        verified: Map<String, Set<PrimitiveCategory>>,
    ): SyntaxContractMatrix =
        SyntaxContractMatrix.build(
            specifications.map { specification ->
                LanguageContractEvidence(
                    language = specification.storageId,
                    advertised = true,
                    existing = existing[specification.storageId].orEmpty(),
                    declared = specification.declaredCategories(),
                    previewed = specification.previewedCategories(),
                    verified = verified[specification.storageId].orEmpty(),
                )
            },
        )

    private fun LanguageSpecification.declaredCategories(): Set<PrimitiveCategory> =
        SyntaxKeyRoleRegistry
            .rolesFor(storageId)
            .values
            .filterIsInstance<SyntaxKeyRole.Tunable>()
            .mapTo(linkedSetOf(), SyntaxKeyRole.Tunable::primitive)

    private fun LanguageSpecification.previewedCategories(): Set<PrimitiveCategory> =
        preview.files.flatMapTo(linkedSetOf(), PreviewFileSpec::demonstratedCategories)
}
