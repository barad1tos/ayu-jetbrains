package dev.ayuislands.theme

/** Admission requires unchanged physical state; equal rendered colors are not ownership evidence. */
internal object SchemeAdmission {
    fun matches(
        captured: SchemeAdmissionFacts,
        observed: SchemeAdmissionFacts?,
    ): Boolean =
        observed != null &&
            captured.parent === observed.parent &&
            SchemeComponent.entries.all { matchesComponent(captured, observed, it) }

    fun matchesComponent(
        captured: SchemeAdmissionFacts,
        observed: SchemeAdmissionFacts?,
        component: SchemeComponent,
    ): Boolean =
        observed != null &&
            when (component) {
                SchemeComponent.VALUE -> matchesCell(captured.cell, observed.cell)
                SchemeComponent.OWNERSHIP -> captured.owner === observed.owner
                SchemeComponent.METADATA ->
                    captured.metadataTable === observed.metadataTable &&
                        captured.hasMetadata == observed.hasMetadata &&
                        captured.metadataValue === observed.metadataValue
            }

    fun matchesCell(
        captured: SchemeCellFacts,
        observed: SchemeCellFacts,
    ): Boolean =
        when (captured) {
            is SchemeCellFacts.Color -> {
                observed is SchemeCellFacts.Color && captured.value?.identity === observed.value?.identity
            }

            is SchemeCellFacts.Attributes -> {
                observed is SchemeCellFacts.Attributes &&
                    captured.value?.identity === observed.value?.identity &&
                    captured.value?.flyweight === observed.value?.flyweight
            }

            SchemeCellFacts.InheritedAttributes -> {
                observed === SchemeCellFacts.InheritedAttributes
            }

            SchemeCellFacts.Unavailable -> false
        }

    fun isAbsent(cell: SchemeCellFacts): Boolean =
        when (cell) {
            is SchemeCellFacts.Color -> cell.value == null
            is SchemeCellFacts.Attributes -> cell.value == null
            SchemeCellFacts.InheritedAttributes, SchemeCellFacts.Unavailable -> false
        }
}
