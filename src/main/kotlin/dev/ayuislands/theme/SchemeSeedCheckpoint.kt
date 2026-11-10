package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import java.util.Collections
import javax.swing.SwingUtilities

/** Seeds only local cells; ownership and metadata remain with the retained entry checkpoint. */
internal class SchemeSeedCheckpoint private constructor(
    private val scheme: AbstractColorsScheme,
    private val parent: DefaultColorsScheme,
    initial: Map<SchemeEntry, SchemeCellFacts>,
    captured: List<SchemeSeedRecord>,
    private val ownsEntry: (SchemeEntry) -> Boolean,
) {
    val records: List<SchemeSeedRecord> = Collections.unmodifiableList(captured.toList())
    private val steps = records.map { Step(it, SchemeSeedProgress(it.before, it.planned)) }
    private val expected = initial.toMutableMap()

    val observations: List<SchemeSeedObservation>
        get() = Collections.unmodifiableList(steps.map { it.progress.observation })

    val expectedCells: Map<SchemeEntry, SchemeCellFacts>
        get() = Collections.unmodifiableMap(expected.toMap())

    val hasPending: Boolean
        get() = steps.any { it.progress.hasPending }

    /** Every record must be durably acknowledged before the first setter. */
    fun apply() {
        check(SwingUtilities.isEventDispatchThread()) { "Editor seeds must run on EDT" }
        steps.forEach(::applyStep)
    }

    private fun isCurrent(entry: SchemeEntry): Boolean = scheme.parentScheme === parent && ownsEntry(entry)

    private fun applyStep(step: Step) {
        if (!step.progress.hasPending) return
        val entry = step.record.entry
        if (!isCurrent(entry)) {
            preserve(entry)
            return
        }
        val observed = captureSchemeCell(scheme, entry)
        if (!isCurrent(entry)) {
            preserve(entry)
            return
        }
        if (!step.progress.prepare(observed)) {
            observeProgress(step)
            return
        }
        step.progress.begin()
        var failure: Throwable? = null
        try {
            writeSchemeCell(scheme, entry, step.record.planned)
            step.progress.returned()
        } catch (writeFailure: RuntimeException) {
            failure = writeFailure
        }
        try {
            step.progress.observe(captureSchemeCell(scheme, entry))
            observeProgress(step)
        } catch (readFailure: RuntimeException) {
            failure = combineUiFailures(failure, readFailure)
        }
        failure?.let { throw it }
    }

    private fun observeProgress(step: Step) {
        when (step.progress.state) {
            SchemeSeedState.SETTLED -> expected[step.record.entry] = step.progress.expected
            SchemeSeedState.FOREIGN -> preserve(step.record.entry)
            SchemeSeedState.PENDING, SchemeSeedState.WRITING -> Unit
        }
    }

    private fun preserve(entry: SchemeEntry) {
        expected.remove(entry)
        steps.filter { it.record.entry == entry }.forEach { it.progress.preserve() }
    }

    private class Step(
        val record: SchemeSeedRecord,
        val progress: SchemeSeedProgress,
    )

    companion object {
        fun prepare(
            scheme: AbstractColorsScheme,
            entries: Set<SchemeEntry>,
            ownsEntry: (SchemeEntry) -> Boolean,
        ): SchemeSeedCheckpoint {
            check(SwingUtilities.isEventDispatchThread()) { "Editor seed preparation must run on EDT" }
            val parent = checkNotNull((scheme.parentScheme as? AbstractColorsScheme)?.let(::qualifyPruningParent))
            val initial =
                entries.associateWith { entry ->
                    check(ownsEntry(entry)) { "Editor seed target is no longer owned" }
                    checkNotNull(captureSchemeCell(scheme, entry)) { "Editor seed target is unavailable" }
                }
            val plan =
                prepareSchemePruning(
                    parent,
                    scheme.directlyDefinedAttributes,
                    scheme.directlyDefinedColors,
                    entries.filterIsInstance<SchemeEntry.AttributesEntry>().mapTo(linkedSetOf()) { it.key },
                    entries.filterIsInstance<SchemeEntry.ColorEntry>().mapTo(linkedSetOf()) { it.key },
                )
            check(plan != SchemePruningPlan.Unavailable) { "Editor seeds cannot preserve unrelated local fields" }
            val records =
                when (plan) {
                    is SchemePruningPlan.Prepared -> captureRecords(plan, initial)
                    SchemePruningPlan.Unchanged -> emptyList()
                    SchemePruningPlan.Unavailable -> error("Editor seeds are unavailable")
                }
            check(scheme.parentScheme === parent) { "Editor seed parent changed during preparation" }
            for ((entry, previous) in initial) {
                val current = checkNotNull(captureSchemeCell(scheme, entry)) { "Editor seed target became unavailable" }
                check(ownsEntry(entry) && SchemeAdmission.matchesCell(previous, current)) {
                    "Editor seed target changed during preparation"
                }
            }
            return SchemeSeedCheckpoint(scheme, parent, initial, records, ownsEntry)
        }

        private fun captureRecords(
            plan: SchemePruningPlan.Prepared,
            initial: Map<SchemeEntry, SchemeCellFacts>,
        ): List<SchemeSeedRecord> =
            buildList {
                val payloads: Map<SchemeEntry, List<SchemeValue>> =
                    buildMap {
                        plan.attributes.forEach { (key, values) ->
                            val attributeValues =
                                values.map { attributes ->
                                    if (attributes === AbstractColorsScheme.INHERITED_ATTRS_MARKER) {
                                        SchemeValue.InheritedAttributes
                                    } else {
                                        SchemeValue.AttributesValue(attributes.clone())
                                    }
                                }
                            put(SchemeEntry.AttributesEntry(key), attributeValues)
                        }
                        plan.colors.forEach { (key, values) ->
                            put(SchemeEntry.ColorEntry(key), values.map { SchemeValue.ColorValue(it) })
                        }
                    }
                for ((entry, values) in payloads) {
                    var before = initial.getValue(entry)
                    for (value in values) {
                        val planned = capturePlannedCell(value)
                        add(SchemeSeedRecord(entry, before, planned))
                        before = planned
                    }
                }
            }
    }
}
