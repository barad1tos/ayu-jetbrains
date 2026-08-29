package dev.ayuislands.integration

import dev.ayuislands.syntax.PrimitiveCategory

internal data class LanguageContractEvidence(
    val language: String,
    val advertised: Boolean,
    val existing: Set<PrimitiveCategory> = emptySet(),
    val declared: Set<PrimitiveCategory> = emptySet(),
    val previewed: Set<PrimitiveCategory> = emptySet(),
    val verified: Set<PrimitiveCategory> = emptySet(),
    val runtimeEvidence: List<RuntimeSyntaxEvidence> = emptyList(),
)

internal enum class RuntimeEvidenceStatus {
    VERIFIED,
    UNAVAILABLE,
}

internal data class RuntimeSyntaxEvidence(
    val runtimeId: String,
    val profileId: String,
    val status: RuntimeEvidenceStatus,
    val fileTypeName: String,
    val languageIds: Set<String>,
    val originsByPrimitive: Map<PrimitiveCategory, Set<SyntaxEvidenceOrigin>> = emptyMap(),
)

internal data class LanguageContract(
    val language: String,
    val advertised: Boolean,
    val existing: Set<PrimitiveCategory>,
    val declared: Set<PrimitiveCategory>,
    val previewed: Set<PrimitiveCategory>,
    val verified: Set<PrimitiveCategory>,
    val runtimeEvidence: List<RuntimeSyntaxEvidence>,
) {
    val undeclaredImplementations: Set<PrimitiveCategory> = existing - declared
    val unimplementedDeclarations: Set<PrimitiveCategory> = declared - existing
    val unpreviewedDeclarations: Set<PrimitiveCategory> = declared - previewed
    val unverifiedPreviews: Set<PrimitiveCategory> = previewed - verified
    val hasUnsupportedClaim: Boolean = advertised && (existing + declared + previewed + verified).isEmpty()
    val hasStructuralGap: Boolean =
        hasUnsupportedClaim ||
            unimplementedDeclarations.isNotEmpty() ||
            unpreviewedDeclarations.isNotEmpty()
    val isComplete: Boolean =
        !hasUnsupportedClaim &&
            undeclaredImplementations.isEmpty() &&
            unimplementedDeclarations.isEmpty() &&
            unpreviewedDeclarations.isEmpty() &&
            unverifiedPreviews.isEmpty()
    val actions: List<String> = buildActions()

    private fun buildActions(): List<String> =
        buildList {
            if (hasUnsupportedClaim) {
                add("Add native evidence for $language or remove the support claim.")
                return@buildList
            }
            undeclaredImplementations
                .action("Add language-owned native evidence for", "before declaring $language tuning")
                ?.let(::add)
            unimplementedDeclarations.action("Add effective", "actuation for $language")?.let(::add)
            unpreviewedDeclarations.action("Add", "to the $language preview sample")?.let(::add)
            unverifiedPreviews
                .action(
                    "Install the official $language plugin and verify",
                    "through the native preview contract",
                )?.let(::add)
        }
}

internal data class SyntaxContractMatrix(
    val languages: List<LanguageContract>,
) {
    companion object {
        fun build(evidence: List<LanguageContractEvidence>): SyntaxContractMatrix =
            SyntaxContractMatrix(
                evidence
                    .map { language ->
                        LanguageContract(
                            language = language.language,
                            advertised = language.advertised,
                            existing = language.existing,
                            declared = language.declared,
                            previewed = language.previewed,
                            verified = language.verified,
                            runtimeEvidence = language.runtimeEvidence,
                        )
                    }.sortedBy(LanguageContract::language),
            )
    }
}

private fun Set<PrimitiveCategory>.action(
    verb: String,
    destination: String,
): String? =
    takeIf { it.isNotEmpty() }
        ?.sortedBy(PrimitiveCategory::name)
        ?.joinToString(", ", transform = PrimitiveCategory::displayName)
        ?.let { primitives -> "$verb $primitives $destination." }
