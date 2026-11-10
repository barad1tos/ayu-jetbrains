package dev.ayuislands.syntax

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.theme.EditorSchemeChange
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOwner
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.awt.Color
import java.awt.Font
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Read-only target selection and canonical computation; native ownership is covered by Studio participant tests. */
@MockKExtension.KeepMocks
class SyntaxIntensityServiceTest {
    companion object {
        private const val PAYLOAD_KEY_NAME = "K1"

        @BeforeAll
        @JvmStatic
        fun installSharedMocks() {
            mockkStatic(
                PropertiesComponent::class,
                TextAttributesKey::class,
                EditorColorsManager::class,
                ApplicationManager::class,
            )
            mockkObject(
                EditorSchemeChange,
                EditorSchemeOverrides,
                SyntaxOverlayLoader.Companion,
                LicenseChecker,
                RgbBlend,
                SyntaxIntensityApplicator,
            )
        }

        @AfterAll
        @JvmStatic
        fun uninstallSharedMocks() {
            unmockkAll()
            clearAllMocks()
        }
    }

    private lateinit var mockMirage: EditorColorsScheme
    private lateinit var mockDark: EditorColorsScheme
    private lateinit var mockLight: EditorColorsScheme
    private lateinit var mockManager: EditorColorsManager
    private lateinit var mockApp: Application
    private lateinit var loader: SyntaxOverlayLoader
    private lateinit var ayuSettings: AyuIslandsSettings
    private lateinit var ayuState: AyuIslandsState
    private lateinit var overrideCheckpoints: EditorSchemeOverrides.SchemeCheckpoints
    private val keyCache = mutableMapOf<String, TextAttributesKey>()

    @BeforeTest
    fun setUp() {
        keyCache.clear()
        every { TextAttributesKey.find(any<String>()) } answers {
            val name = firstArg<String>()
            keyCache.getOrPut(name) { mockk(relaxed = true) { every { externalName } returns name } }
        }
        mockMirage = mockk(relaxed = true) { every { name } returns "Ayu Islands Mirage" }
        mockDark = mockk(relaxed = true) { every { name } returns "Ayu Islands Dark" }
        mockLight = mockk(relaxed = true) { every { name } returns "Ayu Islands Light" }
        every { mockMirage.defaultBackground } returns Color(0x1F, 0x24, 0x30)
        every { mockDark.defaultBackground } returns Color(0x0D, 0x10, 0x17)
        every { mockLight.defaultBackground } returns Color(0xFC, 0xFC, 0xFC)
        mockManager = mockk(relaxed = true)
        mockApp = mockk(relaxed = true)
        ayuState = AyuIslandsState()
        ayuSettings = mockk(relaxed = true)
        every { ayuSettings.state } answers {
            dev.ayuislands.studio.StudioFixtures
                .legacyView(ayuState)
                .settings
        }
        every { EditorColorsManager.getInstance() } returns mockManager
        every { mockManager.getScheme(AyuVariant.MIRAGE.editorSchemeResource) } returns mockMirage
        every { mockManager.getScheme(AyuVariant.DARK.editorSchemeResource) } returns mockDark
        every { mockManager.getScheme(AyuVariant.LIGHT.editorSchemeResource) } returns mockLight
        every { mockManager.globalScheme } returns mockMirage
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.getService(AyuIslandsSettings::class.java) } returns ayuSettings
        overrideCheckpoints = mockk()
        every { EditorSchemeOverrides.checkpoints } returns overrideCheckpoints
        loader = mockk(relaxed = true)
        val payload =
            mapOf(
                TextAttributesKey.find(PAYLOAD_KEY_NAME) to
                    TextAttributes().apply {
                        foregroundColor = Color(0xFF, 0xCC, 0x66)
                    },
            )
        for (variant in listOf("Mirage", "Dark", "Light")) {
            every { loader.loadOverlayForVariant(variant) } returns payload
            every { loader.loadBaselineForVariant(variant) } returns payload
            every { loader.fallbacksFor(variant) } returns emptyMap()
        }
        every { SyntaxOverlayLoader.getInstance() } returns loader
        every { RgbBlend.fallbackEditorBgFor(any()) } returns Color(0x1F, 0x24, 0x30)
        every { SyntaxIntensityApplicator.compute(any()) } returns payload
    }

    @AfterTest
    fun tearDown() = clearAllMocks()

    @Test
    fun `preparation selects the existing editable resource schemes for all variants without touching originals`() {
        val editable = editableResourceSchemes()
        every { mockManager.globalScheme } returns editable.getValue(AyuVariant.MIRAGE)
        val config = SyntaxPresetConfig(selectedPreset = "AMBIENT", customOverrides = emptyMap())

        val prepared =
            SyntaxIntensityService().prepare(
                config,
                isLicensed = true,
                ignorePluginSyntaxColorsEnabled = true,
            )

        assertEquals(editable.values.toList(), prepared.changes.map { change -> change.scheme })
        val payloadKey = TextAttributesKey.find(PAYLOAD_KEY_NAME)
        for ((variant, scheme) in editable) {
            val change = prepared.changes.single { it.scheme === scheme }
            assertEquals(scheme.defaultBackground, change.attributes.getValue(payloadKey).foregroundColor)
            verify(exactly = 1) { mockManager.getScheme(variant.editorSchemeResource) }
            verify(exactly = 0) { scheme.setAttributes(any(), any()) }
        }
        for (original in listOf(mockMirage, mockDark, mockLight)) {
            verify(exactly = 0) {
                original.setAttributes(any(), any())
                overrideCheckpoints.capture(original, EditorSchemeOwner.Syntax, any())
                EditorSchemeOverrides.restore(original, EditorSchemeOwner.Syntax)
            }
        }
        verify(exactly = 0) {
            mockManager.setGlobalScheme(any())
            EditorSchemeChange.publish()
        }
    }

    @Test
    fun `preparation computes all targets without writing or reading legacy choices`() {
        val service = SyntaxIntensityService()
        val active =
            mockk<EditorColorsScheme>(relaxed = true) {
                every { name } returns "_@user_Ayu Islands Mirage"
                every { defaultBackground } returns Color.BLACK
            }
        every { mockManager.globalScheme } returns active
        every { LicenseChecker.isLicensedOrGrace() } throws AssertionError("Preparation read live entitlement")
        every { ayuSettings.state } throws AssertionError("Preparation read legacy settings")
        val config =
            SyntaxPresetConfig(
                selectedPreset = "CUSTOM",
                customOverrides = mapOf("Kotlin" to mapOf("KEYWORD" to 70)),
                customStyles = mapOf("Kotlin" to mapOf("KEYWORD" to Font.BOLD)),
                customEmphasis = mapOf("Kotlin" to mapOf("KEYWORD" to Font.ITALIC)),
            )

        val prepared = service.prepare(config, isLicensed = true, ignorePluginSyntaxColorsEnabled = true)

        assertEquals(listOf(mockMirage, mockDark, mockLight, active), prepared.changes.map { change -> change.scheme })
        assertEquals(Font.BOLD or Font.ITALIC, prepared.replacementFonts.getValue("Kotlin").getValue("KEYWORD"))
        assertNull(service.replacementFontType("Kotlin", PrimitiveCategory.KEYWORD))
        verify(exactly = 4) { SyntaxIntensityApplicator.compute(match { it.preset == SyntaxPreset.CUSTOM }) }
        verify(exactly = 0) {
            mockMirage.setAttributes(any(), any())
            mockDark.setAttributes(any(), any())
            mockLight.setAttributes(any(), any())
            active.setAttributes(any(), any())
            EditorSchemeChange.publish()
            overrideCheckpoints.capture(any(), any(), any())
            PropertiesComponent.getInstance()
        }
    }

    @Test
    fun `preparation respects explicit free capability without changing requested config`() {
        val service = SyntaxIntensityService()
        val options = SyntaxReadabilityOptions(dimComments = true)
        val styles = mapOf("Kotlin" to mapOf("KEYWORD" to Font.BOLD))
        val config =
            SyntaxPresetConfig(
                selectedPreset = "CUSTOM",
                customOverrides = emptyMap(),
                customStyles = styles,
                readabilityOptions = options,
            )

        val prepared = service.prepare(config, isLicensed = false, ignorePluginSyntaxColorsEnabled = true)

        assertEquals(3, prepared.changes.size)
        assertTrue(prepared.replacementFonts.isEmpty())
        assertEquals("CUSTOM", config.selectedPreset)
        assertSame(styles, config.customStyles)
        assertSame(options, config.readabilityOptions)
        verify(exactly = 3) {
            SyntaxIntensityApplicator.compute(
                match {
                    it.preset == SyntaxPreset.AMBIENT && it.readabilityOptions == SyntaxReadabilityOptions.DEFAULT
                },
            )
        }
        verify(exactly = 0) { LicenseChecker.isLicensedOrGrace() }
    }

    @Test
    fun `preparation takes ignore plugin choice from candidate and skips foreign active scheme`() {
        val service = SyntaxIntensityService()
        val foreign = mockk<EditorColorsScheme>(relaxed = true) { every { name } returns "Other theme" }
        every { mockManager.globalScheme } returns foreign
        ayuState.ignorePluginSyntaxColorsEnabled = true

        val prepared =
            service.prepare(
                SyntaxPresetConfig(selectedPreset = "AMBIENT", customOverrides = emptyMap()),
                isLicensed = true,
                ignorePluginSyntaxColorsEnabled = false,
            )

        assertEquals(listOf(mockMirage, mockDark, mockLight), prepared.changes.map { change -> change.scheme })
        for ((scheme, _, attributes) in prepared.changes) {
            val stock = if (scheme === mockLight) ignorePluginDefaultStock else ignorePluginDarculaStock
            for ((name, expected) in stock) {
                assertTrue(attributes.getValue(TextAttributesKey.find(name)).matches(expected), name)
            }
        }
        assertTrue(ayuState.ignorePluginSyntaxColorsEnabled)
        verify(exactly = 0) { foreign.setAttributes(any(), any()) }
    }

    private fun editableResourceSchemes(): Map<AyuVariant, EditorColorsScheme> {
        val originals = listOf(mockMirage, mockDark, mockLight)
        val payloadKey = TextAttributesKey.find(PAYLOAD_KEY_NAME)
        val editable =
            AyuVariant.entries.zip(originals).associate { (variant, original) ->
                val scheme =
                    mockk<EditorColorsScheme>(relaxed = true) {
                        every { name } returns "_@user_${original.name}"
                        every { defaultBackground } returns original.defaultBackground
                    }
                val canonicalName = original.name
                every { mockManager.getScheme(canonicalName) } returns original
                every { mockManager.getScheme(variant.editorSchemeResource) } returns scheme
                val variantTag = variant.name.lowercase().replaceFirstChar(Char::uppercaseChar)
                val payload = mapOf(payloadKey to TextAttributes().apply { foregroundColor = scheme.defaultBackground })
                every { loader.loadBaselineForVariant(variantTag) } returns payload
                every { loader.loadOverlayForVariant(variantTag) } returns emptyMap()
                variant to scheme
            }
        every { SyntaxIntensityApplicator.compute(any()) } answers {
            firstArg<SyntaxIntensityApplicator.Request>().baseline
        }
        return editable
    }

    @Test
    fun `dark unresolved background uses the canonical fallback while light keeps white`() {
        every { mockMirage.defaultBackground } returns Color.WHITE
        every { mockLight.defaultBackground } returns Color.WHITE
        JBColor.setDark(true)
        try {
            SyntaxIntensityService().prepare(config(), isLicensed = true, ignorePluginSyntaxColorsEnabled = true)
            verify(exactly = 1) { RgbBlend.fallbackEditorBgFor("Mirage") }
            verify(exactly = 0) { RgbBlend.fallbackEditorBgFor("Light") }
        } finally {
            JBColor.setDark(false)
        }
    }

    @Test
    fun `candidate forwards custom styles emphasis readability and scheme fallbacks unchanged`() {
        val styles = mapOf("Java" to mapOf("KEYWORD" to (Font.BOLD or Font.ITALIC)))
        val emphasis = mapOf("Kotlin" to mapOf("FUNCTION_DECLARATION" to Font.BOLD))
        val fallbacks = mapOf("SWIFT.BRACKETS" to "DEFAULT_BRACES")
        val readability =
            SyntaxReadabilityOptions(
                dimComments = true,
                softenDocumentation = true,
                quietOperators = true,
                emphasizeDeclarations = true,
            )
        val requested =
            SyntaxPresetConfig(
                "CUSTOM",
                mapOf("Java" to mapOf("KEYWORD" to 75)),
                customStyles = styles,
                customEmphasis = emphasis,
                readabilityOptions = readability,
            )
        every { loader.fallbacksFor("Mirage") } returns fallbacks

        SyntaxIntensityService().prepare(requested, isLicensed = true, ignorePluginSyntaxColorsEnabled = true)

        verify(exactly = 3) {
            SyntaxIntensityApplicator.compute(
                match {
                    it.preset == SyntaxPreset.CUSTOM &&
                        it.customStyles === styles &&
                        it.customEmphasis === emphasis &&
                        it.readabilityOptions == readability
                },
            )
        }
        verify(exactly = 1) {
            SyntaxIntensityApplicator.compute(match { it.variantName == "Mirage" && it.fallbacks === fallbacks })
        }
    }

    @Test
    fun `disabled ignore colors include stock values on the distinct active Ayu scheme`() {
        val active =
            mockk<EditorColorsScheme>(relaxed = true) {
                every { name } returns "_@user_Ayu Islands Mirage (Personal)"
                every { defaultBackground } returns Color.BLACK
            }
        every { mockManager.globalScheme } returns active
        val prepared =
            SyntaxIntensityService().prepare(
                config(),
                isLicensed = true,
                ignorePluginSyntaxColorsEnabled = false,
            )
        val change = prepared.changes.single { it.scheme === active }
        for ((name, expected) in ignorePluginDarculaStock) {
            assertTrue(change.attributes.getValue(TextAttributesKey.find(name)).matches(expected), name)
        }
        verify(exactly = 0) { active.setAttributes(any(), any()) }
    }

    @Test
    fun `enabled ignore colors do not manufacture stock override entries`() {
        val prepared =
            SyntaxIntensityService().prepare(
                config(),
                isLicensed = true,
                ignorePluginSyntaxColorsEnabled = true,
            )
        assertTrue(
            prepared.changes.all { change ->
                change.attributes.keys.none { it.externalName.startsWith("IGNORE.") }
            },
        )
    }

    @Test
    fun `missing editable targets are skipped without mutating foreign active selection`() {
        val foreign = mockk<EditorColorsScheme>(relaxed = true) { every { name } returns "Solarized (Light)" }
        every { mockManager.globalScheme } returns foreign
        every { mockManager.getScheme(AyuVariant.DARK.editorSchemeResource) } returns null
        val service = SyntaxIntensityService()
        repeat(2) {
            val prepared = service.prepare(config(), isLicensed = true, ignorePluginSyntaxColorsEnabled = true)
            assertEquals(listOf(mockMirage, mockLight), prepared.changes.map { change -> change.scheme })
        }
        verify(exactly = 0) {
            foreign.setAttributes(any(), any())
            mockManager.setGlobalScheme(any())
        }
    }

    @Test
    fun `semantic lookup follows the exact captured write and restoration owner`() {
        val service = SyntaxIntensityService()
        val original = service.fontState.current
        val styles = mapOf("Swift" to mapOf(PrimitiveCategory.OPERATOR.name to Font.BOLD))
        val write = service.fontState.prepare(styles)
        assertNull(service.replacementFontType("Swift", PrimitiveCategory.OPERATOR))
        assertTrue(write.apply())
        assertEquals(Font.BOLD, service.replacementFontType("Swift", PrimitiveCategory.OPERATOR))
        assertTrue(write.restore())
        assertSame(original, service.fontState.current)
        assertNull(service.replacementFontType("Swift", PrimitiveCategory.OPERATOR))
    }

    @Test
    fun `free capability remains effective across repeated custom requests`() {
        val service = SyntaxIntensityService()
        repeat(2) {
            service.prepare(
                config().copy(selectedPreset = "CUSTOM"),
                isLicensed = false,
                ignorePluginSyntaxColorsEnabled = true,
            )
        }
        verify(exactly = 6) { SyntaxIntensityApplicator.compute(match { it.preset == SyntaxPreset.AMBIENT }) }
        verify(exactly = 0) { SyntaxIntensityApplicator.compute(match { it.preset == SyntaxPreset.CUSTOM }) }
    }

    private fun config(): SyntaxPresetConfig = SyntaxPresetConfig("AMBIENT", emptyMap())

    private fun TextAttributes.matches(expectedStyle: ExpectedIgnorePluginStyle): Boolean =
        foregroundColor?.rgb == Color(expectedStyle.foregroundRgb).rgb &&
            backgroundColor?.rgb == expectedStyle.backgroundRgb?.let(::Color)?.rgb &&
            fontType == expectedStyle.fontType

    private data class ExpectedIgnorePluginStyle(
        val foregroundRgb: Int,
        val backgroundRgb: Int? = null,
        val fontType: Int = Font.PLAIN,
    )

    private val ignorePluginDarculaStock =
        mapOf(
            "IGNORE.COMMENT" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080),
            "IGNORE.SECTION" to ExpectedIgnorePluginStyle(foregroundRgb = 0x8C8C8C, backgroundRgb = 0x3A3A3A),
            "IGNORE.HEADER" to
                ExpectedIgnorePluginStyle(
                    foregroundRgb = 0x8C8C8C,
                    backgroundRgb = 0x3A3A3A,
                    fontType = Font.BOLD,
                ),
            "IGNORE.NEGATION" to ExpectedIgnorePluginStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
            "IGNORE.BRACKET" to ExpectedIgnorePluginStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
            "IGNORE.SLASH" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080),
            "IGNORE.SYNTAX" to
                ExpectedIgnorePluginStyle(
                    foregroundRgb = 0xACACAC,
                    backgroundRgb = 0x4A4A4A,
                    fontType = Font.BOLD,
                ),
            "IGNORE.VALUE" to ExpectedIgnorePluginStyle(foregroundRgb = 0x629755),
            "IGNORE.UNUSED_ENTRY" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080, fontType = Font.ITALIC),
        )

    private val ignorePluginDefaultStock =
        mapOf(
            "IGNORE.COMMENT" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080),
            "IGNORE.SECTION" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080, backgroundRgb = 0xECFAEB),
            "IGNORE.HEADER" to
                ExpectedIgnorePluginStyle(
                    foregroundRgb = 0x808080,
                    backgroundRgb = 0xECFAEB,
                    fontType = Font.BOLD,
                ),
            "IGNORE.NEGATION" to ExpectedIgnorePluginStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
            "IGNORE.BRACKET" to ExpectedIgnorePluginStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
            "IGNORE.SLASH" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080),
            "IGNORE.SYNTAX" to
                ExpectedIgnorePluginStyle(
                    foregroundRgb = 0xACACAC,
                    backgroundRgb = 0x4A4A4A,
                    fontType = Font.BOLD,
                ),
            "IGNORE.VALUE" to ExpectedIgnorePluginStyle(foregroundRgb = 0x5C9F30),
            "IGNORE.UNUSED_ENTRY" to ExpectedIgnorePluginStyle(foregroundRgb = 0x808080, fontType = Font.ITALIC),
        )
}
