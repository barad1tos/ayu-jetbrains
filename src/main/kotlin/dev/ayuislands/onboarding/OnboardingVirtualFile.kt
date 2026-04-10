package dev.ayuislands.onboarding

import com.intellij.testFramework.LightVirtualFile

/** Marker virtual file for the premium onboarding wizard editor tab. */
internal class OnboardingVirtualFile : LightVirtualFile("Ayu Islands Premium") {
    override fun isWritable(): Boolean = false

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}
