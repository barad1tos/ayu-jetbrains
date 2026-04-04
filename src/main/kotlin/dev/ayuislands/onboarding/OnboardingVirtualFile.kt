package dev.ayuislands.onboarding

import com.intellij.testFramework.LightVirtualFile

/** Marker virtual file for the onboarding wizard editor tab. */
internal class OnboardingVirtualFile : LightVirtualFile("Ayu Islands") {
    override fun isWritable(): Boolean = false
}
