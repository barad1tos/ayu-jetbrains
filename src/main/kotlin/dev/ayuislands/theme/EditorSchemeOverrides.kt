package dev.ayuislands.theme

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.JDOMUtil
import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.AccentElementId
import org.jdom.Element
import java.awt.Color
import java.util.Base64
import java.util.EnumMap
import java.util.IdentityHashMap
import kotlin.coroutines.cancellation.CancellationException

internal sealed interface EditorSchemeOwner {
    data class Element(
        val id: AccentElementId,
    ) : EditorSchemeOwner

    data object AlwaysOn : EditorSchemeOwner

    data object Syntax : EditorSchemeOwner

    data object Vcs : EditorSchemeOwner
}

internal enum class OverrideWriteResult {
    APPLIED,
    RELINQUISHED,
    SKIPPED,
}

internal object EditorSchemeOverrides {
    private val log = logger<EditorSchemeOverrides>()
    private val lock = Any()
    private val states = IdentityHashMap<EditorColorsScheme, MutableMap<SchemeEntry, OverrideState>>()
    private val elementEnabled = EnumMap<AccentElementId, Boolean>(AccentElementId::class.java)
    val checkpoints = AttributeCheckpoints()

    internal data class AttributesCheckpoint(
        val scheme: EditorColorsScheme,
        val entries: Map<SchemeEntry, CheckpointEntry>,
    )

    internal data class CheckpointEntry(
        val directValue: SchemeValue,
        val overrideState: OverrideState?,
        val metadataValue: String?,
    )

    internal class PreviewCheckpoint(
        val original: AttributesCheckpoint,
        val expected: MutableMap<SchemeEntry, CheckpointEntry>,
        val pending: MutableSet<SchemeEntry>,
    )

    internal data class PreviewRestoreAttempt(
        val completed: Set<PreviewCheckpoint>,
        val failures: List<RuntimeException>,
        val cancellation: RuntimeException?,
        val changed: Boolean,
    )

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
    ): OverrideWriteResult =
        write(
            scheme,
            owner,
            SchemeEntry.AttributesEntry(key),
            SchemeValue.AttributesValue(value?.clone()),
        )

    fun restore(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
    ) {
        synchronized(lock) {
            hydrate(scheme)
            val schemeStates = states[scheme] ?: return

            var firstFailure: RuntimeException? = null
            for ((entry, state) in schemeStates.toList()) {
                val failure = restoreEntry(scheme, owner, schemeStates, entry, state) ?: continue
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

    internal class AttributeCheckpoints {
        fun sealPreview(checkpoint: AttributesCheckpoint): PreviewCheckpoint =
            synchronized(lock) {
                PreviewCheckpoint(
                    checkpoint,
                    checkpoint.entries.keys
                        .associateWith { snapshotEntry(checkpoint.scheme, it) }
                        .toMutableMap(),
                    checkpoint.entries.keys.toMutableSet(),
                )
            }

        fun restorePreviews(checkpoints: List<PreviewCheckpoint>): PreviewRestoreAttempt =
            synchronized(lock) { PreviewRestoration().restore(checkpoints) }

        fun capture(
            scheme: EditorColorsScheme,
            owner: EditorSchemeOwner,
            keys: Set<TextAttributesKey>,
        ): AttributesCheckpoint =
            synchronized(lock) {
                hydrate(scheme)
                val schemeStates = states[scheme].orEmpty()
                val entries =
                    buildSet {
                        keys.mapTo(this) { key -> SchemeEntry.AttributesEntry(key) }
                        schemeStates
                            .filterValues { state -> state.owner == owner }
                            .keys
                            .filterIsInstanceTo<SchemeEntry.AttributesEntry, _>(this)
                    }
                AttributesCheckpoint(
                    scheme = scheme,
                    entries =
                        entries.associateWith { entry ->
                            CheckpointEntry(
                                directValue = read(scheme, entry, isBaseline = true).snapshot(),
                                overrideState = schemeStates[entry]?.snapshot(),
                                metadataValue = scheme.metaProperties.getProperty(entry.metadataKey),
                            )
                        },
                )
            }

        fun rollback(checkpoint: AttributesCheckpoint): List<RuntimeException> =
            synchronized(lock) {
                val failures = mutableListOf<RuntimeException>()
                val schemeStates = states.getOrPut(checkpoint.scheme) { mutableMapOf() }
                var cancellation: RuntimeException? = null
                checkpoint.entries.forEach { (entry, saved) ->
                    try {
                        failures += restoreCheckpointEntry(checkpoint.scheme, schemeStates, entry, saved)
                    } catch (failure: RuntimeException) {
                        if (!failure.isCancellation()) throw failure
                        cancellation = cancellation.record(failure)
                    }
                }
                if (schemeStates.isEmpty()) states.remove(checkpoint.scheme)
                cancellation?.let { cancelled ->
                    failures.forEach(cancelled::addSuppressed)
                    throw cancelled
                }
                failures
            }

        private fun restoreCheckpointEntry(
            scheme: EditorColorsScheme,
            schemeStates: MutableMap<SchemeEntry, OverrideState>,
            entry: SchemeEntry,
            saved: CheckpointEntry,
        ): List<RuntimeException> =
            buildList {
                var cancellation =
                    attemptRestore(this) {
                        writeValue(scheme, entry, saved.directValue)
                    }
                cancellation =
                    attemptRestore(this, cancellation) {
                        val state = saved.overrideState
                        if (state == null) {
                            schemeStates.remove(entry)
                        } else {
                            schemeStates[entry] = state.snapshot()
                        }
                        val metadata = saved.metadataValue
                        if (metadata == null) {
                            scheme.metaProperties.remove(entry.metadataKey)
                        } else {
                            scheme.metaProperties.setProperty(entry.metadataKey, metadata)
                        }
                    }
                cancellation?.let { cancelled ->
                    forEach(cancelled::addSuppressed)
                    throw cancelled
                }
            }

        private fun attemptRestore(
            failures: MutableList<RuntimeException>,
            cancellation: RuntimeException? = null,
            action: () -> Unit,
        ): RuntimeException? =
            try {
                action()
                cancellation
            } catch (failure: RuntimeException) {
                if (failure.isCancellation()) {
                    cancellation.record(failure)
                } else {
                    failures += failure
                    cancellation
                }
            }
    }

    private fun snapshotEntry(
        scheme: EditorColorsScheme,
        entry: SchemeEntry,
    ): CheckpointEntry =
        CheckpointEntry(
            read(scheme, entry, isBaseline = true).snapshot(),
            states[scheme]?.get(entry)?.snapshot(),
            scheme.metaProperties.getProperty(entry.metadataKey),
        )

    /** Restores only a continuous chain of owned preview writes, retaining proven progress for retry. */
    private class PreviewRestoration {
        private val completed = mutableSetOf<PreviewCheckpoint>()
        private val failures = mutableListOf<RuntimeException>()
        private var cancellation: RuntimeException? = null
        private var changed = false

        fun restore(checkpoints: List<PreviewCheckpoint>): PreviewRestoreAttempt {
            if (attempt { validateChain(checkpoints) }) {
                val blocked = IdentityHashMap<EditorColorsScheme, MutableSet<SchemeEntry>>()
                checkpoints.asReversed().forEach { checkpoint ->
                    val original = checkpoint.original
                    val blockedEntries = blocked.getOrPut(original.scheme) { mutableSetOf() }
                    if (original.entries.keys.any { it in blockedEntries }) {
                        failures +=
                            IllegalStateException("Syntax preview restore is blocked by an incomplete newer preview")
                        blockedEntries += original.entries.keys
                    } else if (restoreCheckpoint(checkpoint)) {
                        completed += checkpoint
                    } else {
                        blockedEntries += original.entries.keys
                    }
                }
            }
            return PreviewRestoreAttempt(completed.toSet(), failures.toList(), cancellation, changed)
        }

        private fun validateChain(checkpoints: List<PreviewCheckpoint>) {
            val projected = IdentityHashMap<EditorColorsScheme, MutableMap<SchemeEntry, CheckpointEntry>>()
            checkpoints.asReversed().forEach { checkpoint ->
                val original = checkpoint.original
                val entries = projected.getOrPut(original.scheme) { mutableMapOf() }
                original.entries.forEach { (entry, saved) ->
                    val observed = entries.getOrPut(entry) { snapshotEntry(original.scheme, entry) }
                    checkOwned(entry, observed, checkpoint.expected.getValue(entry))
                    entries[entry] = saved
                }
            }
        }

        private fun restoreCheckpoint(checkpoint: PreviewCheckpoint): Boolean {
            var succeeded = true
            checkpoint.pending.toList().forEach { entry ->
                val restored = restoreEntry(checkpoint, entry)
                if (restored) checkpoint.pending.remove(entry) else succeeded = false
            }
            return succeeded
        }

        private fun restoreEntry(
            checkpoint: PreviewCheckpoint,
            entry: SchemeEntry,
        ): Boolean {
            var succeeded = true
            for (component in RestoreComponent.entries) {
                if (!restoreComponent(checkpoint, entry, component)) succeeded = false
            }
            return succeeded
        }

        private fun restoreComponent(
            checkpoint: PreviewCheckpoint,
            entry: SchemeEntry,
            component: RestoreComponent,
        ): Boolean {
            val scheme = checkpoint.original.scheme
            val expected = checkpoint.expected.getValue(entry)
            val desired = expected.restoring(component, checkpoint.original.entries.getValue(entry))
            if (!attempt { checkOwned(entry, snapshotEntry(scheme, entry), expected) }) return false
            if (expected == desired) return true
            val written = attempt { writeComponent(scheme, entry, desired, component) }
            val verified =
                attempt {
                    val observed = snapshotEntry(scheme, entry)
                    if (observed == desired) {
                        checkpoint.expected[entry] = desired
                        changed = true
                    } else {
                        checkOwned(entry, observed, expected)
                        check(!written) { "Syntax preview restore did not update ${entry.metadataKey}" }
                    }
                }
            return written && verified
        }

        private fun attempt(action: () -> Unit): Boolean =
            try {
                action()
                true
            } catch (failure: RuntimeException) {
                if (failure.isCancellation()) cancellation = cancellation.record(failure) else failures += failure
                false
            }

        private fun checkOwned(
            entry: SchemeEntry,
            observed: CheckpointEntry,
            expected: CheckpointEntry,
        ) {
            check(
                observed == expected,
            ) { "Syntax preview restore conflicts with current state for ${entry.metadataKey}" }
        }
    }

    private enum class RestoreComponent { ATTRIBUTES, OWNERSHIP, METADATA }

    private fun CheckpointEntry.restoring(
        component: RestoreComponent,
        saved: CheckpointEntry,
    ): CheckpointEntry =
        when (component) {
            RestoreComponent.ATTRIBUTES -> copy(directValue = saved.directValue.snapshot())
            RestoreComponent.OWNERSHIP -> copy(overrideState = saved.overrideState?.snapshot())
            RestoreComponent.METADATA -> copy(metadataValue = saved.metadataValue)
        }

    private fun writeComponent(
        scheme: EditorColorsScheme,
        entry: SchemeEntry,
        desired: CheckpointEntry,
        component: RestoreComponent,
    ) {
        when (component) {
            RestoreComponent.ATTRIBUTES -> writeValue(scheme, entry, desired.directValue)
            RestoreComponent.OWNERSHIP -> {
                val state = desired.overrideState
                if (state == null) {
                    states[scheme]?.remove(entry)
                    if (states[scheme]?.isEmpty() == true) states.remove(scheme)
                } else {
                    states.getOrPut(scheme) { mutableMapOf() }[entry] = state.snapshot()
                }
            }
            RestoreComponent.METADATA -> {
                val metadata = desired.metadataValue
                if (metadata == null) {
                    scheme.metaProperties.remove(entry.metadataKey)
                } else {
                    scheme.metaProperties.setProperty(entry.metadataKey, metadata)
                }
            }
        }
    }

    private fun write(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        entry: SchemeEntry,
        value: SchemeValue,
    ): OverrideWriteResult {
        synchronized(lock) {
            val schemeStates = states.getOrPut(scheme) { mutableMapOf() }
            hydrate(scheme, entry)
            return when (val state = schemeStates[entry]) {
                null -> {
                    val original = read(scheme, entry, isBaseline = true)
                    writeValue(scheme, entry, value)
                    schemeStates[entry] = OverrideState.Owned(owner, original, value.snapshot())
                    scheme.metaProperties.setProperty(
                        entry.metadataKey,
                        OverrideCodec.encode(schemeStates.getValue(entry)),
                    )
                    OverrideWriteResult.APPLIED
                }

                is OverrideState.Relinquished -> OverrideWriteResult.RELINQUISHED
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
    ): OverrideWriteResult {
        val schemeStates = states.getValue(scheme)
        if (state.owner != owner) {
            log.warn("Skipping editor scheme override because $entry is already owned by ${state.owner}")
            return OverrideWriteResult.SKIPPED
        }
        if (read(scheme, entry) != state.lastWritten) {
            schemeStates[entry] = OverrideState.Relinquished(owner)
            scheme.metaProperties.setProperty(entry.metadataKey, OverrideCodec.encode(schemeStates.getValue(entry)))
            return OverrideWriteResult.RELINQUISHED
        }
        writeValue(scheme, entry, value)
        schemeStates[entry] = state.copy(lastWritten = value.snapshot())
        scheme.metaProperties.setProperty(entry.metadataKey, OverrideCodec.encode(schemeStates.getValue(entry)))
        return OverrideWriteResult.APPLIED
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
        when (entry) {
            is SchemeEntry.ColorEntry ->
                when (value) {
                    is SchemeValue.ColorValue -> scheme.setColor(entry.key, value.value)
                    is SchemeValue.InheritedColor ->
                        scheme.setColor(entry.key, AbstractColorsScheme.INHERITED_COLOR_MARKER)
                    else -> error("Editor scheme entry and value types must match")
                }

            is SchemeEntry.AttributesEntry ->
                when (value) {
                    is SchemeValue.AttributesValue -> scheme.setAttributes(entry.key, value.value?.clone())
                    is SchemeValue.InheritedAttributes ->
                        scheme.setAttributes(entry.key, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
                    else -> error("Editor scheme entry and value types must match")
                }
        }
    }

    fun rearm(
        owner: EditorSchemeOwner,
        schemes: Iterable<EditorColorsScheme>,
        activeAttributes: Map<EditorColorsScheme, Set<String>> = emptyMap(),
    ) {
        synchronized(lock) {
            schemes.forEach(::hydrate)
            val isTargeted = activeAttributes.isNotEmpty()
            for ((scheme, schemeStates) in states) {
                if (isTargeted && scheme !in activeAttributes) continue
                schemeStates.entries.removeIf { (entry, state) ->
                    val activeKeyNames = activeAttributes[scheme]
                    val shouldRemove =
                        state is OverrideState.Relinquished &&
                            state.owner == owner &&
                            (
                                activeKeyNames == null ||
                                    entry !is SchemeEntry.AttributesEntry ||
                                    entry.key.externalName !in activeKeyNames
                            )
                    if (shouldRemove) scheme.metaProperties.remove(entry.metadataKey)
                    shouldRemove
                }
            }
            states.entries.removeIf { (_, schemeStates) -> schemeStates.isEmpty() }
        }
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
            target.metaProperties.setProperty(entry.metadataKey, OverrideCodec.encode(inherited))
        }
    }

    private fun restoreEntry(
        scheme: EditorColorsScheme,
        owner: EditorSchemeOwner,
        schemeStates: MutableMap<SchemeEntry, OverrideState>,
        entry: SchemeEntry,
        state: OverrideState,
    ): RuntimeException? {
        if (state.owner != owner || state !is OverrideState.Owned) return null
        if (read(scheme, entry) != state.lastWritten) {
            schemeStates[entry] = OverrideState.Relinquished(owner)
            scheme.metaProperties.setProperty(entry.metadataKey, OverrideCodec.encode(schemeStates.getValue(entry)))
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
            ?.let { encoded -> schemeStates[entry] = OverrideCodec.decode(encoded) }
    }

    internal sealed interface SchemeEntry {
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

    internal sealed interface SchemeValue {
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

    internal sealed interface OverrideState {
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

    private fun OverrideState.snapshot(): OverrideState =
        when (this) {
            is OverrideState.Owned ->
                copy(
                    original = original.snapshot(),
                    lastWritten = lastWritten.snapshot(),
                )
            is OverrideState.Relinquished -> this
        }

    private object OverrideCodec {
        fun encode(state: OverrideState): String {
            val owner =
                when (val stateOwner = state.owner) {
                    is EditorSchemeOwner.Element -> "E:${stateOwner.id.name}"
                    EditorSchemeOwner.AlwaysOn -> "A"
                    EditorSchemeOwner.Syntax -> "S"
                    EditorSchemeOwner.Vcs -> "V"
                }
            return when (state) {
                is OverrideState.Owned ->
                    listOf("O", owner, encodeValue(state.original), encodeValue(state.lastWritten))
                        .joinToString(";")
                is OverrideState.Relinquished -> "R;$owner"
            }
        }

        fun decode(encoded: String): OverrideState {
            val parts = encoded.split(';')
            val owner =
                when (val encodedOwner = parts[1]) {
                    "A" -> EditorSchemeOwner.AlwaysOn
                    "S" -> EditorSchemeOwner.Syntax
                    "V" -> EditorSchemeOwner.Vcs
                    else -> EditorSchemeOwner.Element(AccentElementId.valueOf(encodedOwner.removePrefix("E:")))
                }
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
                                    ?.let(::decodeArgb),
                            )
                        'N' -> SchemeValue.AttributesValue(null)
                        else -> {
                            val xml = Base64.getDecoder().decode(encoded.substringAfter(',')).decodeToString()
                            SchemeValue.AttributesValue(TextAttributes(JDOMUtil.load(xml)))
                        }
                    }
                }
            }

        private fun decodeArgb(value: Int): Color {
            val rgbHex = (value and RGB_MASK).toString(HEX_RADIX).padStart(RGB_HEX_LENGTH, '0')
            val alpha = value ushr ALPHA_SHIFT and CHANNEL_MASK
            return ColorUtil.toAlpha(ColorUtil.fromHex(rgbHex), alpha)
        }

        private const val RGB_MASK = 0xFFFFFF
        private const val HEX_RADIX = 16
        private const val RGB_HEX_LENGTH = 6
        private const val ALPHA_SHIFT = 24
        private const val CHANNEL_MASK = 0xFF

        private const val STATE_PAYLOAD_INDEX = 2
    }

    private const val METADATA_PREFIX = "dev.ayuislands.override.v1."
    private const val COLOR_KIND = "color"
    private const val ATTRIBUTES_KIND = "attributes"
}

private fun RuntimeException?.record(next: RuntimeException): RuntimeException {
    val first = this ?: return next
    if (first !== next) first.addSuppressed(next)
    return first
}

private fun RuntimeException.isCancellation(): Boolean =
    this is ProcessCanceledException || this is CancellationException
