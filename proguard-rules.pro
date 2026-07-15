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

# plugin.xml: applicationService (per-project + per-language accent overrides)
-keep class dev.ayuislands.settings.mappings.AccentMappingsSettings { *; }
# State class — XML serialized, preserve property names for map delegates
-keep class dev.ayuislands.settings.mappings.AccentMappingsState { *; }
# Resolver + detector singletons called from kept classes
-keep class dev.ayuislands.accent.AccentResolver { *; }
-keep class dev.ayuislands.accent.ProjectLanguageDetector { *; }
# plugin.xml: projectService (language detector cache invalidation)
-keep class dev.ayuislands.accent.ProjectLanguageRootChangeListener { *; }
# plugin.xml: applicationService (focus-swap listener)
-keep class dev.ayuislands.settings.mappings.ProjectAccentSwapService { *; }

# MessageBus pub/sub for component-tree refresh. Topic is accessed reflectively by the
# platform's message-bus machinery to deserialize listeners; the fun interface is the
# listener type registered via project.messageBus.connect(this).subscribe(TOPIC, ...).
-keep class dev.ayuislands.ui.ComponentTreeRefresher { *; }
-keep class dev.ayuislands.ui.ComponentTreeRefreshedTopic { *; }
-keep class dev.ayuislands.ui.ComponentTreeRefreshedListener { *; }

# Enums — serialized by name
-keep enum dev.ayuislands.accent.AyuVariant { *; }
-keep enum dev.ayuislands.accent.AccentElementId { *; }
-keep enum dev.ayuislands.accent.AccentGroup { *; }
# Step names appear verbatim in torn-apply/revert WARN logs — obfuscation
# would break the triage grep contract ("Accent apply torn at <step>").
-keep enum dev.ayuislands.accent.AccentApplyStep { *; }
-keep enum dev.ayuislands.glow.GlowStyle { *; }
-keep enum dev.ayuislands.glow.GlowAnimation { *; }
-keep enum dev.ayuislands.glow.GlowTabMode { *; }
-keep enum dev.ayuislands.glow.GlowPreset { *; }
-keep enum dev.ayuislands.glow.GlowPlacement { *; }
-keep enum dev.ayuislands.glow.GlowShape { *; }
-keep enum dev.ayuislands.glow.waveform.WaveformMotion { *; }
-keep enum dev.ayuislands.glow.waveform.WaveformDirection { *; }
-keep enum dev.ayuislands.glow.waveform.WaveformBaseline { *; }
-keep enum dev.ayuislands.font.FontWeight { *; }
-keep enum dev.ayuislands.font.FontPreset { *; }
-keep enum dev.ayuislands.vcs.VcsColorPreset { *; }
-keep enum dev.ayuislands.vcs.VcsColorCategory { *; }
-keep enum dev.ayuislands.syntax.SyntaxPreset { *; }
-keep enum dev.ayuislands.syntax.PrimitiveCategory { *; }
-keep enum dev.ayuislands.syntax.FontStyleOverride { *; }
-keep enum dev.ayuislands.settings.PanelWidthMode { *; }
-keep enum dev.ayuislands.settings.CommitPathDisplayMode { *; }

# plugin.xml: projectService (Project View tweaks)
-keep class dev.ayuislands.projectview.ProjectViewScrollbarManager { *; }
-keep class dev.ayuislands.projectview.ProjectViewScrollbarManager$Companion { *; }

# plugin.xml: projectService (Editor scrollbar tweaks)
-keep class dev.ayuislands.editor.EditorScrollbarManager { *; }
-keep class dev.ayuislands.editor.EditorScrollbarManager$Companion { *; }

# plugin.xml: applicationListeners
-keep class dev.ayuislands.AyuIslandsAppListener { *; }
-keep class dev.ayuislands.AppearanceSyncListener { *; }
-keep class dev.ayuislands.AppearanceSyncService { *; }

# plugin.xml: applicationService (accent rotation)
-keep class dev.ayuislands.rotation.AccentRotationService { *; }

# plugin.xml: applicationService (syntax intensity presets and overlays)
-keep class dev.ayuislands.syntax.SyntaxOverlayLoader { *; }
-keep class dev.ayuislands.syntax.SyntaxIntensityService { *; }
-keep class dev.ayuislands.syntax.SyntaxIntensityState { *; }

# plugin.xml: projectService (workspace panel managers)
-keep class dev.ayuislands.commitpanel.CommitPanelAutoFitManager { *; }
-keep class dev.ayuislands.gitpanel.GitPanelAutoFitManager { *; }

# plugin.xml: fileEditorProvider (onboarding wizard)
-keep class dev.ayuislands.onboarding.OnboardingEditorProvider { *; }

# plugin.xml: fileEditorProvider (free onboarding wizard)
-keep class dev.ayuislands.onboarding.FreeOnboardingEditorProvider { *; }

# plugin.xml: fileEditorProvider (What's New release showcase)
-keep class dev.ayuislands.whatsnew.WhatsNewEditorProvider { *; }

# plugin.xml: action (Tools → Show What's New…)
-keep class dev.ayuislands.whatsnew.ShowWhatsNewAction { *; }

# plugin.xml: action (Tools → Ayu Islands → Rescan Project Language)
-keep class dev.ayuislands.actions.RescanLanguageAction { *; }

# plugin.xml: action + CustomComponentAction (MainToolbarRight Quick-Switcher chip)
-keep class dev.ayuislands.accent.toolbar.QuickSwitcherWidgetAction { *; }

# plugin.xml: statusBarWidgetFactory (Accent diagnostics widget)
-keep class dev.ayuislands.accent.statusbar.AccentStatusBarWidgetFactory { *; }

# Public API singletons (called from kept classes)
-keep class dev.ayuislands.accent.AccentApplicator { *; }
-keep class dev.ayuislands.accent.AccentColor { *; }
-keep class dev.ayuislands.glow.GlowOverlayManager { *; }

# plugin.xml: applicationListeners (license transition)
-keep class dev.ayuislands.licensing.LicenseTransitionListener { *; }

# plugin.xml: applicationListeners (drop language-detector cache on project close)
-keep class dev.ayuislands.accent.ProjectLanguageCacheInvalidator { *; }

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

# Suppress warnings for IDE platform APIs and JDK modules
-dontwarn com.intellij.**
-dontwarn org.jetbrains.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn java.awt.datatransfer.**
