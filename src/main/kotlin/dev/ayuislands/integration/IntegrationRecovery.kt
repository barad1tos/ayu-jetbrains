package dev.ayuislands.integration

import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KMutableProperty0

/** Unwraps native integration failures; fatal errors escape without further effects. */
internal fun captureFailure(action: () -> Unit): Throwable? =
    try {
        invokeNative(action)
        null
    } catch (exception: ReflectiveOperationException) {
        exception
    } catch (exception: RuntimeException) {
        exception
    }

internal fun <T> invokeNative(action: () -> T): T =
    try {
        action()
    } catch (exception: InvocationTargetException) {
        throw exception.cause ?: exception
    }

/** Holds raw settings values without normalizing unknown strings or unrelated preferences. */
internal class SettingsCheckpoint(
    private val ownership: KMutableProperty0<String?>,
    vararg fields: SettingCheckpoint<*>,
) {
    private val marker = SettingCheckpoint(ownership)
    private val entries = listOf<SettingCheckpoint<*>>(marker) + fields

    fun capture() = entries.forEach { it.capture() }

    fun restore(foreignNative: Boolean) {
        val ownsMarker = marker.isExpected
        val changed = entries.any { !it.isExpected }
        entries.forEach { it.restore() }
        if (ownsMarker && (foreignNative || changed)) ownership.set(IntegrationOwnership.SUSPENDED.name)
    }

    fun suspendIfOwned() {
        if (marker.isExpected) ownership.set(IntegrationOwnership.SUSPENDED.name)
    }
}

/** Each entry keeps its own property type even in a heterogeneous settings snapshot. */
internal class SettingCheckpoint<T>(
    private val property: KMutableProperty0<T>,
) {
    private val original = property.get()
    private var expected = original

    val isExpected: Boolean
        get() = property.get() == expected

    fun capture() {
        expected = property.get()
    }

    fun restore() {
        if (isExpected) property.set(original)
    }
}
