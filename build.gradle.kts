import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.10.0")
    }
}

plugins {
    // 2.18.1 breaks the verifyPlugin compatibility matrix; keep in sync with Dependabot.
    id("org.jetbrains.intellij.platform") version "2.17.0"
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val integrationPlugins =
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

val syntaxPreviewTestPlugins =
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

val communityIde = verifiedIdeGroups.getValue("A1").first()
val communityBundledPlugins = listOf("org.intellij.groovy") + syntaxPreviewTestPlugins
val webStormBundledPlugins =
    listOf(
        "AngularJS",
        "JavaScript",
        "com.intellij.css",
        "com.jetbrains.restClient",
        "gherkin",
        "org.jetbrains.plugins.sass",
    )
val ideaUltimateBundledPlugins =
    listOf(
        "Docker",
        "com.intellij.freemarker",
        "com.intellij.quarkus",
        "com.intellij.velocity",
        "idea.plugin.protoeditor",
        "org.editorconfig.editorconfigjetbrains",
    )
val syntaxRuntimeTargets =
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
        testBundledPlugin("org.intellij.groovy")
        syntaxPreviewTestPlugins.forEach(::testBundledPlugin)
    }
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:${providers.gradleProperty("mockkVersion").get()}")
    testImplementation("io.kotest:kotest-property-jvm:$kotestVersion")
    testImplementation("junit:junit:${providers.gradleProperty("junitVersion").get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    testClassesDirs = unitTest.testClassesDirs
    classpath =
        unitTest.classpath.filter {
            !it.name.startsWith("kotlinx-coroutines-") || it.name.contains("-intellij")
        }
    jvmArgs(unitTest.jvmArgs)
    jvmArgumentProviders.addAll(
        unitTest.jvmArgumentProviders.filterNot {
            it.javaClass.name.contains("kover", ignoreCase = true)
        },
    )
    systemProperties(unitTest.systemProperties)
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

val syntaxContractGroups = syntaxRuntimeTargets.chunked(3)
val syntaxContractMatrixSize = providers.gradleProperty("syntaxContractMatrixSize")
val syntaxContractGroupTasks =
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
                    null -> verifiedIdeGroups.keys + "C"
                    "A" -> setOf("A1", "A2")
                    "C" -> setOf("C")
                    in verifiedIdeGroups.keys -> setOf(group)
                    else ->
                        error(
                            "Unknown verifyGroup '$group' — expected A1, A2, A, B, C, or unset",
                        )
                }
            wanted
                .filter { it in verifiedIdeGroups }
                .flatMap { verifiedIdeGroups.getValue(it) }
                .forEach { (type, version) ->
                    create(type, version)
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

detekt {
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = true
}

tasks.named("detekt") {
    dependsOn("detektTest")
}

kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.addAll(syntaxRuntimeTargets.map(SyntaxRuntimeTarget::taskName))
        }
    }

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
                    // Glow rendering (Graphics2D paint, animation overlay, Swing lifecycle)
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
                    // IDE scheduling glue: AppExecutorUtil, AyuIslandsSettings, AccentApplicator singletons
                    "dev.ayuislands.rotation.AccentRotationService*",
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
                    // Startup lifecycle (coroutine scheduling, project service init)
                    "dev.ayuislands.StartupLicenseHandler*",
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
                    // Swing Border + Component lifecycle (editor focus management)
                    "dev.ayuislands.glow.FocusRingManager*",
                    // Accent mappings UI: DialogWrapper, JBTable wiring, paintComponent renderers
                    "dev.ayuislands.settings.mappings.OverridesGroupBuilder*",
                    "dev.ayuislands.settings.mappings.AddProjectMappingDialog*",
                    "dev.ayuislands.settings.mappings.AddLanguageMappingDialog*",
                    "dev.ayuislands.settings.mappings.EditAccentColorDialog*",
                    "dev.ayuislands.settings.mappings.AccentSwatchPickerRow*",
                    "dev.ayuislands.settings.mappings.RoundedSwatchRenderer*",
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
