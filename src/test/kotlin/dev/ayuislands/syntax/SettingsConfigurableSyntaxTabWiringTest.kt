package dev.ayuislands.syntax

import dev.ayuislands.settings.AyuIslandsSyntaxPanel
import dev.ayuislands.settings.SettingsParticipant
import kotlin.test.Test
import kotlin.test.assertTrue

/** Compile-time coverage for Syntax participation in the shared settings lifecycle. */
class SettingsConfigurableSyntaxTabWiringTest {
    @Test
    fun `AyuIslandsSyntaxPanel is a settings participant`() {
        val participant: SettingsParticipant = AyuIslandsSyntaxPanel()

        assertTrue(participant is AyuIslandsSyntaxPanel)
    }
}
