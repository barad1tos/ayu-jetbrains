import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.intellij.platform") version "2.10.5"
    kotlin("jvm") version "2.1.10"
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
        ides {
            // Pre-2025.3: IC (Community) still published as a separate distribution
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.3")
            // 2025.3+: IC merged into unified IU (IntelliJ IDEA) distribution
            create(IntelliJPlatformType.IntellijIdea, "2025.3.3")
            // Additional IDE types (latest stable only)
            create(IntelliJPlatformType.PhpStorm, "2025.3.3")
            create(IntelliJPlatformType.WebStorm, "2025.3.3")
            create(IntelliJPlatformType.CLion, "2025.3.3")
            // Language-specific IDEs
            create(IntelliJPlatformType.RustRover, "2025.1.3")
            create(IntelliJPlatformType.GoLand, "2025.1.3")
            create(IntelliJPlatformType.PyCharm, "2025.1.3")
            create(IntelliJPlatformType.DataGrip, "2025.1.3")
            create(IntelliJPlatformType.Rider, "2025.1.3")
            create(IntelliJPlatformType.RubyMine, "2025.1.3")
        }
    }
}
