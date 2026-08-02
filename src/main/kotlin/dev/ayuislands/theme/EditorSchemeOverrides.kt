package dev.ayuislands.theme

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil
import dev.ayuislands.accent.AccentElementId
import org.jdom.Element
import java.awt.Color
import java.util.Base64
import java.util.EnumMap
import java.util.IdentityHashMap

internal sealed interface EditorSchemeOwner {
    data class Element(
        val id: AccentElementId,
    ) : EditorSchemeOwner

    data object AlwaysOn : EditorSchemeOwner

    data object Vcs : EditorSchemeOwner
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
            hydrate(scheme)
            val schemeStates = states[scheme] ?: return

            fun restoreEntry(
                entry: SchemeEntry,
                state: OverrideState,
            ): RuntimeException? {
                if (state.owner != owner || state !is OverrideState.Owned) return null
                if (read(scheme, entry) != state.lastWritten) {
                    schemeStates[entry] = OverrideState.Relinquished(owner)
                    scheme.metaProperties.setProperty(entry.metadataKey, encodeState(schemeStates.getValue(entry)))
                    return null
                }
                return try {
                    writeValue(scheme, entry, state.original)
                    schemeStates.remove(entry)
                    scheme.metaProperties.remove(entry.metadataKey)
                    null
                } catch (exception: RuntimeException) {
                    exception
                }
            }

            var firstFailure: RuntimeException? = null
            for ((entry, state) in schemeStates.toList()) {
                val failure = restoreEntry(entry, state) ?: continue
                if (firstFailure == null) firstFailure = failure
            }
            if (schemeStates.isEmpty()) states.remove(scheme)
            firstFailure?.let { throw it }
        }
    }

    fun observeElementEnabled(
        id: AccentElementId,
        isEnabled: Boolean,
        schemes: () -> Iterable<EditorColorsScheme>,
    ) {
        synchronized(lock) {
            val wasEnabled = elementEnabled.put(id, isEnabled)
            if (wasEnabled == false && isEnabled) {
                rearm(EditorSchemeOwner.Element(id), schemes())
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
            hydrate(scheme, entry)
            when (val state = schemeStates[entry]) {
                null -> {
                    val original = read(scheme, entry, isBaseline = true)
                    writeValue(scheme, entry, value)
                    schemeStates[entry] = OverrideState.Owned(owner, original, value.snapshot())
                    scheme.metaProperties.setProperty(entry.metadataKey, encodeState(schemeStates.getValue(entry)))
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
            scheme.metaProperties.setProperty(entry.metadataKey, encodeState(schemeStates.getValue(entry)))
            return
        }
        writeValue(scheme, entry, value)
        schemeStates[entry] = state.copy(lastWritten = value.snapshot())
        scheme.metaProperties.setProperty(entry.metadataKey, encodeState(schemeStates.getValue(entry)))
    }

    private fun read(
        scheme: EditorColorsScheme,
        entry: SchemeEntry,
        isBaseline: Boolean = false,
    ): SchemeValue {
        if (isBaseline && scheme is AbstractColorsScheme) {
            return when (entry) {
                is SchemeEntry.ColorEntry -> {
                    val direct = scheme.directlyDefinedColors[entry.key]
                    when {
                        direct == null || direct === AbstractColorsScheme.INHERITED_COLOR_MARKER ->
                            SchemeValue.InheritedColor
                        direct === AbstractColorsScheme.NULL_COLOR_MARKER -> SchemeValue.ColorValue(null)
                        else -> SchemeValue.ColorValue(direct)
                    }
                }

                is SchemeEntry.AttributesEntry -> {
                    val direct = scheme.directlyDefinedAttributes[entry.key.externalName]
                    if (direct == null || direct === AbstractColorsScheme.INHERITED_ATTRS_MARKER) {
                        SchemeValue.InheritedAttributes
                    } else {
                        SchemeValue.AttributesValue(direct.clone())
                    }
                }
            }
        }
        return when (entry) {
            is SchemeEntry.ColorEntry -> SchemeValue.ColorValue(scheme.getColor(entry.key))
            is SchemeEntry.AttributesEntry ->
                SchemeValue.AttributesValue(
                    scheme.getAttributes(entry.key)?.clone(),
                )
        }
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

            entry is SchemeEntry.ColorEntry && value is SchemeValue.InheritedColor -> {
                scheme.setColor(entry.key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
            }

            entry is SchemeEntry.AttributesEntry && value is SchemeValue.InheritedAttributes -> {
                scheme.setAttributes(entry.key, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            }

            else -> error("Editor scheme entry and value types must match")
        }
    }

    fun rearm(
        owner: EditorSchemeOwner,
        schemes: Iterable<EditorColorsScheme>,
    ) = synchronized(lock) {
        schemes.forEach(::hydrate)
        for ((scheme, schemeStates) in states) {
            schemeStates.entries.removeIf { (entry, state) ->
                val shouldRemove = state is OverrideState.Relinquished && state.owner == owner
                if (shouldRemove) scheme.metaProperties.remove(entry.metadataKey)
                shouldRemove
            }
        }
        states.entries.removeIf { (_, schemeStates) -> schemeStates.isEmpty() }
    }

    fun hasState(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
    ): Boolean =
        synchronized(lock) {
            hydrate(scheme)
            states[scheme]?.values?.any { it.owner == owner } == true
        }

    fun inherit(
        target: EditorColorsScheme,
        canonical: EditorColorsScheme,
    ) = synchronized(lock) {
        hydrate(target)
        hydrate(canonical)
        val targetStates = states.getOrPut(target) { mutableMapOf() }
        for ((entry, state) in states[canonical].orEmpty()) {
            if (entry in targetStates) continue
            if (state is OverrideState.Owned && read(target, entry) != state.lastWritten) continue
            val inherited =
                when (state) {
                    is OverrideState.Owned ->
                        state.copy(
                            original = state.original.snapshot(),
                            lastWritten = state.lastWritten.snapshot(),
                        )
                    is OverrideState.Relinquished -> state
                }
            targetStates[entry] = inherited
            target.metaProperties.setProperty(entry.metadataKey, encodeState(inherited))
        }
    }

    private fun hydrate(scheme: EditorColorsScheme) {
        scheme.metaProperties
            .stringPropertyNames()
            .filter { it.startsWith(METADATA_PREFIX) }
            .forEach { key ->
                val entry = SchemeEntry.fromMetadataKey(key) ?: return@forEach
                hydrate(scheme, entry)
            }
    }

    private fun hydrate(
        scheme: EditorColorsScheme,
        entry: SchemeEntry,
    ) {
        val schemeStates = states.getOrPut(scheme) { mutableMapOf() }
        if (entry in schemeStates) return
        scheme.metaProperties
            .getProperty(entry.metadataKey)
            .takeIf { it?.contains(';') == true }
            ?.let { encoded -> schemeStates[entry] = decodeState(encoded) }
    }

    private sealed interface SchemeEntry {
        val metadataKey: String

        data class ColorEntry(
            val key: ColorKey,
        ) : SchemeEntry {
            override val metadataKey = "$METADATA_PREFIX$COLOR_KIND.${key.externalName}"
        }

        data class AttributesEntry(
            val key: TextAttributesKey,
        ) : SchemeEntry {
            override val metadataKey = "$METADATA_PREFIX$ATTRIBUTES_KIND.${key.externalName}"
        }

        companion object {
            fun fromMetadataKey(key: String): SchemeEntry? {
                val encoded = key.removePrefix(METADATA_PREFIX)
                val kind = encoded.substringBefore('.')
                val name = encoded.substringAfter('.', missingDelimiterValue = "")
                if (name.isEmpty()) return null
                return when (kind) {
                    COLOR_KIND -> ColorEntry(ColorKey.find(name))
                    ATTRIBUTES_KIND -> AttributesEntry(TextAttributesKey.find(name))
                    else -> null
                }
            }
        }
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

        data object InheritedColor : SchemeValue {
            override fun snapshot(): SchemeValue = this
        }

        data object InheritedAttributes : SchemeValue {
            override fun snapshot(): SchemeValue = this
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

    private fun encodeState(state: OverrideState): String {
        val stateOwner = state.owner
        val owner =
            when (stateOwner) {
                is EditorSchemeOwner.Element -> "E:${stateOwner.id.name}"
                EditorSchemeOwner.AlwaysOn -> "A"
                EditorSchemeOwner.Vcs -> "V"
            }
        return when (state) {
            is OverrideState.Owned ->
                listOf("O", owner, encodeValue(state.original), encodeValue(state.lastWritten))
                    .joinToString(";")
            is OverrideState.Relinquished -> "R;$owner"
        }
    }

    private fun decodeState(encoded: String): OverrideState {
        val parts = encoded.split(';')
        val owner = decodeOwner(parts[1])
        return if (parts[0] == "R") {
            OverrideState.Relinquished(owner)
        } else {
            OverrideState.Owned(
                owner,
                decodeValue(parts[STATE_PAYLOAD_INDEX]),
                decodeValue(parts.last()),
            )
        }
    }

    private fun decodeOwner(encoded: String): EditorSchemeOwner =
        when (encoded) {
            "A" -> EditorSchemeOwner.AlwaysOn
            "V" -> EditorSchemeOwner.Vcs
            else -> EditorSchemeOwner.Element(AccentElementId.valueOf(encoded.removePrefix("E:")))
        }

    private fun encodeValue(value: SchemeValue): String =
        when (value) {
            is SchemeValue.ColorValue -> "C,${value.value?.rgb?.toString().orEmpty()}"
            is SchemeValue.AttributesValue ->
                value.value?.let { attributes ->
                    val element = Element("attributes")
                    attributes.writeExternal(element)
                    "A,${Base64.getEncoder().encodeToString(JDOMUtil.writeElement(element).encodeToByteArray())}"
                } ?: "N"
            SchemeValue.InheritedColor -> "IC"
            SchemeValue.InheritedAttributes -> "IA"
        }

    private fun decodeValue(encoded: String): SchemeValue =
        when (encoded) {
            "IC" -> SchemeValue.InheritedColor
            "IA" -> SchemeValue.InheritedAttributes
            else -> {
                when (encoded.first()) {
                    'C' ->
                        SchemeValue.ColorValue(
                            encoded
                                .substringAfter(',')
                                .takeIf(String::isNotEmpty)
                                ?.toInt()
                                ?.let { Color(it, true) },
                        )
                    'N' -> SchemeValue.AttributesValue(null)
                    else -> {
                        val xml = Base64.getDecoder().decode(encoded.substringAfter(',')).decodeToString()
                        SchemeValue.AttributesValue(TextAttributes(JDOMUtil.load(xml)))
                    }
                }
            }
        }

    private const val STATE_PAYLOAD_INDEX = 2
    private const val METADATA_PREFIX = "dev.ayuislands.override.v1."
    private const val COLOR_KIND = "color"
    private const val ATTRIBUTES_KIND = "attributes"
}
