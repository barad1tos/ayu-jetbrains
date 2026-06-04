package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.AccentMappingsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.awt.Color
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AccentResolverExternalTest {
    @Test
    fun `external resolve prefers licensed project override over material accent`() {
        withResolverStubs {
            val projectPath = File(System.getProperty("java.io.tmpdir"), "external-project").canonicalPath
            mappingsState.projectAccents[projectPath] = "#112233"
            state.externalThemeAccent = "#445566"
            val project = stubProject(File(projectPath))

            withUiColorProvider({ key -> if (key == "material.accent") Color(0xAA, 0xBB, 0xCC) else null }) {
                assertEquals("#112233", AccentResolver.resolve(project, AccentContext.External))
                assertEquals(
                    AccentResolver.Source.PROJECT_OVERRIDE,
                    AccentResolver.source(project, AccentContext.External),
                )
            }
        }
    }

    @Test
    fun `external resolve prefers licensed language override over material accent`() {
        withResolverStubs {
            mappingsState.languageAccents["kotlin"] = "#223344"
            state.externalThemeAccent = "#445566"
            val project = stubProject(File(System.getProperty("java.io.tmpdir"), "external-language"))
            every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

            withUiColorProvider({ key -> if (key == "material.accent") Color(0xAA, 0xBB, 0xCC) else null }) {
                assertEquals("#223344", AccentResolver.resolve(project, AccentContext.External))
                assertEquals(
                    AccentResolver.Source.LANGUAGE_OVERRIDE,
                    AccentResolver.source(project, AccentContext.External),
                )
            }
        }
    }

    @Test
    fun `external automatic resolve uses material accent before stored fallback`() {
        withResolverStubs {
            state.externalThemeAccent = "#445566"

            withUiColorProvider({ key -> if (key == "material.accent") Color(0x0A, 0x14, 0x1E) else null }) {
                assertEquals("#0A141E", AccentResolver.resolve(null, AccentContext.External))
                assertEquals(
                    AccentResolver.Source.MATERIAL_THEME,
                    AccentResolver.source(null, AccentContext.External),
                )
            }
        }
    }

    @Test
    fun `external automatic resolve uses material accent before later ide accent keys`() {
        withResolverStubs {
            state.externalThemeAccent = "#445566"

            withUiColorProvider(
                { key ->
                    when (key) {
                        "material.accent" -> Color(0x0A, 0x14, 0x1E)
                        "Component.accentColor" -> Color(0xC8, 0x64, 0x32)
                        "Actions.Blue" -> Color(0x11, 0x22, 0x33)
                        else -> null
                    }
                },
            ) {
                assertEquals("#0A141E", AccentResolver.resolve(null, AccentContext.External))
                assertEquals(
                    AccentResolver.Source.MATERIAL_THEME,
                    AccentResolver.source(null, AccentContext.External),
                )
            }
        }
    }

    @Test
    fun `external automatic resolve uses component accent color before actions blue`() {
        withResolverStubs {
            state.externalThemeAccent = "#445566"

            withUiColorProvider(
                { key ->
                    when (key) {
                        "Component.accentColor" -> Color(0xC8, 0x64, 0x32)
                        "Actions.Blue" -> Color(0x11, 0x22, 0x33)
                        else -> null
                    }
                },
            ) {
                assertEquals("#C86432", AccentResolver.resolve(null, AccentContext.External))
                assertEquals(
                    AccentResolver.Source.IDE_ACCENT,
                    AccentResolver.source(null, AccentContext.External),
                )
            }
        }
    }

    @Test
    fun `external automatic resolve uses actions blue before stored fallback`() {
        withResolverStubs {
            state.externalThemeAccent = "#445566"

            withUiColorProvider({ key -> if (key == "Actions.Blue") Color(0x11, 0x22, 0x33) else null }) {
                assertEquals("#112233", AccentResolver.resolve(null, AccentContext.External))
                assertEquals(
                    AccentResolver.Source.IDE_ACCENT,
                    AccentResolver.source(null, AccentContext.External),
                )
            }
        }
    }

    @Test
    fun `external automatic resolve falls back to stored external accent`() {
        withResolverStubs {
            state.externalThemeAccent = "#445566"

            withUiColorProvider({ null }) {
                assertEquals("#445566", AccentResolver.resolve(null, AccentContext.External))
                assertEquals(
                    AccentResolver.Source.EXTERNAL_ACCENT,
                    AccentResolver.source(null, AccentContext.External),
                )
            }
        }
    }

    @Test
    fun `external manual mode uses stored external accent and ignores all automatic sources`() {
        withResolverStubs {
            val projectPath = File(System.getProperty("java.io.tmpdir"), "external-manual").canonicalPath
            mappingsState.projectAccents[projectPath] = "#112233"
            mappingsState.languageAccents["kotlin"] = "#223344"
            state.externalThemeAccentSource = ExternalAccentSource.MANUAL.name
            state.externalThemeAccent = "#13579B"
            val project = stubProject(File(projectPath))
            every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

            withUiColorProvider({ key -> if (key == "material.accent") Color(0xAA, 0xBB, 0xCC) else null }) {
                assertEquals("#13579B", AccentResolver.resolve(project, AccentContext.External))
                assertEquals(
                    AccentResolver.Source.EXTERNAL_ACCENT,
                    AccentResolver.source(project, AccentContext.External),
                )
            }
        }
    }

    @Test
    fun `external automatic resolve skips unlicensed overrides before material accent`() {
        withResolverStubs(licensed = false) {
            val projectPath = File(System.getProperty("java.io.tmpdir"), "external-unlicensed").canonicalPath
            mappingsState.projectAccents[projectPath] = "#112233"
            mappingsState.languageAccents["kotlin"] = "#223344"
            state.externalThemeAccent = "#445566"
            val project = stubProject(File(projectPath))
            every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

            withUiColorProvider({ key -> if (key == "material.accent") Color(0xAA, 0xBB, 0xCC) else null }) {
                assertEquals("#AABBCC", AccentResolver.resolve(project, AccentContext.External))
                assertEquals(
                    AccentResolver.Source.MATERIAL_THEME,
                    AccentResolver.source(project, AccentContext.External),
                )
            }
        }
    }

    @Test
    fun `invalid stored external accent falls back to mirage default accent`() {
        withResolverStubs {
            state.externalThemeAccentSource = ExternalAccentSource.MANUAL.name
            state.externalThemeAccent = "not-a-hex"

            assertEquals(AyuVariant.MIRAGE.defaultAccent, AccentResolver.resolve(null, AccentContext.External))
            assertEquals(
                AccentResolver.Source.EXTERNAL_ACCENT,
                AccentResolver.source(null, AccentContext.External),
            )
        }
    }

    private fun withResolverStubs(
        licensed: Boolean = true,
        block: ResolverStubs.() -> Unit,
    ) {
        mockkObject(AyuIslandsSettings.Companion)
        mockkObject(AccentMappingsSettings.Companion)
        mockkObject(ProjectLanguageDetector)
        mockkObject(LicenseChecker)
        try {
            val settings = mockk<AyuIslandsSettings>()
            val state = AyuIslandsState()
            every { settings.state } returns state
            every { settings.getAccentForVariant(AyuVariant.MIRAGE) } returns AyuVariant.MIRAGE.defaultAccent
            every { settings.getAccentForVariant(AyuVariant.DARK) } returns AyuVariant.DARK.defaultAccent
            every { settings.getAccentForVariant(AyuVariant.LIGHT) } returns AyuVariant.LIGHT.defaultAccent
            every { AyuIslandsSettings.getInstance() } returns settings

            val mappingsState = AccentMappingsState()
            val mappingsSettings = mockk<AccentMappingsSettings>()
            every { mappingsSettings.state } returns mappingsState
            every { AccentMappingsSettings.getInstance() } returns mappingsSettings

            every { ProjectLanguageDetector.dominant(any()) } returns null
            every { LicenseChecker.isLicensedOrGrace() } returns licensed

            AccentResolver.resetWarnGatesForTest()
            AccentResolver.resetUiColorProviderForTest()
            ResolverStubs(state, mappingsState).block()
        } finally {
            AccentResolver.resetWarnGatesForTest()
            AccentResolver.resetUiColorProviderForTest()
            unmockkObject(LicenseChecker)
            unmockkObject(ProjectLanguageDetector)
            unmockkObject(AccentMappingsSettings.Companion)
            unmockkObject(AyuIslandsSettings.Companion)
        }
    }

    private fun withUiColorProvider(
        provider: (String) -> Color?,
        block: () -> Unit,
    ) {
        AccentResolver.withUiColorProviderForTest(provider, block)
    }

    private data class ResolverStubs(
        val state: AyuIslandsState,
        val mappingsState: AccentMappingsState,
    )

    private fun stubProject(baseDir: File): Project {
        val project = mockk<Project>()
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.basePath } returns baseDir.path
        every { project.name } returns baseDir.name
        return project
    }
}
