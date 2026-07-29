package dev.ayuislands.integration

internal enum class IntegrationOwnership {
    UNOWNED,
    OWNED,
    SUSPENDED,
    ;

    companion object {
        fun fromName(value: String?): IntegrationOwnership = entries.firstOrNull { it.name == value } ?: UNOWNED
    }
}

internal sealed interface IntegrationOutcome {
    data object Applied : IntegrationOutcome

    data object Restored : IntegrationOutcome

    data object Skipped : IntegrationOutcome

    data class Failed(
        val operation: String,
        val error: Throwable,
    ) : IntegrationOutcome
}
