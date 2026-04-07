package dev.ayuislands.onboarding

import com.intellij.testFramework.LightVirtualFile

/** Marker virtual file for the free onboarding wizard editor tab. */
internal class FreeOnboardingVirtualFile : LightVirtualFile("Welcome to Ayu Islands") {
    override fun isWritable(): Boolean = false

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}
