package dev.ayuislands.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OnboardingVirtualFileTest {
    @Test
    fun `file name is Ayu Islands`() {
        val file = OnboardingVirtualFile()
        assertEquals("Ayu Islands", file.name)
    }

    @Test
    fun `file is not writable`() {
        val file = OnboardingVirtualFile()
        assertFalse(file.isWritable)
    }
}
