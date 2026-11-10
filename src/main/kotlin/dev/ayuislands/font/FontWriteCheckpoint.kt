package dev.ayuislands.font

import com.intellij.openapi.editor.colors.EditorColorsScheme

/** Retains an attempt's immediate native state independently from the managed preset baseline. */
internal class FontWriteCheckpoint private constructor(
    private val scheme: EditorColorsScheme,
    private val surface: FontSurface,
    initial: FontWriteRecord,
    isRecovery: Boolean,
) {
    constructor(scheme: EditorColorsScheme, surface: FontSurface, requested: FontSnapshot) :
        this(scheme, surface, capture(scheme, surface, requested), false)

    private val target = initial.target
    private val before = initial.before
    private val preferenceOwner = if (isRecovery && initial.isSettled()) null else surface.preferences(scheme)
    private val effectiveBefore = initial.effectiveBefore
    private val restoreOrigins = initial.origins.toMutableList()
    private val fieldStates =
        initial.fields.toMutableMap().apply {
            if (isRecovery) {
                for ((field, _) in initial.origins.filterIsInstance<FontRestoreOrigin.Field>()) {
                    if (this[field]?.isSettled() != true) this[field] = FontRestoreState.WRITING
                }
            }
        }
    private var applyPhase =
        if (isRecovery && initial.applyPhase == FontWritePhase.PREPARED) FontWritePhase.WRITING else initial.applyPhase
    private var inheritanceState =
        if (isRecovery &&
            !initial.inheritanceState.isSettled() &&
            initial.origins.any { it is FontRestoreOrigin.Mode }
        ) {
            FontRestoreState.WRITING
        } else {
            initial.inheritanceState
        }

    val record: FontWriteRecord
        get() =
            freeze(
                FontWriteRecord(
                    before,
                    target,
                    effectiveBefore,
                    applyPhase,
                    inheritanceState,
                    fieldStates,
                    restoreOrigins,
                ),
            )

    init {
        if (!isRecovery && before is FontSnapshot.Explicit) {
            val originalOwner = requireNotNull(preferenceOwner)
            val restored = before.preferences.toPreferences()
            check(FontPreferencePolicy.matches(before.preferences, FontData.captureExact(restored))) {
                "Native fonts cannot retain the exact baseline"
            }
            val names = before.preferences.effectiveFamilies + before.preferences.families.map(FontFamily::name)
            check(
                names.all { name ->
                    originalOwner.hasSize(name) == restored.hasSize(name) &&
                        originalOwner.getSize2D(name).toRawBits() == restored.getSize2D(name).toRawBits()
                },
            ) { "Native font copy omits a named size" }
        }
    }

    fun apply() {
        check(applyPhase == FontWritePhase.PREPARED)
        check(
            FontPreferencePolicy.matches(surface.captureExact(scheme), before) &&
                surface.preferences(scheme) === preferenceOwner,
        )
        if (!FontPreferencePolicy.matches(before, target)) {
            applyPhase = FontWritePhase.WRITING
            surface.write(scheme, target)
        }
        applyPhase = FontWritePhase.SETTLED
    }

    /** Captures restorative writes; the outer owner acknowledges appended evidence before restore. */
    fun prepareRestore(blocked: Set<FontPreferenceField> = emptySet()) {
        if (applyPhase == FontWritePhase.PREPARED || record.isSettled()) return
        if (before is FontSnapshot.Explicit && target is FontSnapshot.Explicit) {
            prepareFields(blocked, before.preferences, target.preferences)
        } else if (blocked.isEmpty()) {
            prepareMode()
        }
    }

    private fun prepareFields(
        blocked: Set<FontPreferenceField>,
        baseline: FontData,
        applied: FontData,
    ) {
        if (surface.preferences(scheme) !== preferenceOwner) {
            preserveReplacement(blocked)
            return
        }
        val current = FontData.captureExact(surface.preferences(scheme))
        for (field in FontPreferenceField.entries.reversed()) {
            if (field in blocked || fieldStates[field]?.isSettled() == true) continue
            when (classifyField(field, current, baseline, applied)) {
                FontRestoreDecision.RESTORED -> fieldStates[field] = FontRestoreState.RESTORED
                FontRestoreDecision.FOREIGN -> fieldStates[field] = FontRestoreState.FOREIGN
                FontRestoreDecision.OWNED -> {
                    if (restoreOrigins.none { it is FontRestoreOrigin.Field && it.field == field }) {
                        restoreOrigins.add(FontRestoreOrigin.Field(field, current))
                    }
                }
                FontRestoreDecision.UNRESOLVED -> Unit
            }
        }
    }

    private fun prepareMode() {
        val current = surface.captureExact(scheme)
        if (FontPreferencePolicy.matches(current, before)) {
            inheritanceState = FontRestoreState.RESTORED
            return
        }
        val recognized = canRestoreInheritance(current)
        if (inheritanceState == FontRestoreState.WRITING && recognized) return
        if (recognized || FontPreferencePolicy.matches(current, target)) {
            val effective =
                (current as? FontSnapshot.Explicit)?.preferences ?: FontData.captureExact(surface.preferences(scheme))
            val previous = restoreOrigins.filterIsInstance<FontRestoreOrigin.Mode>().lastOrNull()
            if (previous == null ||
                !FontPreferencePolicy.matches(previous.snapshot, current) ||
                !FontPreferencePolicy.matches(previous.effective, effective)
            ) {
                restoreOrigins.add(FontRestoreOrigin.Mode(current, effective))
            }
        } else if (applyPhase == FontWritePhase.SETTLED && inheritanceState == FontRestoreState.PENDING) {
            inheritanceState = FontRestoreState.FOREIGN
        }
    }

    fun restore(blocked: Set<FontPreferenceField> = emptySet()): Boolean {
        if (applyPhase == FontWritePhase.PREPARED) {
            inheritanceState = FontRestoreState.RESTORED
            FontPreferenceField.entries.forEach { fieldStates[it] = FontRestoreState.RESTORED }
            return true
        }
        if (record.isSettled()) return true
        if (before !is FontSnapshot.Explicit || target !is FontSnapshot.Explicit) {
            return blocked.isEmpty() && restoreInheritance()
        }
        if (surface.preferences(scheme) !== preferenceOwner) return preserveReplacement(blocked)
        var settled = true
        for (field in FontPreferenceField.entries.reversed()) {
            if (fieldStates[field]?.isSettled() == true) continue
            val result = field !in blocked && restoreField(field, before.preferences, target.preferences)
            settled = result && settled
        }
        return settled
    }

    private fun preserveReplacement(blocked: Set<FontPreferenceField>): Boolean {
        val pending = FontPreferenceField.entries.filterNot { fieldStates[it]?.isSettled() == true }
        for (field in pending) {
            if (field !in blocked) fieldStates[field] = FontRestoreState.FOREIGN
        }
        return pending.none { it in blocked }
    }

    private fun restoreInheritance(): Boolean =
        try {
            val current = surface.captureExact(scheme)
            when {
                FontPreferencePolicy.matches(current, before) -> inheritanceState = FontRestoreState.RESTORED
                canRestoreInheritance(current) && restoreOrigins.any { it is FontRestoreOrigin.Mode } -> {
                    inheritanceState = FontRestoreState.WRITING
                    surface.write(scheme, before)
                    if (FontPreferencePolicy.matches(surface.captureExact(scheme), before)) {
                        inheritanceState = FontRestoreState.RESTORED
                    }
                }
                current == FontSnapshot.Inherited && target == current -> Unit
                applyPhase == FontWritePhase.SETTLED && inheritanceState == FontRestoreState.PENDING ->
                    inheritanceState = FontRestoreState.FOREIGN
            }
            inheritanceState.isSettled()
        } catch (_: RuntimeException) {
            false
        }

    private fun canRestoreInheritance(current: FontSnapshot): Boolean {
        val origin = restoreOrigins.filterIsInstance<FontRestoreOrigin.Mode>().lastOrNull()
        if (current == FontSnapshot.Inherited) {
            val effective = FontData.captureExact(surface.preferences(scheme))
            return target == FontSnapshot.Inherited &&
                origin != null &&
                origin.snapshot == FontSnapshot.Inherited &&
                FontPreferencePolicy.matches(effective, origin.effective)
        }
        val actual = (current as FontSnapshot.Explicit).preferences
        if (origin != null && before == FontSnapshot.Inherited) {
            return FontPreferencePolicy.matches(current, origin.snapshot)
        }
        return when (before) {
            FontSnapshot.Inherited ->
                target is FontSnapshot.Explicit &&
                    FontPreferencePolicy.copyWrites(effectiveBefore, target.preferences).any {
                        FontPreferencePolicy.matches(actual, it)
                    }
            is FontSnapshot.Explicit ->
                origin != null &&
                    FontPreferencePolicy.copyWrites(origin.effective, before.preferences).any {
                        FontPreferencePolicy.matches(actual, it)
                    }
        }
    }

    private fun restoreField(
        field: FontPreferenceField,
        baseline: FontData,
        applied: FontData,
    ): Boolean {
        return try {
            if (surface.preferences(scheme) !== preferenceOwner) {
                fieldStates[field] = FontRestoreState.FOREIGN
                return true
            }
            val current = FontData.captureExact(surface.preferences(scheme))
            var decision = classifyField(field, current, baseline, applied)
            if (decision == FontRestoreDecision.OWNED) {
                if (restoreOrigins.none { it is FontRestoreOrigin.Field && it.field == field }) return false
                fieldStates[field] = FontRestoreState.WRITING
                surface.writePreferences(
                    scheme,
                    FontPreferenceWrites(preferenceOwner, field, current, baseline),
                )
                val actual = surface.preferences(scheme)
                decision =
                    if (actual !== preferenceOwner) {
                        FontRestoreDecision.FOREIGN
                    } else {
                        classifyField(field, FontData.captureExact(actual), baseline, applied)
                    }
            }
            when (decision) {
                FontRestoreDecision.RESTORED -> fieldStates[field] = FontRestoreState.RESTORED
                FontRestoreDecision.FOREIGN -> fieldStates[field] = FontRestoreState.FOREIGN
                FontRestoreDecision.UNRESOLVED, FontRestoreDecision.OWNED -> return false
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun classifyField(
        field: FontPreferenceField,
        current: FontData,
        baseline: FontData,
        applied: FontData,
    ): FontRestoreDecision {
        val origin =
            restoreOrigins
                .filterIsInstance<FontRestoreOrigin.Field>()
                .lastOrNull { it.field == field }
                ?.preferences
        val isInterrupted =
            fieldStates[field] == FontRestoreState.WRITING || origin == null && applyPhase != FontWritePhase.SETTLED
        val evidence =
            FontFieldEvidence(
                baseline,
                applied,
                origin,
                isInterrupted,
            )
        return FontPreferencePolicy.restoreDecision(
            field,
            current,
            evidence,
        )
    }

    companion object {
        fun recover(
            scheme: EditorColorsScheme,
            surface: FontSurface,
            record: FontWriteRecord,
        ): FontWriteCheckpoint = FontWriteCheckpoint(scheme, surface, freeze(record), true)

        private fun capture(
            scheme: EditorColorsScheme,
            surface: FontSurface,
            requested: FontSnapshot,
        ): FontWriteRecord {
            val before = surface.captureExact(scheme)
            val target =
                when (requested) {
                    FontSnapshot.Inherited -> requested
                    is FontSnapshot.Explicit ->
                        FontSnapshot.Explicit(
                            FontData.captureExact(requested.preferences.toPreferences()),
                        )
                }
            return FontWriteRecord(
                before,
                target,
                (before as? FontSnapshot.Explicit)?.preferences ?: FontData.captureExact(surface.preferences(scheme)),
                FontWritePhase.PREPARED,
                FontRestoreState.PENDING,
                emptyMap(),
                emptyList(),
            )
        }
    }
}

private fun FontRestoreState.isSettled(): Boolean =
    this == FontRestoreState.RESTORED || this == FontRestoreState.FOREIGN

private fun FontWriteRecord.isSettled(): Boolean =
    if (before is FontSnapshot.Explicit && target is FontSnapshot.Explicit) {
        FontPreferenceField.entries.all { fields[it]?.isSettled() == true }
    } else {
        inheritanceState.isSettled()
    }

private fun freeze(record: FontWriteRecord): FontWriteRecord =
    record.copy(
        before = freeze(record.before),
        target = freeze(record.target),
        effectiveBefore = freeze(record.effectiveBefore),
        fields = java.util.Map.copyOf(record.fields),
        origins = java.util.List.copyOf(record.origins.map(::freeze)),
    )

private fun freeze(snapshot: FontSnapshot): FontSnapshot =
    when (snapshot) {
        FontSnapshot.Inherited -> snapshot
        is FontSnapshot.Explicit -> FontSnapshot.Explicit(freeze(snapshot.preferences))
    }

private fun freeze(data: FontData): FontData =
    data.copy(
        effectiveFamilies = java.util.List.copyOf(data.effectiveFamilies),
        families = java.util.List.copyOf(data.families),
    )

private fun freeze(origin: FontRestoreOrigin): FontRestoreOrigin =
    when (origin) {
        is FontRestoreOrigin.Mode ->
            origin.copy(
                snapshot = freeze(origin.snapshot),
                effective = freeze(origin.effective),
            )
        is FontRestoreOrigin.Field -> origin.copy(preferences = freeze(origin.preferences))
    }
