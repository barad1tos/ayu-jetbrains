package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import java.util.Collections
import javax.swing.SwingUtilities

/** Target-only native deletion after seed acknowledgement; ownership remains with the existing entry owner. */
internal class SchemeDeletionCheckpoint private constructor(
    private val scheme: AbstractColorsScheme,
    private val parent: DefaultColorsScheme,
    captured: Map<SchemeEntry, SchemeCellFacts>,
    private val ownsEntry: (SchemeEntry) -> Boolean,
) {
    val cells: Map<SchemeEntry, SchemeCellFacts> = Collections.unmodifiableMap(captured.toMap())
    private val progress = captured.keys.associateWith { SchemeDeletionState.PENDING }.toMutableMap()

    val states: Map<SchemeEntry, SchemeDeletionState>
        get() = Collections.unmodifiableMap(progress.toMap())

    val hasPending: Boolean
        get() = progress.values.any { it == SchemeDeletionState.PENDING }

    /** The caller must durably acknowledge the captured targets before invoking this method. */
    fun apply() {
        check(SwingUtilities.isEventDispatchThread()) { "Editor deletion must run on EDT" }
        observe()
        if (!hasPending) return
        qualifyCurrent()
        var failure: Throwable? = null
        try {
            scheme.setSaveNeeded(true)
            scheme.optimizeAttributeMap()
        } catch (deletionFailure: RuntimeException) {
            failure = deletionFailure
        }
        try {
            observe()
        } catch (readFailure: RuntimeException) {
            failure = combineUiFailures(failure, readFailure)
        }
        failure?.let { throw it }
        check(!hasPending) { "Editor deletion did not settle" }
    }

    private fun observe() {
        for ((entry, previous) in cells) {
            if (progress[entry] != SchemeDeletionState.PENDING) continue
            if (scheme.parentScheme !== parent || !ownsEntry(entry)) {
                progress[entry] = SchemeDeletionState.FOREIGN
                continue
            }
            val current = captureSchemeCell(scheme, entry)
            progress[entry] =
                when {
                    current == null -> SchemeDeletionState.FOREIGN
                    SchemeAdmission.isAbsent(current) -> SchemeDeletionState.RESTORED
                    SchemeAdmission.matchesCell(previous, current) -> SchemeDeletionState.PENDING
                    else -> SchemeDeletionState.FOREIGN
                }
        }
    }

    private fun qualifyCurrent() {
        if (!hasPending) return
        check(scheme.parentScheme === parent) { "Editor deletion parent changed" }
        val pending = progress.filterValues { it == SchemeDeletionState.PENDING }.keys
        val attributes = scheme.directlyDefinedAttributes
        val colors = scheme.directlyDefinedColors
        val facts =
            checkNotNull(capturePruningFacts(parent, attributes, colors)) {
                "Editor deletion census is unavailable"
            }
        val attributeTargets =
            pending.filterIsInstance<SchemeEntry.AttributesEntry>().mapTo(linkedSetOf()) {
                it.key.externalName
            }
        val colorTargets =
            pending.filterIsInstance<SchemeEntry.ColorEntry>().mapTo(
                linkedSetOf(),
            ) { it.key.externalName }
        check(SchemeComparison.permitsPruning(facts, attributeTargets, colorTargets)) {
            "Editor deletion would change fields outside its acknowledged targets"
        }
        for (entry in pending) {
            val current = checkNotNull(captureSchemeCell(entry, attributes, colors))
            check(SchemeAdmission.matchesCell(cells.getValue(entry), current) && ownsEntry(entry)) {
                "Editor deletion target changed during its census"
            }
        }
        check(scheme.parentScheme === parent) { "Editor deletion parent changed during its census" }
    }

    companion object {
        /** Captures seeded cells without effects; rejects unqualified targets. */
        fun prepare(
            scheme: AbstractColorsScheme,
            entries: Set<SchemeEntry>,
            ownsEntry: (SchemeEntry) -> Boolean,
        ): SchemeDeletionCheckpoint {
            check(SwingUtilities.isEventDispatchThread()) { "Editor deletion preparation must run on EDT" }
            val parent =
                checkNotNull((scheme.parentScheme as? AbstractColorsScheme)?.let(::qualifyPruningParent)) {
                    "Editor deletion requires a qualified parent"
                }
            val captured =
                entries.associateWith { entry ->
                    checkNotNull(captureSchemeCell(scheme, entry)) { "Editor deletion target is unavailable" }
                }
            return SchemeDeletionCheckpoint(scheme, parent, captured, ownsEntry).also {
                it.observe()
                it.qualifyCurrent()
            }
        }
    }
}
