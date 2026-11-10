package dev.ayuislands.glow

import com.intellij.openapi.Disposable
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection

/** Retains partial native registration until it can be disconnected before an explicit retry. */
internal class GlowSubscription {
    private var connection: MessageBusConnection? = null
    private var isComplete = false

    fun register(
        bus: MessageBus,
        parent: Disposable,
        subscribe: (MessageBusConnection) -> Unit,
    ) {
        if (isComplete) return
        connection?.disconnect()
        connection = null
        val created = bus.connect(parent)
        connection = created
        subscribe(created)
        isComplete = true
    }
}
