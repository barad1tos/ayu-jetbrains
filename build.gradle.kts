import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.8.2")
    }
}

plugins {
    id("org.jetbrains.intellij.platform") version "2.12.0"
    kotlin("jvm") version "2.3.10"
    id("org.jetbrains.kotlinx.kover") version "0.9.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.1.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        pluginVerifier()
    }
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:${providers.gradleProperty("mockkVersion").get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    useJUnitPlatform {
        includeTags("integration")
    }
    group = "verification"
    description = "Run integration tests with IDE fixtures"
}

tasks {
    named<JavaExec>("runIde") {
        jvmArgumentProviders +=
            CommandLineArgumentProvider {
                listOf("-Dayu.islands.dev=true")
            }
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    buildSearchableOptions = false

    pluginVerification {
        freeArgs =
            listOf(
                "-mute",
                "ReleaseVersionAndPluginVersionMismatch,ExperimentalApiUsage",
            )
        ides {
            val group = providers.systemProperty("verifyGroup").orNull
            if (group != "B") {
                // Group A: IC/IU + major IDEs
                create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
                create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.3")
                create(IntelliJPlatformType.IntellijIdea, "2025.3.3")
                create(IntelliJPlatformType.PhpStorm, "2025.3.3")
                create(IntelliJPlatformType.WebStorm, "2025.3.3")
                create(IntelliJPlatformType.CLion, "2025.3.3")
            }
            if (group != "A") {
                // Group B: Language-specific IDEs
                create(IntelliJPlatformType.RustRover, "2025.1.3")
                create(IntelliJPlatformType.GoLand, "2025.1.3")
                create(IntelliJPlatformType.PyCharm, "2025.1.3")
                create(IntelliJPlatformType.DataGrip, "2025.1.3")
                create(IntelliJPlatformType.Rider, "2025.1.3")
                create(IntelliJPlatformType.RubyMine, "2025.1.3")
            }
        }
    }
}

detekt {
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = true
}

kover {
    reports {
        filters {
            excludes {
                // Pure-rendering UI panels (no extractable logic, visual-only)
                classes(
                    "dev.ayuislands.settings.AyuIslandsPreviewPanel*",
                    "dev.ayuislands.settings.AyuIslandsEffectsPanel*",
                    "dev.ayuislands.settings.AyuIslandsElementsPanel*",
                    "dev.ayuislands.settings.AyuIslandsAccentPanel*",
                    "dev.ayuislands.settings.AyuIslandsAppearancePanel*",
                    "dev.ayuislands.settings.AyuIslandsSettingsPanel*",
                    "dev.ayuislands.settings.AyuIslandsConfigurable*",
                    "dev.ayuislands.settings.PresetButtonBar*",
                    "dev.ayuislands.settings.GlowGroupPanel*",
                    // Data class in EffectsPanel file, pure UI config
                    "dev.ayuislands.settings.SliderConfig*",
                    // Glow rendering (Graphics2D paint, animation overlay)
                    "dev.ayuislands.glow.GlowOverlayManager*",
                    "dev.ayuislands.glow.GlowGlassPane*",
                    "dev.ayuislands.glow.GlowFocusBorder*",
                    // macOS-only (SystemInfo.isMac guard, untestable on Linux CI)
                    "dev.ayuislands.accent.CachedMacReader*",
                    "dev.ayuislands.accent.SystemAccentProvider*",
                    "dev.ayuislands.accent.SystemAppearanceProvider*",
                    // Pure-rendering UI panel (Integrations settings)
                    "dev.ayuislands.settings.IntegrationsPanel*",
                    // Pure-rendering UI panels (Font preset settings)
                    "dev.ayuislands.settings.FontPresetPanel*",
                    "dev.ayuislands.settings.FontPreviewComponent*",
                    // IDE glue (EditorColorsManager / ApplicationManager singletons)
                    "dev.ayuislands.font.FontPresetApplicator*",
                    // IDE glue (thin event listeners, startup activity)
                    "dev.ayuislands.AyuIslandsStartupActivity*",
                    "dev.ayuislands.AyuIslandsLafListener*",
                    "dev.ayuislands.AppearanceSyncListener*",
                    // LicenseChecker: public API tested, private crypto is JetBrains boilerplate
                    "dev.ayuislands.licensing.LicenseChecker*",
                )
            }
        }

        total {
            xml {
                onCheck = false
                xmlFile = layout.buildDirectory.file("reports/kover/report.xml")
            }
        }

        verify {
            rule("Line coverage") {
                minBound(80)
            }
        }
    }
}

val proguardTask =
    tasks.register<proguard.gradle.ProGuardTask>("proguard") {
        group = "build"
        description = "Obfuscate JAR with ProGuard"
        dependsOn("jar")

        val jarTask = tasks.named<Jar>("jar").get()
        val jarFile = jarTask.archiveFile.get().asFile
        val tempOut =
            layout.buildDirectory
                .file("proguard/obfuscated.jar")
                .get()
                .asFile

        injars(jarFile)
        outjars(tempOut)

        val javaHome =
            org.gradle.internal.jvm.Jvm
                .current()
                .javaHome.absolutePath
        libraryjars(
            mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
            "$javaHome/jmods/java.base.jmod",
        )
        libraryjars(
            mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
            "$javaHome/jmods/java.desktop.jmod",
        )

        libraryjars(configurations.getByName("compileClasspath"))

        configuration(file("proguard-rules.pro"))

        doLast {
            tempOut.copyTo(jarFile, overwrite = true)
        }
    }

tasks.named("prepareSandbox") {
    dependsOn(proguardTask)
}
