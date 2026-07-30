package dev.ayuislands.integration

internal enum class IntegrationOwnership {
    UNOWNED,
    OWNED,
    RECOVERY_PENDING,
    SUSPENDED,
    ;

    companion object {
        fun fromName(value: String?): IntegrationOwnership {
            if (value == null) return UNOWNED
            return entries.firstOrNull { it.name == value } ?: SUSPENDED
        }
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

internal fun IntegrationOutcome.propagateFailure() {
    if (this is IntegrationOutcome.Failed) throw error
}
