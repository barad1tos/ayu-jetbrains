package dev.ayuislands.rotation

import kotlin.test.Test
import kotlin.test.assertEquals

class AccentRotationModeTest {
    @Test
    fun `fromName PRESET round-trips`() {
        assertEquals(AccentRotationMode.PRESET, AccentRotationMode.fromName("PRESET"))
    }

    @Test
    fun `fromName RANDOM round-trips`() {
        assertEquals(AccentRotationMode.RANDOM, AccentRotationMode.fromName("RANDOM"))
    }

    @Test
    fun `fromName returns PRESET for null`() {
        assertEquals(AccentRotationMode.PRESET, AccentRotationMode.fromName(null))
    }

    @Test
    fun `fromName returns PRESET for unknown string`() {
        assertEquals(AccentRotationMode.PRESET, AccentRotationMode.fromName("garbage"))
    }

    @Test
    fun `all modes round-trip through name`() {
        for (mode in AccentRotationMode.entries) {
            assertEquals(mode, AccentRotationMode.fromName(mode.name))
        }
    }
}
