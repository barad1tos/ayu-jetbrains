package dev.ayuislands.theme

/** Pure component progress; observations classify retained payloads, never create new ownership. */
internal class SchemeWriteEvidence(
    private val component: SchemeComponent,
    private val before: SchemeAdmissionFacts,
    val planned: SchemeAdmissionFacts,
    restored: SchemeAdmissionFacts,
    private val released: SchemeAdmissionFacts? = null,
) {
    private var phase = SchemeWritePhase.PREPARED
    private var expected = before
    var restored: SchemeAdmissionFacts = restored
        private set

    val progress: SchemeWriteProgress
        get() = SchemeWriteProgress(phase, expected, restored)

    val isForeign: Boolean
        get() = phase == SchemeWritePhase.FOREIGN

    val hasPending: Boolean
        get() =
            phase == SchemeWritePhase.WRITING ||
                phase == SchemeWritePhase.APPLIED ||
                phase == SchemeWritePhase.RESTORING

    fun accepts(observed: SchemeAdmissionFacts?): Boolean =
        phase != SchemeWritePhase.FOREIGN && SchemeAdmission.matchesComponent(expected, observed, component)

    fun beginApply() {
        check(phase == SchemeWritePhase.PREPARED) { "Editor component write was already started" }
        phase = SchemeWritePhase.WRITING
    }

    fun observeApply(observed: SchemeAdmissionFacts?): Boolean {
        check(phase == SchemeWritePhase.WRITING)
        expected =
            when {
                SchemeAdmission.matchesComponent(before, observed, component) -> before
                SchemeAdmission.matchesComponent(planned, observed, component) -> planned
                else -> {
                    phase = SchemeWritePhase.FOREIGN
                    return false
                }
            }
        phase = SchemeWritePhase.APPLIED
        return true
    }

    fun prepareRestore(observed: SchemeAdmissionFacts?): Boolean {
        if (phase == SchemeWritePhase.PREPARED) phase = SchemeWritePhase.RESTORED
        if (phase == SchemeWritePhase.WRITING) observeApply(observed)
        if (phase == SchemeWritePhase.RESTORED && !isRestored(observed)) phase = SchemeWritePhase.FOREIGN
        if (!hasPending) return false
        if (isRestored(observed)) {
            phase = SchemeWritePhase.RESTORED
            return false
        }
        if (SchemeAdmission.matchesComponent(expected, observed, component)) return true
        phase = SchemeWritePhase.FOREIGN
        return false
    }

    fun release() {
        val destination = released ?: return
        if (phase == SchemeWritePhase.FOREIGN || restored === destination) return
        if (phase == SchemeWritePhase.RESTORED) expected = restored
        restored = destination
        if (phase == SchemeWritePhase.PREPARED || phase == SchemeWritePhase.RESTORED) phase = SchemeWritePhase.APPLIED
    }

    fun beginRestore() {
        check(phase == SchemeWritePhase.APPLIED || phase == SchemeWritePhase.RESTORING)
        phase = SchemeWritePhase.RESTORING
    }

    fun preserve() {
        if (hasPending) phase = SchemeWritePhase.FOREIGN
    }

    fun finishUnused() {
        if (phase == SchemeWritePhase.PREPARED) phase = SchemeWritePhase.RESTORED
    }

    /** Advances only from a retained seed result, never from an arbitrary native observation. */
    fun followSeed(cell: SchemeCellFacts?) {
        check(component == SchemeComponent.VALUE && SchemeAdmission.isAbsent(before.cell))
        if (cell == null) {
            preserve()
        } else if (hasPending) {
            expected =
                SchemeAdmissionFacts(
                    cell,
                    expected.owner,
                    expected.metadataTable,
                    expected.hasMetadata,
                    expected.metadataValue,
                    expected.parent,
                )
            phase = SchemeWritePhase.RESTORING
        }
    }

    fun observeRestore(observed: SchemeAdmissionFacts?) {
        check(phase == SchemeWritePhase.RESTORING)
        phase =
            when {
                isRestored(observed) -> SchemeWritePhase.RESTORED
                SchemeAdmission.matchesComponent(expected, observed, component) -> SchemeWritePhase.RESTORING
                else -> SchemeWritePhase.FOREIGN
            }
    }

    private fun isRestored(observed: SchemeAdmissionFacts?): Boolean =
        SchemeAdmission.matchesComponent(restored, observed, component) ||
            (restored !== released && SchemeAdmission.matchesComponent(before, observed, component))
}
