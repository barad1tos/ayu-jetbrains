# ProGuard rules for Ayu Islands JetBrains plugin
# Goal: obfuscate licensing internals, keep all plugin.xml references

-dontoptimize
-dontshrink

# Kotlin metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# plugin.xml: applicationConfigurable, applicationService, postStartupActivity
-keep class dev.ayuislands.settings.AyuIslandsConfigurable { *; }
-keep class dev.ayuislands.settings.AyuIslandsSettings { *; }
-keep class dev.ayuislands.AyuIslandsStartupActivity { *; }
-keep class dev.ayuislands.AyuIslandsLafListener { *; }

# Extension point interface + all implementations
-keep interface dev.ayuislands.accent.AccentElement { *; }
-keep class dev.ayuislands.accent.elements.** { *; }

# State class — XML serialized, all property names must be preserved
-keep class dev.ayuislands.settings.AyuIslandsState { *; }

# Enums — serialized by name
-keep enum dev.ayuislands.accent.AyuVariant { *; }
-keep enum dev.ayuislands.accent.AccentElementId { *; }
-keep enum dev.ayuislands.accent.AccentGroup { *; }
-keep enum dev.ayuislands.glow.GlowStyle { *; }
-keep enum dev.ayuislands.glow.GlowAnimation { *; }
-keep enum dev.ayuislands.glow.GlowTabMode { *; }
-keep enum dev.ayuislands.glow.GlowPreset { *; }

# Public API singletons (called from kept classes)
-keep class dev.ayuislands.accent.AccentApplicator { *; }
-keep class dev.ayuislands.accent.AccentColor { *; }
-keep class dev.ayuislands.glow.GlowOverlayManager { *; }

# LicenseChecker — keep class name + public API, obfuscate private crypto internals
# Kotlin `object` compiles to instance methods (public final), not static
-keep class dev.ayuislands.licensing.LicenseChecker {
    public static final java.lang.String PRODUCT_CODE;
    public final *** isLicensed();
    public final *** isLicensedOrGrace();
    public final *** requestLicense(java.lang.String);
    public final *** notifyTrialExpired(...);
    public final *** revertToFreeDefaults(...);
}

# Kotlin singleton INSTANCE fields
-keepclassmembers class * {
    public static final ** INSTANCE;
}

# Suppress warnings for IDE platform APIs
-dontwarn com.intellij.**
-dontwarn org.jetbrains.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
