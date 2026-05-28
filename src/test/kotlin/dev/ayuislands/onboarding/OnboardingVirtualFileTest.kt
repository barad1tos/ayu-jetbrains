package dev.ayuislands.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class OnboardingVirtualFileTest {
    @Test
    fun `file name is Ayu Islands Premium`() {
        val file = OnboardingVirtualFile()
        assertEquals("Ayu Islands Premium", file.name)
    }

    @Test
    fun `file is not writable`() {
        val file = OnboardingVirtualFile()
        assertFalse(file.isWritable)
    }

    @Test
    fun `equals is identity-based`() {
        val first = OnboardingVirtualFile()
        val second = OnboardingVirtualFile()
        assertNotEquals(first, second)
    }

    @Test
    fun `equals rejects null and wrong-type instances`() {
        // PR #160 added the `is OnboardingVirtualFile` type guard for SonarCloud S2097.
        // Locks both branches of the guard against future revert/drop.
        val file = OnboardingVirtualFile()
        // Indirect null binding — direct `.equals(null)` trips detekt EqualsNullCall.
        val nullArg: Any? = null
        assertFalse(file.equals(nullArg), "Identity-only equals must reject null")
        assertFalse(
            file.equals("not a virtual file"),
            "Identity-only equals must reject non-OnboardingVirtualFile types",
        )
    }
}
