package dev.ayuislands.settings

/** Feature-owned state that participates in one settings session. */
interface SettingsParticipant {
    fun isModified(): Boolean

    fun apply()

    fun reset()

    fun dispose() = Unit
}
