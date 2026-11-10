package dev.ayuislands.syntax

import java.util.Collections

/** An immutable style observation whose identity distinguishes otherwise equal writers. */
internal class SyntaxFontSnapshot(
    initialStyles: Map<String, Map<String, Int>>,
) {
    val styles: Map<String, Map<String, Int>> =
        Collections.unmodifiableMap(
            initialStyles.mapValuesTo(linkedMapOf()) { (_, categories) ->
                Collections.unmodifiableMap(LinkedHashMap(categories))
            },
        )
}

/** Owns the semantic styles used by highlighting independently of editor-scheme I/O. */
internal class SyntaxFontState {
    private val lock = Any()

    @Volatile
    var current: SyntaxFontSnapshot = SyntaxFontSnapshot(emptyMap())
        private set

    fun replace(styles: Map<String, Map<String, Int>>) {
        val replacement = SyntaxFontSnapshot(styles)
        synchronized(lock) { current = replacement }
    }

    fun prepare(styles: Map<String, Map<String, Int>>): SyntaxFontWrite {
        val planned = SyntaxFontSnapshot(styles)
        return SyntaxFontWrite(this, current, planned)
    }

    fun replaceIfCurrent(
        expected: SyntaxFontSnapshot,
        replacement: SyntaxFontSnapshot,
    ): Boolean =
        synchronized(lock) {
            if (current !== expected) return@synchronized false
            current = replacement
            true
        }
}

internal enum class SyntaxFontPhase { PREPARED, APPLIED, RESTORED, FOREIGN, ACCEPTED }

/** Retains an exact rollback baseline until the encompassing transaction accepts the write. */
internal class SyntaxFontWrite(
    private val state: SyntaxFontState,
    val before: SyntaxFontSnapshot,
    val planned: SyntaxFontSnapshot,
) {
    @Volatile
    var phase: SyntaxFontPhase = SyntaxFontPhase.PREPARED
        private set

    @Synchronized
    fun apply(): Boolean {
        check(phase == SyntaxFontPhase.PREPARED) { "Syntax font write is not prepared" }
        val applied = state.replaceIfCurrent(before, planned)
        phase = if (applied) SyntaxFontPhase.APPLIED else SyntaxFontPhase.FOREIGN
        return applied
    }

    @Synchronized
    fun restore(): Boolean =
        when (phase) {
            SyntaxFontPhase.PREPARED -> {
                phase = SyntaxFontPhase.RESTORED
                true
            }

            SyntaxFontPhase.APPLIED -> {
                val restored = state.replaceIfCurrent(planned, before)
                phase = if (restored) SyntaxFontPhase.RESTORED else SyntaxFontPhase.FOREIGN
                restored
            }

            SyntaxFontPhase.RESTORED -> {
                true
            }

            SyntaxFontPhase.FOREIGN -> {
                false
            }

            SyntaxFontPhase.ACCEPTED -> {
                error("Accepted syntax font write cannot be restored")
            }
        }

    @Synchronized
    fun accept() {
        check(phase == SyntaxFontPhase.APPLIED || phase == SyntaxFontPhase.ACCEPTED) {
            "Only an applied syntax font write can be accepted"
        }
        phase = SyntaxFontPhase.ACCEPTED
    }
}
