package dev.ayuislands.theme

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AccentElementId
import java.awt.Color
import java.util.EnumMap
import java.util.IdentityHashMap

internal sealed interface EditorSchemeOwner {
    data class Element(
        val id: AccentElementId,
    ) : EditorSchemeOwner

    data object AlwaysOn : EditorSchemeOwner
}

internal object EditorSchemeOverrides {
    private val log = logger<EditorSchemeOverrides>()
    private val lock = Any()
    private val states = IdentityHashMap<EditorColorsScheme, MutableMap<SchemeEntry, OverrideState>>()
    private val elementEnabled = EnumMap<AccentElementId, Boolean>(AccentElementId::class.java)

    fun writeColor(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        key: ColorKey,
        value: Color?,
    ) {
        write(scheme, owner, SchemeEntry.ColorEntry(key), SchemeValue.ColorValue(value))
    }

    fun writeAttributes(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        key: TextAttributesKey,
        value: TextAttributes?,
    ) {
        write(
            scheme,
            owner,
            SchemeEntry.AttributesEntry(key),
            SchemeValue.AttributesValue(value?.clone()),
        )
    }

    fun restore(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
    ) {
        synchronized(lock) {
            val schemeStates = states[scheme] ?: return
            var firstFailure: RuntimeException? = null
            for ((entry, state) in schemeStates.toList()) {
                if (state.owner != owner || state !is OverrideState.Owned) continue
                if (read(scheme, entry) != state.lastWritten) {
                    schemeStates[entry] = OverrideState.Relinquished(owner)
                    continue
                }
                try {
                    writeValue(scheme, entry, state.original)
                    schemeStates.remove(entry)
                } catch (exception: RuntimeException) {
                    if (firstFailure == null) firstFailure = exception
                }
            }
            if (schemeStates.isEmpty()) states.remove(scheme)
            firstFailure?.let { throw it }
        }
    }

    fun observeElementEnabled(
        id: AccentElementId,
        isEnabled: Boolean,
    ) {
        synchronized(lock) {
            val wasEnabled = elementEnabled.put(id, isEnabled)
            if (wasEnabled == false && isEnabled) {
                rearm(EditorSchemeOwner.Element(id))
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            states.clear()
            elementEnabled.clear()
        }
    }

    private fun write(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        entry: SchemeEntry,
        value: SchemeValue,
    ) {
        synchronized(lock) {
            val schemeStates = states.getOrPut(scheme) { mutableMapOf() }
            when (val state = schemeStates[entry]) {
                null -> {
                    val original = read(scheme, entry)
                    writeValue(scheme, entry, value)
                    schemeStates[entry] = OverrideState.Owned(owner, original, value.snapshot())
                }

                is OverrideState.Relinquished -> Unit
                is OverrideState.Owned -> updateOwned(scheme, owner, entry, value, state)
            }
        }
    }

    private fun updateOwned(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        entry: SchemeEntry,
        value: SchemeValue,
        state: OverrideState.Owned,
    ) {
        val schemeStates = states.getValue(scheme)
        if (state.owner != owner) {
            log.warn("Skipping editor scheme override because $entry is already owned by ${state.owner}")
            return
        }
        if (read(scheme, entry) != state.lastWritten) {
            schemeStates[entry] = OverrideState.Relinquished(owner)
            return
        }
        writeValue(scheme, entry, value)
        schemeStates[entry] = state.copy(lastWritten = value.snapshot())
    }

    private fun read(
        scheme: EditorColorsScheme,
        entry: SchemeEntry,
    ): SchemeValue =
        when (entry) {
            is SchemeEntry.ColorEntry -> SchemeValue.ColorValue(scheme.getColor(entry.key))
            is SchemeEntry.AttributesEntry ->
                SchemeValue.AttributesValue(
                    scheme.getAttributes(entry.key)?.clone(),
                )
        }

    private fun writeValue(
        scheme: EditorColorsScheme,
        entry: SchemeEntry,
        value: SchemeValue,
    ) {
        when {
            entry is SchemeEntry.ColorEntry && value is SchemeValue.ColorValue -> {
                scheme.setColor(entry.key, value.value)
            }

            entry is SchemeEntry.AttributesEntry && value is SchemeValue.AttributesValue -> {
                scheme.setAttributes(entry.key, value.value?.clone())
            }

            else -> error("Editor scheme entry and value types must match")
        }
    }

    private fun rearm(owner: EditorSchemeOwner) {
        for (schemeStates in states.values) {
            schemeStates.entries.removeIf { (_, state) ->
                state is OverrideState.Relinquished && state.owner == owner
            }
        }
        states.entries.removeIf { (_, schemeStates) -> schemeStates.isEmpty() }
    }

    private sealed interface SchemeEntry {
        data class ColorEntry(
            val key: ColorKey,
        ) : SchemeEntry

        data class AttributesEntry(
            val key: TextAttributesKey,
        ) : SchemeEntry
    }

    private sealed interface SchemeValue {
        fun snapshot(): SchemeValue

        data class ColorValue(
            val value: Color?,
        ) : SchemeValue {
            override fun snapshot(): SchemeValue = this
        }

        data class AttributesValue(
            val value: TextAttributes?,
        ) : SchemeValue {
            override fun snapshot(): SchemeValue = AttributesValue(value?.clone())
        }
    }

    private sealed interface OverrideState {
        val owner: EditorSchemeOwner

        data class Owned(
            override val owner: EditorSchemeOwner,
            val original: SchemeValue,
            val lastWritten: SchemeValue,
        ) : OverrideState

        data class Relinquished(
            override val owner: EditorSchemeOwner,
        ) : OverrideState
    }
}
