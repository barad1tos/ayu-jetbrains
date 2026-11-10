import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import io.gitlab.arturbosch.detekt.DetektGenerateConfigTask
import kotlinx.kover.features.jvm.AggregationType
import kotlinx.kover.features.jvm.Bound
import kotlinx.kover.features.jvm.ClassFilters
import kotlinx.kover.features.jvm.CoverageUnit
import kotlinx.kover.features.jvm.GroupingBy
import kotlinx.kover.features.jvm.KoverLegacyFeatures
import kotlinx.kover.features.jvm.Rule
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Named
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.ReportingBasePlugin
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files

buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.10.0")
        classpath("org.jetbrains.kotlinx:kover-features-jvm:${providers.gradleProperty("koverVersion").get()}")
    }
}

plugins {
    // 2.18.1 breaks the verifyPlugin compatibility matrix; keep in sync with Dependabot.
    id("org.jetbrains.intellij.platform") version "2.20.1"
    kotlin("jvm") version "2.4.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version providers.gradleProperty("detektVersion").get() apply false
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val integrationPlugins: List<Pair<String, String>> =
    listOf(
        "Dart" to "506.1.0",
        "com.intellij.lang.jsgraphql" to "251.23774.318",
        "com.intellij.plugin.adernov.powershell" to "2.10.0",
        "com.redhat.devtools.lsp4ij" to "0.20.1",
        "dev.j-a.swift" to "1.11.1.435-251",
        "org.intellij.scala" to "2025.1.22",
        "org.intellij.plugins.hcl" to "251.23774.426",
        "pl.thedeem.dql" to "1.10.0",
        "com.nasller.CodeGlancePro" to "2.0.2",
        "indent-rainbow.indent-rainbow" to "2.2.0",
    )

val syntaxPreviewTestPlugins: List<String> =
    listOf(
        "com.intellij.modules.json",
        "com.intellij.properties",
        "com.jetbrains.sh",
        "org.intellij.plugins.markdown",
        "org.jetbrains.kotlin",
        "org.jetbrains.plugins.yaml",
    )

val verifiedIdeGroups: Map<String, List<Pair<IntelliJPlatformType, String>>> =
    mapOf(
        "A1" to
            listOf(
                IntelliJPlatformType.IntellijIdeaCommunity to "2025.1",
                IntelliJPlatformType.IntellijIdeaCommunity to "2025.2.3",
                IntelliJPlatformType.IntellijIdea to "2025.3.3",
            ),
        "A2" to
            listOf(
                IntelliJPlatformType.PhpStorm to "2025.3.3",
                IntelliJPlatformType.WebStorm to "2025.3.3",
                IntelliJPlatformType.CLion to "2025.3.3",
            ),
        "B" to
            listOf(
                IntelliJPlatformType.GoLand to "2025.1.3",
                IntelliJPlatformType.PyCharm to "2025.1.3",
                IntelliJPlatformType.DataGrip to "2025.1.3",
                IntelliJPlatformType.Rider to "2025.1.3",
                IntelliJPlatformType.RubyMine to "2025.1.3",
            ),
    )

data class SyntaxRuntimeTarget(
    val id: String,
    val taskName: String,
    val ide: Pair<IntelliJPlatformType, String>,
    val bundledPlugins: List<String> = emptyList(),
    val enabledMarketplacePlugins: Set<String> = emptySet(),
)

fun verifiedIde(type: IntelliJPlatformType): Pair<IntelliJPlatformType, String> =
    verifiedIdeGroups
        .values
        .flatten()
        .single { (candidateType) -> candidateType == type }

fun testFrameworkVersionRange(buildNumber: String): String = "[${buildNumber.substringBefore('.')}, $buildNumber]"

val communityIde: Pair<IntelliJPlatformType, String> = verifiedIdeGroups.getValue("A1").first()
val communityBundledPlugins = listOf("org.intellij.groovy") + syntaxPreviewTestPlugins
val webStormBundledPlugins: List<String> =
    listOf(
        "AngularJS",
        "JavaScript",
        "com.intellij.css",
        "com.jetbrains.restClient",
        "gherkin",
        "org.jetbrains.plugins.sass",
    )
val ideaUltimateBundledPlugins: List<String> =
    listOf(
        "Docker",
        "com.intellij.freemarker",
        "com.intellij.quarkus",
        "com.intellij.velocity",
        "idea.plugin.protoeditor",
        "org.editorconfig.editorconfigjetbrains",
    )
val syntaxRuntimeTargets: List<SyntaxRuntimeTarget> =
    listOf(
        SyntaxRuntimeTarget(
            id = "idea-community",
            taskName = "syntaxContractIdeaCommunity",
            ide = communityIde,
            bundledPlugins = communityBundledPlugins,
        ),
        SyntaxRuntimeTarget(
            id = "idea-ultimate",
            taskName = "syntaxContractIdeaUltimate",
            ide = verifiedIde(IntelliJPlatformType.IntellijIdea),
            bundledPlugins = ideaUltimateBundledPlugins,
        ),
        SyntaxRuntimeTarget(
            "webstorm",
            "syntaxContractWebStorm",
            verifiedIde(IntelliJPlatformType.WebStorm),
            webStormBundledPlugins,
        ),
        SyntaxRuntimeTarget(
            id = "webstorm-gitlab-ci",
            taskName = "syntaxContractWebStormGitLabCi",
            ide = verifiedIde(IntelliJPlatformType.WebStorm),
            bundledPlugins =
                listOf(
                    "Git4Idea",
                    "org.jetbrains.plugins.gitlab",
                    "org.jetbrains.plugins.yaml",
                ),
        ),
        SyntaxRuntimeTarget(
            "phpstorm",
            "syntaxContractPhpStorm",
            verifiedIde(IntelliJPlatformType.PhpStorm),
            bundledPlugins = listOf("com.jetbrains.php"),
        ),
        SyntaxRuntimeTarget(
            "clion",
            "syntaxContractCLion",
            verifiedIde(IntelliJPlatformType.CLion),
            bundledPlugins = listOf("name.kropp.intellij.makefile"),
        ),
        SyntaxRuntimeTarget(
            "goland",
            "syntaxContractGoLand",
            verifiedIde(IntelliJPlatformType.GoLand),
            bundledPlugins = listOf("org.jetbrains.plugins.go"),
        ),
        SyntaxRuntimeTarget(
            "pycharm",
            "syntaxContractPyCharm",
            verifiedIde(IntelliJPlatformType.PyCharm),
            bundledPlugins = listOf("PythonCore", "com.intellij.lang.puppet", "ru.adelf.idea.dotenv"),
        ),
        SyntaxRuntimeTarget(
            "pycharm-django",
            "syntaxContractPyCharmDjango",
            verifiedIde(IntelliJPlatformType.PyCharm),
            bundledPlugins = listOf("PythonCore", "Pythonid", "com.intellij.python.django"),
        ),
        SyntaxRuntimeTarget(
            id = "dynatrace-dql",
            taskName = "syntaxContractDynatraceDql",
            ide = communityIde,
            bundledPlugins = communityBundledPlugins,
            enabledMarketplacePlugins = setOf("pl.thedeem.dql"),
        ),
        SyntaxRuntimeTarget(
            id = "dart",
            taskName = "syntaxContractDart",
            ide = communityIde,
            bundledPlugins = communityBundledPlugins,
            enabledMarketplacePlugins = setOf("Dart", "com.redhat.devtools.lsp4ij"),
        ),
        SyntaxRuntimeTarget(
            id = "graphql",
            taskName = "syntaxContractGraphql",
            ide = communityIde,
            bundledPlugins = communityBundledPlugins,
            enabledMarketplacePlugins = setOf("com.intellij.lang.jsgraphql"),
        ),
        SyntaxRuntimeTarget(
            id = "hcl",
            taskName = "syntaxContractHcl",
            ide = communityIde,
            bundledPlugins = communityBundledPlugins,
            enabledMarketplacePlugins = setOf("org.intellij.plugins.hcl"),
        ),
        SyntaxRuntimeTarget(
            id = "scala",
            taskName = "syntaxContractScala",
            ide = communityIde,
            bundledPlugins = communityBundledPlugins,
            enabledMarketplacePlugins = setOf("org.intellij.scala"),
        ),
        SyntaxRuntimeTarget(
            id = "powershell",
            taskName = "syntaxContractPowerShell",
            ide = communityIde,
            bundledPlugins = communityBundledPlugins,
            enabledMarketplacePlugins = setOf("com.intellij.plugin.adernov.powershell"),
        ),
        SyntaxRuntimeTarget(
            "rubymine",
            "syntaxContractRubyMine",
            verifiedIde(IntelliJPlatformType.RubyMine),
            bundledPlugins =
                listOf(
                    "com.intellij.modules.json",
                    "org.coffeescript",
                    "org.jetbrains.plugins.ruby",
                    "org.jetbrains.plugins.haml",
                    "org.jetbrains.plugins.slim",
                    "org.jetbrains.plugins.yaml",
                ),
        ),
        SyntaxRuntimeTarget(
            id = "noctule-swift",
            taskName = "syntaxContractNoctuleSwift",
            ide = communityIde,
            bundledPlugins = communityBundledPlugins,
            enabledMarketplacePlugins = setOf("dev.j-a.swift"),
        ),
    )

kotlin {
    jvmToolchain(21)
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    val kotestVersion = providers.gradleProperty("kotestVersion").get()

    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugin("org.intellij.groovy")
        integrationPlugins.forEach { (pluginId, pluginVersion) ->
            plugin(pluginId, pluginVersion)
        }
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
        testBundledPlugin("org.intellij.groovy")
        syntaxPreviewTestPlugins.forEach(::testBundledPlugin)
    }
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:${providers.gradleProperty("mockkVersion").get()}")
    testImplementation("io.kotest:kotest-property-jvm:$kotestVersion")
    testImplementation("junit:junit:${providers.gradleProperty("junitVersion").get()}")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
}

tasks.test {
    useJUnitPlatform()
    exclude("**/integration/**")
}

tasks.register<Test>("integrationTest") {
    val unitTest = tasks.named<Test>("test").get()
    val syntaxRuntimeId = providers.systemProperty("syntaxRuntimeId").orElse("idea-community")

    useJUnitPlatform()
    include("**/integration/**")
    exclude("**/integration/RegisteredSchemeTest*")
    exclude("**/integration/NativeSchemePersistenceTest*")
    exclude("**/integration/StudioMappingsSectionTest*")
    exclude("**/integration/StudioSettingsHostTest*")
    exclude("**/integration/StudioReviewDialogTest*")
    exclude("**/integration/StudioSyntaxParticipantTest*")
    exclude("**/integration/StudioVcsParticipantTest*")
    exclude("**/integration/StudioAccentElementsTest*")
    exclude("**/integration/StudioAccentParticipantTest*")
    exclude("**/integration/StudioAccentRuntimeTest*")
    exclude("**/integration/StudioCapabilityCleanupTest*")
    exclude("**/integration/StudioAccentCoordinatorTest*")
    exclude("**/integration/StudioNativeEnvironmentTest*")
    exclude("**/integration/StudioSystemParticipantTest*")
    exclude("**/integration/StudioSystemSchemeTest*")
    exclude("**/integration/StudioEntitlementGraphTest*")
    exclude("**/integration/CommitPathRendererNativeTest*")
    exclude("**/integration/ToolWindowWidthNativeTest*")
    testClassesDirs = unitTest.testClassesDirs
    classpath =
        unitTest.classpath.filter {
            !it.name.startsWith("kotlinx-coroutines-") || it.name.contains("-intellij")
        }
    jvmArgs(unitTest.jvmArgs)
    jvmArgumentProviders.addAll(
        unitTest.jvmArgumentProviders.filterNot {
            it is AyuCoverageArguments
        },
    )
    systemProperties(unitTest.systemProperties)
    systemProperty("ayu.native.cleanup", true)
    systemProperty("syntaxRuntimeId", syntaxRuntimeId.get())
    group = "verification"
    description = "Run integration tests with IDE fixtures"
    dependsOn("prepareTest")
    shouldRunAfter(tasks.test)
}

tasks {
    named<PrepareSandboxTask>("prepareTestSandbox") {
        disabledPlugins.addAll(integrationPlugins.map { (pluginId, _) -> pluginId })
    }

    named<JavaExec>("runIde") {
        jvmArgumentProviders +=
            CommandLineArgumentProvider {
                listOf("-Dayu.islands.dev=true")
            }
    }
}

intellijPlatformTesting {
    testIde {
        register("nativeSchemeTest") {
            type = communityIde.first
            version = communityIde.second
            testFramework(
                TestFrameworkType.Platform,
                task.map { testTask -> testFrameworkVersionRange(testTask.productInfo.buildNumber) },
            )
            plugins {
                bundledPlugins(communityBundledPlugins)
                disablePlugins(integrationPlugins.map { (pluginId, _) -> pluginId })
            }
            task {
                group = "verification"
                description = "Verify actual registered Ayu schemes in an isolated graphical fixture"
                useJUnitPlatform()
                include("**/integration/RegisteredSchemeTest*")
                include("**/integration/NativeSchemePersistenceTest*")
                include("**/integration/StudioMappingsSectionTest*")
                include("**/integration/StudioSettingsHostTest*")
                include("**/integration/StudioReviewDialogTest*")
                include("**/integration/StudioSyntaxParticipantTest*")
                include("**/integration/StudioVcsParticipantTest*")
                include("**/integration/StudioAccentElementsTest*")
                include("**/integration/StudioAccentParticipantTest*")
                include("**/integration/StudioAccentRuntimeTest*")
                include("**/integration/StudioCapabilityCleanupTest*")
                include("**/integration/StudioAccentCoordinatorTest*")
                include("**/integration/StudioNativeEnvironmentTest*")
                include("**/integration/StudioSystemParticipantTest*")
                include("**/integration/StudioSystemSchemeTest*")
                include("**/integration/StudioEntitlementGraphTest*")
                include("**/integration/CommitPathRendererNativeTest*")
                include("**/integration/ToolWindowWidthNativeTest*")
                jvmArgumentProviders +=
                    CommandLineArgumentProvider {
                        listOf("-Dayu.scheme.probe.classpath=${classpath.asPath}")
                    }
                systemProperty("java.awt.headless", false)
                systemProperty("ayu.native.cleanup", true)
                systemProperty(
                    "ayu.scheme.probe.reports",
                    layout.buildDirectory
                        .dir("reports/nativeSchemeProbe")
                        .get()
                        .asFile,
                )
                maxParallelForks = 1
                shouldRunAfter(tasks.named("integrationTest"))
            }
        }
        syntaxRuntimeTargets.forEach { runtime ->
            register(runtime.taskName) {
                val (targetType, targetVersion) = runtime.ide
                val frameworkVersion =
                    task.map { testTask -> testFrameworkVersionRange(testTask.productInfo.buildNumber) }
                val javaTestRuntime =
                    configurations.detachedConfiguration().apply {
                        dependencies.addAllLater(
                            task.map { testTask ->
                                val bundledRuntime = testTask.platformPath.resolve("lib/idea_rt.jar").toFile()
                                if (bundledRuntime.isFile) {
                                    emptyList()
                                } else {
                                    listOf(
                                        project.dependencies.create(
                                            "com.jetbrains.intellij.java:java-rt:" +
                                                testFrameworkVersionRange(testTask.productInfo.buildNumber),
                                        ),
                                    )
                                }
                            },
                        )
                    }

                type = targetType
                version = targetVersion
                testFramework(TestFrameworkType.Platform, frameworkVersion)
                plugins {
                    bundledPlugins(runtime.bundledPlugins)
                    if (runtime.id == "pycharm") {
                        // Pro helpers cannot resolve their module JAR on the flat test classpath.
                        disablePlugin("Pythonid")
                    }
                    disablePlugins(
                        integrationPlugins
                            .map { (pluginId, _) -> pluginId }
                            .filterNot(runtime.enabledMarketplacePlugins::contains),
                    )
                }
                task {
                    val requiredPluginIds =
                        listOf("com.ayuislands.theme") + runtime.bundledPlugins
                    group = "verification"
                    description = "Verify the native syntax contract in isolated runtime '${runtime.id}'"
                    useJUnitPlatform()
                    include("**/integration/NativePreviewContractTest*")
                    jvmArgumentProviders +=
                        CommandLineArgumentProvider {
                            listOf("-Didea.required.plugins.id=${requiredPluginIds.joinToString(",")}")
                        }
                    systemProperty("syntaxRuntimeId", runtime.id)
                    systemProperty(
                        "syntaxContractReportDir",
                        layout.buildDirectory
                            .dir("reports/syntax-contract/${runtime.id}")
                            .get()
                            .asFile.absolutePath,
                    )
                    classpath += javaTestRuntime
                    maxParallelForks = 1
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn("nativeSchemeTest")
}

val syntaxContractGroups: List<List<SyntaxRuntimeTarget>> = syntaxRuntimeTargets.chunked(3)
val syntaxContractMatrixSize: Provider<String> = providers.gradleProperty("syntaxContractMatrixSize")
val syntaxContractGroupTasks: List<TaskProvider<Task>> =
    syntaxContractGroups.mapIndexed { index, runtimes ->
        tasks.register("syntaxContractGroup${index + 1}") {
            group = "verification"
            description = "Verify syntax contracts for ${runtimes.joinToString { it.id }}"
            dependsOn(runtimes.map(SyntaxRuntimeTarget::taskName))
        }
    }

tasks.register("verifySyntaxContractMatrix") {
    group = "verification"
    description = "Verify that CI schedules every declared syntax contract group"
    doLast {
        val configuredSize =
            syntaxContractMatrixSize.orNull?.toIntOrNull()
                ?: error("Provide -PsyntaxContractMatrixSize=<CI matrix job count>")
        check(configuredSize == syntaxContractGroups.size) {
            "Syntax contract CI matrix has $configuredSize groups, but the runtime catalog requires " +
                "${syntaxContractGroups.size}"
        }
    }
}

tasks.register("syntaxContractAll") {
    group = "verification"
    description = "Verify every declared native syntax runtime contract"
    dependsOn(syntaxContractGroupTasks)
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
                "ReleaseVersionAndPluginVersionMismatch",
            )
        ides {
            // verifyGroup splits the 12 IDE targets across hosted runners to keep
            // the per-runner disk and I/O budget under what GitHub Actions can
            // sustain. The A1/A2 split prevents the 6-way verifier-thread fan-out
            // from saturating one runner's I/O and hitting ClosedByInterruptException
            // on JAR extraction (observed three times on the unsplit Group A).
            //
            //   null  → every target (local dev: `./gradlew verifyPlugin`)
            //   "A"   → A1 + A2 (back-compat with earlier matrix shape)
            //   "A1"  → IntelliJ family (3 IDEs)
            //   "A2"  → JetBrains pro IDEs on 2025.3 (3 IDEs)
            //   "B"   → Language-specific IDEs on 2025.1 (5 IDEs)
            //   "C"   → Marketplace-current IntelliJ IDEA 2026.2 EAP
            //
            // RustRover 2025.1.3 excluded from Group B: corrupted CDN artifact
            // (InvalidIdeException: missing Core plugin).
            val wanted =
                when (val group = providers.systemProperty("verifyGroup").orNull) {
                    null -> {
                        verifiedIdeGroups.keys + "C"
                    }

                    "A" -> {
                        setOf("A1", "A2")
                    }

                    "C" -> {
                        setOf("C")
                    }

                    in verifiedIdeGroups.keys -> {
                        setOf(group)
                    }

                    else -> {
                        error(
                            "Unknown verifyGroup '$group' — expected A1, A2, A, B, C, or unset",
                        )
                    }
                }
            wanted
                .filter { it in verifiedIdeGroups }
                .flatMap { verifiedIdeGroups.getValue(it) }
                .forEach { (ideType, version) ->
                    create(ideType, version) {
                        if (ideType == IntelliJPlatformType.Rider) useInstaller = false
                    }
                }
            if ("C" in wanted) {
                select {
                    types = listOf(IntelliJPlatformType.IntellijIdeaCommunity)
                    channels = listOf(ProductRelease.Channel.EAP)
                    sinceBuild = "262.6653.22"
                    untilBuild = "262.6653.22"
                }
            }
        }
    }
}

// The Detekt plugin declaration uses apply false; its public task classes retain CLI behavior.
pluginManager.apply(ReportingBasePlugin::class.java)

val detektVersion = providers.gradleProperty("detektVersion")
val detektRuntime =
    configurations.register("detekt") {
        description = "The detekt dependencies to be used for this project."
        isCanBeResolved = true
        isCanBeConsumed = false
        isTransitive = true
        defaultDependencies {
            add(project.dependencies.create("io.gitlab.arturbosch.detekt:detekt-cli:${detektVersion.get()}"))
        }
    }
val detektPlugins =
    configurations.register("detektPlugins") {
        description = "The detektPlugins libraries to be used for this project."
        isCanBeResolved = true
        isCanBeConsumed = false
        isTransitive = true
    }
val detektConfig = layout.projectDirectory.file("detekt.yml")
val detektBaselineFile = layout.projectDirectory.file("detekt-baseline.xml")
val detektReports = extensions.getByType<ReportingExtension>().baseDirectory.dir("detekt")
val detektLauncher =
    extensions.getByType<JavaToolchainService>().launcherFor(
        extensions.getByType<JavaPluginExtension>().toolchain,
    )
val detektSources =
    files(
        "src/main/java",
        "src/test/java",
        "src/main/kotlin",
        "src/test/kotlin",
    ).filter { it.exists() }

tasks.withType<Detekt>().configureEach {
    detektClasspath.setFrom(detektRuntime)
    pluginClasspath.setFrom(detektPlugins)
    config.setFrom(detektConfig)
    buildUponDefaultConfig = true
    allRules = false
    debug = false
    parallel = false
    disableDefaultRuleSets = false
    autoCorrect = false
    ignoreFailures = false
    jdkHome.convention(detektLauncher.map { it.metadata.installationPath })
    jvmTarget =
        JavaVersion
            .toVersion(
                detektLauncher
                    .get()
                    .metadata.languageVersion
                    .asInt(),
            ).toString()
}
tasks.withType<DetektCreateBaselineTask>().configureEach {
    detektClasspath.setFrom(detektRuntime)
    pluginClasspath.setFrom(detektPlugins)
    config.setFrom(detektConfig)
    buildUponDefaultConfig.convention(true)
    allRules.convention(false)
    debug.convention(false)
    parallel.convention(false)
    disableDefaultRuleSets.convention(false)
    autoCorrect.convention(false)
    jdkHome.convention(detektLauncher.map { it.metadata.installationPath })
    jvmTarget =
        JavaVersion
            .toVersion(
                detektLauncher
                    .get()
                    .metadata.languageVersion
                    .asInt(),
            ).toString()
}
tasks.withType<DetektGenerateConfigTask>().configureEach {
    detektClasspath.setFrom(detektRuntime)
    pluginClasspath.setFrom(detektPlugins)
}

val detekt =
    tasks.register<Detekt>("detekt") {
        setSource(detektSources)
        include("**/*.kt", "**/*.kts")
        exclude("build/")
        reportsDir.convention(detektReports.map { it.asFile })
        detektBaselineFile.takeIf { it.asFile.exists() }?.let { baseline.convention(it) }
        dependsOn("detektTest")
    }
tasks.register<DetektCreateBaselineTask>("detektBaseline") {
    setSource(detektSources)
    baseline.convention(detektBaselineFile)
}

kotlin.target.compilations.configureEach {
    val compilation = this
    val taskSuffix = name.replaceFirstChar(Char::uppercaseChar)
    val compilationSources = files(kotlinSourceSets.map { it.kotlin.sourceDirectories })
    val variantBaseline = layout.projectDirectory.file("detekt-baseline-$name.xml")
    tasks.register<Detekt>("detekt$taskSuffix") {
        description = "EXPERIMENTAL: Run detekt analysis for ${compilation.name} classes with type resolution"
        setSource(compilationSources)
        classpath.setFrom(compilation.output.classesDirs, compilation.compileDependencyFiles)
        listOf(variantBaseline, detektBaselineFile).firstOrNull { it.asFile.exists() }?.let {
            baseline.convention(it)
        }
        listOf(reports.xml, reports.html, reports.txt, reports.sarif, reports.md).forEach { report ->
            report.outputLocation.convention(
                detektReports.map { it.file("${compilation.name}.${report.type.extension}") },
            )
        }
    }
    tasks.register<DetektCreateBaselineTask>("detektBaseline$taskSuffix") {
        description = "EXPERIMENTAL: Creates detekt baseline for ${compilation.name} classes with type resolution"
        setSource(compilationSources)
        classpath.setFrom(compilation.output.classesDirs, compilation.compileDependencyFiles)
        baseline.convention(variantBaseline)
    }
}

val detektConfigService =
    gradle.sharedServices.registerIfAbsent(
        "DetektGenerateConfigSingleExecution",
        DetektGenerateConfigTask.SingleExecutionBuildService::class.java,
    ) {
        maxParallelUsages.set(1)
    }
tasks.register<DetektGenerateConfigTask>("detektGenerateConfig") {
    configFile.set(detektConfig)
    usesService(detektConfigService)
}
tasks.named("check") {
    dependsOn(detekt)
}

// Report required flags and optional languageVersion/basePath stay unset, preserving upstream defaults.
// The public jvmTarget setter requires a String, so task realization resolves the configured launcher.

val coverageExcludedClasses: List<String> =
    listOf(
        // Pure-rendering UI panels (no extractable logic, visual-only)
        "dev.ayuislands.settings.AyuIslandsPreviewPanel*",
        "dev.ayuislands.settings.AyuIslandsEffectsPanel*",
        "dev.ayuislands.settings.AyuIslandsElementsPanel*",
        "dev.ayuislands.settings.AyuIslandsAccentPanel*",
        "dev.ayuislands.settings.AyuIslandsAppearancePanel*",
        "dev.ayuislands.settings.AyuIslandsSettingsPanel*",
        "dev.ayuislands.settings.AyuIslandsConfigurable*",
        "dev.ayuislands.settings.PresetButtonBar*",
        // Data class in EffectsPanel file, pure UI config
        "dev.ayuislands.settings.SliderConfig*",
        // Pure-rendering UI panel (Integrations settings)
        "dev.ayuislands.settings.IntegrationsPanel*",
        // LicenseChecker: thin IDE wrapper; crypto logic tested via LicenseVerifier
        "dev.ayuislands.licensing.LicenseChecker*",
        // IDE glue: Swing UI DSL panel (pure rendering, no extractable logic)
        "dev.ayuislands.settings.WorkspacePanel*",
        "dev.ayuislands.settings.PluginsPanel*",
        // IDE glue: thin ToolWindowManagerListener wrappers delegating to ToolWindowAutoFitter
        "dev.ayuislands.commitpanel.CommitPanelAutoFitManager*",
        "dev.ayuislands.gitpanel.GitPanelAutoFitManager*",
        // IDE glue: Project service (ToolWindowManager, Registry, ProjectView singletons)
        "dev.ayuislands.projectview.ProjectViewScrollbarManager*",
        // IDE glue: JTree traversal, ToolWindowManager, Timer, expansion listeners
        "dev.ayuislands.toolwindow.ToolWindowAutoFitter*",
        // Swing animation panel (Timer, Graphics2D, Swing lifecycle, editor font lookup)
        "dev.ayuislands.settings.AccentColorPanel",
        // Pure Swing rendering: Graphics2D paint, mouse handlers, animation timers
        $$"dev.ayuislands.settings.AccentColorPanel$ThirteenthSwatch*",
        $$"dev.ayuislands.settings.AccentColorPanel$ShuffleLink*",
        $$"dev.ayuislands.settings.AccentColorPanel$ShadeNameLabel*",
        $$"dev.ayuislands.settings.AccentColorPanel$LinkLabel*",
        $$"dev.ayuislands.settings.AccentColorPanel$PresetComponent*",
        $$"dev.ayuislands.settings.AccentColorPanel$CustomLink*",
        $$"dev.ayuislands.settings.AccentColorPanel$ResetLabel*",
        // Onboarding Swing panels (Graphics2D paint, mouse handlers, SVG rendering)
        "dev.ayuislands.onboarding.PremiumOnboardingPanel*",
        "dev.ayuislands.onboarding.FreeOnboardingPanel*",
        // Onboarding Swing factories and rendering helpers (createStyledButton, createRailCard, paintScrim etc.)
        "dev.ayuislands.onboarding.OnboardingComponentsKt*",
        "dev.ayuislands.onboarding.OnboardingSharedRenderingKt*",
        // Onboarding IDE glue (thin editor providers, virtual files, coroutine scheduler)
        "dev.ayuislands.onboarding.*EditorProvider*",
        "dev.ayuislands.onboarding.*Editor",
        "dev.ayuislands.onboarding.*VirtualFile*",
        "dev.ayuislands.onboarding.OnboardingSchedulerService*",
        // What's New tab — pure Swing UI builders + IDE glue. Pure logic
        // (computeScale, computeMaxLogicalImageWidth, computeButtonWidth,
        // isEligible, normalizeVersion, centerInRow, gaussianBlur, toBufferedImage,
        // readImageScale, readString, half-CTA WARN, per-slide try/catch contract)
        // lives in companion objects / top-level helpers with dedicated red/green
        // tests. These excludes only cover the Swing lifecycle scaffolding:
        //   - WhatsNewPanel: JBScrollPane assembly, AncestorListener/ComponentListener
        //     wiring, FileEditorManager.closeFile invocation, Swing paint
        //   - WhatsNewSlideCard: JBLabel/HTML body construction, BoxLayout wiring,
        //     paintCardChrome forward to onboarding helper
        //   - WhatsNewImagePanel: Graphics2D paintComponent, shadow BufferedImage
        //     caching, removeNotify/invalidate Swing lifecycle
        //   - ShowWhatsNewButton: MouseAdapter event dispatch, hover/pressed
        //     paint, JBLabel layout
        //   - WhatsNewEditor: thin FileEditor contract wrapper
        //   - WhatsNewLauncher: OnboardingSchedulerService coroutine launch,
        //     FileEditorManager open (the pure isEligible + version
        //     normalization paths ARE tested; the focus-race protocol lives
        //     in the fully tested dev.ayuislands.ui.FocusWinningTabOpener)
        "dev.ayuislands.whatsnew.WhatsNewPanel",
        $$"dev.ayuislands.whatsnew.WhatsNewPanel$1",
        $$"dev.ayuislands.whatsnew.WhatsNewPanel$2",
        "dev.ayuislands.whatsnew.WhatsNewSlideCard",
        "dev.ayuislands.whatsnew.WhatsNewSlideCard$*",
        "dev.ayuislands.whatsnew.WhatsNewImagePanel",
        "dev.ayuislands.whatsnew.ShowWhatsNewButton",
        $$"dev.ayuislands.whatsnew.ShowWhatsNewButton$1",
        "dev.ayuislands.whatsnew.WhatsNewEditor",
        "dev.ayuislands.whatsnew.WhatsNewLauncher",
        "dev.ayuislands.whatsnew.WhatsNewLauncher$*",
        // Onboarding data-class holders (generated constructors + getters only)
        "dev.ayuislands.onboarding.WizardSvgGeometry",
        "dev.ayuislands.onboarding.RailCardSpec",
        "dev.ayuislands.onboarding.RailCardLayout",
        // IR plugin integration: reflection into 3rd-party plugin internals
        "dev.ayuislands.indent.IndentRainbowSync*",
        // Swing TreeCellRenderer (paintComponent, JTree integration)
        "dev.ayuislands.projectview.RootFilteringRenderer*",
        // Graphics2D paint rendering (editor highlight overlays)
        "dev.ayuislands.accent.elements.BracketScopeRenderer*",
        // Accent mappings UI: DialogWrapper, JBTable wiring, paintComponent renderers
        "dev.ayuislands.settings.mappings.OverridesGroupBuilder*",
        "dev.ayuislands.settings.mappings.AddProjectMappingDialog*",
        "dev.ayuislands.settings.mappings.AddLanguageMappingDialog*",
        "dev.ayuislands.settings.mappings.EditAccentColorDialog*",
        "dev.ayuislands.settings.mappings.AccentSwatchPickerRow*",
        "dev.ayuislands.settings.mappings.RoundedSwatchRenderer*",
    )

class AyuCoverageArguments(
    private val argumentsFile: File,
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val agentJar: Provider<RegularFile>,
    @get:Input val excludedClasses: Provider<Set<String>>,
    @get:OutputFile val reportFile: Provider<RegularFile>,
) : CommandLineArgumentProvider,
    Named {
    @Internal
    override fun getName(): String = "ayuCoverageArguments"

    override fun asArguments(): Iterable<String> {
        argumentsFile.parentFile.mkdirs()
        argumentsFile.writeText(
            buildString {
                appendLine("report.file=${reportFile.get().asFile.canonicalPath}")
                excludedClasses.get().forEach { appendLine("exclude=$it") }
            },
            Charsets.UTF_8,
        )
        return listOf("-javaagent:${agentJar.get().asFile.canonicalPath}=file:${argumentsFile.canonicalPath}")
    }
}

@CacheableTask
abstract class AyuCoverageTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val binaryReports: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectories: ConfigurableFileCollection

    @get:Input abstract val excludedClasses: SetProperty<String>

    protected fun createFilters(): ClassFilters =
        ClassFilters(
            includeClasses = emptySet(),
            excludeClasses = excludedClasses.get(),
            includeAnnotation = emptySet(),
            excludeAnnotation = emptySet(),
            includeInheritedFrom = emptySet(),
            excludeInheritedFrom = emptySet(),
        )

    protected fun collectReports(): List<File> = binaryReports.files.filter { it.isFile }

    protected fun collectClasses(): List<File> = classDirectories.files.filter { it.isDirectory }

    protected fun collectSources(): List<File> = sourceDirectories.files.filter { it.isDirectory }
}

@CacheableTask
abstract class AyuCoverageXml : AyuCoverageTask() {
    @get:Input abstract val title: Property<String>

    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun generateReport() {
        KoverLegacyFeatures.generateXmlReport(
            reportFile.get().asFile.also { it.parentFile.mkdirs() },
            collectReports(),
            collectClasses(),
            collectSources(),
            title.get(),
            createFilters(),
        )
    }
}

@CacheableTask
abstract class AyuCoverageHtml : AyuCoverageTask() {
    @get:Input abstract val title: Property<String>

    @get:OutputDirectory abstract val reportDirectory: DirectoryProperty

    @TaskAction
    fun generateReport() {
        KoverLegacyFeatures.generateHtmlReport(
            reportDirectory.get().asFile.also { it.mkdirs() },
            null,
            collectReports(),
            collectClasses(),
            collectSources(),
            title.get(),
            createFilters(),
        )
    }
}

@CacheableTask
abstract class AyuCoverageBinary : AyuCoverageTask() {
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun generateReport() {
        KoverLegacyFeatures.aggregateIc(
            reportFile.get().asFile.also { it.parentFile.mkdirs() },
            createFilters(),
            temporaryDir,
            collectReports(),
            collectClasses(),
        )
    }
}

@CacheableTask
abstract class AyuCoverageCheck : AyuCoverageTask() {
    @get:Input abstract val minimumCoverage: Property<Int>

    @get:OutputFile abstract val resultFile: RegularFileProperty

    @TaskAction
    fun checkCoverage() {
        val rules =
            listOf(
                Rule(
                    "Line coverage",
                    GroupingBy.APPLICATION,
                    listOf(
                        Bound(
                            BigDecimal(minimumCoverage.get()),
                            null,
                            CoverageUnit.LINE,
                            AggregationType.COVERED_PERCENTAGE,
                        ),
                    ),
                ),
            )
        val violations =
            KoverLegacyFeatures.verify(
                rules,
                temporaryDir,
                createFilters(),
                collectReports(),
                collectClasses(),
            )
        resultFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(KoverLegacyFeatures.violationMessage(violations), Charsets.UTF_8)
        }
    }
}

@DisableCachingByDefault(because = "Enforces the cached coverage result on every invocation")
abstract class AyuCoverageVerify : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resultFile: RegularFileProperty

    @TaskAction
    fun verifyCoverage() {
        val failure = resultFile.get().asFile.readText(Charsets.UTF_8)
        if (failure.isNotEmpty()) throw GradleException(failure)
    }
}

@DisableCachingByDefault(because = "Prints current coverage without producing a report")
abstract class AyuCoverageLog : AyuCoverageTask() {
    @TaskAction
    fun logCoverage() {
        val coverage =
            KoverLegacyFeatures.evalCoverage(
                GroupingBy.APPLICATION,
                CoverageUnit.LINE,
                AggregationType.COVERED_PERCENTAGE,
                temporaryDir,
                createFilters(),
                collectReports(),
                collectClasses(),
            )
        if (coverage.isEmpty()) {
            logger.quiet("No sources")
            return
        }
        coverage.forEach { entry ->
            val entityName = entry.entityName ?: "application"
            val percentage = entry.value.stripTrailingZeros().toPlainString()
            logger.quiet("$entityName line coverage: $percentage%")
        }
    }
}

val coverageVersion = providers.gradleProperty("koverVersion")
val coverageAgent =
    configurations.create("koverJvmAgent") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
dependencies.add(coverageAgent.name, coverageVersion.map { "org.jetbrains.kotlinx:kover-jvm-agent:$it" })
val findCoverageAgent =
    tasks.register<Copy>("koverFindJar") {
        from(coverageAgent)
        into(layout.buildDirectory.dir("kover/agent"))
    }
val coverageAgentFile = layout.buildDirectory.file(coverageVersion.map { "kover/agent/kover-jvm-agent-$it.jar" })
val coverageAgentExcludes =
    providers
        .gradleProperty("kover.android.excludes.disable")
        .map { setOf("jdk.internal.*") }
        .orElse(setOf("android.*", "com.android.*", "jdk.internal.*"))
val uncoveredTests = syntaxRuntimeTargets.map { it.taskName }.toSet()

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.removeAll { it is AyuCoverageArguments }
    if (name !in uncoveredTests) {
        val binaryReport = layout.buildDirectory.file("kover/bin-reports/$name.ic")
        dependsOn(findCoverageAgent)
        jvmArgumentProviders.add(
            AyuCoverageArguments(
                temporaryDir.resolve("kover-agent.args"),
                coverageAgentFile,
                coverageAgentExcludes,
                binaryReport,
            ),
        )
        doFirst {
            val reportPath = binaryReport.get().asFile.toPath()
            Files.createDirectories(reportPath.parent)
            Files.deleteIfExists(reportPath)
        }
    }
}

val coverageTests = tasks.withType<Test>().matching { it.enabled && it.name !in uncoveredTests }
val coverageSources = sourceSets.matching { it.name != "test" }
val coverageClasses = objects.fileCollection().from(coverageSources.map { it.output.classesDirs })
val coverageSourceDirs =
    objects.fileCollection().from(
        coverageSources.map { it.java.srcDirs },
        kotlin.sourceSets.matching { it.name != "test" }.map { it.kotlin.srcDirs },
    )
val coverageReports =
    objects.fileCollection().from(
        providers.provider {
            coverageTests.map { layout.buildDirectory.file("kover/bin-reports/${it.name}.ic").get() }
        },
    )

tasks.withType<AyuCoverageTask>().configureEach {
    group = "verification"
    dependsOn(coverageTests)
    binaryReports.from(coverageReports)
    classDirectories.from(coverageClasses)
    sourceDirectories.from(coverageSourceDirs)
    excludedClasses.set(coverageExcludedClasses)
}

for (suffix in listOf("", "Jvm")) {
    val xmlReport =
        tasks.register<AyuCoverageXml>("koverXmlReport$suffix") {
            title.set("Kover Gradle Plugin XML report for ${project.path}")
            reportFile.set(layout.buildDirectory.file("reports/kover/report$suffix.xml"))
        }
    val htmlReport =
        tasks.register<AyuCoverageHtml>("koverHtmlReport$suffix") {
            title.set(project.name)
            reportDirectory.set(layout.buildDirectory.dir("reports/kover/html$suffix"))
        }
    val binaryReport =
        tasks.register<AyuCoverageBinary>("koverBinaryReport$suffix") {
            reportFile.set(layout.buildDirectory.file("reports/kover/report$suffix.bin"))
        }
    val cachedCheck =
        tasks.register<AyuCoverageCheck>("koverCachedVerify$suffix") {
            minimumCoverage.set(80)
            resultFile.set(layout.buildDirectory.file("reports/kover/verify$suffix.err"))
        }
    val verifyCoverage =
        tasks.register<AyuCoverageVerify>("koverVerify$suffix") {
            group = "verification"
            resultFile.set(cachedCheck.flatMap { it.resultFile })
            shouldRunAfter(xmlReport, htmlReport, binaryReport)
        }
    tasks.register<AyuCoverageLog>("koverLog$suffix")
    if (suffix.isEmpty()) tasks.named("check") { dependsOn(verifyCoverage) }
}

val proguardTask: TaskProvider<proguard.gradle.ProGuardTask> =
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

        val javaHome = System.getProperty("java.home")
        libraryjars(
            mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
            "$javaHome/jmods/java.base.jmod",
        )
        libraryjars(
            mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
            "$javaHome/jmods/java.desktop.jmod",
        )
        libraryjars(
            mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
            "$javaHome/jmods/java.xml.jmod",
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
