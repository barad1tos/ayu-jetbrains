package dev.ayuislands.whatsnew

import com.intellij.testFramework.LightVirtualFile

/**
 * Marker virtual file for the What's New release-showcase editor tab.
 * Mirrors [dev.ayuislands.onboarding.OnboardingVirtualFile] — non-writable,
 * identity-equals (so each opener gets its own tab if needed, but in practice
 * the orchestrator gates to one).
 */
internal class WhatsNewVirtualFile : LightVirtualFile("What's New in Ayu Islands") {
    override fun isWritable(): Boolean = false

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}
