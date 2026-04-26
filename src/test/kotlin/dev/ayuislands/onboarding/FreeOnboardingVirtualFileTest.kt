package dev.ayuislands.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * Locks the [FreeOnboardingVirtualFile] identity-based equals contract added in
 * PR #160 to satisfy SonarCloud `kotlin:S2097`. Mirrors [OnboardingVirtualFileTest]
 * and [WhatsNewVirtualFileTest] — same equals + hashCode + identity semantics.
 */
class FreeOnboardingVirtualFileTest {
    @Test
    fun `file name is Welcome to Ayu Islands`() {
        val file = FreeOnboardingVirtualFile()
        assertEquals("Welcome to Ayu Islands", file.name)
    }

    @Test
    fun `file is not writable`() {
        val file = FreeOnboardingVirtualFile()
        assertFalse(file.isWritable)
    }

    @Test
    fun `equals is identity-based`() {
        val first = FreeOnboardingVirtualFile()
        val second = FreeOnboardingVirtualFile()
        assertEquals(first, first)
        assertNotEquals(first, second)
    }

    @Test
    fun `equals rejects null and wrong-type instances`() {
        val file = FreeOnboardingVirtualFile()
        // Indirect null binding — direct `.equals(null)` trips detekt EqualsNullCall.
        val nullArg: Any? = null
        assertFalse(file.equals(nullArg), "Identity-only equals must reject null")
        assertFalse(
            file.equals("not a virtual file"),
            "Identity-only equals must reject non-FreeOnboardingVirtualFile types",
        )
    }

    @Test
    fun `hashCode is stable across calls on the same instance`() {
        val file = FreeOnboardingVirtualFile()
        assertEquals(file.hashCode(), file.hashCode())
    }
}
