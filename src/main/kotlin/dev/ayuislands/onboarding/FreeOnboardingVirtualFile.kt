package dev.ayuislands.onboarding

import com.intellij.testFramework.LightVirtualFile

/** Marker virtual file for the free onboarding wizard editor tab. */
internal class FreeOnboardingVirtualFile : LightVirtualFile("Ayu Islands") {
    override fun isWritable(): Boolean = false
}
